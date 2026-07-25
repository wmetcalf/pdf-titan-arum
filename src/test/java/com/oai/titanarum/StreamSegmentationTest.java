package com.oai.titanarum;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 9d: region-segmentation tests. {@code extractPage} previously treated an entire page as
 * ONE candidate table -- pooling every line (title/caption/footnote included) into a single
 * {@code findGutters}/{@code scoreGrid}/{@code buildHit} call. The dominant, measured consequence
 * (see {@code .superpowers/sdd/task-9c-diagnosis-report.md}) was that row 0 of the produced table
 * was the page's title/running-header text, not the table's real header row -- which, because
 * {@code TableScore} matches cells by exact (row, col, text), shifts every subsequent row index
 * and drives F1 toward 0. These tests exercise the new block-splitting (Step A), per-block
 * detection (Step B), and non-conforming-edge trimming (Step C) that fixes it.
 */
class StreamSegmentationTest {

    private static PDType1Font helv() { return new PDType1Font(Standard14Fonts.FontName.HELVETICA); }

    /**
     * Title + a clean 3-column numeric table + footnote, all on one page. The title/footnote
     * gaps (24pt) are deliberately kept BELOW the block-split threshold (1.6 x the in-table
     * pitch of 20pt = 32pt) -- i.e. small enough that Step A's vertical-gap block splitter does
     * NOT separate them from the table into their own blocks (median gap across all 6 line-to-
     * line transitions is 20, so the threshold is 32; the 24pt title/footnote gaps sit under
     * it). This means title + table + footnote land in ONE block, exactly like the real
     * us-020/us-007/eu-027 cases in the task-9c diagnosis report (single-spaced report pages
     * where the running header/caption sit close enough to the table that no big gap separates
     * them) -- so the regression can ONLY be fixed by Step C's edge-trimming, not by block
     * splitting alone. The title and footnote are each two words placed entirely inside column
     * 0's x-range (left-aligned, well short of the first gutter), while every real table row
     * occupies all 3 columns -- the "all words fall into a single column while the block's
     * other lines occupy 2+" non-conforming rule (Step C) is what must drop them.
     */
    @Test
    void pageTitleIsNotRow0() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 400));
            doc.addPage(page);
            PDType1Font f = helv();
            String[][] data = {
                {"Region", "Votes", "Pct"},
                {"North", "1200", "41.2"},
                {"South", "900", "30.9"},
                {"East", "450", "15.4"},
                {"West", "360", "12.5"}
            };
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(f, 11);
                float[] colX = {40, 150, 230};

                // Title: 2 words, both well inside column 0's x-range (< ~x=100, comfortably
                // left of where the col0/col1 gutter will land near x=110-130).
                float yTitle = 340;
                cs.beginText(); cs.newLineAtOffset(colX[0], yTitle); cs.showText("NET TOTAL"); cs.endText();

                // Table: header + 4 data rows, pitch 20pt, gap from title = 24pt (< 32 threshold).
                float yHeaderRow = yTitle - 24;
                for (int r = 0; r < data.length; r++) {
                    float y = yHeaderRow - r * 20;
                    for (int c = 0; c < 3; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(data[r][c]); cs.endText();
                    }
                }

                // Footnote: 2 words, also inside column 0's x-range, gap from last data row =
                // 24pt (< 32 threshold).
                float yLastRow = yHeaderRow - (data.length - 1) * 20;
                float yFooter = yLastRow - 24;
                cs.beginText(); cs.newLineAtOffset(colX[0], yFooter); cs.showText("SEE NOTES"); cs.endText();
            }
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<TableExtractor.TableHit> hits = StreamTableExtractor.extractPage(1, glyphs);
            assertEquals(1, hits.size(), "expected exactly one stream table (title/footnote trimmed, not separate tables)");
            TableExtractor.TableHit t = hits.get(0);
            assertEquals(3, t.colCount, "colCount must be the table's real 3 columns");
            assertEquals(5, t.rowCount, "rowCount must be the table's real 5 rows (header+4 data), NOT including title/footnote");
            assertEquals("Region", t.rows.get(0).get(0), "row 0 must be the table's header row, not the title");
            assertEquals("Votes", t.rows.get(0).get(1));
            assertEquals("North", t.rows.get(1).get(0), "row 1 must be the first real data row");
        }
    }

    /**
     * Two independent 3-column numeric tables on one page, separated by a large vertical gap
     * (120pt, far more than 1.6x the 20pt in-table pitch's ~32pt threshold) -- Step A's block
     * splitter must separate them into two blocks, and Step B must detect and score each one
     * independently, yielding 2 stream {@code TableHit}s (a page can legitimately contain
     * multiple borderless tables; the old whole-page pipeline could only ever emit one hit for
     * the entire page).
     */
    @Test
    void multipleTablesOnOnePageAreDetectedSeparately() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 500));
            doc.addPage(page);
            PDType1Font f = helv();
            String[][] tableA = {
                {"Region", "Votes", "Pct"},
                {"North", "1200", "41.2"},
                {"South", "900", "30.9"},
                {"East", "450", "15.4"},
                {"West", "360", "12.5"}
            };
            String[][] tableB = {
                {"Team", "Wins", "Losses"},
                {"Hawks", "44", "38"},
                {"Bulls", "39", "43"},
                {"Suns", "51", "31"}
            };
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(f, 11);
                float[] colX = {40, 150, 230};

                float yA = 440;
                for (int r = 0; r < tableA.length; r++) {
                    float y = yA - r * 20;
                    for (int c = 0; c < 3; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(tableA[r][c]); cs.endText();
                    }
                }
                float yALast = yA - (tableA.length - 1) * 20;
                float yB = yALast - 120; // large gap: forces a separate block
                for (int r = 0; r < tableB.length; r++) {
                    float y = yB - r * 20;
                    for (int c = 0; c < 3; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(tableB[r][c]); cs.endText();
                    }
                }
            }
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<TableExtractor.TableHit> hits = StreamTableExtractor.extractPage(1, glyphs);
            assertEquals(2, hits.size(), "expected 2 separately-detected stream tables");
            TableExtractor.TableHit first = hits.get(0);
            TableExtractor.TableHit second = hits.get(1);
            assertEquals(5, first.rowCount, "table A: header + 4 data rows");
            assertEquals(3, first.colCount);
            assertEquals("Region", first.rows.get(0).get(0));
            assertEquals(4, second.rowCount, "table B: header + 3 data rows");
            assertEquals(3, second.colCount);
            assertEquals("Team", second.rows.get(0).get(0));
        }
    }

    /**
     * Task 9m (block-merge): ONE 3-column table whose upper band (header + first two rows) is
     * separated from its lower band (four more rows) by a vertical gap of 40pt -- 2.0x the
     * table's own 20pt line pitch. That gap is OVER Step A's split threshold ({@link
     * StreamTableExtractor#BLOCK_GAP_FACTOR} = 1.6x -> 32pt) so Step A splits the single real
     * table into TWO blocks, and both halves are >=3 lines so both independently clear the
     * gridness gate -- yielding 2 stream {@code TableHit}s for what ground truth calls ONE
     * table. This is the measured dominant segmentation defect (us-002 p1/p3 = 5 blocks inside
     * one GT table, us-018 p4-p7 = 2 each, us-017 p2-p4 = 2 each, us-032 p1 = 5, us-001 p1 = 4;
     * 23 of the 163 geometry-carrying ICDAR GT tables are split across >1 block, and those 13
     * PDFs score adjacency F1 0.352 vs. 0.673 for the cleanly-segmented ones).
     * <p>The column x positions are IDENTICAL across the gap -- the column model is continuous,
     * which is exactly the evidence that the table continues -- and the gap is under {@link
     * StreamTableExtractor#BLOCK_MERGE_MAX_GAP_FACTOR} (2.5x), so Step A' must re-merge the two
     * blocks back into one hit covering all 7 physical rows.
     * <p>RED before Step A': {@code hits.size()} is 2 (rowCount 3 and 4), not 1 with rowCount 7.
     */
    @Test
    void tableSplitByInternalGapIsRemergedIntoOneHit() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 400));
            doc.addPage(page);
            PDType1Font f = helv();
            String[][] upper = {
                {"Region", "Votes", "Pct"},
                {"North", "1200", "41.2"},
                {"South", "900", "30.9"}
            };
            String[][] lower = {
                {"East", "450", "15.4"},
                {"West", "360", "12.5"},
                {"Alpha", "220", "7.5"},
                {"Beta", "110", "3.8"}
            };
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(f, 11);
                float[] colX = {40, 150, 230};
                float yTop = 340;
                for (int r = 0; r < upper.length; r++) {
                    float y = yTop - r * 20;
                    for (int c = 0; c < 3; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(upper[r][c]); cs.endText();
                    }
                }
                // 40pt gap = 2.0x the 20pt pitch: over Step A's 1.6x split threshold, under
                // Step A''s 2.5x merge cap.
                float yLower = yTop - (upper.length - 1) * 20 - 40;
                for (int r = 0; r < lower.length; r++) {
                    float y = yLower - r * 20;
                    for (int c = 0; c < 3; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(lower[r][c]); cs.endText();
                    }
                }
            }
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<TableExtractor.TableHit> hits = StreamTableExtractor.extractPage(1, glyphs);
            assertEquals(1, hits.size(), "one real table split by an internal gap must be re-merged into ONE hit, got "
                    + hits.stream().map(h -> h.rowCount + "x" + h.colCount).toList());
            TableExtractor.TableHit t = hits.get(0);
            assertEquals(3, t.colCount, "colCount must be the table's real 3 columns");
            assertEquals(7, t.rowCount, "the merged hit must cover every physical row (3 upper + 4 lower)");
            assertEquals("Region", t.rows.get(0).get(0));
            assertEquals("South", t.rows.get(2).get(0), "the last upper-band row");
            assertEquals("East", t.rows.get(3).get(0), "the first lower-band row must follow it in the SAME table");
            assertEquals("Beta", t.rows.get(6).get(0));
        }
    }

    /**
     * Task 9m (block-merge) boundary guard: the {@link #multipleTablesOnOnePageAreDetectedSeparately}
     * fixture with the inter-table gap tightened from 120pt to 60pt -- 3.0x the 20pt pitch, i.e.
     * still comfortably over Step A's 1.6x split threshold but JUST over Step A''s {@link
     * StreamTableExtractor#BLOCK_MERGE_MAX_GAP_FACTOR} (2.5x -> 50pt) merge cap. The two tables
     * share IDENTICAL column x positions, so the column-model test (merge condition 2) AGREES and
     * the merged block would still be a clean grid (condition 3 passes) -- the gap cap is the ONLY
     * thing keeping them apart. Without it, two genuinely separate sibling tables get fabricated
     * into one (measured: eu-025's 5 identical-column sibling tables lost 0.801 adjacency F1 under
     * an uncapped merge). The whole ICDAR corpus contains only 9 adjacent-block pairs that span two
     * DIFFERENT ground-truth tables, so the corpus cannot test this boundary -- this test is the guard.
     */
    @Test
    void siblingTablesJustOverTheGapCapStaySeparate() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 500));
            doc.addPage(page);
            PDType1Font f = helv();
            String[][] tableA = {
                {"Region", "Votes", "Pct"},
                {"North", "1200", "41.2"},
                {"South", "900", "30.9"},
                {"East", "450", "15.4"},
                {"West", "360", "12.5"}
            };
            String[][] tableB = {
                {"Team", "Wins", "Losses"},
                {"Hawks", "44", "38"},
                {"Bulls", "39", "43"},
                {"Suns", "51", "31"}
            };
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(f, 11);
                float[] colX = {40, 150, 230};
                float yA = 440;
                for (int r = 0; r < tableA.length; r++) {
                    float y = yA - r * 20;
                    for (int c = 0; c < 3; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(tableA[r][c]); cs.endText();
                    }
                }
                float yALast = yA - (tableA.length - 1) * 20;
                float yB = yALast - 60;   // 3.0x pitch: just over the 2.5x merge cap
                for (int r = 0; r < tableB.length; r++) {
                    float y = yB - r * 20;
                    for (int c = 0; c < 3; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(tableB[r][c]); cs.endText();
                    }
                }
            }
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<TableExtractor.TableHit> hits = StreamTableExtractor.extractPage(1, glyphs);
            assertEquals(2, hits.size(), "a gap just over the merge cap must NOT fabricate one table out of two, got "
                    + hits.stream().map(h -> h.rowCount + "x" + h.colCount).toList());
            assertEquals(5, hits.get(0).rowCount, "table A: header + 4 data rows");
            assertEquals("Region", hits.get(0).rows.get(0).get(0));
            assertEquals(4, hits.get(1).rowCount, "table B: header + 3 data rows");
            assertEquals("Team", hits.get(1).rows.get(0).get(0));
        }
    }

    /**
     * A page of only scattered prose "paragraphs" -- 4 separate 2-line blocks (each well below
     * the 3-line-per-block minimum from Step B), spaced far enough apart (100pt gaps vs. a
     * 15pt in-paragraph pitch, comfortably over the 1.6x-median-pitch split threshold) that
     * Step A correctly splits them into 4 distinct blocks, each of which must be rejected by
     * Step B's "at least 3 lines" floor. No stream table should be produced.
     */
    @Test
    void blockWithTooFewLinesIsRejected() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 600));
            doc.addPage(page);
            PDType1Font f = helv();
            String[][] paragraphs = {
                {"This is a simple prose line", "continuing on for a bit more"},
                {"Another short paragraph begins", "and wraps onto a second line"},
                {"A third little snippet starts", "then keeps going a while longer"},
                {"Finally a fourth paragraph here", "trails off after this second line"}
            };
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(f, 11);
                float y = 560;
                for (String[] para : paragraphs) {
                    for (String line : para) {
                        cs.beginText(); cs.newLineAtOffset(40, y); cs.showText(line); cs.endText();
                        y -= 15;
                    }
                    y -= 100; // large inter-paragraph gap
                }
            }
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<TableExtractor.TableHit> hits = StreamTableExtractor.extractPage(1, glyphs);
            assertEquals(0, hits.size(), "scattered prose paragraphs (each < 3 lines per block) must yield no stream tables");
        }
    }

    // ---------------------------------------------------------------- Task 9h: debris vs. header

    private static StreamTableExtractor.Word w(float x0, float x1, float y, String t) {
        StreamTableExtractor.Word wd = new StreamTableExtractor.Word();
        wd.x0 = x0; wd.x1 = x1; wd.y0 = y; wd.y1 = y + 10; wd.text = t;
        return wd;
    }

    private static StreamTableExtractor.Gutter gutter(float x0, float x1) {
        StreamTableExtractor.Gutter g = new StreamTableExtractor.Gutter();
        g.x0 = x0; g.x1 = x1;
        return g;
    }

    private static String concat(StreamTableExtractor.Line l) {
        StringBuilder sb = new StringBuilder();
        for (StreamTableExtractor.Word wd : l.words) { if (sb.length() > 0) sb.append(' '); sb.append(wd.text); }
        return sb.toString();
    }

    /**
     * Task 9h: two DIFFERENT kinds of leading debris glued to a clean 3-column table, each
     * requiring a different one of the strengthened {@code trimEdgeLines} signals (per the task
     * brief's "use several, don't rely on one") --
     * <ul>
     *   <li>a centered caption line ("ANNUAL SUMMARY") whose words fall entirely inside ONE
     *       column while the block's other lines occupy 2+ -- the pre-existing single-column
     *       signal, unchanged by this task, still catches this on its own;</li>
     *   <li>a short unit-caption line ("(in millions)") that populates 2 DIFFERENT columns
     *       (not single-column) and straddles no gutter (its words land cleanly inside existing
     *       column bands) -- exactly the shape that survived trimming before this task (see
     *       eu-004's real "(measured in hundreds)" line in the task-9h report). It is caught only
     *       by the NEW combined signal: it sits hard against a gap to the header (45pt, 2.25x the
     *       block's own 20pt median line pitch -- over {@link
     *       StreamTableExtractor#EDGE_LINE_GAP_OUTLIER_FACTOR}) AND has far fewer words (2) than
     *       the block's own median (3) -- {@link StreamTableExtractor#EDGE_FEW_WORDS_FACTOR}.</li>
     * </ul>
     * RED before this task's fix (with the new signal absent): the unit-caption line survived
     * trimming as a phantom row, so {@code trimmed.size()} was 5 (not 4) and {@code
     * trimmed.get(0)} was "(in millions)", not the real header.
     */
    @Test
    void captionDebrisIsTrimmedNotTurnedIntoRows() {
        List<StreamTableExtractor.Line> block = new ArrayList<>();
        List<StreamTableExtractor.Word> l0 = List.of(w(110, 142, 0, "ANNUAL"), w(144, 158, 0, "SUMMARY"));
        List<StreamTableExtractor.Word> l1 = List.of(w(10, 30, 20, "(in"), w(110, 145, 20, "millions)"));
        List<StreamTableExtractor.Word> header = List.of(w(10, 55, 65, "Region"), w(110, 150, 65, "Votes"), w(210, 240, 65, "Pct"));
        List<StreamTableExtractor.Word> d1 = List.of(w(10, 40, 85, "North"), w(110, 140, 85, "1200"), w(210, 235, 85, "41.2"));
        List<StreamTableExtractor.Word> d2 = List.of(w(10, 40, 105, "South"), w(110, 135, 105, "900"), w(210, 235, 105, "30.9"));
        List<StreamTableExtractor.Word> d3 = List.of(w(10, 35, 125, "East"), w(110, 135, 125, "450"), w(210, 235, 125, "15.4"));
        for (List<StreamTableExtractor.Word> ws : List.of(l0, l1, header, d1, d2, d3)) {
            StreamTableExtractor.Line ln = new StreamTableExtractor.Line();
            ln.yTop = ws.get(0).y0; ln.yBot = ws.get(0).y1;
            ln.words.addAll(ws);
            block.add(ln);
        }
        List<StreamTableExtractor.Gutter> gutters = List.of(gutter(60, 110), gutter(160, 210));

        List<StreamTableExtractor.Line> trimmed = StreamTableExtractor.trimEdgeLines(block, gutters, 10, 260, 6f);

        assertEquals(4, trimmed.size(), "both debris lines must be trimmed, leaving header + 3 data rows; got: "
                + trimmed.stream().map(StreamSegmentationTest::concat).toList());
        assertEquals("Region Votes Pct", concat(trimmed.get(0)), "row 0 must be the table's real header, not caption debris");
    }

    /**
     * Task 9h defect fix: a genuinely stacked/hierarchical 2-line header -- line 1 has two
     * merged/spanning group labels ("Domestic" over columns 1-2, "Foreign" over columns 3-4),
     * each individually CENTERED over its own multi-column span and so straddling that span's
     * own internal gutter (exactly us-018's real "Actual"/"Projected" shape: 2 words, each
     * straddling one gutter, each landing in a DIFFERENT column bucket); line 2 is the ordinary
     * per-column header ("Category","2020"..."2023", one word per column, no straddle). Neither
     * line is single-column (each populates 2+ distinct columns), and both sit at the block's
     * normal line pitch (no outlier gap) -- i.e. this is a clean table with NO debris at all.
     * {@code trimEdgeLines} must not remove either header line.
     * <p>RED before this task's fix: the unconditional "any word straddles a gutter" check had
     * no exemption for a column-shaped (wide-internal-gap) line, so line 1 ("Domestic Foreign")
     * was unconditionally non-conforming and got trimmed away -- {@code trimmed.size()} was 3
     * (not 4) and {@code trimmed.get(0)} was the per-column header line, not the stacked one.
     * This is the exact defect measured on us-018 page 1 (its own "Actual"/"Projected" line).
     */
    @Test
    void stackedHeaderRowsSurviveTrimming() {
        List<StreamTableExtractor.Line> block = new ArrayList<>();
        List<StreamTableExtractor.Word> line1 = List.of(w(125, 165, 0, "Domestic"), w(245, 285, 0, "Foreign"));
        List<StreamTableExtractor.Word> line2 = List.of(
                w(15, 55, 20, "Category"), w(115, 135, 20, "2020"), w(155, 175, 20, "2021"),
                w(235, 255, 20, "2022"), w(275, 295, 20, "2023"));
        List<StreamTableExtractor.Word> d1 = List.of(
                w(15, 55, 40, "Widget"), w(115, 135, 40, "12"), w(155, 175, 40, "8"),
                w(235, 255, 40, "5"), w(275, 295, 40, "3"));
        List<StreamTableExtractor.Word> d2 = List.of(
                w(15, 55, 60, "Gadget"), w(115, 135, 60, "9"), w(155, 175, 60, "7"),
                w(235, 255, 60, "4"), w(275, 295, 60, "2"));
        for (List<StreamTableExtractor.Word> ws : List.of(line1, line2, d1, d2)) {
            StreamTableExtractor.Line ln = new StreamTableExtractor.Line();
            ln.yTop = ws.get(0).y0; ln.yBot = ws.get(0).y1;
            ln.words.addAll(ws);
            block.add(ln);
        }
        List<StreamTableExtractor.Gutter> gutters = List.of(
                gutter(60, 110), gutter(140, 150), gutter(180, 230), gutter(260, 270));

        List<StreamTableExtractor.Line> trimmed = StreamTableExtractor.trimEdgeLines(block, gutters, 10, 300, 6f);

        assertEquals(4, trimmed.size(), "no line is debris -- nothing should be trimmed; got: "
                + trimmed.stream().map(StreamSegmentationTest::concat).toList());
        assertEquals("Domestic Foreign", concat(trimmed.get(0)), "the stacked/spanning header line must survive");
        assertEquals("Category 2020 2021 2022 2023", concat(trimmed.get(1)), "the per-column header line must survive");
    }

    // ------------------------------------------------------ Task 9m: Step A' merge-condition tests

    private static final float MS = 5f;    // medianSpace for the hand-built fixtures below
    private static final float MP = 20f;   // median line pitch for the same

    /** A word whose {@code numeric} flag is set explicitly (the {@link #w} helper leaves it false). */
    private static StreamTableExtractor.Word wnum(float x0, float x1, float y, String t, boolean numeric) {
        StreamTableExtractor.Word wd = w(x0, x1, y, t);
        wd.numeric = numeric;
        return wd;
    }

    private static StreamTableExtractor.Line lineOf(List<StreamTableExtractor.Word> ws) {
        StreamTableExtractor.Line ln = new StreamTableExtractor.Line();
        ln.yTop = ws.get(0).y0; ln.yBot = ws.get(0).y1;
        ln.words.addAll(ws);
        return ln;
    }

    /** Three rows of cells at the given x-slots, starting at {@code yTop} and stepping by {@link #MP}.
     *  {@code numericCol} (or -1 for none) marks which column's cells carry the numeric flag. */
    private static List<StreamTableExtractor.Line> rows(float yTop, int n, float[][] slots, int numericCol) {
        List<StreamTableExtractor.Line> out = new ArrayList<>();
        for (int r = 0; r < n; r++) {
            float y = yTop + r * MP;
            List<StreamTableExtractor.Word> ws = new ArrayList<>();
            for (int c = 0; c < slots.length; c++) {
                boolean num = c == numericCol;
                ws.add(wnum(slots[c][0], slots[c][1], y, num ? String.valueOf(1000 + r * 7 + c) : "Cell" + r + c, num));
            }
            out.add(lineOf(ws));
        }
        return out;
    }

    /** The gutters Step A' would find for a block, on the block's own band. */
    private static List<StreamTableExtractor.Gutter> guttersOf(List<StreamTableExtractor.Line> block) {
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE;
        for (StreamTableExtractor.Line l : block) for (StreamTableExtractor.Word wd : l.words) {
            x0 = Math.min(x0, wd.x0); x1 = Math.max(x1, wd.x1);
        }
        return StreamTableExtractor.findGutters(block, x0, x1, MS);
    }

    /** The confidence Step A''s merge probe would compute for a block (band -> gutters -> trim -> score). */
    private static double confidenceOf(List<StreamTableExtractor.Line> block) {
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE;
        for (StreamTableExtractor.Line l : block) for (StreamTableExtractor.Word wd : l.words) {
            x0 = Math.min(x0, wd.x0); x1 = Math.max(x1, wd.x1);
        }
        List<StreamTableExtractor.Gutter> g = StreamTableExtractor.findGutters(block, x0, x1, MS);
        List<StreamTableExtractor.Line> trimmed = StreamTableExtractor.trimEdgeLines(block, g, x0, x1, MS);
        return StreamTableExtractor.scoreGrid(trimmed, g, x0, x1).confidence;
    }

    private static List<StreamTableExtractor.Line> concatBlocks(List<StreamTableExtractor.Line> a,
                                                               List<StreamTableExtractor.Line> b) {
        List<StreamTableExtractor.Line> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /**
     * Task 9m, merge condition 2 in isolation: a 2-column block sitting 40pt (2.0x the 20pt
     * pitch -- comfortably INSIDE the 2.5x gap cap, so condition 1 passes) above a 4-column
     * block, with the 2-column block's single gutter centre nowhere near any of the 4-column
     * block's three. The column model is discontinuous across the gap, so the table does NOT
     * continue and Step A' must leave both blocks alone.
     * <p>Condition 2 is proved to be the rejecter, not conditions 1 or 3: the test asserts the
     * gap is under the cap AND that the merged block's own gridness confidence clears {@link
     * StreamTableExtractor#STREAM_CONFIDENCE_MIN} (so condition 3 would have let it through).
     * Without this test the corpus micro-F1 still rises (0.548) but the MACRO average collapses
     * to 0.489, i.e. BELOW the 0.500 baseline -- indiscriminate merging wins a few big files and
     * wrecks many small ones.
     */
    @Test
    void adjacentBlocksWithDifferentColumnModelsAreNotMerged() {
        // 2 columns; band 10..180, so its only gutter is the wide 60..130 gap (centre ~95).
        List<StreamTableExtractor.Line> twoCol =
                rows(0, 3, new float[][]{{10, 60}, {130, 180}}, 1);
        // 4 columns; band 10..240, gutters at centres ~65, ~125, ~185 -- none within the 10pt
        // (2 x medianSpace) tolerance of 95.
        List<StreamTableExtractor.Line> fourCol =
                rows(3 * MP + 40 - MP, 3, new float[][]{{10, 60}, {70, 120}, {130, 180}, {190, 240}}, 3);

        float gap = fourCol.get(0).yTop - twoCol.get(twoCol.size() - 1).yTop;
        assertEquals(40f, gap, 0.01f, "fixture sanity: the gap must be 2.0x the pitch");
        assertTrue(gap <= StreamTableExtractor.BLOCK_MERGE_MAX_GAP_FACTOR * MP,
                "merge condition 1 (gap cap) must PASS here, so it cannot be what rejects the merge");
        assertTrue(confidenceOf(concatBlocks(twoCol, fourCol)) >= StreamTableExtractor.STREAM_CONFIDENCE_MIN,
                "merge condition 3 (merged gridness) must PASS here, so it cannot be what rejects the merge");

        List<StreamTableExtractor.Gutter> gA = guttersOf(twoCol);
        List<StreamTableExtractor.Gutter> gB = guttersOf(fourCol);
        assertFalse(StreamTableExtractor.columnModelsAgree(
                        gA, gB, StreamTableExtractor.BLOCK_MERGE_GUTTER_TOL_FACTOR * MS),
                "the two column models must NOT agree; A centres="
                        + gA.stream().map(StreamTableExtractor.Gutter::cx).toList()
                        + " B centres=" + gB.stream().map(StreamTableExtractor.Gutter::cx).toList());

        List<StreamTableExtractor.BlockGroup> groups = StreamTableExtractor.mergeAgreeingBlocks(
                List.of(twoCol, fourCol), new BreuelGutterFinder(), MS, MP);
        assertEquals(2, groups.size(), "blocks with different column models must stay separate");
        assertEquals(3, groups.get(0).lines.size());
        assertEquals(3, groups.get(1).lines.size());
    }

    /**
     * Task 9m, merge condition 3 in isolation: two 3-column blocks with IDENTICAL column x
     * positions (so their gutter sets agree exactly -- condition 2 passes) at a 40pt / 2.0x-pitch
     * gap (condition 1 passes), each individually a perfectly valid table. They differ in ONE
     * respect: the numeric column is column 1 in the upper block and column 2 in the lower one.
     * Each block therefore has a numeric column of its own, which is conclusive proof it is a
     * data table and blocks {@code scoreGrid}'s prose hard veto. Their UNION has neither column
     * numeric (each is only 50% numeric, under the 70% bar), so the union is nothing but three
     * columns of full-width text -- the two-column-prose signature -- and the veto fires,
     * confidence 0. Step A' must therefore refuse the merge.
     * <p>The same geometry with the numeric column in the SAME place in both blocks (the control
     * at the end) DOES merge, which is what proves the numeric/veto difference -- i.e. condition
     * 3 -- is the only thing separating the two outcomes. On the real corpus this gate is worth
     * 0.018 micro-F1 and is what neutralises the over-merge canaries eu-020 (-0.212 -> 0.000),
     * us-025 (-0.179 -> +0.028) and eu-003 (-0.184 -> -0.001).
     */
    @Test
    void mergeIsRejectedWhenMergedBlockFailsGridnessGate() {
        // Tight 6pt gutters so each column's text fills >85% of its column width (the prose
        // fill signature); 3 columns, so a veto needs >50% of them prose-like -- all 3 are.
        float[][] slots = {{10, 60}, {66, 116}, {122, 172}};
        List<StreamTableExtractor.Line> upperNum1 = rows(0, 3, slots, 1);
        List<StreamTableExtractor.Line> lowerNum2 = rows(3 * MP + 40 - MP, 3, slots, 2);

        assertTrue(confidenceOf(upperNum1) >= StreamTableExtractor.STREAM_CONFIDENCE_MIN,
                "fixture sanity: the upper block alone must be a valid table, got " + confidenceOf(upperNum1));
        assertTrue(confidenceOf(lowerNum2) >= StreamTableExtractor.STREAM_CONFIDENCE_MIN,
                "fixture sanity: the lower block alone must be a valid table, got " + confidenceOf(lowerNum2));
        assertTrue(StreamTableExtractor.columnModelsAgree(guttersOf(upperNum1), guttersOf(lowerNum2),
                        StreamTableExtractor.BLOCK_MERGE_GUTTER_TOL_FACTOR * MS),
                "merge condition 2 (column models) must PASS here, so it cannot be what rejects the merge");
        assertTrue(confidenceOf(concatBlocks(upperNum1, lowerNum2)) < StreamTableExtractor.STREAM_CONFIDENCE_MIN,
                "fixture sanity: the union must fail the gridness gate, got "
                        + confidenceOf(concatBlocks(upperNum1, lowerNum2)));

        List<StreamTableExtractor.BlockGroup> rejected = StreamTableExtractor.mergeAgreeingBlocks(
                List.of(upperNum1, lowerNum2), new BreuelGutterFinder(), MS, MP);
        assertEquals(2, rejected.size(), "a merge whose union fails the gridness gate must be refused");

        // Control: same geometry, same gap, agreeing gutters -- but the numeric column is column 1
        // in BOTH blocks, so the union keeps a numeric column, the veto cannot fire, and the merge
        // is accepted. Only condition 3's outcome differs between the two cases.
        List<StreamTableExtractor.Line> lowerNum1 = rows(3 * MP + 40 - MP, 3, slots, 1);
        List<StreamTableExtractor.BlockGroup> accepted = StreamTableExtractor.mergeAgreeingBlocks(
                List.of(upperNum1, lowerNum1), new BreuelGutterFinder(), MS, MP);
        assertEquals(1, accepted.size(), "control: with the gridness gate satisfied the same two blocks DO merge");
        assertEquals(6, accepted.get(0).lines.size());
    }
}
