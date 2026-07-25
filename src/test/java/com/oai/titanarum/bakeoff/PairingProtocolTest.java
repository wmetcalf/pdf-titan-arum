// PACKAGE NOTE: physically under src/test/java/com/oai/titanarum/bakeoff/ (matching BaselineHarness's
// own convention -- see its header) but declares `package com.oai.titanarum;` so it can reach
// BaselineHarness's package-private correspondence rules directly. Nothing here touches the corpus,
// PDFBox, or any file under src/main: every fixture is built by hand, so this test runs in the default
// suite in milliseconds and needs no -D flag.
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.TableScore;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the 1:1 table-correspondence rules the ICDAR 2013 baseline is measured under, and pins the
 * DEFECT that motivated adding the official one.
 *
 * <p>The official rule is transcribed from {@code MeasureRecognitionPerformance.evaluateResultStr}
 * in {@code github.com/tamirhassan/dataset-tools} ("as used in the ICDAR 2013 Table Competition"):
 * for each ground-truth table, over the candidates remaining ON ITS PAGE, keep the one with the
 * greatest number of CORRECT ADJACENCY RELATIONS ({@code highestCorr}, initialised to -1, updated on
 * {@code >} so ties keep the first), remove it from the pool, and charge every leftover candidate as
 * an FP table.
 *
 * <p>The legacy rule instead maximised EXACT-CELL F1 -- a position-identified metric that appears
 * nowhere in the official tool. These tests fix in place both what the official rule does and the
 * specific way the legacy rule fails, so neither can drift back silently.
 */
class PairingProtocolTest {

    // ------------------------------------------------------------------------------- fixtures --

    /** A ground-truth table from a rectangular text grid, all cells 1x1, annotated on {@code page}. */
    private static GroundTruth.Table gt(int page, List<List<String>> rows) {
        List<GroundTruth.Cell> cells = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < rows.get(r).size(); c++) {
                cells.add(new GroundTruth.Cell(r, c, r, c, rows.get(r).get(c), page,
                        0f, 0f, 0f, 0f, false));
            }
        }
        return new GroundTruth.Table(rows, cells);
    }

    /**
     * A detected candidate from a text grid. {@code rowOffset}/{@code colOffset} shift the grid
     * indices without changing the content, which is exactly the situation that zeroes exact-cell F1
     * while leaving every adjacency relation intact.
     */
    private static BaselineHarness.Cand cand(int page, List<List<String>> rows,
                                              int rowOffset, int colOffset) {
        List<TableScore.GridCell> cells = new ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            for (int c = 0; c < rows.get(r).size(); c++) {
                if (rows.get(r).get(c).isEmpty()) continue;
                cells.add(new TableScore.GridCell(r + rowOffset, c + colOffset,
                        r + rowOffset, c + colOffset, rows.get(r).get(c)));
            }
        }
        List<List<String>> shifted = new ArrayList<>();
        for (int i = 0; i < rowOffset; i++) shifted.add(List.of());
        shifted.addAll(rows);
        return new BaselineHarness.Cand(page, shifted, cells);
    }

    private static List<List<String>> grid(String... rows) {
        List<List<String>> out = new ArrayList<>();
        for (String row : rows) out.add(List.of(row.split(",", -1)));
        return out;
    }

    // ---------------------------------------------------------------- the defect, pinned as-is --

    @Test
    @DisplayName("legacy exact-cell-F1 pairing ties at zero and keeps the FIRST candidate, "
            + "scoring a verbatim recovery as 0 matched")
    void legacyPairingTiesAtZeroAndKeepsEnumerationOrder() {
        // One annotated table. Three candidates, all on its page:
        //   [0] a mangled header block that shares NO content with it,
        //   [1] the table recovered VERBATIM but shifted down two grid rows,
        //   [2] another unrelated block.
        // Every candidate scores exact-cell F1 = 0 against the ground truth -- [0] and [2] because
        // their content differs, [1] because exact-cell matching is position-identified and the
        // shift moves every cell off its annotated (row, col). So `f1 > bestF1` never fires after
        // the first candidate and the pairing is decided by enumeration order alone.
        GroundTruth.Table table = gt(1, grid("dose,units", "10mg,ml", "20mg,ml"));
        List<BaselineHarness.Cand> hits = List.of(
                cand(1, grid("HEADER,JUNK"), 0, 0),
                cand(1, grid("dose,units", "10mg,ml", "20mg,ml"), 2, 0),
                cand(1, grid("FOOTER,NOISE"), 0, 0));

        BaselineHarness.Tally legacy = BaselineHarness.e2ePaired(hits, List.of(table));

        assertEquals(0, legacy.matched,
                "legacy pairing kept candidate[0] (enumeration order) and matched nothing, "
                        + "even though candidate[1] recovers the table verbatim");
        assertTrue(legacy.gt > 0, "the ground-truth relations are still in the denominator");

        BaselineHarness.PairAudit audit = BaselineHarness.auditPairing(hits, List.of(table));
        assertEquals(1, audit.tieAtZeroTables, "the audit must recognise this as a tie at zero");
        assertEquals(1, audit.misassignedTables,
                "the audit must recognise that a strictly better candidate was available");
        assertEquals(0, audit.currentCorr);
        assertTrue(audit.bestStepwiseCorr > 0,
                "and must report the correct relations the choice threw away");
        assertEquals(audit.bestStepwiseCorr, audit.lostRelations);
    }

    // ------------------------------------------------------------------ the official rule, pinned --

    @Test
    @DisplayName("official pairing maximises CORRECT ADJACENCY RELATIONS, so it recovers the "
            + "verbatim candidate the legacy rule threw away")
    void officialPairingMaximisesCorrectRelations() {
        GroundTruth.Table table = gt(1, grid("dose,units", "10mg,ml", "20mg,ml"));
        List<BaselineHarness.Cand> hits = List.of(
                cand(1, grid("HEADER,JUNK"), 0, 0),
                cand(1, grid("dose,units", "10mg,ml", "20mg,ml"), 2, 0),
                cand(1, grid("FOOTER,NOISE"), 0, 0));

        BaselineHarness.Tally official = BaselineHarness.e2ePairedOfficial(hits, List.of(table), false);

        int allGtRelations = TableScore.officialRelationCount(
                TableScore.gridCellsFromGroundTruth(table), false, TableScore.Semantics.MULTISET);
        assertEquals(allGtRelations, official.matched,
                "adjacency relations are translation-invariant, so the shifted verbatim candidate "
                        + "matches every ground-truth relation");
        assertEquals(allGtRelations, official.gt);
    }

    @Test
    @DisplayName("official pairing restricts candidates to the ground-truth table's own page")
    void officialPairingAppliesThePageFilter() {
        GroundTruth.Table onPage3 = gt(3, grid("a,b", "c,d"));
        // The perfect candidate is on the WRONG page; the official rule must not reach it.
        List<BaselineHarness.Cand> wrongPage = List.of(cand(7, grid("a,b", "c,d"), 0, 0));

        BaselineHarness.Tally official =
                BaselineHarness.e2ePairedOfficial(wrongPage, List.of(onPage3), false);
        assertEquals(0, official.matched,
                "pageCheck=true: a candidate on another page is not a candidate at all");
        assertEquals(0, official.tables, "and no `Table n:` line is produced for that GT table");

        // Same candidate, right page -> pairs and matches everything.
        List<BaselineHarness.Cand> rightPage = List.of(cand(3, grid("a,b", "c,d"), 0, 0));
        BaselineHarness.Tally paired =
                BaselineHarness.e2ePairedOfficial(rightPage, List.of(onPage3), false);
        assertTrue(paired.matched > 0, "on the right page the same candidate pairs");
        assertEquals(1, paired.tables);
    }

    @Test
    @DisplayName("official pairing keeps the FIRST candidate on a tie, exactly as `>` (not `>=`) does")
    void officialPairingBreaksTiesTowardsTheFirstCandidate() {
        // Two annotated tables on page 1, and two candidates. The FIRST ground-truth table matches
        // NEITHER candidate, so its pairing is a tie at zero; the SECOND is recovered verbatim by
        // candidate[1]. Which candidate the tie consumes is therefore observable in the total matched
        // count: `>` keeps candidate[0], leaving candidate[1] for the table it actually recovered
        // (matched > 0), whereas `>=` would keep candidate[1], consume it on the wrong table, and
        // leave candidate[0] for the second table (matched == 0).
        List<GroundTruth.Table> expected = List.of(
                gt(1, grid("nomatch1,nomatch2")),
                gt(1, grid("dose,units", "10mg,ml")));
        List<BaselineHarness.Cand> hits = List.of(
                cand(1, grid("junkA,junkB"), 0, 0),
                cand(1, grid("dose,units", "10mg,ml"), 0, 0));

        BaselineHarness.Tally t = BaselineHarness.e2ePairedOfficial(hits, expected, false);

        int secondTableRelations = TableScore.officialRelationCount(
                TableScore.gridCellsFromGroundTruth(expected.get(1)), false,
                TableScore.Semantics.MULTISET);
        assertEquals(secondTableRelations, t.matched,
                "the zero-tie on the first table must keep candidate[0] and leave candidate[1] for "
                        + "the table it recovers; keeping the last instead would score 0");
        // And the invariant holds regardless of which way the tie went: every candidate's relations
        // are charged exactly once, paired or as an FP table.
        // candidate[0] is 1x2 -> 1 RIGHT relation; candidate[1] is 2x2 -> 2 RIGHT + 2 DOWN = 4.
        assertEquals(1 + 4, t.detected,
                "every candidate's relations are charged exactly once, paired or not");
    }

    @Test
    @DisplayName("a correspondence rule may move only MATCHED: both denominators are "
            + "pairing-invariant")
    void bothDenominatorsAreIndependentOfTheCorrespondenceRule() {
        // This is the corpus-wide invariant BaselineHarness#printProtocolSelfCheck reports as
        // "denominator mismatches between POOLED and 1:1off : 0", pinned on a fixture so a future
        // change to either rule cannot break it silently.
        List<GroundTruth.Table> expected = List.of(
                gt(1, grid("a,b", "c,d")),
                gt(1, grid("e,f", "g,h")));
        List<BaselineHarness.Cand> hits = List.of(
                cand(1, grid("e,f", "g,h"), 0, 0),      // deliberately in the WRONG order
                cand(1, grid("a,b", "c,d"), 0, 0),
                cand(1, grid("spurious,block"), 0, 0));

        BaselineHarness.Tally legacy = BaselineHarness.e2ePaired(hits, expected);
        BaselineHarness.Tally official = BaselineHarness.e2ePairedOfficial(hits, expected, false);

        assertEquals(legacy.gt, official.gt, "ground-truth total cannot depend on pairing");
        assertEquals(legacy.detected, official.detected, "detected total cannot depend on pairing");
        assertTrue(official.matched >= legacy.matched,
                "argmax over the matched count can only raise or hold MATCHED -- which is exactly "
                        + "why 'the fix raised our score' is not evidence that the fix is correct");
    }

    @Test
    @DisplayName("the declined leniency (rule 5) makes emitting NOTHING beat emitting something wrong")
    void theDeclinedLeniencyRewardsUnderDetection() {
        // One annotated table on page 1. Detector A emits nothing at all; detector B emits one
        // unrelated block on that page.
        GroundTruth.Table table = gt(1, grid("a,b", "c,d"));
        List<BaselineHarness.Cand> emitNothing = List.of();
        List<BaselineHarness.Cand> emitGarbage = List.of(cand(1, grid("x,y"), 0, 0));

        // STRICT (what this project reports): the missed table is charged either way, so giving up
        // is not rewarded -- recall is 0 for both.
        BaselineHarness.Tally strictNothing =
                BaselineHarness.e2ePairedOfficial(emitNothing, List.of(table), false);
        BaselineHarness.Tally strictGarbage =
                BaselineHarness.e2ePairedOfficial(emitGarbage, List.of(table), false);
        assertEquals(strictNothing.gt, strictGarbage.gt,
                "strict: the ground-truth denominator is the same whether or not we guessed");

        // LENIENT (the reference pipeline): emitting nothing removes the table from the recall
        // denominator entirely, while emitting garbage pairs it and charges the whole table.
        BaselineHarness.Tally lenientNothing =
                BaselineHarness.e2ePairedOfficial(emitNothing, List.of(table), true);
        BaselineHarness.Tally lenientGarbage =
                BaselineHarness.e2ePairedOfficial(emitGarbage, List.of(table), true);
        assertEquals(0, lenientNothing.gt,
                "lenient: a table with no candidate on its page vanishes from the denominator");
        assertTrue(lenientGarbage.gt > lenientNothing.gt,
                "lenient: guessing is charged where giving up is not -- the perverse ordering that "
                        + "is the reason this harness declines rule 5");
    }
}
