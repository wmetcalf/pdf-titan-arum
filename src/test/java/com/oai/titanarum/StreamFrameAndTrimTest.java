package com.oai.titanarum;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * COORDINATE FRAMES and the FOOTNOTE TRIM on the whitespace ("stream") path. Every test here was RED
 * against the code as shipped on this branch; each pins one defect that silently emitted wrong
 * geometry, or silently DELETED real table content:
 *
 * <ol>
 *   <li><b>F8, the y frame.</b> {@code buildWords} treated {@code TextPosition.getYDirAdj()} as the
 *       TOP of the glyph and added the height downward, but it is the glyph's BASELINE (it is
 *       {@code pageHeight - textMatrix.getTranslateY()}, and the text matrix' translate is the glyph
 *       origin). Every stream {@code CellHit}/{@code TableHit} bbox therefore sat roughly one glyph
 *       height BELOW the ink it described. The rest of the codebase already agrees on the right
 *       frame: {@code TableExtractor#fillCellsFromPositions} uses {@code getYDirAdj() -
 *       getHeightDir()/2} as the glyph MIDPOINT and {@code TableExtractor#resolveCellText} builds
 *       the tagged bbox as {@code [getYDirAdj()-h, getYDirAdj()]}.</li>
 *   <li><b>F9, the page's /Rotate.</b> {@code buildWords} reads the /Rotate-BLIND {@code
 *       getXDirAdj()/getYDirAdj()} and nothing mapped the result through {@code applyPageRotation},
 *       so on a rotated page stream boxes landed in a DIFFERENT frame than the lattice/tagged boxes
 *       beside them in the same report.json -- and {@code arbitrate}'s {@code contestsSameRegion},
 *       which is a bbox intersection ACROSS those two frames, saw no contest and emitted both
 *       contradictory answers for one region.</li>
 *   <li><b>F10, the footnote trim.</b> An ALL-CAPS {@code "WORD:"} first token was taken as proof
 *       that a line and everything after it is a footnote. On invoices and remittance advices --
 *       the population this tool triages -- {@code TOTAL:}, {@code VAT:}, {@code SUBTOTAL:} are how
 *       the most important ROWS are labelled, and the trim runs before {@code scoreGrid}/{@code
 *       buildHit}, so those rows never became cells and nothing set {@code truncated}.</li>
 *   <li><b>F12, empty declared columns.</b> The band comes from the UNTRIMMED block while the score
 *       is computed over the TRIMMED lines, so a column whose only content was trimmed away survived
 *       in {@code colBounds} with ZERO words -- inflating {@code colCount}, which {@code arbitrate}'s
 *       clause 5 reads as "stream resolved a column the rulings missed".</li>
 *   <li><b>F12, the rounded confidence at the arbitration gate.</b> {@code buildHit} rounds the
 *       reported confidence to 3 decimals and {@code arbitrate} compared THAT against the calibrated
 *       {@code ARB_MIN_STREAM_CONFIDENCE} = 0.65, making the effective floor 0.6495.</li>
 * </ol>
 */
class StreamFrameAndTrimTest {

    private static PDType1Font helv() { return new PDType1Font(Standard14Fonts.FontName.HELVETICA); }

    private static Map<Integer, List<TextPosition>> glyphsOf(PDDocument doc) throws Exception {
        Map<Integer, List<TextPosition>> out = new LinkedHashMap<>();
        for (int p = 1; p <= doc.getNumberOfPages(); p++) {
            out.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
        }
        return out;
    }

    private static List<Integer> pagesOf(PDDocument doc) {
        List<Integer> out = new ArrayList<>();
        for (int p = 1; p <= doc.getNumberOfPages(); p++) out.add(p);
        return out;
    }

    /** A 5x4 borderless numeric table, drawn at the given baselines in PDF user space. */
    private static void drawBorderlessTable(PDDocument doc, PDPage page, float[] colX,
                                            float topBaseline, float leading) throws IOException {
        String[][] rows = {
            {"Name", "Qty", "Price", "Total"},
            {"Widget", "2", "3.00", "6.00"},
            {"Gadget", "5", "1.50", "7.50"},
            {"Doodad", "9", "2.25", "20.25"},
            {"Gizmo", "4", "5.00", "20.00"},
        };
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            for (int r = 0; r < rows.length; r++) {
                for (int c = 0; c < rows[r].length; c++) {
                    cs.beginText();
                    cs.setFont(helv(), 10f);
                    cs.newLineAtOffset(colX[c], topBaseline - r * leading);
                    cs.showText(rows[r][c]);
                    cs.endText();
                }
            }
        }
    }

    // ------------------------------------------------------------------------ F8: the y frame

    /**
     * A stream cell's bbox must CONTAIN the ink of its own glyphs. The ink's vertical extent is taken
     * the way every other path in this codebase takes it -- {@code [getYDirAdj()-getHeightDir(),
     * getYDirAdj()]}, i.e. the same expression {@code TableExtractor#resolveCellText} uses for the
     * tagged bbox -- so this asserts frame AGREEMENT with the rest of the pipeline, not merely
     * self-consistency within the stream path.
     *
     * <p>RED before the fix: cell "Name" was reported at y=[50.00, 55.78] while its own glyphs
     * occupy y=[42.82, 50.00] -- a box almost entirely OUTSIDE the ink it describes.
     */
    @Test
    void streamCellBboxContainsItsOwnGlyphInk() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(400, 300));
            doc.addPage(page);
            drawBorderlessTable(doc, page, new float[]{30f, 150f, 220f, 300f}, 250f, 20f);

            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<TableExtractor.TableHit> hits = StreamTableExtractor.extractPage(1, glyphs);
            assertEquals(1, hits.size(), "fixture must yield exactly one stream table");
            TableExtractor.TableHit t = hits.get(0);
            assertFalse(t.cells.isEmpty(), "fixture must yield cells");

            // Every cell text in this fixture is UNIQUE across the whole table, so a cell's glyphs
            // can be located by exact contiguous-run match on the glyph list alone -- with no
            // reference to the stream path's own y arithmetic, which is what is under test.
            List<TextPosition> ink = new ArrayList<>();
            for (TextPosition tp : glyphs) {
                String u = tp.getUnicode();
                if (u != null && !u.isEmpty() && !u.trim().isEmpty()) ink.add(tp);
            }
            for (TableExtractor.CellHit cell : t.cells) {
                float[] extent = runInkExtent(ink, cell.text);
                assertNotNull(extent, "could not locate the glyphs of cell '" + cell.text + "'");
                assertTrue(cell.bbox[1] <= extent[0] + 0.01f && cell.bbox[3] >= extent[1] - 0.01f,
                        "cell '" + cell.text + "' bbox y=[" + cell.bbox[1] + ", " + cell.bbox[3]
                        + "] must contain its own glyphs' ink y=[" + extent[0] + ", " + extent[1] + "]");
            }
        }
    }

    /**
     * The stream frame must equal the TAGGED path's frame exactly, glyph for glyph -- the tagged bbox
     * expression ({@code TableExtractor#resolveCellText}) is the codebase's own statement of what a
     * text bbox is, so this pins the two together rather than restating one of them.
     */
    @Test
    void streamWordBoxAgreesWithTheTaggedPathsGlyphBox() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 200));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(helv(), 12f);
                cs.newLineAtOffset(20f, 150f);
                cs.showText("Alpha");
                cs.endText();
            }
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<StreamTableExtractor.Word> words = StreamTableExtractor.buildWords(glyphs);
            assertEquals(1, words.size(), "fixture must build exactly one word");
            StreamTableExtractor.Word w = words.get(0);

            float taggedTop = Float.MAX_VALUE, taggedBot = -Float.MAX_VALUE;
            for (TextPosition tp : glyphs) {
                taggedTop = Math.min(taggedTop, tp.getYDirAdj() - tp.getHeightDir());
                taggedBot = Math.max(taggedBot, tp.getYDirAdj());
            }
            assertEquals(taggedTop, w.y0, 0.001f,
                    "stream word top must equal the tagged path's getYDirAdj()-getHeightDir()");
            assertEquals(taggedBot, w.y1, 0.001f,
                    "stream word bottom must equal the tagged path's getYDirAdj() (the baseline)");
        }
    }

    // ------------------------------------------------------------------- F9: the page's /Rotate

    /**
     * On a {@code /Rotate 90} page the stream bbox must land inside the VISUAL page bounds, i.e. in
     * the same /Rotate-applied frame every lattice and tagged box in the same report.json lives in.
     * Driven through the PRODUCTION entry point ({@code TableExtractor.extract(..., streamTables)}),
     * because that is where the page's rotation is known.
     *
     * <p>RED before the fix: the stream bbox was [30.0, 50.0, 355.0, 135.8] on a page whose visual
     * width is 300 -- x1 alone was 55pt off the page.
     */
    @Test
    void streamBboxIsInTheVisualFrameOnARotatedPage() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(400, 300));   // unrotated: 400 wide, 300 tall
            page.setRotation(90);                                  // visual: 300 wide, 400 tall
            doc.addPage(page);
            drawBorderlessTable(doc, page, new float[]{30f, 150f, 240f, 330f}, 250f, 20f);

            TableExtractor.Result r = TableExtractor.extract(doc, pagesOf(doc), glyphsOf(doc), true);
            List<TableExtractor.TableHit> stream = new ArrayList<>();
            for (TableExtractor.TableHit t : r.tables) {
                if ("stream".equals(t.extractionMethod)) stream.add(t);
            }
            assertEquals(1, stream.size(), "fixture must yield exactly one stream table");
            float[] b = stream.get(0).bbox;
            assertNotNull(b);
            float visualW = 300f, visualH = 400f;                  // /Rotate 90 swaps the axes
            assertTrue(b[0] >= -0.5f && b[2] <= visualW + 0.5f,
                    "stream bbox x range " + Arrays.toString(b)
                    + " must be inside the VISUAL page width " + visualW);
            assertTrue(b[1] >= -0.5f && b[3] <= visualH + 0.5f,
                    "stream bbox y range " + Arrays.toString(b)
                    + " must be inside the VISUAL page height " + visualH);
        }
    }

    /**
     * ATTACKING THE FIX. The frame plumbing must hold for every legal /Rotate, not just 90 -- 180 maps
     * both axes and 270 swaps them the other way, so a sign error in either arm would put the box off
     * the page in a direction the /Rotate-90 test cannot see.
     */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 90, 180, 270})
    void streamBboxIsInTheVisualFrameAtEveryLegalRotation(int rotation) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(400, 300));
            page.setRotation(rotation);
            doc.addPage(page);
            drawBorderlessTable(doc, page, new float[]{30f, 150f, 240f, 330f}, 250f, 20f);

            TableExtractor.Result r = TableExtractor.extract(doc, pagesOf(doc), glyphsOf(doc), true);
            List<TableExtractor.TableHit> stream = new ArrayList<>();
            for (TableExtractor.TableHit t : r.tables) {
                if ("stream".equals(t.extractionMethod)) stream.add(t);
            }
            assertEquals(1, stream.size(), "fixture must yield exactly one stream table at /Rotate " + rotation);
            float[] b = stream.get(0).bbox;
            boolean swapped = rotation == 90 || rotation == 270;
            float visualW = swapped ? 300f : 400f, visualH = swapped ? 400f : 300f;
            assertTrue(b[0] >= -0.5f && b[2] <= visualW + 0.5f && b[1] >= -0.5f && b[3] <= visualH + 0.5f,
                    "/Rotate " + rotation + ": bbox " + Arrays.toString(b)
                    + " must be inside the visual page " + visualW + "x" + visualH);
            assertTrue(b[0] < b[2] && b[1] < b[3],
                    "/Rotate " + rotation + ": bbox " + Arrays.toString(b) + " must stay normalised");
        }
    }

    /**
     * ATTACKING THE FIX. A hostile /Rotate the transform has no arm for must degrade to the identity
     * frame -- never to a thrown exception, and never to a lost table. {@code extract} must not throw,
     * per this class's own hard invariant.
     */
    @Test
    void aRotationTheTransformHasNoArmForStillYieldsTheTable() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(400, 300));
            page.setRotation(45);                              // not a multiple of 90
            doc.addPage(page);
            drawBorderlessTable(doc, page, new float[]{30f, 150f, 220f, 300f}, 250f, 20f);

            TableExtractor.Result r = TableExtractor.extract(doc, pagesOf(doc), glyphsOf(doc), true);
            long stream = r.tables.stream().filter(t -> "stream".equals(t.extractionMethod)).count();
            assertEquals(1, stream, "an unsupported /Rotate must not cost the page its table");
        }
    }

    /**
     * ATTACKING THE FIX. Dropping an empty declared column must merge it into a NEIGHBOUR, never move
     * a boundary -- so no word may change column index relative to the columns that survive. Checked
     * by comparing each word's surviving-column assignment against the order of the words themselves:
     * the words of column k must all still share one column, and those columns must be in x order.
     */
    @Test
    void droppingAnEmptyColumnNeverMovesAWordIntoAnotherColumn() {
        List<StreamTableExtractor.Line> lines = new ArrayList<>();
        // Populated at ~30, ~150, ~240; empty bands declared at BOTH ends and in the middle.
        float y = 50f;
        for (int i = 0; i < 4; i++) {
            StreamTableExtractor.Line l = new StreamTableExtractor.Line();
            l.yTop = y; l.yBot = y + 8f;
            l.words.add(word(30, 80, y, "A" + i));
            l.words.add(word(150, 180, y, "B" + i));
            l.words.add(word(240, 275, y, "C" + i));
            lines.add(l);
            y += 20f;
        }
        List<StreamTableExtractor.Gutter> gutters = List.of(
                gutter(100, 140, 4),    // real: between A and B
                gutter(200, 215, 4),    // real-ish: between B and C
                gutter(216, 230, 4),    // spurious: makes an EMPTY band 215..216 ... 230
                gutter(280, 330, 4),    // spurious: makes an EMPTY trailing band
                gutter(340, 360, 4));   // spurious: makes a second EMPTY trailing band
        List<StreamTableExtractor.Gutter> kept =
                StreamTableExtractor.dropEmptyColumns(lines, gutters, 30f, 380f);

        float[] bounds = new float[kept.size() + 2];
        bounds[0] = 30f; bounds[bounds.length - 1] = 380f;
        for (int i = 0; i < kept.size(); i++) bounds[i + 1] = kept.get(i).cx();
        int cols = bounds.length - 1;
        int[] counts = new int[cols];
        List<Integer> colOfA = new ArrayList<>(), colOfB = new ArrayList<>(), colOfC = new ArrayList<>();
        for (StreamTableExtractor.Line l : lines) {
            for (StreamTableExtractor.Word w : l.words) {
                int c = cols - 1;
                for (int i = 0; i < cols; i++) if (w.cx() < bounds[i + 1]) { c = i; break; }
                counts[c]++;
                (w.text.startsWith("A") ? colOfA : w.text.startsWith("B") ? colOfB : colOfC).add(c);
            }
        }
        for (int c = 0; c < cols; c++) {
            assertTrue(counts[c] > 0, "column " + c + " of " + cols + " is empty; bounds="
                    + Arrays.toString(bounds) + " counts=" + Arrays.toString(counts));
        }
        assertEquals(1, new java.util.HashSet<>(colOfA).size(), "every A word must share one column");
        assertEquals(1, new java.util.HashSet<>(colOfB).size(), "every B word must share one column");
        assertEquals(1, new java.util.HashSet<>(colOfC).size(), "every C word must share one column");
        assertTrue(colOfA.get(0) < colOfB.get(0) && colOfB.get(0) < colOfC.get(0),
                "column order must be preserved: A=" + colOfA.get(0) + " B=" + colOfB.get(0)
                + " C=" + colOfC.get(0) + " bounds=" + Arrays.toString(bounds));
    }

    /**
     * The frame is load-bearing, not cosmetic: a lattice answer and a stream answer to the SAME
     * region of a {@code /Rotate 90} page must CONTEST, so exactly one of them survives arbitration.
     * The "lattice" side is built from the very same glyphs through {@code applyPageRotation} -- the
     * transform the lattice and tagged paths actually use -- so this test cannot pass by both sides
     * being wrong in the same way.
     *
     * <p>RED before the fix: {@code arbitrate} kept BOTH candidates (2 survivors), emitting two
     * contradictory answers for one region.
     */
    @Test
    void sameRegionLatticeAndStreamContestOnARotatedPage() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(400, 300));
            page.setRotation(90);
            doc.addPage(page);
            drawBorderlessTable(doc, page, new float[]{30f, 150f, 240f, 330f}, 250f, 20f);

            TableExtractor.Result r = TableExtractor.extract(doc, pagesOf(doc), glyphsOf(doc), true);
            TableExtractor.TableHit s = null;
            for (TableExtractor.TableHit t : r.tables) if ("stream".equals(t.extractionMethod)) s = t;
            assertNotNull(s, "fixture must yield a stream table");

            // The same region, in the frame the lattice/tagged paths put everything in.
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (TextPosition tp : TableTestPdfs.harvestGlyphs(doc, 0)) {
                String u = tp.getUnicode();
                if (u == null || u.isEmpty() || u.trim().isEmpty()) continue;
                float[] p1 = TableExtractor.applyPageRotation(
                        tp.getXDirAdj(), tp.getYDirAdj() - tp.getHeightDir(), 90, 400f, 300f);
                float[] p2 = TableExtractor.applyPageRotation(
                        tp.getXDirAdj() + tp.getWidthDirAdj(), tp.getYDirAdj(), 90, 400f, 300f);
                x0 = Math.min(x0, Math.min(p1[0], p2[0])); x1 = Math.max(x1, Math.max(p1[0], p2[0]));
                y0 = Math.min(y0, Math.min(p1[1], p2[1])); y1 = Math.max(y1, Math.max(p1[1], p2[1]));
            }
            TableExtractor.TableHit lattice = new TableExtractor.TableHit();
            lattice.extractionMethod = "lattice";
            lattice.page = 1;
            lattice.bbox = new float[]{x0, y0, x1, y1};
            lattice.rowCount = 5; lattice.colCount = 4;
            lattice.cells = new ArrayList<>();
            for (int i = 0; i < 20; i++) {                          // fully occupied 5x4
                TableExtractor.CellHit c = new TableExtractor.CellHit();
                c.row = i / 4; c.col = i % 4; c.rowSpan = 1; c.colSpan = 1; c.text = "x";
                c.bbox = lattice.bbox.clone();
                lattice.cells.add(c);
            }
            List<TableExtractor.TableHit> kept =
                    TableExtractor.arbitrate(List.of(lattice), List.of(s));
            assertEquals(1, kept.size(),
                    "a lattice and a stream answer to the SAME region of a rotated page must contest; "
                    + "kept " + kept.size() + " candidates (lattice bbox " + Arrays.toString(lattice.bbox)
                    + ", stream bbox " + Arrays.toString(s.bbox) + ")");
        }
    }

    // -------------------------------------------------------------------- F10: the footnote trim

    /**
     * An invoice whose last two rows are labelled {@code TOTAL:} and {@code VAT:} must keep them.
     * These are the values a phishing-invoice triage analyst most wants, and the trim ran BEFORE
     * {@code buildHit}, so they were not merely mis-placed -- they were absent from report.json with
     * nothing flagging the loss.
     *
     * <p>RED before the fix: a 7-row invoice emitted a 5x4 table; {@code TOTAL:, 77, 283.75} and
     * {@code VAT:, 56.75} were on the page and absent from the output.
     */
    @Test
    void invoiceTotalsRowLabelledTotalSurvivesTheFootnoteTrim() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(400, 300));
            doc.addPage(page);
            String[][] rows = {
                {"Item", "Qty", "Unit", "Amount"},
                {"Widget", "12", "3.00", "36.00"},
                {"Gadget", "5", "1.50", "7.50"},
                {"Doodad", "9", "2.25", "20.25"},
                {"Gizmo", "44", "5.00", "220.00"},
                {"TOTAL:", "77", "", "283.75"},
                {"VAT:", "", "", "56.75"},
            };
            float[] colX = {30f, 150f, 220f, 300f};
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int r = 0; r < rows.length; r++) {
                    for (int c = 0; c < rows[r].length; c++) {
                        if (rows[r][c].isEmpty()) continue;
                        cs.beginText();
                        cs.setFont(helv(), 10f);
                        cs.newLineAtOffset(colX[c], 250f - r * 20f);
                        cs.showText(rows[r][c]);
                        cs.endText();
                    }
                }
            }
            List<TableExtractor.TableHit> hits =
                    StreamTableExtractor.extractPage(1, TableTestPdfs.harvestGlyphs(doc, 0));
            assertEquals(1, hits.size(), "fixture must yield exactly one stream table");
            TableExtractor.TableHit t = hits.get(0);
            String all = t.rows.toString();
            assertTrue(all.contains("TOTAL:"), "the TOTAL: row must survive; got " + all);
            assertTrue(all.contains("283.75"), "the TOTAL: row's amount must survive; got " + all);
            assertTrue(all.contains("VAT:"), "the VAT: row must survive; got " + all);
            assertTrue(all.contains("56.75"), "the VAT: row's amount must survive; got " + all);
            assertEquals(7, t.rowCount, "all 7 drawn rows must be present; got " + all);
        }
    }

    /**
     * The GENUINE case the marker rule exists for must still work: a real footnote block, introduced
     * by an ALL-CAPS marker and running as continuous prose to the end of the block, is still cut in
     * one decisive move. GREEN both before and after -- this is the regression guard on the fix
     * above, and the shape of the corpus lines the rule was calibrated on (us-007's "KEY:" legend and
     * us-018's "NOTE:" block).
     */
    @Test
    void genuineProseFootnoteBlockIsStillTrimmed() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(400, 300));
            doc.addPage(page);
            String[][] rows = {
                {"Item", "Qty", "Unit", "Amount"},
                {"Widget", "12", "3.00", "36.00"},
                {"Gadget", "5", "1.50", "7.50"},
                {"Doodad", "9", "2.25", "20.25"},
                {"Gizmo", "44", "5.00", "220.00"},
            };
            float[] colX = {30f, 150f, 220f, 300f};
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int r = 0; r < rows.length; r++) {
                    for (int c = 0; c < rows[r].length; c++) {
                        cs.beginText();
                        cs.setFont(helv(), 10f);
                        cs.newLineAtOffset(colX[c], 250f - r * 20f);
                        cs.showText(rows[r][c]);
                        cs.endText();
                    }
                }
                // Two lines of continuous footnote prose, ordinary word spacing throughout, at the
                // block's own row pitch so Step A keeps them in the SAME block as the table.
                cs.beginText();
                cs.setFont(helv(), 10f);
                cs.newLineAtOffset(colX[0], 250f - 5 * 20f);
                cs.showText("NOTE: some figures have been revised from previously published totals.");
                cs.endText();
                cs.beginText();
                cs.setFont(helv(), 10f);
                cs.newLineAtOffset(colX[0], 250f - 6 * 20f);
                cs.showText("Calculations are based on unrounded numbers throughout this table.");
                cs.endText();
            }
            List<TableExtractor.TableHit> hits =
                    StreamTableExtractor.extractPage(1, TableTestPdfs.harvestGlyphs(doc, 0));
            assertEquals(1, hits.size(), "fixture must yield exactly one stream table");
            TableExtractor.TableHit t = hits.get(0);
            String all = t.rows.toString();
            assertFalse(all.contains("NOTE:"),
                    "a genuine prose footnote block must still be trimmed; got " + all);
            assertFalse(all.contains("unrounded"),
                    "every line after the footnote marker must be trimmed too; got " + all);
            assertEquals(5, t.rowCount, "the table's own 5 rows must remain; got " + all);
        }
    }

    /**
     * Unit-level statement of the shape rule, so the discriminator itself is pinned independently of
     * any one PDF fixture: a row of cells (wide inter-cell gaps) is never a footnote, a bare marker
     * and a real prose run both are, and a two-word "label: value" pair is not.
     */
    @Test
    void footnoteShapeRuleSeparatesRowsFromProse() {
        float medianSpace = 3.5f;                          // ~10pt type; the 8x gate is ~28pt
        assertFalse(StreamFrameAndTrimTest.isFootnote(medianSpace,
                        new String[]{"TOTAL:", "77", "283.75"}, new float[]{30, 150, 300},
                        new float[]{62, 165, 330}),
                "a row of cells with gutter-wide gaps is not a footnote");
        assertFalse(StreamFrameAndTrimTest.isFootnote(medianSpace,
                        new String[]{"PH:", "281-219-0465"}, new float[]{72, 90},
                        new float[]{88, 153}),
                "a two-word label/value pair is not a footnote");
        assertTrue(StreamFrameAndTrimTest.isFootnote(medianSpace,
                        new String[]{"KEY:"}, new float[]{72}, new float[]{92}),
                "a bare ALL-CAPS marker on its own line is a footnote start");
        assertTrue(StreamFrameAndTrimTest.isFootnote(medianSpace,
                        new String[]{"NOTE:", "Detail", "may", "not", "sum", "to", "100", "percent"},
                        new float[]{36, 62, 85, 103, 122, 142, 158, 175},
                        new float[]{60, 83, 101, 120, 140, 156, 173, 205}),
                "a continuous prose run is a footnote start -- including one with a number in it");
    }

    private static boolean isFootnote(float medianSpace, String[] text, float[] x0, float[] x1) {
        StreamTableExtractor.Line l = new StreamTableExtractor.Line();
        l.yTop = 100f; l.yBot = 110f;
        for (int i = 0; i < text.length; i++) {
            StreamTableExtractor.Word w = new StreamTableExtractor.Word();
            w.x0 = x0[i]; w.x1 = x1[i]; w.y0 = 100f; w.y1 = 110f; w.text = text[i];
            w.numeric = StreamTableExtractor.isNumericToken(text[i]);
            l.words.add(w);
        }
        return StreamTableExtractor.isFootnoteShapedLine(l, medianSpace);
    }

    // ------------------------------------------------------- F12: empty columns + rounded gate

    /**
     * A declared column with no words in it must not survive into {@code colBounds}. The fixture is
     * the exact shape {@code extractPage} produces: the band came from the UNTRIMMED block (a trimmed
     * edge line reached out to x=380) while the score is computed over the three populated columns.
     *
     * <p>RED before the fix: {@code cols=4}, confidence 0.950, and {@code arbitrate} then handed the
     * region to that stream candidate, dropping a complete, fully-ruled 3-column lattice table.
     */
    @Test
    void declaredColumnWithNoWordsIsDropped() {
        List<StreamTableExtractor.Line> lines = new ArrayList<>();
        String[][] data = {
            {"Widget", "12", "3.00"},
            {"Gadget", "5", "1.50"},
            {"Doodad", "9", "2.25"},
            {"Gizmo", "44", "5.00"},
        };
        float y = 50f;
        for (String[] row : data) {
            StreamTableExtractor.Line l = new StreamTableExtractor.Line();
            l.yTop = y; l.yBot = y + 8f;
            l.words.add(word(30, 80, y, row[0]));
            l.words.add(word(150, 180, y, row[1]));
            l.words.add(word(240, 275, y, row[2]));
            lines.add(l);
            y += 20f;
        }
        // Gutters at 100-140, 200-230 and -- the offender -- 280-330, whose right-hand column has no
        // word in it at all because the only line that reached out there was trimmed away.
        List<StreamTableExtractor.Gutter> gutters =
                List.of(gutter(100, 140, 4), gutter(200, 230, 4), gutter(280, 330, 4));

        StreamTableExtractor.Grid grid = StreamTableExtractor.scoreGrid(lines, gutters, 30f, 380f);
        int cols = grid.colBounds.length - 1;
        int[] counts = new int[cols];
        for (StreamTableExtractor.Line l : lines) {
            for (StreamTableExtractor.Word w : l.words) {
                int c = cols - 1;
                for (int i = 0; i < cols; i++) if (w.cx() < grid.colBounds[i + 1]) { c = i; break; }
                counts[c]++;
            }
        }
        for (int c = 0; c < cols; c++) {
            assertTrue(counts[c] > 0, "declared column " + c + " of " + cols
                    + " has no words; colBounds=" + Arrays.toString(grid.colBounds)
                    + " counts=" + Arrays.toString(counts));
        }
        assertEquals(3, cols, "only the three POPULATED columns may be declared; colBounds="
                + Arrays.toString(grid.colBounds));
    }

    /** A stream candidate whose EXACT confidence is below the calibrated arbitration floor must not
     *  displace drawn rulings just because rounding to 3 decimals lifted it onto the bar.
     *
     *  <p>RED before the fix: the effective floor was 0.6495, so this 0.6496 candidate took the
     *  region. */
    @Test
    void arbitrationConfidenceFloorUsesTheUnroundedConfidence() {
        TableExtractor.TableHit lattice = new TableExtractor.TableHit();
        lattice.extractionMethod = "lattice";
        lattice.page = 1;
        lattice.bbox = new float[]{30, 50, 280, 130};
        lattice.rowCount = 4; lattice.colCount = 3;
        lattice.cells = new ArrayList<>();
        for (int i = 0; i < 6; i++) {                    // occupancy 6/12 = 0.50 < ARB_MIN_GRID_OCCUPANCY
            TableExtractor.CellHit c = new TableExtractor.CellHit();
            c.row = i / 3; c.col = i % 3; c.rowSpan = 1; c.colSpan = 1; c.text = "x";
            c.bbox = lattice.bbox.clone();
            lattice.cells.add(c);
        }
        TableExtractor.TableHit stream = new TableExtractor.TableHit();
        stream.extractionMethod = "stream";
        stream.page = 1;
        stream.bbox = new float[]{30, 50, 275, 130};
        stream.rowCount = 4; stream.colCount = 3;
        stream.confidence = Math.round(0.6496 * 1000.0) / 1000.0;   // 0.650, as report.json shows it
        stream.confidenceUnrounded = 0.6496;                        // the value the gate must read
        stream.cells = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            TableExtractor.CellHit c = new TableExtractor.CellHit();
            c.row = i / 3; c.col = i % 3; c.rowSpan = 1; c.colSpan = 1; c.text = "x";
            c.bbox = stream.bbox.clone();
            stream.cells.add(c);
        }
        assertEquals(0.65, stream.confidence, 1e-9, "fixture must round onto the bar");
        assertTrue(stream.confidenceUnrounded < TableExtractor.ARB_MIN_STREAM_CONFIDENCE,
                "fixture's exact confidence must be below the bar");

        List<TableExtractor.TableHit> kept =
                TableExtractor.arbitrate(List.of(lattice), List.of(stream));
        assertEquals(1, kept.size(), "one candidate must win this contested region");
        assertEquals("lattice", kept.get(0).extractionMethod,
                "a stream candidate whose exact confidence is under the calibrated floor must not "
                + "displace the drawn rulings");
    }

    /**
     * The vertical ink extent {@code [top, bottom]} of the FIRST contiguous run of glyphs whose
     * unicode concatenation equals {@code text}, in the same frame the tagged path uses
     * ({@code [getYDirAdj()-getHeightDir(), getYDirAdj()]}), or null when there is no such run.
     */
    private static float[] runInkExtent(List<TextPosition> ink, String text) {
        for (int i = 0; i < ink.size(); i++) {
            StringBuilder sb = new StringBuilder();
            float top = Float.MAX_VALUE, bot = -Float.MAX_VALUE;
            for (int j = i; j < ink.size() && sb.length() < text.length(); j++) {
                sb.append(ink.get(j).getUnicode());
                top = Math.min(top, ink.get(j).getYDirAdj() - ink.get(j).getHeightDir());
                bot = Math.max(bot, ink.get(j).getYDirAdj());
                if (sb.length() == text.length() && sb.toString().equals(text)) {
                    return new float[]{top, bot};
                }
            }
        }
        return null;
    }

    private static StreamTableExtractor.Word word(float x0, float x1, float yTop, String text) {
        StreamTableExtractor.Word w = new StreamTableExtractor.Word();
        w.x0 = x0; w.x1 = x1; w.y0 = yTop; w.y1 = yTop + 8f; w.text = text;
        w.numeric = StreamTableExtractor.isNumericToken(text);
        return w;
    }

    private static StreamTableExtractor.Gutter gutter(float x0, float x1, int rows) {
        StreamTableExtractor.Gutter g = new StreamTableExtractor.Gutter();
        g.x0 = x0; g.x1 = x1; g.rowsCovered = rows;
        return g;
    }
}
