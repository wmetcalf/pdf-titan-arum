package com.oai.titanarum;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-geometry tests: no PDF parsing involved. */
class TableGeometryTest {

    private static TableExtractor.Ruling h(float y, float x1, float x2) {
        return new TableExtractor.Ruling(x1, y, x2, y);
    }

    private static TableExtractor.Ruling v(float x, float y1, float y2) {
        return new TableExtractor.Ruling(x, y1, x, y2);
    }

    /** 4 horizontals x 4 verticals = full 3x3 grid: 9 minimal cells. */
    private static List<TableExtractor.Ruling> grid3x3() {
        List<TableExtractor.Ruling> r = new ArrayList<>();
        for (float y : new float[]{100, 130, 160, 190}) r.add(h(y, 50, 350));
        for (float x : new float[]{50, 150, 250, 350}) r.add(v(x, 100, 190));
        return r;
    }

    @Test
    void normalizeMergesCollinearFragments() {
        // One logical horizontal drawn as two touching fragments + a vertical to keep it alive.
        List<TableExtractor.Ruling> raw = List.of(
                h(100, 50, 200), h(100, 200, 350), v(50, 100, 190), h(190, 50, 350), v(350, 100, 190));
        List<TableExtractor.Ruling> out = TableExtractor.normalize(raw);
        long horiz = out.stream().filter(TableExtractor.Ruling::horizontal).count();
        assertEquals(2, horiz, "fragments at y=100 must merge into one ruling");
    }

    @Test
    void normalizeDropsIsolatedRulings() {
        // A lone underline intersecting nothing must be dropped.
        List<TableExtractor.Ruling> raw = new ArrayList<>(grid3x3());
        raw.add(h(500, 50, 120)); // underline far below the grid
        List<TableExtractor.Ruling> out = TableExtractor.normalize(raw);
        assertTrue(out.stream().noneMatch(r -> r.horizontal() && r.y1 > 400),
                "isolated underline must not survive normalize()");
    }

    @Test
    void fullGridYieldsNineMinimalCells() {
        List<TableExtractor.Ruling> n = TableExtractor.normalize(grid3x3());
        List<TableExtractor.Ruling> horiz = n.stream().filter(TableExtractor.Ruling::horizontal).toList();
        List<TableExtractor.Ruling> vert = n.stream().filter(TableExtractor.Ruling::vertical).toList();
        assertEquals(9, TableExtractor.findCells(horiz, vert).size());
    }

    @Test
    void missingInternalEdgeYieldsSpanningCell() {
        // 2x2 grid whose internal vertical is missing in the TOP row → top cell spans 2 cols.
        List<TableExtractor.Ruling> r = new ArrayList<>();
        for (float y : new float[]{100, 130, 160}) r.add(h(y, 50, 250));
        r.add(v(50, 100, 160));
        r.add(v(250, 100, 160));
        r.add(v(150, 130, 160)); // internal vertical only exists in the bottom row
        List<TableExtractor.Ruling> n = TableExtractor.normalize(r);
        List<TableExtractor.CellRect> cells = TableExtractor.findCells(
                n.stream().filter(TableExtractor.Ruling::horizontal).toList(),
                n.stream().filter(TableExtractor.Ruling::vertical).toList());
        assertEquals(3, cells.size(), "one wide top cell + two bottom cells");

        TableExtractor.TableHit t = TableExtractor.buildTable(1, cells, "lattice");
        assertNotNull(t);
        assertEquals(2, t.rowCount);
        assertEquals(2, t.colCount);
        TableExtractor.CellHit wide = t.cells.stream()
                .filter(c -> c.row == 0 && c.col == 0).findFirst().orElseThrow();
        assertEquals(2, wide.colSpan);
        assertEquals(1, wide.rowSpan);
    }

    @Test
    void buildTableRejectsSubGridComponents() {
        // A single boxed rectangle = 1 minimal cell -> not a table.
        List<TableExtractor.CellRect> one = List.of(cellRect(50, 100, 350, 190));
        assertNull(TableExtractor.buildTable(1, one, "lattice"));
    }

    @Test
    void groupSeparatesDistantComponents() {
        List<TableExtractor.CellRect> cells = new ArrayList<>();
        cells.add(cellRect(50, 100, 150, 130));
        cells.add(cellRect(150, 100, 250, 130)); // touches first -> same component
        cells.add(cellRect(400, 500, 500, 530)); // far away -> second component
        assertEquals(2, TableExtractor.groupIntoTables(cells).size());
    }

    @Test
    void buildTableAssignsIndicesAndBbox() {
        List<TableExtractor.Ruling> n = TableExtractor.normalize(grid3x3());
        List<TableExtractor.CellRect> cells = TableExtractor.findCells(
                n.stream().filter(TableExtractor.Ruling::horizontal).toList(),
                n.stream().filter(TableExtractor.Ruling::vertical).toList());
        TableExtractor.TableHit t = TableExtractor.buildTable(4, TableExtractor.groupIntoTables(cells).get(0), "lattice");
        assertNotNull(t);
        assertEquals(4, t.page);
        assertEquals("lattice", t.extractionMethod);
        assertEquals(3, t.rowCount);
        assertEquals(3, t.colCount);
        assertEquals(9, t.cells.size());
        assertArrayEquals(new float[]{50, 100, 350, 190}, t.bbox, 0.01f);
        // cells sorted row-major
        assertEquals(0, t.cells.get(0).row);
        assertEquals(0, t.cells.get(0).col);
        assertEquals(2, t.cells.get(8).row);
        assertEquals(2, t.cells.get(8).col);
    }

    @Test
    void groupRequiresEdgeAdjacencyNotCornerTouch() {
        // Two cells meeting only at a shared corner point (diagonal quadrants of an
        // otherwise-empty 2x2 grid) must NOT be merged into one component.
        List<TableExtractor.CellRect> cells = new ArrayList<>();
        cells.add(cellRect(50, 100, 150, 130));
        cells.add(cellRect(150, 130, 250, 160));
        List<List<TableExtractor.CellRect>> comps = TableExtractor.groupIntoTables(cells);
        assertEquals(2, comps.size(), "corner-touching cells must be separate components");
        assertNull(TableExtractor.buildTable(1, comps.get(0), "lattice"));
        assertNull(TableExtractor.buildTable(1, comps.get(1), "lattice"));
    }

    @Test
    void findCellsThrowsOnIntersectionBomb() {
        // 250 horizontals x 250 verticals, all crossing -> 62,500 intersection points,
        // well past MAX_INTERSECTIONS (40,000); must fail fast during point collection.
        List<TableExtractor.Ruling> horiz = new ArrayList<>();
        List<TableExtractor.Ruling> vert = new ArrayList<>();
        for (int i = 0; i < 250; i++) horiz.add(h(i * 4f, 0, 1000));
        for (int i = 0; i < 250; i++) vert.add(v(i * 4f, 0, 1000));
        assertThrows(TableExtractor.RulingOverflowException.class,
                () -> TableExtractor.findCells(horiz, vert));
    }

    @Test
    void normalizeMergesDashedRulingBeforeLengthFilter() {
        // A horizontal drawn as 20 dashes of 4pt each with 1pt gaps (merged span ~100pt);
        // individually each dash is well under MIN_RULING_LEN(8) and must not be dropped
        // before merging. A frame (second long horizontal + two crossing verticals) keeps
        // it alive per the isolated-ruling rule.
        List<TableExtractor.Ruling> raw = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            float x0 = i * 5f;
            raw.add(h(100, x0, x0 + 4));
        }
        raw.add(h(190, 0, 99));
        raw.add(v(0, 100, 190));
        raw.add(v(99, 100, 190));
        List<TableExtractor.Ruling> out = TableExtractor.normalize(raw);
        TableExtractor.Ruling dashed = out.stream()
                .filter(r -> r.horizontal() && Math.abs(r.y1 - 100) < 3)
                .findFirst()
                .orElse(null);
        assertNotNull(dashed, "dashed horizontal must survive merge + length filter");
        assertTrue(dashed.length() > 80, "merged dashed ruling should be close to full span, not filtered piecewise");
    }

    @Test
    void tickMarkGridFailsFastViaWorkBudget() {
        // Reviewer's worst-case reproducer: n=60 rows x 60 cols of 10pt tick-mark fragments
        // (>= MIN_RULING_LEN, so they survive normalize's length filter), with >SNAP gaps
        // between same-row fragments (so mergeCollinear does NOT merge them), each fragment
        // crossing only its own column's vertical. This is fully normalize()-legal (no direct
        // findCells() bypass) and yields ~3.6k intersection points (9% of MAX_INTERSECTIONS)
        // where essentially no (top-left, bottom-right) candidate pair ever completes a cell
        // -- forcing a near-full O(P^2) pair scan. Must fail deterministically via the work
        // budget, and fast (well under a second), not after a long input-dependent stall.
        int n = 60;
        float spacing = 20f;
        List<TableExtractor.Ruling> raw = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            float y = i * spacing;
            for (int j = 0; j < n; j++) {
                float x = j * spacing;
                raw.add(new TableExtractor.Ruling(x - 5, y, x + 5, y));
            }
        }
        for (int j = 0; j < n; j++) {
            float x = j * spacing;
            raw.add(new TableExtractor.Ruling(x, 0, x, (n - 1) * spacing));
        }
        List<TableExtractor.Ruling> normed = TableExtractor.normalize(raw);
        List<TableExtractor.Ruling> horiz = normed.stream().filter(TableExtractor.Ruling::horizontal).toList();
        List<TableExtractor.Ruling> vert = normed.stream().filter(TableExtractor.Ruling::vertical).toList();
        assertThrows(TableExtractor.RulingOverflowException.class,
                () -> TableExtractor.findCells(horiz, vert));
    }

    @Test
    void denseLegitGridStillCompletes() {
        // A full 60x60 REAL grid (complete rulings, every cell closed) must still complete
        // well under the work budget -- proves the cap doesn't harm legitimate dense tables.
        int n = 60;
        float spacing = 20f;
        List<TableExtractor.Ruling> raw = new ArrayList<>();
        for (int i = 0; i < n; i++) raw.add(h(i * spacing, 0, (n - 1) * spacing));
        for (int j = 0; j < n; j++) raw.add(v(j * spacing, 0, (n - 1) * spacing));
        List<TableExtractor.Ruling> normed = TableExtractor.normalize(raw);
        List<TableExtractor.Ruling> horiz = normed.stream().filter(TableExtractor.Ruling::horizontal).toList();
        List<TableExtractor.Ruling> vert = normed.stream().filter(TableExtractor.Ruling::vertical).toList();
        List<TableExtractor.CellRect> cells = TableExtractor.findCells(horiz, vert);
        assertEquals((n - 1) * (n - 1), cells.size());
    }

    @Test
    void groupIntoTablesThrowsOnGroupingWorkBudget() {
        // Reshaped for the near-linear spatial-index rewrite (see groupIntoTables' own doc
        // comment): touches() candidates are now bucketed by shared edge coordinate, so a
        // merely large, far-apart cell list (the old test's shape) no longer defeats it -- the
        // budget is now a pure backstop against a genuinely pathological, BUCKET-DEFEATING
        // distribution. Here every cell shares the exact same y0/y1 (so they all land in the
        // SAME y-edge bucket, forcing an O(n) candidate set per cell / ~O(n^2) total pair
        // checks), while remaining spread far apart on x (so none of them actually touch).
        // n=11,000 -> ~11,000*10,999/2 = 60,494,500 possible pair checks, just over the (now
        // 60,000,000) budget.
        int n = 11_000;
        List<TableExtractor.CellRect> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            float x = i * 1000f;
            cells.add(cellRect(x, 100f, x + 10f, 130f));
        }
        assertThrows(TableExtractor.RulingOverflowException.class,
                () -> TableExtractor.groupIntoTables(cells));
    }

    @Test
    void groupIntoTablesHandlesNormalPageSizePromptly() {
        // A legitimate page (a few hundred cells) must stay far under the grouping work
        // budget and return normal, correct grouping.
        int n = 200;
        List<TableExtractor.CellRect> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            float base = i * 1000f;
            cells.add(cellRect(base, base, base + 10f, base + 10f));
        }
        List<List<TableExtractor.CellRect>> comps = TableExtractor.groupIntoTables(cells);
        assertEquals(n, comps.size(), "far-apart cells must remain separate components");
    }

    @Test
    void groupIntoTablesHandlesLegitimateLargeTableWithoutTripping() {
        // Regression guard for the flat-O(n^2)-budget false-drop: a real, densely-touching
        // table at MAX_CELLS_PER_TABLE scale (100x100 unit cells, every cell touching its
        // neighbors) must group into ONE component, without throwing, and near-instantly. The
        // old MAX_GROUPING_WORK=4,000,000 flat budget (below MAX_CELLS_PER_TABLE=10,000) would
        // have wrongly dropped a table exactly this size -- whole page, before ever reaching the
        // per-table cell cap. The spatial index must keep real large tables far under the (now
        // much higher, backstop-only) budget.
        int side = 100; // 100 x 100 = 10,000 cells == MAX_CELLS_PER_TABLE
        List<TableExtractor.CellRect> cells = new ArrayList<>();
        for (int r = 0; r < side; r++) {
            for (int c = 0; c < side; c++) {
                cells.add(cellRect(c * 10f, r * 10f, c * 10f + 10f, r * 10f + 10f));
            }
        }
        assertEquals(side * side, cells.size());
        assertEquals(TableExtractor.MAX_CELLS_PER_TABLE, cells.size());

        long start = System.nanoTime();
        List<List<TableExtractor.CellRect>> comps = TableExtractor.groupIntoTables(cells);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, comps.size(), "a fully-connected dense grid must group into ONE component");
        assertEquals(side * side, comps.get(0).size());
        assertTrue(elapsedMs < 1000,
                "a legit " + (side * side) + "-cell table must group in well under a second, took " + elapsedMs + "ms");
    }

    // ---------------------------------------------------------------- FIX 5: splitComponent

    /** Table A: 2 rows x 2 cols, 30pt row pitch, x in [0,100,200], y in [0,30,60]. */
    private static List<TableExtractor.CellRect> tableA() {
        List<TableExtractor.CellRect> cells = new ArrayList<>();
        for (float y : new float[]{0, 30}) {
            for (float x : new float[]{0, 100}) {
                cells.add(cellRect(x, y, x + 100, y + 30));
            }
        }
        return cells;
    }

    /** Table B: 3 rows x 2 cols, 20pt row pitch, x in [200,300,400], y in [0,20,40,60] --
     * directly right of {@link #tableA}, sharing its border at x=200, same total y-range [0,60]
     * but an INCOMPATIBLE row partition (20pt pitch vs A's 30pt). */
    private static List<TableExtractor.CellRect> tableB() {
        List<TableExtractor.CellRect> cells = new ArrayList<>();
        for (float y : new float[]{0, 20, 40}) {
            for (float x : new float[]{200, 300}) {
                cells.add(cellRect(x, y, x + 100, y + 20));
            }
        }
        return cells;
    }

    @Test
    void splitComponentSeparatesTwoAdjacentTablesOfDifferentPitch() {
        // FIX 5 core repro, driven directly against splitComponent (no PDF/rendering involved):
        // table A (2x2 @ 30pt pitch) and table B (3x2 @ 20pt pitch) touch at x=200 but their
        // combined y-range [0,60] happens to tile evenly either way (2*30 == 3*20 == 60) -- so a
        // naive "does the merged grid cover every slot" check would (wrongly) call this coherent.
        List<TableExtractor.CellRect> merged = new ArrayList<>();
        merged.addAll(tableA());
        merged.addAll(tableB());

        // Sanity: groupIntoTables really does merge these into ONE component (edge-adjacency
        // only), which is exactly the situation splitComponent must repair.
        assertEquals(1, TableExtractor.groupIntoTables(merged).size(),
                "sanity: edge-adjacent A+B must merge into one component before the split fix runs");

        List<List<TableExtractor.CellRect>> split = TableExtractor.splitComponent(merged);
        assertEquals(2, split.size(), "must split into exactly two independent tables");

        List<TableExtractor.TableHit> hits = split.stream()
                .map(part -> TableExtractor.buildTable(1, part, "lattice"))
                .toList();
        TableExtractor.TableHit a = hits.stream().filter(t -> t.rowCount == 2 && t.colCount == 2)
                .findFirst().orElseThrow(() -> new AssertionError("2x2 table not found: " + hits));
        TableExtractor.TableHit b = hits.stream().filter(t -> t.rowCount == 3 && t.colCount == 2)
                .findFirst().orElseThrow(() -> new AssertionError("3x2 table not found: " + hits));
        assertTrue(a.cells.stream().allMatch(c -> c.rowSpan == 1 && c.colSpan == 1),
                "table A must carry no invented spans: " + a.cells);
        assertTrue(b.cells.stream().allMatch(c -> c.rowSpan == 1 && c.colSpan == 1),
                "table B must carry no invented spans: " + b.cells);
    }

    @Test
    void splitComponentDoesNotFragmentAGenuineSpannedTable() {
        // Regression guard: a real single table with a genuine spanning cell (missing internal
        // vertical in the top row, per missingInternalEdgeYieldsSpanningCell) must NOT be split --
        // splitComponent must return it unchanged, and buildTable must still report the correct
        // colSpan=2 header cell.
        List<TableExtractor.Ruling> r = new ArrayList<>();
        for (float y : new float[]{100, 130, 160}) r.add(h(y, 50, 250));
        r.add(v(50, 100, 160));
        r.add(v(250, 100, 160));
        r.add(v(150, 130, 160)); // internal vertical only exists in the bottom row
        List<TableExtractor.Ruling> n = TableExtractor.normalize(r);
        List<TableExtractor.CellRect> cells = TableExtractor.findCells(
                n.stream().filter(TableExtractor.Ruling::horizontal).toList(),
                n.stream().filter(TableExtractor.Ruling::vertical).toList());

        List<List<TableExtractor.CellRect>> split = TableExtractor.splitComponent(cells);
        assertEquals(1, split.size(), "a genuinely spanned single table must not be fragmented");

        TableExtractor.TableHit t = TableExtractor.buildTable(1, split.get(0), "lattice");
        assertNotNull(t);
        assertEquals(2, t.rowCount);
        assertEquals(2, t.colCount);
        TableExtractor.CellHit wide = t.cells.stream()
                .filter(c -> c.row == 0 && c.col == 0).findFirst().orElseThrow();
        assertEquals(2, wide.colSpan, "genuine span must be preserved, not clamped");
        assertEquals(1, wide.rowSpan);
    }

    @Test
    void splitComponentLeavesPlainGridUnsplit() {
        // A fully-connected, dense, non-spanning grid (no cell has any span at all) must hit
        // splitComponent's fast path and come back completely unchanged as ONE component --
        // splitting must never fragment an ordinary table just because SOME internal boundary
        // happens to allow a clean cut.
        int side = 20;
        List<TableExtractor.CellRect> cells = new ArrayList<>();
        for (int rIdx = 0; rIdx < side; rIdx++) {
            for (int cIdx = 0; cIdx < side; cIdx++) {
                cells.add(cellRect(cIdx * 10f, rIdx * 10f, cIdx * 10f + 10f, rIdx * 10f + 10f));
            }
        }
        List<List<TableExtractor.CellRect>> split = TableExtractor.splitComponent(cells);
        assertEquals(1, split.size(), "a plain non-spanning grid must never be split");
        assertEquals(cells.size(), split.get(0).size());
    }

    private static TableExtractor.CellRect cellRect(float x0, float y0, float x1, float y1) {
        TableExtractor.CellRect c = new TableExtractor.CellRect();
        c.x0 = x0; c.y0 = y0; c.x1 = x1; c.y1 = y1;
        return c;
    }
}
