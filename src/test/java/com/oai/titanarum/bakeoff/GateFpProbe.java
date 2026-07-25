// Diagnostic probe (gated -DgateFp=true): measures the stream path's prose false-positive rate over
// the WHOLE real-world sample rather than the 200-PDF official sub-sample, at several emit
// thresholds, and NAMES the files each threshold newly flags so they can be inspected by hand. The
// project's headline prose-FP number quantises at 1/200 = 0.0050, which is too coarse to judge a
// change worth ~0.02 macro F1.
package com.oai.titanarum;

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
import java.util.TreeMap;

class GateFpProbe {

    private static final double[] THS = {0.35, 0.40, 0.45, 0.48, 0.50, 0.55, 0.60};

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("gateFp"), "set -DgateFp=true to run");
        List<Path> all = pdfs(Path.of("/home/coz/Downloads/phishpdfs"),
                             Integer.getInteger("gateFpCap", 1600));
        System.out.printf(Locale.ROOT, "corpus: %d real PDFs%n", all.size());

        GutterFinder finder = new BreuelGutterFinder();
        // best confidence any page-1 candidate reached, per file
        Map<Path, Double> best = new LinkedHashMap<>();
        Map<Path, Integer> bestCols = new LinkedHashMap<>();
        Map<Path, String> bestReject = new LinkedHashMap<>();
        for (Path p : all) {
            double b = -1; int cols = 0; String rej = "-";
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                if (doc.getNumberOfPages() >= 1) {
                    List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
                    List<StreamTableExtractor.Candidate> cands = new ArrayList<>();
                    StreamTableExtractor.extractPage(1, glyphs, finder, c -> -1e9,
                            StreamTableExtractor.PRODUCTION_BAR, cands);
                    for (StreamTableExtractor.Candidate c : cands) {
                        if (c.hit == null) continue;
                        if (c.confidence > b) {
                            b = c.confidence; cols = c.grid.nCols;
                            rej = c.grid.hardReject == null ? "-" : c.grid.hardReject;
                        }
                    }
                }
            } catch (Throwable ignored) { }
            best.put(p, b); bestCols.put(p, cols); bestReject.put(p, rej);
        }

        System.out.println("=== prose FP over the full sample ===");
        System.out.printf(Locale.ROOT, "  %-8s %8s %8s%n", "th", "flagged", "rate");
        for (double th : THS) {
            long n = best.values().stream().filter(v -> v >= th).count();
            System.out.printf(Locale.ROOT, "  %-8.2f %8d %8.4f%n", th, n, (double) n / all.size());
        }

        System.out.println("=== files newly flagged when the emit gate drops 0.55 -> lower ===");
        for (double th : new double[]{0.50, 0.48, 0.45, 0.40, 0.35}) {
            System.out.printf(Locale.ROOT, "-- th=%.2f (was rejected at 0.55) --%n", th);
            for (Map.Entry<Path, Double> e : best.entrySet()) {
                double v = e.getValue();
                if (v >= th && v < 0.55) {
                    System.out.printf(Locale.ROOT, "   %-42s conf=%.3f cols=%d%n",
                            e.getKey().getFileName(), v, bestCols.get(e.getKey()));
                }
            }
        }

        System.out.println("=== confidence histogram of the best page-1 candidate per file ===");
        Map<String, Integer> hist = new TreeMap<>();
        for (double v : best.values()) {
            String k = v < 0 ? "(no candidate)" : String.format(Locale.ROOT, "%.1f", Math.floor(v * 10) / 10.0);
            hist.merge(k, 1, Integer::sum);
        }
        hist.forEach((k, v) -> System.out.printf(Locale.ROOT, "  %-16s %d%n", k, v));
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
