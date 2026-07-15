package com.oai.titanarum;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * I1 (warm-plan.md §I1): a PDF with many small (>=40x40) images must not fork a
 * ZXingReader subprocess per image without bound. These tests exercise the per-document
 * QR-scan budget and the image-extraction cap directly through {@code callWith}, using
 * small overridden caps (via the setters) so the test runs fast while still exercising
 * the real gate/anomaly-recording code path used in production (default caps 256 / 30s).
 */
class QrScanBudgetTest {

    /** One page with {@code count} draws of the same small XObject (cheap to build, N distinct Do ops). */
    private static Path buildPdfWithDrawnImages(Path dir, int count) throws Exception {
        Path pdf = dir.resolve("qr-flood.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            BufferedImage img = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
            PDImageXObject xobj = LosslessFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int i = 0; i < count; i++) {
                    cs.saveGraphicsState();
                    cs.transform(new org.apache.pdfbox.util.Matrix(1, 0, 0, 1, 10, 10));
                    cs.drawImage(xobj, 0, 0, 40, 40);
                    cs.restoreGraphicsState();
                }
            }
            doc.save(pdf.toFile());
        }
        return pdf;
    }

    @Test
    void qrScanBudget_stopsAtCap_andRecordsAnomaly(@TempDir Path tmp) throws Exception {
        int cap = 3;
        int imageCount = cap + 5; // N > MAX_QR_SCANS

        Path pdf = buildPdfWithDrawnImages(tmp, imageCount);
        Path out = tmp.resolve("out");
        Files.createDirectories(out);

        PdfTitanArumApp app = new PdfTitanArumApp();
        app.setMaxQrScans(cap);
        app.setQrTotalBudgetMs(60_000L); // keep the wall-clock budget out of play for this test
        app.setSkipScreenshots(true);
        app.setSkipPhones(true);
        app.setSkipTextUrls(true);
        app.setSkipPageExport(true);

        byte[] pdfBytes = Files.readAllBytes(pdf);
        PdfTitanArumApp.AnalysisReport report = app.callWith(
                pdfBytes, "qr-flood.pdf", out, 150f, "1",
                /* skipQrScan */ false, /* addLinkAnnotations */ false,
                /* modifiedPdfOutput */ null, /* password */ null);

        assertEquals(cap, app.getQrScanCount(),
                "scan count must stop exactly at the per-document MAX_QR_SCANS cap");
        assertNotNull(report.structuralAnomalies, "budget-exceeded anomaly must be recorded");
        assertTrue(report.structuralAnomalies.stream()
                        .anyMatch(a -> "qr_scan_budget_exceeded".equals(a.type)),
                "structuralAnomalies must contain a qr_scan_budget_exceeded entry");
    }

    @Test
    void qrScanBudget_underCap_noAnomaly(@TempDir Path tmp) throws Exception {
        int cap = 10;
        int imageCount = 2; // well under cap

        Path pdf = buildPdfWithDrawnImages(tmp, imageCount);
        Path out = tmp.resolve("out");
        Files.createDirectories(out);

        PdfTitanArumApp app = new PdfTitanArumApp();
        app.setMaxQrScans(cap);
        app.setQrTotalBudgetMs(60_000L);
        app.setSkipScreenshots(true);
        app.setSkipPhones(true);
        app.setSkipTextUrls(true);
        app.setSkipPageExport(true);

        byte[] pdfBytes = Files.readAllBytes(pdf);
        PdfTitanArumApp.AnalysisReport report = app.callWith(
                pdfBytes, "qr-flood.pdf", out, 150f, "1",
                false, false, null, null);

        assertEquals(imageCount, app.getQrScanCount(), "every image should be scanned when under the cap");
        if (report.structuralAnomalies != null) {
            assertFalse(report.structuralAnomalies.stream()
                            .anyMatch(a -> "qr_scan_budget_exceeded".equals(a.type)),
                    "no budget-exceeded anomaly expected when under the cap");
        }
    }

    @Test
    void imagesExtractedCap_stopsExtraction_andRecordsAnomaly(@TempDir Path tmp) throws Exception {
        int cap = 3;
        int imageCount = cap + 5;

        Path pdf = buildPdfWithDrawnImages(tmp, imageCount);
        Path out = tmp.resolve("out");
        Files.createDirectories(out);

        PdfTitanArumApp app = new PdfTitanArumApp();
        app.setMaxImagesExtracted(cap);
        app.setSkipScreenshots(true);
        app.setSkipPhones(true);
        app.setSkipTextUrls(true);
        app.setSkipPageExport(true);

        byte[] pdfBytes = Files.readAllBytes(pdf);
        PdfTitanArumApp.AnalysisReport report = app.callWith(
                pdfBytes, "qr-flood.pdf", out, 150f, "1",
                /* skipQrScan */ true, false, null, null);

        assertEquals(cap, report.renderedImages.size(),
                "drawn-image extraction must stop exactly at MAX_IMAGES_EXTRACTED");
        assertNotNull(report.structuralAnomalies);
        assertTrue(report.structuralAnomalies.stream()
                        .anyMatch(a -> "images_extracted_cap_exceeded".equals(a.type)),
                "structuralAnomalies must contain an images_extracted_cap_exceeded entry");
    }
}
