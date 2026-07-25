// Diagnostic dump (gated -DlatticeStats=true). One TSV row per LATTICE table emitted, for BOTH the
// 77-PDF ICDAR scoring corpus (label=corpus) and the 200-PDF prose sample (label=prose), with the
// per-table statistics any candidate suppression rule could key off. Used to pick thresholds from
// the measured distributions instead of guessing them.
//
// Physically under bakeoff/ but declares `package com.oai.titanarum;` (BaselineHarness convention).
//   mvn -q -o test -Dtest=LatticeStatsDump -DlatticeStats=true
package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

class LatticeStatsDump {

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("latticeStats"), "set -DlatticeStats=true to run");
        System.out.println("TSVHEAD\tlabel\tid\tmethod\trows\tcols\tcells\tnonEmpty\tchars"
                + "\tmaxCellChars\tdistinctTextRows\tdistinctTextCols\tocc");

        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        for (BakeOffHarness.ScoreUnit u : corpus.units) {
            dump("corpus", u.id(), u.pdf(), true);
        }
        for (Path p : BakeOffHarness.sampleProsePdfs()) {
            dump("prose", p.getFileName().toString(), p, false);
        }
    }

    private static void dump(String label, String id, Path pdf, boolean allPages) {
        List<TableExtractor.TableHit> lt = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<Integer> pages = new ArrayList<>();
            Map<Integer, List<TextPosition>> byPage = new LinkedHashMap<>();
            int n = allPages ? doc.getNumberOfPages() : Math.min(1, doc.getNumberOfPages());
            for (int i = 0; i < n; i++) {
                pages.add(i + 1);
                byPage.put(i + 1, TableTestPdfs.harvestGlyphs(doc, i));
            }
            if (pages.isEmpty()) return;
            lt.addAll(TableExtractor.extract(doc, pages, byPage).tables);
        } catch (Throwable t) {
            return;
        }
        for (TableExtractor.TableHit t : lt) {
            int nonEmpty = 0, chars = 0, maxCell = 0;
            Set<Integer> rowsWithText = new LinkedHashSet<>();
            Set<Integer> colsWithText = new LinkedHashSet<>();
            for (TableExtractor.CellHit c : t.cells) {
                String s = c.text == null ? "" : c.text.trim();
                if (s.isEmpty()) continue;
                nonEmpty++;
                chars += s.length();
                maxCell = Math.max(maxCell, s.length());
                rowsWithText.add(c.row);
                colsWithText.add(c.col);
            }
            long prod = (long) t.rowCount * t.colCount;
            System.out.printf(Locale.ROOT, "TSV\t%s\t%s\t%s\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%.3f%n",
                    label, id, t.extractionMethod, t.rowCount, t.colCount, t.cells.size(),
                    nonEmpty, chars, maxCell, rowsWithText.size(), colsWithText.size(),
                    prod == 0 ? 0.0 : t.cells.size() / (double) prod);
        }
    }
}
