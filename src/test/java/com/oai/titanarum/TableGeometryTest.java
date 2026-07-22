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

    private static TableExtractor.CellRect cellRect(float x0, float y0, float x1, float y1) {
        TableExtractor.CellRect c = new TableExtractor.CellRect();
        c.x0 = x0; c.y0 = y0; c.x1 = x1; c.y1 = y1;
        return c;
    }
}
