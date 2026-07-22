package com.oai.titanarum;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.PDTableAttributeObject;
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

    /**
     * A TAGGED 2x2 table (no drawn rulings): StructTreeRoot -> Table -> TR/TR with
     * TH("Name") TH("Qty") / TD("Ada", colSpan=1) TD("3"). First-row cells are TH.
     * The second row's first TD carries a ColSpan=1 attribute (attribute plumbing test).
     */
    static void tagged2x2(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            String[][] cells = {{"Name", "Qty"}, {"Ada", "3"}};
            int mcid = 0;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        COSDictionary d = new COSDictionary();
                        d.setInt(COSName.MCID, mcid++);
                        cs.beginMarkedContent(COSName.getPDFName(r == 0 ? "TH" : "TD"),
                                PDPropertyList.create(d));
                        text(cs, 60 + c * 120, 700 - r * 30, cells[r][c]);
                        cs.endMarkedContent();
                    }
                }
            }

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement table = new PDStructureElement("Table", root);
            table.setPage(page);
            root.appendKid(table);
            mcid = 0;
            for (int r = 0; r < 2; r++) {
                PDStructureElement tr = new PDStructureElement("TR", table);
                tr.setPage(page);
                table.appendKid(tr);
                for (int c = 0; c < 2; c++) {
                    PDStructureElement cell = new PDStructureElement(r == 0 ? "TH" : "TD", tr);
                    cell.setPage(page);
                    if (r == 1 && c == 0) {
                        PDTableAttributeObject att = new PDTableAttributeObject();
                        att.setColSpan(1);
                        cell.addAttribute(att);
                    }
                    cell.getCOSObject().setInt(COSName.K, mcid++); // kid = bare MCID
                    tr.appendKid(cell);
                }
            }
            doc.save(file.toFile());
        }
    }

    /** Tagged Table element with NO TR children — degenerate; must be rejected. */
    static void taggedDegenerate(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, 60, 700, "not really a table");
            }
            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement table = new PDStructureElement("Table", root);
            table.setPage(page);
            root.appendKid(table);
            doc.save(file.toFile());
        }
    }

    /**
     * Same shape as {@link #tagged2x2}, except the second row's first TD carries a hostile
     * {@code RowSpan=50_000_000} attribute instead of {@code ColSpan=1} -- a span-bomb PoC.
     */
    static void taggedSpanBomb(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            String[][] cells = {{"Name", "Qty"}, {"Ada", "3"}};
            int mcid = 0;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        COSDictionary d = new COSDictionary();
                        d.setInt(COSName.MCID, mcid++);
                        cs.beginMarkedContent(COSName.getPDFName(r == 0 ? "TH" : "TD"),
                                PDPropertyList.create(d));
                        text(cs, 60 + c * 120, 700 - r * 30, cells[r][c]);
                        cs.endMarkedContent();
                    }
                }
            }

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement table = new PDStructureElement("Table", root);
            table.setPage(page);
            root.appendKid(table);
            mcid = 0;
            for (int r = 0; r < 2; r++) {
                PDStructureElement tr = new PDStructureElement("TR", table);
                tr.setPage(page);
                table.appendKid(tr);
                for (int c = 0; c < 2; c++) {
                    PDStructureElement cell = new PDStructureElement(r == 0 ? "TH" : "TD", tr);
                    cell.setPage(page);
                    if (r == 1 && c == 0) {
                        PDTableAttributeObject att = new PDTableAttributeObject();
                        att.setRowSpan(50_000_000);
                        cell.addAttribute(att);
                    }
                    cell.getCOSObject().setInt(COSName.K, mcid++);
                    tr.appendKid(cell);
                }
            }
            doc.save(file.toFile());
        }
    }

    /**
     * Two-page doc; page 1 has ordinary (untagged) text; the ENTIRE tagged 2x2 table (structure
     * + all TH/TD marked content) lives on page 2. Used to prove that a table whose page falls
     * outside {@code pagesToProcess} is rejected before its page's content stream is ever walked.
     */
    static void taggedOnPageTwo(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page1 = new PDPage(PDRectangle.LETTER);
            doc.addPage(page1);
            PDPage page2 = new PDPage(PDRectangle.LETTER);
            doc.addPage(page2);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                text(cs, 60, 700, "page one, no table");
            }

            String[][] cells = {{"Name", "Qty"}, {"Ada", "3"}};
            int mcid = 0;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        COSDictionary d = new COSDictionary();
                        d.setInt(COSName.MCID, mcid++);
                        cs.beginMarkedContent(COSName.getPDFName(r == 0 ? "TH" : "TD"),
                                PDPropertyList.create(d));
                        text(cs, 60 + c * 120, 700 - r * 30, cells[r][c]);
                        cs.endMarkedContent();
                    }
                }
            }

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement table = new PDStructureElement("Table", root);
            table.setPage(page2);
            root.appendKid(table);
            mcid = 0;
            for (int r = 0; r < 2; r++) {
                PDStructureElement tr = new PDStructureElement("TR", table);
                tr.setPage(page2);
                table.appendKid(tr);
                for (int c = 0; c < 2; c++) {
                    PDStructureElement cell = new PDStructureElement(r == 0 ? "TH" : "TD", tr);
                    cell.setPage(page2);
                    cell.getCOSObject().setInt(COSName.K, mcid++);
                    tr.appendKid(cell);
                }
            }
            doc.save(file.toFile());
        }
    }

    /**
     * Two-page doc, ONE Table whose first TR's cells ("A","B") carry /Pg = page 1 and whose
     * second TR's cells ("C","D") carry /Pg = page 2. The table must be attributed to page 1
     * (the first cell's page); page-2 cells keep their text but must not contribute a bbox.
     */
    static void taggedCrossPage(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page1 = new PDPage(PDRectangle.LETTER);
            doc.addPage(page1);
            PDPage page2 = new PDPage(PDRectangle.LETTER);
            doc.addPage(page2);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page1)) {
                int mcid = 0;
                for (String s : new String[]{"A", "B"}) {
                    COSDictionary d = new COSDictionary();
                    d.setInt(COSName.MCID, mcid);
                    cs.beginMarkedContent(COSName.getPDFName("TD"), PDPropertyList.create(d));
                    text(cs, 60 + mcid * 120, 700, s);
                    cs.endMarkedContent();
                    mcid++;
                }
            }
            try (PDPageContentStream cs = new PDPageContentStream(doc, page2)) {
                int mcid = 0;
                for (String s : new String[]{"C", "D"}) {
                    COSDictionary d = new COSDictionary();
                    d.setInt(COSName.MCID, mcid);
                    cs.beginMarkedContent(COSName.getPDFName("TD"), PDPropertyList.create(d));
                    text(cs, 60 + mcid * 120, 700, s);
                    cs.endMarkedContent();
                    mcid++;
                }
            }

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement table = new PDStructureElement("Table", root);
            root.appendKid(table);

            PDStructureElement tr1 = new PDStructureElement("TR", table);
            tr1.setPage(page1);
            table.appendKid(tr1);
            for (int c = 0; c < 2; c++) {
                PDStructureElement cell = new PDStructureElement("TD", tr1);
                cell.setPage(page1);
                cell.getCOSObject().setInt(COSName.K, c);
                tr1.appendKid(cell);
            }

            PDStructureElement tr2 = new PDStructureElement("TR", table);
            tr2.setPage(page2);
            table.appendKid(tr2);
            for (int c = 0; c < 2; c++) {
                PDStructureElement cell = new PDStructureElement("TD", tr2);
                cell.setPage(page2);
                cell.getCOSObject().setInt(COSName.K, c);
                tr2.appendKid(cell);
            }

            doc.save(file.toFile());
        }
    }

    /**
     * An outer 1x1 tagged table whose sole TD has NO marked content of its own and instead wraps
     * a nested, independently-tagged 2x2 table (own TR/TD + own MCID text). Used to prove the
     * nested table's rows never leak into the outer table's grid, and extract standalone.
     */
    static void taggedNested(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            String[][] innerCells = {{"R1C1", "R1C2"}, {"R2C1", "R2C2"}};
            int mcid = 0;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        COSDictionary d = new COSDictionary();
                        d.setInt(COSName.MCID, mcid++);
                        cs.beginMarkedContent(COSName.getPDFName("TD"), PDPropertyList.create(d));
                        text(cs, 60 + c * 120, 700 - r * 30, innerCells[r][c]);
                        cs.endMarkedContent();
                    }
                }
            }

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);

            PDStructureElement outerTable = new PDStructureElement("Table", root);
            outerTable.setPage(page);
            root.appendKid(outerTable);
            PDStructureElement outerTr = new PDStructureElement("TR", outerTable);
            outerTr.setPage(page);
            outerTable.appendKid(outerTr);
            PDStructureElement outerTd = new PDStructureElement("TD", outerTr);
            outerTd.setPage(page);
            outerTr.appendKid(outerTd);
            // outerTd deliberately carries no MCID of its own -- only wraps the nested table.

            PDStructureElement innerTable = new PDStructureElement("Table", outerTd);
            innerTable.setPage(page);
            outerTd.appendKid(innerTable);
            mcid = 0;
            for (int r = 0; r < 2; r++) {
                PDStructureElement tr = new PDStructureElement("TR", innerTable);
                tr.setPage(page);
                innerTable.appendKid(tr);
                for (int c = 0; c < 2; c++) {
                    PDStructureElement cell = new PDStructureElement("TD", tr);
                    cell.setPage(page);
                    cell.getCOSObject().setInt(COSName.K, mcid++);
                    tr.appendKid(cell);
                }
            }

            doc.save(file.toFile());
        }
    }
}
