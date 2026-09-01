// Diagnostic probe (gated -Deu002=true): why the FULL pipeline regresses on eu-002 when the
// two-tier gate admits a wide stream candidate. Prints the lattice/tagged hits and the newly
// admitted stream candidate with bboxes and the overlap fraction the harness's combination rule
// computes, so it can be established whether this is a GATE problem or a COMBINATION problem.
package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class Eu002Probe {

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("eu002"), "set -Deu002=true");
        GutterFinder finder = new BreuelGutterFinder();
        StringBuilder notes = new StringBuilder();
        String want = System.getProperty("eu002Doc", "eu-002");
        for (BakeOffHarness.ScoreUnit u : BakeOffHarness.buildScoringSet(notes).units) {
            if (!u.id().contains(want)) continue;
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                int pages = doc.getNumberOfPages();
                List<Integer> pageList = new ArrayList<>();
                Map<Integer, List<TextPosition>> glyphs = new LinkedHashMap<>();
                for (int p = 1; p <= pages; p++) {
                    pageList.add(p);
                    glyphs.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
                }
                List<TableExtractor.TableHit> tl =
                        new ArrayList<>(TableExtractor.extract(doc, pageList, glyphs).tables);
                System.out.println("### " + u.id() + "  tagged/lattice hits=" + tl.size());
                for (TableExtractor.TableHit t : tl) {
                    System.out.printf(Locale.ROOT, "  %-8s p%d %dx%d bbox=%s%n",
                            t.extractionMethod, t.page, t.rowCount, t.colCount, Arrays.toString(t.bbox));
                }
                List<TableExtractor.TableHit> flat = new ArrayList<>();
                for (int p : pageList) {
                    flat.addAll(StreamTableExtractor.extractPage(p, glyphs.get(p), finder,
                            c -> StreamTableExtractor.STREAM_CONFIDENCE_MIN,
                            c -> StreamTableExtractor.STREAM_CONFIDENCE_MIN, null));
                }
                List<TableExtractor.TableHit> two = new ArrayList<>();
                for (int p : pageList) {
                    two.addAll(StreamTableExtractor.extractPage(p, glyphs.get(p), finder,
                            StreamTableExtractor.PRODUCTION_BAR,
                            StreamTableExtractor.PRODUCTION_BAR, null));
                }
                System.out.println("  stream flat=" + flat.size() + " twoTier=" + two.size());
                for (TableExtractor.TableHit s : two) {
                    boolean overlaps = MetricFixHarness.overlapsSubstantially(s, tl);
                    double bestFrac = 0;
                    float area = Math.max(0f, s.bbox[2] - s.bbox[0]) * Math.max(0f, s.bbox[3] - s.bbox[1]);
                    for (TableExtractor.TableHit t : tl) {
                        if (t.page != s.page || t.bbox == null) continue;
                        float x0 = Math.max(s.bbox[0], t.bbox[0]), y0 = Math.max(s.bbox[1], t.bbox[1]);
                        float x1 = Math.min(s.bbox[2], t.bbox[2]), y1 = Math.min(s.bbox[3], t.bbox[3]);
                        if (x1 <= x0 || y1 <= y0) continue;
                        bestFrac = Math.max(bestFrac, ((x1 - x0) * (y1 - y0)) / area);
                    }
                    System.out.printf(Locale.ROOT,
                            "  stream p%d %dx%d conf=%.3f bbox=%s overlapFrac=%.3f dropped=%s%n",
                            s.page, s.rowCount, s.colCount, s.confidence, Arrays.toString(s.bbox),
                            bestFrac, overlaps);
                    if (!overlaps && Boolean.getBoolean("eu002md")) System.out.println(s.markdown);
                }
            }
        }
    }
}
