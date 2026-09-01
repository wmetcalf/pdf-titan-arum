package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Child-JVM driver for the two HEAVY document-level lattice-budget checks (see {@link
 * TableLatticeDocBudgetTest}): the 60-page adversarial document that must trip {@link
 * TableExtractor#MAX_LATTICE_DOC_WORK}, and the 300-page legitimate document that must not.
 *
 * <p>WHY A CHILD JVM (the pattern {@code TableRegionGlyphBombProbe} already establishes here).
 * These two checks push hundreds of pages through the real per-page pipeline, which makes {@code
 * placeGridBudgeted} and its callers hot. That changes how much the JIT inlines into {@code
 * splitComponent}'s RECURSIVE frame, and {@code
 * TableGeometryTest#splitComponentDepthCapPreventsUnboundedRecursionAndStackOverflow} asserts that
 * that recursion survives a 128KB stack -- a margin thin enough to depend on JVM warm-up state.
 * VERIFIED at the base commit with base {@code src/main} and a throwaway control class: four
 * ordinary extraction tests running before {@code TableGeometryTest} are enough to make that test
 * fail, with no change to {@code src/main} at all. So the fragility is PRE-EXISTING and belongs to
 * that budget, not this one -- but a new test class must not be the thing that trips it. Running
 * these two here keeps every assertion at full strength (production constant, real page counts, real
 * wall clock) in a JVM whose JIT state cannot reach the suite's.
 *
 * <p>Exits 0 and prints {@code PROBE_OK} only when every check passes; otherwise prints
 * {@code PROBE_FAIL <reason>} and exits 1. Never discovered by Surefire (no {@code @Test}, and the
 * name ends in Probe).
 */
public final class TableLatticeDocBudgetProbe {

    private static final int HOSTILE_PAGES = 60;
    private static final int LEGIT_PAGES = 300;

    public static void main(String[] args) {
        try {
            if (args.length < 2) {
                fail("usage: TableLatticeDocBudgetProbe <hostile|legit> <pdfPath>");
                return;
            }
            Path pdf = Path.of(args[1]);
            if ("hostile".equals(args[0])) hostile(pdf);
            else if ("legit".equals(args[0])) legit(pdf);
            else fail("unknown mode " + args[0]);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            fail("threw " + t);
        }
    }

    private static void fail(String why) {
        System.out.println("PROBE_FAIL " + why);
        System.exit(1);
    }

    // ------------------------------------------------------------------------------ the two checks

    /** 60 page objects sharing ONE content stream (a ~300KB file buying 60 pages of work -- the
     *  amplification this bound closes) whose page is the highest-charge lattice page measured. */
    private static void hostile(Path pdf) throws Exception {
        sharedHostilePage(pdf, HOSTILE_PAGES);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (doc.getNumberOfPages() != HOSTILE_PAGES) {
                fail("fixture has " + doc.getNumberOfPages() + " pages, expected " + HOSTILE_PAGES);
                return;
            }
            List<Integer> pages = allPages(doc);
            Map<Integer, List<TextPosition>> g = allGlyphs(doc, pages);

            long t0 = System.nanoTime();
            TableExtractor.Result r = TableExtractor.extract(doc, pages, g, false);
            double ms = (System.nanoTime() - t0) / 1e6;

            System.out.printf(Locale.ROOT,
                    "hostile pages=%d charged=%d budget=%d ms=%.0f truncated=%s tables=%d%n",
                    HOSTILE_PAGES, r.latticeWorkCharged, TableExtractor.MAX_LATTICE_DOC_WORK, ms,
                    r.truncated, r.tables.size());

            if (!r.truncated) {
                fail("a document past MAX_LATTICE_DOC_WORK must set Result.truncated so report.json's "
                        + "tablesTruncated surfaces the loss");
                return;
            }
            if (r.latticeWorkCharged < TableExtractor.MAX_LATTICE_DOC_WORK) {
                fail("the budget should have been reached; charged only " + r.latticeWorkCharged);
                return;
            }
            // One page's charge is bounded by the sum of the per-page lattice budgets; the worst
            // single page ever measured charges 32M. 200M of slack is 6x that and still proves the
            // TAIL WAS CUT rather than merely flagged: 60 unbounded pages charge ~1.26 BILLION.
            if (r.latticeWorkCharged > TableExtractor.MAX_LATTICE_DOC_WORK + 200_000_000L) {
                fail("the document tail must be cut once the budget is spent, but charged "
                        + r.latticeWorkCharged + " against a budget of "
                        + TableExtractor.MAX_LATTICE_DOC_WORK);
                return;
            }
            System.out.println("PROBE_OK");
        }
    }

    /** 300 pages, one ordinary DENSE 25-row x 6-column ruled table each -- 20x the longest document
     *  in either real sample, 4.7x MAX_STREAM_PAGES_PER_DOC. Must complete untouched. */
    private static void legit(Path pdf) throws Exception {
        legitRuledPages(pdf, LEGIT_PAGES);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<Integer> pages = allPages(doc);
            Map<Integer, List<TextPosition>> g = allGlyphs(doc, pages);

            long t0 = System.nanoTime();
            TableExtractor.Result r = TableExtractor.extract(doc, pages, g, false);
            double ms = (System.nanoTime() - t0) / 1e6;

            System.out.printf(Locale.ROOT,
                    "legit pages=%d charged=%d perPage=%d budget=%d pctOfBudget=%.2f ms=%.0f "
                    + "truncated=%s tables=%d%n",
                    LEGIT_PAGES, r.latticeWorkCharged, r.latticeWorkCharged / LEGIT_PAGES,
                    TableExtractor.MAX_LATTICE_DOC_WORK,
                    100.0 * r.latticeWorkCharged / TableExtractor.MAX_LATTICE_DOC_WORK, ms,
                    r.truncated, r.tables.size());

            if (r.truncated) {
                fail("a " + LEGIT_PAGES + "-page legitimate ruled document must not be truncated; charged "
                        + r.latticeWorkCharged + " of " + TableExtractor.MAX_LATTICE_DOC_WORK);
                return;
            }
            if (r.tables.size() != LEGIT_PAGES) {
                fail("every page's table must survive; got " + r.tables.size() + " of " + LEGIT_PAGES);
                return;
            }
            for (TableExtractor.TableHit t : r.tables) {
                if (t.rowCount != 25 || t.colCount != 6) {
                    fail("a kept table must be complete; page " + t.page + " is "
                            + t.rowCount + "x" + t.colCount);
                    return;
                }
            }
            if (r.latticeWorkCharged * 10 >= TableExtractor.MAX_LATTICE_DOC_WORK) {
                fail("the worst realistic long document should sit an order of magnitude under the "
                        + "budget, but charged " + r.latticeWorkCharged + " of "
                        + TableExtractor.MAX_LATTICE_DOC_WORK);
                return;
            }
            System.out.println("PROBE_OK");
        }
    }

    // ---------------------------------------------------------------------------------- machinery

    private static List<Integer> allPages(PDDocument doc) {
        List<Integer> pages = new ArrayList<>();
        for (int i = 1; i <= doc.getNumberOfPages(); i++) pages.add(i);
        return pages;
    }

    private static Map<Integer, List<TextPosition>> allGlyphs(PDDocument doc, List<Integer> pages)
            throws IOException {
        Map<Integer, List<TextPosition>> g = new LinkedHashMap<>();
        for (int p : pages) g.put(p, glyphs(doc, p));
        return g;
    }

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

    private static void sharedHostilePage(Path file, int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage first = new PDPage(new PDRectangle(1600, 1600));
            doc.addPage(first);
            try (PDPageContentStream cs = new PDPageContentStream(doc, first)) {
                cs.setLineWidth(0.4f);
                for (int r = 0; r <= 60; r++) {
                    float y = 20 + r * (1560f / 60);
                    cs.moveTo(20, y); cs.lineTo(1580, y); cs.stroke();
                }
                for (int c = 0; c <= 60; c++) {
                    float x = 20 + c * (1560f / 60);
                    cs.moveTo(x, 20); cs.lineTo(x, 1580); cs.stroke();
                }
                cs.setFont(TableTestPdfs.HELV, 5);
                for (int i = 0; i < 6000; i++) {
                    cs.beginText();
                    cs.newLineAtOffset(22 + (i % 100) * 15f, 1570 - (i / 100) * 10f);
                    cs.showText("W");
                    cs.endText();
                }
            }
            COSBase contents = first.getCOSObject().getItem(COSName.CONTENTS);
            COSBase resources = first.getCOSObject().getItem(COSName.RESOURCES);
            for (int i = 1; i < pages; i++) {
                PDPage p = new PDPage(new PDRectangle(1600, 1600));
                p.getCOSObject().setItem(COSName.CONTENTS, contents);
                p.getCOSObject().setItem(COSName.RESOURCES, resources);
                doc.addPage(p);
            }
            doc.save(file.toFile());
        }
    }

    private static void legitRuledPages(Path file, int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int pg = 0; pg < pages; pg++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.setLineWidth(0.6f);
                    for (int r = 0; r <= 25; r++) {
                        float y = 80 + r * 26f;
                        cs.moveTo(40, y); cs.lineTo(560, y); cs.stroke();
                    }
                    for (int c = 0; c <= 6; c++) {
                        float x = 40 + c * (520f / 6);
                        cs.moveTo(x, 80); cs.lineTo(x, 80 + 25 * 26f); cs.stroke();
                    }
                    cs.setFont(TableTestPdfs.HELV, 8);
                    for (int r = 0; r < 25; r++) {
                        for (int c = 0; c < 6; c++) {
                            cs.beginText();
                            cs.newLineAtOffset(46 + c * (520f / 6), 88 + r * 26f);
                            cs.showText("R" + r + "C" + c);
                            cs.endText();
                        }
                    }
                }
            }
            doc.save(file.toFile());
        }
    }
}
