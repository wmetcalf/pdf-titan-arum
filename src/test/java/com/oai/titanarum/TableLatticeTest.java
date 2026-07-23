package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

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
            // (C) pure-lattice page, no tagged table anywhere on it -> the advisory dedup flag
            // must never be set (left null, so omitted from report.json entirely).
            assertNull(t.likelyDuplicateOfTagged,
                    "a lattice table on a page with no tagged tables must never be flagged as a "
                            + "likely duplicate of one: " + t.likelyDuplicateOfTagged);
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
    void adjacentIndependentTablesSplitIntoTwoCorrectTables() throws Exception {
        // FIX 5 reproducer: two independent, axis-aligned tables (A: 2x2 @ 30pt row pitch; B: 3x2
        // @ 20pt row pitch) drawn directly touching, sharing one vertical border ruling at x=250.
        // groupIntoTables' edge-adjacency union-find merges them into ONE component; without the
        // split fix this used to yield a single bogus rowCount=4/colCount=4 table with invented
        // spans and interleaved rows. Must now split into exactly two correct tables.
        Path pdf = tmp.resolve("adjacent.pdf");
        TableTestPdfs.adjacentIndependentTables(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), stripPositions(doc, List.of(1)));
            assertFalse(r.truncated);
            assertEquals(2, r.tables.size(), "two independent adjacent tables must split, not merge: " + r.tables);

            TableExtractor.TableHit a = r.tables.stream()
                    .filter(t -> t.rowCount == 2 && t.colCount == 2)
                    .findFirst().orElseThrow(() -> new AssertionError("2x2 table A not found: " + r.tables));
            TableExtractor.TableHit b = r.tables.stream()
                    .filter(t -> t.rowCount == 3 && t.colCount == 2)
                    .findFirst().orElseThrow(() -> new AssertionError("3x2 table B not found: " + r.tables));

            assertEquals(List.of(List.of("A11", "A12"), List.of("A21", "A22")), a.rows);
            assertTrue(a.cells.stream().allMatch(c -> c.rowSpan == 1 && c.colSpan == 1),
                    "table A must have no invented spans: " + a.cells);

            assertEquals(List.of(
                    List.of("B11", "B12"), List.of("B21", "B22"), List.of("B31", "B32")), b.rows);
            assertTrue(b.cells.stream().allMatch(c -> c.rowSpan == 1 && c.colSpan == 1),
                    "table B must have no invented spans: " + b.cells);
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
     * Rulings (RulingCollector) and TextPosition disagree about which coordinate space is
     * "rotation aware" (getXDirAdj/getYDirAdj track per-glyph text DIRECTION, not the page's
     * /Rotate; getX()/getY() DOES track /Rotate, but neither text-fill path uses it directly --
     * both {@link TableExtractor#fillCellsFromPositions} and the region-fallback's
     * PositionCollectingStripper explicitly re-derive the visual frame from getXDirAdj/getYDirAdj
     * via {@link TableExtractor#applyPageRotation}). Pin that both text-fill paths land on the
     * correct cell regardless.
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
    void duplicateDrawnCellTextExtractsAsSingleCopyNotGarbled() throws Exception {
        // f095959 regression reproducer, still valid post round-6 rewrite: fillCellsByRegion
        // (--skip-text-urls / positionsByPage empty -> region fallback) must extract a cell whose
        // text is drawn TWICE at the IDENTICAL position as the single correct copy ("Total"), not
        // a duplicate-suppression regression's character-interleaved garble ("TToottaall") nor a
        // doubled transcript ("TotalTotal"). Since round 6, this is no longer PDFTextStripterByArea's
        // suppressDuplicateOverlappingText flag doing the work at all (that class is gone) -- it's
        // the BASE PDFTextStripper's own processTextPosition dedup (on by default, never touched by
        // PositionCollectingStripper), which every PDFTextStripper subclass gets for free and which
        // fillCellsByRegion now inherits unconditionally. See MAX_REGION_GLYPHS's doc for the full
        // round-6 history of why the old PDFTextStripterByArea-based approach could never reconcile
        // this correctness requirement with the overlapping-cell-regions correctness requirement
        // (round-6's own new finding) at the same time.
        Path pdf = tmp.resolve("dup.pdf");
        TableTestPdfs.ruled2x2DuplicateDrawnCell(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of()); // region-fill path
            assertEquals(1, r.tables.size());
            TableExtractor.TableHit t = r.tables.get(0);
            assertEquals(List.of(List.of("Total", "B"), List.of("C", "D")), t.rows,
                    "a cell drawn twice at the same position must extract as a single correct copy, "
                            + "not garbled/interleaved or duplicated: " + t.rows);
        }
    }

    @Test
    void overlappingCellRegionsBothReceiveSharedGlyph() throws Exception {
        // round-6 finding: PDFTextStripterByArea's suppressDuplicateOverlappingText dedup
        // (needed -- see duplicateDrawnCellTextExtractsAsSingleCopyNotGarbled above -- to collapse
        // a genuinely duplicate-drawn glyph into one copy) shares its bookkeeping (a single
        // characterListMapping field) ACROSS every region registered in one extractRegions() call.
        // So a glyph that geometrically falls inside TWO OR MORE overlapping cell regions used to
        // be recorded for only the FIRST region that claimed it and silently DROPPED from every
        // other region that also genuinely contains it -- a confirmed MED correctness bug, not a
        // hostile-input concern.
        //
        // Fixture: table A is a 2x2 ruled grid; table B is a SEPARATE, smaller 2x2 ruled grid
        // nested entirely inside table A's (row0,col0) cell (see
        // TableTestPdfs.nestedOverlappingTables's doc for why no ruling from either table ever
        // crosses the other, so groupIntoTables keeps them as two independent kept components
        // rather than merging them into one). A single glyph ("X") sits at a point inside BOTH
        // table A's (row0,col0) cell AND table B's (row0,col0) cell.
        //
        // RED (confirmed against pre-round-6 HEAD): table B's (row0,*) cells came back EMPTY --
        // "X" (and B's own "B01" label, which shares table A's larger cell too) were retained only
        // by table A's region, dropped from table B's own overlapping regions entirely, even
        // though B's cells geometrically contain them just as validly as A's does.
        //
        // GREEN (this fix): fillCellsByRegion buckets each kept cell INDEPENDENTLY via
        // fillCellsFromPositions' per-cell midpoint-containment test (the same mechanism the
        // default, non-skip-text-urls path already uses), so a glyph inside more than one cell's
        // rect is correctly retained by EVERY one of them -- table A's (row0,col0) cell shows every
        // glyph geometrically inside its (large) rect, INCLUDING all of table B's glyphs, and table
        // B's own smaller cells each correctly show their own glyph too.
        Path pdf = tmp.resolve("nestedoverlap.pdf");
        TableTestPdfs.nestedOverlappingTables(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of()); // --skip-text-urls
            assertFalse(r.truncated);
            assertEquals(2, r.tables.size(), "two independent, geometrically-overlapping tables must both survive: " + r.tables);

            TableExtractor.TableHit tableA = r.tables.stream()
                    .filter(t -> t.bbox[2] - t.bbox[0] > 300) // A spans x[50,450]; B spans x[100,200]
                    .findFirst().orElseThrow(() -> new AssertionError("outer table A not found: " + r.tables));
            TableExtractor.TableHit tableB = r.tables.stream()
                    .filter(t -> t.bbox[2] - t.bbox[0] <= 300)
                    .findFirst().orElseThrow(() -> new AssertionError("nested table B not found: " + r.tables));

            // Table B: the shared glyph "X" must land in B's own (row0,col0) cell, not be dropped
            // because table A's overlapping region already claimed it.
            assertEquals(List.of(List.of("X", "B01"), List.of("B10", "B11")), tableB.rows,
                    "table B's cells must all receive their own text, including the glyph shared "
                            + "with table A's overlapping cell: " + tableB.rows);

            // Table A: its (row0,col0) cell fully contains table B, so it must show every glyph
            // geometrically inside it -- including B's own glyphs, none of which are exclusive to
            // B's smaller regions.
            TableExtractor.CellHit a00 = tableA.cells.stream()
                    .filter(c -> c.row == 0 && c.col == 0).findFirst().orElseThrow();
            for (String must : new String[]{"X", "B01", "B10", "B11"}) {
                assertTrue(a00.text.contains(must),
                        "table A's (row0,col0) cell must still contain '" + must
                                + "' (nested table B's overlap must not steal it away): " + a00.text);
            }
        }
    }

    @Test
    void fillCellsByRegionCapsRegionCellsAndSetsTruncated() throws Exception {
        // round-6 adaptation: fillCellsByRegion no longer has a dedicated cell-count cap
        // (MAX_REGION_CELLS is gone -- see MAX_REGION_GLYPHS's doc) because the new mechanism's
        // collection pass is O(glyphs) regardless of cell count, and its bucketing pass
        // (fillCellsFromPositions) is already bounded by the SAME cells-x-positions work budget
        // (MAX_TEXTFILL_WORK) used everywhere else in this class. An extreme cell count combined
        // with even a modest real glyph count still exceeds that product budget and must still
        // throw promptly (not hang) -- driven here through extract()'s per-page catch, so
        // Result.truncated is still the observable end-to-end signal, matching what this test
        // verified before.
        Path pdf = tmp.resolve("hugecellcount.pdf");
        TableTestPdfs.ruled3x3(pdf); // a dozen or so real glyphs, all within a small bbox
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDPage page = doc.getPage(0);
            List<TextPosition> positions = stripPositions(doc, List.of(1)).get(1);
            int glyphCount = positions.size();

            // Enough synthetic cells (all overlapping the real text's bbox, so every one of them
            // is actually charged against the work budget, not skipped) that cellCount * glyphCount
            // exceeds MAX_TEXTFILL_WORK.
            long cellCount = TableExtractor.MAX_TEXTFILL_WORK / glyphCount + 10;
            assertTrue(cellCount < 5_000_000, "sanity: keep the synthetic cell list a reasonable size for a unit test");
            List<TableExtractor.CellRect> comp = new ArrayList<>();
            for (long i = 0; i < cellCount; i++) {
                TableExtractor.CellRect c = new TableExtractor.CellRect();
                c.x0 = 0; c.y0 = 0; c.x1 = 400; c.y1 = 400; // overlaps all the real glyphs
                comp.add(c);
            }
            assertTrue((long) glyphCount * cellCount > TableExtractor.MAX_TEXTFILL_WORK,
                    "sanity: glyphs x cells must actually exceed MAX_TEXTFILL_WORK for this to be a real product-bound test");

            long start = System.nanoTime();
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            // extract() itself only ever builds cells from real ruling geometry (a handful for
            // ruled3x3), so it won't organically trip this -- drive fillCellsByRegion directly
            // with the synthetic huge cell list instead, then confirm extract()'s own
            // RulingOverflowException handling contract (proven generically elsewhere in this
            // suite) is what turns the same exception into Result.truncated=true.
            List<List<TableExtractor.CellRect>> tables = List.of(comp);
            TableExtractor.Result direct = new TableExtractor.Result();
            assertThrows(TableExtractor.RulingOverflowException.class,
                    () -> TableExtractor.fillCellsByRegion(tables, page, direct),
                    "an extreme cell count x real glyph count exceeding MAX_TEXTFILL_WORK must throw, "
                            + "not hang or silently complete");
            assertTrue(elapsedMs < 5000, "sanity: the unrelated real extract() call above must itself be fast: " + elapsedMs + "ms");
            assertFalse(r.truncated, "sanity: extract() on the real (small) ruled3x3 fixture alone must not truncate");
        }
    }

    @Test
    void fillCellsByRegionGlyphWorkIsBudgeted() throws Exception {
        // MAX_REGION_GLYPHS reproducer: fillCellsByRegion's position-COLLECTION side must be
        // bounded regardless of cell count. PositionCollectingStripper counts every RETAINED
        // TextPosition and throws once the budget is exceeded, before the base PDFTextStripper's
        // own (otherwise unbounded) per-glyph bookkeeping runs any further. Pin that with the
        // package-private explicit-glyph-budget overload (mirroring fillCellsFromPositions' own
        // test-only budget override) rather than needing a real multi-million-glyph fixture -- see
        // regionGlyphBombIsBoundedNotBufferedOrOOMed below for the full-scale proof.
        Path pdf = tmp.resolve("regionbudget.pdf");
        TableTestPdfs.ruled3x3(pdf); // a handful of real glyphs (R1C1..R3C3) is plenty
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDPage page = doc.getPage(0);

            List<TableExtractor.CellRect> comp = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                TableExtractor.CellRect c = new TableExtractor.CellRect();
                c.x0 = 0; c.y0 = 0; c.x1 = 400; c.y1 = 400; // overlaps all the real glyphs
                comp.add(c);
            }
            List<List<TableExtractor.CellRect>> tables = List.of(comp);

            TableExtractor.Result result = new TableExtractor.Result();
            assertThrows(TableExtractor.RulingOverflowException.class,
                    () -> TableExtractor.fillCellsByRegion(tables, page, result, 2L),
                    "a glyph count exceeding MAX_REGION_GLYPHS must throw, not stall/buffer unbounded");
        }
    }

    @Test
    void regionWorkBudgetCatchesMaxBothShapeThatGlyphCapAloneWouldMiss() throws Exception {
        // MAX_TEXTFILL_WORK reproducer (round-6 adaptation of the original MAX_REGION_WORK test):
        // MAX_REGION_GLYPHS bounds the RETAINED glyph COUNT alone, but the bucketing pass
        // (fillCellsFromPositions) is O(cells x retained positions) -- neither the glyph cap nor a
        // small cell count alone bounds that PRODUCT. This fixture uses a cell count (5,000) and
        // 50,000 real glyphs whose product (250,000,000) is comfortably under MAX_REGION_GLYPHS
        // (2,000,000) for the glyph side, but well past MAX_TEXTFILL_WORK (20,000,000) for the
        // product side -- proving the product-work budget, not either factor alone, is what's
        // bounded end-to-end through the PRODUCTION fillCellsByRegion(tables, page, result) entry
        // point.
        Path pdf = tmp.resolve("regionwork.pdf");
        int glyphCount = 50_000;
        TableTestPdfs.manyGlyphsOnePage(pdf, glyphCount);
        assertTrue(glyphCount < TableExtractor.MAX_REGION_GLYPHS,
                "sanity: glyph count alone must be nowhere near MAX_REGION_GLYPHS -- "
                        + "the glyph-only cap would NOT catch this shape, only the product-work cap can");

        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDPage page = doc.getPage(0);

            int cellCount = 5_000;
            List<TableExtractor.CellRect> comp = new ArrayList<>();
            for (int i = 0; i < cellCount; i++) {
                TableExtractor.CellRect c = new TableExtractor.CellRect();
                // Overlapping the huge glyph run's bbox so every glyph is actually retained (not
                // discarded by PositionCollectingStripper's combined-bbox pre-filter), so the
                // bucketing pass really does see all 50,000 positions per cell.
                c.x0 = -1_000f; c.y0 = 700f; c.x1 = 100_000_000f; c.y1 = 800f;
                comp.add(c);
            }
            List<List<TableExtractor.CellRect>> tables = List.of(comp);
            assertTrue((long) glyphCount * cellCount > TableExtractor.MAX_TEXTFILL_WORK,
                    "sanity: glyphs x cells must actually exceed MAX_TEXTFILL_WORK for this to be a real product-bound test");

            TableExtractor.Result result = new TableExtractor.Result();
            long start = System.nanoTime();
            assertThrows(TableExtractor.RulingOverflowException.class,
                    () -> TableExtractor.fillCellsByRegion(tables, page, result),
                    "glyphs x cells exceeding MAX_TEXTFILL_WORK must throw, even though glyph count alone is under MAX_REGION_GLYPHS");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 5000, "the product-work cap must trip fast, not process all " + glyphCount + " glyphs x " + cellCount + " cells: took " + elapsedMs + "ms");
        }
    }

    @Test
    void regionWorkBudgetNotTrippedByLegitSmallTable() throws Exception {
        // Companion to the adversarial test above: a REALISTIC region-fill shape (a handful of
        // cells, a handful of glyphs) must complete normally through the PRODUCTION
        // fillCellsByRegion (real MAX_REGION_GLYPHS / MAX_TEXTFILL_WORK budgets) without tripping
        // either cap -- the work counter must not be so tight that legitimate small tables get
        // spuriously truncated.
        Path pdf = tmp.resolve("regionworklegit.pdf");
        TableTestPdfs.ruled3x3(pdf); // 3x3 table, 9 cells, a dozen or so real glyphs
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertFalse(r.truncated, "a legitimate small table must not trip MAX_TEXTFILL_WORK");
            assertEquals(1, r.tables.size());
            assertEquals(List.of(
                    List.of("R1C1", "R1C2", "R1C3"),
                    List.of("R2C1", "R2C2", "R2C3"),
                    List.of("R3C1", "R3C2", "R3C3")), r.tables.get(0).rows);
        }
    }

    @Test
    void regionGlyphBombIsBoundedNotBufferedOrOOMed() throws Exception {
        // round-3 regression reproducer, adapted for round 6: a small-on-disk, one-page PDF (a
        // single Flate-compressed (AAAA...) Tj) carrying 6,000,000 glyphs, run through the
        // PRODUCTION entry point TableExtractor.fillCellsByRegion(tables, page, result) in a CHILD
        // JVM capped at a small, fixed heap -- with the probe's single cell now OVERLAPPING the
        // entire glyph run (rather than far from it, as the original round-3 test placed it),
        // forcing PositionCollectingStripper to actually RETAIN glyphs up to MAX_REGION_GLYPHS
        // rather than discarding them via its combined-bbox pre-filter. This proves the retention
        // CAP itself -- not the pre-filter -- is what bounds memory: worst case, up to
        // MAX_REGION_GLYPHS (2,000,000) real TextPosition objects are alive at once before the
        // throw.
        //
        // Before the round-3 fix, fillCellsByRegion's --skip-text-urls fallback (stripAllPositions,
        // an anonymous PDFTextStripper subclass) BUFFERED every page glyph into a List before
        // bucketing -- PDFTextStripper's own per-glyph bookkeeping is itself unbounded, so this
        // OOM'd even at -Xmx2g. Measured directly against THIS round's implementation
        // (PositionCollectingStripper, retention capped at MAX_REGION_GLYPHS, with this test's
        // cell overlapping the glyph run so retention actually reaches the cap): a binary search
        // over -Xmx found the pass/fail boundary between -Xmx704m (OutOfMemoryError) and -Xmx768m
        // (completes cleanly, throwing the bounded RulingOverflowException once 2,000,000
        // positions are retained) -- so ~2,000,000 real, non-trivial TextPosition objects (each
        // carrying its own Matrix, font reference, and per-glyph width data) plus JVM/PDFBox's own
        // fixed overhead need a bit under 768m of heap. -Xmx1024m (1g) is used here for a
        // comfortable, still small and deliberately-bounded margin above that measured boundary --
        // comfortably under the production -Xmx4g the real server runs with (4g has almost 4x this
        // fixture's measured requirement to spare for everything else the server does).
        Path pdf = tmp.resolve("bomb.pdf");
        int glyphCount = 6_000_000;

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBinary());
        cmd.add("-Xmx1024m");
        cmd.add("-cp");
        cmd.add(currentTestClasspath());
        cmd.add("com.oai.titanarum.TableRegionGlyphBombProbe");
        cmd.add(String.valueOf(glyphCount));
        cmd.add(pdf.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Path log = tmp.resolve("probe.log");
        pb.redirectOutput(log.toFile());

        Process proc = pb.start();
        boolean exited = proc.waitFor(60, TimeUnit.SECONDS);
        String output = exited ? java.nio.file.Files.readString(log) : "(child did not exit)";
        try {
            assertTrue(exited, "the probe child JVM must complete (not hang) within 60s: " + output);
            assertEquals(0, proc.exitValue(),
                    "fillCellsByRegion on a 6,000,000-glyph page must not OOM/crash at -Xmx1024m: " + output);
            assertTrue(output.contains("PROBE_OK"), "probe must report success: " + output);
        } finally {
            proc.destroyForcibly();
        }
    }

    @Test
    void interruptedThreadStopsProcessingFurtherPages() throws Exception {
        // extract() had zero interrupt checks: a multi-page hostile doc would ignore a soft
        // --timeout entirely (only checked by the caller AFTER extract() returns), eventually
        // triggering the hard-halt watchdog. A between-page check (top of the per-page loop,
        // before the try block, so it can never be swallowed by catch(Exception)) must stop
        // promptly and flag truncated, instead of processing every page.
        Path pdf = tmp.resolve("multipage.pdf");
        int pageCount = 20;
        TableTestPdfs.multiPageRuled3x3(pdf, pageCount);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<Integer> pages = new ArrayList<>();
            for (int p = 1; p <= pageCount; p++) pages.add(p);

            TableExtractor.Result full = TableExtractor.extract(doc, pages, Map.of());
            assertEquals(pageCount, full.tables.size(), "sanity: every page yields one table when uninterrupted");
            assertFalse(full.truncated);

            Thread.currentThread().interrupt();
            try {
                TableExtractor.Result interrupted = TableExtractor.extract(doc, pages, Map.of());
                assertTrue(interrupted.truncated, "interrupted extract() must set Result.truncated");
                assertTrue(interrupted.tables.size() < pageCount,
                        "interrupted extract() must not process every page: got "
                                + interrupted.tables.size() + " of " + pageCount);
            } finally {
                Thread.interrupted(); // clear the flag so it doesn't leak into other tests
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

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * Builds the classpath to hand to a freshly-launched child JVM so it can find {@code
     * TableRegionGlyphBombProbe} and its dependencies (PDFBox, ...). Surefire sometimes forks its
     * own test JVM using a single manifest-only "booter" jar (to dodge OS command-line length
     * limits) whose {@code java.class.path} system property is therefore just that one jar; the
     * real classpath lives in its {@code Class-Path} manifest attribute. Handle both cases so
     * this test is robust to Surefire's forking strategy (mirrors {@code HardWatchdogTest}'s own
     * identical helper).
     */
    private static String currentTestClasspath() throws IOException {
        String cp = System.getProperty("java.class.path");
        String[] entries = cp.split(File.pathSeparator);
        if (entries.length == 1 && entries[0].toLowerCase(Locale.ROOT).endsWith(".jar")) {
            try (JarFile jf = new JarFile(entries[0])) {
                String classPathAttr = jf.getManifest() == null ? null
                        : jf.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
                if (classPathAttr != null && !classPathAttr.isBlank()) {
                    StringBuilder sb = new StringBuilder(entries[0]);
                    for (String part : classPathAttr.split(" ")) {
                        if (part.isBlank()) continue;
                        sb.append(File.pathSeparator).append(new File(URI.create(part)).getAbsolutePath());
                    }
                    return sb.toString();
                }
            }
        }
        return cp;
    }
}
