package com.oai.titanarum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class ZXingReaderScannerTest {

    @Test
    void parsesJsonLines() {
        String out = "{\"Format\":\"QRCode\",\"Text\":\"https://evil.example/pay\","
                + "\"Position\":\"40x40 290x40 290x290 40x290\",\"ECLevel\":\"M\","
                + "\"IsMirrored\":false}\n";
        List<ZXingReaderScanner.QrResult> r = ZXingReaderScanner.parseJsonLines(out);
        assertEquals(1, r.size());
        assertEquals("https://evil.example/pay", r.get(0).text());
        assertEquals("QRCode", r.get(0).format());
        assertEquals("40x40 290x40 290x290 40x290", r.get(0).position());
    }

    @Test
    void skipsBlankAndNonJsonLines() {
        assertTrue(ZXingReaderScanner.parseJsonLines("").isEmpty());
        assertTrue(ZXingReaderScanner.parseJsonLines("not json\n\n").isEmpty());
    }

    @Test
    void requiresFormatField() {
        assertTrue(ZXingReaderScanner.parseJsonLines("{\"Text\":\"x\"}").isEmpty());
    }

    @Test
    void readCapped_truncatesAtExactlyOneMiB_andSignalsKill() throws IOException {
        // Child emits well over 1 MiB of output (I1: check-before-write must never overshoot).
        final int oneMiB = 1 << 20;
        final int over = oneMiB + 50_000;
        InputStream endless = new InputStream() {
            int remaining = over;
            @Override public int read() {
                if (remaining <= 0) return -1;
                remaining--;
                return 'A';
            }
            @Override public int read(byte[] b, int off, int len) {
                if (remaining <= 0) return -1;
                int n = Math.min(len, remaining);
                Arrays.fill(b, off, off + n, (byte) 'A');
                remaining -= n;
                return n;
            }
        };
        AtomicBoolean killed = new AtomicBoolean(false);
        byte[] out = ZXingReaderScanner.readCapped(endless, () -> killed.set(true));
        assertEquals(oneMiB, out.length, "stdout must be truncated at exactly the 1 MiB cap");
        assertTrue(killed.get(), "child must be signalled for a forced kill once the cap is exceeded");
    }

    @Test
    void readCapped_underCap_doesNotSignalKill() throws IOException {
        byte[] small = "hello world".getBytes(StandardCharsets.UTF_8);
        AtomicBoolean killed = new AtomicBoolean(false);
        byte[] out = ZXingReaderScanner.readCapped(new ByteArrayInputStream(small), () -> killed.set(true));
        assertArrayEquals(small, out);
        assertFalse(killed.get(), "child must not be killed when output stays under the cap");
    }

    /**
     * I2 (warm-plan.md, carried in from the I1 review): before the fix, {@code scan()} called
     * {@code readCapped(stdout)} — which blocks on {@code in.read()} with NO timeout — and only
     * reached {@code proc.waitFor(timeoutMillis,...)} AFTER {@code readCapped} returned. A child
     * that hangs mid-decode without hitting the 1 MiB cap or closing stdout therefore blocked the
     * calling thread indefinitely, defeating the QR wall-clock budget entirely. This fake
     * "ZXingReader" execs {@code sleep} (replacing its own process image, so a single SIGKILL to
     * the ProcessBuilder-spawned PID kills it with nothing left running) well beyond the
     * configured timeout, and writes nothing to stdout/stderr.
     */
    @Test
    void scan_boundsWallClockWhenChildHangsWithoutWriting_andKillsChild(@TempDir Path tmp) throws Exception {
        Path stub = tmp.resolve("hang-zxing.sh");
        Files.writeString(stub, "#!/bin/sh\nexec sleep 30\n");
        assertTrue(stub.toFile().setExecutable(true), "test stub must be made executable");

        Path png = tmp.resolve("dummy.png");
        Files.write(png, new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        long timeoutMs = 300L;
        ZXingReaderScanner scanner = new ZXingReaderScanner(stub.toString(), "QRCode", timeoutMs);

        long startNanos = System.nanoTime();
        List<ZXingReaderScanner.QrResult> result = scanner.scan(png);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertTrue(result.isEmpty(), "a hung child that wrote nothing must yield an empty result");
        assertTrue(elapsedMs < 5_000,
                "scan() must return within roughly the configured timeout (" + timeoutMs
                        + "ms) instead of blocking on the hung child; took " + elapsedMs + "ms");

        // Verify the child was actually killed (destroyForcibly), not merely abandoned: poll
        // briefly for any live process whose command line references our stub script.
        boolean stillRunning = pollUntil(2_000L, () -> ProcessHandle.allProcesses()
                .noneMatch(ph -> ph.info().commandLine().map(cl -> cl.contains(stub.toString())).orElse(false)));
        assertTrue(stillRunning, "the hung child process must be killed, not left running");
    }

    private static boolean pollUntil(long timeoutMs, java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(25L);
        }
        return condition.getAsBoolean();
    }

    @org.junit.jupiter.api.Test
    void decodesRealQr_whenBinaryPresent() throws Exception {
        // Self-skip unless a -json-capable ZXingReader is on PATH.
        String bin = System.getenv().getOrDefault("TITANARUM_ZXING_BIN", "ZXingReader");
        java.nio.file.Path png = java.nio.file.Path.of(
                getClass().getResource("/qr-sample.png").toURI());
        List<ZXingReaderScanner.QrResult> r;
        try {
            r = new ZXingReaderScanner().scan(png);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.abort("ZXingReader unavailable: " + e);
            return;
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(
                !r.isEmpty(), "ZXingReader (" + bin + ") produced no output (missing/old binary)");
        assertEquals("https://titanarum.test/qr", r.get(0).text());
    }
}
