package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WIRING the whitespace ("stream") path into {@link TableExtractor#extract}.
 *
 * <p>Until this class existed, {@code StreamTableExtractor.extractPage} and
 * {@link TableExtractor#arbitrate} were production code that NOTHING in production ever called --
 * only the bake-off harness did. Every measured borderless-table number was therefore a
 * measurement of functions that could not reach {@code report.json}. These tests pin the wiring
 * itself:
 *
 * <ol>
 *   <li>the stream stage is OPT-IN (default off), so the shipping default is unchanged;</li>
 *   <li>with it on, the combined candidate list is resolved by {@code arbitrate}, and the result is
 *       BIT-EQUAL to running the two paths separately and arbitrating them -- i.e. the harness's
 *       simulation and the production pipeline are the same computation, not two similar ones;</li>
 *   <li>{@code extract} still never throws, aborts still yield no partial output, and the per-page
 *       table cap is COMPOSED across paths rather than multiplied by adding one.</li>
 * </ol>
 */
class TableStreamWiringTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------------------------ helpers

    private static Map<Integer, List<TextPosition>> glyphsOf(PDDocument doc) throws Exception {
        Map<Integer, List<TextPosition>> out = new LinkedHashMap<>();
        for (int p = 1; p <= doc.getNumberOfPages(); p++) {
            out.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
        }
        return out;
    }

    private static List<Integer> pagesOf(PDDocument doc) {
        List<Integer> out = new ArrayList<>();
        for (int p = 1; p <= doc.getNumberOfPages(); p++) out.add(p);
        return out;
    }

    /**
     * Value signature of a hit. Deliberately covers every field a consumer of report.json can see
     * (including {@code confidence} and the rendered {@code rows}), so an equality assertion built
     * on it cannot pass while the two computations disagree about content.
     */
    private static String sig(TableExtractor.TableHit t) {
        StringBuilder sb = new StringBuilder();
        sb.append(t.page).append('|').append(t.extractionMethod).append('|')
          .append(t.rowCount).append('x').append(t.colCount).append('|')
          .append(t.bbox == null ? "nobbox" : java.util.Arrays.toString(t.bbox)).append('|')
          .append(t.confidence).append('|').append(t.likelyDuplicateOfTagged).append('|')
          .append(t.rows).append('|').append(t.cells == null ? -1 : t.cells.size());
        return sb.toString();
    }

    private static List<String> sigs(List<TableExtractor.TableHit> hits) {
        List<String> out = new ArrayList<>();
        for (TableExtractor.TableHit t : hits) out.add(sig(t));
        return out;
    }

    // ------------------------------------------------------------------- 1. the flag is opt-in

    @Test
    void borderlessTableIsNotExtractedWhenStreamIsOff() throws Exception {
        Path pdf = tmp.resolve("borderless.pdf");
        TableTestPdfs.borderless3Col(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, pagesOf(doc), glyphsOf(doc));
            assertEquals(0, r.tables.size(),
                    "the stream path must be OPT-IN: the 3-arg extract must behave exactly as before");
            assertFalse(r.truncated);
        }
    }

    @Test
    void borderlessTableIsExtractedWhenStreamIsOn() throws Exception {
        Path pdf = tmp.resolve("borderless.pdf");
        TableTestPdfs.borderless3Col(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r =
                    TableExtractor.extract(doc, pagesOf(doc), glyphsOf(doc), true);
            assertEquals(1, r.tables.size(), "the borderless table must reach the Result");
            TableExtractor.TableHit t = r.tables.get(0);
            assertEquals("stream", t.extractionMethod);
            assertEquals(3, t.colCount);
            assertEquals(5, t.rowCount);
            assertEquals("Region", t.rows.get(0).get(0));
            assertNotNull(t.confidence, "confidence must be carried through the wiring, not dropped");
            assertTrue(t.confidence > 0.0 && t.confidence <= 1.0);
        }
    }

    // ------------------------------------------------ 2. production == the harness's simulation

    /**
     * THE point of this whole change. {@code extract(..., streamTables=true)} must compute exactly
     * {@code arbitrate(taggedLatticeCandidates, streamCandidates)} -- the same expression the
     * bake-off harness scores. If this fails, the published corpus number does not describe the
     * shipping pipeline.
     */
    @Test
    void wiredResultEqualsArbitrateOfTheTwoPathsRunSeparately() throws Exception {
        Path both = tmp.resolve("both.pdf");
        TableTestPdfs.ruledPlusBorderlessSamePage(both);
        Path borderless = tmp.resolve("bl.pdf");
        TableTestPdfs.borderless3Col(borderless);
        Path ruledOnly = tmp.resolve("ruled.pdf");
        TableTestPdfs.ruled3x3(ruledOnly);
        Path multi = tmp.resolve("multi.pdf");
        TableTestPdfs.multiPageRuled3x3(multi, 3);
        Path prose = tmp.resolve("prose.pdf");
        TableTestPdfs.noTables(prose);

        for (Path pdf : List.of(both, borderless, ruledOnly, multi, prose)) {
            try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                List<Integer> pages = pagesOf(doc);
                Map<Integer, List<TextPosition>> glyphs = glyphsOf(doc);

                // The harness's exact expression, built from the two paths run separately.
                List<TableExtractor.TableHit> ruled =
                        new ArrayList<>(TableExtractor.extract(doc, pages, glyphs).tables);
                List<TableExtractor.TableHit> stream = new ArrayList<>();
                for (int p : pages) {
                    stream.addAll(StreamTableExtractor.extractPage(
                            p, glyphs.get(p), new BreuelGutterFinder()));
                }
                List<TableExtractor.TableHit> expected = TableExtractor.arbitrate(ruled, stream);
                expected.sort(java.util.Comparator
                        .<TableExtractor.TableHit>comparingInt(t -> t.page)
                        .thenComparingDouble(t -> t.bbox == null ? 0 : t.bbox[1]));

                List<TableExtractor.TableHit> actual =
                        TableExtractor.extract(doc, pages, glyphs, true).tables;

                assertEquals(sigs(expected), sigs(actual),
                        pdf.getFileName() + ": the wired pipeline must compute the SAME merge the "
                        + "harness scores, candidate for candidate");
            }
        }
    }

    @Test
    void ruledAndBorderlessOnOnePageBothSurviveArbitration() throws Exception {
        Path pdf = tmp.resolve("both.pdf");
        TableTestPdfs.ruledPlusBorderlessSamePage(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r =
                    TableExtractor.extract(doc, pagesOf(doc), glyphsOf(doc), true);
            long lattice = r.tables.stream().filter(t -> "lattice".equals(t.extractionMethod)).count();
            long stream = r.tables.stream().filter(t -> "stream".equals(t.extractionMethod)).count();
            assertEquals(1, lattice, "the drawn grid must survive: it is not contested");
            assertEquals(1, stream, "the borderless table must survive: it is not contested");
            // Canonical ordering (page, then top edge) must still hold across the merged list.
            for (int i = 1; i < r.tables.size(); i++) {
                float prev = r.tables.get(i - 1).bbox[1];
                float cur = r.tables.get(i).bbox[1];
                assertTrue(prev <= cur, "merged tables must stay sorted by page then top edge");
            }
        }
    }

    /**
     * {@code arbitrate}'s rule 1 -- the structure tree is authoritative and a tagged candidate is
     * never arbitrated away -- must survive the wiring, not just the unit test that drives
     * {@code arbitrate} with synthetic hits. Turning the flag on must never cost a document its
     * tagged tables.
     *
     * <p>The fixture is chosen so the assertion is NOT vacuous: this page really does produce two
     * raw stream candidates (asserted below), both of which are contested and must lose. A fixture
     * where the stream path finds nothing would pass this test for the wrong reason.
     */
    @Test
    void aTaggedTableIsNeverLostWhenTheStreamStageIsEnabled() throws Exception {
        Path pdf = tmp.resolve("tagged.pdf");
        TableTestPdfs.taggedHollowMiddleTwoDenseBlocksPlusDistinctRuledTableInGap(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<Integer> pages = pagesOf(doc);
            Map<Integer, List<TextPosition>> glyphs = glyphsOf(doc);

            List<TableExtractor.TableHit> rawStream = new ArrayList<>();
            for (int p : pages) {
                rawStream.addAll(StreamTableExtractor.extractPage(
                        p, glyphs.get(p), new BreuelGutterFinder()));
            }
            assertFalse(rawStream.isEmpty(),
                    "fixture must actually give the stream path something to contest with");

            List<TableExtractor.TableHit> off = TableExtractor.extract(doc, pages, glyphs).tables;
            List<String> taggedOff = new ArrayList<>();
            for (TableExtractor.TableHit t : off) {
                if ("tagged".equals(t.extractionMethod)) taggedOff.add(sig(t));
            }
            assertFalse(taggedOff.isEmpty(), "fixture must actually produce a tagged table");

            List<TableExtractor.TableHit> on = TableExtractor.extract(doc, pages, glyphs, true).tables;
            List<String> taggedOn = new ArrayList<>();
            for (TableExtractor.TableHit t : on) {
                if ("tagged".equals(t.extractionMethod)) taggedOn.add(sig(t));
            }
            assertEquals(taggedOff, taggedOn,
                    "every tagged table must survive the stream stage, byte for byte");
            assertEquals(0, on.stream().filter(t -> "stream".equals(t.extractionMethod)).count(),
                    "both stream candidates contest the tagged/ruled answer and must lose it");
        }
    }

    /**
     * THE ARBITRATION DROP MUST REACH THE Result, THROUGH THE REAL WIRING.
     *
     * <p>{@code arbitrate} used to take no {@link TableExtractor.Result} parameter at all, so a
     * candidate that lost a contested region set nothing: {@code tablesTruncated} stayed absent from
     * report.json and no per-hit marker existed either, which made a table arbitrated away
     * byte-identical to a region that never had a table. Every SIBLING drop path in the class
     * (extractTagged's three catches, the per-page lattice catches, the document lattice budget,
     * extractStreamPage's caps, capTablesPerPage) surfaces itself; this pins that arbitration now
     * does too, and does so through {@code extract()} rather than only in the unit test that drives
     * {@code arbitrate} directly.
     *
     * <p>Same fixture as the test above, chosen because its contest is real and its OUTCOME is
     * already asserted there: two genuine stream candidates lose to the tagged answer.
     */
    @Test
    void aContestLostInsideExtractIsSurfacedOnTheResult() throws Exception {
        Path pdf = tmp.resolve("tagged.pdf");
        TableTestPdfs.taggedHollowMiddleTwoDenseBlocksPlusDistinctRuledTableInGap(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<Integer> pages = pagesOf(doc);
            Map<Integer, List<TextPosition>> glyphs = glyphsOf(doc);

            TableExtractor.Result off = TableExtractor.extract(doc, pages, glyphs);
            assertFalse(off.truncated, "sanity: with the stream stage OFF there is no contest at all");
            assertEquals(0, off.arbitrationDisplaced);

            TableExtractor.Result on = TableExtractor.extract(doc, pages, glyphs, true);
            assertTrue(on.truncated,
                    "two fully-built stream candidates were discarded on a quality judgement: "
                            + "report.json must say tablesTruncated, not stay silent");
            assertEquals(2, on.arbitrationDisplaced,
                    "both discarded candidates must be counted, exactly");
            TableExtractor.TableHit tagged = on.tables.stream()
                    .filter(t -> "tagged".equals(t.extractionMethod)).findFirst().orElseThrow();
            assertEquals(Boolean.TRUE, tagged.displacedStreamCandidate,
                    "the surviving answer must record that it replaced a borderless candidate");
            assertNull(tagged.displacedRuledCandidate);
            for (TableExtractor.TableHit t : on.tables) {
                assertNull(t.displacedRuledCandidate,
                        "no candidate here displaced a drawn-ruling answer: " + sig(t));
            }
        }
    }

    // ------------------------------------------------------- 3. contract: never throw, no partials

    @Test
    void streamStageToleratesMissingGlyphsForAPage() throws Exception {
        // --skip-text-urls hands extract() an EMPTY positionsByPage: positionsByPage.get(page) is
        // null. The lattice path has a region-strip fallback for that; the stream path is glyph-only
        // and must simply produce nothing -- never an NPE out of extract().
        Path pdf = tmp.resolve("grid.pdf");
        TableTestPdfs.ruled3x3(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = assertDoesNotThrow(() ->
                    TableExtractor.extract(doc, pagesOf(doc), new LinkedHashMap<>(), true));
            assertEquals(1, r.tables.size(), "the lattice region fallback must still work");
            assertEquals("lattice", r.tables.get(0).extractionMethod);
            assertEquals(0, r.tables.stream()
                    .filter(t -> "stream".equals(t.extractionMethod)).count(),
                    "no glyphs means no stream candidate -- never a fabricated one");
        }
    }

    @Test
    void glyphBombPageAbortsTheStreamStageAndFlagsTruncation() throws Exception {
        // A page above StreamTableExtractor.MAX_STREAM_GLYPHS: the stream stage must abort that
        // page with NO partial output, and (unlike a silent internal abort) the omission must be
        // visible to the caller through Result.truncated -- the same contract every sibling
        // hostile-input cap in TableExtractor honours.
        Path pdf = tmp.resolve("bomb.pdf");
        TableTestPdfs.manyGlyphsOnePage(pdf, StreamTableExtractor.MAX_STREAM_GLYPHS + 1000);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            Map<Integer, List<TextPosition>> glyphs = glyphsOf(doc);
            assertTrue(glyphs.get(1).size() > StreamTableExtractor.MAX_STREAM_GLYPHS,
                    "fixture must actually exceed the cap (got " + glyphs.get(1).size() + ")");
            TableExtractor.Result r = assertDoesNotThrow(() ->
                    TableExtractor.extract(doc, pagesOf(doc), glyphs, true));
            assertEquals(0, r.tables.stream()
                    .filter(t -> "stream".equals(t.extractionMethod)).count(),
                    "an aborted stream page must yield NO partial output");
            assertTrue(r.truncated, "the abort must be reported through Result.truncated");
        }
    }

    /**
     * MAX_TABLES_PER_PAGE must be COMPOSED across paths, not multiplied by adding one: each path
     * enforces its own per-page cap (tagged 50, lattice 50, stream 20), so a naive union could emit
     * more tables for one page than the documented ceiling. Driven through the package-private
     * composition helper with synthetic candidates, because building a PDF that saturates both caps
     * at once would be a multi-thousand-table fixture whose real cost is the fixture, not the rule.
     */
    @Test
    void perPageTableCapIsComposedAcrossPathsAndNeverDropsARuledCandidate() {
        List<TableExtractor.TableHit> merged = new ArrayList<>();
        for (int i = 0; i < TableExtractor.MAX_TABLES_PER_PAGE; i++) {
            TableExtractor.TableHit t = new TableExtractor.TableHit();
            t.page = 1;
            t.extractionMethod = "lattice";
            t.bbox = new float[]{0, i, 10, i + 1};
            merged.add(t);
        }
        for (int i = 0; i < 5; i++) {
            TableExtractor.TableHit t = new TableExtractor.TableHit();
            t.page = 1;
            t.extractionMethod = "stream";
            t.bbox = new float[]{100, i, 110, i + 1};
            t.confidence = 0.9;
            merged.add(t);
        }
        // A second page, well under the cap, must be untouched.
        TableExtractor.TableHit p2 = new TableExtractor.TableHit();
        p2.page = 2;
        p2.extractionMethod = "stream";
        p2.bbox = new float[]{0, 0, 10, 10};
        p2.confidence = 0.9;
        merged.add(p2);

        TableExtractor.Result r = new TableExtractor.Result();
        List<TableExtractor.TableHit> capped = TableExtractor.capTablesPerPage(merged, r);

        assertEquals(TableExtractor.MAX_TABLES_PER_PAGE,
                capped.stream().filter(t -> t.page == 1).count(),
                "page 1 must not exceed the documented per-page ceiling");
        assertEquals(TableExtractor.MAX_TABLES_PER_PAGE,
                capped.stream().filter(t -> t.page == 1 && "lattice".equals(t.extractionMethod)).count(),
                "the overflow must come off the STREAM side -- a drawn/tagged table is never dropped here");
        assertTrue(capped.contains(p2), "an under-cap page must be untouched");
        assertTrue(r.truncated, "dropping a candidate to the cap must set truncated");
    }

    // --------------------------------------------------- 4. the document-level stream page budget

    /**
     * The stream stage's per-page cost is bounded by {@code StreamTableExtractor}'s own budgets
     * (measured worst case 411ms on the most expensive page that could be constructed -- a
     * brick-offset layout that defeats the gutter search -- and constant in glyph count from 12k to
     * 108k glyphs, see {@code WiredDosProbe}). What was NOT bounded is the AGGREGATE across a long
     * document: cost is linear in page count, and {@code --timeout} defaults to 0 (no limit). This
     * budget bounds it. See {@link TableExtractor#MAX_STREAM_PAGES_PER_DOC} for the sizing argument.
     */
    @Test
    void streamStageStopsAtTheDocumentPageBudgetAndFlagsIt() throws Exception {
        int pages = TableExtractor.MAX_STREAM_PAGES_PER_DOC + 3;
        Path pdf = tmp.resolve("many.pdf");
        TableTestPdfs.borderless3ColManyPages(pdf, pages);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r =
                    TableExtractor.extract(doc, pagesOf(doc), glyphsOf(doc), true);
            long stream = r.tables.stream().filter(t -> "stream".equals(t.extractionMethod)).count();
            assertEquals(TableExtractor.MAX_STREAM_PAGES_PER_DOC, stream,
                    "every page carries exactly one borderless table, so the count of stream hits "
                    + "IS the number of pages the stream stage ran on");
            assertTrue(r.truncated, "stopping early must be reported, never silent");
            int lastStreamPage = r.tables.stream()
                    .filter(t -> "stream".equals(t.extractionMethod))
                    .mapToInt(t -> t.page).max().orElse(-1);
            assertEquals(TableExtractor.MAX_STREAM_PAGES_PER_DOC, lastStreamPage,
                    "the budget must cut off the TAIL of the document, in page order");
        }
    }

    @Test
    void aDocumentInsideThePageBudgetIsNotFlagged() throws Exception {
        Path pdf = tmp.resolve("few.pdf");
        TableTestPdfs.borderless3ColManyPages(pdf, 4);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r =
                    TableExtractor.extract(doc, pagesOf(doc), glyphsOf(doc), true);
            assertEquals(4, r.tables.size());
            assertFalse(r.truncated, "nothing was dropped, so nothing may be flagged");
        }
    }

    @Test
    void underCapPagesAreNotCopiedOrReordered() {
        List<TableExtractor.TableHit> merged = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TableExtractor.TableHit t = new TableExtractor.TableHit();
            t.page = 1 + i;
            t.extractionMethod = i == 0 ? "lattice" : "stream";
            t.bbox = new float[]{0, i, 10, i + 1};
            merged.add(t);
        }
        TableExtractor.Result r = new TableExtractor.Result();
        List<TableExtractor.TableHit> capped = TableExtractor.capTablesPerPage(merged, r);
        assertEquals(merged, capped, "no page is over the cap: the list must pass through unchanged");
        assertFalse(r.truncated, "nothing was dropped, so nothing may be flagged");
    }
}
