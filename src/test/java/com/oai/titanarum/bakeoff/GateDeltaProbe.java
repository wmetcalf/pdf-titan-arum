// Diagnostic probe (gated -DgateDelta=true): per-document pooled adjacency F1 for the FLAT gate
// (0.55 everywhere) versus the two-tier gate (0.40 from WIDE_GRID_MIN_COLS columns up), for both the
// stream path alone and the shipped full pipeline (tagged+lattice+non-overlapping stream). Exists
// because the two aggregates moved very differently and the reason had to be located, not guessed.
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class GateDeltaProbe {

    private static final StreamTableExtractor.ConfidenceBar FLAT =
            c -> StreamTableExtractor.STREAM_CONFIDENCE_MIN;
    private static final StreamTableExtractor.ConfidenceBar TWO_TIER = StreamTableExtractor.PRODUCTION_BAR;

    private record Row(String id, boolean borderless, double streamFlat, double streamTwo,
                       double fullFlat, double fullTwo, int hitsFlat, int hitsTwo) {}

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("gateDelta"), "set -DgateDelta=true");
        GutterFinder finder = new BreuelGutterFinder();
        StringBuilder notes = new StringBuilder();
        List<Row> rows = new ArrayList<>();

        for (BakeOffHarness.ScoreUnit u : BakeOffHarness.buildScoringSet(notes).units) {
            List<GroundTruth.Table> kept = GtDedup.dedup(u.expected()).kept();
            List<TableScore.Relation> gt = new ArrayList<>();
            for (GroundTruth.Table t : kept) {
                gt.addAll(TableScore.buildOfficialRelations(
                        TableScore.gridCellsFromGroundTruth(t), false).relations());
            }
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                int pages = doc.getNumberOfPages();
                List<Integer> pageList = new ArrayList<>();
                Map<Integer, List<TextPosition>> glyphs = new LinkedHashMap<>();
                Map<Integer, PDRectangle> crop = new LinkedHashMap<>();
                for (int p = 1; p <= pages; p++) {
                    pageList.add(p);
                    glyphs.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
                    crop.put(p, doc.getPage(p - 1).getCropBox());
                }
                List<TableExtractor.TableHit> tl = new ArrayList<>();
                try { tl.addAll(TableExtractor.extract(doc, pageList, glyphs).tables); } catch (Throwable ignored) {}

                List<TableExtractor.TableHit> sFlat = stream(pageList, glyphs, finder, FLAT);
                List<TableExtractor.TableHit> sTwo  = stream(pageList, glyphs, finder, TWO_TIER);

                rows.add(new Row(shortId(u.id()), tl.isEmpty(),
                        f1(gt, sFlat), f1(gt, sTwo),
                        f1(gt, full(tl, sFlat)), f1(gt, full(tl, sTwo)),
                        sFlat.size(), sTwo.size()));
            } catch (Throwable ignored) { }
        }

        System.out.printf(Locale.ROOT, "docs=%d%n", rows.size());
        report("STREAM ALONE", rows, Row::streamFlat, Row::streamTwo);
        report("FULL PIPELINE", rows, Row::fullFlat, Row::fullTwo);

        System.out.println("=== per-document FULL-pipeline movers (|delta| >= 0.001) ===");
        System.out.printf(Locale.ROOT, "  %-34s %4s %8s %8s %9s %8s %8s %9s %5s%n",
                "doc", "bl", "sFlat", "sTwo", "dStream", "fFlat", "fTwo", "dFull", "hits");
        rows.sort(Comparator.comparingDouble(r -> r.fullTwo() - r.fullFlat()));
        for (Row r : rows) {
            double d = r.fullTwo() - r.fullFlat();
            if (Math.abs(d) < 0.001) continue;
            System.out.printf(Locale.ROOT, "  %-34s %4s %8.4f %8.4f %+9.4f %8.4f %8.4f %+9.4f %2d/%2d%n",
                    r.id(), r.borderless() ? "y" : "n", r.streamFlat(), r.streamTwo(),
                    r.streamTwo() - r.streamFlat(), r.fullFlat(), r.fullTwo(), d,
                    r.hitsFlat(), r.hitsTwo());
        }
    }

    private static void report(String label, List<Row> rows,
                               java.util.function.ToDoubleFunction<Row> a,
                               java.util.function.ToDoubleFunction<Row> b) {
        double ma = rows.stream().mapToDouble(a).average().orElse(0);
        double mb = rows.stream().mapToDouble(b).average().orElse(0);
        long up = rows.stream().filter(r -> b.applyAsDouble(r) > a.applyAsDouble(r) + 1e-9).count();
        long dn = rows.stream().filter(r -> b.applyAsDouble(r) < a.applyAsDouble(r) - 1e-9).count();
        System.out.printf(Locale.ROOT,
                "%-15s MACRO flat=%.4f twoTier=%.4f delta=%+.4f   improved=%d regressed=%d%n",
                label, ma, mb, mb - ma, up, dn);
        // borderless / non-borderless split
        for (boolean bl : new boolean[]{true, false}) {
            List<Row> sub = rows.stream().filter(r -> r.borderless() == bl).toList();
            if (sub.isEmpty()) continue;
            System.out.printf(Locale.ROOT, "   %-13s n=%2d flat=%.4f twoTier=%.4f delta=%+.4f%n",
                    bl ? "borderless" : "lattice-covered", sub.size(),
                    sub.stream().mapToDouble(a).average().orElse(0),
                    sub.stream().mapToDouble(b).average().orElse(0),
                    sub.stream().mapToDouble(b).average().orElse(0)
                            - sub.stream().mapToDouble(a).average().orElse(0));
        }
    }

    private static List<TableExtractor.TableHit> stream(List<Integer> pages,
            Map<Integer, List<TextPosition>> glyphs, GutterFinder f,
            StreamTableExtractor.ConfidenceBar bar) {
        List<TableExtractor.TableHit> out = new ArrayList<>();
        for (int p : pages) out.addAll(StreamTableExtractor.extractPage(p, glyphs.get(p), f, bar, bar, null));
        return out;
    }

    private static List<TableExtractor.TableHit> full(List<TableExtractor.TableHit> tl,
                                                     List<TableExtractor.TableHit> stream) {
        List<TableExtractor.TableHit> out = new ArrayList<>(tl);
        for (TableExtractor.TableHit s : stream) {
            if (!MetricFixHarness.overlapsSubstantially(s, tl)) out.add(s);
        }
        return out;
    }

    private static double f1(List<TableScore.Relation> gt, List<TableExtractor.TableHit> hits) {
        List<TableScore.Relation> det = new ArrayList<>();
        for (TableExtractor.TableHit h : hits) {
            det.addAll(TableScore.buildOfficialRelations(MetricFixHarness.cellsOf(h), false).relations());
        }
        TableScore.AdjResult r = TableScore.compareRelations(gt, det, TableScore.Semantics.MULTISET);
        if (r.gtTotal() == 0 && r.detectedTotal() == 0) return 0;
        if (r.matched() == 0) return 0;
        double p = (double) r.matched() / r.detectedTotal();
        double rc = (double) r.matched() / r.gtTotal();
        return 2 * p * rc / (p + rc);
    }

    private static String shortId(String id) {
        int i = id.lastIndexOf('/');
        return i < 0 ? id : id.substring(Math.max(0, id.lastIndexOf('/', i - 1) + 1));
    }
}
