// Physically under src/test/java/com/oai/titanarum/bakeoff/ but declares `package com.oai.titanarum;`
// (same convention/reason as BakeOffHarness / Lever3NumericProbe).
//
// PURPOSE (CEILING MEASUREMENT, no production change involved). Before implementing the
// "chop over-clumped lattice cells by column alignment" lever, measure whether the defect it
// targets EXISTS on the 77-PDF scoring corpus at all, and if so how much text it holds.
//
// The defect: in an under-ruled band (typically a TOTAL row whose interior vertical rulings stop
// short) the lattice grid produces one WIDE cell instead of N narrow ones, so several
// column-aligned numbers land in a single cell. The detector below is exactly the candidate test
// the production fix would use -- a lattice cell with colSpan >= 2, >= 2 whitespace-separated
// tokens, and >= 2 of the table's OWN resolved columns inside its span -- plus the numeric-majority
// guard. It performs NO split; it only counts and dumps, so the corpus ceiling can be judged
// before a build.
//
//   mvn -q -o test -Dtest=ClumpProbe -DclumpProbe=true
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class ClumpProbe {

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("clumpProbe"), "set -DclumpProbe=true");
        StringBuilder notes = new StringBuilder();
        List<BakeOffHarness.ScoreUnit> units = BakeOffHarness.buildScoringSet(notes).units;
        int docsWithCandidate = 0, totalCandidates = 0, numericMajority = 0, tokensRecoverable = 0;
        int[] oneLineCandidates = {0};
        java.util.Set<String> oneLineDocs = new java.util.LinkedHashSet<>();
        for (BakeOffHarness.ScoreUnit u : units) {
            int docCand = 0;
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                List<Integer> pages = new ArrayList<>();
                for (int p = 1; p <= doc.getNumberOfPages(); p++) pages.add(p);
                Map<Integer, List<TextPosition>> byPage = new LinkedHashMap<>();
                for (int p : pages) byPage.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
                TableExtractor.Result r = TableExtractor.extract(doc, pages, byPage);
                for (TableExtractor.TableHit t : r.tables) {
                    if (!"lattice".equals(t.extractionMethod) || t.cells == null) continue;
                    float[][] colBounds = resolveColumns(t);
                    for (TableExtractor.CellHit c : t.cells) {
                        if (c.colSpan < 2 || c.text == null) continue;
                        String[] toks = c.text.trim().split("\\s+");
                        if (toks.length < 2) continue;
                        int resolved = 0;
                        for (int j = c.col; j < c.col + c.colSpan && j < t.colCount; j++) {
                            if (!Float.isNaN(colBounds[0][j])) resolved++;
                        }
                        if (resolved < 2) continue;
                        docCand++;
                        totalCandidates++;
                        int num = 0;
                        for (String tk : toks) if (isNumeric(tk)) num++;
                        boolean oneLine = c.text.indexOf('\n') < 0;
                        if (num * 2 > toks.length) {
                            numericMajority++;
                            tokensRecoverable += toks.length;
                            System.out.printf(Locale.ROOT,
                                    "CAND %s p%d r%d c%d span%d resolved=%d num=%d/%d lines=%s "
                                            + "text=[%s]%n",
                                    u.id(), t.page, c.row, c.col, c.colSpan, resolved,
                                    num, toks.length, oneLine ? "1" : "N",
                                    c.text.replace('\n', '/'));
                            if (oneLine) {
                                oneLineCandidates[0]++;
                                oneLineDocs.add(u.id());
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                System.out.println("SKIP " + u.id() + ": " + e);
            }
            if (docCand > 0) docsWithCandidate++;
        }
        System.out.printf(Locale.ROOT,
                "%nCLUMP PROBE: docs=%d docsWithCandidate=%d candidates=%d numericMajority=%d "
                        + "tokensInNumericMajorityCandidates=%d oneLineNumericMajority=%d "
                        + "oneLineDocs=%d%n",
                units.size(), docsWithCandidate, totalCandidates, numericMajority, tokensRecoverable,
                oneLineCandidates[0], oneLineDocs.size());
        System.out.println("ONE-LINE DOCS: " + oneLineDocs);
        // GroundTruth is imported only so this file's package-private access pattern matches the
        // sibling probes; touch it so the import is not flagged unused by a strict build.
        assert GroundTruth.class != null;
    }

    /** [0][j] = left edge, [1][j] = right edge of column j, NaN when no single-span cell resolves it. */
    private static float[][] resolveColumns(TableExtractor.TableHit t) {
        float[] l = new float[t.colCount], r = new float[t.colCount];
        java.util.Arrays.fill(l, Float.NaN);
        java.util.Arrays.fill(r, Float.NaN);
        for (TableExtractor.CellHit c : t.cells) {
            if (c.colSpan != 1 || c.col < 0 || c.col >= t.colCount || c.bbox == null) continue;
            l[c.col] = Float.isNaN(l[c.col]) ? c.bbox[0] : Math.min(l[c.col], c.bbox[0]);
            r[c.col] = Float.isNaN(r[c.col]) ? c.bbox[2] : Math.max(r[c.col], c.bbox[2]);
        }
        return new float[][]{l, r};
    }

    private static boolean isNumeric(String s) {
        return s.matches("[-+(]?[\\d.,%$)]+") && s.chars().anyMatch(Character::isDigit);
    }
}
