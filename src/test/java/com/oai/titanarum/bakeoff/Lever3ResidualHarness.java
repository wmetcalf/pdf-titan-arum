// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/ (matching
// BakeOffHarness / BaselineHarness / GateOracleHarness's convention) but declares
// `package com.oai.titanarum;` because it needs the package-private production types.
//
// PURPOSE. Re-derive the RESIDUAL oracle ceiling of the confidence gate under the PRIMARY protocol,
// on BOTH the stream-alone diagnostic configuration AND -- crucially -- on the FULL pipeline
// (tagged+lattice+non-overlapping-stream), which is the product configuration and therefore the one
// whose macro is the reported number. The previously-quoted residual (+0.0514) was measured against
// stream-alone; a stream-alone gain does NOT carry over to the full pipeline, because 55 of the 77
// documents already have their tables recovered by the lattice path and any overlapping stream hit is
// dropped before scoring. This harness measures both, and decomposes the residual by hard-reject
// reason and by column count so the addressable subset is explicit.
//
//   mvn -q -o test -Dtest=Lever3ResidualHarness -Dlever3=true
//
// PROTOCOL. Identical to BaselineHarness's PRIMARY: document-POOLED official ICDAR-2013 adjacency
// relations, MULTISET, DE-DUPLICATED ground truth, MACRO (per-document mean F1) first, 77 units.
// READ-ONLY: changes no extraction behaviour, asserts nothing about it.
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.GtDedup;
import com.oai.titanarum.bakeoff.TableScore;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class Lever3ResidualHarness {

    /** Emit gate low enough that no candidate is ever rejected. */
    private static final StreamTableExtractor.ConfidenceBar NO_GATE = c -> -1e9;

    private final StringBuilder out = new StringBuilder();

    private void line(String s) { out.append(s).append('\n'); System.out.println(s); }
    private void line(String f, Object... a) { line(String.format(Locale.ROOT, f, a)); }
    private void rule() { line("================================================================================"); }

    // ------------------------------------------------------------------------------- relation glue

    private static List<TableScore.Relation> rels(List<TableScore.GridCell> cells) {
        return TableScore.buildOfficialRelations(cells, false).relations();
    }

    private static List<TableScore.Relation> hitRels(TableExtractor.TableHit h) {
        return rels(MetricFixHarness.cellsOf(h));
    }

    private static double f1(long matched, long det, long gt) {
        if (matched == 0) return 0.0;
        double p = det == 0 ? 0 : (double) matched / det;
        double rc = gt == 0 ? 0 : (double) matched / gt;
        return (p + rc) == 0 ? 0.0 : 2 * p * rc / (p + rc);
    }

    private static TableScore.AdjResult cmp(List<TableScore.Relation> gt, List<TableScore.Relation> det) {
        return TableScore.compareRelations(gt, det, TableScore.Semantics.MULTISET);
    }

    private static double pooledF1(List<TableScore.Relation> gtPooled,
                                   List<TableExtractor.TableHit> hits,
                                   Map<TableExtractor.TableHit, List<TableScore.Relation>> cache) {
        List<TableScore.Relation> det = new ArrayList<>();
        for (TableExtractor.TableHit h : hits) det.addAll(cache.get(h));
        TableScore.AdjResult r = cmp(gtPooled, det);
        return f1(r.matched(), r.detectedTotal(), r.gtTotal());
    }

    private static final class Acc {
        long matched, det, gt;
        final List<Double> perDoc = new ArrayList<>();
        void addDoc(long m, long d, long g) {
            if (g == 0 && d == 0) return;
            matched += m; det += d; gt += g;
            perDoc.add(f1(m, d, g));
        }
        double macro() {
            return perDoc.isEmpty() ? 0 : perDoc.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        }
        double microP() { return det == 0 ? 0 : (double) matched / det; }
        double microR() { return gt == 0 ? 0 : (double) matched / gt; }
        double microF1() { return f1(matched, det, gt); }
        int docs() { return perDoc.size(); }
    }

    private static final class Tal {
        final long m, d, g;
        Tal(TableScore.AdjResult r) { m = r.matched(); d = r.detectedTotal(); g = r.gtTotal(); }
    }

    private static Tal tally(List<TableScore.Relation> gtPooled, List<TableExtractor.TableHit> hits,
                             Map<TableExtractor.TableHit, List<TableScore.Relation>> cache) {
        List<TableScore.Relation> det = new ArrayList<>();
        for (TableExtractor.TableHit h : hits) det.addAll(cache.get(h));
        return new Tal(cmp(gtPooled, det));
    }

    /** Greedy removal to a fixpoint: drop whichever chosen hit most improves pooled F1. Models the
     *  TIGHTENING side of the gate residual, which is prose-FP-safe by construction (a stricter gate
     *  can only ever flag fewer prose pages). */
    private static List<TableExtractor.TableHit> greedyDrop(List<TableScore.Relation> gtPooled,
                                                            List<TableExtractor.TableHit> seed,
                                                            List<TableExtractor.TableHit> droppable,
                                                            Map<TableExtractor.TableHit, List<TableScore.Relation>> cache) {
        List<TableExtractor.TableHit> chosen = new ArrayList<>(seed);
        double cur = pooledF1(gtPooled, chosen, cache);
        boolean improved = true;
        while (improved) {
            improved = false;
            int bestIdx = -1;
            double bestF1 = cur;
            for (int i = 0; i < chosen.size(); i++) {
                if (!droppable.contains(chosen.get(i))) continue;   // lattice/tagged hits are not the gate's business
                TableExtractor.TableHit h = chosen.remove(i);
                double f = pooledF1(gtPooled, chosen, cache);
                chosen.add(i, h);
                if (f > bestF1 + 1e-12) { bestF1 = f; bestIdx = i; }
            }
            if (bestIdx >= 0) { chosen.remove(bestIdx); cur = bestF1; improved = true; }
        }
        return chosen;
    }

    /** Greedy subset addition to a fixpoint (see GateOracleHarness: greedy under-states, which is
     *  the safe direction for a go/no-go). */
    private static List<TableExtractor.TableHit> greedy(List<TableScore.Relation> gtPooled,
                                                        List<TableExtractor.TableHit> seed,
                                                        List<TableExtractor.TableHit> pool,
                                                        Map<TableExtractor.TableHit, List<TableScore.Relation>> cache) {
        List<TableExtractor.TableHit> chosen = new ArrayList<>(seed);
        List<TableExtractor.TableHit> remaining = new ArrayList<>(pool);
        double cur = pooledF1(gtPooled, chosen, cache);
        boolean improved = true;
        while (improved) {
            improved = false;
            TableExtractor.TableHit best = null;
            double bestF1 = cur;
            for (TableExtractor.TableHit h : remaining) {
                chosen.add(h);
                double f = pooledF1(gtPooled, chosen, cache);
                chosen.remove(chosen.size() - 1);
                if (f > bestF1 + 1e-12) { bestF1 = f; best = h; }
            }
            if (best != null) { chosen.add(best); remaining.remove(best); cur = bestF1; improved = true; }
        }
        return chosen;
    }

    // ------------------------------------------------------------------------------ per-doc record

    private static final class Doc {
        String id;
        boolean borderless;
        final Map<String, Tal> tallies = new LinkedHashMap<>();
        int nCand, nGated, nRejected, nAddedFull, nAddedStream, nDroppedFull, nDroppedStream;
        final List<String> notes = new ArrayList<>();
    }

    /** One oracle-added candidate, with the configuration it helped and by how much. */
    record Add(String cfg, String doc, int page, int cols, int rows, double conf, String hardReject,
               long matched, long det, double dF1, boolean overlapsLattice) {}

    private static final List<Add> ADDS = new ArrayList<>();
    private static final List<Add> DROPS = new ArrayList<>();

    /** Census row for every 2-column all-non-numeric candidate, corpus AND prose side, with every
     *  extraction-time signal a separating rule could plausibly use. */
    record TwoCol(String src, String doc, int page, int rows, double graded, double cc, double viol,
                  double prose, double proseColFrac,
                  double gutFracOfBand, double gutCoverFrac, double bothFilledFrac,
                  double col0FillMean, double col1FillMean, double col1LeftJitter,
                  long matched, long det, double dF1Full) {}

    private static final List<TwoCol> TWOCOL = new ArrayList<>();
    private static final List<TwoCol> PROSE_TWOCOL = new ArrayList<>();

    /** Extraction-time geometry of a 2-column candidate: relative gutter width, gutter row
     *  coverage, fraction of rows with BOTH columns filled, per-column mean fill fraction, and the
     *  right column's left-edge jitter (a real value column is left-aligned; a prose second block
     *  is ragged). All of it available without ground truth. */
    private static double[] twoColFeatures(StreamTableExtractor.Grid g) {
        List<StreamTableExtractor.Line> lines = g.rows;
        float[] b = g.colBounds;
        float band = Math.max(1e-6f, b[b.length - 1] - b[0]);
        StreamTableExtractor.Gutter gut = g.gutters.isEmpty() ? null : g.gutters.get(0);
        double gutFrac = gut == null ? 0 : (gut.x1 - gut.x0) / band;
        double coverFrac = gut == null || lines.isEmpty() ? 0 : (double) gut.rowsCovered / lines.size();
        int both = 0;
        double sumFill0 = 0, sumFill1 = 0; int n0 = 0, n1 = 0;
        List<Double> lefts = new ArrayList<>();
        for (StreamTableExtractor.Line l : lines) {
            boolean f0 = false, f1 = false;
            float min0 = Float.MAX_VALUE, max0 = -Float.MAX_VALUE;
            float min1 = Float.MAX_VALUE, max1 = -Float.MAX_VALUE;
            for (StreamTableExtractor.Word w : l.words) {
                boolean right = w.cx() >= b[1];
                if (right) { f1 = true; min1 = Math.min(min1, w.x0); max1 = Math.max(max1, w.x1); }
                else { f0 = true; min0 = Math.min(min0, w.x0); max0 = Math.max(max0, w.x1); }
            }
            if (f0 && f1) both++;
            float w0 = Math.max(1e-6f, b[1] - b[0]), w1 = Math.max(1e-6f, b[2] - b[1]);
            if (f0) { sumFill0 += (max0 - min0) / w0; n0++; }
            if (f1) { sumFill1 += (max1 - min1) / w1; n1++; lefts.add((double) min1); }
        }
        double jitter = 0;
        if (lefts.size() > 1) {
            double mean = lefts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double var = 0;
            for (double v : lefts) var += (v - mean) * (v - mean);
            jitter = Math.sqrt(var / lefts.size()) / Math.max(1e-6f, b[2] - b[1]);
        }
        return new double[]{gutFrac, coverFrac,
                lines.isEmpty() ? 0 : (double) both / lines.size(),
                n0 == 0 ? 0 : sumFill0 / n0, n1 == 0 ? 0 : sumFill1 / n1, jitter};
    }

    private static Doc measure(BakeOffHarness.ScoreUnit unit, GutterFinder finder) {
        Doc d = new Doc();
        d.id = unit.id();

        List<GroundTruth.Table> kept = GtDedup.dedup(unit.expected()).kept();
        List<TableScore.Relation> gtPooled = new ArrayList<>();
        for (GroundTruth.Table t : kept) gtPooled.addAll(rels(TableScore.gridCellsFromGroundTruth(t)));

        try (PDDocument doc = Loader.loadPDF(unit.pdf().toFile())) {
            int pages = doc.getNumberOfPages();
            List<Integer> pageList = new ArrayList<>();
            Map<Integer, List<TextPosition>> glyphs = new LinkedHashMap<>();
            for (int p = 1; p <= pages; p++) {
                pageList.add(p);
                glyphs.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
            }

            List<TableExtractor.TableHit> tl = new ArrayList<>();
            try { tl.addAll(TableExtractor.extract(doc, pageList, glyphs).tables); }
            catch (Throwable t) { d.notes.add("tl: " + t.getClass().getSimpleName()); }
            d.borderless = tl.isEmpty();

            // Every candidate the pipeline scored, emit gate OFF, merge gate at PRODUCTION.
            List<StreamTableExtractor.Candidate> cands = new ArrayList<>();
            for (int p : pageList) {
                try {
                    StreamTableExtractor.extractPage(p, glyphs.get(p), finder, NO_GATE,
                            StreamTableExtractor.PRODUCTION_BAR, cands);
                } catch (Throwable t) { d.notes.add("cand/" + p + ": " + t.getClass().getSimpleName()); }
            }

            Map<TableExtractor.TableHit, List<TableScore.Relation>> cache = new LinkedHashMap<>();
            List<TableExtractor.TableHit> gatedStream = new ArrayList<>();
            List<TableExtractor.TableHit> rejStream = new ArrayList<>();
            List<StreamTableExtractor.Candidate> rejCands = new ArrayList<>();
            for (StreamTableExtractor.Candidate c : cands) {
                if (c.hit == null) continue;
                d.nCand++;
                cache.put(c.hit, hitRels(c.hit));
                if (StreamTableExtractor.acceptsGrid(c.grid)) { gatedStream.add(c.hit); d.nGated++; }
                else { rejStream.add(c.hit); rejCands.add(c); d.nRejected++; }
            }
            for (TableExtractor.TableHit h : tl) cache.put(h, hitRels(h));

            // ---- STREAM-ALONE (diagnostic) ----
            d.tallies.put("stream:gated", tally(gtPooled, gatedStream, cache));
            List<TableExtractor.TableHit> streamOracle = greedy(gtPooled, gatedStream, rejStream, cache);
            d.nAddedStream = streamOracle.size() - gatedStream.size();
            d.tallies.put("stream:oracleAdd", tally(gtPooled, streamOracle, cache));

            // ---- FULL PIPELINE (product configuration) ----
            // Production combine rule: a stream hit substantially overlapping a tagged/lattice hit
            // is dropped. The oracle pool must obey the SAME rule, otherwise the ceiling counts
            // additions production could never make.
            List<TableExtractor.TableHit> keptGated = new ArrayList<>();
            for (TableExtractor.TableHit s : gatedStream) {
                if (!MetricFixHarness.overlapsSubstantially(s, tl)) keptGated.add(s);
            }
            List<TableExtractor.TableHit> fullSeed = new ArrayList<>(tl);
            fullSeed.addAll(keptGated);
            d.tallies.put("full:gated", tally(gtPooled, fullSeed, cache));

            List<TableExtractor.TableHit> poolNonOverlapping = new ArrayList<>();
            for (TableExtractor.TableHit s : rejStream) {
                if (!MetricFixHarness.overlapsSubstantially(s, tl)) poolNonOverlapping.add(s);
            }
            List<TableExtractor.TableHit> fullOracle = greedy(gtPooled, fullSeed, poolNonOverlapping, cache);
            d.nAddedFull = fullOracle.size() - fullSeed.size();
            d.tallies.put("full:oracleAdd", tally(gtPooled, fullOracle, cache));

            // Looser upper bound: allow the oracle to add even lattice-overlapping rejects.
            List<TableExtractor.TableHit> fullOracleAny = greedy(gtPooled, fullSeed, rejStream, cache);
            d.tallies.put("full:oracleAdd-anyOverlap", tally(gtPooled, fullOracleAny, cache));

            // ---- TIGHTENING side: drop admitted stream hits that hurt (prose-FP-safe by
            // construction). Only stream hits are droppable; lattice/tagged are not the gate's job.
            List<TableExtractor.TableHit> fullDrop = greedyDrop(gtPooled, fullSeed, keptGated, cache);
            d.nDroppedFull = fullSeed.size() - fullDrop.size();
            d.tallies.put("full:oracleDrop", tally(gtPooled, fullDrop, cache));
            // Both sides at once: the total residual of a perfect gate.
            List<TableExtractor.TableHit> fullBoth =
                    greedy(gtPooled, fullDrop, poolNonOverlapping, cache);
            d.tallies.put("full:oracleBoth", tally(gtPooled, fullBoth, cache));

            List<TableExtractor.TableHit> streamDrop = greedyDrop(gtPooled, gatedStream, gatedStream, cache);
            d.nDroppedStream = gatedStream.size() - streamDrop.size();
            d.tallies.put("stream:oracleDrop", tally(gtPooled, streamDrop, cache));

            // Attribution of the FULL-pipeline DROPS.
            double baseFullForDrop = pooledF1(gtPooled, fullSeed, cache);
            for (StreamTableExtractor.Candidate c : cands) {
                if (c.hit == null || !StreamTableExtractor.acceptsGrid(c.grid)) continue;
                if (!keptGated.contains(c.hit) || fullDrop.contains(c.hit)) continue;
                List<TableExtractor.TableHit> minus = new ArrayList<>(fullSeed);
                minus.remove(c.hit);
                TableScore.AdjResult r = cmp(gtPooled, cache.get(c.hit));
                DROPS.add(new Add("fullDrop", shortId(unit.id()), c.page, c.grid.nCols, c.grid.nRows,
                        c.confidence, c.grid.hardReject, r.matched(), r.detectedTotal(),
                        pooledF1(gtPooled, minus, cache) - baseFullForDrop, false));
            }

            // ---- attribution of the FULL-pipeline additions ----
            double baseFull = pooledF1(gtPooled, fullSeed, cache);
            for (StreamTableExtractor.Candidate c : rejCands) {
                if (!fullOracle.contains(c.hit)) continue;
                List<TableExtractor.TableHit> plus = new ArrayList<>(fullSeed); plus.add(c.hit);
                TableScore.AdjResult r = cmp(gtPooled, cache.get(c.hit));
                ADDS.add(new Add("full", shortId(unit.id()), c.page, c.grid.nCols, c.grid.nRows,
                        c.confidence, c.grid.hardReject, r.matched(), r.detectedTotal(),
                        pooledF1(gtPooled, plus, cache) - baseFull,
                        MetricFixHarness.overlapsSubstantially(c.hit, tl)));
            }
            double baseStream = pooledF1(gtPooled, gatedStream, cache);
            for (StreamTableExtractor.Candidate c : rejCands) {
                if (!streamOracle.contains(c.hit)) continue;
                List<TableExtractor.TableHit> plus = new ArrayList<>(gatedStream); plus.add(c.hit);
                TableScore.AdjResult r = cmp(gtPooled, cache.get(c.hit));
                ADDS.add(new Add("stream", shortId(unit.id()), c.page, c.grid.nCols, c.grid.nRows,
                        c.confidence, c.grid.hardReject, r.matched(), r.detectedTotal(),
                        pooledF1(gtPooled, plus, cache) - baseStream,
                        MetricFixHarness.overlapsSubstantially(c.hit, tl)));
            }

            // ---- BAR SWEEP on the FULL pipeline: can any (narrow, wide) confidence bar pair reach
            // the tightening residual? Each config re-selects from the SAME candidate set and
            // re-applies production's overlap-drop rule. (Approximation: Step A' merge decisions are
            // held at the production bar, exactly as GateOracleHarness's own variant sweep does --
            // this is a SELECTION measurement, not a shipped e2e number.)
            for (double[] bar : BARS) {
                List<TableExtractor.TableHit> sel = new ArrayList<>(tl);
                for (StreamTableExtractor.Candidate c : cands) {
                    if (c.hit == null) continue;
                    int cols = c.grid.colBounds.length - 1;
                    double b = cols >= StreamTableExtractor.WIDE_GRID_MIN_COLS ? bar[1] : bar[0];
                    if (c.confidence < b) continue;
                    if (MetricFixHarness.overlapsSubstantially(c.hit, tl)) continue;
                    sel.add(c.hit);
                }
                d.tallies.put(barKey(bar), tally(gtPooled, sel, cache));
            }

            // ---- 2-column non-numeric census (the block the gate agent recommended stay closed) ----
            for (StreamTableExtractor.Candidate c : cands) {
                if (c.hit == null) continue;
                if (!"cols==2-nonnumeric".equals(c.grid.hardReject)) continue;
                double graded = gradedOf(c.grid);
                List<TableExtractor.TableHit> plus = new ArrayList<>(fullSeed); plus.add(c.hit);
                TableScore.AdjResult r = cmp(gtPooled, cache.get(c.hit));
                double[] ft = twoColFeatures(c.grid);
                TWOCOL.add(new TwoCol("corpus", shortId(unit.id()), c.page, c.grid.nRows, graded,
                        c.grid.tColConsistency, c.grid.tViolation, c.grid.tProse, c.grid.tProseColFrac,
                        ft[0], ft[1], ft[2], ft[3], ft[4], ft[5],
                        r.matched(), r.detectedTotal(), pooledF1(gtPooled, plus, cache) - baseFull));
            }
        } catch (Throwable t) {
            d.notes.add("load: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return d;
    }

    /** (narrowBar, wideBar) pairs to sweep. Row 1 is production. */
    private static final double[][] BARS = {
        {0.55, 0.40},                                     // production
        {0.60, 0.40}, {0.65, 0.40}, {0.70, 0.40}, {0.75, 0.40}, {0.80, 0.40}, {0.90, 0.40},
        {0.55, 0.45}, {0.55, 0.50}, {0.55, 0.55},
        {0.65, 0.50}, {0.70, 0.55}, {0.75, 0.60}, {0.80, 0.70},
    };

    private static String barKey(double[] b) {
        return String.format(Locale.ROOT, "bar n=%.2f w=%.2f", b[0], b[1]);
    }

    /** The graded confidence a hard-rejected grid WOULD have had (its terms are recorded on the
     *  Grid before the hard gates fire, so this is a pure read of production's own numbers). */
    private static double gradedOf(StreamTableExtractor.Grid g) {
        return 0.30 * g.tColConsistency + 0.25 * g.tViolation + 0.20 * g.tProse
             + 0.15 * g.tColCount + 0.10 * g.tNumeric;
    }

    // --------------------------------------------------------------------------------------- test

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lever3"), "set -Dlever3=true to run");

        GutterFinder finder = new BreuelGutterFinder();
        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);

        List<Doc> docs = new ArrayList<>();
        for (BakeOffHarness.ScoreUnit u : corpus.units) docs.add(measure(u, finder));

        rule();
        line("LEVER 3 -- RESIDUAL ORACLE CEILING OF THE CONFIDENCE GATE");
        rule();
        line("Corpus: %d scoring units. PRIMARY protocol: POOLED official ICDAR adjacency relations,",
                corpus.units.size());
        line("MULTISET, de-duplicated GT, MACRO first. Oracle = greedy add of gate-REJECTED candidates,");
        line("obeying production's own stream/lattice overlap-drop rule for the full-pipeline rows.");
        line("");

        List<String> variants = List.of("full:gated", "full:oracleAdd", "full:oracleAdd-anyOverlap",
                "full:oracleDrop", "full:oracleBoth",
                "stream:gated", "stream:oracleAdd", "stream:oracleDrop");

        line("ALL-%d, POOLED + dedup", docs.size());
        line("  %-26s %8s %8s %8s %8s %6s", "variant", "MACRO", "microP", "microR", "microF1", "docs");
        Map<String, Double> macroAll = new LinkedHashMap<>();
        for (String v : variants) {
            Acc a = new Acc();
            for (Doc d : docs) { Tal t = d.tallies.get(v); if (t != null) a.addDoc(t.m, t.d, t.g); }
            macroAll.put(v, a.macro());
            line("  %-26s %8.4f %8.4f %8.4f %8.4f %6d", v, a.macro(), a.microP(), a.microR(),
                    a.microF1(), a.docs());
        }
        line("");
        line("BORDERLESS subset (lattice+tagged found nothing), POOLED + dedup");
        line("  %-26s %8s %8s %8s %8s %6s", "variant", "MACRO", "microP", "microR", "microF1", "docs");
        for (String v : variants) {
            Acc a = new Acc();
            for (Doc d : docs) {
                if (!d.borderless) continue;
                Tal t = d.tallies.get(v); if (t != null) a.addDoc(t.m, t.d, t.g);
            }
            line("  %-26s %8.4f %8.4f %8.4f %8.4f %6d", v, a.macro(), a.microP(), a.microR(),
                    a.microF1(), a.docs());
        }
        line("");
        rule();
        line("RESIDUAL CEILING");
        rule();
        line("  FULL pipeline  (THE PRODUCT NUMBER): %.4f -> %.4f   = %+.4f",
                macroAll.get("full:gated"), macroAll.get("full:oracleAdd"),
                macroAll.get("full:oracleAdd") - macroAll.get("full:gated"));
        line("  FULL, overlap rule waived (looser) : %.4f -> %.4f   = %+.4f",
                macroAll.get("full:gated"), macroAll.get("full:oracleAdd-anyOverlap"),
                macroAll.get("full:oracleAdd-anyOverlap") - macroAll.get("full:gated"));
        line("  stream-alone   (diagnostic only)   : %.4f -> %.4f   = %+.4f",
                macroAll.get("stream:gated"), macroAll.get("stream:oracleAdd"),
                macroAll.get("stream:oracleAdd") - macroAll.get("stream:gated"));
        line("");
        line("  TIGHTENING side (prose-FP-safe by construction -- a stricter gate cannot flag MORE):");
        line("  FULL, oracle DROP only             : %.4f -> %.4f   = %+.4f",
                macroAll.get("full:gated"), macroAll.get("full:oracleDrop"),
                macroAll.get("full:oracleDrop") - macroAll.get("full:gated"));
        line("  FULL, oracle DROP then ADD (total) : %.4f -> %.4f   = %+.4f",
                macroAll.get("full:gated"), macroAll.get("full:oracleBoth"),
                macroAll.get("full:oracleBoth") - macroAll.get("full:gated"));
        line("  stream, oracle DROP only           : %.4f -> %.4f   = %+.4f",
                macroAll.get("stream:gated"), macroAll.get("stream:oracleDrop"),
                macroAll.get("stream:oracleDrop") - macroAll.get("stream:gated"));
        line("");

        int cand = 0, gated = 0, rej = 0, addF = 0, addS = 0, dropF = 0, dropS = 0;
        for (Doc d : docs) { cand += d.nCand; gated += d.nGated; rej += d.nRejected;
            addF += d.nAddedFull; addS += d.nAddedStream;
            dropF += d.nDroppedFull; dropS += d.nDroppedStream; }
        line("  oracle would drop (FULL)   : %d", dropF);
        line("  oracle would drop (stream) : %d", dropS);
        line("  candidates scored          : %d", cand);
        line("  passed the production gate : %d", gated);
        line("  REJECTED                   : %d", rej);
        line("  oracle would add (FULL)    : %d", addF);
        line("  oracle would add (stream)  : %d", addS);
        line("");

        rule();
        line("THE FULL-PIPELINE ADDITIONS, ranked by their own dF1 (macro contribution = dF1/77)");
        rule();
        line("  %-22s %4s %5s %5s %6s %-20s %7s %7s %9s %9s",
                "doc", "pg", "cols", "rows", "conf", "hardReject", "prec", "dF1", "macroShare", "ovLattice");
        List<Add> full = new ArrayList<>();
        for (Add a : ADDS) if (a.cfg().equals("full")) full.add(a);
        full.sort(Comparator.comparingDouble((Add a) -> -a.dF1()));
        for (Add a : full) {
            line("  %-22s %4d %5d %5d %6.3f %-20s %7.3f %+7.4f %+9.4f %9s",
                    a.doc(), a.page(), a.cols(), a.rows(), a.conf(),
                    a.hardReject() == null ? "-" : a.hardReject(),
                    a.det() == 0 ? 0 : (double) a.matched() / a.det(), a.dF1(), a.dF1() / docs.size(),
                    a.overlapsLattice());
        }
        line("");
        line("  FULL additions by hardReject class (n, summed dF1, summed macro share):");
        Map<String, double[]> byClass = new LinkedHashMap<>();
        for (Add a : full) {
            String k = a.hardReject() == null ? "(graded, below bar)" : a.hardReject();
            double[] v = byClass.computeIfAbsent(k, x -> new double[2]);
            v[0]++; v[1] += a.dF1();
        }
        for (Map.Entry<String, double[]> e : byClass.entrySet()) {
            line("    %-24s n=%3.0f  sum dF1 %+8.4f  macro share %+8.4f",
                    e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[1] / docs.size());
        }
        line("");
        line("  FULL additions by column count:");
        Map<Integer, double[]> byCols = new java.util.TreeMap<>();
        for (Add a : full) {
            double[] v = byCols.computeIfAbsent(a.cols(), x -> new double[2]);
            v[0]++; v[1] += a.dF1();
        }
        for (Map.Entry<Integer, double[]> e : byCols.entrySet()) {
            line("    cols=%-3d n=%3.0f  sum dF1 %+8.4f  macro share %+8.4f",
                    e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[1] / docs.size());
        }
        line("");

        rule();
        line("THE FULL-PIPELINE DROPS the oracle would make (prose-FP-safe direction)");
        rule();
        line("  %-22s %4s %5s %5s %6s %-20s %7s %7s %9s",
                "doc", "pg", "cols", "rows", "conf", "hardReject", "prec", "dF1", "macroShare");
        DROPS.sort(Comparator.comparingDouble((Add a) -> -a.dF1()));
        for (Add a : DROPS) {
            line("  %-22s %4d %5d %5d %6.3f %-20s %7.3f %+7.4f %+9.4f",
                    a.doc(), a.page(), a.cols(), a.rows(), a.conf(),
                    a.hardReject() == null ? "-" : a.hardReject(),
                    a.det() == 0 ? 0 : (double) a.matched() / a.det(), a.dF1(), a.dF1() / docs.size());
        }
        line("");
        line("  DROPS by column count (n, summed dF1, summed macro share):");
        Map<Integer, double[]> dropByCols = new java.util.TreeMap<>();
        for (Add a : DROPS) {
            double[] v = dropByCols.computeIfAbsent(a.cols(), x -> new double[2]);
            v[0]++; v[1] += a.dF1();
        }
        for (Map.Entry<Integer, double[]> e : dropByCols.entrySet()) {
            line("    cols=%-3d n=%3.0f  sum dF1 %+8.4f  macro share %+8.4f",
                    e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[1] / docs.size());
        }
        line("");
        line("  DROPS confidence distribution vs KEPT-and-good confidence distribution:");
        List<Double> dc = new ArrayList<>();
        for (Add a : DROPS) dc.add(a.conf());
        java.util.Collections.sort(dc);
        if (!dc.isEmpty()) line("    dropped conf: min %.3f p25 %.3f p50 %.3f p75 %.3f max %.3f  (n=%d)",
                dc.get(0), dc.get(dc.size() / 4), dc.get(dc.size() / 2),
                dc.get(3 * dc.size() / 4), dc.get(dc.size() - 1), dc.size());
        line("");

        rule();
        line("CONFIDENCE-BAR SWEEP ON THE FULL PIPELINE -- can a threshold reach the drop residual?");
        rule();
        line("  Selection measurement (Step A' merge bar held at production). prose-FP re-measured");
        line("  per config on the same 200-PDF sample, with the same two-tier bar shape.");
        List<Path> proseSample = BakeOffHarness.sampleProsePdfs();
        line("  %-18s %8s %8s %8s %8s %8s %12s", "bar", "M(77)", "mP", "mR", "mF1", "M(bl22)", "proseFP200");
        for (double[] bar : BARS) {
            String k = barKey(bar);
            Acc a = new Acc(), abl = new Acc();
            for (Doc d : docs) {
                Tal t = d.tallies.get(k); if (t == null) continue;
                a.addDoc(t.m, t.d, t.g);
                if (d.borderless) abl.addDoc(t.m, t.d, t.g);
            }
            String fp = "n/a";
            if (proseSample != null && !proseSample.isEmpty()) {
                int flagged = 0;
                for (Path p : proseSample) if (proseFlagged(finder, p, bar)) flagged++;
                fp = String.format(Locale.ROOT, "%d/%d=%.4f", flagged, proseSample.size(),
                        (double) flagged / proseSample.size());
            }
            line("  %-18s %8.4f %8.4f %8.4f %8.4f %8.4f %12s", k, a.macro(), a.microP(), a.microR(),
                    a.microF1(), abl.macro(), fp);
        }
        line("");

        rule();
        line("2-COLUMN ALL-NON-NUMERIC CENSUS (the block recommended to stay closed)");
        rule();
        line("  n = %d candidates on the 77-doc corpus.", TWOCOL.size());
        int good = 0, bad = 0, neutral = 0;
        for (TwoCol t : TWOCOL) {
            if (t.dF1Full() > 1e-9) good++; else if (t.dF1Full() < -1e-9) bad++; else neutral++;
        }
        line("  helps FULL pipeline: %d   hurts: %d   neutral: %d", good, bad, neutral);
        line("");
        line("  Every extraction-time signal, for every 2-col candidate. If NO column separates the");
        line("  'helps' rows from the rest (corpus AND prose), no shippable rule exists.");
        line("  %-22s %4s %5s %7s %6s %6s %6s %6s %7s %7s %7s %7s %7s %7s %9s",
                "doc", "pg", "rows", "graded", "cc", "viol", "prose", "pcFrac",
                "gutFrc", "gutCov", "bothFl", "fill0", "fill1", "jitter", "dF1(full)");
        List<TwoCol> tc = new ArrayList<>(TWOCOL);
        tc.sort(Comparator.comparingDouble((TwoCol t) -> -t.dF1Full()));
        for (TwoCol t : tc) {
            line("  %-22s %4d %5d %7.3f %6.3f %6.3f %6.3f %6.3f %7.3f %7.3f %7.3f %7.3f %7.3f %7.3f %+9.4f",
                    t.doc(), t.page(), t.rows(), t.graded(), t.cc(), t.viol(), t.prose(),
                    t.proseColFrac(), t.gutFracOfBand(), t.gutCoverFrac(), t.bothFilledFrac(),
                    t.col0FillMean(), t.col1FillMean(), t.col1LeftJitter(), t.dF1Full());
        }
        line("");
        line("  graded-confidence separability of the helpful vs unhelpful 2-col candidates:");
        double gMin = 1, gMax = 0, bMin = 1, bMax = 0; int gn = 0, bn = 0;
        double gSum = 0, bSum = 0;
        for (TwoCol t : TWOCOL) {
            if (t.dF1Full() > 1e-9) { gMin = Math.min(gMin, t.graded()); gMax = Math.max(gMax, t.graded()); gn++; gSum += t.graded(); }
            else { bMin = Math.min(bMin, t.graded()); bMax = Math.max(bMax, t.graded()); bn++; bSum += t.graded(); }
        }
        line("    helpful  n=%d  graded range [%.3f, %.3f]  mean %.3f", gn, gMin, gMax, gn == 0 ? 0 : gSum / gn);
        line("    unhelpful n=%d graded range [%.3f, %.3f]  mean %.3f", bn, bMin, bMax, bn == 0 ? 0 : bSum / bn);
        line("");

        // ---------------------------------------------------------------- prose-side 2-col exposure
        rule();
        line("PROSE-SIDE EXPOSURE OF THE 2-COLUMN BLOCK (200-PDF real-world sample, page 1)");
        rule();
        List<Path> prose = BakeOffHarness.sampleProsePdfs();
        if (prose == null || prose.isEmpty()) {
            line("  phishpdfs unavailable -- not measured");
        } else {
            int base = 0, twoColOnly = 0;
            List<Double> twoColGraded = new ArrayList<>();
            for (Path p : prose) {
                boolean flaggedBase = false, flaggedTwoCol = false;
                try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                    if (doc.getNumberOfPages() < 1) continue;
                    List<TextPosition> gl = TableTestPdfs.harvestGlyphs(doc, 0);
                    List<StreamTableExtractor.Candidate> cs = new ArrayList<>();
                    StreamTableExtractor.extractPage(1, gl, finder, NO_GATE,
                            StreamTableExtractor.PRODUCTION_BAR, cs);
                    for (StreamTableExtractor.Candidate c : cs) {
                        if (c.hit == null) continue;
                        if (StreamTableExtractor.acceptsGrid(c.grid)) flaggedBase = true;
                        if ("cols==2-nonnumeric".equals(c.grid.hardReject)) {
                            flaggedTwoCol = true;
                            twoColGraded.add(gradedOf(c.grid));
                            double[] ft = twoColFeatures(c.grid);
                            PROSE_TWOCOL.add(new TwoCol("prose", p.getFileName().toString(), 1,
                                    c.grid.nRows, gradedOf(c.grid), c.grid.tColConsistency,
                                    c.grid.tViolation, c.grid.tProse, c.grid.tProseColFrac,
                                    ft[0], ft[1], ft[2], ft[3], ft[4], ft[5], 0, 0, -1));
                        }
                    }
                } catch (Throwable ignored) { }
                if (flaggedBase) base++;
                if (!flaggedBase && flaggedTwoCol) twoColOnly++;
            }
            line("  production flags                              : %d/%d = %.4f", base, prose.size(),
                    (double) base / prose.size());
            line("  ADDITIONAL pages flagged if the 2-col block   : +%d  -> %d/%d = %.4f",
                    twoColOnly, base + twoColOnly, prose.size(),
                    (double) (base + twoColOnly) / prose.size());
            line("  (i.e. opening it fully costs %+.4f prose-FP)",
                    (double) (base + twoColOnly) / prose.size() - (double) base / prose.size());
            java.util.Collections.sort(twoColGraded);
            if (!twoColGraded.isEmpty()) {
                line("  graded confidences of the %d prose 2-col candidates: min %.3f p50 %.3f p90 %.3f max %.3f",
                        twoColGraded.size(), twoColGraded.get(0),
                        twoColGraded.get(twoColGraded.size() / 2),
                        twoColGraded.get((int) (twoColGraded.size() * 0.9)),
                        twoColGraded.get(twoColGraded.size() - 1));
            }
        }
        line("");

        // ------------------------------------------------------- per-feature separability verdict
        rule();
        line("PER-FEATURE SEPARABILITY OF THE 2-COLUMN BLOCK");
        rule();
        line("  For a rule to be shippable it must admit the HELPFUL corpus rows while excluding both");
        line("  the UNHELPFUL corpus rows and the PROSE rows. A feature whose helpful range is");
        line("  CONTAINED IN the union of the other two ranges cannot do that at any threshold.");
        line("  %-10s %-28s %-28s %-28s %s", "feature", "helpful(corpus)", "unhelpful(corpus)",
                "prose", "separable?");
        String[] names = {"graded", "cc", "viol", "prose", "pcFrac", "gutFrc", "gutCov", "bothFl",
                "fill0", "fill1", "jitter", "rows"};
        for (int fi = 0; fi < names.length; fi++) {
            List<Double> h = new ArrayList<>(), u = new ArrayList<>(), pr = new ArrayList<>();
            for (TwoCol t : TWOCOL) (t.dF1Full() > 1e-9 ? h : u).add(feat(t, fi));
            for (TwoCol t : PROSE_TWOCOL) pr.add(feat(t, fi));
            line("  %-10s %-28s %-28s %-28s %s", names[fi], rangeStr(h), rangeStr(u), rangeStr(pr),
                    separable(h, u, pr));
        }
        line("");
        line("  'one-sided-clean' means SOME threshold on this feature alone admits every helpful row");
        line("  while excluding every unhelpful corpus row AND every prose row. Anything else means");
        line("  the feature cannot be the basis of a shippable rule on its own.");
        line("");

        // ------------------------------------------------- exhaustive 1- and 2-feature rule search
        rule();
        line("EXHAUSTIVE 1- AND 2-FEATURE CONJUNCTION SEARCH OVER THE 2-COLUMN BLOCK");
        rule();
        line("  Every (feature, direction, threshold) and every conjunction of two such tests, scored");
        line("  by how many HELPFUL corpus rows it admits and how many NEGATIVES (unhelpful corpus +");
        line("  prose) it admits. Thresholds are drawn from the observed values themselves, so this is");
        line("  the most generous possible search -- if nothing clean turns up here, nothing exists.");
        List<TwoCol> pos = new ArrayList<>(), negRows = new ArrayList<>();
        for (TwoCol t : TWOCOL) (t.dF1Full() > 1e-9 ? pos : negRows).add(t);
        negRows.addAll(PROSE_TWOCOL);
        line("  positives = %d helpful corpus candidates; negatives = %d (%d unhelpful corpus + %d prose)",
                pos.size(), negRows.size(), negRows.size() - PROSE_TWOCOL.size(), PROSE_TWOCOL.size());
        line("");
        line("  BEST rules, ranked by (positives captured desc, negatives admitted asc):");
        line("  %-58s %5s %5s %8s %8s", "rule", "pos", "neg", "negProse", "posGain");
        List<String> best = new ArrayList<>();
        // single tests
        record Test(int f, boolean ge, double th) {}
        List<Test> tests = new ArrayList<>();
        for (int fi = 0; fi < names.length; fi++) {
            java.util.TreeSet<Double> vals = new java.util.TreeSet<>();
            for (TwoCol t : pos) vals.add(feat(t, fi));
            for (TwoCol t : negRows) vals.add(feat(t, fi));
            for (double v : vals) { tests.add(new Test(fi, true, v)); tests.add(new Test(fi, false, v)); }
        }
        record Rule(String label, int pos, int neg, int negProse, double gain) {}
        List<Rule> rules = new ArrayList<>();
        for (Test a : tests) {
            int p = 0, n = 0, np = 0; double gain = 0;
            for (TwoCol t : pos) if (hit(t, a.f(), a.ge(), a.th())) { p++; gain += t.dF1Full(); }
            for (TwoCol t : negRows) if (hit(t, a.f(), a.ge(), a.th())) { n++; if (t.src().equals("prose")) np++; }
            if (p > 0) rules.add(new Rule(desc(names, a.f(), a.ge(), a.th()), p, n, np, gain));
        }
        for (int i = 0; i < tests.size(); i++) {
            for (int j = i + 1; j < tests.size(); j++) {
                Test a = tests.get(i), b = tests.get(j);
                if (a.f() == b.f() && a.ge() == b.ge()) continue;
                int p = 0, n = 0, np = 0; double gain = 0;
                for (TwoCol t : pos) {
                    if (hit(t, a.f(), a.ge(), a.th()) && hit(t, b.f(), b.ge(), b.th())) { p++; gain += t.dF1Full(); }
                }
                if (p == 0) continue;
                for (TwoCol t : negRows) {
                    if (hit(t, a.f(), a.ge(), a.th()) && hit(t, b.f(), b.ge(), b.th())) {
                        n++; if (t.src().equals("prose")) np++;
                    }
                }
                if (n <= 3) rules.add(new Rule(desc(names, a.f(), a.ge(), a.th()) + " AND "
                        + desc(names, b.f(), b.ge(), b.th()), p, n, np, gain));
            }
        }
        rules.sort(Comparator.comparingInt((Rule r) -> -r.pos())
                .thenComparingInt(Rule::neg).thenComparingDouble(r -> -r.gain()));
        java.util.Set<String> seen = new java.util.HashSet<>();
        int printed = 0;
        for (Rule r : rules) {
            String sig = r.pos() + "/" + r.neg();
            if (!seen.add(sig)) continue;                 // one exemplar per (pos,neg) frontier point
            line("  %-58s %5d %5d %8d %+8.4f", r.label(), r.pos(), r.neg(), r.negProse(), r.gain());
            if (++printed >= 14) break;
        }
        line("");
        line("  The frontier rule's own admitted NEGATIVES, named (does it cost prose-FP?):");
        if (!rules.isEmpty()) {
            Rule top = rules.get(0);
            line("    rule = %s", top.label());
            for (TwoCol t : negRows) {
                // re-evaluate the top rule by re-parsing is fragile; instead re-run the same two
                // tests we know produced the frontier: pcFrac<=x AND jitter>=y is generic, so just
                // report every negative that satisfies the SAME feature bounds as all positives.
                boolean all = true;
                for (int fi = 0; fi < names.length; fi++) {
                    double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
                    for (TwoCol q : pos) { lo = Math.min(lo, feat(q, fi)); hi = Math.max(hi, feat(q, fi)); }
                    double v = feat(t, fi);
                    if (v < lo || v > hi) { all = false; break; }
                }
                if (all) line("    inside EVERY positive's feature box: src=%s %s p%d rows=%d",
                        t.src(), t.doc(), t.page(), t.rows());
            }
            line("    (a negative inside the positives' full 12-feature bounding box cannot be");
            line("     excluded by ANY conjunction of one-sided tests on these features.)");
        }
        line("");
        line("  NOTE ON WHAT A CLEAN ROW WOULD MEAN: with only %d positives, ANY rule found here is",
                pos.size());
        line("  fitted to %d examples. Its thresholds carry no evidence of generalisation, and its", pos.size());
        line("  entire reachable prize is the +%.4f macro the 2-column block is worth in total.",
                byClass.getOrDefault("cols==2-nonnumeric", new double[]{0, 0})[1] / docs.size());
        line("");

        // ------------------------------------------------- frontier rule on the WIDE prose corpus
        rule();
        line("FRONTIER RULE, STRESSED ON THE WIDE REAL-WORLD CORPUS");
        rule();
        line("  Rule R (the ONLY 2-feature conjunction that captured all 4 helpful corpus candidates");
        line("  with zero prose flags on the 200-PDF sample):");
        line("     cols==2 && numericCols==0 && graded >= %.2f && proseColFrac <= 0 && col1LeftJitter >= %.3f",
                StreamTableExtractor.STREAM_CONFIDENCE_MIN, RULE_JITTER_MIN);
        line("  The 200-PDF sample resolves 0.0050 per flag -- far too coarse to certify a rule whose");
        line("  whole claim is 'costs no false positives'. Re-measured on every PDF in the corpus:");
        List<Path> wide = wideProseSample(Integer.getInteger("proseWide", 1600));
        if (wide.isEmpty()) {
            line("  wide corpus unavailable -- NOT measured, so the rule is NOT certified");
        } else {
            int base = 0, withRule = 0, ruleOnly = 0;
            for (Path p : wide) {
                boolean b = false, r = false;
                try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                    if (doc.getNumberOfPages() < 1) continue;
                    List<TextPosition> gl = TableTestPdfs.harvestGlyphs(doc, 0);
                    List<StreamTableExtractor.Candidate> cs = new ArrayList<>();
                    StreamTableExtractor.extractPage(1, gl, finder, NO_GATE,
                            StreamTableExtractor.PRODUCTION_BAR, cs);
                    for (StreamTableExtractor.Candidate c : cs) {
                        if (c.hit == null) continue;
                        if (StreamTableExtractor.acceptsGrid(c.grid)) b = true;
                        if (ruleR(c.grid)) r = true;
                    }
                } catch (Throwable ignored) { }
                if (b) base++;
                if (b || r) withRule++;
                if (!b && r) ruleOnly++;
            }
            line("  wide sample size                       : %d PDFs (1 flag = %.5f)", wide.size(),
                    1.0 / wide.size());
            line("  production flags                       : %d/%d = %.4f", base, wide.size(),
                    (double) base / wide.size());
            line("  production + rule R                    : %d/%d = %.4f", withRule, wide.size(),
                    (double) withRule / wide.size());
            line("  pages flagged ONLY because of rule R    : %d", ruleOnly);
            line("  prose-FP cost of rule R                : %+.4f",
                    (double) withRule / wide.size() - (double) base / wide.size());
        }
        line("");

        // Machine-readable feature dump, so the generalisation analysis (leave-one-out, margins) can
        // be done offline without re-running the corpus.
        StringBuilder tsv = new StringBuilder(
                "src\tdoc\tpage\trows\tgraded\tcc\tviol\tprose\tpcFrac\tgutFrc\tgutCov\tbothFl\tfill0\tfill1\tjitter\tdF1\n");
        List<TwoCol> allRows = new ArrayList<>(TWOCOL); allRows.addAll(PROSE_TWOCOL);
        for (TwoCol t : allRows) {
            tsv.append(String.format(Locale.ROOT,
                    "%s\t%s\t%d\t%d\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f\t%.6f%n",
                    t.src(), t.doc(), t.page(), t.rows(), t.graded(), t.cc(), t.viol(), t.prose(),
                    t.proseColFrac(), t.gutFracOfBand(), t.gutCoverFrac(), t.bothFilledFrac(),
                    t.col0FillMean(), t.col1FillMean(), t.col1LeftJitter(), t.dF1Full()));
        }
        Files.writeString(Path.of(System.getProperty("lever3Tsv", "target/lever3-twocol.tsv")),
                tsv.toString(), StandardCharsets.UTF_8);

        String path = System.getProperty("lever3Out", "target/lever3-residual.md");
        Files.writeString(Path.of(path), out.toString(), StandardCharsets.UTF_8);
        System.out.println("Report written to " + path);
    }

    /** The jitter threshold the exhaustive search picked -- exactly the SMALLEST value observed on
     *  the four helpful corpus candidates, i.e. a threshold with a single supporting example. */
    static final double RULE_JITTER_MIN = 0.071;

    /** Rule R: the frontier 2-feature conjunction from the exhaustive search, applied to a grid the
     *  production gate hard-rejected as 2-column all-non-numeric. */
    private static boolean ruleR(StreamTableExtractor.Grid g) {
        if (!"cols==2-nonnumeric".equals(g.hardReject)) return false;
        if (gradedOf(g) < StreamTableExtractor.STREAM_CONFIDENCE_MIN) return false;
        if (g.tProseColFrac > 0) return false;
        return twoColFeatures(g)[5] >= RULE_JITTER_MIN;
    }

    private static List<Path> wideProseSample(int cap) throws java.io.IOException {
        Path root = Path.of("/home/coz/Downloads/phishpdfs");
        if (!Files.isDirectory(root)) return List.of();
        List<Path> all;
        try (java.util.stream.Stream<Path> s = Files.list(root)) {
            all = s.filter(Files::isRegularFile).sorted().toList();
        }
        List<Path> out = new ArrayList<>();
        for (Path p : all) {
            if (out.size() >= cap) break;
            try (java.io.InputStream in = Files.newInputStream(p)) {
                byte[] b = new byte[5];
                int n = in.read(b);
                if (n >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') out.add(p);
            } catch (java.io.IOException ignored) { }
        }
        return out;
    }

    /** Does page 1 of {@code pdf} yield a stream table under the two-tier bar {@code bar}? */
    private static boolean proseFlagged(GutterFinder finder, Path pdf, double[] bar) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (doc.getNumberOfPages() < 1) return false;
            List<TextPosition> gl = TableTestPdfs.harvestGlyphs(doc, 0);
            StreamTableExtractor.ConfidenceBar b =
                    c -> c >= StreamTableExtractor.WIDE_GRID_MIN_COLS ? bar[1] : bar[0];
            return !StreamTableExtractor.extractPage(1, gl, finder, b,
                    StreamTableExtractor.PRODUCTION_BAR, null).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static double feat(TwoCol t, int i) {
        switch (i) {
            case 0: return t.graded();
            case 1: return t.cc();
            case 2: return t.viol();
            case 3: return t.prose();
            case 4: return t.proseColFrac();
            case 5: return t.gutFracOfBand();
            case 6: return t.gutCoverFrac();
            case 7: return t.bothFilledFrac();
            case 8: return t.col0FillMean();
            case 9: return t.col1FillMean();
            case 10: return t.col1LeftJitter();
            default: return t.rows();
        }
    }

    private static boolean hit(TwoCol t, int f, boolean ge, double th) {
        double v = feat(t, f);
        return ge ? v >= th : v <= th;
    }

    private static String desc(String[] names, int f, boolean ge, double th) {
        return String.format(Locale.ROOT, "%s%s%.3f", names[f], ge ? ">=" : "<=", th);
    }

    private static String rangeStr(List<Double> v) {
        if (v.isEmpty()) return "n=0";
        double min = v.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double max = v.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double mean = v.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        return String.format(Locale.ROOT, "n=%d [%.3f,%.3f] mu=%.3f", v.size(), min, max, mean);
    }

    /** Is there a single one-sided threshold on this feature that admits every helpful row and
     *  excludes every unhelpful corpus row AND every prose row? */
    private static String separable(List<Double> h, List<Double> u, List<Double> pr) {
        if (h.isEmpty()) return "no helpful rows";
        List<Double> neg = new ArrayList<>(u); neg.addAll(pr);
        if (neg.isEmpty()) return "no negatives";
        double hMin = h.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double hMax = h.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double nMin = neg.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        double nMax = neg.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        boolean geClean = hMin > nMax;   // "feature >= t" admits all helpful, no negatives
        boolean leClean = hMax < nMin;   // "feature <= t" ditto
        if (geClean) return "YES one-sided-clean (>= t)";
        if (leClean) return "YES one-sided-clean (<= t)";
        // How many negatives would a "capture all helpful" threshold let in, on the better side?
        int inGe = 0, inLe = 0;
        for (double n : neg) { if (n >= hMin) inGe++; if (n <= hMax) inLe++; }
        return String.format(Locale.ROOT, "no  (>=hMin admits %d/%d neg; <=hMax admits %d/%d)",
                inGe, neg.size(), inLe, neg.size());
    }

    private static String shortId(String id) {
        int i = id.lastIndexOf('/');
        return i < 0 ? id : id.substring(Math.max(0, id.lastIndexOf('/', i - 1) + 1));
    }
}
