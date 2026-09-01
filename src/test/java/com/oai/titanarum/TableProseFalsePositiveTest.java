package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PROSE FALSE POSITIVES on the tagged + lattice paths (lever 4).
 *
 * <p>MEASUREMENT that motivates this class. Over the project's 200-PDF real-world prose sample
 * (page 1 only), the tagged+lattice configuration emitted at least one table on 21 of 200
 * documents (0.1050) and the full arbitrated pipeline on 25 of 200 (0.1250) -- documents that are
 * ordinary mail attachments, not tabular reports. Classifying all 21 by mechanism
 * (see {@code LatticeFpProbe}) found:
 *
 * <ul>
 *   <li><b>15 of 21 are the TAGGED path</b>, not the lattice path -- HTML-email layout
 *       {@code <table>}s that survive conversion into the PDF structure tree. 12 of those 15
 *       documents emit ONLY tables of structural rank below 2x2 (1x1, Nx1 or 1xN): a single cell
 *       holding a paragraph, a "View in OneDrive" button, a stack of prose blocks. The lattice
 *       path has demanded a 2x2 minimum since it was written ({@code buildTable} returns null
 *       below 2 rows or 2 columns); the tagged path never did. This test class closes that
 *       inconsistency.</li>
 *   <li><b>4 of 21 are genuine lattice fabrications</b>, all one shape: a drawn border box, or a
 *       banner and a disclaimer separated by a rule, resolving into a grid whose text does not
 *       support the drawn column structure -- 0, 1, 2 and 3 textful cells respectively. Three of the
 *       four have all their text anchored in ONE grid column and are removed by
 *       {@code TableExtractor.MIN_LATTICE_TEXTFUL_COLUMNS}; the fourth (a boxed prose paragraph
 *       whose text happens to land in two columns) still survives -- see the report accompanying
 *       this change for why the rules that would remove it were measured and rejected.</li>
 *   <li>The remaining 2 documents (a real vendor invoice with 5 drawn tables, and a drawn
 *       attachment-name/size list) genuinely CONTAIN tables. They are counted in the 0.1050 figure
 *       but are not defects, and nothing here tries to remove them.</li>
 * </ul>
 *
 * <p>So the headline figure is dominated by the TAGGED path even though the defect has been
 * described as a lattice one; both fixes are asserted here. MEASURED RESULT: full-pipeline prose
 * false-positive rate 0.1250 -> 0.0600, lattice+tagged alone 0.1050 -> 0.0350, with the reference
 * corpus's document-pooled adjacency macro F1 unchanged to the last decimal the harness prints for
 * every configuration, and with ZERO per-document change on all 77 scoring PDFs.
 */
class TableProseFalsePositiveTest {

    @TempDir
    Path tmp;

    private static Map<Integer, List<TextPosition>> positions(PDDocument doc) throws Exception {
        Map<Integer, List<TextPosition>> out = new HashMap<>();
        for (int i = 0; i < doc.getNumberOfPages(); i++) {
            out.put(i + 1, TableTestPdfs.harvestGlyphs(doc, i));
        }
        return out;
    }

    private static TableExtractor.Result run(Path pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<Integer> pages = List.of(1);
            return TableExtractor.extract(doc, pages, positions(doc));
        }
    }

    // ------------------------------------------------------------------ tagged: rank-1 layout

    @Test
    void taggedSingleCellLayoutTableIsNotEmitted() throws Exception {
        Path pdf = tmp.resolve("tagged_1x1_layout.pdf");
        TableTestPdfs.taggedSingleCellLayoutTable(pdf);
        TableExtractor.Result r = run(pdf);
        assertTrue(r.tables.isEmpty(),
                "a 1x1 tagged layout table is a prose false positive -- it declares no row and no "
                        + "column relation, and the lattice path has always rejected the same "
                        + "shape; got: " + describe(r));
    }

    @Test
    void taggedSingleColumnLayoutTableIsNotEmitted() throws Exception {
        Path pdf = tmp.resolve("tagged_3x1_layout.pdf");
        TableTestPdfs.taggedSingleColumnLayoutTable(pdf);
        TableExtractor.Result r = run(pdf);
        assertTrue(r.tables.isEmpty(),
                "a 3x1 tagged layout table (stacked prose blocks) is a prose false positive; got: "
                        + describe(r));
    }

    @Test
    void taggedSingleRowLayoutTableIsNotEmitted() throws Exception {
        Path pdf = tmp.resolve("tagged_1x3_layout.pdf");
        TableTestPdfs.taggedLayoutTable(pdf, 1, 3);
        TableExtractor.Result r = run(pdf);
        assertTrue(r.tables.isEmpty(),
                "a 1x3 tagged layout table (one banner row) is a prose false positive; got: "
                        + describe(r));
    }

    /** The precision fix must not cost the genuine tagged table: 2x2 stays. */
    @Test
    void genuineTagged2x2IsStillEmitted() throws Exception {
        Path pdf = tmp.resolve("tagged_2x2.pdf");
        TableTestPdfs.tagged2x2(pdf);
        TableExtractor.Result r = run(pdf);
        assertEquals(1, r.tables.size(), "the genuine 2x2 tagged table must survive: " + describe(r));
        assertEquals("tagged", r.tables.get(0).extractionMethod);
        assertEquals(2, r.tables.get(0).rowCount);
        assertEquals(2, r.tables.get(0).colCount);
    }

    /** A 2x3 tagged table -- the smallest shape above the threshold in both dimensions. */
    @Test
    void genuineTagged2x3IsStillEmitted() throws Exception {
        Path pdf = tmp.resolve("tagged_2x3.pdf");
        TableTestPdfs.taggedLayoutTable(pdf, 2, 3);
        TableExtractor.Result r = run(pdf);
        assertEquals(1, r.tables.size(), "a 2x3 tagged table must survive: " + describe(r));
        assertEquals(2, r.tables.get(0).rowCount);
        assertEquals(3, r.tables.get(0).colCount);
    }

    // ------------------------------------------------------------------ lattice: boxed paragraph

    @Test
    void latticeBoxedParagraphWithOneTextfulCellIsNotEmitted() throws Exception {
        Path pdf = tmp.resolve("boxed_1.pdf");
        TableTestPdfs.latticeBoxedParagraph(pdf, 1);
        TableExtractor.Result r = run(pdf);
        assertTrue(r.tables.isEmpty(),
                "a drawn border box whose grid holds ONE textful cell is a boxed paragraph, not a "
                        + "table; got: " + describe(r));
    }

    @Test
    void latticeFullWidthBannerAndDisclaimerRulesAreNotEmitted() throws Exception {
        Path pdf = tmp.resolve("banner_disclaimer.pdf");
        TableTestPdfs.latticeFullWidthBannerAndDisclaimer(pdf);
        TableExtractor.Result r = run(pdf);
        assertTrue(r.tables.isEmpty(),
                "two full-width spanning prose blocks separated by a rule have all their text in "
                        + "ONE grid column -- no horizontal relation exists, so this is not a "
                        + "table; got: " + describe(r));
    }

    /** The lattice fix must not cost the genuine small ruled table: text in both columns. */
    @Test
    void latticeGridWithTextInBothColumnsIsStillEmitted() throws Exception {
        Path pdf = tmp.resolve("boxed_4.pdf");
        TableTestPdfs.latticeBoxedParagraph(pdf, 4);
        TableExtractor.Result r = run(pdf);
        assertEquals(1, r.tables.size(),
                "a 2x2 drawn grid with all four cells textful is a table and must survive: "
                        + describe(r));
        assertEquals("lattice", r.tables.get(0).extractionMethod);
    }

    /**
     * PINS THE MEASURED ASYMMETRY of {@code TableExtractor.MIN_LATTICE_TEXTFUL_COLUMNS}. The
     * mirror-image rule on ROWS -- "the text must occupy two distinct grid rows" -- was implemented
     * and measured, and rejected: it costs real matched adjacency relations on the reference corpus
     * (lattice+tagged document-pooled macro 0.4718 -> 0.4716; 2 matched relations lost on
     * icdar-eu/eu-024, 2 on eu-025), because a real ruled table whose later rows failed text
     * assignment still contributes correct relations from the row that did resolve. So a grid whose
     * text occupies ONE ROW but several COLUMNS must still be emitted. If someone later adds the row
     * direction to the rule, this test is what fails.
     */
    @Test
    void latticeGridWithTextInOnlyOneRowButSeveralColumnsIsStillEmitted() throws Exception {
        Path pdf = tmp.resolve("header_row_only.pdf");
        TableTestPdfs.latticeHeaderRowOnly(pdf);
        TableExtractor.Result r = run(pdf);
        assertEquals(1, r.tables.size(),
                "a ruled grid whose only textful row is its header still carries real horizontal "
                        + "relations and must NOT be dropped -- the column-direction rule is "
                        + "deliberately not mirrored on rows: " + describe(r));
        assertEquals("lattice", r.tables.get(0).extractionMethod);
    }

    /**
     * PINS THE TEXTLESS CARVE-OUT of {@code TableExtractor.MIN_LATTICE_TEXTFUL_COLUMNS}: a drawn
     * grid with NO text at all is NOT rejected. Extending the rule to cover it was measured and
     * rejected -- it removes no prose false positive on the 200-PDF sample (no sampled document has
     * a textless-only lattice hit) and it costs full+arbitration document-pooled macro
     * 0.8079 -> 0.8077, because a textless ruled grid currently suppresses an overlapping stream
     * candidate under both merge rules. An all-empty grid also fabricates no CONTENT: every cell of
     * it renders as the empty string, so it contributes zero adjacency relations.
     */
    @Test
    void latticeGridWithNoTextAtAllIsStillEmitted() throws Exception {
        Path pdf = tmp.resolve("empty_grid.pdf");
        TableTestPdfs.latticeEmptyGrid(pdf);
        TableExtractor.Result r = run(pdf);
        assertEquals(1, r.tables.size(),
                "a textless drawn grid must NOT be dropped by the column rule -- measured to cost "
                        + "corpus macro F1 for zero prose-FP benefit: " + describe(r));
        assertEquals("lattice", r.tables.get(0).extractionMethod);
    }

    /**
     * The REJECTED alternative rule ("at least 4 textful cells") would have removed this shape.
     * A 2x2 grid whose header spans both columns has only THREE textful cells but its text does
     * occupy two columns, so it is a genuine table and must survive. This is the measurement that
     * decided the rule; see {@code TableExtractor.MIN_LATTICE_TEXTFUL_COLUMNS}.
     */
    @Test
    void latticeSpanningHeaderWithThreeTextfulCellsIsStillEmitted() throws Exception {
        Path pdf = tmp.resolve("merged_header.pdf");
        TableTestPdfs.mergedHeader(pdf);
        TableExtractor.Result r = run(pdf);
        assertEquals(1, r.tables.size(),
                "a 2x2 lattice table with a column-spanning header (3 textful cells) is a real "
                        + "table and must survive the prose-FP rule: " + describe(r));
        assertEquals(2, r.tables.get(0).rowCount);
        assertEquals(2, r.tables.get(0).colCount);
    }

    /** The canonical 3x3 ruled fixture used across the lattice tests must be unaffected. */
    @Test
    void canonicalRuled3x3IsStillEmitted() throws Exception {
        Path pdf = tmp.resolve("ruled3x3.pdf");
        TableTestPdfs.ruled3x3(pdf);
        TableExtractor.Result r = run(pdf);
        assertEquals(1, r.tables.size(), "ruled 3x3 must survive: " + describe(r));
        assertEquals(3, r.tables.get(0).rowCount);
        assertEquals(3, r.tables.get(0).colCount);
    }

    // ------------------------------------------------------------------ DoS: the new scan's cost

    /**
     * The column rule adds one work item per lattice table: an early-exit non-whitespace scan of
     * each cell's text plus a distinct-anchor-column set. This pins its WORST CASE -- a large ruled
     * grid whose every cell holds only SPACE glyphs, the one input for which neither the per-string
     * early exit nor the two-distinct-columns short circuit ever fires, so every character of every
     * cell is scanned and all cells are visited.
     *
     * <p>The budget below is justified by measurement, not guessed. First, the SIZE: the scan can
     * only ever see components that already survived every pre-existing geometry budget, and those
     * bind long before MAX_CELLS_PER_TABLE does on this fixture family -- measured, a 50x50 ruled
     * grid of whitespace cells already aborts the whole page on the ruling/intersection cap
     * ({@code Result.truncated=true}, no output), and 45x45 (1,936 cells, 7,744 glyphs) is the
     * largest that completes. So 45x45 IS the adversarial envelope here, not an arbitrary size.
     * Second, the COST: the scan's work is dominated by work the same call already does
     * unconditionally -- {@code fillCellsFromPositions} already touched every (cell, glyph) pair
     * under MAX_REGION_WORK, and {@code renderViews} already materialised a rowCount x colCount
     * string grid plus a full-grid markdown table for this same component. Measured on this machine:
     * whitespace-only and textful runs both land in the ~100ms range for the whole extract() call,
     * within noise of each other. The assertion allows 15s -- the same generous order the sibling
     * hostile-input tests in TableTaggedTest use -- so it fails on an algorithmic blow-up rather
     * than on CI jitter.
     */
    @Test
    void whitespaceOnlyCellsDoNotMakeTheColumnScanExpensive() throws Exception {
        Path blank = tmp.resolve("ws_grid.pdf");
        Path textful = tmp.resolve("textful_grid.pdf");
        TableTestPdfs.ruledGridWithWhitespaceOnlyCells(blank, 45, 8, false);
        TableTestPdfs.ruledGridWithWhitespaceOnlyCells(textful, 45, 8, true);

        long t0 = System.nanoTime();
        TableExtractor.Result blankResult = run(blank);
        long blankMs = (System.nanoTime() - t0) / 1_000_000;

        t0 = System.nanoTime();
        TableExtractor.Result textfulResult = run(textful);
        long textfulMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(blankMs < 15_000,
                "the whitespace-only worst case for the column scan must not blow up: took "
                        + blankMs + "ms (textful contrast: " + textfulMs + "ms)");
        // The rule's own decision on each: all-whitespace -> no textful cell -> textless carve-out
        // keeps it; real glyphs in every cell -> many textful columns -> kept. Both emitted, so the
        // timing above really did run the full scan rather than bailing out early somewhere.
        assertFalse(blankResult.tables.isEmpty(),
                "sanity: the whitespace-only grid is textless, so the carve-out keeps it: "
                        + describe(blankResult));
        assertFalse(textfulResult.tables.isEmpty(),
                "sanity: the textful grid must be kept: " + describe(textfulResult));
        System.out.printf(java.util.Locale.ROOT,
                "[prose-fp DoS] 45x45 ruled grid (1,936 cells): whitespace-only %dms, textful %dms%n",
                blankMs, textfulMs);
    }

    private static String describe(TableExtractor.Result r) {
        StringBuilder sb = new StringBuilder("tables=" + r.tables.size());
        for (TableExtractor.TableHit t : r.tables) {
            int nonEmpty = 0;
            for (TableExtractor.CellHit c : t.cells) {
                if (c.text != null && !c.text.trim().isEmpty()) nonEmpty++;
            }
            sb.append(" [").append(t.extractionMethod).append(' ').append(t.rowCount).append('x')
              .append(t.colCount).append(" cells=").append(t.cells.size())
              .append(" textful=").append(nonEmpty).append(']');
        }
        return sb.toString();
    }
}
