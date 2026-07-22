package com.oai.titanarum;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/**
 * Table extraction for report.json: a tagged-structure path (Table/TR/TH/TD from the
 * PDF structure tree) and a lattice path reconstructing tables from drawn ruling lines.
 *
 * <p>Lattice extraction approach informed by tabula-java
 * (https://github.com/tabulapdf/tabula-java), MIT License. No tabula code is vendored;
 * the spreadsheet/lattice algorithm is reimplemented against the PDFBox 3 API.
 *
 * <p>All coordinates are top-left-origin PDF points (y increases downward), matching
 * {@code TextPosition} space. bbox arrays are [x0, y0, x1, y1].
 */
final class TableExtractor {

    // Hostile-input bounds — over-cap drops the page/table and sets Result.truncated.
    static final int MAX_TABLES_PER_PAGE = 50;
    static final int MAX_CELLS_PER_TABLE = 10_000;
    static final int MAX_RULINGS_PER_PAGE = 10_000;

    private TableExtractor() {}

    // ---------------------------------------------------------------- result model

    /** One extracted table, serialized verbatim into report.json. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TableHit {
        public int page;
        public String extractionMethod;   // "tagged" | "lattice"
        public float[] bbox;              // [x0, y0, x1, y1], top-left-origin points
        public int rowCount;
        public int colCount;
        public List<List<String>> rows;   // jq-friendly grid; span anchors carry text, covered = ""
        public List<CellHit> cells;       // faithful structure
        public String markdown;
    }

    /** One cell (span anchor) of a table. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CellHit {
        public int row;
        public int col;
        public int rowSpan;
        public int colSpan;
        public String text;
        public float[] bbox;
        public Boolean header;            // only set on the tagged path (TH); null → omitted
    }

    /** Extraction outcome for one document. */
    static final class Result {
        final List<TableHit> tables = new ArrayList<>();
        boolean truncated = false;
    }

    // ---------------------------------------------------------------- views

    /** Fill {@code t.rows} and {@code t.markdown} from cells + rowCount/colCount. */
    static void renderViews(TableHit t) {
        String[][] grid = new String[t.rowCount][t.colCount];
        for (String[] row : grid) java.util.Arrays.fill(row, "");
        for (CellHit c : t.cells) {
            if (c.row >= 0 && c.row < t.rowCount && c.col >= 0 && c.col < t.colCount) {
                grid[c.row][c.col] = c.text == null ? "" : c.text;
            }
        }
        List<List<String>> rows = new ArrayList<>(t.rowCount);
        for (String[] row : grid) rows.add(List.of(row));
        t.rows = rows;

        StringBuilder md = new StringBuilder();
        for (int r = 0; r < t.rowCount; r++) {
            md.append('|');
            for (int c = 0; c < t.colCount; c++) {
                md.append(' ').append(mdEscape(grid[r][c])).append(" |");
            }
            if (r == 0 && t.rowCount > 1) {
                md.append('\n').append("|");
                for (int c = 0; c < t.colCount; c++) md.append("---|");
            }
            if (r < t.rowCount - 1) md.append('\n');
        }
        t.markdown = md.toString();
    }

    private static String mdEscape(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replace("\\", "\\\\").replace("|", "\\|").replace('\n', ' ');
    }

    // ---------------------------------------------------------------- geometry

    static final float SNAP = 2f;           // endpoint snap grid (pt)
    static final float EPS = 1f;            // intersection tolerance (pt)
    static final float MIN_RULING_LEN = 8f; // shorter segments are decoration
    static final float THIN_FILL_MAX = 3f;  // filled rects thinner than this are lines
    // A hair above the intersection-point count of a MAX_CELLS_PER_TABLE-sized grid
    // (101x101 points ~ 10k cells); MAX_RULINGS_PER_PAGE alone (5k horiz x 5k vert) still
    // allows a 25M-point cross product, so findCells enforces this cap independently.
    // This bounds memory (point count); it does NOT bound the O(P^2) pair-matching work
    // below the cap, which is why MAX_FINDCELLS_WORK exists separately.
    static final int MAX_INTERSECTIONS = 40_000;
    // Deterministic bound on (top-left, bottom-right) candidate pairs examined in findCells'
    // matching loop. A legitimate dense grid completes almost every top-left's search within
    // a handful of candidates (the very next point along usually closes the cell), so real
    // tables finish far under this budget. A "tick-mark grid" adversarial layout — many
    // intersection points, none of which ever complete a cell — instead forces a full O(P^2)
    // scan with per-candidate edge-coverage checks; that pathological case now fails fast and
    // deterministically instead of after a long, input-dependent wall-clock stall.
    static final long MAX_FINDCELLS_WORK = 2_000_000;

    /** Thrown when a page exceeds a geometry cap (ruling/intersection bomb) — caller skips the page. */
    static final class RulingOverflowException extends RuntimeException {
        RulingOverflowException() { super("ruling cap exceeded", null, true, false); }
    }

    /** An axis-aligned ruling segment, normalized so x1<=x2 and y1<=y2. */
    static final class Ruling {
        final float x1, y1, x2, y2;

        Ruling(float x1, float y1, float x2, float y2) {
            this.x1 = Math.min(x1, x2);
            this.x2 = Math.max(x1, x2);
            this.y1 = Math.min(y1, y2);
            this.y2 = Math.max(y1, y2);
        }

        boolean horizontal() { return y1 == y2; }
        boolean vertical()   { return x1 == x2; }
        float length()       { return horizontal() ? x2 - x1 : y2 - y1; }
    }

    /** A minimal (possibly spanning) cell rectangle found from rulings. */
    static final class CellRect {
        float x0, y0, x1, y1;
        String text;
    }

    private static float snap(float v) { return Math.round(v / SNAP) * SNAP; }

    /**
     * Snap endpoints to the SNAP grid, merge collinear overlapping/adjacent fragments,
     * apply the minimum-length filter to the MERGED rulings, then drop rulings that
     * intersect no perpendicular ruling (isolated underlines).
     *
     * <p>The length filter runs after merging (not per raw fragment) so a dashed border
     * drawn as many short collinear strokes (each individually under MIN_RULING_LEN) still
     * survives once the dashes are merged into one long logical ruling.
     */
    static List<Ruling> normalize(List<Ruling> raw) {
        List<Ruling> horiz = new ArrayList<>();
        List<Ruling> vert = new ArrayList<>();
        for (Ruling r : raw) {
            Ruling s = new Ruling(snap(r.x1), snap(r.y1), snap(r.x2), snap(r.y2));
            if (s.horizontal() && !s.vertical()) horiz.add(s);
            else if (s.vertical() && !s.horizontal()) vert.add(s);
        }
        horiz = mergeCollinear(horiz, true);
        vert = mergeCollinear(vert, false);
        horiz.removeIf(h -> h.length() < MIN_RULING_LEN);
        vert.removeIf(v -> v.length() < MIN_RULING_LEN);

        List<Ruling> keptH = new ArrayList<>();
        for (Ruling h : horiz) if (intersectsAny(h, vert)) keptH.add(h);
        List<Ruling> keptV = new ArrayList<>();
        for (Ruling v : vert) if (intersectsAny(v, keptH)) keptV.add(v);
        List<Ruling> out = new ArrayList<>(keptH);
        out.addAll(keptV);
        return out;
    }

    private static List<Ruling> mergeCollinear(List<Ruling> in, boolean horizontal) {
        // Group by the fixed coordinate, then sweep-merge along the variable one.
        java.util.Map<Float, List<Ruling>> byPos = new java.util.TreeMap<>();
        for (Ruling r : in) byPos.computeIfAbsent(horizontal ? r.y1 : r.x1, k -> new ArrayList<>()).add(r);
        List<Ruling> out = new ArrayList<>();
        for (var e : byPos.entrySet()) {
            List<Ruling> group = e.getValue();
            group.sort(java.util.Comparator.comparingDouble(r -> horizontal ? r.x1 : r.y1));
            float lo = horizontal ? group.get(0).x1 : group.get(0).y1;
            float hi = horizontal ? group.get(0).x2 : group.get(0).y2;
            for (int i = 1; i < group.size(); i++) {
                Ruling r = group.get(i);
                float s = horizontal ? r.x1 : r.y1, t = horizontal ? r.x2 : r.y2;
                if (s <= hi + SNAP) { hi = Math.max(hi, t); }
                else {
                    out.add(horizontal ? new Ruling(lo, e.getKey(), hi, e.getKey())
                                       : new Ruling(e.getKey(), lo, e.getKey(), hi));
                    lo = s; hi = t;
                }
            }
            out.add(horizontal ? new Ruling(lo, e.getKey(), hi, e.getKey())
                               : new Ruling(e.getKey(), lo, e.getKey(), hi));
        }
        return out;
    }

    private static boolean intersectsAny(Ruling r, List<Ruling> perpendiculars) {
        for (Ruling p : perpendiculars) if (intersects(r, p)) return true;
        return false;
    }

    /** True when a horizontal and a vertical ruling cross (with EPS slack at endpoints). */
    private static boolean intersects(Ruling a, Ruling b) {
        Ruling h = a.horizontal() ? a : b;
        Ruling v = a.horizontal() ? b : a;
        if (!h.horizontal() || !v.vertical()) return false;
        return v.x1 >= h.x1 - EPS && v.x1 <= h.x2 + EPS
            && h.y1 >= v.y1 - EPS && h.y1 <= v.y2 + EPS;
    }

    /**
     * tabula's spreadsheet algorithm: intersection points -> for each top-left corner,
     * the nearest bottom-right corner whose four edges are fully covered by rulings
     * forms a cell. Spanning cells fall out naturally where internal edges are absent.
     */
    static List<CellRect> findCells(List<Ruling> horiz, List<Ruling> vert) {
        // All intersection points, sorted (y, x).
        java.util.TreeSet<Long> pts = new java.util.TreeSet<>();
        List<float[]> points = new ArrayList<>();
        for (Ruling h : horiz) {
            for (Ruling v : vert) {
                if (intersects(h, v)) {
                    long key = (((long) Float.floatToIntBits(v.x1)) << 32) | (Float.floatToIntBits(h.y1) & 0xffffffffL);
                    if (pts.add(key)) {
                        points.add(new float[]{v.x1, h.y1});
                        if (points.size() > MAX_INTERSECTIONS) throw new RulingOverflowException();
                    }
                }
            }
        }
        points.sort((p, q) -> p[1] != q[1] ? Float.compare(p[1], q[1]) : Float.compare(p[0], q[0]));

        java.util.Set<String> pointSet = new java.util.HashSet<>();
        for (float[] p : points) pointSet.add(pkey(p[0], p[1]));

        // Index rulings by their fixed (snapped) coordinate so an edge-coverage query only
        // scans the (usually one) rulings actually at that coordinate, instead of every
        // horizontal/vertical ruling on the page. Since all rulings passed in are
        // normalize()-snapped to the SNAP grid, a query at a snapped coordinate need only
        // check that exact key plus its +-SNAP neighbors to preserve the |coord| <= EPS
        // tolerance the old linear scan gave (EPS < SNAP, so no coordinate 2*SNAP+ away can
        // ever satisfy it).
        java.util.Map<Float, List<Ruling>> hByY = new java.util.HashMap<>();
        for (Ruling h : horiz) hByY.computeIfAbsent(snap(h.y1), k -> new ArrayList<>()).add(h);
        java.util.Map<Float, List<Ruling>> vByX = new java.util.HashMap<>();
        for (Ruling v : vert) vByX.computeIfAbsent(snap(v.x1), k -> new ArrayList<>()).add(v);

        List<CellRect> cells = new ArrayList<>();
        long work = 0;
        for (int i = 0; i < points.size(); i++) {
            float[] tl = points.get(i);
            CellRect best = null;
            for (int j = i + 1; j < points.size() && best == null; j++) {
                if (++work > MAX_FINDCELLS_WORK) throw new RulingOverflowException();
                float[] br = points.get(j);
                if (br[0] <= tl[0] + EPS || br[1] <= tl[1] + EPS) continue;
                if (!pointSet.contains(pkey(br[0], tl[1]))) continue; // top-right corner
                if (!pointSet.contains(pkey(tl[0], br[1]))) continue; // bottom-left corner
                if (edgeCoveredH(hByY, tl[1], tl[0], br[0])
                        && edgeCoveredH(hByY, br[1], tl[0], br[0])
                        && edgeCoveredV(vByX, tl[0], tl[1], br[1])
                        && edgeCoveredV(vByX, br[0], tl[1], br[1])) {
                    CellRect c = new CellRect();
                    c.x0 = tl[0]; c.y0 = tl[1]; c.x1 = br[0]; c.y1 = br[1];
                    best = c;
                }
            }
            if (best != null) cells.add(best);
        }
        return cells;
    }

    private static String pkey(float x, float y) { return snap(x) + ":" + snap(y); }

    private static boolean edgeCoveredH(java.util.Map<Float, List<Ruling>> hByY, float y, float xa, float xb) {
        float key = snap(y);
        for (float k : new float[]{key - SNAP, key, key + SNAP}) {
            List<Ruling> bucket = hByY.get(k);
            if (bucket == null) continue;
            for (Ruling h : bucket) {
                if (Math.abs(h.y1 - y) <= EPS && h.x1 <= xa + EPS && h.x2 >= xb - EPS) return true;
            }
        }
        return false;
    }

    private static boolean edgeCoveredV(java.util.Map<Float, List<Ruling>> vByX, float x, float ya, float yb) {
        float key = snap(x);
        for (float k : new float[]{key - SNAP, key, key + SNAP}) {
            List<Ruling> bucket = vByX.get(k);
            if (bucket == null) continue;
            for (Ruling v : bucket) {
                if (Math.abs(v.x1 - x) <= EPS && v.y1 <= ya + EPS && v.y2 >= yb - EPS) return true;
            }
        }
        return false;
    }

    /** Union cells whose rectangles touch (within SNAP) into candidate tables. */
    static List<List<CellRect>> groupIntoTables(List<CellRect> cells) {
        int n = cells.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (touches(cells.get(i), cells.get(j))) union(parent, i, j);
            }
        }
        java.util.Map<Integer, List<CellRect>> comps = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) comps.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(cells.get(i));
        return new ArrayList<>(comps.values());
    }

    /**
     * True when two cell rectangles share an EDGE, not merely a corner point: they must
     * abut (within SNAP) along one axis while their projections on the other axis overlap
     * by a positive length (&gt; EPS). Pure bbox-overlap-with-slack would also call two
     * rectangles that meet only at a shared corner "touching", which lets diagonal quadrants
     * of an otherwise-empty 2x2 grid merge into a single (bogus) component.
     */
    private static boolean touches(CellRect a, CellRect b) {
        float xOverlap = Math.min(a.x1, b.x1) - Math.max(a.x0, b.x0);
        float yOverlap = Math.min(a.y1, b.y1) - Math.max(a.y0, b.y0);
        boolean xAdjacent = Math.abs(a.x1 - b.x0) <= SNAP || Math.abs(b.x1 - a.x0) <= SNAP;
        boolean yAdjacent = Math.abs(a.y1 - b.y0) <= SNAP || Math.abs(b.y1 - a.y0) <= SNAP;
        if (xAdjacent && yOverlap > EPS) return true;   // side-by-side, sharing a vertical edge
        if (yAdjacent && xOverlap > EPS) return true;   // stacked, sharing a horizontal edge
        return xOverlap > EPS && yOverlap > EPS;        // genuine area overlap (shouldn't normally occur)
    }

    private static int find(int[] p, int i) { while (p[i] != i) { p[i] = p[p[i]]; i = p[i]; } return i; }
    private static void union(int[] p, int a, int b) { p[find(p, a)] = find(p, b); }

    /**
     * Assign row/col indices + spans from clustered edge boundaries and build the TableHit
     * (no text, views not rendered). Returns null for components below 2x2 / 4 cells.
     *
     * <p>Note: the 2x2 threshold is enforced below via the clustered row/col boundary counts
     * (rowCount, colCount), not via {@code comp.size()} — a component can legitimately contain
     * fewer than 4 CellRects (e.g. 3) when a spanning cell covers more than one logical grid
     * cell, while still representing a full 2x2 (4 logical cell) table.
     */
    static TableHit buildTable(int page, List<CellRect> comp, String method) {
        if (comp.isEmpty()) return null;
        java.util.TreeSet<Float> xsSet = new java.util.TreeSet<>();
        java.util.TreeSet<Float> ysSet = new java.util.TreeSet<>();
        for (CellRect c : comp) {
            xsSet.add(snap(c.x0)); xsSet.add(snap(c.x1));
            ysSet.add(snap(c.y0)); ysSet.add(snap(c.y1));
        }
        List<Float> xs = new ArrayList<>(xsSet);
        List<Float> ys = new ArrayList<>(ysSet);
        int colCount = xs.size() - 1;
        int rowCount = ys.size() - 1;
        if (rowCount < 2 || colCount < 2) return null;

        TableHit t = new TableHit();
        t.page = page;
        t.extractionMethod = method;
        t.rowCount = rowCount;
        t.colCount = colCount;
        t.cells = new ArrayList<>(comp.size());
        float bx0 = Float.MAX_VALUE, by0 = Float.MAX_VALUE, bx1 = -Float.MAX_VALUE, by1 = -Float.MAX_VALUE;
        for (CellRect c : comp) {
            CellHit cell = new CellHit();
            cell.col = nearestIndex(xs, c.x0);
            cell.row = nearestIndex(ys, c.y0);
            cell.colSpan = Math.max(1, nearestIndex(xs, c.x1) - cell.col);
            cell.rowSpan = Math.max(1, nearestIndex(ys, c.y1) - cell.row);
            cell.text = c.text;
            cell.bbox = new float[]{c.x0, c.y0, c.x1, c.y1};
            t.cells.add(cell);
            bx0 = Math.min(bx0, c.x0); by0 = Math.min(by0, c.y0);
            bx1 = Math.max(bx1, c.x1); by1 = Math.max(by1, c.y1);
        }
        t.bbox = new float[]{bx0, by0, bx1, by1};
        t.cells.sort(java.util.Comparator.<CellHit>comparingInt(c -> c.row).thenComparingInt(c -> c.col));
        return t;
    }

    private static int nearestIndex(List<Float> boundaries, float v) {
        int best = 0;
        float bestD = Float.MAX_VALUE;
        for (int i = 0; i < boundaries.size(); i++) {
            float d = Math.abs(boundaries.get(i) - v);
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }
}
