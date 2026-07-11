package com.oai.titanarum;

import org.junit.jupiter.api.Test;

import java.util.List;

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
