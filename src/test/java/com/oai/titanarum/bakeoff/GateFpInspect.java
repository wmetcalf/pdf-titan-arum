// Diagnostic probe (gated -DgateInspect=true): prints the markdown of the page-1 candidates a given
// list of real-world files produces, so a human can judge whether a "prose false positive" is really
// a false positive or is genuinely tabular content the FP metric mislabels.
package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class GateFpInspect {

    @Test
    void run() {
        Assumptions.assumeTrue(Boolean.getBoolean("gateInspect"), "set -DgateInspect=true");
        String names = System.getProperty("gateInspectFiles", "");
        GutterFinder finder = new BreuelGutterFinder();
        for (String n : names.split(",")) {
            if (n.isBlank()) continue;
            Path p = Path.of("/home/coz/Downloads/phishpdfs", n.trim());
            System.out.println("################ " + n.trim());
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
                List<StreamTableExtractor.Candidate> cands = new ArrayList<>();
                StreamTableExtractor.extractPage(1, glyphs, finder, c -> -1e9,
                        StreamTableExtractor.PRODUCTION_BAR, cands);
                for (StreamTableExtractor.Candidate c : cands) {
                    if (c.hit == null || c.confidence < 0.34) continue;
                    System.out.printf(Locale.ROOT,
                            "-- conf=%.3f cols=%d rows=%d cc=%.3f viol=%.3f prose=%.3f num=%.3f%n",
                            c.confidence, c.grid.nCols, c.grid.nRows, c.grid.tColConsistency,
                            c.grid.tViolation, c.grid.tProse, c.grid.tNumeric);
                    System.out.println(c.hit.markdown);
                }
            } catch (Throwable t) {
                System.out.println("  ERROR " + t);
            }
        }
    }
}
