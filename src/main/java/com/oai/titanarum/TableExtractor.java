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
import org.apache.pdfbox.text.PDFTextStripper;
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
    // Bounds the --skip-text-urls region-fill fallback (fillCellsByRegion) on the MEMORY side.
    //
    // round-6 (THIS fix, supersedes rounds 3-5 below): fillCellsByRegion no longer uses
    // PDFTextStripterByArea at all. Rounds 3-5 (see the git history of this file) chased an
    // inherent, unresolvable tension in that class: setSuppressDuplicateOverlappingText(true)
    // (needed to collapse a cell whose text is drawn TWICE at the identical (x,y) -- a common
    // fake-bold-via-redraw / redundant-text-layer generator pattern -- into one correct copy
    // instead of a garbled interleave) shares its dedup bookkeeping (a single {@code
    // characterListMapping} field) ACROSS every region registered in one {@code extractRegions()}
    // call -- so a glyph that geometrically falls inside TWO OR MORE overlapping/nested cell
    // regions (e.g. one table nested inside another table's cell) gets recorded for only the
    // FIRST region that claims it and silently DROPPED from every other region that also
    // genuinely contains it (REPRODUCED: {@code
    // TableLatticeTest#overlappingCellRegionsBothReceiveSharedGlyph}). Turning suppression off
    // fixes the overlap case but garbles the duplicate-draw case -- PDFTextStripterByArea cannot
    // satisfy both at once, no matter how the two per-glyph work budgets (the since-removed
    // MAX_REGION_CELLS / MAX_REGION_WORK) were tuned.
    //
    // Fix: stop using PDFTextStripterByArea's own region-matching entirely. Instead, {@link
    // PositionCollectingStripper} makes ONE streaming pass collecting this page's TextPositions
    // (benefiting for free from the dedup every {@code PDFTextStripper} subclass already gets
    // from the BASE class's {@code processTextPosition} -- suppression there is keyed only by
    // (character, x, y), not by which region is being read back, so it can't have the
    // cross-region drop bug at all), then {@link #fillCellsFromPositions} -- the SAME
    // midpoint-bucketing mechanism the default (non-skip-text-urls) path already uses -- buckets
    // those positions into each cell INDEPENDENTLY, so overlapping/nested cells each correctly
    // receive their own copy of a shared glyph.
    //
    // MAX_REGION_GLYPHS now bounds the number of TextPositions {@link PositionCollectingStripper}
    // will RETAIN: {@code writeString} throws {@link RulingOverflowException} once the retained
    // count would exceed this cap, before delegating to the base class's own per-glyph
    // bookkeeping -- capping the list at a fixed size regardless of how many glyphs the page
    // actually contains (closing the FIX-2-era OOM this constant has bounded since round 3: a
    // 12,483-byte one-page PDF with 6,000,000 glyphs used to throw OutOfMemoryError at -Xmx2g).
    // {@link PositionCollectingStripper} additionally discards (without counting against the
    // budget) any position whose bucketing midpoint falls outside the combined bounding box of
    // every cell being filled -- see that class's doc for why this can never exclude a glyph that
    // could actually match a cell, only glyphs that provably can't.
    //
    // The CPU side no longer needs a dedicated region budget at all: collection is a single O(1)-
    // per-glyph streaming pass (bounded in TOTAL retained count by this same cap), and the
    // bucketing pass is {@link #fillCellsFromPositions}'s existing O(cells x positions) budget
    // ({@link #MAX_TEXTFILL_WORK}) -- the same budget, same formula, same proven bound the default
    // path has always relied on. 2,000,000 retained TextPositions comfortably fits the production
    // -Xmx4g heap (measured: see {@code
    // TableLatticeTest#regionGlyphBombIsBoundedNotBufferedOrOOMed}'s updated doc for the actual
    // child-JVM heap this round's fixture needed).
    static final int MAX_REGION_GLYPHS = 2_000_000;
    // PR review P1 (CRITICAL, reproduced end-to-end): glyphsFor's `PDFMarkedContentExtractor ex =
    // new PDFMarkedContentExtractor(); ex.processPage(page);` had NO glyph cap, NO work budget, and
    // NO interrupt check -- the ONLY unbounded stage left in this class. Worse than a linear memory
    // bomb: PDFMarkedContentExtractor.processTextPosition, with suppressDuplicateOverlappingText ON
    // (the default -- see MAX_TAGGED_WORK's doc for why it must STAY on), buckets every glyph into a
    // Map<character-string, List<TextPosition>> keyed SOLELY by the glyph's unicode character (not
    // by position) and does a full LINEAR SCAN of that bucket on every glyph before appending to it
    // -- an O(bucket size) inner scan per glyph, so N glyphs sharing one repeated character cost
    // O(N) work N times over = O(N^2) total, on top of the unbounded memory every retained glyph
    // already costs. REPRODUCED (this fix's own measurement, real PDFMarkedContentExtractor,
    // -Xmx4g, single repeated character): N=50,000 -> 3.3s; N=100,000 -> 14.7s; N=150,000 -> 34.2s
    // (quadratic; matches the PR review's own independently-measured N=100,000/15.16s and
    // N=300,000/169.7s figures). A page with ~2,000,000 such glyphs (the same order of magnitude
    // the region path's MAX_REGION_GLYPHS already bounds to a sub-second throw) would run for
    // roughly an hour single-core here, past the 15s hard-halt watchdog that then kills the whole
    // worker JVM via Runtime.halt(3) instead of a graceful truncate.
    //
    // FIX: {@link BudgetedMarkedContentExtractor} wraps glyphsFor's PDFMarkedContentExtractor call,
    // overriding processTextPosition to enforce BOTH bounds below BEFORE delegating to super,
    // mirroring the region path's PositionCollectingStripper (memory cap) + MAX_TEXTFILL_WORK-style
    // (CPU cap) pairing, adapted to marked-content extraction's different cost shape:
    //
    // MEMORY -- MAX_TAGGED_GLYPHS: caps the TextPositions retained from one page's marked-content
    // pass. Kept as a DISTINCT constant from MAX_REGION_GLYPHS (same value, same "how many
    // TextPositions can this page safely retain" semantics) rather than reused outright, because
    // the tagged path's extractor is memoized ONE PER PAGE across every MCID lookup on that page
    // (a different cache lifecycle than the region path's one-shot-per-page-fill use) -- keeping it
    // a separate named constant lets either be retuned independently without an unrelated coupling.
    static final int MAX_TAGGED_GLYPHS = 2_000_000;
    // CPU bound for glyphsFor (see MAX_TAGGED_GLYPHS immediately above for the memory side of this
    // same fix). PDFMarkedContentExtractor DOES expose a public setSuppressDuplicateOverlappingText
    // (false) setter that would remove the O(n^2) scan entirely -- but this project already tried
    // exactly that trade for the closely analogous region-fill path (commit f095959) and REVERTED
    // it (commit 6d5bf8e) once it was shown to garble genuinely duplicate-drawn cell text
    // (fake-bold-via-redraw / redundant text layers -- a common NON-hostile PDF-generator pattern,
    // not just a hostile one): with suppression off, two identically-positioned character runs
    // interleave into garbage ("TToottaall" instead of "Total") instead of collapsing to one
    // correct copy. PDFMarkedContentExtractor's dedup is the SAME bucketed-by-character,
    // linear-scanned mechanism (just against a plain List instead of PDFTextStripperByArea's
    // TreeMap/TreeSet), so disabling it here would reintroduce the identical correctness
    // regression for tagged cell text. Suppression is left ON (the default); instead, {@link
    // BudgetedMarkedContentExtractor} tracks its OWN Map<String, Long> of per-character counts,
    // mirroring the base class's real bucket key (text.getUnicode()) exactly, and charges `work` by
    // the CURRENT count for that glyph's character before forwarding it to super. Summed over every
    // glyph, this equals Sum_c(n_c*(n_c-1)/2) -- the EXACT total the base class's own O(bucket)
    // scan performs -- so this budget bounds the TRUE aggregate cost regardless of how an attacker
    // distributes repeats across one or many characters. (A coarser "charge by total glyph count"
    // formula was considered and rejected: it would also throttle a legitimate page with thousands
    // of DIVERSE characters and no actual hot bucket, purely because of its raw glyph count.)
    //
    // Calibrated by direct measurement (this fix, -Xmx4g, real PDFMarkedContentExtractor wrapped by
    // BudgetedMarkedContentExtractor): a single repeated character (the mathematically worst
    // concentration for a fixed glyph count) trips this budget at ~24,500 glyphs in ~0.4s. The
    // worst REALISTIC shape found -- 91 distinct characters (the practical ceiling for one
    // single-byte/WinAnsiEncoding-style font -- PDF string-literal syntax reserves 3 of the 94
    // printable-ASCII codes), cycled so every character's own bucket still grows large -- trips it
    // at ~226,000 glyphs in ~1.4s: worse than the single-character case (more DISTINCT retained
    // TextPosition/bookkeeping objects accumulate before the same aggregate dedup-scan cost is
    // reached) but still comfortably under this doc's ~3s target and the 15s hard-halt watchdog,
    // with real headroom left for a more exotic (e.g. embedded multi-byte CID font) attack to do
    // somewhat worse. MAX_TAGGED_GLYPHS remains an independent backstop for the opposite shape
    // (extreme character DIVERSITY keeping every bucket -- and so this work budget -- cheap, but
    // total glyph COUNT unbounded): baseline per-glyph cost with dedup entirely disabled (i.e.
    // zero-cost bucket scans) measured at ~150ms for 2,000,000 glyphs, so the glyph cap alone stays
    // fast even if an attacker defeats this work budget via extreme diversity.
    static final long MAX_TAGGED_WORK = 300_000_000;
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

    // PR re-review P2 (DoS): collectByType's FIX 1 identity-memoization only dedups node visits
    // WITHIN one call -- a fresh IdentityHashMap-backed visited set is created per call (see that
    // method's doc). extractTagged calls collectByType ONCE PER Table element found (the "Table"
    // search's own results) to gather that table's own "TR" descendants -- so a hostile structure
    // DAG with many Table elements all referencing the SAME large shared TR subtree (each
    // reachable via >1 parent, exactly the DAG-fan-in shape FIX 1 already handles WITHIN a call)
    // forces that shared subtree to be walked in FULL, from scratch, once per referencing Table
    // element: ~10,000 tables x a ~10,000-node shared subtree ~= 100,000,000 total node visits
    // before MAX_TABLES_PER_PAGE/MAX_CELLS_PER_TABLE ever get a chance to gate anything (those
    // caps only apply to ACCEPTED tables, long after this traversal cost is already paid).
    //
    // Fix: a document-wide work counter, threaded BY REFERENCE (not reset between tables) through
    // every collectByType call made by one extractTagged run -- the top-level "Table" search AND
    // every per-table "TR" search alike -- charged once per PDStructureElement node examined,
    // throwing RulingOverflowException once the cumulative total exceeds this budget (caught by
    // extract()'s existing per-stage catch, same as every other geometry/glyph cap in this class).
    //
    // Sized from the SAME per-call cap already in force (collectByType's own out.size()>10_000
    // bail-out) times a generously large but still bounded table count: a single legitimate table
    // can reach up to MAX_CELLS_PER_TABLE (10,000) TD/TH cells, so one table's own "TR" walk can
    // legitimately cost up to ~10,000 node visits; 500 such large tables in one document (a
    // generous ceiling -- MAX_TABLES_PER_PAGE alone is 50, so this covers 10 pages' worth of
    // maximally-sized tables with room to spare) is 5,000,000 -- comfortably above any real
    // document's total structural cost, while an adversarial shape (thousands of tables sharing
    // one huge subtree) trips this after only a handful of tables' worth of repeated work, failing
    // fast and deterministically well under the 15s hard-halt watchdog rather than after ~100M
    // visits.
    static final long MAX_STRUCTURE_WORK = 5_000_000;

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
        // Advisory-only hint: true when this LATTICE table's own cell footprint is substantially
        // covered by an already-emitted TAGGED table on the same page (see
        // #isLikelyDuplicateOfTaggedTable). Never causes suppression -- both tables are ALWAYS
        // emitted; a downstream consumer may use this to dedup if it chooses. Left null (and so
        // omitted from report.json, via the class's NON_NULL inclusion) for every tagged table and
        // for any lattice table that isn't flagged, so existing consumers that ignore unknown
        // fields see no change in shape for the common case.
        public Boolean likelyDuplicateOfTagged;
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
    // PR re-review (round 2): perpendicular-distance-from-chord tolerance RulingCollector.curveTo
    // uses to decide whether a cubic Bezier is GEOMETRICALLY a straight line (both control points
    // within this distance of the line through the curve's own start/end points) rather than
    // suppressing every curveTo-authored segment purely because of which content-stream operator
    // produced it -- see curveTo's own doc for the full rationale. A fraction of a point, matching
    // this file's other sub-point geometry tolerances (SNAP/EPS above).
    static final float CURVE_STRAIGHT_EPS = 0.5f;
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
     * (see {@link #applyPageRotation}) -- the same frame {@code TextPosition.getX()/getY()}
     * use, so rulings and text always share one frame.
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
     * rotation-aware {@code TextPosition.getX()/getY()} (which IS keyed off page.getRotation()):
     * verified by constructing a rotated fixture and printing getX()/getY() against the formula below for
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
         *
         * <p>PR re-review P2: each point is a {@code float[3]} of {@code {x, y, isCurveEndpoint}}
         * -- {@code isCurveEndpoint} (1f/0f) is set ONLY by {@link #curveTo}, and ONLY when that
         * curve is GEOMETRICALLY curved, not merely curveTo-authored (see that method's doc and
         * {@link #isEffectivelyStraight}), and read by {@link #addRulingsForSubpath} to suppress
         * emitting a straight ruling for the segment INTO a genuinely-curved endpoint, while still
         * keeping the point itself in the path for continuity (a subsequent lineTo from it must
         * still connect correctly).
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
            addPoint(new float[]{x, y, 0f});
        }

        @Override public void lineTo(float x, float y) {
            if (overflowed) return;
            addPoint(new float[]{x, y, 0f});
        }

        /**
         * PR re-review P2 (correctness -- false positives), round 1: the endpoint is kept in the
         * path for CONTINUITY (a subsequent lineTo from here must still connect correctly), but
         * (round 1) was unconditionally flagged so {@link #addRulingsForSubpath} never treated the
         * segment from the curve's start point INTO this endpoint as a straight ruling. Before
         * that fix, {@code strokePath}/{@code fillAndStrokePath} blindly connected every pair of
         * CONSECUTIVE buffered points with {@code addRuling}, regardless of whether pdfbox got
         * from one to the other via a straight lineTo or a Bezier curveTo -- so any curved artwork
         * (rounded rects, decorative curves) whose curve endpoint happened to land axis-aligned
         * with its own start point (a common case: rounded-rect corners return to the same x or y
         * as the straight edge that led into them) injected a bogus straight-line ruling along the
         * curve's chord, corrupting lattice table detection with phantom borders/intersections.
         *
         * <p>PR re-review, round 2 (recall regression found in round 1): keying suppression on
         * WHICH OPERATOR pdfbox dispatched (curveTo vs lineTo), rather than on actual geometry, is
         * itself wrong the other way -- a genuinely STRAIGHT line authored via the {@code c}
         * (curve) operator with control points collinear with its own endpoints (common from
         * vector-editor exports -- Illustrator/Inkscape -- and some report generators) was being
         * silently dropped as a ruling, a real false negative (missed table borders) on
         * non-hostile documents. A cubic Bezier is an AFFINE combination of its 4 control points
         * (the Bernstein coefficients always sum to 1) at every {@code t}, so when all 4 points
         * are collinear the entire curve renders exactly on that one line, start to end --
         * geometrically indistinguishable from a straight lineTo, regardless of where the two
         * control points fall along it. {@link #isEffectivelyStraight} tests exactly that
         * (perpendicular distance of each control point from the P0-&gt;P3 chord, within {@link
         * #CURVE_STRAIGHT_EPS}); only a curve that FAILS this test (a control point genuinely off
         * the chord) gets flagged as a curve endpoint. This closes BOTH directions at once: a
         * genuinely curved Bezier still never contributes a phantom chord ruling, and a
         * collinear-control straight line authored via {@code c} is treated exactly like a lineTo.
         */
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            if (overflowed) return;
            Point2D.Float p0 = getCurrentPoint();
            boolean straight = isEffectivelyStraight(p0.x, p0.y, x1, y1, x2, y2, x3, y3);
            addPoint(new float[]{x3, y3, straight ? 0f : 1f}); // keep the endpoint for continuity either way
        }

        /**
         * True when the cubic Bezier from {@code (x0,y0)} (control points {@code (x1,y1)}/{@code
         * (x2,y2)}, endpoint {@code (x3,y3)}) is geometrically indistinguishable from the straight
         * chord {@code (x0,y0)->(x3,y3)}: both control points lie within {@link
         * #CURVE_STRAIGHT_EPS} of the infinite line through the chord (perpendicular distance,
         * i.e. the 2D cross product of the chord vector and the control-point offset, normalized
         * by chord length). A degenerate (near-zero-length) chord has no meaningful line to test
         * collinearity against -- per {@link #curveTo}'s caller, treated as NOT straight (there is
         * no chord to draw as a ruling either way, so this only affects the flag, not correctness).
         */
        private static boolean isEffectivelyStraight(float x0, float y0, float x1, float y1,
                                                      float x2, float y2, float x3, float y3) {
            float dx = x3 - x0, dy = y3 - y0;
            float chordLen = (float) Math.hypot(dx, dy);
            if (chordLen < 1e-4f) return false; // degenerate chord: no line to test against
            float d1 = Math.abs(dx * (y1 - y0) - dy * (x1 - x0)) / chordLen;
            float d2 = Math.abs(dx * (y2 - y0) - dy * (x2 - x0)) / chordLen;
            return d1 <= CURVE_STRAIGHT_EPS && d2 <= CURVE_STRAIGHT_EPS;
        }

        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            if (overflowed) return;
            finalizeCurrentSubpath();
            addPoint(new float[]{(float) p0.getX(), (float) p0.getY(), 0f});
            addPoint(new float[]{(float) p1.getX(), (float) p1.getY(), 0f});
            addPoint(new float[]{(float) p2.getX(), (float) p2.getY(), 0f});
            addPoint(new float[]{(float) p3.getX(), (float) p3.getY(), 0f});
            addPoint(new float[]{(float) p0.getX(), (float) p0.getY(), 0f});
        }

        @Override public void closePath() {
            if (overflowed || current.isEmpty()) return;
            float[] first = current.get(0);
            addPoint(new float[]{first[0], first[1], 0f}); // closing edge is a straight line, not a curve
        }

        /** True when {@code p} (a buffered path point, see {@link #addPoint}) arrived via {@link
         * #curveTo} -- the segment ENDING at such a point must not be emitted as a straight ruling
         * by {@link #addRulingsForSubpath} (see that curveTo override's doc for the full FIX). */
        private static boolean isCurveEndpoint(float[] p) { return p[2] != 0f; }

        /** Shared by {@link #strokePath} and {@link #fillAndStrokePath}: connects consecutive
         * buffered points of one subpath with {@link #addRuling}, EXCEPT a segment whose
         * destination point is a curve endpoint (see {@link #curveTo}) -- that segment traces a
         * Bezier curve, not a drawn straight edge, so it must never be reported as a ruling. */
        private void addRulingsForSubpath(List<float[]> sp) {
            for (int i = 1; i < sp.size(); i++) {
                float[] a = sp.get(i - 1), b = sp.get(i);
                if (isCurveEndpoint(b)) continue;
                addRuling(a[0], a[1], b[0], b[1]);
            }
        }

        @Override public void strokePath() {
            finalizeCurrentSubpath();
            for (List<float[]> sp : subpaths) addRulingsForSubpath(sp);
            resetPath();
        }

        @Override public void fillPath(int windingRule) {
            finalizeCurrentSubpath();
            for (List<float[]> sp : subpaths) emitThinFillAsRuling(sp);
            resetPath();
        }

        /**
         * PR re-review P2 (correctness -- phantom micro-rows/cols): a table border is often drawn
         * as a thin filled-AND-stroked rectangle (the {@code B} operator). Such a subpath's fill
         * geometry qualifies as a thin bar per {@link #emitThinFillAsRuling} -- its stroked outline
         * traces the SAME bar's own long edges, so unconditionally ALSO calling {@link
         * #addRulingsForSubpath} used to emit those two stroked edges (bottom+top, or left+right)
         * PLUS the fill's own centerline: 2-3 near-parallel rulings, all within the bar's own thin
         * width/height of each other, for what is visually ONE logical border. Near {@link #SNAP}
         * (2pt) those normalize into DISTINCT micro-rulings, corrupting lattice row/col detection
         * with phantom rows/columns. Fix: test the thin-bar case FIRST; if {@link
         * #emitThinFillAsRuling} handled it (one centerline ruling emitted), that IS this
         * subpath's sole contribution -- skip the stroked edges entirely. A subpath that is NOT a
         * thin bar (e.g. a real filled cell background) is unaffected and keeps the stroked-edge
         * behavior exactly as before.
         */
        @Override public void fillAndStrokePath(int windingRule) {
            finalizeCurrentSubpath();
            for (List<float[]> sp : subpaths) {
                if (!emitThinFillAsRuling(sp)) addRulingsForSubpath(sp);
            }
            resetPath();
        }

        /** A filled axis-aligned rect thinner than THIN_FILL_MAX in one dimension is a drawn line.
         * Returns true when {@code sp} qualified as such a thin bar and its single centerline
         * ruling was emitted -- read by {@link #fillAndStrokePath} to decide whether the stroked
         * rectangle edges must be suppressed for this same subpath (see that method's doc). */
        private boolean emitThinFillAsRuling(List<float[]> sp) {
            if (sp.isEmpty()) return false;
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (float[] p : sp) {
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            }
            float w = maxX - minX, h = maxY - minY;
            if (h <= THIN_FILL_MAX && w >= MIN_RULING_LEN) {
                float midY = (minY + maxY) / 2;
                addRuling(minX, midY, maxX, midY);
                return true;
            } else if (w <= THIN_FILL_MAX && h >= MIN_RULING_LEN) {
                float midX = (minX + maxX) / 2;
                addRuling(midX, minY, midX, maxY);
                return true;
            }
            return false;
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

        // FIX 3 (Codex P2): PositionAwareTextStripper (PdfTitanArumApp.java)'s indexToPosition
        // pushes the SAME TextPosition reference once per UTF-16 code unit of a multi-code-unit
        // glyph (a ligature like "fi"/"fl"/"ffi", or a surrogate pair), so positionsForRange(...)
        // -- and so `chars` here, on the default lattice path -- can hand this method the identical
        // reference N times for what is really ONE glyph; appending tp.getUnicode() (the glyph's
        // FULL string) once per duplicate reference doubles/triples the glyph's text ("fifi"
        // instead of "fi"). Fixed by collapsing consecutive IDENTICAL references (identity `==`,
        // never value/equals -- two DISTINCT glyphs that happen to share a character and position,
        // e.g. two separately-drawn "a"s, must both survive) right after the (y,x) sort above:
        // duplicate references share the exact same coordinates, so they always sort adjacent,
        // making a single "skip if same reference as the previous kept one" pass sufficient no
        // matter where in `chars`' original order the duplicates appeared.
        List<TextPosition> deduped = new ArrayList<>(sorted.size());
        TextPosition prevRef = null;
        for (TextPosition tp : sorted) {
            if (tp != prevRef) deduped.add(tp);
            prevRef = tp;
        }
        sorted = deduped;

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
     * round-6: streaming, glyph-budgeted {@code PDFTextStripper} subclass used by {@link
     * #fillCellsByRegion} to collect a page's TextPositions for the SAME midpoint-bucketing
     * mechanism ({@link #fillCellsFromPositions}) the default (non-skip-text-urls) path already
     * uses, instead of the removed PDFTextStripterByArea-based region matching (see {@link
     * #MAX_REGION_GLYPHS}'s doc for the full round-6 rationale: PDFTextStripterByArea's shared,
     * cross-region {@code characterListMapping} silently dropped a glyph from all-but-one of
     * several overlapping/nested cell regions, and there was no way to fix that without
     * reintroducing the duplicate-drawn-text garble rounds 3-5 fought over).
     *
     * <p>Overrides the LOW-level {@code processTextPosition(TextPosition)} hook, not {@code
     * writeString}: {@code writeString} is only called from {@code writePage()}, which does not
     * run until AFTER {@code processPage()} has already fed every one of the page's glyphs through
     * {@code processTextPosition} into the base class's own {@code charactersByArticle} buffer --
     * by the time {@code writeString} would see anything, an unbounded number of glyphs is already
     * sitting in memory. (Reproduced directly: an earlier version of this class overrode {@code
     * writeString} and OOM'd on a 6,000,000-glyph page even at multi-gigabyte heaps -- exactly the
     * round-3 {@code stripAllPositions} regression this budget exists to prevent, reintroduced by
     * hooking too late.) Hooking {@code processTextPosition} runs the budget check BEFORE any
     * buffering happens for this glyph, so the cap is real.
     *
     * <p>Still gets the base {@code PDFTextStripper}'s {@code suppressDuplicateOverlappingText}
     * dedup (on by default) for free on every glyph this class forwards, by calling {@code
     * super.processTextPosition(text)} for it -- unlike PDFTextStripterByArea's version of that
     * same flag, the base class's dedup is keyed purely by (character, x, y), never by which
     * "region" is being read back, so it cannot have the cross-region drop bug described above.
     *
     * <p>Bounded on memory by {@code glyphBudget} (see {@link #MAX_REGION_GLYPHS}): throws {@link
     * RulingOverflowException} once the RETAINED count ({@code charactersByArticle.get(0).size()})
     * would exceed the budget, BEFORE calling {@code super.processTextPosition(text)} for this
     * glyph -- capping retained memory regardless of the page's real glyph count. Also discards --
     * without counting against the budget, and without ever reaching {@code super} at all -- any
     * position whose bucketing midpoint (computed with the exact same formula {@link
     * #fillCellsFromPositions} uses) falls outside the combined bounding box of every cell being
     * filled: since that combined box is the UNION of every individual cell's rect, a glyph that
     * lands outside it cannot land inside any cell either, so discarding it here never changes the
     * final bucketed text -- it just keeps memory flat on an ordinary page where the table(s) cover
     * only a small fraction of the page's total text.
     *
     * <p>{@code writePage()} is overridden to do nothing: the default implementation sorts,
     * space-collapses, and writes {@code charactersByArticle} out through {@code writeString} --
     * all needless work here (this class reads {@code charactersByArticle} directly via {@link
     * #collected()} instead, preserving every retained {@code TextPosition} exactly as {@link
     * #fillCellsFromPositions}' own {@code joinText} line-clustering expects to sort/join it
     * itself), and skipping it means {@code output} (a {@code Writer}, normally installed by {@code
     * getText()}/{@code writeText()} -- neither of which this class's caller ever invokes, see
     * {@link #fillCellsByRegion}) is never touched, so none needs to be installed at all.
     */
    private static final class PositionCollectingStripper extends PDFTextStripper {
        private final long glyphBudget;
        private final int rotation;
        private final float unrotatedW, unrotatedH;
        private final float bx0, by0, bx1, by1;

        PositionCollectingStripper(long glyphBudget, int rotation, float unrotatedW, float unrotatedH,
                                    float bx0, float by0, float bx1, float by1) throws IOException {
            super();
            this.glyphBudget = glyphBudget;
            this.rotation = rotation;
            this.unrotatedW = unrotatedW;
            this.unrotatedH = unrotatedH;
            this.bx0 = bx0; this.by0 = by0; this.bx1 = bx1; this.by1 = by1;
        }

        /** The positions retained so far -- valid to call any time, including after {@code
         * processPage} returns normally OR throws {@link RulingOverflowException}. {@code
         * shouldSeparateByBeads} defaults to false, so the base class only ever populates a single
         * article (index 0). */
        List<TextPosition> collected() {
            return charactersByArticle.isEmpty() ? List.of() : charactersByArticle.get(0);
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            float ux = text.getXDirAdj() + text.getWidthDirAdj() / 2;
            float uy = text.getYDirAdj() - text.getHeightDir() / 2;
            float[] v = applyPageRotation(ux, uy, rotation, unrotatedW, unrotatedH);
            if (v[0] < bx0 || v[0] > bx1 || v[1] < by0 || v[1] > by1) return; // can't land in any cell
            if (!charactersByArticle.isEmpty() && charactersByArticle.get(0).size() >= glyphBudget) {
                throw new RulingOverflowException();
            }
            super.processTextPosition(text); // base class's own dedup (suppressDuplicateOverlappingText) applies
        }

        @Override
        protected void writePage() {
            // no-op -- see class doc.
        }
    }

    /**
     * Fallback when no TextPositions were collected (--skip-text-urls): a single {@link
     * PositionCollectingStripper} content-stream pass collects this page's TextPositions (bounded
     * by {@code glyphBudget}, see {@link #MAX_REGION_GLYPHS}), then {@link #fillCellsFromPositions}
     * -- the SAME midpoint-bucketing mechanism the default path uses -- buckets them into every
     * kept cell (across every qualifying table on the page), sharing one work counter (bounded by
     * {@code workBudget}, see {@link #MAX_TEXTFILL_WORK}) across all of them, exactly mirroring
     * {@link #extractLatticePage}'s own position-path loop.
     *
     * <p>Rotation correctness (e.g. {@code rotatedRuled3x3}, and plenty of real scanned/rotated
     * PDFs, where the page's /Rotate disagrees with its glyphs' own unrotated text direction) is
     * handled by {@link #fillCellsFromPositions}'/{@link #joinText}'s own line-clustering (by
     * YDirAdj/XDirAdj, independent of any stripper-level sort setting) -- the same mechanism that
     * already makes the default (positions-supplied) path rotation-correct, so nothing
     * rotation-specific is needed in {@link PositionCollectingStripper} itself beyond passing the
     * page's rotation through to the shared {@link #applyPageRotation} transform.
     *
     * <p>See {@link #MAX_REGION_GLYPHS}'s doc for the full round-6 history of why this method no
     * longer uses PDFTextStripterByArea at all (a confirmed correctness bug: a glyph inside two or
     * more overlapping/nested cell regions used to be silently dropped from all but one of them).
     */
    static void fillCellsByRegion(List<List<CellRect>> tables, PDPage page, Result result) throws IOException {
        fillCellsByRegion(tables, page, result, MAX_REGION_GLYPHS, MAX_TEXTFILL_WORK);
    }

    /** Package-private overload taking an explicit glyph-collection budget (see {@link
     * PositionCollectingStripper}) with {@link #MAX_TEXTFILL_WORK} as the (production)
     * bucketing-work budget -- mirrors {@link #fillCellsFromPositions(List, List, int, float,
     * float, long[], long)}'s test-only budget override, letting a test pin {@link
     * #MAX_REGION_GLYPHS} deterministically without needing a real multi-million-glyph PDF
     * fixture. */
    static void fillCellsByRegion(List<List<CellRect>> tables, PDPage page, Result result, long glyphBudget)
            throws IOException {
        fillCellsByRegion(tables, page, result, glyphBudget, MAX_TEXTFILL_WORK);
    }

    /** Package-private overload taking BOTH explicit budgets -- lets a test pin the
     * position-collection cap AND the bucketing-work cap ({@link #fillCellsFromPositions}'s own
     * budget) deterministically without needing a real fixture large enough to trip either at the
     * production budget. */
    static void fillCellsByRegion(List<List<CellRect>> tables, PDPage page, Result result,
                                  long glyphBudget, long workBudget) throws IOException {
        long totalCells = 0;
        for (List<CellRect> comp : tables) totalCells += comp.size();
        if (totalCells == 0) return; // nothing to fill -- avoid registering a bogus bbox below

        int rotation = page.getRotation();
        PDRectangle cropBox = page.getCropBox();
        float unrotatedW = cropBox.getWidth();
        float unrotatedH = cropBox.getHeight();

        // Combined bounding box across every kept cell, in the same visual (/Rotate-applied) space
        // fillCellsFromPositions buckets against -- see PositionCollectingStripper's doc for why
        // this is a safe (never-excludes-a-true-match) pre-filter on what it retains.
        float bx0 = Float.MAX_VALUE, by0 = Float.MAX_VALUE, bx1 = -Float.MAX_VALUE, by1 = -Float.MAX_VALUE;
        for (List<CellRect> comp : tables) {
            for (CellRect c : comp) {
                bx0 = Math.min(bx0, c.x0); by0 = Math.min(by0, c.y0);
                bx1 = Math.max(bx1, c.x1); by1 = Math.max(by1, c.y1);
            }
        }

        PositionCollectingStripper stripper = new PositionCollectingStripper(
                glyphBudget, rotation, unrotatedW, unrotatedH, bx0, by0, bx1, by1);
        if (page.hasContents()) stripper.processPage(page);

        long[] work = {0};
        List<TextPosition> positions = stripper.collected();
        for (List<CellRect> comp : tables) {
            fillCellsFromPositions(comp, positions, rotation, unrotatedW, unrotatedH, work, workBudget);
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
        try {
            extractTagged(doc, new HashSet<>(pagesToProcess), result);
        } catch (StackOverflowError e) {
            // A pathologically deep (or cyclic-looking) structure/marked-content tree can still
            // overflow inside pdfbox's own traversal even with our depth guards in place; degrade
            // to "skip tagged, lattice still runs" rather than killing the worker thread.
            System.err.println("WARNING: tagged table extraction overflowed the stack (skipped): " + e);
        } catch (RulingOverflowException e) {
            // PR re-review P2 (DoS): extractTagged's document-wide structure-traversal work
            // budget (see MAX_STRUCTURE_WORK) tripped -- a hostile structure DAG forced far more
            // total collectByType node visits than any legitimate document could. Every tagged
            // table already accepted into result.tables before the trip is kept; tagged
            // extraction simply stops here (lattice still runs for every page below).
            result.truncated = true;
            System.err.println("WARNING: tagged table extraction truncated (structure-traversal work cap): " + e);
        } catch (Exception e) {
            System.err.println("WARNING: tagged table extraction failed: " + e);
        }
        // FIX 2 (Codex P1 / ledger M-T6-2): a page carrying a tagged table used to be entirely
        // SKIPPED here (coveredByTagged), so a second, separate ruled-but-untagged table on that
        // same page was silently dropped from report.json. Lattice now runs on EVERY processed
        // page regardless of tagged coverage.
        //
        // FINAL DECISION (this fix, supersedes every suppression round below): geometric dedup
        // between the tagged and lattice paths is PROVABLY LEAKY (see the round 2-5 history still
        // documented on {@link #isLikelyDuplicateOfTaggedTable}) and risks the one outcome this
        // threat model treats as worst-case -- silently dropping a real, distinct table from
        // report.json. Suppression is REMOVED entirely: {@link #renderKeptTables} now ALWAYS
        // emits every lattice table it builds, tagged or not, overlapping or not. The same
        // per-cell footprint signal that used to gate a `continue` (skip) now only sets an
        // ADVISORY {@code likelyDuplicateOfTagged} flag on the lattice copy when it looks like the
        // same table as an already-emitted tagged one -- a hint a downstream consumer may use to
        // dedup, never a reason for this extractor to drop data. Over-flagging is a mild cosmetic
        // issue; silent-drop is now structurally impossible from this path.
        for (int pageNum : pagesToProcess) {
            if (Thread.currentThread().isInterrupted()) {
                result.truncated = true;
                break;
            }
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
                // FINAL DECISION (this fix): never drop a lattice table because it overlaps a
                // tagged one -- ALWAYS add it. Tagged extraction (see extract()) always runs to
                // completion for every page BEFORE this per-page lattice loop starts, so every
                // tagged TableHit for THIS page is already in result.tables by the time we get
                // here, making it safe to check for an advisory match against them now.
                if (isLikelyDuplicateOfTaggedTable(t, result.tables)) {
                    t.likelyDuplicateOfTagged = Boolean.TRUE;
                }
                result.tables.add(t);
            } catch (RulingOverflowException e) {
                result.truncated = true;
                System.err.println("WARNING: table render skipped on page " + pageNum + " (grid-product cap)");
            }
        }
    }

    /**
     * FIX 2 (Codex P1 / ledger M-T6-2): lattice extraction now runs on every processed page even
     * when that page also has a tagged table (see {@link #extract}'s doc), so a genuinely SEPARATE
     * ruled table is no longer silently dropped. Rounds 2-5 (below) then chased whether a table
     * that BOTH paths independently find could be safely surfaced only ONCE, by suppressing the
     * lattice copy -- that suppression is GONE as of this fix (see the FINAL DECISION paragraph
     * on {@link #extract}'s own doc): a lattice table is now NEVER dropped for overlapping a
     * tagged one. This method's boolean result no longer gates a skip; it only decides whether
     * {@link #renderKeptTables} sets the ADVISORY {@code likelyDuplicateOfTagged} flag on a
     * lattice table it is emitting UNCONDITIONALLY either way. Relies on FIX 1 (tagged and
     * lattice bboxes share one visual/rotated frame) to be valid on rotated pages, same as
     * everywhere else in this class that compares the two paths' geometry.
     *
     * <p>Rounds 2-4 (post-review, all reverted/superseded -- see git history) tried, in turn:
     * candidate-bbox-CENTROID-inside-tagged-bbox, then Intersection-over-Union of the two OUTER
     * bboxes, then IoU + a tagged/lattice cell-COUNT-ratio guard, then that plus a tagged
     * fill-ratio (cell-area-sum / own-bbox-area) plausibility gate. Every one of those is an
     * AGGREGATE comparison -- centroid, whole-bbox geometry, cell count, cell-area-sum-vs-own-bbox
     * -- and every one was eventually defeated by a differently-shaped tagged bbox that is not a
     * tight fit for its own cells: a sparse 1-cell table (round 2/3), a spread-but-real N-cell
     * table (round 4), and (round 5) a tagged table whose bbox is the union of two far-apart DENSE
     * blocks ("dense bookends, hollow middle" -- e.g. a real header block and a real footer block
     * that are legitimately one tagged table together, such as a continued-on-next-page note): its
     * fill ratio (~0.37), cell count, and IoU-via-containment ALL cleared every prior guard's
     * threshold, yet a genuinely distinct ruled table sitting in the EMPTY MIDDLE between the two
     * dense blocks overlaps NEITHER tagged block. Back when this method's result gated a skip,
     * that meant a real table was silently dropped -- the exact failure mode that motivated
     * removing suppression entirely. No aggregate statistic over the tagged table's cells could
     * ever close this: the blind spot is structural, not a wrong threshold -- none of rounds 2-4
     * ever asked WHERE the tagged content actually is relative to the candidate.
     *
     * <p>Round 5 (the signal THIS method still uses, now purely advisory): compare against the
     * tagged table's actual CELL FOOTPRINT, not any aggregate over its outer bbox. {@link
     * #taggedCellFootprintCoversCandidate} sums, over every one of the tagged table's own cell
     * rectangles, the area of that cell's intersection with the candidate lattice table's bbox,
     * and reports a likely match only when that summed overlap covers a majority of the
     * candidate's own area. This is the invariant every prior round missed: a genuinely DISTINCT
     * table shares no cell-level footprint with the tagged table it's compared against, no matter
     * how its OUTER bbox happens to relate to the tagged table's outer bbox --
     * <ul>
     *   <li>hollow-middle (round 5's own reproducer): the candidate sits in the gap between the two
     *       dense tagged blocks -- it intersects ZERO tagged cell rectangles -- overlap 0 -- kept,
     *       unflagged;</li>
     *   <li>sparse-1-cell / spread-N-cell inflated bbox (rounds 2-4's reproducers): the candidate
     *       can only overlap the tagged table's few small ACTUAL cells, never the inflated gaps
     *       between them -- overlap fraction stays low -- kept, unflagged;</li>
     *   <li>a genuine same-table match: the candidate's bbox IS the same grid the tagged cells
     *       tile, so the summed per-cell overlap covers nearly all of it -- kept AND flagged
     *       {@code likelyDuplicateOfTagged=true}, leaving the choice to dedup to the consumer.</li>
     * </ul>
     * Even if this signal is ever wrong in some future shape (over- or under-flagging), the
     * consequence is now purely cosmetic -- a flag set or unset on data that is ALWAYS present --
     * never data silently missing from report.json.
     */
    private static boolean isLikelyDuplicateOfTaggedTable(TableHit candidate, List<TableHit> tables) {
        if (candidate.bbox == null) return false;
        for (TableHit t : tables) {
            if (t.page != candidate.page) continue;
            if (!"tagged".equals(t.extractionMethod)) continue;
            if (t.bbox == null) continue;
            if (taggedCellFootprintCoversCandidate(t, candidate)) return true;
        }
        return false;
    }

    // A majority of the candidate's own area must be covered by the tagged table's REAL cells for
    // the advisory likelyDuplicateOfTagged flag to be set (this signal no longer suppresses
    // anything -- see isLikelyDuplicateOfTaggedTable's doc). A genuinely distinct table
    // (hollow-middle, sparse-inflated-bbox shapes) never approaches this -- its overlap with the
    // tagged table's actual cell footprint is near zero, regardless of how the two tables' OUTER
    // bboxes relate. A genuine same-table match has its entire bbox tiled by the tagged cells
    // (they're the same grid), so overlap approaches the candidate's full area, clearing 0.5 with
    // room to spare.
    private static final float CELL_FOOTPRINT_COVERAGE_THRESHOLD = 0.5f;

    /**
     * True when tagged table {@code t}'s own cell rectangles cover a majority of candidate lattice
     * table {@code c}'s bbox area -- the sole test for whether {@code c} should be flagged {@code
     * likelyDuplicateOfTagged} against {@code t}. This is now purely advisory (see {@link
     * #isLikelyDuplicateOfTaggedTable}'s doc for the FINAL DECISION that ended suppression); {@code
     * c} is emitted either way. Tagged grid cells never overlap each other (occupancy-map-placed,
     * one structure element per grid slot -- see {@link #buildTaggedTable}), so summing each cell's
     * OWN intersection with {@code c.bbox} is a correct (no double-count) substitute for a proper
     * union-of-rectangles computation.
     *
     * <p>Cell bboxes are the SAME rotation-aware per-cell bboxes {@link #resolveCellText} builds
     * (FIX 1), already in the visual frame this comparison operates in. A cell with a null or
     * zero-area bbox (a textless placeholder cell, or one resolved from a different page -- see
     * {@link #buildTaggedTable}'s {@code onTablePage} gate) contributes zero overlap, never a
     * divide-by-zero or a spurious match. Guards {@code c.bbox}'s own area being non-positive
     * (degenerate candidate bbox -> never eligible to be flagged, the safe direction).
     *
     * <p>Requires {@code t.cells.size() >= 2} -- discovered necessary by direct measurement, not
     * merely a leftover from the prior (round 3) cell-count guard. A tagged table with EXACTLY one
     * cell has its own bbox built (in {@link #buildTaggedTable}) as the union of that single
     * cell's own bbox alone -- meaning a 1-cell table's "cell footprint" and its "outer bbox" are
     * mathematically IDENTICAL. For round 2/3's sparse reproducer (one TD cell whose /K lists two
     * far-apart MCIDs), that single cell's own bbox is exactly the same huge, gappy rectangle its
     * outer table bbox is -- so the per-cell-footprint test, run over exactly one cell, reduces
     * right back to plain outer-bbox containment and WOULD wrongly re-flag a small distinct
     * candidate sitting inside it (verified directly: re-ran round 2/3's fixtures against the
     * cell-footprint test with no floor and confirmed the false match recurred back when this test
     * gated suppression). A table that resolves to a single cell is not a distinguishable
     * multi-cell grid at all -- there is no "footprint versus outer bbox" distinction left to check
     * for it -- so it is never eligible to flag anything under this test, independent of geometry.
     */
    private static boolean taggedCellFootprintCoversCandidate(TableHit t, TableHit c) {
        float candidateArea = bboxArea(c.bbox);
        if (candidateArea <= 0f || t.cells == null || t.cells.size() < 2) return false;
        float overlapArea = 0f;
        for (CellHit cell : t.cells) {
            if (cell.bbox == null) continue;
            overlapArea += bboxIntersectionArea(c.bbox, cell.bbox);
        }
        return (overlapArea / candidateArea) > CELL_FOOTPRINT_COVERAGE_THRESHOLD;
    }

    private static float bboxArea(float[] b) {
        if (b == null) return 0f;
        return Math.max(0f, b[2] - b[0]) * Math.max(0f, b[3] - b[1]);
    }

    /** Area of the intersection of two [x0,y0,x1,y1] bboxes; 0 when they don't overlap (or either
     * is degenerate) -- never negative. */
    private static float bboxIntersectionArea(float[] a, float[] b) {
        if (a == null || b == null) return 0f;
        float ix0 = Math.max(a[0], b[0]), iy0 = Math.max(a[1], b[1]);
        float ix1 = Math.min(a[2], b[2]), iy1 = Math.min(a[3], b[3]);
        float iw = ix1 - ix0, ih = iy1 - iy0;
        if (iw <= 0 || ih <= 0) return 0f;
        return iw * ih;
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
                                      Result result) throws IOException {
        PDStructureTreeRoot root = doc.getDocumentCatalog().getStructureTreeRoot();
        if (root == null) return;
        // PR re-review P2 (DoS): a SINGLE work counter, shared BY REFERENCE across every
        // collectByType call this extractTagged run makes (the "Table" search below AND every
        // per-table "TR" search further down) -- see MAX_STRUCTURE_WORK's doc. Never reset
        // between tables: the budget is document-wide for this one run, closing the DAG-fan-in
        // DoS where many Table elements all reference the same large shared TR subtree.
        long[] structureWork = {0};
        List<PDStructureElement> tables = new ArrayList<>();
        // No stop set here: nested Table elements must surface as their OWN entries in `tables`
        // (fix for the flattening bug below), so recursion continues past a "Table" match too.
        collectByType(root, "Table", tables, 0, Set.of(), structureWork, MAX_STRUCTURE_WORK);
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
            collectByType(tableEl, "TR", trs, 0, STOP_AT_TABLE, structureWork, MAX_STRUCTURE_WORK);
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

            // PR review P1 fix: buildTaggedTable's own grid-product guard (FIX B) rejects an
            // oversized grid by RETURNING NULL (handled by the `t == null` continue below), not by
            // throwing -- but resolveCellText/collectGlyphs/glyphsFor CAN now throw
            // RulingOverflowException (MAX_TAGGED_GLYPHS / MAX_TAGGED_WORK, see those constants'
            // docs) when this table's own cells' marked content is hostile. Isolate that failure to
            // just THIS table element -- flag truncated and move on to the next one -- mirroring
            // selectKeptTables'/renderKeptTables' own per-component isolation on the lattice path,
            // rather than letting one hostile table element's cap trip unwind this whole loop and
            // silently drop every other (possibly perfectly legitimate) tagged table already found.
            TableHit t;
            try {
                t = buildTaggedTable(trs, earlyPage, pageNum, mcidCache, pageNumbers, pagesToProcess, result);
            } catch (RulingOverflowException e) {
                result.truncated = true;
                System.err.println("WARNING: tagged table skipped on page " + pageNum
                        + " (marked-content glyph/work cap)");
                continue;
            }
            if (t == null) continue;                          // degenerate, or cap-rejected (result.truncated
                                                               // already set inside buildTaggedTable) -- lattice
                                                               // may still run for a degenerate page
            tablesPerPage.merge(pageNum, 1, Integer::sum);
            renderViews(t);
            result.tables.add(t);
        }
    }

    /** Cheap page lookup with NO content-stream access: first TD/TH (in row/cell order) whose
     * /Pg resolves via {@link #resolveElementPageWithMcrFallback}. Used both to gate a table
     * against pagesToProcess before any MCID work, and (by construction, same row/cell order as
     * buildTaggedTable) to pick the table's own page. */
    private static PDPage firstCellPage(List<PDStructureElement> trs) {
        for (PDStructureElement tr : trs) {
            for (Object kid : tr.getKids()) {
                if (kid instanceof PDStructureElement el) {
                    String st = el.getStandardStructureType();
                    if ("TD".equals(st) || "TH".equals(st)) {
                        PDPage page = resolveElementPageWithMcrFallback(el);
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

    /**
     * PR re-review P2 (recall): {@link #resolveElementPage} only consults the element's OWN /Pg
     * plus ancestor /Pg (element + TR + Table, per ISO 32000's common inheritance pattern) -- but
     * a TD/TH's page can ALSO be declared SOLELY via one of its {@link PDMarkedContentReference}
     * kids' own /Pg, with no /Pg anywhere on the element or any ancestor at all (a third, equally
     * legal way ISO 32000 lets marked content be associated with a page). {@link #collectGlyphs}
     * (further down this class) already resolves glyphs correctly through exactly this path
     * ({@code mcr.getPage()}) -- but that only runs AFTER {@link #firstCellPage}'s early, cheap
     * page-gate in {@code extractTagged}, so a table using ONLY this structure was silently
     * rejected before glyph resolution ever ran.
     *
     * <p>Falls back to a SHALLOW scan of {@code el}'s own direct kids for a {@link
     * PDMarkedContentReference} (matching exactly the depth {@link #collectGlyphs} itself uses to
     * find one -- no recursion into nested structure elements here), returning the first non-null
     * {@code mcr.getPage()} found. Never resolves glyphs itself -- this stays as cheap as the
     * ancestor walk above it (one dictionary-reference read per kid, no content-stream access, no
     * MCID resolution) -- so callers can still gate the returned page against pagesToProcess
     * BEFORE any glyph work, exactly as {@link #resolveElementPage} already requires; this must
     * not reintroduce the out-of-scope-page-walk DoS the pagesToProcess gating previously fixed.
     */
    private static PDPage resolveElementPageWithMcrFallback(PDStructureElement el) {
        PDPage page = resolveElementPage(el);
        if (page != null) return page;
        for (Object kid : el.getKids()) {
            if (kid instanceof PDMarkedContentReference mcr) {
                PDPage mcrPage = mcr.getPage();
                if (mcrPage != null) return mcrPage;
            }
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
     * entry point, not the recursive helper) so tests can drive a synthetic diamond DAG directly.
     *
     * <p>Unlimited-budget convenience overload: equivalent to calling the 7-arg overload below
     * with a fresh, throwaway work counter and {@code Long.MAX_VALUE} as the budget. Used by every
     * caller that does not need the document-wide, cross-call DoS bound (see {@link
     * #MAX_STRUCTURE_WORK}) -- i.e. everything except {@code extractTagged} itself. */
    static void collectByType(PDStructureNode node, String type,
                              List<PDStructureElement> out, int depth, Set<String> stopTypes) {
        collectByType(node, type, out, depth, stopTypes, new long[]{0}, Long.MAX_VALUE);
    }

    /**
     * PR re-review P2 (DoS): overload taking a work counter/budget threaded THROUGH to every
     * recursive call -- see {@link #MAX_STRUCTURE_WORK}'s doc. {@code extractTagged} passes the
     * SAME {@code long[] work} array (by reference, never reset) to both its top-level "Table"
     * search and every subsequent per-table "TR" search, so the cumulative node-visit cost of one
     * whole extractTagged run is bounded document-wide, not merely per-call. A FRESH
     * IDENTITY-keyed visited set is still created for THIS call (preserving FIX 1's DAG-memoization
     * semantics within one call -- a diamond fan-in within a single Table element's own subtree is
     * still visited once, not exponentially); only the work budget is shared across calls, never
     * the visited set (which would incorrectly suppress a shared subtree's SECOND, THIRD, ... Table
     * owner from ever seeing its own rows at all).
     */
    static void collectByType(PDStructureNode node, String type, List<PDStructureElement> out,
                              int depth, Set<String> stopTypes, long[] work, long budget) {
        collectByType(node, type, out, depth, stopTypes,
                Collections.newSetFromMap(new IdentityHashMap<>()), work, budget);
    }

    private static void collectByType(PDStructureNode node, String type, List<PDStructureElement> out,
                                      int depth, Set<String> stopTypes, Set<COSBase> visited,
                                      long[] work, long budget) {
        if (depth > 64) return;
        for (Object kid : node.getKids()) {
            if (out.size() > 10_000) return; // bail mid-loop once the cap is hit, not just on entry
            if (!(kid instanceof PDStructureElement el)) continue;
            // Charged BEFORE the DAG-memoization check below: memoization only dedups WITHIN this
            // one call's own visited set, so a node reached again via a DIFFERENT Table element's
            // OWN (fresh-visited-set) call must still count against the shared, document-wide
            // budget -- that repeated-across-calls cost is exactly what MAX_STRUCTURE_WORK bounds.
            if (++work[0] > budget) throw new RulingOverflowException();
            COSBase cos = el.getCOSObject();
            if (cos != null && !visited.add(cos)) continue; // DAG fan-in: already reached via another parent
            structureNodesVisited++;
            String st = el.getStandardStructureType();
            if (type.equals(st)) out.add(el);
            if (stopTypes.contains(st)) continue; // nested Table (or other stop boundary): don't descend
            collectByType(el, type, out, depth + 1, stopTypes, visited, work, budget);
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

    /** Gather the cell's MCIDs (bare integer kids and MCR kids), resolve to glyphs, join text.
     *
     * <p>FIX 1 (Codex P2): the glyph bbox union used to be built directly from {@code
     * tp.getXDirAdj()/getYDirAdj()/getWidthDirAdj()/getHeightDir()} with NO page-rotation
     * transform, while the LATTICE path (see {@link #fillCellsFromPositions}/{@link
     * RulingCollector#addRuling}) transforms both ruling and text coordinates into the visual
     * top-left frame via {@link #applyPageRotation} before ever comparing them. On a page with
     * /Rotate 90/180/270 this left the tagged bbox in a DIFFERENT coordinate frame than a lattice
     * bbox on the very same page in the same report.json. Fixed by applying the SAME {@link
     * #applyPageRotation} transform used everywhere else in this class to each glyph's own two
     * corners (unrotated) BEFORE taking the min/max union, using the cell's OWN resolved page's
     * rotation/cropBox -- correct regardless of frame, and exactly equal to the table's own page's
     * rotation/cropBox whenever {@code resolvedPage} is the table's page (the only case where this
     * bbox is actually kept -- see {@link #buildTaggedTable}'s {@code onTablePage} gate).
     */
    private static void resolveCellText(PDStructureElement el, TaggedCell cell,
                                        Map<PDPage, Map<Integer, List<TextPosition>>> mcidCache,
                                        Map<PDPage, Integer> pageNumbers,
                                        Set<Integer> pagesToProcess) throws IOException {
        // Ancestor-fallback resolution (FIX 7), PLUS the MCR-kid fallback (PR re-review P2, see
        // resolveElementPageWithMcrFallback's doc): the TD/TH itself may carry no /Pg at all when
        // it is inherited from an enclosing TR/Table, per ISO 32000, or when its page is instead
        // declared solely via one of its own PDMarkedContentReference kids. Seeding collectGlyphs
        // with this resolved page (rather than the bare el.getPage()) is what lets glyphsFor's own
        // pagesToProcess gate work correctly for such a cell, and what lets THIS cell's own
        // bbox/page (used by buildTaggedTable's onTablePage gate) resolve correctly too.
        PDPage resolvedPage = resolveElementPageWithMcrFallback(el);
        List<TextPosition> glyphs = new ArrayList<>();
        collectGlyphs(el, resolvedPage, glyphs, mcidCache, 0, pageNumbers, pagesToProcess);
        cell.text = joinText(glyphs);
        if (!glyphs.isEmpty()) {
            int rotation = 0;
            float unrotatedW = 0f, unrotatedH = 0f;
            if (resolvedPage != null) {
                rotation = resolvedPage.getRotation();
                PDRectangle cropBox = resolvedPage.getCropBox();
                unrotatedW = cropBox.getWidth();
                unrotatedH = cropBox.getHeight();
            }
            float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
            for (TextPosition tp : glyphs) {
                float ux0 = tp.getXDirAdj();
                float ux1 = tp.getXDirAdj() + tp.getWidthDirAdj();
                float uy0 = tp.getYDirAdj() - tp.getHeightDir();
                float uy1 = tp.getYDirAdj();
                float[] corner1 = applyPageRotation(ux0, uy0, rotation, unrotatedW, unrotatedH);
                float[] corner2 = applyPageRotation(ux1, uy1, rotation, unrotatedW, unrotatedH);
                x0 = Math.min(x0, Math.min(corner1[0], corner2[0]));
                x1 = Math.max(x1, Math.max(corner1[0], corner2[0]));
                y0 = Math.min(y0, Math.min(corner1[1], corner2[1]));
                y1 = Math.max(y1, Math.max(corner1[1], corner2[1]));
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

    /**
     * Budgeted subclass of {@link PDFMarkedContentExtractor} used by {@link #glyphsFor} to close
     * the PR review P1 DoS -- see {@link #MAX_TAGGED_GLYPHS}/{@link #MAX_TAGGED_WORK}'s docs for
     * the full writeup of the O(n^2) this bounds and why the fix is a work-charge rather than
     * {@code setSuppressDuplicateOverlappingText(false)}.
     *
     * <p>Overrides {@code processTextPosition(TextPosition)} -- the same low-level hook {@link
     * PositionCollectingStripper} overrides on the region path, for the identical reason: it runs
     * BEFORE any of the base class's own per-glyph bookkeeping (the expensive O(bucket) dedup
     * scan, and the {@code currentMarkedContents.peek().addText(text)} retention), so both bounds
     * below are enforced before that cost is paid for the OFFENDING glyph, not after.
     */
    private static final class BudgetedMarkedContentExtractor extends PDFMarkedContentExtractor {
        private final long glyphBudget;
        private final long workBudget;
        private long retained = 0;
        private long work = 0;
        // Per-character retained count, mirroring the base class's OWN characterListMapping
        // bucket key (text.getUnicode()) exactly -- see MAX_TAGGED_WORK's doc for why this must
        // track the REAL bucket sizes, not merely the total glyph count.
        private final Map<String, Long> charCounts = new HashMap<>();

        BudgetedMarkedContentExtractor(long glyphBudget, long workBudget) {
            this.glyphBudget = glyphBudget;
            this.workBudget = workBudget;
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            if (retained >= glyphBudget) throw new RulingOverflowException(); // MEMORY cap
            String ch = text.getUnicode();
            long bucket = charCounts.getOrDefault(ch, 0L);
            work += bucket; // cost of the O(bucket) scan super.processTextPosition is about to do
            if (work > workBudget) throw new RulingOverflowException(); // CPU cap
            charCounts.put(ch, bucket + 1);
            retained++;
            super.processTextPosition(text); // base class's own dedup (kept ON) applies from here
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
            BudgetedMarkedContentExtractor ex =
                    new BudgetedMarkedContentExtractor(MAX_TAGGED_GLYPHS, MAX_TAGGED_WORK);
            try {
                ex.processPage(page);
                for (PDMarkedContent mc : ex.getMarkedContents()) flattenMarkedContent(mc, byMcid, 0);
            } catch (RulingOverflowException e) {
                // Cache the (empty) result so a hostile page's cap trip is paid ONCE per page, not
                // once per cell/table that shares it -- every other cell/table resolving against
                // this SAME page will hit this cached empty map and just get "no glyphs found"
                // rather than re-walking (and re-failing against) the content stream. Still
                // propagate so the FIRST caller (buildTaggedTable, via extractTagged's per-table
                // catch) can flag Result.truncated for the table whose resolution triggered this.
                cache.put(page, byMcid);
                throw e;
            }
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
