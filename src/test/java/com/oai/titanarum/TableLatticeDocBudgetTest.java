package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The document-level bound on the LATTICE path ({@link TableExtractor#MAX_LATTICE_DOC_WORK}).
 *
 * <p>WHAT WAS BROKEN. Every lattice budget in {@link TableExtractor} was PER PAGE. Measured (see
 * {@code LatticeDosProbe}) those per-page budgets hold -- the most expensive single lattice page
 * that could be constructed costs ~280ms -- but the AGGREGATE was unbounded: cost is linear in page
 * count (measured 139.4ms/page, flat from 1 to 64 pages), {@code --pages all} admits up to 1,000
 * pages and an explicit {@code --pages 1-z} spec is not capped at all, and {@code --timeout}
 * defaults to 0 (no limit). The stream stage has had {@link TableExtractor#MAX_STREAM_PAGES_PER_DOC}
 * and the tagged stage {@code MAX_STRUCTURE_WORK} since each was wired in; lattice had nothing.
 *
 * <p>These tests pin the four properties that make the bound safe rather than merely present:
 * it FIRES on a long hostile document; it charges REAL work so cheap pages are effectively free;
 * a long LEGITIMATE document still completes untouched; and the loss is never silent
 * ({@link TableExtractor.Result#truncated}, surfaced as {@code tablesTruncated} in report.json).
 */
class TableLatticeDocBudgetTest {

    private static List<TextPosition> glyphs(PDDocument doc, int pageNum) throws IOException {
        List<TextPosition> out = new ArrayList<>();
        PDFTextStripper s = new PDFTextStripper() {
            @Override protected void writeString(String t, List<TextPosition> ps) {
                for (TextPosition p : ps) {
                    String u = p.getUnicode();
                    if (u != null && !u.isEmpty()) out.add(p);
                }
            }
        };
        s.setSortByPosition(true);
        s.setStartPage(pageNum);
        s.setEndPage(pageNum);
        s.getText(doc);
        return out;
    }

    private static Map<Integer, List<TextPosition>> allGlyphs(PDDocument doc, List<Integer> pages)
            throws IOException {
        Map<Integer, List<TextPosition>> g = new LinkedHashMap<>();
        for (int p : pages) g.put(p, glyphs(doc, p));
        return g;
    }

    private static List<Integer> allPages(PDDocument doc) {
        List<Integer> pages = new ArrayList<>();
        for (int i = 1; i <= doc.getNumberOfPages(); i++) pages.add(i);
        return pages;
    }

    /** Pages that carry a table, in ascending page order. */
    private static List<Integer> pagesWithTables(TableExtractor.Result r) {
        List<Integer> out = new ArrayList<>();
        for (TableExtractor.TableHit t : r.tables) if (!out.contains(t.page)) out.add(t.page);
        return out;
    }

    // ------------------------------------------------------------------ the bound FIRES (hostile)

    /**
     * THE ATTACK, at the PRODUCTION budget, in a CHILD JVM. Every page is the same shared content
     * stream (one stream object, 60 page objects -- a small file buying 60 pages of work, which is
     * the amplification this lever closes) carrying a dense ruling grid crossed with a glyph field:
     * the highest-charge single lattice page measured.
     *
     * <p>Checks the bound both cut work AND said so, and -- the property that matters most for a work
     * budget rather than a page-count cap -- that the total charge cannot exceed the budget by more
     * than one page's worth, i.e. the tail really was cut instead of merely flagged. Assertions live
     * in {@link TableLatticeDocBudgetProbe}; see that class for why they run out of process.
     */
    @Test
    void latticeDocWorkBudgetFiresOnALongAdversarialDocument() throws Exception {
        String out = runProbe("hostile", "lattice-doc-dos");
        assertTrue(out.contains("PROBE_OK"), "the document-level lattice bound must fire: " + out);
        assertTrue(out.contains("truncated=true"), "the cut must be reported, never silent: " + out);
    }

    /**
     * The bound cuts the document TAIL, in page order, and everything before the cut is COMPLETE --
     * never a partially-filled table. Driven through the test-only budget override (the convention
     * {@code fillCellsByRegion}/{@code fillCellsFromPositions} already use for their own budgets) so
     * the mechanism is pinned deterministically on a small fixture instead of by wall-clock scale.
     */
    @Test
    void latticeDocWorkBudgetCutsTheTailInPageOrderAndEmitsNoPartialTable() throws Exception {
        Path tmp = Files.createTempFile("lattice-doc-tail", ".pdf");
        try {
            legitRuledPages(tmp, 10);
            try (PDDocument doc = Loader.loadPDF(tmp.toFile())) {
                List<Integer> pageList = allPages(doc);
                Map<Integer, List<TextPosition>> g = allGlyphs(doc, pageList);

                TableExtractor.Result full = TableExtractor.extract(doc, pageList, g, false);
                assertFalse(full.truncated, "10 ordinary ruled pages must not truncate unbounded");
                assertEquals(10, full.tables.size());
                long perPage = full.latticeWorkCharged / 10;
                assertTrue(perPage > 0, "a ruled page must charge real work");

                // A budget that pays for ~4 pages: pages 1-4 run, page 5 finds the budget spent.
                TableExtractor.Result cut = TableExtractor.extract(
                        doc, pageList, g, false, perPage * 4);
                assertTrue(cut.truncated, "the cut must be reported, never silent");
                List<Integer> kept = pagesWithTables(cut);
                assertTrue(kept.size() >= 3 && kept.size() <= 6,
                        "expected roughly the leading 4 pages, got " + kept);
                // page ORDER, not an arbitrary subset: the pages kept are a prefix 1..k
                for (int i = 0; i < kept.size(); i++) {
                    assertEquals(i + 1, (int) kept.get(i),
                            "the budget must cut the document TAIL, keeping the prefix; got " + kept);
                }
                // and every table that WAS emitted is whole -- same shape as the unbounded run
                for (TableExtractor.TableHit t : cut.tables) {
                    assertEquals(12, t.rowCount, "a kept table must be complete, not partial");
                    assertEquals(4, t.colCount, "a kept table must be complete, not partial");
                    assertNotNull(t.rows);
                    assertEquals(12, t.rows.size());
                }
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // -------------------------------------------------------- the bound does NOT fire (legitimate)

    /**
     * A long LEGITIMATE document completes untouched at the production budget, in a CHILD JVM
     * (see {@link TableLatticeDocBudgetProbe} for why out of process). 300 pages, each carrying a
     * dense 25x6 ruled table with text in every cell.
     *
     * <p>This is the check a page-COUNT cap (the shape {@link TableExtractor#MAX_STREAM_PAGES_PER_DOC}
     * uses for the stream stage) would fail: 300 pages is 4.7x that cap and 20x the longest document
     * in either real sample, yet the whole document charges a few percent of the budget -- because
     * the bound charges REAL work, and real ruled pages are cheap (measured over 277 real PDFs:
     * p50 2,889 units per page, worst 2,151,463).
     */
    @Test
    void longLegitimateRuledDocumentCompletesUnderTheProductionBudget() throws Exception {
        String out = runProbe("legit", "lattice-doc-legit");
        assertTrue(out.contains("PROBE_OK"),
                "a 300-page legitimate ruled document must complete untouched: " + out);
        assertTrue(out.contains("truncated=false") && out.contains("tables=300"),
                "every page's table must survive, unflagged: " + out);
    }

    /**
     * CHARGE REAL WORK, the invariant that separates this from a page cap: a page the lattice path
     * does no work on costs nothing. 400 blank pages must charge ZERO, so a long document of
     * unruled (prose, image-only, empty) pages can never exhaust the budget for the ruled pages
     * that follow it -- mirroring {@code extractStreamPage}'s "a document of blank pages must not
     * spend a real document's budget" rule.
     */
    @Test
    void pagesWithNoLatticeWorkAreNotChargedAgainstTheDocumentBudget() throws Exception {
        Path tmp = Files.createTempFile("lattice-doc-blank", ".pdf");
        try {
            int blanks = 400;
            blankPagesThenOneTable(tmp, blanks);
            try (PDDocument doc = Loader.loadPDF(tmp.toFile())) {
                List<Integer> pageList = allPages(doc);
                Map<Integer, List<TextPosition>> g = allGlyphs(doc, pageList);

                // A budget that pays for ONE ruled page only. If blank pages were charged (per
                // page, as a page cap would), the table on the last page would be lost.
                TableExtractor.Result probe = TableExtractor.extract(doc, pageList, g, false);
                long oneRuledPage = probe.latticeWorkCharged;
                assertTrue(oneRuledPage > 0, "the single ruled page must charge something");

                TableExtractor.Result r = TableExtractor.extract(
                        doc, pageList, g, false, oneRuledPage);
                assertEquals(1, r.tables.size(),
                        blanks + " blank pages must not consume the budget the ruled page needs");
                assertEquals(blanks + 1, r.tables.get(0).page);
                assertFalse(r.truncated, "nothing was dropped, so nothing should be flagged");
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /**
     * The lattice budget must not leak into the STREAM stage. A document whose lattice budget is
     * fully spent still runs the borderless path on every page (up to that stage's OWN
     * {@link TableExtractor#MAX_STREAM_PAGES_PER_DOC}) -- the two stages are independently bounded,
     * which is what lets the corpus macro for the stream path be unaffected by this change.
     */
    @Test
    void exhaustingTheLatticeBudgetDoesNotStopTheStreamStage() throws Exception {
        Path tmp = Files.createTempFile("lattice-doc-stream", ".pdf");
        try {
            borderlessPlusRuledPages(tmp, 6);
            try (PDDocument doc = Loader.loadPDF(tmp.toFile())) {
                List<Integer> pageList = allPages(doc);
                Map<Integer, List<TextPosition>> g = allGlyphs(doc, pageList);

                // budget 1 unit: the first page charges past it, so pages 2..6 get no lattice at all
                TableExtractor.Result r = TableExtractor.extract(doc, pageList, g, true, 1L);
                assertTrue(r.truncated);
                long streamHits = r.tables.stream()
                        .filter(t -> "stream".equals(t.extractionMethod)).count();
                assertTrue(streamHits > 0,
                        "the stream stage must still run when the lattice budget is spent");
                long latticeOnLatePages = r.tables.stream()
                        .filter(t -> "lattice".equals(t.extractionMethod) && t.page > 1).count();
                assertEquals(0, latticeOnLatePages,
                        "lattice must be the only stage the lattice budget stops");
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    // -------------------------------------------------------------------------- child-JVM driver

    /**
     * Runs {@link TableLatticeDocBudgetProbe} in a freshly-launched JVM and returns its combined
     * output. Mirrors {@code TableLatticeTest#regionGlyphBombIsBoundedNotBufferedOrOOMed}'s
     * child-JVM pattern, including its Surefire-booter-jar classpath handling.
     */
    private String runProbe(String mode, String prefix) throws Exception {
        Path pdf = Files.createTempFile(prefix, ".pdf");
        Path log = Files.createTempFile(prefix, ".log");
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(currentTestClasspath());
        cmd.add("com.oai.titanarum.TableLatticeDocBudgetProbe");
        cmd.add(mode);
        cmd.add(pdf.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        Process proc = pb.start();
        try {
            boolean exited = proc.waitFor(180, java.util.concurrent.TimeUnit.SECONDS);
            String out = exited ? Files.readString(log) : "(child did not exit within 180s)";
            assertTrue(exited, "the " + mode + " probe child JVM must complete, not hang: " + out);
            assertEquals(0, proc.exitValue(), "the " + mode + " probe must pass: " + out);
            System.out.print("  [" + mode + "] " + lastResultLine(out));
            return out;
        } finally {
            proc.destroyForcibly();
            Files.deleteIfExists(pdf);
            Files.deleteIfExists(log);
        }
    }

    private static String lastResultLine(String out) {
        for (String line : out.split("\\R")) {
            if (line.startsWith("hostile ") || line.startsWith("legit ")) return line + System.lineSeparator();
        }
        return out;
    }

    /** Surefire sometimes forks with a manifest-only booter jar whose {@code java.class.path} is
     *  just that jar; the real classpath lives in its {@code Class-Path} manifest attribute. Handle
     *  both (identical helper to {@code TableLatticeTest}'s and {@code HardWatchdogTest}'s). */
    private static String currentTestClasspath() throws IOException {
        String cp = System.getProperty("java.class.path");
        String[] entries = cp.split(java.io.File.pathSeparator);
        if (entries.length == 1 && entries[0].toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            try (java.util.jar.JarFile jf = new java.util.jar.JarFile(entries[0])) {
                String attr = jf.getManifest() == null ? null
                        : jf.getManifest().getMainAttributes()
                              .getValue(java.util.jar.Attributes.Name.CLASS_PATH);
                if (attr != null && !attr.isBlank()) {
                    StringBuilder sb = new StringBuilder(entries[0]);
                    for (String part : attr.split(" ")) {
                        if (part.isBlank()) continue;
                        sb.append(java.io.File.pathSeparator)
                          .append(new java.io.File(java.net.URI.create(part)).getAbsolutePath());
                    }
                    return sb.toString();
                }
            }
        }
        return cp;
    }

    // ---------------------------------------------------------------------------------- fixtures

    /** {@code pages} pages each with one ordinary ruled table (see {@link #drawRuledTable}), text
     *  in every cell: an ordinary long report. */
    private static void legitRuledPages(Path file, int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int pg = 0; pg < pages; pg++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    drawRuledTable(cs);
                }
            }
            doc.save(file.toFile());
        }
    }

    /**
     * ONE ordinary ruled table: 12 data rows x 4 columns, text in every cell.
     *
     * <p>Kept deliberately MODEST in cell count. These tests run hundreds of pages through the real
     * per-page pipeline, and a denser grid pushes {@code placeGridBudgeted}'s loops over a JIT
     * compilation threshold, which grows the inlined stack frame of {@code splitComponent}'s
     * recursion and makes the sibling 128KB-stack test
     * ({@code TableGeometryTest#splitComponentDepthCapPreventsUnboundedRecursionAndStackOverflow})
     * fail on a warm JVM. That fragility is pre-existing and real (see this change's report) but it
     * is NOT this bound's subject, and no assertion here depends on grid density -- only on page
     * count, on every page's table surviving, and on the charge staying far under budget. The dense
     * 25x6 shape is still measured, in {@code LatticeDosProbe} (gated, not in the default suite).
     */
    private static void drawRuledTable(PDPageContentStream cs) throws Exception {
        cs.setLineWidth(0.6f);
        for (int r = 0; r <= 12; r++) {
            float y = 120 + r * 40f;
            cs.moveTo(40, y); cs.lineTo(560, y); cs.stroke();
        }
        for (int c = 0; c <= 4; c++) {
            float x = 40 + c * 130f;
            cs.moveTo(x, 120); cs.lineTo(x, 120 + 12 * 40f); cs.stroke();
        }
        cs.setFont(TableTestPdfs.HELV, 9);
        for (int r = 0; r < 12; r++) {
            for (int c = 0; c < 4; c++) {
                cs.beginText();
                cs.newLineAtOffset(48 + c * 130f, 132 + r * 40f);
                cs.showText("R" + r + "C" + c);
                cs.endText();
            }
        }
    }

    /** {@code blanks} genuinely empty pages, then one ordinary ruled table page. */
    private static void blankPagesThenOneTable(Path file, int blanks) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < blanks; i++) doc.addPage(new PDPage(PDRectangle.LETTER));
            PDPage last = new PDPage(PDRectangle.LETTER);
            doc.addPage(last);
            try (PDPageContentStream cs = new PDPageContentStream(doc, last)) {
                drawRuledTable(cs);
            }
            doc.save(file.toFile());
        }
    }

    /** Every page carries BOTH a ruled table and a borderless (whitespace-aligned) one, so the
     *  stream stage has something to find on pages the lattice budget refuses. */
    private static void borderlessPlusRuledPages(Path file, int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int pg = 0; pg < pages; pg++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    // ruled table, top half
                    cs.setLineWidth(0.6f);
                    for (int r = 0; r <= 6; r++) {
                        float y = 500 + r * 20f;
                        cs.moveTo(40, y); cs.lineTo(560, y); cs.stroke();
                    }
                    for (int c = 0; c <= 4; c++) {
                        float x = 40 + c * 130f;
                        cs.moveTo(x, 500); cs.lineTo(x, 620); cs.stroke();
                    }
                    cs.setFont(TableTestPdfs.HELV, 8);
                    for (int r = 0; r < 6; r++) {
                        for (int c = 0; c < 4; c++) {
                            cs.beginText();
                            cs.newLineAtOffset(46 + c * 130f, 506 + r * 20f);
                            cs.showText("v" + r + c);
                            cs.endText();
                        }
                    }
                    // borderless numeric table, bottom half: 12 rows x 4 aligned columns
                    cs.setFont(TableTestPdfs.HELV, 9);
                    String[] head = {"Region", "Units", "Price", "Total"};
                    for (int c = 0; c < 4; c++) {
                        cs.beginText();
                        cs.newLineAtOffset(60 + c * 120f, 440);
                        cs.showText(head[c]);
                        cs.endText();
                    }
                    for (int r = 0; r < 12; r++) {
                        String[] row = {"Row" + r, String.valueOf(100 + r * 7),
                                String.valueOf(12 + r) + ".50", String.valueOf(1000 + r * 37)};
                        for (int c = 0; c < 4; c++) {
                            cs.beginText();
                            cs.newLineAtOffset(60 + c * 120f, 415 - r * 22f);
                            cs.showText(row[c]);
                            cs.endText();
                        }
                    }
                }
            }
            doc.save(file.toFile());
        }
    }
}
