package com.oai.titanarum;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDAttributeObject;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.Revisions;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.PDTableAttributeObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.PDFMarkedContentExtractor;
import org.apache.pdfbox.text.PDFTextStripperByArea;
import org.apache.pdfbox.text.TextPosition;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
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
    // Tagged-path RowSpan/ColSpan are attacker-controlled ints straight from the structure tree
    // (no PDF-spec upper bound). Clamped in readSpans() to this ceiling BEFORE any grid-placement
    // work happens; buildTaggedTable additionally bounds the cumulative (rowSpan*colSpan) area
    // against MAX_CELLS_PER_TABLE, checked before the occupancy map is populated for that cell.
    static final int MAX_SPAN = 1_000;
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
    // groupIntoTables' touches()/union() work runs over the WHOLE page's CellRect list BEFORE
    // any per-table cap (MAX_CELLS_PER_TABLE, MAX_TABLES_PER_PAGE) gates it, so it is reachable
    // at full page-cell-count scale (up to MAX_INTERSECTIONS-derived cell counts). Since v2,
    // grouping is spatially indexed (near-linear on realistic input, see groupIntoTables' own
    // doc comment) rather than a raw O(n^2) scan, so this budget is a pure backstop against a
    // pathological, bucket-defeating cell distribution (e.g. many cells sharing one exact edge
    // coordinate) -- NOT the primary defense, and set high enough that a legitimate table at
    // MAX_CELLS_PER_TABLE (10,000 cells) never comes close to tripping it (a real 100x100 dense
    // grid at that size completes in low hundreds of thousands of pair checks, comfortably under
    // 1% of this budget). Counts each touches() pair evaluation across the whole call.
    static final long MAX_GROUPING_WORK = 60_000_000;
    // Bounds the --skip-text-urls region-fill fallback (fillCellsByRegion) on the CELL side: a
    // hostile page whose kept-cell count (across every table combined) exceeds this cap must not
    // register any PDFTextStripperByArea regions / walk the content stream at all; flag truncated
    // and bail out promptly instead. See MAX_REGION_GLYPHS below for the companion GLYPH-side cap.
    //
    // round-3 history: a prior round (FIX 2, ledger r9-region) replaced this method's original
    // PDFTextStripperByArea-based region strip with an anonymous PDFTextStripper subclass
    // (stripAllPositions) that BUFFERED every page glyph into a List before bucketing, to close
    // an O(glyphs x cells) CPU concern on the glyph side (extractRegions() had no work budget at
    // all). That rewrite introduced its own regression: PDFTextStripper's base-class per-glyph
    // bookkeeping is unbounded, so a hostile page with millions of glyphs OOM'd building that
    // list BEFORE any cell-side or work-side budget could bound anything (REPRODUCED: a
    // 12,483-byte one-page PDF with 6,000,000 glyphs threw OutOfMemoryError at -Xmx2g via
    // fillCellsByRegion, vs. ~3MB/562ms for the streaming PDFTextStripperByArea approach on the
    // same file). This round reverts to the original streaming PDFTextStripperByArea approach
    // (see RegionStripper) -- O(1)-ish memory, since a glyph outside every registered region
    // never reaches the base class's per-glyph bookkeeping at all -- while ALSO closing the
    // original CPU concern that motivated FIX 2, via MAX_REGION_GLYPHS (a hard glyph-count cap
    // RegionStripper enforces BEFORE delegating to the base class), so neither regression can
    // recur. This also restores the original region-overlap cell-assignment semantics (a glyph
    // is in a cell when PDFTextStripperByArea's own /Rotate-aware rect.contains(getX(),getY())
    // says so), rather than the FIX 2 rewrite's midpoint-containment reimplementation.
    static final int MAX_REGION_CELLS = 5_000;
    // Bounds the --skip-text-urls region-fill fallback (fillCellsByRegion) on the GLYPH side:
    // RegionStripper (see below) counts every TextPosition the content-stream engine reports --
    // regardless of whether it falls inside any registered cell region -- and throws once this
    // cap is exceeded, BEFORE delegating to PDFTextStripperByArea's own (unbounded-per-glyph)
    // bookkeeping. This is what closes the original FIX 2 CPU concern (O(glyphs x cells), no
    // bound on glyphs) without reintroducing FIX 2's own buffer-the-whole-page memory regression:
    // memory stays bounded because (a) unmatched glyphs are never retained past the containment
    // check, and (b) even a pathological all-cells-overlap-every-glyph shape can retain at most
    // MAX_REGION_GLYPHS matched positions total. 2,000,000 is comfortably above any legitimate
    // page's real glyph count (a dense page of small type rarely reaches even tens of thousands)
    // while still bounding per-page work to a finite, deterministic amount.
    static final int MAX_REGION_GLYPHS = 2_000_000;
    // Bounds the --skip-text-urls region-fill fallback (fillCellsByRegion) on the PRODUCT of the
    // glyph and cell sides -- the load-bearing cap, mirroring MAX_TEXTFILL_WORK/MAX_FINDCELLS_WORK.
    //
    // round-3 follow-up: MAX_REGION_GLYPHS alone bounds only the glyph COUNT, and MAX_REGION_CELLS
    // alone bounds only the cell COUNT, but PDFTextStripperByArea's own per-glyph region-match
    // scan is O(glyphs x cells) -- maxing BOTH caps simultaneously (2,000,000 glyphs x 5,000
    // cells) is ~10^10 cheap-but-nonzero region-containment checks in ONE uninterruptible
    // extractRegions() call, which can run tens of seconds -- long enough to blow past the
    // hard-halt watchdog's window and get the JVM killed by Runtime.halt, exactly the failure
    // mode this whole cap hierarchy exists to prevent. Neither existing cap bounds the PRODUCT.
    // RegionStripper (see below) charges {@code getRegions().size()} (the per-glyph cost: every
    // glyph is tested against every currently-registered region) against this budget on EVERY
    // glyph, and throws once it is exceeded -- BEFORE delegating to the base class's per-glyph
    // work, so the actual cost incurred before bailing out is always <= MAX_REGION_WORK, not
    // glyphs x cells.
    //
    // round-4 follow-up (SUPERSEDED, see round-5 below): 200,000,000 was calibrated assuming every
    // charged work-unit costs about the same regardless of whether the glyph actually falls inside
    // a region ("~0.2-2s at typical containment-check speeds"). That holds for a glyph that matches
    // NO region -- PDFTextStripperByArea.processTextPosition's own {@code rect.contains(...)} check
    // is cheap and uniform -- but is FALSE for a glyph that DOES match: PDFTextStripperByArea calls
    // {@code super.processTextPosition(text)} (the base PDFTextStripper's real per-glyph
    // bookkeeping) once per MATCHING region, and a shape where many registered cell regions
    // geometrically OVERLAP (so one glyph matches many of them at once) multiplies that per-glyph
    // cost well beyond what the flat "glyphs x cells" work formula assumes. REPRODUCED: ~100 kept
    // cells whose registered regions geometrically OVERLAP one another with ~2,000,000 matching
    // glyphs took 20-33s at the OLD 200,000,000 budget, comfortably past the 15s hard-halt window.
    // round 4's fix disabled {@code setSuppressDuplicateOverlappingText} on this instance to make
    // that per-match bookkeeping cheaper, and lowered this budget to 20,000,000 to compensate --
    // but that traded CORRECTNESS for CPU: cell text that is drawn TWICE at the identical (x,y)
    // (fake-bold-via-redraw, redundant text layers -- a common NON-hostile PDF-generator pattern,
    // not just a hostile one) came back GARBLED ("TToottaall" instead of "Total") once suppression
    // was off, because {@code setSortByPosition(true)} (needed for cell-text ordering / rotation)
    // sorts the two identically-positioned runs into an interleaved character stream with nothing
    // left to collapse them back together. See {@code
    // TableLatticeTest#duplicateDrawnCellTextExtractsAsSingleCopyNotGarbled} for the reproducer.
    //
    // round-5 (THIS fix): correctness wins. {@code setSuppressDuplicateOverlappingText(false)} is
    // REMOVED -- fillCellsByRegion now runs with PDFTextStripperByArea's default (suppression ON),
    // producing correct text for the duplicate-draw case above. The matched-glyph DoS this budget
    // exists for is instead bounded by the work budget ALONE, recalibrated for the (now more
    // expensive per match, thanks to the restored TreeMap/TreeSet dedup bookkeeping)
    // suppression-ON cost.
    //
    // Measured directly against the fixed code (suppression back ON, setSortByPosition still ON),
    // driving the PRODUCTION fillCellsByRegion (via the package-private (glyphBudget, workBudget)
    // overload, glyphBudget pinned at the production MAX_REGION_GLYPHS) with the same
    // fully-overlapping-cell-regions DoS shape as the round-4 repro (2,000,000 matching glyphs,
    // real PDFBox-measured TextPositions from a real PDF, not synthetic ones), swept across cell
    // counts {2, 10, 100, 1000, 5000 (= MAX_REGION_CELLS)} x candidate budgets, taking the WORST of
    // several repeated runs per point to damp JIT/GC noise. The worst case at every budget tested
    // fell at LOW cell counts (cells=10, not cells=2 or cells=5000) -- for cells=2 the real 2,000,000
    // glyph feed itself becomes the binding constraint once budget/cellCount exceeds it (so higher
    // budgets stop making cells=2 any slower), while cells=10 is exactly where "glyphs x cells" first
    // reaches multi-million-work territory before the (fixed) glyph feed runs out, maximizing the
    // number of expensive matched-and-forwarded glyphs actually processed before the throw:
    //   budget=20,000,000 (old, unchanged from round 4): worst ~4.3s (cells=10)  -- ABOVE the ~3s
    //     target with suppression back on, confirming suppression-ON is materially more expensive
    //     per match than round 4's suppression-OFF calibration assumed.
    //   budget=15,000,000: worst ~3.0-3.2s (cells=10) -- borderline/inconsistent across repeats.
    //   budget=14,000,000: worst ~2.9-3.1s (cells=10) -- still borderline under repeated sampling.
    //   budget=13,000,000: worst ~2.5-2.7s (cells=10), consistent across 8+ repeats -- comfortably
    //     under the ~3s target with margin for measurement noise.
    //   budget=12,000,000: worst ~2.4-2.5s (cells=10).
    // 13,000,000 is chosen: the largest of the tested candidates whose measured worst case stays
    // reliably under ~3s (not just on a lucky run), comfortably under the 15s hard-halt window even
    // with normal environment variance. A legitimate page's real (glyphs x cells) product remains a
    // tiny fraction of even this lowered budget (few cells, few thousand glyphs), so ordinary tables
    // are unaffected -- see regionWorkBudgetNotTrippedByLegitSmallTable.
    //
    // Trade-off (ACCEPTED): a lower budget truncates a dense LEGITIMATE --skip-text-urls page sooner
    // (Result.truncated = true, that page's tables simply missing their region-filled text) than the
    // old 20,000,000 would have. This is the deliberate, documented cost of closing the matched-glyph
    // DoS without re-introducing the duplicate-draw text-correctness regression: this fallback only
    // runs in opt-in --skip-text-urls mode, and a capacity cap that degrades visibly (truncated) is a
    // strictly better failure mode than one that silently garbles cell text or risks the hard-halt
    // watchdog killing the whole job.
    static final long MAX_REGION_WORK = 13_000_000;
    // FIX 4: caps splitComponent/tryCuts' OWN recursion depth (distinct from MAX_SPLIT_WORK,
    // which bounds total work but not stack depth), mirroring the depth>64 convention used by
    // every other recursive walk in this class (collectByType/collectGlyphs/resolveElementPage).
    // A "peelable" adversarial component -- one legitimate cut found at every level, recursing
    // into an ever-shrinking remainder -- can otherwise recurse hundreds of levels deep with NO
    // other guard against it; on a constrained stack (e.g. a 128KB microVM worker thread) this
    // is a StackOverflowError waiting to happen, an Error that would otherwise escape the lattice
    // per-page catch(Exception) entirely. Past the cap, splitComponent degrades to "return the
    // component un-split" (same fallback already used when no valid cut is found) rather than
    // recursing further -- a plausible-if-imperfect placement, never a crash.
    static final int MAX_SPLIT_DEPTH = 64;

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

    /**
     * Fill {@code t.rows} and {@code t.markdown} from cells + rowCount/colCount.
     *
     * <p>FIX B (round 3) defense-in-depth: every caller already rejects a table whose grid
     * product exceeds {@link #MAX_CELLS_PER_TABLE} before reaching here (the tagged path via
     * {@link #buildTaggedTable}'s own grid-product guard; the lattice path because {@link
     * #placeGridBudgeted} already charged that same rowCount*colCount cost against {@link
     * #MAX_SPLIT_WORK} while resolving the final placement). This is a cheap backstop, not the
     * primary defense, against a future caller forgetting that gate -- refuse the giant {@code
     * String[rowCount][colCount]} + full-grid markdown allocation outright rather than silently
     * materializing hundreds of MB for one table.
     */
    static void renderViews(TableHit t) {
        if ((long) t.rowCount * t.colCount > MAX_CELLS_PER_TABLE) throw new RulingOverflowException();
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
    // Bounds ALL of splitComponent's work, counted cumulatively across a single shared counter
    // for one top-level splitComponent invocation (including every recursive sub-split attempted):
    // both (a) placeGrid/isCoherentPlacement's O(rowCount*colCount) coherence-check cost, and
    // (b) tryCuts' straddle-scan, an O(comp.size()) pass run once per candidate cut BEFORE any
    // placeGrid call -- charged per cell examined, so an adversarial offset-pitch shape (many
    // candidate cuts, each straddled by a cell near the end of the component's iteration order,
    // forcing a near-full scan every time) can't do that work uncounted. Without charging (b),
    // this reintroduces an O(candidates x cells) DoS: measured at 561ms/10k cells, 1954ms/20k
    // cells (quadratic) before this was closed, without ever tripping the budget.
    //
    // A genuine touching-but-independent-tables shape (the FIX 5 repro) resolves in a handful of
    // candidate cuts on a component already capped at MAX_CELLS_PER_TABLE, so real input finishes
    // far under this budget -- and neither rowCount*colCount nor the straddle-scan cost is itself
    // bounded by comp.size() alone (a component with few CellRects but many distinct, unaligned
    // boundary coordinates can still cluster into a huge grid; an offset-pitch shape can force a
    // near-full straddle-scan on nearly every candidate), so an adversarial or pathologically
    // irregular shape, re-evaluated across recursive cut attempts, could otherwise blow up
    // combinatorially. Sized generously above a legitimate dense MAX_CELLS_PER_TABLE-sized table's
    // cost (~10,000) for headroom on real irregular tables, while still failing fast (well under a
    // second) on the adversarial case.
    static final long MAX_SPLIT_WORK = 20_000_000;

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

    /**
     * Union cells whose rectangles touch (within SNAP) into candidate tables.
     *
     * <p>Spatial index mirroring findCells' hByY/vByX pattern: two cells can only touch() if one
     * of their edges shares a (within-SNAP) coordinate with one of the other's -- touches()
     * itself requires an x- or y-edge match plus positive overlap on the other axis. So bucket
     * every cell under each of its four snapped edge coordinates (x0, x1 into byXEdge; y0, y1
     * into byYEdge), then for each cell only evaluate touches() against cells sharing (or
     * SNAP-adjacent to) one of ITS OWN edge buckets, instead of the full O(n^2) all-pairs scan.
     * A legitimate large, regular table (up to MAX_CELLS_PER_TABLE) has small real-world bucket
     * occupancy (roughly the row/column count sharing a boundary line), so this completes far
     * under MAX_GROUPING_WORK; only a pathological, bucket-defeating layout (e.g. many cells all
     * sharing one exact edge coordinate) can still drive the work counter into the cap -- which
     * remains as a pure backstop, not the primary defense.
     *
     * <p>Note: touches()' third branch (pure area overlap with no edge adjacency at all) is,
     * per its own comment, not expected from real findCells() output (minimal cells tile the
     * plane, they don't overlap) -- the edge-coordinate index above does not generate candidates
     * for that case. Preserved for documentation: this is an intentional trade-off, not an
     * oversight, favoring near-linear performance on realistic input.
     */
    static List<List<CellRect>> groupIntoTables(List<CellRect> cells) {
        int n = cells.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        java.util.Map<Float, List<Integer>> byXEdge = new java.util.HashMap<>();
        java.util.Map<Float, List<Integer>> byYEdge = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            CellRect c = cells.get(i);
            byXEdge.computeIfAbsent(snap(c.x0), k -> new ArrayList<>()).add(i);
            byXEdge.computeIfAbsent(snap(c.x1), k -> new ArrayList<>()).add(i);
            byYEdge.computeIfAbsent(snap(c.y0), k -> new ArrayList<>()).add(i);
            byYEdge.computeIfAbsent(snap(c.y1), k -> new ArrayList<>()).add(i);
        }

        long work = 0;
        java.util.Set<Integer> candidates = new java.util.HashSet<>();
        for (int i = 0; i < n; i++) {
            CellRect a = cells.get(i);
            candidates.clear();
            addBucket(byXEdge, snap(a.x0), candidates);
            addBucket(byXEdge, snap(a.x1), candidates);
            addBucket(byYEdge, snap(a.y0), candidates);
            addBucket(byYEdge, snap(a.y1), candidates);
            for (int j : candidates) {
                if (j <= i) continue; // undirected pair -- evaluate once, from the smaller index
                if (++work > MAX_GROUPING_WORK) throw new RulingOverflowException();
                if (touches(a, cells.get(j))) union(parent, i, j);
            }
        }
        java.util.Map<Integer, List<CellRect>> comps = new java.util.LinkedHashMap<>();
        for (int i = 0; i < n; i++) comps.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(cells.get(i));
        return new ArrayList<>(comps.values());
    }

    private static void addBucket(java.util.Map<Float, List<Integer>> byEdge, float key, java.util.Set<Integer> out) {
        for (float k : new float[]{key - SNAP, key, key + SNAP}) {
            List<Integer> bucket = byEdge.get(k);
            if (bucket != null) out.addAll(bucket);
        }
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
     * Clustered-grid placement shared by {@link #buildTable} and the split/coherence-detection
     * logic below: the sorted x/y grid-line boundaries, plus, PARALLEL to the input {@code comp}
     * (same index i &lt;-&gt; comp.get(i)), each cell's placed row/col/rowSpan/colSpan.
     */
    private static final class GridPlacement {
        final List<Float> xs, ys;
        final int rowCount, colCount;
        final int[] row, col, rowSpan, colSpan;

        GridPlacement(List<Float> xs, List<Float> ys, int rowCount, int colCount,
                      int[] row, int[] col, int[] rowSpan, int[] colSpan) {
            this.xs = xs; this.ys = ys; this.rowCount = rowCount; this.colCount = colCount;
            this.row = row; this.col = col; this.rowSpan = rowSpan; this.colSpan = colSpan;
        }
    }

    private static GridPlacement placeGrid(List<CellRect> comp) {
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
        int n = comp.size();
        int[] row = new int[n], col = new int[n], rowSpan = new int[n], colSpan = new int[n];
        for (int i = 0; i < n; i++) {
            CellRect c = comp.get(i);
            col[i] = nearestIndex(xs, c.x0);
            row[i] = nearestIndex(ys, c.y0);
            colSpan[i] = Math.max(1, nearestIndex(xs, c.x1) - col[i]);
            rowSpan[i] = Math.max(1, nearestIndex(ys, c.y1) - row[i]);
        }
        return new GridPlacement(xs, ys, rowCount, colCount, row, col, rowSpan, colSpan);
    }

    /**
     * A genuine single table -- even one with row/col-spanning cells -- covers every (row, col)
     * slot of its clustered rowCount x colCount grid EXACTLY once. Used by {@link #buildTable} as
     * a last-resort guard: when {@link #splitComponent} could not cleanly resolve a component (no
     * valid cut found), a still-incoherent grid here means the clustered spans are untrustworthy
     * and must be suppressed rather than emitted as invented.
     */
    private static boolean isCoherentPlacement(GridPlacement g) {
        boolean[][] occ = new boolean[g.rowCount][g.colCount];
        int n = g.row.length;
        for (int i = 0; i < n; i++) {
            int r1 = Math.min(g.rowCount, g.row[i] + g.rowSpan[i]);
            int c1 = Math.min(g.colCount, g.col[i] + g.colSpan[i]);
            for (int r = g.row[i]; r < r1; r++) {
                for (int c = g.col[i]; c < c1; c++) {
                    if (occ[r][c]) return false; // double-covered -> incoherent merge
                    occ[r][c] = true;
                }
            }
        }
        for (boolean[] rowOcc : occ) {
            for (boolean b : rowOcc) if (!b) return false; // uncovered slot -> incoherent merge
        }
        return true;
    }

    /** True when ANY cell in the placement has a rowSpan or colSpan greater than 1. A grid with
     * no spans at all cannot be a merge artifact (see {@link #splitComponent}'s doc) -- inventing
     * a span is exactly the mechanism by which two independent tables' foreign boundaries corrupt
     * each other's cell placement, so "no spans anywhere" is a cheap, safe fast-path out of the
     * (more expensive) split search below. */
    private static boolean hasAnySpan(GridPlacement g) {
        for (int i = 0; i < g.rowSpan.length; i++) {
            if (g.rowSpan[i] > 1 || g.colSpan[i] > 1) return true;
        }
        return false;
    }

    /** {@code placeGrid}, charging the shared split-work budget (see {@link #MAX_SPLIT_WORK}) for
     * the O(rowCount*colCount) cost the caller is about to incur (coherence/coverage-style scans,
     * or simply the cost of having clustered this many boundaries). */
    private static GridPlacement placeGridBudgeted(List<CellRect> comp, long[] work) {
        GridPlacement g = placeGrid(comp);
        work[0] += (long) g.rowCount * g.colCount;
        if (work[0] > MAX_SPLIT_WORK) throw new RulingOverflowException();
        return g;
    }

    /**
     * Detect whether a grouped component (from {@link #groupIntoTables}) is actually the union of
     * two or more geometrically DISJOINT tables that merely touch along a shared border, and if
     * so, split it into its maximal independent sub-grids -- e.g. two adjacent, axis-aligned
     * tables of different row/column pitch that share one drawn border line. Returns a singleton
     * list containing {@code comp} unchanged when there is nothing to split (no cell has any span
     * at all, or the component is too small to be a table -- {@link #buildTable} rejects that
     * case as before).
     *
     * <p>Naively checking "does the merged grid cover every slot exactly once" is NOT sufficient
     * to detect a merge artifact: two independent tables whose total extents happen to tile
     * evenly (e.g. A: 2 rows x 30pt = 60pt, B: 3 rows x 20pt = 60pt, stacked over the identical
     * y-range) can still cover the merged grid perfectly, just via mutually-exclusive INVENTED
     * spans on each side -- coverage alone can't tell that apart from a genuine single spanned
     * table. Instead, a candidate axis-aligned cut (along one of the component's own clustered x
     * or y boundaries, chosen so every CellRect falls ENTIRELY on one side -- none straddle it) is
     * only accepted as a genuine split when BOTH of these hold:
     * <ul>
     *   <li>each side independently clusters into its own real (&gt;=2x2) candidate table -- a
     *       cut through the middle of one genuinely uniform table (e.g. bisecting a plain 5x6
     *       grid at some arbitrary interior column) produces two sides that are still &gt;=2x2,
     *       but see the second condition below;</li>
     *   <li>the MERGED component's own boundary count on the axis PERPENDICULAR to the cut (e.g.
     *       rowCount for a vertical/column cut) is strictly greater than EITHER side's own,
     *       independently-clustered count on that same axis. This is the actual signature of two
     *       incompatible partitions being conflated: A merged with B's foreign row boundaries
     *       always needs MORE distinct rows than A or B needs alone. A true single uniform table
     *       cut at an arbitrary internal line shares the identical row structure on both sides
     *       (merged rowCount == each side's rowCount), so this condition correctly rejects it.
     * </ul>
     *
     * <p>Recurses on each accepted side (bounded by comp's own size, since each recursive call
     * strictly partitions -- never grows -- the CellRect list) to support more than two mutually
     * touching tables. Falls back to returning the component UNSPLIT when no cut satisfies both
     * conditions on either axis (e.g. a genuinely ambiguous/pathological shape); {@link
     * #buildTable} independently re-checks {@link #isCoherentPlacement} and clamps spans to 1 in
     * that fallback case, so an unsplittable component still never emits an INVENTED span, even
     * if its row/col placement ends up implausible.
     *
     * <p>Bounded by {@link #MAX_SPLIT_WORK}: see that constant's doc for why rowCount*colCount
     * (charged on every placeGrid call, including recursive sub-splits) needs an explicit budget
     * here, separate from the existing per-table cell-count cap.
     */
    static List<List<CellRect>> splitComponent(List<CellRect> comp) {
        return splitComponent(comp, new long[]{0}, 0);
    }

    /** {@code depth} is FIX 4's recursion-depth guard (see {@link #MAX_SPLIT_DEPTH}), separate
     * from and in addition to the {@code work}-budget cap: a "peelable" adversarial shape (a
     * valid cut found at every level, recursing into an ever-shrinking remainder) can recurse
     * hundreds of levels deep while staying comfortably under MAX_SPLIT_WORK the whole time, so
     * the work budget alone does not bound stack depth. */
    private static List<List<CellRect>> splitComponent(List<CellRect> comp, long[] work, int depth) {
        if (comp.size() < 2) return List.of(comp);
        if (depth > MAX_SPLIT_DEPTH) return List.of(comp); // degrade to "one table", not unbounded recursion
        GridPlacement g = placeGridBudgeted(comp, work);
        if (g.rowCount < 2 || g.colCount < 2 || !hasAnySpan(g)) return List.of(comp);

        // NOTE: both axes are ALWAYS searched here -- do not add a "skip this axis if no cell has
        // a span on it" shortcut. That heuristic is UNSOUND: two side-by-side tables A/B can share
        // a vertical border while each extends beyond the other's y-range (so NO cell anywhere
        // picks up rowSpan inflation, hasAnySpan is satisfied purely by B's own unrelated colSpan
        // header), yet the vertical A|B cut is still the ONLY correct split. Pruning the vertical
        // search there (a prior, reverted version of this method did exactly that) silently fell
        // through to "unsplit", garbling both tables' placement AND clamping B's genuine header
        // colSpan to 1 via buildTable's incoherence safety net -- see
        // TableGeometryTest.splitComponentSplitsWhenOneTableHasNoRowSpanButOtherHasColSpanHeader.
        List<List<CellRect>> viaVertical = tryCuts(comp, g.xs, true, g.rowCount, work, depth);
        if (viaVertical != null) return viaVertical;
        List<List<CellRect>> viaHorizontal = tryCuts(comp, g.ys, false, g.colCount, work, depth);
        if (viaHorizontal != null) return viaHorizontal;
        return List.of(comp); // no clean cut -- unsplit fallback; buildTable will clamp spans
    }

    /** Try every interior boundary of one axis as a candidate cut; returns the flattened,
     * recursively-split result for the first candidate that satisfies both acceptance conditions
     * documented on {@link #splitComponent}, else null. {@code mergedPerpCount} is the ORIGINAL
     * (undivided) component's boundary count on the axis perpendicular to the cut (rowCount for a
     * vertical cut, colCount for a horizontal one). */
    private static List<List<CellRect>> tryCuts(List<CellRect> comp, List<Float> boundaries,
                                                 boolean vertical, int mergedPerpCount, long[] work, int depth) {
        for (int k = 1; k < boundaries.size() - 1; k++) {
            float cut = boundaries.get(k);
            List<CellRect> side1 = new ArrayList<>();
            List<CellRect> side2 = new ArrayList<>();
            boolean straddle = false;
            for (CellRect c : comp) {
                // Charged against the SAME shared budget as placeGridBudgeted (see
                // MAX_SPLIT_WORK's doc): this straddle-scan is itself O(comp.size()) PER
                // candidate cut, run for up to O(boundaries.size()) candidates, entirely BEFORE
                // any placeGridBudgeted call below -- an adversarial, offset-pitch shape (many
                // candidates, each straddled by a cell near the end of `comp`'s iteration order)
                // previously did this work uncounted, reintroducing an O(n^2) DoS the budget was
                // meant to close.
                if (++work[0] > MAX_SPLIT_WORK) throw new RulingOverflowException();
                float lo = vertical ? snap(c.x0) : snap(c.y0);
                float hi = vertical ? snap(c.x1) : snap(c.y1);
                boolean onSide1 = hi <= cut;
                boolean onSide2 = lo >= cut;
                if (onSide1 && !onSide2) side1.add(c);
                else if (onSide2 && !onSide1) side2.add(c);
                else { straddle = true; break; }
            }
            if (straddle || side1.isEmpty() || side2.isEmpty()) continue;

            GridPlacement g1 = placeGridBudgeted(side1, work);
            GridPlacement g2 = placeGridBudgeted(side2, work);
            if (g1.rowCount < 2 || g1.colCount < 2 || g2.rowCount < 2 || g2.colCount < 2) continue;
            int perp1 = vertical ? g1.rowCount : g1.colCount;
            int perp2 = vertical ? g2.rowCount : g2.colCount;
            if (!(mergedPerpCount > perp1 && mergedPerpCount > perp2)) continue;

            List<List<CellRect>> result = new ArrayList<>();
            result.addAll(splitComponent(side1, work, depth + 1));
            result.addAll(splitComponent(side2, work, depth + 1));
            return result;
        }
        return null;
    }

    /**
     * Assign row/col indices + spans from clustered edge boundaries and build the TableHit
     * (no text, views not rendered). Returns null for components below 2x2 / 4 cells.
     *
     * <p>Note: the 2x2 threshold is enforced below via the clustered row/col boundary counts
     * (rowCount, colCount), not via {@code comp.size()} — a component can legitimately contain
     * fewer than 4 CellRects (e.g. 3) when a spanning cell covers more than one logical grid
     * cell, while still representing a full 2x2 (4 logical cell) table.
     *
     * <p>FIX 5 safety net: independently re-checks {@link #isCoherentPlacement} regardless of
     * whether the caller already ran {@link #splitComponent} on this component. When the grid is
     * still incoherent here (i.e. no clean split was found upstream), every cell's row/colSpan is
     * clamped to 1 rather than using the clustered-but-untrustworthy span -- a merged, unsplit
     * component may look implausible, but must never emit an INVENTED span.
     */
    static TableHit buildTable(int page, List<CellRect> comp, String method) {
        if (comp.isEmpty()) return null;
        GridPlacement g = placeGrid(comp);
        if (g.rowCount < 2 || g.colCount < 2) return null;
        boolean coherent = isCoherentPlacement(g);

        TableHit t = new TableHit();
        t.page = page;
        t.extractionMethod = method;
        t.rowCount = g.rowCount;
        t.colCount = g.colCount;
        t.cells = new ArrayList<>(comp.size());
        float bx0 = Float.MAX_VALUE, by0 = Float.MAX_VALUE, bx1 = -Float.MAX_VALUE, by1 = -Float.MAX_VALUE;
        for (int i = 0; i < comp.size(); i++) {
            CellRect c = comp.get(i);
            CellHit cell = new CellHit();
            cell.col = g.col[i];
            cell.row = g.row[i];
            cell.colSpan = coherent ? g.colSpan[i] : 1;
            cell.rowSpan = coherent ? g.rowSpan[i] : 1;
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

    /**
     * Index of the boundary closest to {@code v}. {@code boundaries} is always sorted ascending
     * (built from a TreeSet by every caller), so this binary-searches rather than scanning
     * linearly -- O(log n) instead of O(n) per call. This matters well beyond a single-shot
     * per-table {@link #buildTable} call: {@link #splitComponent}'s candidate-cut search invokes
     * {@link #placeGrid} (which calls this 4x per cell) on independently re-clustered sub-lists
     * for EVERY candidate cut it tries, so an O(n) nearestIndex compounds into the dominant cost
     * of the whole search on a large component -- this was the difference between a legit
     * MAX_CELLS_PER_TABLE-scale spanned table completing in ~500ms vs. well under 200ms.
     * On an exact tie (v equidistant from two boundaries -- not expected in practice, since v is
     * always one of the exact values originally inserted into the boundary set, but preserved for
     * safety) this returns the LOWER index, matching the original linear scan's strict-less-than
     * tie-break.
     */
    private static int nearestIndex(List<Float> boundaries, float v) {
        int lo = 0, hi = boundaries.size() - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (boundaries.get(mid) < v) lo = mid + 1; else hi = mid;
        }
        if (lo > 0) {
            float dPrev = Math.abs(boundaries.get(lo - 1) - v);
            float dLo = Math.abs(boundaries.get(lo) - v);
            if (dPrev <= dLo) return lo - 1;
        }
        return lo;
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

    // Fraction of the taller of two glyphs' getHeightDir() used as the same-line tolerance in
    // joinText's line clustering, replacing a fixed 2pt cutoff. A superscript/subscript glyph
    // (footnote marker, exponent, trademark symbol) commonly has its baseline offset a few pt
    // off the main text's baseline -- well under one glyph-height -- while a genuine next line
    // sits a full line-height (~1x-1.2x font size, i.e. more than one glyph-height) further down.
    // 0.9 keeps a several-pt superscript offset on the same line while still breaking cleanly on
    // real next lines (verified against actual PDFBox-measured glyph geometry, not assumed).
    private static final float LINE_GROUP_HEIGHT_FRACTION = 0.9f;

    /**
     * Join per-char TextPositions into cell text: cluster into lines by baseline (YDirAdj)
     * proximity scaled to glyph height (see {@link #LINE_GROUP_HEIGHT_FRACTION}) rather than a
     * fixed pt tolerance, so a superscript/subscript glyph stays on its base text's line instead
     * of sorting as a spurious separate (and mis-ordered) line; order each line's glyphs
     * left-to-right by XDirAdj (so a superscript that sorts earliest by Y still lands after its
     * base character), insert a space on word-sized gaps, join lines with '\n'.
     */
    static String joinText(List<TextPosition> chars) {
        if (chars.isEmpty()) return "";
        List<TextPosition> sorted = new ArrayList<>(chars);
        sorted.sort(java.util.Comparator.comparingDouble(TextPosition::getYDirAdj)
                .thenComparingDouble(TextPosition::getXDirAdj));

        // Chain-cluster into lines. `lineTopY` is the Y of the first (topmost, since `sorted` is
        // Y-ascending) member of the current group -- it never regresses, so later members are
        // always compared against the group's topmost baseline, not merely the previous glyph.
        // `lineMaxHeight` tracks the tallest glyph seen so far in the group so the tolerance grows
        // to cover the "real" text's height even when the group started with a small glyph (e.g.
        // a raised superscript sorts before its larger base-text line).
        List<List<TextPosition>> lines = new ArrayList<>();
        List<TextPosition> currentLine = new ArrayList<>();
        float lineTopY = 0f;
        float lineMaxHeight = 0f;
        for (TextPosition tp : sorted) {
            if (currentLine.isEmpty()) {
                currentLine.add(tp);
                lineTopY = tp.getYDirAdj();
                lineMaxHeight = tp.getHeightDir();
                continue;
            }
            float tol = Math.max(2f, LINE_GROUP_HEIGHT_FRACTION * Math.max(lineMaxHeight, tp.getHeightDir()));
            if (tp.getYDirAdj() - lineTopY > tol) {
                lines.add(currentLine);
                currentLine = new ArrayList<>();
                currentLine.add(tp);
                lineTopY = tp.getYDirAdj();
                lineMaxHeight = tp.getHeightDir();
            } else {
                currentLine.add(tp);
                lineMaxHeight = Math.max(lineMaxHeight, tp.getHeightDir());
            }
        }
        if (!currentLine.isEmpty()) lines.add(currentLine);

        StringBuilder out = new StringBuilder();
        for (int li = 0; li < lines.size(); li++) {
            List<TextPosition> line = lines.get(li);
            line.sort(java.util.Comparator.comparingDouble(TextPosition::getXDirAdj));
            TextPosition prev = null;
            for (TextPosition tp : line) {
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
            if (li < lines.size() - 1) out.append('\n');
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
     * FIX A (round 3): streaming, glyph-budgeted subclass of {@code PDFTextStripperByArea} used
     * by {@link #fillCellsByRegion}. {@code PDFTextStripperByArea}'s own {@code
     * processTextPosition(TextPosition)} (called once per glyph by the underlying content-stream
     * engine, the SAME entry point the base {@code PDFTextStripper} uses) tests the glyph against
     * every registered region and, ONLY for a region it actually falls in, forwards it to {@code
     * super.processTextPosition(...)} (the base class's own per-glyph text bookkeeping). A glyph
     * that matches no region is therefore never retained anywhere -- this is what makes the
     * region-strip approach streaming/O(1)-ish memory, unlike the {@code stripAllPositions}
     * approach it replaces (see {@link #MAX_REGION_GLYPHS}'s doc for that regression).
     *
     * <p>But the per-glyph region-containment scan itself still runs once per glyph regardless of
     * match, so a hostile page with a huge glyph count is still unbounded CPU-wise without an
     * explicit cap. This subclass counts EVERY glyph the engine reports (matched or not) and
     * throws {@link RulingOverflowException} once {@code glyphBudget} is exceeded, BEFORE calling
     * {@code super.processTextPosition(...)} -- bounding both the CPU (no more than {@code
     * glyphBudget} glyphs are ever scanned against the region map) and, as a hard backstop, the
     * memory a pathological all-glyphs-match-every-region shape could retain (at most {@code
     * glyphBudget} matched positions total, across every region combined).
     *
     * <p>round-3 follow-up ({@link #MAX_REGION_WORK}): {@code glyphBudget} alone bounds only the
     * glyph COUNT -- it does NOT bound the PRODUCT of glyphs and registered regions, which is the
     * actual per-page cost (every glyph is tested against every region). Maxing both the glyph
     * cap and {@link #MAX_REGION_CELLS} simultaneously is still ~10^10 cheap containment checks in
     * one uninterruptible {@code extractRegions()} call -- bounded (never OOMs, never runs
     * forever) but slow enough to risk the hard-halt watchdog killing the JVM before this method
     * ever returns. So this subclass ALSO charges {@code getRegions().size()} (the true per-glyph
     * cost: this glyph is about to be tested against every one of that many regions) against a
     * separate {@code workBudget} on every glyph, throwing the same way once THAT is exceeded --
     * this is the cap that actually bounds the glyphs x cells product, and is checked BEFORE the
     * (cheaper, coarser) glyph-count check would otherwise let a max-both shape run to completion.
     *
     * <p>round-4 follow-up ({@link #MAX_REGION_WORK}'s doc has the full measurement): charging
     * {@code getRegions().size()} per glyph assumes every charged unit costs about the same amount
     * of wall-clock regardless of whether the glyph actually falls inside a region. That is true for
     * the (cheap, uniform) {@code rect.contains(...)} containment check itself, but NOT true of what
     * happens next for a glyph that DOES match: {@code PDFTextStripperByArea.processTextPosition}
     * calls {@code super.processTextPosition(text)} (real per-glyph bookkeeping in the base {@code
     * PDFTextStripper}) once per matching region, and that bookkeeping is materially more expensive
     * than the containment check alone -- especially with {@code suppressDuplicateOverlappingText}
     * (on by default) doing a TreeMap/TreeSet dedup lookup on every match. A shape where many
     * registered regions geometrically overlap (so a single glyph matches many of them at once)
     * multiplies that per-glyph cost well beyond what the flat "glyphs x cells" work formula assumes,
     * which is what let a ~100-cell / ~2,000,000-matching-glyph page take 20-33s despite the work
     * counter itself topping out at exactly {@code MAX_REGION_WORK}.
     *
     * <p>round-5 follow-up (THIS fix -- see {@link #MAX_REGION_WORK}'s doc for the full
     * re-calibration): round 4's response to the above was to disable
     * duplicate-overlapping-text suppression on this instance to make the per-match cost cheaper.
     * That traded away CORRECTNESS: a cell whose text is drawn twice at the identical position (a
     * common non-hostile fake-bold-via-redraw / redundant-text-layer generator pattern, not just a
     * hostile one) came back character-interleaved-garbled once suppression was off, because {@code
     * setSortByPosition(true)} sorts the two identically-positioned runs together with nothing left
     * to collapse them back into one copy. Suppression is restored to its default (ON) here --
     * fixing that correctness regression -- and {@link #MAX_REGION_WORK} is instead recalibrated
     * (lowered) to keep the matched-glyph DoS bounded under the NOW-more-expensive (suppression-ON)
     * per-match cost, so the work budget alone -- not a cheaper-but-wrong per-glyph path -- is what
     * closes this DoS.
     */
    private static final class RegionStripper extends PDFTextStripperByArea {
        private final long glyphBudget;
        private final long workBudget;
        private long glyphCount = 0;
        private long work = 0;

        RegionStripper(long glyphBudget, long workBudget) throws IOException {
            super();
            this.glyphBudget = glyphBudget;
            this.workBudget = workBudget;
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            if (++glyphCount > glyphBudget) throw new RulingOverflowException();
            work += getRegions().size();
            if (work > workBudget) throw new RulingOverflowException();
            super.processTextPosition(text);
        }
    }

    /**
     * Fallback when no TextPositions were collected (--skip-text-urls): a single {@code
     * PDFTextStripperByArea}-based content-stream pass (via {@link RegionStripper}), registering
     * one region per kept cell (across every qualifying table on the page, namespaced {@code
     * "t"+tableIdx+"c"+cellIdx} so cells from different tables never collide) and reading each
     * cell's matched text back out afterwards.
     *
     * <p>{@code PDFTextStripperByArea} matches a region against {@code TextPosition.getX()/getY()}
     * (verified from source: {@code rect.contains(text.getX(), text.getY())}), which -- unlike
     * getXDirAdj/getYDirAdj -- IS keyed off the page's actual /Rotate. Our cell rects are already
     * in that same visual frame (via {@link #collectRulings}), so no extra transform is needed
     * here, and this restores the ORIGINAL region-overlap cell-assignment semantics (rather than
     * a since-reverted midpoint-containment reimplementation that changed cell-boundary text
     * inclusion behavior).
     *
     * <p>{@code setSortByPosition(true)}: without it, on a page whose /Rotate disagrees with its
     * glyphs' own (unrotated) text direction -- exactly the shape produced by rotating an
     * otherwise-ordinary page via the /Rotate flag alone, which is how {@code rotatedRuled3x3}
     * (and plenty of real scanned/rotated PDFs) are built -- the default line-grouping
     * mis-splits a single token across several one-character lines (e.g. "R3C1" back as
     * "R\n3C\n1"). Verified empirically: identical region, same characters matched, only this
     * flag differs between the broken and clean output.
     *
     * <p>round-4 (REVERTED by round-5, see below): a prior version of this method called {@code
     * area.setSuppressDuplicateOverlappingText(false)} here, reasoning that cell-text extraction
     * has no need for cross-call duplicate-overlapping-text suppression (each region is read back
     * independently via {@link PDFTextStripperByArea#getTextForRegion}, not merged into one running
     * document-wide transcript) -- so disabling it should cost nothing correctness-wise while
     * removing a real per-matched-glyph TreeMap/TreeSet cost the work budget's calibration hadn't
     * accounted for. That reasoning MISSED a real correctness case: a cell whose text is drawn
     * TWICE at the IDENTICAL (x, y) position (fake-bold-via-redraw / redundant text layers -- a
     * common NON-hostile PDF-generator pattern) relies on {@code
     * suppressDuplicateOverlappingText}'s dedup to collapse the two runs back into one copy; with
     * it off, {@code setSortByPosition(true)} above instead sorts the two identically-positioned
     * runs into an interleaved character stream ("TToottaall" instead of "Total") -- REPRODUCED by
     * {@code TableLatticeTest#duplicateDrawnCellTextExtractsAsSingleCopyNotGarbled}.
     *
     * <p>round-5 (THIS fix): correctness wins. Suppression is left at its {@code
     * PDFTextStripperByArea} default (ON) -- the {@code setSuppressDuplicateOverlappingText(false)}
     * call above is REMOVED. The matched-glyph DoS round 4 was closing is instead bounded by {@link
     * #MAX_REGION_WORK} alone, recalibrated (lowered) against the real, now-restored suppression-ON
     * per-match cost -- see that constant's doc for the full measurement sweep and the accepted
     * capacity trade-off (a lower budget truncates a dense legitimate page sooner, but never garbles
     * text and never risks the hard-halt watchdog).
     */
    static void fillCellsByRegion(List<List<CellRect>> tables, PDPage page, Result result) throws IOException {
        fillCellsByRegion(tables, page, result, MAX_REGION_GLYPHS, MAX_REGION_WORK);
    }

    /** Package-private overload taking an explicit glyph-count budget (see {@link
     * RegionStripper}) with {@link #MAX_REGION_WORK} as the (production) product-work budget --
     * mirrors {@link #fillCellsFromPositions(List, List, int, float, float, long[], long)}'s
     * test-only budget override, letting a test pin {@link #MAX_REGION_GLYPHS} deterministically
     * without needing a real multi-million-glyph PDF fixture. */
    static void fillCellsByRegion(List<List<CellRect>> tables, PDPage page, Result result, long glyphBudget)
            throws IOException {
        fillCellsByRegion(tables, page, result, glyphBudget, MAX_REGION_WORK);
    }

    /** Package-private overload taking BOTH explicit budgets (see {@link RegionStripper}) --
     * lets a test pin {@link #MAX_REGION_WORK} (the glyphs x cells PRODUCT bound) deterministically
     * without needing a real fixture large enough to trip it at the production budget. */
    static void fillCellsByRegion(List<List<CellRect>> tables, PDPage page, Result result,
                                  long glyphBudget, long workBudget) throws IOException {
        // Bound the cells side (see MAX_REGION_CELLS): a hostile page whose kept-cell count
        // (across every table combined) exceeds the cap must not register any regions / walk the
        // content stream at all; flag truncated and bail out promptly instead.
        long totalCells = 0;
        for (List<CellRect> comp : tables) totalCells += comp.size();
        if (totalCells > MAX_REGION_CELLS) {
            result.truncated = true;
            return;
        }
        if (tables.isEmpty()) return;

        RegionStripper area = new RegionStripper(glyphBudget, workBudget);
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
     *
     * <p>Checks {@code Thread.currentThread().isInterrupted()} at the TOP of the per-page loop,
     * BEFORE the try block -- every sibling pipeline stage checks interruption in-loop, but this
     * one previously didn't, so a multi-page hostile doc would ignore a soft --timeout entirely
     * (only checked by the caller AFTER extract() returns) and eventually trip the hard-halt
     * watchdog. Checking before the try (rather than inside it) is deliberate: an
     * InterruptedException/the interrupt flag must never be swallowed by the per-page
     * catch(Exception) below. FIX 1 and FIX 2 already bound the work a single page can do, so a
     * between-page check is sufficient -- no in-loop check is needed within one page.
     */
    static Result extract(PDDocument doc, List<Integer> pagesToProcess,
                          Map<Integer, List<TextPosition>> positionsByPage) {
        Result result = new Result();
        Set<Integer> coveredByTagged = new HashSet<>();
        try {
            extractTagged(doc, new HashSet<>(pagesToProcess), coveredByTagged, result);
        } catch (StackOverflowError e) {
            // A pathologically deep (or cyclic-looking) structure/marked-content tree can still
            // overflow inside pdfbox's own traversal even with our depth guards in place; degrade
            // to "skip tagged, lattice still runs" rather than killing the worker thread.
            System.err.println("WARNING: tagged table extraction overflowed the stack (skipped): " + e);
        } catch (Exception e) {
            System.err.println("WARNING: tagged table extraction failed: " + e);
        }
        for (int pageNum : pagesToProcess) {
            if (Thread.currentThread().isInterrupted()) {
                result.truncated = true;
                break;
            }
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
        //
        // FIX 5: groupIntoTables' union-find only requires edge-adjacency (see its own doc
        // comment), so two genuinely separate tables that merely share a drawn border line get
        // merged into one component. splitComponent (run per grouped component, BEFORE the
        // MAX_CELLS_PER_TABLE / MAX_TABLES_PER_PAGE gates below, so each resulting sub-table is
        // gated on its own true size/count) detects that case and emits the maximal coherent
        // sub-grids instead of one bogus merged grid.
        List<List<CellRect>> kept = selectKeptTables(groupIntoTables(cells), pageNum, result);

        if (positions != null && !positions.isEmpty()) {
            long[] work = {0};
            for (List<CellRect> comp : kept) {
                fillCellsFromPositions(comp, positions, rotation, unrotatedW, unrotatedH, work);
            }
        } else if (!kept.isEmpty()) {
            fillCellsByRegion(kept, page, result);
        }

        renderKeptTables(pageNum, kept, result);
    }

    /**
     * Build + render each kept lattice component into a {@link TableHit} and append it to {@code
     * result.tables}, isolating one component's render failure from its siblings. Package-private
     * (split out of {@link #extractLatticePage}, mirroring {@link #selectKeptTables}'s own split)
     * so tests can drive it directly with a synthetic component list, without needing PDF/ruling
     * geometry.
     *
     * <p>round-3 follow-up (post-FIX-B): buildTable/renderViews must be isolated PER TABLE here,
     * mirroring {@link #selectKeptTables}'s own FIX-3 pattern. renderViews' defense-in-depth
     * grid-product guard (added by FIX B) can throw {@link RulingOverflowException} for a
     * component that already cleared selectKeptTables' cell-count ({@link #MAX_CELLS_PER_TABLE})
     * and split-work ({@link #MAX_SPLIT_WORK}) gates -- a sparse/pathological shape whose
     * clustered rowCount*colCount product still exceeds MAX_CELLS_PER_TABLE even though neither
     * of THOSE gates caught it (REPRODUCED: kept.size()=2, one legit 2x2 component + one
     * pathological ~200-cell diagonal clustering into a 200x200 grid; without this per-table
     * catch, only 1 of the 2 tables reached result.tables -- a legit table ordered AFTER the
     * pathological one in {@code kept} was silently lost too, with only a generic
     * Result.truncated=true). Skip just the offending table (flagging truncated) and keep
     * rendering the rest, the same isolation selectKeptTables already applies to splitComponent's
     * own RulingOverflowException.
     *
     * <p>No catch(StackOverflowError) here (unlike selectKeptTables' splitComponent isolation):
     * buildTable/renderViews do not recurse -- placeGrid/isCoherentPlacement/renderViews are all
     * flat loops over the already-clustered grid -- so there is no analogous deep-recursion
     * source to guard against in this loop.
     */
    static void renderKeptTables(int pageNum, List<List<CellRect>> kept, Result result) {
        for (List<CellRect> comp : kept) {
            try {
                TableHit t = buildTable(pageNum, comp, "lattice");
                renderViews(t);
                result.tables.add(t);
            } catch (RulingOverflowException e) {
                result.truncated = true;
                System.err.println("WARNING: table render skipped on page " + pageNum + " (grid-product cap)");
            }
        }
    }

    /**
     * Decides which grouped components (from {@link #groupIntoTables}) actually become kept
     * tables: runs {@link #splitComponent} per component, then gates the resulting sub-tables
     * against {@link #MAX_CELLS_PER_TABLE} / {@link #MAX_TABLES_PER_PAGE}. Package-private (split
     * out of {@link #extractLatticePage}) so tests can drive it directly with a synthetic
     * component list, without needing PDF/ruling geometry.
     *
     * <p>FIX 3: splitComponent's {@link RulingOverflowException} (MAX_SPLIT_WORK) used to have no
     * per-component try/catch here, so ONE adversarial component's budget trip unwound this WHOLE
     * loop -- since {@code kept} (and, transitively, {@code Result.tables}) is only populated
     * AFTER the loop returns, every OTHER legitimate table already found on the page was silently
     * lost too (REPRODUCED: 3 legit tables lost to 1 adversarial 880-cell component). Isolate the
     * failure to just the offending component: skip it (flagging {@code truncated}) and keep
     * processing the rest.
     *
     * <p>FIX 4(b): likewise, a {@link StackOverflowError} escaping splitComponent's recursion
     * (the {@link #MAX_SPLIT_DEPTH} guard should already prevent this, but on a constrained stack
     * an Error can still slip past a coarser guard) must not kill the whole page -- or the worker
     * -- either, mirroring {@code extractTagged}'s existing {@code catch(StackOverflowError)} for
     * the tagged path.
     */
    static List<List<CellRect>> selectKeptTables(List<List<CellRect>> components, int pageNum, Result result) {
        List<List<CellRect>> kept = new ArrayList<>();
        int tablesOnPage = 0;
        pageLoop:
        for (List<CellRect> comp : components) {
            if (comp.size() > MAX_CELLS_PER_TABLE) {
                result.truncated = true;
                continue;
            }
            List<List<CellRect>> subComponents;
            try {
                subComponents = splitComponent(comp);
            } catch (RulingOverflowException e) {
                result.truncated = true;
                System.err.println("WARNING: table split skipped on page " + pageNum + " (split-work cap)");
                continue;
            } catch (StackOverflowError e) {
                result.truncated = true;
                System.err.println("WARNING: table split overflowed the stack on page " + pageNum + " (component skipped): " + e);
                continue;
            }
            for (List<CellRect> sub : subComponents) {
                if (buildTable(pageNum, sub, "lattice") == null) continue;
                if (++tablesOnPage > MAX_TABLES_PER_PAGE) {
                    result.truncated = true;
                    break pageLoop;
                }
                kept.add(sub);
            }
        }
        return kept;
    }

    // ---------------------------------------------------------------- tagged path

    // Structure types collectByType must not descend past when walking for "TR" (or nested
    // "Table") matches -- a Table nested inside a TD is discovered as its OWN independent
    // top-level Table entry (see the "Table" collection call in extractTagged, which uses no
    // stop set and so keeps recursing past a match), but must never have its rows/cells folded
    // into the OUTER table that contains it.
    private static final Set<String> STOP_AT_TABLE = Set.of("Table");

    /** Test-only instrumentation: counts {@code PDFMarkedContentExtractor.processPage(...)} calls
     * made by the tagged path's MCID resolution. Package-private so tests can assert a hostile,
     * out-of-scope-page table never triggers a content-stream walk. Not reset automatically;
     * callers should snapshot the value before/after the call under test. */
    static int taggedProcessPageCalls = 0;

    /** Test-only instrumentation: counts DISTINCT structure-tree nodes actually recursed into by
     * {@link #collectByType} and {@link #collectGlyphs} (i.e. AFTER the FIX 1 identity-memoization
     * skip check), across both call sites. Package-private so tests can assert a DAG "diamond"
     * fan-in (the same child element referenced as more than one kid) is visited ONCE, not
     * exponentially re-visited -- linear in distinct nodes, not exponential in depth. Not reset
     * automatically; callers should snapshot the value before/after the call under test. */
    static long structureNodesVisited = 0;

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
        // No stop set here: nested Table elements must surface as their OWN entries in `tables`
        // (fix for the flattening bug below), so recursion continues past a "Table" match too.
        collectByType(root, "Table", tables, 0, Set.of());
        if (tables.isEmpty()) return;

        // page -> (mcid -> glyphs), computed lazily, one content pass per page
        Map<PDPage, Map<Integer, List<TextPosition>>> mcidCache = new HashMap<>();
        // page object -> 1-indexed number
        Map<PDPage, Integer> pageNumbers = new HashMap<>();
        int n = 0;
        for (PDPage p : doc.getPages()) pageNumbers.put(p, ++n);

        // Per-page count of ACCEPTED tagged tables, enforcing the same MAX_TABLES_PER_PAGE cap
        // the lattice path applies -- a hostile structure tree with thousands of Table elements
        // sharing one TR/TD set (or independently authored) must not emit unbounded duplicates
        // into report.json.
        Map<Integer, Integer> tablesPerPage = new HashMap<>();

        for (PDStructureElement tableEl : tables) {
            // Same between-item interrupt check as extract()'s per-page loop, and for the same
            // reason: a hostile structure tree can carry thousands of Table elements, so this
            // loop must also honor a soft --timeout rather than only being checked after
            // extract() returns.
            if (Thread.currentThread().isInterrupted()) {
                result.truncated = true;
                break;
            }
            List<PDStructureElement> trs = new ArrayList<>();
            collectByType(tableEl, "TR", trs, 0, STOP_AT_TABLE);
            if (trs.isEmpty()) continue;                     // degenerate -> lattice may still run

            // Determine the table's page EARLY and cheaply -- PDStructureElement.getPage() only
            // reads the /Pg dictionary reference, no MCID resolution / content-stream walk. A
            // hostile structure tree referencing thousands of out-of-scope pages is rejected
            // right here, before buildTaggedTable ever calls resolveCellText/glyphsFor.
            PDPage earlyPage = firstCellPage(trs);
            Integer pageNum = earlyPage == null ? null : pageNumbers.get(earlyPage);
            if (pageNum == null || !pagesToProcess.contains(pageNum)) continue;

            if (tablesPerPage.getOrDefault(pageNum, 0) >= MAX_TABLES_PER_PAGE) {
                result.truncated = true;
                continue; // per-page cap already met -- drop further tables for this page
            }

            TableHit t = buildTaggedTable(trs, earlyPage, pageNum, mcidCache, pageNumbers, pagesToProcess, result);
            if (t == null) continue;                          // degenerate, or cap-rejected (result.truncated
                                                               // already set inside buildTaggedTable) -- lattice
                                                               // may still run for a degenerate page
            // No per-table try/catch needed here (unlike extractLatticePage's render loop, see its
            // own comment on that fix): buildTaggedTable's own grid-product guard (FIX B) already
            // rejects an oversized grid by RETURNING NULL (handled by the `t == null` continue
            // above), not by throwing -- so renderViews' defense-in-depth RulingOverflowException
            // guard can never trip for a `t` that reaches this point (t.rowCount*t.colCount was
            // already checked against the SAME MAX_CELLS_PER_TABLE bound, using the SAME
            // rowCount/colCount values, before buildTaggedTable ever returned this TableHit).
            tablesPerPage.merge(pageNum, 1, Integer::sum);
            renderViews(t);
            result.tables.add(t);
            coveredOut.add(t.page);
        }
    }

    /** Cheap page lookup with NO content-stream access: first TD/TH (in row/cell order) whose
     * /Pg resolves via {@link #resolveElementPage}. Used both to gate a table against
     * pagesToProcess before any MCID work, and (by construction, same row/cell order as
     * buildTaggedTable) to pick the table's own page. */
    private static PDPage firstCellPage(List<PDStructureElement> trs) {
        for (PDStructureElement tr : trs) {
            for (Object kid : tr.getKids()) {
                if (kid instanceof PDStructureElement el) {
                    String st = el.getStandardStructureType();
                    if ("TD".equals(st) || "TH".equals(st)) {
                        PDPage page = resolveElementPage(el);
                        if (page != null) return page;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Resolve a structure element's page, walking up ANCESTOR structure elements
     * ({@code getParent()}) when the element itself carries no explicit /Pg. Per ISO 32000, /Pg
     * is commonly inherited -- set once on an ancestor (e.g. the enclosing Table or TR) rather
     * than repeated on every TD/TH leaf -- so checking only the element itself silently treats a
     * spec-conforming, accessibility-tagged table as pageless.
     *
     * <p>Cheap and side-effect-free: only reads each ancestor's /Pg dictionary reference, no MCID
     * resolution or content-stream access, so callers can gate the resolved page against
     * pagesToProcess BEFORE any glyph work -- this must not reintroduce the out-of-scope-page-walk
     * DoS the pagesToProcess gating previously fixed.
     *
     * <p>Depth-capped (mirrors collectByType/collectGlyphs' existing depth&gt;64 guards) against a
     * cyclic/hostile structure tree; past the cap this simply gives up and returns null (same as
     * "no /Pg found anywhere"), it does not loop or overflow.
     */
    private static PDPage resolveElementPage(PDStructureElement el) {
        PDStructureNode node = el;
        int depth = 0;
        while (node instanceof PDStructureElement se && depth <= 64) {
            PDPage page = se.getPage();
            if (page != null) return page;
            node = se.getParent();
            depth++;
        }
        return null;
    }

    /** DFS for structure elements of a standard type; depth-capped against cyclic/hostile trees.
     * Does not descend past any element whose standard type is in {@code stopTypes} (still adds
     * it first if it matches {@code type} itself) -- used to keep a nested Table's TR/TD from
     * being folded into an outer table's row list.
     *
     * <p>FIX 1: a PDF structure tree is a DAG, not a tree -- {@code PDStructureElement.appendKid}
     * allows the SAME child element to be referenced as a kid of more than one parent. A "diamond"
     * chain where each level references the same previous element as BOTH of its kids causes 2^N
     * visits with only a depth guard (REPRODUCED: depth=22 -> 4.1s on a real ~1KB fixture, and
     * depth is attacker-controlled up to the depth-64 cap, reaching hours). Threads an
     * IDENTITY-keyed (COS object identity, not structural equals/hashCode) visited-set through the
     * recursion so a child already reached via one parent is skipped, not re-walked, via another --
     * converting exponential fan-in blowup into linear-in-distinct-nodes. Package-private (this
     * entry point, not the recursive helper) so tests can drive a synthetic diamond DAG directly. */
    static void collectByType(PDStructureNode node, String type,
                              List<PDStructureElement> out, int depth, Set<String> stopTypes) {
        collectByType(node, type, out, depth, stopTypes, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void collectByType(PDStructureNode node, String type, List<PDStructureElement> out,
                                      int depth, Set<String> stopTypes, Set<COSBase> visited) {
        if (depth > 64) return;
        for (Object kid : node.getKids()) {
            if (out.size() > 10_000) return; // bail mid-loop once the cap is hit, not just on entry
            if (!(kid instanceof PDStructureElement el)) continue;
            COSBase cos = el.getCOSObject();
            if (cos != null && !visited.add(cos)) continue; // DAG fan-in: already reached via another parent
            structureNodesVisited++;
            String st = el.getStandardStructureType();
            if (type.equals(st)) out.add(el);
            if (stopTypes.contains(st)) continue; // nested Table (or other stop boundary): don't descend
            collectByType(el, type, out, depth + 1, stopTypes, visited);
        }
    }

    /** Build one tagged table; returns null when degenerate (no rows / no textful cells -- silent,
     * lattice fallback covers these pages) or when the span-bomb cumulative-area guard trips (NOT
     * silent: sets {@code result.truncated = true} before returning null, since this is the same
     * hostile-input cap the lattice path signals via Result.truncated). {@code trs} is
     * pre-collected (stopping at nested Table boundaries) and {@code tablePage}/{@code pageNum}
     * pre-resolved and pagesToProcess-gated by the caller -- this method never has to reject on
     * page-scope itself. */
    private static TableHit buildTaggedTable(List<PDStructureElement> trs, PDPage tablePage, int pageNum,
                                             Map<PDPage, Map<Integer, List<TextPosition>>> mcidCache,
                                             Map<PDPage, Integer> pageNumbers,
                                             Set<Integer> pagesToProcess, Result result) throws IOException {
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
                resolveCellText(el, cell, mcidCache, pageNumbers, pagesToProcess);
                row.add(cell);
            }
            if (!row.isEmpty()) rows.add(row);
        }
        if (rows.isEmpty()) return null;
        if (rows.stream().allMatch(r -> r.stream().allMatch(c -> c.text.isEmpty()))) return null;

        // Grid placement with an occupancy map (HTML table algorithm), bounded against a
        // RowSpan/ColSpan bomb: readSpans() already clamps each span to MAX_SPAN, but a single
        // cell can still claim up to MAX_SPAN*MAX_SPAN cells of area -- cumulativeArea is checked
        // against MAX_CELLS_PER_TABLE BEFORE the occupancy map is populated for that cell, so a
        // hostile span is rejected in O(1) rather than after an O(area) HashMap-filling loop.
        int colCount = 0;
        List<CellHit> placed = new ArrayList<>();
        Map<Long, Boolean> occupied = new HashMap<>();
        float bx0 = Float.MAX_VALUE, by0 = Float.MAX_VALUE, bx1 = -Float.MAX_VALUE, by1 = -Float.MAX_VALUE;
        boolean anyBbox = false;
        long cumulativeArea = 0;
        for (int r = 0; r < rows.size(); r++) {
            int c = 0;
            for (TaggedCell cell : rows.get(r)) {
                while (occupied.containsKey(((long) r << 32) | c)) c++;
                long area = (long) cell.rowSpan * (long) cell.colSpan;
                cumulativeArea += area;
                if (cumulativeArea > MAX_CELLS_PER_TABLE) {
                    result.truncated = true; // hostile-input cap, distinct from a silent degenerate reject
                    return null; // reject BEFORE inserting slots
                }
                CellHit hit = new CellHit();
                hit.row = r;
                hit.col = c;
                hit.rowSpan = cell.rowSpan;
                hit.colSpan = cell.colSpan;
                hit.text = cell.text;
                // Cross-page guard: a cell resolved from a DIFFERENT page than the table's own
                // page must not have its bbox unioned into this table's (single-page) frame.
                boolean onTablePage = tablePage.equals(cell.page);
                hit.bbox = onTablePage ? cell.bbox : null;
                if (cell.th) hit.header = Boolean.TRUE;
                placed.add(hit);
                for (int rr = r; rr < r + cell.rowSpan; rr++) {
                    for (int cc = c; cc < c + cell.colSpan; cc++) {
                        occupied.put(((long) rr << 32) | cc, Boolean.TRUE);
                    }
                }
                colCount = Math.max(colCount, c + cell.colSpan);
                if (onTablePage && hit.bbox != null) {
                    anyBbox = true;
                    bx0 = Math.min(bx0, hit.bbox[0]); by0 = Math.min(by0, hit.bbox[1]);
                    bx1 = Math.max(bx1, hit.bbox[2]); by1 = Math.max(by1, hit.bbox[3]);
                }
                c += cell.colSpan;
            }
        }
        if (colCount == 0) return null;

        // FIX B (round 3): cumulativeArea above bounds the sum of each INDIVIDUAL cell's
        // rowSpan*colSpan area, but a sparse/pathological structure tree can keep that sum small
        // while still clustering into a huge ALLOCATED grid -- e.g. one row with a handful of
        // wide-colSpan cells (only the first of which carries an MCID, so its declared colSpan
        // alone pushes colCount out) followed by thousands of mostly-empty 1x1 rows (each adding
        // just +1 to cumulativeArea but +1 to rowCount). REPRODUCED: 5,000 TRs (TR#0: 5 TDs each
        // ColSpan=1000; TR#1..4999: near-empty 1x1 rows) -> cumulativeArea 9,999 (under
        // MAX_CELLS_PER_TABLE, so the guard above never trips) but rowCount=5000 x colCount=5000
        // -> a 25,000,000-slot grid; renderViews' {@code new String[rowCount][colCount]} plus its
        // full-grid markdown StringBuilder then allocate ~250MB for this ONE table (x up to
        // MAX_TABLES_PER_PAGE on the same page -> gigabytes -> OOM), entirely BEFORE
        // renderViews is reached. A legitimate table's grid product approximates its real cell
        // count, so MAX_CELLS_PER_TABLE is the same consistent bound here as cumulativeArea uses
        // above -- reject (not silently drop: same truncated signal as the cumulativeArea cap)
        // BEFORE the caller ever calls renderViews on this table.
        if ((long) rows.size() * colCount > MAX_CELLS_PER_TABLE) {
            result.truncated = true;
            return null;
        }

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
            Revisions<PDAttributeObject> revisions = el.getAttributes();
            // Revisions is not Iterable (no getAll()/iterator()) -- index by size()/getObject(i).
            for (int i = 0; i < revisions.size(); i++) {
                Object att = revisions.getObject(i);
                if (att instanceof PDTableAttributeObject tao) {
                    cell.rowSpan = clampSpan(tao.getRowSpan());
                    cell.colSpan = clampSpan(tao.getColSpan());
                }
            }
        } catch (Exception ignored) {
            // hostile/malformed attribute dicts: keep 1x1
        }
    }

    /** Clamp an attacker-controlled RowSpan/ColSpan attribute into [1, MAX_SPAN]. */
    private static int clampSpan(int v) {
        if (v < 1) return 1;
        return Math.min(v, MAX_SPAN);
    }

    /** Gather the cell's MCIDs (bare integer kids and MCR kids), resolve to glyphs, join text. */
    private static void resolveCellText(PDStructureElement el, TaggedCell cell,
                                        Map<PDPage, Map<Integer, List<TextPosition>>> mcidCache,
                                        Map<PDPage, Integer> pageNumbers,
                                        Set<Integer> pagesToProcess) throws IOException {
        // Ancestor-fallback resolution (FIX 7): the TD/TH itself may carry no /Pg at all when it
        // is inherited from an enclosing TR/Table, per ISO 32000. Seeding collectGlyphs with this
        // resolved page (rather than the bare el.getPage()) is what lets glyphsFor's own
        // pagesToProcess gate work correctly for such a cell.
        PDPage resolvedPage = resolveElementPage(el);
        List<TextPosition> glyphs = new ArrayList<>();
        collectGlyphs(el, resolvedPage, glyphs, mcidCache, 0, pageNumbers, pagesToProcess);
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
        if (resolvedPage != null) cell.page = resolvedPage;
    }

    /** FIX 1: same DAG fan-in hazard as {@link #collectByType} (a shared structure element
     * reachable via more than one parent), and REPRODUCED even worse here (depth=22 -> 11.2s,
     * near the 15s hard-halt) since each visit also does glyph-resolution work. Same fix: an
     * identity-keyed visited-set threaded through the recursion, skipping a child already reached
     * via another parent. Package-private (this entry point) so tests can drive a synthetic
     * diamond DAG directly. */
    static void collectGlyphs(PDStructureNode node, PDPage inheritedPage, List<TextPosition> out,
                              Map<PDPage, Map<Integer, List<TextPosition>>> mcidCache, int depth,
                              Map<PDPage, Integer> pageNumbers, Set<Integer> pagesToProcess) throws IOException {
        collectGlyphs(node, inheritedPage, out, mcidCache, depth, pageNumbers, pagesToProcess,
                Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    private static void collectGlyphs(PDStructureNode node, PDPage inheritedPage, List<TextPosition> out,
                                      Map<PDPage, Map<Integer, List<TextPosition>>> mcidCache, int depth,
                                      Map<PDPage, Integer> pageNumbers, Set<Integer> pagesToProcess,
                                      Set<COSBase> visited) throws IOException {
        if (depth > 64) return;
        PDPage page = inheritedPage;
        if (node instanceof PDStructureElement el && el.getPage() != null) page = el.getPage();
        for (Object kid : node.getKids()) {
            if (kid instanceof PDStructureElement el) {
                // A nested Table is discovered/built as its own independent TableHit (see
                // extractTagged); pulling its cell text into the OUTER cell here would both
                // duplicate it and defeat the "nested rows don't leak into the outer table"
                // fix -- so this cell's own text resolution stops at that boundary too.
                if ("Table".equals(el.getStandardStructureType())) continue;
                COSBase cos = el.getCOSObject();
                if (cos != null && !visited.add(cos)) continue; // DAG fan-in: already reached via another parent
                structureNodesVisited++;
                collectGlyphs(el, page, out, mcidCache, depth + 1, pageNumbers, pagesToProcess, visited);
            } else if (kid instanceof Integer mcid && page != null) {
                out.addAll(glyphsFor(page, mcid, mcidCache, pageNumbers, pagesToProcess));
            } else if (kid instanceof COSInteger ci && page != null) {
                out.addAll(glyphsFor(page, (int) ci.longValue(), mcidCache, pageNumbers, pagesToProcess));
            } else if (kid instanceof PDMarkedContentReference mcr) {
                PDPage mcrPage = mcr.getPage() != null ? mcr.getPage() : page;
                if (mcrPage != null) out.addAll(glyphsFor(mcrPage, mcr.getMCID(), mcidCache, pageNumbers, pagesToProcess));
            }
        }
    }

    private static List<TextPosition> glyphsFor(PDPage page, int mcid,
                                                Map<PDPage, Map<Integer, List<TextPosition>>> cache,
                                                Map<PDPage, Integer> pageNumbers,
                                                Set<Integer> pagesToProcess) throws IOException {
        // Defense-in-depth against a mixed-page hostile table: even if the table's OWN page
        // passed extractTagged's early gate, an individual cell's /Pg could point at a
        // different, out-of-scope page. Never walk that page's content stream.
        Integer pn = pageNumbers.get(page);
        if (pn == null || !pagesToProcess.contains(pn)) return List.of();
        Map<Integer, List<TextPosition>> byMcid = cache.get(page);
        if (byMcid == null) {
            byMcid = new HashMap<>();
            taggedProcessPageCalls++;
            PDFMarkedContentExtractor ex = new PDFMarkedContentExtractor();
            ex.processPage(page);
            for (PDMarkedContent mc : ex.getMarkedContents()) flattenMarkedContent(mc, byMcid, 0);
            cache.put(page, byMcid);
        }
        return byMcid.getOrDefault(mcid, List.of());
    }

    /** Depth-capped (mirrors collectGlyphs' depth>64 guard): attacker-controlled nested marked
     * content with no bound here would StackOverflowError -- an Error that escapes
     * extractTagged's catch(Exception) entirely. Past the cap we simply stop descending; any
     * text further down that nested chain is lost, not corrupted. */
    static void flattenMarkedContent(PDMarkedContent mc, Map<Integer, List<TextPosition>> byMcid, int depth) {
        if (depth > 64) return;
        int mcid = mc.getMCID();
        for (Object content : mc.getContents()) {
            if (content instanceof TextPosition tp && mcid >= 0) {
                byMcid.computeIfAbsent(mcid, k -> new ArrayList<>()).add(tp);
            } else if (content instanceof PDMarkedContent nested) {
                flattenMarkedContent(nested, byMcid, depth + 1);
            }
        }
    }
}
