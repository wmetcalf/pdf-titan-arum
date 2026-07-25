package com.oai.titanarum;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PER-REGION PATH ARBITRATION ({@link TableExtractor#arbitrate}).
 *
 * <p>Two extraction paths can both produce a candidate for the same region of a page: the
 * drawn-ruling paths ("tagged"/"lattice") and the whitespace path ("stream"). Before this class
 * existed the merge rule was purely positional -- a stream candidate was dropped whenever a
 * ruling-derived candidate covered it -- so the drawn-ruling answer always won a contest it was
 * often losing on the merits. These tests pin the SIGNALS the selection is allowed to use
 * (grid occupancy, row/column counts, the stream path's own gridness confidence) and, just as
 * importantly, pin the cases where the ruling-derived answer must still win.
 *
 * <p>No test here uses ground truth, a corpus document, or a score: arbitration is a pure function
 * of extraction-time signals, and that is exactly what is asserted.
 */
class TableArbitrationTest {

    // -------------------------------------------------------------------------------- tiny builders

    /** A candidate with a full grid: rows*cols cells, all filled. */
    private static TableExtractor.TableHit hit(String method, int page, float x0, float y0,
                                               float x1, float y1, int rows, int cols,
                                               int cellCount, Double conf) {
        TableExtractor.TableHit t = new TableExtractor.TableHit();
        t.extractionMethod = method;
        t.page = page;
        t.bbox = new float[]{x0, y0, x1, y1};
        t.rowCount = rows;
        t.colCount = cols;
        t.confidence = conf;
        t.cells = new ArrayList<>();
        for (int i = 0; i < cellCount; i++) {
            TableExtractor.CellHit c = new TableExtractor.CellHit();
            c.row = i / Math.max(1, cols);
            c.col = i % Math.max(1, cols);
            c.rowSpan = 1;
            c.colSpan = 1;
            c.text = "x";
            c.bbox = new float[]{x0, y0, x1, y1};
            t.cells.add(c);
        }
        return t;
    }

    private static TableExtractor.TableHit lattice(float x0, float y0, float x1, float y1,
                                                  int rows, int cols, int cells) {
        return hit("lattice", 1, x0, y0, x1, y1, rows, cols, cells, null);
    }

    private static TableExtractor.TableHit stream(float x0, float y0, float x1, float y1,
                                                  int rows, int cols, double conf) {
        return hit("stream", 1, x0, y0, x1, y1, rows, cols, rows * cols, conf);
    }

    private static boolean has(List<TableExtractor.TableHit> out, TableExtractor.TableHit h) {
        for (TableExtractor.TableHit t : out) if (t == h) return true;
        return false;
    }

    // ------------------------------------------------------------------------------ the uncontested

    @Test
    void candidatesThatDoNotOverlapAreAllKept() {
        TableExtractor.TableHit l = lattice(0, 0, 100, 100, 10, 3, 30);
        TableExtractor.TableHit s = stream(0, 400, 100, 500, 10, 3, 0.9);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(2, out.size(), "disjoint regions are not a contest -- both must survive");
        assertTrue(has(out, l));
        assertTrue(has(out, s));
    }

    @Test
    void candidatesOnDifferentPagesAreNeverAContest() {
        TableExtractor.TableHit l = lattice(0, 0, 100, 100, 20, 5, 25);   // occupancy 0.25
        TableExtractor.TableHit s = hit("stream", 2, 0, 0, 100, 100, 20, 5, 100, 0.95);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(2, out.size());
        assertTrue(has(out, l), "a candidate on another page must never lose a contest it is not in");
    }

    // -------------------------------------------------------- when the drawn grid must still win

    @Test
    void aCompleteDrawnGridBeatsAnEquallyShapedStreamCandidate() {
        TableExtractor.TableHit l = lattice(0, 0, 100, 100, 10, 3, 30);    // occupancy 1.00
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 10, 3, 0.95);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(List.of(l), out,
                "a fully ruled grid that agrees with stream on shape is the better answer");
    }

    @Test
    void lowConfidenceStreamNeverWinsEvenAgainstAPartialGrid() {
        TableExtractor.TableHit l = lattice(0, 0, 100, 100, 20, 5, 20);    // occupancy 0.20
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 20, 5, 0.40);   // not confident
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(List.of(l), out,
                "an unconfident whitespace guess must never displace drawn rulings");
    }

    @Test
    void aStreamCandidateWithNoConfidenceAtAllNeverWins() {
        TableExtractor.TableHit l = lattice(0, 0, 100, 100, 20, 5, 20);
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 20, 5, 0.95);
        s.confidence = null;
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(List.of(l), out, "absent confidence must be treated as no evidence, not as 1.0");
    }

    @Test
    void aTaggedCandidateIsNeverOverriddenByStream() {
        // Structure-tree output is authoritative: even a sparse tagged grid facing a very confident
        // stream candidate keeps the region.
        TableExtractor.TableHit tagged = hit("tagged", 1, 0, 0, 100, 100, 20, 5, 20, null);
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 40, 6, 0.99);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(tagged), List.of(s));
        assertEquals(List.of(tagged), out,
                "the structure tree states the author's own table; stream must not displace it");
    }

    // ------------------------------------------------------- when the stream candidate must win

    @Test
    void aPartiallyRuledGridLosesToAConfidentStreamCandidate() {
        // 20x5 declared but only 25 of 100 slots actually carry a cell: the rulings do not form a
        // grid over this region at all.
        TableExtractor.TableHit l = lattice(0, 0, 100, 100, 20, 5, 25);
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 20, 5, 0.90);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(List.of(s), out, "a grid that is mostly missing must lose to a confident stream grid");
    }

    @Test
    void streamWinsWhenItResolvesAColumnTheRulingsMissed() {
        // Same rows, one more column: a ruling set that missed a column separator.
        TableExtractor.TableHit l = lattice(0, 0, 100, 100, 10, 2, 20);    // occupancy 1.00
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 10, 3, 0.90);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(List.of(s), out);
    }

    @Test
    void streamWinsWhenItResolvesManyMoreRowsInTheSameRegion() {
        // Rulings gave 3 tall bands over a region stream reads as 15 rows: the rulings
        // under-segment rows badly.
        TableExtractor.TableHit l = lattice(0, 0, 100, 300, 3, 4, 12);     // occupancy 1.00
        TableExtractor.TableHit s = stream(0, 0, 100, 300, 15, 4, 0.85);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(List.of(s), out);
    }

    // --------------------------------------------- the row-coverage floor (content-loss guard)

    @Test
    void aStreamCandidateCoveringFarFewerRowsNeverTakesTheRegion() {
        // The rulings are badly incomplete (25 of 100 slots) so on grid occupancy alone stream would
        // win -- but stream only read 5 of the region's 20 rows. Handing it the whole region would
        // DROP the other 15 rows of content, which is the outcome this project treats as worst. The
        // partial grid is kept instead.
        TableExtractor.TableHit l = lattice(0, 0, 100, 400, 20, 5, 25);
        TableExtractor.TableHit s = stream(0, 0, 100, 400, 5, 5, 0.90);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(List.of(l), out,
                "a stream candidate that reads a fraction of the region's rows must not replace it");
    }

    @Test
    void anExtraColumnDoesNotWinTheRegionIfStreamReadsOnlyPartOfIt() {
        // Stream resolves 4 columns where the rulings found 3 -- but over 4 rows of a 8-row region.
        // The extra column is not worth losing half the rows.
        TableExtractor.TableHit l = lattice(0, 0, 100, 200, 8, 3, 20);
        TableExtractor.TableHit s = stream(0, 0, 100, 200, 4, 4, 0.90);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(List.of(l), out);
    }

    @Test
    void aStreamCandidateThatCoversTheRowsAndAddsAColumnStillWins() {
        // The floor is a floor, not a requirement to match exactly: stream reading 9 of 10 ruled rows
        // while resolving an extra column still wins.
        TableExtractor.TableHit l = lattice(0, 0, 100, 200, 10, 2, 20);
        TableExtractor.TableHit s = stream(0, 0, 100, 200, 9, 3, 0.90);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(List.of(s), out);
    }

    @Test
    void aModestRowDisagreementIsNotEnoughForStreamToWin() {
        // 12 vs 10 rows is ordinary header/footer disagreement, not under-segmentation.
        TableExtractor.TableHit l = lattice(0, 0, 100, 100, 10, 3, 30);
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 12, 3, 0.90);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(List.of(l), out);
    }

    @Test
    void everyRulingFragmentOfALostRegionIsDroppedTogether() {
        // Rulings split one table into three stacked fragments; stream reads it as one grid with an
        // extra column. Losing the region must remove all three fragments, not just the first --
        // leaving a fragment behind would double-report the same content.
        TableExtractor.TableHit f1 = lattice(0, 0, 100, 30, 5, 2, 10);
        TableExtractor.TableHit f2 = lattice(0, 30, 100, 60, 5, 2, 10);
        TableExtractor.TableHit f3 = lattice(0, 60, 100, 100, 5, 2, 10);
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 15, 3, 0.90);
        List<TableExtractor.TableHit> out =
                TableExtractor.arbitrate(List.of(f1, f2, f3), List.of(s));
        assertEquals(List.of(s), out);
    }

    // ------------------------------------------------------------------------------- housekeeping

    @Test
    void arbitrateDoesNotMutateOrReorderTheSurvivingCandidates() {
        TableExtractor.TableHit l1 = lattice(0, 0, 100, 100, 10, 3, 30);
        TableExtractor.TableHit l2 = lattice(0, 200, 100, 300, 10, 3, 30);
        TableExtractor.TableHit s = stream(0, 400, 100, 500, 4, 4, 0.9);
        List<TableExtractor.TableHit> ruled = new ArrayList<>(List.of(l1, l2));
        List<TableExtractor.TableHit> str = new ArrayList<>(List.of(s));
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(ruled, str);
        assertEquals(2, ruled.size(), "inputs must not be mutated");
        assertEquals(1, str.size());
        assertEquals(3, out.size());
        assertSame(l1, out.get(0), "surviving ruling candidates keep their input order");
        assertSame(l2, out.get(1));
    }

    @Test
    void emptyInputsAreHandled() {
        assertTrue(TableExtractor.arbitrate(List.of(), List.of()).isEmpty());
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 4, 4, 0.9);
        assertEquals(List.of(s), TableExtractor.arbitrate(List.of(), List.of(s)));
        TableExtractor.TableHit l = lattice(0, 0, 100, 100, 4, 4, 16);
        assertEquals(List.of(l), TableExtractor.arbitrate(List.of(l), List.of()));
    }

    @Test
    void aCandidateWithNoBboxIsNeverArbitratedAway() {
        TableExtractor.TableHit l = lattice(0, 0, 100, 100, 20, 5, 20);
        l.bbox = null;
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 20, 5, 0.95);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(2, out.size(), "a candidate with no geometry cannot be shown to contest anything");
    }

    // -------------------------------------------------------------------------------------- DoS

    @Test
    void arbitrationAbortsOnAnAdversarialCandidateExplosion() {
        // Every candidate covers every other, so the contest graph is complete: the pair-comparison
        // work is quadratic. Production caps candidates far below this (50 lattice + 20 stream per
        // page), so only a hostile/synthetic caller can reach the budget -- and when it does,
        // arbitration must abort rather than run unbounded work OR allocate a quadratic matrix.
        List<TableExtractor.TableHit> ruled = new ArrayList<>();
        List<TableExtractor.TableHit> str = new ArrayList<>();
        for (int i = 0; i < 7000; i++) {
            ruled.add(lattice(0, 0, 100, 100, 2, 2, 4));
            str.add(stream(0, 0, 100, 100, 2, 2, 0.9));
        }
        assertThrows(TableExtractor.RulingOverflowException.class,
                () -> TableExtractor.arbitrate(ruled, str),
                "the arbitration work budget must trip on an adversarial candidate explosion");
    }

    @Test
    void aLegitimatelyDenseMultiTablePageStaysWellInsideTheBudget() {
        // The real production ceiling: MAX_TABLES_PER_PAGE (50) ruling candidates and
        // MAX_STREAM_TABLES_PER_PAGE (20) stream candidates on the same page, all mutually
        // overlapping. This must complete, and must complete on every page of a LONG document -- the
        // first version of arbitrate() charged document-wide candidate counts per traversal step and
        // this test caught it aborting here. 800 pages at both caps is far beyond anything the
        // corpus (max 9 ruling + 4 stream on a page, 15 pages) or the prose sample (15 + 1) reaches.
        List<TableExtractor.TableHit> ruled = new ArrayList<>();
        List<TableExtractor.TableHit> str = new ArrayList<>();
        for (int page = 1; page <= 800; page++) {
            for (int i = 0; i < TableExtractor.MAX_TABLES_PER_PAGE; i++) {
                ruled.add(hit("lattice", page, 0, 0, 100, 100, 4, 4, 16, null));
            }
            for (int i = 0; i < StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE; i++) {
                str.add(hit("stream", page, 0, 0, 100, 100, 4, 4, 16, 0.9));
            }
        }
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(ruled, str);
        assertFalse(out.isEmpty(),
                "800 pages at the real per-page candidate caps must arbitrate without aborting");
    }
}
