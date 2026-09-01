// Physically under src/test/java/com/oai/titanarum/bakeoff/ but declares `package com.oai.titanarum;`
//
// PURPOSE (ADVERSARIAL AUDIT of the over-clumped-cell split's corpus gain). The per-document
// comparison says four documents move and one of them (eu-019) reaches a PERFECT lattice adjacency
// F1 of 1.0000. A perfect score is exactly the kind of number that turns out to be a scoring
// artifact, so this probe prints, for the named documents: how many cells the split actually
// changed, the resulting grid, and the ground-truth grid it is being scored against -- so the claim
// can be read off the content rather than trusted from an aggregate.
//
//   mvn -q -o test -Dtest=ClumpAuditProbe -DclumpAudit=eu-019,eu-004
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.TableScore;

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

class ClumpAuditProbe {

    @Test
    void run() throws Exception {
        String want = System.getProperty("clumpAudit", "");
        Assumptions.assumeTrue(!want.isEmpty(), "set -DclumpAudit=eu-019,eu-004");
        List<String> wanted = List.of(want.split(","));
        StringBuilder notes = new StringBuilder();
        for (BakeOffHarness.ScoreUnit u : BakeOffHarness.buildScoringSet(notes).units) {
            boolean hit = false;
            for (String w : wanted) if (u.id().contains(w)) hit = true;
            if (!hit) continue;
            System.out.println("================ " + u.id());
            for (GroundTruth.Table gt : u.expected()) {
                List<TableScore.GridCell> gc = TableScore.gridCellsFromGroundTruth(gt);
                System.out.printf(Locale.ROOT, "  GT table: %d rows, %d relations%n",
                        gt.rows().size(), TableScore.buildOfficialRelations(gc, false).relations().size());
                for (int i = 0; i < gt.rows().size() && i < 6; i++) {
                    System.out.println("    GT   " + gt.rows().get(i));
                }
                if (gt.rows().size() > 6) System.out.println("    GT   ... (" + gt.rows().size() + " rows)");
            }
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                    PDPage page = doc.getPage(p - 1);
                    List<TextPosition> positions = TableTestPdfs.harvestGlyphs(doc, p - 1);
                    int rot = page.getRotation();
                    PDRectangle crop = page.getCropBox();
                    float uw = crop.getWidth(), uh = crop.getHeight();
                    List<TableExtractor.Ruling> rl =
                            TableExtractor.normalize(TableExtractor.collectRulings(page));
                    if (rl.isEmpty()) continue;
                    List<TableExtractor.Ruling> h = new ArrayList<>(), v = new ArrayList<>();
                    for (TableExtractor.Ruling r : rl) (r.horizontal() ? h : v).add(r);
                    List<TableExtractor.CellRect> cs = TableExtractor.findCells(h, v);
                    if (cs.isEmpty()) continue;
                    TableExtractor.Result res = new TableExtractor.Result();
                    List<List<TableExtractor.CellRect>> kept = TableExtractor.selectKeptTables(
                            TableExtractor.groupIntoTables(cs), p, res);
                    long[] fill = {0};
                    for (List<TableExtractor.CellRect> comp : kept) {
                        TableExtractor.fillCellsFromPositions(comp, positions, rot, uw, uh, fill,
                                TableExtractor.MAX_TEXTFILL_WORK);
                    }
                    long[] work = {0};
                    for (List<TableExtractor.CellRect> comp : kept) {
                        TableExtractor.TableHit t = TableExtractor.buildTable(p, comp, "lattice");
                        if (t == null) continue;
                        int before = t.cells.size();
                        TableExtractor.splitClumpedCells(t, positions, rot, uw, uh, work,
                                TableExtractor.MAX_CLUMP_SPLIT_WORK);
                        if (t.cells.size() == before) continue;
                        TableExtractor.renderViews(t);
                        System.out.printf(Locale.ROOT,
                                "  p%d SPLIT table %dx%d cells %d -> %d%n",
                                p, t.rowCount, t.colCount, before, t.cells.size());
                        for (int i = 0; i < t.rows.size() && i < 6; i++) {
                            System.out.println("    OUT  " + t.rows.get(i));
                        }
                        if (t.rows.size() > 6) System.out.println("    OUT  ... (" + t.rows.size() + " rows)");
                    }
                }
            }
        }
    }
}
