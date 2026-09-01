// Physically under src/test/java/com/oai/titanarum/bakeoff/ but declares `package com.oai.titanarum;`
// (same convention/reason as BakeOffHarness and GateOracleHarness).
//
// PURPOSE. Mechanism check for the 2-column-block rule: dump the actual extracted cell text of the
// 2-column all-non-numeric candidates the gate hard-rejects, split by the col1-left-edge jitter
// signal, so the rule can be judged on what it is physically separating rather than on the score it
// produces. Read-only diagnostic, gated by -Dlever3Content=true.
//
//   mvn -q -o test -Dtest=Lever3ContentProbe -Dlever3Content=true -Dlever3Docs=eu-014,us-011a,eu-006
package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class Lever3ContentProbe {

    private static final StreamTableExtractor.ConfidenceBar NO_GATE = c -> -1e9;

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lever3Content"), "set -Dlever3Content=true");
        String want = System.getProperty("lever3Docs", "eu-014,us-011a,eu-006");
        List<String> wanted = List.of(want.split(","));
        GutterFinder finder = new BreuelGutterFinder();
        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);

        for (BakeOffHarness.ScoreUnit u : corpus.units) {
            boolean match = false;
            for (String w : wanted) if (u.id().contains(w.trim())) match = true;
            if (!match) continue;
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                Map<Integer, List<TextPosition>> glyphs = new LinkedHashMap<>();
                for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                    glyphs.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
                }
                List<StreamTableExtractor.Candidate> cands = new ArrayList<>();
                for (int p : glyphs.keySet()) {
                    try {
                        StreamTableExtractor.extractPage(p, glyphs.get(p), finder, NO_GATE,
                                StreamTableExtractor.PRODUCTION_BAR, cands);
                    } catch (Throwable ignored) { }
                }
                for (StreamTableExtractor.Candidate c : cands) {
                    if (c.hit == null) continue;
                    if (!"cols==2-nonnumeric".equals(c.grid.hardReject)) continue;
                    double jit = jitter(c.grid);
                    System.out.printf(Locale.ROOT,
                            "%n=== %s p%d  rows=%d  jitter=%.3f  proseColFrac=%.2f ===%n",
                            u.id(), c.page, c.grid.nRows, jit, c.grid.tProseColFrac);
                    List<List<String>> cells = rowsOf(c.hit);
                    for (int i = 0; i < cells.size() && i < 14; i++) {
                        System.out.printf(Locale.ROOT, "   %2d | %s%n", i,
                                String.join("  ||  ", cells.get(i)));
                    }
                }
            }
        }
    }

    private static double jitter(StreamTableExtractor.Grid g) {
        float[] b = g.colBounds;
        List<Double> lefts = new ArrayList<>();
        for (StreamTableExtractor.Line l : g.rows) {
            float min1 = Float.MAX_VALUE;
            boolean any = false;
            for (StreamTableExtractor.Word w : l.words) {
                if (w.cx() >= b[1]) { any = true; min1 = Math.min(min1, w.x0); }
            }
            if (any) lefts.add((double) min1);
        }
        if (lefts.size() < 2) return 0;
        double mean = lefts.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double var = 0;
        for (double v : lefts) var += (v - mean) * (v - mean);
        return Math.sqrt(var / lefts.size()) / Math.max(1e-6f, b[2] - b[1]);
    }

    private static List<List<String>> rowsOf(TableExtractor.TableHit h) {
        Map<Integer, Map<Integer, String>> byRow = new java.util.TreeMap<>();
        for (com.oai.titanarum.bakeoff.TableScore.GridCell c : MetricFixHarness.cellsOf(h)) {
            byRow.computeIfAbsent(c.startRow(), x -> new java.util.TreeMap<>()).put(c.startCol(), c.text());
        }
        List<List<String>> out = new ArrayList<>();
        for (Map<Integer, String> r : byRow.values()) out.add(new ArrayList<>(r.values()));
        return out;
    }
}
