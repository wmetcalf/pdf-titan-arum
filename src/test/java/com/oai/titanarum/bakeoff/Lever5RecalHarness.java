// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/ (BaselineHarness's own
// convention) but declares `package com.oai.titanarum;` because it drives package-private production
// types (TableExtractor, StreamTableExtractor, GutterFinder, ConfidenceBar) and the package-private
// test helper TableTestPdfs.harvestGlyphs.
//
// PURPOSE. RE-RUN, on the CORRECTED instrument (commit ec93b10 fed the benchmark production's own
// glyph stream), the two calibrations that were originally fitted on the OLD off-distribution glyph
// ordering:
//
//   A. the four PER-REGION ARBITRATION parameters (TableExtractor.ARB_*), and
//   B. the two-tier STREAM ADMISSION GATE (StreamTableExtractor.STREAM_CONFIDENCE_MIN,
//      WIDE_GRID_MIN_COLS, STREAM_CONFIDENCE_MIN_WIDE).
//
// WHAT THIS HARNESS ADDS OVER ArbRuleHarness / GateTierSweep, both of which already existed:
//   1. BOTH PAGE SCOPES. Every search runs twice -- once over all pages (the scope the parameters
//      were originally fitted on) and once over PRODUCTION'S OWN default page selection (first four
//      pages plus the last, blank-substituted), reached by reflection so it cannot drift. Nothing in
//      the repo checked before now that a parameter chosen on all-pages is still the winner at the
//      default a user with no --pages flag actually gets.
//   2. THE GATE IS SCORED THROUGH ARBITRATION. GateTierSweep scores the gate through the POSITIONAL
//      merge, which stopped being production when arbitration shipped. A gate threshold chosen
//      against the positional merge is chosen against a pipeline that no longer exists.
//   3. LEAVE-ONE-DOCUMENT-OUT for the gate grid too, not only for the arbitration grid.
//   4. The gate grid's PROSE FALSE-POSITIVE cost measured through the FULL pipeline over production's
//      own default page selection -- the 0.0650 watch item, not the page-1 0.0600 one.
//
// GROUND TRUTH IS NEVER READ INSIDE A CANDIDATE RULE. It scores rules and nothing else. The oracle
// rows are ceilings; they can never ship.
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.GtDedup;
import com.oai.titanarum.bakeoff.TableScore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

class Lever5RecalHarness {

    /** The same directional coverage threshold production's arbitrate() treats as "same region". */
    private static final float DROP = TableExtractor.ARB_CONTEST_COVERAGE;

    private static final String ALL = "all-pages";
    private static final String SHIP = "shipping-dflt";

    private final StringBuilder out = new StringBuilder();

    private void line(String fmt, Object... a) {
        String s = a.length == 0 ? fmt : String.format(Locale.ROOT, fmt, a);
        out.append(s).append('\n');
        System.out.println(s);
    }

    // ------------------------------------------------------------------ shipping page selection
    //
    // Production's own computePagesToProcess / classifyBlankPages / fillBlankPages, reached
    // reflectively for exactly the reason BaselineHarness gives: a transcription of "first four pages
    // plus the last" into a harness is a SECOND implementation, free to drift silently. Reflection
    // either resolves the real member or fails loudly.
    private static final class ShippingPages {
        private static final Object APP;
        private static final java.lang.reflect.Method COMPUTE, CLASSIFY, FILL;

        static {
            try {
                java.lang.reflect.Constructor<PdfTitanArumApp> ctor =
                        PdfTitanArumApp.class.getDeclaredConstructor();
                ctor.setAccessible(true);
                APP = ctor.newInstance();
                COMPUTE = PdfTitanArumApp.class
                        .getDeclaredMethod("computePagesToProcess", String.class, int.class);
                CLASSIFY = PdfTitanArumApp.class
                        .getDeclaredMethod("classifyBlankPages", PDDocument.class);
                FILL = PdfTitanArumApp.class
                        .getDeclaredMethod("fillBlankPages", List.class, Set.class, int.class);
                COMPUTE.setAccessible(true);
                CLASSIFY.setAccessible(true);
                FILL.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        @SuppressWarnings("unchecked")
        static List<Integer> select(PDDocument doc) {
            try {
                int pageCount = doc.getNumberOfPages();
                List<Integer> pages = (List<Integer>) COMPUTE.invoke(APP, "default", pageCount);
                Set<Integer> blank = (Set<Integer>) CLASSIFY.invoke(APP, doc);
                return blank.isEmpty()
                        ? pages : (List<Integer>) FILL.invoke(APP, pages, blank, pageCount);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot reach production's own page selection", e);
            }
        }
    }

    // ------------------------------------------------------------------------------ scoring plumbing
    //
    // The relation projection of a TableHit is memoised per hit IDENTITY. The searches below re-score
    // the same hit thousands of times (1080 arbitration points x 77 documents), and re-deriving its
    // relations each time is what makes the naive loop intractable. The cached value is the output of
    // the SAME TableScore/MetricFixHarness code BaselineHarness scores with -- nothing about the
    // metric changes, only how often it is recomputed.
    private final Map<TableExtractor.TableHit, List<TableScore.Relation>> relCache =
            new IdentityHashMap<>();

    private List<TableScore.Relation> relsOf(TableExtractor.TableHit h) {
        return relCache.computeIfAbsent(h,
                k -> TableScore.buildOfficialRelations(MetricFixHarness.cellsOf(k), false).relations());
    }

    private static List<TableScore.Relation> gtRels(List<TableScore.GridCell> cells) {
        return TableScore.buildOfficialRelations(cells, false).relations();
    }

    /** Document-POOLED end-to-end F1 -- the same accounting BaselineHarness#e2ePooled uses. */
    private double pooledF1(List<TableExtractor.TableHit> hits, List<TableScore.Relation> gtPooled) {
        List<TableScore.Relation> det = new ArrayList<>();
        for (TableExtractor.TableHit h : hits) det.addAll(relsOf(h));
        TableScore.AdjResult r =
                TableScore.compareRelations(gtPooled, det, TableScore.Semantics.MULTISET);
        long m = r.matched();
        if (m == 0) return 0.0;
        double p = r.detectedTotal() == 0 ? 0.0 : (double) m / r.detectedTotal();
        double rec = r.gtTotal() == 0 ? 0.0 : (double) m / r.gtTotal();
        return (p + rec) == 0 ? 0.0 : 2 * p * rec / (p + rec);
    }

    // ------------------------------------------------------------------------------- region grouping

    private static float coverage(TableExtractor.TableHit a, TableExtractor.TableHit b) {
        if (a.bbox == null || b.bbox == null || a.page != b.page) return 0f;
        float area = Math.max(0f, a.bbox[2] - a.bbox[0]) * Math.max(0f, a.bbox[3] - a.bbox[1]);
        if (area <= 0f) return 0f;
        float x0 = Math.max(a.bbox[0], b.bbox[0]), y0 = Math.max(a.bbox[1], b.bbox[1]);
        float x1 = Math.min(a.bbox[2], b.bbox[2]), y1 = Math.min(a.bbox[3], b.bbox[3]);
        if (x1 <= x0 || y1 <= y0) return 0f;
        return ((x1 - x0) * (y1 - y0)) / area;
    }

    private static boolean contests(TableExtractor.TableHit s, TableExtractor.TableHit t) {
        return coverage(s, t) > DROP || coverage(t, s) > DROP;
    }

    /** One contested region and the extraction-time signals arbitration is allowed to read. */
    private static final class Group {
        final List<TableExtractor.TableHit> lat = new ArrayList<>();
        final List<TableExtractor.TableHit> str = new ArrayList<>();
        int latRows, latCols, latCells, latSlots, strRows, strCols;
        double strConf;
        boolean anyTagged, anyNullConf;

        void computeSignals() {
            for (TableExtractor.TableHit h : lat) {
                if ("tagged".equals(h.extractionMethod)) anyTagged = true;
                latRows = Math.max(latRows, h.rowCount);
                latCols = Math.max(latCols, h.colCount);
                latSlots += Math.max(0, h.rowCount) * Math.max(0, h.colCount);
                latCells += h.cells == null ? 0 : h.cells.size();
            }
            double confSum = 0;
            for (TableExtractor.TableHit h : str) {
                strRows = Math.max(strRows, h.rowCount);
                strCols = Math.max(strCols, h.colCount);
                if (h.confidence == null) anyNullConf = true;
                else confSum += h.confidence;
            }
            strConf = str.isEmpty() ? 0.0 : confSum / str.size();
        }

        double latOccupancy() { return latSlots == 0 ? 0.0 : (double) latCells / latSlots; }
    }

    /** One region-grouping of a document's candidates: the contested regions plus everything that had
     *  no cross-path partner and is therefore emitted unconditionally. */
    private record Regions(List<Group> contested, List<TableExtractor.TableHit> uncontested) {}

    private static Regions regions(List<TableExtractor.TableHit> lat,
                                   List<TableExtractor.TableHit> str) {
        int nl = lat.size(), ns = str.size();
        boolean[][] adj = new boolean[nl][ns];
        boolean[] latHas = new boolean[nl], strHas = new boolean[ns];
        for (int i = 0; i < nl; i++) {
            for (int j = 0; j < ns; j++) {
                if (contests(str.get(j), lat.get(i))) {
                    adj[i][j] = true; latHas[i] = true; strHas[j] = true;
                }
            }
        }
        List<TableExtractor.TableHit> unc = new ArrayList<>();
        for (int i = 0; i < nl; i++) if (!latHas[i]) unc.add(lat.get(i));
        for (int j = 0; j < ns; j++) if (!strHas[j]) unc.add(str.get(j));
        boolean[] seenL = new boolean[nl], seenS = new boolean[ns];
        List<Group> gs = new ArrayList<>();
        for (int i0 = 0; i0 < nl; i0++) {
            if (!latHas[i0] || seenL[i0]) continue;
            Group g = new Group();
            List<Integer> ql = new ArrayList<>(), qs = new ArrayList<>();
            ql.add(i0); seenL[i0] = true;
            while (!ql.isEmpty() || !qs.isEmpty()) {
                if (!ql.isEmpty()) {
                    int i = ql.remove(ql.size() - 1);
                    g.lat.add(lat.get(i));
                    for (int j = 0; j < ns; j++) if (adj[i][j] && !seenS[j]) { seenS[j] = true; qs.add(j); }
                } else {
                    int j = qs.remove(qs.size() - 1);
                    g.str.add(str.get(j));
                    for (int i = 0; i < nl; i++) if (adj[i][j] && !seenL[i]) { seenL[i] = true; ql.add(i); }
                }
            }
            g.computeSignals();
            gs.add(g);
        }
        return new Regions(gs, unc);
    }

    // ------------------------------------------------------------------------- the arbitration rule
    //
    // A FAITHFUL re-statement of TableExtractor#streamWinsRegion with its four thresholds made free.
    // Faithfulness is not assumed: #run asserts that this family evaluated at the SHIPPED thresholds
    // reproduces, to 1e-9 per document, what production's own arbitrate() scores. If production's rule
    // ever changes shape, that control fails rather than this search silently measuring a fiction.

    private interface Rule { boolean streamWins(Group g); }

    private record Params(double occ, double conf, double rowRatio, double rowFloor) {
        public String toString() {
            return String.format(Locale.ROOT, "occ<%.2f conf>=%.2f rows>=x%.2f floor=%.2f",
                    occ, conf, rowRatio, rowFloor);
        }
    }

    private static Rule rule(Params p) {
        return g -> {
            if (g.str.isEmpty()) return false;
            if (g.anyTagged) return false;                       // structure tree is authoritative
            if (g.anyNullConf) return false;                     // no confidence == no evidence
            if (g.strConf < p.conf()) return false;
            if (g.latRows > 0 && g.strRows < p.rowFloor() * g.latRows) return false;
            if (g.latOccupancy() < p.occ()) return true;
            if (g.strCols > g.latCols) return true;
            return g.latRows > 0 && (double) g.strRows / g.latRows >= p.rowRatio();
        };
    }

    private static final Params SHIPPED = new Params(
            TableExtractor.ARB_MIN_GRID_OCCUPANCY, TableExtractor.ARB_MIN_STREAM_CONFIDENCE,
            TableExtractor.ARB_ROW_UNDERSEGMENTATION_RATIO, TableExtractor.ARB_MIN_ROW_COVERAGE);

    private static final double[] OCC_VALUES  = {0.60, 0.70, 0.80, 0.90, 0.95, 1.00};
    private static final double[] CONF_VALUES = {0.45, 0.55, 0.65, 0.75, 0.85};
    private static final double[] RR_VALUES   = {1.15, 1.35, 1.75, 2.50, 4.00, 99.0};
    private static final double[] FL_VALUES   = {0.00, 0.50, 0.65, 0.75, 0.90, 1.00};

    /** The grid. The shipped point is a member of it, so "the shipped point wins" is falsifiable. */
    private static List<Params> arbGrid() {
        List<Params> l = new ArrayList<>();
        for (double occ : OCC_VALUES) {
            for (double cf : CONF_VALUES) {
                for (double rr : RR_VALUES) {
                    for (double fl : FL_VALUES) l.add(new Params(occ, cf, rr, fl));
                }
            }
        }
        return l;
    }

    // -------------------------------------------------------------------------------- the gate grid

    private record Gate(int minCols, double wideBar, double narrowBar) {
        StreamTableExtractor.ConfidenceBar bar() {
            return c -> c >= minCols ? wideBar : narrowBar;
        }
        boolean isShipped() {
            return minCols == StreamTableExtractor.WIDE_GRID_MIN_COLS
                    && wideBar == StreamTableExtractor.STREAM_CONFIDENCE_MIN_WIDE
                    && narrowBar == StreamTableExtractor.STREAM_CONFIDENCE_MIN;
        }
        public String toString() {
            return minCols == Integer.MAX_VALUE
                    ? String.format(Locale.ROOT, "FLAT %.2f", narrowBar)
                    : String.format(Locale.ROOT, "narrow %.2f, cols>=%d @ %.2f",
                                    narrowBar, minCols, wideBar);
        }
    }

    private static List<Gate> gateGrid() {
        List<Gate> l = new ArrayList<>();
        for (double nb : new double[]{0.45, 0.50, 0.55, 0.60, 0.65}) {
            l.add(new Gate(Integer.MAX_VALUE, nb, nb));                  // flat: no wide tier at all
            for (int mc : new int[]{5, 6, 7, 8, 9}) {
                for (double wb : new double[]{0.30, 0.35, 0.40, 0.45}) {
                    if (wb >= nb) continue;   // a wide bar at/above the narrow one is not a wide tier
                    l.add(new Gate(mc, wb, nb));
                }
            }
        }
        return l;
    }

    // ----------------------------------------------------------------------------- per-document data

    private static final class Doc {
        String id, source, scope;
        int pageCount;
        List<Integer> pages = List.of();
        List<TableScore.Relation> gt = List.of();
        /** Region grouping at the SHIPPED gate -- what the arbitration search runs over. */
        Regions shippedRegions = new Regions(List.of(), List.of());
        /** MACRO contribution of each gate config under the SHIPPED arbitration rule. Scores only:
         *  caching every gate's candidate hits for 100 configs x 77 documents is gigabytes. */
        double[] gateScore = new double[0];
        double fProdShippedGate, fPositionalMerge, fOracle;
        int nGroupsShipped, nStreamShipped, nLat;
        String err;
    }

    /** Assemble the emitted candidate set for one rule over one region grouping, exactly as
     *  production does: contested regions resolved one way or the other, then MAX_TABLES_PER_PAGE
     *  composed over the arbitrated list (TableExtractor#extract -> #capTablesPerPage). */
    private static List<TableExtractor.TableHit> assemble(Regions rg, Rule r) {
        List<TableExtractor.TableHit> hits = new ArrayList<>(rg.uncontested());
        for (Group g : rg.contested()) {
            if (r.streamWins(g)) hits.addAll(g.str); else hits.addAll(g.lat);
        }
        return TableExtractor.capTablesPerPage(hits, new TableExtractor.Result());
    }

    private double score(Doc d, Rule r) {
        return pooledF1(assemble(d.shippedRegions, r), d.gt);
    }

    private Doc measure(BakeOffHarness.ScoreUnit unit, String scope, List<Gate> gates,
                        Gate shippedGate, GutterFinder finder) {
        Doc d = new Doc();
        d.id = unit.id();
        d.scope = scope;
        d.source = unit.id().contains("competition-dataset-us") ? "icdar-us"
                : unit.id().contains("competition-dataset-eu") ? "icdar-eu" : "csv";
        d.gateScore = new double[gates.size()];
        try (PDDocument doc = Loader.loadPDF(unit.pdf().toFile())) {
            d.pageCount = doc.getNumberOfPages();
            List<Integer> pageList = new ArrayList<>();
            if (scope.equals(SHIP)) pageList.addAll(ShippingPages.select(doc));
            else for (int p = 1; p <= d.pageCount; p++) pageList.add(p);
            d.pages = List.copyOf(pageList);

            Map<Integer, List<TextPosition>> glyphs = new LinkedHashMap<>();
            for (int p : pageList) glyphs.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));

            List<TableExtractor.TableHit> lat =
                    new ArrayList<>(TableExtractor.extract(doc, pageList, glyphs).tables);
            d.nLat = lat.size();

            List<GroundTruth.Table> raw = unit.expected();
            GtDedup.Result dd = GtDedup.dedup(raw);
            Set<Integer> removed = new HashSet<>();
            for (GtDedup.Duplicate x : dd.removed()) removed.add(x.removedIndex());
            List<TableScore.Relation> gt = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                if (removed.contains(i)) continue;
                gt.addAll(gtRels(TableScore.gridCellsFromGroundTruth(raw.get(i))));
            }
            d.gt = gt;

            Rule shippedRule = rule(SHIPPED);
            for (int g = 0; g < gates.size(); g++) {
                Gate gate = gates.get(g);
                List<TableExtractor.TableHit> str = streamFor(pageList, glyphs, gate, finder);
                Regions rg = regions(lat, str);
                d.gateScore[g] = pooledF1(assemble(rg, shippedRule), gt);
                if (gate.equals(shippedGate)) {
                    d.shippedRegions = rg;
                    d.nGroupsShipped = rg.contested().size();
                    d.nStreamShipped = str.size();

                    // PARITY CONTROL: production's own arbitrate(), not re-implemented.
                    List<TableExtractor.TableHit> prod;
                    try {
                        prod = TableExtractor.arbitrate(lat, str);
                    } catch (TableExtractor.RulingOverflowException e) {
                        prod = new ArrayList<>(lat);
                    }
                    d.fProdShippedGate = pooledF1(
                            TableExtractor.capTablesPerPage(prod, new TableExtractor.Result()), gt);

                    // The positional merge arbitration replaced -- the ceiling-capture denominator.
                    List<TableExtractor.TableHit> pos = new ArrayList<>(lat);
                    for (TableExtractor.TableHit s : str) {
                        if (!MetricFixHarness.overlapsSubstantially(s, lat)) pos.add(s);
                    }
                    d.fPositionalMerge = pooledF1(pos, gt);
                    d.fOracle = oracle(rg, gt);
                } else {
                    // Not the shipped gate: drop these hits' cached relations so the cache cannot grow
                    // to hold 100 gate configs' worth of candidates for every document.
                    for (TableExtractor.TableHit h : str) relCache.remove(h);
                }
            }
        } catch (Throwable t) {
            d.err = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return d;
    }

    private static List<TableExtractor.TableHit> streamFor(List<Integer> pageList,
                                                          Map<Integer, List<TextPosition>> glyphs,
                                                          Gate gate, GutterFinder finder) {
        // Production's DOCUMENT-level stream budget and per-page glyph pre-check, charged the way
        // TableExtractor#extractStreamPage charges them.
        List<TableExtractor.TableHit> str = new ArrayList<>();
        int streamPagesRun = 0;
        for (int p : pageList) {
            List<TextPosition> pg = glyphs.get(p);
            if (pg == null || pg.isEmpty()) continue;
            if (pg.size() > StreamTableExtractor.MAX_STREAM_GLYPHS) continue;
            if (streamPagesRun >= TableExtractor.MAX_STREAM_PAGES_PER_DOC) break;
            streamPagesRun++;
            try {
                str.addAll(StreamTableExtractor.extractPage(
                        p, pg, finder, gate.bar(), gate.bar(), null));
            } catch (Throwable ignored) {
                // extractPage's contract is that it never throws; if it ever does, that page simply
                // produced nothing rather than losing the document.
            }
        }
        return str;
    }

    /** Best achievable by choosing lattice-or-stream per contested region WITH ground truth. Ceiling
     *  only; it can never ship. Exhaustive up to 16 regions (the corpus maximum is 12). */
    private double oracle(Regions rg, List<TableScore.Relation> gt) {
        int n = rg.contested().size();
        if (n == 0) return pooledF1(assemble(rg, g -> false), gt);
        if (n > 16) return Double.NaN;
        boolean[] pick = new boolean[n];
        double best = -1;
        for (int code = 0; code < (1 << n); code++) {
            for (int i = 0; i < n; i++) pick[i] = ((code >> i) & 1) == 1;
            List<TableExtractor.TableHit> hits = new ArrayList<>(rg.uncontested());
            for (int i = 0; i < n; i++) {
                hits.addAll(pick[i] ? rg.contested().get(i).str : rg.contested().get(i).lat);
            }
            double f = pooledF1(
                    TableExtractor.capTablesPerPage(hits, new TableExtractor.Result()), gt);
            if (f > best) best = f;
        }
        return best;
    }

    // ------------------------------------------------------------------------------ LOO over a grid

    /**
     * One leave-one-document-out search.
     *
     * <p>TIES ARE REPORTED, NOT HIDDEN. A grid over four thresholds contains points that are exactly
     * equivalent on this corpus (no region falls between two neighbouring threshold values), so an
     * arbitrary "first index wins" tie-break can label the shipped point as never selected while it is
     * in fact always tied for first. Both readings are therefore reported:
     * {@code foldsPickingShipped} uses index order, {@code foldsShippedTiedBest} counts the folds in
     * which the shipped point is within {@link #TIE} of the fold winner, and {@code looShippedFirst} is
     * the held-out mean under a tie-break that prefers the shipped point. Where those two LOO numbers
     * differ, the tie is not a tie.
     */
    private static final double TIE = 1e-12;

    private record Loo(double loo, double looShippedFirst, double inSample, String bestName,
                       int bestIdx, Map<String, Integer> picks, int foldsPickingShipped,
                       int foldsShippedTiedBest, int tiedWithArgmax) {}

    private static Loo loo(int n, int m, double[][] s, List<String> names, int shippedIdx) {
        double[] total = new double[m];
        for (int r = 0; r < m; r++) for (int i = 0; i < n; i++) total[r] += s[r][i];
        double sum = 0, sumShippedFirst = 0;
        int shippedFolds = 0, shippedTied = 0;
        Map<String, Integer> picks = new LinkedHashMap<>();
        for (int held = 0; held < n; held++) {
            int best = 0;
            double bestV = -1;
            for (int r = 0; r < m; r++) {
                double v = total[r] - s[r][held];
                if (v > bestV) { bestV = v; best = r; }        // index order wins any tie
            }
            picks.merge(names.get(best), 1, Integer::sum);
            if (best == shippedIdx) shippedFolds++;
            boolean tied = (total[shippedIdx] - s[shippedIdx][held]) >= bestV - TIE;
            if (tied) shippedTied++;
            sum += s[best][held];
            sumShippedFirst += tied ? s[shippedIdx][held] : s[best][held];
        }
        int bestIdx = 0;
        for (int r = 0; r < m; r++) if (total[r] > total[bestIdx]) bestIdx = r;
        int tiedWithArgmax = 0;
        for (int r = 0; r < m; r++) if (total[bestIdx] - total[r] <= TIE * n) tiedWithArgmax++;
        return new Loo(sum / n, sumShippedFirst / n, total[bestIdx] / n, names.get(bestIdx), bestIdx,
                picks, shippedFolds, shippedTied, tiedWithArgmax);
    }

    /** Print the exact per-document relationship between two grid points -- the only way to tell a
     *  real tie (pointwise identical) from two different rules that happen to average the same. */
    private void pointwise(String label, int n, double[][] s, int a, int b,
                           List<String> names, List<Doc> docs) {
        double worst = 0, sa = 0, sb = 0;
        int differing = 0;
        String worstDoc = "(none)";
        for (int i = 0; i < n; i++) {
            sa += s[a][i];
            sb += s[b][i];
            double diff = Math.abs(s[a][i] - s[b][i]);
            if (diff > TIE) {
                differing++;
                if (diff > worst) { worst = diff; worstDoc = shortId(docs.get(i).id); }
            }
        }
        line("    %s", label);
        line("      A = %s   MACRO %.6f", names.get(a), sa / n);
        line("      B = %s   MACRO %.6f", names.get(b), sb / n);
        line("      documents whose score DIFFERS at all: %d of %d ; worst |dF1| = %.6f (%s)",
                differing, n, worst, worstDoc);
        line("      -> %s", differing == 0
                ? "POINTWISE IDENTICAL on this corpus: the two points are the same rule here"
                : "genuinely different rules that happen to average close");
    }

    private static double macro(List<Doc> docs, java.util.function.ToDoubleFunction<Doc> f) {
        return docs.stream().mapToDouble(f).average().orElse(0.0);
    }

    /** MACRO of one gate column over the subset of documents matching {@code keep}. */
    private static double subsetMacro(List<Doc> docs, int gateIdx,
                                      java.util.function.Predicate<Doc> keep) {
        double sum = 0;
        int n = 0;
        for (Doc d : docs) {
            if (!keep.test(d)) continue;
            sum += d.gateScore[gateIdx];
            n++;
        }
        return n == 0 ? Double.NaN : sum / n;
    }

    // ------------------------------------------------------------------------------------- the test

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lever5"), "set -Dlever5=true to run");
        GutterFinder finder = new BreuelGutterFinder();
        List<Gate> gates = gateGrid();
        Gate shippedGate = gates.stream().filter(Gate::isShipped).findFirst().orElseThrow(
                () -> new IllegalStateException("the shipped gate is not in the gate grid"));
        int shippedGateIdx = gates.indexOf(shippedGate);
        List<Params> params = arbGrid();
        int shippedParamIdx = params.indexOf(SHIPPED);
        if (shippedParamIdx < 0) {
            throw new IllegalStateException("the shipped arbitration point is not in the grid: "
                    + SHIPPED);
        }

        List<BakeOffHarness.ScoreUnit> units =
                BakeOffHarness.buildScoringSet(new StringBuilder()).units;

        line("================================================================================");
        line("LEVER 5 -- ARBITRATION + GATE RECALIBRATION ON THE CORRECTED INSTRUMENT");
        line("================================================================================");
        line("PRIMARY protocol throughout: document-POOLED official ICDAR-2013 adjacency relations,");
        line("de-duplicated ground truth, MACRO first, %d scoring units, end-to-end.", units.size());
        line("Arbitration grid : %d points (occ x conf x rowRatio x rowFloor); shipped point present.",
                params.size());
        line("Gate grid        : %d points (narrow bar x wide-tier column threshold x wide bar).",
                gates.size());
        line("Shipped gate        = %s", shippedGate);
        line("Shipped arbitration = %s", SHIPPED);
        line("");

        Map<String, List<Doc>> byScope = new LinkedHashMap<>();
        for (String scope : List.of(ALL, SHIP)) {
            List<Doc> docs = new ArrayList<>();
            for (BakeOffHarness.ScoreUnit u : units) {
                docs.add(measure(u, scope, gates, shippedGate, finder));
            }
            byScope.put(scope, docs);
        }

        // --------------------------------------------------------- PARITY CONTROL (must hold exactly)
        line("================================================================================");
        line("PARITY CONTROL -- the searched family AT THE SHIPPED POINT vs production itself");
        line("================================================================================");
        line("If these differ, the search is measuring a re-implementation and not production's rule.");
        boolean parityOk = true;
        for (String scope : List.of(ALL, SHIP)) {
            double worst = 0;
            String worstDoc = "(none)";
            for (Doc d : byScope.get(scope)) {
                if (d.err != null) continue;
                double diff = Math.abs(score(d, rule(SHIPPED)) - d.fProdShippedGate);
                if (diff > worst) { worst = diff; worstDoc = shortId(d.id); }
            }
            line("  %-14s max |searched family - production arbitrate()| = %.12f  %s  (worst: %s)",
                    scope, worst, worst <= 1e-9 ? "OK" : "*** MISMATCH ***", worstDoc);
            if (worst > 1e-9) parityOk = false;
        }
        for (String scope : List.of(ALL, SHIP)) {
            line("  %-14s MACRO, production arbitrate() at the shipped gate = %.4f",
                    scope, macro(byScope.get(scope), d -> d.fProdShippedGate));
        }
        if (!parityOk) line("  *** BUG *** the numbers below are NOT trustworthy");
        line("");

        // ------------------------------------------------------------------------ oracle + baselines
        line("================================================================================");
        line("CORRECTED CEILINGS AND FIXED POLICIES (shipped gate, per page scope)");
        line("================================================================================");
        line("  %-14s %10s %10s %10s %10s", "scope", "posMerge", "shipped", "ORACLE", "captured");
        for (String scope : List.of(ALL, SHIP)) {
            List<Doc> docs = byScope.get(scope);
            double pm = macro(docs, d -> d.fPositionalMerge);
            double pr = macro(docs, d -> d.fProdShippedGate);
            double or = macro(docs, d -> d.fOracle);
            line("  %-14s %10.4f %10.4f %10.4f %9.1f%%", scope, pm, pr, or,
                    100.0 * (pr - pm) / (or - pm));
        }
        line("");
        line("  ORACLE = per-contested-region choice of {lattice, stream} maximising THAT document's");
        line("  pooled F1, chosen WITH ground truth. A ceiling; it can never ship. This is the 2-WAY");
        line("  oracle -- exactly the choice arbitration makes. ArbOracleHarness reports a 3-way one");
        line("  that may also emit BOTH sides of a region, so its ceiling is >= this one.");
        line("");

        // -------------------------------------------------------------- A. arbitration recalibration
        line("================================================================================");
        line("A. ARBITRATION PARAMETER SEARCH (gate held at the shipped two-tier bar)");
        line("================================================================================");
        Map<String, Loo> arbLoo = new LinkedHashMap<>();
        for (String scope : List.of(ALL, SHIP)) {
            List<Doc> docs = byScope.get(scope);
            int n = docs.size(), m = params.size();
            double[][] s = new double[m][n];
            List<String> names = new ArrayList<>();
            for (int r = 0; r < m; r++) {
                Rule rl = rule(params.get(r));
                names.add(params.get(r).toString());
                for (int i = 0; i < n; i++) s[r][i] = score(docs.get(i), rl);
            }
            Loo lo = loo(n, m, s, names, shippedParamIdx);
            arbLoo.put(scope, lo);
            double shippedIn = 0;
            for (int i = 0; i < n; i++) shippedIn += s[shippedParamIdx][i];
            shippedIn /= n;
            line("  scope=%s", scope);
            line("    LOO (HEADLINE)                     : %.4f", lo.loo());
            line("    LOO, shipped-preferring tie-break  : %.4f  (%s)", lo.looShippedFirst(),
                    Math.abs(lo.loo() - lo.looShippedFirst()) <= 1e-9
                            ? "identical -- the tie-break does not move it"
                            : "*** DIFFERS from the headline LOO ***");
            line("    in-sample argmax                   : %.6f  at  %s", lo.inSample(), lo.bestName());
            line("    shipped point, in-sample           : %.6f", shippedIn);
            line("    grid points TIED with the argmax   : %d of %d", lo.tiedWithArgmax(), m);
            line("    shipped point is the argmax?       : %s",
                    lo.bestIdx() == shippedParamIdx ? "YES (strict)"
                            : Math.abs(lo.inSample() - shippedIn) <= TIE
                              ? "TIED for argmax (index-order tie-break named another point)"
                              : "NO (argmax is " + lo.bestName() + ", +"
                                + String.format(Locale.ROOT, "%.6f", lo.inSample() - shippedIn) + ")");
            line("    shipped point selected in          : %d of %d LOO folds (index-order tie-break)",
                    lo.foldsPickingShipped(), n);
            line("    shipped point TIED for best in     : %d of %d LOO folds",
                    lo.foldsShippedTiedBest(), n);
            line("    fold picks                         : %s", lo.picks());
            if (lo.bestIdx() != shippedParamIdx) {
                pointwise("SHIPPED vs the named in-sample argmax:", n, s, lo.bestIdx(),
                        shippedParamIdx, names, docs);
            }
            double[] tot = new double[m];
            double best = -1;
            for (int r = 0; r < m; r++) {
                for (int i = 0; i < n; i++) tot[r] += s[r][i];
                tot[r] /= n;
                if (tot[r] > best) best = tot[r];
            }
            int w10 = 0, w50 = 0;
            for (int r = 0; r < m; r++) {
                if (best - tot[r] <= 0.0010) w10++;
                if (best - tot[r] <= 0.0050) w50++;
            }
            line("    plateau                            : %d/%d points within 0.0010 of the argmax,"
                    + " %d/%d within 0.0050", w10, m, w50, m);
            line("    shipped point is %.4f below the in-sample argmax", best - tot[shippedParamIdx]);
            line("    ONE-AT-A-TIME sweeps through the shipped point (in-sample MACRO):");
            sweep(docs, "occ", OCC_VALUES,
                    v -> new Params(v, SHIPPED.conf(), SHIPPED.rowRatio(), SHIPPED.rowFloor()));
            sweep(docs, "conf", CONF_VALUES,
                    v -> new Params(SHIPPED.occ(), v, SHIPPED.rowRatio(), SHIPPED.rowFloor()));
            sweep(docs, "rowRatio", RR_VALUES,
                    v -> new Params(SHIPPED.occ(), SHIPPED.conf(), v, SHIPPED.rowFloor()));
            sweep(docs, "rowFloor", FL_VALUES,
                    v -> new Params(SHIPPED.occ(), SHIPPED.conf(), SHIPPED.rowRatio(), v));
            line("");
        }
        line("  CROSS-SCOPE CHECK -- does the all-pages winner survive the shipping default?");
        {
            Loo a = arbLoo.get(ALL), b = arbLoo.get(SHIP);
            line("    all-pages in-sample argmax : %s", a.bestName());
            line("    shipping  in-sample argmax : %s", b.bestName());
            line("    same point? %s", a.bestName().equals(b.bestName()) ? "YES" : "NO");
        }
        line("");

        // ------------------------------------------------------------------- B. gate recalibration
        line("================================================================================");
        line("B. GATE SEARCH, SCORED THROUGH PRODUCTION ARBITRATION (not the positional merge)");
        line("================================================================================");
        Map<String, Loo> gateLoo = new LinkedHashMap<>();
        Map<String, double[]> gateMacro = new LinkedHashMap<>();
        for (String scope : List.of(ALL, SHIP)) {
            List<Doc> docs = byScope.get(scope);
            int n = docs.size(), m = gates.size();
            double[][] s = new double[m][n];
            List<String> names = new ArrayList<>();
            for (int r = 0; r < m; r++) {
                names.add(gates.get(r).toString());
                for (int i = 0; i < n; i++) s[r][i] = docs.get(i).gateScore[r];
            }
            Loo lo = loo(n, m, s, names, shippedGateIdx);
            gateLoo.put(scope, lo);
            double[] tot = new double[m];
            for (int r = 0; r < m; r++) {
                for (int i = 0; i < n; i++) tot[r] += s[r][i];
                tot[r] /= n;
            }
            gateMacro.put(scope, tot);
            line("  scope=%s", scope);
            line("    LOO (HEADLINE)                     : %.4f", lo.loo());
            line("    LOO, shipped-preferring tie-break  : %.4f", lo.looShippedFirst());
            line("    in-sample argmax                   : %.6f  at  %s", lo.inSample(), lo.bestName());
            line("    shipped gate, in-sample            : %.6f", tot[shippedGateIdx]);
            line("    grid points TIED with the argmax   : %d of %d", lo.tiedWithArgmax(), m);
            line("    shipped gate is the argmax?        : %s",
                    lo.bestIdx() == shippedGateIdx ? "YES (strict)" : "NO");
            line("    shipped gate selected in           : %d of %d LOO folds (index-order tie-break)",
                    lo.foldsPickingShipped(), n);
            line("    shipped gate TIED for best in      : %d of %d LOO folds",
                    lo.foldsShippedTiedBest(), n);
            line("    fold picks                         : %s", lo.picks());
            // The neighbour that matters: same wide tier, narrow bar one step UP. It scores the same
            // MACRO to four decimals on this corpus and rejects more prose, so whether it is a real
            // Pareto improvement or an averaging coincidence has to be settled pointwise.
            int nb060 = gates.indexOf(new Gate(StreamTableExtractor.WIDE_GRID_MIN_COLS,
                    StreamTableExtractor.STREAM_CONFIDENCE_MIN_WIDE, 0.60));
            if (nb060 >= 0) {
                pointwise("SHIPPED gate vs narrow-bar-0.60 neighbour:", n, s, shippedGateIdx, nb060,
                        names, docs);
            }
            line("");
        }
        line("  CROSS-SCOPE CHECK -- does the all-pages gate winner survive the shipping default?");
        line("    all-pages in-sample argmax : %s", gateLoo.get(ALL).bestName());
        line("    shipping  in-sample argmax : %s", gateLoo.get(SHIP).bestName());
        line("    same gate? %s", gateLoo.get(ALL).bestName().equals(gateLoo.get(SHIP).bestName())
                ? "YES" : "NO");
        line("");
        line("  FULL GATE GRID, both scopes (MACRO through arbitration; * = the shipped gate)");
        line("  %-30s %12s %12s", "gate", ALL, SHIP);
        for (int r = 0; r < gates.size(); r++) {
            line("  %-30s %12.4f %12.4f %s", gates.get(r),
                    gateMacro.get(ALL)[r], gateMacro.get(SHIP)[r],
                    gates.get(r).isShipped() ? "*" : "");
        }
        line("");
        line("  SUBSET BREAKDOWN for the candidate gates (MACRO, 6 dp so a tie is visible as a tie)");
        {
            List<Gate> ofInterest = new ArrayList<>();
            ofInterest.add(shippedGate);
            for (double nb : new double[]{0.60, 0.65}) {
                int i = gates.indexOf(new Gate(StreamTableExtractor.WIDE_GRID_MIN_COLS,
                        StreamTableExtractor.STREAM_CONFIDENCE_MIN_WIDE, nb));
                if (i >= 0) ofInterest.add(gates.get(i));
            }
            line("  %-30s %-14s %10s %10s %10s %10s", "gate", "scope", "ALL-77", "icdar-EU",
                    "icdar-US", "borderless");
            for (Gate g : ofInterest) {
                int r = gates.indexOf(g);
                for (String scope : List.of(ALL, SHIP)) {
                    List<Doc> docs = byScope.get(scope);
                    line("  %-30s %-14s %10.6f %10.6f %10.6f %10.6f %s", g, scope,
                            subsetMacro(docs, r, d -> true),
                            subsetMacro(docs, r, d -> "icdar-eu".equals(d.source)),
                            subsetMacro(docs, r, d -> "icdar-us".equals(d.source)),
                            subsetMacro(docs, r, d -> d.nLat == 0),
                            g.isShipped() ? "*" : "");
                }
            }
        }
        line("");

        // ------------------------------------------------------- gate cost: prose false positives
        line("================================================================================");
        line("C. PROSE FALSE-POSITIVE COST OF EACH GATE -- FULL PIPELINE, SHIPPING PAGE SELECTION");
        line("================================================================================");
        proseFp(gates, finder);

        line("");
        for (String scope : List.of(ALL, SHIP)) {
            List<Doc> bad = byScope.get(scope).stream().filter(d -> d.err != null).toList();
            line("documents with a measurement error (%s): %d", scope, bad.size());
            for (Doc d : bad) line("    %s : %s", d.id, d.err);
        }
        line("");
        line("CONTESTED-REGION INVENTORY at the shipped gate");
        for (String scope : List.of(ALL, SHIP)) {
            int docsWith = 0, total = 0;
            for (Doc d : byScope.get(scope)) {
                if (d.nGroupsShipped > 0) docsWith++;
                total += d.nGroupsShipped;
            }
            line("  %-14s documents with >=1 contested region: %d/%d ; contested regions: %d",
                    scope, docsWith, byScope.get(scope).size(), total);
        }

        Path p = Path.of(System.getProperty("lever5Out", "target/lever5-recal-report.md"));
        Files.createDirectories(p.toAbsolutePath().getParent());
        Files.writeString(p, "```\n" + out + "```\n");
        System.out.println("Report written to " + p.toAbsolutePath());
    }

    private void sweep(List<Doc> docs, String label, double[] values,
                       java.util.function.DoubleFunction<Params> mk) {
        StringBuilder b = new StringBuilder();
        for (double v : values) {
            Rule r = rule(mk.apply(v));
            double m = 0;
            for (Doc d : docs) m += score(d, r);
            b.append(String.format(Locale.ROOT, "  %s=%.2f:%.4f", label, v, m / docs.size()));
        }
        line("     %s", b.toString().trim());
    }

    /**
     * Prose false-positive rate per gate config, measured the way the watch item is defined: the FULL
     * pipeline (tagged+lattice+arbitrated stream) over PRODUCTION'S OWN default page selection, a
     * document counting as a false positive when any selected page emits >=1 table.
     */
    private void proseFp(List<Gate> gates, GutterFinder finder) throws IOException {
        Path root = Path.of("/home/coz/Downloads/phishpdfs");
        List<Path> prose = pdfs(root, Integer.getInteger("proseCap", 100000));
        if (prose.isEmpty()) {
            line("  prose corpus unavailable -- prose false-positive rate NOT measured this run.");
            return;
        }
        // The TRACKED watch item (0.0650) is measured on BakeOffHarness's own 200-PDF STRIDED sample,
        // not the first 200 files. Both are reported: the tracked subsample so the number is
        // comparable to the one in the brief, and the whole 1,599-PDF population because 200 is a
        // sample of it and a 6-document difference between two gates is inside 200's noise.
        List<Path> tracked = BakeOffHarness.sampleProsePdfs();
        Set<Path> trackedSet = tracked == null ? Set.of() : new HashSet<>(tracked);
        int[] fpAll = new int[gates.size()];
        int[] fp200 = new int[gates.size()];
        int latAll = 0, lat200 = 0, pagesScanned = 0, failed = 0, n200 = 0;
        for (Path p : prose) {
            boolean inTracked = trackedSet.contains(p);
            if (inTracked) n200++;
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                List<Integer> pageList = ShippingPages.select(doc);
                Map<Integer, List<TextPosition>> glyphs = new LinkedHashMap<>();
                for (int q : pageList) glyphs.put(q, TableTestPdfs.harvestGlyphs(doc, q - 1));
                pagesScanned += pageList.size();
                List<TableExtractor.TableHit> lat = new ArrayList<>();
                try {
                    lat.addAll(TableExtractor.extract(doc, pageList, glyphs).tables);
                } catch (Throwable ignored) { }
                if (!lat.isEmpty()) { latAll++; if (inTracked) lat200++; }
                for (int g = 0; g < gates.size(); g++) {
                    List<TableExtractor.TableHit> str =
                            streamFor(pageList, glyphs, gates.get(g), finder);
                    List<TableExtractor.TableHit> merged;
                    try {
                        merged = TableExtractor.arbitrate(lat, str);
                    } catch (TableExtractor.RulingOverflowException e) {
                        merged = new ArrayList<>(lat);
                    }
                    merged = TableExtractor.capTablesPerPage(merged, new TableExtractor.Result());
                    if (!merged.isEmpty()) { fpAll[g]++; if (inTracked) fp200[g]++; }
                }
            } catch (Throwable t) {
                failed++;
            }
        }
        line("  population: %d PDFs (%%PDF magic) under %s, %d pages under production's own default",
                prose.size(), root, pagesScanned);
        line("  tracked subsample (BakeOffHarness.sampleProsePdfs, strided): %d PDFs", n200);
        line("  %d PDFs could not be measured (load failure)", failed);
        line("  flag OFF (tagged+lattice only)  : %d/%d = %.4f  (tracked-200: %d/%d = %.4f)",
                latAll, prose.size(), (double) latAll / prose.size(),
                lat200, n200, n200 == 0 ? Double.NaN : (double) lat200 / n200);
        line("  %-30s %10s %8s %10s %8s", "gate", "FP/1599", "rate", "FP/200", "rate");
        for (int g = 0; g < gates.size(); g++) {
            line("  %-30s %5d/%-4d %8.4f %5d/%-4d %8.4f %s", gates.get(g),
                    fpAll[g], prose.size(), (double) fpAll[g] / prose.size(),
                    fp200[g], n200, n200 == 0 ? Double.NaN : (double) fp200[g] / n200,
                    gates.get(g).isShipped() ? "*" : "");
        }
    }

    private static List<Path> pdfs(Path root, int cap) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        List<Path> all;
        try (java.util.stream.Stream<Path> s = Files.list(root)) {
            all = s.filter(Files::isRegularFile).sorted().toList();
        }
        List<Path> outp = new ArrayList<>();
        for (Path p : all) {
            if (outp.size() >= cap) break;
            try (InputStream in = Files.newInputStream(p)) {
                byte[] b = new byte[5];
                int n = in.read(b);
                if (n >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') outp.add(p);
            } catch (IOException ignored) { }
        }
        return outp;
    }

    private static String shortId(String id) {
        int i = id.lastIndexOf('/');
        return i < 0 ? id : id.substring(i + 1);
    }
}
