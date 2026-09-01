// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/ but declares
// `package com.oai.titanarum;` -- the same convention BakeOffHarness / BaselineHarness /
// Diag9mHarness use, and for the same reason: it needs StreamTableExtractor, its Line/Word/Gutter
// types, GutterFinder, BreuelGutterFinder, TableExtractor.TableHit and TableTestPdfs.harvestGlyphs,
// all of which are package-private.
//
// PURPOSE. One question: is the prior finding "handing the pipeline ground-truth column boundaries
// LOWERS the score" a property of the PIPELINE, or a property of the INSTRUMENT that measured it?
//
// The instrument that produced it is Diag9mHarness#OracleFinder. Three defects in that instrument
// are visible by inspection and are measured below:
//
//   (1) NO PAGE. Diag9mHarness#GtBoundary carries (pdfId, tableIdx, leftCol, x0, x1) and no page
//       number, and gtBoundaries() collects every boundary of every table in a PDF into ONE flat
//       list which OracleFinder then applies to EVERY block on EVERY page. A 5-page report whose
//       table is on page 3 therefore has that table's column boundaries injected into the prose
//       blocks of pages 1, 2, 4 and 5.
//   (2) NO VERTICAL SCOPE. Even on the right page, every block whose x-band spans the boundary gets
//       it -- title blocks, paragraphs, footnotes, a second unrelated table.
//   (3) NO CROP-BOX ORIGIN SHIFT. Diag9mHarness parses the ICDAR XML's x attributes directly and
//       compares them to PDFBox stripper x, but the stripper reports x relative to the CROP BOX
//       (MetricFixHarness#regionsOf: ourX = icdarX - cropBox.lowerLeftX). On any PDF with a
//       non-zero cropBox.lowerLeftX every oracle boundary is shifted.
//
// This harness therefore builds the oracle from GroundTruth.Cell -- the SAME loader the metric
// itself uses, which already carries page and per-cell bbox -- applies MetricFixHarness's own
// coordinate convention, and scopes each boundary to its own page and its own table's y-band. It
// then reports the ceiling under the CURRENT reference protocols (POOLED and 1:1, all-pages and the
// shipping default) so the numbers are directly comparable to BaselineHarness's 0.8199 / 0.7298.
//
// Read-only: no src/main file is touched, no threshold is changed, nothing about extraction moves.
// Gated behind -Dcolora=true and named so Surefire's default includes never discover it.
//   mvn -q -o test -Dtest=ColOracleHarness -Dcolora=true
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.GtDedup;
import com.oai.titanarum.bakeoff.TableScore;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

class ColOracleHarness {

    private static void p(String s) { System.out.println(s); }
    private static void p(String fmt, Object... a) { System.out.println(String.format(Locale.ROOT, fmt, a)); }
    private static String f4(double v) { return String.format(Locale.ROOT, "%.4f", v); }

    // ------------------------------------------------------------------ ground-truth column model

    /**
     * One interior ground-truth column boundary, in OUR coordinate frame (crop-box relative,
     * top-left origin), scoped to the page and the y-band of the table it came from.
     *
     * <p>{@code x0}/{@code x1} bracket the clear span between the right ink edge of everything
     * ending in column {@code leftCol} and the left ink edge of everything starting in column
     * {@code leftCol+1}, computed over the cells of THIS table on THIS page only.
     */
    record GtCol(String pdfId, int tableIdx, int page, int leftCol, float x0, float x1,
                 float yTop, float yBot) {
        float cx() { return (x0 + x1) * 0.5f; }
    }

    /**
     * Derives one table's interior column boundaries per page from its cells' ICDAR bounding boxes,
     * using MetricFixHarness#regionsOf's coordinate convention verbatim
     * ({@code ourX = icdarX - crop.lowerLeftX}, {@code ourY = crop.upperRightY - icdarY}).
     */
    static List<GtCol> colBoundariesOf(String pdfId, int tableIdx, GroundTruth.Table t,
                                        Map<Integer, PDRectangle> cropByPage) {
        // page -> col -> extremum
        Map<Integer, Map<Integer, Float>> rightByPage = new TreeMap<>();
        Map<Integer, Map<Integer, Float>> leftByPage = new TreeMap<>();
        Map<Integer, Integer> maxColByPage = new TreeMap<>();
        Map<Integer, float[]> yByPage = new TreeMap<>();
        for (GroundTruth.Cell c : t.cells()) {
            if (!c.hasBox() || c.page() <= 0) continue;
            PDRectangle crop = cropByPage.get(c.page());
            if (crop == null) continue;
            float ourX0 = c.x1() - crop.getLowerLeftX();
            float ourX1 = c.x2() - crop.getLowerLeftX();
            float ourY0 = crop.getUpperRightY() - c.y2();
            float ourY1 = crop.getUpperRightY() - c.y1();
            if (!(ourX1 > ourX0)) continue;
            rightByPage.computeIfAbsent(c.page(), k -> new TreeMap<>())
                    .merge(c.endCol(), ourX1, Math::max);
            leftByPage.computeIfAbsent(c.page(), k -> new TreeMap<>())
                    .merge(c.startCol(), ourX0, Math::min);
            maxColByPage.merge(c.page(), c.endCol(), Math::max);
            float[] y = yByPage.get(c.page());
            if (y == null) yByPage.put(c.page(), new float[]{ourY0, ourY1});
            else { y[0] = Math.min(y[0], ourY0); y[1] = Math.max(y[1], ourY1); }
        }
        List<GtCol> out = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : maxColByPage.entrySet()) {
            int page = e.getKey();
            int maxCol = e.getValue();
            Map<Integer, Float> right = rightByPage.get(page);
            Map<Integer, Float> left = leftByPage.get(page);
            float[] y = yByPage.get(page);
            if (right == null || left == null || y == null) continue;
            for (int c = 0; c < maxCol; c++) {
                Float r = right.get(c), l = left.get(c + 1);
                if (r == null || l == null) continue;
                if (l - r < 0.5f) continue;   // GT columns physically touch: no clean span exists
                out.add(new GtCol(pdfId, tableIdx, page, c, r, l, y[0], y[1]));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------ oracle finders

    /** How a boundary is matched to a block. */
    enum Scope {
        /** Diag9mHarness's own rule: any block on any page whose x-band contains the midpoint. */
        UNSCOPED,
        /** Right page only. */
        PAGE,
        /** Right page AND the block must vertically overlap the boundary's own table band. */
        PAGE_YBAND
    }

    /**
     * What geometry the oracle hands over for a boundary it has decided applies.
     *
     * <p>This distinction turns out to matter more than the scoping does, and it is the reason a
     * "ground-truth column boundary" is not the same object as a "column gutter". The GT boundary is
     * {@code [max right ink of column c, min left ink of column c+1]} over the WHOLE table -- a
     * single wide cell anywhere in the table pushes that span narrow or off-centre. Every downstream
     * stage consumes only {@link StreamTableExtractor.Gutter#cx()}, the span's MIDPOINT, so a
     * boundary derived from mixed-alignment columns can put its midpoint inside real ink on most
     * rows -- which is not a correct column separator at all, it is a straddle generator.
     */
    enum Geom {
        /** The GT span verbatim: {@code cx} = midpoint of (max right ink, min left ink). */
        RAW,
        /** Snap to the block's OWN widest strictly-clean strip through the GT midpoint -- i.e. the
         *  geometry a perfect real finder would emit for that boundary. Falls back to RAW when the
         *  block has no clean strip there (production's 0.60 row-coverage bar). */
        SNAP_OR_RAW,
        /** As SNAP_OR_RAW, but DROP the boundary when the block has no clean strip through it. This
         *  is the strictly-implementable oracle: it proposes only boundaries some finder could. */
        SNAP_OR_DROP
    }

    /**
     * The block's widest strictly-clean x-interval containing {@code mid}: the intersection of the
     * per-row maximal empty intervals containing {@code mid}, over the rows that have one. Returns
     * null when fewer than {@link StreamTableExtractor#GUTTER_MIN_COVER_FRACTION} of the rows have
     * one, or when the intersection is narrower than production's own minimum gutter width.
     */
    static float[] cleanStripThrough(List<StreamTableExtractor.Line> lines, float mid,
                                      float bandX0, float bandX1, float medianSpace) {
        int total = lines.size();
        if (total == 0) return null;
        float lo = -Float.MAX_VALUE, hi = Float.MAX_VALUE;
        int cover = 0;
        for (StreamTableExtractor.Line l : lines) {
            float a = bandX0, bb = bandX1;
            boolean blocked = false;
            for (StreamTableExtractor.Word w : l.words) {
                if (w.x0 <= mid && w.x1 >= mid) { blocked = true; break; }
                if (w.x1 < mid) a = Math.max(a, w.x1);
                if (w.x0 > mid) bb = Math.min(bb, w.x0);
            }
            if (blocked) continue;
            cover++;
            lo = Math.max(lo, a);
            hi = Math.min(hi, bb);
        }
        if (cover < Math.ceil(StreamTableExtractor.GUTTER_MIN_COVER_FRACTION * total)) return null;
        if (hi - lo < Math.max(medianSpace, 1f)) return null;
        return new float[]{lo, hi};
    }

    /**
     * Hands the pipeline ground-truth column boundaries at a chosen scope, either REPLACING the
     * production finder's output or being UNIONED with it.
     *
     * <p>{@code page} is set by the harness immediately before each {@code extractPage} call, which
     * is the only way a GutterFinder can learn which page it is on (the interface deliberately sees
     * geometry only). Single-threaded by construction here.
     */
    static final class OracleColFinder implements GutterFinder {
        private final List<GtCol> all;
        private final Scope scope;
        private final boolean augment;
        private final Geom geom;
        private final String label;
        /**
         * IMPLEMENTABLE SELECTION. When true, the finder computes the grid BOTH ways -- production's
         * own gutters and the oracle-augmented set -- and returns whichever scores higher under
         * {@link StreamTableExtractor#scoreGrid}, preferring production on a tie. The DECISION uses
         * no ground truth, only the same confidence the emit gate already reads, so this is the
         * strongest form of "propose more candidate column models and pick the best" that could
         * actually be shipped; only the PROPOSAL is oracular. This is the arm that tells us whether a
         * decoupling fix has anything to bank.
         */
        private final boolean pickBestConfidence;
        int page = -1;

        OracleColFinder(List<GtCol> all, Scope scope, boolean augment, Geom geom,
                        boolean pickBestConfidence, String label) {
            this.all = all; this.scope = scope; this.augment = augment; this.geom = geom;
            this.pickBestConfidence = pickBestConfidence; this.label = label;
        }

        @Override public List<StreamTableExtractor.Gutter> find(
                List<StreamTableExtractor.Line> lines, float bandX0, float bandX1, float medianSpace) {
            return find(lines, bandX0, bandX1, medianSpace, new long[1]);
        }

        @Override public List<StreamTableExtractor.Gutter> find(
                List<StreamTableExtractor.Line> lines, float bandX0, float bandX1, float medianSpace,
                long[] workOut) {
            float blockTop = Float.MAX_VALUE, blockBot = -Float.MAX_VALUE;
            for (StreamTableExtractor.Line l : lines) {
                blockTop = Math.min(blockTop, l.yTop);
                blockBot = Math.max(blockBot, l.yBot);
            }
            List<StreamTableExtractor.Gutter> out = new ArrayList<>();
            if (augment) {
                out.addAll(StreamTableExtractor.findGutters(lines, bandX0, bandX1, medianSpace, workOut));
            }
            for (GtCol gc : all) {
                if (scope != Scope.UNSCOPED && gc.page() != page) continue;
                float mid = gc.cx();
                if (mid <= bandX0 + 0.5f || mid >= bandX1 - 0.5f) continue;
                if (scope == Scope.PAGE_YBAND && !yOverlaps(blockTop, blockBot, gc)) continue;
                float gx0 = gc.x0(), gx1 = gc.x1();
                if (geom != Geom.RAW) {
                    float[] strip = cleanStripThrough(lines, mid, bandX0, bandX1, medianSpace);
                    if (strip == null) {
                        if (geom == Geom.SNAP_OR_DROP) continue;
                    } else { gx0 = strip[0]; gx1 = strip[1]; }
                }
                StreamTableExtractor.Gutter g = new StreamTableExtractor.Gutter();
                g.x0 = gx0; g.x1 = gx1; g.rowsCovered = lines.size();
                out.add(g);
            }
            out.sort(Comparator.comparingDouble(g -> g.x0));
            // collapse overlaps -- two GT tables on one page can contribute crossing boundaries, and
            // in augment mode a GT boundary can coincide with a production one.
            List<StreamTableExtractor.Gutter> keep = new ArrayList<>();
            for (StreamTableExtractor.Gutter g : out) {
                if (!keep.isEmpty()) {
                    StreamTableExtractor.Gutter last = keep.get(keep.size() - 1);
                    if (g.x0 < last.x1) { last.x1 = Math.max(last.x1, g.x1); continue; }
                }
                keep.add(g);
            }
            if (!pickBestConfidence) return keep;
            // Implementable selection: score both column models with the SAME stages the emit gate
            // uses and return the better one. No ground truth is consulted by the decision.
            List<StreamTableExtractor.Gutter> prod =
                    StreamTableExtractor.findGutters(lines, bandX0, bandX1, medianSpace, workOut);
            double cProd = confOf(lines, prod, bandX0, bandX1, medianSpace);
            double cOra  = confOf(lines, keep, bandX0, bandX1, medianSpace);
            return cOra > cProd ? keep : prod;
        }

        private static double confOf(List<StreamTableExtractor.Line> lines,
                                     List<StreamTableExtractor.Gutter> g, float bandX0, float bandX1,
                                     float medianSpace) {
            if (g.isEmpty()) return -1;
            List<StreamTableExtractor.Line> trimmed =
                    StreamTableExtractor.trimEdgeLines(lines, g, bandX0, bandX1, medianSpace);
            if (trimmed.size() < 3) return -1;
            return StreamTableExtractor.scoreGrid(trimmed, g, bandX0, bandX1).confidence;
        }

        /** Does the block overlap the boundary's table band by at least half the SHORTER of the two?
         *  A prose paragraph that merely abuts the table does not; the table's own block (or a block
         *  that is the table plus a little debris) does. */
        private static boolean yOverlaps(float blockTop, float blockBot, GtCol gc) {
            float lo = Math.max(blockTop, gc.yTop()), hi = Math.min(blockBot, gc.yBot());
            float ov = hi - lo;
            if (ov <= 0) return false;
            float shorter = Math.min(blockBot - blockTop, gc.yBot() - gc.yTop());
            return shorter <= 0 || ov >= 0.5f * shorter;
        }

        @Override public String name() { return label; }
    }

    // -------------------------------------------------------------------------- protocol machinery
    //
    // Replicated from BaselineHarness (which is the pinned reference instrument and is NOT touched
    // by this file). The four functions below are byte-for-byte the same rules, so a control run of
    // this harness on the production finder must reproduce BaselineHarness's own numbers -- and the
    // report prints that control comparison explicitly.

    private static List<TableScore.Relation> rels(List<TableScore.GridCell> cells) {
        return TableScore.buildOfficialRelations(cells, false).relations();
    }

    private static List<TableScore.Relation> gtRels(GroundTruth.Table t) {
        return rels(TableScore.gridCellsFromGroundTruth(t));
    }

    private static TableScore.AdjResult cmp(List<TableScore.Relation> gt, List<TableScore.Relation> det) {
        return TableScore.compareRelations(gt, det, TableScore.Semantics.MULTISET);
    }

    private static final class Tally {
        long matched, detected, gt;
        int tables;
        void add(TableScore.AdjResult r) {
            matched += r.matched(); detected += r.detectedTotal(); gt += r.gtTotal();
        }
        double f1() {
            double p = detected == 0 ? 0 : (double) matched / detected;
            double r = gt == 0 ? 0 : (double) matched / gt;
            return matched == 0 ? 0 : 2 * p * r / (p + r);
        }
    }

    private static final class Acc {
        long matched, detected, gt;
        final List<Double> f1s = new ArrayList<>();
        void add(Tally t) {
            if (t.gt == 0 && t.detected == 0 && t.tables == 0) return;
            matched += t.matched; detected += t.detected; gt += t.gt;
            f1s.add(t.f1());
        }
        double macro() { return f1s.isEmpty() ? 0 : f1s.stream().mapToDouble(d -> d).average().orElse(0); }
        double microP() { return detected == 0 ? 0 : (double) matched / detected; }
        double microR() { return gt == 0 ? 0 : (double) matched / gt; }
        double microF1() {
            double p = microP(), r = microR();
            return (p + r) == 0 ? 0 : 2 * p * r / (p + r);
        }
        int n() { return f1s.size(); }
    }

    private static Tally e2ePooled(List<TableExtractor.TableHit> hits, List<GroundTruth.Table> exp) {
        Tally t = new Tally();
        List<TableScore.Relation> gt = new ArrayList<>();
        for (GroundTruth.Table e : exp) gt.addAll(gtRels(e));
        List<TableScore.Relation> det = new ArrayList<>();
        for (TableExtractor.TableHit h : hits) det.addAll(rels(MetricFixHarness.cellsOf(h)));
        t.add(cmp(gt, det));
        t.tables = exp.size();
        return t;
    }

    /** {@code dims[0]} += tables paired, {@code dims[1]} += those whose (rows,cols) matched exactly,
     *  {@code dims[2]} += paired tables the hit gave TOO MANY columns (split), {@code dims[3]} += too
     *  few (merged). Accumulated here rather than in a second pass so the pairing is the same one. */
    private static Tally e2ePaired(List<TableExtractor.TableHit> hits, List<GroundTruth.Table> exp,
                                    int[] dims) {
        Tally t = new Tally();
        List<TableExtractor.TableHit> avail = new ArrayList<>(hits);
        for (GroundTruth.Table e : exp) {
            List<TableScore.GridCell> gtCells = TableScore.gridCellsFromGroundTruth(e);
            if (avail.isEmpty()) {
                t.gt += TableScore.officialRelationCount(gtCells, false, TableScore.Semantics.MULTISET);
                continue;
            }
            TableExtractor.TableHit best = null;
            double bestF1 = -1;
            for (TableExtractor.TableHit h : avail) {
                double f1 = TableScore.score(e, h.rows).f1();
                if (f1 > bestF1) { bestF1 = f1; best = h; }
            }
            avail.remove(best);
            if (dims != null) {
                dims[0]++;
                if (TableScore.score(e, best.rows).dimsExactMatch()) dims[1]++;
                int gtCols = 0;
                for (List<String> r : e.rows()) gtCols = Math.max(gtCols, r.size());
                int detCols = 0;
                for (List<String> r : best.rows) detCols = Math.max(detCols, r.size());
                if (detCols > gtCols) dims[2]++; else if (detCols < gtCols) dims[3]++;
            }
            t.add(cmp(rels(gtCells), rels(MetricFixHarness.cellsOf(best))));
            t.tables++;
        }
        for (TableExtractor.TableHit h : avail) {
            t.detected += TableScore.officialRelationCount(MetricFixHarness.cellsOf(h), false,
                    TableScore.Semantics.MULTISET);
        }
        return t;
    }

    private static Tally regionPaired(List<TableExtractor.TableHit> hits, List<GroundTruth.Table> exp,
                                       Map<Integer, PDRectangle> cropByPage) {
        Tally t = new Tally();
        for (GroundTruth.Table e : exp) {
            List<MetricFixHarness.Region> regions = MetricFixHarness.regionsOf(e, cropByPage);
            if (regions.isEmpty()) continue;
            List<TableScore.GridCell> det = MetricFixHarness.regionGivenCells(hits, regions, null);
            List<TableScore.GridCell> gtCells = TableScore.gridCellsFromGroundTruth(e);
            if (det.isEmpty()) {
                t.gt += TableScore.officialRelationCount(gtCells, false, TableScore.Semantics.MULTISET);
            } else {
                t.add(cmp(rels(gtCells), rels(det)));
            }
            t.tables++;
        }
        return t;
    }

    // ------------------------------------------------------------------------------ configurations

    private static final String S_ALL = "all-pages", S_SHIP = "shipping-dflt";

    /** One measured configuration for one document: the four cells the report needs. */
    private static final class DocCell {
        Tally pooled = new Tally(), paired = new Tally(), region = new Tally();
        int hits;
        int streamHits;
        /** {paired, dimsExact, tooManyCols(split), tooFewCols(merged)} over this document. */
        final int[] dims = new int[4];
    }

    /** {@code gateOff}: emit every scored candidate regardless of the gridness gate. Measured as a
     *  MATCHED PAIR (control and oracle both gate-off) so the gate's contribution to the oracle's
     *  loss is separated from the gate's contribution to the baseline. */
    private record Cfg(String label, Scope scope, boolean augment, boolean oracle, Geom geom,
                       boolean gateOff, boolean pickBest, double flatBar) {
        Cfg(String label, Scope scope, boolean augment, boolean oracle, Geom geom, boolean gateOff,
            boolean pickBest) {
            this(label, scope, augment, oracle, geom, gateOff, pickBest, Double.NaN);
        }
    }

    private static final String CTL = "A control breuel";

    private static final List<Cfg> CFGS = List.of(
            new Cfg(CTL,                           Scope.UNSCOPED,   false, false, Geom.RAW,         false, false),
            new Cfg("B oracle REPL unscoped raw",  Scope.UNSCOPED,   false, true,  Geom.RAW,         false, false),
            new Cfg("C oracle REPL page raw",      Scope.PAGE,       false, true,  Geom.RAW,         false, false),
            new Cfg("D oracle REPL yband raw",     Scope.PAGE_YBAND, false, true,  Geom.RAW,         false, false),
            new Cfg("D2 oracle REPL yband snap",   Scope.PAGE_YBAND, false, true,  Geom.SNAP_OR_RAW, false, false),
            new Cfg("D3 oracle REPL yband snapDrp",Scope.PAGE_YBAND, false, true,  Geom.SNAP_OR_DROP,false, false),
            new Cfg("E oracle UNION yband raw",    Scope.PAGE_YBAND, true,  true,  Geom.RAW,         false, false),
            new Cfg("E2 oracle UNION yband snap",  Scope.PAGE_YBAND, true,  true,  Geom.SNAP_OR_RAW, false, false),
            new Cfg("E3 oracle UNION ybnd snapDrp",Scope.PAGE_YBAND, true,  true,  Geom.SNAP_OR_DROP,false, false),
            // THE IMPLEMENTABLE ARM: oracular proposal, production-visible per-block selection.
            new Cfg("P pickBest UNION snapDrop",   Scope.PAGE_YBAND, true,  true,  Geom.SNAP_OR_DROP,false, true),
            new Cfg("P2 pickBest UNION snap",      Scope.PAGE_YBAND, true,  true,  Geom.SNAP_OR_RAW, false, true),
            new Cfg("G control GATE-OFF",          Scope.UNSCOPED,   false, false, Geom.RAW,         true,  false),
            new Cfg("H oracle UNION snap GATE-OFF",Scope.PAGE_YBAND, true,  true,  Geom.SNAP_OR_RAW, true,  false),
            new Cfg("I oracle REPL snap GATE-OFF", Scope.PAGE_YBAND, false, true,  Geom.SNAP_OR_RAW, true,  false),
            // PLACEBO ARMS. No ground truth of any kind -- just the production finder with the emit
            // gate moved. They exist to answer the one question that decides whether the
            // per-document max-of-2 ceiling means anything: does an arm that KNOWS NOTHING about
            // columns, and differs from the control only in how many tables it emits, produce the
            // SAME apparent headroom? If it does, the headroom is a property of taking a max over two
            // configurations with anti-correlated precision/recall errors, not a property of columns.
            new Cfg("Z1 placebo gate 0.70",        Scope.UNSCOPED,   false, false, Geom.RAW,         false, false, 0.70),
            new Cfg("Z2 placebo gate 0.45",        Scope.UNSCOPED,   false, false, Geom.RAW,         false, false, 0.45));

    /**
     * The per-document oracle-selection CEILINGS, each over its own arm set. Reported as a FAMILY
     * rather than as one number, because a per-document max is fitted on the test set and inflates
     * with the number of arms: the max-of-2 rows below are the honest ones, and the max-of-7 row is
     * printed only so the size of that inflation is visible instead of hidden.
     */
    private static final Map<String, List<String>> CEILING_SETS = new LinkedHashMap<>();
    static {
        CEILING_SETS.put("max2(ctl,D REPL raw)",  List.of(CTL, "D oracle REPL yband raw"));
        CEILING_SETS.put("max2(ctl,E3 UNION)",    List.of(CTL, "E3 oracle UNION ybnd snapDrp"));
        CEILING_SETS.put("max2(ctl,P pickBest)",  List.of(CTL, "P pickBest UNION snapDrop"));
        CEILING_SETS.put("max2(ctl,Z1 PLACEBO)",  List.of(CTL, "Z1 placebo gate 0.70"));
        CEILING_SETS.put("max2(ctl,Z2 PLACEBO)",  List.of(CTL, "Z2 placebo gate 0.45"));
        CEILING_SETS.put("max3(ctl,Z1,Z2 PLACEBO)", List.of(CTL, "Z1 placebo gate 0.70",
                                                            "Z2 placebo gate 0.45"));
        CEILING_SETS.put("max3(ctl,D,E3)",        List.of(CTL, "D oracle REPL yband raw",
                                                           "E3 oracle UNION ybnd snapDrp"));
        CEILING_SETS.put("max7(all honest arms)", List.of(CTL, "D oracle REPL yband raw",
                "D2 oracle REPL yband snap", "D3 oracle REPL yband snapDrp",
                "E oracle UNION yband raw", "E2 oracle UNION yband snap",
                "E3 oracle UNION ybnd snapDrp"));
    }
    private static final List<String> CEILING_OVER = CEILING_SETS.get("max7(all honest arms)");

    // -------------------------------------------------------------------------------------- runner

    /** Per-block stage trace, for the attribution table. */
    private static final class Stage {
        int blocks, blocksGuttersChanged;
        int trimKilled, trimKilledCtl;
        int gateFail, gateFailCtl;
        int emitted, emittedCtl;
        int colsSum, colsSumCtl;
        double confSum, confSumCtl;
        int confN;
    }

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("colora"), "set -Dcolora=true to run");

        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        List<BakeOffHarness.ScoreUnit> units = corpus.units;
        p("Scoring set: %d PDFs (%d ICDAR + %d CSV)", units.size(), corpus.icdarCount, corpus.csvCount);

        // ---------------------------------------------------------- instrument-defect inventory
        p("");
        p("================================================================================");
        p("PART 0 -- INSTRUMENT DEFECT INVENTORY (why the prior oracle could not be trusted)");
        p("================================================================================");
        int pdfsWithBoxGt = 0, boundariesTotal = 0, multiPageDocs = 0, docsWithGtOnPage2Plus = 0;
        int nonZeroCropX = 0;
        long leakPairs = 0;
        Map<String, List<GtCol>> colsById = new LinkedHashMap<>();
        Map<String, Map<Integer, PDRectangle>> cropById = new LinkedHashMap<>();
        for (BakeOffHarness.ScoreUnit u : units) {
            Map<Integer, PDRectangle> crop = new LinkedHashMap<>();
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                for (int pg = 1; pg <= doc.getNumberOfPages(); pg++) {
                    PDRectangle cb = doc.getPage(pg - 1).getCropBox();
                    crop.put(pg, cb);
                    if (Math.abs(cb.getLowerLeftX()) > 0.01f) nonZeroCropX++;
                }
                if (doc.getNumberOfPages() > 1) multiPageDocs++;
            }
            cropById.put(u.id(), crop);
            List<GtCol> cols = new ArrayList<>();
            List<GroundTruth.Table> kept = GtDedup.dedup(u.expected()).kept();
            for (int i = 0; i < kept.size(); i++) {
                cols.addAll(colBoundariesOf(u.id(), i, kept.get(i), crop));
            }
            colsById.put(u.id(), cols);
            if (!cols.isEmpty()) {
                pdfsWithBoxGt++;
                boundariesTotal += cols.size();
                Set<Integer> pages = new HashSet<>();
                for (GtCol c : cols) pages.add(c.page());
                if (pages.stream().anyMatch(x -> x > 1)) docsWithGtOnPage2Plus++;
                // how many (boundary, wrong page) pairs the unscoped instrument can leak
                int docPages = crop.size();
                for (GtCol c : cols) leakPairs += (docPages - 1);
            }
        }
        p("  PDFs with per-cell bbox ground truth        : %d of %d", pdfsWithBoxGt, units.size());
        p("  interior GT column boundaries (clean span)  : %d", boundariesTotal);
        p("  multi-page documents in the scoring set     : %d", multiPageDocs);
        p("  documents with GT columns on page >= 2       : %d", docsWithGtOnPage2Plus);
        p("  pages whose cropBox.lowerLeftX != 0          : %d  (Diag9m's oracle x is unshifted)", nonZeroCropX);
        p("  DEFECT 1 -- (boundary x wrong-page) injections the unscoped oracle performs: %d", leakPairs);

        // -------------------------------------------------------- the measurement, both scopes
        Map<String, Map<String, Acc[]>> results = new LinkedHashMap<>();  // scope -> cfg -> [pool,pair,region]
        Map<String, Map<String, Acc[]>> resultsBorderless = new LinkedHashMap<>();
        Map<String, Map<String, int[]>> hitCounts = new LinkedHashMap<>(); // scope -> cfg -> [hits, streamHits]

        // which documents are the borderless-22 (no tagged and no lattice hit under all-pages)
        Set<String> borderless = new HashSet<>();

        // scope -> ceilingSetName -> [ALL-77 means, borderless means]
        Map<String, Map<String, double[][]>> ceilings = new LinkedHashMap<>();
        Map<String, Map<String, int[]>> ceilWinners = new LinkedHashMap<>();

        for (String scope : List.of(S_ALL, S_SHIP)) {
            Map<String, Acc[]> byCfg = new LinkedHashMap<>();
            Map<String, Acc[]> byCfgB = new LinkedHashMap<>();
            Map<String, int[]> hc = new LinkedHashMap<>();
            for (Cfg c : CFGS) {
                byCfg.put(c.label(), new Acc[]{new Acc(), new Acc(), new Acc()});
                byCfgB.put(c.label(), new Acc[]{new Acc(), new Acc(), new Acc()});
                hc.put(c.label(), new int[6]);
            }
            Map<String, List<double[]>> ceilPerDoc = new LinkedHashMap<>();
            Map<String, List<double[]>> ceilPerDocB = new LinkedHashMap<>();
            for (String setName : CEILING_SETS.keySet()) {
                ceilPerDoc.put(setName, new ArrayList<>());
                ceilPerDocB.put(setName, new ArrayList<>());
            }
            Map<String, int[]> winners = new TreeMap<>();
            for (String lbl : CEILING_OVER) winners.put(lbl, new int[3]);
            for (BakeOffHarness.ScoreUnit u : units) {
                Map<String, DocCell> cells = measure(u, scope, colsById.get(u.id()), cropById.get(u.id()),
                                                     borderless, scope.equals(S_ALL));
                boolean isB = borderless.contains(u.id());
                for (Cfg c : CFGS) {
                    DocCell dc = cells.get(c.label());
                    if (dc == null) continue;
                    Acc[] a = byCfg.get(c.label());
                    a[0].add(dc.pooled); a[1].add(dc.paired); a[2].add(dc.region);
                    hc.get(c.label())[0] += dc.hits;
                    hc.get(c.label())[1] += dc.streamHits;
                    for (int k = 0; k < 4; k++) hc.get(c.label())[2 + k] += dc.dims[k];
                    if (isB) {
                        Acc[] b = byCfgB.get(c.label());
                        b[0].add(dc.pooled); b[1].add(dc.paired); b[2].add(dc.region);
                    }
                }
                // per-document oracle-selection ceiling. A document only contributes to a metric's
                // ceiling if it contributed to that metric's macro average at all (same rule Acc
                // uses), so the ceiling's denominator equals the control's.
                DocCell ctl = cells.get(CTL);
                if (ctl == null) continue;
                boolean[] counts = new boolean[3];
                Tally[] ctlT = {ctl.pooled, ctl.paired, ctl.region};
                for (int m = 0; m < 3; m++) {
                    counts[m] = !(ctlT[m].gt == 0 && ctlT[m].detected == 0 && ctlT[m].tables == 0);
                }
                for (Map.Entry<String, List<String>> set : CEILING_SETS.entrySet()) {
                    double[] best = {-1, -1, -1};
                    String[] who = new String[3];
                    for (String lbl : set.getValue()) {
                        DocCell dc = cells.get(lbl);
                        if (dc == null) continue;
                        Tally[] ts = {dc.pooled, dc.paired, dc.region};
                        for (int m = 0; m < 3; m++) {
                            double f1 = ts[m].f1();
                            if (f1 > best[m] + 1e-12) { best[m] = f1; who[m] = lbl; }
                        }
                    }
                    double[] row = new double[3];
                    for (int m = 0; m < 3; m++) row[m] = counts[m] ? Math.max(0, best[m]) : Double.NaN;
                    ceilPerDoc.get(set.getKey()).add(row);
                    if (isB) ceilPerDocB.get(set.getKey()).add(row);
                    if (set.getKey().equals("max7(all honest arms)")) {
                        for (int m = 0; m < 3; m++) if (counts[m] && who[m] != null) winners.get(who[m])[m]++;
                    }
                }
            }
            results.put(scope, byCfg);
            resultsBorderless.put(scope, byCfgB);
            hitCounts.put(scope, hc);
            Map<String, double[][]> byset = new LinkedHashMap<>();
            for (String setName : CEILING_SETS.keySet()) {
                byset.put(setName, new double[][]{meanOf(ceilPerDoc.get(setName)),
                                                  meanOf(ceilPerDocB.get(setName))});
            }
            ceilings.put(scope, byset);
            ceilWinners.put(scope, winners);
        }

        p("");
        p("================================================================================");
        p("PART 1 -- THE MEASUREMENT. Full pipeline (tagged+lattice+stream, arbitrated),");
        p("          stream path's gutter finder swapped for the oracle. 77 documents.");
        p("================================================================================");
        p("  scope        config                       POOLED   1:1      region1:1  microP  microR  hits stream");
        for (String scope : List.of(S_ALL, S_SHIP)) {
            for (Cfg c : CFGS) {
                Acc[] a = results.get(scope).get(c.label());
                int[] h = hitCounts.get(scope).get(c.label());
                p("  %-12s %-28s %s  %s   %s     %s  %s  %4d %5d",
                        scope, c.label(), f4(a[0].macro()), f4(a[1].macro()), f4(a[2].macro()),
                        f4(a[0].microP()), f4(a[0].microR()), h[0], h[1]);
            }
            p("");
        }

        // THE CROSS-CHECK on the brief's "50 of 156 tables have the wrong column count, and they
        // are the expensive failures". If perfect columns really were the blocked lever, the oracle
        // must at least FIX the column count. Measured over the SAME 1:1 pairing the score uses.
        p("  DIMENSION CROSS-CHECK -- does the oracle fix the wrong-column-count class at all?");
        p("  scope        config                       paired  dimsExact  tooManyCols  tooFewCols");
        for (String scope : List.of(S_ALL, S_SHIP)) {
            for (Cfg c : CFGS) {
                int[] h = hitCounts.get(scope).get(c.label());
                p("  %-12s %-28s %5d   %4d %s   %4d         %4d", scope, c.label(), h[2], h[3],
                        h[2] == 0 ? "(-)   " : String.format(Locale.ROOT, "(%.3f)", h[3] / (double) h[2]),
                        h[4], h[5]);
            }
            p("");
        }

        p("  borderless-22 subset (the documents where the stream path is the ONLY path):");
        p("  scope        config                       POOLED   1:1      region1:1   n");
        for (String scope : List.of(S_ALL, S_SHIP)) {
            for (Cfg c : CFGS) {
                Acc[] a = resultsBorderless.get(scope).get(c.label());
                p("  %-12s %-28s %s  %s   %s      %d",
                        scope, c.label(), f4(a[0].macro()), f4(a[1].macro()), f4(a[2].macro()), a[0].n());
            }
            p("");
        }

        // -------------------------------------------------------------------- THE CEILING NUMBER
        p("");
        p("================================================================================");
        p("PART 1b -- THE ORACLE-SELECTION CEILING. Per document, the BEST of {control, every");
        p("           honest oracle variant}. This is the score an implementation that never did");
        p("           worse than production and always banked the oracle's gain would achieve --");
        p("           i.e. the upper bound on ANY column-detection improvement through this seam,");
        p("           with the downstream coupling ALREADY assumed away (the selection is free).");
        p("================================================================================");
        p("  A per-document max is FITTED ON THE TEST SET and inflates with the number of arms, so");
        p("  the max-of-2 rows are the honest ones and max7 is shown only to size that inflation.");
        p("");
        p("  scope        arm set                  subset       metric      control   CEILING   headroom");
        for (String scope : List.of(S_ALL, S_SHIP)) {
            Acc[] ctlAll = results.get(scope).get(CTL);
            Acc[] ctlB = resultsBorderless.get(scope).get(CTL);
            String[] names = {"POOLED   ", "1:1      ", "region1:1"};
            for (Map.Entry<String, double[][]> e : ceilings.get(scope).entrySet()) {
                double[][] ce = e.getValue();
                for (int m = 0; m < 3; m++) {
                    p("  %-12s %-24s %-12s %s   %s    %s    %+.4f", scope, e.getKey(), "ALL-77",
                            names[m], f4(ctlAll[m].macro()), f4(ce[0][m]),
                            ce[0][m] - ctlAll[m].macro());
                }
                for (int m = 0; m < 3; m++) {
                    p("  %-12s %-24s %-12s %s   %s    %s    %+.4f", scope, e.getKey(), "borderless22",
                            names[m], f4(ctlB[m].macro()), f4(ce[1][m]), ce[1][m] - ctlB[m].macro());
                }
                p("");
            }
        }
        p("  which configuration WINS each document under the ceiling (pooled / 1:1 / region):");
        for (String scope : List.of(S_ALL, S_SHIP)) {
            for (Map.Entry<String, int[]> e : ceilWinners.get(scope).entrySet()) {
                p("  %-12s %-30s %3d / %3d / %3d", scope, e.getKey(),
                        e.getValue()[0], e.getValue()[1], e.getValue()[2]);
            }
            p("");
        }

        // ------------------------------------------------------------------- stage attribution
        p("");
        p("================================================================================");
        p("PART 2 -- STAGE ATTRIBUTION. Every stream block, control gutters vs oracle gutters");
        p("          (scope = page+yband, REPLACE). Stages in pipeline order.");
        p("================================================================================");
        attribute(units, colsById, cropById, Geom.RAW);
        p("");
        p("  ---- same census with the oracle's geometry SNAPPED to the block's own clean strip ----");
        attribute(units, colsById, cropById, Geom.SNAP_OR_RAW);

        p("");
        p("================================================================================");
        p("PART 3 -- THE ONLY GENUINE COUPLING: blocks the oracle gives the SAME NUMBER of");
        p("          columns as production, yet whose grid confidence collapses. Every scoreGrid");
        p("          term printed, so the responsible term is named rather than guessed.");
        p("================================================================================");
        sameColCollapse(units, colsById);
    }

    /** Column-wise mean of a per-document ceiling table, skipping NaN (document not in that metric). */
    private static double[] meanOf(List<double[]> rows) {
        double[] sum = new double[3];
        int[] n = new int[3];
        for (double[] r : rows) {
            for (int m = 0; m < 3; m++) {
                if (Double.isNaN(r[m])) continue;
                sum[m] += r[m]; n[m]++;
            }
        }
        double[] out = new double[3];
        for (int m = 0; m < 3; m++) out[m] = n[m] == 0 ? 0 : sum[m] / n[m];
        return out;
    }

    // ------------------------------------------------------------------------------- measurement

    private Map<String, DocCell> measure(BakeOffHarness.ScoreUnit u, String scope, List<GtCol> cols,
                                          Map<Integer, PDRectangle> cropByPage, Set<String> borderlessOut,
                                          boolean recordBorderless) {
        Map<String, DocCell> out = new LinkedHashMap<>();
        List<GroundTruth.Table> kept = GtDedup.dedup(u.expected()).kept();
        try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
            int pages = doc.getNumberOfPages();
            List<Integer> pageList = new ArrayList<>();
            if (scope.equals(S_SHIP)) {
                pageList.addAll(shippingPages(doc));
            } else {
                for (int pg = 1; pg <= pages; pg++) pageList.add(pg);
            }
            Map<Integer, List<TextPosition>> glyphs = new LinkedHashMap<>();
            for (int pg : pageList) glyphs.put(pg, TableTestPdfs.harvestGlyphs(doc, pg - 1));

            List<TableExtractor.TableHit> taggedLattice = new ArrayList<>();
            try {
                taggedLattice.addAll(TableExtractor.extract(doc, pageList, glyphs).tables);
            } catch (Throwable ignored) { }
            if (recordBorderless && taggedLattice.isEmpty()) borderlessOut.add(u.id());

            for (Cfg c : CFGS) {
                GutterFinder finder = c.oracle()
                        ? new OracleColFinder(cols, c.scope(), c.augment(), c.geom(), c.pickBest(),
                                              "oracle")
                        : new BreuelGutterFinder();
                StreamTableExtractor.ConfidenceBar emitBar =
                        c.gateOff() ? (n -> Double.NEGATIVE_INFINITY)
                      : !Double.isNaN(c.flatBar()) ? (n -> c.flatBar())
                      : StreamTableExtractor.PRODUCTION_BAR;
                List<TableExtractor.TableHit> stream = new ArrayList<>();
                int run = 0;
                for (int pg : pageList) {
                    List<TextPosition> g = glyphs.get(pg);
                    if (g == null || g.isEmpty()) continue;
                    if (g.size() > StreamTableExtractor.MAX_STREAM_GLYPHS) continue;
                    if (run >= TableExtractor.MAX_STREAM_PAGES_PER_DOC) break;
                    run++;
                    if (finder instanceof OracleColFinder ocf) ocf.page = pg;
                    try {
                        stream.addAll(StreamTableExtractor.extractPage(pg, g, finder, emitBar,
                                StreamTableExtractor.PRODUCTION_BAR, null));
                    } catch (Throwable ignored) { }
                }
                List<TableExtractor.TableHit> merged;
                try {
                    merged = TableExtractor.arbitrate(taggedLattice, stream);
                } catch (TableExtractor.RulingOverflowException e) {
                    merged = new ArrayList<>(taggedLattice);
                    for (TableExtractor.TableHit s : stream) {
                        if (!MetricFixHarness.overlapsSubstantially(s, taggedLattice)) merged.add(s);
                    }
                }
                merged = TableExtractor.capTablesPerPage(merged, new TableExtractor.Result());

                DocCell dc = new DocCell();
                dc.hits = merged.size();
                dc.streamHits = stream.size();
                dc.pooled = e2ePooled(merged, kept);
                dc.paired = e2ePaired(merged, kept, dc.dims);
                dc.region = regionPaired(merged, kept, cropByPage);
                out.put(c.label(), dc);
            }
        } catch (Throwable t) {
            p("  ERROR %s: %s", u.id(), t);
        }
        return out;
    }

    /** Production's own default page selection, reached reflectively (same as BaselineHarness). */
    @SuppressWarnings("unchecked")
    private static List<Integer> shippingPages(PDDocument doc) throws Exception {
        java.lang.reflect.Constructor<PdfTitanArumApp> ctor =
                PdfTitanArumApp.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object app = ctor.newInstance();
        java.lang.reflect.Method compute = PdfTitanArumApp.class
                .getDeclaredMethod("computePagesToProcess", String.class, int.class);
        java.lang.reflect.Method classify = PdfTitanArumApp.class
                .getDeclaredMethod("classifyBlankPages", PDDocument.class);
        java.lang.reflect.Method fill = PdfTitanArumApp.class
                .getDeclaredMethod("fillBlankPages", List.class, Set.class, int.class);
        compute.setAccessible(true); classify.setAccessible(true); fill.setAccessible(true);
        int n = doc.getNumberOfPages();
        List<Integer> pages = (List<Integer>) compute.invoke(app, "default", n);
        Set<Integer> blank = (Set<Integer>) classify.invoke(app, doc);
        return blank.isEmpty() ? pages : (List<Integer>) fill.invoke(app, pages, blank, n);
    }

    // ------------------------------------------------------------------------------- attribution

    private void attribute(List<BakeOffHarness.ScoreUnit> units, Map<String, List<GtCol>> colsById,
                            Map<String, Map<Integer, PDRectangle>> cropById, Geom geom) throws Exception {
        Stage st = new Stage();
        Map<String, Integer> transitions = new TreeMap<>();
        List<String> worst = new ArrayList<>();
        int oracleStarved = 0;
        // hardReject census: which all-or-nothing gate zeroed a grid, control vs oracle. This is the
        // only place a stage can be "tuned to the finder's output" in a way a better finder cannot
        // route around, so it gets counted rather than inferred.
        Map<String, int[]> vetoes = new TreeMap<>();
        for (BakeOffHarness.ScoreUnit u : units) {
            List<GtCol> cols = colsById.get(u.id());
            if (cols == null || cols.isEmpty()) continue;
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                for (int pi = 0; pi < doc.getNumberOfPages(); pi++) {
                    List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, pi);
                    List<StreamTableExtractor.Word> words;
                    List<StreamTableExtractor.Line> lines;
                    try {
                        words = StreamTableExtractor.buildWords(glyphs);
                        if (words.size() < 6) continue;
                        float mfs = StreamTableExtractor.medianFontSize(words);
                        lines = StreamTableExtractor.buildLines(words, mfs);
                        if (lines.size() < 3) continue;
                    } catch (TableExtractor.RulingOverflowException e) { continue; }
                    float medianSpace = 0.5f * StreamTableExtractor.medianFontSize(words);
                    OracleColFinder oracle =
                            new OracleColFinder(cols, Scope.PAGE_YBAND, false, geom, false, "oracle");
                    oracle.page = pi + 1;
                    GutterFinder ctl = new BreuelGutterFinder();
                    // Step A blocks only (no Step A' re-merge) so the comparison is per-block and
                    // the merge stage is measured separately by the transition census below.
                    for (List<StreamTableExtractor.Line> b : StreamTableExtractor.splitIntoBlocks(lines)) {
                        if (b.size() < 3) continue;
                        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE;
                        for (StreamTableExtractor.Line l : b)
                            for (StreamTableExtractor.Word w : l.words) {
                                x0 = Math.min(x0, w.x0); x1 = Math.max(x1, w.x1);
                            }
                        List<StreamTableExtractor.Gutter> g1, g2;
                        try { g1 = ctl.find(b, x0, x1, medianSpace); }
                        catch (TableExtractor.RulingOverflowException e) { continue; }
                        g2 = oracle.find(b, x0, x1, medianSpace);
                        st.blocks++;
                        if (g1.size() != g2.size()) st.blocksGuttersChanged++;

                        String[] v1 = traceOne(b, g1, x0, x1, medianSpace);
                        String[] v2 = traceOne(b, g2, x0, x1, medianSpace);
                        st.colsSumCtl += g1.size() + 1;
                        st.colsSum += g2.size() + 1;
                        if (v1[0].equals("trimKilled")) st.trimKilledCtl++;
                        if (v2[0].equals("trimKilled")) st.trimKilled++;
                        if (v1[0].equals("gateFail")) st.gateFailCtl++;
                        if (v2[0].equals("gateFail")) st.gateFail++;
                        if (v1[0].equals("emit")) st.emittedCtl++;
                        if (v2[0].equals("emit")) st.emitted++;
                        if (g2.isEmpty() && !g1.isEmpty()) oracleStarved++;
                        StreamTableExtractor.Grid q1 = gridOf(b, g1, x0, x1, medianSpace);
                        StreamTableExtractor.Grid q2 = gridOf(b, g2, x0, x1, medianSpace);
                        if (q1 != null && q1.hardReject != null) {
                            vetoes.computeIfAbsent(q1.hardReject, k -> new int[2])[0]++;
                        }
                        if (q2 != null && q2.hardReject != null) {
                            vetoes.computeIfAbsent(q2.hardReject, k -> new int[2])[1]++;
                        }
                        if (!v1[0].equals(v2[0])) {
                            transitions.merge(v1[0] + " -> " + v2[0], 1, Integer::sum);
                            if (v1[0].equals("emit")) {
                                worst.add(String.format(Locale.ROOT,
                                        "    LOST %s p%d rows=%d cols %d->%d conf %s->%s  (%s -> %s)%s",
                                        shortId(u.id()), pi + 1, b.size(), g1.size() + 1, g2.size() + 1,
                                        v1[1], v2[1], v1[0], v2[0],
                                        g2.isEmpty() ? "  [ORACLE STARVED: no GT boundary here]" : ""));
                            }
                        }
                    }
                }
            }
        }
        p("  geometry mode: %s", geom);
        p("  stream blocks examined (>=3 lines)                       : %d", st.blocks);
        p("  blocks whose gutter COUNT changed under the oracle        : %d", st.blocksGuttersChanged);
        p("  blocks where the ORACLE HAS NOTHING but the control did   : %d  <-- oracle STARVATION,",
                oracleStarved);
        p("      not downstream coupling: a table the finder found that has no bbox ground truth,");
        p("      or whose GT column edges physically touch so no boundary could be derived.");
        p("  mean columns per block   control %.2f  oracle %.2f",
                st.blocks == 0 ? 0 : st.colsSumCtl / (double) st.blocks,
                st.blocks == 0 ? 0 : st.colsSum / (double) st.blocks);
        p("  blocks killed by trimEdgeLines (<3 lines left) ctl=%d oracle=%d", st.trimKilledCtl, st.trimKilled);
        p("  blocks refused by the gridness gate            ctl=%d oracle=%d", st.gateFailCtl, st.gateFail);
        p("  blocks that would EMIT a hit                   ctl=%d oracle=%d", st.emittedCtl, st.emitted);
        p("  scoreGrid hardReject census (control / oracle) -- the all-or-nothing gates:");
        for (Map.Entry<String, int[]> e : vetoes.entrySet()) {
            p("      %-24s ctl=%-4d oracle=%-4d", e.getKey(), e.getValue()[0], e.getValue()[1]);
        }
        p("  per-block verdict transitions (control -> oracle):");
        for (Map.Entry<String, Integer> e : transitions.entrySet()) p("      %-34s %d", e.getKey(), e.getValue());
        p("  blocks the control emitted and the oracle did NOT (first 60):");
        worst.sort(Comparator.naturalOrder());
        for (int i = 0; i < Math.min(60, worst.size()); i++) p(worst.get(i));
        p("  ... %d such blocks in total", worst.size());
    }

    /**
     * The blocks where the oracle and production agree on the COLUMN COUNT and the oracle still
     * loses. These are the only candidates for the claimed "downstream is tuned to the finder's own
     * output" mechanism -- everywhere else the two column MODELS differ, so a score difference is a
     * different table, not a coupling.
     */
    private void sameColCollapse(List<BakeOffHarness.ScoreUnit> units,
                                  Map<String, List<GtCol>> colsById) throws Exception {
        int found = 0;
        for (BakeOffHarness.ScoreUnit u : units) {
            List<GtCol> cols = colsById.get(u.id());
            if (cols == null || cols.isEmpty()) continue;
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                for (int pi = 0; pi < doc.getNumberOfPages(); pi++) {
                    List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, pi);
                    List<StreamTableExtractor.Word> words;
                    List<StreamTableExtractor.Line> lines;
                    try {
                        words = StreamTableExtractor.buildWords(glyphs);
                        if (words.size() < 6) continue;
                        float mfs = StreamTableExtractor.medianFontSize(words);
                        lines = StreamTableExtractor.buildLines(words, mfs);
                        if (lines.size() < 3) continue;
                    } catch (TableExtractor.RulingOverflowException e) { continue; }
                    float ms = 0.5f * StreamTableExtractor.medianFontSize(words);
                    OracleColFinder oracle =
                            new OracleColFinder(cols, Scope.PAGE_YBAND, false, Geom.RAW, false, "o");
                    oracle.page = pi + 1;
                    for (List<StreamTableExtractor.Line> b : StreamTableExtractor.splitIntoBlocks(lines)) {
                        if (b.size() < 3) continue;
                        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE;
                        for (StreamTableExtractor.Line l : b)
                            for (StreamTableExtractor.Word w : l.words) {
                                x0 = Math.min(x0, w.x0); x1 = Math.max(x1, w.x1);
                            }
                        List<StreamTableExtractor.Gutter> g1, g2;
                        try { g1 = new BreuelGutterFinder().find(b, x0, x1, ms); }
                        catch (TableExtractor.RulingOverflowException e) { continue; }
                        g2 = oracle.find(b, x0, x1, ms);
                        if (g1.size() != g2.size() || g1.isEmpty()) continue;
                        StreamTableExtractor.Grid a = gridOf(b, g1, x0, x1, ms);
                        StreamTableExtractor.Grid c = gridOf(b, g2, x0, x1, ms);
                        if (a == null || c == null) continue;
                        if (c.confidence >= a.confidence - 0.02) continue;
                        found++;
                        p("  %s p%d rows=%d cols=%d  conf %.3f -> %.3f", shortId(u.id()), pi + 1,
                                b.size(), g1.size() + 1, a.confidence, c.confidence);
                        p("      prod   cx=%s", cxOf(g1));
                        p("      oracle cx=%s", cxOf(g2));
                        p("      prod   nCols=%d colCons=%.3f viol=%.3f prose=%.3f colCount=%.3f "
                                        + "numeric=%.3f hardReject=%s",
                                a.nCols, a.tColConsistency, a.tViolation, a.tProse, a.tColCount,
                                a.tNumeric, a.hardReject);
                        p("      oracle nCols=%d colCons=%.3f viol=%.3f prose=%.3f colCount=%.3f "
                                        + "numeric=%.3f hardReject=%s",
                                c.nCols, c.tColConsistency, c.tViolation, c.tProse, c.tColCount,
                                c.tNumeric, c.hardReject);
                    }
                }
            }
        }
        p("  blocks in this class: %d (of 773 stream blocks examined)", found);
    }

    private static StreamTableExtractor.Grid gridOf(List<StreamTableExtractor.Line> b,
                                                     List<StreamTableExtractor.Gutter> g,
                                                     float x0, float x1, float ms) {
        List<StreamTableExtractor.Line> trimmed = StreamTableExtractor.trimEdgeLines(b, g, x0, x1, ms);
        if (trimmed.size() < 3) return null;
        return StreamTableExtractor.scoreGrid(trimmed, g, x0, x1);
    }

    private static String cxOf(List<StreamTableExtractor.Gutter> gs) {
        StringBuilder sb = new StringBuilder();
        for (StreamTableExtractor.Gutter g : gs)
            sb.append(String.format(Locale.ROOT, " %.1f[%.1f-%.1f]", g.cx(), g.x0, g.x1));
        return sb.toString().trim();
    }

    /** {verdict, confidence-string} for one (block, gutter-set) through the real emit stages. */
    private static String[] traceOne(List<StreamTableExtractor.Line> b,
                                      List<StreamTableExtractor.Gutter> g, float x0, float x1,
                                      float medianSpace) {
        int cols = g.size() + 1;
        if (cols > StreamTableExtractor.MAX_STREAM_GRID_COLS) return new String[]{"tooWide", "-"};
        List<StreamTableExtractor.Line> trimmed =
                StreamTableExtractor.trimEdgeLines(b, g, x0, x1, medianSpace);
        if (trimmed.size() < 3) return new String[]{"trimKilled", "-"};
        StreamTableExtractor.Grid grid = StreamTableExtractor.scoreGrid(trimmed, g, x0, x1);
        boolean pass = grid.confidence
                >= StreamTableExtractor.confidenceFloorFor(grid.colBounds.length - 1);
        String cs = String.format(Locale.ROOT, "%.3f", grid.confidence);
        if (!pass) return new String[]{"gateFail", cs};
        return new String[]{"emit", cs};
    }

    private static String shortId(String id) {
        int i = id.lastIndexOf('/');
        return i < 0 ? id : id.substring(i + 1);
    }
}
