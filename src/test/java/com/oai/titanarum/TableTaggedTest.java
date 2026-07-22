package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TableTaggedTest {

    @TempDir
    Path tmp;

    @Test
    void taggedTableExtractedWithHeadersAndNoRulings() throws Exception {
        Path pdf = tmp.resolve("tagged.pdf");
        TableTestPdfs.tagged2x2(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(1, r.tables.size());
            TableExtractor.TableHit t = r.tables.get(0);
            assertEquals("tagged", t.extractionMethod);
            assertEquals(1, t.page);
            assertEquals(2, t.rowCount);
            assertEquals(2, t.colCount);
            assertEquals(List.of(List.of("Name", "Qty"), List.of("Ada", "3")), t.rows);
            TableExtractor.CellHit th = t.cells.get(0);
            assertEquals(Boolean.TRUE, th.header, "TH cells must set header=true");
            TableExtractor.CellHit td = t.cells.get(2);
            assertNull(td.header, "TD cells leave header unset");
            assertTrue(t.markdown.contains("| Name | Qty |"));
        }
    }

    @Test
    void degenerateTaggedTableIsRejected() throws Exception {
        Path pdf = tmp.resolve("degenerate.pdf");
        TableTestPdfs.taggedDegenerate(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertTrue(r.tables.isEmpty(), "Table element with no TRs must be discarded");
        }
    }

    @Test
    void latticeStillRunsOnPagesWithoutTaggedTables() throws Exception {
        // A ruled (untagged) document must still go through lattice when a structure tree exists elsewhere.
        Path pdf = tmp.resolve("grid.pdf");
        TableTestPdfs.ruled3x3(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(1, r.tables.size());
            assertEquals("lattice", r.tables.get(0).extractionMethod);
        }
    }
}
