package com.oai.titanarum;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DoS bounds on the per-block grid stages of the borderless ("stream") path, and on the COLUMN COUNT
 * they are super-linear in.
 *
 * <p>THE DEFECT THESE PIN. {@code MAX_STREAM_PAGE_BLOCK_WORK} charged ONE UNIT PER WORD, on the
 * premise (quoted from its own superseded doc) that "the REMAINING linear (scoreGrid/trimEdgeLines are
 * O(block word count)) per-block work" was all that was left. That premise was wrong: {@code
 * scoreGrid}'s numeric-lean pass, its prose veto, {@link StreamTableExtractor#numericDataColumnCount}
 * and {@code trimEdgeLines}/{@code isNonConformingEdge} are each O(cols x words), and {@link
 * StreamTableExtractor#colOf} -- the innermost operation of all of them -- was itself a LINEAR scan
 * over {@code cols+1} bounds, making the measured cost of scoring one block CUBIC in the column count.
 * Meanwhile {@code MAX_GUTTER_CANDIDATES} had been raised 16 -> 20,000 for search-convergence reasons,
 * which removed the only thing that had ever incidentally bounded that column count.
 *
 * <p>MEASURED before the fix, at the stage level, on a 3-row block whose gutters the primary search
 * accepts outright: 600 cols 198ms, 1,000 cols 795ms, 1,500 cols 2,303ms, 2,000 cols 5,312ms, 3,000
 * cols 17,457ms. End to end through {@code TableExtractor.extract}, a 135,316-byte SINGLE-PAGE PDF of
 * six such 3,000-column blocks cost <b>215.2 SECONDS</b>, returned six tables and reported {@code
 * truncated=false} while charging 9% of the word-denominated budget. (The end-to-end assertions live
 * in {@code TableStreamDosTest}; this class pins the stages and the bounds themselves.)
 *
 * <p>THE GAP IN THE OLD SUITE that let this through, recorded because it is why these fixtures are
 * shaped as they are: the pre-existing adversarial fixture ({@code
 * findGuttersAbortsOnDenseAdversarialPage}) only exercises the case where NOTHING is accepted --
 * {@code cols} stays 1 and {@code scoreGrid} hard-rejects at {@code cols < 2} before doing any work.
 * <b>No test in the suite ever paid the downstream cost of a HIGH ACCEPTED gutter count.</b>
 * {@link #highAcceptedGutterCountIsReachableAndIsNowBounded} is that missing test.
 */
class StreamGridDosTest {

    // ------------------------------------------------------------------------------------ fixtures

    private static StreamTableExtractor.Word w(float x0, float x1, float yTop, float yBot, String t) {
        StreamTableExtractor.Word wd = new StreamTableExtractor.Word();
        wd.x0 = x0; wd.x1 = x1; wd.y0 = yTop; wd.y1 = yBot; wd.text = t;
        wd.numeric = true;
        return wd;
    }

    /**
     * A perfectly regular {@code rows x cols} numeric grid whose inter-column gap (8pt) comfortably
     * EXCEEDS {@code minGutterW = max(medianSpace, 1) = 5}, so the primary branch-and-bound accepts
     * every one of the {@code cols-1} boundaries cheaply. That "cheap search, many accepted gutters"
     * regime is the one the downstream O(cols x words) stages are expensive in, and the one no
     * pre-existing fixture covered.
     */
    private static List<StreamTableExtractor.Line> regularGrid(int rows, int cols) {
        List<StreamTableExtractor.Word> ws = new ArrayList<>(rows * cols);
        for (int r = 0; r < rows; r++) {
            float y = r * 12f;
            for (int c = 0; c < cols; c++) {
                ws.add(w(c * 10f, c * 10f + 2f, y, y + 10f, String.valueOf((r + c) % 10)));
            }
        }
        return StreamTableExtractor.buildLines(ws, 10f);
    }

    /** The dense adversarial shape, byte-identical in geometry to {@code
     *  StreamGutterTest#findGuttersAbortsOnDenseAdversarialPage}'s fixture (reused deliberately, so
     *  these tests exercise the SAME shape that invariant already pins): a brick pattern in which no
     *  two adjacent rows share a column boundary, so nothing is ever accepted and the
     *  branch-and-bound cannot terminate cheaply. */
    private static final int BRICK_ROWS = 2000, BRICK_COLS = 25;
    private static final float BRICK_COL_W = 12f, BRICK_WORD_W = 8f, BRICK_ROW_SPACING = 15f;

    private static List<StreamTableExtractor.Line> brickOffsetBlock() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < BRICK_ROWS; r++) {
            float y = r * BRICK_ROW_SPACING;
            float off = (r % 2 == 0) ? 0f : BRICK_COL_W / 2f;
            for (int c = 0; c < BRICK_COLS; c++) {
                float x0 = c * BRICK_COL_W + off;
                ws.add(w(x0, x0 + BRICK_WORD_W, y, y + 10f, String.valueOf(c)));
            }
        }
        return StreamTableExtractor.buildLines(ws, 10f);
    }

    /** The band the brick fixture is searched over: its right edge sits just 2pt past the last word,
     *  narrower than {@code minGutterW}, so the search cannot satisfy itself on a free margin. */
    private static float brickBandX1() {
        return (BRICK_COLS - 1) * BRICK_COL_W + BRICK_COL_W / 2f + BRICK_WORD_W + 2f;
    }

    private static float bandX1(int cols) { return (cols - 1) * 10f + 2f; }

    private static long wordsOf(List<StreamTableExtractor.Line> lines) {
        long n = 0;
        for (StreamTableExtractor.Line l : lines) n += l.words.size();
        return n;
    }

    // ------------------------------------------------------- colOf: the cubic factor, pinned exact

    /**
     * {@link StreamTableExtractor#colOf} was rewritten from a linear scan into a binary search -- the
     * change that removed the SECOND {@code cols} factor from every O(cols x words) pass (measured:
     * the 3,000-column block dropped from 17,457ms to 2,928ms on that change alone). It must be
     * EXACTLY equivalent, so this brute-forces the two definitions against each other rather than
     * trusting the argument that the predicate is monotone.
     *
     * <p>Adversarial bounds arrays on purpose: duplicates (a zero-width column, which a degenerate
     * gutter can produce), negatives, a single-column array (the {@code lo == hi} path), extreme
     * magnitudes, and probe points exactly ON each boundary as well as just below, just above, and
     * far outside.
     */
    @Test
    void colOfBinarySearchMatchesLinearScanExhaustively() {
        List<float[]> boundsSets = new ArrayList<>(List.of(
            new float[]{0, 10},                                  // one column: the lo == hi path
            new float[]{0, 10, 20},
            new float[]{0, 10, 10, 20},                          // duplicate -> zero-width column
            new float[]{0, 0, 10, 10, 20, 20},                   // several duplicates
            new float[]{-50, -10, -10, 0, 3.5f, 3.5f, 3.5f, 99},
            new float[]{-1e6f, -1f, 0f, 1f, 1e6f},
            new float[]{7, 7}                                    // degenerate zero-width band
        ));
        float[] wide = new float[257];                           // real binary-search depth
        for (int i = 0; i < wide.length; i++) wide[i] = i * 7f;
        boundsSets.add(wide);

        int checked = 0;
        for (float[] bounds : boundsSets) {
            List<Float> probes = new ArrayList<>();
            for (float b : bounds) { probes.add(b - 0.5f); probes.add(b); probes.add(b + 0.5f); }
            probes.add(-1e9f); probes.add(1e9f); probes.add(0f); probes.add(-0f);
            for (float x : probes) {
                assertEquals(linearColOf(x, bounds), StreamTableExtractor.colOf(x, bounds),
                        "colOf disagreed with the original linear definition at x=" + x
                                + " bounds=" + Arrays.toString(bounds));
                checked++;
            }
        }
        assertTrue(checked > 200, "fixture must actually check many probes, got " + checked);
    }

    /** The ORIGINAL definition, verbatim, used as the oracle. */
    private static int linearColOf(float x, float[] bounds) {
        for (int c = 0; c < bounds.length - 1; c++) if (x < bounds[c + 1]) return c;
        return bounds.length - 2;
    }

    /** ...and the equivalence must hold on the bounds arrays the pipeline itself produces, not only
     *  on synthetic ones. */
    @Test
    void colOfMatchesLinearScanOnRealPipelineBounds() {
        List<StreamTableExtractor.Line> lines = regularGrid(4, 40);
        List<StreamTableExtractor.Gutter> gutters =
                StreamTableExtractor.findGutters(lines, 0f, bandX1(40), 5f);
        assertTrue(gutters.size() > 20, "fixture must produce a real multi-column model");
        float[] bounds = new float[gutters.size() + 2];
        bounds[0] = 0f; bounds[bounds.length - 1] = bandX1(40);
        for (int i = 0; i < gutters.size(); i++) bounds[i + 1] = gutters.get(i).cx();
        for (StreamTableExtractor.Line l : lines) {
            for (StreamTableExtractor.Word wd : l.words) {
                assertEquals(linearColOf(wd.cx(), bounds), StreamTableExtractor.colOf(wd.cx(), bounds));
            }
        }
    }

    // --------------------------------------------------------------- D5: the missing high-cols test

    /**
     * THE TEST THE SUITE NEVER HAD. A block whose gutter search ACCEPTS thousands of boundaries --
     * cheap to find (~2 pops each), catastrophically expensive downstream. Before the fix this exact
     * shape accepted 2,999 gutters from 9,000 words for 279ms of CHARGED search and then spent
     * 17,457ms of UNCHARGED scoring.
     *
     * <p>Asserts, in order: the fixture really does reach the accepting regime (otherwise it would be
     * testing the same thing the old adversarial fixture already tested); the resulting column count
     * is over the cap; and the per-block charge for it does NOT fit the per-page budget, so the block
     * is refused before any O(cols x words) pass runs -- and the refusal is REPORTED.
     */
    @Test
    void highAcceptedGutterCountIsReachableAndIsNowBounded() {
        int cols = 3000, rows = 3;
        List<StreamTableExtractor.Line> lines = regularGrid(rows, cols);
        assertEquals(rows, lines.size(), "fixture must be a " + rows + "-row block");
        assertEquals((long) rows * cols, wordsOf(lines));

        long[] searchWork = {0};
        List<StreamTableExtractor.Gutter> gutters =
                StreamTableExtractor.findGutters(lines, 0f, bandX1(cols), 5f, searchWork);
        assertTrue(gutters.size() > 2_000,
                "fixture must reach the HIGH-ACCEPTED-COUNT regime (this is the regime no other test "
                        + "in the suite covers); got " + gutters.size() + " gutters");
        assertTrue(searchWork[0] < StreamTableExtractor.MAX_GUTTER_SCAN_WORK,
                "and the SEARCH must be cheap here -- that is the whole point: the search budget "
                        + "cannot protect the stages after it. charged=" + searchWork[0]);

        int gridCols = gutters.size() + 1;
        assertTrue(gridCols > StreamTableExtractor.MAX_STREAM_GRID_COLS,
                "fixture must exceed MAX_STREAM_GRID_COLS ("
                        + StreamTableExtractor.MAX_STREAM_GRID_COLS + "); got " + gridCols);

        StreamTableExtractor.PageAccount account = new StreamTableExtractor.PageAccount();
        long charge = StreamTableExtractor.gridWorkFor(gridCols, wordsOf(lines));
        assertFalse(account.afford(charge),
                gridCols + " columns x " + wordsOf(lines) + " words charges " + charge + " units, "
                        + "which must NOT fit the per-page grid budget "
                        + StreamTableExtractor.MAX_STREAM_PAGE_GRID_WORK);
        assertTrue(account.truncated, "an unaffordable charge must flag truncated, never be silent");
    }

    /**
     * The charge must have the SHAPE of the work: multiplicative in columns. A word-linear charge --
     * the defect -- prices a 3,000-column block and a 3-column block of the same word count
     * identically, which is exactly how 215 seconds of CPU passed for 9% of a budget.
     */
    @Test
    void gridChargeIsMultiplicativeInColumnsNotWordLinear() {
        long words = 9_000;
        long narrow = StreamTableExtractor.gridWorkFor(3, words);
        long wide = StreamTableExtractor.gridWorkFor(3_000, words);
        // exact ratio is (3000+1)/(3+1) = 750.25 -- the charge is (cols+1) x words
        assertTrue(wide > 500L * narrow,
                "a 1000x wider grid must cost hundreds of times more; got narrow=" + narrow
                        + " wide=" + wide + " ratio=" + (wide / narrow));
        assertTrue(narrow > words,
                "even a 3-column block must charge more than its bare word count, got " + narrow);
        // the shape that matters: doubling columns at constant words must roughly double the charge
        assertTrue(StreamTableExtractor.gridWorkFor(200, words)
                        > 1.9 * StreamTableExtractor.gridWorkFor(100, words) - words);
    }

    /**
     * A LEGITIMATE wide grid -- 250 columns, just under the cap, with as many rows as {@link
     * TableExtractor#MAX_CELLS_PER_TABLE} would ever let become a table at all -- must still be
     * affordable. This is the guard that the cap and budget were sized over real input, not merely set
     * low enough to stop the attack.
     */
    @Test
    void widestLegitimateGridStillFitsTheBudget() {
        int cols = 250, rows = 39;                   // 250 x 39 = 9,750 cells, just under 10,000
        long words = (long) cols * rows;
        long charge = StreamTableExtractor.gridWorkFor(cols, words);
        StreamTableExtractor.PageAccount account = new StreamTableExtractor.PageAccount();
        assertTrue(cols <= StreamTableExtractor.MAX_STREAM_GRID_COLS,
                "250 columns must be admissible at all");
        assertTrue(account.afford(charge),
                cols + "x" + rows + " charges " + charge + " units, which MUST fit the per-page grid "
                        + "budget " + StreamTableExtractor.MAX_STREAM_PAGE_GRID_WORK);
        assertFalse(account.truncated);
    }

    /** The sizing claims on the four new bounds, checked against the recorded worst REAL observations
     *  (503 pages of 297 documents: every PDF under the tabula-java test-resource tree, all pages,
     *  plus the deterministic 200-PDF prose sample, all pages) rather than only asserted in prose. */
    @Test
    void newBoundsKeepGenerousHeadroomOverTheWorstRealInput() {
        assertTrue(StreamTableExtractor.MAX_STREAM_GRID_COLS
                        >= 10 * StreamTableExtractor.REAL_CORPUS_WORST_COLS,
                "column cap " + StreamTableExtractor.MAX_STREAM_GRID_COLS + " must leave >=10x over "
                        + "the worst real column count " + StreamTableExtractor.REAL_CORPUS_WORST_COLS);
        assertTrue(StreamTableExtractor.MAX_STREAM_PAGE_GRID_WORK
                        >= 50 * StreamTableExtractor.REAL_CORPUS_WORST_PAGE_GRID_WORK,
                "per-page grid budget must leave >=50x over the worst real page "
                        + StreamTableExtractor.REAL_CORPUS_WORST_PAGE_GRID_WORK);
        assertTrue(StreamTableExtractor.MAX_STREAM_PAGE_FINDER_WORK
                        >= 10 * StreamTableExtractor.REAL_CORPUS_WORST_PAGE_FINDER_WORK,
                "per-page search budget must leave >=10x over the worst real page "
                        + StreamTableExtractor.REAL_CORPUS_WORST_PAGE_FINDER_WORK);
        assertTrue(TableExtractor.MAX_STREAM_DOC_WORK
                        >= 50 * StreamTableExtractor.REAL_CORPUS_WORST_DOC_WORK,
                "document budget must leave >=50x over the worst real document "
                        + StreamTableExtractor.REAL_CORPUS_WORST_DOC_WORK);
    }

    // ------------------------------------------------------------ D6: additive pass, additive fail

    /**
     * D6. {@code findAlignmentNarrowGutters} is a STRICTLY ADDITIVE secondary pass, and its own work
     * budget ({@code MAX_ALIGNMENT_GUTTER_WORK}) used to throw straight out of {@code findGutters} --
     * discarding the primary search's already-valid result and, with it, the whole block. MEASURED
     * before the fix: this exact clean grid completed at cols=1,000 and THREW at cols=2,000,
     * discarding 1,999 correctly-found gutters. A legitimate-input recall cliff, not a defence.
     *
     * <p>The pass is charged (primaryGutters+1) x blockWords, so a grid with many primary gutters
     * drives it over budget while having nothing whatever to contribute -- every boundary is ALREADY a
     * primary gutter, so its segments are all too narrow to hold another one.
     */
    @Test
    void alignmentPassOverflowKeepsThePrimaryGutterSet() {
        // The regime findAlignmentNarrowGutters can actually blow its budget in. Per line: 1,000
        // GROUPS of two words. The 2pt gap INSIDE a group is below minGutterW (= max(medianSpace,1) =
        // 5) so the primary search cannot claim it; the 10pt gap BETWEEN groups is above it, so the
        // primary search accepts all 999 of those. That leaves the narrow pass 1,000 segments, each
        // 4pt wide -- above its own skip threshold (2 x NARROW_GUTTER_ABS_FLOOR_FACTOR x medianSpace =
        // 3pt), so none is skipped -- and it scans every word of every line per segment: 1,000 x 6,000
        // = 6,000,000 charged units against MAX_ALIGNMENT_GUTTER_WORK = 5,000,000. It overflows with
        // 999 perfectly good primary gutters already in hand.
        //
        // (The wide-gutter fixture used elsewhere in this class cannot exercise this: with a primary
        // gutter at every boundary, every segment is one 2pt word and is skipped, so the pass costs
        // nothing. That is why this test carries its own geometry.)
        int rows = 3, groups = 1000;
        List<StreamTableExtractor.Word> ws = new ArrayList<>(rows * groups * 2);
        for (int r = 0; r < rows; r++) {
            float y = r * 12f;
            for (int g = 0; g < groups; g++) {
                float x = g * 14f;
                ws.add(w(x, x + 1f, y, y + 10f, "1"));
                ws.add(w(x + 3f, x + 4f, y, y + 10f, "2"));
            }
        }
        List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(ws, 10f);
        float groupedBandX1 = (groups - 1) * 14f + 4f;

        List<StreamTableExtractor.Gutter> gutters = assertDoesNotThrow(
                () -> StreamTableExtractor.findGutters(lines, 0f, groupedBandX1, 5f),
                "a strictly ADDITIVE secondary pass must not be able to destroy the primary result");
        assertTrue(gutters.size() >= 900,
                "the primary search's own ~" + (groups - 1) + " gutters must survive the additive "
                        + "pass's overflow; got " + gutters.size());
    }

    /** ...and the PRIMARY search's own budget must still abort the block, so swallowing the secondary
     *  pass's overflow cannot be mistaken for having removed the real DoS backstop. */
    @Test
    void primaryGutterSearchStillAbortsWhenItsOwnBudgetBlows() {
        List<StreamTableExtractor.Line> lines = brickOffsetBlock();
        assertThrows(TableExtractor.RulingOverflowException.class,
                () -> StreamTableExtractor.findGutters(lines, 0f, brickBandX1(), 6f),
                "the primary search must still abort on the dense adversarial shape");
    }

    // ------------------------------------------------ search work leaves the finder, and is bounded

    /** The gutter search's charged work now leaves the finder, so page and document budgets can see
     *  it. An ABORTED search must still report what it burned -- forgiving it would hand a hostile
     *  document a free multiplier (64 pages x 6 blocks x an aborting search is real time). */
    @Test
    void abortedGutterSearchStillReportsTheWorkItBurned() {
        List<StreamTableExtractor.Line> lines = brickOffsetBlock();
        long[] work = {0};
        assertThrows(TableExtractor.RulingOverflowException.class,
                () -> StreamTableExtractor.findGutters(lines, 0f, brickBandX1(), 6f, work));
        assertTrue(work[0] > StreamTableExtractor.MAX_GUTTER_SCAN_WORK / 2,
                "an aborted search must have charged most of its budget, got " + work[0]);
    }

    /** Threading a caller's running total through must not change WHEN a single search aborts: the
     *  per-call {@code MAX_GUTTER_SCAN_WORK} semantics are levied on that call's own delta. */
    @Test
    void callerRunningTotalDoesNotChangePerCallAbortBehaviour() {
        List<StreamTableExtractor.Line> lines = regularGrid(3, 200);
        long[] fresh = {0};
        List<StreamTableExtractor.Gutter> a =
                StreamTableExtractor.findGutters(lines, 0f, bandX1(200), 5f, fresh);
        long preload = StreamTableExtractor.MAX_GUTTER_SCAN_WORK * 3;
        long[] preloaded = {preload};
        List<StreamTableExtractor.Gutter> b =
                StreamTableExtractor.findGutters(lines, 0f, bandX1(200), 5f, preloaded);
        assertEquals(a.size(), b.size(),
                "a caller's already-spent total must not make this call abort earlier");
        assertEquals(fresh[0], preloaded[0] - preload,
                "the call must charge the same delta either way");
        assertTrue(fresh[0] > 0, "the search must charge something on a real block");
    }

    /** The per-page search budget exists, is exhaustible, and exhaustion is REPORTED. Without it, a
     *  page split into K blocks that each drive the search to its own per-call budget costs K x 335ms
     *  with only the obstacle TOTAL bounded -- constructible at ~67s on one page. */
    @Test
    void pageFinderBudgetIsExhaustibleAndReported() {
        StreamTableExtractor.PageAccount account = new StreamTableExtractor.PageAccount();
        assertFalse(account.finderExhausted(), "a fresh page has its whole search budget");
        assertFalse(account.truncated);
        account.finderWork[0] = StreamTableExtractor.MAX_STREAM_PAGE_FINDER_WORK + 1;
        assertTrue(account.finderExhausted(), "over-budget search work must stop further blocks");
        assertTrue(account.truncated, "and must be reported, never silent");
    }

    /**
     * The page grid budget must cut the page's REMAINDER, never retract what earlier blocks already
     * produced -- the same discipline the document-level budgets use. Pinned deterministically on a
     * small fixture by injecting a budget that covers the first block and not the second, which is why
     * {@link StreamTableExtractor.PageAccount} takes explicit budgets.
     */
    @Test
    void pageGridBudgetExhaustionKeepsTheHitsEarlierBlocksAlreadyProduced() {
        // two identical, well-separated 4x4 numeric blocks on one page
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int b = 0; b < 2; b++) {
            for (int r = 0; r < 4; r++) {
                float y = b * 400f + r * 12f;
                for (int c = 0; c < 4; c++) {
                    ws.add(w(c * 40f, c * 40f + 12f, y, y + 10f, String.valueOf(1000 + r * 4 + c)));
                }
            }
        }
        List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(ws, 10f);
        List<List<StreamTableExtractor.Line>> blocks = StreamTableExtractor.splitIntoBlocks(lines);
        assertEquals(2, blocks.size(), "fixture must split into exactly two blocks");

        long oneBlockCharge = StreamTableExtractor.gridWorkFor(4, 16);
        // Enough for Step A''s two probes plus ONE emit-loop block, not two.
        StreamTableExtractor.PageAccount tight =
                new StreamTableExtractor.PageAccount(3 * oneBlockCharge, Long.MAX_VALUE / 4);
        assertTrue(tight.afford(oneBlockCharge), "the first block must be affordable");
        assertTrue(tight.afford(oneBlockCharge), "and the second");
        assertTrue(tight.afford(oneBlockCharge), "and the third");
        assertFalse(tight.afford(oneBlockCharge), "the fourth must not be, and must flag truncated");
        assertTrue(tight.truncated);
        assertEquals(3 * oneBlockCharge, tight.gridWork,
                "a refused charge must not be added -- the block does no work and is not billed");
    }

    /** Grid and search work are summed into ONE document denomination, with search CONVERTED (not
     *  added raw) so the document budget means something in wall clock. */
    @Test
    void totalWorkCombinesGridAndSearchInOneDenomination() {
        StreamTableExtractor.PageAccount account = new StreamTableExtractor.PageAccount();
        assertTrue(account.afford(1_000));
        account.finderWork[0] = 32_000;
        assertEquals(1_000 + 32_000 / StreamTableExtractor.FINDER_WORK_PER_GRID_UNIT,
                account.totalWork());
        assertTrue(StreamTableExtractor.FINDER_WORK_PER_GRID_UNIT > 1,
                "search units are cheaper per unit than grid units and must be scaled, not added raw");
    }
}
