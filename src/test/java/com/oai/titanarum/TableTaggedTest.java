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

    // ---------------------------------------------------------------- hardening (review fixes)

    @Test
    void rowSpanBombIsClampedNotHung() throws Exception {
        // RowSpan=50_000_000 must be clamped (readSpans) and/or rejected (cumulative-area guard
        // in buildTaggedTable) long before any HashMap-filling occupancy loop runs. No timing
        // assertion here by design -- a hung/looping implementation would simply never return and
        // the test would be killed by the surefire/JVM timeout, which is evidence enough.
        Path pdf = tmp.resolve("spanbomb.pdf");
        TableTestPdfs.taggedSpanBomb(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            // Either outcome ("clamped" or "rejected") is acceptable per the fix; if a table
            // came back, its bombed cell's rowSpan must be clamped to MAX_SPAN, never the
            // attacker-supplied 50_000_000.
            if (!r.tables.isEmpty()) {
                TableExtractor.TableHit t = r.tables.get(0);
                boolean anyBombed = t.cells.stream().anyMatch(c -> c.rowSpan > 1);
                assertTrue(anyBombed, "expected the RowSpan-bearing cell to survive with a clamped (but >1) span");
                assertTrue(t.cells.stream().allMatch(c -> c.rowSpan <= TableExtractor.MAX_SPAN),
                        "rowSpan must never exceed MAX_SPAN");
            }
        }
    }

    @Test
    void tableOutsidePagesToProcessNeverWalksThatPagesContentStream() throws Exception {
        Path pdf = tmp.resolve("page2table.pdf");
        TableTestPdfs.taggedOnPageTwo(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int before = TableExtractor.taggedProcessPageCalls;
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertTrue(r.tables.isEmpty(), "a table living entirely on an out-of-scope page must not be emitted");
            assertEquals(before, TableExtractor.taggedProcessPageCalls,
                    "page 2's content stream must never be walked when pagesToProcess=[1]");
        }
    }

    @Test
    void crossPageTaggedTableExcludesOtherPagesBbox() throws Exception {
        Path pdf = tmp.resolve("crosspage.pdf");
        TableTestPdfs.taggedCrossPage(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1, 2), Map.of());
            assertEquals(1, r.tables.size());
            TableExtractor.TableHit t = r.tables.get(0);
            assertEquals(1, t.page, "table attributed to the page of its first cell");
            assertEquals("A", t.cells.get(0).text);
            assertEquals("B", t.cells.get(1).text);
            assertEquals("C", t.cells.get(2).text, "page-2 cell text must still be resolved");
            assertEquals("D", t.cells.get(3).text);
            assertNotNull(t.cells.get(0).bbox);
            assertNotNull(t.cells.get(1).bbox);
            assertNull(t.cells.get(2).bbox, "a cell resolved from a different page must not carry a bbox");
            assertNull(t.cells.get(3).bbox);
            assertNotNull(t.bbox, "table bbox must still be built from the page-1 cells");
        }
    }

    @Test
    void nestedTaggedTableDoesNotLeakIntoOuter() throws Exception {
        Path pdf = tmp.resolve("nested.pdf");
        TableTestPdfs.taggedNested(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            // The outer 1x1 wrapper (no MCID content of its own) is degenerate and rejected;
            // the inner 2x2 table must extract standalone, with its rows never folded into a
            // 1-row/1-col outer entry.
            assertTrue(r.tables.stream().noneMatch(t -> t.rowCount == 1 && t.colCount == 1),
                    "the outer wrapper must not surface with the inner table's rows/text folded in");
            TableExtractor.TableHit inner = r.tables.stream()
                    .filter(t -> t.rowCount == 2 && t.colCount == 2)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("inner 2x2 table not found: " + r.tables));
            assertEquals(List.of(List.of("R1C1", "R1C2"), List.of("R2C1", "R2C2")), inner.rows);
        }
    }
}
