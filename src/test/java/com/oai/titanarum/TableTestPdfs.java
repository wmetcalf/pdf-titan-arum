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
     * One page whose content stream is a single {@code (AAAA...) Tj} of {@code glyphCount}
     * identical characters, Flate-compressed by PDFBox's own {@code createOutputStream
     * (FLATE_DECODE)} as it is written -- millions of identical bytes compress to a few KB on
     * disk, matching how a hostile few-KB PDF can carry a huge glyph count. Used by
     * region-fill memory/CPU-bound regression tests (round 3, FIX A and its MAX_REGION_WORK
     * follow-up).
     */
    static void manyGlyphsOnePage(Path file, int glyphCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            org.apache.pdfbox.pdmodel.PDResources resources = new org.apache.pdfbox.pdmodel.PDResources();
            resources.put(COSName.getPDFName("F1"), HELV);
            page.setResources(resources);

            org.apache.pdfbox.cos.COSStream cosStream = doc.getDocument().createCOSStream();
            try (java.io.OutputStream os = cosStream.createOutputStream(COSName.FLATE_DECODE)) {
                os.write("BT /F1 12 Tf (".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                byte[] chunk = new byte[1 << 16];
                java.util.Arrays.fill(chunk, (byte) 'A');
                int remaining = glyphCount;
                while (remaining > 0) {
                    int n = Math.min(chunk.length, remaining);
                    os.write(chunk, 0, n);
                    remaining -= n;
                }
                os.write(") Tj ET".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cosStream);
            doc.save(file.toFile());
        }
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

    /**
     * Reviewer's FIX 5 reproducer: two INDEPENDENT, axis-aligned tables drawn directly touching,
     * sharing one vertical border ruling at x=250, but with incompatible row pitches --
     * table A (2 rows x 2 cols, 30pt row pitch, x in [50,250]) and table B (3 rows x 2 cols, 20pt
     * row pitch, x in [250,450]), both spanning the same y-range [640,700]. Since A and B share
     * only that one border edge (not a common row partition), groupIntoTables' edge-adjacency
     * union-find merges them into ONE component -- exercising the split-detection fix. Cell text
     * is "A{row}{col}" / "B{row}{col}" (1-indexed) so the two expected output tables are easy to
     * tell apart and verify independently.
     */
    static void adjacentIndependentTables(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.75f);
                // Table A: 2 rows x 2 cols, 30pt pitch, x in [50,150,250].
                for (float y : new float[]{700, 670, 640}) line(cs, 50, y, 250, y);
                for (float x : new float[]{50, 150, 250}) line(cs, x, 700, x, 640);
                String[][] aCells = {{"A11", "A12"}, {"A21", "A22"}};
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        text(cs, 55 + c * 100, 700 - 20 - r * 30, aCells[r][c]);
                    }
                }
                // Table B: 3 rows x 2 cols, 20pt pitch, x in [250,350,450]. Shares the x=250
                // border with A's right edge, but its OWN row partition (700/680/660/640) has no
                // correspondence to A's (700/670/640).
                for (float y : new float[]{700, 680, 660, 640}) line(cs, 250, y, 450, y);
                for (float x : new float[]{250, 350, 450}) line(cs, x, 700, x, 640);
                String[][] bCells = {{"B11", "B12"}, {"B21", "B22"}, {"B31", "B32"}};
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 2; c++) {
                        text(cs, 255 + c * 100, 700 - 13 - r * 20, bCells[r][c]);
                    }
                }
            }
            doc.save(file.toFile());
        }
    }

    /**
     * A ruled 2x2 grid (verticals at x=50/150/250, horizontals at y=700/670/640) whose (0,0) cell
     * has its text ("Total") drawn TWICE at the IDENTICAL (x,y) position -- a common non-hostile
     * PDF-generator pattern (fake-bold-via-redraw, redundant text layers) rather than an
     * adversarial one. The other three cells each hold a distinct single-drawn label so the
     * fixture can pin that ONLY the duplicated cell is affected. Used to reproduce the f095959
     * regression: with duplicate-overlapping-text suppression disabled, {@code
     * setSortByPosition(true)}'s sort interleaves the two identically-positioned "Total" runs
     * character-by-character ("TToottaall") instead of collapsing them to the single correct
     * copy PDFTextStripperByArea's default suppression produces.
     */
    static void ruled2x2DuplicateDrawnCell(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.75f);
                for (float y : new float[]{700, 670, 640}) line(cs, 50, y, 250, y);
                for (float x : new float[]{50, 150, 250}) line(cs, x, 700, x, 640);
                text(cs, 55, 680, "Total");
                text(cs, 55, 680, "Total"); // identical (x,y) redraw -- fake-bold / redundant layer
                text(cs, 155, 680, "B");
                text(cs, 55, 650, "C");
                text(cs, 155, 650, "D");
            }
            doc.save(file.toFile());
        }
    }

    /**
     * Two independently-ruled 2x2 tables whose cell geometry genuinely OVERLAPS: table A is a 2x2
     * grid (x in {50,250,450}, y in {600,650,700}), and table B is a SEPARATE, smaller 2x2 grid
     * (x in {100,150,200}, y in {660,675,690}) nested entirely inside A's own (row0,col0) cell
     * (x[50,250] y[650,700]), with enough margin that none of B's ruling coordinates fall on any
     * of A's (and vice versa) -- so {@code intersects()} never fires between A's and B's rulings
     * (verified: A's own gridlines never enter B's coordinate range on either axis, and B's
     * gridlines never enter A's), and {@code groupIntoTables} keeps them as two separate
     * components rather than merging them. A single glyph ("X") is drawn at a point that falls
     * inside BOTH table A's (row0,col0) cell AND table B's (row0,col0) cell -- the round-6
     * reproducer for the confirmed correctness bug where a glyph inside more than one overlapping
     * cell region used to be silently retained by only ONE of them.
     */
    static void nestedOverlappingTables(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.75f);
                // Table A: 2x2 grid, x in {50,250,450}, y in {600,650,700}.
                for (float y : new float[]{600, 650, 700}) line(cs, 50, y, 450, y);
                for (float x : new float[]{50, 250, 450}) line(cs, x, 600, x, 700);
                text(cs, 260, 660, "A01");
                text(cs, 60, 610, "A10");
                text(cs, 260, 610, "A11");
                // Table B: nested 2x2 grid entirely inside A's (row0,col0) cell = x[50,250]
                // y[650,700], at x in {100,150,200}, y in {660,675,690} -- chosen so neither
                // table's ruling coordinates fall inside the other's coordinate range on either
                // axis, so their rulings never cross.
                for (float y : new float[]{660, 675, 690}) line(cs, 100, y, 200, y);
                for (float x : new float[]{100, 150, 200}) line(cs, x, 660, x, 690);
                text(cs, 160, 680, "B01");
                text(cs, 105, 663, "B10");
                text(cs, 160, 663, "B11");
                // The shared glyph: inside BOTH A's (row0,col0) cell (x[50,250] y[650,700]) AND
                // B's (row0,col0) cell (x[100,150] y[675,690]).
                text(cs, 105, 680, "X");
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

    /**
     * A {@code pageCount}-page document, each page an independent copy of {@link #ruled3x3}'s
     * ruled 3x3 grid (own rulings, own text). Used to exercise multi-page lifecycle behavior
     * (e.g. an interrupted extract() must stop between pages rather than processing all of them).
     */
    static void multiPageRuled3x3(Path file, int pageCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
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
            }
            doc.save(file.toFile());
        }
    }

    /**
     * A single page with three lines of raw (untagged, unruled) text: "Total" at normal 10pt
     * baseline (bottom-left y=700), immediately followed by a superscripted "1" at 5pt whose
     * baseline is raised 4pt above "Total"'s (y=704) -- a footnote-marker-style annotation -- and
     * a genuine second line "next" a full line-height (12pt) below "Total"'s baseline (y=688).
     * Used to drive {@code TableExtractor.joinText} directly against real, PDFBox-measured
     * TextPositions (not hand-constructed ones) to pin the superscript-safe line-grouping fix.
     */
    static void superscriptFootnote(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(HELV, 10);
                cs.newLineAtOffset(60, 700);
                cs.showText("Total");
                cs.endText();
                cs.beginText();
                cs.setFont(HELV, 5);
                cs.newLineAtOffset(82.3f, 704); // touching "Total"'s right edge, raised 4pt
                cs.showText("1");
                cs.endText();
                cs.beginText();
                cs.setFont(HELV, 10);
                cs.newLineAtOffset(60, 688); // one 12pt line-height below "Total"'s baseline
                cs.showText("next");
                cs.endText();
            }
            doc.save(file.toFile());
        }
    }

    /**
     * A single page with the plain text "AB" -- two distinct, single-char glyphs side by side.
     * FIX 3 fixture: used to build a list holding the SAME (first) TextPosition reference twice,
     * mimicking what positionsForRange returns for a multi-code-unit glyph (ligature/surrogate
     * pair), without needing an embedded ligature font.
     */
    static void twoGlyphsAB(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, 60, 700, "AB");
            }
            doc.save(file.toFile());
        }
    }

    /**
     * A single page with the plain text "aa" -- two distinct, separately-drawn 'a' glyphs (two
     * separate TextPosition objects sharing the same unicode value). FIX 3 companion fixture:
     * proves reference-identity dedup does not over-dedup genuinely distinct same-character glyphs.
     */
    static void twoGlyphsAA(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                text(cs, 60, 700, "aa");
            }
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

    /** Same as {@link #tagged2x2}, but with the page's /Rotate set to the given degrees -- used
     * to pin FIX 1 (tagged cell/table bbox must share the lattice path's rotated visual frame). */
    static void taggedRotated2x2(Path file, int rotationDegrees) throws IOException {
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
                    cell.getCOSObject().setInt(COSName.K, mcid++);
                    tr.appendKid(cell);
                }
            }
            page.setRotation(rotationDegrees);
            doc.save(file.toFile());
        }
    }

    /**
     * A tagged 1x1 table (one TD, MCID 0) AND a separate, non-overlapping ruled (untagged) 2x2
     * lattice grid on the SAME page -- FIX 2 reproducer: a page carrying a tagged table used to be
     * entirely skipped for lattice extraction, silently dropping the second, genuinely separate
     * ruled table. The tagged cell sits at the top of the page (y around 700); the ruled grid sits
     * well below it (y in [400,460]) so their bboxes never overlap.
     */
    static void taggedPlusSeparateRuledTable(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                COSDictionary d = new COSDictionary();
                d.setInt(COSName.MCID, 0);
                cs.beginMarkedContent(COSName.getPDFName("TD"), PDPropertyList.create(d));
                text(cs, 60, 700, "Tagged");
                cs.endMarkedContent();

                // Separate ruled 2x2 grid, well below the tagged cell -- no structure reference.
                cs.setLineWidth(0.75f);
                for (float y : new float[]{460, 430, 400}) line(cs, 50, y, 250, y);
                for (float x : new float[]{50, 150, 250}) line(cs, x, 460, x, 400);
                text(cs, 55, 440, "L");
                text(cs, 155, 440, "R");
                text(cs, 55, 410, "C");
                text(cs, 155, 410, "D");
            }

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement table = new PDStructureElement("Table", root);
            table.setPage(page);
            root.appendKid(table);
            PDStructureElement tr = new PDStructureElement("TR", table);
            tr.setPage(page);
            table.appendKid(tr);
            PDStructureElement cell = new PDStructureElement("TD", tr);
            cell.setPage(page);
            cell.getCOSObject().setInt(COSName.K, 0);
            tr.appendKid(cell);

            doc.save(file.toFile());
        }
    }

    /**
     * A tagged table whose SOLE cell is ALSO surrounded by a drawn ruling grid at the exact same
     * geometric extent -- FIX 2 control reproducer: the tagged and lattice paths both find the
     * SAME visual table here (unlike {@link #taggedPlusSeparateRuledTable}'s genuinely separate
     * pair), so it must be emitted exactly ONCE (the tagged copy), not duplicated.
     */
    static void taggedAndRuledSameTable(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            String[][] cells = {{"Name", "Qty"}, {"Ada", "3"}};
            int mcid = 0;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.75f);
                for (float y : new float[]{700, 670, 640}) line(cs, 50, y, 250, y);
                for (float x : new float[]{50, 150, 250}) line(cs, x, 700, x, 640);
                for (int r = 0; r < 2; r++) {
                    for (int c = 0; c < 2; c++) {
                        COSDictionary d = new COSDictionary();
                        d.setInt(COSName.MCID, mcid++);
                        cs.beginMarkedContent(COSName.getPDFName(r == 0 ? "TH" : "TD"),
                                PDPropertyList.create(d));
                        text(cs, 55 + c * 100, 700 - 20 - r * 30, cells[r][c]);
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
                    cell.getCOSObject().setInt(COSName.K, mcid++);
                    tr.appendKid(cell);
                }
            }
            doc.save(file.toFile());
        }
    }

    /**
     * A tagged 1x1 table (one TD, MCID 0) whose cell text ("Total") is drawn TWICE at the
     * IDENTICAL position -- FIX 4 lock-in: mirrors {@link #ruled2x2DuplicateDrawnCell}'s
     * fake-bold-via-redraw pattern but for the tagged path (glyphs resolved via
     * PDFMarkedContentExtractor, not PDFTextStripper/PDFTextStripperByArea).
     */
    static void taggedDuplicateDrawnCellText(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                COSDictionary d = new COSDictionary();
                d.setInt(COSName.MCID, 0);
                cs.beginMarkedContent(COSName.getPDFName("TD"), PDPropertyList.create(d));
                text(cs, 60, 700, "Total");
                text(cs, 60, 700, "Total"); // identical (x,y) redraw -- fake-bold / redundant layer
                cs.endMarkedContent();
            }

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement table = new PDStructureElement("Table", root);
            table.setPage(page);
            root.appendKid(table);
            PDStructureElement tr = new PDStructureElement("TR", table);
            tr.setPage(page);
            table.appendKid(tr);
            PDStructureElement cell = new PDStructureElement("TD", tr);
            cell.setPage(page);
            cell.getCOSObject().setInt(COSName.K, 0);
            tr.appendKid(cell);

            doc.save(file.toFile());
        }
    }

    /**
     * PR review P1 (CRITICAL) reproducer: a page with ONE legit 1-cell tagged table (Table -> TR ->
     * TD, MCID 0, text "OK") PLUS a huge run of {@code bombGlyphCount} repeated, structure-
     * UNREFERENCED glyphs elsewhere in the SAME content stream -- no BDC/EMC wrapper, no MCID, never
     * pointed at by any kid of the structure tree. Written as a single raw (Flate-compressed, like
     * {@link #manyGlyphsOnePage}) content stream so the bomb's on-disk size stays tiny regardless of
     * {@code bombGlyphCount}.
     *
     * <p>extractTagged's pagesToProcess/firstCellPage gating only filters a table by PAGE NUMBER
     * before glyphsFor ever runs -- it does not (and, short of walking the content stream first,
     * cannot cheaply) bound how much OTHER marked content an in-scope page carries. Once the legit
     * TD's own MCID-0 lookup triggers glyphsFor's one-per-page {@code
     * PDFMarkedContentExtractor.processPage(page)} walk, that walk processes the ENTIRE page content
     * stream in one pass -- including this structure-unreferenced bomb -- regardless of MCID
     * boundaries.
     */
    static void taggedWithUnreferencedGlyphBomb(Path file, int bombGlyphCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            org.apache.pdfbox.pdmodel.PDResources resources = new org.apache.pdfbox.pdmodel.PDResources();
            resources.put(COSName.getPDFName("F1"), HELV);
            page.setResources(resources);

            org.apache.pdfbox.cos.COSStream cosStream = doc.getDocument().createCOSStream();
            try (java.io.OutputStream os = cosStream.createOutputStream(COSName.FLATE_DECODE)) {
                // Legit 1-cell tagged table content: MCID 0, the ONLY marked content referenced by
                // the structure tree below.
                os.write("/TD << /MCID 0 >> BDC BT /F1 12 Tf 60 700 Td (OK) Tj ET EMC "
                        .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                // Structure-unreferenced glyph bomb: no BDC/EMC wrapper, no MCID, never pointed at
                // by the structure tree at all -- yet still walked by the same processPage() call.
                os.write("BT /F1 12 Tf 60 650 Td (".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
                byte[] chunk = new byte[1 << 16];
                java.util.Arrays.fill(chunk, (byte) 'A');
                int remaining = bombGlyphCount;
                while (remaining > 0) {
                    int n = Math.min(chunk.length, remaining);
                    os.write(chunk, 0, n);
                    remaining -= n;
                }
                os.write(") Tj ET".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cosStream);

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement table = new PDStructureElement("Table", root);
            table.setPage(page);
            root.appendKid(table);
            PDStructureElement tr = new PDStructureElement("TR", table);
            tr.setPage(page);
            table.appendKid(tr);
            PDStructureElement cell = new PDStructureElement("TD", tr);
            cell.setPage(page);
            cell.getCOSObject().setInt(COSName.K, 0); // kid = bare MCID 0 -- the ONLY structure reference
            tr.appendKid(cell);

            doc.save(file.toFile());
        }
    }

    /**
     * A tagged 1x1 table (one TD, MCID 0) whose text is wrapped in {@code nestDepth} nested,
     * untagged "Span" BDC/EMC marked-content blocks in the content stream -- exercising
     * flattenMarkedContent's recursive walk over a deeply nested {@code PDMarkedContent} tree
     * built by {@code PDFMarkedContentExtractor} from real (if hostile) content-stream nesting.
     */
    static void taggedDeeplyNestedMarkedContent(Path file, int nestDepth) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                COSName span = COSName.getPDFName("Span");
                for (int i = 0; i < nestDepth; i++) cs.beginMarkedContent(span);
                COSDictionary d = new COSDictionary();
                d.setInt(COSName.MCID, 0);
                cs.beginMarkedContent(COSName.getPDFName("TD"), PDPropertyList.create(d));
                text(cs, 60, 700, "DEEP");
                cs.endMarkedContent();
                for (int i = 0; i < nestDepth; i++) cs.endMarkedContent();
            }

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement table = new PDStructureElement("Table", root);
            table.setPage(page);
            root.appendKid(table);
            PDStructureElement tr = new PDStructureElement("TR", table);
            tr.setPage(page);
            table.appendKid(tr);
            PDStructureElement cell = new PDStructureElement("TD", tr);
            cell.setPage(page);
            cell.getCOSObject().setInt(COSName.K, 0);
            tr.appendKid(cell);
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
     * Same layout as {@link #taggedOnPageTwo} (page 1 plain text, page 2 the whole tagged 2x2
     * table's marked content), EXCEPT /Pg is set ONLY on the Table structure element itself --
     * neither the TR nor the TD/TH elements call {@code setPage(...)} at all. Per ISO 32000, /Pg
     * is commonly inherited from an ancestor rather than repeated on every leaf; a page lookup
     * that only checks the TD/TH element itself (no ancestor fallback) resolves {@code null} for
     * every cell here and must not silently drop the whole table.
     */
    static void taggedPgOnTableAncestorOnly(Path file) throws IOException {
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
            table.setPage(page2); // /Pg lives ONLY here -- no TR/TD below ever calls setPage()
            root.appendKid(table);
            mcid = 0;
            for (int r = 0; r < 2; r++) {
                PDStructureElement tr = new PDStructureElement("TR", table);
                table.appendKid(tr);
                for (int c = 0; c < 2; c++) {
                    PDStructureElement cell = new PDStructureElement(r == 0 ? "TH" : "TD", tr);
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
     * A single tagged TR with 11 TD cells, each carrying a ColSpan=1000 attribute -- cumulative
     * area 11 x 1,000 = 11,000 exceeds MAX_CELLS_PER_TABLE (10,000). Only the first cell carries
     * real MCID text, so the table clears the "degenerate" (all-cells-empty) check and reaches
     * the cumulative-area guard instead of being rejected as degenerate.
     */
    static void taggedAreaCapBomb(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                COSDictionary d = new COSDictionary();
                d.setInt(COSName.MCID, 0);
                cs.beginMarkedContent(COSName.getPDFName("TD"), PDPropertyList.create(d));
                text(cs, 60, 700, "X");
                cs.endMarkedContent();
            }

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement table = new PDStructureElement("Table", root);
            table.setPage(page);
            root.appendKid(table);
            PDStructureElement tr = new PDStructureElement("TR", table);
            tr.setPage(page);
            table.appendKid(tr);
            for (int c = 0; c < 11; c++) {
                PDStructureElement cell = new PDStructureElement("TD", tr);
                cell.setPage(page);
                PDTableAttributeObject att = new PDTableAttributeObject();
                att.setColSpan(1000);
                cell.addAttribute(att);
                if (c == 0) cell.getCOSObject().setInt(COSName.K, 0); // only this cell carries text
                tr.appendKid(cell);
            }
            doc.save(file.toFile());
        }
    }

    /**
     * FIX B (round 3) reproducer: one Table element with 5,000 TRs. TR#0 carries 5 TD cells,
     * each with a ColSpan=1000 attribute (only the first carries an MCID, so the table clears
     * the "degenerate" all-cells-empty check); TR#1..4999 each carry a single plain (no span) TD.
     *
     * <p>Every cell's OWN rowSpan*colSpan area is small, so cumulativeArea stays at 5,000 (TR#0's
     * five 1000-wide cells) + 4,999 (one each for the remaining rows) = 9,999 -- under
     * MAX_CELLS_PER_TABLE (10,000), so the existing cumulative-area guard never trips. But the
     * CLUSTERED grid this places has 5,000 rows (one per TR) x 5,000 cols (TR#0's five
     * 1000-wide columns), a product of 25,000,000 -- the shape buildTaggedTable's grid-product
     * guard must reject.
     */
    static void taggedGridProductBomb(Path file) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                COSDictionary d = new COSDictionary();
                d.setInt(COSName.MCID, 0);
                cs.beginMarkedContent(COSName.getPDFName("TD"), PDPropertyList.create(d));
                text(cs, 60, 700, "X");
                cs.endMarkedContent();
            }

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement table = new PDStructureElement("Table", root);
            table.setPage(page);
            root.appendKid(table);

            PDStructureElement tr0 = new PDStructureElement("TR", table);
            tr0.setPage(page);
            table.appendKid(tr0);
            for (int c = 0; c < 5; c++) {
                PDStructureElement cell = new PDStructureElement("TD", tr0);
                cell.setPage(page);
                PDTableAttributeObject att = new PDTableAttributeObject();
                att.setColSpan(1000);
                cell.addAttribute(att);
                if (c == 0) cell.getCOSObject().setInt(COSName.K, 0); // only this cell carries text
                tr0.appendKid(cell);
            }

            for (int r = 1; r < 5_000; r++) {
                PDStructureElement tr = new PDStructureElement("TR", table);
                tr.setPage(page);
                table.appendKid(tr);
                PDStructureElement cell = new PDStructureElement("TD", tr);
                cell.setPage(page);
                tr.appendKid(cell); // no MCID -> textless, no span attribute -> 1x1
            }
            doc.save(file.toFile());
        }
    }

    /**
     * {@code tableCount} independent 1x1 Table elements (own TR/TD, own MCID text), all
     * attributed to a single page. Used to prove the tagged path's per-page table cap
     * (MAX_TABLES_PER_PAGE) is enforced and {@code Result.truncated} is set once exceeded.
     *
     * <p>Each cell's text is placed at a DISTINCT (x, y) offset (a wrapping grid): PDFBox's text
     * extraction drops a glyph as a duplicate-render artifact (faux-bold detection) when the same
     * character is drawn at the exact same position more than once, so drawing every "T" at an
     * identical spot -- as this fixture initially did -- silently loses the shared "T" prefix from
     * all but the first cell's text.
     */
    static void taggedManyTablesOnePage(Path file, int tableCount) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int i = 0; i < tableCount; i++) {
                    COSDictionary d = new COSDictionary();
                    d.setInt(COSName.MCID, i);
                    cs.beginMarkedContent(COSName.getPDFName("TD"), PDPropertyList.create(d));
                    text(cs, 60 + (i % 15) * 30, 700 - (i / 15) * 15, "T" + i);
                    cs.endMarkedContent();
                }
            }

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            for (int i = 0; i < tableCount; i++) {
                PDStructureElement table = new PDStructureElement("Table", root);
                table.setPage(page);
                root.appendKid(table);
                PDStructureElement tr = new PDStructureElement("TR", table);
                tr.setPage(page);
                table.appendKid(tr);
                PDStructureElement cell = new PDStructureElement("TD", tr);
                cell.setPage(page);
                cell.getCOSObject().setInt(COSName.K, i);
                tr.appendKid(cell);
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
