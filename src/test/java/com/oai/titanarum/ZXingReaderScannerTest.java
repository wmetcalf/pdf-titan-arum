package com.oai.titanarum;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
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
