// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/, but declares
// `package com.oai.titanarum;` -- same trick BakeOffHarness/Diag9cHarness/Diag9jHarness use, for
// the same reason (StreamTableExtractor + its Word/Line/Gutter types, GutterFinder,
// BreuelGutterFinder and TableExtractor.TableHit are all package-private).
//
// TASK 9m DIAGNOSTIC (read-only). Question: is the Breuel branch-and-bound's candidate GENERATION
// non-exhaustive, i.e. does it silently fail to ever generate blatant, wide column gutters? Purely
// observational: does NOT modify StreamTableExtractor, TableScore or GroundTruth. Gated behind
// -Ddiag9m=true and named so Surefire's default includes never pick it up.
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class Diag9mHarness {

    private static final Path TABULA_RESOURCES =
            Path.of("corpus/tabula-java/src/test/resources").toAbsolutePath().normalize();
    private static final Path ICDAR_ROOT =
            TABULA_RESOURCES.resolve("technology/tabula/icdar2013-dataset");
    private static final Path PHISH_ROOT = Path.of("/home/coz/Downloads/phishpdfs");
    private static final int PROSE_SAMPLE_CAP = 200;

    // ------------------------------------------------------------------------------------ entry

    /** Also runnable outside Surefire (other agents' half-written harnesses in this same tree can
     *  break `mvn test-compile` at any moment): javac this file plus src/main/java + BakeOffHarness
     *  + GroundTruth + TableScore + TableTestPdfs, then run this main from the repo root. */
    public static void main(String[] args) throws Exception {
        System.setProperty("diag9m", "true");
        new Diag9mHarness().run();
    }

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("diag9m"), "set -Ddiag9m=true to run");

        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        List<BakeOffHarness.ScoreUnit> units = corpus.units;
        p("Scoring set: " + units.size() + " PDFs (" + corpus.icdarCount + " ICDAR + "
                + corpus.csvCount + " CSV)");

        String only = System.getProperty("diag9m.only", "");
        String sections = System.getProperty("diag9m.sections", "ABCD");

        if (sections.contains("A")) sectionA(units, only);
        if (sections.contains("B")) sectionB(units, only);
        if (sections.contains("C")) sectionC(units);
        if (sections.contains("E")) sectionE(units, only);
        if (sections.contains("F")) sectionF(units);
        if (sections.contains("D")) sectionD();
    }

    private static void p(String s) { System.out.println(s); }
    private static String f3(double v) { return String.format(Locale.ROOT, "%.3f", v); }
    private static String f1(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    // ============================================================== block enumeration (pipeline)

    record BlockCtx(String pdfId, int page, int blockIdx, List<StreamTableExtractor.Line> lines,
                    float bandX0, float bandX1, float medianSpace) {}

    static List<BlockCtx> blocksOf(BakeOffHarness.ScoreUnit unit) throws IOException {
        List<BlockCtx> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(unit.pdf().toFile())) {
            for (int pi = 0; pi < doc.getNumberOfPages(); pi++) {
                List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, pi);
                List<StreamTableExtractor.Word> words;
                List<StreamTableExtractor.Line> lines;
                try {
                    words = StreamTableExtractor.buildWords(glyphs);
                    if (words.size() < 6) continue;
                    float mfs = StreamTableExtractor.medianFontSize(words);
                    lines = StreamTableExtractor.buildLines(words, mfs);
                    if (lines.size() < 3) continue;
                } catch (TableExtractor.RulingOverflowException e) {
                    continue;
                }
                float medianSpace = 0.5f * StreamTableExtractor.medianFontSize(words);
                int bi = 0;
                for (List<StreamTableExtractor.Line> b : StreamTableExtractor.splitIntoBlocks(lines)) {
                    bi++;
                    if (b.size() < 3) continue;
                    float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE;
                    for (StreamTableExtractor.Line l : b)
                        for (StreamTableExtractor.Word w : l.words) {
                            x0 = Math.min(x0, w.x0); x1 = Math.max(x1, w.x1);
                        }
                    out.add(new BlockCtx(unit.id(), pi + 1, bi, b, x0, x1, medianSpace));
                }
            }
        }
        return out;
    }

    // ================================================== SECTION A: B&B termination + completeness

    /** Faithful clone of StreamTableExtractor.findGutters' branch-and-bound loop, instrumented.
     *  Only the counters are added -- the pop/gate/split/accept logic is copied verbatim, so its
     *  termination behavior is the production one. The (separate, additive) narrow-gutter
     *  secondary pass and the final merge/coverage stage are NOT cloned: this measures candidate
     *  GENERATION only. */
    record TermStats(String reason, int accepted, int dupSkipped, long work, int pqAtExit, int pops,
                     List<float[]> acceptedRects) {}

    private static final class Rect {
        float x0, y0, x1, y1;
        Rect(float a, float b, float c, float d) { x0 = a; y0 = b; x1 = c; y1 = d; }
        float w() { return x1 - x0; }
        float h() { return y1 - y0; }
    }

    static TermStats bbCensus(BlockCtx b) {
        List<StreamTableExtractor.Line> lines = b.lines();
        float bandX0 = b.bandX0(), bandX1 = b.bandX1(), medianSpace = b.medianSpace();
        if (lines.isEmpty() || bandX1 - bandX0 <= 0)
            return new TermStats("degenerate", 0, 0, 0, 0, 0, List.of());
        float yTop = Float.MAX_VALUE, yBot = -Float.MAX_VALUE;
        List<float[]> obstacles = new ArrayList<>();
        for (StreamTableExtractor.Line l : lines) {
            yTop = Math.min(yTop, l.yTop); yBot = Math.max(yBot, l.yBot);
            for (StreamTableExtractor.Word w : l.words) obstacles.add(new float[]{w.x0, w.y0, w.x1, w.y1});
        }
        final float bandH = yBot - yTop;
        final float minGutterW = Math.max(medianSpace, 1f);

        PriorityQueue<Rect> pq = new PriorityQueue<>(
                (x, y) -> Float.compare(quality(y, medianSpace), quality(x, medianSpace)));
        pq.add(new Rect(bandX0, yTop, bandX1, yBot));
        List<Rect> accepted = new ArrayList<>();
        int duplicatesSkipped = 0, pops = 0;
        long work = 0;
        String reason = "pqEmpty";
        while (!pq.isEmpty() && accepted.size() + duplicatesSkipped < StreamTableExtractor.MAX_GUTTER_CANDIDATES) {
            Rect r = pq.poll();
            pops++;
            if (r.w() < minGutterW || r.h() < 0.60f * bandH) continue;
            if (coveredByAccepted(r, accepted)) { duplicatesSkipped++; continue; }
            work += obstacles.size();
            if (work > StreamTableExtractor.MAX_GUTTER_SCAN_WORK) { reason = "workAbort"; break; }
            float[] pivot = firstObstacleInside(r, obstacles);
            if (pivot == null) { accepted.add(r); continue; }
            if (pivot[0] - r.x0 >= minGutterW) pq.add(new Rect(r.x0, r.y0, pivot[0], r.y1));
            if (r.x1 - pivot[2] >= minGutterW) pq.add(new Rect(pivot[2], r.y0, r.x1, r.y1));
            float aboveY1 = Math.min(r.y1, Math.max(r.y0, pivot[1]));
            float belowY0 = Math.max(r.y0, Math.min(r.y1, pivot[3]));
            pq.add(new Rect(r.x0, r.y0, r.x1, aboveY1));
            pq.add(new Rect(r.x0, belowY0, r.x1, r.y1));
        }
        if (reason.equals("pqEmpty")
                && accepted.size() + duplicatesSkipped >= StreamTableExtractor.MAX_GUTTER_CANDIDATES) {
            reason = "candidateCap";
        }
        List<float[]> rects = new ArrayList<>();
        for (Rect r : accepted) rects.add(new float[]{r.x0, r.y0, r.x1, r.y1});
        return new TermStats(reason, accepted.size(), duplicatesSkipped, work, pq.size(), pops, rects);
    }

    /** Replicates findGutters' post-search merge + row-coverage + edge-margin stage on a given set
     *  of accepted rects, reporting the DISPOSITION of every merged group -- so a strip the search
     *  really did generate but whose group was then discarded can be told apart from a strip the
     *  search never generated at all. Verbatim logic from findGutters (minus the work charging). */
    record MergedGroup(float x0, float x1, int cover, int straddling, int total,
                       double coverFraction, String verdict) {}

    static List<MergedGroup> mergeAndFilter(BlockCtx b, List<float[]> acceptedRects) {
        List<StreamTableExtractor.Line> lines = b.lines();
        List<float[]> acc = new ArrayList<>(acceptedRects);
        acc.sort(Comparator.comparingDouble(r -> r[0]));
        List<float[]> merged = new ArrayList<>();
        List<BitSet> mergedRows = new ArrayList<>();
        for (float[] r : acc) {
            BitSet rows = new BitSet(lines.size());
            for (int i = 0; i < lines.size(); i++) {
                StreamTableExtractor.Line l = lines.get(i);
                if (l.yTop < r[3] && l.yBot > r[1]) rows.set(i);
            }
            if (!merged.isEmpty() && r[0] <= merged.get(merged.size() - 1)[1]) {
                float[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], r[2]);
                mergedRows.get(mergedRows.size() - 1).or(rows);
            } else {
                merged.add(new float[]{r[0], r[2]});
                mergedRows.add(rows);
            }
        }
        List<MergedGroup> out = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            float[] m = merged.get(i);
            int cover = mergedRows.get(i).cardinality();
            int totalRows = lines.size();
            int straddling = 0;
            for (StreamTableExtractor.Line l : lines) {
                for (StreamTableExtractor.Word w : l.words) {
                    if (w.x0 < m[1] && w.x1 > m[0]) { straddling++; break; }
                }
            }
            int nonStraddling = totalRows - straddling;
            double coverFraction;
            if (straddling > 0
                    && nonStraddling >= StreamTableExtractor.GUTTER_MIN_NONSTRADDLING_ROWS_FOR_EXCLUSION
                    && straddling < StreamTableExtractor.GUTTER_MAX_STRADDLE_FRACTION_FOR_EXCLUSION * totalRows) {
                coverFraction = nonStraddling > 0 ? (double) cover / nonStraddling : 0;
            } else {
                coverFraction = totalRows > 0 ? (double) cover / totalRows : 0;
            }
            String verdict;
            if (coverFraction < StreamTableExtractor.GUTTER_MIN_COVER_FRACTION) verdict = "DROP_lowCover";
            else if (m[0] <= b.bandX0() + 0.5f || m[1] >= b.bandX1() - 0.5f) verdict = "DROP_edgeMargin";
            else verdict = "KEEP";
            out.add(new MergedGroup(m[0], m[1], cover, straddling, totalRows, coverFraction, verdict));
        }
        return out;
    }

    /** The tallest obstacle-free RECT the B&B could ever accept inside this x-strip, as a fraction
     *  of the band height -- i.e. the tallest run of consecutive rows with nothing in the strip,
     *  measured from the bottom of the row above the run to the top of the row below it. The search
     *  discards any candidate shorter than 0.60*bandH, so a value below 0.60 means the strip is
     *  UNREACHABLE BY DESIGN, not missed by an incomplete search. */
    static double tallestCleanRunFrac(BlockCtx b, float x0, float x1) {
        List<StreamTableExtractor.Line> lines = b.lines();
        float yTop = Float.MAX_VALUE, yBot = -Float.MAX_VALUE;
        for (StreamTableExtractor.Line l : lines) { yTop = Math.min(yTop, l.yTop); yBot = Math.max(yBot, l.yBot); }
        float bandH = yBot - yTop;
        if (bandH <= 0) return 0;
        boolean[] clean = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            boolean any = false;
            for (StreamTableExtractor.Word w : lines.get(i).words) if (w.x0 < x1 && w.x1 > x0) { any = true; break; }
            clean[i] = !any;
        }
        double best = 0;
        int i = 0;
        while (i < clean.length) {
            if (!clean[i]) { i++; continue; }
            int j = i;
            while (j + 1 < clean.length && clean[j + 1]) j++;
            float top = i == 0 ? yTop : lines.get(i - 1).yBot;
            float bot = j == clean.length - 1 ? yBot : lines.get(j + 1).yTop;
            best = Math.max(best, (bot - top) / bandH);
            i = j + 1;
        }
        return best;
    }

    /** Disposition of a swept strip that production did not return. */
    static String missedWhy(BlockCtx b, Strip s, TermStats ts) {
        // (1) did the search accept a rect whose x-range overlaps this strip at all?
        boolean generated = false;
        for (float[] r : ts.acceptedRects()) if (s.x0() < r[2] && s.x1() > r[0]) { generated = true; break; }
        if (!generated) return "NEVER_GENERATED" + String.format(Locale.ROOT,
                "[tallestCleanRun=%.2fxbandH vs required 0.60]", tallestCleanRunFrac(b, s.x0(), s.x1()));
        for (MergedGroup g : mergeAndFilter(b, ts.acceptedRects())) {
            if (s.x0() < g.x1() && s.x1() > g.x0()) {
                if (g.verdict().equals("KEEP")) return "GENERATED_keptButShifted";
                return "GENERATED_" + g.verdict() + String.format(Locale.ROOT,
                        "[grp=%.1f-%.1f cov=%d/%d strad=%d cf=%.2f]", g.x0(), g.x1(), g.cover(),
                        g.total(), g.straddling(), g.coverFraction());
            }
        }
        return "GENERATED_noGroup";
    }

    private static float quality(Rect r, float medianSpace) {
        return r.h() * Math.min(r.w(), 3f * medianSpace);
    }

    private static float[] firstObstacleInside(Rect r, List<float[]> obstacles) {
        float cx = (r.x0 + r.x1) * 0.5f;
        float[] best = null;
        float bestDist = Float.MAX_VALUE;
        for (float[] o : obstacles) {
            if (o[0] < r.x1 && o[2] > r.x0 && o[1] < r.y1 && o[3] > r.y0) {
                float ocx = (Math.max(o[0], r.x0) + Math.min(o[2], r.x1)) * 0.5f;
                float dist = Math.abs(ocx - cx);
                if (dist < bestDist) { bestDist = dist; best = o; }
            }
        }
        return best;
    }

    private static boolean coveredByAccepted(Rect r, List<Rect> accepted) {
        for (Rect g : accepted) {
            if (r.x0 >= g.x0 - 0.01f && r.x1 <= g.x1 + 0.01f
                    && r.y0 >= g.y0 - 0.01f && r.y1 <= g.y1 + 0.01f) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------- complete strip sweep

    /** A candidate column gutter found by EXHAUSTIVE enumeration: [x0,x1] is an x-interval over
     *  which {@code cover} of the block's rows have NO word at all (strictly clean). */
    record Strip(float x0, float x1, int cover, int total) {
        float w() { return x1 - x0; }
    }

    /** Per-row maximal empty x-intervals ("gaps") clipped to the band: {a, b, rowIdx}. Every
     *  possible clean strip is, by definition, contained in one gap of every row it is clean on,
     *  so enumerating over gaps is exhaustive by construction (no search, no pruning, no budget
     *  interacting with completeness). */
    static List<float[]> rowGaps(BlockCtx b) {
        List<float[]> gaps = new ArrayList<>();
        List<StreamTableExtractor.Line> lines = b.lines();
        for (int i = 0; i < lines.size(); i++) {
            List<StreamTableExtractor.Word> ws = lines.get(i).words;
            float cursor = b.bandX0();
            // words are already sorted by x0 (buildLines); merge overlaps as we walk
            for (StreamTableExtractor.Word w : ws) {
                if (w.x0 > cursor) gaps.add(new float[]{cursor, w.x0, i});
                cursor = Math.max(cursor, w.x1);
            }
            if (b.bandX1() > cursor) gaps.add(new float[]{cursor, b.bandX1(), i});
        }
        return gaps;
    }

    /** Production's own final acceptance rule for a merged gutter, applied to an exactly-clean
     *  strip. For an exact strip, "rows covered" and "rows not straddling" are the same set (a row
     *  either has a word overlapping the strip -> straddles, or it does not -> clean), so the
     *  task-9l straddle-exclusion branch evaluates to coverFraction 1.0. Reproduced literally
     *  rather than simplified so it cannot drift from the shipped code. */
    static boolean prodAccepts(int cover, int total) {
        int straddling = total - cover;
        double coverFraction;
        if (straddling > 0
                && cover >= StreamTableExtractor.GUTTER_MIN_NONSTRADDLING_ROWS_FOR_EXCLUSION
                && straddling < StreamTableExtractor.GUTTER_MAX_STRADDLE_FRACTION_FOR_EXCLUSION * total) {
            coverFraction = cover > 0 ? (double) cover / cover : 0;
        } else {
            coverFraction = total > 0 ? (double) cover / total : 0;
        }
        return coverFraction >= StreamTableExtractor.GUTTER_MIN_COVER_FRACTION;
    }

    /** Minimum row count a strip must be clean on to pass {@link #prodAccepts}. */
    static int requiredCover(int total) {
        for (int c = 0; c <= total; c++) if (prodAccepts(c, total)) return c;
        return total + 1;
    }

    /**
     * EXHAUSTIVE enumeration of every maximal clean strip that would pass production's own
     * width + row-coverage + interior-margin acceptance rules. No search, no priority queue, no
     * budget: for every distinct gap-start u, the widest strip starting at u with at least
     * {@code requiredCover} clean rows is the K-th largest gap end among gaps covering u. Any
     * maximal accepted strip's left edge IS such a gap start, so this set is a superset of the
     * accepted maximal strips.
     */
    static List<Strip> sweepStrips(BlockCtx b, double coverFracOverride) {
        int total = b.lines().size();
        int need = coverFracOverride > 0
                ? Math.max(3, (int) Math.ceil(coverFracOverride * total))
                : requiredCover(total);
        if (need > total) return List.of();
        float minGutterW = Math.max(b.medianSpace(), 1f);
        List<float[]> gaps = rowGaps(b);
        if (gaps.isEmpty()) return List.of();

        // distinct candidate left edges (rounded to 0.05pt to collapse near-identical starts)
        TreeMap<Integer, Float> us = new TreeMap<>();
        for (float[] g : gaps) us.putIfAbsent(Math.round(g[0] * 20f), g[0]);

        List<Strip> raw = new ArrayList<>();
        float[] ends = new float[gaps.size()];
        for (Float u : us.values()) {
            int n = 0;
            for (float[] g : gaps) if (g[0] <= u + 0.01f && g[1] > u + 0.01f) ends[n++] = g[1];
            if (n < need) continue;
            float[] sub = Arrays.copyOf(ends, n);
            Arrays.sort(sub);                            // ascending
            float v = sub[n - need];                     // need-th largest
            if (v - u < minGutterW) continue;
            if (u <= b.bandX0() + 0.5f || v >= b.bandX1() - 0.5f) continue;
            BitSet rows = new BitSet(total);
            for (float[] g : gaps) if (g[0] <= u + 0.01f && g[1] >= v - 0.01f) rows.set((int) g[2]);
            int cover = rows.cardinality();
            if (coverFracOverride > 0 ? (cover >= need) : prodAccepts(cover, total)) {
                raw.add(new Strip(u, v, cover, total));
            }
        }
        // greedy non-overlapping selection: widest first, then highest cover
        raw.sort(Comparator.<Strip>comparingDouble(s -> -s.w()).thenComparingInt(s -> -s.cover()));
        List<Strip> keep = new ArrayList<>();
        for (Strip s : raw) {
            boolean clash = false;
            for (Strip k : keep) if (s.x0() < k.x1() && s.x1() > k.x0()) { clash = true; break; }
            if (!clash) keep.add(s);
        }
        keep.sort(Comparator.comparingDouble(Strip::x0));
        return keep;
    }

    private static void sectionA(List<BakeOffHarness.ScoreUnit> units, String only) throws Exception {
        p("");
        p("================ SECTION A: B&B termination census + completeness gap ================");
        Map<String, Integer> reasons = new LinkedHashMap<>();
        int blocks = 0, totalProd = 0;
        // per cover-bar variant: total swept, missed-by-production, bucketed by width/medianSpace
        double[] bars = {-1, 0.60, 0.80, 1.00};
        Map<Double, int[]> sweptByBar = new LinkedHashMap<>();     // [swept, missed]
        Map<Double, int[]> missedWidthBuckets = new LinkedHashMap<>(); // [<1.5x, 1.5-3x, 3-8x, >=8x]
        Map<Double, Integer> blocksWithMissedByBar = new LinkedHashMap<>();
        for (double bar : bars) {
            sweptByBar.put(bar, new int[2]);
            missedWidthBuckets.put(bar, new int[4]);
            blocksWithMissedByBar.put(bar, 0);
        }
        List<String> blatant = new ArrayList<>();   // missed strips >= 3x medianSpace, prod-rule bar
        // disposition of every missed strip, per bar: NEVER_GENERATED vs generated-then-discarded
        Map<Double, Map<String, Integer>> dispo = new LinkedHashMap<>();
        for (double bar : bars) dispo.put(bar, new TreeMap<>());
        long maxCandidates = 0, maxWork = 0;
        for (BakeOffHarness.ScoreUnit unit : units) {
            if (!only.isEmpty() && !unit.id().contains(only)) continue;
            for (BlockCtx b : blocksOf(unit)) {
                blocks++;
                TermStats ts = bbCensus(b);
                reasons.merge(ts.reason(), 1, Integer::sum);
                maxCandidates = Math.max(maxCandidates, ts.accepted() + ts.dupSkipped());
                maxWork = Math.max(maxWork, ts.work());
                List<StreamTableExtractor.Gutter> prod;
                try {
                    prod = StreamTableExtractor.findGutters(b.lines(), b.bandX0(), b.bandX1(), b.medianSpace());
                } catch (TableExtractor.RulingOverflowException e) {
                    prod = List.of();
                }
                totalProd += prod.size();
                for (double bar : bars) {
                    List<Strip> sweep = sweepStrips(b, bar);
                    sweptByBar.get(bar)[0] += sweep.size();
                    int missed = 0;
                    StringBuilder miss = new StringBuilder();
                    for (Strip s : sweep) {
                        boolean overlaps = false;
                        for (StreamTableExtractor.Gutter g : prod)
                            if (s.x0() < g.x1 && s.x1() > g.x0) { overlaps = true; break; }
                        if (overlaps) continue;
                        missed++;
                        double ratio = s.w() / b.medianSpace();
                        int[] bk = missedWidthBuckets.get(bar);
                        if (ratio < 1.5) bk[0]++; else if (ratio < 3) bk[1]++; else if (ratio < 8) bk[2]++; else bk[3]++;
                        String why = missedWhy(b, s, ts);
                        String whyKey = why.contains("[") ? why.substring(0, why.indexOf('[')) : why;
                        if (ratio >= 3) whyKey += "(>=3x)";
                        dispo.get(bar).merge(whyKey, 1, Integer::sum);
                        if (bar < 0 && ratio >= 3) {
                            miss.append(String.format(Locale.ROOT, " [%.1f,%.1f w=%.1f (%.1fxms) cover=%d/%d %s]",
                                    s.x0(), s.x1(), s.w(), ratio, s.cover(), s.total(), why));
                        }
                    }
                    sweptByBar.get(bar)[1] += missed;
                    if (missed > 0) blocksWithMissedByBar.merge(bar, 1, Integer::sum);
                    if (bar < 0 && miss.length() > 0) {
                        blatant.add(String.format(Locale.ROOT,
                                "%s p%d b%d rows=%d words=%d band=[%.0f,%.0f] ms=%.2f prod=%d MISSED>=3x:%s  term=%s(acc=%d dup=%d work=%d pq=%d)",
                                shortId(b.pdfId()), b.page(), b.blockIdx(), b.lines().size(),
                                wordCount(b), b.bandX0(), b.bandX1(), b.medianSpace(),
                                prod.size(), miss, ts.reason(), ts.accepted(), ts.dupSkipped(),
                                ts.work(), ts.pqAtExit()));
                    }
                }
            }
        }
        p("blocks examined: " + blocks + " | production gutters total: " + totalProd);
        p("B&B termination reasons: " + reasons);
        p("cover-bar sweeps (bar<0 == production's own effective acceptance rule):");
        for (double bar : bars) {
            int[] sw = sweptByBar.get(bar);
            int[] bk = missedWidthBuckets.get(bar);
            p(String.format(Locale.ROOT,
                    "  bar=%-5s swept=%-5d missedByProduction=%-5d inBlocks=%-4d widthBuckets[<1.5x=%d, 1.5-3x=%d, 3-8x=%d, >=8x=%d]",
                    bar < 0 ? "prod" : f3(bar), sw[0], sw[1], blocksWithMissedByBar.get(bar),
                    bk[0], bk[1], bk[2], bk[3]));
            p("            dispositions: " + dispo.get(bar));
        }
        p("DoS headroom actually used on this corpus: max(accepted+duplicatesSkipped)=" + maxCandidates
                + " of MAX_GUTTER_CANDIDATES=" + StreamTableExtractor.MAX_GUTTER_CANDIDATES
                + " | max charged work=" + maxWork
                + " of MAX_GUTTER_SCAN_WORK=" + StreamTableExtractor.MAX_GUTTER_SCAN_WORK);
        p("blocks with a BLATANT (>=3x medianSpace) missed strip, production rule:");
        blatant.sort(Comparator.naturalOrder());
        for (String s : blatant) p("  " + s);
    }

    private static int wordCount(BlockCtx b) {
        int n = 0;
        for (StreamTableExtractor.Line l : b.lines()) n += l.words.size();
        return n;
    }

    private static String shortId(String id) {
        int i = id.lastIndexOf('/');
        return i < 0 ? id : id.substring(i + 1);
    }

    // ============================================== SECTION B: GT column boundaries (ICDAR bbox)

    /** One ground-truth interior column boundary, in PDF x-points: the clear span between the
     *  right edge of everything in column c and the left edge of everything in column c+1. */
    record GtBoundary(String pdfId, int tableIdx, int leftCol, float x0, float x1) {}

    private static final Pattern CELL = Pattern.compile(
            "<cell\\b([^>]*)>\\s*<bounding-box\\b([^>]*)/>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    static Map<String, List<GtBoundary>> gtBoundaries() throws IOException {
        Map<String, List<Path>> xmlsByPdfId = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(ICDAR_ROOT)) {
            for (Path xml : walk.filter(x -> x.getFileName().toString().endsWith("-str.xml"))
                    .sorted().collect(Collectors.toList())) {
                String base = xml.getFileName().toString();
                base = base.substring(0, base.length() - "-str.xml".length());
                Path cand = xml.getParent().resolve(base + ".pdf");
                if (!Files.exists(cand)) {
                    char last = base.isEmpty() ? ' ' : base.charAt(base.length() - 1);
                    if (Character.isLowerCase(last) && last != 'a') {
                        Path alt = xml.getParent().resolve(base.substring(0, base.length() - 1) + "a.pdf");
                        if (Files.exists(alt)) cand = alt;
                    }
                }
                if (!Files.exists(cand)) continue;
                String id = TABULA_RESOURCES.relativize(cand.toAbsolutePath().normalize()).toString();
                xmlsByPdfId.computeIfAbsent(id, k -> new ArrayList<>()).add(xml);
            }
        }
        Map<String, List<GtBoundary>> out = new LinkedHashMap<>();
        for (Map.Entry<String, List<Path>> e : xmlsByPdfId.entrySet()) {
            List<GtBoundary> bs = new ArrayList<>();
            int tableIdx = 0;
            for (Path xml : e.getValue()) {
                String text = Files.readString(xml);
                for (String tableBody : splitTables(text)) {
                    tableIdx++;
                    Map<Integer, Float> right = new TreeMap<>();   // col -> max x2 of cells ENDING there
                    Map<Integer, Float> left = new TreeMap<>();    // col -> min x1 of cells STARTING there
                    int maxCol = -1;
                    Matcher m = CELL.matcher(tableBody);
                    while (m.find()) {
                        Map<String, String> a = attrs(m.group(1));
                        Map<String, String> bb = attrs(m.group(2));
                        if (!a.containsKey("start-col")) continue;
                        int sc, ec;
                        float bx1, bx2;
                        try {   // a couple of corpus XMLs have typo'd coordinates, e.g. x2='26ß'
                            sc = Integer.parseInt(a.get("start-col").trim());
                            ec = a.containsKey("end-col") ? Integer.parseInt(a.get("end-col").trim()) : sc;
                            bx1 = Float.parseFloat(bb.get("x1").trim());
                            bx2 = Float.parseFloat(bb.get("x2").trim());
                        } catch (RuntimeException ex) { continue; }
                        maxCol = Math.max(maxCol, ec);
                        left.merge(sc, bx1, Math::min);
                        right.merge(ec, bx2, Math::max);
                    }
                    for (int c = 0; c < maxCol; c++) {
                        Float r = right.get(c), l = left.get(c + 1);
                        if (r == null || l == null) continue;
                        if (l - r < 0.5f) continue;           // GT columns physically touch: no clean span
                        bs.add(new GtBoundary(e.getKey(), tableIdx, c, r, l));
                    }
                }
            }
            out.put(e.getKey(), bs);
        }
        return out;
    }

    private static List<String> splitTables(String xml) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (true) {
            int s = xml.indexOf("<table", i);
            if (s < 0) break;
            int e = xml.indexOf("</table>", s);
            if (e < 0) break;
            out.add(xml.substring(s, e));
            i = e + 1;
        }
        return out;
    }

    private static Map<String, String> attrs(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        Matcher a = ATTR.matcher(s);
        while (a.find()) m.put(a.group(1), a.group(2) != null ? a.group(2) : a.group(3));
        return m;
    }

    /** Why a ground-truth column boundary is not represented by any production gutter. */
    private static String classify(BlockCtx b, GtBoundary gb, List<StreamTableExtractor.Gutter> prod,
                                    List<Strip> sweep) {
        float mid = (gb.x0() + gb.x1()) * 0.5f;
        for (StreamTableExtractor.Gutter g : prod) if (mid >= g.x0 - 0.5f && mid <= g.x1 + 0.5f) return "FOUND";
        for (StreamTableExtractor.Gutter g : prod) if (gb.x0() < g.x1 && gb.x1() > g.x0) return "FOUND_partial";
        for (Strip s : sweep) if (mid >= s.x0() - 0.5f && mid <= s.x1() + 0.5f) return "MISSED_sweepFinds";
        // not accepted by the exhaustive sweep either -- say why
        List<float[]> gaps = rowGaps(b);
        int total = b.lines().size();
        int need = requiredCover(total);
        float minW = Math.max(b.medianSpace(), 1f);
        List<float[]> at = new ArrayList<>();
        for (float[] g : gaps) if (g[0] <= mid && g[1] >= mid) at.add(g);
        if (at.size() < need) return "NO_CLEAN_" + at.size() + "of" + total + "need" + need;
        // widest strip through mid with >= need clean rows
        float bestW = 0;
        at.sort(Comparator.comparingDouble(g -> g[0]));
        for (int i = 0; i < at.size(); i++) {
            float u = at.get(i)[0];
            List<Float> endsList = new ArrayList<>();
            for (float[] g : at) if (g[0] <= u + 0.01f) endsList.add(g[1]);
            if (endsList.size() < need) continue;
            endsList.sort(Comparator.naturalOrder());
            float v = endsList.get(endsList.size() - need);
            if (v - u > bestW) bestW = v - u;
        }
        if (bestW < minW) return String.format(Locale.ROOT, "TOO_NARROW_%.1f<%.1f", bestW, minW);
        if (mid <= b.bandX0() + 0.5f || mid >= b.bandX1() - 0.5f) return "EDGE_MARGIN";
        return String.format(Locale.ROOT, "OTHER_bestW=%.1f", bestW);
    }

    private static void sectionB(List<BakeOffHarness.ScoreUnit> units, String only) throws Exception {
        p("");
        p("======== SECTION B: ICDAR ground-truth column boundaries vs production gutters ========");
        Map<String, List<GtBoundary>> gt = gtBoundaries();
        Map<String, Integer> verdicts = new TreeMap<>();
        Map<String, Integer> perPdfMissedSweepFinds = new TreeMap<>();
        int pdfs = 0, bnds = 0;
        for (BakeOffHarness.ScoreUnit unit : units) {
            List<GtBoundary> bs = gt.get(unit.id());
            if (bs == null || bs.isEmpty()) continue;
            if (!only.isEmpty() && !unit.id().contains(only)) continue;
            pdfs++;
            List<BlockCtx> blocks = blocksOf(unit);
            for (GtBoundary gb : bs) {
                bnds++;
                // attribute the boundary to the block whose band contains its midpoint and which
                // has the most rows (the table block, not a stray 3-line header block)
                float mid = (gb.x0() + gb.x1()) * 0.5f;
                BlockCtx best = null;
                for (BlockCtx b : blocks) {
                    if (mid <= b.bandX0() || mid >= b.bandX1()) continue;
                    if (best == null || b.lines().size() > best.lines().size()) best = b;
                }
                if (best == null) { verdicts.merge("NO_BLOCK", 1, Integer::sum); continue; }
                List<StreamTableExtractor.Gutter> prod;
                try {
                    prod = StreamTableExtractor.findGutters(best.lines(), best.bandX0(), best.bandX1(),
                            best.medianSpace());
                } catch (TableExtractor.RulingOverflowException e) { prod = List.of(); }
                List<Strip> sweep = sweepStrips(best, -1);
                String v = classify(best, gb, prod, sweep);
                String key = v.startsWith("NO_CLEAN") ? "NO_CLEAN_STRIP"
                        : v.startsWith("TOO_NARROW") ? "TOO_NARROW"
                        : v.startsWith("OTHER") ? "OTHER" : v;
                verdicts.merge(key, 1, Integer::sum);
                if (key.equals("MISSED_sweepFinds")) {
                    perPdfMissedSweepFinds.merge(shortId(unit.id()), 1, Integer::sum);
                    p(String.format(Locale.ROOT,
                            "  MISSED %s tbl%d col%d->%d gtSpan=[%.1f,%.1f] (%.1fx ms) block rows=%d prodGutters=%d sweepGutters=%d",
                            shortId(unit.id()), gb.tableIdx(), gb.leftCol(), gb.leftCol() + 1,
                            gb.x0(), gb.x1(), (gb.x1() - gb.x0()) / best.medianSpace(),
                            best.lines().size(), prod.size(), sweep.size()));
                }
            }
        }
        p("ICDAR PDFs with bbox ground truth: " + pdfs + " | interior column boundaries with a clean GT span: " + bnds);
        p("verdicts: " + verdicts);
        p("per-PDF counts of MISSED_sweepFinds (the completeness-bug class): " + perPdfMissedSweepFinds);
    }

    // ================================================ SECTION C: end-to-end prize (adjacency F1)

    /** breuel + every exhaustively-swept strip production missed (strictly additive). */
    static final class SweepAugmentedFinder implements GutterFinder {
        private final double coverFrac;
        private final String name;
        SweepAugmentedFinder(double coverFrac, String name) { this.coverFrac = coverFrac; this.name = name; }
        @Override public List<StreamTableExtractor.Gutter> find(List<StreamTableExtractor.Line> lines,
                float bandX0, float bandX1, float medianSpace) {
            List<StreamTableExtractor.Gutter> prod = new ArrayList<>(
                    StreamTableExtractor.findGutters(lines, bandX0, bandX1, medianSpace));
            BlockCtx b = new BlockCtx("", 0, 0, lines, bandX0, bandX1, medianSpace);
            for (Strip s : sweepStrips(b, coverFrac)) {
                boolean clash = false;
                for (StreamTableExtractor.Gutter g : prod) if (s.x0() < g.x1 && s.x1() > g.x0) { clash = true; break; }
                if (clash) continue;
                StreamTableExtractor.Gutter g = new StreamTableExtractor.Gutter();
                g.x0 = s.x0(); g.x1 = s.x1(); g.rowsCovered = s.cover();
                prod.add(g);
            }
            prod.sort(Comparator.comparingDouble(g -> g.x0));
            return prod;
        }
        @Override public String name() { return name; }
    }

    /** Pure exhaustive sweep, replacing the B&B entirely. */
    static final class SweepOnlyFinder implements GutterFinder {
        private final double coverFrac;
        private final String name;
        SweepOnlyFinder(double coverFrac, String name) { this.coverFrac = coverFrac; this.name = name; }
        @Override public List<StreamTableExtractor.Gutter> find(List<StreamTableExtractor.Line> lines,
                float bandX0, float bandX1, float medianSpace) {
            BlockCtx b = new BlockCtx("", 0, 0, lines, bandX0, bandX1, medianSpace);
            List<StreamTableExtractor.Gutter> out = new ArrayList<>();
            for (Strip s : sweepStrips(b, coverFrac)) {
                StreamTableExtractor.Gutter g = new StreamTableExtractor.Gutter();
                g.x0 = s.x0(); g.x1 = s.x1(); g.rowsCovered = s.cover();
                out.add(g);
            }
            return out;
        }
        @Override public String name() { return name; }
    }

    /** ORACLE: hands the pipeline the ground-truth column boundaries directly (filtered to the
     *  block's own band). Upper bound on ANY gutter-detection improvement whatsoever. */
    static final class OracleFinder implements GutterFinder {
        private final List<GtBoundary> bounds;
        private final boolean requireSomeClean;
        OracleFinder(List<GtBoundary> bounds, boolean requireSomeClean) {
            this.bounds = bounds; this.requireSomeClean = requireSomeClean;
        }
        @Override public List<StreamTableExtractor.Gutter> find(List<StreamTableExtractor.Line> lines,
                float bandX0, float bandX1, float medianSpace) {
            List<StreamTableExtractor.Gutter> out = new ArrayList<>();
            for (GtBoundary gb : bounds) {
                float mid = (gb.x0() + gb.x1()) * 0.5f;
                if (mid <= bandX0 + 0.5f || mid >= bandX1 - 0.5f) continue;
                if (requireSomeClean) {
                    int clean = 0;
                    for (StreamTableExtractor.Line l : lines) {
                        boolean hit = false;
                        for (StreamTableExtractor.Word w : l.words) if (w.x0 < gb.x1() && w.x1 > gb.x0()) { hit = true; break; }
                        if (!hit) clean++;
                    }
                    if (clean * 2 < lines.size()) continue;
                }
                StreamTableExtractor.Gutter g = new StreamTableExtractor.Gutter();
                g.x0 = gb.x0(); g.x1 = gb.x1(); g.rowsCovered = lines.size();
                out.add(g);
            }
            out.sort(Comparator.comparingDouble(g -> g.x0));
            // collapse overlaps (two GT tables on one page can contribute crossing boundaries)
            List<StreamTableExtractor.Gutter> keep = new ArrayList<>();
            for (StreamTableExtractor.Gutter g : out) {
                if (!keep.isEmpty()) {
                    StreamTableExtractor.Gutter last = keep.get(keep.size() - 1);
                    if (g.x0 < last.x1) { last.x1 = Math.max(last.x1, g.x1); continue; }
                }
                keep.add(g);
            }
            return keep;
        }
        @Override public String name() { return "oracle"; }
    }

    /**
     * THE DECISIVE INSTRUMENT. Production's own gutters, PLUS the ground-truth column boundaries
     * production missed -- i.e. the BEST POSSIBLE outcome of "fix candidate generation": exactly
     * the right extra columns, and no wrong ones. If this does not beat the baseline, no
     * implementation of this lever can, because any real implementation is a noisy approximation
     * of it.
     *
     * <p>{@code mode}: "all" = every missed GT boundary inside the band; "sweepable" = only those
     * an exhaustive strip sweep would actually be able to propose (the realistic ceiling for a
     * candidate-generation fix); "cleanOnly" = every missed GT boundary that is clean on a
     * majority of the block's rows.
     */
    static final class OracleAugmentedFinder implements GutterFinder {
        private final List<GtBoundary> bounds;
        private final String mode;
        OracleAugmentedFinder(List<GtBoundary> bounds, String mode) { this.bounds = bounds; this.mode = mode; }
        @Override public List<StreamTableExtractor.Gutter> find(List<StreamTableExtractor.Line> lines,
                float bandX0, float bandX1, float medianSpace) {
            List<StreamTableExtractor.Gutter> prod = new ArrayList<>(
                    StreamTableExtractor.findGutters(lines, bandX0, bandX1, medianSpace));
            BlockCtx b = new BlockCtx("", 0, 0, lines, bandX0, bandX1, medianSpace);
            boolean sweepMode = mode.equals("sweepable") || mode.equals("guarded");
            List<Strip> sweep = sweepMode ? sweepStrips(b, -1) : List.of();
            for (GtBoundary gb : bounds) {
                float mid = (gb.x0() + gb.x1()) * 0.5f;
                if (mid <= bandX0 + 0.5f || mid >= bandX1 - 0.5f) continue;
                boolean clash = false;
                for (StreamTableExtractor.Gutter g : prod) if (gb.x0() < g.x1 && gb.x1() > g.x0) { clash = true; break; }
                if (clash) continue;
                float gx0 = gb.x0(), gx1 = gb.x1();
                if (sweepMode) {
                    Strip hit = null;
                    for (Strip s : sweep) if (mid >= s.x0() - 0.5f && mid <= s.x1() + 0.5f) { hit = s; break; }
                    if (hit == null) continue;
                    gx0 = hit.x0(); gx1 = hit.x1();     // use the geometry a real finder would emit
                } else if (mode.equals("cleanOnly")) {
                    int clean = 0;
                    for (StreamTableExtractor.Line l : lines) {
                        boolean any = false;
                        for (StreamTableExtractor.Word w : l.words) if (w.x0 < gx1 && w.x1 > gx0) { any = true; break; }
                        if (!any) clean++;
                    }
                    if (clean * 2 < lines.size()) continue;
                }
                boolean clash2 = false;
                for (StreamTableExtractor.Gutter g : prod) if (gx0 < g.x1 && gx1 > g.x0) { clash2 = true; break; }
                if (clash2) continue;
                StreamTableExtractor.Gutter g = new StreamTableExtractor.Gutter();
                g.x0 = gx0; g.x1 = gx1; g.rowsCovered = lines.size();
                prod.add(g);
            }
            prod.sort(Comparator.comparingDouble(g -> g.x0));
            if (mode.equals("guarded")) {
                // strongest implementable form: keep the augmentation only when it does not push a
                // block that ALREADY passed the gridness gate back below it (a real implementation
                // can evaluate both grids and choose; this is an upper bound on that design).
                List<StreamTableExtractor.Gutter> before =
                        StreamTableExtractor.findGutters(lines, bandX0, bandX1, medianSpace);
                double c0 = confOf(b, before), c1 = confOf(b, prod);
                boolean p0 = c0 >= StreamTableExtractor.STREAM_CONFIDENCE_MIN;
                boolean p1 = c1 >= StreamTableExtractor.STREAM_CONFIDENCE_MIN;
                if (p0 && !p1) return before;
            }
            return prod;
        }
        @Override public String name() { return "oracleAug-" + mode; }
    }

    private static final class Agg {
        long adjMatched, adjDet, adjGt, tp, fp, fn;
        int detected, errors, dimsExact, paired, n;
        List<Double> adjF1s = new ArrayList<>();
        void add(BakeOffHarness.PdfScore s) {
            n++;
            adjMatched += s.adjMatched(); adjDet += s.adjDetectedTotal(); adjGt += s.adjGtTotal();
            tp += s.tp(); fp += s.fp(); fn += s.fn();
            if (s.detected()) detected++;
            if (s.error() != null) errors++;
            dimsExact += s.dimsExactMatches(); paired += s.pairedTables();
            adjF1s.add(s.adjF1());
        }
        double adjP() { return adjDet == 0 ? 0 : (double) adjMatched / adjDet; }
        double adjR() { return adjGt == 0 ? 0 : (double) adjMatched / adjGt; }
        double adjF1() { double p = adjP(), r = adjR(); return (p + r) == 0 ? 0 : 2 * p * r / (p + r); }
        double adjMac() { return adjF1s.stream().mapToDouble(Double::doubleValue).average().orElse(0); }
        double exF1() {
            double p = (tp + fp) == 0 ? 0 : (double) tp / (tp + fp);
            double r = (tp + fn) == 0 ? 0 : (double) tp / (tp + fn);
            return (p + r) == 0 ? 0 : 2 * p * r / (p + r);
        }
        String row(String label) {
            return String.format(Locale.ROOT,
                    "%-22s n=%-3d exF1=%s adjP=%s adjR=%s adjF1=%s adjMac=%s dims=%s det=%s err=%d",
                    label, n, f3(exF1()), f3(adjP()), f3(adjR()), f3(adjF1()), f3(adjMac()),
                    f3(paired == 0 ? 0 : dimsExact / (double) paired),
                    f3(n == 0 ? 0 : detected / (double) n), errors);
        }
    }

    private static void sectionC(List<BakeOffHarness.ScoreUnit> units) throws Exception {
        p("");
        p("================= SECTION C: end-to-end adjacency F1 (77-PDF scoring set) =================");
        Map<String, List<GtBoundary>> gt = gtBoundaries();

        Map<String, GutterFinder> finders = new LinkedHashMap<>();
        finders.put("breuel(baseline)", new BreuelGutterFinder());
        finders.put("breuel+sweep(prod)", new SweepAugmentedFinder(-1, "bsweep"));
        finders.put("breuel+sweep(0.60)", new SweepAugmentedFinder(0.60, "bsweep60"));
        finders.put("breuel+sweep(0.80)", new SweepAugmentedFinder(0.80, "bsweep80"));
        finders.put("breuel+sweep(1.00)", new SweepAugmentedFinder(1.00, "bsweep100"));
        finders.put("sweepOnly(prod)", new SweepOnlyFinder(-1, "sweep"));
        finders.put("sweepOnly(0.60)", new SweepOnlyFinder(0.60, "sweep60"));

        List<String> named = List.of("spanning_cells.pdf", "us-018.pdf", "us-007.pdf", "us-020.pdf",
                "eu-027.pdf", "eu-004.pdf", "us-017.pdf", "us-012.pdf");
        Map<String, Map<String, Double>> perPdf = new TreeMap<>();
        Map<String, Map<String, Integer>> perPdfHits = new TreeMap<>();

        for (Map.Entry<String, GutterFinder> e : finders.entrySet()) {
            Agg all = new Agg(), icdarOnly = new Agg();
            for (BakeOffHarness.ScoreUnit u : units) {
                BakeOffHarness.PdfScore s = BakeOffHarness.scoreUnit(e.getValue(), u);
                all.add(s);
                if (gt.containsKey(u.id())) icdarOnly.add(s);
                String sid = shortId(u.id());
                if (named.contains(sid)) {
                    perPdf.computeIfAbsent(sid, k -> new LinkedHashMap<>()).put(e.getKey(), s.adjF1());
                    perPdfHits.computeIfAbsent(sid, k -> new LinkedHashMap<>())
                            .put(e.getKey(), BakeOffHarness.runFinderOnPdf(e.getValue(), u.pdf()).hits().size());
                }
            }
            p(all.row(e.getKey()));
            p("   (ICDAR-bbox subset) " + icdarOnly.row(e.getKey()));
        }

        // oracle: only meaningful on PDFs with bbox ground truth
        for (boolean requireClean : new boolean[]{false, true}) {
            Agg agg = new Agg();
            for (BakeOffHarness.ScoreUnit u : units) {
                List<GtBoundary> bs = gt.get(u.id());
                if (bs == null || bs.isEmpty()) continue;
                agg.add(BakeOffHarness.scoreUnit(new OracleFinder(bs, requireClean), u));
            }
            p(agg.row("ORACLE-GT-only" + (requireClean ? "(clean)" : "(raw)")));
        }
        // the decisive instrument: production + EXACTLY the GT boundaries it missed
        for (String mode : new String[]{"all", "cleanOnly", "sweepable", "guarded"}) {
            Agg agg = new Agg();
            Map<String, Double> perNamed = new TreeMap<>();
            for (BakeOffHarness.ScoreUnit u : units) {
                List<GtBoundary> bs = gt.get(u.id());
                if (bs == null || bs.isEmpty()) continue;
                BakeOffHarness.PdfScore s = BakeOffHarness.scoreUnit(new OracleAugmentedFinder(bs, mode), u);
                agg.add(s);
                if (named.contains(shortId(u.id()))) perNamed.put(shortId(u.id()), s.adjF1());
            }
            p(agg.row("ORACLE-AUG(" + mode + ")"));
            p("      named: " + perNamed.entrySet().stream()
                    .map(en -> en.getKey() + "=" + f3(en.getValue())).collect(Collectors.joining(" ")));
        }
        // breuel restricted to the same subset, for a like-for-like oracle comparison
        Agg base = new Agg();
        for (BakeOffHarness.ScoreUnit u : units) {
            List<GtBoundary> bs = gt.get(u.id());
            if (bs == null || bs.isEmpty()) continue;
            base.add(BakeOffHarness.scoreUnit(new BreuelGutterFinder(), u));
        }
        p(base.row("breuel(same subset)"));

        p("");
        p("named fixtures, adjacency F1 (and hit count) per finder:");
        for (Map.Entry<String, Map<String, Double>> e : perPdf.entrySet()) {
            StringBuilder sb = new StringBuilder(String.format("  %-22s", e.getKey()));
            for (String fname : finders.keySet()) {
                Double v = e.getValue().get(fname);
                Integer h = perPdfHits.get(e.getKey()).get(fname);
                sb.append(String.format(Locale.ROOT, " %s=%s/%s", fname, v == null ? "-" : f3(v),
                        h == null ? "-" : h.toString()));
            }
            p(sb.toString());
        }
    }

    // ============== SECTION E: why does adding a CORRECT column boundary lose relations? ========

    private static void sectionE(List<BakeOffHarness.ScoreUnit> units, String only) throws Exception {
        p("");
        p("===== SECTION E: per-hit before/after for breuel vs breuel+missed-GT-boundaries =====");
        Map<String, List<GtBoundary>> gt = gtBoundaries();
        for (BakeOffHarness.ScoreUnit u : units) {
            if (!only.isEmpty() && !shortId(u.id()).contains(only)) continue;
            List<GtBoundary> bs = gt.get(u.id());
            if (bs == null) continue;
            GutterFinder base = new BreuelGutterFinder();
            GutterFinder aug = new OracleAugmentedFinder(bs, "sweepable");
            for (GutterFinder f : List.of(base, aug)) {
                BakeOffHarness.PdfScore sc = BakeOffHarness.scoreUnit(f, u);
                List<TableExtractor.TableHit> hits = BakeOffHarness.runFinderOnPdf(f, u.pdf()).hits();
                p(String.format(Locale.ROOT, "  %s finder=%-22s adjF1=%s (matched=%d det=%d gt=%d) hits=%d",
                        shortId(u.id()), f.name(), f3(sc.adjF1()), sc.adjMatched(),
                        sc.adjDetectedTotal(), sc.adjGtTotal(), hits.size()));
                for (TableExtractor.TableHit h : hits) {
                    p(String.format(Locale.ROOT, "      hit p%d rows=%d cols=%d conf=%.3f  row0=%s | row1=%s",
                            h.page, h.rowCount, h.colCount, h.confidence,
                            h.rows.isEmpty() ? "-" : String.join("¦", h.rows.get(0)),
                            h.rows.size() < 2 ? "-" : String.join("¦", h.rows.get(1))));
                }
                // gutter geometry per block
                for (BlockCtx b : blocksOf(u)) {
                    List<StreamTableExtractor.Gutter> gs;
                    try { gs = f.find(b.lines(), b.bandX0(), b.bandX1(), b.medianSpace()); }
                    catch (TableExtractor.RulingOverflowException e) { gs = List.of(); }
                    if (gs.isEmpty()) continue;
                    float[] bounds = new float[gs.size() + 2];
                    bounds[0] = b.bandX0(); bounds[bounds.length - 1] = b.bandX1();
                    for (int i = 0; i < gs.size(); i++) bounds[i + 1] = gs.get(i).cx();
                    int anchor = StreamTableExtractor.findAnchorColumn(b.lines(), bounds);
                    int logical = StreamTableExtractor.groupLogicalRows(b.lines(), bounds).size();
                    StreamTableExtractor.Grid grid = StreamTableExtractor.scoreGrid(
                            StreamTableExtractor.trimEdgeLines(b.lines(), gs, b.bandX0(), b.bandX1(), b.medianSpace()),
                            gs, b.bandX0(), b.bandX1());
                    StringBuilder cx = new StringBuilder();
                    for (StreamTableExtractor.Gutter g : gs) cx.append(String.format(Locale.ROOT, " %.0f", g.cx()));
                    p(String.format(Locale.ROOT,
                            "      block p%d b%d rows=%d gutters=%d anchorCol=%d logicalRows=%d conf=%.3f cx=[%s]",
                            b.page(), b.blockIdx(), b.lines().size(), gs.size(), anchor, logical,
                            grid.confidence, cx.toString().trim()));
                }
            }
        }
    }

    // ===== SECTION F: gate-crossing census + does the lever pay where the gate does not move? ===

    private static void sectionF(List<BakeOffHarness.ScoreUnit> units) throws Exception {
        p("");
        p("===== SECTION F: confidence-gate census for breuel vs breuel+missed-GT-boundaries =====");
        Map<String, List<GtBoundary>> gt = gtBoundaries();
        int lostGate = 0, gainedGate = 0, bothPass = 0, bothFail = 0, blocksChangedGutters = 0, blocksTotal = 0;
        Agg stableBase = new Agg(), stableAug = new Agg(), movedBase = new Agg(), movedAug = new Agg();
        List<String> gateLosses = new ArrayList<>();
        for (BakeOffHarness.ScoreUnit u : units) {
            List<GtBoundary> bs = gt.get(u.id());
            if (bs == null) continue;
            GutterFinder base = new BreuelGutterFinder();
            GutterFinder aug = new OracleAugmentedFinder(bs, "sweepable");
            for (BlockCtx b : blocksOf(u)) {
                blocksTotal++;
                List<StreamTableExtractor.Gutter> g1, g2;
                try { g1 = base.find(b.lines(), b.bandX0(), b.bandX1(), b.medianSpace()); }
                catch (TableExtractor.RulingOverflowException e) { g1 = List.of(); }
                try { g2 = aug.find(b.lines(), b.bandX0(), b.bandX1(), b.medianSpace()); }
                catch (TableExtractor.RulingOverflowException e) { g2 = List.of(); }
                if (g1.size() == g2.size()) continue;
                blocksChangedGutters++;
                double c1 = confOf(b, g1), c2 = confOf(b, g2);
                boolean p1 = c1 >= StreamTableExtractor.STREAM_CONFIDENCE_MIN;
                boolean p2 = c2 >= StreamTableExtractor.STREAM_CONFIDENCE_MIN;
                if (p1 && !p2) {
                    lostGate++;
                    gateLosses.add(String.format(Locale.ROOT,
                            "    LOST %s p%d b%d rows=%d gutters %d->%d conf %.3f->%.3f",
                            shortId(u.id()), b.page(), b.blockIdx(), b.lines().size(),
                            g1.size(), g2.size(), c1, c2));
                } else if (!p1 && p2) gainedGate++;
                else if (p1) bothPass++;
                else bothFail++;
            }
            BakeOffHarness.PdfScore s1 = BakeOffHarness.scoreUnit(base, u);
            BakeOffHarness.PdfScore s2 = BakeOffHarness.scoreUnit(aug, u);
            int h1 = BakeOffHarness.runFinderOnPdf(base, u.pdf()).hits().size();
            int h2 = BakeOffHarness.runFinderOnPdf(aug, u.pdf()).hits().size();
            if (h1 == h2) { stableBase.add(s1); stableAug.add(s2); }
            else { movedBase.add(s1); movedAug.add(s2); }
        }
        p("blocks total=" + blocksTotal + " with a changed gutter set=" + blocksChangedGutters
                + " | gate: lost=" + lostGate + " gained=" + gainedGate
                + " bothAboveGate=" + bothPass + " bothBelowGate(no table either way)=" + bothFail);
        for (String s : gateLosses) p(s);
        p("  SUBSET where the per-PDF hit COUNT did not change (gate interaction removed):");
        p("    " + stableBase.row("breuel"));
        p("    " + stableAug.row("+missed-GT-boundaries"));
        p("  SUBSET where the hit count DID change:");
        p("    " + movedBase.row("breuel"));
        p("    " + movedAug.row("+missed-GT-boundaries"));
    }

    private static double confOf(BlockCtx b, List<StreamTableExtractor.Gutter> gs) {
        List<StreamTableExtractor.Line> trimmed = StreamTableExtractor.trimEdgeLines(
                b.lines(), gs, b.bandX0(), b.bandX1(), b.medianSpace());
        if (trimmed.size() < 3) return 0;
        return StreamTableExtractor.scoreGrid(trimmed, gs, b.bandX0(), b.bandX1()).confidence;
    }

    // ================================================================ SECTION D: prose FP check

    private static void sectionD() throws Exception {
        p("");
        p("================= SECTION D: prose false-positive upper bound (page 1) =================");
        if (!Files.isDirectory(PHISH_ROOT)) { p("phishpdfs corpus absent -- skipped"); return; }
        List<Path> all;
        try (Stream<Path> s = Files.list(PHISH_ROOT)) {
            all = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        List<Path> pdfs = new ArrayList<>();
        for (Path pp : all) if (looksLikePdf(pp)) pdfs.add(pp);
        List<Path> sample = new ArrayList<>();
        if (pdfs.size() <= PROSE_SAMPLE_CAP) sample = pdfs;
        else {
            int step = (int) Math.ceil(pdfs.size() / (double) PROSE_SAMPLE_CAP);
            for (int i = 0; i < pdfs.size() && sample.size() < PROSE_SAMPLE_CAP; i += step) sample.add(pdfs.get(i));
        }
        Map<String, GutterFinder> finders = new LinkedHashMap<>();
        finders.put("breuel(baseline)", new BreuelGutterFinder());
        finders.put("breuel+sweep(prod)", new SweepAugmentedFinder(-1, "bsweep"));
        finders.put("breuel+sweep(0.60)", new SweepAugmentedFinder(0.60, "bsweep60"));
        finders.put("breuel+sweep(0.80)", new SweepAugmentedFinder(0.80, "bsweep80"));
        finders.put("breuel+sweep(1.00)", new SweepAugmentedFinder(1.00, "bsweep100"));
        finders.put("sweepOnly(prod)", new SweepOnlyFinder(-1, "sweep"));
        for (Map.Entry<String, GutterFinder> e : finders.entrySet()) {
            int flagged = 0;
            for (Path pp : sample) if (hasTableOnPage1(e.getValue(), pp)) flagged++;
            p(String.format(Locale.ROOT, "  %-22s proseFP=%s (%d/%d)", e.getKey(),
                    f3(flagged / (double) sample.size()), flagged, sample.size()));
        }
    }

    private static boolean looksLikePdf(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[5];
            int n = in.read(buf);
            return n >= 4 && buf[0] == '%' && buf[1] == 'P' && buf[2] == 'D' && buf[3] == 'F';
        } catch (IOException e) { return false; }
    }

    private static boolean hasTableOnPage1(GutterFinder finder, Path pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (doc.getNumberOfPages() < 1) return false;
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            return !StreamTableExtractor.extractPage(1, glyphs, finder).isEmpty();
        } catch (Throwable t) { return false; }
    }
}
