package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @TempDir
    Path tmp;

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

    // ==========================================================================================
    // F1. A LOST CONTEST MUST BE OBSERVABLE -- BUT ONLY A CONTENT-LOSING ONE MAY SET truncated.
    //
    // Arbitration is the only path in TableExtractor that discards a fully-built, successfully
    // extracted table on a QUALITY judgement rather than on a cap or an error. Every sibling drop
    // path (extractTagged's three catches, the per-page lattice catches, the document lattice
    // budget, extractStreamPage's caps, capTablesPerPage) sets Result.truncated on a genuine
    // hostile-input cap or error. Arbitration used to take no Result at all, so a region whose
    // correct table was arbitrated away was byte-identical in report.json to a region that never
    // had a table -- which is exactly why the eu-003 corpus regression (adjacency F1 1.0000 ->
    // 0.7149) went unexplained. THAT fix (round 1) set truncated on EITHER side losing, which
    // over-corrected: a ruled/tagged win is arbitration's ORDINARY, HEALTHY outcome (it wins the
    // large majority of real contests), and the output for that region is then byte-identical to
    // what the flag-off pipeline would have produced -- nothing the user would otherwise have seen
    // is missing. Flagging tablesTruncated there is noise, measured on 25 of the 77-document
    // ICDAR/tabula corpus (44 total minus 19 genuine). The counter and the advisory per-hit markers
    // below are UNCONDITIONAL (either side losing is always counted and always flagged on the
    // winner); only Result.truncated is conditioned on which side lost.
    // ==========================================================================================

    @Test
    void aRuledCandidateLostToStreamIsReportedNotSilent() {
        TableExtractor.TableHit l = lattice(0, 0, 100, 100, 20, 5, 25);   // occupancy 0.25
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 20, 5, 0.90);
        TableExtractor.Result r = new TableExtractor.Result();
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s), r);

        assertEquals(List.of(s), out, "sanity: the partially ruled grid loses this contest");
        assertTrue(r.truncated,
                "a drawn-ruling table discarded in favor of a stream candidate is content the "
                        + "flag-off pipeline would have emitted and this output does not have: "
                        + "truncated must fire, exactly as every other drop path in TableExtractor does");
        assertEquals(1, r.arbitrationDisplaced, "exactly one candidate was displaced");
        assertEquals(Boolean.TRUE, s.displacedRuledCandidate,
                "the surviving stream hit must record that it replaced a drawn-ruling candidate");
        assertNull(s.displacedStreamCandidate, "the winner displaced ruled, not stream");
    }

    @Test
    void aStreamCandidateLostToTheRulingsDoesNotSetTruncated() {
        // THE CENTRAL CASE THIS FIX PINS. The complete drawn grid is the BETTER answer and wins on
        // the merits -- this is exactly what the flag-off (ruled/tagged-only) pipeline would also
        // have emitted for this region, so nothing the user would otherwise have seen is missing.
        // The contest is still fully TRACKED (arbitrationDisplaced, the advisory marker) so a
        // consumer or a test can still see it happened; it must not be reported as truncated,
        // which every other path in this class reserves for genuine incompleteness.
        TableExtractor.TableHit l = lattice(0, 0, 100, 100, 10, 3, 30);   // occupancy 1.00
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 10, 3, 0.95);
        TableExtractor.Result r = new TableExtractor.Result();
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s), r);

        assertEquals(List.of(l), out, "sanity: the complete drawn grid wins this contest");
        assertFalse(r.truncated,
                "the ruled side won: this document's tables are byte-identical to what "
                        + "--stream-tables off would have produced here, so nothing is truncated");
        assertEquals(1, r.arbitrationDisplaced,
                "the contest is still counted even though it did not cost any content");
        assertEquals(Boolean.TRUE, l.displacedStreamCandidate,
                "the surviving ruled hit must still record that it displaced a borderless candidate");
        assertNull(l.displacedRuledCandidate);
    }

    @Test
    void everyDiscardedCandidateOfALostComponentIsCounted() {
        // Three ruling fragments lose one region to a single stream candidate: the count is the
        // number of candidates that did not reach the output, not the number of regions. This is
        // the content-losing direction (ruled fragments dropped), so truncated must also fire.
        TableExtractor.TableHit f1 = lattice(0, 0, 100, 30, 5, 2, 10);
        TableExtractor.TableHit f2 = lattice(0, 30, 100, 60, 5, 2, 10);
        TableExtractor.TableHit f3 = lattice(0, 60, 100, 100, 5, 2, 10);
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 15, 3, 0.90);
        TableExtractor.Result r = new TableExtractor.Result();
        List<TableExtractor.TableHit> out =
                TableExtractor.arbitrate(List.of(f1, f2, f3), List.of(s), r);
        assertEquals(List.of(s), out);
        assertTrue(r.truncated, "three ruled fragments are missing from this output: truncated must fire");
        assertEquals(3, r.arbitrationDisplaced, "all three discarded fragments must be counted");
        assertEquals(Boolean.TRUE, s.displacedRuledCandidate);
    }

    @Test
    void anUncontestedRegionIsNeverFlaggedAndNeverMarked() {
        TableExtractor.TableHit l = lattice(0, 0, 100, 100, 10, 3, 30);
        TableExtractor.TableHit s = stream(0, 400, 100, 500, 10, 3, 0.9);
        TableExtractor.Result r = new TableExtractor.Result();
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s), r);
        assertEquals(2, out.size());
        assertFalse(r.truncated, "nothing was discarded, so nothing may be flagged");
        assertEquals(0, r.arbitrationDisplaced);
        assertNull(l.displacedStreamCandidate, "an uncontested hit carries no arbitration marker");
        assertNull(l.displacedRuledCandidate);
        assertNull(s.displacedStreamCandidate);
        assertNull(s.displacedRuledCandidate);
    }

    @Test
    void theTwoArgOverloadDecidesExactlyAsTheThreeArgOneDoes() {
        // The harnesses and most tests call the 2-arg form. It must be a pure convenience wrapper:
        // same decision, same markers -- only the loss REPORT goes nowhere.
        TableExtractor.TableHit l = lattice(0, 0, 100, 100, 20, 5, 25);
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 20, 5, 0.90);
        assertEquals(List.of(s), TableExtractor.arbitrate(List.of(l), List.of(s)));
        assertEquals(Boolean.TRUE, s.displacedRuledCandidate,
                "the advisory marker is part of the decision, not of the reporting");
    }

    // ==========================================================================================
    // F2. GRID COVERAGE MUST COUNT SPANS.
    //
    // buildTable emits ONE CellHit per MERGED cell carrying rowSpan/colSpan, so cells.size() /
    // (rowCount*colCount) under-counts any complete grid with a merged title/header/TOTAL row and
    // reads it as "partially ruled".
    // ==========================================================================================

    /** A fully covered rows x cols grid in which each listed row is ONE full-width merged cell. */
    private static TableExtractor.TableHit latticeWithMergedRows(float x0, float y0, float x1,
                                                                float y1, int rows, int cols,
                                                                int... mergedRows) {
        TableExtractor.TableHit t = hit("lattice", 1, x0, y0, x1, y1, rows, cols, 0, null);
        outer:
        for (int row = 0; row < rows; row++) {
            for (int m : mergedRows) {
                if (m == row) { t.cells.add(cell(row, 0, 1, cols, t.bbox)); continue outer; }
            }
            for (int c = 0; c < cols; c++) t.cells.add(cell(row, c, 1, 1, t.bbox));
        }
        return t;
    }

    private static TableExtractor.CellHit cell(int row, int col, int rowSpan, int colSpan, float[] bbox) {
        TableExtractor.CellHit c = new TableExtractor.CellHit();
        c.row = row; c.col = col; c.rowSpan = rowSpan; c.colSpan = colSpan;
        c.text = "x"; c.bbox = bbox.clone();
        return c;
    }

    @Test
    void aCompleteGridWithMergedRowsIsNotReadAsPartiallyRuled() {
        // 6x4 fully covered: rows 0 and 5 are single full-width merged cells (a title row and a
        // TOTAL row). 18 CellHits cover all 24 slots -- 18/24 = 0.750 is BELOW the 0.80 floor, so
        // counting cells instead of spans handed this complete drawn grid to a 0.66-confidence
        // stream guess.
        TableExtractor.TableHit l = latticeWithMergedRows(0, 0, 400, 120, 6, 4, 0, 5);
        assertEquals(18, l.cells.size(), "sanity: fewer CellHits than slots, because of the merges");
        TableExtractor.TableHit s = stream(0, 0, 400, 120, 5, 4, 0.66);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(List.of(l), out,
                "every one of the 24 slots is covered by a cell: this grid is fully ruled and must win");
    }

    @Test
    void theRealMergedHeaderFixtureKeepsItsRegionAgainstAConfidentStreamGuess() throws Exception {
        // The repo's OWN fixture, extracted through the REAL lattice path: a complete ruled 2x2
        // whose header is one ColSpan=2 cell, asserted correct by
        // TableLatticeTest#mergedHeaderProducesColSpan2. It carries 3 CellHits over 4 slots, so the
        // cells.size() ratio was 0.750 and it lost to any 2-row stream candidate at confidence 0.65.
        Path pdf = tmp.resolve("merged.pdf");
        TableTestPdfs.mergedHeader(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            Map<Integer, List<TextPosition>> glyphs = new LinkedHashMap<>();
            glyphs.put(1, TableTestPdfs.harvestGlyphs(doc, 0));
            TableExtractor.Result ruled = TableExtractor.extract(doc, List.of(1), glyphs);
            assertEquals(1, ruled.tables.size(), "fixture must produce exactly one lattice table");
            TableExtractor.TableHit l = ruled.tables.get(0);
            assertEquals(2, l.rowCount);
            assertEquals(2, l.colCount);
            assertEquals(3, l.cells.size(),
                    "sanity: the merged header means 3 CellHits cover the 4 declared slots");

            TableExtractor.TableHit s = stream(l.bbox[0], l.bbox[1], l.bbox[2], l.bbox[3], 2, 2, 0.66);
            TableExtractor.Result r = new TableExtractor.Result();
            List<TableExtractor.TableHit> out =
                    TableExtractor.arbitrate(List.of(l), List.of(s), r);
            assertEquals(List.of(l), out,
                    "a COMPLETE drawn grid, asserted correct elsewhere in this suite, must not be "
                            + "discarded as 'partially ruled' because one of its cells is merged");
            assertEquals(1, r.arbitrationDisplaced, "the stream candidate is what lost here");
            assertFalse(r.truncated,
                    "the ruled side won: output here matches the flag-off pipeline, so truncated "
                            + "must not fire even though the contest is still counted");
        }
    }

    @Test
    void aGenuinelyPartialGridWithMergedCellsStillLoses() {
        // ATTACK ON THE F2 FIX: counting spans must not amount to disabling the occupancy clause.
        // 20x5 declared = 100 slots; five 2x2 merged cells cover 20 of them (0.20).
        TableExtractor.TableHit l = hit("lattice", 1, 0, 0, 100, 400, 20, 5, 0, null);
        for (int i = 0; i < 5; i++) l.cells.add(cell(i * 2, 0, 2, 2, l.bbox));
        TableExtractor.TableHit s = stream(0, 0, 100, 400, 20, 5, 0.90);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(List.of(s), out,
                "a grid whose SPANS still cover only a fifth of it is genuinely partially ruled");
    }

    @Test
    void overDeclaredSpansCannotScoreAboveFullCoverage() {
        // ATTACK ON THE F2 FIX: hostile/incoherent spans must not overflow the ratio. A 2x2 grid
        // declaring one 99x99 cell covers "9801" slots of 4 -- clamped to 1.0, never negative or
        // wrapped, and the candidate is treated as fully ruled rather than as anything exotic.
        TableExtractor.TableHit l = hit("lattice", 1, 0, 0, 100, 100, 2, 2, 0, null);
        l.cells.add(cell(0, 0, 99, 99, l.bbox));
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 2, 2, 0.99);
        assertEquals(List.of(l), TableExtractor.arbitrate(List.of(l), List.of(s)));
    }

    // ==========================================================================================
    // F3. THE RULED SIDE'S ROW COUNT IS COMPONENT-WIDE.
    //
    // ruledRows used to be Math.max over the ruled members while the stream side contributed its
    // whole-region count, so both the row-coverage floor and the under-segmentation ratio compared
    // the stream side's total against ONE fragment -- and fired mechanically as the fragment count
    // grew, which is exactly the case the component traversal exists to handle.
    // ==========================================================================================

    @Test
    void threeDisjointCompleteGridsAreNotDeletedByOneMergedStreamCandidate() {
        // Three PERFECT, disjoint 5x3 tables (occupancy 1.00 each) and one stream candidate that
        // merged across the gaps between them. ruledRows = max(5,5,5) = 5 made 15/5 = 3.0 >= 2.5
        // read as "the rulings under-segment rows" -- they do not; they segmented them correctly and
        // the stream candidate is the one that merged three tables into one.
        TableExtractor.TableHit t1 = lattice(0, 0, 400, 100, 5, 3, 15);
        TableExtractor.TableHit t2 = lattice(0, 150, 400, 250, 5, 3, 15);
        TableExtractor.TableHit t3 = lattice(0, 300, 400, 400, 5, 3, 15);
        TableExtractor.TableHit big = stream(0, 0, 400, 400, 15, 3, 0.70);
        TableExtractor.Result r = new TableExtractor.Result();
        List<TableExtractor.TableHit> out =
                TableExtractor.arbitrate(List.of(t1, t2, t3), List.of(big), r);
        assertTrue(has(out, t1), "a complete drawn grid must not be deleted for having neighbours");
        assertTrue(has(out, t2));
        assertTrue(has(out, t3));
        assertEquals(3, out.size(), "the merged stream candidate is the one that loses");
        assertEquals(1, r.arbitrationDisplaced);
        assertFalse(r.truncated,
                "the ruled side won: output here matches the flag-off pipeline, so truncated "
                        + "must not fire even though the contest is still counted");
    }

    @Test
    void theRowCoverageFloorMeasuresTheWholeFragmentedRuledSide() {
        // Three stacked ruling fragments of 10 rows = 30 ruled rows in the region. A stream
        // candidate that read 10 of them covers a third of it, and taking the region would drop 20
        // rows of content -- but against max(10,10,10) it looked like FULL coverage.
        TableExtractor.TableHit f1 = lattice(0, 0, 400, 100, 10, 4, 20);   // occupancy 0.50
        TableExtractor.TableHit f2 = lattice(0, 100, 400, 200, 10, 4, 20);
        TableExtractor.TableHit f3 = lattice(0, 200, 400, 300, 10, 4, 20);
        TableExtractor.TableHit s = stream(0, 0, 400, 300, 10, 4, 0.90);
        List<TableExtractor.TableHit> out =
                TableExtractor.arbitrate(List.of(f1, f2, f3), List.of(s), new TableExtractor.Result());
        assertTrue(has(out, f1), "the content-loss guard must see all 30 ruled rows, not 10");
        assertTrue(has(out, f2));
        assertTrue(has(out, f3));
        assertEquals(3, out.size());
    }

    @Test
    void sideBySideRulingFragmentsDescribeTheSameRowsAndAreNotAdded() {
        // ATTACK ON THE F3 FIX, and a real corpus regression it caused before being corrected. The
        // first version of this fix SUMMED rowCount over the component, which is right for STACKED
        // fragments and wrong for fragments split along x: eu-022 page 2 carries two 15x2 halves at
        // x 58-210 and 210-318 over the SAME 15 rows, a blind sum called that 30 ruled rows, the
        // row-coverage floor then refused a 14-row stream candidate that had covered the region
        // fine, and that document lost 0.0215 adjacency F1. The ruled side's row count is the row
        // count of the UNION: max within a y-overlapping band, summed across disjoint bands.
        TableExtractor.TableHit left = lattice(58, 90, 210, 270, 15, 2, 29);
        TableExtractor.TableHit right = lattice(210, 104, 318, 270, 15, 2, 30);
        TableExtractor.TableHit s = stream(63, 111, 354, 272, 14, 5, 0.93);
        List<TableExtractor.TableHit> out =
                TableExtractor.arbitrate(List.of(left, right), List.of(s));
        assertEquals(List.of(s), out,
                "two side-by-side halves describe 15 ruled rows, not 30: a 14-row stream candidate "
                        + "that resolves 5 columns where the rulings found 2 must still win");
    }

    @Test
    void aFragmentedRuledSideThatGenuinelyUnderSegmentsStillLoses() {
        // ATTACK ON THE F3 FIX: summing rows must not amount to disabling clause 6. Three ruling
        // fragments of ONE tall band each = 3 ruled rows over a region stream reads as 15 rows.
        TableExtractor.TableHit f1 = lattice(0, 0, 400, 100, 1, 4, 4);
        TableExtractor.TableHit f2 = lattice(0, 100, 400, 200, 1, 4, 4);
        TableExtractor.TableHit f3 = lattice(0, 200, 400, 300, 1, 4, 4);
        TableExtractor.TableHit s = stream(0, 0, 400, 300, 15, 4, 0.90);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(f1, f2, f3), List.of(s));
        assertEquals(List.of(s), out,
                "15 text rows against 3 ruled bands is real under-segmentation and must still win");
    }

    // ==========================================================================================
    // F4. GRID OCCUPANCY IS DECIDED PER CANDIDATE, NEVER POOLED.
    //
    // declaredSlots is quadratic in a candidate's DECLARED shape, so summing it across a component
    // let one junk fragment dominate the denominator and condemn every correct table transitively
    // chained to it through the contest graph.
    // ==========================================================================================

    @Test
    void aJunkRuledFragmentDoesNotCondemnThePerfectTablesChainedToIt() {
        // Contest chain  c1 -- sA -- junk -- sB -- c3  is ONE component. c1 and c3 are PERFECT
        // grids; junk declares 10x12 (120 slots) and fills 15. Pooled: (20+15+40)/(20+120+40) =
        // 0.417 < 0.80, so both perfect tables were deleted for their neighbour's occupancy.
        TableExtractor.TableHit c1 = lattice(0, 0, 400, 100, 5, 4, 20);        // occupancy 1.00
        TableExtractor.TableHit junk = lattice(0, 90, 400, 170, 10, 12, 15);   // occupancy 0.125
        TableExtractor.TableHit c3 = lattice(0, 200, 400, 400, 10, 4, 40);     // occupancy 1.00
        TableExtractor.TableHit sA = stream(0, 0, 400, 180, 9, 4, 0.70);
        TableExtractor.TableHit sB = stream(0, 120, 400, 400, 12, 4, 0.70);
        List<TableExtractor.TableHit> out =
                TableExtractor.arbitrate(List.of(c1, junk, c3), List.of(sA, sB));
        assertTrue(has(out, c1), "a complete grid must never be condemned by a neighbour's occupancy");
        assertTrue(has(out, c3));
    }

    @Test
    void aComponentWhoseEveryRuledMemberIsPartiallyRuledStillLoses() {
        // ATTACK ON THE F4 FIX: per-candidate must not amount to "any complete member saves the
        // component". Two fragments, BOTH genuinely partial (0.25), and the region goes to stream.
        TableExtractor.TableHit p1 = lattice(0, 0, 400, 100, 10, 4, 10);
        TableExtractor.TableHit p2 = lattice(0, 100, 400, 200, 10, 4, 10);
        TableExtractor.TableHit s = stream(0, 0, 400, 200, 20, 4, 0.90);
        assertEquals(List.of(s), TableExtractor.arbitrate(List.of(p1, p2), List.of(s)));
    }

    // ==========================================================================================
    // F5. THE CONTENT-LOSS GUARD HAS A COLUMN HALF.
    //
    // ARB_MIN_ROW_COVERAGE only ever bounded rows, yet content loss is measured in CELLS: a stream
    // candidate that resolved 2 of a region's 8 columns concatenates six fields into one cell on
    // every row it did read.
    // ==========================================================================================

    @Test
    void aStreamCandidateCoveringAFractionOfTheColumnsNeverTakesTheRegion() {
        // 20 rows x 8 columns, 80 of 160 slots filled (occupancy 0.50 -> clause 4 would fire) and
        // stream covers every row -- but only 2 of the 8 columns. Handing it the region concatenates
        // six columns of data into two cells per row: 20 rows kept, 120 cells lost.
        TableExtractor.TableHit wide = lattice(0, 0, 500, 200, 20, 8, 80);
        TableExtractor.TableHit narrow = stream(0, 0, 500, 200, 20, 2, 0.70);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(wide), List.of(narrow));
        assertEquals(List.of(wide), out,
                "a stream candidate that read a quarter of the region's columns must not take it");
    }

    @Test
    void theColumnFloorIsAFloorNotAnEqualityRequirement() {
        // ATTACK ON THE F5 FIX: the floor must not stop stream from winning a region it really did
        // read. 8 ruled columns, stream resolves 7 (0.875 >= 0.75) over a partially ruled grid.
        TableExtractor.TableHit wide = lattice(0, 0, 500, 200, 20, 8, 80);
        TableExtractor.TableHit s = stream(0, 0, 500, 200, 20, 7, 0.70);
        assertEquals(List.of(s), TableExtractor.arbitrate(List.of(wide), List.of(s)));
    }

    // ==========================================================================================
    // F7. DEGENERATE AND NON-FINITE CANDIDATES TAKE PART IN NO CONTEST.
    //
    // Both builders reject a candidate below 2x2, so none of this is a live production path -- it
    // is a stated invariant on a package-private API that tests and harnesses call directly.
    // ==========================================================================================

    @Test
    void anEmptyStreamCandidateNeverWinsARegion() {
        TableExtractor.TableHit l = lattice(0, 0, 400, 200, 20, 5, 25);   // occupancy 0.25
        TableExtractor.TableHit s = stream(0, 0, 400, 200, 20, 5, 0.99);
        s.cells = new ArrayList<>();                                       // no content at all
        assertEquals(List.of(l), TableExtractor.arbitrate(List.of(l), List.of(s)),
                "a candidate carrying no cells is not evidence and must never be handed a region");
    }

    @Test
    void aDegenerateRuledShapeIsNotCondemnedByItsOwnEmptyDeclaredGrid() {
        // 10 rows x 0 cols declares ZERO slots. The pooled arithmetic turned that into occupancy
        // 0.0 -- "entirely unruled" -- and handed the region away on absent evidence.
        TableExtractor.TableHit l = hit("lattice", 1, 0, 0, 400, 200, 10, 0, 0, null);
        l.cells.add(cell(0, 0, 1, 1, l.bbox));
        l.cells.add(cell(1, 0, 1, 1, l.bbox));
        TableExtractor.TableHit s = stream(0, 0, 400, 200, 8, 3, 0.70);
        TableExtractor.Result r = new TableExtractor.Result();
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s), r);
        assertTrue(has(out, l), "a shape with no declared slots is no evidence either way");
        assertEquals(2, out.size(), "no contest: a degenerate shape contests nothing");
        assertFalse(r.truncated, "nothing was discarded, so nothing may be flagged");
    }

    @Test
    void aZeroByZeroRuledCandidateDoesNotLoseToAnEmptyStreamCandidate() {
        TableExtractor.TableHit l = hit("lattice", 1, 0, 0, 400, 200, 0, 0, 0, null);
        TableExtractor.TableHit s = hit("stream", 1, 0, 0, 400, 200, 0, 0, 0, 0.90);
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s));
        assertEquals(2, out.size(), "two degenerate shapes are not a contest either");
        assertTrue(has(out, l));
    }

    @Test
    void aNonFiniteBboxContestsNothing() {
        // Same documented treatment as a null bbox (see aCandidateWithNoBboxIsNeverArbitratedAway):
        // a candidate whose geometry cannot be compared cannot be shown to contest anything, so it
        // is never dropped -- by rule now, rather than by NaN comparisons happening to be false.
        TableExtractor.TableHit l = lattice(0, 0, 400, 200, 20, 4, 20);
        TableExtractor.TableHit s = stream(Float.NaN, Float.NaN, Float.NaN, Float.NaN, 20, 4, 0.90);
        TableExtractor.Result r = new TableExtractor.Result();
        List<TableExtractor.TableHit> out = TableExtractor.arbitrate(List.of(l), List.of(s), r);
        assertEquals(2, out.size());
        assertFalse(r.truncated);
        TableExtractor.TableHit inf = stream(0, 0, Float.POSITIVE_INFINITY, 200, 20, 4, 0.90);
        assertEquals(2, TableExtractor.arbitrate(List.of(l), List.of(inf)).size());
    }

    @Test
    void aNonFiniteStreamConfidenceIsNoEvidence() {
        TableExtractor.TableHit l = lattice(0, 0, 400, 200, 20, 5, 25);   // occupancy 0.25
        TableExtractor.TableHit s = stream(0, 0, 400, 200, 20, 5, Double.NaN);
        assertEquals(List.of(l), TableExtractor.arbitrate(List.of(l), List.of(s)),
                "NaN is not a confidence: it must be treated as no evidence, like null");
    }

    // ==========================================================================================
    // F6 (REVIEWED, DELIBERATELY NOT CHANGED). The component-wide tagged veto stays absolute.
    //
    // The review asked for it to be scoped or made advisory, because a 2x2 HTML layout wrapper
    // whose bbox spans the page deletes a real borderless table nested inside it. That reproduces.
    // But TableTestPdfs#taggedHollowMiddleTwoDenseBlocksPlusDistinctRuledTableInGap presents
    // arbitration with the same shapes and requires the OPPOSITE outcome (tagged 2x2 spanning the
    // page, stream 10x4 conf 0.725 x2, which must LOSE -- see
    // TableStreamWiringTest#aTaggedTableIsNeverLostWhenTheStreamStageIsEnabled). Every scoping
    // signal available here fires on both or neither. These tests pin the decision so it is a
    // decision, not an omission -- and pin that the drop is at least now VISIBLE.
    // ==========================================================================================

    @Test
    void aTaggedCandidateStillVetoesStreamNoMatterHowManyMoreRowsStreamReads() {
        TableExtractor.TableHit tagged = hit("tagged", 1, 0, 0, 400, 700, 2, 2, 4, null);
        TableExtractor.TableHit s = stream(20, 100, 380, 600, 30, 5, 0.85);
        assertEquals(List.of(tagged), TableExtractor.arbitrate(List.of(tagged), List.of(s)),
                "the structure tree stays authoritative: this is a measured-follow-up decision, "
                        + "not a threshold to turn here");
    }

    @Test
    void aStreamCandidateLostToTheTaggedVetoIsTrackedButDoesNotSetTruncated() {
        // The veto keeps the tagged answer -- the author's own structure tree -- which is exactly
        // what the flag-off pipeline emits for this region too. Nothing the user would otherwise
        // have seen is missing, so this must not be reported as truncated (see the F1 block comment
        // above): the drop is still VISIBLE (arbitrationDisplaced, the advisory marker), just not
        // through the field that means "content may be missing".
        TableExtractor.TableHit tagged = hit("tagged", 1, 0, 0, 400, 700, 2, 2, 4, null);
        TableExtractor.TableHit s = stream(20, 100, 380, 600, 30, 5, 0.85);
        TableExtractor.Result r = new TableExtractor.Result();
        TableExtractor.arbitrate(List.of(tagged), List.of(s), r);
        assertFalse(r.truncated,
                "the tagged answer won: output here matches the flag-off pipeline, so truncated "
                        + "must not fire");
        assertEquals(1, r.arbitrationDisplaced, "the veto is a hard suppression; it must at least "
                + "not be an invisible one");
        assertEquals(Boolean.TRUE, tagged.displacedStreamCandidate);
    }

    @Test
    void aTaggedCandidateSurvivesEvenWhenTheComponentGoesToStream() {
        // STRUCTURAL invariant, belt-and-braces with clause 2: a tagged member sharing a component
        // with a losing lattice fragment must still be emitted. Unreachable today (clause 2 refuses
        // STREAM_WINS whenever a tagged member is present); asserted so it stays true if clause 2 is
        // ever relaxed.
        TableExtractor.TableHit tagged = hit("tagged", 1, 0, 0, 100, 100, 20, 5, 100, null);
        TableExtractor.TableHit partial = lattice(0, 0, 100, 100, 20, 5, 25);
        TableExtractor.TableHit s = stream(0, 0, 100, 100, 20, 5, 0.90);
        List<TableExtractor.TableHit> out =
                TableExtractor.arbitrate(List.of(tagged, partial), List.of(s));
        assertTrue(has(out, tagged), "a tagged candidate is never arbitrated away, by any route");
    }
}
