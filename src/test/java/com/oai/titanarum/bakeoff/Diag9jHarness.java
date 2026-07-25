// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/ (task-9j diagnostic
// brief instructions), but declares `package com.oai.titanarum;` -- same trick BakeOffHarness and
// Diag9cHarness use, for the same reason (StreamTableExtractor, its Word/Line/Gutter/Grid types,
// GutterFinder, BreuelGutterFinder, and TableExtractor.TableHit/CellHit are all package-private).
//
// Task 9j: diagnoses WHY breuel's adjacency-F1 plateaus at ~0.50 on the 77-PDF ICDAR/tabula
// scoring set. Purely observational: does NOT modify StreamTableExtractor, TableScore, or
// GroundTruth's scoring logic. It DOES widen four BakeOffHarness members from `private` to
// package-private (ScoreUnit, PdfScore, RunResult, CorpusResult, buildScoringSet, scoreUnit,
// runFinderOnPdf) so this harness can reuse the EXACT SAME 77-PDF corpus discovery and greedy
// pairing/scoring policy byte-for-byte, rather than risk silently drifting from it by
// re-implementing it independently -- see BakeOffHarness's own comment at those members for the
// "verified: byte-identical summary table after widening" check.
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.TableScore;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class Diag9jHarness {

    private static final Path TABULA_RESOURCES =
            Path.of("corpus/tabula-java/src/test/resources").toAbsolutePath().normalize();
    private static final Path ICDAR_ROOT =
            TABULA_RESOURCES.resolve("technology/tabula/icdar2013-dataset");

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("diag9j"), "set -Ddiag9j=true to run");

        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        List<BakeOffHarness.ScoreUnit> units = corpus.units;
        System.out.println("Scoring set: " + units.size() + " PDFs ("
                + corpus.icdarCount + " ICDAR + " + corpus.csvCount + " CSV)");

        Map<String, List<Path>> xmlsByPdfId = icdarXmlsByPdfId();
        GutterFinder breuel = new BreuelGutterFinder();

        List<UnitDiag> diags = new ArrayList<>();
        for (BakeOffHarness.ScoreUnit unit : units) {
            BakeOffHarness.PdfScore score = BakeOffHarness.scoreUnit(breuel, unit);
            List<Path> xmls = xmlsByPdfId.get(unit.id());
            boolean hasSpan = xmls != null && anyHasRealSpan(xmls);
            boolean hasSpanAttrPresent = xmls != null && anyHasSpanAttrPresent(xmls);
            BakeOffHarness.RunResult ourRun = BakeOffHarness.runFinderOnPdf(breuel, unit.pdf());
            diags.add(new UnitDiag(unit, score, hasSpan, hasSpanAttrPresent, ourRun.hits().size()));
        }

        printH1(diags);
        printH2(diags);
        printH3(diags);
        printH4(diags);
        printH4b(diags, breuel);
        printH3b(diags, breuel);
    }

    private record UnitDiag(BakeOffHarness.ScoreUnit unit, BakeOffHarness.PdfScore score,
                             boolean hasSpan, boolean hasSpanAttrPresent, int ourHitCount) {}

    // ============================================================================== H1: spans

    private void printH1(List<UnitDiag> diags) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("H1: row/col SPANS in ground truth -- partition adjacency micro-F1");
        System.out.println("================================================================================");

        long spanN = diags.stream().filter(UnitDiag::hasSpan).count();
        long attrN = diags.stream().filter(UnitDiag::hasSpanAttrPresent).count();
        System.out.println("PDFs whose GT has a FUNCTIONAL span (end-row!=start-row or end-col!=start-col "
                + "for >=1 cell): " + spanN + " / " + diags.size());
        System.out.println("PDFs whose GT XML merely HAS an end-row/end-col attribute present anywhere "
                + "(may be a redundant no-op, e.g. end-col==start-col written explicitly): " + attrN + " / " + diags.size());

        Agg spanAgg = new Agg(), noSpanAgg = new Agg();
        for (UnitDiag d : diags) {
            (d.hasSpan() ? spanAgg : noSpanAgg).add(d.score());
        }
        System.out.println();
        System.out.println(String.format(Locale.ROOT,
                "  %-12s %6s  %8s %8s %8s %8s", "partition", "n", "adjP", "adjR", "adjF1(micro)", "adjF1(macro)"));
        System.out.println(fmtAgg("NO-span GT", noSpanAgg));
        System.out.println(fmtAgg("HAS-span GT", spanAgg));
        System.out.println();
        System.out.println("  gap (no-span microF1 - has-span microF1) = "
                + String.format(Locale.ROOT, "%.4f", noSpanAgg.microF1() - spanAgg.microF1()));
    }

    private static String fmtAgg(String label, Agg a) {
        return String.format(Locale.ROOT, "  %-12s %6d  %8.3f %8.3f %8.3f %8.3f",
                label, a.n, a.microP(), a.microR(), a.microF1(), a.macroF1());
    }

    private static final class Agg {
        int n;
        long matched, detTotal, gtTotal;
        double macroSum;
        void add(BakeOffHarness.PdfScore s) {
            n++;
            matched += s.adjMatched();
            detTotal += s.adjDetectedTotal();
            gtTotal += s.adjGtTotal();
            macroSum += s.adjF1();
        }
        double microP() { return detTotal == 0 ? 0.0 : (double) matched / detTotal; }
        double microR() { return gtTotal == 0 ? 0.0 : (double) matched / gtTotal; }
        double microF1() { double p = microP(), r = microR(); return (p + r) == 0 ? 0.0 : 2 * p * r / (p + r); }
        double macroF1() { return n == 0 ? 0.0 : macroSum / n; }
    }

    // ----------------------------------------------------------------- ICDAR pdf-id -> xml files

    /** Re-derives the SAME pdf-id -> str.xml mapping BakeOffHarness#buildScoringSet computes
     *  internally (including the a/b-sibling fallback for us-011b/us-031b/us-035b/eu-009b), so
     *  span-detection can be joined back onto BakeOffHarness's own {@code ScoreUnit#id()} exactly.
     *  Read-only duplication of ~20 lines of directory-walking logic (not scoring logic). */
    private static Map<String, List<Path>> icdarXmlsByPdfId() throws IOException {
        List<Path> strXmls;
        try (Stream<Path> walk = Files.walk(ICDAR_ROOT)) {
            strXmls = walk.filter(p -> p.getFileName().toString().endsWith("-str.xml"))
                    .sorted().collect(Collectors.toList());
        }
        Map<Path, List<Path>> pdfToXmls = new LinkedHashMap<>();
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
            if (!Files.exists(candidate)) continue;
            pdfToXmls.computeIfAbsent(candidate.toAbsolutePath().normalize(), k -> new ArrayList<>()).add(xml);
        }
        Map<String, List<Path>> byId = new LinkedHashMap<>();
        for (Map.Entry<Path, List<Path>> e : pdfToXmls.entrySet()) {
            byId.put(TABULA_RESOURCES.relativize(e.getKey()).toString(), e.getValue());
        }
        return byId;
    }

    private static final Pattern CELL_START = Pattern.compile("<cell\\b([^>]*)>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("(\\S+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    /** True if ANY cell in {@code xml} has end-row != start-row or end-col != start-col, i.e. a
     *  FUNCTIONAL span (one that actually causes {@link GroundTruth} to repeat that cell's text
     *  across multiple grid positions), as opposed to merely having the attribute present but
     *  redundant (end-col written equal to start-col). */
    private static boolean hasRealSpan(Path xml) throws IOException {
        String text = Files.readString(xml, StandardCharsets.UTF_8);
        Matcher cm = CELL_START.matcher(text);
        while (cm.find()) {
            Map<String, String> attrs = parseAttrs(cm.group(1));
            String sr = attrs.get("start-row"), sc = attrs.get("start-col");
            String er = attrs.getOrDefault("end-row", sr), ec = attrs.getOrDefault("end-col", sc);
            if (sr != null && er != null && !sr.trim().equals(er.trim())) return true;
            if (sc != null && ec != null && !sc.trim().equals(ec.trim())) return true;
        }
        return false;
    }

    /** True if ANY cell in {@code xml} has an end-row/end-col attribute present at all (the
     *  coarser "44 of 71" count from the task brief, which includes redundant no-op spans). */
    private static boolean hasSpanAttrPresent(Path xml) throws IOException {
        String text = Files.readString(xml, StandardCharsets.UTF_8);
        Matcher cm = CELL_START.matcher(text);
        while (cm.find()) {
            Map<String, String> attrs = parseAttrs(cm.group(1));
            if (attrs.containsKey("end-row") || attrs.containsKey("end-col")) return true;
        }
        return false;
    }

    private static boolean anyHasRealSpan(List<Path> xmls) throws IOException {
        for (Path x : xmls) if (hasRealSpan(x)) return true;
        return false;
    }

    private static boolean anyHasSpanAttrPresent(List<Path> xmls) throws IOException {
        for (Path x : xmls) if (hasSpanAttrPresent(x)) return true;
        return false;
    }

    private static Map<String, String> parseAttrs(String attrText) {
        Map<String, String> map = new LinkedHashMap<>();
        if (attrText == null) return map;
        Matcher am = ATTR.matcher(attrText);
        while (am.find()) {
            String name = am.group(1);
            String value = am.group(2) != null ? am.group(2) : am.group(3);
            map.put(name, value);
        }
        return map;
    }

    // ============================================================================ H2: non-detect

    private void printH2(List<UnitDiag> diags) throws Exception {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("H2: non-detection -- PDFs where breuel produced ZERO stream tables anywhere");
        System.out.println("================================================================================");

        List<UnitDiag> missed = diags.stream().filter(d -> !d.score().detected()).toList();
        System.out.println(missed.size() + " / " + diags.size() + " PDFs produced zero hits.");
        long relationsLost = missed.stream().mapToLong(d -> d.score().adjGtTotal()).sum();
        long totalGtRelations = diags.stream().mapToLong(d -> d.score().adjGtTotal()).sum();
        System.out.println("Relations lost to non-detection (pure recall loss, adjGtTotal summed over "
                + "missed PDFs): " + relationsLost + " / " + totalGtRelations + " total GT relations ("
                + String.format(Locale.ROOT, "%.1f", 100.0 * relationsLost / Math.max(1, totalGtRelations)) + "%)");

        for (UnitDiag d : missed) {
            System.out.println();
            System.out.println("--- " + d.unit().id() + "  (GT relations lost: " + d.score().adjGtTotal() + ") ---");
            diagnoseNonDetection(d.unit());
        }
    }

    /** Runs the SAME per-page pipeline {@link StreamTableExtractor#extractPage} runs, but without
     *  swallowing the reason each candidate block failed to clear the confidence gate -- purely
     *  observational replication of that method's control flow (mirrors what {@code
     *  Diag9cHarness#dumpSubscores} already does for a single fixed target; this generalizes it
     *  to an arbitrary PDF's every page/block so it can be run over whichever PDFs H2 finds). */
    private static void diagnoseNonDetection(BakeOffHarness.ScoreUnit unit) throws Exception {
        try (PDDocument doc = Loader.loadPDF(unit.pdf().toFile())) {
            int pages = doc.getNumberOfPages();
            int reportedBlocks = 0;
            for (int i = 0; i < pages && reportedBlocks < 30; i++) {
                List<TextPosition> glyphs;
                try {
                    glyphs = TableTestPdfs.harvestGlyphs(doc, i);
                } catch (Throwable t) {
                    System.out.println("    page " + (i + 1) + ": glyph harvest threw " + t);
                    continue;
                }
                List<StreamTableExtractor.Word> words;
                List<StreamTableExtractor.Line> lines;
                try {
                    words = StreamTableExtractor.buildWords(glyphs);
                    if (words.size() < 6) continue; // matches extractPage's own early return; not worth reporting per-page
                    float mfs = StreamTableExtractor.medianFontSize(words);
                    lines = StreamTableExtractor.buildLines(words, mfs);
                    if (lines.size() < 3) continue;
                } catch (TableExtractor.RulingOverflowException e) {
                    System.out.println("    page " + (i + 1) + ": PAGE-GLOBAL DoS ABORT in buildWords/buildLines "
                            + "(glyph/word/line cap breached) -- whole page contributes 0 hits.");
                    continue;
                }
                float medianSpace = 0.5f * StreamTableExtractor.medianFontSize(words);
                List<List<StreamTableExtractor.Line>> blocks = StreamTableExtractor.splitIntoBlocks(lines);
                for (List<StreamTableExtractor.Line> block : blocks) {
                    if (block.size() < 3) continue;
                    reportedBlocks++;
                    String reason = classifyBlock(block, medianSpace);
                    System.out.println("    page " + (i + 1) + " block(" + block.size() + " lines): " + reason);
                }
            }
            if (reportedBlocks == 0) {
                System.out.println("    No candidate block (>=3 lines after word/line build) was found on ANY "
                        + "page -- the page-level word/line thresholds themselves (words>=6, lines>=3) are the "
                        + "reason, or every page's own word/line build hit the page-global DoS cap.");
            }
        }
    }

    private static String classifyBlock(List<StreamTableExtractor.Line> block, float medianSpace) {
        float bandX0 = Float.MAX_VALUE, bandX1 = -Float.MAX_VALUE;
        for (StreamTableExtractor.Line l : block) for (StreamTableExtractor.Word w : l.words) {
            bandX0 = Math.min(bandX0, w.x0); bandX1 = Math.max(bandX1, w.x1);
        }
        List<StreamTableExtractor.Gutter> gutters;
        try {
            gutters = StreamTableExtractor.findGutters(block, bandX0, bandX1, medianSpace);
        } catch (TableExtractor.RulingOverflowException e) {
            return "DoS ABORT in findGutters (MAX_GUTTER_SCAN_WORK breached) -- block skipped, 0 hits from it";
        }
        if (gutters.isEmpty()) return "0 gutters found (cols=1) -- hard-gated to confidence=0 before scoring";

        List<StreamTableExtractor.Line> trimmed;
        try {
            trimmed = StreamTableExtractor.trimEdgeLines(block, gutters, bandX0, bandX1, medianSpace);
        } catch (Throwable t) {
            return "trimEdgeLines threw " + t;
        }
        if (trimmed.size() < 3) {
            return "edge-trim (Step C) reduced block from " + block.size() + " to " + trimmed.size()
                    + " lines (<3) -- REJECTED before scoring";
        }

        StreamTableExtractor.Grid grid;
        try {
            grid = StreamTableExtractor.scoreGrid(trimmed, gutters, bandX0, bandX1);
        } catch (TableExtractor.RulingOverflowException e) {
            return "DoS ABORT in scoreGrid";
        }
        int cols = gutters.size() + 1;
        int rows = trimmed.size();
        if (cols < 2 || rows < 3) {
            return "confidence=0: hard gate cols<2||rows<3 (cols=" + cols + " rows=" + rows + ")";
        }
        if (grid.confidence == 0) {
            return classifyZeroConfidence(trimmed, gutters, grid.colBounds, cols)
                    + "  [gutters=" + gutters.size() + " rows=" + rows + "]";
        }
        if (grid.confidence < StreamTableExtractor.STREAM_CONFIDENCE_MIN) {
            return "confidence=" + fmt4(grid.confidence) + " < gate " + StreamTableExtractor.STREAM_CONFIDENCE_MIN
                    + " (cols=" + cols + " rows=" + rows + ") -- " + dominantWeakSubscore(trimmed, gutters, grid.colBounds, cols);
        }
        return "confidence=" + fmt4(grid.confidence) + " >= gate -- SHOULD have produced a hit here "
                + "(cols=" + cols + " rows=" + rows + "); if the PDF still shows 0 hits overall, "
                + "check buildHit/MAX_CELLS_PER_TABLE or a later block/page consuming the MAX_STREAM_TABLES_PER_PAGE "
                + "or MAX_STREAM_PAGE_BLOCK_WORK budget first.";
    }

    /** Distinguishes WHY scoreGrid forced confidence to exactly 0 for a cols>=2,rows>=3 grid: the
     *  two-column-non-numeric hard gate, or the prose hard veto. Re-derives the same math {@code
     *  Diag9cHarness#dumpSubscores} already re-derives for its 5 fixed targets, generalized here
     *  to an arbitrary block -- observational only, scoreGrid itself is untouched. */
    private static String classifyZeroConfidence(List<StreamTableExtractor.Line> lines,
            List<StreamTableExtractor.Gutter> gutters, float[] bounds, int cols) {
        int numericCols = 0;
        for (int c = 0; c < cols; c++) {
            int tot = 0, num = 0;
            for (StreamTableExtractor.Line l : lines) for (StreamTableExtractor.Word w : l.words)
                if (colOf(w.cx(), bounds) == c) { tot++; if (w.numeric) num++; }
            if (tot > 0 && (double) num / tot >= 0.70) numericCols++;
        }
        if (cols == 2 && numericCols == 0) {
            return "confidence=0: TWO-COLUMN NON-NUMERIC hard gate (cols==2, no numeric-leaning column)";
        }
        int proseColumns = 0;
        for (int c = 0; c < cols; c++) {
            int occupiedLines = 0, highFillLines = 0;
            float colW = bounds[c + 1] - bounds[c];
            for (StreamTableExtractor.Line l : lines) {
                float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
                boolean any = false;
                for (StreamTableExtractor.Word w : l.words) {
                    if (colOf(w.cx(), bounds) == c) { any = true; minX = Math.min(minX, w.x0); maxX = Math.max(maxX, w.x1); }
                }
                if (any) {
                    occupiedLines++;
                    if (colW > 0 && (maxX - minX) / colW > StreamTableExtractor.VETO_FILL_THRESHOLD) highFillLines++;
                }
            }
            double colFillFrac = occupiedLines > 0 ? (double) highFillLines / occupiedLines : 0;
            if (colFillFrac > StreamTableExtractor.VETO_ROW_MAJORITY_FRACTION) proseColumns++;
        }
        double proseColumnFraction = cols > 0 ? (double) proseColumns / cols : 0;
        if (proseColumnFraction > StreamTableExtractor.VETO_COLUMN_MAJORITY_FRACTION && numericCols == 0) {
            return "confidence=0: PROSE VETO fired (proseColumns=" + proseColumns + "/" + cols + ")";
        }
        return "confidence=0 but neither known hard-gate/veto condition matched (unexpected -- re-check math)";
    }

    private static String dominantWeakSubscore(List<StreamTableExtractor.Line> lines,
            List<StreamTableExtractor.Gutter> gutters, float[] bounds, int cols) {
        long wordsN = 0, viol = 0;
        for (StreamTableExtractor.Line l : lines) for (StreamTableExtractor.Word w : l.words) {
            wordsN++;
            for (StreamTableExtractor.Gutter g : gutters) if (w.x0 < g.cx() && w.x1 > g.cx()) { viol++; break; }
        }
        double violation = wordsN == 0 ? 1 : (double) viol / wordsN;
        int consistentRows = 0;
        for (StreamTableExtractor.Line l : lines) {
            int[] perCol = new int[cols];
            boolean straddle = false;
            for (StreamTableExtractor.Word w : l.words) {
                int c = colOf(w.cx(), bounds);
                perCol[c]++;
                for (StreamTableExtractor.Gutter g : gutters) if (w.x0 < g.cx() && w.x1 > g.cx()) straddle = true;
            }
            int filled = 0; for (int p : perCol) if (p >= 1) filled++;
            if (!straddle && filled >= Math.max(2, cols - 1)) consistentRows++;
        }
        double colConsistency = (double) consistentRows / lines.size();
        return String.format(Locale.ROOT, "violation=%.3f colConsistency=%.3f (raw, pre-min/0.85) cols=%d",
                violation, colConsistency, cols);
    }

    private static int colOf(float x, float[] bounds) {
        for (int c = 0; c < bounds.length - 1; c++) if (x < bounds[c + 1]) return c;
        return bounds.length - 2;
    }

    private static String fmt4(double v) { return String.format(Locale.ROOT, "%.4f", v); }

    // =========================================================================== H3: wrong rels

    private void printH3(List<UnitDiag> diags) throws Exception {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("H3: wrong relations -- sample of relations WE produce that GT does NOT contain,");
        System.out.println("    for 3 mid-range-F1 PDFs (0.2 <= adjF1 <= 0.8, detected, has a paired table)");
        System.out.println("================================================================================");

        List<UnitDiag> midRange = diags.stream()
                .filter(d -> d.score().detected() && d.score().pairedTables() > 0)
                .filter(d -> d.score().adjF1() >= 0.2 && d.score().adjF1() <= 0.8)
                .sorted(Comparator.comparingDouble(d -> Math.abs(d.score().adjF1() - 0.5)))
                .toList();
        System.out.println(midRange.size() + " candidate PDFs in [0.2, 0.8]; sampling up to 4 "
                + "(closest to 0.5 first; skipping any whose single best-matched table turns out "
                + "near-perfect, since a multi-table PDF's aggregate adjF1 can be mid-range even "
                + "when the specific best pairing shown here is trivially good).");

        int shown = 0;
        for (UnitDiag d : midRange) {
            if (shown >= 4) break;
            if (!dumpWrongRelations(d.unit())) continue; // no ICDAR geometry parseable / not worth it -> skip
            else shown++;
        }
    }

    /** Reproduces the SAME greedy exact-cell-F1 pairing {@code BakeOffHarness#scoreUnit} uses
     *  (see that method's own javadoc for the policy), then dumps relations present in OUR output
     *  but absent from GT for the winning pair, with a coarse cause classification. Duplicates
     *  {@link TableScore#buildRelations}'s RIGHT/DOWN walk locally (that method is private) --
     *  read-only re-derivation for observability, TableScore itself is untouched. */
    private static boolean dumpWrongRelations(BakeOffHarness.ScoreUnit unit) throws Exception {
        BakeOffHarness.RunResult run = BakeOffHarness.runFinderOnPdf(new BreuelGutterFinder(), unit.pdf());
        List<TableExtractor.TableHit> available = new ArrayList<>(run.hits());
        if (available.isEmpty() || unit.expected().isEmpty()) return false;

        // Pick the expected/actual pair with the single best exact-cell F1 (mirrors scoreUnit's
        // greedy walk collapsed to "just show me the best one" for this diagnostic).
        GroundTruth.Table bestExpected = null;
        TableExtractor.TableHit bestHit = null;
        double bestF1 = -1;
        for (GroundTruth.Table expected : unit.expected()) {
            for (TableExtractor.TableHit h : available) {
                TableScore.Result r = TableScore.score(expected, h.rows);
                if (r.f1() > bestF1) { bestF1 = r.f1(); bestExpected = expected; bestHit = h; }
            }
        }
        if (bestHit == null) return false;
        if (bestF1 >= 0.95) return false; // near-perfect single pairing -- not informative for H3

        System.out.println();
        System.out.println("--- " + unit.id() + "  (exact-cell F1=" + fmt4(bestF1) + ", GT "
                + bestExpected.rowCount() + "x" + bestExpected.colCount() + " vs OURS "
                + bestHit.rowCount + "x" + bestHit.colCount + ") ---");

        List<Rel> gtRels = buildRelationsForDiag(bestExpected.rows());
        List<Rel> ourRels = buildRelationsForDiag(bestHit.rows);
        Map<Rel, Integer> gtCounts = counts(gtRels);
        Map<Rel, Integer> ourCounts = counts(ourRels);

        List<Rel> extra = new ArrayList<>(); // ours, not (fully) in GT
        for (Map.Entry<Rel, Integer> e : ourCounts.entrySet()) {
            int gtN = gtCounts.getOrDefault(e.getKey(), 0);
            int surplus = e.getValue() - gtN;
            for (int i = 0; i < surplus; i++) extra.add(e.getKey());
        }
        List<Rel> missing = new ArrayList<>(); // in GT, not (fully) covered by ours
        for (Map.Entry<Rel, Integer> e : gtCounts.entrySet()) {
            int ourN = ourCounts.getOrDefault(e.getKey(), 0);
            int deficit = e.getValue() - ourN;
            for (int i = 0; i < deficit; i++) missing.add(e.getKey());
        }
        System.out.println("    GT relations=" + gtRels.size() + "  OUR relations=" + ourRels.size()
                + "  extra(FP)=" + extra.size() + "  missing(FN)=" + missing.size());

        Map<String, Integer> classCounts = new LinkedHashMap<>();
        for (Rel r : extra) classCounts.merge(classifyExtraRelation(r, gtRels, gtCounts), 1, Integer::sum);
        System.out.println("    FULL classification breakdown of all " + extra.size() + " extra relations:");
        for (Map.Entry<String, Integer> e : classCounts.entrySet()) {
            System.out.println("      " + e.getValue() + "/" + extra.size() + " ("
                    + String.format(Locale.ROOT, "%.0f", 100.0 * e.getValue() / extra.size()) + "%): " + e.getKey());
        }
        System.out.println("    Sample of EXTRA relations (we produce, GT lacks) with cause classification:");
        int n = 0;
        for (Rel r : extra) {
            if (n++ >= 15) { System.out.println("      ... (" + (extra.size() - 15) + " more)"); break; }
            System.out.println("      " + r + "  -- " + classifyExtraRelation(r, gtRels, gtCounts));
        }
        System.out.println("    Sample of MISSING relations (GT has, we lack):");
        n = 0;
        for (Rel r : missing) {
            if (n++ >= 8) { System.out.println("      ... (" + (missing.size() - 8) + " more)"); break; }
            System.out.println("      " + r);
        }
        return true;
    }

    private static String classifyExtraRelation(Rel r, List<Rel> gtRels, Map<Rel, Integer> gtCounts) {
        // merged-columns / split heuristic: does GT contain a relation with the SAME "a" but a
        // DIFFERENT "b" (we jumped past/short of the real neighbour -- consistent with a
        // merged-or-split column changing who the nearest non-empty neighbour is)?
        boolean gtHasSameA = gtRels.stream().anyMatch(g -> g.direction() == r.direction() && g.a().equals(r.a()));
        boolean gtHasSameB = gtRels.stream().anyMatch(g -> g.direction() == r.direction() && g.b().equals(r.b()));
        // substring heuristic: our "b" contains GT's real neighbour text glued to something else,
        // or vice versa -- consistent with a join/split of adjacent cell text.
        boolean aIsConcat = gtRels.stream().anyMatch(g -> g.direction() == r.direction()
                && !g.a().equals(r.a()) && (r.a().contains(g.a()) || g.a().contains(r.a())) && !r.a().isEmpty() && !g.a().isEmpty());
        boolean bIsConcat = gtRels.stream().anyMatch(g -> g.direction() == r.direction()
                && !g.b().equals(r.b()) && (r.b().contains(g.b()) || g.b().contains(r.b())) && !r.b().isEmpty() && !g.b().isEmpty());
        if (aIsConcat || bIsConcat) return "TEXT-JOIN/SPLIT (our cell text is a superstring/substring of a GT cell's)";
        if (gtHasSameA && !gtHasSameB) return "WRONG NEIGHBOUR (GT has this 'a' but pairs it with different content -- merged/split/extra column or row candidate)";
        if (!gtHasSameA && !gtHasSameB) return "PHANTOM CONTENT (neither side appears anywhere in GT's relation set -- extra row/col, caption/footnote debris, or OCR-ish artifact)";
        return "OTHER (both sides appear in GT relations individually, but not paired this way)";
    }

    private record Rel(String a, String b, TableScore.Direction direction) {
        @Override public String toString() { return "\"" + a + "\" -" + direction + "-> \"" + b + "\""; }
    }

    private static List<Rel> buildRelationsForDiag(List<List<String>> rows) {
        int numRows = rows.size();
        int numCols = 0;
        for (List<String> row : rows) numCols = Math.max(numCols, row.size());
        String[][] grid = new String[numRows][numCols];
        for (int r = 0; r < numRows; r++) {
            List<String> row = rows.get(r);
            for (int c = 0; c < numCols; c++) {
                String raw = c < row.size() ? row.get(c) : "";
                grid[r][c] = GroundTruth.normalizeCell(raw);
            }
        }
        List<Rel> relations = new ArrayList<>();
        for (int r = 0; r < numRows; r++) {
            String prev = null;
            for (int c = 0; c < numCols; c++) {
                String text = grid[r][c];
                if (text.isEmpty()) continue;
                if (prev != null) relations.add(new Rel(prev, text, TableScore.Direction.RIGHT));
                prev = text;
            }
        }
        for (int c = 0; c < numCols; c++) {
            String prev = null;
            for (int r = 0; r < numRows; r++) {
                String text = grid[r][c];
                if (text.isEmpty()) continue;
                if (prev != null) relations.add(new Rel(prev, text, TableScore.Direction.DOWN));
                prev = text;
            }
        }
        return relations;
    }

    private static Map<Rel, Integer> counts(List<Rel> rels) {
        Map<Rel, Integer> m = new HashMap<>();
        for (Rel r : rels) m.merge(r, 1, Integer::sum);
        return m;
    }

    // ========================================================================== H4: table-count

    private void printH4(List<UnitDiag> diags) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("H4: our table-count-per-PDF vs GT table-count-per-PDF, correlated with adjF1");
        System.out.println("================================================================================");

        int mismatched = 0, matched = 0;
        double mismatchF1Sum = 0, matchF1Sum = 0;
        for (UnitDiag d : diags) {
            int gtCount = d.unit().expected().size();
            int ourCount = d.ourHitCount();
            boolean same = gtCount == ourCount;
            if (same) { matched++; matchF1Sum += d.score().adjF1(); }
            else { mismatched++; mismatchF1Sum += d.score().adjF1(); }
        }
        System.out.println("PDFs where ourHitCount == gtTableCount: " + matched
                + "  (mean adjF1=" + fmt4(matched == 0 ? 0 : matchF1Sum / matched) + ")");
        System.out.println("PDFs where ourHitCount != gtTableCount: " + mismatched
                + "  (mean adjF1=" + fmt4(mismatched == 0 ? 0 : mismatchF1Sum / mismatched) + ")");

        System.out.println();
        System.out.println("Multi-table-GT PDFs specifically (gtCount > 1):");
        for (UnitDiag d : diags) {
            int gtCount = d.unit().expected().size();
            if (gtCount > 1) {
                System.out.println("  " + d.unit().id() + ": gtCount=" + gtCount
                        + " ourCount=" + d.ourHitCount() + " adjF1=" + fmt4(d.score().adjF1())
                        + " pairedTables=" + d.score().pairedTables());
            }
        }
    }

    // ============================================================ H4b: FP/FN decomposition

    private record Decomp(long pairedMatched, long pairedDetected, long pairedGt,
                           long unpairedExtraDetected, long unpairedMissingGt) {}

    /**
     * Mirrors {@code BakeOffHarness#scoreUnit}'s exact greedy-pairing walk (same policy: pair on
     * best exact-cell F1, consume that hit), but instead of collapsing straight to one aggregate
     * adjF1, keeps the two contributions to precision/recall LOSS separate:
     * <ul>
     *   <li><b>paired-table loss</b>: {@code pairedDetected - pairedMatched} (FP) / {@code
     *       pairedGt - pairedMatched} (FN) -- wrong relations WITHIN a table that WAS correctly
     *       matched to its GT counterpart (H3's failure mode: merged/split columns, text-join,
     *       phantom rows, etc., all happening inside an otherwise-correct pairing).</li>
     *   <li><b>unpaired-table loss</b>: {@code unpairedExtraDetected} (pure FP -- an actual hit
     *       left over with no expected table to pair against) / {@code unpairedMissingGt} (pure
     *       FN -- an expected table left over with no hit to pair against) -- H4's failure mode
     *       (our per-PDF table COUNT not matching GT's).</li>
     * </ul>
     * Summing both contributions and recomputing micro P/R/F1 from them must reproduce the same
     * aggregate {@code BakeOffHarness} reports (sanity-checked below) -- this is a decomposition
     * of the SAME total, not a different metric.
     */
    private static Decomp decompose(GutterFinder finder, BakeOffHarness.ScoreUnit unit) {
        BakeOffHarness.RunResult run = BakeOffHarness.runFinderOnPdf(finder, unit.pdf());
        List<TableExtractor.TableHit> available = new ArrayList<>(run.hits());
        long pairedMatched = 0, pairedDetected = 0, pairedGt = 0, unpairedExtra = 0, unpairedMissing = 0;
        for (GroundTruth.Table expected : unit.expected()) {
            if (available.isEmpty()) {
                unpairedMissing += TableScore.relationCount(expected.rows());
                continue;
            }
            TableExtractor.TableHit best = null;
            TableScore.Result bestResult = null;
            double bestF1 = -1;
            for (TableExtractor.TableHit h : available) {
                TableScore.Result r = TableScore.score(expected, h.rows);
                if (r.f1() > bestF1) { bestF1 = r.f1(); best = h; bestResult = r; }
            }
            available.remove(best);
            TableScore.AdjResult adj = TableScore.scoreAdjacency(expected, best.rows);
            pairedMatched += adj.matched();
            pairedDetected += adj.detectedTotal();
            pairedGt += adj.gtTotal();
        }
        for (TableExtractor.TableHit h : available) {
            unpairedExtra += TableScore.relationCount(h.rows);
        }
        return new Decomp(pairedMatched, pairedDetected, pairedGt, unpairedExtra, unpairedMissing);
    }

    private void printH4b(List<UnitDiag> diags, GutterFinder breuel) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("H4b: decompose total FP/FN relations into WITHIN-paired-table errors (H3's");
        System.out.println("     merged/split-column etc. failure mode) vs UNPAIRED-table errors (H4's");
        System.out.println("     table-count-mismatch failure mode)");
        System.out.println("================================================================================");

        long pm = 0, pd = 0, pg = 0, ue = 0, um = 0;
        for (UnitDiag d : diags) {
            Decomp dec = decompose(breuel, d.unit());
            pm += dec.pairedMatched(); pd += dec.pairedDetected(); pg += dec.pairedGt();
            ue += dec.unpairedExtraDetected(); um += dec.unpairedMissingGt();
        }
        long fpWithinPaired = pd - pm;
        long fnWithinPaired = pg - pm;
        long totalFp = fpWithinPaired + ue;
        long totalFn = fnWithinPaired + um;
        long totalDetected = pd + ue;
        long totalGt = pg + um;
        double p = totalDetected == 0 ? 0 : (double) pm / totalDetected;
        double r = totalGt == 0 ? 0 : (double) pm / totalGt;
        double f1 = (p + r) == 0 ? 0 : 2 * p * r / (p + r);
        System.out.println("Sanity check -- recomposed micro P/R/F1 from this decomposition: P="
                + fmt4(p) + " R=" + fmt4(r) + " F1=" + fmt4(f1)
                + "  (should match BakeOffHarness's reported breuel adjP/adjR/adjF1 = 0.497/0.506/0.501)");
        System.out.println();
        System.out.println("Total FALSE POSITIVE relations = " + totalFp + ":");
        System.out.println("  from WITHIN a correctly-paired table (wrong relation, right table): "
                + fpWithinPaired + " (" + pct(fpWithinPaired, totalFp) + "%)");
        System.out.println("  from an entirely UNPAIRED extra hit (spurious/over-fragmented table): "
                + ue + " (" + pct(ue, totalFp) + "%)");
        System.out.println();
        System.out.println("Total FALSE NEGATIVE relations = " + totalFn + ":");
        System.out.println("  from WITHIN a correctly-paired table (missed relation, right table): "
                + fnWithinPaired + " (" + pct(fnWithinPaired, totalFn) + "%)");
        System.out.println("  from an entirely UNPAIRED missing GT table (never even paired): "
                + um + " (" + pct(um, totalFn) + "%)");
    }

    private static String pct(long num, long den) {
        return den == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", 100.0 * num / den);
    }

    // ================================================== H3b: corpus-wide cause classification

    /**
     * Generalizes {@link #dumpWrongRelations}'s per-PDF cause classification (TEXT-JOIN/SPLIT vs
     * WRONG-NEIGHBOUR vs PHANTOM vs OTHER) from the 4 hand-picked mid-range PDFs H3 dumps in
     * detail to EVERY paired table in the WHOLE 77-PDF corpus, so H1's dominant-cause claim
     * ("within-paired-table wrong relations are 56.7% of all FP / 88.8% of all FN", from H4b)
     * can be broken down by CAUSE at full-corpus scale instead of resting on 4 anecdotal samples.
     */
    private void printH3b(List<UnitDiag> diags, GutterFinder breuel) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("H3b: corpus-wide classification of WITHIN-paired-table extra (FP) relations");
        System.out.println("     (generalizes H3's 4-PDF sample to the full 77-PDF corpus)");
        System.out.println("================================================================================");

        Map<String, Long> tally = new LinkedHashMap<>();
        List<String> phantomSamples = new ArrayList<>();
        long total = 0;
        for (UnitDiag d : diags) {
            BakeOffHarness.RunResult run = BakeOffHarness.runFinderOnPdf(breuel, d.unit().pdf());
            List<TableExtractor.TableHit> available = new ArrayList<>(run.hits());
            for (GroundTruth.Table expected : d.unit().expected()) {
                if (available.isEmpty()) continue;
                TableExtractor.TableHit best = null;
                double bestF1 = -1;
                for (TableExtractor.TableHit h : available) {
                    TableScore.Result r = TableScore.score(expected, h.rows);
                    if (r.f1() > bestF1) { bestF1 = r.f1(); best = h; }
                }
                available.remove(best);

                List<Rel> gtRels = buildRelationsForDiag(expected.rows());
                List<Rel> ourRels = buildRelationsForDiag(best.rows);
                Map<Rel, Integer> gtCounts = counts(gtRels);
                Map<Rel, Integer> ourCounts = counts(ourRels);
                for (Map.Entry<Rel, Integer> e : ourCounts.entrySet()) {
                    int gtN = gtCounts.getOrDefault(e.getKey(), 0);
                    int surplus = e.getValue() - gtN;
                    if (surplus <= 0) continue;
                    String cls = classifyExtraRelation(e.getKey(), gtRels, gtCounts);
                    tally.merge(cls, (long) surplus, Long::sum);
                    total += surplus;
                    if (cls.startsWith("PHANTOM") && phantomSamples.size() < 20) {
                        phantomSamples.add(d.unit().id() + ": " + e.getKey());
                    }
                }
            }
        }
        System.out.println("Total within-paired-table extra (FP) relations classified: " + total
                + " (should equal H4b's fpWithinPaired=8597)");
        for (Map.Entry<String, Long> e : tally.entrySet()) {
            System.out.println("  " + e.getValue() + "/" + total + " ("
                    + String.format(Locale.ROOT, "%.1f", 100.0 * e.getValue() / Math.max(1, total)) + "%): " + e.getKey());
        }
        System.out.println();
        System.out.println("Sample of PHANTOM CONTENT relations (id: relation), for manual inspection:");
        for (String s : phantomSamples) System.out.println("  " + s);
    }
}
