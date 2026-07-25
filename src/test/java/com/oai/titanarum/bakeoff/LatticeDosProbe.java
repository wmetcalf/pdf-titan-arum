// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/ (matching this
// directory's convention -- see BaselineHarness's header) but declares `package com.oai.titanarum;`
// so it can drive package-private TableExtractor internals.
//
// PURPOSE. Size the document-level lattice budget (TableExtractor.MAX_LATTICE_DOC_WORK) from
// MEASUREMENT. Three questions, in the order that matters:
//   Q1  What does a REAL document charge? (77-PDF scoring corpus + 200-PDF prose sample, ALL pages.)
//       Establishes the number the budget must leave headroom over.
//   Q2  What can ONE hostile page charge, and what does that cost in wall time? One fixture per
//       per-page lattice budget (textfill / grouping / findcells+intersections / rulings / clump),
//       so the per-page ceiling is characterised across all of them, not just the one the brief
//       happened to name.
//   Q3  What does a hostile DOCUMENT cost, before and after a bound? N copies of the worst page
//       from Q2 -- the actual attack this lever exists to close.
//
// Gated by -DlatticeDos=true and named so Surefire's default includes never discover it. Run:
//   mvn -q -o test -Dtest=LatticeDosProbe -DlatticeDos=true
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class LatticeDosProbe {

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

    private static double pct(List<Double> sorted, int q) {
        if (sorted.isEmpty()) return 0;
        int i = Math.min(sorted.size() - 1, (int) Math.ceil(q / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, i));
    }

    private static long lpct(List<Long> sorted, int q) {
        if (sorted.isEmpty()) return 0;
        int i = Math.min(sorted.size() - 1, (int) Math.ceil(q / 100.0 * sorted.size()) - 1);
        return sorted.get(Math.max(0, i));
    }

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("latticeDos"), "set -DlatticeDos=true to run");

        p("=".repeat(92));
        p("LATTICE DOCUMENT-LEVEL COST -- charged work and wall time, real vs hostile");
        p("MAX_LATTICE_DOC_WORK currently = %d", TableExtractor.MAX_LATTICE_DOC_WORK);
        p("=".repeat(92));

        // ------------------------------------------------------------------ Q1: real documents
        StringBuilder notes = new StringBuilder();
        List<Path> corpus = new ArrayList<>();
        for (BakeOffHarness.ScoreUnit u : BakeOffHarness.buildScoringSet(notes).units) corpus.add(u.pdf());
        List<Path> prose = BakeOffHarness.sampleProsePdfs();
        if (prose == null) prose = List.of();

        p("");
        p("Q1  REAL DOCUMENTS, ALL PAGES, lattice+tagged only (flag OFF) through TableExtractor.extract");
        for (String which : new String[]{"corpus-77", "prose-200"}) {
            List<Path> set = which.equals("corpus-77") ? corpus : prose;
            if (set.isEmpty()) { p("  %-10s : unavailable", which); continue; }
            List<Long> docWork = new ArrayList<>();
            List<Long> pageWork = new ArrayList<>();
            List<Double> docMs = new ArrayList<>();
            List<Integer> pageCounts = new ArrayList<>();
            long worstDoc = -1, worstPage = -1;
            String worstDocId = "", worstPageId = "";
            double worstMs = 0;
            String worstMsId = "";
            int errs = 0;
            for (Path pdf : set) {
                try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                    int n = doc.getNumberOfPages();
                    pageCounts.add(n);
                    List<Integer> pages = new ArrayList<>();
                    Map<Integer, List<TextPosition>> g = new LinkedHashMap<>();
                    for (int i = 1; i <= n; i++) { pages.add(i); g.put(i, prodGlyphs(doc, i)); }

                    // per-page charge: one single-page extract per page (the charge is additive, so
                    // this is exactly the per-page decomposition of the whole-document charge)
                    for (int i = 1; i <= n; i++) {
                        TableExtractor.Result r1 =
                                TableExtractor.extract(doc, List.of(i), g, false);
                        pageWork.add(r1.latticeWorkCharged);
                        if (r1.latticeWorkCharged > worstPage) {
                            worstPage = r1.latticeWorkCharged;
                            worstPageId = pdf.getFileName() + " p" + i;
                        }
                    }

                    TableExtractor.extract(doc, pages, g, false);   // warm
                    long t0 = System.nanoTime();
                    TableExtractor.Result r = TableExtractor.extract(doc, pages, g, false);
                    double ms = (System.nanoTime() - t0) / 1e6;
                    docWork.add(r.latticeWorkCharged);
                    docMs.add(ms);
                    if (r.latticeWorkCharged > worstDoc) {
                        worstDoc = r.latticeWorkCharged;
                        worstDocId = pdf.getFileName() + " (" + n + "pp)";
                    }
                    if (ms > worstMs) { worstMs = ms; worstMsId = pdf.getFileName().toString(); }
                } catch (Throwable t) { errs++; }
            }
            docWork.sort(null); pageWork.sort(null); docMs.sort(null); pageCounts.sort(null);
            p("  %-10s n=%d errors=%d", which, docWork.size(), errs);
            p("    pages/doc          p50=%d p95=%d max=%d",
                    pageCounts.get(pageCounts.size() / 2),
                    pageCounts.get(Math.min(pageCounts.size() - 1, (int) (0.95 * pageCounts.size()))),
                    pageCounts.get(pageCounts.size() - 1));
            p("    charge per PAGE    p50=%,d p95=%,d max=%,d   (%s)",
                    lpct(pageWork, 50), lpct(pageWork, 95), pageWork.get(pageWork.size() - 1), worstPageId);
            p("    charge per DOC     p50=%,d p95=%,d max=%,d   (%s)",
                    lpct(docWork, 50), lpct(docWork, 95), worstDoc, worstDocId);
            p("    lattice wall/DOC   p50=%.1fms p95=%.1fms max=%.1fms (%s)",
                    pct(docMs, 50), pct(docMs, 95), worstMs, worstMsId);
        }

        // ------------------------------------------------------- Q2: one hostile page, per budget
        p("");
        p("Q2  ONE HOSTILE PAGE -- one fixture per per-page lattice budget");
        Path tmp = Files.createTempDirectory("lattice-dos");
        long worstPageCharge = 0;
        double worstPageMs = 0;
        String worstShape = "";
        Path worstFixture = null;
        try {
            List<Object[]> shapes = new ArrayList<>();
            shapes.add(new Object[]{"textfill 60x60 grid x 6000 glyphs", (Runner) f -> bigGridManyGlyphs(f, 60, 60, 6000)});
            shapes.add(new Object[]{"textfill 100x100 grid x 12000 glyphs", (Runner) f -> bigGridManyGlyphs(f, 100, 100, 12000)});
            shapes.add(new Object[]{"non-crossing ruling fans 5000+5000", (Runner) f -> nonCrossingFans(f, 5000)});
            shapes.add(new Object[]{"dense 200x200 ruling grid", (Runner) f -> pureGrid(f, 200, 200)});
            shapes.add(new Object[]{"co-linear cell pile (grouping)", (Runner) f -> colinearPile(f, 4500)});
            shapes.add(new Object[]{"path-point flood (100k lineTo, no paint)", (Runner) f -> pathFlood(f, 100_000)});
            shapes.add(new Object[]{"clump: 1 grid + 40000-run glyph bomb", (Runner) f -> clumpBomb(f, 40_000)});
            shapes.add(new Object[]{"path-point flood 400k lineTo", (Runner) f -> pathFlood(f, 400_000)});
            shapes.add(new Object[]{"path-point flood 1.6M lineTo", (Runner) f -> pathFlood(f, 1_600_000)});
            shapes.add(new Object[]{"rect flood 300k re (no paint)", (Runner) f -> rectFlood(f, 300_000)});
            shapes.add(new Object[]{"6000 short strokes (all painted)", (Runner) f -> manyStrokes(f, 6000)});

            for (Object[] s : shapes) {
                String name = (String) s[0];
                Runner mk = (Runner) s[1];
                Path f = tmp.resolve(name.replaceAll("[^a-zA-Z0-9]+", "-") + ".pdf");
                mk.make(f);
                try (PDDocument doc = Loader.loadPDF(f.toFile())) {
                    Map<Integer, List<TextPosition>> g = new LinkedHashMap<>();
                    g.put(1, prodGlyphs(doc, 1));
                    TableExtractor.extract(doc, List.of(1), g, false);  // warm
                    long t0 = System.nanoTime();
                    TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), g, false);
                    double ms = (System.nanoTime() - t0) / 1e6;
                    p("  %-42s glyphs=%,7d  charge=%,12d  %7.1fms  %6.1f ns/unit  tables=%d truncated=%s",
                            name, g.get(1).size(), r.latticeWorkCharged, ms,
                            r.latticeWorkCharged == 0 ? 0 : ms * 1e6 / r.latticeWorkCharged,
                            r.tables.size(), r.truncated);
                    if (ms > worstPageMs) {
                        worstPageMs = ms; worstShape = name; worstFixture = f;
                    }
                    worstPageCharge = Math.max(worstPageCharge, r.latticeWorkCharged);
                }
            }
            p("  WORST single page by wall time: %s at %.1fms; worst charge seen on any page = %,d",
                    worstShape, worstPageMs, worstPageCharge);

            // --------------------------------------------------- Q3: a hostile DOCUMENT (many pages)
            p("");
            p("Q3  HOSTILE DOCUMENT -- N copies of the worst page, lattice only (flag OFF)");
            for (int n : new int[]{1, 4, 16, 64}) {
                Path many = tmp.resolve("many-" + n + ".pdf");
                repeatWorstPage(many, n);
                try (PDDocument doc = Loader.loadPDF(many.toFile())) {
                    List<Integer> pages = new ArrayList<>();
                    Map<Integer, List<TextPosition>> g = new LinkedHashMap<>();
                    for (int i = 1; i <= doc.getNumberOfPages(); i++) { pages.add(i); g.put(i, prodGlyphs(doc, i)); }
                    long t0 = System.nanoTime();
                    TableExtractor.Result r = TableExtractor.extract(doc, pages, g, false);
                    double ms = (System.nanoTime() - t0) / 1e6;
                    p("  %3d hostile pages: %8.1fms total (%6.1fms/page) charge=%,14d tables=%d truncated=%s",
                            n, ms, ms / n, r.latticeWorkCharged, r.tables.size(), r.truncated);
                }
            }
            // ------------------------- Q4: per-page cost the work budget does NOT charge at all
            // collectRulings walks the whole content stream; only PATH operators are charged. A
            // page whose stream is all TEXT (or empty) therefore costs real tokenizing time at
            // (near) zero charge. Measure that residual so the budget's claim can be honest about
            // what it does not cover.
            p("");
            p("Q4  UNCHARGED PER-PAGE RESIDUAL -- pages that cost wall time but charge ~nothing");
            for (Object[] s : new Object[][]{
                    {"1000 empty pages", 1000, 0},
                    {"1000 pages x 200 text ops", 1000, 200},
                    {"200 pages x 5000 text ops", 200, 5000}}) {
                Path f = tmp.resolve("residual-" + s[1] + "-" + s[2] + ".pdf");
                textOnlyPages(f, (Integer) s[1], (Integer) s[2]);
                try (PDDocument doc = Loader.loadPDF(f.toFile())) {
                    List<Integer> pages = new ArrayList<>();
                    Map<Integer, List<TextPosition>> g = new LinkedHashMap<>();
                    for (int i = 1; i <= doc.getNumberOfPages(); i++) { pages.add(i); g.put(i, List.of()); }
                    long t0 = System.nanoTime();
                    TableExtractor.Result r = TableExtractor.extract(doc, pages, g, false);
                    double ms = (System.nanoTime() - t0) / 1e6;
                    p("  %-28s lattice %8.1fms total (%.3fms/page) charge=%,d truncated=%s",
                            s[0], ms, ms / (Integer) s[1], r.latticeWorkCharged, r.truncated);
                }
            }

            // ------------------------------ Q5: a LONG LEGITIMATE ruled document must not truncate
            p("");
            p("Q5  LONG LEGITIMATE DOCUMENT -- N pages, one real 24x6 ruled table each");
            for (int n : new int[]{50, 150, 300}) {
                Path f = tmp.resolve("legit-" + n + ".pdf");
                legitRuledPages(f, n);
                try (PDDocument doc = Loader.loadPDF(f.toFile())) {
                    List<Integer> pages = new ArrayList<>();
                    Map<Integer, List<TextPosition>> g = new LinkedHashMap<>();
                    for (int i = 1; i <= doc.getNumberOfPages(); i++) { pages.add(i); g.put(i, prodGlyphs(doc, i)); }
                    long t0 = System.nanoTime();
                    TableExtractor.Result r = TableExtractor.extract(doc, pages, g, false);
                    double ms = (System.nanoTime() - t0) / 1e6;
                    p("  %3d legit ruled pages: %8.1fms charge=%,12d (%,d/page) tables=%d truncated=%s",
                            n, ms, r.latticeWorkCharged, r.latticeWorkCharged / n, r.tables.size(), r.truncated);
                }
            }
            // ------------------------- Q6: HOSTILE WORST CASE, bound vs no bound, per attack shape
            // For each shape: a document long enough to spend the whole budget, run at the
            // production budget and again with the budget effectively disabled, so the ceiling the
            // bound buys is stated per shape rather than for one fixture.
            p("");
            p("Q6  HOSTILE DOCUMENT CEILING -- production budget vs unbounded, by attack shape");
            p("    (budget = %,d units)", TableExtractor.MAX_LATTICE_DOC_WORK);
            for (Object[] s : new Object[][]{
                    {"textfill 60x60 x 6000 glyphs", 60, (Runner) f -> bigGridManyGlyphs(f, 60, 60, 6000)},
                    {"rect flood 300k re", 40, (Runner) f -> rectFlood(f, 300_000)},
                    {"path flood 400k lineTo", 140, (Runner) f -> pathFlood(f, 400_000)},
                    {"200 pages x 5000 text ops (uncharged)", 200, (Runner) f -> textOnlyPages(f, 1, 5000)}}) {
                String name = (String) s[0];
                int n = (Integer) s[1];
                Path one = tmp.resolve("q6-one-" + name.replaceAll("[^a-zA-Z0-9]+", "-") + ".pdf");
                ((Runner) s[2]).make(one);
                Path many = tmp.resolve("q6-many-" + name.replaceAll("[^a-zA-Z0-9]+", "-") + ".pdf");
                repeatSharedPage(one, many, n);
                try (PDDocument doc = Loader.loadPDF(many.toFile())) {
                    List<Integer> pages = new ArrayList<>();
                    Map<Integer, List<TextPosition>> g = new LinkedHashMap<>();
                    for (int i = 1; i <= doc.getNumberOfPages(); i++) { pages.add(i); g.put(i, prodGlyphs(doc, i)); }

                    long t0 = System.nanoTime();
                    TableExtractor.Result bounded = TableExtractor.extract(doc, pages, g, false);
                    double boundedMs = (System.nanoTime() - t0) / 1e6;

                    t0 = System.nanoTime();
                    TableExtractor.Result free = TableExtractor.extract(doc, pages, g, false, Long.MAX_VALUE / 4);
                    double freeMs = (System.nanoTime() - t0) / 1e6;

                    p("  %-40s %3d pages: BOUND %7.0fms charged %,13d trunc=%-5s | UNBOUND %7.0fms charged %,13d",
                            name, n, boundedMs, bounded.latticeWorkCharged, bounded.truncated,
                            freeMs, free.latticeWorkCharged);
                    p("      -> per-page unbounded %.1fms; at MAX_PAGES_ALL=1000 that is %.1fs; "
                            + "bound caps it at %.1fs", freeMs / n, freeMs / n, boundedMs / 1000.0);
                }
            }
        } finally {
            try (java.util.stream.Stream<Path> w = Files.walk(tmp)) {
                w.sorted(Comparator.reverseOrder()).forEach(q -> {
                    try { Files.deleteIfExists(q); } catch (Exception ignored) { }
                });
            }
        }
    }

    private interface Runner { void make(Path f) throws Exception; }

    /** Rewrites {@code src} (a 1-page PDF) as {@code out} with {@code n} page objects all pointing at
     *  the SAME content stream: a small file buying n pages of work, the amplification the
     *  document-level bound exists to stop. */
    private static void repeatSharedPage(Path src, Path out, int n) throws Exception {
        try (PDDocument in = Loader.loadPDF(src.toFile()); PDDocument doc = new PDDocument()) {
            PDPage template = in.getPage(0);
            org.apache.pdfbox.cos.COSBase contents =
                    template.getCOSObject().getItem(org.apache.pdfbox.cos.COSName.CONTENTS);
            org.apache.pdfbox.cos.COSBase resources =
                    template.getCOSObject().getItem(org.apache.pdfbox.cos.COSName.RESOURCES);
            for (int i = 0; i < n; i++) {
                PDPage p = new PDPage(template.getMediaBox());
                if (contents != null) p.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.CONTENTS, contents);
                if (resources != null) p.getCOSObject().setItem(org.apache.pdfbox.cos.COSName.RESOURCES, resources);
                doc.addPage(p);
            }
            doc.save(out.toFile());
        }
    }

    /** Drives MAX_TEXTFILL_WORK: a big drawn grid crossed with many glyphs. */
    private static void bigGridManyGlyphs(Path file, int rows, int cols, int glyphs) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(1600, 1600));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                drawGrid(cs, rows, cols);
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

    private static void drawGrid(PDPageContentStream cs, int rows, int cols) throws Exception {
        cs.setLineWidth(0.4f);
        for (int r = 0; r <= rows; r++) {
            float y = 20 + r * (1560f / rows);
            cs.moveTo(20, y); cs.lineTo(1580, y); cs.stroke();
        }
        for (int c = 0; c <= cols; c++) {
            float x = 20 + c * (1560f / cols);
            cs.moveTo(x, 20); cs.lineTo(x, 1580); cs.stroke();
        }
    }

    /** Drives findCells' h x v intersection scan with ZERO retained points, so MAX_INTERSECTIONS
     *  (a cap on RETAINED points) cannot stop it: two fans that never cross. */
    private static void nonCrossingFans(Path file, int n) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(1600, 1600));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.4f);
                // horizontals confined to the LEFT half, verticals to the RIGHT half: no crossings
                for (int i = 0; i < n; i++) {
                    float y = 20 + (i % 1500) * 1.0f + (i / 1500) * 0.13f;
                    cs.moveTo(20, y); cs.lineTo(700, y); cs.stroke();
                }
                for (int i = 0; i < n; i++) {
                    float x = 900 + (i % 600) * 1.0f + (i / 600) * 0.11f;
                    cs.moveTo(x, 20); cs.lineTo(x, 1580); cs.stroke();
                }
            }
            doc.save(file.toFile());
        }
    }

    private static void pureGrid(Path file, int rows, int cols) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(1600, 1600));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                drawGrid(cs, rows, cols);
            }
            doc.save(file.toFile());
        }
    }

    /** Many cells sharing one exact edge coordinate: the bucket-defeating layout groupIntoTables'
     *  own doc names as the only way to drive MAX_GROUPING_WORK. */
    private static void colinearPile(Path file, int n) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(1600, 1600));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.4f);
                // one long horizontal pair, and n verticals between them -> n-1 cells all sharing
                // BOTH y edges, so every cell lands in the same two byYEdge buckets
                cs.moveTo(20, 700); cs.lineTo(1580, 700); cs.stroke();
                cs.moveTo(20, 740); cs.lineTo(1580, 740); cs.stroke();
                for (int i = 0; i < n; i++) {
                    float x = 20 + i * (1560f / n);
                    cs.moveTo(x, 700); cs.lineTo(x, 740); cs.stroke();
                }
            }
            doc.save(file.toFile());
        }
    }

    /** A single path that free-runs lineTo without ever painting: drives MAX_PATH_POINTS and the
     *  content-stream walk, with no rulings emitted at all. */
    private static void pathFlood(Path file, int points) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(1600, 1600));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.4f);
                cs.moveTo(20, 20);
                for (int i = 0; i < points; i++) cs.lineTo(20 + (i % 1500), 20 + ((i * 7) % 1500));
                cs.stroke();
            }
            doc.save(file.toFile());
        }
    }

    /** Many `re` operators, never painted: the appendRectangle equivalent of pathFlood. */
    private static void rectFlood(Path file, int rects) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(1600, 1600));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.4f);
                for (int i = 0; i < rects; i++) {
                    cs.addRect(20 + (i % 1500), 20 + ((i * 7) % 1500), 3, 3);
                }
                cs.stroke();
            }
            doc.save(file.toFile());
        }
    }

    /** Many separately PAINTED short strokes: each resets the path, so pointCount never overflows
     *  and every operator does full buffering work -- the shape that maximises real per-op cost
     *  while still retaining rulings. */
    private static void manyStrokes(Path file, int n) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(1600, 1600));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.4f);
                for (int i = 0; i < n; i++) {
                    float y = 20 + (i % 1500) * 1.0f + (i / 1500) * 0.17f;
                    cs.moveTo(20, y); cs.lineTo(1580, y); cs.stroke();
                }
            }
            doc.save(file.toFile());
        }
    }

    /** The shape TableClumpSplitDosTest uses: one under-ruled grid crossed with a huge run bomb,
     *  driving MAX_CLUMP_SPLIT_WORK. */
    private static void clumpBomb(Path file, int runs) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(1600, 1600));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.4f);
                // 2 columns x many rows, so each row's single wide cell looks "clumped"
                for (int r = 0; r <= 520; r++) {
                    float y = 20 + r * 3f;
                    cs.moveTo(20, y); cs.lineTo(1580, y); cs.stroke();
                }
                cs.moveTo(20, 20); cs.lineTo(20, 1580); cs.stroke();
                cs.moveTo(1580, 20); cs.lineTo(1580, 1580); cs.stroke();
                cs.setFont(TableTestPdfs.HELV, 4);
                for (int i = 0; i < runs; i++) {
                    cs.beginText();
                    cs.newLineAtOffset(22 + (i % 380) * 4f, 1570 - (i / 380) * 3f);
                    cs.showText("ab");
                    cs.endText();
                }
            }
            doc.save(file.toFile());
        }
    }

    /** {@code pages} pages whose content stream contains only text (or nothing at all): real
     *  tokenizing cost for collectRulings, zero PATH operators, so ~zero charge. */
    private static void textOnlyPages(Path file, int pages, int textOps) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int pg = 0; pg < pages; pg++) {
                PDPage page = new PDPage(new PDRectangle(600, 800));
                doc.addPage(page);
                if (textOps == 0) continue;
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.setFont(TableTestPdfs.HELV, 6);
                    for (int i = 0; i < textOps; i++) {
                        cs.beginText();
                        cs.newLineAtOffset(20 + (i % 50) * 10f, 780 - (i / 50) * 8f);
                        cs.showText("xy");
                        cs.endText();
                    }
                }
            }
            doc.save(file.toFile());
        }
    }

    /** {@code pages} pages each carrying ONE ordinary 24-row x 6-column ruled table with text in
     *  every cell: the "long legitimate document" the budget must not truncate. */
    private static void legitRuledPages(Path file, int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int pg = 0; pg < pages; pg++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.setLineWidth(0.6f);
                    for (int r = 0; r <= 24; r++) {
                        float y = 80 + r * 26f;
                        cs.moveTo(40, y); cs.lineTo(560, y); cs.stroke();
                    }
                    for (int c = 0; c <= 6; c++) {
                        float x = 40 + c * (520f / 6);
                        cs.moveTo(x, 80); cs.lineTo(x, 80 + 24 * 26f); cs.stroke();
                    }
                    cs.setFont(TableTestPdfs.HELV, 8);
                    for (int r = 0; r < 24; r++) {
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

    /** {@code n} copies of the textfill-worst page (the shape Q2 measures as the most expensive
     *  single lattice page): the actual document-length amplification attack. */
    private static void repeatWorstPage(Path file, int n) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int pg = 0; pg < n; pg++) {
                PDPage page = new PDPage(new PDRectangle(1600, 1600));
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    drawGrid(cs, 60, 60);
                    cs.setFont(TableTestPdfs.HELV, 5);
                    for (int i = 0; i < 6000; i++) {
                        cs.beginText();
                        cs.newLineAtOffset(22 + (i % 100) * 15f, 1570 - (i / 100) * 10f);
                        cs.showText("W");
                        cs.endText();
                    }
                }
            }
            doc.save(file.toFile());
        }
    }
}
