package com.oai.titanarum;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * DoS bound for the pass {@link StreamTableExtractor#numericDataColumnCount} adds to {@code
 * scoreGrid}. That pass introduces NO new search, queue, or recursion -- it is one linear sweep of
 * the SAME already-bounded line list, per column, i.e. exactly the O(cols x words) shape the two
 * loops beside it (the word-based numeric-column count and the prose-veto column scan) already have.
 * So it is a constant-factor addition to an existing bounded cost, not a new attack surface.
 *
 * <p>These tests pin that claim at the two ends that matter:
 * <ul>
 *   <li>the WORST legitimate shape reachable inside the page caps (many columns x many rows) still
 *       completes well inside a wall-clock bound that leaves the p95 stream budget intact, and the
 *       added pass costs a bounded fraction of the whole scoreGrid call; and</li>
 *   <li>the dense adversarial page still aborts in the gutter search BEFORE scoreGrid is ever
 *       reached, so this pass can never run on it at all.</li>
 * </ul>
 */
class StreamNumericDataColumnDosTest {

    private static StreamTableExtractor.Word w(float x0, float x1, float yTop, String t) {
        StreamTableExtractor.Word wd = new StreamTableExtractor.Word();
        wd.x0 = x0; wd.x1 = x1; wd.y0 = yTop; wd.y1 = yTop + 10; wd.text = t;
        wd.numeric = t.matches("[-+(]?[\\d.,%$)]+") && t.chars().anyMatch(Character::isDigit);
        return wd;
    }

    /**
     * Worst legitimate shape: a grid at the line cap with a wide column model, i.e. the maximum
     * cols x rows product a real page can drive scoreGrid to without first tripping
     * MAX_STREAM_LINES / MAX_STREAM_WORDS. 40 columns x 1,500 rows = 60,000 words, exactly
     * MAX_STREAM_WORDS. Bound is generous (2s) because this test asserts a CEILING, not a
     * benchmark -- the measured value is printed so a regression is visible.
     */
    @Test
    void widestLegitimateGridStaysWithinWallClockBound() {
        int rows = 1_500, cols = 40;
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            float y = r * 12f;
            for (int c = 0; c < cols; c++) {
                float x0 = c * 30f;
                ws.add(w(x0, x0 + 20f, y, String.valueOf(r * cols + c)));
            }
        }
        assertTrue(ws.size() <= StreamTableExtractor.MAX_STREAM_WORDS,
                "fixture must stay inside the word cap, got " + ws.size());
        List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(ws, 10f);
        assertTrue(lines.size() <= StreamTableExtractor.MAX_STREAM_LINES,
                "fixture must stay inside the line cap, got " + lines.size());

        float[] bounds = new float[cols + 1];
        for (int c = 0; c <= cols; c++) bounds[c] = c * 30f - 5f;

        long t0 = System.nanoTime();
        int n = StreamTableExtractor.numericDataColumnCount(lines, bounds);
        long addedMs = (System.nanoTime() - t0) / 1_000_000;

        List<StreamTableExtractor.Gutter> gutters = new ArrayList<>();
        for (int c = 1; c < cols; c++) {
            StreamTableExtractor.Gutter g = new StreamTableExtractor.Gutter();
            g.x0 = c * 30f - 8f; g.x1 = c * 30f - 2f;
            gutters.add(g);
        }
        long t1 = System.nanoTime();
        StreamTableExtractor.scoreGrid(lines, gutters, bounds[0], bounds[cols]);
        long wholeMs = (System.nanoTime() - t1) / 1_000_000;

        System.out.printf(java.util.Locale.ROOT,
                "numericDataColumnCount on %dx%d (%d words): %d ms; whole scoreGrid: %d ms%n",
                rows, cols, ws.size(), addedMs, wholeMs);
        assertEquals(cols, n, "every column of an all-numeric grid is a numeric data column");
        assertTrue(addedMs < 2_000,
                "added pass must stay far inside the per-page budget, took " + addedMs + " ms");
        assertTrue(wholeMs < 10_000,
                "whole scoreGrid on the widest legitimate grid must stay bounded, took " + wholeMs + " ms");
    }

    /**
     * The dense adversarial page aborts inside the gutter search, so scoreGrid -- and therefore the
     * added pass -- is never reached on it. This is the same fixture as
     * StreamGutterTest#findGuttersAbortsOnDenseAdversarialPage; asserted here too so the DoS claim
     * for THIS pass does not depend on a test in another file continuing to use that shape.
     */
    @Test
    void denseAdversarialPageStillAbortsBeforeScoringIsReached() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        int rows = 2000, cols = 25;
        float colWidth = 12f, wordWidth = 8f, rowSpacing = 15f;
        for (int r = 0; r < rows; r++) {
            float y = r * rowSpacing;
            float offset = (r % 2 == 0) ? 0f : colWidth / 2f;
            for (int c = 0; c < cols; c++) {
                float x0 = c * colWidth + offset;
                ws.add(w(x0, x0 + wordWidth, y, String.valueOf(c)));
            }
        }
        List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(ws, 10f);
        float bandX1 = (cols - 1) * colWidth + colWidth / 2f + wordWidth + 2f;
        long t0 = System.nanoTime();
        assertThrows(TableExtractor.RulingOverflowException.class,
                () -> StreamTableExtractor.findGutters(lines, 0f, bandX1, 6f));
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.printf(java.util.Locale.ROOT, "adversarial page aborted in %d ms%n", ms);
        assertTrue(ms < 30_000, "adversarial abort must stay fast, took " + ms + " ms");
    }
}
