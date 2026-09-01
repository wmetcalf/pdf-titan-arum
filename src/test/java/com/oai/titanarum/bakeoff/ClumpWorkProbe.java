// Physically under src/test/java/com/oai/titanarum/bakeoff/ but declares `package com.oai.titanarum;`
// (same convention/reason as BakeOffHarness / ClumpProbe).
//
// PURPOSE. MAX_CLUMP_SPLIT_WORK has to be justified by measurement, not by a round number. This
// probe replays extractLatticePage's exact sequence (collectRulings -> normalize -> findCells ->
// groupIntoTables -> selectKeptTables -> fillCellsFromPositions -> buildTable) on every page of the
// 77-PDF scoring corpus and calls TableExtractor.splitClumpedCells with its OWN work counter and an
// effectively-infinite budget, so the REAL work a legitimate page charges to that budget can be
// read off directly (per page, since production shares one counter across a page's tables).
//
//   mvn -q -o test -Dtest=ClumpWorkProbe -DclumpWork=true
package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class ClumpWorkProbe {

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("clumpWork"), "set -DclumpWork=true");
        StringBuilder notes = new StringBuilder();
        List<BakeOffHarness.ScoreUnit> units = BakeOffHarness.buildScoringSet(notes).units;
        long worstPage = 0;
        String worstId = "-";
        List<Long> pageWork = new ArrayList<>();
        long totalNs = 0;
        for (BakeOffHarness.ScoreUnit u : units) {
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                    PDPage page = doc.getPage(p - 1);
                    List<TextPosition> positions = TableTestPdfs.harvestGlyphs(doc, p - 1);
                    int rotation = page.getRotation();
                    PDRectangle crop = page.getCropBox();
                    float uw = crop.getWidth(), uh = crop.getHeight();
                    List<TableExtractor.Ruling> rulings =
                            TableExtractor.normalize(TableExtractor.collectRulings(page));
                    if (rulings.isEmpty()) continue;
                    List<TableExtractor.Ruling> h = new ArrayList<>(), v = new ArrayList<>();
                    for (TableExtractor.Ruling r : rulings) (r.horizontal() ? h : v).add(r);
                    List<TableExtractor.CellRect> cells = TableExtractor.findCells(h, v);
                    if (cells.isEmpty()) continue;
                    TableExtractor.Result res = new TableExtractor.Result();
                    List<List<TableExtractor.CellRect>> kept = TableExtractor.selectKeptTables(
                            TableExtractor.groupIntoTables(cells), p, res);
                    if (kept.isEmpty()) continue;
                    long[] fill = {0};
                    for (List<TableExtractor.CellRect> comp : kept) {
                        TableExtractor.fillCellsFromPositions(comp, positions, rotation, uw, uh,
                                fill, TableExtractor.MAX_TEXTFILL_WORK);
                    }
                    long[] work = {0};
                    long t0 = System.nanoTime();
                    for (List<TableExtractor.CellRect> comp : kept) {
                        TableExtractor.TableHit t = TableExtractor.buildTable(p, comp, "lattice");
                        TableExtractor.splitClumpedCells(t, positions, rotation, uw, uh,
                                work, Long.MAX_VALUE / 4);
                    }
                    totalNs += System.nanoTime() - t0;
                    if (work[0] > 0) pageWork.add(work[0]);
                    if (work[0] > worstPage) { worstPage = work[0]; worstId = u.id() + " p" + p; }
                }
            } catch (Throwable e) {
                System.out.println("SKIP " + u.id() + ": " + e);
            }
        }
        pageWork.sort(null);
        System.out.printf(Locale.ROOT,
                "%nCLUMP WORK: pages charging >0 = %d, p50=%d p95=%d MAX=%d (%s), "
                        + "total split wall time over the whole corpus = %.1fms%n",
                pageWork.size(),
                pageWork.isEmpty() ? 0 : pageWork.get(pageWork.size() / 2),
                pageWork.isEmpty() ? 0 : pageWork.get((int) Math.min(pageWork.size() - 1,
                        Math.ceil(0.95 * pageWork.size()))),
                worstPage, worstId, totalNs / 1e6);
    }
}
