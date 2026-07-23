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
    void duplicateDrawnCellTextExtractsAsSingleCopyNotGarbled() throws Exception {
        // f095959 regression reproducer: fillCellsByRegion (--skip-text-urls / positionsByPage
        // empty -> region fallback) must extract a cell whose text is drawn TWICE at the
        // IDENTICAL position as the single correct copy ("Total"), not a duplicate-suppression
        // regression's character-interleaved garble ("TToottaall") nor a doubled transcript
        // ("TotalTotal"). f095959's setSuppressDuplicateOverlappingText(false) -- added to make
        // matched glyphs cheap for the region-fill work budget -- broke this: with
        // setSortByPosition(true) (needed for cell text ordering/rotation) and suppression OFF,
        // the sort interleaves the two identically-positioned "Total" runs character-by-character.
        // Restoring suppression (its PDFTextStripperByArea default) fixes it; the DoS concern that
        // motivated disabling it is instead closed purely by MAX_REGION_WORK (see that constant's
        // doc).
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
    void fillCellsByRegionCapsRegionCellsAndSetsTruncated() throws Exception {
        // Reviewer's reproducer: fillCellsByRegion (the --skip-text-urls fallback) had NO work
        // budget, unlike fillCellsFromPositions -- it would register one PDFTextStripperByArea
        // region per kept cell (up to ~40k) with an O(glyphs x cells) extractRegions() pass.
        // Drive it directly with a synthetic cell list well past MAX_REGION_CELLS and confirm it
        // bails out promptly (no region registration / content-stream walk) and flags truncated.
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);

            List<TableExtractor.CellRect> comp = new ArrayList<>();
            int overCap = TableExtractor.MAX_REGION_CELLS + 500;
            for (int i = 0; i < overCap; i++) {
                TableExtractor.CellRect c = new TableExtractor.CellRect();
                c.x0 = i; c.y0 = 0; c.x1 = i + 1; c.y1 = 1;
                comp.add(c);
            }
            List<List<TableExtractor.CellRect>> tables = List.of(comp);

            TableExtractor.Result result = new TableExtractor.Result();
            long start = System.nanoTime();
            TableExtractor.fillCellsByRegion(tables, page, result);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(result.truncated, "exceeding MAX_REGION_CELLS must set Result.truncated");
            assertTrue(elapsedMs < 5000, "must bail out promptly instead of registering " + overCap + " regions");
        }
    }

    @Test
    void fillCellsByRegionGlyphWorkIsBudgeted() throws Exception {
        // MAX_REGION_GLYPHS reproducer (round 3, FIX A): fillCellsByRegion's GLYPH side (as
        // opposed to the cell side capped by MAX_REGION_CELLS above) must be bounded regardless
        // of cell count. RegionStripper counts every glyph the content-stream engine reports --
        // whether or not it falls inside a registered cell region -- and throws once the budget
        // is exceeded, BEFORE delegating to PDFTextStripperByArea's own per-glyph bookkeeping.
        // Pin that with the package-private explicit-glyph-budget overload (mirroring
        // fillCellsFromPositions' own test-only budget override) rather than needing a real
        // multi-million-glyph fixture -- see regionGlyphBombIsBoundedNotBufferedOrOOMed below for
        // the full-scale (real MAX_REGION_GLYPHS, millions of glyphs) proof.
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
            assertTrue(comp.size() <= TableExtractor.MAX_REGION_CELLS,
                    "sanity: this must exercise the GLYPH budget, not the (separate) cell-count cap");

            TableExtractor.Result result = new TableExtractor.Result();
            assertThrows(TableExtractor.RulingOverflowException.class,
                    () -> TableExtractor.fillCellsByRegion(tables, page, result, 2L),
                    "a glyph count exceeding MAX_REGION_GLYPHS must throw, not stall/buffer unbounded");
        }
    }

    @Test
    void regionWorkBudgetCatchesMaxBothShapeThatGlyphCapAloneWouldMiss() throws Exception {
        // MAX_REGION_WORK reproducer (round 3 follow-up): MAX_REGION_GLYPHS bounds the glyph
        // COUNT alone, and MAX_REGION_CELLS bounds the cell COUNT alone, but
        // PDFTextStripperByArea's own per-glyph region-match scan is O(glyphs x cells) -- neither
        // cap alone bounds that PRODUCT. This fixture deliberately maxes cells (at the
        // MAX_REGION_CELLS boundary, 5,000) and uses 50,000 real glyphs: comfortably UNDER
        // MAX_REGION_GLYPHS (2,000,000) -- so the glyph-only cap would let this shape run to
        // completion (~50,000 x 5,000 = 250,000,000 containment checks, well past the live
        // MAX_REGION_WORK budget (see that constant's doc for its current value/calibration),
        // though still "only" a fraction of a second here; the
        // production-scale adversarial version -- glyphs near the 2,000,000 cap alongside 5,000
        // cells -- is what risks tens of seconds and the hard-halt window). RegionStripper's
        // work counter (glyphs x currently-registered-region-count, charged per glyph) must
        // trip FIRST, proving the product is what's bounded, not just either factor alone.
        Path pdf = tmp.resolve("regionwork.pdf");
        int glyphCount = 50_000;
        TableTestPdfs.manyGlyphsOnePage(pdf, glyphCount);
        assertTrue(glyphCount < TableExtractor.MAX_REGION_GLYPHS,
                "sanity: glyph count alone must be nowhere near MAX_REGION_GLYPHS -- "
                        + "the glyph-only cap would NOT catch this shape, only the product-work cap can");

        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDPage page = doc.getPage(0);

            List<TableExtractor.CellRect> comp = new ArrayList<>();
            for (int i = 0; i < TableExtractor.MAX_REGION_CELLS; i++) {
                TableExtractor.CellRect c = new TableExtractor.CellRect();
                c.x0 = i; c.y0 = 0; c.x1 = i + 1; c.y1 = 1; // position irrelevant -- work is charged per glyph regardless of match
                comp.add(c);
            }
            List<List<TableExtractor.CellRect>> tables = List.of(comp);
            assertTrue(comp.size() <= TableExtractor.MAX_REGION_CELLS, "sanity: must not trip the (separate) cell-count cap");
            assertTrue((long) glyphCount * comp.size() > TableExtractor.MAX_REGION_WORK,
                    "sanity: glyphs x cells must actually exceed MAX_REGION_WORK for this to be a real product-bound test");

            TableExtractor.Result result = new TableExtractor.Result();
            long start = System.nanoTime();
            assertThrows(TableExtractor.RulingOverflowException.class,
                    () -> TableExtractor.fillCellsByRegion(tables, page, result),
                    "glyphs x cells exceeding MAX_REGION_WORK must throw, even though glyph count alone is under MAX_REGION_GLYPHS");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertTrue(elapsedMs < 5000, "the product-work cap must trip fast, not process all " + glyphCount + " glyphs: took " + elapsedMs + "ms");
        }
    }

    @Test
    void regionWorkBudgetNotTrippedByLegitSmallTable() throws Exception {
        // Companion to the adversarial test above: a REALISTIC region-fill shape (a handful of
        // cells, a handful of glyphs) must complete normally through the PRODUCTION
        // fillCellsByRegion (real MAX_REGION_GLYPHS / MAX_REGION_WORK budgets) without tripping
        // either cap -- the work counter must not be so tight that legitimate small tables get
        // spuriously truncated.
        Path pdf = tmp.resolve("regionworklegit.pdf");
        TableTestPdfs.ruled3x3(pdf); // 3x3 table, 9 cells, a dozen or so real glyphs
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertFalse(r.truncated, "a legitimate small table must not trip MAX_REGION_WORK");
            assertEquals(1, r.tables.size());
            assertEquals(List.of(
                    List.of("R1C1", "R1C2", "R1C3"),
                    List.of("R2C1", "R2C2", "R2C3"),
                    List.of("R3C1", "R3C2", "R3C3")), r.tables.get(0).rows);
        }
    }

    @Test
    void regionGlyphBombIsBoundedNotBufferedOrOOMed() throws Exception {
        // FIX A (round 3) regression reproducer, matching the reviewer's own methodology exactly:
        // a small-on-disk, one-page PDF (a single Flate-compressed (AAAA...) Tj) carrying
        // 6,000,000 glyphs, run through the PRODUCTION entry point
        // TableExtractor.fillCellsByRegion(tables, page, result) in a CHILD JVM capped at
        // -Xmx256m.
        //
        // Before this fix, fillCellsByRegion's --skip-text-urls fallback (stripAllPositions, an
        // anonymous PDFTextStripper subclass) BUFFERED every page glyph into a List before
        // bucketing -- PDFTextStripper's own per-glyph bookkeeping is itself unbounded, so this
        // OOMs even at -Xmx2g (verified directly against the pre-fix implementation: same
        // fixture, same entry point, OutOfMemoryError at TreeMap.subMap inside
        // PDFTextStripper.processTextPosition). After this fix, fillCellsByRegion streams via a
        // glyph-budgeted PDFTextStripperByArea (RegionStripper) -- a glyph outside every
        // registered cell region is never retained -- and MAX_REGION_GLYPHS additionally bounds
        // the per-glyph region-scan CPU, so the child must exit cleanly within a small, fixed
        // heap (256m, comfortably below what buffering 6,000,000 TextPositions would need, and
        // comfortably above what the streaming/capped implementation actually uses).
        Path pdf = tmp.resolve("bomb.pdf");
        int glyphCount = 6_000_000;

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBinary());
        cmd.add("-Xmx256m");
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
                    "fillCellsByRegion on a 6,000,000-glyph page must not OOM/crash at -Xmx256m: " + output);
            assertTrue(output.contains("PROBE_OK"), "probe must report success: " + output);
        } finally {
            proc.destroyForcibly();
        }
    }

    @Test
    void matchedGlyphsInOverlappingCellRegionsStayBoundedNotSlow() throws Exception {
        // round-4 MATCHED-glyph gap: every region-fill test above (
        // regionWorkBudgetCatchesMaxBothShapeThatGlyphCapAloneWouldMiss,
        // regionGlyphBombIsBoundedNotBufferedOrOOMed, fillCellsByRegionGlyphWorkIsBudgeted)
        // deliberately places its cells FAR from the real text ("position irrelevant" /
        // a tiny far-corner cell) so no glyph ever falls inside a registered region --
        // PDFTextStripperByArea.processTextPosition's cheap rect.contains() check runs, but
        // it never forwards the glyph to the base class's real per-glyph bookkeeping
        // (super.processTextPosition). MAX_REGION_WORK's calibration ("~0.2-2s at typical
        // containment-check speeds") assumed that cheap-check cost represents EVERY charged
        // work-unit, matched or not -- false: a MATCHING glyph pays real TreeMap/TreeSet
        // dedup bookkeeping (suppressDuplicateOverlappingText, on by default) once per matching
        // region, and a shape where many registered cell regions geometrically OVERLAP (so one
        // glyph matches many of them at once -- realistic from a hostile ruling layout producing
        // several small "kept" tables whose bounding boxes coincide without their edges literally
        // touching, so groupIntoTables never merges them) multiplies that cost well beyond the
        // work formula's flat glyphs-x-cells count.
        //
        // round-5 (THIS fix): the earlier fix for this made matched glyphs artificially cheap by
        // calling setSuppressDuplicateOverlappingText(false) -- which garbled duplicate-drawn cell
        // text (see duplicateDrawnCellTextExtractsAsSingleCopyNotGarbled) and has been reverted.
        // Suppression is back at its default (ON), so a matched glyph is exactly as expensive as
        // production text extraction actually needs it to be; the DoS is instead closed purely by
        // MAX_REGION_WORK (recalibrated -- see that constant's doc for the full measurement sweep).
        //
        // DETERMINISM: this test asserts ONLY that RulingOverflowException is thrown for an
        // over-budget matched-glyph shape -- never a wall-clock ceiling. work = glyphs x cells is
        // computed BEFORE the call and asserted to exceed the live MAX_REGION_WORK, so the trip is
        // guaranteed by the budget arithmetic itself, not by how fast this particular machine
        // happens to run (a timing assertion here would be exactly the flaky, environment-dependent
        // check this rewrite removes). A generous, LOOSE wall-clock ceiling is kept as a secondary
        // guard against a totally different failure mode (an infinite loop / a budget check that
        // silently stopped firing), not as the primary correctness signal.
        Path pdf = tmp.resolve("matchedoverlap.pdf");
        int glyphCount = 2_000_000;
        TableTestPdfs.manyGlyphsOnePage(pdf, glyphCount);

        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            PDPage page = doc.getPage(0);

            // 100 cells, ALL sharing the identical bbox spanning the whole (huge, single-line)
            // glyph run manyGlyphsOnePage renders at the default (0,0) PDF text position: a
            // glyph that matches ANY one of them matches ALL of them, exercising the expensive
            // MATCHED super.processTextPosition path up to 100x per glyph, unlike every other
            // region test's deliberately unmatched (0x) shape.
            List<TableExtractor.CellRect> comp = new ArrayList<>();
            int cellCount = 100;
            for (int i = 0; i < cellCount; i++) {
                TableExtractor.CellRect c = new TableExtractor.CellRect();
                c.x0 = 0; c.y0 = 770; c.x1 = 20_000_000f; c.y1 = 800;
                comp.add(c);
            }
            List<List<TableExtractor.CellRect>> tables = List.of(comp);
            assertTrue(comp.size() <= TableExtractor.MAX_REGION_CELLS,
                    "sanity: must not trip the (separate) cell-count cap");
            assertTrue((long) glyphCount * cellCount > TableExtractor.MAX_REGION_WORK,
                    "sanity: glyphs x cells must actually exceed the live MAX_REGION_WORK for the "
                            + "throw below to be guaranteed by budget arithmetic, not chance");

            TableExtractor.Result result = new TableExtractor.Result();
            long start = System.nanoTime();
            assertThrows(TableExtractor.RulingOverflowException.class,
                    () -> TableExtractor.fillCellsByRegion(tables, page, result),
                    "work = glyphs x cells exceeds MAX_REGION_WORK by construction -- must trip "
                            + "deterministically, not silently run to completion");
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            // Loose secondary guard only (see DETERMINISM note above) -- generous enough to never
            // flake on a slow/shared CI box, tight enough to catch a totally broken budget check.
            assertTrue(elapsedMs < 15_000,
                    "even as a loose secondary guard, a budget-bounded call must not approach the "
                            + "15s hard-halt window: took " + elapsedMs + "ms");
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
