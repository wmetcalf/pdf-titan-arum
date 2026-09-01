// Physically under src/test/java/com/oai/titanarum/bakeoff/ but declares `package com.oai.titanarum;`
// (same convention/reason as BakeOffHarness / GateOracleHarness).
//
// PURPOSE. The content dump (Lever3ContentProbe) showed that the two GENUINE tables among the
// gate-rejected 2-column candidates (eu-006 p2, eu-014 p2) both have a numeric data column whose
// numeric FRACTION is diluted below scoreGrid's 0.70 bar by the column's own multi-word HEADER label
// ("Own Brands Market Shares" over 28%/25%/23%..., "Weight of indicator in 2006" over 40/15/13/...).
// This probe measures, for every 2-column all-non-numeric candidate on the corpus AND on the
// real-world prose sample, what the numeric fraction would be under alternative definitions that do
// not count header/label text as data -- so the separability of a principled numeric test can be
// judged before any production change.
//
//   mvn -q -o test -Dtest=Lever3NumericProbe -Dlever3Num=true
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

class Lever3NumericProbe {

    private static final StreamTableExtractor.ConfidenceBar NO_GATE = c -> -1e9;

    /** The four candidates the FULL-pipeline oracle would add, per Lever3ResidualHarness. */
    private static final List<String> HELPFUL = List.of(
            "eu-014#2", "us-011a#2", "us-011a#3", "eu-006#2");

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lever3Num"), "set -Dlever3Num=true");
        GutterFinder finder = new BreuelGutterFinder();
        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);

        System.out.printf(Locale.ROOT, "%n%-30s %5s %5s %8s %8s %8s %8s %8s %s%n",
                "candidate", "rows", "cols", "allWord", "skipR0", "rowMaj", "rowMajR0", "anyCol", "verdict");
        for (BakeOffHarness.ScoreUnit u : corpus.units) {
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                    List<TextPosition> gl = TableTestPdfs.harvestGlyphs(doc, p - 1);
                    List<StreamTableExtractor.Candidate> cs = new ArrayList<>();
                    try {
                        StreamTableExtractor.extractPage(p, gl, finder, NO_GATE,
                                StreamTableExtractor.PRODUCTION_BAR, cs);
                    } catch (Throwable ignored) { }
                    for (StreamTableExtractor.Candidate c : cs) {
                        if (c.hit == null || !"cols==2-nonnumeric".equals(c.grid.hardReject)) continue;
                        report(shortName(u.id()) + "#" + p, c.grid, "corpus");
                    }
                }
            } catch (Throwable ignored) { }
        }
        List<Path> prose = BakeOffHarness.sampleProsePdfs();
        if (prose != null) {
            for (Path p : prose) {
                try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                    if (doc.getNumberOfPages() < 1) continue;
                    List<TextPosition> gl = TableTestPdfs.harvestGlyphs(doc, 0);
                    List<StreamTableExtractor.Candidate> cs = new ArrayList<>();
                    StreamTableExtractor.extractPage(1, gl, finder, NO_GATE,
                            StreamTableExtractor.PRODUCTION_BAR, cs);
                    for (StreamTableExtractor.Candidate c : cs) {
                        if (c.hit == null || !"cols==2-nonnumeric".equals(c.grid.hardReject)) continue;
                        report("PROSE:" + p.getFileName(), c.grid, "prose");
                    }
                } catch (Throwable ignored) { }
            }
        }
    }

    private static void report(String id, StreamTableExtractor.Grid g, String src) {
        double[] m = metrics(g);
        String verdict = HELPFUL.stream().anyMatch(id::startsWith) ? "ORACLE-HELPFUL"
                : src.equals("prose") ? "prose" : "corpus-unhelpful";
        System.out.printf(Locale.ROOT, "%-30s %5d %5d %8.3f %8.3f %8.3f %8.3f %8s %s%n",
                id, g.nRows, g.nCols, m[0], m[1], m[2], m[3], m[4] > 0 ? "yes" : "no", verdict);
    }

    /**
     * Four alternative numeric-fraction definitions for the BEST column of a grid:
     *   [0] allWord  = production's own: numeric words / all words in the column
     *   [1] skipR0   = same, but the grid's first row (the header band) excluded
     *   [2] rowMaj   = fraction of the column's OCCUPIED ROWS whose content is entirely numeric
     *   [3] rowMajR0 = rowMaj with the first row excluded
     *   [4] anyCol   = 1 if any column reaches 0.70 under rowMajR0
     */
    private static double[] metrics(StreamTableExtractor.Grid g) {
        float[] b = g.colBounds;
        int cols = b.length - 1;
        double bestAll = 0, bestSkip = 0, bestRow = 0, bestRowSkip = 0;
        for (int c = 0; c < cols; c++) {
            int totW = 0, numW = 0, totWs = 0, numWs = 0;
            int occRows = 0, numRows = 0, occRowsS = 0, numRowsS = 0;
            for (int li = 0; li < g.rows.size(); li++) {
                StreamTableExtractor.Line l = g.rows.get(li);
                int inCol = 0, inColNum = 0;
                for (StreamTableExtractor.Word w : l.words) {
                    if (colOf(w.cx(), b) != c) continue;
                    inCol++; if (w.numeric) inColNum++;
                    totW++; if (w.numeric) numW++;
                    if (li > 0) { totWs++; if (w.numeric) numWs++; }
                }
                if (inCol > 0) {
                    occRows++; if (inColNum == inCol) numRows++;
                    if (li > 0) { occRowsS++; if (inColNum == inCol) numRowsS++; }
                }
            }
            if (totW > 0) bestAll = Math.max(bestAll, (double) numW / totW);
            if (totWs > 0) bestSkip = Math.max(bestSkip, (double) numWs / totWs);
            if (occRows > 0) bestRow = Math.max(bestRow, (double) numRows / occRows);
            if (occRowsS > 0) bestRowSkip = Math.max(bestRowSkip, (double) numRowsS / occRowsS);
        }
        return new double[]{bestAll, bestSkip, bestRow, bestRowSkip, bestRowSkip >= 0.70 ? 1 : 0};
    }

    private static int colOf(float x, float[] bounds) {
        for (int c = 0; c < bounds.length - 1; c++) if (x < bounds[c + 1]) return c;
        return bounds.length - 2;
    }

    private static String shortName(String id) {
        int i = id.lastIndexOf('/');
        String f = i < 0 ? id : id.substring(i + 1);
        return f.endsWith(".pdf") ? f.substring(0, f.length() - 4) : f;
    }
}
