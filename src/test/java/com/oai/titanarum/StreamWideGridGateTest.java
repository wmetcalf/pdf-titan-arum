package com.oai.titanarum;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The confidence gate's admission bar must scale with the number of columns.
 *
 * <p>MEASURED PROBLEM (gate-oracle measurement, 77-PDF corpus + 1,599 real-world PDFs).
 * {@code scoreGrid}'s column-count credit, {@code 0.15 * min(1, (cols-2)/2)}, SATURATES at 4 columns,
 * while its {@code colConsistency} term actively penalises wide grids: it counts a row as consistent
 * only when at least {@code cols-1} of the {@code cols} columns are populated, so the requirement
 * grows with the column count and any legitimately SPARSE wide table drives the term towards 0.
 * Past ~5 columns the structural evidence therefore RISES (an 8-column grid needs seven simultaneous
 * full-height whitespace gutters, which prose does not produce by accident) while the score FALLS.
 *
 * <p>{@link #addingOneMoreCorrectColumnMustNotDeleteTheTable} pins the sharpest form of that bug:
 * the same sparse content, described with MORE (correct) columns, scores LOWER. Under a single flat
 * bar that can flip a correct table from emitted to silently deleted.
 *
 * <p>Corpus consequence at the flat 0.55 bar: correct 7-to-9-column tables scoring 0.405-0.516 were
 * deleted outright (eu-026 p4 and p6, tabula/twotables p1). The column threshold is 7 rather than 6
 * because at 6 the gate also admits eu-002 p1, which is five rows of running prose chopped into six
 * fake columns and scores HIGHER (0.451) than either eu-026 candidate -- see the sweep table above
 * {@link StreamTableExtractor#WIDE_GRID_MIN_COLS}.
 *
 * <p>These tests pin the fix (a lower bar from {@link StreamTableExtractor#WIDE_GRID_MIN_COLS}
 * columns up) and, just as importantly, pin that the NARROW bar -- the false-positive firewall that
 * keeps ordinary 2-to-6-column prose, forms and nav bars out -- is completely unchanged.
 */
class StreamWideGridGateTest {

    private static StreamTableExtractor.Word w(float x0, float x1, float yTop, String t) {
        StreamTableExtractor.Word wd = new StreamTableExtractor.Word();
        wd.x0 = x0; wd.x1 = x1; wd.y0 = yTop; wd.y1 = yTop + 10; wd.text = t;
        wd.numeric = t.matches("[-+(]?[\\d.,%$)]+") && t.chars().anyMatch(Character::isDigit);
        return wd;
    }

    /** Sparsity patterns. {@code CHECKER} leaves ~half the columns of any row empty (the shape that
     *  collapses colConsistency as the column count grows); {@code EVERY_THIRD_ROW} fills all columns
     *  on one row in three and only column 0 on the rest. */
    private enum Sparsity { CHECKER, EVERY_THIRD_ROW }

    /**
     * A grid of {@code cols} non-numeric columns on a 40pt column pitch with {@code wordW}-wide text,
     * {@code rows} rows, sparsified by {@code how}. Everything is deliberately explicit so the
     * resulting confidence is reproducible and can be asserted against the two bars.
     */
    private static StreamTableExtractor.Grid grid(int cols, int rows, int wordW, Sparsity how) {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            float y = 20 + r * 15;
            for (int c = 0; c < cols; c++) {
                boolean populated = how == Sparsity.CHECKER ? (c == 0 || (c + r) % 2 == 0)
                                                            : (c == 0 || r % 3 == 0);
                if (!populated) continue;
                float x0 = 10 + c * 40;
                ws.add(w(x0, x0 + wordW, y, "Label" + c + "x" + r));
            }
        }
        float bandX1 = 10 + (cols - 1) * 40 + wordW;
        List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(ws, 10f);
        List<StreamTableExtractor.Gutter> g = StreamTableExtractor.findGutters(lines, 10, bandX1, 5f);
        StreamTableExtractor.Grid grid = StreamTableExtractor.scoreGrid(lines, g, 10, bandX1);
        assertEquals(cols, grid.colBounds.length - 1,
                "fixture must actually produce " + cols + " columns");
        return grid;
    }

    private static final double FLAT = StreamTableExtractor.STREAM_CONFIDENCE_MIN;
    private static final double WIDE = StreamTableExtractor.STREAM_CONFIDENCE_MIN_WIDE;

    @Test
    void wideSparseGridBelowTheFlatBarIsAdmitted() {
        StreamTableExtractor.Grid g = grid(7, 9, 29, Sparsity.CHECKER);
        assertNull(g.hardReject, "must be a GRADED score, not a hard reject, or it proves nothing");
        assertTrue(g.confidence < FLAT,
                "fixture must sit BELOW the flat bar, else it proves nothing; got " + g.confidence);
        assertTrue(g.confidence >= WIDE, "fixture must sit at or above the wide bar; got " + g.confidence);
        assertTrue(StreamTableExtractor.acceptsGrid(g),
                "a 7-column grid scoring " + g.confidence + " must be admitted");
    }

    @Test
    void narrowGridAtOrAboveThatScoreIsStillRejected() {
        StreamTableExtractor.Grid wide = grid(7, 9, 29, Sparsity.CHECKER);
        StreamTableExtractor.Grid narrow = grid(3, 9, 29, Sparsity.EVERY_THIRD_ROW);
        assertNull(narrow.hardReject, "narrow fixture must be a graded score too");
        assertTrue(narrow.confidence < FLAT,
                "narrow fixture must sit below the flat bar; got " + narrow.confidence);
        assertTrue(narrow.confidence > wide.confidence,
                "narrow fixture must score at least as high as the admitted wide one, so the only "
                        + "thing separating them is the column count; narrow=" + narrow.confidence
                        + " wide=" + wide.confidence);
        assertFalse(StreamTableExtractor.acceptsGrid(narrow),
                "a 3-column grid below the flat bar must STILL be rejected even though it outscores "
                        + "an admitted wide grid -- the narrow prose firewall is unchanged; got "
                        + narrow.confidence);
    }

    @Test
    void theBoundaryIsExactlySixVersusSevenColumnsAtIdenticalScores() {
        // The CHECKER pattern saturates colConsistency at 0 from 5 columns up, so these two grids
        // score IDENTICALLY. The only difference in verdict is the column count -- which is exactly
        // the rule under test, with no confounding score difference.
        StreamTableExtractor.Grid six = grid(6, 9, 29, Sparsity.CHECKER);
        StreamTableExtractor.Grid seven = grid(7, 9, 29, Sparsity.CHECKER);
        assertEquals(six.confidence, seven.confidence, 1e-9,
                "fixtures must score identically for this comparison to isolate the column count");
        assertFalse(StreamTableExtractor.acceptsGrid(six), "6 columns -> flat bar -> rejected");
        assertTrue(StreamTableExtractor.acceptsGrid(seven), "7 columns -> wide bar -> admitted");
    }

    @Test
    void addingOneMoreCorrectColumnMustNotDeleteTheTable() {
        // THE PATHOLOGY, in its sharpest form. Same sparse content, same text width, same rows --
        // only the number of (correct) columns changes. colConsistency collapses as the column count
        // rises, so the confidence FALLS even though the description got better.
        StreamTableExtractor.Grid three = grid(3, 9, 25, Sparsity.CHECKER);
        StreamTableExtractor.Grid seven = grid(7, 9, 25, Sparsity.CHECKER);
        assertTrue(seven.confidence < three.confidence,
                "documents the bug: more correct columns must not have RAISED the score for this "
                        + "assertion to be meaningful; three=" + three.confidence
                        + " seven=" + seven.confidence);
        assertTrue(seven.confidence < FLAT,
                "and the wider description falls below the flat bar; got " + seven.confidence);
        assertTrue(StreamTableExtractor.acceptsGrid(three), "3-column version was accepted");
        assertTrue(StreamTableExtractor.acceptsGrid(seven),
                "adding correct columns must NOT delete the table: 7-column version scoring "
                        + seven.confidence + " must still be accepted");
    }

    @Test
    void wideGridBelowEvenTheWideBarIsRejected() {
        // Wide but genuinely bad: every word fills essentially its whole column (maximal prose fill).
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            float y = 20 + r * 15;
            for (int c = 0; c < 7; c++) {
                float x0 = 10 + c * 40;
                ws.add(w(x0, x0 + 36, y, "aaaaaaaaaaaaaaaaaaaaaa"));
            }
        }
        List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(ws, 10f);
        List<StreamTableExtractor.Gutter> g = StreamTableExtractor.findGutters(lines, 10, 286, 5f);
        StreamTableExtractor.Grid grid = StreamTableExtractor.scoreGrid(lines, g, 10, 286);
        assertFalse(StreamTableExtractor.acceptsGrid(grid),
                "solid wide prose must be rejected at the wide bar too; got " + grid.confidence);
    }

    @Test
    void twoColumnProseIsStillHardRejected() {
        // The single most dangerous false positive (ordinary two-column body text) must be unaffected.
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 95, y, "leftcolumnfullofprosethatwraps"));
            ws.add(w(110, 195, y, "rightcolumnfullofprosethatwraps"));
        }
        List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(ws, 10f);
        List<StreamTableExtractor.Gutter> g = StreamTableExtractor.findGutters(lines, 10, 195, 6f);
        StreamTableExtractor.Grid grid = StreamTableExtractor.scoreGrid(lines, g, 10, 195);
        assertEquals(0.0, grid.confidence, 1e-12, "two-column prose must still score exactly 0");
        assertFalse(StreamTableExtractor.acceptsGrid(grid), "two-column prose must not be admitted");
    }

    @Test
    void barIsTheFlatOneUpToSixColumnsAndTheWideOneFromSeven() {
        for (int cols = 0; cols <= 6; cols++) {
            assertEquals(FLAT, StreamTableExtractor.confidenceFloorFor(cols), 1e-12,
                    cols + " columns must use the unchanged flat bar");
        }
        for (int cols = 7; cols <= 200; cols++) {
            assertEquals(WIDE, StreamTableExtractor.confidenceFloorFor(cols), 1e-12,
                    cols + " columns must use the wide bar");
        }
        assertTrue(WIDE < FLAT, "the wide bar must be the LOWER of the two");
        assertEquals(7, StreamTableExtractor.WIDE_GRID_MIN_COLS);
        assertEquals(0.40, WIDE, 1e-12);
        assertEquals(0.55, FLAT, 1e-12);
    }
}
