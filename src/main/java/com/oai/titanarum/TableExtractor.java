package com.oai.titanarum;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.PDTableAttributeObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFMarkedContentExtractor;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    // Total buffered path points (across all pending subpaths of one path) RulingCollector
    // will hold before giving up on that path as a ruling candidate. A content stream that
    // free-runs lineTo/curveTo without ever issuing a paint op would otherwise grow this
    // buffer unboundedly; see RulingCollector.addPoint for the drop-not-throw handling.
    static final int MAX_PATH_POINTS = 50_000;
    // fillCellsFromPositions is O(cells x positions) with no other natural bound (unlike the
    // ruling/cell caps above, which cap the geometry side). A page with a huge glyph count
    // (text bomb) crossed with a table containing many cells could otherwise stall on this
    // midpoint-containment scan; this budget counts each (cell, position) check across the
    // WHOLE page (shared across every table component filled via the position path) and bails
    // out deterministically, consistent with the other geometry caps' throw-and-skip-page
    // handling in extract()'s per-page catch.
    static final long MAX_TEXTFILL_WORK = 20_000_000;

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

    // ---------------------------------------------------------------- ruling collection

    /**
     * Collect candidate ruling segments from a page's content stream: stroked axis-aligned
     * segments plus thin filled rectangles (many generators draw rules as fills).
     * Output is in the page's VISUAL top-left-origin space, i.e. after applying /Rotate
     * (see {@link #applyPageRotation}) -- the same frame {@code PDFTextStripperByArea} and
     * {@code TextPosition.getX()/getY()} use, so rulings and text always share one frame.
     */
    static List<Ruling> collectRulings(PDPage page) throws IOException {
        RulingCollector rc = new RulingCollector(page);
        rc.processPage(page);
        return rc.rulings;
    }

    /**
     * Maps a point from the page's UNROTATED cropBox-relative, top-left, y-down frame (i.e.
     * post shiftX/flipY, which is also the frame {@code TextPosition.getXDirAdj()/getYDirAdj()}
     * always report in -- those track per-glyph text DIRECTION, not the page's /Rotate) into the
     * page's VISUAL top-left frame after applying /Rotate. This is derived to match PDFBox's own
     * rotation-aware {@code TextPosition.getX()/getY()} (which IS keyed off page.getRotation(),
     * and which {@code PDFTextStripperByArea} compares region rectangles against): verified by
     * constructing a rotated fixture and printing getX()/getY() against the formula below for
     * r=90/180/270. {@code unrotatedW}/{@code unrotatedH} are the page's cropBox width/height
     * BEFORE any 90/270 visual swap.
     */
    static float[] applyPageRotation(float x, float y, int rotation, float unrotatedW, float unrotatedH) {
        switch (rotation) {
            case 90:  return new float[]{ unrotatedH - y, x };
            case 180: return new float[]{ unrotatedW - x, unrotatedH - y };
            case 270: return new float[]{ y, unrotatedW - x };
            default:  return new float[]{ x, y };
        }
    }

    private static final class RulingCollector extends PDFGraphicsStreamEngine {
        final List<Ruling> rulings = new ArrayList<>();
        private final PDRectangle cropBox;
        private final int rotation;

        // A path may batch several subpaths (multiple m/l groups, or multiple re rects)
        // before a single paint op (S / f / B); each finished subpath is moved into
        // `subpaths` by moveTo/appendRectangle, with `current` holding the one still open.
        private final List<List<float[]>> subpaths = new ArrayList<>();
        private List<float[]> current = new ArrayList<>();
        private int pointCount = 0;
        private boolean overflowed = false;

        RulingCollector(PDPage page) {
            super(page);
            this.cropBox = page.getCropBox();
            this.rotation = page.getRotation();
        }

        // Page space is cropBox-relative (matches TextPosition space): shift x by the
        // cropBox's lower-left corner, and flip y around the cropBox's upper edge rather
        // than the raw page height, so a non-origin CropBox doesn't leave rulings offset.
        private float shiftX(float x) { return x - cropBox.getLowerLeftX(); }
        private float flipY(float y) { return cropBox.getUpperRightY() - y; }

        private void addRuling(float x1, float y1, float x2, float y2) {
            boolean axisAligned = Math.abs(x1 - x2) <= EPS || Math.abs(y1 - y2) <= EPS;
            if (!axisAligned) return;
            float uw = cropBox.getWidth(), uh = cropBox.getHeight();
            float[] p1 = applyPageRotation(shiftX(x1), flipY(y1), rotation, uw, uh);
            float[] p2 = applyPageRotation(shiftX(x2), flipY(y2), rotation, uw, uh);
            Ruling r = new Ruling(p1[0], p1[1], p2[0], p2[1]);
            if (r.length() < MIN_RULING_LEN) return;
            if (rulings.size() >= MAX_RULINGS_PER_PAGE) throw new RulingOverflowException();
            rulings.add(r);
        }

        // -- path accumulation (coordinates arrive already CTM-transformed / device space)

        /**
         * Buffer one path point, subject to MAX_PATH_POINTS. On overflow we DROP the
         * buffered geometry for this path rather than throwing: an enormous path is
         * legitimate page content (vector art), just not a ruling candidate, and the
         * caps that guard actual output size (MAX_RULINGS_PER_PAGE, findCells' caps)
         * already bound the expensive parts of the pipeline. We stay "overflowed" -
         * ignoring further points - until the next paint op or endPath resets state.
         */
        private void addPoint(float[] p) {
            if (overflowed) return;
            if (pointCount >= MAX_PATH_POINTS) {
                overflowed = true;
                subpaths.clear();
                current = new ArrayList<>();
                return;
            }
            current.add(p);
            pointCount++;
        }

        private void finalizeCurrentSubpath() {
            if (!current.isEmpty()) {
                subpaths.add(current);
                current = new ArrayList<>();
            }
        }

        private void resetPath() {
            subpaths.clear();
            current = new ArrayList<>();
            pointCount = 0;
            overflowed = false;
        }

        @Override public void moveTo(float x, float y) {
            if (overflowed) return;
            finalizeCurrentSubpath();
            addPoint(new float[]{x, y});
        }

        @Override public void lineTo(float x, float y) {
            if (overflowed) return;
            addPoint(new float[]{x, y});
        }

        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            if (overflowed) return;
            addPoint(new float[]{x3, y3}); // curves are not rulings; keep the endpoint for continuity
        }

        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            if (overflowed) return;
            finalizeCurrentSubpath();
            addPoint(new float[]{(float) p0.getX(), (float) p0.getY()});
            addPoint(new float[]{(float) p1.getX(), (float) p1.getY()});
            addPoint(new float[]{(float) p2.getX(), (float) p2.getY()});
            addPoint(new float[]{(float) p3.getX(), (float) p3.getY()});
            addPoint(new float[]{(float) p0.getX(), (float) p0.getY()});
        }

        @Override public void closePath() {
            if (overflowed || current.isEmpty()) return;
            float[] first = current.get(0);
            addPoint(new float[]{first[0], first[1]});
        }

        @Override public void strokePath() {
            finalizeCurrentSubpath();
            for (List<float[]> sp : subpaths) {
                for (int i = 1; i < sp.size(); i++) {
                    float[] a = sp.get(i - 1), b = sp.get(i);
                    addRuling(a[0], a[1], b[0], b[1]);
                }
            }
            resetPath();
        }

        @Override public void fillPath(int windingRule) {
            finalizeCurrentSubpath();
            for (List<float[]> sp : subpaths) emitThinFillAsRuling(sp);
            resetPath();
        }

        @Override public void fillAndStrokePath(int windingRule) {
            finalizeCurrentSubpath();
            for (List<float[]> sp : subpaths) {
                // Stroke edges AND consider the thin-fill case, per subpath.
                for (int i = 1; i < sp.size(); i++) {
                    float[] a = sp.get(i - 1), b = sp.get(i);
                    addRuling(a[0], a[1], b[0], b[1]);
                }
                emitThinFillAsRuling(sp);
            }
            resetPath();
        }

        /** A filled axis-aligned rect thinner than THIN_FILL_MAX in one dimension is a drawn line. */
        private void emitThinFillAsRuling(List<float[]> sp) {
            if (sp.isEmpty()) return;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (float[] p : sp) {
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            }
            float w = maxX - minX, h = maxY - minY;
            if (h <= THIN_FILL_MAX && w >= MIN_RULING_LEN) {
                float midY = (minY + maxY) / 2;
                addRuling(minX, midY, maxX, midY);
            } else if (w <= THIN_FILL_MAX && h >= MIN_RULING_LEN) {
                float midX = (minX + maxX) / 2;
                addRuling(midX, minY, midX, maxY);
            }
        }

        // -- everything else ignored

        @Override public void drawImage(PDImage pdImage) {}
        @Override public void clip(int windingRule) {}
        @Override public void shadingFill(COSName shadingName) {}
        @Override public void endPath() { resetPath(); }
        @Override public Point2D.Float getCurrentPoint() {
            if (!current.isEmpty()) {
                float[] last = current.get(current.size() - 1);
                return new Point2D.Float(last[0], last[1]);
            }
            if (!subpaths.isEmpty()) {
                List<float[]> lastSp = subpaths.get(subpaths.size() - 1);
                if (!lastSp.isEmpty()) {
                    float[] p = lastSp.get(lastSp.size() - 1);
                    return new Point2D.Float(p[0], p[1]);
                }
            }
            return new Point2D.Float(0, 0);
        }
    }

    // ---------------------------------------------------------------- text fill

    /**
     * Join per-char TextPositions into cell text: group into lines by y (2pt tolerance),
     * order lines top-to-bottom and chars left-to-right, insert a space on word-sized gaps,
     * join lines with '\n'.
     */
    static String joinText(List<TextPosition> chars) {
        if (chars.isEmpty()) return "";
        List<TextPosition> sorted = new ArrayList<>(chars);
        sorted.sort(java.util.Comparator.comparingDouble(TextPosition::getYDirAdj)
                .thenComparingDouble(TextPosition::getXDirAdj));
        StringBuilder out = new StringBuilder();
        float lineY = sorted.get(0).getYDirAdj();
        TextPosition prev = null;
        for (TextPosition tp : sorted) {
            if (tp.getYDirAdj() - lineY > 2f) {           // new line
                out.append('\n');
                lineY = tp.getYDirAdj();
                prev = null;
            }
            if (prev != null) {
                float gap = tp.getXDirAdj() - (prev.getXDirAdj() + prev.getWidthDirAdj());
                if (gap > Math.max(1.5f, 0.25f * tp.getHeightDir()) && out.length() > 0
                        && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
            }
            out.append(tp.getUnicode());
            prev = tp;
        }
        return out.toString().strip();
    }

    /**
     * Midpoint-containment bucket of positions into a cell rect (visual, /Rotate-applied space
     * -- same as {@link #collectRulings}). {@code getXDirAdj()/getYDirAdj()} report per-glyph
     * text DIRECTION, not the page's /Rotate (proven empirically: identical values regardless of
     * page.getRotation() for upright text), so the raw midpoint computed from them is always in
     * the UNROTATED frame; {@link #applyPageRotation} converts it into the same visual frame the
     * (now rotation-aware) cell rects live in, using the shared transform also applied in
     * {@code RulingCollector}.
     *
     * <p>Bounded by {@code budget} (see {@link #MAX_TEXTFILL_WORK}): a hostile page pairing a
     * huge glyph count with a many-celled table would otherwise make this O(cells x positions)
     * scan unbounded, unlike every other hot loop in this class.
     */
    static void fillCellsFromPositions(List<CellRect> cells, List<TextPosition> positions,
                                        int rotation, float unrotatedW, float unrotatedH,
                                        long[] work, long budget) {
        for (CellRect c : cells) {
            List<TextPosition> in = new ArrayList<>();
            for (TextPosition tp : positions) {
                if (++work[0] > budget) throw new RulingOverflowException();
                float ux = tp.getXDirAdj() + tp.getWidthDirAdj() / 2;
                float uy = tp.getYDirAdj() - tp.getHeightDir() / 2;
                float[] v = applyPageRotation(ux, uy, rotation, unrotatedW, unrotatedH);
                if (v[0] >= c.x0 && v[0] <= c.x1 && v[1] >= c.y0 && v[1] <= c.y1) in.add(tp);
            }
            c.text = joinText(in);
        }
    }

    private static void fillCellsFromPositions(List<CellRect> cells, List<TextPosition> positions,
                                               int rotation, float unrotatedW, float unrotatedH, long[] work) {
        fillCellsFromPositions(cells, positions, rotation, unrotatedW, unrotatedH, work, MAX_TEXTFILL_WORK);
    }

    /**
     * Fallback when no TextPositions were collected (--skip-text-urls): region-strip, batched
     * into ONE {@code PDFTextStripperByArea} pass (one content-stream walk) for every qualifying
     * table on the page, rather than one walk per table. Regions are namespaced
     * {@code "t"+tableIdx+"c"+cellIdx} so cells from different tables never collide.
     *
     * <p>{@code PDFTextStripperByArea} matches a region against {@code TextPosition.getX()/getY()}
     * (verified from source: {@code rect.contains(text.getX(), text.getY())}), which -- unlike
     * getXDirAdj/getYDirAdj -- IS keyed off the page's actual /Rotate. Our cell rects are already
     * in that same visual frame (via {@link #collectRulings}), so no extra transform is needed
     * here.
     *
     * <p>{@code setSortByPosition(true)}: without it, on a page whose /Rotate disagrees with its
     * glyphs' own (unrotated) text direction -- exactly the shape produced by rotating an
     * otherwise-ordinary page via the /Rotate flag alone, which is how {@code rotatedRuled3x3}
     * (and plenty of real scanned/rotated PDFs) are built -- the default line-grouping
     * mis-splits a single token across several one-character lines (e.g. "R3C1" back as
     * "R\n3C\n1"). Verified empirically: identical region, same characters matched, only this
     * flag differs between the broken and clean output.
     */
    private static void fillCellsByRegion(List<List<CellRect>> tables, PDPage page) throws IOException {
        PDFTextStripperByArea area = new PDFTextStripperByArea();
        area.setSortByPosition(true);
        for (int ti = 0; ti < tables.size(); ti++) {
            List<CellRect> comp = tables.get(ti);
            for (int ci = 0; ci < comp.size(); ci++) {
                CellRect c = comp.get(ci);
                area.addRegion("t" + ti + "c" + ci,
                        new java.awt.geom.Rectangle2D.Float(c.x0, c.y0, c.x1 - c.x0, c.y1 - c.y0));
            }
        }
        area.extractRegions(page);
        for (int ti = 0; ti < tables.size(); ti++) {
            List<CellRect> comp = tables.get(ti);
            for (int ci = 0; ci < comp.size(); ci++) {
                comp.get(ci).text = area.getTextForRegion("t" + ti + "c" + ci).strip();
            }
        }
    }

    // ---------------------------------------------------------------- entry point

    /**
     * Extract tables for the given (1-indexed) pages. Never throws: per-page failures are
     * logged and skipped. positionsByPage may be empty (--skip-text-urls) -> region fallback.
     */
    static Result extract(PDDocument doc, List<Integer> pagesToProcess,
                          Map<Integer, List<TextPosition>> positionsByPage) {
        Result result = new Result();
        Set<Integer> coveredByTagged = new HashSet<>();
        try {
            extractTagged(doc, new HashSet<>(pagesToProcess), coveredByTagged, result);
        } catch (Exception e) {
            System.err.println("WARNING: tagged table extraction failed: " + e);
        }
        for (int pageNum : pagesToProcess) {
            if (coveredByTagged.contains(pageNum)) continue;
            try {
                extractLatticePage(doc, pageNum, positionsByPage.get(pageNum), result);
            } catch (RulingOverflowException e) {
                result.truncated = true;
                System.err.println("WARNING: table extraction skipped on page " + pageNum + " (ruling cap)");
            } catch (Exception e) {
                System.err.println("WARNING: table extraction failed on page " + pageNum + ": " + e);
            }
        }
        result.tables.sort(java.util.Comparator
                .<TableHit>comparingInt(t -> t.page)
                .thenComparingDouble(t -> t.bbox == null ? 0 : t.bbox[1]));
        return result;
    }

    private static void extractLatticePage(PDDocument doc, int pageNum,
                                           List<TextPosition> positions, Result result) throws IOException {
        if (pageNum < 1 || pageNum > doc.getNumberOfPages()) return;
        PDPage page = doc.getPage(pageNum - 1);
        int rotation = page.getRotation();
        PDRectangle cropBox = page.getCropBox();
        float unrotatedW = cropBox.getWidth();
        float unrotatedH = cropBox.getHeight();

        List<Ruling> rulings = normalize(collectRulings(page));
        if (rulings.isEmpty()) return;
        List<Ruling> horiz = new ArrayList<>();
        List<Ruling> vert = new ArrayList<>();
        for (Ruling r : rulings) (r.horizontal() ? horiz : vert).add(r);
        List<CellRect> cells = findCells(horiz, vert);
        if (cells.isEmpty()) return;

        // Decide which components actually become tables (geometry only - buildTable(...) here
        // is used twice per kept table: once to gate on the 2x2 threshold / MAX_TABLES_PER_PAGE
        // before any text is filled, and once for real below once cell text is in place. This
        // (cheap, in-memory) double geometry pass is what lets the region-strip fallback below
        // run ONE content-stream walk for the whole page instead of one per table.
        List<List<CellRect>> kept = new ArrayList<>();
        int tablesOnPage = 0;
        for (List<CellRect> comp : groupIntoTables(cells)) {
            if (comp.size() > MAX_CELLS_PER_TABLE) {
                result.truncated = true;
                continue;
            }
            if (buildTable(pageNum, comp, "lattice") == null) continue;
            if (++tablesOnPage > MAX_TABLES_PER_PAGE) {
                result.truncated = true;
                break;
            }
            kept.add(comp);
        }

        if (positions != null && !positions.isEmpty()) {
            long[] work = {0};
            for (List<CellRect> comp : kept) {
                fillCellsFromPositions(comp, positions, rotation, unrotatedW, unrotatedH, work);
            }
        } else if (!kept.isEmpty()) {
            fillCellsByRegion(kept, page);
        }

        for (List<CellRect> comp : kept) {
            TableHit t = buildTable(pageNum, comp, "lattice");
            renderViews(t);
            result.tables.add(t);
        }
    }

    // ---------------------------------------------------------------- tagged path

    /** Internal: one tagged cell before grid placement. */
    private static final class TaggedCell {
        String text = "";
        int rowSpan = 1, colSpan = 1;
        boolean th;
        float[] bbox;   // union of glyph boxes; null when no glyphs resolved
        PDPage page;
    }

    private static void extractTagged(PDDocument doc, Set<Integer> pagesToProcess,
                                      Set<Integer> coveredOut, Result result) throws IOException {
        PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
        if (root == null) return;
        List<PDStructureElement> tables = new ArrayList<>();
        collectByType(root, "Table", tables, 0);
        if (tables.isEmpty()) return;

        // page -> (mcid -> glyphs), computed lazily, one content pass per page
        Map<PDPage, Map<Integer, List<TextPosition>>> mcidCache = new HashMap<>();
        // page object -> 1-indexed number
        Map<PDPage, Integer> pageNumbers = new HashMap<>();
        int n = 0;
        for (PDPage p : doc.getPages()) pageNumbers.put(p, ++n);

        for (PDStructureElement tableEl : tables) {
            TableHit t = buildTaggedTable(tableEl, mcidCache, pageNumbers);
            if (t == null) continue;                      // degenerate -> lattice may still run
            if (!pagesToProcess.contains(t.page)) continue;
            renderViews(t);
            result.tables.add(t);
            coveredOut.add(t.page);
        }
    }

    /** DFS for structure elements of a standard type; depth-capped against cyclic/hostile trees. */
    private static void collectByType(PDStructureNode node, String type,
                                      List<PDStructureElement> out, int depth) {
        if (depth > 64 || out.size() > 10_000) return;
        for (Object kid : node.getKids()) {
            if (kid instanceof PDStructureElement el) {
                if (type.equals(el.getStandardStructureType())) out.add(el);
                else collectByType(el, type, out, depth + 1);
            }
        }
    }

    /** Build one tagged table; returns null when degenerate (no rows / no textful cells). */
    private static TableHit buildTaggedTable(PDStructureElement tableEl,
                                             Map<PDPage, Map<Integer, List<TextPosition>>> mcidCache,
                                             Map<PDPage, Integer> pageNumbers) throws IOException {
        List<PDStructureElement> trs = new ArrayList<>();
        collectByType(tableEl, "TR", trs, 0);
        if (trs.isEmpty()) return null;

        List<List<TaggedCell>> rows = new ArrayList<>();
        for (PDStructureElement tr : trs) {
            List<TaggedCell> row = new ArrayList<>();
            for (Object kid : tr.getKids()) {
                if (!(kid instanceof PDStructureElement el)) continue;
                String st = el.getStandardStructureType();
                if (!"TD".equals(st) && !"TH".equals(st)) continue;
                TaggedCell cell = new TaggedCell();
                cell.th = "TH".equals(st);
                readSpans(el, cell);
                resolveCellText(el, cell, mcidCache);
                row.add(cell);
            }
            if (!row.isEmpty()) rows.add(row);
        }
        if (rows.isEmpty()) return null;
        if (rows.stream().allMatch(r -> r.stream().allMatch(c -> c.text.isEmpty()))) return null;

        // Grid placement with an occupancy map (HTML table algorithm).
        int colCount = 0;
        List<CellHit> placed = new ArrayList<>();
        Map<Long, Boolean> occupied = new HashMap<>();
        PDPage tablePage = null;
        float bx0 = Float.MAX_VALUE, by0 = Float.MAX_VALUE, bx1 = -Float.MAX_VALUE, by1 = -Float.MAX_VALUE;
        boolean anyBbox = false;
        for (int r = 0; r < rows.size(); r++) {
            int c = 0;
            for (TaggedCell cell : rows.get(r)) {
                while (occupied.containsKey(((long) r << 32) | c)) c++;
                CellHit hit = new CellHit();
                hit.row = r;
                hit.col = c;
                hit.rowSpan = cell.rowSpan;
                hit.colSpan = cell.colSpan;
                hit.text = cell.text;
                hit.bbox = cell.bbox;
                if (cell.th) hit.header = Boolean.TRUE;
                placed.add(hit);
                for (int rr = r; rr < r + cell.rowSpan; rr++) {
                    for (int cc = c; cc < c + cell.colSpan; cc++) {
                        occupied.put(((long) rr << 32) | cc, Boolean.TRUE);
                    }
                }
                colCount = Math.max(colCount, c + cell.colSpan);
                if (cell.page != null && tablePage == null) tablePage = cell.page;
                if (cell.bbox != null) {
                    anyBbox = true;
                    bx0 = Math.min(bx0, cell.bbox[0]); by0 = Math.min(by0, cell.bbox[1]);
                    bx1 = Math.max(bx1, cell.bbox[2]); by1 = Math.max(by1, cell.bbox[3]);
                }
                c += cell.colSpan;
                if (placed.size() > MAX_CELLS_PER_TABLE) return null;
            }
        }
        if (tablePage == null || colCount == 0) return null;
        Integer pageNum = pageNumbers.get(tablePage);
        if (pageNum == null) return null;

        TableHit t = new TableHit();
        t.page = pageNum;
        t.extractionMethod = "tagged";
        t.rowCount = rows.size();
        t.colCount = colCount;
        t.cells = placed;
        if (anyBbox) t.bbox = new float[]{bx0, by0, bx1, by1};
        return t;
    }

    private static void readSpans(PDStructureElement el, TaggedCell cell) {
        try {
            org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.Revisions<
                    org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDAttributeObject> revisions =
                    el.getAttributes();
            // Revisions is not Iterable (no getAll()/iterator()) -- index by size()/getObject(i).
            for (int i = 0; i < revisions.size(); i++) {
                Object att = revisions.getObject(i);
                if (att instanceof PDTableAttributeObject tao) {
                    cell.rowSpan = Math.max(1, tao.getRowSpan());
                    cell.colSpan = Math.max(1, tao.getColSpan());
                }
            }
        } catch (Exception ignored) {
            // hostile/malformed attribute dicts: keep 1x1
        }
    }

    /** Gather the cell's MCIDs (bare integer kids and MCR kids), resolve to glyphs, join text. */
    private static void resolveCellText(PDStructureElement el, TaggedCell cell,
                                        Map<PDPage, Map<Integer, List<TextPosition>>> mcidCache) throws IOException {
        List<TextPosition> glyphs = new ArrayList<>();
        collectGlyphs(el, el.getPage(), glyphs, mcidCache, 0);
        cell.text = joinText(glyphs);
        if (!glyphs.isEmpty()) {
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (TextPosition tp : glyphs) {
                x0 = Math.min(x0, tp.getXDirAdj());
                y0 = Math.min(y0, tp.getYDirAdj() - tp.getHeightDir());
                x1 = Math.max(x1, tp.getXDirAdj() + tp.getWidthDirAdj());
                y1 = Math.max(y1, tp.getYDirAdj());
            }
            cell.bbox = new float[]{x0, y0, x1, y1};
        }
        if (el.getPage() != null) cell.page = el.getPage();
    }

    private static void collectGlyphs(PDStructureNode node, PDPage inheritedPage, List<TextPosition> out,
                                      Map<PDPage, Map<Integer, List<TextPosition>>> mcidCache, int depth) throws IOException {
        if (depth > 64) return;
        PDPage page = inheritedPage;
        if (node instanceof PDStructureElement el && el.getPage() != null) page = el.getPage();
        for (Object kid : node.getKids()) {
            if (kid instanceof PDStructureElement el) {
                collectGlyphs(el, page, out, mcidCache, depth + 1);
            } else if (kid instanceof Integer mcid && page != null) {
                out.addAll(glyphsFor(page, mcid, mcidCache));
            } else if (kid instanceof COSInteger ci && page != null) {
                out.addAll(glyphsFor(page, (int) ci.longValue(), mcidCache));
            } else if (kid instanceof org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference mcr) {
                PDPage mcrPage = mcr.getPage() != null ? mcr.getPage() : page;
                if (mcrPage != null) out.addAll(glyphsFor(mcrPage, mcr.getMCID(), mcidCache));
            }
        }
    }

    private static List<TextPosition> glyphsFor(PDPage page, int mcid,
                                                Map<PDPage, Map<Integer, List<TextPosition>>> cache) throws IOException {
        Map<Integer, List<TextPosition>> byMcid = cache.get(page);
        if (byMcid == null) {
            byMcid = new HashMap<>();
            PDFMarkedContentExtractor ex = new PDFMarkedContentExtractor();
            ex.processPage(page);
            for (PDMarkedContent mc : ex.getMarkedContents()) flattenMarkedContent(mc, byMcid);
            cache.put(page, byMcid);
        }
        return byMcid.getOrDefault(mcid, List.of());
    }

    private static void flattenMarkedContent(PDMarkedContent mc, Map<Integer, List<TextPosition>> byMcid) {
        int mcid = mc.getMCID();
        for (Object content : mc.getContents()) {
            if (content instanceof TextPosition tp && mcid >= 0) {
                byMcid.computeIfAbsent(mcid, k -> new ArrayList<>()).add(tp);
            } else if (content instanceof PDMarkedContent nested) {
                flattenMarkedContent(nested, byMcid);
            }
        }
    }
}
