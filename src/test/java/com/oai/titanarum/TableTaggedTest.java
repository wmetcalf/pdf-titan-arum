package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
    void mcrOnlyPageFallbackStillExtractsTable() throws Exception {
        // PR re-review P2 (recall) reproducer: /Pg is set NOWHERE in the Table/TR/TD structure
        // subtree -- instead, each cell's marked content is referenced via a
        // PDMarkedContentReference KID whose OWN /Pg declares the page (a third, equally legal
        // ISO 32000 way to associate content with a page, distinct from element /Pg and ancestor
        // /Pg inheritance). Before this fix, firstCellPage()'s cheap early gate -- which only
        // consulted element+ancestor /Pg via resolveElementPage -- resolved null for every cell,
        // silently dropping the whole table before collectGlyphs (which already handles
        // mcr.getPage() correctly) ever ran. 0 tables pre-fix; the table, with correct cell text,
        // post-fix.
        Path pdf = tmp.resolve("mcr_only_page.pdf");
        TableTestPdfs.taggedMcrOnlyPage(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(1, r.tables.size(),
                    "a table whose page is declared ONLY via its cells' MCR children must not be "
                            + "silently dropped: " + r.tables);
            TableExtractor.TableHit t = r.tables.get(0);
            assertEquals("tagged", t.extractionMethod);
            assertEquals(1, t.page, "table's page resolved via the MCR-only fallback");
            assertEquals(2, t.rowCount);
            assertEquals(2, t.colCount);
            assertEquals(List.of(List.of("Name", "Qty"), List.of("Ada", "3")), t.rows,
                    "cell text must be resolved correctly through the MCR-only page path");
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
    void crossPageTaggedTableSurvivesWithOnlyContinuationPageSelected() throws Exception {
        // PR re-review P2 (recall) reproducer: the OLD gate (firstCellPage) resolved only the
        // table's FIRST cell's page (page 1, carrying "A"/"B") and dropped the WHOLE table when
        // that page wasn't in pagesToProcess -- even though this SAME table continues onto page 2
        // (carrying "C"/"D"), which IS selected here. This simulates a noncontiguous page
        // selection like "1-4,last" where a table starts on an unselected page and continues onto
        // a selected one: it must survive as a page-scoped PARTIAL table, not be dropped outright.
        Path pdf = tmp.resolve("crosspage_p2only.pdf");
        TableTestPdfs.taggedCrossPage(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int before = TableExtractor.taggedProcessPageCalls;
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(2), Map.of());

            assertEquals(1, r.tables.size(),
                    "a table with a continuation page in scope must NOT be dropped entirely: " + r.tables);
            TableExtractor.TableHit t = r.tables.get(0);
            assertEquals(2, t.page, "table's page must be a SELECTED page among its cells (page 2), "
                    + "not the unselected page its first cell happens to be on");
            assertEquals("", t.cells.get(0).text, "page-1 cell (out of scope) must be empty");
            assertEquals("", t.cells.get(1).text, "page-1 cell (out of scope) must be empty");
            assertEquals("C", t.cells.get(2).text, "page-2 cell (in scope) must resolve its real text");
            assertEquals("D", t.cells.get(3).text, "page-2 cell (in scope) must resolve its real text");
            assertNull(t.cells.get(0).bbox, "an out-of-scope cell must not carry a bbox");
            assertNull(t.cells.get(1).bbox, "an out-of-scope cell must not carry a bbox");
            assertNotNull(t.cells.get(2).bbox, "an in-scope cell must carry a bbox");
            assertNotNull(t.cells.get(3).bbox, "an in-scope cell must carry a bbox");

            // The out-of-scope-page-walk DoS guard must remain intact: glyphsFor's own per-page
            // pagesToProcess gate is what produced the empty page-1 cell text above, and since
            // pagesToProcess=[2] here, page 1's content stream is the ONLY one that could ever be
            // walked in error -- exactly one page (2) is genuinely in scope, so exactly one
            // taggedProcessPageCalls increment (memoized per page) is the only way this can play
            // out if page 1 was never touched.
            assertEquals(1, TableExtractor.taggedProcessPageCalls - before,
                    "page 1 (out of scope) must never be content-walked -- only page 2's single "
                            + "(memoized) walk may count against taggedProcessPageCalls");
        }
    }

    @Test
    void taggedTableFullyOutOfScopeAcrossAllItsCellsStaysDropped() throws Exception {
        // Control for the fix above: when NONE of a tagged table's cells resolve to a page in
        // pagesToProcess (both page 1 and page 2 excluded here), it must still be skipped
        // entirely -- and neither page's content stream may ever be walked.
        Path pdf = tmp.resolve("crosspage_none_selected.pdf");
        TableTestPdfs.taggedCrossPage(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int before = TableExtractor.taggedProcessPageCalls;
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(), Map.of());

            assertTrue(r.tables.isEmpty(),
                    "a table with NO cell on a selected page must still be dropped entirely: " + r.tables);
            assertEquals(before, TableExtractor.taggedProcessPageCalls,
                    "neither page's content stream may be walked when no page is in scope");
        }
    }

    @Test
    void stackOverflowInTaggedExtractionSetsTruncated() throws Exception {
        // PR re-review P2 (trivial) reproducer: extract()'s catch(StackOverflowError) around
        // extractTagged used to skip tagged extraction (lattice still runs) WITHOUT marking
        // Result.truncated -- unlike every sibling hostile-input cap in this class. A hostile/deep
        // structure could silently omit every tagged table from report.json with NO
        // tablesTruncated warning to explain why.
        //
        // Real end-to-end trigger, not a synthetic depth-cap probe: every recursive walk inside
        // TableExtractor's OWN tagged-path traversal (collectByType/collectGlyphs/
        // resolveElementPage/flattenMarkedContent) is already depth-capped at 64 and cannot
        // overflow by design. The genuine, still-unguarded overflow source here is INSIDE pdfbox
        // itself: PDPageTree.getInheritableAttribute (used by PDPage.getCropBox()/getRotation(),
        // called the moment glyphsFor's PDFMarkedContentExtractor.processPage(page) initializes)
        // recurses up a page's /Parent ancestry with a cycle GUARD but NO depth cap. A page whose
        // /Parent points into a pathologically deep (but acyclic) chain of synthetic /Pages nodes
        // overflows the stack there, well outside any of this class's own guards.
        //
        // This same page.getCropBox()/getRotation() call is ALSO made directly, unconditionally,
        // by the (separate, out-of-scope-for-this-fix) LATTICE per-page loop -- so to isolate this
        // reproducer to the TAGGED path's own catch(StackOverflowError) under test here, the
        // document's page-tree root /Count is spoofed to 0 (PDDocument.getNumberOfPages() reads
        // /Count directly, independent of the actual /Kids-based traversal doc.getPages() uses),
        // which makes extractLatticePage's own page-range guard reject page 1 before ever calling
        // getRotation()/getCropBox() on it -- without touching any lattice-path code.
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            // Build the fake ancestry chain OUTERMOST-first, propagating pdfbox's own
            // COSUpdateState.originDocumentState one level at a time as each node is created,
            // rather than linking the whole pre-built 500,000-deep chain to the (already-tracked)
            // document in one shot at the end. That single-shot link is itself a real,
            // currently-unbounded RECURSIVE cascade in pdfbox (COSUpdateState.setOriginDocumentState
            // walks every nested COSUpdateInfo value) -- attaching a deep pre-built chain to a
            // tracked document in one call overflows the stack during FIXTURE SETUP, before
            // TableExtractor.extract() is ever called, which would not exercise the catch under
            // test here. Setting each node's origin state immediately after it is created (while
            // its own /Parent still points only to an ALREADY-tracked node, whose state is already
            // set and so short-circuits per setOriginDocumentState's own already-set guard) keeps
            // every step O(1), producing the identical deep-chain STRUCTURE for
            // getInheritableAttribute to walk later, without any construction-time recursion.
            org.apache.pdfbox.cos.COSDocumentState originState =
                    page.getCOSObject().getUpdateState().getOriginDocumentState();
            int depth = 500_000;
            COSDictionary nodeAbovePage = null;
            for (int i = 0; i < depth; i++) {
                COSDictionary node = new COSDictionary();
                node.setItem(COSName.TYPE, COSName.PAGES);
                if (nodeAbovePage != null) node.setItem(COSName.PARENT, nodeAbovePage);
                node.getUpdateState().setOriginDocumentState(originState);
                nodeAbovePage = node;
            }
            // Decouple ancestry (used by getInheritableAttribute) from the real, shallow Kids-based
            // tree doc.getPages() still uses for enumeration -- PDPageTree's iterator is Kids-based
            // BFS and never follows /Parent, so pageNumbers resolution is unaffected by this.
            page.getCOSObject().setItem(COSName.PARENT, nodeAbovePage);
            // Spoof /Count so the LATTICE per-page loop's own bounds check rejects page 1 outright
            // (see this test's own doc above) -- isolating the reproducer to the tagged path.
            doc.getDocumentCatalog().getPages().getCOSObject().setInt(COSName.COUNT, 0);

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement table = new PDStructureElement("Table", root);
            root.appendKid(table);
            PDStructureElement tr = new PDStructureElement("TR", table);
            tr.setPage(page);
            table.appendKid(tr);
            PDStructureElement td = new PDStructureElement("TD", tr);
            td.setPage(page);
            td.getCOSObject().setInt(COSName.K, 0);
            tr.appendKid(td);

            TableExtractor.Result r = assertDoesNotThrow(
                    () -> TableExtractor.extract(doc, List.of(1), Map.of()),
                    "a StackOverflowError escaping tagged extraction must be caught, not propagate");
            assertTrue(r.truncated,
                    "a StackOverflowError escaping tagged extraction must mark the result truncated, "
                            + "so report.json's tablesTruncated warning isn't silently omitted");
        }
    }

    @Test
    void genericExceptionInTaggedExtractionSetsTruncated() throws Exception {
        // Codex re-review P2 (consistency) reproducer: extractTagged's OWN top-level
        // catch(Exception e) in extract() -- the generic fallback below the
        // StackOverflowError/RulingOverflowException catches above it -- used to only log, leaving
        // Result.truncated FALSE. A document whose tagged extraction genuinely FAILED (e.g. an
        // IOException from a malformed content stream) was then indistinguishable in report.json
        // from a document with genuinely no tagged tables at all.
        //
        // Real, narrow reproducer: the tagged cell's own page content stream is a single
        // unterminated hex string ("<AB", no closing ">"). glyphsFor's
        // BudgetedMarkedContentExtractor.processPage(page) hits PDFBox's own low-level tokenizer
        // (PDFStreamParser) while reading that token and throws a plain java.io.IOException
        // ("Missing closing bracket for hex string...") BEFORE any operator is ever dispatched --
        // neither a RulingOverflowException nor a StackOverflowError -- which propagates out of
        // resolveCellText -> buildTaggedTable, uncaught by extractTagged's own per-table
        // catch(RulingOverflowException), all the way to extract()'s catch(Exception) around the
        // whole extractTagged call. Verified directly against PDFBox 3.0.8 with a scratch probe
        // (both this tagged engine and the lattice one) before writing this test, rather than
        // assumed from reading PDFBox's source alone.
        try (PDDocument doc = new PDDocument()) {
            PDPage poisoned = new PDPage(PDRectangle.LETTER);
            doc.addPage(poisoned);
            org.apache.pdfbox.pdmodel.common.PDStream badContent =
                    new org.apache.pdfbox.pdmodel.common.PDStream(doc);
            try (var os = badContent.createOutputStream()) {
                os.write("<AB".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            poisoned.setContents(badContent);

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement table = new PDStructureElement("Table", root);
            table.setPage(poisoned);
            root.appendKid(table);
            PDStructureElement tr = new PDStructureElement("TR", table);
            table.appendKid(tr);
            PDStructureElement cell = new PDStructureElement("TD", tr);
            cell.setPage(poisoned);
            cell.getCOSObject().setInt(COSName.K, 0);
            tr.appendKid(cell);

            TableExtractor.Result r = assertDoesNotThrow(
                    () -> TableExtractor.extract(doc, List.of(1), Map.of()),
                    "a generic Exception escaping tagged extraction must be caught, not propagate");

            assertTrue(r.truncated,
                    "a document whose tagged extraction genuinely FAILED must mark the result "
                            + "truncated, so it's distinguishable from a document with no tagged tables at all");
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
    void taggedCellMcidReReferenceAmplificationIsBoundedNotUnbounded() throws Exception {
        // PR re-review P2 (DoS) reproducer: glyphsFor's per-page cache bounds how many DISTINCT
        // glyphs one MCID can ever hold (MAX_TAGGED_GLYPHS, extraction-side) and
        // structureNodesVisited/MAX_STRUCTURE_WORK bounds how many structure NODES one traversal
        // visits -- but neither bounds how many times a SINGLE cell's own kid list re-references
        // the SAME in-scope MCID. Here MCID 0 carries a modest, legitimate 1,000 glyphs, but the
        // TD's /K kid array lists that one MCID 2,001 times -- collectGlyphs' out.addAll(...)
        // accumulates 1,000 * 2,001 = 2,001,000 TextPosition references for this ONE cell, crossing
        // MAX_TAGGED_GLYPHS (2,000,000) via pure re-reference amplification, not real content.
        // Pre-fix this list grows unbounded (no cap on accumulated per-cell output) toward a
        // memory-exhausting size; post-fix collectGlyphs must throw once out.size() crosses the
        // cap, isolated by extractTagged's per-table catch into Result.truncated=true with the
        // hostile table simply dropped -- deterministically, not by racing an OOM or a timeout.
        Path pdf = tmp.resolve("mcid_amplification.pdf");
        int glyphsPerMcid = 1_000;
        int referenceCount = 2_001; // 1,000 * 2,001 = 2,001,000 > MAX_TAGGED_GLYPHS (2,000,000)
        TableTestPdfs.taggedCellReReferencingSameMcid(pdf, glyphsPerMcid, referenceCount);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            long start = System.nanoTime();
            TableExtractor.Result r = assertDoesNotThrow(
                    () -> TableExtractor.extract(doc, List.of(1), Map.of()),
                    "the per-cell MCID re-reference cap must be caught, not propagate/OOM");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMs < 5_000,
                    "the per-cell output cap must bound this deterministically, not accumulate "
                            + "millions of glyph references: took " + elapsedMs + "ms");
            assertTrue(r.truncated,
                    "the per-cell MCID re-reference cap trip must set Result.truncated");
            assertTrue(r.tables.isEmpty(),
                    "the sole hostile table must be dropped, not emitted with a bogus giant cell");
        }
    }

    @Test
    void taggedCellReReferencingSameMcidASmallNumberOfTimesStillExtracts() throws Exception {
        // Control for the amplification cap immediately above: a cell that references its own
        // in-scope MCID only a HANDFUL of times (well under MAX_TAGGED_GLYPHS even multiplied out)
        // must still extract normally -- the new per-cell ceiling must not false-positive on
        // ordinary small cells.
        Path pdf = tmp.resolve("mcid_small_repeat.pdf");
        int glyphsPerMcid = 3;
        int referenceCount = 4; // 3 * 4 = 12, nowhere near MAX_TAGGED_GLYPHS
        TableTestPdfs.taggedCellReReferencingSameMcid(pdf, glyphsPerMcid, referenceCount);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertFalse(r.truncated, "a small, legitimate re-reference count must not trip the cap");
            assertEquals(1, r.tables.size(), "the one legitimate table must still be extracted");
            assertFalse(r.tables.get(0).cells.get(0).text.isEmpty(),
                    "the cell must still resolve its (repeated) glyph text, not come back empty");
        }
    }

    // ---------------------------------------------------------------- FIX 1: rotated tagged bbox

    @Test
    void taggedTableBboxSharesLatticeVisualFrameAcrossRotation() throws Exception {
        // Codex P2 reproducer: resolveCellText used to build cell.bbox/table.bbox from raw
        // tp.getXDirAdj()/getYDirAdj()/getWidthDirAdj()/getHeightDir() with NO page-rotation
        // transform, while the LATTICE path (RulingCollector.addRuling, fillCellsFromPositions)
        // always transforms into the visual top-left frame via applyPageRotation -- so on a
        // /Rotate 90/180/270 page, the tagged bbox landed in a DIFFERENT frame than a lattice
        // bbox on the same page in the same report.json.
        //
        // Reference-based assertion (no magic glyph-metric constants): build the SAME tagged
        // fixture unrotated to get its "raw" bbox U, then build the rotated version and assert its
        // bbox equals applyPageRotation's transform of U's own two corners -- exactly the relation
        // that must hold once the tagged path shares the lattice path's rotation-aware frame.
        Path unrotatedPdf = tmp.resolve("tagged_unrotated_ref.pdf");
        TableTestPdfs.tagged2x2(unrotatedPdf);
        float[] unrotatedBbox;
        try (PDDocument doc = Loader.loadPDF(unrotatedPdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(1, r.tables.size());
            unrotatedBbox = r.tables.get(0).bbox;
            assertNotNull(unrotatedBbox, "sanity: the unrotated reference table must carry a bbox");
        }

        for (int rotation : new int[]{90, 180, 270}) {
            Path pdf = tmp.resolve("tagged_rotated_" + rotation + ".pdf");
            TableTestPdfs.taggedRotated2x2(pdf, rotation);
            try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
                assertEquals(1, r.tables.size(), "rotation=" + rotation);
                TableExtractor.TableHit t = r.tables.get(0);
                assertNotNull(t.bbox, "rotation=" + rotation);

                PDPage page = doc.getPage(0);
                PDRectangle cropBox = page.getCropBox();
                float uw = cropBox.getWidth(), uh = cropBox.getHeight();
                float[] c0 = TableExtractor.applyPageRotation(
                        unrotatedBbox[0], unrotatedBbox[1], rotation, uw, uh);
                float[] c1 = TableExtractor.applyPageRotation(
                        unrotatedBbox[2], unrotatedBbox[3], rotation, uw, uh);
                float expX0 = Math.min(c0[0], c1[0]), expX1 = Math.max(c0[0], c1[0]);
                float expY0 = Math.min(c0[1], c1[1]), expY1 = Math.max(c0[1], c1[1]);

                assertEquals(expX0, t.bbox[0], 0.05f, "bbox[0] (x0), rotation=" + rotation);
                assertEquals(expY0, t.bbox[1], 0.05f, "bbox[1] (y0), rotation=" + rotation);
                assertEquals(expX1, t.bbox[2], 0.05f, "bbox[2] (x1), rotation=" + rotation);
                assertEquals(expY1, t.bbox[3], 0.05f, "bbox[3] (y1), rotation=" + rotation);
            }
        }
    }

    // ---------------------------------------------------------------- FIX 2 / final decision:
    // ---------------------------------------------------------------- lattice is NEVER suppressed;
    // ---------------------------------------------------------------- likelyDuplicateOfTagged is
    // ---------------------------------------------------------------- an advisory flag only.

    @Test
    void taggedTableDoesNotSuppressSeparateRuledTableOnSamePage() throws Exception {
        // Codex P1 / ledger M-T6-2 reproducer: a page carrying a tagged table used to be entirely
        // SKIPPED for lattice extraction (coveredByTagged), silently dropping a second, genuinely
        // separate ruled (untagged) table living elsewhere on the same page. Both must now survive,
        // and (this fix) the distinct ruled table must NOT be flagged as a likely duplicate either.
        Path pdf = tmp.resolve("tagged_plus_ruled.pdf");
        TableTestPdfs.taggedPlusSeparateRuledTable(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(2, r.tables.size(),
                    "both the tagged table and the separate ruled table must survive: " + r.tables);

            TableExtractor.TableHit tagged = r.tables.stream()
                    .filter(t -> "tagged".equals(t.extractionMethod))
                    .findFirst().orElseThrow(() -> new AssertionError("tagged table missing: " + r.tables));
            assertEquals("Tagged", tagged.cells.get(0).text);
            assertNull(tagged.likelyDuplicateOfTagged, "the flag is only ever set on a lattice table");

            TableExtractor.TableHit lattice = r.tables.stream()
                    .filter(t -> "lattice".equals(t.extractionMethod))
                    .findFirst().orElseThrow(
                            () -> new AssertionError("the separate ruled table must not be silently dropped: " + r.tables));
            assertEquals(List.of(List.of("L", "R"), List.of("C", "D")), lattice.rows);
            assertNull(lattice.likelyDuplicateOfTagged,
                    "a genuinely distinct ruled table must not be flagged as a likely duplicate of the "
                            + "tagged table: " + lattice.likelyDuplicateOfTagged);
        }
    }

    @Test
    void tableBothTaggedAndRuledEmitsBothWithLatticeFlaggedDuplicate() throws Exception {
        // FINAL DECISION (this fix): a table that BOTH paths independently find (same visual
        // location, same cells) is no longer deduped away -- BOTH copies are emitted, ALWAYS.
        // The lattice copy is instead marked with the advisory likelyDuplicateOfTagged flag (the
        // tagged copy, which carries header/th info, is never flagged), so a downstream consumer
        // can still collapse the pair if it chooses -- but this extractor never silently drops one.
        Path pdf = tmp.resolve("tagged_and_ruled_same.pdf");
        TableTestPdfs.taggedAndRuledSameTable(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(2, r.tables.size(),
                    "both the tagged AND lattice copies of the same table must now be emitted, "
                            + "never silently suppressed: " + r.tables);

            TableExtractor.TableHit tagged = r.tables.stream()
                    .filter(t -> "tagged".equals(t.extractionMethod))
                    .findFirst().orElseThrow(() -> new AssertionError("tagged table missing: " + r.tables));
            assertNull(tagged.likelyDuplicateOfTagged,
                    "the tagged table itself is never the CANDIDATE side of the flag");

            TableExtractor.TableHit lattice = r.tables.stream()
                    .filter(t -> "lattice".equals(t.extractionMethod))
                    .findFirst().orElseThrow(() -> new AssertionError("lattice table missing: " + r.tables));
            assertEquals(Boolean.TRUE, lattice.likelyDuplicateOfTagged,
                    "the lattice copy of a table also found (identically) by the tagged path must be "
                            + "flagged likelyDuplicateOfTagged=true: " + r.tables);
        }
    }

    @Test
    void sparseTaggedCellInflatedBboxDoesNotSuppressDistinctRuledTable() throws Exception {
        // FIX 2 round-2 (post-review) reproducer: the FIRST version of the dedup used "lattice
        // bbox centroid inside tagged bbox", which false-suppressed this exact shape -- a LEGAL
        // sparse tagged table (one TD cell whose /K lists two MCIDs drawn far apart, e.g. a
        // "notes" cell spanning a page's header and footer) inflates that tagged table's bbox to
        // nearly the whole page; a completely separate, visually distinct ruled 2x2 table sitting
        // anywhere inside that inflated rectangle (but nowhere near either sparse glyph) had its
        // centroid fall inside the tagged bbox and was silently dropped -- the exact
        // silent-data-loss class FIX 2 exists to close, reintroduced via geometry. IoU fixes this:
        // the ruled table's area is tiny relative to the inflated tagged bbox's area, so
        // IoU ~= 0.02, far under the dedup threshold -- both tables must survive.
        //
        // LEVER 4 UPDATE (prose false positives). This fixture's tagged table is rank 1x1 (one TR,
        // one TD), so TableExtractor.MIN_TAGGED_RANK now rejects it before it is ever emitted --
        // that rule exists because 12 of the 21 measured tagged+lattice false positives on the
        // 200-PDF real-world prose sample are exactly this shape. The property this test exists for
        // is UNCHANGED and still asserted in full below: the genuinely distinct ruled table must
        // still be emitted, with its real rows, and must not be flagged as a likely duplicate. What
        // changed is that the degenerate 1-cell tagged table it could have been suppressed BY is now
        // suppressed itself -- a strictly stronger outcome, asserted explicitly. The general
        // property (a tagged table whose OUTER bbox is inflated away from its real cell footprint
        // must not suppress a distinct ruled table inside it) remains covered end-to-end at ranks
        // the extractor still emits by the hollow-middle, spread-nine-cell and partial-overlap
        // fixtures below, whose tagged tables have 2 and 9 real cells.
        Path pdf = tmp.resolve("sparse_tagged_inflated_bbox.pdf");
        TableTestPdfs.taggedSparseTwoMcidCellPlusSeparateRuledTable(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(1, r.tables.size(),
                    "the distinct ruled table must NOT be dropped just because its centroid falls "
                            + "inside a sparse tagged cell's inflated bbox; and the rank-1x1 sparse "
                            + "tagged table itself must not be emitted at all: " + r.tables);
            assertTrue(r.tables.stream().noneMatch(t -> "tagged".equals(t.extractionMethod)),
                    "a rank-1x1 tagged layout table must be rejected by MIN_TAGGED_RANK: " + r.tables);

            TableExtractor.TableHit lattice = r.tables.stream()
                    .filter(t -> "lattice".equals(t.extractionMethod))
                    .findFirst().orElseThrow(
                            () -> new AssertionError("the distinct ruled table must survive, not be suppressed: " + r.tables));
            assertEquals(List.of(List.of("L", "R"), List.of("C", "D")), lattice.rows);
            assertNull(lattice.likelyDuplicateOfTagged,
                    "the distinct ruled table must not be flagged just because its centroid falls "
                            + "inside a sparse tagged cell's inflated bbox: " + lattice.likelyDuplicateOfTagged);
        }
    }

    @Test
    void sparseTaggedCellDoesNotSuppressLargeDistinctRuledTableFillingMostOfItsBbox() throws Exception {
        // FIX 2 round-3 (post-review) reproducer: IoU alone closed round-2's false suppression
        // (a SMALL distinct ruled table inside an inflated sparse-tagged bbox), but a LARGE
        // distinct ruled table (a real 3x3, 9 cells) sized to fill ~75% of that SAME kind of
        // inflated bbox still gives IoU(latticeBbox, taggedBbox) > 0.5 -- IoU alone cannot tell
        // "same physical table" apart from "distinct table that happens to fill a degenerate
        // tagged bbox". The cell-count comparability guard closes this: the sparse tagged table
        // has only 1 real cell, the ruled table has 9 -- far from comparable -- so the ruled table
        // must survive no matter how much of the tagged bbox's area it fills.
        //
        // LEVER 4 UPDATE: see sparseTaggedCellInflatedBboxDoesNotSuppressDistinctRuledTable's own
        // note -- this fixture's tagged table is likewise rank 1x1 and so is now rejected by
        // TableExtractor.MIN_TAGGED_RANK before emission. Every assertion about the LARGE distinct
        // ruled table (present, 9 real cells, correct rows, unflagged) is kept verbatim; the
        // 1-cell-tagged side becomes an explicit assertion that the degenerate table is suppressed.
        Path pdf = tmp.resolve("sparse_tagged_large_ruled.pdf");
        TableTestPdfs.taggedSparseTwoMcidCellPlusSeparateLargeRuledTable(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(1, r.tables.size(),
                    "a large, genuinely distinct 9-cell ruled table must NOT be dropped just because "
                            + "it fills most of a 1-cell sparse tagged table's inflated bbox; and the "
                            + "rank-1x1 sparse tagged table itself must not be emitted: " + r.tables);
            assertTrue(r.tables.stream().noneMatch(t -> "tagged".equals(t.extractionMethod)),
                    "a rank-1x1 tagged layout table must be rejected by MIN_TAGGED_RANK: " + r.tables);

            TableExtractor.TableHit lattice = r.tables.stream()
                    .filter(t -> "lattice".equals(t.extractionMethod))
                    .findFirst().orElseThrow(
                            () -> new AssertionError("the large distinct ruled table must survive, not be suppressed: " + r.tables));
            assertEquals(9, lattice.cells.size(), "sanity: the ruled table really is a full 3x3 (9 cells)");
            assertEquals(List.of(
                    List.of("R1C1", "R1C2", "R1C3"),
                    List.of("R2C1", "R2C2", "R2C3"),
                    List.of("R3C1", "R3C2", "R3C3")), lattice.rows);
            assertNull(lattice.likelyDuplicateOfTagged,
                    "a large distinct ruled table must not be flagged just because it fills most of "
                            + "a 1-cell sparse tagged table's inflated bbox: " + lattice.likelyDuplicateOfTagged);
        }
    }

    @Test
    void multiMcidTdConcatenatesAllReferencedFragmentsIntoOneCellText() throws Exception {
        // Coverage gap (adversarial review, finding 1): the sparse-tagged-cell fixtures above build
        // a TD whose /K lists two far-apart MCIDs, but their tagged tables are rank 1x1 and are now
        // rejected by MIN_TAGGED_RANK before ever being emitted -- so nothing in the suite actually
        // asserts on the resolved text of a multi-MCID cell any more. This fixture is rank 2x2 (a
        // real TH/TH header row plus a TD/TD data row), so MIN_TAGGED_RANK lets it through, and its
        // row-2/col-1 TD is the same "one cell, two MCIDs, far apart" shape ("A" near the top of the
        // page, "B" near the bottom). The production behaviour under test is resolveCellText's use
        // of collectGlyphs (which walks EVERY /K entry, not just the first) followed by joinText
        // (which clusters far-apart glyphs onto separate lines): a single TD referencing multiple
        // MCIDs must have every one of those fragments concatenated into that cell's text.
        Path pdf = tmp.resolve("multi_mcid_cell.pdf");
        TableTestPdfs.taggedTwoMcidCellRank2x2(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(1, r.tables.size(), "the rank-2x2 tagged table must be emitted: " + r.tables);
            TableExtractor.TableHit tagged = r.tables.get(0);
            assertEquals("tagged", tagged.extractionMethod);
            assertEquals(2, tagged.rowCount);
            assertEquals(2, tagged.colCount);
            // cells is row-major: index 2 is row 1, col 0 -- the multi-MCID TD.
            assertEquals("A\nB", tagged.cells.get(2).text,
                    "a TD whose /K lists two far-apart MCIDs must concatenate BOTH fragments into "
                            + "that cell's text, not just the first: " + tagged.cells.get(2).text);
        }
    }

    @Test
    void spreadNineCellTaggedTableDoesNotSuppressDistinctNineCellRuledTable() throws Exception {
        // FIX 2 round-4 (post-review) reproducer: round-3's cell-count guard is bypassed by a
        // STRUCTURALLY-REAL 9-cell tagged table (a genuine 3x3 TR/TD grid, own MCID text in every
        // cell -- not a degenerate 1-cell sparse-MCID table) whose 9 cells are legitimately SPREAD
        // across almost the whole page: cellCount 9 == 9 (ratio 1.0, passes cellCountsComparable)
        // and IoU against a distinct 9-cell ruled table filling most of that inflated bbox is still
        // > 0.5 -- neither aggregate-comparison guard can tell this apart from a genuine same-table
        // match. Only the tagged table's OWN fill ratio (its cells cover a mere sliver of its own
        // bbox, unlike a real dense table) can. Both tables must survive.
        Path pdf = tmp.resolve("spread_nine_cell_tagged.pdf");
        TableTestPdfs.taggedSpreadNineCellPlusDistinctNineCellRuledTable(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(2, r.tables.size(),
                    "a distinct 9-cell ruled table must NOT be dropped just because a spread, "
                            + "structurally-real 9-cell tagged table also inflates to near-page size: " + r.tables);

            TableExtractor.TableHit tagged = r.tables.stream()
                    .filter(t -> "tagged".equals(t.extractionMethod))
                    .findFirst().orElseThrow(() -> new AssertionError("spread tagged table missing: " + r.tables));
            assertEquals(9, tagged.cells.size(), "sanity: the tagged table really is a structurally-real 3x3 (9 cells)");
            assertEquals(List.of(List.of("1", "2", "3"), List.of("4", "5", "6"), List.of("7", "8", "9")), tagged.rows);

            TableExtractor.TableHit lattice = r.tables.stream()
                    .filter(t -> "lattice".equals(t.extractionMethod))
                    .findFirst().orElseThrow(
                            () -> new AssertionError("the distinct 9-cell ruled table must survive, not be suppressed: " + r.tables));
            assertEquals(9, lattice.cells.size(), "sanity: the ruled table really is a full 3x3 (9 cells)");
            assertEquals(List.of(
                    List.of("R1C1", "R1C2", "R1C3"),
                    List.of("R2C1", "R2C2", "R2C3"),
                    List.of("R3C1", "R3C2", "R3C3")), lattice.rows);
            assertNull(lattice.likelyDuplicateOfTagged,
                    "a distinct 9-cell ruled table must not be flagged just because a spread, "
                            + "structurally-real 9-cell tagged table also inflates to near-page size: "
                            + lattice.likelyDuplicateOfTagged);
        }
    }

    @Test
    void hollowMiddleTaggedTableDoesNotSuppressDistinctRuledTableInTheGap() throws Exception {
        // FIX 2 round-5 (post-review, DEFINITIVE) reproducer -- "dense bookends, hollow middle":
        // every prior aggregate-comparison guard (centroid, IoU-via-outer-bbox, cell-count-ratio,
        // tagged fill-ratio) is defeated by a 2-cell tagged table whose two cells are each their
        // own genuinely DENSE block (a real header-ish block and a real footer-ish block, far
        // apart on the page -- e.g. a legitimately-one-table "continued on next page" note). Its
        // outer bbox spans the whole gap between the two blocks; a separate, genuinely distinct
        // ruled table sitting entirely in that EMPTY MIDDLE overlaps neither tagged cell's own
        // footprint at all. Only a per-cell-footprint test (does the tagged table's ACTUAL cell
        // geometry, not its outer bbox, cover the candidate?) correctly excludes this shape.
        Path pdf = tmp.resolve("hollow_middle.pdf");
        TableTestPdfs.taggedHollowMiddleTwoDenseBlocksPlusDistinctRuledTableInGap(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(2, r.tables.size(),
                    "a distinct ruled table sitting in the hollow middle between two dense tagged "
                            + "blocks must NOT be dropped just because it falls inside their union bbox: " + r.tables);

            TableExtractor.TableHit tagged = r.tables.stream()
                    .filter(t -> "tagged".equals(t.extractionMethod))
                    .findFirst().orElseThrow(() -> new AssertionError("hollow-middle tagged table missing: " + r.tables));
            assertEquals(2, tagged.cells.size(), "sanity: the tagged table really is the 2 dense blocks (header + footer)");
            assertTrue(tagged.cells.get(0).text.startsWith("Header dense line 0"),
                    "sanity: first cell is the dense header block: " + tagged.cells.get(0).text);
            assertTrue(tagged.cells.get(1).text.startsWith("Footer dense line 0"),
                    "sanity: second cell is the dense footer block: " + tagged.cells.get(1).text);

            TableExtractor.TableHit lattice = r.tables.stream()
                    .filter(t -> "lattice".equals(t.extractionMethod))
                    .findFirst().orElseThrow(
                            () -> new AssertionError("the distinct ruled table in the gap must survive, not be suppressed: " + r.tables));
            assertEquals(List.of(List.of("MID", ""), List.of("L", "R")), lattice.rows);
            assertNull(lattice.likelyDuplicateOfTagged,
                    "a distinct ruled table sitting in the hollow middle between two dense tagged "
                            + "blocks must not be flagged as a likely duplicate: " + lattice.likelyDuplicateOfTagged);
        }
    }

    @Test
    void partialCellOverlapBelowCoverageThresholdIsNotSuppressed() throws Exception {
        // FIX 2 round-5 sanity check: pins that CELL_FOOTPRINT_COVERAGE_THRESHOLD (0.5) actually
        // behaves as a threshold, not a rubber stamp -- a tagged table whose real cells overlap
        // only a PARTIAL fraction (measured ~0.28, comfortably inside [0.2, 0.5)) of a distinct
        // ruled table's own footprint must be KEPT (not suppressed): some genuine geometric overlap
        // exists (unlike the hollow-middle case above, where overlap is exactly zero), but not
        // enough to plausibly be the SAME table.
        Path pdf = tmp.resolve("partial_overlap.pdf");
        TableTestPdfs.taggedPartiallyOverlapsDistinctRuledTableBelowCoverageThreshold(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(2, r.tables.size(),
                    "a candidate table only PARTIALLY (well under half) covered by the tagged table's "
                            + "real cells must be kept, not treated as the same table: " + r.tables);
            assertTrue(r.tables.stream().anyMatch(t -> "tagged".equals(t.extractionMethod)));
            TableExtractor.TableHit lattice = r.tables.stream()
                    .filter(t -> "lattice".equals(t.extractionMethod))
                    .findFirst().orElseThrow(() -> new AssertionError("lattice table missing: " + r.tables));
            assertNull(lattice.likelyDuplicateOfTagged,
                    "partial (well under threshold) cell-footprint overlap must not set the advisory "
                            + "duplicate flag: " + lattice.likelyDuplicateOfTagged);
        }
    }

    // ---------------------------------------------------------------- FIX 4: lock-in only, no code change

    @Test
    void taggedDuplicateDrawnCellTextExtractsAsSingleCopyNotGarbled() throws Exception {
        // FIX 4 lock-in: mirrors TableLatticeTest#duplicateDrawnCellTextExtractsAsSingleCopyNotGarbled
        // but for the TAGGED path (glyphs resolved via BudgetedMarkedContentExtractor /
        // PDFMarkedContentExtractor, not PDFTextStripper/PDFTextStripperByArea). A prior fix
        // (commit 6d5bf8e) relies on suppressDuplicateOverlappingText staying ON for the tagged
        // path too, but only the region path had a committed test pinning it -- this closes that gap.
        Path pdf = tmp.resolve("tagged_dup_drawn.pdf");
        TableTestPdfs.taggedDuplicateDrawnCellText(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(1, r.tables.size());
            TableExtractor.TableHit t = r.tables.get(0);
            assertEquals("tagged", t.extractionMethod);
            assertEquals("Total", t.cells.get(0).text,
                    "a cell drawn twice at the same position via the tagged path must extract as a single "
                            + "correct copy (\"Total\"), not garbled (\"TToottaall\") or duplicated (\"TotalTotal\")");
        }
    }

    @Test
    void manyTablesSharingOneHugeTrSubtreeIsBoundedByDocumentWideStructureWork() throws Exception {
        // PR re-review P2 (DoS) reproducer: collectByType's FIX 1 DAG memoization only dedups
        // node visits WITHIN one call (a fresh identity-visited set per call) -- it does NOT dedup
        // ACROSS the once-per-Table-element calls extractTagged makes to find each table's own
        // "TR" descendants. A hostile structure DAG with many Table elements all referencing the
        // SAME large shared TR subtree forces that shared subtree to be walked in FULL, from
        // scratch, once per referencing Table element.
        //
        // 2,000 tables x (1 shared TR + 3,000 TD kids) = 6,002,000 total node visits if fully
        // walked -- comfortably past MAX_STRUCTURE_WORK (5,000,000), so the document-wide budget
        // must trip well before the loop completes naturally, bounding both wall-clock (this
        // assertion is a generous sanity backstop, not the primary check) and setting
        // Result.truncated -- WITHOUT ever needing the review's full ~100,000,000-visit adversarial
        // scale to prove the bound holds.
        Path pdf = tmp.resolve("shared_tr_subtree_bomb.pdf");
        int tableCount = 2_000;
        int trChildCount = 3_000;
        TableTestPdfs.taggedManyTablesShareOneHugeTrSubtree(pdf, tableCount, trChildCount);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            long start = System.nanoTime();
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(r.truncated,
                    "the document-wide structure-traversal work cap trip must set Result.truncated");
            assertTrue(elapsedMs < 15_000,
                    "must be bounded by the work budget, not walk anywhere near the full "
                            + (tableCount * (long) (trChildCount + 1)) + "-node blowup: took " + elapsedMs + "ms");
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

    // ---------------------------------------------------------------- PR re-review P2: MCR-page
    // ---------------------------------------------------------------- precedence over an INHERITED
    // ---------------------------------------------------------------- ancestor /Pg

    /**
     * Builds Table -&gt; TR -&gt; TD (single cell, no rulings) where {@code table.setPage(pageA)}
     * (so {@code pageA} is only reachable by the TD via ANCESTOR /Pg inheritance) while the TD's
     * OWN kid is a {@link PDMarkedContentReference} whose OWN /Pg points at {@code pageB} --
     * {@code pageB} is where the cell's real marked content (and so its real glyphs) lives. The TD
     * itself carries no direct {@code setPage(...)} of its own. Returns the TD element so the
     * caller can also drive {@link TableExtractor#resolveElementPageWithMcrFallback} on it
     * directly.
     */
    private static PDStructureElement buildCellWithOwnMcrOnDifferentPageThanInheritedAncestor(
            PDDocument doc, PDPage pageA, PDPage pageB) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, pageB)) {
            COSDictionary d = new COSDictionary();
            d.setInt(COSName.MCID, 0);
            cs.beginMarkedContent(COSName.getPDFName("TD"), PDPropertyList.create(d));
            TableTestPdfs.text(cs, 60, 700, "Hi");
            cs.endMarkedContent();
        }

        PDStructureTreeRoot root = new PDStructureTreeRoot();
        doc.getDocumentCatalog().setStructureTreeRoot(root);
        PDStructureElement table = new PDStructureElement("Table", root);
        table.setPage(pageA); // INHERITED by TR/TD below -- neither calls setPage() itself
        root.appendKid(table);
        PDStructureElement tr = new PDStructureElement("TR", table);
        table.appendKid(tr); // no tr.setPage()
        PDStructureElement cell = new PDStructureElement("TD", tr);
        // No cell.setPage() -- the cell's own /Pg is absent, so only inheritance (pageA) or its
        // own MCR (pageB) can resolve it.
        PDMarkedContentReference mcr = new PDMarkedContentReference();
        mcr.setPage(pageB); // the cell's OWN MCR page -- where collectGlyphs actually reads from
        mcr.setMCID(0);
        cell.appendKid(mcr);
        tr.appendKid(cell);
        // MIN_TAGGED_RANK scaffolding (see TableTestPdfs.declareColSpan2's doc). The filler row is
        // added with a null page so it keeps this fixture's whole point intact: no TR or TD in this
        // table ever calls setPage(), leaving /Pg resolvable only by ancestor inheritance or by the
        // content cell's own MCR.
        TableTestPdfs.declareColSpan2(cell);
        TableTestPdfs.appendEmptyRankFillerRow(table, null);
        return cell;
    }

    @Test
    void cellOwnMcrPageTakesPrecedenceOverInheritedAncestorPage() throws Exception {
        // Codex re-review P2 (correctness) reproducer: resolveElementPageWithMcrFallback used to
        // do the element+ANCESTOR /Pg walk FIRST, only falling back to a cell's own MCR page when
        // that walk returned null -- so a cell that INHERITS /Pg from an ancestor (here, the
        // Table) but whose OWN MCR points at a DIFFERENT page (where its glyphs actually live) was
        // wrongly resolved to the ANCESTOR's page. That both mis-gates the cell against
        // pagesToProcess and (were the ancestor page ever in scope too) mis-attributes the cell's
        // rotation/bbox to the wrong page's frame.
        //
        // Reference build: the SAME cell/glyph content, but with pageB UNROTATED, to get a "raw"
        // bbox U in the shared visual frame -- exactly the technique
        // taggedTableBboxSharesLatticeVisualFrameAcrossRotation above uses to prove which page's
        // rotation was actually applied, without hardcoding glyph-metric constants.
        float[] unrotatedBbox;
        try (PDDocument doc = new PDDocument()) {
            PDPage pageA = new PDPage(PDRectangle.LETTER);
            doc.addPage(pageA);
            PDPage pageB = new PDPage(PDRectangle.LETTER);
            doc.addPage(pageB);
            buildCellWithOwnMcrOnDifferentPageThanInheritedAncestor(doc, pageA, pageB);

            TableExtractor.Result ref = TableExtractor.extract(doc, List.of(2), Map.of());
            assertEquals(1, ref.tables.size(), "sanity: reference (unrotated pageB) build must extract");
            unrotatedBbox = ref.tables.get(0).bbox;
            assertNotNull(unrotatedBbox, "sanity: reference table must carry a bbox");
        }

        try (PDDocument doc = new PDDocument()) {
            PDPage pageA = new PDPage(PDRectangle.LETTER); // ancestor's INHERITED /Pg (wrong answer)
            doc.addPage(pageA);
            PDPage pageB = new PDPage(PDRectangle.LETTER); // cell's OWN MCR page (correct answer)
            pageB.setRotation(90);
            doc.addPage(pageB);
            PDStructureElement cell =
                    buildCellWithOwnMcrOnDifferentPageThanInheritedAncestor(doc, pageA, pageB);

            // Direct unit-level assertion (RED pre-fix: returns pageA).
            assertEquals(pageB, TableExtractor.resolveElementPageWithMcrFallback(cell),
                    "a cell's OWN MCR page must take precedence over an INHERITED ancestor /Pg");

            // End-to-end: only pageB (page 2, where the glyphs really live) is in scope. Pre-fix,
            // the early page-gate resolves pageA (page 1, out of scope) for this cell, so the
            // whole table is silently dropped even though its only real content is in scope.
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(2), Map.of());
            assertEquals(1, r.tables.size(),
                    "a table whose only cell resolves via its own MCR to an in-scope page must not "
                            + "be dropped just because it INHERITS a different, out-of-scope page: "
                            + r.tables);
            TableExtractor.TableHit t = r.tables.get(0);
            assertEquals(2, t.page,
                    "table attributed to the cell's own MCR page, not the inherited ancestor page");
            assertEquals("Hi", t.cells.get(0).text);
            assertNotNull(t.cells.get(0).bbox);

            // Not mis-rotated: the cell's bbox must reflect pageB's OWN rotation (90) -- the page
            // its glyphs are actually on -- not pageA's (0, the merely-inherited ancestor page).
            PDRectangle cropBox = pageB.getCropBox();
            float uw = cropBox.getWidth(), uh = cropBox.getHeight();
            float[] c0 = TableExtractor.applyPageRotation(unrotatedBbox[0], unrotatedBbox[1], 90, uw, uh);
            float[] c1 = TableExtractor.applyPageRotation(unrotatedBbox[2], unrotatedBbox[3], 90, uw, uh);
            float expX0 = Math.min(c0[0], c1[0]), expX1 = Math.max(c0[0], c1[0]);
            float expY0 = Math.min(c0[1], c1[1]), expY1 = Math.max(c0[1], c1[1]);
            assertEquals(expX0, t.cells.get(0).bbox[0], 0.05f, "bbox[0] must use pageB's rotation");
            assertEquals(expY0, t.cells.get(0).bbox[1], 0.05f, "bbox[1] must use pageB's rotation");
            assertEquals(expX1, t.cells.get(0).bbox[2], 0.05f, "bbox[2] must use pageB's rotation");
            assertEquals(expY1, t.cells.get(0).bbox[3], 0.05f, "bbox[3] must use pageB's rotation");
        }
    }

    @Test
    void cellOwnDirectPgTakesPrecedenceOverOwnMcr() throws Exception {
        // Control for the fix above: a cell's OWN direct /Pg (not inherited from any ancestor)
        // must still beat its OWN MCR child's page -- the MCR fallback is a fallback for when NO
        // more-specific /Pg source is present, not a competitor to the cell's own explicit /Pg.
        try (PDDocument doc = new PDDocument()) {
            PDPage pageC = new PDPage(PDRectangle.LETTER); // cell's OWN direct /Pg (correct answer)
            doc.addPage(pageC);
            PDPage pageD = new PDPage(PDRectangle.LETTER); // cell's OWN MCR page (must lose)
            doc.addPage(pageD);

            PDStructureTreeRoot root = new PDStructureTreeRoot();
            doc.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement table = new PDStructureElement("Table", root);
            root.appendKid(table);
            PDStructureElement tr = new PDStructureElement("TR", table);
            table.appendKid(tr);
            PDStructureElement cell = new PDStructureElement("TD", tr);
            cell.setPage(pageC); // cell's OWN direct /Pg
            PDMarkedContentReference mcr = new PDMarkedContentReference();
            mcr.setPage(pageD); // same cell ALSO carries an MCR pointing elsewhere
            mcr.setMCID(0);
            cell.appendKid(mcr);
            tr.appendKid(cell);

            assertEquals(pageC, TableExtractor.resolveElementPageWithMcrFallback(cell),
                    "a cell's OWN direct /Pg must take precedence over its OWN MCR child's page");
        }
    }
}
