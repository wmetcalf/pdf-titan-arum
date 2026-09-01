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
    void splitComponentSplitsWhenOneTableHasNoRowSpanButOtherHasColSpanHeader() {
        // Reviewer's counterexample against the (now-reverted) global hasRowSpan/hasColSpan
        // axis-skip pruning: A (y=[0,60], 2 rows @ pitch 30) and B (y=[30,120], 3 rows @ pitch 30,
        // OVERLAPPING A's range in [30,60] with IDENTICAL boundaries there) each extend BEYOND the
        // other's y-range (A down to 0, B up to 120) without ANY foreign boundary ever falling
        // strictly inside another cell's own span -- so NOT ONE cell anywhere in the merged
        // placement picks up rowSpan inflation (hasRowSpan(merged) is false), even though the
        // vertical A|B cut is still the ONLY correct split (mergedRowCount=4 is still strictly
        // greater than BOTH A's own rowCount=2 and B's own rowCount=3, achieved via each side's
        // EXCLUSIVE outer extension rather than interleaving). B independently carries a genuine
        // merged 2-col header (its own colSpan=2 cell), so the top-level hasAnySpan fast path is
        // still entered.
        //
        // A pruning strategy that skips the vertical search whenever hasRowSpan(merged) is false
        // -- as a prior, reverted version of splitComponent did -- would skip the ONLY valid cut
        // here, falling through to "unsplit": 9 cells garbled into a bogus 4x4=16-slot grid, and
        // (per buildTable's incoherence safety net) B's genuine header colSpan silently clamped
        // from 2 to 1. This must instead split cleanly into exactly 2 tables.
        List<TableExtractor.CellRect> tableANoRowSpan = new ArrayList<>();
        for (float y : new float[]{0, 30}) {
            for (float x : new float[]{0, 100}) {
                tableANoRowSpan.add(cellRect(x, y, x + 100, y + 30));
            }
        }
        List<TableExtractor.CellRect> tableBWithHeader = new ArrayList<>();
        tableBWithHeader.add(cellRect(200, 30, 300, 60));
        tableBWithHeader.add(cellRect(300, 30, 400, 60));
        tableBWithHeader.add(cellRect(200, 60, 300, 90));
        tableBWithHeader.add(cellRect(300, 60, 400, 90));
        tableBWithHeader.add(cellRect(200, 90, 400, 120)); // genuine merged 2-col header

        List<TableExtractor.CellRect> merged = new ArrayList<>();
        merged.addAll(tableANoRowSpan);
        merged.addAll(tableBWithHeader);
        assertEquals(9, merged.size());

        // Sanity: confirm the premise -- no cell anywhere in the merged placement has rowSpan>1,
        // yet the split must still succeed.
        assertEquals(1, TableExtractor.groupIntoTables(merged).size(),
                "sanity: edge-adjacent A+B must merge into one component");

        List<List<TableExtractor.CellRect>> split = TableExtractor.splitComponent(merged);
        assertEquals(2, split.size(),
                "must split into exactly two independent tables despite zero global rowSpan inflation: "
                        + split);

        List<TableExtractor.TableHit> hits = split.stream()
                .map(part -> TableExtractor.buildTable(1, part, "lattice"))
                .toList();
        TableExtractor.TableHit a = hits.stream().filter(t -> t.rowCount == 2 && t.colCount == 2)
                .findFirst().orElseThrow(() -> new AssertionError("2x2 table A not found: " + hits));
        TableExtractor.TableHit b = hits.stream().filter(t -> t.rowCount == 3 && t.colCount == 2)
                .findFirst().orElseThrow(() -> new AssertionError("3x2 table B not found: " + hits));

        assertTrue(a.cells.stream().allMatch(c -> c.rowSpan == 1 && c.colSpan == 1),
                "table A must have no invented spans: " + a.cells);

        TableExtractor.CellHit header = b.cells.stream()
                .filter(c -> c.colSpan == 2).findFirst()
                .orElseThrow(() -> new AssertionError("table B's genuine header must survive as colSpan=2: " + b.cells));
        assertEquals(1, header.rowSpan);
        assertEquals(4, b.cells.stream().filter(c -> c.rowSpan == 1 && c.colSpan == 1).count(),
                "table B's other 4 cells must remain plain 1x1, no clamping and no extra invented spans");
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

    // ------------------------------------------------------- split-search work budget (DoS close)

    @Test
    void splitComponentThrowsOnStraddleScanWorkBudget() {
        // Reviewer's DoS reproducer: tryCuts' per-candidate straddle-scan is itself O(comp.size()),
        // run once per candidate cut, BEFORE placeGridBudgeted runs -- previously uncounted against
        // MAX_SPLIT_WORK. Two row-bands of ~5,000 columns each, at OFFSET (mismatched) pitch, so
        // literally every interior vertical-cut candidate straddles some cell (verified: A's pitch
        // and B's half-pitch-shifted columns never share a boundary), forcing a near-full scan of
        // the ~10,000-cell component for most/all of the ~10,000 candidates -- quadratic, and
        // (pre-fix) measured at 561ms/10k cells, 1954ms/20k cells without ever tripping the budget.
        // Column 0 is a single cell bridging BOTH row-bands (rowSpan=2 in the merged placement) so
        // hasRowSpan(g) is true and the vertical/row-inflation search actually runs (isn't skipped
        // by the axis-pruning fast path, which correctly prunes an axis with no possible inflation
        // at all -- this shape must still exercise, and trip, the budget on the axis that DOES).
        int cols = 5000;
        List<TableExtractor.CellRect> cells = new ArrayList<>();
        cells.add(cellRect(0f, 0f, 10f, 20f));
        for (int c = 1; c < cols; c++) {
            cells.add(cellRect(c * 10f, 0f, c * 10f + 10f, 10f));           // row-band A
            cells.add(cellRect(c * 10f + 5f, 10f, c * 10f + 15f, 20f));    // row-band B, half-pitch shifted
        }
        assertEquals(2 * cols - 1, cells.size());

        long start = System.nanoTime();
        assertThrows(TableExtractor.RulingOverflowException.class,
                () -> TableExtractor.splitComponent(cells),
                "an offset-pitch shape with no valid resolving cut must fail via the work budget");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 1000,
                "must fail FAST (budget-bounded, not merely bounded by eventually finishing), took " + elapsedMs + "ms");
    }

    @Test
    void splitComponentHandlesLegitimateLargeSpannedTableWithoutTrippingBudget() {
        // Positive guard, paired with the negative test above: a real, single coherent table at
        // MAX_CELLS_PER_TABLE scale (100x100 = 10,000 minimal cells, with ONE genuine merged-header
        // -style spanning cell so splitComponent's search actually runs instead of short-circuiting
        // via the "no spans anywhere" fast path) must complete WITHOUT tripping the work budget,
        // and fast -- the budget must be a backstop for the adversarial case above, not something
        // a legitimate large table comes anywhere close to on the way to its correct (unsplit)
        // result.
        int side = 100;
        List<TableExtractor.CellRect> cells = new ArrayList<>();
        for (int r = 0; r < side; r++) {
            for (int c = 0; c < side; c++) {
                if (r == 0 && c == 1) continue; // merged into the row0/col0 cell below
                float x0 = c * 10f, y0 = r * 10f;
                float x1 = (r == 0 && c == 0) ? 20f : x0 + 10f; // row0/col0 spans 2 columns
                cells.add(cellRect(x0, y0, x1, y0 + 10f));
            }
        }
        assertEquals(side * side - 1, cells.size());

        long start = System.nanoTime();
        List<List<TableExtractor.CellRect>> split = TableExtractor.splitComponent(cells);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, split.size(), "a genuinely single coherent large table must not be split");
        assertEquals(cells.size(), split.get(0).size());
        assertTrue(elapsedMs < 1000,
                "a legit " + cells.size() + "-cell spanned table must complete well under a second, took "
                        + elapsedMs + "ms");
    }

    private static TableExtractor.CellRect cellRect(float x0, float y0, float x1, float y1) {
        TableExtractor.CellRect c = new TableExtractor.CellRect();
        c.x0 = x0; c.y0 = y0; c.x1 = x1; c.y1 = y1;
        return c;
    }

    // ------------------------------------------------------- FIX 3: per-component split isolation

    private static List<TableExtractor.CellRect> plain3x3Grid() {
        List<TableExtractor.Ruling> raw = new ArrayList<>();
        for (float y : new float[]{100, 130, 160, 190}) raw.add(h(y, 50, 350));
        for (float x : new float[]{50, 150, 250, 350}) raw.add(v(x, 100, 190));
        List<TableExtractor.Ruling> n = TableExtractor.normalize(raw);
        return TableExtractor.findCells(
                n.stream().filter(TableExtractor.Ruling::horizontal).toList(),
                n.stream().filter(TableExtractor.Ruling::vertical).toList());
    }

    /** The exact offset-pitch shape from {@code splitComponentThrowsOnStraddleScanWorkBudget},
     * guaranteed to trip {@code MAX_SPLIT_WORK} inside {@code splitComponent}. */
    private static List<TableExtractor.CellRect> adversarialSplitWorkBomb() {
        int cols = 5000;
        List<TableExtractor.CellRect> cells = new ArrayList<>();
        cells.add(cellRect(0f, 0f, 10f, 20f));
        for (int c = 1; c < cols; c++) {
            cells.add(cellRect(c * 10f, 0f, c * 10f + 10f, 10f));
            cells.add(cellRect(c * 10f + 5f, 10f, c * 10f + 15f, 20f));
        }
        return cells;
    }

    @Test
    void selectKeptTablesIsolatesOneAdversarialComponentFromOthers() {
        // FIX 3 reproducer: extractLatticePage's per-component loop used to call splitComponent
        // with NO per-component try/catch, so ONE adversarial component's RulingOverflowException
        // (MAX_SPLIT_WORK) unwound the WHOLE per-page loop -- kept (and, transitively,
        // Result.tables) is only populated AFTER that loop returns, so every OTHER legitimate
        // table already found on the page was silently discarded too (REPRODUCED: 3 legit tables
        // lost to 1 adversarial 880-cell component). 3 good components + 1 adversarial
        // (budget-tripping) component must yield exactly the 3 good tables, with truncated set
        // for the skipped adversarial one -- red pre-fix (0 or fewer than 3 tables survive
        // because the whole call throws), green after.
        List<List<TableExtractor.CellRect>> components = new ArrayList<>();
        for (int t = 0; t < 3; t++) components.add(plain3x3Grid());
        components.add(adversarialSplitWorkBomb());

        TableExtractor.Result result = new TableExtractor.Result();
        List<List<TableExtractor.CellRect>> kept =
                TableExtractor.selectKeptTables(components, 1, result);

        assertEquals(3, kept.size(),
                "the 3 good components must survive even though the 4th trips the split-work budget: "
                        + kept.size());
        assertTrue(result.truncated, "the skipped adversarial component must set Result.truncated");
        for (List<TableExtractor.CellRect> comp : kept) {
            TableExtractor.TableHit t = TableExtractor.buildTable(1, comp, "lattice");
            assertNotNull(t, "each surviving component must still build into a valid table");
            assertEquals(3, t.rowCount);
            assertEquals(3, t.colCount);
        }
    }

    @Test
    void selectKeptTablesStillWorksWithNoAdversarialComponent() {
        // Regression guard: the ordinary (no adversarial component) case must behave exactly as
        // before -- all good components kept, nothing truncated.
        List<List<TableExtractor.CellRect>> components = new ArrayList<>();
        for (int t = 0; t < 3; t++) components.add(plain3x3Grid());

        TableExtractor.Result result = new TableExtractor.Result();
        List<List<TableExtractor.CellRect>> kept =
                TableExtractor.selectKeptTables(components, 1, result);

        assertEquals(3, kept.size());
        assertFalse(result.truncated);
    }

    // --------------------------------------------------- round-3 follow-up: render loop isolation

    /**
     * ~200 CellRects placed diagonally (cell i spans {@code [i*10, i*10+10]} on BOTH axes):
     * clusters into a 200x200 grid (product 40,000 &gt; MAX_CELLS_PER_TABLE=10,000) while {@code
     * comp.size()}=200 stays far under MAX_CELLS_PER_TABLE and rowCount*colCount (40,000) stays
     * far under MAX_SPLIT_WORK (20,000,000) -- this clears BOTH of selectKeptTables' own gates
     * (cell-count, split-work) cleanly (every cell's row/colSpan is exactly 1, so splitComponent's
     * hasAnySpan fast-path returns immediately without a costly search); only renderViews'
     * defense-in-depth grid-product guard (FIX B) catches it.
     */
    private static List<TableExtractor.CellRect> diagonalGridProductBomb() {
        int n = 200;
        List<TableExtractor.CellRect> cells = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cells.add(cellRect(i * 10f, i * 10f, i * 10f + 10f, i * 10f + 10f));
        }
        return cells;
    }

    @Test
    void renderKeptTablesIsolatesGridProductCapFromOthers() {
        // round-3 follow-up reproducer: renderViews' defense-in-depth grid-product guard (FIX B)
        // can throw RulingOverflowException from what was, pre-fix, an UNGUARDED per-table render
        // loop inside extractLatticePage (buildTable -> renderViews -> result.tables.add) --
        // unlike selectKeptTables' own splitComponent isolation (FIX 3) above, that loop had no
        // per-table try/catch, so the exception unwound the WHOLE loop, silently dropping every
        // table ordered AFTER the offending one too (REPRODUCED: kept.size()=2, one pathological
        // ~200-cell diagonal ordered FIRST + one legit 3x3 table ordered AFTER it -> 0 tables
        // survived, the legit one lost as pure collateral damage, with only a generic
        // Result.truncated=true). The fixed renderKeptTables must isolate the failure to just the
        // offending component and keep rendering its siblings.
        List<List<TableExtractor.CellRect>> kept = new ArrayList<>();
        kept.add(diagonalGridProductBomb());
        kept.add(plain3x3Grid());

        TableExtractor.Result result = new TableExtractor.Result();
        TableExtractor.renderKeptTables(1, kept, result);

        assertEquals(1, result.tables.size(),
                "the legit 3x3 table (ordered AFTER the grid-product-cap-tripping component) must survive: "
                        + result.tables);
        TableExtractor.TableHit t = result.tables.get(0);
        assertEquals(3, t.rowCount);
        assertEquals(3, t.colCount);
        assertTrue(result.truncated, "the grid-product-cap-rejected table must set Result.truncated");
    }

    @Test
    void renderKeptTablesStillWorksWithNoAdversarialComponent() {
        // Regression guard: the ordinary (no adversarial component) case must behave exactly as
        // before -- all good components rendered, nothing truncated.
        List<List<TableExtractor.CellRect>> kept = new ArrayList<>();
        for (int t = 0; t < 3; t++) kept.add(plain3x3Grid());

        TableExtractor.Result result = new TableExtractor.Result();
        TableExtractor.renderKeptTables(1, kept, result);

        assertEquals(3, result.tables.size());
        assertFalse(result.truncated);
    }

    // --------------------------------------------------------- FIX 4: splitComponent depth cap

    /**
     * A chain of {@code count} tables placed side by side, each spanning the SAME shared y-range
     * [0, H] but with its own UNIQUE internal row boundary at y = 2*i (i = 1..count, so all
     * boundaries land exactly on the SNAP=2pt grid with no rounding ambiguity). Because each
     * table's boundary is unique to it (no other table in the chain shares that exact y value),
     * removing ANY single table from the merged set strictly reduces the total distinct-boundary
     * count by exactly one -- so splitComponent's acceptance condition ("merged perpendicular
     * count strictly exceeds EITHER side's own") holds at the very first (leftmost) interior x
     * boundary tried, peeling off table 1 and recursing on the rest, then peeling off table 2,
     * and so on -- an intentionally "peelable" shape that recurses roughly {@code count} levels
     * deep, one level per peel, with NO cap.
     */
    private static List<TableExtractor.CellRect> peelableChain(int count) {
        List<TableExtractor.CellRect> cells = new ArrayList<>();
        float h = 2f * (count + 1); // shared height; every internal split lands on the SNAP grid
        for (int i = 1; i <= count; i++) {
            float xBase = (i - 1) * 200f;
            float split = 2f * i; // this table's OWN unique internal row boundary
            cells.add(cellRect(xBase, 0f, xBase + 100f, split));
            cells.add(cellRect(xBase + 100f, 0f, xBase + 200f, split));
            cells.add(cellRect(xBase, split, xBase + 100f, h));
            cells.add(cellRect(xBase + 100f, split, xBase + 200f, h));
        }
        return cells;
    }

    @Test
    void splitComponentDepthCapPreventsUnboundedRecursionAndStackOverflow() throws Exception {
        // FIX 4 reproducer: splitComponent/tryCuts recursion had NO depth cap (unlike every
        // sibling recursive walk in this class), so a "peelable" adversarial shape recurses one
        // level per peel with nothing to stop it. On a constrained stack (plausible in this
        // project's firecracker/gvisor microVM deploy targets) this is a StackOverflowError --
        // an Error that would escape a plain catch(RulingOverflowException)/catch(Exception).
        // Run on a dedicated 128KB-stack thread to prove no SOE escapes; the depth cap must also
        // engage well before all `count` tables are individually peeled out.
        int count = 200;
        List<TableExtractor.CellRect> chain = peelableChain(count);

        var resultRef = new java.util.concurrent.atomic.AtomicReference<List<List<TableExtractor.CellRect>>>();
        var errorRef = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread t = new Thread(null, () -> {
            try {
                resultRef.set(TableExtractor.splitComponent(chain));
            } catch (Throwable e) {
                errorRef.set(e);
            }
        }, "small-stack-split-test", 128 * 1024);
        t.start();
        t.join(15_000);

        assertFalse(t.isAlive(), "splitComponent must complete promptly on a deep peelable chain, not hang");
        assertNull(errorRef.get(), "splitComponent must not let a StackOverflowError escape on a "
                + "constrained (128KB) stack: " + errorRef.get());

        List<List<TableExtractor.CellRect>> split = resultRef.get();
        assertNotNull(split);
        int totalCells = split.stream().mapToInt(List::size).sum();
        assertEquals(chain.size(), totalCells, "no cells may be dropped even when the depth cap engages");
        assertTrue(split.size() < count,
                "the depth cap must engage before fully peeling all " + count + " tables individually, got "
                        + split.size() + " pieces");
        assertTrue(split.size() <= TableExtractor.MAX_SPLIT_DEPTH + 2,
                "split piece count must be bounded close to MAX_SPLIT_DEPTH, not merely 'less than " + count
                        + "': got " + split.size() + " pieces");
    }

    @Test
    void splitComponentHandlesAShallowPeelableChainCompletely() {
        // Positive companion: a chain well under the depth cap must peel apart FULLY and
        // correctly (every table becomes its own well-formed 2x2 piece), proving the depth cap
        // doesn't harm a legitimate, moderately-chained input.
        int count = 10;
        List<TableExtractor.CellRect> chain = peelableChain(count);

        List<List<TableExtractor.CellRect>> split = TableExtractor.splitComponent(chain);
        assertEquals(count, split.size(), "a shallow chain must fully separate into " + count + " tables");
        for (List<TableExtractor.CellRect> piece : split) {
            assertEquals(4, piece.size());
            TableExtractor.TableHit t = TableExtractor.buildTable(1, piece, "lattice");
            assertNotNull(t);
            assertEquals(2, t.rowCount);
            assertEquals(2, t.colCount);
            assertTrue(t.cells.stream().allMatch(c -> c.rowSpan == 1 && c.colSpan == 1),
                    "each peeled-off table must carry no invented spans: " + t.cells);
        }
    }
}
