package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
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
        // RowSpan=50_000_000 must be clamped (readSpans) long before any HashMap-filling
        // occupancy loop runs. No timing assertion here by design -- a hung/looping
        // implementation would simply never return and the test would be killed by the
        // surefire/JVM timeout, which is evidence enough.
        //
        // Expected outcome for THIS fixture is not conditional: the clamped span yields a
        // cumulative area of 1,003 (TH+TH+clamped-TD(1000)+TD), which is under
        // MAX_CELLS_PER_TABLE (10,000), so the table survives (clamped, not rejected) and
        // must NOT be flagged truncated.
        Path pdf = tmp.resolve("spanbomb.pdf");
        TableTestPdfs.taggedSpanBomb(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(1, r.tables.size(), "clamped-span area (1,003) is under the cap; table must survive");
            TableExtractor.TableHit t = r.tables.get(0);
            TableExtractor.CellHit bombed = t.cells.stream()
                    .filter(c -> c.rowSpan > 1)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected the RowSpan-bearing cell to survive clamped"));
            assertEquals(TableExtractor.MAX_SPAN, bombed.rowSpan,
                    "rowSpan must be clamped to exactly MAX_SPAN, never the attacker-supplied 50_000_000");
            assertFalse(r.truncated, "area 1,003 < MAX_CELLS_PER_TABLE=10,000; must not be flagged truncated");
        }
    }

    @Test
    void taggedAreaCapSetsTruncated() throws Exception {
        // A single TR with 11 TD cells, each ColSpan=1000: cumulative area 11 x 1,000 = 11,000
        // exceeds MAX_CELLS_PER_TABLE (10,000). Unlike the clamped-but-under-cap case above, this
        // must be dropped AND must set Result.truncated -- the tagged path's cap-rejection must
        // not vanish silently the way a degenerate (no rows / no text) table does.
        Path pdf = tmp.resolve("areabomb.pdf");
        TableTestPdfs.taggedAreaCapBomb(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertTrue(r.tables.isEmpty(),
                    "table whose clamped cumulative area (11,000) exceeds MAX_CELLS_PER_TABLE must be dropped");
            assertTrue(r.truncated, "area-cap rejection on the tagged path must set Result.truncated");
        }
    }

    @Test
    void taggedGridProductCapRejectsSparseWideTableWithoutOOM() throws Exception {
        // FIX B (round 3) reproducer: 5,000 TRs (TR#0: 5 TDs each ColSpan=1000; TR#1..4999: a
        // plain 1x1 TD each). cumulativeArea (9,999) stays UNDER MAX_CELLS_PER_TABLE, so the
        // existing per-cell area guard never trips -- but the clustered grid is 5,000 rows x
        // 5,000 cols (product 25,000,000). Before the fix this reached renderViews, which
        // allocated a 5,000x5,000 String[][] (~25,000,000 cells) plus a full-grid markdown
        // StringBuilder for this ONE table. The fixed buildTaggedTable must reject the table
        // (grid product > MAX_CELLS_PER_TABLE) and flag truncated, the SAME non-silent signal
        // the cumulativeArea guard already uses -- the table must be ABSENT from the result, not
        // merely present-but-wrong, and extract() must return promptly (no multi-hundred-MB
        // allocation, no long stall) rather than hanging or OOMing while rendering it.
        Path pdf = tmp.resolve("gridproductbomb.pdf");
        TableTestPdfs.taggedGridProductBomb(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            long startMs = System.nanoTime();
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            long elapsedMs = (System.nanoTime() - startMs) / 1_000_000;

            assertTrue(r.tables.stream().noneMatch(t -> "tagged".equals(t.extractionMethod)),
                    "the 5,000x5,000-grid-product tagged table must be rejected, not emitted: " + r.tables);
            assertTrue(r.truncated,
                    "grid-product cap rejection must set Result.truncated, like the cumulativeArea cap does");
            assertTrue(elapsedMs < 15_000,
                    "must reject promptly, not stall/allocate rendering a 25,000,000-cell grid: took " + elapsedMs + "ms");
        }
    }

    @Test
    void taggedTablesPerPageCapped() throws Exception {
        // MAX_TABLES_PER_PAGE + 5 independent 1x1 tagged tables, all resolving to page 1: a
        // hostile structure tree with many Table elements must not emit unbounded duplicates
        // (collectByType's own 10_001 cap is 200x looser than the intended per-page limit).
        Path pdf = tmp.resolve("manytables.pdf");
        int total = TableExtractor.MAX_TABLES_PER_PAGE + 5;
        TableTestPdfs.taggedManyTablesOnePage(pdf, total);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            long onPage1 = r.tables.stream().filter(t -> t.page == 1).count();
            assertTrue(onPage1 <= TableExtractor.MAX_TABLES_PER_PAGE,
                    "at most MAX_TABLES_PER_PAGE tagged tables may survive for page 1, got " + onPage1);
            assertTrue(r.truncated, "exceeding the per-page tagged table cap must set Result.truncated");
        }
    }

    @Test
    void interruptedThreadStopsExtractTaggedPerTableLoop() throws Exception {
        // extractTagged's per-Table-element loop got the same between-item interrupt check as
        // extract()'s per-page loop (Fix 4), on the grounds a hostile structure tree can carry
        // thousands of independent Table elements; this pins that it actually works. Reuses the
        // existing independent-1x1-tables fixture, kept under MAX_TABLES_PER_PAGE so the
        // uninterrupted baseline isn't already truncated by the per-page tagged-table cap.
        Path pdf = tmp.resolve("manytables_interrupt.pdf");
        int total = 30;
        TableTestPdfs.taggedManyTablesOnePage(pdf, total);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result full = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(total, full.tables.size(), "sanity: every independent tagged table extracted when uninterrupted");
            assertFalse(full.truncated);

            Thread.currentThread().interrupt();
            try {
                TableExtractor.Result interrupted = TableExtractor.extract(doc, List.of(1), Map.of());
                assertTrue(interrupted.truncated, "interrupted extractTagged per-table loop must set Result.truncated");
                assertTrue(interrupted.tables.size() < total,
                        "interrupted extract() must not process every tagged table: got "
                                + interrupted.tables.size() + " of " + total);
            } finally {
                Thread.interrupted(); // clear the flag so it doesn't leak into other tests
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
    void pgInheritedFromTableAncestorStillExtracts() throws Exception {
        // FIX 7 reproducer: /Pg is set ONLY on the Table structure element (not on TR/TD, as
        // ISO 32000 permits via inheritance). A page lookup that checks only the TD/TH element
        // itself resolves null for every cell -> firstCellPage() returns null -> the whole table
        // is silently dropped (0 tables, no lattice fallback since there are no rulings, no log).
        // With ancestor-fallback page resolution the table must still extract correctly.
        Path pdf = tmp.resolve("pg_ancestor.pdf");
        TableTestPdfs.taggedPgOnTableAncestorOnly(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1, 2), Map.of());
            assertEquals(1, r.tables.size(),
                    "a table whose /Pg is only set on an ancestor (Table) must still be extracted, not silently dropped");
            TableExtractor.TableHit t = r.tables.get(0);
            assertEquals(2, t.page, "table's page resolved via ancestor /Pg inheritance");
            assertEquals(2, t.rowCount);
            assertEquals(2, t.colCount);
            assertEquals(List.of(List.of("Name", "Qty"), List.of("Ada", "3")), t.rows);
        }
    }

    @Test
    void pgInheritedFromTableAncestorOnOutOfScopePageNeverWalksItsContentStream() throws Exception {
        // Companion hostile-input guard: the SAME ancestor-/Pg-only fixture, but with page 2
        // excluded from pagesToProcess. Ancestor-fallback page resolution must remain a cheap,
        // /Pg-only lookup (no MCID/glyph work) so an out-of-scope table is still rejected before
        // its page's content stream is ever walked -- this must not reintroduce the
        // out-of-scope-page-walk DoS the pagesToProcess gating previously fixed.
        Path pdf = tmp.resolve("pg_ancestor_oos.pdf");
        TableTestPdfs.taggedPgOnTableAncestorOnly(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int before = TableExtractor.taggedProcessPageCalls;
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertTrue(r.tables.isEmpty(), "table's only page (2) is out of scope -- must not be emitted");
            assertEquals(before, TableExtractor.taggedProcessPageCalls,
                    "page 2's content stream must never be walked when pagesToProcess=[1], even via ancestor /Pg resolution");
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
    void flattenMarkedContentDoesNotStackOverflowOnDeepNesting() {
        // Reviewer's reproducer: flattenMarkedContent recurses over attacker-controlled nested
        // marked content with NO depth guard, so a deeply nested PDMarkedContent tree
        // (achievable via deeply nested BDC/EMC in a hostile content stream) blows the stack
        // with a StackOverflowError -- an Error, which escapes extractTagged's catch(Exception)
        // entirely. Build a synthetic chain far deeper than any real JVM stack tolerates and
        // drive flattenMarkedContent directly: it must return normally once depth-capped
        // (mirroring collectGlyphs' existing depth>64 guard), not overflow.
        int depth = 500_000;
        PDMarkedContent root = new PDMarkedContent(COSName.getPDFName("Span"), null);
        PDMarkedContent cur = root;
        for (int i = 1; i < depth; i++) {
            PDMarkedContent next = new PDMarkedContent(COSName.getPDFName("Span"), null);
            cur.addMarkedContent(next);
            cur = next;
        }
        Map<Integer, List<TextPosition>> byMcid = new HashMap<>();
        assertDoesNotThrow(() -> TableExtractor.flattenMarkedContent(root, byMcid, 0),
                "deeply nested marked content must not StackOverflowError");
    }

    @Test
    void extractNeverThrowsOnDeeplyNestedMarkedContent() throws Exception {
        // End-to-end companion: a real tagged fixture whose TD's marked content is wrapped in
        // many nested (untagged) BDC/EMC spans must still extract without extract() propagating
        // a StackOverflowError out of the whole pipeline.
        Path pdf = tmp.resolve("deepnest.pdf");
        TableTestPdfs.taggedDeeplyNestedMarkedContent(pdf, 100_000);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = assertDoesNotThrow(
                    () -> TableExtractor.extract(doc, List.of(1), Map.of()),
                    "extract() must never propagate a StackOverflowError");
            assertNotNull(r);
        }
    }

    @Test
    void taggedMarkedContentGlyphBombIsBoundedNotQuadratic() throws Exception {
        // PR review P1 (CRITICAL, confirmed by an independent probe) reproducer: glyphsFor's
        // `PDFMarkedContentExtractor ex = new PDFMarkedContentExtractor(); ex.processPage(page);`
        // had NO glyph cap, NO work budget, and NO interrupt check -- the only unbounded stage left
        // in this class. PDFMarkedContentExtractor's own processTextPosition (suppression ON, the
        // default) buckets every glyph by its unicode character and linearly scans that bucket
        // before appending -- O(bucket size) per glyph, O(n^2) total for N glyphs sharing one
        // repeated character.
        //
        // The fixture below places ONE legit 1-cell tagged table (Table -> TR -> TD, MCID 0) plus a
        // huge run of repeated, structure-UNREFERENCED glyphs elsewhere in the SAME content stream.
        // extractTagged's pagesToProcess/firstCellPage gating only filters a table by PAGE NUMBER
        // before glyphsFor ever runs -- it does not bound how much OTHER marked content an
        // in-scope page carries -- so the legit table's own MCID-0 lookup still triggers a full
        // walk of the whole page, including the unreferenced bomb.
        //
        // 300,000 repeated glyphs is far past MAX_TAGGED_WORK's measured worst-case crossover
        // (~24,500 glyphs for a single repeated character, ~226,000 for the worst realistic
        // multi-character shape -- see that constant's doc), so BudgetedMarkedContentExtractor's
        // work budget must trip well before the bomb is fully consumed, bounding wall-clock
        // deterministically rather than racing the 15s hard-halt watchdog. Before this fix: N=100,000
        // took ~14.7s and N=300,000 scales quadratically well past a minute (measured directly
        // against unbounded PDFMarkedContentExtractor while calibrating this fix) -- this assertion
        // is on the DETERMINISTIC cap/truncated outcome, not a wall-time race, so it stays robust
        // regardless of machine speed.
        Path pdf = tmp.resolve("taggedbomb.pdf");
        int bombGlyphCount = 300_000;
        TableTestPdfs.taggedWithUnreferencedGlyphBomb(pdf, bombGlyphCount);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            long start = System.nanoTime();
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMs < 5_000,
                    "the marked-content glyph/work cap must bound this deterministically, not "
                            + "process the whole 300,000-glyph bomb: took " + elapsedMs + "ms");
            assertTrue(r.truncated,
                    "the tagged path's marked-content cap trip must set Result.truncated");
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
