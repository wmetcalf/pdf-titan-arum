package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RulingCollectorTest {

    @TempDir
    Path tmp;

    @Test
    void collectsStrokedGridInTopLeftSpace() throws Exception {
        Path pdf = tmp.resolve("grid.pdf");
        TableTestPdfs.ruled3x3(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            long horiz = rulings.stream().filter(TableExtractor.Ruling::horizontal).count();
            long vert = rulings.stream().filter(TableExtractor.Ruling::vertical).count();
            assertEquals(4, horiz);
            assertEquals(4, vert);
            // Page height 792: bottom-left y=700 becomes top-left y=92.
            assertTrue(rulings.stream().anyMatch(r -> r.horizontal() && Math.abs(r.y1 - 92) <= 2),
                    "y must be flipped into top-left-origin space");
        }
    }

    @Test
    void treatsThinFilledRectsAsLines() throws Exception {
        Path pdf = tmp.resolve("fills.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // 1pt-tall filled rect = horizontal line; 300x200 filled rect = NOT a line
                cs.addRect(50, 700, 300, 1);
                cs.fill();
                cs.addRect(50, 400, 300, 200);
                cs.fill();
                // a stroked vertical so the horizontal isn't dropped later (not needed for collect)
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            assertEquals(1, rulings.size(), "only the thin fill is a ruling; the big rect is ignored");
            assertTrue(rulings.get(0).horizontal());
        }
    }

    @Test
    void batchedStrokedSubpathsAllYieldRulings() throws Exception {
        // Three m/l horizontal subpaths batched before a single S — the common generator
        // pattern (build the whole path, then one paint op) that a naive "clear on moveTo"
        // implementation would collapse down to just the last subpath.
        Path pdf = tmp.resolve("batched-stroke.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(1f);
                cs.moveTo(50, 700); cs.lineTo(200, 700);
                cs.moveTo(50, 670); cs.lineTo(200, 670);
                cs.moveTo(50, 640); cs.lineTo(200, 640);
                cs.stroke();
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            assertEquals(3, rulings.size(), "all three batched subpaths must survive, not just the last");
            assertTrue(rulings.stream().allMatch(TableExtractor.Ruling::horizontal));
        }
    }

    @Test
    void batchedFilledThinRectsAllYieldRulings() throws Exception {
        // Three re thin-rects batched before a single f — same batching pattern, fill path.
        Path pdf = tmp.resolve("batched-fill.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.addRect(50, 700, 100, 1);
                cs.addRect(50, 650, 100, 1);
                cs.addRect(50, 600, 100, 1);
                cs.fill();
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            assertEquals(3, rulings.size(), "each batched thin rect must be evaluated as its own ruling");
            assertTrue(rulings.stream().allMatch(TableExtractor.Ruling::horizontal));
        }
    }

    @Test
    void nonOriginCropBoxIsAccountedFor() throws Exception {
        Path pdf = tmp.resolve("cropbox.pdf");
        try (PDDocument doc = new PDDocument()) {
            // MediaBox must be >= the CropBox we set below: PDPage.getCropBox() clips to
            // the MediaBox, so a CropBox exceeding US Letter would silently shrink back down.
            PDPage page = new PDPage(new PDRectangle(800, 1000));
            // Lower-left (100,100), upper-right (700,900).
            page.setCropBox(new PDRectangle(100, 100, 600, 800));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                TableTestPdfs.line(cs, 150, 800, 350, 800);
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            // x' = x - 100 -> 50..250; y' = 900 - 800 = 100.
            assertTrue(rulings.stream().anyMatch(r -> r.horizontal()
                            && Math.abs(r.x1 - 50) <= 2 && Math.abs(r.x2 - 250) <= 2
                            && Math.abs(r.y1 - 100) <= 2),
                    "cropBox-relative origin must be applied, not just page height");
        }
    }

    @Test
    void unboundedPathIsDroppedNotThrown() throws Exception {
        Path pdf = tmp.resolve("giant-path.pdf");
        int n = TableExtractor.MAX_PATH_POINTS + 1000;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.moveTo(10, 10);
                for (int i = 0; i < n; i++) {
                    cs.lineTo(10 + (i % 500), 10 + (i % 100));
                }
                cs.stroke();
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            assertEquals(0, rulings.size(), "an over-cap path is dropped silently, not converted to rulings");
        }
    }

    @Test
    void capThrowsOnRulingBomb() throws Exception {
        Path pdf = tmp.resolve("bomb.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int i = 0; i < TableExtractor.MAX_RULINGS_PER_PAGE + 100; i++) {
                    float y = 10 + (i % 770);
                    TableTestPdfs.line(cs, 10, y, 30, y);
                }
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertThrows(TableExtractor.RulingOverflowException.class,
                    () -> TableExtractor.collectRulings(doc.getPage(0)));
        }
    }
}
