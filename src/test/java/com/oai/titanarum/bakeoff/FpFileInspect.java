// One-off inspector (gated -DfpInspect=true, -DfpFiles=a,b,c): for each named file in
// /home/coz/Downloads/phishpdfs, prints what each path emits on page 1 -- used to check whether
// removing a fabricated tagged/lattice table also removed the only signal about a document that is
// genuinely tabular. Physically under bakeoff/, declares `package com.oai.titanarum;`.
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

class FpFileInspect {

    @Test
    void run() {
        Assumptions.assumeTrue(Boolean.getBoolean("fpInspect"), "set -DfpInspect=true");
        String names = System.getProperty("fpFiles", "");
        GutterFinder breuel = new BreuelGutterFinder();
        for (String n : names.split(",")) {
            if (n.isBlank()) continue;
            Path p = Path.of("/home/coz/Downloads/phishpdfs", n.trim());
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
                Map<Integer, List<TextPosition>> byPage = new LinkedHashMap<>();
                byPage.put(1, glyphs);
                List<TableExtractor.TableHit> lt =
                        new ArrayList<>(TableExtractor.extract(doc, List.of(1), byPage).tables);
                List<TableExtractor.TableHit> str =
                        StreamTableExtractor.extractPage(1, glyphs, breuel);
                List<TableExtractor.TableHit> arb = TableExtractor.arbitrate(lt, str);
                System.out.printf(Locale.ROOT,
                        "INSPECT %s  latticeTagged=%d stream=%d arbitrated=%d%n",
                        n.trim(), lt.size(), str.size(), arb.size());
                for (TableExtractor.TableHit t : arb) {
                    System.out.printf(Locale.ROOT, "   [%s] %dx%d conf=%s%n",
                            t.extractionMethod, t.rowCount, t.colCount, t.confidence);
                }
            } catch (Throwable e) {
                System.out.println("INSPECT " + n + " ERROR " + e);
            }
        }
    }
}
