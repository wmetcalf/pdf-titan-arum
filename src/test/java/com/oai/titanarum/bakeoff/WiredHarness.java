// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/ (matching
// BaselineHarness / BakeOffHarness / MetricFixHarness's own convention -- see their headers), but
// declares `package com.oai.titanarum;` because it needs direct access to package-private production
// types (TableExtractor, StreamTableExtractor, BreuelGutterFinder) and to the package-private test
// helpers TableTestPdfs.harvestGlyphs / MetricFixHarness / BakeOffHarness.
//
// PURPOSE. BaselineHarness's headline "full+arbitration" number is a measurement of an EXPRESSION
// the harness builds itself:
//
//     TableExtractor.arbitrate(TableExtractor.extract(doc, pages, harvestGlyphs(doc)).tables,
//                              per-page StreamTableExtractor.extractPage(...))
//
// Nothing in production computed that expression: extract() never ran the stream stage and
// arbitrate() had no production caller. The wiring change this harness accompanies makes
// extract(doc, pages, glyphs, /*streamTables=*/true) compute it. This harness answers, with real
// numbers and no smoothing, THREE separate questions that the headline number silently conflates:
//
//   Q1  Does the wired production call produce the SAME candidate list, candidate for candidate,
//       as the harness's expression -- given the SAME glyphs? (If not, the published number does not
//       describe the shipping pipeline at all.)
//
//   Q2  Production does NOT feed extract() the glyphs the harness feeds it. The harness uses a bare
//       PDFTextStripper with default settings (TableTestPdfs.harvestGlyphs); PdfTitanArumApp uses
//       PositionAwareTextStripper with setSortByPosition(TRUE), keeps only glyphs with a non-empty
//       unicode, and collapses consecutive duplicate references. Does that difference move the
//       score? This applies to the tagged+lattice paths too, so it is a question about EVERY number
//       this project has published, not only the stream ones.
//
//   Q3  What does the ACTUAL shipping pipeline score -- the CLI, end to end, scored from the
//       report.json it writes -- with the flag off and with the flag on?
//
// Q3 is the only one of the three that is a claim about the product. It is measured by running the
// real app in-process and deserializing its report.json back into TableHits, so no extraction code
// is re-implemented here.
//
// Gated by -Dwired=true AND named so Surefire's default includes never discover it. Run:
//   mvn -q -o test -Dtest=WiredHarness -Dwired=true
// Optionally -DwiredOut=<path> (default target/wired-report.md).
package com.oai.titanarum;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.GtDedup;
import com.oai.titanarum.bakeoff.TableScore;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

class WiredHarness {

    private final StringBuilder out = new StringBuilder();

    private void line(String fmt, Object... args) {
        String s = args.length == 0 ? fmt : String.format(Locale.ROOT, fmt, args);
        System.out.println(s);
        out.append(s).append('\n');
    }

    private void rule() { line("=".repeat(88)); }

    // ------------------------------------------------------------------------------- glyph sources

    /**
     * The glyph list {@link PdfTitanArumApp} actually hands {@link TableExtractor#extract}, rebuilt
     * here because {@code PositionAwareTextStripper} is private to that class. Equivalent by
     * construction: that stripper appends one entry per unicode CHARACTER of every position with a
     * non-empty unicode (skipping the rest), and {@code dedupeConsecutiveTextPositionRefs} then
     * collapses consecutive identical references -- so the net list is exactly "every position with
     * a non-empty unicode, once, in stripper order". The load-bearing difference from
     * {@link TableTestPdfs#harvestGlyphs} is {@code setSortByPosition(true)}, which changes the
     * ORDER glyphs arrive in and therefore the text the word-builder and the cell-filler assemble.
     */
    private static List<TextPosition> productionGlyphs(PDDocument doc, int pageNum) throws Exception {
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

    // ------------------------------------------------------------------------------------ scoring

    /** Micro sums plus the per-document F1 list MACRO is the mean of. Same shape and same formulas
     *  as {@code BaselineHarness.Acc} -- deliberately, so the numbers are comparable. */
    private static final class Acc {
        long matched, detected, gt;
        final List<Double> perDocF1 = new ArrayList<>();

        void addDoc(long m, long d, long g) {
            matched += m; detected += d; gt += g;
            double p = d == 0 ? 0.0 : (double) m / d;
            double r = g == 0 ? 0.0 : (double) m / g;
            perDocF1.add(m == 0 ? 0.0 : 2 * p * r / (p + r));
        }
        double macro() {
            return perDocF1.isEmpty() ? 0.0
                    : perDocF1.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
        double microF1() {
            double p = detected == 0 ? 0.0 : (double) matched / detected;
            double r = gt == 0 ? 0.0 : (double) matched / gt;
            return (p + r) == 0 ? 0.0 : 2 * p * r / (p + r);
        }
    }

    /** Per-document F1 from a {matched, detected, gt} triple -- the exact quantity MACRO averages. */
    private static double f1(long[] t) {
        double p = t[1] == 0 ? 0.0 : (double) t[0] / t[1];
        double r = t[2] == 0 ? 0.0 : (double) t[0] / t[2];
        return t[0] == 0 ? 0.0 : 2 * p * r / (p + r);
    }

    private static List<TableScore.Relation> rels(List<TableScore.GridCell> cells) {
        return TableScore.buildOfficialRelations(cells, false).relations();
    }

    /** END-TO-END, DOCUMENT-POOLED, exactly {@code BaselineHarness.e2ePooled}. */
    private static long[] pooled(List<TableExtractor.TableHit> hits, List<GroundTruth.Table> expected) {
        List<TableScore.Relation> gt = new ArrayList<>();
        for (GroundTruth.Table exp : expected) {
            gt.addAll(rels(TableScore.gridCellsFromGroundTruth(exp)));
        }
        List<TableScore.Relation> det = new ArrayList<>();
        for (TableExtractor.TableHit h : hits) det.addAll(rels(MetricFixHarness.cellsOf(h)));
        TableScore.AdjResult r =
                TableScore.compareRelations(gt, det, TableScore.Semantics.MULTISET);
        return new long[]{r.matched(), r.detectedTotal(), r.gtTotal()};
    }

    /** Value signature of a hit -- every field a report.json consumer can see. */
    private static String sig(TableExtractor.TableHit t) {
        return t.page + "|" + t.extractionMethod + "|" + t.rowCount + "x" + t.colCount + "|"
                + (t.bbox == null ? "nobbox" : bboxStr(t.bbox)) + "|" + t.rows;
    }

    /** bbox rounded to 0.01pt: report.json round-trips floats through decimal text, so an exact
     *  float comparison would report a formatting artifact as a pipeline difference. */
    private static String bboxStr(float[] b) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(String.format(Locale.ROOT, "%.2f", b[i]));
        }
        return sb.append(']').toString();
    }

    private static Set<String> sigSet(List<TableExtractor.TableHit> hits) {
        Set<String> s = new HashSet<>();
        for (TableExtractor.TableHit h : hits) s.add(sig(h));
        return s;
    }

    // --------------------------------------------------------------------------------- the harness

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("wired"), "set -Dwired=true to run");

        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        List<BakeOffHarness.ScoreUnit> units = corpus.units;
        rule();
        line("WIRED-PIPELINE VERIFICATION -- %d scoring units (%d ICDAR + %d CSV-matched)",
                units.size(), corpus.icdarCount, corpus.csvCount);
        line("PRIMARY: ICDAR 2013 adjacency-relation F1, end-to-end, DOCUMENT-POOLED, de-duplicated");
        line("ground truth, MACRO first. Identical protocol and identical scoring code to");
        line("BaselineHarness -- nothing under bakeoff/ (TableScore, GroundTruth, GtDedup) is touched.");
        rule();

        Acc harnessExpr = new Acc();     // arbitrate(extract(3-arg), stream)  -- harness glyphs
        Acc wiredHarnessGlyphs = new Acc();   // extract(4-arg, true)          -- harness glyphs
        Acc wiredProdGlyphs = new Acc();      // extract(4-arg, true)          -- production glyphs
        Acc ruledHarnessGlyphs = new Acc();   // extract(3-arg)                -- harness glyphs
        Acc ruledProdGlyphs = new Acc();      // extract(3-arg)                -- production glyphs

        int q1Mismatch = 0, q2Mismatch = 0, errors = 0;
        List<String> q1Detail = new ArrayList<>();
        List<String> q2Detail = new ArrayList<>();

        for (BakeOffHarness.ScoreUnit unit : units) {
            List<GroundTruth.Table> raw = unit.expected();
            List<GroundTruth.Table> kept = GtDedup.dedup(raw).kept();
            try (PDDocument doc = Loader.loadPDF(unit.pdf().toFile())) {
                int pages = doc.getNumberOfPages();
                List<Integer> pageList = new ArrayList<>();
                Map<Integer, List<TextPosition>> hg = new LinkedHashMap<>();
                Map<Integer, List<TextPosition>> pg = new LinkedHashMap<>();
                for (int p = 1; p <= pages; p++) {
                    pageList.add(p);
                    hg.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
                    pg.put(p, productionGlyphs(doc, p));
                }

                // --- the harness's own expression, harness glyphs ---
                List<TableExtractor.TableHit> ruledH =
                        new ArrayList<>(TableExtractor.extract(doc, pageList, hg).tables);
                List<TableExtractor.TableHit> streamH = new ArrayList<>();
                for (int p : pageList) {
                    streamH.addAll(StreamTableExtractor.extractPage(
                            p, hg.get(p), new BreuelGutterFinder()));
                }
                List<TableExtractor.TableHit> expr = TableExtractor.arbitrate(ruledH, streamH);

                // --- the wired production call, SAME glyphs (Q1) ---
                List<TableExtractor.TableHit> wiredH =
                        TableExtractor.extract(doc, pageList, hg, true).tables;

                // --- the wired production call, PRODUCTION glyphs (Q2) ---
                List<TableExtractor.TableHit> wiredP =
                        TableExtractor.extract(doc, pageList, pg, true).tables;
                List<TableExtractor.TableHit> ruledP =
                        new ArrayList<>(TableExtractor.extract(doc, pageList, pg).tables);

                if (!sigSet(expr).equals(sigSet(wiredH))) {
                    q1Mismatch++;
                    q1Detail.add(unit.id() + ": expr=" + expr.size() + " wired=" + wiredH.size());
                }
                if (!sigSet(wiredH).equals(sigSet(wiredP))) {
                    q2Mismatch++;
                    long[] fh = pooled(wiredH, kept);
                    long[] fp = pooled(wiredP, kept);
                    q2Detail.add(String.format(Locale.ROOT,
                            "%s: harnessGlyphs %d hits F1=%.4f -> prodGlyphs %d hits F1=%.4f (%+.4f)",
                            unit.id(), wiredH.size(), f1(fh), wiredP.size(), f1(fp),
                            f1(fp) - f1(fh)));
                }

                long[] a = pooled(expr, kept);       harnessExpr.addDoc(a[0], a[1], a[2]);
                long[] b = pooled(wiredH, kept);     wiredHarnessGlyphs.addDoc(b[0], b[1], b[2]);
                long[] c = pooled(wiredP, kept);     wiredProdGlyphs.addDoc(c[0], c[1], c[2]);
                long[] d = pooled(ruledH, kept);     ruledHarnessGlyphs.addDoc(d[0], d[1], d[2]);
                long[] e = pooled(ruledP, kept);     ruledProdGlyphs.addDoc(e[0], e[1], e[2]);
            } catch (Throwable t) {
                errors++;
                line("  MEASUREMENT ERROR %s: %s", unit.id(), t);
            }
        }

        line("");
        line("Q1 -- IS THE WIRED CALL THE SAME COMPUTATION AS THE HARNESS EXPRESSION?");
        line("     (same document, same glyphs; compared candidate for candidate by value)");
        line("  documents whose candidate sets differ : %d / %d", q1Mismatch, units.size());
        for (String s : q1Detail) line("      %s", s);
        line("  MACRO, harness expression arbitrate(extract(3-arg), stream) : %.4f (micro %.4f)",
                harnessExpr.macro(), harnessExpr.microF1());
        line("  MACRO, wired extract(doc, pages, glyphs, true)              : %.4f (micro %.4f)",
                wiredHarnessGlyphs.macro(), wiredHarnessGlyphs.microF1());
        line("  delta                                                       : %+.4f",
                wiredHarnessGlyphs.macro() - harnessExpr.macro());

        line("");
        line("Q2 -- DOES PRODUCTION'S OWN GLYPH SOURCE MOVE THE SCORE?");
        line("     harness glyphs : bare PDFTextStripper, DEFAULT ordering, every TextPosition");
        line("     prod glyphs    : setSortByPosition(TRUE), non-empty-unicode positions only");
        line("     (this affects the tagged+lattice paths too -- it is not a stream-only question)");
        line("  documents whose wired candidate sets differ : %d / %d", q2Mismatch, units.size());
        for (String s : q2Detail) line("      %s", s);
        line("  MACRO wired, harness glyphs      : %.4f", wiredHarnessGlyphs.macro());
        line("  MACRO wired, production glyphs   : %.4f  (delta %+.4f)",
                wiredProdGlyphs.macro(), wiredProdGlyphs.macro() - wiredHarnessGlyphs.macro());
        line("  MACRO ruled-only, harness glyphs : %.4f", ruledHarnessGlyphs.macro());
        line("  MACRO ruled-only, prod glyphs    : %.4f  (delta %+.4f)",
                ruledProdGlyphs.macro(), ruledProdGlyphs.macro() - ruledHarnessGlyphs.macro());
        line("  measurement errors               : %d", errors);

        measureRealCli(units);
        measureProseFp();

        String path = System.getProperty("wiredOut", "target/wired-report.md");
        Path outPath = Path.of(path).toAbsolutePath().normalize();
        Files.createDirectories(outPath.getParent());
        Files.writeString(outPath, "```\n" + out + "```\n", StandardCharsets.UTF_8);
        System.out.println("Report also written to " + outPath);
    }

    // ------------------------------------------------------------------------- Q3: the real product

    /**
     * Q3. Runs the REAL CLI in-process over every scoring unit, with the flag off and then on, and
     * scores the tables it finds in the report.json the app wrote. Nothing about extraction is
     * re-implemented: the glyph source, the flag plumbing, the JSON serialization and the report
     * assembly are all the shipping ones.
     */
    private void measureRealCli(List<BakeOffHarness.ScoreUnit> units) throws Exception {
        line("");
        rule();
        line("Q3 -- THE ACTUAL PRODUCT: real CLI, scored from the report.json it writes");
        rule();
        ObjectMapper mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Path work = Files.createTempDirectory("wired-cli");
        // "all" isolates the wiring: the harness scores every page, so scoring a subset would mix a
        // page-selection effect into the comparison. "default" is the SHIPPING page selection
        // (first 4 pages plus the last), and is measured separately below because that -- not "all"
        // -- is what an unattended triage run actually does.
        Acc off = new Acc(), on = new Acc(), onDefaultPages = new Acc();
        int failures = 0, streamTablesEmitted = 0, docsWithStream = 0, multiPage = 0;
        try {
            int i = 0;
            for (BakeOffHarness.ScoreUnit unit : units) {
                List<GroundTruth.Table> kept = GtDedup.dedup(unit.expected()).kept();
                for (String cfg : new String[]{"off", "on", "on-default-pages"}) {
                    Path dir = work.resolve(cfg + "-" + (i++));
                    List<String> args = new ArrayList<>(List.of(
                            "--input", unit.pdf().toString(), "--output", dir.toString(),
                            "--pages", cfg.equals("on-default-pages") ? "default" : "all",
                            "--skip-screenshots", "--skip-images", "--skip-page-export", "--skip-qr"));
                    // Both arms are EXPLICIT. The "off" arm used to just omit the flag, which was
                    // only "off" while the CLI default was off; now that the default is ON, omitting
                    // it would silently measure the on-pipeline twice and report a delta of 0.0000.
                    args.add(cfg.equals("off") ? "--stream-tables=false" : "--stream-tables=true");
                    int exit = new CommandLine(new PdfTitanArumApp())
                            .execute(args.toArray(String[]::new));
                    Path report = dir.resolve("report.json");
                    if (exit != 0 || !Files.exists(report)) {
                        failures++;
                        continue;
                    }
                    JsonNode root = mapper.readTree(report.toFile());
                    JsonNode tablesNode = root.get("tables");
                    List<TableExtractor.TableHit> hits = new ArrayList<>();
                    if (tablesNode != null) {
                        for (JsonNode t : tablesNode) {
                            hits.add(mapper.treeToValue(t, TableExtractor.TableHit.class));
                        }
                    }
                    long[] s = pooled(hits, kept);
                    switch (cfg) {
                        case "off" -> {
                            off.addDoc(s[0], s[1], s[2]);
                            JsonNode pc = root.get("pageCount");
                            if (pc != null && pc.asInt() > 5) multiPage++;
                        }
                        case "on" -> {
                            on.addDoc(s[0], s[1], s[2]);
                            long n = hits.stream()
                                    .filter(h -> "stream".equals(h.extractionMethod)).count();
                            streamTablesEmitted += (int) n;
                            if (n > 0) docsWithStream++;
                        }
                        default -> onDefaultPages.addDoc(s[0], s[1], s[2]);
                    }
                }
            }
        } finally {
            deleteRecursively(work);
        }
        line("  CLI runs that failed to produce a report.json : %d", failures);
        line("  MACRO, --pages all, --stream-tables=false : %.4f (micro %.4f)", off.macro(), off.microF1());
        line("  MACRO, --pages all, --stream-tables=true  : %.4f (micro %.4f)", on.macro(), on.microF1());
        line("  delta                                     : %+.4f", on.macro() - off.macro());
        line("  stream tables actually emitted            : %d across %d of %d documents",
                streamTablesEmitted, docsWithStream, units.size());
        line("");
        line("  SHIPPING page selection (--pages default = first 4 + last), --stream-tables=true:");
        line("  MACRO                                     : %.4f (micro %.4f)",
                onDefaultPages.macro(), onDefaultPages.microF1());
        line("  delta vs --pages all                      : %+.4f",
                onDefaultPages.macro() - on.macro());
        line("  corpus documents with >5 pages         : %d of %d", multiPage, units.size());
    }

    // -------------------------------------------------------------- FULL-pipeline prose FP rate

    /**
     * FULL-PIPELINE false-positive rate: the share of real-world prose PDFs where the whole pipeline
     * emits at least one table on page 1. Measured through the wired production {@code extract},
     * with production glyphs, flag off and flag on -- NOT the stream path alone (that number has
     * been quoted for the full pipeline before, and it is not the same number).
     */
    private void measureProseFp() throws Exception {
        line("");
        rule();
        line("FULL-PIPELINE PROSE FALSE-POSITIVE RATE (page 1, >=1 table emitted)");
        rule();
        List<Path> prose = BakeOffHarness.sampleProsePdfs();
        if (prose == null || prose.isEmpty()) {
            line("  prose corpus unavailable -- NOT measured this run.");
            return;
        }
        int off = 0, on = 0, streamOnly = 0, truncOn = 0, unreadable = 0;
        int maxStreamPerPage = 0;
        for (Path p : prose) {
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                if (doc.getNumberOfPages() < 1) continue;
                Map<Integer, List<TextPosition>> pg = new LinkedHashMap<>();
                pg.put(1, productionGlyphs(doc, 1));
                TableExtractor.Result rOff = TableExtractor.extract(doc, List.of(1), pg, false);
                TableExtractor.Result rOn = TableExtractor.extract(doc, List.of(1), pg, true);
                if (!rOff.tables.isEmpty()) off++;
                if (!rOn.tables.isEmpty()) on++;
                long ns = rOn.tables.stream()
                        .filter(h -> "stream".equals(h.extractionMethod)).count();
                if (ns > 0) streamOnly++;
                maxStreamPerPage = (int) Math.max(maxStreamPerPage, ns);
                if (rOn.truncated) truncOn++;
            } catch (Throwable t) {
                unreadable++;   // conservatively counts as "no table", same as BaselineHarness
            }
        }
        line("  sample size                                  : %d", prose.size());
        line("  unreadable (counted as no table)             : %d", unreadable);
        line("  FULL pipeline, flag OFF (tagged+lattice)     : %d/%d = %.4f",
                off, prose.size(), off / (double) prose.size());
        line("  FULL pipeline, flag ON  (+stream+arbitration) : %d/%d = %.4f",
                on, prose.size(), on / (double) prose.size());
        line("  delta                                        : %+.4f",
                (on - off) / (double) prose.size());
        line("  documents where a STREAM table was emitted   : %d/%d = %.4f",
                streamOnly, prose.size(), streamOnly / (double) prose.size());
        line("  max stream candidates emitted on one page    : %d", maxStreamPerPage);
        line("  documents flagged tablesTruncated (flag on)  : %d", truncOn);

        // ADVERSARIAL SELF-CHECK on the number just printed. Every prose false-positive figure this
        // project has published is PAGE 1 ONLY. The shipping page selection is first 4 pages plus the
        // last, so a real triage run gives the extractor ~5x as many chances to emit a spurious
        // table. Measured here so the honest number is on the record, not the flattering one.
        int offSel = 0, onSel = 0, streamSel = 0, pagesSeen = 0;
        for (Path p : prose) {
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                int n = doc.getNumberOfPages();
                List<Integer> sel = new ArrayList<>();
                for (int i = 1; i <= Math.min(4, n); i++) sel.add(i);
                if (n > 4) sel.add(n);
                if (sel.isEmpty()) continue;
                pagesSeen += sel.size();
                Map<Integer, List<TextPosition>> g = new LinkedHashMap<>();
                for (int i : sel) g.put(i, productionGlyphs(doc, i));
                TableExtractor.Result rOff = TableExtractor.extract(doc, sel, g, false);
                TableExtractor.Result rOn = TableExtractor.extract(doc, sel, g, true);
                if (!rOff.tables.isEmpty()) offSel++;
                if (!rOn.tables.isEmpty()) onSel++;
                if (rOn.tables.stream().anyMatch(h -> "stream".equals(h.extractionMethod))) streamSel++;
            } catch (Throwable ignored) { }
        }
        line("");
        line("  SAME sample, SHIPPING page selection (first 4 + last; %d pages total, %.1f/doc):",
                pagesSeen, pagesSeen / (double) prose.size());
        line("  FULL pipeline, flag OFF                      : %d/%d = %.4f",
                offSel, prose.size(), offSel / (double) prose.size());
        line("  FULL pipeline, flag ON                       : %d/%d = %.4f",
                onSel, prose.size(), onSel / (double) prose.size());
        line("  delta                                        : %+.4f",
                (onSel - offSel) / (double) prose.size());
        line("  documents with >=1 STREAM table              : %d/%d = %.4f",
                streamSel, prose.size(), streamSel / (double) prose.size());
    }

    private static void deleteRecursively(Path p) {
        try (java.util.stream.Stream<Path> walk = Files.walk(p)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(q -> {
                try { Files.deleteIfExists(q); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}
