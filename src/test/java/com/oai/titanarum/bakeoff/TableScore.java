package com.oai.titanarum.bakeoff;

import java.util.HashSet;
import java.util.List;
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
}
