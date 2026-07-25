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

    /**
     * One adjacency relation: {@code a}'s nearest non-blank neighbour in {@code direction} is
     * {@code b}, identified by normalized cell text (see {@link GroundTruth#normalizeCell}).
     *
     * <p>{@code noBlanks} is the number of blank cells skipped over between {@code a} and
     * {@code b}. The official ICDAR 2013 evaluator carries this count as part of a relation's
     * identity; {@link #NOBLANKS_IGNORED} is the sentinel used when a caller deliberately drops it
     * from the identity (so the two modes can never be compared against each other by accident).
     */
    public record Relation(String a, String b, Direction direction, int noBlanks) {

        /** Legacy 3-tuple relation, with the blank count excluded from identity. */
        public Relation(String a, String b, Direction direction) {
            this(a, b, direction, NOBLANKS_IGNORED);
        }
    }

    /** Sentinel {@code noBlanks} meaning "blank count deliberately not part of relation identity".
     *  Negative so it can never collide with a real count (which is always &gt;= 0). */
    public static final int NOBLANKS_IGNORED = -1;

    /** How a relation collection is compared against another. */
    public enum Semantics {
        /** Each occurrence counted separately; intersection = sum of per-value min(gt, det). */
        MULTISET,
        /** Duplicate-by-value relations collapsed to one on both sides before comparing. */
        SET
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
        return scoreAdjacency(expected, actual, Semantics.MULTISET);
    }

    /**
     * As {@link #scoreAdjacency(GroundTruth.Table, List)} but with the comparison semantics
     * selectable, so the cost of the (wrong) SET reading can be MEASURED on real data rather than
     * asserted. {@link Semantics#MULTISET} is the correct/default reading and is what every
     * reported score uses; {@link Semantics#SET} exists only to quantify the bias.
     */
    public static AdjResult scoreAdjacency(GroundTruth.Table expected, List<List<String>> actual,
                                            Semantics semantics) {
        return compare(buildRelations(expected.rows()), buildRelations(actual), semantics);
    }

    /** The shared multiset/set comparison used by every adjacency scorer in this class. */
    private static AdjResult compare(List<Relation> gtRelations, List<Relation> detRelations,
                                      Semantics semantics) {
        Map<Relation, Integer> gtCounts = toCounts(gtRelations);
        Map<Relation, Integer> detCounts = toCounts(detRelations);

        int matched = 0;
        int detectedTotal;
        int gtTotal;
        if (semantics == Semantics.SET) {
            // Collapse duplicates by value on BOTH sides first: every distinct relation counts
            // exactly once, and a shared distinct relation contributes exactly one match.
            for (Relation r : detCounts.keySet()) {
                if (gtCounts.containsKey(r)) matched++;
            }
            detectedTotal = detCounts.size();
            gtTotal = gtCounts.size();
        } else {
            for (Map.Entry<Relation, Integer> e : detCounts.entrySet()) {
                Integer gtCount = gtCounts.get(e.getKey());
                if (gtCount != null) {
                    matched += Math.min(gtCount, e.getValue());
                }
            }
            detectedTotal = detRelations.size();
            gtTotal = gtRelations.size();
        }

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

    // -------------------------------------------------- official (cell-identity) adjacency --

    /**
     * One logical cell with its SPAN, as the official ICDAR 2013 structure metric understands a
     * cell: {@code startRow..endRow} x {@code startCol..endCol} inclusive, carrying one text.
     *
     * <p>This is what {@link #buildOfficialRelations} needs and what a plain text grid cannot
     * express. The grid-based {@link #buildRelations} sees a spanning cell either as its content
     * REPEATED across every covered position (which is how {@link GroundTruth} expands ICDAR
     * ground truth) or as an anchor plus blanks (which is how {@code TableExtractor.TableHit#rows}
     * emits it) -- two different shapes for the same structure, and the mismatch produces relations
     * that exist on one side only. Comparing cells-with-spans removes that entire class of
     * artifact.
     */
    public record GridCell(int startRow, int startCol, int endRow, int endCol, String text) {
    }

    /**
     * Derives 1x1 {@link GridCell}s from a plain text grid, for the sides that genuinely have no
     * span information: CSV-sourced ground truth, and any detected table whose cell list is
     * unavailable. Blank positions are omitted -- with 1x1 cells, "a blank cell" and "a hole in the
     * grid" are indistinguishable and both count as one skipped blank in
     * {@link #buildOfficialRelations}, so omitting them changes nothing.
     */
    public static List<GridCell> gridCellsFromRows(List<List<String>> rows) {
        List<GridCell> cells = new java.util.ArrayList<>();
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                if (!GroundTruth.normalizeCell(row.get(c)).isEmpty()) {
                    cells.add(new GridCell(r, c, r, c, row.get(c)));
                }
            }
        }
        return cells;
    }

    /** {@link GroundTruth.Cell}s -> {@link GridCell}s, keeping declared spans and blank cells. */
    public static List<GridCell> gridCellsFromGroundTruth(GroundTruth.Table table) {
        if (table.cells().isEmpty()) {
            return gridCellsFromRows(table.rows());
        }
        List<GridCell> out = new java.util.ArrayList<>(table.cells().size());
        for (GroundTruth.Cell c : table.cells()) {
            out.add(new GridCell(c.startRow(), c.startCol(), c.endRow(), c.endCol(), c.text()));
        }
        return out;
    }

    /** Outcome of building official relations, including what the parallel-link dedup removed. */
    public record RelationBuild(List<Relation> relations, int parallelLinksSuppressed,
                                 int rightCount, int downCount, int zeroBlankCount) {
    }

    /**
     * Builds adjacency relations the way the official ICDAR 2013 evaluator does
     * ({@code Table.findAdjacencyRelations} + {@code AdjacencyRelation.normalize}), from cells with
     * spans rather than from an expanded text grid. Three properties distinguish it from
     * {@link #buildRelations}:
     *
     * <ol>
     *   <li><b>Cell identity, not position identity.</b> A cell spanning several rows/columns is
     *       ONE cell, so walking across its own interior emits nothing. The grid builder, fed
     *       {@link GroundTruth}'s span-EXPANDED grid, instead sees the same text in two adjacent
     *       positions and emits a spurious self-relation {@code (A, A)} -- which our detected side
     *       can never produce, because {@code TableHit#rows} leaves span-covered positions blank.
     *       Those self-relations were therefore pure, systematic recall loss.</li>
     *   <li><b>Parallel-link dedup.</b> When two cells are adjacent along more than one grid line
     *       (e.g. a 3-row-spanning cell beside another 3-row-spanning cell, adjacent in all three
     *       rows), the official evaluator records ONE relation for that ordered pair and direction,
     *       not three. Multiplicity BY VALUE across DISTINCT cell pairs is still preserved -- this
     *       dedup is by cell identity, and is a completely different thing from collapsing
     *       duplicate-by-value relations (see {@link Semantics}).</li>
     *   <li><b>Blank count.</b> The number of blank cells skipped between the two cells is carried
     *       on the relation, and is part of its identity when {@code includeNoBlanks} is true. A
     *       blank cell that spans several positions counts ONCE (it is one cell); a hole in the
     *       grid -- a position no declared cell covers -- counts as one blank per position, since
     *       there is no cell there whose extent could group it.</li>
     * </ol>
     *
     * <p>Blank/non-blank is decided on {@link GroundTruth#normalizeCell}, the same normalization
     * used for relation text, so a whitespace-only cell is blank on both sides.
     *
     * <p>When cells overlap (two declared cells covering the same position -- malformed input), the
     * LATER cell in the list owns the position, matching {@link GroundTruth}'s own expanded-grid
     * last-wins behavior so the two views stay consistent.
     */
    public static RelationBuild buildOfficialRelations(List<GridCell> cells, boolean includeNoBlanks) {
        int maxRow = -1;
        int maxCol = -1;
        for (GridCell c : cells) {
            maxRow = Math.max(maxRow, Math.max(c.startRow(), c.endRow()));
            maxCol = Math.max(maxCol, Math.max(c.startCol(), c.endCol()));
        }
        if (maxRow < 0 || maxCol < 0) {
            return new RelationBuild(List.of(), 0, 0, 0, 0);
        }

        int n = cells.size();
        String[] norm = new String[n];
        boolean[] blank = new boolean[n];
        for (int i = 0; i < n; i++) {
            norm[i] = GroundTruth.normalizeCell(cells.get(i).text());
            blank[i] = norm[i].isEmpty();
        }

        int[][] owner = new int[maxRow + 1][maxCol + 1];
        for (int[] row : owner) {
            java.util.Arrays.fill(row, -1);
        }
        for (int i = 0; i < n; i++) {
            GridCell c = cells.get(i);
            int r0 = Math.min(c.startRow(), c.endRow());
            int r1 = Math.max(c.startRow(), c.endRow());
            int c0 = Math.min(c.startCol(), c.endCol());
            int c1 = Math.max(c.startCol(), c.endCol());
            if (r0 < 0 || c0 < 0) {
                continue;
            }
            for (int r = r0; r <= r1; r++) {
                for (int cc = c0; cc <= c1; cc++) {
                    owner[r][cc] = i;
                }
            }
        }

        List<Relation> relations = new java.util.ArrayList<>();
        java.util.Set<Long> emittedRight = new HashSet<>();
        java.util.Set<Long> emittedDown = new HashSet<>();
        int suppressed = 0;
        int rightCount = 0;
        int downCount = 0;
        int zeroBlank = 0;

        // ---- RIGHT: walk each grid ROW left-to-right over cell owners.
        for (int r = 0; r <= maxRow; r++) {
            int cur = -1;
            int blanks = 0;
            int c = 0;
            while (c <= maxCol) {
                int o = owner[r][c];
                if (o < 0) {                       // hole: no declared cell here
                    if (cur >= 0) blanks++;
                    c++;
                    continue;
                }
                if (o == cur) {                    // still inside the anchor cell's own span
                    c++;
                    continue;
                }
                if (blank[o]) {                    // one blank CELL, however wide
                    if (cur >= 0) blanks++;
                    while (c <= maxCol && owner[r][c] == o) c++;
                    continue;
                }
                if (cur >= 0) {
                    long pair = ((long) cur << 32) | (o & 0xFFFFFFFFL);
                    if (emittedRight.add(pair)) {
                        relations.add(new Relation(norm[cur], norm[o], Direction.RIGHT,
                                includeNoBlanks ? blanks : NOBLANKS_IGNORED));
                        rightCount++;
                        if (blanks == 0) zeroBlank++;
                    } else {
                        suppressed++;
                    }
                }
                cur = o;
                blanks = 0;
                while (c <= maxCol && owner[r][c] == o) c++;
            }
        }

        // ---- DOWN: walk each grid COLUMN top-to-bottom over cell owners.
        for (int c = 0; c <= maxCol; c++) {
            int cur = -1;
            int blanks = 0;
            int r = 0;
            while (r <= maxRow) {
                int o = owner[r][c];
                if (o < 0) {
                    if (cur >= 0) blanks++;
                    r++;
                    continue;
                }
                if (o == cur) {
                    r++;
                    continue;
                }
                if (blank[o]) {
                    if (cur >= 0) blanks++;
                    while (r <= maxRow && owner[r][c] == o) r++;
                    continue;
                }
                if (cur >= 0) {
                    long pair = ((long) cur << 32) | (o & 0xFFFFFFFFL);
                    if (emittedDown.add(pair)) {
                        relations.add(new Relation(norm[cur], norm[o], Direction.DOWN,
                                includeNoBlanks ? blanks : NOBLANKS_IGNORED));
                        downCount++;
                        if (blanks == 0) zeroBlank++;
                    } else {
                        suppressed++;
                    }
                }
                cur = o;
                blanks = 0;
                while (r <= maxRow && owner[r][c] == o) r++;
            }
        }

        return new RelationBuild(relations, suppressed, rightCount, downCount, zeroBlank);
    }

    /**
     * Scores detected cells against ground-truth cells using the official (cell-identity,
     * parallel-link-deduped) relation definition. {@code includeNoBlanks} selects whether the
     * skipped-blank count is part of a relation's identity; {@code semantics} selects
     * multiset (correct) vs set (measurement-only) comparison.
     */
    public static AdjResult scoreAdjacencyOfficial(List<GridCell> gt, List<GridCell> detected,
                                                    boolean includeNoBlanks, Semantics semantics) {
        return compare(buildOfficialRelations(gt, includeNoBlanks).relations(),
                buildOfficialRelations(detected, includeNoBlanks).relations(),
                semantics);
    }

    /** Official-definition relation count for one side, for unpaired-table bookkeeping. */
    public static int officialRelationCount(List<GridCell> cells, boolean includeNoBlanks,
                                             Semantics semantics) {
        List<Relation> rels = buildOfficialRelations(cells, includeNoBlanks).relations();
        return semantics == Semantics.SET ? toCounts(rels).size() : rels.size();
    }
}
