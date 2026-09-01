// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/ but declares
// `package com.oai.titanarum;` for the same reason BaselineHarness does -- it drives
// package-private production types.
//
// PURPOSE: search the SIGNALS-ONLY rule space for per-region path arbitration and report how much
// of ArbOracleHarness's oracle ceiling each rule captures, under the PRIMARY protocol (POOLED,
// dedup GT, MACRO, 77 units, end-to-end). Every rule here reads ONLY extraction-time signals
// (grid occupancy, cell counts, row/column counts, fragment counts, stream confidence). Ground
// truth is used ONLY to SCORE a rule, never inside one.
//
// ANTI-OVERFIT: every parameterised family is also reported under a 2-fold split (fit the threshold
// on one half of the corpus, score the other half, both directions, sum the halves back into one
// MACRO). A rule whose split-half number collapses toward the baseline is fitting 98 regions, not
// learning a signal.
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.GtDedup;
import com.oai.titanarum.bakeoff.TableScore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

class ArbRuleHarness {

    private static final float DROP = 0.5f;

    private final StringBuilder out = new StringBuilder();

    private void line(String fmt, Object... a) {
        String s = a.length == 0 ? fmt : String.format(fmt, a);
        out.append(s).append('\n');
        System.out.println(s);
    }

    // ---------------------------------------------------------------------------- scoring plumbing

    private static List<TableScore.Relation> rels(List<TableScore.GridCell> cells) {
        return TableScore.buildOfficialRelations(cells, false).relations();
    }

    private static double pooledF1(List<TableExtractor.TableHit> hits,
                                   List<TableScore.Relation> gtPooled) {
        List<TableScore.Relation> det = new ArrayList<>();
        for (TableExtractor.TableHit h : hits) det.addAll(rels(MetricFixHarness.cellsOf(h)));
        TableScore.AdjResult r =
                TableScore.compareRelations(gtPooled, det, TableScore.Semantics.MULTISET);
        long m = r.matched();
        if (m == 0) return 0.0;
        double p = r.detectedTotal() == 0 ? 0.0 : (double) m / r.detectedTotal();
        double rec = r.gtTotal() == 0 ? 0.0 : (double) m / r.gtTotal();
        return (p + rec) == 0 ? 0.0 : 2 * p * rec / (p + rec);
    }

    // ------------------------------------------------------------------------------- geometry glue

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

    private static final class Group {
        final List<TableExtractor.TableHit> lat = new ArrayList<>();
        final List<TableExtractor.TableHit> str = new ArrayList<>();
        // signals, all extraction-time
        int latRows, latCols, latCells, latSlots, latFilled, latFrag;
        int strRows, strCols, strCells, strFilled, strFrag;
        double strConf;
        /** Fraction of this region's glyphs that fall inside SOME lattice cell rectangle. A drawn
         *  grid that does not account for the region's text leaves this low. */
        double latGlyphCoverage = 1.0;
        /** Fraction of the contested region's bbox area covered by lattice cell rectangles. */
        double latAreaCoverage = 1.0;
        double strGlyphCoverage = 1.0;

        void computeSignals() {
            for (TableExtractor.TableHit h : lat) {
                latFrag++;
                latRows = Math.max(latRows, h.rowCount);
                latCols = Math.max(latCols, h.colCount);
                latSlots += h.rowCount * h.colCount;
                if (h.cells != null) {
                    latCells += h.cells.size();
                    for (TableExtractor.CellHit c : h.cells) {
                        if (c.text != null && !c.text.isBlank()) latFilled++;
                    }
                }
            }
            double confSum = 0;
            for (TableExtractor.TableHit h : str) {
                strFrag++;
                strRows = Math.max(strRows, h.rowCount);
                strCols = Math.max(strCols, h.colCount);
                if (h.cells != null) {
                    strCells += h.cells.size();
                    for (TableExtractor.CellHit c : h.cells) {
                        if (c.text != null && !c.text.isBlank()) strFilled++;
                    }
                }
                confSum += h.confidence == null ? 0.0 : h.confidence;
            }
            strConf = str.isEmpty() ? 0.0 : confSum / str.size();
        }

        /** Fraction of the lattice grid's row x column slots that actually carry a cell. A drawn grid
         *  that is only partially ruled leaves most slots empty. */
        double latOccupancy() { return latSlots == 0 ? 0.0 : (double) latCells / latSlots; }
        /** Fraction of the lattice cells that carry text. */
        double latFillRate() { return latCells == 0 ? 0.0 : (double) latFilled / latCells; }
        double rowRatio() { return latRows == 0 ? 1.0 : (double) strRows / latRows; }

        /** Union bbox of every candidate in the region, in the shared visual frame. */
        float[] unionBbox() {
            float[] b = null;
            List<TableExtractor.TableHit> all = new ArrayList<>(lat);
            all.addAll(str);
            for (TableExtractor.TableHit h : all) {
                if (h.bbox == null) continue;
                if (b == null) b = h.bbox.clone();
                else {
                    b[0] = Math.min(b[0], h.bbox[0]); b[1] = Math.min(b[1], h.bbox[1]);
                    b[2] = Math.max(b[2], h.bbox[2]); b[3] = Math.max(b[3], h.bbox[3]);
                }
            }
            return b;
        }

        int page() { return lat.isEmpty() ? (str.isEmpty() ? -1 : str.get(0).page) : lat.get(0).page; }

        /** Glyph- and area-coverage signals for the lattice/stream candidates over this region. */
        void computeCoverage(Map<Integer, List<TextPosition>> glyphsByPage,
                             Map<Integer, Float> pageHeights) {
            float[] b = unionBbox();
            if (b == null) return;
            // area coverage: lattice cell rects vs the region bbox
            double regionArea = Math.max(1e-6, (b[2] - b[0]) * (double) (b[3] - b[1]));
            double covered = 0;
            for (TableExtractor.TableHit h : lat) {
                if (h.cells == null) continue;
                for (TableExtractor.CellHit c : h.cells) {
                    if (c.bbox == null) continue;
                    double x0 = Math.max(b[0], c.bbox[0]), y0 = Math.max(b[1], c.bbox[1]);
                    double x1 = Math.min(b[2], c.bbox[2]), y1 = Math.min(b[3], c.bbox[3]);
                    if (x1 > x0 && y1 > y0) covered += (x1 - x0) * (y1 - y0);
                }
            }
            latAreaCoverage = Math.min(1.0, covered / regionArea);

            List<TextPosition> gs = glyphsByPage.get(page());
            if (gs == null || gs.isEmpty()) return;
            Float ph = pageHeights.get(page());
            int inRegion = 0, inLat = 0, inStr = 0;
            for (TextPosition tp : gs) {
                float cx = tp.getXDirAdj() + tp.getWidthDirAdj() / 2f;
                // getYDirAdj() is the TOP of the glyph in a top-left-origin frame (same convention
                // StreamTableExtractor uses at its line 55), and TableHit bboxes are in that frame.
                float cy = tp.getYDirAdj() + Math.max(1f, tp.getHeightDir()) / 2f;
                if (cx < b[0] || cx > b[2] || cy < b[1] || cy > b[3]) continue;
                inRegion++;
                if (insideAnyCell(lat, cx, cy)) inLat++;
                if (insideAnyCell(str, cx, cy)) inStr++;
            }
            if (inRegion > 0) {
                latGlyphCoverage = (double) inLat / inRegion;
                strGlyphCoverage = (double) inStr / inRegion;
            }
            if (ph == null) return;   // unused; kept so the signature documents the frame assumption
        }

        private static boolean insideAnyCell(List<TableExtractor.TableHit> hs, float cx, float cy) {
            for (TableExtractor.TableHit h : hs) {
                if (h.cells == null) continue;
                for (TableExtractor.CellHit c : h.cells) {
                    if (c.bbox == null) continue;
                    if (cx >= c.bbox[0] && cx <= c.bbox[2] && cy >= c.bbox[1] && cy <= c.bbox[3]) return true;
                }
            }
            return false;
        }
    }

    private static List<Group> groups(List<TableExtractor.TableHit> lat,
                                      List<TableExtractor.TableHit> str,
                                      List<TableExtractor.TableHit> uncontested) {
        int nl = lat.size(), ns = str.size();
        boolean[][] adj = new boolean[nl][ns];
        boolean[] latHas = new boolean[nl], strHas = new boolean[ns];
        for (int i = 0; i < nl; i++) {
            for (int j = 0; j < ns; j++) {
                if (contests(str.get(j), lat.get(i))) { adj[i][j] = true; latHas[i] = true; strHas[j] = true; }
            }
        }
        for (int i = 0; i < nl; i++) if (!latHas[i]) uncontested.add(lat.get(i));
        for (int j = 0; j < ns; j++) if (!strHas[j]) uncontested.add(str.get(j));
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
        return gs;
    }

    /** Diagnostic dump of one region's signals, for the design write-up. */
    private static String sig(Group g) {
        return String.format("occ=%.2f fill=%.2f rows=%d/%d cols=%d/%d frag=%d/%d conf=%.3f "
                        + "latGlyph=%.2f strGlyph=%.2f latArea=%.2f",
                g.latOccupancy(), g.latFillRate(), g.latRows, g.strRows, g.latCols, g.strCols,
                g.latFrag, g.strFrag, g.strConf, g.latGlyphCoverage, g.strGlyphCoverage,
                g.latAreaCoverage);
    }

    // ---------------------------------------------------------------------------------- rule space

    /** 0 = keep lattice/tagged, 1 = keep stream, 2 = keep both. */
    private interface Rule extends ToIntFunction<Group> {}

    private static final Rule R_BASELINE = g -> 0;
    private static final Rule R_STREAMPREF = g -> 1;
    private static final Rule R_BOTH = g -> 2;

    /** Occupancy-only family: stream wins when the drawn grid is only partially ruled. */
    private static Rule occRule(double theta) {
        return g -> g.latOccupancy() < theta ? 1 : 0;
    }

    /** Occupancy + confidence floor. */
    private static Rule occConfRule(double theta, double confMin) {
        return g -> (g.latOccupancy() < theta && g.strConf >= confMin) ? 1 : 0;
    }

    /** GLYPH-COVERAGE family: stream wins when the drawn grid's cells do not account for the
     *  region's text. Directly encodes "text falls outside the detected grid". */
    private static Rule glyphRule(double theta) {
        return g -> g.latGlyphCoverage < theta ? 1 : 0;
    }

    private static Rule glyphConfRule(double theta, double confMin) {
        return g -> (g.latGlyphCoverage < theta && g.strConf >= confMin) ? 1 : 0;
    }

    /** Glyph coverage COMPARED between the two candidates: stream wins when it explains
     *  materially more of the region's text than the rulings do. */
    private static Rule glyphDeltaRule(double margin) {
        return g -> (g.strGlyphCoverage - g.latGlyphCoverage) > margin ? 1 : 0;
    }

    private static Rule areaRule(double theta) {
        return g -> g.latAreaCoverage < theta ? 1 : 0;
    }

    /**
     * The composite family plus a ROW-COVERAGE FLOOR: a stream candidate that resolves materially
     * FEWER rows than the rulings did is reading only part of the region, and must not be allowed to
     * replace the whole of it (that would lose content, the outcome this project treats as worst).
     * {@code rowFloor} is the fraction of the ruled row count the stream candidate must reach.
     */
    private static Rule composite2(double theta, double confMin, double rowRatio,
                                   boolean useCols, double rowFloor) {
        return g -> {
            if (g.strConf < confMin) return 0;
            if (g.latRows > 0 && g.strRows < rowFloor * g.latRows) return 0;
            boolean partial = g.latOccupancy() < theta;
            boolean colGain = useCols && g.strCols > g.latCols;
            boolean rowGain = g.rowRatio() >= rowRatio;
            return (partial || colGain || rowGain) ? 1 : 0;
        };
    }

    private static List<Named> composite2Grid() {
        List<Named> l = new ArrayList<>();
        for (double th : new double[]{0.60, 0.70, 0.80, 0.90, 0.95, 1.00}) {
            for (double cf : new double[]{0.55, 0.65, 0.75, 0.85}) {
                for (double rr : new double[]{1.15, 1.35, 1.75, 2.50, 99.0}) {
                    for (double fl : new double[]{0.0, 0.50, 0.75, 0.90, 1.00}) {
                        l.add(new Named("c2 t=" + th + " c=" + cf + " r=" + rr + " fl=" + fl,
                                composite2(th, cf, rr, true, fl)));
                    }
                }
            }
        }
        return l;
    }

    /**
     * The composite family. Stream wins a contested region when it is confident AND the lattice
     * candidate shows a concrete structural defect:
     *   (a) its drawn grid is only partially ruled (occupancy below theta), or
     *   (b) stream resolves strictly more COLUMNS than the rulings did while agreeing on rows
     *       (a ruling set that missed a column separator), or
     *   (c) stream resolves rowRatio x more ROWS in the same region (rulings under-segment rows).
     */
    private static Rule composite(double theta, double confMin, double rowRatio, boolean useCols) {
        return g -> {
            if (g.strConf < confMin) return 0;
            boolean partial = g.latOccupancy() < theta;
            boolean colGain = useCols && g.strCols > g.latCols && g.strRows <= g.latRows;
            boolean rowGain = g.rowRatio() >= rowRatio;
            return (partial || colGain || rowGain) ? 1 : 0;
        };
    }

    // ----------------------------------------------------------------------------- per-document data

    private static final class Doc {
        String id, source;
        List<TableExtractor.TableHit> lat = List.of(), str = List.of(), uncontested = List.of();
        List<Group> gs = List.of();
        List<TableScore.Relation> gt = List.of();
        double fBaselineMerge;
        int maxRuledPerPage, maxStreamPerPage, pages;
        double arbMicros;
        String err;

        double score(Rule r) {
            List<TableExtractor.TableHit> hits = new ArrayList<>(uncontested);
            for (Group g : gs) {
                int c = r.applyAsInt(g);
                if (c == 0 || c == 2) hits.addAll(g.lat);
                if (c == 1 || c == 2) hits.addAll(g.str);
            }
            return pooledF1(hits, gt);
        }
    }

    private Doc measure(BakeOffHarness.ScoreUnit unit, GutterFinder finder) {
        Doc d = new Doc();
        d.id = unit.id();
        d.source = unit.id().contains("competition-dataset-us") ? "icdar-us"
                : unit.id().contains("competition-dataset-eu") ? "icdar-eu" : "csv";
        try (PDDocument doc = Loader.loadPDF(unit.pdf().toFile())) {
            int pages = doc.getNumberOfPages();
            List<Integer> pageList = new ArrayList<>();
            Map<Integer, List<TextPosition>> glyphs = new LinkedHashMap<>();
            for (int p = 1; p <= pages; p++) {
                pageList.add(p);
                glyphs.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
            }
            d.lat = new ArrayList<>(TableExtractor.extract(doc, pageList, glyphs).tables);
            List<TableExtractor.TableHit> str = new ArrayList<>();
            for (int p : pageList) str.addAll(StreamTableExtractor.extractPage(p, glyphs.get(p), finder));
            d.str = str;

            List<GroundTruth.Table> raw = unit.expected();
            GtDedup.Result dd = GtDedup.dedup(raw);
            Set<Integer> removed = new HashSet<>();
            for (GtDedup.Duplicate x : dd.removed()) removed.add(x.removedIndex());
            List<TableScore.Relation> gt = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                if (removed.contains(i)) continue;
                gt.addAll(rels(TableScore.gridCellsFromGroundTruth(raw.get(i))));
            }
            d.gt = gt;

            List<TableExtractor.TableHit> unc = new ArrayList<>();
            d.gs = groups(d.lat, d.str, unc);
            d.uncontested = unc;
            Map<Integer, Float> heights = new LinkedHashMap<>();
            for (int p : pageList) heights.put(p, doc.getPage(p - 1).getCropBox().getHeight());
            for (Group g : d.gs) g.computeCoverage(glyphs, heights);

            // baseline merge, verbatim (drop stream when >50% covered by a tagged/lattice hit)
            List<TableExtractor.TableHit> full = new ArrayList<>(d.lat);
            for (TableExtractor.TableHit s : d.str) {
                boolean drop = false;
                for (TableExtractor.TableHit t : d.lat) if (coverage(s, t) > DROP) { drop = true; break; }
                if (!drop) full.add(s);
            }
            d.fBaselineMerge = pooledF1(full, gt);

            // ---- DoS evidence: real per-page candidate density, and arbitration's own cost ----
            d.pages = pages;
            Map<Integer, Integer> ruledPerPage = new LinkedHashMap<>();
            Map<Integer, Integer> streamPerPage = new LinkedHashMap<>();
            for (TableExtractor.TableHit h : d.lat) ruledPerPage.merge(h.page, 1, Integer::sum);
            for (TableExtractor.TableHit h : d.str) streamPerPage.merge(h.page, 1, Integer::sum);
            for (int v : ruledPerPage.values()) d.maxRuledPerPage = Math.max(d.maxRuledPerPage, v);
            for (int v : streamPerPage.values()) d.maxStreamPerPage = Math.max(d.maxStreamPerPage, v);
            TableExtractor.arbitrate(d.lat, d.str);              // warm
            long t0 = System.nanoTime();
            for (int rep = 0; rep < 20; rep++) TableExtractor.arbitrate(d.lat, d.str);
            d.arbMicros = (System.nanoTime() - t0) / 20_000.0;
        } catch (Throwable t) {
            d.err = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        return d;
    }

    private static double macro(List<Doc> docs, Rule r) {
        double s = 0;
        for (Doc d : docs) s += d.score(r);
        return docs.isEmpty() ? 0 : s / docs.size();
    }

    // ------------------------------------------------------------------------------------- the test

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("arbRule"), "set -DarbRule=true to run");
        GutterFinder finder = new BreuelGutterFinder();
        List<BakeOffHarness.ScoreUnit> units =
                BakeOffHarness.buildScoringSet(new StringBuilder()).units;
        List<Doc> docs = new ArrayList<>();
        for (BakeOffHarness.ScoreUnit u : units) docs.add(measure(u, finder));

        double baseMerge = docs.stream().mapToDouble(d -> d.fBaselineMerge).average().orElse(0);
        line("================================================================================");
        line("SIGNALS-ONLY ARBITRATION RULES -- PRIMARY protocol (POOLED, dedup GT, MACRO, %d docs)",
                docs.size());
        line("================================================================================");
        line("  sanity: baseline merge recomputed here = %.4f (must equal BaselineHarness full)", baseMerge);
        line("  sanity: rule 'keep lattice in every contested region' = %.4f", macro(docs, R_BASELINE));
        line("");
        line("  %-46s %8s %8s %8s %8s", "rule", "MACRO", "d(base)", "EU", "US");
        List<Doc> eu = docs.stream().filter(d -> "icdar-eu".equals(d.source)).toList();
        List<Doc> us = docs.stream().filter(d -> "icdar-us".equals(d.source)).toList();
        report("lattice-preferred (= today)", R_BASELINE, docs, eu, us, baseMerge);
        report("stream-preferred (no signals)", R_STREAMPREF, docs, eu, us, baseMerge);
        report("emit both", R_BOTH, docs, eu, us, baseMerge);
        line("");
        for (double th : new double[]{0.30, 0.40, 0.50, 0.60, 0.70, 0.80, 0.90, 0.95, 1.00}) {
            report(String.format("occupancy < %.2f -> stream", th), occRule(th), docs, eu, us, baseMerge);
        }
        line("");
        for (double th : new double[]{0.50, 0.70, 0.90, 1.00}) {
            for (double cf : new double[]{0.55, 0.65, 0.75, 0.85}) {
                report(String.format("occ < %.2f AND conf >= %.2f -> stream", th, cf),
                        occConfRule(th, cf), docs, eu, us, baseMerge);
            }
        }
        line("");
        for (double cf : new double[]{0.55, 0.65, 0.75, 0.85}) {
            for (double rr : new double[]{1.15, 1.35, 1.75, 2.50, 99.0}) {
                report(String.format("composite th=0.90 conf>=%.2f rowRatio>=%.2f +cols", cf, rr),
                        composite(0.90, cf, rr, true), docs, eu, us, baseMerge);
            }
        }
        line("");
        for (double cf : new double[]{0.65, 0.75}) {
            for (double rr : new double[]{1.35, 1.75, 99.0}) {
                report(String.format("composite th=0.90 conf>=%.2f rowRatio>=%.2f NOcols", cf, rr),
                        composite(0.90, cf, rr, false), docs, eu, us, baseMerge);
            }
        }

        line("");
        for (double th : new double[]{0.50, 0.70, 0.80, 0.90, 0.95, 0.99, 1.00}) {
            report(String.format("latGlyphCoverage < %.2f -> stream", th), glyphRule(th), docs, eu, us, baseMerge);
        }
        line("");
        for (double th : new double[]{0.80, 0.90, 0.95, 0.99}) {
            for (double cf : new double[]{0.55, 0.65, 0.75}) {
                report(String.format("latGlyph < %.2f AND conf >= %.2f -> stream", th, cf),
                        glyphConfRule(th, cf), docs, eu, us, baseMerge);
            }
        }
        line("");
        for (double m : new double[]{0.00, 0.02, 0.05, 0.10, 0.20, 0.35}) {
            report(String.format("strGlyph - latGlyph > %.2f -> stream", m), glyphDeltaRule(m), docs, eu, us, baseMerge);
        }
        line("");
        for (double th : new double[]{0.50, 0.70, 0.85, 0.95, 1.00}) {
            report(String.format("latAreaCoverage < %.2f -> stream", th), areaRule(th), docs, eu, us, baseMerge);
        }

        // ---- ANTI-OVERFIT: 2-fold, fit theta on one half, score the other ----
        line("");
        line("ANTI-OVERFIT 2-FOLD (fit the family's threshold on half the corpus by MACRO, score the");
        line("OTHER half with it; the reported number is the mean of both held-out halves' per-doc F1)");
        double[] occThetas = {0.30, 0.40, 0.50, 0.60, 0.70, 0.80, 0.90, 0.95, 1.00};
        line("  %-46s %8s", "family", "held-out");
        line("  %-46s %8.4f", "occupancy family (1 param)",
                twoFold(docs, occThetas, ArbRuleHarness::occRule));
        double[] confs = {0.55, 0.65, 0.75, 0.85};
        line("  %-46s %8.4f", "composite family (conf param, th=.90 rr=1.75 +cols)",
                twoFold(docs, confs, c -> composite(0.90, c, 1.75, true)));
        line("  %-46s %8.4f", "stream-preferred (0 params -> no fitting possible)",
                macro(docs, R_STREAMPREF));
        line("  %-46s %8.4f", "lattice-preferred (0 params, = today)", macro(docs, R_BASELINE));

        // ---- ANTI-OVERFIT: LEAVE-ONE-DOCUMENT-OUT over the WHOLE grid of a family ----
        line("");
        line("LEAVE-ONE-DOCUMENT-OUT (for each document, the family's parameters are fitted by MACRO");
        line("on the other %d documents and the held-out document is then scored with them -- so no", docs.size() - 1);
        line("document's own ground truth can influence the parameters it is judged by. This is the");
        line("honest generalisation estimate for a fitted rule; compare it to the 0-parameter rows.)");
        line("  %-46s %8s %8s", "family (grid searched)", "LOO", "in-sample");
        looReport(docs, "occupancy (9 thresholds)", occGrid());
        looReport(docs, "latGlyphCoverage (7 thresholds)", glyphGrid());
        looReport(docs, "latGlyph x conf (12)", glyphConfGrid());
        looReport(docs, "occ x conf (16)", occConfGrid());
        looReport(docs, "composite occ/conf/rowRatio/cols (40)", compositeGrid());
        looReport(docs, "composite, occ threshold ALSO searched (240)", compositeWideGrid());
        looReport(docs, "composite + row-coverage floor (600)", composite2Grid());
        looReport(docs, "ALL of the above pooled", allGrid());

        // ---- the FROZEN PRODUCTION rule, called directly, per-document win/loss ----
        line("");
        line("FROZEN PRODUCTION RULE (TableExtractor.arbitrate called directly, not re-implemented):");
        line("  conf >= %.2f AND streamRows >= %.2f x ruledRows AND (occupancy < %.2f"
                        + " OR extra column OR rows x%.2f); tagged never overridden",
                TableExtractor.ARB_MIN_STREAM_CONFIDENCE, TableExtractor.ARB_MIN_ROW_COVERAGE,
                TableExtractor.ARB_MIN_GRID_OCCUPANCY,
                TableExtractor.ARB_ROW_UNDERSEGMENTATION_RATIO);
        int improved = 0, regressed = 0, same = 0;
        double prodSum = 0;
        List<String> regressions = new ArrayList<>();
        List<String> gains = new ArrayList<>();
        for (Doc d : docs) {
            double f;
            try {
                f = pooledF1(TableExtractor.arbitrate(d.lat, d.str), d.gt);
            } catch (TableExtractor.RulingOverflowException e) {
                f = d.fBaselineMerge;
            }
            prodSum += f;
            double delta = f - d.fBaselineMerge;
            if (delta > 1e-9) {
                improved++;
                gains.add(String.format("%s %+.4f (%.4f -> %.4f)", shortId(d.id), delta,
                        d.fBaselineMerge, f));
            } else if (delta < -1e-9) {
                regressed++;
                regressions.add(String.format("%s %+.4f (%.4f -> %.4f)", shortId(d.id), delta,
                        d.fBaselineMerge, f));
            } else {
                same++;
            }
        }
        line("  MACRO = %.4f  (positional merge %.4f, delta %+.4f)",
                prodSum / docs.size(), baseMerge, prodSum / docs.size() - baseMerge);
        line("  per-document: improved=%d  regressed=%d  unchanged=%d", improved, regressed, same);
        gains.sort((a, b) -> a.compareTo(b));
        line("  DOCUMENTS THAT REGRESSED (the honest cost of the rule):");
        if (regressions.isEmpty()) line("    (none)");
        for (String s : regressions) line("    %s", s);
        line("  documents that improved:");
        for (String s : gains) line("    %s", s);

        line("");
        line("DoS EVIDENCE FROM REAL CORPUS PAGES (what the arbitration budget must accommodate)");
        int mr = 0, ms = 0, mp = 0;
        double maxUs = 0, sumUs = 0;
        for (Doc d : docs) {
            mr = Math.max(mr, d.maxRuledPerPage);
            ms = Math.max(ms, d.maxStreamPerPage);
            mp = Math.max(mp, d.pages);
            maxUs = Math.max(maxUs, d.arbMicros);
            sumUs += d.arbMicros;
        }
        line("  max ruling candidates on ANY single corpus page  : %d  (production cap %d)",
                mr, TableExtractor.MAX_TABLES_PER_PAGE);
        line("  max stream candidates on ANY single corpus page  : %d  (production cap %d)",
                ms, StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE);
        line("  longest corpus document                          : %d pages", mp);
        line("  arbitrate() wall time per document: mean %.1f us, max %.1f us",
                sumUs / docs.size(), maxUs);
        line("  worst charged work a page at the production caps can reach:");
        line("     pair scan %d*%d = %d, plus traversal <= (%d+%d)*%d = %d  ->  ~%d per page",
                TableExtractor.MAX_TABLES_PER_PAGE, StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE,
                TableExtractor.MAX_TABLES_PER_PAGE * StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE,
                TableExtractor.MAX_TABLES_PER_PAGE, StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE,
                TableExtractor.MAX_TABLES_PER_PAGE,
                (TableExtractor.MAX_TABLES_PER_PAGE + StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE)
                        * TableExtractor.MAX_TABLES_PER_PAGE,
                TableExtractor.MAX_TABLES_PER_PAGE * StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE
                        + (TableExtractor.MAX_TABLES_PER_PAGE
                           + StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE)
                          * TableExtractor.MAX_TABLES_PER_PAGE);
        line("  budget MAX_ARBITRATION_WORK = %d  ->  headroom for ~%d such pages in one document",
                TableExtractor.MAX_ARBITRATION_WORK,
                TableExtractor.MAX_ARBITRATION_WORK
                        / (TableExtractor.MAX_TABLES_PER_PAGE
                           * StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE
                           + (TableExtractor.MAX_TABLES_PER_PAGE
                              + StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE)
                             * TableExtractor.MAX_TABLES_PER_PAGE));
        line("");
        line("PER-REGION SIGNAL DUMP (every contested region, oracle-free)");
        for (Doc d : docs) {
            for (int i = 0; i < d.gs.size(); i++) {
                line("  %-70s g%d %s", shortId(d.id), i, sig(d.gs.get(i)));
            }
        }

        List<Doc> bad = docs.stream().filter(d -> d.err != null).toList();
        line("");
        line("documents with a measurement error: %d", bad.size());
        for (Doc d : bad) line("    %s : %s", d.id, d.err);

        Path p = Path.of("target/arb-rule-report.md");
        Files.createDirectories(p.getParent());
        Files.writeString(p, "```\n" + out + "```\n");
        System.out.println("Report written to " + p.toAbsolutePath());
    }

    private static String shortId(String id) {
        int i = id.lastIndexOf('/');
        return i < 0 ? id : id.substring(i + 1);
    }

    // ------------------------------------------------------------------- named grids for LOO search

    private record Named(String name, Rule rule) {}

    private static List<Named> occGrid() {
        List<Named> l = new ArrayList<>();
        for (double th : new double[]{0.30, 0.40, 0.50, 0.60, 0.70, 0.80, 0.90, 0.95, 1.00}) {
            l.add(new Named("occ<" + th, occRule(th)));
        }
        return l;
    }

    private static List<Named> glyphGrid() {
        List<Named> l = new ArrayList<>();
        for (double th : new double[]{0.50, 0.70, 0.80, 0.90, 0.95, 0.99, 1.00}) {
            l.add(new Named("glyph<" + th, glyphRule(th)));
        }
        return l;
    }

    private static List<Named> glyphConfGrid() {
        List<Named> l = new ArrayList<>();
        for (double th : new double[]{0.80, 0.90, 0.95, 0.99}) {
            for (double cf : new double[]{0.55, 0.65, 0.75}) {
                l.add(new Named("glyph<" + th + "&conf>=" + cf, glyphConfRule(th, cf)));
            }
        }
        return l;
    }

    private static List<Named> occConfGrid() {
        List<Named> l = new ArrayList<>();
        for (double th : new double[]{0.50, 0.70, 0.90, 1.00}) {
            for (double cf : new double[]{0.55, 0.65, 0.75, 0.85}) {
                l.add(new Named("occ<" + th + "&conf>=" + cf, occConfRule(th, cf)));
            }
        }
        return l;
    }

    private static List<Named> compositeGrid() {
        List<Named> l = new ArrayList<>();
        for (double cf : new double[]{0.55, 0.65, 0.75, 0.85}) {
            for (double rr : new double[]{1.15, 1.35, 1.75, 2.50, 99.0}) {
                for (boolean cols : new boolean[]{true, false}) {
                    l.add(new Named("comp c=" + cf + " r=" + rr + (cols ? " +cols" : ""),
                            composite(0.90, cf, rr, cols)));
                }
            }
        }
        return l;
    }

    /** The composite family with its OCCUPANCY threshold searched too, not fixed by hand. */
    private static List<Named> compositeWideGrid() {
        List<Named> l = new ArrayList<>();
        for (double th : new double[]{0.60, 0.70, 0.80, 0.90, 0.95, 1.00}) {
            for (double cf : new double[]{0.55, 0.65, 0.75, 0.85}) {
                for (double rr : new double[]{1.15, 1.35, 1.75, 2.50, 99.0}) {
                    for (boolean cols : new boolean[]{true, false}) {
                        l.add(new Named("comp t=" + th + " c=" + cf + " r=" + rr + (cols ? " +cols" : ""),
                                composite(th, cf, rr, cols)));
                    }
                }
            }
        }
        return l;
    }

    private static List<Named> allGrid() {
        List<Named> l = new ArrayList<>();
        l.addAll(occGrid());
        l.addAll(glyphGrid());
        l.addAll(glyphConfGrid());
        l.addAll(occConfGrid());
        l.addAll(compositeWideGrid());
        l.addAll(composite2Grid());
        return l;
    }

    /**
     * Leave-one-document-out over a grid of candidate rules. Scores every (rule, document) pair ONCE
     * into a matrix; the folds are then pure arithmetic over that matrix, which is what makes a
     * 600-rule grid tractable (the naive nested loop is |grid| x N^2 relation comparisons).
     */
    private void looReport(List<Doc> docs, String label, List<Named> grid) {
        int n = docs.size(), m = grid.size();
        double[][] s = new double[m][n];
        double[] total = new double[m];
        for (int r = 0; r < m; r++) {
            Rule rule = grid.get(r).rule();
            for (int i = 0; i < n; i++) { s[r][i] = docs.get(i).score(rule); total[r] += s[r][i]; }
        }
        double sum = 0;
        Map<String, Integer> chosen = new LinkedHashMap<>();
        for (int held = 0; held < n; held++) {
            int best = 0;
            double bestV = -1;
            for (int r = 0; r < m; r++) {
                double v = total[r] - s[r][held];       // fit on the other n-1 documents
                if (v > bestV) { bestV = v; best = r; }
            }
            chosen.merge(grid.get(best).name(), 1, Integer::sum);
            sum += s[best][held];                       // score the held-out document
        }
        double inSample = 0;
        String inSampleName = "";
        for (int r = 0; r < m; r++) {
            if (total[r] / n > inSample) { inSample = total[r] / n; inSampleName = grid.get(r).name(); }
        }
        line("  %-46s %8.4f %8.4f  best=%s  picks: %s",
                label, sum / n, inSample, inSampleName, chosen);
    }

    private void report(String name, Rule r, List<Doc> all, List<Doc> eu, List<Doc> us, double base) {
        line("  %-46s %8.4f %+8.4f %8.4f %8.4f", name, macro(all, r), macro(all, r) - base,
                macro(eu, r), macro(us, r));
    }

    /** Fit the parameter on fold A by MACRO, score fold B with it, and vice versa; return the MACRO
     *  over the union of the two HELD-OUT scores (so no document is ever scored by a parameter that
     *  was chosen with its own ground truth). */
    private static double twoFold(List<Doc> docs, double[] params,
                                  java.util.function.DoubleFunction<Rule> mk) {
        List<Doc> a = new ArrayList<>(), b = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) (i % 2 == 0 ? a : b).add(docs.get(i));
        double sum = 0;
        for (int fold = 0; fold < 2; fold++) {
            List<Doc> fit = fold == 0 ? a : b;
            List<Doc> test = fold == 0 ? b : a;
            double bestV = -1; Rule best = null;
            for (double p : params) {
                Rule r = mk.apply(p);
                double v = macro(fit, r);
                if (v > bestV) { bestV = v; best = r; }
            }
            for (Doc d : test) sum += d.score(best);
        }
        return sum / docs.size();
    }
}
