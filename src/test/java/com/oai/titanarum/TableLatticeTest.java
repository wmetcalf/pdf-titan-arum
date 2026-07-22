package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TableLatticeTest {

    @TempDir
    Path tmp;

    /** Run the real per-page text strip so extract() gets genuine TextPositions. */
    private static Map<Integer, List<TextPosition>> stripPositions(PDDocument doc, List<Integer> pages) throws Exception {
        Map<Integer, List<TextPosition>> out = new java.util.HashMap<>();
        for (int p : pages) {
            var stripper = new org.apache.pdfbox.text.PDFTextStripper() {
                final List<TextPosition> positions = new java.util.ArrayList<>();
                @Override
                protected void writeString(String s, List<TextPosition> tps) {
                    positions.addAll(tps);
                }
            };
            stripper.setStartPage(p);
            stripper.setEndPage(p);
            stripper.getText(doc);
            out.put(p, stripper.positions);
        }
        return out;
    }

    @Test
    void ruled3x3ExtractsFaithfully() throws Exception {
        Path pdf = tmp.resolve("grid.pdf");
        TableTestPdfs.ruled3x3(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), stripPositions(doc, List.of(1)));
            assertFalse(r.truncated);
            assertEquals(1, r.tables.size());
            TableExtractor.TableHit t = r.tables.get(0);
            assertEquals(1, t.page);
            assertEquals("lattice", t.extractionMethod);
            assertEquals(3, t.rowCount);
            assertEquals(3, t.colCount);
            assertEquals(List.of(
                    List.of("R1C1", "R1C2", "R1C3"),
                    List.of("R2C1", "R2C2", "R2C3"),
                    List.of("R3C1", "R3C2", "R3C3")), t.rows);
            assertNotNull(t.markdown);
            assertTrue(t.markdown.startsWith("| R1C1 | R1C2 | R1C3 |"));
            // header is unknown on the lattice path -> omitted
            assertTrue(t.cells.stream().allMatch(c -> c.header == null));
        }
    }

    @Test
    void mergedHeaderProducesColSpan2() throws Exception {
        Path pdf = tmp.resolve("merged.pdf");
        TableTestPdfs.mergedHeader(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), stripPositions(doc, List.of(1)));
            assertEquals(1, r.tables.size());
            TableExtractor.TableHit t = r.tables.get(0);
            assertEquals(2, t.rowCount);
            assertEquals(2, t.colCount);
            TableExtractor.CellHit hdr = t.cells.get(0);
            assertEquals("HDR", hdr.text);
            assertEquals(2, hdr.colSpan);
            assertEquals(List.of(List.of("HDR", ""), List.of("L", "R")), t.rows);
        }
    }

    @Test
    void underlinesAndBoxesAreNotTables() throws Exception {
        Path pdf = tmp.resolve("none.pdf");
        TableTestPdfs.noTables(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), stripPositions(doc, List.of(1)));
            assertTrue(r.tables.isEmpty(), "no false positives on underline/callout page");
        }
    }

    @Test
    void missingPositionsFallsBackToRegionStrip() throws Exception {
        // Simulates --skip-text-urls: no TextPositions available.
        Path pdf = tmp.resolve("grid2.pdf");
        TableTestPdfs.ruled3x3(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(1, r.tables.size());
            assertEquals(List.of(
                    List.of("R1C1", "R1C2", "R1C3"),
                    List.of("R2C1", "R2C2", "R2C3"),
                    List.of("R3C1", "R3C2", "R3C3")), r.tables.get(0).rows);
        }
    }

    @Test
    void extractNeverThrows() throws Exception {
        // Page index out of range must be swallowed (per-page try/catch), not thrown.
        Path pdf = tmp.resolve("grid3.pdf");
        TableTestPdfs.ruled3x3(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1, 99), Map.of());
            assertEquals(1, r.tables.size());
        }
    }

    /**
     * Rulings (RulingCollector) and TextPosition/PDFTextStripperByArea disagree about which
     * coordinate space is "rotation aware" (getXDirAdj/getYDirAdj track per-glyph text
     * DIRECTION, not the page's /Rotate; getX()/getY() and PDFTextStripperByArea's region match
     * DO track /Rotate). Pin that both text-fill paths land on the correct cell regardless.
     */
    @Test
    void rotatedPageExtractsFaithfully() throws Exception {
        // Rotating the page 90/180/270 physically rotates the WHOLE table with it (rulings and
        // text alike), so which original cell reads as visual "row 0 col 0" legitimately changes
        // -- this is not "the same grid, unaffected by rotation". Expected grids below were
        // derived from the page-rotation geometry (old top-left corner -> new top-right for a
        // 90 CW rotation, etc.) and independently cross-checked against the actual extractor
        // output before being pinned as assertions (not the other way around).
        Map<Integer, List<List<String>>> expected = Map.of(
                90, List.of(
                        List.of("R3C1", "R2C1", "R1C1"),
                        List.of("R3C2", "R2C2", "R1C2"),
                        List.of("R3C3", "R2C3", "R1C3")),
                180, List.of(
                        List.of("R3C3", "R3C2", "R3C1"),
                        List.of("R2C3", "R2C2", "R2C1"),
                        List.of("R1C3", "R1C2", "R1C1")),
                270, List.of(
                        List.of("R1C3", "R2C3", "R3C3"),
                        List.of("R1C2", "R2C2", "R3C2"),
                        List.of("R1C1", "R2C1", "R3C1")));

        for (int rotation : new int[]{90, 180, 270}) {
            Path pdf = tmp.resolve("rotated" + rotation + ".pdf");
            TableTestPdfs.rotatedRuled3x3(pdf, rotation);
            try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                TableExtractor.Result rPos = TableExtractor.extract(doc, List.of(1), stripPositions(doc, List.of(1)));
                assertEquals(1, rPos.tables.size(), "positions path, rotation=" + rotation);
                assertEquals(expected.get(rotation), rPos.tables.get(0).rows, "positions path, rotation=" + rotation);

                TableExtractor.Result rRegion = TableExtractor.extract(doc, List.of(1), Map.of());
                assertEquals(1, rRegion.tables.size(), "region-fallback path, rotation=" + rotation);
                assertEquals(expected.get(rotation), rRegion.tables.get(0).rows, "region-fallback path, rotation=" + rotation);
            }
        }
    }

    @Test
    void textFillWorkBudgetThrowsOnOverflow() throws Exception {
        // Package-private overload with an explicit (lowered) budget, per the brief's own
        // suggestion, pins the throw path deterministically without needing a real glyph bomb.
        Path pdf = tmp.resolve("budget.pdf");
        TableTestPdfs.ruled3x3(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TextPosition> positions = stripPositions(doc, List.of(1)).get(1);
            assertTrue(positions.size() > 2, "fixture should have several glyphs to exceed a tiny budget");

            TableExtractor.CellRect cell = new TableExtractor.CellRect();
            cell.x0 = 0; cell.y0 = 0; cell.x1 = 1000; cell.y1 = 1000;
            long[] work = {0};
            assertThrows(TableExtractor.RulingOverflowException.class, () ->
                    TableExtractor.fillCellsFromPositions(List.of(cell), positions, 0, 612f, 792f, work, 2L));
        }
    }
}
