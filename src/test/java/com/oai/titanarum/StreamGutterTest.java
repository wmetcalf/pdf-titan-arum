package com.oai.titanarum;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamGutterTest {

    private static StreamTableExtractor.Word w(float x0, float x1, float yTop, String t) {
        StreamTableExtractor.Word wd = new StreamTableExtractor.Word();
        wd.x0=x0; wd.x1=x1; wd.y0=yTop; wd.y1=yTop+10; wd.text=t;
        wd.numeric = t.matches("[-+(]?[\\d.,%$)]+");
        return wd;
    }

    /** 3-column numeric grid: label | col1 | col2, clear gutters at ~x=60 and ~x=120. */
    private static List<StreamTableExtractor.Line> grid() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 6; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 45, y, "Row" + r));
            ws.add(w(70, 90, y, String.valueOf(r * 3)));
            ws.add(w(130,150, y, String.valueOf(r * 7)));
        }
        return StreamTableExtractor.buildLines(ws, 10f);
    }

    /**
     * Uniform, non-adversarial grid of {@code rows} x {@code cols} with ordinary,
     * well-separated column slots (no staggering between rows). Cell width has a small,
     * realistic spread (4 distinct widths, like numbers with a few different digit counts)
     * rather than being byte-identical across every row -- real PDF text essentially never
     * repeats the exact same glyph metrics hundreds of rows straight, and a small spread is
     * exactly what legitimately forces the branch-and-bound to verify a handful of candidate
     * gutter boundaries per column before settling on the true (widest-row) safe interval.
     */
    private static List<StreamTableExtractor.Line> uniformGrid(int rows, int cols) {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        float colWidth = 60f, rowSpacing = 15f;
        Random rnd = new Random(42);
        for (int r = 0; r < rows; r++) {
            float y = r * rowSpacing;
            for (int c = 0; c < cols; c++) {
                float x0 = c * colWidth;
                float wordWidth = 20f + rnd.nextInt(4) * 2f; // 20,22,24,26 -- well clear of the gutter
                ws.add(w(x0, x0 + wordWidth, y, String.valueOf((r * 31 + c * 7) % 97)));
            }
        }
        return StreamTableExtractor.buildLines(ws, 10f);
    }

    @Test
    void findsTwoInteriorGuttersInThreeColumnGrid() {
        List<StreamTableExtractor.Gutter> g =
            StreamTableExtractor.findGutters(grid(), 10, 150, 6f);
        assertEquals(2, g.size(), "expect 2 interior gutters -> 3 columns");
        assertTrue(g.get(0).cx() > 45 && g.get(0).cx() < 70);
        assertTrue(g.get(1).cx() > 90 && g.get(1).cx() < 130);
        assertTrue(g.get(0).rowsCovered >= 5);
    }

    @Test
    void singleCentralGutterInTwoColumnProse() {
        // two blocks of text with one central gutter -> exactly 1 interior gutter
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 90, y, "leftcolumnprosetext"));
            ws.add(w(110, 190, y, "rightcolumnprosetext"));
        }
        List<StreamTableExtractor.Gutter> g =
            StreamTableExtractor.findGutters(StreamTableExtractor.buildLines(ws, 10f), 10, 190, 6f);
        assertEquals(1, g.size());
    }

    /**
     * Dense/adversarial obstacle field: a brick-pattern grid where alternating rows offset
     * their columns by half a column width, so no single x-range is empty across more than
     * one row at a time. Real cost of the branch-and-bound is pops * obstacles.size() (see
     * MAX_GUTTER_SCAN_WORK's doc) -- this construction forces many surviving pops (peeling
     * one obstacle at a time off the near-full-height "remainder" rect) each scanning nearly
     * the full obstacle list, so the budget must fire well before the search would otherwise
     * terminate. Word/line counts stay under MAX_STREAM_WORDS/MAX_STREAM_LINES so it's the
     * gutter budget -- not an earlier cap -- that trips.
     */
    @Test
    void findGuttersAbortsOnDenseAdversarialPage() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        int rows = 2000, cols = 25;
        float colWidth = 12f, wordWidth = 8f, rowSpacing = 15f;
        for (int r = 0; r < rows; r++) {
            float y = r * rowSpacing;
            float offset = (r % 2 == 0) ? 0f : colWidth / 2f; // brick pattern: breaks column alignment
            for (int c = 0; c < cols; c++) {
                float x0 = c * colWidth + offset;
                ws.add(w(x0, x0 + wordWidth, y, String.valueOf(c)));
            }
        }
        assertTrue(ws.size() < StreamTableExtractor.MAX_STREAM_WORDS, "stay under the word cap");
        List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(ws, 10f);
        assertTrue(lines.size() < StreamTableExtractor.MAX_STREAM_LINES, "stay under the line cap");
        // Right edge sits just 2pt past the last word -- narrower than minGutterW(6f) -- so the
        // search can't trivially satisfy MAX_GUTTER_CANDIDATES via a free margin region; it must
        // dig into the (obstacle-free-nowhere) brick pattern, which is where the real cost blows up.
        float bandX1 = (cols - 1) * colWidth + colWidth / 2f + wordWidth + 2f;
        assertThrows(TableExtractor.RulingOverflowException.class,
            () -> StreamTableExtractor.findGutters(lines, 0f, bandX1, 6f));
    }

    /**
     * 3 numeric columns with NARROW gutters (14pt and 10pt, both between minGutterW=6 and
     * 3*medianSpace=18). Two stray glyphs intrude into gutter1 asymmetrically -- one blocking
     * its left half [45,52] for only the last "P" row, the other blocking its right half
     * [52,59] for only the first "R" row -- so the branch-and-bound can only ever discover
     * gutter1 as two x-adjacent, individually-partial fragments: [45,52] covering the P/Q rows
     * and [52,59] covering the Q/R rows. Each fragment alone covers just 4 of 7 rows (< the
     * 60% cover threshold of 4.2); only their union (all 7 rows, via the shared Q row) clears
     * it. This is exactly Finding 2's scenario: verified (via a side-by-side reimplementation
     * of the pre-fix filter-then-merge ordering) that the OLD code collapses this to a single
     * gutter (losing gutter1 entirely: old gutters=1, x=[79,89]) while the FIXED merge-then-
     * filter ordering recovers both, each with full 7-row coverage.
     */
    @Test
    void narrowGuttersSurviveVerticalFragmentation() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        float[] pRows = {0, 15, 30};   // P group: 3 rows, tightly packed near the top
        float[] qRows = {100};         // Q group: 1 row, the shared "overlap" row
        float[] rRows = {200, 215, 230}; // R group: 3 rows, tightly packed near the bottom
        int i = 0;
        for (float y : pRows) {
            // P rows carry only the col0 label -- deliberately no col1/col2 words here, so the
            // branch-and-bound doesn't burn its 16-candidate budget on redundant re-discovery
            // of the (unrelated, already-clean) gutter2 while peeling through this group.
            ws.add(w(10, 45, y, "Row" + i));
            i++;
        }
        ws.add(w(52, 59, 30, "x")); // blocks gutter1's RIGHT half [52,59] for the last P row only
        for (float y : qRows) {
            ws.add(w(10, 45, y, "Row" + i));
            ws.add(w(59, 79, y, String.valueOf(i * 3)));   // col1: [59,79] -> gutter1 = [45,59], width 14
            ws.add(w(89, 109, y, String.valueOf(i * 7)));  // col2: [89,109] -> gutter2 = [79,89], width 10
            i++;
        }
        for (float y : rRows) {
            ws.add(w(10, 45, y, "Row" + i));
            ws.add(w(59, 79, y, String.valueOf(i * 3)));
            ws.add(w(89, 109, y, String.valueOf(i * 7)));
            i++;
        }
        ws.add(w(45, 52, 200, "y")); // blocks gutter1's LEFT half [45,52] for the first R row only

        List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(ws, 10f);
        assertEquals(7, lines.size(), "3 P rows + 1 Q row + 3 R rows");
        List<StreamTableExtractor.Gutter> g = StreamTableExtractor.findGutters(lines, 10, 109, 6f);
        assertEquals(2, g.size(), "narrow gutters must survive vertical fragmentation -> 3 columns");
        assertTrue(g.get(0).cx() > 45 && g.get(0).cx() < 59, "gutter1 between col0 and col1");
        assertTrue(g.get(1).cx() > 79 && g.get(1).cx() < 89, "gutter2 between col1 and col2");
        assertEquals(7, g.get(0).rowsCovered, "gutter1's merged fragments must cover all 7 rows");
        assertEquals(7, g.get(1).rowsCovered, "gutter2 covers all 7 rows");
    }

    /**
     * A normal multi-column table must still complete. A plain uniform grid (well-separated
     * gutters, no staggering) with a small, realistic per-cell width spread -- real PDF text
     * essentially never repeats byte-identical glyph metrics across hundreds of rows -- at
     * 150 rows x 6 columns is comfortably inside "a normal multi-column table" per the
     * acceptance bar.
     */
    @Test
    void largeLegitimateTableCompletes() {
        int rows = 150, cols = 6;
        List<StreamTableExtractor.Line> lines = uniformGrid(rows, cols);
        assertEquals(rows, lines.size());
        float bandX1 = (cols - 1) * 60f + 26f + 20f;
        List<StreamTableExtractor.Gutter> g = StreamTableExtractor.findGutters(lines, 0f, bandX1, 6f);
        assertEquals(cols - 1, g.size(), "expect cols-1 interior gutters for a " + rows + "x" + cols + " grid");
    }

    /**
     * Locks in headroom at a higher column count: 200 rows x 10 columns (9 interior gutters),
     * hundreds of rows and up to a dozen columns is squarely within MAX_STREAM_LINES=8000 and
     * should complete fast, not just "eventually".
     */
    @Test
    void largeLegitimateTableCompletesAtHigherColumnCount() {
        int rows = 200, cols = 10;
        List<StreamTableExtractor.Line> lines = uniformGrid(rows, cols);
        assertEquals(rows, lines.size());
        float bandX1 = (cols - 1) * 60f + 26f + 20f;
        long t0 = System.nanoTime();
        List<StreamTableExtractor.Gutter> g = StreamTableExtractor.findGutters(lines, 0f, bandX1, 6f);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        assertEquals(cols - 1, g.size(), "expect cols-1 interior gutters for a " + rows + "x" + cols + " grid");
        assertTrue(elapsedMs < 2000, "must complete well under 2s, took " + elapsedMs + "ms");
    }

    /**
     * The actual pre-fix regression: a uniform grid whose column gutters are NARROWER than the
     * quality cap (3*medianSpace = 18 here; gutterW=16), still ordinary/non-adversarial (no
     * staggering, identical column slots every row) but a case that genuinely threw
     * RulingOverflowException pre-fix at just 150 rows x 6 columns (verified against the
     * pre-fix algorithm) because duplicate re-derivation of the SAME already-known gutter (via
     * the row-peeling above/below splits of unrelated sibling rects) burned real O(obstacles)
     * scans for zero new information. coveredByAccepted prunes the re-scan of those
     * duplicates outright.
     */
    @Test
    void largeTableWithNarrowGuttersCompletes() {
        int rows = 150, cols = 6;
        float colSlot = 40f, gutterW = 16f, rowSpacing = 15f;
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            float y = r * rowSpacing;
            for (int c = 0; c < cols; c++) {
                float x0 = c * (colSlot + gutterW);
                ws.add(w(x0, x0 + colSlot, y, String.valueOf((r * 31 + c * 7) % 97)));
            }
        }
        List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(ws, 10f);
        assertEquals(rows, lines.size());
        float bandX1 = (cols - 1) * (colSlot + gutterW) + colSlot + 10f;
        List<StreamTableExtractor.Gutter> g = StreamTableExtractor.findGutters(lines, 0f, bandX1, 6f);
        assertEquals(cols - 1, g.size(), "expect cols-1 interior gutters for a " + rows + "x" + cols + " grid");
    }

    /**
     * Task 9e: an 11-column dense numeric grid whose inter-column gaps are only ~1-1.5x the
     * median space width (medianSpace=6f here -> gutterW=8f, i.e. 1.33x), mimicking the real
     * us-018 ICDAR failure (2 gutters found vs. ground truth's 11 columns / 10 interior
     * gutters). Per-cell width has the same small, realistic jitter as {@link #uniformGrid}
     * (4 distinct widths) rather than being byte-identical every row, so the search has to
     * verify a handful of candidate boundaries per column -- exactly like real numeric-table
     * PDF text -- before settling on each column's true safe interval. This directly locks in
     * the fix for the dominant remaining defect measured against ground truth: findGutters
     * under-splitting dense/narrow numeric column blocks.
     */
    /**
     * Task 9k: the dominant measured defect in the corpus-wide adjacency-F1 diagnosis is
     * column UNDER-SPLITTING -- two adjacent ground-truth columns get detected as one because
     * the true inter-column gap is narrower than {@code minGutterW} (= medianSpace here, 6f),
     * so the branch-and-bound never even considers that x-range as a candidate gutter. This
     * fixture reproduces exactly that shape: col0/col1 have an ordinary 20pt gutter (65-45=20,
     * well above minGutterW, trivially found), but col1/col2 have only a 3.5pt gap (0.58x
     * medianSpace) -- narrower than minGutterW=6 -- repeated identically (byte-for-byte aligned
     * left/right edges) across 20 rows. A real narrow-but-consistently-aligned numeric column
     * boundary like this is exactly what the diagnosis's "augment the width floor with an
     * alignment-based signal" fix targets: the gap is real and rock-steady across every row,
     * just physically narrow. Pre-fix, findGutters has no path to accept a sub-minGutterW rect
     * at all (it's dropped at the width check before ever being scored), so only the col0/col1
     * gutter is found -- 1 interior gutter versus the true 2 (3 columns). This test asserts the
     * full 2-gutter/3-column split and documents the pre-fix RED count in the assertion message.
     */
    @Test
    void narrowlySpacedAdjacentColumnsAreSplit() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        int rows = 20;
        for (int r = 0; r < rows; r++) {
            float y = r * 15f;
            ws.add(w(10, 45, y, "Row" + r));                 // col0: label, [10,45]
            ws.add(w(65, 85, y, String.valueOf(10 + r)));    // col1: [65,85] -- 20pt gutter to col0
            ws.add(w(88.5f, 108.5f, y, String.valueOf(90 + r))); // col2: [88.5,108.5] -- ONLY 3.5pt gap to col1
        }
        List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(ws, 10f);
        assertEquals(rows, lines.size());
        List<StreamTableExtractor.Gutter> g = StreamTableExtractor.findGutters(lines, 10, 108.5f, 6f);
        assertEquals(2, g.size(),
            "expect 2 interior gutters -> 3 columns (narrow-but-aligned col1/col2 boundary must "
                + "still be found); got " + g.size() + " -- narrow gap under-split if this is 1");
        assertTrue(g.get(0).cx() > 45 && g.get(0).cx() < 65, "gutter0 between col0 and col1");
        assertTrue(g.get(1).cx() > 85 && g.get(1).cx() < 88.5f, "gutter1 (narrow) between col1 and col2");
    }

    @Test
    void denseNumericTableYieldsAllColumns() {
        int rows = 40, cols = 11;
        float colSlot = 20f, gutterW = 8f, rowSpacing = 15f;
        Random rnd = new Random(7);
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            float y = r * rowSpacing;
            for (int c = 0; c < cols; c++) {
                float x0 = c * (colSlot + gutterW);
                float wordWidth = colSlot - rnd.nextInt(4) * 2f; // 14,16,18,20 -- stays inside the slot
                ws.add(w(x0, x0 + wordWidth, y, String.valueOf((r * 31 + c * 7) % 97)));
            }
        }
        List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(ws, 10f);
        assertEquals(rows, lines.size());
        float bandX1 = (cols - 1) * (colSlot + gutterW) + colSlot + 10f;
        List<StreamTableExtractor.Gutter> g = StreamTableExtractor.findGutters(lines, 0f, bandX1, 6f);
        assertEquals(cols - 1, g.size(), "expect cols-1=10 interior gutters for an " + rows + "x" + cols + " dense numeric grid, got " + g.size());
    }
}
