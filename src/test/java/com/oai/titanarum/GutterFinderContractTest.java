package com.oai.titanarum;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract test run against ALL FOUR column-gutter finders (breuel, gapvote, alignedge,
 * occupancy) with the SAME fixtures, establishing the bake-off's per-method baseline.
 *
 * Per the task: assert only the UNIVERSAL contract (non-null, sorted left-&gt;right, strictly
 * interior to the band, count &lt;= MAX_GUTTER_CANDIDATES, no exception other than
 * {@link TableExtractor.RulingOverflowException}) for every finder x fixture pair -- individual
 * finders are EXPECTED to disagree on gutter count (that disagreement is the actual bake-off
 * data, printed by {@link #printResultsTable()}). The one exception is threeColumnNumericGrid,
 * whose "3 clean, widely-separated numeric columns" case is unambiguous enough that all four
 * finders must find exactly 2 interior gutters; a finder that cannot clear that bar is
 * disqualified and its dedicated test is left failing (not weakened) so the failure is visible.
 */
class GutterFinderContractTest {

    private static StreamTableExtractor.Word w(float x0, float x1, float yTop, String t) {
        StreamTableExtractor.Word wd = new StreamTableExtractor.Word();
        wd.x0 = x0; wd.x1 = x1; wd.y0 = yTop; wd.y1 = yTop + 10; wd.text = t;
        wd.numeric = t.matches("[-+(]?[\\d.,%$)]+");
        return wd;
    }

    // ---------------------------------------------------------------- fixtures

    private static final float GRID_BAND_X0 = 10, GRID_BAND_X1 = 150;
    private static final float PROSE_BAND_X0 = 10, PROSE_BAND_X1 = 190;
    private static final float MEDIAN_SPACE = 6f;

    /** 3-column numeric grid: label | col1 | col2. Clear gutters -&gt; exactly 2 interior gutters. */
    private static List<StreamTableExtractor.Line> threeColumnNumericGrid() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 6; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 45, y, "Row" + r));
            ws.add(w(70, 90, y, String.valueOf(r * 3)));
            ws.add(w(130, 150, y, String.valueOf(r * 7)));
        }
        return StreamTableExtractor.buildLines(ws, 10f);
    }

    /** Two blocks of running prose text with one central gutter -&gt; exactly 1 interior gutter. */
    private static List<StreamTableExtractor.Line> twoColumnProse() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 90, y, "leftcolumnprosetext"));
            ws.add(w(110, 190, y, "rightcolumnprosetext"));
        }
        return StreamTableExtractor.buildLines(ws, 10f);
    }

    /**
     * The SAME 3-column grid as {@link #threeColumnNumericGrid}, but the first line is a single
     * full-band-width title word spanning all 3 columns (a report/section header above the
     * table). Interior structure is unchanged -- still exactly 2 interior gutters -- but the
     * title's span, unioned into occupancy's whole-page whitespace projection, erases BOTH
     * gaps: this is tabula's well-known "a spanning header collapses columns" weakness, and
     * {@code occupancy} is expected to fail (return 0) here while the other three (whose signals
     * are per-word or per-line, not a single page-wide union) are not fooled by one spanning row.
     */
    private static List<StreamTableExtractor.Line> spanningHeaderGrid() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        ws.add(w(GRID_BAND_X0, GRID_BAND_X1, 0, "FullWidthTitleSpanningAllThreeColumns"));
        for (int r = 0; r < 6; r++) {
            float y = 40 + r * 15;
            ws.add(w(10, 45, y, "Row" + r));
            ws.add(w(70, 90, y, String.valueOf(r * 3)));
            ws.add(w(130, 150, y, String.valueOf(r * 7)));
        }
        return StreamTableExtractor.buildLines(ws, 10f);
    }

    // Brick-pattern adversarial page: the SAME construction StreamGutterTest uses to trip
    // Breuel's own budget (alternating rows offset by half a column width, so no x-range is
    // empty across more than one row at a time -- see
    // StreamGutterTest.findGuttersAbortsOnDenseAdversarialPage). Reused here unchanged because
    // it is a PROVEN adversarial input for Breuel, and its 50,000 words / ~48,000 inter-word
    // gaps / 150,000 alignment-population entries are, by construction, comfortably past every
    // OTHER finder's documented budget too (see each finder's MAX_* constant and rationale) --
    // one fixture trips all four.
    private static final int DENSE_ROWS = 2000, DENSE_COLS = 25;
    private static final float DENSE_COL_WIDTH = 12f, DENSE_WORD_WIDTH = 8f, DENSE_ROW_SPACING = 15f;

    private static List<StreamTableExtractor.Line> denseAdversarialPage() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < DENSE_ROWS; r++) {
            float y = r * DENSE_ROW_SPACING;
            float offset = (r % 2 == 0) ? 0f : DENSE_COL_WIDTH / 2f; // brick pattern
            for (int c = 0; c < DENSE_COLS; c++) {
                float x0 = c * DENSE_COL_WIDTH + offset;
                ws.add(w(x0, x0 + DENSE_WORD_WIDTH, y, String.valueOf(c)));
            }
        }
        return StreamTableExtractor.buildLines(ws, 10f);
    }

    private static float denseBandX1() {
        return (DENSE_COLS - 1) * DENSE_COL_WIDTH + DENSE_COL_WIDTH / 2f + DENSE_WORD_WIDTH + 2f;
    }

    // ---------------------------------------------------------------- finders under test

    private static List<GutterFinder> finders() {
        return List.of(new BreuelGutterFinder(), new GapVotingGutterFinder(),
                        new AlignmentEdgeGutterFinder(), new OccupancyProjectionGutterFinder());
    }

    static Stream<GutterFinder> finderProvider() { return finders().stream(); }

    // ---------------------------------------------------------------- results table (the key
    // bake-off deliverable: printed once at the end via @AfterAll so it survives regardless of
    // per-method test execution order)

    private static final List<String[]> RESULTS = Collections.synchronizedList(new ArrayList<>());

    private static void record(String finder, String fixture, String outcome) {
        RESULTS.add(new String[]{finder, fixture, outcome});
    }

    @AfterAll
    static void printResultsTable() {
        System.out.println();
        System.out.println("==================== GutterFinder bake-off: gutter count per fixture ====================");
        System.out.printf("%-12s %-22s %s%n", "finder", "fixture", "result");
        System.out.println("-------------------------------------------------------------------------------------------");
        for (String[] row : RESULTS) {
            System.out.printf("%-12s %-22s %s%n", row[0], row[1], row[2]);
        }
        System.out.println("=============================================================================================");
    }

    // ---------------------------------------------------------------- universal contract

    private void assertUniversalContract(GutterFinder f, String fixtureName,
                                          List<StreamTableExtractor.Line> lines,
                                          float bandX0, float bandX1) {
        List<StreamTableExtractor.Gutter> gutters;
        try {
            gutters = f.find(lines, bandX0, bandX1, MEDIAN_SPACE);
        } catch (TableExtractor.RulingOverflowException e) {
            record(f.name(), fixtureName, "ABORT (RulingOverflowException)");
            return; // an abort is an allowed universal outcome
        }
        assertNotNull(gutters, f.name() + ": must not return null");
        assertTrue(gutters.size() <= StreamTableExtractor.MAX_GUTTER_CANDIDATES,
            f.name() + ": must respect MAX_GUTTER_CANDIDATES, got " + gutters.size());
        for (int i = 0; i < gutters.size(); i++) {
            StreamTableExtractor.Gutter g = gutters.get(i);
            assertTrue(g.x0 > bandX0 + 0.5f && g.x1 < bandX1 - 0.5f,
                f.name() + ": gutter [" + g.x0 + "," + g.x1 + "] must be strictly interior to band ["
                    + bandX0 + "," + bandX1 + "]");
            if (i > 0) {
                assertTrue(gutters.get(i - 1).cx() <= g.cx(),
                    f.name() + ": gutters must be sorted left->right");
            }
        }
        record(f.name(), fixtureName, String.valueOf(gutters.size()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("finderProvider")
    void contractOnThreeColumnNumericGrid(GutterFinder f) {
        assertUniversalContract(f, "threeColumnNumericGrid", threeColumnNumericGrid(), GRID_BAND_X0, GRID_BAND_X1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("finderProvider")
    void contractOnTwoColumnProse(GutterFinder f) {
        assertUniversalContract(f, "twoColumnProse", twoColumnProse(), PROSE_BAND_X0, PROSE_BAND_X1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("finderProvider")
    void contractOnSpanningHeaderGrid(GutterFinder f) {
        assertUniversalContract(f, "spanningHeaderGrid", spanningHeaderGrid(), GRID_BAND_X0, GRID_BAND_X1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("finderProvider")
    void abortsOnDenseAdversarialPage(GutterFinder f) {
        List<StreamTableExtractor.Line> lines = denseAdversarialPage();
        float bandX1 = denseBandX1();
        assertThrows(TableExtractor.RulingOverflowException.class,
            () -> f.find(lines, 0f, bandX1, MEDIAN_SPACE),
            f.name() + ": must abort (throw RulingOverflowException), not degrade, once its work "
                + "budget is exceeded");
        record(f.name(), "denseAdversarialPage", "ABORT (RulingOverflowException)");
    }

    // ---------------------------------------------------------------- disqualification gate
    // Per-finder, NOT parameterized: threeColumnNumericGrid is unambiguous (3 clean, widely
    // separated numeric columns) -- every finder must find exactly 2 interior gutters. If one
    // doesn't, this is a real disqualifying failure and must be left failing, not weakened.

    @Test
    void breuelFindsExactlyTwoGuttersInCleanThreeColumnGrid() {
        assertEquals(2, new BreuelGutterFinder()
            .find(threeColumnNumericGrid(), GRID_BAND_X0, GRID_BAND_X1, MEDIAN_SPACE).size());
    }

    @Test
    void gapVoteFindsExactlyTwoGuttersInCleanThreeColumnGrid() {
        assertEquals(2, new GapVotingGutterFinder()
            .find(threeColumnNumericGrid(), GRID_BAND_X0, GRID_BAND_X1, MEDIAN_SPACE).size());
    }

    @Test
    void alignEdgeFindsExactlyTwoGuttersInCleanThreeColumnGrid() {
        assertEquals(2, new AlignmentEdgeGutterFinder()
            .find(threeColumnNumericGrid(), GRID_BAND_X0, GRID_BAND_X1, MEDIAN_SPACE).size());
    }

    @Test
    void occupancyFindsExactlyTwoGuttersInCleanThreeColumnGrid() {
        assertEquals(2, new OccupancyProjectionGutterFinder()
            .find(threeColumnNumericGrid(), GRID_BAND_X0, GRID_BAND_X1, MEDIAN_SPACE).size());
    }
}
