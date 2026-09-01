package com.oai.titanarum.bakeoff;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the DOCUMENT-POOLED scoring protocol, i.e. for the two read-only accessors
 * {@link TableScore#compareRelations} and {@link TableScore#relationsFromRows} and for the properties
 * the protocol built on them must have.
 *
 * <p>The point of these tests is that pooling must drop the table CORRESPONDENCE and nothing else. In
 * particular both denominators have to be untouched: the ground-truth relation total obviously cannot
 * depend on pairing, and neither can the detected total, because under 1:1 pairing the detected total
 * is (paired hits' relations + unpaired hits' relations) = every hit's relations, which is exactly what
 * pooling sums. If pooling ever moved a denominator it would be inflating the score rather than
 * removing an artifact, so that invariant is asserted here and re-asserted on all 77 corpus PDFs by
 * BaselineHarness#printProtocolSelfCheck.
 */
class TableScorePooledTest {

    private static List<TableScore.Relation> rels(List<List<String>> rows) {
        return TableScore.buildOfficialRelations(TableScore.gridCellsFromRows(rows), false).relations();
    }

    private static final List<List<String>> TABLE_A = List.of(
            List.of("h1", "h2"),
            List.of("a1", "a2"));

    private static final List<List<String>> TABLE_B = List.of(
            List.of("k1", "k2"),
            List.of("b1", "b2"));

    private static final List<List<String>> TABLE_C = List.of(
            List.of("z1", "z2"),
            List.of("c1", "c2"));

    // ------------------------------------------------------------------ the accessors themselves --

    @Test
    void relationsFromRowsAgreesWithTheExistingGridRelationCount() {
        List<List<String>> grid = List.of(
                List.of("a", "", "b"),
                List.of("c", "d", ""));
        assertEquals(TableScore.relationCount(grid), TableScore.relationsFromRows(grid).size(),
                "the exposed builder must be the same one relationCount already uses");
    }

    @Test
    void compareRelationsReproducesTheExistingGridScorerOnASinglePair() {
        GroundTruth.Table gt = new GroundTruth.Table(TABLE_A);
        TableScore.AdjResult viaScorer =
                TableScore.scoreAdjacency(gt, TABLE_A, TableScore.Semantics.MULTISET);
        TableScore.AdjResult viaCompare = TableScore.compareRelations(
                TableScore.relationsFromRows(TABLE_A), TableScore.relationsFromRows(TABLE_A),
                TableScore.Semantics.MULTISET);

        assertEquals(viaScorer.matched(), viaCompare.matched());
        assertEquals(viaScorer.gtTotal(), viaCompare.gtTotal());
        assertEquals(viaScorer.detectedTotal(), viaCompare.detectedTotal());
        assertEquals(viaScorer.f1(), viaCompare.f1(), 1e-12);
    }

    @Test
    void compareRelationsKeepsMultisetSemanticsAndDoesNotOverCreditRepeats() {
        List<TableScore.Relation> once = List.of(
                new TableScore.Relation("a", "b", TableScore.Direction.RIGHT, 0));
        List<TableScore.Relation> twice = new ArrayList<>(once);
        twice.addAll(once);

        TableScore.AdjResult r = TableScore.compareRelations(once, twice,
                TableScore.Semantics.MULTISET);
        assertEquals(1, r.matched(), "ground truth has the relation once -> at most one match");
        assertEquals(2, r.detectedTotal(), "producing it twice still costs precision");
        assertEquals(1, r.gtTotal());
    }

    // ------------------------------------------------------------- what pooling does and does not --

    @Test
    void poolingCreditsCorrectContentEvenWhenAPairingWouldMissIt() {
        // Ground truth: two tables. Detected: exactly ONE of them, reproduced perfectly.
        List<TableScore.Relation> gt = new ArrayList<>(rels(TABLE_A));
        gt.addAll(rels(TABLE_B));
        List<TableScore.Relation> det = rels(TABLE_B);

        TableScore.AdjResult pooled = TableScore.compareRelations(gt, det,
                TableScore.Semantics.MULTISET);

        assertEquals(rels(TABLE_B).size(), pooled.matched(),
                "every relation of the table we did recover must be credited, regardless of which "
                        + "ground-truth entry a pairing rule would have assigned the hit to");
        assertEquals(1.0, pooled.precision(), 1e-12, "nothing spurious was produced");
        assertEquals(rels(TABLE_B).size() / (double) gt.size(), pooled.recall(), 1e-12,
                "the table we missed is still fully charged as recall loss");
    }

    @Test
    void poolingStillFullyChargesSpuriousContent() {
        List<TableScore.Relation> gt = rels(TABLE_A);
        List<TableScore.Relation> detGood = rels(TABLE_A);
        List<TableScore.Relation> detWithSpurious = new ArrayList<>(detGood);
        detWithSpurious.addAll(rels(TABLE_C));   // a wholly invented table

        TableScore.AdjResult clean = TableScore.compareRelations(gt, detGood,
                TableScore.Semantics.MULTISET);
        TableScore.AdjResult dirty = TableScore.compareRelations(gt, detWithSpurious,
                TableScore.Semantics.MULTISET);

        assertEquals(clean.matched(), dirty.matched(), "the invention matches nothing");
        assertTrue(dirty.detectedTotal() > clean.detectedTotal(),
                "its relations still enter the detected total");
        assertTrue(dirty.precision() < clean.precision(),
                "so precision falls: pooling forgives segmentation, not fabrication");
    }

    @Test
    void poolingStillFullyChargesMissedContent() {
        List<TableScore.Relation> gt = new ArrayList<>(rels(TABLE_A));
        gt.addAll(rels(TABLE_B));

        TableScore.AdjResult none = TableScore.compareRelations(gt, List.of(),
                TableScore.Semantics.MULTISET);

        assertEquals(0, none.matched());
        assertEquals(gt.size(), none.gtTotal());
        assertEquals(0.0, none.recall(), 1e-12);
        assertEquals(0.0, none.f1(), 1e-12);
    }

    @Test
    void poolingIsInvariantToTheOrderTablesArePooledIn() {
        List<TableScore.Relation> ab = new ArrayList<>(rels(TABLE_A));
        ab.addAll(rels(TABLE_B));
        List<TableScore.Relation> ba = new ArrayList<>(rels(TABLE_B));
        ba.addAll(rels(TABLE_A));

        List<TableScore.Relation> det = rels(TABLE_B);
        TableScore.AdjResult one = TableScore.compareRelations(ab, det, TableScore.Semantics.MULTISET);
        TableScore.AdjResult two = TableScore.compareRelations(ba, det, TableScore.Semantics.MULTISET);

        assertEquals(one.matched(), two.matched());
        assertEquals(one.f1(), two.f1(), 1e-12);
    }

    @Test
    void poolingLeavesBOTHDENOMINATORSIdenticalToThePerTableSums() {
        // This is the invariant that makes the pooled-vs-1:1 comparison meaningful: pooling may only
        // move the MATCHED count. Here the "1:1" side is the sum of per-table totals.
        List<List<List<String>>> gtTables = List.of(TABLE_A, TABLE_B);
        List<List<List<String>>> detTables = List.of(TABLE_B, TABLE_C);

        int perTableGt = 0;
        int perTableDet = 0;
        List<TableScore.Relation> pooledGt = new ArrayList<>();
        List<TableScore.Relation> pooledDet = new ArrayList<>();
        for (List<List<String>> t : gtTables) {
            perTableGt += rels(t).size();
            pooledGt.addAll(rels(t));
        }
        for (List<List<String>> t : detTables) {
            perTableDet += rels(t).size();
            pooledDet.addAll(rels(t));
        }

        TableScore.AdjResult pooled = TableScore.compareRelations(pooledGt, pooledDet,
                TableScore.Semantics.MULTISET);

        assertEquals(perTableGt, pooled.gtTotal());
        assertEquals(perTableDet, pooled.detectedTotal());
    }

    @Test
    void pooledScoreIsNeverBelowTheSumOfAnyFixedOneToOnePairing() {
        // Pooling can only ever match MORE, because any pairing's matches are a sub-multiset of the
        // pooled intersection. Checked on the concrete case where a pairing rule guesses wrong.
        List<TableScore.Relation> gt = new ArrayList<>(rels(TABLE_A));
        gt.addAll(rels(TABLE_B));
        List<TableScore.Relation> det = rels(TABLE_B);

        int pairedMatched = TableScore.compareRelations(rels(TABLE_A), det,
                TableScore.Semantics.MULTISET).matched();          // the wrong pairing: A <-> B'
        int pooledMatched = TableScore.compareRelations(gt, det,
                TableScore.Semantics.MULTISET).matched();

        assertEquals(0, pairedMatched);
        assertTrue(pooledMatched > pairedMatched);
    }
}
