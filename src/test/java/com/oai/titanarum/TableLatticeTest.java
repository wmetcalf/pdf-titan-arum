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
