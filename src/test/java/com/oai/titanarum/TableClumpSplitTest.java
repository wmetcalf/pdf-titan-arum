package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The user's own bug report: in an UNDER-RULED band (typically a TOTAL row whose interior vertical
 * rulings do not extend down into it) the lattice grid finds one WIDE cell instead of N narrow ones,
 * so several column-aligned numbers get joined into a single cell. Observed verbatim on a real
 * fisheries-export PDF, in a 15x14 table whose rows 1-13 were each extracted perfectly:
 *
 * <pre>
 *   row14 col0 = "TOTAL 453,515 895,111"
 *   row14 col3 = "456,431 718,382 487,183 886,211"
 * </pre>
 *
 * The table's real column boundaries are already known from the rows that ARE fully ruled, so the
 * clump is re-splittable by column alignment. These tests pin BOTH directions: the totals row must
 * split, and a genuine SPANNING TEXT TITLE in the same under-ruled position must NOT.
 */
class TableClumpSplitTest {

    @TempDir
    Path tmp;

    private static Map<Integer, List<TextPosition>> stripPositions(PDDocument doc, List<Integer> pages)
            throws Exception {
        Map<Integer, List<TextPosition>> out = new HashMap<>();
        for (int p : pages) out.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
        return out;
    }

    private static TableExtractor.TableHit onlyTable(TableExtractor.Result r) {
        assertEquals(1, r.tables.size(), "expected exactly one table, got " + r.tables.size());
        return r.tables.get(0);
    }

    // ---------------------------------------------------------------- the defect

    @Test
    void underRuledTotalsRowIsSplitIntoItsOwnColumns() throws Exception {
        Path pdf = tmp.resolve("totals.pdf");
        TableTestPdfs.ruledTotalsRowMissingInteriorVerticals(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r =
                    TableExtractor.extract(doc, List.of(1), stripPositions(doc, List.of(1)));
            assertFalse(r.truncated);
            TableExtractor.TableHit t = onlyTable(r);
            assertEquals("lattice", t.extractionMethod);
            assertEquals(3, t.rowCount);
            assertEquals(3, t.colCount);
            assertEquals(List.of(
                    List.of("Region", "Exports", "Imports"),
                    List.of("North", "453,102", "895,004"),
                    List.of("TOTAL", "453,515", "895,111")), t.rows,
                    "the under-ruled TOTAL band must land one value per column, not clumped into col0");
            // the split must produce genuine single-column cells, not a re-labelled spanning cell
            List<TableExtractor.CellHit> totals = new ArrayList<>();
            for (TableExtractor.CellHit c : t.cells) if (c.row == 2) totals.add(c);
            assertEquals(3, totals.size(), "totals row should hold 3 cells: " + totals.size());
            for (TableExtractor.CellHit c : totals) {
                assertEquals(1, c.colSpan, "split cell must not claim a span: col=" + c.col);
                assertEquals(1, c.rowSpan);
                assertNotNull(c.bbox);
                assertTrue(c.bbox[2] > c.bbox[0], "split cell needs a positive-width bbox");
            }
            // each split cell's bbox must sit inside its own column band, i.e. be narrower than the
            // whole table -- otherwise "per-column cells" is a lie the downstream consumer inherits
            float tableWidth = t.bbox[2] - t.bbox[0];
            for (TableExtractor.CellHit c : totals) {
                assertTrue(c.bbox[2] - c.bbox[0] < 0.75f * tableWidth,
                        "split cell " + c.col + " is still table-wide: " + (c.bbox[2] - c.bbox[0]));
            }
        }
    }

    /**
     * The reported bug at full shape: TWO clumps in the SAME under-ruled row (the user's row14 had
     * one at col0 and another at col3, because a single interior vertical did reach the band). Both
     * must split, and neither may land on a column the other already took.
     */
    @Test
    void twoClumpsInOneRowBothSplitWithoutCollision() throws Exception {
        Path pdf = tmp.resolve("twoclumps.pdf");
        TableTestPdfs.ruledTotalsRowWithTwoClumpsInOneRow(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r =
                    TableExtractor.extract(doc, List.of(1), stripPositions(doc, List.of(1)));
            assertFalse(r.truncated);
            TableExtractor.TableHit t = onlyTable(r);
            assertEquals(3, t.rowCount);
            assertEquals(7, t.colCount);
            assertEquals(List.of("TOTAL", "453,515", "895,111", "456,431", "718,382", "487,183",
                    "886,211"), t.rows.get(2),
                    "both clumps of the under-ruled row must split into their own columns");
            // no two cells of the totals row may share a column
            List<Integer> cols = new ArrayList<>();
            for (TableExtractor.CellHit c : t.cells) if (c.row == 2) cols.add(c.col);
            assertEquals(7, cols.size(), "totals row should hold 7 cells: " + cols);
            assertEquals(7, cols.stream().distinct().count(), "column collision: " + cols);
        }
    }

    // ---------------------------------------------------------------- the guard

    @Test
    void spanningTextHeaderIsNotSplit() throws Exception {
        Path pdf = tmp.resolve("header.pdf");
        TableTestPdfs.ruledSpanningTextHeaderMissingInteriorVerticals(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r =
                    TableExtractor.extract(doc, List.of(1), stripPositions(doc, List.of(1)));
            assertFalse(r.truncated);
            TableExtractor.TableHit t = onlyTable(r);
            assertEquals(3, t.rowCount);
            assertEquals(3, t.colCount);
            assertEquals(List.of(
                    List.of("Quarterly Fisheries Export Summary", "", ""),
                    List.of("North", "453,102", "895,004"),
                    List.of("South", "453,515", "895,111")), t.rows,
                    "a spanning non-numeric title is ONE logical value and must stay intact");
            List<TableExtractor.CellHit> head = new ArrayList<>();
            for (TableExtractor.CellHit c : t.cells) if (c.row == 0) head.add(c);
            assertEquals(1, head.size(), "header row must stay a single spanning cell: " + head);
            assertEquals(3, head.get(0).colSpan);
        }
    }

    /**
     * Adversarial variant of the guard: a spanning title whose glyphs straddle two columns AND that
     * carries a YEAR ("2013"), so it is non-numeric only by MAJORITY, not by absence of digits. The
     * majority test (not "contains no digits") is what has to hold the line here.
     */
    @Test
    void spanningTitleWithOneNumberIsNotSplit() throws Exception {
        Path pdf = tmp.resolve("titleyear.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.75f);
                for (float y : new float[]{700, 670, 640, 610}) TableTestPdfs.line(cs, 50, y, 350, y);
                for (float x : new float[]{50, 350}) TableTestPdfs.line(cs, x, 700, x, 610);
                for (float x : new float[]{150, 250}) TableTestPdfs.line(cs, x, 670, x, 610);
                TableTestPdfs.text(cs, 55, 680, "Fisheries Exports 2013");
                TableTestPdfs.text(cs, 55, 650, "North");
                TableTestPdfs.text(cs, 155, 650, "453,102");
                TableTestPdfs.text(cs, 255, 650, "895,004");
                TableTestPdfs.text(cs, 55, 620, "South");
                TableTestPdfs.text(cs, 155, 620, "453,515");
                TableTestPdfs.text(cs, 255, 620, "895,111");
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r =
                    TableExtractor.extract(doc, List.of(1), stripPositions(doc, List.of(1)));
            TableExtractor.TableHit t = onlyTable(r);
            assertEquals("Fisheries Exports 2013", t.rows.get(0).get(0),
                    "1 numeric token out of 3 is not a majority -- the title must stay intact");
            assertEquals("", t.rows.get(0).get(1));
            assertEquals("", t.rows.get(0).get(2));
        }
    }

    /**
     * The split must be a pure RE-PARTITION of text the extractor already had: every character of
     * the clumped cell survives, in order, and nothing is invented. Pinned on the totals fixture by
     * comparing whitespace-stripped concatenations before and after.
     */
    @Test
    void splitNeitherInventsNorDropsText() throws Exception {
        Path pdf = tmp.resolve("integrity.pdf");
        TableTestPdfs.ruledTotalsRowMissingInteriorVerticals(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r =
                    TableExtractor.extract(doc, List.of(1), stripPositions(doc, List.of(1)));
            TableExtractor.TableHit t = onlyTable(r);
            StringBuilder all = new StringBuilder();
            for (List<String> row : t.rows) for (String c : row) all.append(c);
            assertEquals("RegionExportsImportsNorth453,102895,004TOTAL453,515895,111",
                    all.toString().replaceAll("\\s+", ""));
        }
    }

    /**
     * The {@code --skip-text-urls} region-fill path (empty {@code positionsByPage}) is a SEPARATE
     * glyph source -- {@code fillCellsByRegion}'s own bbox-prefiltered collection rather than the
     * caller's page strip -- and the split pass is wired to it too. Same fixture, same expected
     * result: if the two paths ever disagree, one of them is wrong.
     */
    @Test
    void splitAlsoFiresOnTheRegionFillPath() throws Exception {
        Path pdf = tmp.resolve("region.pdf");
        TableTestPdfs.ruledTotalsRowMissingInteriorVerticals(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertFalse(r.truncated);
            TableExtractor.TableHit t = onlyTable(r);
            assertEquals(List.of(
                    List.of("Region", "Exports", "Imports"),
                    List.of("North", "453,102", "895,004"),
                    List.of("TOTAL", "453,515", "895,111")), t.rows,
                    "the region-fill path must split identically to the position path");
        }
    }

    /**
     * A fully-ruled table must be BYTE-IDENTICAL to what it was before the split pass existed --
     * the pass may only ever fire on a cell that genuinely spans columns.
     */
    @Test
    void fullyRuledTableIsUntouched() throws Exception {
        Path pdf = tmp.resolve("plain.pdf");
        TableTestPdfs.ruled3x3(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r =
                    TableExtractor.extract(doc, List.of(1), stripPositions(doc, List.of(1)));
            TableExtractor.TableHit t = onlyTable(r);
            assertEquals(List.of(
                    List.of("R1C1", "R1C2", "R1C3"),
                    List.of("R2C1", "R2C2", "R2C3"),
                    List.of("R3C1", "R3C2", "R3C3")), t.rows);
            assertEquals(9, t.cells.size());
        }
    }
}
