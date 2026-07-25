package com.oai.titanarum.bakeoff;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the OFFICIAL-definition adjacency scorer ({@link TableScore#buildOfficialRelations},
 * {@link TableScore#scoreAdjacencyOfficial}) -- the cell-identity, parallel-link-deduped,
 * blank-count-carrying form of the ICDAR 2013 structure metric, as opposed to the grid-based
 * {@link TableScore#buildRelations} that {@link TableScore#scoreAdjacency} uses.
 *
 * <p>Each test states the official rule it pins and, where the two builders disagree, shows the
 * grid builder's answer alongside so the delta is visible rather than implied.
 */
class TableScoreOfficialTest {

    private static TableScore.GridCell cell(int r0, int c0, int r1, int c1, String text) {
        return new TableScore.GridCell(r0, c0, r1, c1, text);
    }

    // ------------------------------------------------- rule 1: a spanning cell is ONE cell --

    /**
     * THE MEASUREMENT BUG THIS SCORER FIXES. Ground truth from {@code GroundTruth} expands a
     * spanning cell by REPEATING its text into every covered position; a detected table
     * ({@code TableHit#rows}) instead puts the text on the span ANCHOR and leaves covered positions
     * blank. Under the grid builder those two encodings of the SAME structure produce different
     * relations -- ground truth gains self-relations ("A is right of A") that the detected side can
     * never produce -- so recall is lost to an encoding artifact. Under the official builder both
     * sides describe one cell and the artifact is gone.
     */
    @Test
    void spanningCellEmitsNoSelfRelationAndGridBuilderShowsTheBiasItRemoves() {
        // Logical table: "A" spans all 3 rows of col 0; "B" spans all 3 rows of col 1.
        List<TableScore.GridCell> gtCells = List.of(
                cell(0, 0, 2, 0, "A"),
                cell(0, 1, 2, 1, "B"));

        TableScore.RelationBuild official = TableScore.buildOfficialRelations(gtCells, false);
        // Exactly ONE relation: A -RIGHT-> B. No (A,A)/(B,B) DOWN self-relations (each is one
        // cell), and the pair (A,B) is adjacent along all three rows but recorded once.
        assertEquals(1, official.relations().size(), "one cell pair => one relation");
        assertEquals(1, official.rightCount());
        assertEquals(0, official.downCount());
        assertEquals(2, official.parallelLinksSuppressed(),
                "rows 1 and 2 re-derive the same A->B link and must be suppressed");

        // What the grid builder sees when handed GroundTruth's span-EXPANDED encoding:
        List<List<String>> expandedGrid = List.of(
                List.of("A", "B"),
                List.of("A", "B"),
                List.of("A", "B"));
        assertEquals(7, TableScore.relationCount(expandedGrid),
                "grid builder on expanded GT: 3x RIGHT (A,B) + 2x DOWN (A,A) + 2x DOWN (B,B)");

        // And what it sees when handed the detected side's anchor-plus-blanks encoding:
        List<List<String>> anchorGrid = List.of(
                List.of("A", "B"),
                List.of("", ""),
                List.of("", ""));
        assertEquals(1, TableScore.relationCount(anchorGrid));

        // The bias, made explicit: a PERFECT extraction of this table scores 1/7 recall under the
        // grid metric and 1/1 under the official one.
        GroundTruth.Table gtTable = new GroundTruth.Table(expandedGrid);
        TableScore.AdjResult gridScored = TableScore.scoreAdjacency(gtTable, anchorGrid);
        assertEquals(1, gridScored.matched());
        assertEquals(7, gridScored.gtTotal());
        assertEquals(1.0 / 7.0, gridScored.recall(), 1e-9);

        List<TableScore.GridCell> detectedCells = List.of(
                cell(0, 0, 2, 0, "A"),
                cell(0, 1, 2, 1, "B"));
        TableScore.AdjResult officialScored = TableScore.scoreAdjacencyOfficial(
                gtCells, detectedCells, false, TableScore.Semantics.MULTISET);
        assertEquals(1, officialScored.matched());
        assertEquals(1, officialScored.gtTotal());
        assertEquals(1.0, officialScored.f1(), 1e-9);
    }

    // ----------------------------------------- rule 2: parallel-link dedup is BY CELL IDENTITY --

    /**
     * Parallel-link dedup must not be confused with collapsing duplicate-by-VALUE relations. Two
     * DISTINCT cell pairs that happen to carry the same text still yield TWO relations; only the
     * same ordered cell pair re-derived along a second grid line is suppressed.
     */
    @Test
    void parallelLinkDedupIsByCellIdentityNotByValue() {
        // Two separate rows, each with its own pair of cells, same texts in both rows. Four
        // distinct cells => two distinct cell pairs => two RIGHT relations, both (H,X).
        List<TableScore.GridCell> cells = List.of(
                cell(0, 0, 0, 0, "H"), cell(0, 1, 0, 1, "X"),
                cell(1, 0, 1, 0, "H"), cell(1, 1, 1, 1, "X"));

        TableScore.RelationBuild b = TableScore.buildOfficialRelations(cells, false);
        assertEquals(0, b.parallelLinksSuppressed(),
                "distinct cell pairs are not parallel links, whatever their text");
        assertEquals(2, b.rightCount(), "both (H,X) RIGHT relations survive -- multiplicity by value");
        // DOWN: H->H (col 0) and X->X (col 1), between DIFFERENT cells, so both are real.
        assertEquals(2, b.downCount());
        assertEquals(4, b.relations().size());
    }

    // ------------------------------------------------------ rule 3: blank cells are skipped --

    @Test
    void blanksAreSkippedAndCountedOncePerBlankCell() {
        // Row 0: "A" | blank cell spanning cols 1-2 | "B".  The blank must be skipped over so A
        // links to B, and it is ONE blank cell even though it covers two columns.
        List<TableScore.GridCell> cells = List.of(
                cell(0, 0, 0, 0, "A"),
                cell(0, 1, 0, 2, ""),
                cell(0, 3, 0, 3, "B"));

        TableScore.RelationBuild withBlanks = TableScore.buildOfficialRelations(cells, true);
        assertEquals(1, withBlanks.relations().size());
        TableScore.Relation r = withBlanks.relations().get(0);
        assertEquals("a", r.a());
        assertEquals("b", r.b());
        assertEquals(TableScore.Direction.RIGHT, r.direction());
        assertEquals(1, r.noBlanks(), "a 2-column-wide blank CELL is one skipped blank, not two");

        // With the count excluded from identity, the same relation carries the sentinel instead.
        TableScore.RelationBuild without = TableScore.buildOfficialRelations(cells, false);
        assertEquals(TableScore.NOBLANKS_IGNORED, without.relations().get(0).noBlanks());
    }

    @Test
    void holesCountOneBlankPerPosition() {
        // No declared cell at all at (0,1) and (0,2) -- a hole, not a blank cell. There is no cell
        // extent to group the positions, so each counts as one blank.
        List<TableScore.GridCell> cells = List.of(
                cell(0, 0, 0, 0, "A"),
                cell(0, 3, 0, 3, "B"));
        TableScore.RelationBuild b = TableScore.buildOfficialRelations(cells, true);
        assertEquals(1, b.relations().size());
        assertEquals(2, b.relations().get(0).noBlanks());
    }

    /** Leading blanks, before any anchor exists, must not be attributed to the first relation. */
    @Test
    void leadingBlanksAreNotCounted() {
        List<TableScore.GridCell> cells = List.of(
                cell(0, 1, 0, 1, ""),
                cell(0, 2, 0, 2, "A"),
                cell(0, 3, 0, 3, "B"));
        TableScore.RelationBuild b = TableScore.buildOfficialRelations(cells, true);
        assertEquals(1, b.relations().size());
        assertEquals(0, b.relations().get(0).noBlanks());
    }

    // ------------------------------------------- rule 4: noBlanks participates in identity --

    /**
     * When the blank count is part of a relation's identity, a detector that gets the neighbour
     * right but the number of intervening blank cells wrong scores ZERO on that relation. This is
     * the stricter reading; it is measured separately rather than assumed, because it can only ever
     * lower a score relative to excluding the count.
     */
    @Test
    void noBlanksInIdentityIsStrictlyHarder() {
        List<TableScore.GridCell> gt = List.of(
                cell(0, 0, 0, 0, "A"),
                cell(0, 1, 0, 1, ""),
                cell(0, 2, 0, 2, "B"));
        // Detector found A next to B with no blank between them.
        List<TableScore.GridCell> det = List.of(
                cell(0, 0, 0, 0, "A"),
                cell(0, 1, 0, 1, "B"));

        TableScore.AdjResult ignoring = TableScore.scoreAdjacencyOfficial(
                gt, det, false, TableScore.Semantics.MULTISET);
        assertEquals(1, ignoring.matched());
        assertEquals(1.0, ignoring.f1(), 1e-9);

        TableScore.AdjResult counting = TableScore.scoreAdjacencyOfficial(
                gt, det, true, TableScore.Semantics.MULTISET);
        assertEquals(0, counting.matched(), "(a,b,RIGHT,1) != (a,b,RIGHT,0)");
        assertEquals(0.0, counting.f1(), 1e-9);
        assertTrue(counting.f1() <= ignoring.f1());
    }

    // --------------------------------------------------- rule 5: multiset vs set semantics --

    /**
     * Pins the set-vs-multiset difference the corrected metric depends on: SET semantics discards
     * duplicate-by-value relations on BOTH sides, which shrinks the ground-truth denominator and so
     * reports a DIFFERENT (here: higher) recall than the honest multiset reading for a detector that
     * only reproduced one of two genuine occurrences.
     */
    @Test
    void setSemanticsShrinksTheGroundTruthDenominator() {
        // GT: (H,X,RIGHT) genuinely occurs twice, between two different cell pairs.
        List<TableScore.GridCell> gt = List.of(
                cell(0, 0, 0, 0, "H"), cell(0, 1, 0, 1, "X"),
                cell(1, 0, 1, 0, "H"), cell(1, 1, 1, 1, "X"));
        // Detector reproduced row 0 only; row 1's right-neighbour came out as "Y".
        List<TableScore.GridCell> det = List.of(
                cell(0, 0, 0, 0, "H"), cell(0, 1, 0, 1, "X"),
                cell(1, 0, 1, 0, "H"), cell(1, 1, 1, 1, "Y"));

        TableScore.AdjResult bag = TableScore.scoreAdjacencyOfficial(
                gt, det, false, TableScore.Semantics.MULTISET);
        // GT: RIGHT (H,X) x2; DOWN (H,H), (X,X) => 4.
        assertEquals(4, bag.gtTotal());
        // det: RIGHT (H,X), (H,Y); DOWN (H,H), (X,Y) => 4. Matched: one (H,X), one (H,H) => 2.
        assertEquals(4, bag.detectedTotal());
        assertEquals(2, bag.matched());
        assertEquals(0.5, bag.recall(), 1e-9);

        TableScore.AdjResult set = TableScore.scoreAdjacencyOfficial(
                gt, det, false, TableScore.Semantics.SET);
        // SET collapses GT's two (H,X) into one: denominator 3 instead of 4, and the SAME 2
        // matches now look like 2/3 recall -- a different number for identical output.
        assertEquals(3, set.gtTotal());
        assertEquals(2, set.matched());
        assertTrue(set.recall() > bag.recall(),
                "set semantics reports higher recall than multiset here: "
                        + set.recall() + " vs " + bag.recall());
    }

    /** The legacy grid scorer must expose the same knob, so the bias can be measured on it too. */
    @Test
    void gridScorerSemanticsKnobMatchesItsDefault() {
        GroundTruth.Table expected = new GroundTruth.Table(List.of(
                List.of("H", "X"),
                List.of("H", "X")));
        List<List<String>> actual = List.of(
                List.of("H", "X"),
                List.of("H", "Y"));

        TableScore.AdjResult dflt = TableScore.scoreAdjacency(expected, actual);
        TableScore.AdjResult bag = TableScore.scoreAdjacency(expected, actual, TableScore.Semantics.MULTISET);
        assertEquals(dflt.gtTotal(), bag.gtTotal());
        assertEquals(dflt.matched(), bag.matched());
        assertEquals(dflt.f1(), bag.f1(), 1e-12);

        TableScore.AdjResult set = TableScore.scoreAdjacency(expected, actual, TableScore.Semantics.SET);
        assertEquals(3, set.gtTotal(), "GT's duplicated (H,X,RIGHT) collapses under SET");
        assertEquals(4, bag.gtTotal());
    }

    // ------------------------------------------------------------- ground-truth plumbing --

    @Test
    void csvGroundTruthWithoutCellsFallsBackToOneByOneCells() {
        GroundTruth.Table table = new GroundTruth.Table(List.of(
                List.of("A", "B"),
                List.of("C", "D")));
        assertTrue(table.cells().isEmpty(), "hand-built/CSV tables carry no declared cells");
        List<TableScore.GridCell> cells = TableScore.gridCellsFromGroundTruth(table);
        assertEquals(4, cells.size());
        TableScore.RelationBuild b = TableScore.buildOfficialRelations(cells, false);
        // Same 4 relations the grid builder finds, since there are no spans to differ over.
        assertEquals(4, b.relations().size());
        assertEquals(TableScore.relationCount(table.rows()), b.relations().size());
    }
}
