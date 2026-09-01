// MEASUREMENT ONLY (gated -DfpChar=true). Characterises the prose false positives the stream path
// ADDS over the shipping page selection: for every real-world PDF where the flag OFF emits no table
// and the flag ON emits one, dumps every stream candidate the page produced with its full scoreGrid
// term breakdown, its numeric evidence, its cell-fill density and its actual text, so the mechanism
// can be identified rather than the threshold tuned.
//
// -DfpAll=true measures the whole 1,599-PDF population instead of the tracked 200-PDF stride sample.
// -DfpDump=true prints the per-document detail (otherwise only the aggregate tables).
//
// Physically under bakeoff/, declares `package com.oai.titanarum;` for package-private access to
// TableExtractor / StreamTableExtractor internals -- the same convention BaselineHarness uses.
package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class FpCharProbe {

    private static final Path PHISH_ROOT = Path.of("/home/coz/Downloads/phishpdfs");

    private final StringBuilder out = new StringBuilder();

    private void line(String fmt, Object... a) {
        out.append(String.format(Locale.ROOT, fmt, a)).append('\n');
    }

    /**
     * Verifies the parked STREAM_CONFIDENCE_MIN 0.55 -> 0.60 claim STRUCTURALLY rather than by
     * agreement of two aggregate scores: dumps every stream candidate the 77-PDF corpus produces
     * whose confidence lands in [0.50, 0.65), so the emptiness of the [0.55, 0.60) band -- the
     * reason the raise cannot move a single corpus document -- is visible rather than inferred.
     */
    @Test
    void corpusConfidenceBand() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("fpCorpusConf"), "set -DfpCorpusConf=true");
        StringBuilder notes = new StringBuilder();
        List<BakeOffHarness.ScoreUnit> units = BakeOffHarness.buildScoringSet(notes).units;
        GutterFinder breuel = new BreuelGutterFinder();
        int inBand = 0, near = 0, total = 0;
        StringBuilder sb = new StringBuilder();
        for (BakeOffHarness.ScoreUnit u : units) {
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                    List<TextPosition> g = TableTestPdfs.harvestGlyphs(doc, p - 1);
                    if (g.isEmpty() || g.size() > StreamTableExtractor.MAX_STREAM_GLYPHS) continue;
                    List<StreamTableExtractor.Candidate> sink = new ArrayList<>();
                    StreamTableExtractor.extractPage(p, g, breuel,
                            StreamTableExtractor.PageFrame.IDENTITY,
                            StreamTableExtractor.PRODUCTION_BAR,
                            StreamTableExtractor.PRODUCTION_BAR, sink);
                    for (StreamTableExtractor.Candidate c : sink) {
                        if (c.grid.hardReject != null) continue;      // gated out, not graded
                        total++;
                        double f = c.confidence;
                        if (f >= 0.55 && f < 0.60) { inBand++;
                            sb.append(String.format(Locale.ROOT, "  IN-BAND %s p%d conf=%.6f %dx%d%n",
                                    u.id(), p, f, c.grid.nRows, c.grid.nCols)); }
                        else if (f >= 0.50 && f < 0.65) { near++;
                            sb.append(String.format(Locale.ROOT, "  near    %s p%d conf=%.6f %dx%d%n",
                                    u.id(), p, f, c.grid.nRows, c.grid.nCols)); }
                    }
                }
            } catch (Throwable ignored) { /* unreadable corpus file: not this probe's subject */ }
        }
        System.out.printf(Locale.ROOT,
                "CORPUS CONFIDENCE BAND over %d graded stream candidates on %d documents:%n"
                + "  candidates in [0.55,0.60) = %d   (the band the raise would newly reject)%n"
                + "  candidates in [0.50,0.65) but outside it = %d%n%s",
                total, units.size(), inBand, near, sb);
    }

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("fpChar"), "set -DfpChar=true");
        boolean all = Boolean.getBoolean("fpAll");
        boolean dump = Boolean.getBoolean("fpDump");
        List<Path> files = all ? allProsePdfs() : sample(allProsePdfs(), 200);
        line("FP CHARACTERISATION -- %d files (%s)", files.size(), all ? "FULL POPULATION" : "200 stride sample");

        int off = 0, on = 0, added = 0;
        List<String> addedNames = new ArrayList<>();
        // per-candidate rows for the aggregate distribution
        List<double[]> addedCands = new ArrayList<>();   // conf, rows, cols, numCols, numDataCols, fill, populated, tCC, tV, tP, tCnt, tN, tPCF
        for (Path p : files) {
            Doc d = examine(p);
            if (d == null) continue;
            if (d.offTables > 0) off++;
            if (d.onTables > 0) on++;
            if (d.offTables == 0 && d.onTables > 0) {
                added++;
                addedNames.add(p.getFileName().toString());
                if (dump) dumpDoc(p, d);
                for (Cand c : d.streamPassing) addedCands.add(c.vec());
            }
        }
        line("");
        line("  flag OFF  %d/%d = %.4f", off, files.size(), off / (double) files.size());
        line("  flag ON   %d/%d = %.4f", on, files.size(), on / (double) files.size());
        line("  ADDED by the stream path: %d docs", added);
        line("  added docs: %s", String.join(" ", addedNames));

        line("");
        line("AGGREGATE over every PASSING stream candidate on an ADDED-FP document (%d candidates):",
                addedCands.size());
        line("  %-8s %5s %5s %5s %5s %6s %6s %6s %6s %6s %6s %6s %6s",
                "conf", "rows", "cols", "nCol", "nDat", "fill", "pop", "tCC", "tViol", "tPros", "tCnt", "tNum", "tPCF");
        addedCands.sort(Comparator.comparingDouble(v -> v[0]));
        for (double[] v : addedCands) {
            line("  %-8.4f %5.0f %5.0f %5.0f %5.0f %6.3f %6.0f %6.3f %6.3f %6.3f %6.3f %6.3f %6.3f",
                    v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12]);
        }
        // Which single sufficient condition would have removed each candidate?
        line("");
        line("COUNTERFACTUAL: how many of those %d candidates each candidate rule removes",
                addedCands.size());
        line("  conf < 0.60                      : %d", count(addedCands, v -> v[0] < 0.60));
        line("  conf < 0.65                      : %d", count(addedCands, v -> v[0] < 0.65));
        line("  conf < 0.70                      : %d", count(addedCands, v -> v[0] < 0.70));
        line("  conf < 0.75                      : %d", count(addedCands, v -> v[0] < 0.75));
        line("  cols <= 3 and no numeric col     : %d", count(addedCands, v -> v[2] <= 3 && v[3] == 0 && v[4] == 0));
        line("  cols <= 4 and no numeric col     : %d", count(addedCands, v -> v[2] <= 4 && v[3] == 0 && v[4] == 0));
        line("  no numeric col at all            : %d", count(addedCands, v -> v[3] == 0 && v[4] == 0));
        line("  rows < 4                         : %d", count(addedCands, v -> v[1] < 4));
        line("  rows < 5                         : %d", count(addedCands, v -> v[1] < 5));
        line("  populated cells < 8              : %d", count(addedCands, v -> v[6] < 8));
        line("  populated cells < 12             : %d", count(addedCands, v -> v[6] < 12));
        line("  fill density < 0.50              : %d", count(addedCands, v -> v[5] < 0.50));
        line("  fill density < 0.60              : %d", count(addedCands, v -> v[5] < 0.60));
        line("  fill density < 0.70              : %d", count(addedCands, v -> v[5] < 0.70));
        line("  tProse == 0 (medianFill>=0.85)    : %d", count(addedCands, v -> v[9] <= 1e-9));
        line("  tProse < 0.20                    : %d", count(addedCands, v -> v[9] < 0.20));
        line("  tProseColFrac > 0                : %d", count(addedCands, v -> v[12] > 0));
        line("  tViol < 1.0 (any straddling)     : %d", count(addedCands, v -> v[8] < 1.0 - 1e-9));

        Path o = Path.of(System.getProperty("fpCharOut",
                "target/fp-char-" + (all ? "pop" : "s200") + ".txt"));
        Files.createDirectories(o.getParent());
        Files.writeString(o, out.toString());
        System.out.println(out);
        System.out.println("written to " + o.toAbsolutePath());
    }

    private static int count(List<double[]> l, java.util.function.Predicate<double[]> p) {
        int n = 0; for (double[] v : l) if (p.test(v)) n++; return n;
    }

    // ------------------------------------------------------------------------------- per-document

    private static final class Cand {
        double conf; int rows, cols, nNumCols, nNumDataCols; double fill; int populated;
        double tCC, tV, tP, tCnt, tN, tPCF;
        List<List<String>> text;
        double[] vec() {
            return new double[]{conf, rows, cols, nNumCols, nNumDataCols, fill, populated,
                                tCC, tV, tP, tCnt, tN, tPCF};
        }
    }

    private static final class Doc {
        int offTables, onTables;
        List<Integer> pages;
        List<Cand> streamPassing = new ArrayList<>();
    }

    private Doc examine(Path pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (doc.getNumberOfPages() < 1) return null;
            List<Integer> pages = shippingPages(doc);
            Map<Integer, List<TextPosition>> byPage = new LinkedHashMap<>();
            for (int p : pages) byPage.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
            Doc d = new Doc();
            d.pages = pages;
            d.offTables = TableExtractor.extract(doc, pages, byPage, false).tables.size();
            d.onTables = TableExtractor.extract(doc, pages, byPage, true).tables.size();
            if (d.offTables == 0 && d.onTables > 0) {
                GutterFinder breuel = new BreuelGutterFinder();
                for (int p : pages) {
                    List<StreamTableExtractor.Candidate> sink = new ArrayList<>();
                    StreamTableExtractor.extractPage(p, byPage.get(p), breuel,
                            StreamTableExtractor.PageFrame.IDENTITY,
                            StreamTableExtractor.PRODUCTION_BAR,
                            StreamTableExtractor.PRODUCTION_BAR, sink);
                    for (StreamTableExtractor.Candidate c : sink) {
                        if (!c.passed || c.hit == null) continue;
                        d.streamPassing.add(toCand(c));
                    }
                }
            }
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Cand toCand(StreamTableExtractor.Candidate c) {
        Cand k = new Cand();
        k.conf = c.confidence;
        k.rows = c.hit.rowCount; k.cols = c.hit.colCount;
        k.nNumCols = c.grid.nNumericCols; k.nNumDataCols = c.grid.nNumericDataCols;
        k.tCC = c.grid.tColConsistency; k.tV = c.grid.tViolation; k.tP = c.grid.tProse;
        k.tCnt = c.grid.tColCount; k.tN = c.grid.tNumeric; k.tPCF = c.grid.tProseColFrac;
        int pop = 0, tot = 0;
        for (List<String> r : c.hit.rows) for (String s : r) {
            tot++; if (s != null && !s.isBlank()) pop++;
        }
        k.populated = pop;
        k.fill = tot == 0 ? 0 : pop / (double) tot;
        k.text = c.hit.rows;
        return k;
    }

    private void dumpDoc(Path p, Doc d) {
        line("");
        line("================================================================================");
        line("ADDED FP  %s   pages=%s  off=%d on=%d  passing stream candidates=%d",
                p.getFileName(), d.pages, d.offTables, d.onTables, d.streamPassing.size());
        for (Cand k : d.streamPassing) {
            line("  cand conf=%.4f %dx%d numCols=%d numDataCols=%d fill=%.3f pop=%d",
                    k.conf, k.rows, k.cols, k.nNumCols, k.nNumDataCols, k.fill, k.populated);
            line("       terms: colConsist=%.3f viol=%.3f prose=%.3f colCount=%.3f numeric=%.3f proseColFrac=%.3f",
                    k.tCC, k.tV, k.tP, k.tCnt, k.tN, k.tPCF);
            int shown = 0;
            for (List<String> r : k.text) {
                if (shown++ >= 12) { line("       ... (%d more rows)", k.text.size() - 12); break; }
                line("       | %s", r.stream().map(s -> s == null ? "" : s.replace('\n', ' '))
                        .collect(Collectors.joining(" | ")));
            }
        }
    }

    // ------------------------------------------------------------------------------------ plumbing

    /** Production's own default page selection, reached reflectively exactly as BaselineHarness does. */
    @SuppressWarnings("unchecked")
    static List<Integer> shippingPages(PDDocument doc) {
        try {
            java.lang.reflect.Constructor<PdfTitanArumApp> ctor =
                    PdfTitanArumApp.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object app = ctor.newInstance();
            java.lang.reflect.Method compute = PdfTitanArumApp.class
                    .getDeclaredMethod("computePagesToProcess", String.class, int.class);
            java.lang.reflect.Method classify = PdfTitanArumApp.class
                    .getDeclaredMethod("classifyBlankPages", PDDocument.class);
            java.lang.reflect.Method fill = PdfTitanArumApp.class
                    .getDeclaredMethod("fillBlankPages", List.class, Set.class, int.class);
            compute.setAccessible(true); classify.setAccessible(true); fill.setAccessible(true);
            int pageCount = doc.getNumberOfPages();
            List<Integer> pages = (List<Integer>) compute.invoke(app, "default", pageCount);
            Set<Integer> blank = (Set<Integer>) classify.invoke(app, doc);
            return blank.isEmpty() ? pages : (List<Integer>) fill.invoke(app, pages, blank, pageCount);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot reach production's own page selection", e);
        }
    }

    static List<Path> allProsePdfs() throws IOException {
        List<Path> all;
        try (Stream<Path> s = Files.list(PHISH_ROOT)) {
            all = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        List<Path> pdfs = new ArrayList<>();
        for (Path p : all) if (looksLikePdf(p)) pdfs.add(p);
        return pdfs;
    }

    /** The SAME stride rule BakeOffHarness.sampleProsePdfs uses, so the 200 here is the tracked 200. */
    static List<Path> sample(List<Path> pdfs, int cap) {
        if (pdfs.size() <= cap) return pdfs;
        int step = (int) Math.ceil(pdfs.size() / (double) cap);
        List<Path> s = new ArrayList<>();
        for (int i = 0; i < pdfs.size() && s.size() < cap; i += step) s.add(pdfs.get(i));
        return s;
    }

    private static boolean looksLikePdf(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[5];
            int n = in.read(buf);
            return n >= 4 && buf[0] == '%' && buf[1] == 'P' && buf[2] == 'D' && buf[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }
}
