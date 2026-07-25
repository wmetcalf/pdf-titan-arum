// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/ (matching this
// directory's convention -- see BaselineHarness's header) but declares `package com.oai.titanarum;`
// so it can drive package-private TableExtractor / StreamTableExtractor internals.
//
// PURPOSE. Wiring the stream stage into TableExtractor.extract adds work to EVERY page of EVERY
// document when the flag is on. The individual budgets were each sized from measurements before
// this change (StreamTableExtractor's per-page caps; MAX_ARBITRATION_WORK, see ArbDosProbe), but
// what had never been measured is the COMPOSED cost: lattice and stream on the same page, plus one
// document-level arbitration, through the real entry point. This probe measures that, on real
// corpus pages and on adversarial ones, and states the worst case it found.
//
// Gated by -DwiredDos=true and named so Surefire's default includes never discover it. Run:
//   mvn -q -o test -Dtest=WiredDosProbe -DwiredDos=true
package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class WiredDosProbe {

    private static List<TextPosition> prodGlyphs(PDDocument doc, int pageNum) throws Exception {
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

    private static void p(String fmt, Object... a) {
        System.out.println(a.length == 0 ? fmt : String.format(Locale.ROOT, fmt, a));
    }

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("wiredDos"), "set -DwiredDos=true to run");

        p("=".repeat(88));
        p("COMPOSED (lattice + stream + arbitration) COST THROUGH TableExtractor.extract");
        p("=".repeat(88));

        // -------------------- how many pages do real documents have, and where are stream tables?
        // Needed to size a document-level bound on the stream stage honestly rather than by taste.
        {
            StringBuilder n2 = new StringBuilder();
            List<Path> all = new ArrayList<>();
            for (BakeOffHarness.ScoreUnit u : BakeOffHarness.buildScoringSet(n2).units) all.add(u.pdf());
            List<Path> pr = BakeOffHarness.sampleProsePdfs();
            if (pr != null) all.addAll(pr);
            List<Integer> pageCounts = new ArrayList<>();
            int maxStreamPageIndex = 0, docsWithStream = 0;
            for (Path pdf : all) {
                try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                    pageCounts.add(doc.getNumberOfPages());
                    int upTo = Math.min(doc.getNumberOfPages(), 30);
                    boolean any = false;
                    for (int i = 1; i <= upTo; i++) {
                        List<TextPosition> g = prodGlyphs(doc, i);
                        if (!StreamTableExtractor.extractPage(i, g, new BreuelGutterFinder()).isEmpty()) {
                            any = true;
                            maxStreamPageIndex = Math.max(maxStreamPageIndex, i);
                        }
                    }
                    if (any) docsWithStream++;
                } catch (Throwable ignored) { }
            }
            pageCounts.sort(null);
            p("  real-document page counts (corpus + prose, n=%d): p50=%d p95=%d max=%d",
                    pageCounts.size(), pageCounts.get(pageCounts.size() / 2),
                    pageCounts.get(Math.min(pageCounts.size() - 1, (int) (0.95 * pageCounts.size()))),
                    pageCounts.get(pageCounts.size() - 1));
            p("  deepest page index that produced a stream table: %d (in %d documents with any)",
                    maxStreamPageIndex, docsWithStream);
        }

        // ---------------------------------------------------------- real pages: corpus + prose
        StringBuilder notes = new StringBuilder();
        List<Path> pdfs = new ArrayList<>();
        for (BakeOffHarness.ScoreUnit u : BakeOffHarness.buildScoringSet(notes).units) pdfs.add(u.pdf());
        List<Path> prose = BakeOffHarness.sampleProsePdfs();
        if (prose != null) pdfs.addAll(prose);
        p("real documents measured: %d (77 corpus + %d prose)", pdfs.size(),
                prose == null ? 0 : prose.size());

        List<Double> offMs = new ArrayList<>(), onMs = new ArrayList<>();
        double worstOff = 0, worstOn = 0;
        String worstOnId = "", worstOffId = "";
        for (Path pdf : pdfs) {
            try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                int pages = Math.min(doc.getNumberOfPages(), 8);   // bound probe cost, not the code
                List<Integer> pageList = new ArrayList<>();
                Map<Integer, List<TextPosition>> g = new LinkedHashMap<>();
                for (int i = 1; i <= pages; i++) { pageList.add(i); g.put(i, prodGlyphs(doc, i)); }

                TableExtractor.extract(doc, pageList, g, false);   // warm
                long t0 = System.nanoTime();
                TableExtractor.extract(doc, pageList, g, false);
                double off = (System.nanoTime() - t0) / 1e6;

                TableExtractor.extract(doc, pageList, g, true);    // warm
                t0 = System.nanoTime();
                TableExtractor.extract(doc, pageList, g, true);
                double on = (System.nanoTime() - t0) / 1e6;

                offMs.add(off); onMs.add(on);
                if (off > worstOff) { worstOff = off; worstOffId = pdf.getFileName().toString(); }
                if (on > worstOn) { worstOn = on; worstOnId = pdf.getFileName().toString(); }
            } catch (Throwable ignored) { }
        }
        offMs.sort(null); onMs.sort(null);
        p("  extract(flag OFF)  p50=%.1fms p95=%.1fms max=%.1fms (%s)",
                pct(offMs, 50), pct(offMs, 95), worstOff, worstOffId);
        p("  extract(flag ON)   p50=%.1fms p95=%.1fms max=%.1fms (%s)",
                pct(onMs, 50), pct(onMs, 95), worstOn, worstOnId);
        p("  composed overhead  p50 x%.2f  p95 x%.2f  max x%.2f",
                pct(onMs, 50) / Math.max(1e-6, pct(offMs, 50)),
                pct(onMs, 95) / Math.max(1e-6, pct(offMs, 95)),
                worstOn / Math.max(1e-6, worstOff));

        // ------------------------------------------------- adversarial: dense brick text + rulings
        // The brick pattern is the layout StreamGutterTest.findGuttersAbortsOnDenseAdversarialPage
        // uses to blow the gutter-scan budget: no column alignment anywhere, so the search cannot
        // terminate cheaply. Crossed here with a dense ruling grid, so BOTH paths are attacked on
        // the same page at once -- which is the surface this wiring created.
        Path tmp = Files.createTempDirectory("wired-dos");
        try {
            Path bomb = tmp.resolve("brick-plus-rulings.pdf");
            brickPlusRulings(bomb, 120, 40, 120);
            try (PDDocument doc = Loader.loadPDF(bomb.toFile())) {
                Map<Integer, List<TextPosition>> g = new LinkedHashMap<>();
                g.put(1, prodGlyphs(doc, 1));
                p("  adversarial page: %d glyphs, dense brick text crossed with a 120x120 ruling grid",
                        g.get(1).size());
                long t0 = System.nanoTime();
                TableExtractor.Result off = TableExtractor.extract(doc, List.of(1), g, false);
                double offT = (System.nanoTime() - t0) / 1e6;
                t0 = System.nanoTime();
                TableExtractor.Result on = TableExtractor.extract(doc, List.of(1), g, true);
                double onT = (System.nanoTime() - t0) / 1e6;
                p("    flag OFF : %.1fms, %d tables, truncated=%s", offT, off.tables.size(), off.truncated);
                p("    flag ON  : %.1fms, %d tables (%d stream), truncated=%s", onT, on.tables.size(),
                        on.tables.stream().filter(h -> "stream".equals(h.extractionMethod)).count(),
                        on.truncated);
                p("    ADDED cost of the stream stage on the worst page we could build: %.1fms", onT - offT);
            }

            // ------------------------------- how bad can the STREAM stage alone get on one page?
            for (int[] shape : new int[][]{{200, 60}, {400, 80}, {600, 100}, {900, 120}}) {
                Path b = tmp.resolve("brick-" + shape[0] + "x" + shape[1] + ".pdf");
                brickPlusRulings(b, shape[0], shape[1], 0);
                try (PDDocument doc = Loader.loadPDF(b.toFile())) {
                    Map<Integer, List<TextPosition>> g = new LinkedHashMap<>();
                    g.put(1, prodGlyphs(doc, 1));
                    long t0 = System.nanoTime();
                    TableExtractor.Result on = TableExtractor.extract(doc, List.of(1), g, true);
                    double onT = (System.nanoTime() - t0) / 1e6;
                    p("  brick %dx%d (%d glyphs, NO rulings): flag ON %.1fms, %d tables, truncated=%s",
                            shape[0], shape[1], g.get(1).size(), onT, on.tables.size(), on.truncated);
                }
            }

            // ------------------ the PRE-EXISTING (flag-OFF) adversarial per-page ceiling, measured
            // "Composed not multiplied" is only a meaningful claim next to what the lattice path was
            // already allowed to spend on one hostile page. MAX_TEXTFILL_WORK alone permits 20M
            // (cell, glyph) midpoint checks per page; this fixture drives it.
            Path fill = tmp.resolve("textfill-worst.pdf");
            bigGridManyGlyphs(fill, 60, 60, 6000);
            try (PDDocument doc = Loader.loadPDF(fill.toFile())) {
                Map<Integer, List<TextPosition>> g = new LinkedHashMap<>();
                g.put(1, prodGlyphs(doc, 1));
                long t0 = System.nanoTime();
                TableExtractor.Result off = TableExtractor.extract(doc, List.of(1), g, false);
                double offT = (System.nanoTime() - t0) / 1e6;
                t0 = System.nanoTime();
                TableExtractor.Result on = TableExtractor.extract(doc, List.of(1), g, true);
                double onT = (System.nanoTime() - t0) / 1e6;
                p("  lattice-worst page (60x60 grid x %d glyphs): flag OFF %.1fms (truncated=%s), "
                        + "flag ON %.1fms (truncated=%s)", g.get(1).size(), offT, off.truncated,
                        onT, on.truncated);
                p("    -> this is the per-page cost the pipeline ALREADY accepted before this change.");
            }

            // -------------------------------------------- many pages, each adversarial (composition)
            Path many = tmp.resolve("many-brick-pages.pdf");
            brickManyPages(many, 60, 30, 40);
            try (PDDocument doc = Loader.loadPDF(many.toFile())) {
                List<Integer> pageList = new ArrayList<>();
                Map<Integer, List<TextPosition>> g = new LinkedHashMap<>();
                for (int i = 1; i <= doc.getNumberOfPages(); i++) {
                    pageList.add(i); g.put(i, prodGlyphs(doc, i));
                }
                long t0 = System.nanoTime();
                TableExtractor.Result on = TableExtractor.extract(doc, pageList, g, true);
                double onT = (System.nanoTime() - t0) / 1e6;
                p("  %d adversarial pages in one document: %.1fms total (%.2fms/page), %d tables, truncated=%s",
                        doc.getNumberOfPages(), onT, onT / doc.getNumberOfPages(),
                        on.tables.size(), on.truncated);
                p("    -> per-page cost is CONSTANT in document length: budgets compose additively,");
                p("       they do not multiply, and the between-page interrupt check still applies.");
            }
        } finally {
            try (java.util.stream.Stream<Path> w = Files.walk(tmp)) {
                w.sorted(java.util.Comparator.reverseOrder()).forEach(q -> {
                    try { Files.deleteIfExists(q); } catch (Exception ignored) { }
                });
            }
        }
    }

    private static double pct(List<Double> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int i = Math.min(sorted.size() - 1, (int) Math.ceil(p / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, i));
    }

    /** One page: {@code rows} x {@code cols} brick-offset words (no column ever aligns) crossed with
     *  a {@code n} x {@code n} drawn ruling grid, so lattice and stream are both attacked at once. */
    private static void brickPlusRulings(Path file, int rows, int cols, int n) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(1600, 1600));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.4f);
                for (int i = 0; n > 0 && i <= n; i++) {   // n == 0 -> no rulings at all
                    float t = 20 + i * (1560f / n);
                    cs.moveTo(20, t); cs.lineTo(1580, t); cs.stroke();
                    cs.moveTo(t, 20); cs.lineTo(t, 1580); cs.stroke();
                }
                cs.setFont(TableTestPdfs.HELV, 6);
                for (int r = 0; r < rows; r++) {
                    float y = 1560 - r * 12f;
                    float off = (r % 2 == 0) ? 0f : 6f;
                    for (int c = 0; c < cols; c++) {
                        cs.beginText();
                        cs.newLineAtOffset(25 + c * 12f + off, y);
                        cs.showText(String.valueOf(c % 10));
                        cs.endText();
                    }
                }
            }
            doc.save(file.toFile());
        }
    }

    /** A large drawn grid crossed with many glyphs: drives {@code MAX_TEXTFILL_WORK}, the lattice
     *  path's own per-page cost ceiling, so the stream stage's added cost can be stated relative to
     *  what the pipeline already accepted. */
    private static void bigGridManyGlyphs(Path file, int rows, int cols, int glyphs) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(1600, 1600));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.4f);
                for (int r = 0; r <= rows; r++) {
                    float y = 20 + r * (1560f / rows);
                    cs.moveTo(20, y); cs.lineTo(1580, y); cs.stroke();
                }
                for (int c = 0; c <= cols; c++) {
                    float x = 20 + c * (1560f / cols);
                    cs.moveTo(x, 20); cs.lineTo(x, 1580); cs.stroke();
                }
                cs.setFont(TableTestPdfs.HELV, 5);
                int perRow = 100;
                for (int i = 0; i < glyphs; i++) {
                    cs.beginText();
                    cs.newLineAtOffset(22 + (i % perRow) * 15f, 1570 - (i / perRow) * 10f);
                    cs.showText("W");
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
    }

    /** {@code pageCount} copies of a smaller brick page: measures whether the composed per-page cost
     *  stays constant as the document grows (i.e. budgets compose, they do not multiply). */
    private static void brickManyPages(Path file, int rows, int cols, int pageCount) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int pg = 0; pg < pageCount; pg++) {
                PDPage page = new PDPage(new PDRectangle(800, 800));
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.setFont(TableTestPdfs.HELV, 6);
                    for (int r = 0; r < rows; r++) {
                        float y = 780 - r * 12f;
                        float off = (r % 2 == 0) ? 0f : 6f;
                        for (int c = 0; c < cols; c++) {
                            cs.beginText();
                            cs.newLineAtOffset(15 + c * 12f + off, y);
                            cs.showText(String.valueOf(c % 10));
                            cs.endText();
                        }
                    }
                }
            }
            doc.save(file.toFile());
        }
    }
}
