// Physically under src/test/java/com/oai/titanarum/bakeoff/ but declares `package com.oai.titanarum;`
// (same convention/reason as BakeOffHarness / BaselineHarness).
//
// PURPOSE. BaselineHarness reports corpus AGGREGATES. When a change moves the primary number by a
// small amount, the aggregate cannot tell "+0.0007 everywhere" from "+0.02 on one document and
// -0.019 on another". This probe dumps the PER-DOCUMENT pooled + de-duplicated end-to-end adjacency
// F1 for the two full-pipeline configurations, in a machine-diffable form, so a before/after run
// pair shows exactly which documents moved and in which direction.
//
// It builds relations, tallies and F1 through the SAME calls BaselineHarness uses
// (TableScore.buildOfficialRelations / compareRelations with MULTISET semantics, GtDedup for the
// ground-truth view) so its numbers are comparable to that report rather than a second definition.
// It scores NOTHING new and changes no production code.
//
//   mvn -q -o test -Dtest=PerDocArbProbe -DperDoc=true
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

class PerDocArbProbe {

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("perDoc"), "set -DperDoc=true");
        GutterFinder finder = new BreuelGutterFinder();
        StringBuilder notes = new StringBuilder();
        List<BakeOffHarness.ScoreUnit> units = BakeOffHarness.buildScoringSet(notes).units;
        double sumArb = 0, sumPos = 0, sumLat = 0;
        int n = 0;
        for (BakeOffHarness.ScoreUnit u : units) {
            List<GroundTruth.Table> raw = u.expected();
            GtDedup.Result dd = GtDedup.dedup(raw);
            Set<Integer> removed = new HashSet<>();
            for (GtDedup.Duplicate d : dd.removed()) removed.add(d.removedIndex());
            List<TableScore.Relation> gtRel = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                if (!removed.contains(i)) {
                    gtRel.addAll(TableScore.buildOfficialRelations(
                            TableScore.gridCellsFromGroundTruth(raw.get(i)), false).relations());
                }
            }
            double fArb = 0, fPos = 0, fLat = 0;
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                List<Integer> pages = new ArrayList<>();
                Map<Integer, List<TextPosition>> byPage = new LinkedHashMap<>();
                for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                    pages.add(p);
                    byPage.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
                }
                List<TableExtractor.TableHit> ruled =
                        new ArrayList<>(TableExtractor.extract(doc, pages, byPage).tables);
                List<TableExtractor.TableHit> str = new ArrayList<>();
                for (int p : pages) str.addAll(StreamTableExtractor.extractPage(p, byPage.get(p), finder));
                List<TableExtractor.TableHit> positional = new ArrayList<>(ruled);
                for (TableExtractor.TableHit s : str) {
                    if (!MetricFixHarness.overlapsSubstantially(s, ruled)) positional.add(s);
                }
                List<TableExtractor.TableHit> arb;
                try {
                    arb = TableExtractor.arbitrate(ruled, str);
                } catch (TableExtractor.RulingOverflowException e) {
                    arb = positional;
                }
                fArb = f1(gtRel, arb);
                fPos = f1(gtRel, positional);
                fLat = f1(gtRel, ruled);
            } catch (Throwable e) {
                System.out.println("ERR " + u.id() + " " + e);
            }
            System.out.printf(Locale.ROOT, "DOC\t%s\t%.6f\t%.6f\t%.6f%n", u.id(), fArb, fPos, fLat);
            sumArb += fArb; sumPos += fPos; sumLat += fLat; n++;
        }
        System.out.printf(Locale.ROOT, "MACRO\tarb=%.6f\tpos=%.6f\tlat=%.6f\tdocs=%d%n",
                sumArb / n, sumPos / n, sumLat / n, n);
    }

    private static double f1(List<TableScore.Relation> gt, List<TableExtractor.TableHit> hits) {
        List<TableScore.Relation> det = new ArrayList<>();
        for (TableExtractor.TableHit h : hits) {
            det.addAll(TableScore.buildOfficialRelations(MetricFixHarness.cellsOf(h), false).relations());
        }
        TableScore.AdjResult r = TableScore.compareRelations(gt, det, TableScore.Semantics.MULTISET);
        if (r.matched() == 0) return 0.0;
        double p = r.detectedTotal() == 0 ? 0 : (double) r.matched() / r.detectedTotal();
        double rc = r.gtTotal() == 0 ? 0 : (double) r.matched() / r.gtTotal();
        return (p + rc) == 0 ? 0.0 : 2 * p * rc / (p + rc);
    }
}
