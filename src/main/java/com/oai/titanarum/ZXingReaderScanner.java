package com.oai.titanarum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Decodes barcodes by shelling out to the zxing-cpp {@code ZXingReader -json} binary. */
final class ZXingReaderScanner {

    /** One decoded symbol (or an error entry). */
    record QrResult(String text, String format, String position, String error) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_STDOUT = 1 << 20; // 1 MiB

    private final String executable;
    private final String formats;   // comma-separated; "" = all
    private final long timeoutMillis;
    /** Warn at most once per scanner (i.e. per run) when the ZXingReader binary can't be launched. */
    private final AtomicBoolean warnedUnavailable = new AtomicBoolean(false);

    ZXingReaderScanner() {
        String bin = System.getenv("TITANARUM_ZXING_BIN");
        this.executable = (bin == null || bin.isBlank()) ? "ZXingReader" : bin;
        String fmt = System.getenv("TITANARUM_ZXING_FORMATS");
        this.formats = (fmt == null) ? "QRCode" : fmt;   // preserves current QR-only behavior
        // I1: 60s per-scan timeout is a fork-pressure/wall-clock DoS amplifier under a
        // QR-flood PDF, more so once a warm JVM reuses the process. Drop the default and
        // make it operator-tunable.
        this.timeoutMillis = envLong("TITANARUM_ZXING_TIMEOUT_MS", 5_000L);
    }

    /**
     * Package-private constructor for tests: bypasses the env lookups so a fake executable and
     * a tiny timeout can be injected directly, without needing to mutate process environment
     * variables of the running JVM (there is no supported API for that).
     */
    ZXingReaderScanner(String executable, String formats, long timeoutMillis) {
        this.executable = executable;
        this.formats = formats;
        this.timeoutMillis = timeoutMillis;
    }

    private static long envLong(String name, long def) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) return def;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    List<String> buildCommand(Path image) {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.add("-json");
        if (formats != null && !formats.isBlank()) {
            cmd.add("-formats");
            cmd.add(formats);
        }
        cmd.add(image.toAbsolutePath().toString());
        return cmd;
    }

    /**
     * I2 (warm-plan.md, carried in from the I1 review): previously this method called
     * {@code readCapped(stdout)} — which blocks on {@code InputStream.read()} with NO
     * timeout — and only reached {@code proc.waitFor(timeoutMillis,...)} AFTER
     * {@code readCapped} returned. A child that hangs mid-decode (without hitting the 1 MiB
     * cap or closing stdout) therefore blocked the calling thread indefinitely, defeating the
     * QR wall-clock budget entirely (worse in warm mode, where that thread is the reused JVM's
     * only worker thread). Fix: drain stdout AND stderr on daemon threads (a full stderr pipe
     * can stall a child too) and bound the WHOLE scan by {@code proc.waitFor(timeoutMillis)} on
     * the calling thread, independent of whether the drain threads have finished reading. On
     * timeout, {@code destroyForcibly()} the child; that closes the pipes, so the drain threads
     * unblock immediately and are joined (with their own short bound, never trusted to be
     * truly unbounded) so any bytes already captured are still returned.
     */
    List<QrResult> scan(Path image) {
        ProcessBuilder pb = new ProcessBuilder(buildCommand(image));
        pb.redirectErrorStream(false);
        Process proc = null;
        Thread stdoutThread = null;
        Thread stderrThread = null;
        try {
            proc = pb.start();
            final Process forked = proc;

            StreamDrain stdoutDrain = new StreamDrain(proc.getInputStream(), forked::destroyForcibly);
            StreamDrain stderrDrain = new StreamDrain(proc.getErrorStream(), forked::destroyForcibly);
            stdoutThread = newDaemonThread(stdoutDrain, "zxing-stdout-drain");
            stderrThread = newDaemonThread(stderrDrain, "zxing-stderr-drain");
            stdoutThread.start();
            stderrThread.start();

            boolean done = proc.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!done) {
                proc.destroyForcibly();
            }
            joinQuietly(stdoutThread, 2_000L);
            joinQuietly(stderrThread, 2_000L);
            return parseJsonLines(new String(stdoutDrain.result(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            // pb.start() failed — almost always because the ZXingReader binary is absent or
            // not executable. Warn (once) so this doesn't silently swallow QR findings.
            warnUnavailableOnce(e);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            if (proc != null && proc.isAlive()) proc.destroyForcibly();
        }
    }

    /**
     * Standalone-mode safety net. Barcode decoding shells out to the external zxing-cpp
     * {@code ZXingReader} binary, which the blastbox deploy images bundle (and point at via
     * {@code TITANARUM_ZXING_BIN}) but a plain {@code java -jar} user may not have installed.
     * A missing binary makes {@link #scan} return no results; without this warning that loss is
     * SILENT and QR findings just vanish. Emit one clear message per run (not per image — a
     * QR-heavy PDF would otherwise spam it) naming the executable and how to fix it.
     */
    private void warnUnavailableOnce(IOException e) {
        if (warnedUnavailable.compareAndSet(false, true)) {
            // Phrased conditionally ("if it is not installed") so it is accurate whether the binary
            // is genuinely absent (ENOENT/EACCES) or the launch hit a transient limit (fork EAGAIN,
            // EMFILE) with the binary present — without fragile, locale-dependent errno parsing.
            System.err.println("WARNING: QR/barcode scanning skipped — could not launch '"
                    + executable + "' (" + e.getMessage() + "). If it is not installed, install the "
                    + "zxing-cpp ZXingReader binary or set TITANARUM_ZXING_BIN to its path. "
                    + "Pass --skip-qr to silence.");
        }
    }

    private static Thread newDaemonThread(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    private static void joinQuietly(Thread t, long millis) {
        try {
            t.join(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Drains an {@link InputStream} on a background thread, capped at {@link #MAX_STDOUT} bytes. */
    private static final class StreamDrain implements Runnable {
        private final InputStream in;
        private final Runnable onCapExceeded;
        // Fix 4 (I2 review): written to incrementally by readCapped(..., buf) as bytes arrive,
        // so a mid-read IOException (destroyForcibly() closing the pipe, a hung filesystem,
        // etc.) still leaves whatever was captured so far visible via result() -- the previous
        // version only assigned `result` from readCapped's return value, so any exception
        // thrown out of readCapped silently discarded the partial buffer it had accumulated.
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private volatile byte[] result = new byte[0];

        StreamDrain(InputStream in, Runnable onCapExceeded) {
            this.in = in;
            this.onCapExceeded = onCapExceeded;
        }

        @Override
        public void run() {
            try {
                readCapped(in, onCapExceeded, buf);
            } catch (IOException e) {
                // Stream broke mid-read (e.g. destroyForcibly() closed the pipe after a
                // timeout) -- keep whatever was captured before that happened.
            } finally {
                result = buf.toByteArray();
            }
        }

        byte[] result() { return result; }
    }

    /** Parse ZXingReader {@code -json} output: one JSON object per line. */
    static List<QrResult> parseJsonLines(String stdout) {
        List<QrResult> results = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) return results;
        for (String line : stdout.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                JsonNode n = JSON.readTree(t);
                String format = n.path("Format").asText(null);
                if (format == null || format.isBlank()) continue;   // require Format
                results.add(new QrResult(
                        n.path("Text").asText(null),
                        format,
                        n.path("Position").asText(null),
                        n.path("Error").asText(null)));
            } catch (IOException ignore) {
                // non-JSON line (e.g. a version banner) -> skip
            }
        }
        return results;
    }

    /**
     * Reads {@code in} into memory, hard-capped at {@link #MAX_STDOUT} bytes.
     *
     * <p>I1 fix: the cap is checked <em>before</em> each write so the returned buffer never
     * overshoots {@code MAX_STDOUT} (the previous check-after-write let a single 8 KiB chunk
     * push the result past the cap). If the cap is reached — meaning the child produced at
     * least that much output and may still be writing — {@code onCapExceeded} is invoked so
     * the caller can forcibly kill the child rather than let it run to its full timeout.
     *
     * <p>Package-private (not private) so it can be unit-tested directly with a fake
     * {@link InputStream} and a fake kill callback, without needing a real subprocess.
     */
    static byte[] readCapped(InputStream in, Runnable onCapExceeded) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        readCapped(in, onCapExceeded, buf);
        return buf.toByteArray();
    }

    /**
     * Same as {@link #readCapped(InputStream, Runnable)} but writes into a caller-supplied
     * buffer instead of a local one, so the caller (see {@link StreamDrain}) can still read
     * out whatever was captured so far if {@code in.read()} throws partway through -- the
     * buffer isn't scoped to this method's stack, so it survives the exception unwinding.
     */
    private static void readCapped(InputStream in, Runnable onCapExceeded, ByteArrayOutputStream buf) throws IOException {
        byte[] chunk = new byte[8192];
        int n;
        while (buf.size() < MAX_STDOUT && (n = in.read(chunk)) != -1) {
            int room = MAX_STDOUT - buf.size();
            int toWrite = Math.min(room, n);
            buf.write(chunk, 0, toWrite);
        }
        if (buf.size() >= MAX_STDOUT && onCapExceeded != null) {
            onCapExceeded.run();
        }
    }
}
