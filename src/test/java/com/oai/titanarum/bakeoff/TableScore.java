package com.oai.titanarum.bakeoff;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cell-level scoring of an extracted table against a {@link GroundTruth.Table}.
 *
 * <p>A cell "matches" when its normalized text is equal at the same (row, col)
 * position. Cells that are empty in both expected and actual are ignored
 * entirely -- they count toward neither a true positive nor a false
 * positive/negative, since otherwise sparse grids (lots of shared blank
 * cells) would inflate precision/recall misleadingly.
 */
public final class TableScore {

    private TableScore() {
    }

    public record Result(int truePositives, int falsePositives, int falseNegatives,
                          double precision, double recall, double f1,
                          boolean dimsExactMatch) {
    }

    private record CellKey(int row, int col, String text) {
    }

    public static Result score(GroundTruth.Table expected, List<List<String>> actual) {
        Set<CellKey> expectedCells = nonEmptyCells(expected.rows());
        Set<CellKey> actualCells = nonEmptyCells(actual);

        int truePositives = 0;
        for (CellKey e : expectedCells) {
            if (actualCells.contains(e)) {
                truePositives++;
            }
        }
        int falsePositives = actualCells.size() - truePositives;
        int falseNegatives = expectedCells.size() - truePositives;

        double precision = (truePositives + falsePositives) == 0
                ? 0.0
                : (double) truePositives / (truePositives + falsePositives);
        double recall = (truePositives + falseNegatives) == 0
                ? 0.0
                : (double) truePositives / (truePositives + falseNegatives);
        double f1 = truePositives == 0
                ? 0.0
                : 2 * precision * recall / (precision + recall);

        int expectedRows = expected.rowCount();
        int expectedCols = expected.colCount();
        int actualRows = actual.size();
        int actualCols = 0;
        for (List<String> row : actual) {
            actualCols = Math.max(actualCols, row.size());
        }
        boolean dimsExactMatch = expectedRows == actualRows && expectedCols == actualCols;

        return new Result(truePositives, falsePositives, falseNegatives,
                precision, recall, f1, dimsExactMatch);
    }

    private static Set<CellKey> nonEmptyCells(List<List<String>> rows) {
        Set<CellKey> cells = new HashSet<>();
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                String normalized = GroundTruth.normalizeCell(row.get(c));
                if (!normalized.isEmpty()) {
                    cells.add(new CellKey(r, c, normalized));
                }
            }
        }
        return cells;
    }

    // ------------------------------------------------------------ adjacency --

    /**
     * The ICDAR 2013 Table Competition's structure-recognition metric (Göbel,
     * Hassan, Oro, Orsi): instead of comparing cells by absolute (row, col)
     * position, it compares the set of "adjacency relations" between
     * non-empty cell CONTENTS -- who is immediately to a cell's right, and
     * who is immediately below it, where "immediately" skips over any
     * blank cells in between. Because relations are keyed by content, not
     * position, a global row/column offset (e.g. a phantom leading row)
     * barely moves the score: every relation between two real cells is
     * unaffected by extra rows/columns elsewhere in the grid.
     */
    public enum Direction { RIGHT, DOWN }

    /** One adjacency relation: {@code a}'s nearest non-empty neighbour in {@code direction} is
     *  {@code b}, identified by normalized cell text (see {@link GroundTruth#normalizeCell}). */
    public record Relation(String a, String b, Direction direction) {
    }

    public record AdjResult(int matched, int detectedTotal, int gtTotal,
                             double precision, double recall, double f1) {
    }

    /**
     * Scores {@code actual} against {@code expected} using the adjacency-relation metric.
     *
     * <p>Relations are compared with BAG (multiset) semantics, not set semantics: if the same
     * (a, b, direction) relation legitimately occurs more than once (e.g. a repeated header
     * label appears in two different rows, each followed by the same next-cell text), each
     * occurrence is counted separately on both sides, and the intersection size is the sum of
     * per-relation {@code min(countInGt, countInActual)} -- exactly like precision/recall over
     * a multiset of tokens. Using plain set semantics would silently collapse duplicate
     * relations into one, over-crediting a detector that reproduces a repeated relation only
     * once while GT has it twice (or vice versa).
     */
    public static AdjResult scoreAdjacency(GroundTruth.Table expected, List<List<String>> actual) {
        List<Relation> gtRelations = buildRelations(expected.rows());
        List<Relation> actualRelations = buildRelations(actual);

        Map<Relation, Integer> gtCounts = toCounts(gtRelations);
        Map<Relation, Integer> actualCounts = toCounts(actualRelations);

        int matched = 0;
        for (Map.Entry<Relation, Integer> e : actualCounts.entrySet()) {
            Integer gtCount = gtCounts.get(e.getKey());
            if (gtCount != null) {
                matched += Math.min(gtCount, e.getValue());
            }
        }

        int detectedTotal = actualRelations.size();
        int gtTotal = gtRelations.size();

        double precision = detectedTotal == 0 ? 0.0 : (double) matched / detectedTotal;
        double recall = gtTotal == 0 ? 0.0 : (double) matched / gtTotal;
        double f1 = matched == 0 ? 0.0 : 2 * precision * recall / (precision + recall);

        return new AdjResult(matched, detectedTotal, gtTotal, precision, recall, f1);
    }

    /**
     * Total relation count for a single grid, with no comparison side -- used for unpaired-table
     * bookkeeping (an expected table with no matching detected hit, or a detected hit with no
     * matching expected table) so those contribute to {@code gtTotal}/{@code detectedTotal}
     * respectively without any matches, mirroring how the exact-cell metric's unpaired-table
     * bookkeeping (see {@code BakeOffHarness#nonEmptyCellCount}) counts non-empty cells.
     */
    public static int relationCount(List<List<String>> rows) {
        return buildRelations(rows).size();
    }

    private static Map<Relation, Integer> toCounts(List<Relation> relations) {
        Map<Relation, Integer> counts = new HashMap<>();
        for (Relation r : relations) {
            counts.merge(r, 1, Integer::sum);
        }
        return counts;
    }

    /**
     * Builds the RIGHT and DOWN adjacency relations for a grid of rows. Rows may be jagged
     * (varying length); missing trailing cells are treated as empty, exactly like
     * {@link GroundTruth.Table#colCount()} does elsewhere.
     *
     * <p>For each row, walks left-to-right tracking the most recently seen non-empty cell;
     * whenever another non-empty cell is found, emits a RIGHT relation from the previous one to
     * it and advances the "most recent" pointer -- so a blank cell in between is simply skipped
     * over rather than breaking the chain or being treated as a neighbour itself. The same walk
     * runs top-to-bottom per column for DOWN relations.
     */
    private static List<Relation> buildRelations(List<List<String>> rows) {
        int numRows = rows.size();
        int numCols = 0;
        for (List<String> row : rows) {
            numCols = Math.max(numCols, row.size());
        }

        String[][] grid = new String[numRows][numCols];
        for (int r = 0; r < numRows; r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < numCols; c++) {
                String raw = c < row.size() ? row.get(c) : "";
                grid[r][c] = GroundTruth.normalizeCell(raw);
            }
        }

        List<Relation> relations = new java.util.ArrayList<>();

        // RIGHT: nearest non-empty neighbour to the right, in the same row.
        for (int r = 0; r < numRows; r++) {
            String prev = null;
            for (int c = 0; c < numCols; c++) {
                String text = grid[r][c];
                if (text.isEmpty()) {
                    continue;
                }
                if (prev != null) {
                    relations.add(new Relation(prev, text, Direction.RIGHT));
                }
                prev = text;
            }
        }

        // DOWN: nearest non-empty neighbour below, in the same column.
        for (int c = 0; c < numCols; c++) {
            String prev = null;
            for (int r = 0; r < numRows; r++) {
                String text = grid[r][c];
                if (text.isEmpty()) {
                    continue;
                }
                if (prev != null) {
                    relations.add(new Relation(prev, text, Direction.DOWN));
                }
                prev = text;
            }
        }

        return relations;
    }
}
