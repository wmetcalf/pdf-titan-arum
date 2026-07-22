package com.oai.titanarum;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.file.Path;

/** Generated PDF fixtures for table-extraction tests. Coordinates here are PDF-native (bottom-left origin). */
final class TableTestPdfs {

    static final PDType1Font HELV = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    private TableTestPdfs() {}

    static void line(PDPageContentStream cs, float x1, float y1, float x2, float y2) throws IOException {
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
    }

    static void text(PDPageContentStream cs, float x, float y, String s) throws IOException {
        cs.beginText();
        cs.setFont(HELV, 10);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    /**
     * One page (US Letter), ruled 3x3 grid: verticals at x=50/150/250/350,
     * horizontals at y=700/670/640/610 (bottom-left origin). Cell (r,c) holds "R{r}C{c}".
     */
    static void ruled3x3(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.75f);
                for (float y : new float[]{700, 670, 640, 610}) line(cs, 50, y, 350, y);
                for (float x : new float[]{50, 150, 250, 350}) line(cs, x, 700, x, 610);
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        text(cs, 55 + c * 100, 700 - 20 - r * 30, "R" + (r + 1) + "C" + (c + 1));
                    }
                }
            }
            doc.save(file.toFile());
        }
    }

    /**
     * 2x2 grid at x=50/150/250, y(bottom-left)=700/670/640 whose TOP internal vertical is
     * missing: header cell spans both columns. Header text "HDR", bottom cells "L"/"R".
     */
    static void mergedHeader(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.75f);
                for (float y : new float[]{700, 670, 640}) line(cs, 50, y, 250, y);
                line(cs, 50, 700, 50, 640);
                line(cs, 250, 700, 250, 640);
                line(cs, 150, 670, 150, 640); // internal vertical only on the bottom row
                text(cs, 55, 680, "HDR");
                text(cs, 55, 650, "L");
                text(cs, 155, 650, "R");
            }
            doc.save(file.toFile());
        }
    }

    /** Same content as {@link #ruled3x3}, but with the page's /Rotate set to the given degrees. */
    static void rotatedRuled3x3(Path file, int rotationDegrees) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.75f);
                for (float y : new float[]{700, 670, 640, 610}) line(cs, 50, y, 350, y);
                for (float x : new float[]{50, 150, 250, 350}) line(cs, x, 700, x, 610);
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        text(cs, 55 + c * 100, 700 - 20 - r * 30, "R" + (r + 1) + "C" + (c + 1));
                    }
                }
            }
            page.setRotation(rotationDegrees);
            doc.save(file.toFile());
        }
    }

    /** No tables: a paragraph, an underlined word, and one boxed callout rectangle. */
    static void noTables(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, 50, 700, "This paragraph has no table structure at all.");
                text(cs, 50, 660, "underlined");
                line(cs, 50, 657, 105, 657);        // underline
                cs.addRect(50, 500, 200, 80);        // boxed callout = single cell, not a table
                cs.stroke();
                text(cs, 60, 540, "callout box");
            }
            doc.save(file.toFile());
        }
    }
}
