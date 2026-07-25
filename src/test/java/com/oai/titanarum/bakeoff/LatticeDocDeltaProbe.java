// Per-document attribution probe (gated -DlatticeDoc=true). Prints, for the lattice+tagged
// configuration under the PRIMARY protocol (document-POOLED adjacency relations, de-duplicated
// ground truth), one line per corpus document with its pooled F1 -- so two runs (rule on / rule
// off) can be diffed to attribute a macro delta to specific documents instead of guessing.
//
// Scoring code is copied verbatim in behaviour from BaselineHarness.e2ePooled (same gtRels/rels/
// compare calls, same GtDedup view); nothing here defines a new metric.
//
// Physically under bakeoff/ but declares `package com.oai.titanarum;` (BaselineHarness convention).
//   mvn -q -o test -Dtest=LatticeDocDeltaProbe -DlatticeDoc=true
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.GtDedup;
import com.oai.titanarum.bakeoff.TableScore;

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

class LatticeDocDeltaProbe {

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("latticeDoc"), "set -DlatticeDoc=true to run");
        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        double sum = 0;
        int n = 0;
        for (BakeOffHarness.ScoreUnit u : corpus.units) {
            List<TableExtractor.TableHit> hits = new ArrayList<>();
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                List<Integer> pages = new ArrayList<>();
                Map<Integer, List<TextPosition>> byPage = new LinkedHashMap<>();
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    pages.add(i + 1);
                    byPage.put(i + 1, TableTestPdfs.harvestGlyphs(doc, i));
                }
                hits.addAll(TableExtractor.extract(doc, pages, byPage).tables);
            } catch (Throwable ignored) { }

            List<GroundTruth.Table> expected = GtDedup.dedup(u.expected()).kept();
            List<TableScore.Relation> gt = new ArrayList<>();
            for (GroundTruth.Table e : expected) {
                gt.addAll(TableScore.buildOfficialRelations(
                        TableScore.gridCellsFromGroundTruth(e), false).relations());
            }
            List<TableScore.Relation> det = new ArrayList<>();
            for (TableExtractor.TableHit h : hits) {
                det.addAll(TableScore.buildOfficialRelations(
                        MetricFixHarness.cellsOf(h), false).relations());
            }
            TableScore.AdjResult r =
                    TableScore.compareRelations(gt, det, TableScore.Semantics.MULTISET);
            double p = r.detectedTotal() == 0 ? 0 : r.matched() / (double) r.detectedTotal();
            double rc = r.gtTotal() == 0 ? 0 : r.matched() / (double) r.gtTotal();
            double f1 = (p + rc) == 0 ? 0 : 2 * p * rc / (p + rc);
            sum += f1;
            n++;
            System.out.printf(Locale.ROOT, "DOC\t%s\t%.6f\t%d\t%d\t%d\t%d%n",
                    u.id(), f1, hits.size(), r.matched(), r.detectedTotal(), r.gtTotal());
        }
        System.out.printf(Locale.ROOT, "MACRO\t%.6f\t(n=%d)%n", sum / n, n);
    }
}
