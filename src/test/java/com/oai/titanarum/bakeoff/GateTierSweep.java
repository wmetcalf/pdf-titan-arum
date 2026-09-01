// Diagnostic sweep (gated -DgateTier=true): for each candidate two-tier gate (wide bar W applied
// from C columns up, flat 0.55 below), measures BOTH aggregates that matter -- the stream path alone
// (where the gate lives) and the shipped full pipeline (tagged+lattice+non-overlapping stream) --
// under the project's primary protocol (document-POOLED official adjacency relations, de-duplicated
// GT, MACRO first), AND the real-world prose false-positive rate over the whole 1,599-PDF sample.
//
// Exists because the two corpus aggregates move differently: a wide bar from 6 columns up admits both
// correct wide tables and one wide prose-plus-table blob (eu-002 p1), and the sweep is how the
// column threshold gets chosen on evidence rather than on the first number that looked good.
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class GateTierSweep {

    private record Cfg(int minCols, double wideBar) {
        StreamTableExtractor.ConfidenceBar bar() {
            return c -> c >= minCols ? wideBar : StreamTableExtractor.STREAM_CONFIDENCE_MIN;
        }
        public String toString() {
            return minCols == Integer.MAX_VALUE ? "FLAT 0.55"
                    : String.format(Locale.ROOT, "cols>=%d @ %.2f", minCols, wideBar);
        }
    }

    private static final List<Cfg> CFGS = new ArrayList<>(List.of(
            new Cfg(Integer.MAX_VALUE, 0),      // production before this work
            new Cfg(5, 0.40), new Cfg(6, 0.40), new Cfg(7, 0.40), new Cfg(8, 0.40),
            new Cfg(6, 0.45), new Cfg(7, 0.45), new Cfg(7, 0.35), new Cfg(7, 0.30)));

    /** One document's pooled tally under one config, for one hit set. */
    private record Tal(long m, long d, long g) {}

    private static Tal tal(List<TableScore.Relation> gt, List<TableExtractor.TableHit> hits) {
        List<TableScore.Relation> det = new ArrayList<>();
        for (TableExtractor.TableHit h : hits) {
            det.addAll(TableScore.buildOfficialRelations(MetricFixHarness.cellsOf(h), false).relations());
        }
        TableScore.AdjResult r = TableScore.compareRelations(gt, det, TableScore.Semantics.MULTISET);
        return new Tal(r.matched(), r.detectedTotal(), r.gtTotal());
    }

    private static double f1(long m, long d, long g) {
        if (m == 0) return 0;
        double p = d == 0 ? 0 : (double) m / d, rc = g == 0 ? 0 : (double) m / g;
        return (p + rc) == 0 ? 0 : 2 * p * rc / (p + rc);
    }

    private static final class Agg {
        long m, d, g; final List<Double> per = new ArrayList<>();
        void add(Tal t) { if (t.g() == 0 && t.d() == 0) return; m += t.m(); d += t.d(); g += t.g();
                          per.add(f1(t.m(), t.d(), t.g())); }
        double macro() { return per.isEmpty() ? 0 : per.stream().mapToDouble(x -> x).average().orElse(0); }
        double micro() { return f1(m, d, g); }
    }

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("gateTier"), "set -DgateTier=true");
        GutterFinder finder = new BreuelGutterFinder();
        StringBuilder notes = new StringBuilder();

        Map<Cfg, Agg> streamAll = new LinkedHashMap<>(), fullAll = new LinkedHashMap<>();
        Map<Cfg, Agg> streamBl = new LinkedHashMap<>(), fullBl = new LinkedHashMap<>();
        for (Cfg c : CFGS) {
            streamAll.put(c, new Agg()); fullAll.put(c, new Agg());
            streamBl.put(c, new Agg());  fullBl.put(c, new Agg());
        }

        for (BakeOffHarness.ScoreUnit u : BakeOffHarness.buildScoringSet(notes).units) {
            List<TableScore.Relation> gt = new ArrayList<>();
            for (GroundTruth.Table t : GtDedup.dedup(u.expected()).kept()) {
                gt.addAll(TableScore.buildOfficialRelations(
                        TableScore.gridCellsFromGroundTruth(t), false).relations());
            }
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                List<Integer> pages = new ArrayList<>();
                Map<Integer, List<TextPosition>> glyphs = new LinkedHashMap<>();
                for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                    pages.add(p); glyphs.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
                }
                List<TableExtractor.TableHit> tl = new ArrayList<>();
                try { tl.addAll(TableExtractor.extract(doc, pages, glyphs).tables); } catch (Throwable ignored) {}
                boolean bl = tl.isEmpty();
                for (Cfg c : CFGS) {
                    List<TableExtractor.TableHit> s = new ArrayList<>();
                    for (int p : pages) {
                        s.addAll(StreamTableExtractor.extractPage(p, glyphs.get(p), finder,
                                c.bar(), c.bar(), null));
                    }
                    List<TableExtractor.TableHit> full = new ArrayList<>(tl);
                    for (TableExtractor.TableHit h : s) {
                        if (!MetricFixHarness.overlapsSubstantially(h, tl)) full.add(h);
                    }
                    Tal ts = tal(gt, s), tf = tal(gt, full);
                    streamAll.get(c).add(ts); fullAll.get(c).add(tf);
                    if (bl) { streamBl.get(c).add(ts); fullBl.get(c).add(tf); }
                }
            } catch (Throwable ignored) { }
        }

        // ---------------------------------------------------------------- real-world FP per config
        List<Path> real = pdfs(Path.of("/home/coz/Downloads/phishpdfs"), Integer.getInteger("tierCap", 1600));
        Map<Cfg, Integer> fp = new LinkedHashMap<>();
        for (Cfg c : CFGS) fp.put(c, 0);
        for (Path p : real) {
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                if (doc.getNumberOfPages() < 1) continue;
                List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
                for (Cfg c : CFGS) {
                    if (!StreamTableExtractor.extractPage(1, glyphs, finder, c.bar(), c.bar(), null).isEmpty()) {
                        fp.merge(c, 1, Integer::sum);
                    }
                }
            } catch (Throwable ignored) { }
        }

        System.out.printf(Locale.ROOT, "%nreal-world FP sample: %d PDFs, page 1%n%n", real.size());
        System.out.printf(Locale.ROOT, "  %-16s | %-17s | %-17s | %-17s | %-17s | %s%n",
                "gate", "stream ALL-77", "full ALL-77", "stream bl-22", "full bl-22", "proseFP");
        System.out.printf(Locale.ROOT, "  %-16s | %8s %8s | %8s %8s | %8s %8s | %8s %8s | %s%n",
                "", "MACRO", "micro", "MACRO", "micro", "MACRO", "micro", "MACRO", "micro", "");
        for (Cfg c : CFGS) {
            System.out.printf(Locale.ROOT,
                    "  %-16s | %8.4f %8.4f | %8.4f %8.4f | %8.4f %8.4f | %8.4f %8.4f | %3d/%d=%.4f%n",
                    c.toString(),
                    streamAll.get(c).macro(), streamAll.get(c).micro(),
                    fullAll.get(c).macro(), fullAll.get(c).micro(),
                    streamBl.get(c).macro(), streamBl.get(c).micro(),
                    fullBl.get(c).macro(), fullBl.get(c).micro(),
                    fp.get(c), real.size(), (double) fp.get(c) / real.size());
        }
    }

    private static List<Path> pdfs(Path root, int cap) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        List<Path> all;
        try (java.util.stream.Stream<Path> s = Files.list(root)) {
            all = s.filter(Files::isRegularFile).sorted().toList();
        }
        List<Path> out = new ArrayList<>();
        for (Path p : all) {
            if (out.size() >= cap) break;
            try (InputStream in = Files.newInputStream(p)) {
                byte[] b = new byte[5];
                int n = in.read(b);
                if (n >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') out.add(p);
            } catch (IOException ignored) { }
        }
        return out;
    }
}
