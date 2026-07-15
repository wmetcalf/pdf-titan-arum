package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Cross-language parity for an embedded JPEG, end to end through titan's real code path.
 *
 * The Python fleet hashes a PDF-embedded JPEG by decoding the original JFIF stream with PIL. titan
 * must match byte-for-byte: saveOriginalXObjectBytes saves the ORIGINAL encoded JPEG (createRawInputStream),
 * and decodeForHash routes it through rosetta-squint's turbojpeg decode (PIL-exact). The expected hex
 * below are the independent Python values: str(rosetta_squint.phash_bytes(jpeg)) /
 * str(colorhash_bytes(jpeg, binbits=4)).
 *
 * Requires the turbojpeg JNI binding on the classpath (Ubuntu libturbojpeg-java, wired via the pom's
 * surefire additionalClasspathElements). If it is absent, Squint throws a LinkageError and the test
 * self-skips -- so this stays green in environments without turbojpeg while still gating parity where
 * it is present (CI/dev/deploy).
 */
class JpegParityTest {

    private static final File JPEG_PDF =
        new File("deploy/docker/appcds-warmup-corpus/09-image-jpeg.pdf");
    // Independent Python rosetta_squint values on the extracted JFIF stream (the fleet reference).
    private static final String PY_PHASH     = "8a6b4762cd1ec61d";
    private static final String PY_COLORHASH = "02111001002112";

    private static String callComputePhash(BufferedImage img) throws Exception {
        Method m = PdfTitanArumApp.class.getDeclaredMethod("computePhash", BufferedImage.class);
        m.setAccessible(true);
        return (String) m.invoke(null, img);
    }
    private static String callComputeColorHash(BufferedImage img) throws Exception {
        Method m = PdfTitanArumApp.class.getDeclaredMethod("computeColorHash", BufferedImage.class);
        m.setAccessible(true);
        return (String) m.invoke(null, img);
    }
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
        try (InputStream is = ((COSStream) img.getCOSObject()).createRawInputStream();
             ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            is.transferTo(bo); return bo.toByteArray();
        }
    }
    private static PDImageXObject firstImageXObject(PDDocument doc) throws Exception {
        for (int p = 0; p < doc.getNumberOfPages(); p++) {
            PDResources res = doc.getPage(p).getResources();
            if (res == null) continue;
            for (COSName name : res.getXObjectNames()) {
                PDXObject xo = res.getXObject(name);
                if (xo instanceof PDImageXObject img) return img;
            }
        }
        return null;
    }

    @Test
    void embeddedJpegSavesRealJfifStream(@TempDir Path tempDir) throws Exception {
        Assumptions.assumeTrue(JPEG_PDF.exists(), "jpeg fixture missing, skipping");
        try (PDDocument doc = Loader.loadPDF(JPEG_PDF)) {
            PDImageXObject img = firstImageXObject(doc);
            assertNotNull(img);
            assertEquals("jpg", img.getSuffix());
            byte[] raw = rawStreamBytes(img);
            // The saved original must be the REAL JFIF stream (FFD8), not PDFBox-decoded raster.
            assertEquals("ffd8", String.format("%02x%02x", raw[0], raw[1]),
                "createRawInputStream must yield the encoded JFIF (FFD8), not decoded raster");
            String rel = callSaveOriginalXObjectBytes(img, tempDir, "jpeg-original");
            assertNotNull(rel);
            assertTrue(rel.endsWith(".jpg"), "saved as .jpg, got " + rel);
            assertArrayEquals(raw, Files.readAllBytes(tempDir.resolve(rel)),
                "saved file must be the raw JFIF stream byte-for-byte");
        }
    }

    @Test
    void multiFilterDctSavesTrueCodestreamNotWrapperBytes(@TempDir Path tempDir) throws Exception {
        // A DCT codestream may be legally wrapped in a transport filter -- /Filter
        // [/ASCII85Decode /DCTDecode]. createRawInputStream() would save the ASCII85 wrapper
        // bytes, which rosetta can't decode -> silent PDFBox fallback and hash drift off fleet
        // parity. saveOriginalXObjectBytes must decode the wrapper but stop at the codec, saving
        // the true ffd8 JFIF stream.
        Assumptions.assumeTrue(JPEG_PDF.exists(), "jpeg fixture missing, skipping");
        try (PDDocument doc = Loader.loadPDF(JPEG_PDF)) {
            byte[] jpeg = rawStreamBytes(firstImageXObject(doc));  // real FFD8 JFIF codestream
            assertEquals("ffd8", String.format("%02x%02x", jpeg[0], jpeg[1]),
                "fixture precondition: extracted stream is a JPEG");

            // Store ascii85(jpeg) and declare /Filter [/ASCII85Decode /DCTDecode].
            PDStream pdStream = new PDStream(doc, new ByteArrayInputStream(jpeg), COSName.ASCII85_DECODE);
            COSStream cos = pdStream.getCOSObject();
            COSArray filters = new COSArray();
            filters.add(COSName.ASCII85_DECODE);
            filters.add(COSName.DCT_DECODE);
            cos.setItem(COSName.FILTER, filters);
            cos.setItem(COSName.TYPE, COSName.XOBJECT);
            cos.setItem(COSName.SUBTYPE, COSName.IMAGE);
            cos.setInt(COSName.WIDTH, 8);
            cos.setInt(COSName.HEIGHT, 8);
            cos.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
            cos.setInt(COSName.BITS_PER_COMPONENT, 8);
            PDImageXObject wrapped = new PDImageXObject(pdStream, null);
            assertEquals("jpg", wrapped.getSuffix(), "a DCT-terminated multi-filter stream is still a jpg");

            // Precondition == the OLD bug: the RAW stored bytes are the ASCII85 wrapper, not FFD8.
            byte[] rawStored = rawStreamBytes(wrapped);
            assertNotEquals("ffd8", String.format("%02x%02x", rawStored[0], rawStored[1]),
                "raw stored bytes are the ASCII85 wrapper (what createRawInputStream would have saved)");

            // The fix: save the TRUE codestream (wrapper decoded, DCT left intact).
            String rel = callSaveOriginalXObjectBytes(wrapped, tempDir, "wrapped-jpeg");
            assertNotNull(rel);
            byte[] saved = Files.readAllBytes(tempDir.resolve(rel));
            assertEquals("ffd8", String.format("%02x%02x", saved[0], saved[1]),
                "multi-filter DCT must be saved as the real FFD8 codestream, not the ASCII85 wrapper");
            assertArrayEquals(jpeg, saved, "saved codestream must equal the original JFIF byte-for-byte");
        }
    }

    @Test
    void embeddedJpegHashMatchesPythonRosettaSquint() throws Exception {
        Assumptions.assumeTrue(JPEG_PDF.exists(), "jpeg fixture missing, skipping");
        try (PDDocument doc = Loader.loadPDF(JPEG_PDF)) {
            PDImageXObject img = firstImageXObject(doc);
            assertNotNull(img);
            byte[] raw = rawStreamBytes(img);
            BufferedImage decoded;
            try {
                decoded = io.github.wmetcalf.rosettasquint.Squint.decodeBytes(raw);
            } catch (LinkageError e) {
                Assumptions.abort("turbojpeg JNI binding not on classpath (" + e + "); skipping JPEG parity");
                return; // unreachable
            }
            // rosetta turbojpeg decode == Python PIL decode -> byte-identical hashes.
            assertEquals(PY_PHASH, callComputePhash(decoded),
                "titan JPEG phash must equal Python rosetta_squint");
            assertEquals(PY_COLORHASH, callComputeColorHash(decoded),
                "titan JPEG colorhash must equal Python rosetta_squint");
        }
    }
}
