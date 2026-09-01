// Diagnostic probe (gated -DlatticeFp=true). Enumerates every LATTICE (and tagged) table the
// pipeline emits on page 1 of the 200-PDF real-world prose sample -- the same sample
// BakeOffHarness.sampleProsePdfs() returns and BaselineHarness reports the prose FP rate over --
// and dumps enough geometry/content per table to CLASSIFY the false positive by mechanism.
//
// Physically under bakeoff/ but declares `package com.oai.titanarum;` for package-private access
// to TableExtractor / TableTestPdfs, matching BaselineHarness's own convention.
//
//   mvn -q -o test -Dtest=LatticeFpProbe -DlatticeFp=true
package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class LatticeFpProbe {

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("latticeFp"), "set -DlatticeFp=true to run");
        List<Path> prose = BakeOffHarness.sampleProsePdfs();
        System.out.printf(Locale.ROOT, "prose sample: %d PDFs%n", prose.size());

        int flagged = 0;
        for (Path p : prose) {
            List<TableExtractor.TableHit> ruled;
            float pw = 0, ph = 0;
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                if (doc.getNumberOfPages() < 1) continue;
                PDPage page = doc.getPage(0);
                PDRectangle cb = page.getCropBox();
                int rot = page.getRotation();
                pw = (rot == 90 || rot == 270) ? cb.getHeight() : cb.getWidth();
                ph = (rot == 90 || rot == 270) ? cb.getWidth() : cb.getHeight();
                List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
                Map<Integer, List<TextPosition>> byPage = new LinkedHashMap<>();
                byPage.put(1, glyphs);
                ruled = new ArrayList<>(TableExtractor.extract(doc, List.of(1), byPage).tables);
            } catch (Throwable t) {
                continue;
            }
            if (ruled.isEmpty()) continue;
            flagged++;
            System.out.printf(Locale.ROOT, "%n=== FILE %s  page=%.0fx%.0f  tables=%d%n",
                    p.getFileName(), pw, ph, ruled.size());
            for (TableExtractor.TableHit t : ruled) {
                int nonEmpty = 0, chars = 0;
                for (TableExtractor.CellHit c : t.cells) {
                    String s = c.text == null ? "" : c.text.trim();
                    if (!s.isEmpty()) { nonEmpty++; chars += s.length(); }
                }
                float bw = t.bbox[2] - t.bbox[0], bh = t.bbox[3] - t.bbox[1];
                float pageArea = Math.max(1f, pw * ph);
                System.out.printf(Locale.ROOT,
                        "  [%s] %dx%d cells=%d nonEmpty=%d(%.2f) chars=%d "
                        + "bbox=[%.0f,%.0f,%.0f,%.0f] %.0fx%.0f areaFrac=%.3f wFrac=%.3f hFrac=%.3f%n",
                        t.extractionMethod, t.rowCount, t.colCount, t.cells.size(), nonEmpty,
                        t.cells.isEmpty() ? 0 : nonEmpty / (double) t.cells.size(), chars,
                        t.bbox[0], t.bbox[1], t.bbox[2], t.bbox[3], bw, bh,
                        bw * bh / pageArea, bw / Math.max(1f, pw), bh / Math.max(1f, ph));
                // cell text dump, first 12 cells
                StringBuilder sb = new StringBuilder("      cells:");
                int n = 0;
                for (TableExtractor.CellHit c : t.cells) {
                    if (n++ >= 12) { sb.append(" ..."); break; }
                    String s = c.text == null ? "" : c.text.replaceAll("\\s+", " ").trim();
                    if (s.length() > 28) s = s.substring(0, 28) + "~";
                    sb.append(" (").append(c.row).append(',').append(c.col)
                      .append(c.rowSpan != 1 || c.colSpan != 1
                              ? "+" + c.rowSpan + "x" + c.colSpan : "")
                      .append(")'").append(s).append('\'');
                }
                System.out.println(sb);
            }
        }
        System.out.printf(Locale.ROOT, "%n=== %d/%d files emitted >=1 lattice/tagged table ===%n",
                flagged, prose.size());
    }
}
