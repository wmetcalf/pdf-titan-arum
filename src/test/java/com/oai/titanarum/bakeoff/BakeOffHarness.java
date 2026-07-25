// NOTE ON PACKAGE: this file physically lives under src/test/java/com/oai/titanarum/bakeoff/ (per
// the Task 9b plan), but declares `package com.oai.titanarum;` (matching StreamTableExtractor,
// GutterFinder, and the four finder classes it drives). Those classes are all package-PRIVATE
// (BreuelGutterFinder, GapVotingGutterFinder, AlignmentEdgeGutterFinder,
// OccupancyProjectionGutterFinder, GutterFinder, StreamTableExtractor, and TableExtractor.TableHit
// via its package-private outer class TableExtractor) -- a class declared `package
// com.oai.titanarum.bakeoff` cannot see them at all, regardless of directory. javac/Maven compile
// units by their DECLARED package, not their directory, so this is legal and is the only way to
// reach the real pipeline (StreamTableExtractor.extractPage) without widening any production
// class's visibility just for a throwaway bake-off harness (see GutterFinder's own javadoc: "YAGNI
// -- the interface exists for the bake-off, not as permanent architecture"). GroundTruth and
// TableScore (the actually-reusable scoring apparatus) are public and imported normally from
// com.oai.titanarum.bakeoff below.
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.TableScore;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The bake-off harness for Task 9b: measures all four {@link GutterFinder} contenders against
 * real ground truth (tabula-java's expected CSVs + the ICDAR 2013 structure XML corpus) plus a
 * false-positive check against real-world prose PDFs, and writes {@code corpus/bakeoff-report.md}.
 *
 * <p>NOT part of the normal test suite: gated by the {@code bakeOff} system property (see
 * {@link #run()}), AND named so it does not match Surefire's default test-inclusion patterns
 * ({@code **&#47;Test*.java}, {@code **&#47;*Test.java}, {@code **&#47;*Tests.java}, {@code
 * **&#47;*TestCase.java}) -- "BakeOffHarness" matches none of them, so {@code mvn test} (no
 * explicit {@code -Dtest}) never even discovers it. Run explicitly:
 * {@code mvn -q test -Dtest=BakeOffHarness -DbakeOff=true}.
 */
class BakeOffHarness {

    // ------------------------------------------------------------ corpus roots (relative to the
    // Maven basedir, which is Surefire's forked-JVM working directory -- see GroundTruthTest's
    // own CORPUS_ROOT for the same convention).

    private static final Path TABULA_RESOURCES =
            Path.of("corpus/tabula-java/src/test/resources").toAbsolutePath().normalize();
    private static final Path ICDAR_ROOT =
            TABULA_RESOURCES.resolve("technology/tabula/icdar2013-dataset");
    private static final Path CSV_ROOT =
            TABULA_RESOURCES.resolve("technology/tabula/csv");
    private static final Path PHISH_ROOT = Path.of("/home/coz/Downloads/phishpdfs");
    private static final Path REPORT_PATH = Path.of("corpus/bakeoff-report.md");

    private static final int PROSE_SAMPLE_CAP = 200;

    // ------------------------------------------------------------------- one scoreable PDF unit

    /** One PDF to score, with the ground-truth table(s) expected on it (possibly >1, e.g. an
     *  ICDAR "a"+"b" structure-XML pair annotating the same underlying PDF). */
    // Task 9j diagnostic note: widened from `private` to package-private (ScoreUnit, PdfScore,
    // CorpusResult, buildScoringSet, scoreUnit below) so Diag9jHarness can reuse this EXACT
    // 77-PDF corpus-discovery + scoring logic byte-for-byte instead of re-deriving/duplicating
    // it (which would risk silently drifting from the real bake-off's own scoring set or
    // pairing policy). Pure visibility changes only -- no method body, field, or behavior was
    // touched, so BakeOffHarness's own `run()` output is unaffected (verified: reran
    // `-Dtest=BakeOffHarness -DbakeOff=true` after this change, byte-identical summary table).
    record ScoreUnit(String id, Path pdf, List<GroundTruth.Table> expected) {}

    // ------------------------------------------------------------------------- the actual test

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("bakeOff"), "set -DbakeOff=true to run");

        List<GutterFinder> finders = List.of(
                new BreuelGutterFinder(), new GapVotingGutterFinder(),
                new AlignmentEdgeGutterFinder(), new OccupancyProjectionGutterFinder());

        StringBuilder skipNotes = new StringBuilder();
        CorpusResult corpus = buildScoringSet(skipNotes);
        List<ScoreUnit> units = corpus.units;
        System.out.println("Bake-off scoring set: " + units.size() + " PDFs ("
                + corpus.icdarCount + " ICDAR + " + corpus.csvCount + " CSV-matched, "
                + corpus.overlapCount + " overlap)");

        // finderName -> aggregated stats
        Map<String, FinderAgg> aggs = new LinkedHashMap<>();
        // pdf id -> finderName -> F1 (for the discriminating-PDF / systematic-failure sections)
        Map<String, Map<String, Double>> f1Matrix = new TreeMap<>();
        // pdf id -> finderName -> adjacency F1 (same shape, second metric)
        Map<String, Map<String, Double>> adjF1Matrix = new TreeMap<>();

        for (GutterFinder finder : finders) {
            FinderAgg agg = new FinderAgg(finder.name());
            aggs.put(finder.name(), agg);
            for (ScoreUnit unit : units) {
                PdfScore score = scoreUnit(finder, unit);
                agg.tp += score.tp;
                agg.fp += score.fp;
                agg.fn += score.fn;
                agg.perPdfF1.add(score.f1);
                agg.dimsExactMatches += score.dimsExactMatches;
                agg.dimsExactTotal += score.pairedTables;
                if (score.detected) agg.detectedCount++;
                if (score.error != null) {
                    agg.errorCount++;
                    agg.errorPdfs.add(unit.id() + ": " + score.error);
                }
                agg.timesMs.add(score.elapsedMs);
                f1Matrix.computeIfAbsent(unit.id(), k -> new LinkedHashMap<>())
                        .put(finder.name(), score.f1);

                agg.adjMatched += score.adjMatched;
                agg.adjDetectedTotal += score.adjDetectedTotal;
                agg.adjGtTotal += score.adjGtTotal;
                agg.perPdfAdjF1.add(score.adjF1);
                adjF1Matrix.computeIfAbsent(unit.id(), k -> new LinkedHashMap<>())
                        .put(finder.name(), score.adjF1);
            }
        }

        // ---- prose false-positive rate ----
        List<Path> proseSample = null;
        try {
            proseSample = sampleProsePdfs();
        } catch (IOException e) {
            skipNotes.append("Prose FP sampling failed: ").append(e).append('\n');
        }
        if (proseSample != null) {
            System.out.println("Prose FP sample: " + proseSample.size() + " PDFs (page 1 only)");
            for (GutterFinder finder : finders) {
                int flagged = 0;
                for (Path p : proseSample) {
                    if (hasStreamTableOnPage1(finder, p)) flagged++;
                }
                aggs.get(finder.name()).proseFpRate = proseSample.isEmpty() ? Double.NaN
                        : flagged / (double) proseSample.size();
                aggs.get(finder.name()).proseSampleSize = proseSample.size();
            }
        } else {
            skipNotes.append("Prose corpus /home/coz/Downloads/phishpdfs not found -- "
                    + "prose_fp_rate section skipped.\n");
        }

        String report = buildReport(units, corpus, aggs, f1Matrix, adjF1Matrix, skipNotes.toString());
        Files.createDirectories(REPORT_PATH.getParent());
        Files.writeString(REPORT_PATH, report, StandardCharsets.UTF_8);

        System.out.println();
        System.out.println(summaryTable(aggs, finders, units.size()));
        System.out.println();
        System.out.println("Full report written to " + REPORT_PATH.toAbsolutePath());
    }

    // ------------------------------------------------------------------- scoring one (finder,pdf)

    record RunResult(List<TableExtractor.TableHit> hits, long elapsedNanos, String error) {}

    static RunResult runFinderOnPdf(GutterFinder finder, Path pdf) {
        long t0 = System.nanoTime();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.TableHit> hits = new ArrayList<>();
            int pages = doc.getNumberOfPages();
            for (int i = 0; i < pages; i++) {
                List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, i);
                hits.addAll(StreamTableExtractor.extractPage(i + 1, glyphs, finder));
            }
            return new RunResult(hits, System.nanoTime() - t0, null);
        } catch (Throwable t) {
            return new RunResult(List.of(), System.nanoTime() - t0,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    record PdfScore(int tp, int fp, int fn, double f1, int pairedTables,
                             int dimsExactMatches, boolean detected, String error, double elapsedMs,
                             int adjMatched, int adjDetectedTotal, int adjGtTotal, double adjF1) {}

    /**
     * Scores one (finder, PDF) cell per the bake-off protocol: run the full stream pipeline over
     * every page, greedily pair each expected table (in order) with whichever remaining actual hit
     * maximizes {@link TableScore#score}, consuming that hit; expected tables left unpaired (hits
     * ran out) score as all-false-negative; actual hits left over (expected ran out) add their
     * non-empty cell count as false positives (spurious tables).
     *
     * <p><b>Pairing policy:</b> the greedy pairing decision itself is made on EXACT-CELL F1 only
     * (unchanged from before this metric was added), so the exact-cell columns this harness
     * reports are byte-for-byte identical to the pre-adjacency baseline -- adding a second metric
     * must not silently change what the first one measures. The adjacency metric is then scored
     * against that SAME pairing (i.e. it never gets to pick a different partner for an expected
     * table than the exact-cell metric did), so both metrics describe the same head-to-head
     * table-vs-table correspondence.
     */
    static PdfScore scoreUnit(GutterFinder finder, ScoreUnit unit) {
        RunResult run = runFinderOnPdf(finder, unit.pdf());
        double elapsedMs = run.elapsedNanos() / 1_000_000.0;
        boolean detected = !run.hits().isEmpty();

        List<TableExtractor.TableHit> available = new ArrayList<>(run.hits());
        int tp = 0, fp = 0, fn = 0, paired = 0, dimsExact = 0;
        int adjMatched = 0, adjDetectedTotal = 0, adjGtTotal = 0;
        for (GroundTruth.Table expected : unit.expected()) {
            if (available.isEmpty()) {
                fn += nonEmptyCellCount(expected.rows());
                adjGtTotal += TableScore.relationCount(expected.rows());
                continue;
            }
            TableExtractor.TableHit best = null;
            TableScore.Result bestResult = null;
            double bestF1 = -1;
            for (TableExtractor.TableHit h : available) {
                TableScore.Result r = TableScore.score(expected, h.rows);
                if (r.f1() > bestF1) {
                    bestF1 = r.f1();
                    best = h;
                    bestResult = r;
                }
            }
            available.remove(best);
            tp += bestResult.truePositives();
            fp += bestResult.falsePositives();
            fn += bestResult.falseNegatives();
            paired++;
            if (bestResult.dimsExactMatch()) dimsExact++;

            TableScore.AdjResult adjResult = TableScore.scoreAdjacency(expected, best.rows);
            adjMatched += adjResult.matched();
            adjDetectedTotal += adjResult.detectedTotal();
            adjGtTotal += adjResult.gtTotal();
        }
        for (TableExtractor.TableHit h : available) {
            fp += nonEmptyCellCount(h.rows);
            adjDetectedTotal += TableScore.relationCount(h.rows);
        }

        double precision = (tp + fp) == 0 ? 0.0 : (double) tp / (tp + fp);
        double recall = (tp + fn) == 0 ? 0.0 : (double) tp / (tp + fn);
        double f1 = tp == 0 ? 0.0 : 2 * precision * recall / (precision + recall);

        double adjPrecision = adjDetectedTotal == 0 ? 0.0 : (double) adjMatched / adjDetectedTotal;
        double adjRecall = adjGtTotal == 0 ? 0.0 : (double) adjMatched / adjGtTotal;
        double adjF1 = adjMatched == 0 ? 0.0 : 2 * adjPrecision * adjRecall / (adjPrecision + adjRecall);

        return new PdfScore(tp, fp, fn, f1, paired, dimsExact, detected, run.error(), elapsedMs,
                adjMatched, adjDetectedTotal, adjGtTotal, adjF1);
    }

    /** Mirrors TableScore's own non-empty-cell dedup ((row,col,normalizedText) key set) so
     *  unpaired-table bookkeeping (FN for unmatched expected, FP for unmatched actual) counts
     *  cells exactly the same way {@link TableScore#score} does for paired ones. */
    private static int nonEmptyCellCount(List<List<String>> rows) {
        Set<String> cells = new HashSet<>();
        for (int r = 0; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                String norm = GroundTruth.normalizeCell(row.get(c));
                if (!norm.isEmpty()) cells.add(r + "|" + c + "|" + norm);
            }
        }
        return cells.size();
    }

    // ---------------------------------------------------------------------------- prose FP check

    private static List<Path> sampleProsePdfs() throws IOException {
        if (!Files.isDirectory(PHISH_ROOT)) return null;
        List<Path> all;
        try (Stream<Path> s = Files.list(PHISH_ROOT)) {
            all = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        List<Path> pdfs = new ArrayList<>();
        for (Path p : all) {
            if (looksLikePdf(p)) pdfs.add(p);
        }
        if (pdfs.isEmpty()) return pdfs;
        if (pdfs.size() <= PROSE_SAMPLE_CAP) return pdfs;
        int step = (int) Math.ceil(pdfs.size() / (double) PROSE_SAMPLE_CAP);
        List<Path> sample = new ArrayList<>();
        for (int i = 0; i < pdfs.size() && sample.size() < PROSE_SAMPLE_CAP; i += step) {
            sample.add(pdfs.get(i));
        }
        return sample;
    }

    /** Identifies a real PDF by the %PDF magic bytes in the first 5 bytes -- NOT by extension,
     *  since these real-world phishing-corpus files are stored with arbitrary (often {@code
     *  .bin}) extensions. */
    private static boolean looksLikePdf(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[5];
            int n = in.read(buf);
            return n >= 4 && buf[0] == '%' && buf[1] == 'P' && buf[2] == 'D' && buf[3] == 'F';
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean hasStreamTableOnPage1(GutterFinder finder, Path pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (doc.getNumberOfPages() < 1) return false;
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            return !StreamTableExtractor.extractPage(1, glyphs, finder).isEmpty();
        } catch (Throwable t) {
            return false; // unreadable/erroring prose file -> conservatively "no table produced"
        }
    }

    // --------------------------------------------------------------------- scoring-set discovery

    static final class CorpusResult {
        List<ScoreUnit> units = new ArrayList<>();
        int icdarCount, csvCount, overlapCount;
        List<String> csvSkipped = new ArrayList<>();     // no matching PDF at all
        List<String> icdarSkipped = new ArrayList<>();   // no matching PDF (should be empty)
        List<String> overlapDetails = new ArrayList<>();
    }

    /**
     * Discovers the scoreable PDFs and their ground truth. Two sources:
     * <ul>
     *   <li>ICDAR 2013 {@code *-str.xml} files under {@code icdar2013-dataset/**}, paired with
     *       {@code NAME.pdf} in the same directory. Four files in this corpus ({@code
     *       us-011b/us-031b/us-035b/eu-009b-str.xml}) have no {@code NAME.pdf} of their own -- they
     *       are a SECOND structure annotation of the SAME underlying PDF as their "a" sibling
     *       ({@code us-011a.pdf} etc; verified: {@code us-011b.pdf} does not exist, only {@code
     *       us-011a.pdf} does), so they fall back to {@code STEM+"a".pdf} and their tables are
     *       merged into that PDF's expected-table list rather than treated as a separate PDF. This
     *       collapses 71 str.xml files to 67 distinct ICDAR PDFs.</li>
     *   <li>tabula-java expected CSVs under {@code csv/*.csv}, paired by basename search anywhere
     *       under the resources tree. 4 of the 15 CSVs have no matching PDF at all and are skipped;
     *       {@code us-020.csv} matches two identically-named PDF paths (one under {@code
     *       technology/tabula/}, one duplicated inside the ICDAR corpus) which are byte-identical
     *       (verified via MD5) -- i.e. the SAME PDF is reachable via both the CSV and ICDAR ground
     *       truth. Any CSV-matched PDF whose content hash equals an already-collected ICDAR PDF's
     *       hash is dropped from the CSV set (its ICDAR-sourced ground truth is used instead, to
     *       avoid scoring the same physical PDF twice with two independently-authored ground
     *       truths, which would double-count one real table as two "expected" tables).</li>
     * </ul>
     */
    static CorpusResult buildScoringSet(StringBuilder notes) throws IOException {
        CorpusResult result = new CorpusResult();

        // ---- ICDAR ----
        List<Path> strXmls;
        try (Stream<Path> walk = Files.walk(ICDAR_ROOT)) {
            strXmls = walk.filter(p -> p.getFileName().toString().endsWith("-str.xml"))
                    .sorted().collect(Collectors.toList());
        }
        Map<Path, List<Path>> pdfToXmls = new LinkedHashMap<>(); // resolved pdf -> its str.xml file(s), in order
        for (Path xml : strXmls) {
            Path dir = xml.getParent();
            String base = xml.getFileName().toString();
            base = base.substring(0, base.length() - "-str.xml".length());
            Path candidate = dir.resolve(base + ".pdf");
            if (!Files.exists(candidate)) {
                char last = base.isEmpty() ? ' ' : base.charAt(base.length() - 1);
                if (Character.isLowerCase(last) && last != 'a') {
                    Path alt = dir.resolve(base.substring(0, base.length() - 1) + "a.pdf");
                    if (Files.exists(alt)) candidate = alt;
                }
            }
            if (!Files.exists(candidate)) {
                result.icdarSkipped.add(xml.toString() + " (no matching PDF found)");
                continue;
            }
            pdfToXmls.computeIfAbsent(candidate.toAbsolutePath().normalize(), k -> new ArrayList<>()).add(xml);
        }
        Map<String, String> icdarHashByPdf = new HashMap<>(); // absolute pdf path -> md5
        for (Map.Entry<Path, List<Path>> e : pdfToXmls.entrySet()) {
            Path pdf = e.getKey();
            List<GroundTruth.Table> expected = new ArrayList<>();
            for (Path xml : e.getValue()) {
                expected.addAll(GroundTruth.fromIcdarStructureXml(xml));
            }
            String id = TABULA_RESOURCES.relativize(pdf).toString();
            result.units.add(new ScoreUnit(id, pdf, expected));
            icdarHashByPdf.put(pdf.toString(), md5(pdf));
        }
        result.icdarCount = pdfToXmls.size();

        // ---- CSV ----
        Set<String> icdarHashes = new HashSet<>(icdarHashByPdf.values());
        List<Path> csvs;
        try (Stream<Path> s = Files.list(CSV_ROOT)) {
            csvs = s.filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .sorted().collect(Collectors.toList());
        }
        int csvKept = 0;
        for (Path csv : csvs) {
            String base = csv.getFileName().toString();
            base = base.substring(0, base.length() - ".csv".length());
            String wantedName = base + ".pdf";
            List<Path> matches;
            try (Stream<Path> walk = Files.walk(TABULA_RESOURCES)) {
                matches = walk.filter(p -> p.getFileName().toString().equalsIgnoreCase(wantedName))
                        .sorted().collect(Collectors.toList());
            }
            if (matches.isEmpty()) {
                result.csvSkipped.add(base + ".csv (no matching PDF found anywhere in resources)");
                continue;
            }
            Path pdf = matches.get(0).toAbsolutePath().normalize();
            String hash = md5(pdf);
            if (icdarHashes.contains(hash)) {
                result.overlapCount++;
                result.overlapDetails.add(base + ".csv -> " + TABULA_RESOURCES.relativize(pdf)
                        + " (byte-identical to an already-scored ICDAR PDF; ICDAR ground truth used instead)");
                continue;
            }
            GroundTruth.Table table = GroundTruth.fromCsv(csv);
            String id = TABULA_RESOURCES.relativize(pdf).toString();
            result.units.add(new ScoreUnit(id, pdf, List.of(table)));
            csvKept++;
        }
        result.csvCount = csvKept;

        if (!result.csvSkipped.isEmpty()) notes.append("CSV entries skipped (no PDF): ")
                .append(result.csvSkipped).append('\n');
        if (!result.icdarSkipped.isEmpty()) notes.append("ICDAR entries skipped (no PDF): ")
                .append(result.icdarSkipped).append('\n');

        return result;
    }

    private static String md5(Path file) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(Files.readAllBytes(file));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            throw new UncheckedIOException(e instanceof IOException io ? io : new IOException(e));
        }
    }

    // -------------------------------------------------------------------------- aggregation types

    private static final class FinderAgg {
        final String name;
        long tp, fp, fn;
        final List<Double> perPdfF1 = new ArrayList<>();
        int dimsExactMatches, dimsExactTotal;
        int detectedCount;
        int errorCount;
        final List<String> errorPdfs = new ArrayList<>();
        final List<Double> timesMs = new ArrayList<>();
        double proseFpRate = Double.NaN;
        int proseSampleSize;

        // ICDAR 2013 adjacency-relation metric (see TableScore#scoreAdjacency) -- a second,
        // translation-invariant view of the same runs, aggregated the same way (micro = summed
        // matched/detected/gt across every PDF; macro = mean of per-PDF adjacency F1).
        long adjMatched, adjDetectedTotal, adjGtTotal;
        final List<Double> perPdfAdjF1 = new ArrayList<>();

        FinderAgg(String name) { this.name = name; }

        double microPrecision() { return (tp + fp) == 0 ? 0.0 : (double) tp / (tp + fp); }
        double microRecall()    { return (tp + fn) == 0 ? 0.0 : (double) tp / (tp + fn); }
        double microF1() {
            double p = microPrecision(), r = microRecall();
            return (p + r) == 0 ? 0.0 : 2 * p * r / (p + r);
        }
        double macroF1() {
            return perPdfF1.isEmpty() ? 0.0 : perPdfF1.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
        double dimsExactRate() { return dimsExactTotal == 0 ? 0.0 : dimsExactMatches / (double) dimsExactTotal; }
        double detectionRate(int totalUnits) { return totalUnits == 0 ? 0.0 : detectedCount / (double) totalUnits; }
        double medianMs() { return percentile(timesMs, 50); }
        double p95Ms()    { return percentile(timesMs, 95); }

        double adjMicroPrecision() { return (adjDetectedTotal) == 0 ? 0.0 : (double) adjMatched / adjDetectedTotal; }
        double adjMicroRecall()    { return (adjGtTotal) == 0 ? 0.0 : (double) adjMatched / adjGtTotal; }
        double adjMicroF1() {
            double p = adjMicroPrecision(), r = adjMicroRecall();
            return (p + r) == 0 ? 0.0 : 2 * p * r / (p + r);
        }
        double adjMacroF1() {
            return perPdfAdjF1.isEmpty() ? 0.0
                    : perPdfAdjF1.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
    }

    private static double percentile(List<Double> valuesIn, double p) {
        if (valuesIn.isEmpty()) return Double.NaN;
        List<Double> v = new ArrayList<>(valuesIn);
        Collections.sort(v);
        int idx = (int) Math.ceil(p / 100.0 * v.size()) - 1;
        idx = Math.max(0, Math.min(v.size() - 1, idx));
        return v.get(idx);
    }

    // --------------------------------------------------------------------------------- reporting

    private static String summaryTable(Map<String, FinderAgg> aggs, List<GutterFinder> finders, int totalUnits) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-10s %6s %6s %6s %6s %8s %8s %8s %8s %9s %6s %4s %8s %8s %10s%n",
                "finder", "microP", "microR", "microF1", "macroF1",
                "adjP", "adjR", "adjF1", "adjMacF1",
                "dimsRate", "detRt", "err", "medMs", "p95Ms", "proseFP"));
        for (GutterFinder f : finders) {
            FinderAgg a = aggs.get(f.name());
            sb.append(String.format("%-10s %6.3f %6.3f %6.3f %6.3f %8.3f %8.3f %8.3f %8.3f %9.3f %6.3f %4d %8.1f %8.1f %10s%n",
                    a.name, a.microPrecision(), a.microRecall(), a.microF1(), a.macroF1(),
                    a.adjMicroPrecision(), a.adjMicroRecall(), a.adjMicroF1(), a.adjMacroF1(),
                    a.dimsExactRate(), a.detectionRate(totalUnits), a.errorCount,
                    a.medianMs(), a.p95Ms(),
                    Double.isNaN(a.proseFpRate) ? "n/a" : String.format("%.3f", a.proseFpRate)));
        }
        return sb.toString();
    }

    private static String buildReport(List<ScoreUnit> units, CorpusResult corpus,
                                       Map<String, FinderAgg> aggs,
                                       Map<String, Map<String, Double>> f1Matrix,
                                       Map<String, Map<String, Double>> adjF1Matrix,
                                       String notes) {
        List<GutterFinder> finders = List.of(
                new BreuelGutterFinder(), new GapVotingGutterFinder(),
                new AlignmentEdgeGutterFinder(), new OccupancyProjectionGutterFinder());

        StringBuilder md = new StringBuilder();
        md.append("# Stream-table gutter-finder bake-off report\n\n");
        md.append("Generated by `BakeOffHarness` (`mvn -q test -Dtest=BakeOffHarness -DbakeOff=true`).\n\n");

        md.append("## Scoring-set derivation\n\n");
        md.append("- ICDAR 2013 `*-str.xml` files found: collapsed to **").append(corpus.icdarCount)
                .append("** distinct PDFs (71 str.xml files total; 4 of them -- the `*b-str.xml` "
                        + "siblings for us-011/us-031/us-035/eu-009 -- have no PDF of their own and "
                        + "were merged into their `*a.pdf` sibling's expected-table list).\n");
        md.append("- tabula-java expected CSVs matched to a PDF: **").append(corpus.csvCount).append("**")
                .append(" (of 15 total; 4 have no matching PDF anywhere in the corpus, and ")
                .append(corpus.overlapCount).append(" overlapped byte-identically with an ICDAR PDF "
                        + "already counted above, so were dropped rather than double-scored).\n");
        md.append("- **Total scoring set: ").append(units.size()).append(" distinct PDFs** (")
                .append(corpus.icdarCount).append(" + ").append(corpus.csvCount).append(", ")
                .append(corpus.overlapCount).append(" overlap already excluded from the CSV count above).\n");
        md.append("- A prior agent's estimate was 77 (67 ICDAR + 11 CSV-matched, 1 overlap). ");
        if (units.size() == 77 && corpus.icdarCount == 67 && corpus.csvCount == 11 && corpus.overlapCount == 1) {
            md.append("This run reproduces that figure exactly.\n");
        } else {
            md.append("This run measured ").append(units.size()).append(" (")
                    .append(corpus.icdarCount).append(" ICDAR + ").append(corpus.csvCount)
                    .append(" CSV, ").append(corpus.overlapCount).append(" overlap) -- differs from the prior estimate; "
                            + "see the skip/overlap detail below for why.\n");
        }
        if (!notes.isEmpty()) {
            md.append("\n```\n").append(notes).append("```\n");
        }
        if (!corpus.overlapDetails.isEmpty()) {
            md.append("\nOverlap detail:\n");
            for (String s : corpus.overlapDetails) md.append("- ").append(s).append('\n');
        }

        md.append("\n## Summary (per finder, across the full scoring set)\n\n");
        md.append("```\n").append(summaryTable(aggs, finders, units.size())).append("```\n");
        md.append("\n`microP`/`microR`/`microF1`/`macroF1` are the EXACT-CELL metric ((row, col, "
                + "normalizedText) triples; see `TableScore#score`) -- unchanged from every prior "
                + "bake-off run, for direct comparability. `adjP`/`adjR`/`adjF1`/`adjMacF1` are the "
                + "ICDAR 2013 ADJACENCY-RELATION metric (`TableScore#scoreAdjacency`): translation- "
                + "invariant, compares nearest-non-empty-neighbour relations between cell CONTENTS "
                + "(RIGHT/DOWN, blanks skipped over) rather than absolute (row, col) position, so a "
                + "global row/column offset (e.g. one phantom leading row) costs almost nothing. "
                + "Both metrics are computed against the SAME greedy pairing, which is decided by "
                + "exact-cell F1 only (see `BakeOffHarness#scoreUnit` javadoc) -- adjacency never "
                + "gets to pick a different partner table. `dimsRate` = fraction of PAIRED tables "
                + "whose rowCount x colCount matched ground truth exactly. `detRt` = fraction of "
                + "PDFs where >=1 stream table was produced. `err` = count of (finder,PDF) runs that "
                + "threw. `proseFP` = fraction of the sampled real-world prose PDFs (page 1 only) "
                + "that yielded >=1 stream table -- see caveat below; n/a if the phishpdfs corpus was "
                + "absent.\n");
        md.append("\ndimsRate numerator/denominator per finder (paired tables = a PDF where the "
                + "greedy pairing matched >=1 expected table to an actual hit, whether or not that "
                + "match's cell content was any good):\n\n");
        for (FinderAgg a : aggs.values()) {
            md.append("- ").append(a.name).append(": ").append(a.dimsExactMatches)
                    .append('/').append(a.dimsExactTotal).append(" paired tables had exact rowCount x colCount\n");
        }
        if (aggs.values().stream().anyMatch(a -> a.proseSampleSize > 0)) {
            int n = aggs.values().iterator().next().proseSampleSize;
            md.append("\n**Prose FP caveat:** the ").append(n).append("-PDF sample from "
                    + "/home/coz/Downloads/phishpdfs is REAL-WORLD mail-attachment PDFs, not curated "
                    + "prose-only negatives -- some genuinely contain tables. `proseFP` is therefore "
                    + "an UPPER BOUND on false positives, not a pure false-positive rate; its value "
                    + "is only meaningful COMPARATIVELY across finders (same corpus, same sample), "
                    + "where a higher rate means more aggressive/less conservative detection.\n");
        }

        if (!corpus.icdarSkipped.isEmpty()) {
            md.append("\n### ICDAR entries skipped (no PDF found)\n\n");
            for (String s : corpus.icdarSkipped) md.append("- ").append(s).append('\n');
        }
        if (!corpus.csvSkipped.isEmpty()) {
            md.append("\n### CSV entries skipped (no PDF found)\n\n");
            for (String s : corpus.csvSkipped) md.append("- ").append(s).append('\n');
        }

        for (FinderAgg a : aggs.values()) {
            if (!a.errorPdfs.isEmpty()) {
                md.append("\n### Errors for finder `").append(a.name).append("`\n\n");
                for (String s : a.errorPdfs) md.append("- ").append(s).append('\n');
            }
        }

        md.append("\n## Per-PDF x per-finder F1 (exact-cell)\n\n");
        md.append("| PDF | breuel | gapvote | alignedge | occupancy |\n");
        md.append("|---|---|---|---|---|\n");
        for (Map.Entry<String, Map<String, Double>> e : f1Matrix.entrySet()) {
            md.append("| ").append(e.getKey());
            for (GutterFinder f : finders) {
                Double v = e.getValue().get(f.name());
                md.append(" | ").append(v == null ? "-" : String.format("%.3f", v));
            }
            md.append(" |\n");
        }

        md.append("\n## Per-PDF x per-finder F1 (adjacency-relation)\n\n");
        md.append("| PDF | breuel | gapvote | alignedge | occupancy |\n");
        md.append("|---|---|---|---|---|\n");
        for (Map.Entry<String, Map<String, Double>> e : adjF1Matrix.entrySet()) {
            md.append("| ").append(e.getKey());
            for (GutterFinder f : finders) {
                Double v = e.getValue().get(f.name());
                md.append(" | ").append(v == null ? "-" : String.format("%.3f", v));
            }
            md.append(" |\n");
        }

        md.append("\n## Discriminating PDFs (largest F1 spread across finders)\n\n");
        record Spread(String id, double spread, Map<String, Double> row) {}
        List<Spread> spreads = new ArrayList<>();
        for (Map.Entry<String, Map<String, Double>> e : f1Matrix.entrySet()) {
            Map<String, Double> row = e.getValue();
            double max = row.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
            double min = row.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
            spreads.add(new Spread(e.getKey(), max - min, row));
        }
        spreads.sort((x, y) -> Double.compare(y.spread(), x.spread()));
        md.append("| PDF | spread | breuel | gapvote | alignedge | occupancy |\n");
        md.append("|---|---|---|---|---|---|\n");
        for (Spread s : spreads.stream().limit(5).toList()) {
            md.append("| ").append(s.id()).append(" | ").append(String.format("%.3f", s.spread()));
            for (GutterFinder f : finders) {
                Double v = s.row().get(f.name());
                md.append(" | ").append(v == null ? "-" : String.format("%.3f", v));
            }
            md.append(" |\n");
        }

        md.append("\n## Systematic failures (every finder scored 0 F1)\n\n");
        List<String> allZero = spreads.stream()
                .filter(s -> s.row().values().stream().allMatch(v -> v == 0.0))
                .map(Spread::id).toList();
        if (allZero.isEmpty()) {
            md.append("None -- every PDF got a nonzero F1 from at least one finder.\n");
        } else {
            for (String id : allZero) md.append("- ").append(id).append('\n');
        }

        return md.toString();
    }
}
