package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression coverage for the JPEG2000/JBIG2 -> PDFBox-fallback contract.
 *
 * saveOriginalXObjectBytes must save the original encoded stream for JPEG (jpg), JPEG2000 (jpx),
 * and JBIG2 (jb2). It previously gated on getSuffix()=="jp2", which PDFBox 3.0.6 NEVER returns
 * (it returns "jpx"), so JP2/JBIG2 originals were dropped and the hasher fell through to the PNG
 * snapshot instead of the intended PDFBox-decode fallback. Two tests here:
 *   - {@link #jpeg2000OriginalBytesAreSaved()} pins the fixed gate directly: reverting it to "jp2"
 *     makes this fail (the whole point of the fix).
 *   - {@link #jpeg2000FallsBackToPdfboxDecode()} pins the two upstream facts the fallback relies on
 *     (PDFBox suffix "jpx"; rosetta throws on JP2) plus the fallback-identity/hash contract.
 *
 * Only JPEG2000 is covered by a fixture; JBIG2 ("jb2") rides the identical generic mechanism
 * (rosetta throws UNSUPPORTED_FORMAT -> decodeForHash catch-all fallback) but the corpus has no
 * embedded-JBIG2 sample. FOLLOW-UP: add a JBIG2 fixture and an analogous assertion.
 */
class Jp2FallbackTest {

    private static final File JP2_PDF =
        new File("deploy/docker/appcds-warmup-corpus/10-image-jpeg2000.pdf");

    private static BufferedImage callDecodeForHash(byte[] bytes, BufferedImage fallback) throws Exception {
        Method m = PdfTitanArumApp.class.getDeclaredMethod("decodeForHash", byte[].class, BufferedImage.class);
        m.setAccessible(true);
        return (BufferedImage) m.invoke(null, bytes, fallback);
    }

    private static String callComputePhash(BufferedImage img) throws Exception {
        Method m = PdfTitanArumApp.class.getDeclaredMethod("computePhash", BufferedImage.class);
        m.setAccessible(true);
        return (String) m.invoke(null, img);
    }

    /** Reflectively invoke the instance method saveOriginalXObjectBytes with outputDir wired to a temp dir. */
    private static String callSaveOriginalXObjectBytes(PDImage img, Path dir, String baseName) throws Exception {
        PdfTitanArumApp app = new PdfTitanArumApp();
        Field outField = PdfTitanArumApp.class.getDeclaredField("outputDir");
        outField.setAccessible(true);
        outField.set(app, dir);
        Method m = PdfTitanArumApp.class.getDeclaredMethod(
            "saveOriginalXObjectBytes", PDImage.class, Path.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(app, img, dir, baseName);
    }

    private static byte[] rawStreamBytes(PDImageXObject img) throws Exception {
        COSBase cos = img.getCOSObject();
        assertTrue(cos instanceof COSStream, "image XObject backed by a COSStream");
        try (InputStream is = ((COSStream) cos).createInputStream();
             ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            is.transferTo(bo);
            return bo.toByteArray();
        }
    }

    private static PDImageXObject firstImageXObject(PDDocument doc) throws Exception {
        for (int p = 0; p < doc.getNumberOfPages(); p++) {
            PDPage page = doc.getPage(p);
            PDResources res = page.getResources();
            if (res == null) continue;
            for (COSName name : res.getXObjectNames()) {
                PDXObject xo = res.getXObject(name);
                if (xo instanceof PDImageXObject img) return img;
            }
        }
        return null;
    }

    @Test
    void jpeg2000OriginalBytesAreSaved(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(JP2_PDF.exists(), "jpeg2000 fixture missing, skipping");
        try (PDDocument doc = Loader.loadPDF(JP2_PDF)) {
            PDImageXObject img = firstImageXObject(doc);
            assertNotNull(img, "expected an embedded image XObject in the fixture");

            // Directly exercises the fixed suffix gate: a JPEG2000 image (suffix "jpx") must have its
            // original bytes saved as a ".jpx" file. Reverting the gate to "jp2" makes this fail.
            String rel = callSaveOriginalXObjectBytes(img, tempDir, "jp2-original");
            assertNotNull(rel, "JPEG2000 original bytes must be saved (gate must accept 'jpx')");
            assertTrue(rel.endsWith(".jpx"), "original saved with .jpx extension, got: " + rel);
            assertTrue(Files.exists(tempDir.resolve(rel)), "the .jpx original file exists on disk: " + rel);
            assertArrayEquals(rawStreamBytes(img), Files.readAllBytes(tempDir.resolve(rel)),
                "saved file must be the raw JP2 codestream, byte-for-byte");
        }
    }

    @Test
    void jpeg2000FallsBackToPdfboxDecode() throws Exception {
        Assumptions.assumeTrue(JP2_PDF.exists(), "jpeg2000 fixture missing, skipping");
        try (PDDocument doc = Loader.loadPDF(JP2_PDF)) {
            PDImageXObject img = firstImageXObject(doc);
            assertNotNull(img, "expected an embedded image XObject in the fixture");

            // (1) PDFBox reports JPEG2000 as "jpx"; the saveOriginalXObjectBytes gate keys on this.
            assertEquals("jpx", img.getSuffix(),
                "PDFBox 3.0.6 must report JPEG2000 as suffix 'jpx' (the gate keys on this)");

            // (2) rosetta cannot decode JP2 -> Squint.decodeBytes throws, so decodeForHash falls back.
            byte[] raw = rawStreamBytes(img);
            assertThrows(Exception.class,
                () -> io.github.wmetcalf.rosettasquint.Squint.decodeBytes(raw),
                "rosetta must reject the JP2 codestream so the PDFBox fallback engages");

            // (3) The fallback yields the PDFBox-decoded image itself, and its hash equals hashing
            //     that image directly -- i.e. JP2 hashes over PDFBox pixels, per the contract.
            BufferedImage pdfboxImg = img.getImage();
            BufferedImage decoded = callDecodeForHash(raw, pdfboxImg);
            assertSame(pdfboxImg, decoded, "JP2 bytes must fall back to the PDFBox-decoded image");
            assertEquals(callComputePhash(pdfboxImg), callComputePhash(decoded),
                "the JP2 fallback hash must be the PDFBox-decoded-pixel hash");
        }
    }
}
