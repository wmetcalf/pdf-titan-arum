package com.oai.titanarum.bakeoff;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TableScore#scoreAdjacency}, the ICDAR 2013 adjacency-relation metric added
 * alongside the existing exact-cell {@link TableScore#score}. See that method's javadoc for the
 * relation-generation rules (nearest non-empty neighbour to the RIGHT / DOWN, skipping blanks,
 * bag/multiset semantics).
 */
class TableScoreAdjacencyTest {

    // ------------------------------------------------- the whole point of this change --

    /**
     * THIS TEST IS THE JUSTIFICATION FOR ADDING THE ADJACENCY METRIC.
     *
     * <p>The existing exact-cell metric keys a match on (row, col, text). A single phantom row
     * prepended ahead of an otherwise-perfect extraction shifts every real row down by one index,
     * so every real cell now sits at the "wrong" row and the exact-cell metric collapses to
     * (near-)zero -- even though a human looking at the two tables would say the real content was
     * extracted essentially perfectly, just with one garbage row stuck on top.
     *
     * <p>The phantom row here is deliberately SPARSE (one junk cell, two blanks) rather than
     * fully populated, because that is what a real phantom leading row in this corpus tends to
     * look like -- a stray captured fragment (e.g. a page header/number) in one column, not a
     * fully-populated bogus row. The adjacency metric being translation-invariant does not depend
     * on this sparsity to work (a fully-populated junk row would also score far above the
     * exact-cell metric, just with a smaller margin -- see the report for the arithmetic); this
     * shape was chosen to keep the exact expected numbers easy to hand-verify below.
     */
    @Test
    void adjacencyIsInvariantToLeadingPhantomRow() {
        GroundTruth.Table expected = new GroundTruth.Table(List.of(
                List.of("Alpha", "Beta", "Gamma"),
                List.of("Delta", "Epsilon", "Zeta"),
                List.of("Eta", "Theta", "Iota")));

        // Identical content, but with one sparse junk row prepended -- shifts every real row's
        // index by +1.
        List<List<String>> actual = List.of(
                List.of("PAGE 7", "", ""),
                List.of("Alpha", "Beta", "Gamma"),
                List.of("Delta", "Epsilon", "Zeta"),
                List.of("Eta", "Theta", "Iota"));

        // RED evidence: the exact-cell metric collapses. None of the 9 real (row, col, text)
        // triples in `actual` line up with `expected`'s, because every real row moved from index
        // r to r+1; only the junk cell (0,0,"page7") is even at a row expected has content at,
        // and its text doesn't match expected's (0,0,"alpha"). TP = 0 => F1 = 0.
        TableScore.Result exact = TableScore.score(expected, actual);
        assertEquals(0, exact.truePositives(), "no (row,col,text) triple should survive the shift");
        assertTrue(exact.f1() < 0.2,
                "exact-cell F1 must collapse under a single leading phantom row, got " + exact.f1());

        // The adjacency metric ignores row/col position entirely and looks at content-to-content
        // neighbour relations, so it is barely affected:
        //   RIGHT relations: 6 in expected, all 6 reproduced in `actual` (the junk row's only
        //     non-empty cell has no same-row neighbour, so it contributes no RIGHT relation).
        //   DOWN relations: 6 in expected (2 per column). `actual` reproduces all 6, plus ONE
        //     extra unmatched relation ("page7" -> "alpha") from the junk cell chaining into
        //     column 0's first real cell.
        // matched = 12, detectedTotal = 13, gtTotal = 12
        //   precision = 12/13, recall = 1.0, f1 = 2*(12/13)/((12/13)+1) = 0.96
        TableScore.AdjResult adj = TableScore.scoreAdjacency(expected, actual);
        assertEquals(12, adj.matched());
        assertEquals(13, adj.detectedTotal());
        assertEquals(12, adj.gtTotal());
        assertEquals(1.0, adj.recall(), 1e-9);
        assertEquals(12.0 / 13.0, adj.precision(), 1e-9);
        assertEquals(0.96, adj.f1(), 1e-9);
        assertTrue(adj.f1() >= 0.9,
                "adjacency F1 must stay high despite the phantom row, got " + adj.f1());
    }

    // ------------------------------------------------------------- blank-skipping --

    @Test
    void adjacencySkipsBlankCells() {
        // Ground truth has a blank cell physically between "A" and "B" in the same row (e.g. a
        // misaligned/sparse grid). The adjacency metric must look PAST the blank and link A
        // directly to B, rather than emitting no relation (naive adjacent-only) or a relation
        // involving the blank cell itself.
        GroundTruth.Table expected = new GroundTruth.Table(List.of(
                List.of("A", "", "B")));

        // A detector that instead reports A and B as immediately adjacent, with no gap column at
        // all, should still match -- proving the relation is about CONTENT neighbours, not
        // column positions.
        List<List<String>> actual = List.of(
                List.of("A", "B"));

        TableScore.AdjResult adj = TableScore.scoreAdjacency(expected, actual);
        assertEquals(1, adj.gtTotal(), "the blank must be skipped over, yielding exactly one A->B relation");
        assertEquals(1, adj.detectedTotal());
        assertEquals(1, adj.matched());
        assertEquals(1.0, adj.f1(), 1e-9);
    }

    // ------------------------------------------------------------------ basics --

    @Test
    void adjacencyPerfectMatchIsOne() {
        GroundTruth.Table expected = new GroundTruth.Table(List.of(
                List.of("A", "B"),
                List.of("C", "D")));
        List<List<String>> actual = List.of(
                List.of("A", "B"),
                List.of("C", "D"));

        TableScore.AdjResult adj = TableScore.scoreAdjacency(expected, actual);

        // RIGHT: A->B, C->D. DOWN: A->C, B->D. 4 relations total, all matched.
        assertEquals(4, adj.gtTotal());
        assertEquals(4, adj.detectedTotal());
        assertEquals(4, adj.matched());
        assertEquals(1.0, adj.precision(), 1e-9);
        assertEquals(1.0, adj.recall(), 1e-9);
        assertEquals(1.0, adj.f1(), 1e-9);
    }

    @Test
    void adjacencyDisjointIsZero() {
        GroundTruth.Table expected = new GroundTruth.Table(List.of(
                List.of("A", "B"),
                List.of("C", "D")));
        List<List<String>> actual = List.of(
                List.of("X", "Y"),
                List.of("Z", "W"));

        TableScore.AdjResult adj = TableScore.scoreAdjacency(expected, actual);

        assertEquals(0, adj.matched());
        assertTrue(adj.gtTotal() > 0);
        assertTrue(adj.detectedTotal() > 0);
        assertEquals(0.0, adj.precision(), 1e-9);
        assertEquals(0.0, adj.recall(), 1e-9);
        assertEquals(0.0, adj.f1(), 1e-9);
    }

    @Test
    void adjacencyDuplicateRelationsUseBagSemantics() {
        // "H" -> "X" occurs twice on the GT side (rows 0 and 1) but only once on the actual side
        // (row 0 only; row 1's right-neighbour is "Y" instead). With SET semantics the duplicate
        // relation would collapse to one and this detector would look like a perfect match on
        // that relation; with BAG semantics only 1 of the 2 GT occurrences is satisfied.
        GroundTruth.Table expected = new GroundTruth.Table(List.of(
                List.of("H", "X"),
                List.of("H", "X")));
        List<List<String>> actual = List.of(
                List.of("H", "X"),
                List.of("H", "Y"));

        TableScore.AdjResult adj = TableScore.scoreAdjacency(expected, actual);

        // GT RIGHT relations: (H,X) x2. GT DOWN relations: (H,H), (X,X). gtTotal = 4.
        // Actual RIGHT relations: (H,X), (H,Y). Actual DOWN relations: (H,H), (X,Y). detectedTotal = 4.
        // Matches: RIGHT (H,X) matched once (bag min(2,1)=1); DOWN (H,H) matched once. (X,X) vs
        // (X,Y) don't match. matched = 2.
        assertEquals(4, adj.gtTotal());
        assertEquals(4, adj.detectedTotal());
        assertEquals(2, adj.matched());
    }
}
