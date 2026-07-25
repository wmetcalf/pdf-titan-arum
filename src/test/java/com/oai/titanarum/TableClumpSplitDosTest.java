package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DoS bounds for the over-clumped-cell split pass ({@link TableExtractor#splitClumpedCells}).
 *
 * <p>The pass adds exactly two counted loops to the lattice path, both charged against ONE work
 * counter shared by every table on a page ({@link TableExtractor#MAX_CLUMP_SPLIT_WORK}):
 * <ol>
 *   <li>one (glyph -> candidate-union bbox) containment test per page glyph, per table that has any
 *       candidate at all; and</li>
 *   <li>one (candidate, word) containment test per pair.</li>
 * </ol>
 * Loop 2 is the quadratic-shaped one and is what these tests attack. On top of the budget there is a
 * hard candidate cap ({@link TableExtractor#MAX_CLUMP_CANDIDATES_PER_TABLE}).
 *
 * <p>MEASURED on the 77-PDF scoring corpus, replaying extractLatticePage's exact sequence and
 * reading the counter directly (ClumpWorkProbe): 15 pages charge anything at all, p50 = 2,465 units,
 * WORST PAGE = 6,051 units (eu-001 p1) = 0.076% of the budget, and the pass costs 19.0ms summed
 * over every page of every PDF in the corpus. The budget therefore sits ~1,300x above the worst real
 * page it has to let through.
 *
 * <p>The critical property, asserted in every test below: an abort NEVER costs the caller a table.
 * The pass is a post-process on an already-built {@link TableExtractor.TableHit}, so hitting the cap
 * or the budget must leave that table byte-for-byte as it would have been without this code -- a
 * hostile page must not be able to DELETE a table that ships today.
 */
class TableClumpSplitDosTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ fixtures

    /**
     * A synthetic lattice table with {@code clumps} clumped candidate cells (each spanning all
     * {@code cols} columns of its row) plus one fully-resolved row that pins every column's edges.
     * Built in code rather than from rulings because the point is to attack the split pass at cell
     * counts a single page's ruling geometry cannot reach (the 2pt endpoint snap caps a Letter page
     * at ~380 distinct horizontal rules).
     */
    private static TableExtractor.TableHit clumpedTable(int clumps, int cols, float colW) {
        TableExtractor.TableHit t = new TableExtractor.TableHit();
        t.page = 1;
        t.extractionMethod = "lattice";
        t.rowCount = clumps + 1;
        t.colCount = cols;
        t.cells = new ArrayList<>();
        // row 0: one single-column cell per column -> every column RESOLVED
        for (int c = 0; c < cols; c++) {
            TableExtractor.CellHit h = new TableExtractor.CellHit();
            h.row = 0; h.col = c; h.rowSpan = 1; h.colSpan = 1;
            h.text = "h" + c;
            h.bbox = new float[]{c * colW, 0f, (c + 1) * colW, 8f};
            t.cells.add(h);
        }
        for (int r = 1; r <= clumps; r++) {
            TableExtractor.CellHit h = new TableExtractor.CellHit();
            h.row = r; h.col = 0; h.rowSpan = 1; h.colSpan = cols;
            h.text = "1 2 3 4 5 6 7 8";
            h.bbox = new float[]{0f, 8f + (r - 1) * 1.5f, cols * colW, 8f + (r - 1) * 1.5f + 1.4f};
            t.cells.add(h);
        }
        t.bbox = new float[]{0f, 0f, cols * colW, 8f + clumps * 1.5f};
        return t;
    }

    /** A page carrying {@code rows x cols} tiny numeric text runs -- a real glyph/word bomb. */
    private static List<TextPosition> wordBomb(Path pdf, int rows, int cols) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 3);
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < cols; c++) {
                        cs.beginText();
                        cs.newLineAtOffset(4 + c * 6, 780 - r * 3.6f);
                        cs.showText("12");
                        cs.endText();
                    }
                }
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return TableTestPdfs.harvestGlyphs(doc, 0);
        }
    }

    private static List<String> snapshot(TableExtractor.TableHit t) {
        List<String> out = new ArrayList<>();
        for (TableExtractor.CellHit c : t.cells) out.add(c.row + "/" + c.col + "/" + c.colSpan + "/" + c.text);
        return out;
    }

    // ------------------------------------------------------------------ the candidate cap

    @Test
    void overTheCandidateCapTheTableIsLeftExactlyAsItWas() throws Exception {
        List<TextPosition> glyphs = wordBomb(tmp.resolve("bomb1.pdf"), 40, 40);
        TableExtractor.TableHit t =
                clumpedTable(TableExtractor.MAX_CLUMP_CANDIDATES_PER_TABLE + 1, 20, 15f);
        List<String> before = snapshot(t);
        long[] work = {0};
        long t0 = System.nanoTime();
        assertDoesNotThrow(() -> TableExtractor.splitClumpedCells(
                t, glyphs, 0, 612f, 792f, work, TableExtractor.MAX_CLUMP_SPLIT_WORK));
        double ms = (System.nanoTime() - t0) / 1e6;
        assertEquals(before, snapshot(t),
                "over the candidate cap the pass must change NOTHING, not partially split");
        assertEquals(0L, work[0],
                "the cap must be reached before any glyph is scanned, so no work is charged");
        System.out.printf("clump candidate cap: %d candidates rejected in %.1fms%n",
                TableExtractor.MAX_CLUMP_CANDIDATES_PER_TABLE + 1, ms);
        assertTrue(ms < 500, "cap rejection must be near-instant, took " + ms + "ms");
    }

    // ------------------------------------------------------------------ the work budget

    /**
     * Deterministic abort: the same worst-shape table and glyph bomb, run with a budget small enough
     * that the (candidate, word) loop cannot finish. The table must come back untouched.
     */
    @Test
    void exhaustingTheWorkBudgetLeavesTheTableUntouched() throws Exception {
        List<TextPosition> glyphs = wordBomb(tmp.resolve("bomb2.pdf"), 40, 40);
        TableExtractor.TableHit t =
                clumpedTable(TableExtractor.MAX_CLUMP_CANDIDATES_PER_TABLE, 20, 15f);
        List<String> before = snapshot(t);
        long[] work = {0};
        assertDoesNotThrow(() -> TableExtractor.splitClumpedCells(t, glyphs, 0, 612f, 792f, work, 100));
        assertEquals(before, snapshot(t),
                "a budget abort must leave the table exactly as it was -- never partially split");
        assertTrue(work[0] > 100, "the budget must actually have been charged: " + work[0]);
    }

    /**
     * The worst shape reachable at the PRODUCTION budget: the candidate cap crossed with a real
     * glyph bomb (20,000 numeric runs -> 40,000 glyphs -> ~10,000 words). MEASURED: 5,160,000 work
     * units, 64% of MAX_CLUMP_SPLIT_WORK, in ~56ms -- i.e. the production budget is set so that the
     * worst shape a single Letter page can actually carry stays inside it and still costs well under
     * a tenth of a second. The pass must not throw, must charge no more than the budget, and must
     * leave the table byte-for-byte intact (here it declines every candidate, because bomb words do
     * not reconstruct the candidates' own text -- the integrity check doing its job).
     */
    @Test
    void worstShapeAtTheProductionBudgetStaysBoundedAndKeepsTheTable() throws Exception {
        List<TextPosition> glyphs = wordBomb(tmp.resolve("bomb3.pdf"), 200, 100);
        TableExtractor.TableHit t =
                clumpedTable(TableExtractor.MAX_CLUMP_CANDIDATES_PER_TABLE, 20, 15f);
        List<String> before = snapshot(t);
        long[] work = {0};
        long t0 = System.nanoTime();
        assertDoesNotThrow(() -> TableExtractor.splitClumpedCells(
                t, glyphs, 0, 612f, 792f, work, TableExtractor.MAX_CLUMP_SPLIT_WORK));
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf("clump worst shape: %d glyphs x %d candidates -> work=%d (budget %d), "
                        + "%.1fms, cells %d -> %d%n",
                glyphs.size(), TableExtractor.MAX_CLUMP_CANDIDATES_PER_TABLE, work[0],
                TableExtractor.MAX_CLUMP_SPLIT_WORK, ms, before.size(), t.cells.size());
        assertTrue(work[0] <= TableExtractor.MAX_CLUMP_SPLIT_WORK,
                "work must never exceed the budget: " + work[0]);
        assertEquals(before, snapshot(t),
                "the table must survive the worst shape byte-for-byte");
        assertTrue(ms < 2000, "worst shape must stay inside a 2s ceiling, took " + ms + "ms");
    }

    /**
     * MAX_CELLS_PER_TABLE is enforced on the RESULT, not just on the input: a table whose clumps
     * WOULD legitimately split (every guard passes) but whose post-split cell count would exceed the
     * cap must be left alone wholesale, rather than handed over-cap to {@code renderViews}.
     *
     * <p>The fixture is derived FROM its own glyphs so the split really would succeed: a page of
     * {@code rows x cols} numbers is rendered, harvested, and each rendered line becomes one clumped
     * cell whose text is exactly that line's words -- i.e. the integrity check passes. Filler cells
     * then put the post-split total over the cap while the PRE-split total stays under it.
     */
    @Test
    void resultOverTheCellCapIsRejectedWholesale() throws Exception {
        int rows = 400, cols = 20;
        List<TextPosition> glyphs = numberGrid(tmp.resolve("grid4.pdf"), rows, cols);
        TableExtractor.TableHit t = clumpedTableFromGlyphs(glyphs, cols);
        int clumps = t.rowCount - 1;
        assertTrue(clumps > 300, "fixture needs many clumped rows, got " + clumps);
        // filler chosen so that PRE-split cells < cap and POST-split cells > cap
        int filler = TableExtractor.MAX_CELLS_PER_TABLE - clumps * cols + 200;
        assertTrue(filler > 0, "fixture arithmetic: filler=" + filler);
        for (int i = 0; i < filler; i++) {
            TableExtractor.CellHit h = new TableExtractor.CellHit();
            h.row = t.rowCount + i / cols; h.col = i % cols; h.rowSpan = 1; h.colSpan = 1;
            h.text = "x";
            h.bbox = new float[]{(i % cols) * 15f, 2000f + i, (i % cols) * 15f + 15f, 2001f + i};
            t.cells.add(h);
        }
        t.rowCount += filler / cols + 1;
        assertTrue(t.cells.size() < TableExtractor.MAX_CELLS_PER_TABLE,
                "PRE-split the fixture must be under the cap: " + t.cells.size());
        assertTrue(t.cells.size() - clumps + clumps * cols > TableExtractor.MAX_CELLS_PER_TABLE,
                "POST-split the fixture must be over the cap");
        List<String> before = snapshot(t);
        long[] work = {0};
        assertDoesNotThrow(() -> TableExtractor.splitClumpedCells(
                t, glyphs, 0, 612f, 792f, work, TableExtractor.MAX_CLUMP_SPLIT_WORK));
        assertEquals(before, snapshot(t),
                "a split whose RESULT would exceed MAX_CELLS_PER_TABLE must be abandoned entirely");
        assertTrue(t.cells.size() <= TableExtractor.MAX_CELLS_PER_TABLE,
                "cells must stay inside the cap: " + t.cells.size());
    }

    /**
     * Control for {@link #resultOverTheCellCapIsRejectedWholesale}: the SAME glyph-derived fixture
     * WITHOUT the filler really does split, proving that test's rejection is the cell cap talking and
     * not the fixture silently failing a guard.
     */
    @Test
    void glyphDerivedFixtureReallyDoesSplitWithoutTheFiller() throws Exception {
        int rows = 400, cols = 20;
        List<TextPosition> glyphs = numberGrid(tmp.resolve("grid5.pdf"), rows, cols);
        TableExtractor.TableHit t = clumpedTableFromGlyphs(glyphs, cols);
        int cellsBefore = t.cells.size();
        long[] work = {0};
        long t0 = System.nanoTime();
        assertDoesNotThrow(() -> TableExtractor.splitClumpedCells(
                t, glyphs, 0, 612f, 792f, work, TableExtractor.MAX_CLUMP_SPLIT_WORK));
        double ms = (System.nanoTime() - t0) / 1e6;
        System.out.printf("clump control: %d cells -> %d, work=%d, %.1fms%n",
                cellsBefore, t.cells.size(), work[0], ms);
        assertTrue(t.cells.size() > cellsBefore * 2,
                "the control fixture must genuinely split: " + cellsBefore + " -> " + t.cells.size());
    }

    /** A page of {@code rows x cols} distinct numbers, harvested back as real TextPositions. */
    private static List<TextPosition> numberGrid(Path pdf, int rows, int cols) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 1.4f);
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < cols; c++) {
                        cs.beginText();
                        cs.newLineAtOffset(6 + c * 30, 788 - r * 1.9f);
                        cs.showText(String.valueOf(100000 + r * cols + c));
                        cs.endText();
                    }
                }
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            return TableTestPdfs.harvestGlyphs(doc, 0);
        }
    }

    /**
     * Turn a harvested number grid into a synthetic lattice table: one RESOLVED single-column cell
     * per column (from the words' own x extents), then one clumped cell per rendered line whose text
     * is exactly that line's words joined by a space.
     */
    private static TableExtractor.TableHit clumpedTableFromGlyphs(List<TextPosition> glyphs, int cols) {
        List<StreamTableExtractor.Word> words = StreamTableExtractor.buildWords(glyphs);
        List<StreamTableExtractor.Line> lines =
                StreamTableExtractor.buildLines(words, StreamTableExtractor.medianFontSize(words));
        TableExtractor.TableHit t = new TableExtractor.TableHit();
        t.page = 1;
        t.extractionMethod = "lattice";
        t.colCount = cols;
        t.cells = new ArrayList<>();
        // column bands: the k-th word of the FIRST full line pins column k
        StreamTableExtractor.Line first = null;
        for (StreamTableExtractor.Line ln : lines) if (ln.words.size() == cols) { first = ln; break; }
        if (first == null) throw new IllegalStateException("no full line in fixture");
        List<StreamTableExtractor.Word> sorted = new ArrayList<>(first.words);
        sorted.sort((a, b) -> Float.compare(a.x0, b.x0));
        for (int c = 0; c < cols; c++) {
            StreamTableExtractor.Word w = sorted.get(c);
            TableExtractor.CellHit h = new TableExtractor.CellHit();
            h.row = 0; h.col = c; h.rowSpan = 1; h.colSpan = 1;
            h.text = w.text;
            h.bbox = new float[]{w.x0 - 1f, first.yTop, w.x1 + 1f, first.yBot};
            t.cells.add(h);
        }
        int row = 1;
        for (StreamTableExtractor.Line ln : lines) {
            if (ln == first || ln.words.size() < 2) continue;
            List<StreamTableExtractor.Word> ws = new ArrayList<>(ln.words);
            ws.sort((a, b) -> Float.compare(a.x0, b.x0));
            StringBuilder sb = new StringBuilder();
            float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE;
            for (StreamTableExtractor.Word w : ws) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(w.text);
                x0 = Math.min(x0, w.x0); x1 = Math.max(x1, w.x1);
            }
            TableExtractor.CellHit h = new TableExtractor.CellHit();
            h.row = row++; h.col = 0; h.rowSpan = 1; h.colSpan = cols;
            h.text = sb.toString();
            h.bbox = new float[]{x0 - 1f, ln.yTop, x1 + 1f, ln.yBot};
            t.cells.add(h);
        }
        t.rowCount = row;
        t.bbox = new float[]{0f, 0f, 612f, 792f};
        return t;
    }

    // ------------------------------------------------------------------ end to end

    /**
     * A LEGITIMATE large under-ruled table -- 85 data rows x 6 columns at a realistic 8pt row
     * pitch, interior verticals present only in the header band -- must complete end to end AND
     * actually get split, in EVERY data row. This is the "large legitimate table completes"
     * guarantee for the new pass: the budget must not be so tight that the real shape the fix exists
     * for gets abandoned.
     *
     * <p>The 8pt pitch is deliberate. Ruling endpoints snap to a 2pt grid (TableExtractor.SNAP), so
     * a sub-4pt row pitch makes the snapped bands alternate 2pt/4pt and stop being centred on their
     * own text; the word-to-cell assignment then disagrees with joinText's glyph bucketing and the
     * integrity check declines to split (MEASURED on a 3.4pt-pitch variant of this fixture: 21 of 200
     * rows split, the other 179 left exactly as they were). That is the intended conservative
     * behaviour on a degenerate shape, not the case this test is for.
     */
    @Test
    void largeLegitimateUnderRuledTableCompletesAndSplits() throws Exception {
        Path pdf = tmp.resolve("bigtotals.pdf");
        int rows = 85, cols = 6;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.4f);
                // The HEADER band must be at least MIN_RULING_LEN (8pt) tall, otherwise its interior
                // verticals are dropped as decoration and no column is ever resolved.
                float top = 776, head = 12f, pitch = 8f;
                float bot = top - head - rows * pitch;
                float right = 40 + cols * 80;
                TableTestPdfs.line(cs, 40, top, right, top);
                TableTestPdfs.line(cs, 40, top - head, right, top - head);
                for (int r = 1; r <= rows; r++) {
                    float y = top - head - r * pitch;
                    TableTestPdfs.line(cs, 40, y, right, y);
                }
                TableTestPdfs.line(cs, 40, top, 40, bot);
                TableTestPdfs.line(cs, right, top, right, bot);
                // interior verticals ONLY across the header band -> every data row is one wide cell
                for (int c = 1; c < cols; c++) {
                    TableTestPdfs.line(cs, 40 + c * 80, top, 40 + c * 80, top - head);
                }
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 3);
                for (int c = 0; c < cols; c++) {
                    cs.beginText();
                    cs.newLineAtOffset(44 + c * 80, top - 9);
                    cs.showText("c" + c);
                    cs.endText();
                }
                for (int r = 0; r < rows; r++) {
                    for (int c = 0; c < cols; c++) {
                        cs.beginText();
                        cs.newLineAtOffset(44 + c * 80, top - head - r * pitch - 6f);
                        cs.showText(String.valueOf(1000 + r * cols + c));
                        cs.endText();
                    }
                }
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            Map<Integer, List<TextPosition>> byPage = new HashMap<>();
            byPage.put(1, TableTestPdfs.harvestGlyphs(doc, 0));
            long t0 = System.nanoTime();
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), byPage);
            double ms = (System.nanoTime() - t0) / 1e6;
            assertFalse(r.tables.isEmpty(), "the large under-ruled table must still be extracted");
            TableExtractor.TableHit t = r.tables.get(0);
            int filled = 0;
            for (List<String> row : t.rows) {
                for (String c : row) if (!c.isEmpty()) filled++;
            }
            System.out.printf("large under-ruled table: %dx%d, %d cells, %d non-empty, %.1fms%n",
                    t.rowCount, t.colCount, t.cells.size(), filled, ms);
            // Without the split the whole body sits in column 0: at most rowCount non-empty cells
            // plus the header row. With it, each data row contributes one cell PER COLUMN.
            assertEquals(rows + 1, t.rowCount, "fixture must produce one row per ruled band");
            assertEquals(cols, t.colCount);
            assertEquals((rows + 1) * cols, filled,
                    "EVERY cell of the under-ruled body must have been distributed to its own "
                            + "column: non-empty=" + filled + " of " + ((rows + 1) * cols));
            assertTrue(ms < 5000, "extraction must stay inside a 5s ceiling, took " + ms + "ms");
        }
    }
}
