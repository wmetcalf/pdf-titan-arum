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
