// Physically under src/test/java/com/oai/titanarum/bakeoff/ but declares `package com.oai.titanarum;`
// (same convention/reason as BakeOffHarness / ClumpProbe).
//
// PURPOSE (HONESTY CHECK). BaselineHarness's prose false-positive rate is "page 1 of a real-world PDF
// emitted >= 1 table". The over-clumped-cell split pass can NEVER change that number: it only ever
// re-partitions the cells of a table the lattice path has ALREADY emitted, so a page that emitted a
// table still emits one and a page that emitted none still emits none. Reporting "prose FP unchanged"
// as evidence the pass is safe would therefore be circular.
//
// This probe measures what the FP rate cannot see: on the SAME 200-PDF real-world sample, how many
// pages have any cell split at all, how many cells are affected, and what the resulting text is --
// so a fabricated column structure over prose would be visible rather than hidden behind an
// insensitive aggregate.
//
//   mvn -q -o test -Dtest=ClumpProseProbe -DclumpProse=true
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
import java.util.List;
import java.util.Locale;

class ClumpProseProbe {

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("clumpProse"), "set -DclumpProse=true");
        List<Path> prose = BakeOffHarness.sampleProsePdfs();
        int pagesWithSplit = 0, cellsSplit = 0, newCells = 0, filesWithSplit = 0, readable = 0;
        for (Path p : prose) {
            boolean fileHit = false;
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                if (doc.getNumberOfPages() < 1) continue;
                readable++;
                PDPage page = doc.getPage(0);
                List<TextPosition> positions = TableTestPdfs.harvestGlyphs(doc, 0);
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
                        TableExtractor.groupIntoTables(cells), 1, res);
                if (kept.isEmpty()) continue;
                long[] fill = {0};
                for (List<TableExtractor.CellRect> comp : kept) {
                    TableExtractor.fillCellsFromPositions(comp, positions, rotation, uw, uh,
                            fill, TableExtractor.MAX_TEXTFILL_WORK);
                }
                long[] work = {0};
                boolean pageHit = false;
                for (List<TableExtractor.CellRect> comp : kept) {
                    TableExtractor.TableHit t = TableExtractor.buildTable(1, comp, "lattice");
                    if (t == null) continue;
                    List<String> pre = new ArrayList<>();
                    for (TableExtractor.CellHit c : t.cells) {
                        pre.add(c.row + "/" + c.col + "/" + c.colSpan + "/" + c.text);
                    }
                    int nBefore = t.cells.size();
                    TableExtractor.splitClumpedCells(t, positions, rotation, uw, uh, work,
                            TableExtractor.MAX_CLUMP_SPLIT_WORK);
                    if (t.cells.size() == nBefore) continue;
                    pageHit = true;
                    newCells += t.cells.size() - nBefore;
                    for (TableExtractor.CellHit c : t.cells) {
                        String k = c.row + "/" + c.col + "/" + c.colSpan + "/" + c.text;
                        if (!pre.contains(k)) {
                            cellsSplit++;
                            if (cellsSplit <= 40) {
                                System.out.printf(Locale.ROOT, "PROSE-SPLIT %s r%d c%d [%s]%n",
                                        p.getFileName(), c.row, c.col,
                                        c.text == null ? "" : c.text.replace('\n', '/'));
                            }
                        }
                    }
                }
                if (pageHit) { pagesWithSplit++; fileHit = true; }
            } catch (Throwable e) {
                // unreadable prose file: conservatively counted as no split
            }
            if (fileHit) filesWithSplit++;
        }
        System.out.printf(Locale.ROOT,
                "%nCLUMP PROSE: sample=%d readable=%d filesWithAnySplit=%d pagesWithSplit=%d "
                        + "newCellsEmitted=%d%n",
                prose.size(), readable, filesWithSplit, pagesWithSplit, newCells);
    }
}
