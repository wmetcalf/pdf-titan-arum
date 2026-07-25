package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * END-TO-END hostile-input bounds and reporting for the borderless ("stream") path, measured through
 * the real {@link TableExtractor#extract} rather than at the stage level.
 *
 * <p>WHAT WAS MEASURED BEFORE THE FIX, all through this same entry point:
 *
 * <ul>
 *   <li><b>D1.</b> A 135,316-byte SINGLE-PAGE PDF -- six blocks of 3 rows x 3,000 columns of size-0.4
 *       digits, 54,000 glyphs = 18% of {@code MAX_STREAM_GLYPHS} -- cost <b>215,248 ms</b> of CPU,
 *       returned six tables, and reported {@code truncated=false} while charging 9% of the
 *       word-denominated {@code MAX_STREAM_PAGE_BLOCK_WORK} budget.</li>
 *   <li><b>D2.</b> EIGHT {@code /Page} objects sharing ONE {@code /Contents} stream -- a
 *       <b>135,399-byte</b> file, 83 bytes larger than the one-page version -- cost <b>1,690,639 ms
 *       (28.2 minutes)</b>, returned 48 tables, {@code truncated=false}. At that measured 211 s/page,
 *       {@code MAX_STREAM_PAGES_PER_DOC = 64} allowed <b>~3.8 hours</b> on a ~135KB file, against the
 *       ~26 s its own javadoc claimed. Page count is nearly free in file bytes, so a page COUNT was
 *       never a bound.</li>
 *   <li><b>D3.</b> A page carrying an ordinary 5x3 borderless numeric table extracts it (1 table);
 *       add 60,001 one-character filler tokens to the SAME page -- 60,071 glyphs, only 20% of the
 *       glyph cap, so {@code extractStreamPage}'s glyph pre-check passes -- and the real table is
 *       silently dropped: 0 tables, {@code truncated} FALSE both times.</li>
 *   <li><b>D4.</b> The same hostile shape returns six tables at cols=3,000 and ZERO at cols=4,000,
 *       {@code truncated} false both times -- the cap trip was exactly the difference between
 *       emitting content and not, and report.json said nothing either way.</li>
 * </ul>
 *
 * <p>Wall-clock assertions here are generous CEILINGS, not benchmarks; the measured value is printed
 * on failure and the after-fix figures are quoted in each test so a regression is legible.
 */
class TableStreamDosTest {

    // ------------------------------------------------------------------------------------ fixtures

    private interface Painter { void paint(PDPageContentStream cs, PDFont f) throws IOException; }

    /** One painted page, then {@code pages-1} further {@code /Page} objects SHARING that single
     *  {@code /Contents} stream -- the cheapest way, in file bytes, for hostile input to buy page
     *  count, and the property that makes a page COUNT useless as a bound. */
    private static byte[] pdf(float w, float h, int pages, Painter p) throws IOException {
        try (PDDocument d = new PDDocument()) {
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage p0 = new PDPage(new PDRectangle(w, h));
            d.addPage(p0);
            try (PDPageContentStream cs = new PDPageContentStream(d, p0)) {
                p.paint(cs, font);
            }
            var contents = p0.getCOSObject().getDictionaryObject(COSName.CONTENTS);
            var res = p0.getCOSObject().getDictionaryObject(COSName.RESOURCES);
            var mb = p0.getCOSObject().getDictionaryObject(COSName.MEDIA_BOX);
            for (int i = 1; i < pages; i++) {
                PDPage pg = new PDPage();
                pg.getCOSObject().setItem(COSName.CONTENTS, contents);
                pg.getCOSObject().setItem(COSName.RESOURCES, res);
                pg.getCOSObject().setItem(COSName.MEDIA_BOX, mb);
                d.addPage(pg);
            }
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            d.save(bo);
            return bo.toByteArray();
        }
    }

    /**
     * THE REFERENCE ATTACK. {@code blocks} blocks of 3 rows x {@code cols} columns of size-0.4 digits.
     * The 2.0pt column pitch against a ~0.22pt glyph leaves an inter-column gap wider than {@code
     * minGutterW = max(medianSpace, 1) = 1}, so the primary branch-and-bound ACCEPTS every boundary
     * cheaply -- which is what makes the O(cols x words) stages after it, not the search, the cost.
     */
    private static Painter hostileGrid(int cols, int blocks) {
        return (cs, f) -> {
            cs.setFont(f, 0.4f);
            for (int b = 0; b < blocks; b++) {
                for (int r = 0; r < 3; r++) {
                    float y = 380f - b * 40f - r * 2f;
                    for (int c = 0; c < cols; c++) {
                        cs.beginText();
                        cs.newLineAtOffset(10f + c * 2.0f, y);
                        cs.showText(String.valueOf((char) ('0' + ((r + c) % 10))));
                        cs.endText();
                    }
                }
            }
        };
    }

    /** An ordinary 5-row x 3-column borderless numeric table at ordinary 10pt geometry: the REAL table
     *  whose silent disappearance is D3. Optionally preceded by {@code filler} one-char tokens. */
    private static Painter realTableWithFiller(int filler) {
        return (cs, f) -> {
            cs.setFont(f, 1f);
            int perLine = 200, emitted = 0;
            for (int li = 0; emitted < filler; li++) {
                float y = 780f - (li % 600) * 1.05f;
                for (int k = 0; k < perLine && emitted < filler; k++, emitted++) {
                    cs.beginText();
                    cs.newLineAtOffset(5f + k * 3f, y);
                    cs.showText("x");
                    cs.endText();
                }
            }
            cs.setFont(f, 10f);
            String[][] t = {
                {"Region", "Units", "Revenue"},
                {"North", "1200", "34500"},
                {"South", "980", "28700"},
                {"East", "1450", "41200"},
                {"West", "760", "19800"},
            };
            float[] xs = {60f, 220f, 380f};
            for (int r = 0; r < t.length; r++) {
                float y = 100f - r * 14f;
                for (int c = 0; c < 3; c++) {
                    cs.beginText();
                    cs.newLineAtOffset(xs[c], y);
                    cs.showText(t[r][c]);
                    cs.endText();
                }
            }
        };
    }

    private record Run(TableExtractor.Result result, long millis, int glyphs) {}

    /** Runs the REAL production entry point with the stream stage on, exactly as {@code
     *  --stream-tables} does. */
    private static Run extract(byte[] bytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            Map<Integer, List<TextPosition>> byPage = new LinkedHashMap<>();
            List<Integer> pages = new ArrayList<>();
            int glyphs = 0;
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                List<TextPosition> g = TableTestPdfs.harvestGlyphs(doc, i);
                byPage.put(i + 1, g);
                pages.add(i + 1);
                glyphs += g.size();
            }
            long t0 = System.nanoTime();
            TableExtractor.Result r = TableExtractor.extract(doc, pages, byPage, true);
            return new Run(r, (System.nanoTime() - t0) / 1_000_000L, glyphs);
        }
    }

    // ------------------------------------------------------------------------ D1: the 215-second page

    /**
     * D1. The reference attack, one page. Before the fix: 215,248 ms, six tables emitted,
     * {@code truncated=false}. After: 436 ms and {@code truncated=true}.
     *
     * <p>The 30-second ceiling is ~70x the measured after-fix figure -- deliberately far above it, so
     * this test cannot fail from machine noise or a slow CI box, while still being three orders of
     * magnitude below the defect it pins.
     */
    @Test
    void hostileWideGridPageCompletesFastAndReportsTruncation() throws IOException {
        byte[] bytes = pdf(6100f, 400f, 1, hostileGrid(3000, 6));
        Run run = extract(bytes);

        assertTrue(run.glyphs() < StreamTableExtractor.MAX_STREAM_GLYPHS,
                "the attack must stay UNDER the glyph cap -- that is what makes it an attack rather "
                        + "than something the existing caps already refuse; glyphs=" + run.glyphs());
        assertTrue(run.millis() < 30_000,
                "one hostile page must not cost minutes of CPU (measured 215,248 ms before the fix, "
                        + "436 ms after); took " + run.millis() + " ms");
        assertTrue(run.result().truncated,
                "refusing to score the page is correct, but it MUST be reported -- this returned six "
                        + "tables with truncated=false before the fix");
    }

    /** ...and the work the page charged must be BOUNDED by the per-page budget, not merely small on
     *  this particular fixture. */
    @Test
    void hostileWideGridPageChargeStaysInsideThePageBudget() throws IOException {
        Run run = extract(pdf(6100f, 400f, 1, hostileGrid(3000, 6)));
        long perPageCeiling = StreamTableExtractor.MAX_STREAM_PAGE_GRID_WORK
                + StreamTableExtractor.MAX_STREAM_PAGE_FINDER_WORK
                        / StreamTableExtractor.FINDER_WORK_PER_GRID_UNIT
                + StreamTableExtractor.MAX_GUTTER_SCAN_WORK
                        / StreamTableExtractor.FINDER_WORK_PER_GRID_UNIT;
        assertTrue(run.result().streamWorkCharged <= perPageCeiling,
                "one page charged " + run.result().streamWorkCharged + " units, above the per-page "
                        + "ceiling " + perPageCeiling);
    }

    // -------------------------------------------------------------- D2: pages are not a work bound

    /**
     * D2. The SAME content stream referenced by eight {@code /Page} objects -- 83 bytes more file than
     * the one-page version. Before the fix: 1,690,639 ms (28.2 minutes), 48 tables,
     * {@code truncated=false}; at that 211 s/page the 64-page allowance was ~3.8 hours. After: 1,335 ms
     * and {@code truncated=true}, with the document work budget cutting the tail at page 5 of 8.
     */
    @Test
    void pageCountIsCheapInBytesSoTheDocumentBoundIsWorkNotPages() throws IOException {
        byte[] one = pdf(6100f, 400f, 1, hostileGrid(3000, 6));
        byte[] eight = pdf(6100f, 400f, 8, hostileGrid(3000, 6));
        assertTrue(eight.length < one.length + 4_096,
                "eight pages sharing one /Contents must cost almost nothing in bytes -- that is why a "
                        + "page COUNT cannot bound this stage; one=" + one.length + " eight=" + eight.length);

        Run run = extract(eight);
        assertTrue(run.millis() < 60_000,
                "eight hostile pages must not cost half an hour (measured 1,690,639 ms before the fix, "
                        + "1,335 ms after); took " + run.millis() + " ms");
        assertTrue(run.result().truncated, "and the loss must be reported");
    }

    /** The document-level bound is levied on REAL WORK, and it cuts the tail. Charged work may exceed
     *  the budget by at most ONE page's own allowance, because -- exactly like the lattice sibling --
     *  the check is between pages and this class never stops mid-page. */
    @Test
    void streamDocumentWorkBudgetCutsTheTailAndIsNeverExceededByMoreThanOnePage() throws IOException {
        Run run = extract(pdf(6100f, 400f, 8, hostileGrid(3000, 6)));
        assertTrue(run.result().streamWorkCharged > 0, "the stream stage must actually have run");
        long onePageAllowance = StreamTableExtractor.MAX_STREAM_PAGE_GRID_WORK
                + (StreamTableExtractor.MAX_STREAM_PAGE_FINDER_WORK
                        + StreamTableExtractor.MAX_GUTTER_SCAN_WORK)
                        / StreamTableExtractor.FINDER_WORK_PER_GRID_UNIT;
        assertTrue(run.result().streamWorkCharged
                        <= TableExtractor.MAX_STREAM_DOC_WORK + onePageAllowance,
                "charged " + run.result().streamWorkCharged + " must not exceed the document budget "
                        + TableExtractor.MAX_STREAM_DOC_WORK + " by more than one page's allowance "
                        + onePageAllowance);
        assertTrue(run.result().truncated, "cutting the document tail must set truncated");
    }

    /** A short, ORDINARY document must not be touched by the new document budget: no truncation, and a
     *  charge that is a negligible fraction of it. This is the guard that the budget was sized over
     *  real input rather than merely set low enough to stop the attack. */
    @Test
    void ordinaryDocumentIsUnaffectedByTheDocumentWorkBudget() throws IOException {
        Run run = extract(pdf(612f, 792f, 4, realTableWithFiller(0)));
        assertFalse(run.result().truncated,
                "an ordinary 4-page document with one small borderless table per page must not be "
                        + "flagged truncated");
        assertTrue(run.result().streamWorkCharged < TableExtractor.MAX_STREAM_DOC_WORK / 100,
                "an ordinary document must charge <1% of the document budget, charged="
                        + run.result().streamWorkCharged);
        assertFalse(run.result().tables.isEmpty(), "and it must still find its tables");
    }

    // ------------------------------------------------- D3: the word cap silently ate a real table

    /**
     * D3. {@code extractStreamPage}'s pre-check covers only {@code MAX_STREAM_GLYPHS}, one of the
     * THREE page-global caps {@code extractPage}'s try wraps. Adding 60,001 one-character filler
     * tokens to a page that also carries a real borderless table trips {@code MAX_STREAM_WORDS}
     * (60,000) at just 20% of the glyph cap -- so the pre-check passes, the table vanishes, and before
     * this fix {@code truncated} stayed FALSE.
     *
     * <p>The control half of the assertion matters as much as the flag: it establishes that the table
     * IS extractable, so its absence is a real loss and not an artifact of the fixture.
     */
    @Test
    void wordCapDropsRealTablesButNoLongerSilently() throws IOException {
        Run control = extract(pdf(612f, 792f, 1, realTableWithFiller(0)));
        assertFalse(control.result().tables.isEmpty(),
                "CONTROL: the page's borderless table must be extractable without the filler");
        assertFalse(control.result().truncated, "CONTROL: and must not be flagged truncated");

        Run bombed = extract(pdf(612f, 792f, 1,
                realTableWithFiller(StreamTableExtractor.MAX_STREAM_WORDS + 1)));
        assertTrue(bombed.glyphs() > StreamTableExtractor.MAX_STREAM_WORDS,
                "the filler must trip the WORD cap");
        assertTrue(bombed.glyphs() < StreamTableExtractor.MAX_STREAM_GLYPHS,
                "...while staying under the GLYPH cap the pre-check tests, so the pre-check passes; "
                        + "glyphs=" + bombed.glyphs() + " of " + StreamTableExtractor.MAX_STREAM_GLYPHS);
        assertTrue(bombed.result().truncated,
                "the page-global WORD cap dropped every borderless table on this page; that MUST be "
                        + "reported (it was silent before this fix)");
    }

    /** The LINE cap is the third sibling and must report too. 8,001 distinct single-word lines trips
     *  {@code MAX_STREAM_LINES} well under both other caps. */
    @Test
    void lineCapAlsoReportsRatherThanDroppingSilently() throws IOException {
        int lines = StreamTableExtractor.MAX_STREAM_LINES + 1;
        byte[] bytes = pdf(200f, lines * 1.2f + 20f, 1, (cs, f) -> {
            cs.setFont(f, 1f);
            for (int i = 0; i < lines; i++) {
                for (int c = 0; c < 3; c++) {
                    cs.beginText();
                    cs.newLineAtOffset(5f + c * 40f, 10f + i * 1.2f);
                    cs.showText("77");
                    cs.endText();
                }
            }
        });
        Run run = extract(bytes);
        assertTrue(run.glyphs() < StreamTableExtractor.MAX_STREAM_GLYPHS,
                "must stay under the glyph pre-check, glyphs=" + run.glyphs());
        assertTrue(run.result().truncated,
                "the page-global LINE cap must be reported, not swallowed");
    }

    // --------------------------------------------- D4: a block-level cap trip changed the output

    /**
     * D4. cols=4,000 returns ZERO tables where cols=3,000 returned six, and before this fix both
     * reported {@code truncated=false}: the cap trip was precisely the difference between emitting
     * content and not, and report.json said nothing. There was structurally no channel -- {@code
     * extractPage} took no {@code Result}.
     */
    @Test
    void blockLevelCapTripIsReported() throws IOException {
        Run run = extract(pdf(8100f, 400f, 1, hostileGrid(4000, 6)));
        assertTrue(run.glyphs() < StreamTableExtractor.MAX_STREAM_GLYPHS,
                "must stay under the glyph pre-check; glyphs=" + run.glyphs());
        assertTrue(run.result().tables.isEmpty(),
                "this shape produces no tables (the block-level cap trips)");
        assertTrue(run.result().truncated,
                "a block-level cap trip that costs the page its output MUST be reported");
    }

    /** DEGENERATE CONTENT is not a cap trip and must NOT be flagged, or {@code tablesTruncated} would
     *  fire on ordinary prose and mean nothing. This is the boundary of the new reporting channel. */
    @Test
    void ordinaryProsePageIsNotFlaggedTruncated() throws IOException {
        byte[] bytes = pdf(612f, 792f, 1, (cs, f) -> {
            cs.setFont(f, 11f);
            String[] para = {
                "This page is running prose and contains no table of any kind whatsoever.",
                "It exists to pin the boundary of the new truncation-reporting channel: a",
                "page that simply has nothing tabular on it must not be reported as having",
                "lost anything, because a flag that fires on ordinary input carries no",
                "information at all for a triage analyst reading report.json.",
            };
            for (int i = 0; i < para.length; i++) {
                cs.beginText();
                cs.newLineAtOffset(60f, 700f - i * 16f);
                cs.showText(para[i]);
                cs.endText();
            }
        });
        Run run = extract(bytes);
        assertFalse(run.result().truncated,
                "a plain prose page must not be flagged truncated -- only hostile-input CAPS are");
    }

    // ------------------------------------------------------------------------------- D7: arbitrate

    /**
     * D7. {@code arbitrate}'s guard charged {@code lp * sp} before allocating {@code
     * boolean[lp][sp]}, which prices the matrix PAYLOAD but not the {@code lp} separate row objects
     * (~16 bytes of header each) nor the two marker arrays. With {@code sp == 1} the product term is
     * only {@code lp}, so an {@code lp} of 40,000,000 passed the guard and allocated ~640MB of row
     * headers before anything refused it.
     *
     * <p>WHY THE FIXTURE IS SIXTY CANDIDATES AND NOT FORTY MILLION. The defect is WHERE the refusal
     * happens, not whether one happens: at the production budget, {@code lp = 40,000,000} eventually
     * aborts either way (the component traversal charges {@code lp} per popped stream node), so the
     * two builds differ only in whether ~640MB was allocated first -- not in any output a test can
     * assert. Injecting the budget (see {@code arbitrate(List, List, long)}, the same test seam {@code
     * extract} already offers for the lattice document budget) makes the SAME arithmetic observable at
     * sixty candidates: with 60 ruled candidates of which only ONE contests the single stream hit, the
     * traversal charges just {@code sp + lp = 61}, so a budget of 150 admits the old guard's total of
     * 121 and refuses the corrected guard's 182. RED before, GREEN after, no gigabytes.
     */
    @Test
    void arbitrateChargesRowHeadersNotOnlyTheMatrixPayload() {
        List<TableExtractor.TableHit> ruled = new ArrayList<>();
        // one candidate overlapping the stream hit, 59 far away on the same page
        ruled.add(hit("lattice", 1, 0, 0, 10, 10));
        for (int i = 1; i < 60; i++) ruled.add(hit("lattice", 1, 500 + i * 20, 0, 500 + i * 20 + 10, 10));
        List<TableExtractor.TableHit> stream = List.of(hit("stream", 1, 0, 0, 10, 10));

        int lp = 60, sp = 1;
        long entryCharge     = lp + sp;                 //  61  -- charged before any bucketing
        long oldGuardCharge  = (long) lp * sp;          //  60  -- prices the matrix PAYLOAD only
        long newGuardCharge  = oldGuardCharge + lp + sp; // 121  -- prices the row headers too
        long traversalCharge = sp + lp;                 //  61  -- one component: 1 ruled + 1 stream node
        long budget = 200;
        assertEquals(182, entryCharge + oldGuardCharge + traversalCharge,
                "fixture arithmetic: total under the OLD charge");
        assertEquals(243, entryCharge + newGuardCharge + traversalCharge,
                "fixture arithmetic: total under the CORRECTED charge");
        assertTrue(entryCharge + oldGuardCharge + traversalCharge <= budget,
                "fixture arithmetic: the OLD charge must FIT the injected budget, else this is not a "
                        + "red-before test");
        assertTrue(entryCharge + newGuardCharge + traversalCharge > budget,
                "fixture arithmetic: the CORRECTED charge must NOT fit");

        assertThrows(TableExtractor.RulingOverflowException.class,
                () -> TableExtractor.arbitrate(ruled, stream, budget),
                "the guard must price the lp row objects it is gating, not only the lp*sp payload");
    }

    /** ...and the corrected charge must not refuse anything an ordinary page can produce: the densest
     *  page measured across the scoring corpus carried 9 ruled + 4 stream candidates, and the
     *  per-page cap is 50. */
    @Test
    void arbitrateStillAcceptsOrdinaryCandidateCounts() {
        List<TableExtractor.TableHit> ruled = new ArrayList<>();
        List<TableExtractor.TableHit> stream = new ArrayList<>();
        for (int i = 0; i < TableExtractor.MAX_TABLES_PER_PAGE; i++) {
            ruled.add(hit("lattice", 1, i * 20, 0, i * 20 + 10, 10));
            stream.add(hit("stream", 1, i * 20, 0, i * 20 + 10, 10));
        }
        assertDoesNotThrow(() -> TableExtractor.arbitrate(ruled, stream),
                "50 x 50 candidates on one page must still arbitrate at the production budget -- the "
                        + "corrected charge adds only lp + sp, which cannot matter at real counts");
    }

    private static TableExtractor.TableHit hit(String method, int page,
                                              float x0, float y0, float x1, float y1) {
        TableExtractor.TableHit t = new TableExtractor.TableHit();
        t.page = page;
        t.extractionMethod = method;
        t.bbox = new float[]{x0, y0, x1, y1};
        t.rowCount = 3; t.colCount = 3;
        t.cells = new ArrayList<>();
        return t;
    }
}
