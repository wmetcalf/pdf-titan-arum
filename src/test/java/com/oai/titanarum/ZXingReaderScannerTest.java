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
        // Answer the -json capability probe instantly (a real ZXingReader -help is immediate);
        // hang only on the actual scan, which is what this test bounds.
        Files.writeString(stub, "#!/bin/sh\ncase \"$1\" in -help) echo usage; exit 0;; esac\nexec sleep 30\n");
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

    @Test
    void scan_warnsOnceWhenBinaryUnavailable_thenStaysSilent(@TempDir Path tmp) throws Exception {
        Path png = tmp.resolve("dummy.png");
        Files.write(png, new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        String missing = tmp.resolve("no-such-zxing-binary").toString();
        ZXingReaderScanner scanner = new ZXingReaderScanner(missing, "QRCode", 1_000L);

        // Swapping the process-global System.err is safe here because surefire runs tests
        // sequentially in a single fork (no <parallel>/@Execution configured); if JUnit
        // parallelism is ever enabled this capture would need a non-global seam.
        java.io.PrintStream origErr = System.err;
        java.io.ByteArrayOutputStream cap = new java.io.ByteArrayOutputStream();
        System.setErr(new java.io.PrintStream(cap, true, StandardCharsets.UTF_8));
        try {
            assertTrue(scanner.scan(png).isEmpty(), "a missing binary must yield an empty result");
            assertTrue(scanner.scan(png).isEmpty(), "the second scan must also yield an empty result");
        } finally {
            System.setErr(origErr);
        }

        String err = cap.toString(StandardCharsets.UTF_8);
        int occurrences = err.split("QR/barcode scanning skipped", -1).length - 1;
        assertEquals(1, occurrences,
                "warning must be emitted exactly once across two scans (warn-once, no per-image spam); saw:\n" + err);
        assertTrue(err.contains(missing), "warning must name the missing executable");
        // Pin the REAL flag (--skip-qr), not the plausible-but-wrong --skip-qr-scan.
        assertTrue(err.contains("--skip-qr to silence"), "warning must reference the real --skip-qr flag");
        assertFalse(err.contains("--skip-qr-scan"), "must not reference the non-existent --skip-qr-scan flag");
    }

    @Test
    void parsesPlaintext_bytesPreferred_fullPayload() {
        // Real 2.2.1 block shape; Bytes is the authoritative single-line payload (Text agrees here).
        String out = "Text:       \"https://titanarum.test/qr\"\n"
                + "Bytes:      68 74 74 70 73 3A 2F 2F 74 69 74 61 6E 61 72 75 6D 2E 74 65 73 74 2F 71 72\n"
                + "Format:     QRCode\n"
                + "Position:   40x40 290x40 290x290 40x290 \n"
                + "EC Level:   M\n";
        List<ZXingReaderScanner.QrResult> r = ZXingReaderScanner.parsePlaintextOutput(out);
        assertEquals(1, r.size());
        assertEquals("https://titanarum.test/qr", r.get(0).text(), "payload decoded from Bytes");
        assertEquals("QRCode", r.get(0).format());
        assertEquals("40x40 290x40 290x290 40x290", r.get(0).position(), "trailing space trimmed");
    }

    @Test
    void parsesPlaintext_textFallback_whenBytesAbsent() {
        String out = "Text: \"https://x/qr\"\nFormat: QRCode\n";   // no Bytes field
        List<ZXingReaderScanner.QrResult> r = ZXingReaderScanner.parsePlaintextOutput(out);
        assertEquals(1, r.size());
        assertEquals("https://x/qr", r.get(0).text(), "quote-stripped Text when Bytes is absent");
    }

    @Test
    void parsesPlaintext_multilinePayload_viaBytes_notTruncated() {
        // 2.2.1 emits a newline payload as multi-line Text (would truncate the URL); Bytes carries
        // the full value (0A = newline). We must decode the FULL payload, not the truncated Text.
        String out = "Text:       \"https://evil.example/a\n"
                + "HIDDEN-SECOND-LINE\"\n"
                + "Bytes:      68 74 74 70 73 3A 2F 2F 65 76 69 6C 2E 65 78 61 6D 70 6C 65 2F 61 0A "
                + "48 49 44 44 45 4E 2D 53 45 43 4F 4E 44 2D 4C 49 4E 45\n"
                + "Format:     QRCode\n";
        List<ZXingReaderScanner.QrResult> r = ZXingReaderScanner.parsePlaintextOutput(out);
        assertEquals(1, r.size());
        assertEquals("https://evil.example/a\nHIDDEN-SECOND-LINE", r.get(0).text(),
                "full payload incl. the hidden second line — not truncated at the newline");
    }

    @Test
    void parsesPlaintext_noBarcodeFoundInPayload_notDropped_and_genuineEmpty() {
        // A QR whose payload literally is "No barcode found" must NOT blank the result.
        String out = "Text: \"No barcode found\"\n"
                + "Bytes: 4E 6F 20 62 61 72 63 6F 64 65 20 66 6F 75 6E 64\n"   // "No barcode found"
                + "Format: QRCode\n";
        List<ZXingReaderScanner.QrResult> r = ZXingReaderScanner.parsePlaintextOutput(out);
        assertEquals(1, r.size());
        assertEquals("No barcode found", r.get(0).text());
        // Genuine no-barcode output (no Format line) is still empty.
        assertTrue(ZXingReaderScanner.parsePlaintextOutput("No barcode found\n").isEmpty());
    }

    @Test
    void parsesPlaintext_multipleBlocks_dropsBlockWithoutFormat() {
        String two = "Text: \"a\"\nFormat: QRCode\n\nText: \"b\"\nFormat: QRCode\n";
        assertEquals(2, ZXingReaderScanner.parsePlaintextOutput(two).size(), "blank line separates blocks");
        assertTrue(ZXingReaderScanner.parsePlaintextOutput("Text: \"x\"\nBytes: 78\n").isEmpty(),
                "a block without Format is dropped, like parseJsonLines");
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
