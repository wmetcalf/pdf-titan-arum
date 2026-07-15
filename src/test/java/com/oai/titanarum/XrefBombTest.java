package com.oai.titanarum;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A FlateDecode cross-reference stream is attacker-controlled and a decompression-bomb vector.
 * pdfHashAddXrefEntries must bound the inflate (copyBounded / MAX_XREF_STREAM_BYTES) so a small
 * PDF that expands to gigabytes is skipped, not expanded into an OutOfMemoryError that escapes
 * the enclosing Exception-only catch and crashes the analysis thread.
 */
class XrefBombTest {

    @Test
    void xrefDecompressionBombIsBounded_doesNotOOM() throws Exception {
        // Deflate ~2 GiB of zeros -> a few MiB compressed (streamed, so the test itself stays small).
        // 2 GiB is well past both any surefire heap and the 64 MiB cap, so WITHOUT the bound this
        // OOMs; WITH it, copyBounded trips at 64 MiB and the stream is cleanly skipped.
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(deflated)) {
            byte[] chunk = new byte[1 << 20]; // 1 MiB of zeros
            for (int i = 0; i < 2048; i++) d.write(chunk); // 2 GiB decompressed
        }
        byte[] bomb = deflated.toByteArray();

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        pdf.write(("1 0 obj\n<< /Type /XRef /W [1 2 1] /Size 1 /Filter /FlateDecode >>\nstream\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        pdf.write(bomb);
        pdf.write(("\nendstream\n").getBytes(StandardCharsets.ISO_8859_1));
        byte[] pdfBytes = pdf.toByteArray();

        Method m = PdfTitanArumApp.class.getDeclaredMethod(
                "pdfHashAddXrefEntries", byte[].class, long.class, Map.class);
        m.setAccessible(true);
        Map<Integer, Long> map = new HashMap<>();

        // Must return normally (no OutOfMemoryError): the cap trips the enclosing catch -> skip.
        m.invoke(null, pdfBytes, 0L, map);

        assertTrue(map.isEmpty(), "a bombed xref stream must be skipped at the cap, not expanded");
    }
}
