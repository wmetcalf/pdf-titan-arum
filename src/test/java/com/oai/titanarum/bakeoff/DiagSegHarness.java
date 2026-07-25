// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/, but declares
// `package com.oai.titanarum;` -- same trick BakeOffHarness / Diag9cHarness / Diag9jHarness use,
// for the same reason (StreamTableExtractor and its Word/Line/Gutter/Grid types, GutterFinder,
// BreuelGutterFinder and TableExtractor.TableHit are all package-private).
//
// THROWAWAY DIAGNOSTIC (block segmentation / table-count matching lever). Purely observational:
// modifies NOTHING in src/main and does not touch TableScore/GroundTruth scoring logic. It
// re-implements extractPage's per-block LOOP so alternative block partitions can be scored, and
// asserts (section S0) that its replication of the production partition reproduces the production
// pipeline's own hits byte-for-byte on all 77 PDFs.
//
// Run: mvn -q -o test -Dtest=DiagSegHarness -DdiagSeg=true
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.TableScore;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class DiagSegHarness {

    private static final Path TABULA_RESOURCES =
            Path.of("corpus/tabula-java/src/test/resources").toAbsolutePath().normalize();
    private static final Path ICDAR_ROOT =
            TABULA_RESOURCES.resolve("technology/tabula/icdar2013-dataset");

    private static final Method BUILD_HIT;
    static {
        try {
            BUILD_HIT = StreamTableExtractor.class.getDeclaredMethod(
                    "buildHit", int.class, StreamTableExtractor.Grid.class);
            BUILD_HIT.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // ------------------------------------------------------------------ page / block plumbing

    static final class Page {
        int pageNum;
        List<StreamTableExtractor.Line> lines = List.of();
        float medianSpace;
        float height;      // crop-box height, for GT (bottom-left) -> our (top-left) y conversion
    }

    /** Per-page words/lines exactly as extractPage builds them (same caps, same early-outs). */
    static List<Page> loadPages(Path pdf) {
        List<Page> out = new ArrayList<>();
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                Page p = new Page();
                p.pageNum = i + 1;
                PDRectangle crop = doc.getPage(i).getCropBox();
                p.height = crop.getHeight();
                out.add(p);
                List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, i);
                try {
                    List<StreamTableExtractor.Word> words = StreamTableExtractor.buildWords(glyphs);
                    if (words.size() < 6) continue;
                    float mfs = StreamTableExtractor.medianFontSize(words);
                    List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(words, mfs);
                    if (lines.size() < 3) continue;
                    p.lines = lines;
                    p.medianSpace = 0.5f * mfs;
                } catch (TableExtractor.RulingOverflowException e) {
                    /* page-global DoS budget -> no lines for this page, exactly like production */
                }
            }
        } catch (Throwable t) {
            return out;
        }
        return out;
    }

    /** extractPage's per-block body for ONE block: band -> finder -> trim -> score -> buildHit. */
    static TableExtractor.TableHit runOneBlock(int pageNum, List<StreamTableExtractor.Line> block,
                                              GutterFinder finder, float medianSpace, double gate) {
        if (block.size() < 3) return null;
        try {
            float bandX0 = Float.MAX_VALUE, bandX1 = -Float.MAX_VALUE;
            for (StreamTableExtractor.Line l : block) for (StreamTableExtractor.Word w : l.words) {
                bandX0 = Math.min(bandX0, w.x0); bandX1 = Math.max(bandX1, w.x1);
            }
            List<StreamTableExtractor.Gutter> gutters = finder.find(block, bandX0, bandX1, medianSpace);
            List<StreamTableExtractor.Line> trimmed =
                    StreamTableExtractor.trimEdgeLines(block, gutters, bandX0, bandX1, medianSpace);
            if (trimmed.size() < 3) return null;
            StreamTableExtractor.Grid grid =
                    StreamTableExtractor.scoreGrid(trimmed, gutters, bandX0, bandX1);
            if (grid.confidence < gate) return null;
            return (TableExtractor.TableHit) BUILD_HIT.invoke(null, pageNum, grid);
        } catch (Throwable t) {
            return null;   // RulingOverflowException (or its InvocationTargetException wrapper)
        }                  // -> skip this block only, exactly like production
    }

    /** Confidence a block would score (or -1 if it can't produce one) -- observability only. */
    static double blockConfidence(List<StreamTableExtractor.Line> block, GutterFinder finder, float medianSpace) {
        if (block.size() < 3) return -1;
        try {
            float bandX0 = Float.MAX_VALUE, bandX1 = -Float.MAX_VALUE;
            for (StreamTableExtractor.Line l : block) for (StreamTableExtractor.Word w : l.words) {
                bandX0 = Math.min(bandX0, w.x0); bandX1 = Math.max(bandX1, w.x1);
            }
            List<StreamTableExtractor.Gutter> gutters = finder.find(block, bandX0, bandX1, medianSpace);
            List<StreamTableExtractor.Line> trimmed =
                    StreamTableExtractor.trimEdgeLines(block, gutters, bandX0, bandX1, medianSpace);
            if (trimmed.size() < 3) return -1;
            return StreamTableExtractor.scoreGrid(trimmed, gutters, bandX0, bandX1).confidence;
        } catch (Throwable t) {
            return -1;
        }
    }

    static List<StreamTableExtractor.Gutter> blockGutters(List<StreamTableExtractor.Line> block,
                                                          GutterFinder finder, float medianSpace) {
        try {
            float bandX0 = Float.MAX_VALUE, bandX1 = -Float.MAX_VALUE;
            for (StreamTableExtractor.Line l : block) for (StreamTableExtractor.Word w : l.words) {
                bandX0 = Math.min(bandX0, w.x0); bandX1 = Math.max(bandX1, w.x1);
            }
            return finder.find(block, bandX0, bandX1, medianSpace);
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** extractPage's own page loop (incl. the per-page block-work budget) over a GIVEN partition. */
    static List<TableExtractor.TableHit> runPartition(Page page, List<List<StreamTableExtractor.Line>> blocks,
                                                      GutterFinder finder, double gate) {
        List<TableExtractor.TableHit> hits = new ArrayList<>();
        long pageWork = 0;
        for (List<StreamTableExtractor.Line> block : blocks) {
            if (hits.size() >= StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE) break;
            if (block.size() < 3) continue;
            long charge = block.stream().mapToLong(l -> l.words.size()).sum();
            if (pageWork + charge > StreamTableExtractor.MAX_STREAM_PAGE_BLOCK_WORK) break;
            pageWork += charge;
            TableExtractor.TableHit h = runOneBlock(page.pageNum, block, finder, page.medianSpace, gate);
            if (h != null) hits.add(h);
        }
        return hits;
    }

    // --------------------------------------------------------------------------------- scoring

    /** Mirrors BakeOffHarness#scoreUnit's pairing policy exactly, but over caller-supplied hits,
     *  and additionally splits the adjacency totals into paired-table vs unpaired-table parts. */
    record Sc(int matched, int det, int gt, double f1,
              int pairedTables, int leftoverHits, int leftoverDet, int unpairedGtRel) {
        double p() { return det == 0 ? 0 : (double) matched / det; }
        double r() { return gt == 0 ? 0 : (double) matched / gt; }
    }

    static Sc score(BakeOffHarness.ScoreUnit unit, List<TableExtractor.TableHit> hits) {
        List<TableExtractor.TableHit> available = new ArrayList<>(hits);
        int matched = 0, det = 0, gt = 0, paired = 0, leftoverDet = 0, unpairedGtRel = 0;
        for (GroundTruth.Table expected : unit.expected()) {
            if (available.isEmpty()) {
                int rc = TableScore.relationCount(expected.rows());
                gt += rc; unpairedGtRel += rc;
                continue;
            }
            TableExtractor.TableHit best = null;
            double bestF1 = -1;
            for (TableExtractor.TableHit h : available) {
                double f = TableScore.score(expected, h.rows).f1();
                if (f > bestF1) { bestF1 = f; best = h; }
            }
            available.remove(best);
            TableScore.AdjResult adj = TableScore.scoreAdjacency(expected, best.rows);
            matched += adj.matched(); det += adj.detectedTotal(); gt += adj.gtTotal();
            paired++;
        }
        int leftoverHits = available.size();
        for (TableExtractor.TableHit h : available) {
            int rc = TableScore.relationCount(h.rows);
            det += rc; leftoverDet += rc;
        }
        double p = det == 0 ? 0 : (double) matched / det;
        double r = gt == 0 ? 0 : (double) matched / gt;
        double f1 = matched == 0 ? 0 : 2 * p * r / (p + r);
        return new Sc(matched, det, gt, f1, paired, leftoverHits, leftoverDet, unpairedGtRel);
    }

    static final class Agg {
        String label; int n; long matched, det, gt; double macroSum;
        Agg(String label) { this.label = label; }
        void add(Sc s) { n++; matched += s.matched(); det += s.det(); gt += s.gt(); macroSum += s.f1(); }
        double p() { return det == 0 ? 0 : (double) matched / det; }
        double r() { return gt == 0 ? 0 : (double) matched / gt; }
        double f1() { double a = p(), b = r(); return (a + b) == 0 ? 0 : 2 * a * b / (a + b); }
        double macro() { return n == 0 ? 0 : macroSum / n; }
        @Override public String toString() {
            return String.format(Locale.ROOT, "  %-34s n=%-4d adjP=%.4f adjR=%.4f adjF1=%.4f adjMacF1=%.4f",
                    label, n, p(), r(), f1(), macro());
        }
    }

    // ---------------------------------------------------------------- GT geometry (bbox + page)

    record GtGeom(int page, float yTopTL, float yBotTL, float x0, float x1, int cells) {}

    private static final Pattern TABLE_START = Pattern.compile("<table\\b[^>]*>");
    private static final Pattern REGION_START = Pattern.compile("<region\\b([^>]*)>");
    private static final Pattern BBOX = Pattern.compile("<bounding-box\\b([^>]*)/?>");
    private static final Pattern ATTR = Pattern.compile("(\\S+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    private static Map<String, String> attrs(String s) {
        Map<String, String> m = new LinkedHashMap<>();
        Matcher am = ATTR.matcher(s);
        while (am.find()) m.put(am.group(1), am.group(2) != null ? am.group(2) : am.group(3));
        return m;
    }

    /** One entry per GT <table> per page it has cells on: the union bbox of its cells, converted
     *  from ICDAR's bottom-left PDF coords to our top-left coords using the page's crop height. */
    static List<GtGeom> gtGeoms(Path strXml, Map<Integer, Float> pageHeights) throws IOException {
        String text = Files.readString(strXml, StandardCharsets.UTF_8);
        List<Integer> starts = new ArrayList<>();
        Matcher tm = TABLE_START.matcher(text);
        while (tm.find()) starts.add(tm.start());
        List<GtGeom> out = new ArrayList<>();
        if (starts.isEmpty()) return out;
        for (int t = 0; t < starts.size(); t++) {
            int s = starts.get(t), e = (t + 1 < starts.size()) ? starts.get(t + 1) : text.length();
            String chunk = text.substring(s, e);
            // per-region page attribute; regions delimit which page their cells' bboxes belong to
            Matcher rm = REGION_START.matcher(chunk);
            List<int[]> regionSpans = new ArrayList<>();  // {bodyStart, page}
            List<Integer> bodyStarts = new ArrayList<>();
            List<Integer> pages = new ArrayList<>();
            while (rm.find()) {
                bodyStarts.add(rm.end());
                pages.add(Integer.parseInt(attrs(rm.group(1)).getOrDefault("page", "1")));
            }
            if (bodyStarts.isEmpty()) { bodyStarts.add(0); pages.add(1); }
            Map<Integer, float[]> byPage = new LinkedHashMap<>();  // page -> {minY1,maxY2,minX1,maxX2,count}
            for (int i = 0; i < bodyStarts.size(); i++) {
                int bs = bodyStarts.get(i);
                int be = (i + 1 < bodyStarts.size()) ? bodyStarts.get(i + 1) : chunk.length();
                Matcher bm = BBOX.matcher(chunk.substring(bs, be));
                int pg = pages.get(i);
                while (bm.find()) {
                    Map<String, String> a = attrs(bm.group(1));
                    try {
                        float x1 = Float.parseFloat(a.get("x1")), y1 = Float.parseFloat(a.get("y1"));
                        float x2 = Float.parseFloat(a.get("x2")), y2 = Float.parseFloat(a.get("y2"));
                        float[] acc = byPage.computeIfAbsent(pg,
                                k -> new float[]{Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, 0});
                        acc[0] = Math.min(acc[0], y1); acc[1] = Math.max(acc[1], y2);
                        acc[2] = Math.min(acc[2], x1); acc[3] = Math.max(acc[3], x2);
                        acc[4]++;
                    } catch (RuntimeException ignore) { /* malformed bbox */ }
                }
            }
            for (Map.Entry<Integer, float[]> en : byPage.entrySet()) {
                int pg = en.getKey();
                float[] a = en.getValue();
                float h = pageHeights.getOrDefault(pg, 792f);
                out.add(new GtGeom(pg, h - a[1], h - a[0], a[2], a[3], (int) a[4]));
            }
        }
        return out;
    }

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

    // ------------------------------------------------------------------------- alt partitions

    static List<List<StreamTableExtractor.Line>> noSplit(Page p) {
        return p.lines.isEmpty() ? List.of() : List.of(p.lines);
    }

    static List<List<StreamTableExtractor.Line>> gapSplit(Page p) {
        return p.lines.isEmpty() ? List.of() : StreamTableExtractor.splitIntoBlocks(p.lines);
    }

    /** Two-pass: coarse gap split, then re-merge adjacent blocks whose column models agree. */
    static List<List<StreamTableExtractor.Line>> colModelMerge(Page p, GutterFinder finder,
                                                               float tolFactor, double minFrac) {
        List<List<StreamTableExtractor.Line>> base = gapSplit(p);
        if (base.size() < 2) return base;
        List<List<StreamTableExtractor.Gutter>> gs = new ArrayList<>();
        for (List<StreamTableExtractor.Line> b : base) gs.add(blockGutters(b, finder, p.medianSpace));
        List<List<StreamTableExtractor.Line>> out = new ArrayList<>();
        List<StreamTableExtractor.Line> cur = new ArrayList<>(base.get(0));
        List<StreamTableExtractor.Gutter> curG = gs.get(0);
        for (int i = 1; i < base.size(); i++) {
            if (colModelAgrees(curG, gs.get(i), tolFactor * p.medianSpace, minFrac)) {
                cur.addAll(base.get(i));
                // keep the accumulated group's model as the (richer) reference
                if (gs.get(i).size() > curG.size()) curG = gs.get(i);
            } else {
                out.add(cur);
                cur = new ArrayList<>(base.get(i));
                curG = gs.get(i);
            }
        }
        out.add(cur);
        return out;
    }

    static double agreeFrac(List<StreamTableExtractor.Gutter> a, List<StreamTableExtractor.Gutter> b, float tol) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int matches = 0;
        for (StreamTableExtractor.Gutter x : a) {
            for (StreamTableExtractor.Gutter y : b) {
                if (Math.abs(x.cx() - y.cx()) <= tol) { matches++; break; }
            }
        }
        return matches / (double) Math.min(a.size(), b.size());
    }

    static boolean colModelAgrees(List<StreamTableExtractor.Gutter> a, List<StreamTableExtractor.Gutter> b,
                                   float tol, double minFrac) {
        if (a.isEmpty() || b.isEmpty()) return false;
        int matches = 0;
        for (StreamTableExtractor.Gutter x : a) {
            for (StreamTableExtractor.Gutter y : b) {
                if (Math.abs(x.cx() - y.cx()) <= tol) { matches++; break; }
            }
        }
        int minSize = Math.min(a.size(), b.size());
        return matches >= Math.max(1, (int) Math.ceil(minFrac * minSize));
    }

    /** Agglomerative: merge adjacent blocks while the MERGED block's own gridness confidence is
     *  at least as good as the better of the two parts (and clears the production gate). */
    static List<List<StreamTableExtractor.Line>> confMerge(Page p, GutterFinder finder, double slack) {
        List<List<StreamTableExtractor.Line>> base = gapSplit(p);
        if (base.size() < 2) return base;
        List<List<StreamTableExtractor.Line>> out = new ArrayList<>();
        List<StreamTableExtractor.Line> cur = new ArrayList<>(base.get(0));
        double curConf = blockConfidence(cur, finder, p.medianSpace);
        for (int i = 1; i < base.size(); i++) {
            List<StreamTableExtractor.Line> cand = new ArrayList<>(cur);
            cand.addAll(base.get(i));
            double nextConf = blockConfidence(base.get(i), finder, p.medianSpace);
            double mergedConf = blockConfidence(cand, finder, p.medianSpace);
            if (mergedConf >= StreamTableExtractor.STREAM_CONFIDENCE_MIN
                    && mergedConf + slack >= Math.max(curConf, nextConf)) {
                cur = cand; curConf = mergedConf;
            } else {
                out.add(cur); cur = new ArrayList<>(base.get(i)); curConf = nextConf;
            }
        }
        out.add(cur);
        return out;
    }

    // ------------------------------------------------------------------------------------ main

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("diagSeg"), "set -DdiagSeg=true to run");

        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        List<BakeOffHarness.ScoreUnit> units = corpus.units;
        GutterFinder breuel = new BreuelGutterFinder();
        Map<String, List<Path>> xmlsById = icdarXmlsByPdfId();

        System.out.println("Scoring set: " + units.size() + " PDFs");

        // per-unit cached page data
        Map<String, List<Page>> pagesById = new LinkedHashMap<>();
        for (BakeOffHarness.ScoreUnit u : units) pagesById.put(u.id(), loadPages(u.pdf()));

        // ---------------------------------------------------------------- S0: replication check
        System.out.println();
        System.out.println("== S0: replicated partition pipeline vs production extractPage ==");
        int mismatches = 0;
        for (BakeOffHarness.ScoreUnit u : units) {
            List<TableExtractor.TableHit> prod = BakeOffHarness.runFinderOnPdf(breuel, u.pdf()).hits();
            List<TableExtractor.TableHit> mine = new ArrayList<>();
            for (Page p : pagesById.get(u.id()))
                mine.addAll(runPartition(p, gapSplit(p), breuel, StreamTableExtractor.STREAM_CONFIDENCE_MIN));
            if (prod.size() != mine.size() || !sameRows(prod, mine)) {
                mismatches++;
                System.out.println("  MISMATCH " + u.id() + " prod=" + prod.size() + " mine=" + mine.size());
            }
        }
        System.out.println("  mismatching PDFs: " + mismatches + " / " + units.size()
                + (mismatches == 0 ? "  (replication is faithful)" : "  <-- REPLICATION BROKEN"));

        // ---------------------------------------------------------------- S1: count census
        System.out.println();
        System.out.println("== S1: GT-table-count vs our-hit-count vs our-block-count census ==");
        System.out.println(String.format(Locale.ROOT, "  %-52s %4s %4s %4s %4s %7s %7s %7s %7s",
                "pdf", "gtT", "hits", "blk", "blk3", "adjF1", "match", "det", "gt"));
        Agg mEq = new Agg("hits == gtTables"), mOver = new Agg("hits >  gtTables"), mUnder = new Agg("hits <  gtTables");
        Agg bEq = new Agg("blocks>=3lines == gtTables"), bOver = new Agg("blocks>=3lines > gtTables"),
            bUnder = new Agg("blocks>=3lines < gtTables");
        Map<String, Sc> baseScores = new LinkedHashMap<>();
        Map<String, Integer> hitCounts = new LinkedHashMap<>(), blk3Counts = new LinkedHashMap<>();
        for (BakeOffHarness.ScoreUnit u : units) {
            List<Page> pages = pagesById.get(u.id());
            List<TableExtractor.TableHit> hits = new ArrayList<>();
            int blocks = 0, blocks3 = 0;
            for (Page p : pages) {
                List<List<StreamTableExtractor.Line>> bs = gapSplit(p);
                blocks += bs.size();
                for (List<StreamTableExtractor.Line> b : bs) if (b.size() >= 3) blocks3++;
                hits.addAll(runPartition(p, bs, breuel, StreamTableExtractor.STREAM_CONFIDENCE_MIN));
            }
            Sc s = score(u, hits);
            baseScores.put(u.id(), s);
            hitCounts.put(u.id(), hits.size());
            blk3Counts.put(u.id(), blocks3);
            int gtT = u.expected().size();
            System.out.println(String.format(Locale.ROOT, "  %-52s %4d %4d %4d %4d %7.3f %7d %7d %7d",
                    u.id(), gtT, hits.size(), blocks, blocks3, s.f1(), s.matched(), s.det(), s.gt()));
            (hits.size() == gtT ? mEq : hits.size() > gtT ? mOver : mUnder).add(s);
            (blocks3 == gtT ? bEq : blocks3 > gtT ? bOver : bUnder).add(s);
        }
        System.out.println();
        for (Agg a : List.of(mEq, mOver, mUnder, bEq, bOver, bUnder)) System.out.println(a);

        // ---------------------------------------------------------------- S2: FP/FN attribution
        System.out.println();
        System.out.println("== S2: adjacency FP/FN attribution (paired-table interior vs unpaired tables) ==");
        long det = 0, matched = 0, gt = 0, leftoverDet = 0, unpairedGt = 0;
        int pdfsWithLeftover = 0;
        for (Sc s : baseScores.values()) {
            det += s.det(); matched += s.matched(); gt += s.gt();
            leftoverDet += s.leftoverDet(); unpairedGt += s.unpairedGtRel();
            if (s.leftoverHits() > 0) pdfsWithLeftover++;
        }
        long fp = det - matched, fn = gt - matched;
        System.out.printf(Locale.ROOT, "  detected relations = %d, gt relations = %d, matched = %d%n", det, gt, matched);
        System.out.printf(Locale.ROOT, "  FP = %d  of which %d (%.1f%%) are relations of UNPAIRED (spurious) hits%n",
                fp, leftoverDet, 100.0 * leftoverDet / Math.max(1, fp));
        System.out.printf(Locale.ROOT, "  FN = %d  of which %d (%.1f%%) are relations of UNPAIRED (missed) GT tables%n",
                fn, unpairedGt, 100.0 * unpairedGt / Math.max(1, fn));
        System.out.println("  PDFs with >=1 spurious leftover hit: " + pdfsWithLeftover + " / " + units.size());

        // ------------------------------------------- S3: oracle A -- perfect spurious suppression
        System.out.println();
        System.out.println("== S3: ORACLE A -- perfectly suppress every unpaired (spurious) hit ==");
        Agg oracleA = new Agg("oracle-A (leftover hits deleted)");
        Agg baseAgg = new Agg("baseline (production)");
        for (Sc s : baseScores.values()) {
            baseAgg.add(s);
            int d2 = s.det() - s.leftoverDet();
            double p = d2 == 0 ? 0 : (double) s.matched() / d2;
            double r = s.gt() == 0 ? 0 : (double) s.matched() / s.gt();
            double f = s.matched() == 0 ? 0 : 2 * p * r / (p + r);
            oracleA.add(new Sc(s.matched(), d2, s.gt(), f, s.pairedTables(), 0, 0, s.unpairedGtRel()));
        }
        System.out.println(baseAgg);
        System.out.println(oracleA);

        // ------------------------------------------- S4: geometric over/under-fragmentation census
        System.out.println();
        System.out.println("== S4: geometric census -- how many of OUR blocks lie inside each GT table's bbox ==");
        int[] histBlocksPerGt = new int[12];
        int gtTablesSeen = 0, gtTablesFragmented = 0, gtTablesZero = 0;
        int blocksSpanningMultipleGt = 0;
        List<String> fragmentedExamples = new ArrayList<>();
        Agg fragAgg = new Agg("PDFs with >=1 GT table split over >1 block");
        Agg cleanAgg = new Agg("PDFs where every GT table maps to <=1 block");
        for (BakeOffHarness.ScoreUnit u : units) {
            List<Path> xmls = xmlsById.get(u.id());
            if (xmls == null) continue;                 // CSV-only fixture: no bbox ground truth
            List<Page> pages = pagesById.get(u.id());
            Map<Integer, Float> heights = new LinkedHashMap<>();
            for (Page p : pages) heights.put(p.pageNum, p.height);
            List<GtGeom> geoms = new ArrayList<>();
            for (Path x : xmls) geoms.addAll(gtGeoms(x, heights));
            boolean anyFrag = false;
            for (GtGeom g : geoms) {
                gtTablesSeen++;
                Page pg = pages.size() >= g.page() ? pages.get(g.page() - 1) : null;
                if (pg == null) continue;
                int inside = 0;
                for (List<StreamTableExtractor.Line> b : gapSplit(pg)) {
                    if (b.size() < 3) continue;
                    float bTop = b.get(0).yTop, bBot = b.get(b.size() - 1).yBot;
                    float ov = Math.min(bBot, g.yBotTL()) - Math.max(bTop, g.yTopTL());
                    if (ov > 0.5f * (bBot - bTop)) inside++;   // block is mostly within the GT table
                }
                histBlocksPerGt[Math.min(inside, histBlocksPerGt.length - 1)]++;
                if (inside == 0) gtTablesZero++;
                if (inside > 1) { gtTablesFragmented++; anyFrag = true;
                    if (fragmentedExamples.size() < 25)
                        fragmentedExamples.add(u.id() + " p" + g.page() + " gtRows~" + g.cells()
                                + " blocksInside=" + inside + " adjF1=" + String.format(Locale.ROOT, "%.3f", baseScores.get(u.id()).f1()));
                }
            }
            // blocks straddling >1 GT table (under-merge in the other direction)
            for (Page p : pages) {
                for (List<StreamTableExtractor.Line> b : gapSplit(p)) {
                    if (b.size() < 3) continue;
                    float bTop = b.get(0).yTop, bBot = b.get(b.size() - 1).yBot;
                    int touched = 0;
                    for (GtGeom g : geoms) {
                        if (g.page() != p.pageNum) continue;
                        float ov = Math.min(bBot, g.yBotTL()) - Math.max(bTop, g.yTopTL());
                        if (ov > 0.25f * (g.yBotTL() - g.yTopTL())) touched++;
                    }
                    if (touched > 1) blocksSpanningMultipleGt++;
                }
            }
            (anyFrag ? fragAgg : cleanAgg).add(baseScores.get(u.id()));
        }
        System.out.println("  GT tables with bbox geometry: " + gtTablesSeen);
        System.out.print("  histogram of (#our blocks mostly inside one GT table): ");
        for (int i = 0; i < histBlocksPerGt.length; i++) if (histBlocksPerGt[i] > 0)
            System.out.print(i + "->" + histBlocksPerGt[i] + " ");
        System.out.println();
        System.out.println("  GT tables covered by 0 blocks: " + gtTablesZero
                + " ; split across >1 block: " + gtTablesFragmented);
        System.out.println("  our blocks straddling >1 GT table: " + blocksSpanningMultipleGt);
        System.out.println(fragAgg);
        System.out.println(cleanAgg);
        System.out.println("  examples of fragmented GT tables:");
        for (String s : fragmentedExamples) System.out.println("    " + s);

        // ------------------------------------- S5: ORACLE B -- best contiguous re-segmentation
        System.out.println();
        System.out.println("== S5: ORACLE B -- best-of-all-contiguous-block-merges, oracle-assigned ==");
        System.out.println("   maxRun=1 is 'oracle PAIRING + perfect suppression over the CURRENT blocks'");
        System.out.println("   -- its candidate set IS the production hit set, so every gain ABOVE the");
        System.out.println("   maxRun=1 row is attributable to MERGING adjacent blocks and nothing else.");
        Map<String, Double> oracleByPdf = new LinkedHashMap<>();
        for (int maxRun : new int[]{1, 2, 3, 4, 8}) {
            long t0 = System.nanoTime();
            OracleResult or = oracle(units, pagesById, breuel, maxRun);
            System.out.println(or.agg);
            System.out.printf(Locale.ROOT, "     chosen: single-block=%d merged=%d   (%.1fs)%n",
                    or.single, or.merged, (System.nanoTime() - t0) / 1e9);
            if (maxRun == 8) oracleByPdf = or.byPdf;
        }
        System.out.println("  biggest per-PDF oracle (maxRun=8) gains over baseline:");
        List<Map.Entry<String, Double>> gains = new ArrayList<>();
        for (Map.Entry<String, Double> e : oracleByPdf.entrySet())
            gains.add(Map.entry(e.getKey(), e.getValue() - baseScores.get(e.getKey()).f1()));
        gains.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Double> e : gains.subList(0, Math.min(15, gains.size())))
            System.out.printf(Locale.ROOT, "    %-52s base=%.3f oracle=%.3f  (+%.3f)%n",
                    e.getKey(), baseScores.get(e.getKey()).f1(), oracleByPdf.get(e.getKey()), e.getValue());

        // ------------------------------------------- S6: implementable partition rules, scored
        System.out.println();
        System.out.println("== S6: implementable alternative partitions, scored over the full corpus ==");
        List<Agg> results = new ArrayList<>();
        results.add(baseAgg);
        results.add(scoreRule("no-split (whole page = 1 block)", units, pagesById, breuel,
                (p, f) -> noSplit(p)));
        for (float tol : new float[]{0.5f, 1.0f, 2.0f}) {
            for (double frac : new double[]{0.5, 0.999}) {
                results.add(scoreRule(String.format(Locale.ROOT, "colModelMerge tol=%.1f*mSp frac=%.2f", tol, frac),
                        units, pagesById, breuel, (p, f) -> colModelMerge(p, f, tol, frac)));
            }
        }
        for (double slack : new double[]{0.0, 0.05, 0.15}) {
            results.add(scoreRule(String.format(Locale.ROOT, "confMerge slack=%.2f", slack),
                    units, pagesById, breuel, (p, f) -> confMerge(p, f, slack)));
        }
        for (float maxGap : new float[]{2.0f, 2.5f, 3.0f, 5.0f, 100f}) {
            results.add(scoreRule(String.format(Locale.ROOT, "colModelMerge tol=2 frac=0.5 maxGap=%.1f", maxGap),
                    units, pagesById, breuel, (p, f) -> colModelMerge2(p, f, 2.0f, 0.5, maxGap, false)));
        }
        for (float maxGap : new float[]{2.5f, 3.0f, 100f}) {
            results.add(scoreRule(String.format(Locale.ROOT, "colModelMerge tol=2 frac=0.5 maxGap=%.1f +mergedConf", maxGap),
                    units, pagesById, breuel, (p, f) -> colModelMerge2(p, f, 2.0f, 0.5, maxGap, true)));
        }
        results.add(scoreRule("colModelMerge tol=1 frac=0.5 +mergedConf", units, pagesById, breuel,
                (p, f) -> colModelMerge2(p, f, 1.0f, 0.5, 100f, true)));
        results.add(scoreRule("colModelMerge tol=2 frac=0.34 +mergedConf", units, pagesById, breuel,
                (p, f) -> colModelMerge2(p, f, 2.0f, 0.34, 100f, true)));
        results.add(scoreRule("colModelMerge tol=2 frac=1.0 +mergedConf", units, pagesById, breuel,
                (p, f) -> colModelMerge2(p, f, 2.0f, 0.999, 100f, true)));
        results.add(scoreRule("mergedConf-only (no colmodel test)", units, pagesById, breuel,
                (p, f) -> mergedConfOnly(p, f)));
        results.add(scoreRule("FINAL RULE colModel tol=2 frac=0.5 gapCap=2.5 +mergedConf",
                units, pagesById, breuel, (p, f) -> colModelMerge2(p, f, 2.0f, 0.5, 2.5f, true)));
        for (Agg a : results) System.out.println(a);
        for (Agg a : results) System.out.printf(Locale.ROOT,
                "  raw  %-52s matched=%d det=%d gt=%d%n", a.label, a.matched, a.det, a.gt);

        // ------------------------- S6b: GT-supervised ceiling under the PRODUCTION pairing policy
        System.out.println();
        System.out.println("== S6b: GT-bbox-SUPERVISED segmentation (ceiling, production pairing) ==");
        Agg sup = new Agg("gt-supervised segmentation");
        Map<String, List<GtGeom>> geomsById = new LinkedHashMap<>();
        for (BakeOffHarness.ScoreUnit u : units) {
            List<Path> xmls = xmlsById.get(u.id());
            List<GtGeom> geoms = new ArrayList<>();
            if (xmls != null) {
                Map<Integer, Float> heights = new LinkedHashMap<>();
                for (Page p : pagesById.get(u.id())) heights.put(p.pageNum, p.height);
                for (Path x : xmls) geoms.addAll(gtGeoms(x, heights));
            }
            geomsById.put(u.id(), geoms);
            List<TableExtractor.TableHit> hits = new ArrayList<>();
            for (Page p : pagesById.get(u.id())) {
                if (p.lines.isEmpty()) continue;
                List<List<StreamTableExtractor.Line>> part =
                        geoms.isEmpty() ? gapSplit(p) : gtSupervised(p, geoms);
                hits.addAll(runPartition(p, part, breuel, StreamTableExtractor.STREAM_CONFIDENCE_MIN));
            }
            sup.add(score(u, hits));
        }
        System.out.println(baseAgg);
        System.out.println(sup);

        // ------------------- S6c: is there an implementable discriminator? pair-level separability
        System.out.println();
        System.out.println("== S6c: adjacent-block-pair features, labelled by GT bbox (same table or not) ==");
        System.out.println("  label       n   colAgree>=0.5  medGapRatio  p10..p90 gapRatio  medGutters(min)");
        record Feat(boolean same, double agree, float gapRatio, int minG, int maxG) {}
        List<Feat> feats = new ArrayList<>();
        for (BakeOffHarness.ScoreUnit u : units) {
            List<GtGeom> geoms = geomsById.get(u.id());
            if (geoms.isEmpty()) continue;
            for (Page p : pagesById.get(u.id())) {
                if (p.lines.isEmpty()) continue;
                List<List<StreamTableExtractor.Line>> base = gapSplit(p);
                if (base.size() < 2) continue;
                float pitch = pageMedianPitch(p);
                List<List<StreamTableExtractor.Gutter>> gs = new ArrayList<>();
                for (List<StreamTableExtractor.Line> b : base) gs.add(blockGutters(b, breuel, p.medianSpace));
                int[] label = new int[base.size()];
                for (int i = 0; i < base.size(); i++) {
                    label[i] = -1;
                    List<StreamTableExtractor.Line> b = base.get(i);
                    float bTop = b.get(0).yTop, bBot = b.get(b.size() - 1).yBot;
                    double bestOv = 0;
                    for (int gi = 0; gi < geoms.size(); gi++) {
                        GtGeom g = geoms.get(gi);
                        if (g.page() != p.pageNum) continue;
                        float ov = Math.min(bBot, g.yBotTL()) - Math.max(bTop, g.yTopTL());
                        if (ov > 0.5f * (bBot - bTop) && ov > bestOv) { bestOv = ov; label[i] = gi; }
                    }
                }
                for (int i = 1; i < base.size(); i++) {
                    if (label[i] < 0 || label[i - 1] < 0) continue;   // debris block: unlabelled
                    boolean same = label[i] == label[i - 1];
                    float gap = base.get(i).get(0).yTop - base.get(i - 1).get(base.get(i - 1).size() - 1).yTop;
                    double agree = agreeFrac(gs.get(i - 1), gs.get(i), 2.0f * p.medianSpace);
                    feats.add(new Feat(same, agree, gap / pitch,
                            Math.min(gs.get(i - 1).size(), gs.get(i).size()),
                            Math.max(gs.get(i - 1).size(), gs.get(i).size())));
                }
            }
        }
        for (boolean same : new boolean[]{true, false}) {
            List<Feat> f = feats.stream().filter(x -> x.same() == same).toList();
            if (f.isEmpty()) { System.out.println("  " + (same ? "SAME" : "DIFF") + "  (none)"); continue; }
            List<Double> ratios = f.stream().map(x -> (double) x.gapRatio()).sorted().toList();
            long agreeing = f.stream().filter(x -> x.agree() >= 0.5).count();
            List<Double> mins = f.stream().map(x -> (double) x.minG()).sorted().toList();
            System.out.printf(Locale.ROOT, "  %-8s %4d   %5d (%.0f%%)     %7.2f    %6.2f..%6.2f      %5.1f%n",
                    same ? "SAME" : "DIFF", f.size(), agreeing, 100.0 * agreeing / f.size(),
                    ratios.get(ratios.size() / 2), ratios.get((int) (ratios.size() * 0.1)),
                    ratios.get(Math.min(ratios.size() - 1, (int) (ratios.size() * 0.9))),
                    mins.get(mins.size() / 2));
        }
        // joint rule accuracy: agree>=0.5 AND gapRatio<=T
        for (float T : new float[]{2.0f, 2.5f, 3.0f, 5.0f, 1e6f}) {
            long tp = feats.stream().filter(x -> x.same() && x.agree() >= 0.5 && x.gapRatio() <= T).count();
            long fpp = feats.stream().filter(x -> !x.same() && x.agree() >= 0.5 && x.gapRatio() <= T).count();
            long fnn = feats.stream().filter(x -> x.same() && !(x.agree() >= 0.5 && x.gapRatio() <= T)).count();
            System.out.printf(Locale.ROOT,
                    "  merge-rule(agree>=0.5, gapRatio<=%.1f): TP=%d FP=%d FN=%d  precision=%.2f recall=%.2f%n",
                    T, tp, fpp, fnn, tp + fpp == 0 ? 0 : tp / (double) (tp + fpp), tp + fnn == 0 ? 0 : tp / (double) (tp + fnn));
        }

        // ---------------------------------------- S7: oracle pairing ON TOP OF the best real rule
        System.out.println();
        System.out.println("== S7: named fixtures, baseline vs best implementable rule ==");
        Map<String, Double> ruleByPdf = new LinkedHashMap<>();
        Map<String, Integer> ruleHits = new LinkedHashMap<>();
        for (BakeOffHarness.ScoreUnit u : units) {
            List<TableExtractor.TableHit> hits = new ArrayList<>();
            for (Page p : pagesById.get(u.id())) {
                if (p.lines.isEmpty()) continue;
                hits.addAll(runPartition(p, colModelMerge2(p, breuel, 2.0f, 0.5, 2.5f, true), breuel,
                        StreamTableExtractor.STREAM_CONFIDENCE_MIN));
            }
            ruleByPdf.put(u.id(), score(u, hits).f1());
            ruleHits.put(u.id(), hits.size());
        }
        System.out.println(String.format(Locale.ROOT, "  %-52s %8s %8s %8s %6s %6s %6s",
                "pdf", "base", "rule", "oracle8", "gtT", "hitsB", "hitsR"));
        for (BakeOffHarness.ScoreUnit u : units) {
            System.out.println(String.format(Locale.ROOT, "  %-52s %8.3f %8.3f %8.3f %6d %6d %6d",
                    u.id(), baseScores.get(u.id()).f1(), ruleByPdf.get(u.id()),
                    oracleByPdf.getOrDefault(u.id(), Double.NaN),
                    u.expected().size(), hitCounts.get(u.id()), ruleHits.get(u.id())));
        }
        int improved = 0, regressed = 0, same = 0;
        for (String id : ruleByPdf.keySet()) {
            double d = ruleByPdf.get(id) - baseScores.get(id).f1();
            if (d > 0.005) improved++; else if (d < -0.005) regressed++; else same++;
        }
        System.out.println("  rule vs baseline per-PDF: improved=" + improved + " regressed=" + regressed
                + " unchanged=" + same);

        // --------------------------------------- S7b: full summary-line comparison (all metrics)
        System.out.println();
        System.out.println("== S7b: BakeOffHarness-style summary line, baseline vs best rule ==");
        for (String which : new String[]{"baseline", "rule"}) {
            long tp = 0, fpx = 0, fnx = 0, am = 0, ad = 0, ag = 0;
            int dimsOk = 0, dimsTot = 0, detected = 0;
            double macroAdj = 0, macroExact = 0;
            List<Double> times = new ArrayList<>();
            for (BakeOffHarness.ScoreUnit u : units) {
                long t0 = System.nanoTime();
                List<TableExtractor.TableHit> hits = new ArrayList<>();
                for (Page p2 : pagesById.get(u.id())) {
                    if (p2.lines.isEmpty()) continue;
                    List<List<StreamTableExtractor.Line>> part = which.equals("baseline")
                            ? gapSplit(p2) : colModelMerge2(p2, breuel, 2.0f, 0.5, 2.5f, true);
                    hits.addAll(runPartition(p2, part, breuel, StreamTableExtractor.STREAM_CONFIDENCE_MIN));
                }
                times.add((System.nanoTime() - t0) / 1e6);
                if (!hits.isEmpty()) detected++;
                // exact-cell + adjacency under the production pairing
                List<TableExtractor.TableHit> avail = new ArrayList<>(hits);
                int utp = 0, ufp = 0, ufn = 0, uam = 0, uad = 0, uag = 0;
                for (GroundTruth.Table exp : u.expected()) {
                    if (avail.isEmpty()) {
                        ufn += TableScore.score(exp, List.of()).falseNegatives();
                        uag += TableScore.relationCount(exp.rows());
                        continue;
                    }
                    TableExtractor.TableHit best = null; TableScore.Result br = null; double bf = -1;
                    for (TableExtractor.TableHit h : avail) {
                        TableScore.Result r = TableScore.score(exp, h.rows);
                        if (r.f1() > bf) { bf = r.f1(); best = h; br = r; }
                    }
                    avail.remove(best);
                    utp += br.truePositives(); ufp += br.falsePositives(); ufn += br.falseNegatives();
                    dimsTot++; if (br.dimsExactMatch()) dimsOk++;
                    TableScore.AdjResult a = TableScore.scoreAdjacency(exp, best.rows);
                    uam += a.matched(); uad += a.detectedTotal(); uag += a.gtTotal();
                }
                for (TableExtractor.TableHit h : avail) {
                    ufp += TableScore.score(new GroundTruth.Table(List.of()), h.rows).falsePositives();
                    uad += TableScore.relationCount(h.rows);
                }
                tp += utp; fpx += ufp; fnx += ufn; am += uam; ad += uad; ag += uag;
                double ep = (utp + ufp) == 0 ? 0 : utp / (double) (utp + ufp);
                double er = (utp + ufn) == 0 ? 0 : utp / (double) (utp + ufn);
                macroExact += utp == 0 ? 0 : 2 * ep * er / (ep + er);
                double ap = uad == 0 ? 0 : uam / (double) uad, ar = uag == 0 ? 0 : uam / (double) uag;
                macroAdj += uam == 0 ? 0 : 2 * ap * ar / (ap + ar);
            }
            double ep = (tp + fpx) == 0 ? 0 : tp / (double) (tp + fpx);
            double er = (tp + fnx) == 0 ? 0 : tp / (double) (tp + fnx);
            double ef = tp == 0 ? 0 : 2 * ep * er / (ep + er);
            double ap = ad == 0 ? 0 : am / (double) ad, ar = ag == 0 ? 0 : am / (double) ag;
            double af = am == 0 ? 0 : 2 * ap * ar / (ap + ar);
            java.util.Collections.sort(times);
            System.out.printf(Locale.ROOT,
                "  %-9s exactF1=%.3f exactMac=%.3f | adjP=%.3f adjR=%.3f adjF1=%.3f adjMac=%.3f | dims=%.3f det=%.3f | medMs=%.1f p95Ms=%.1f maxMs=%.1f%n",
                which, ef, macroExact / units.size(), ap, ar, af, macroAdj / units.size(),
                dimsTot == 0 ? 0 : dimsOk / (double) dimsTot, detected / (double) units.size(),
                times.get(times.size() / 2), times.get((int) Math.ceil(0.95 * times.size()) - 1),
                times.get(times.size() - 1));
        }

        // ------------------- S9: does the candidate rule preserve the guarded synthetic fixtures?
        System.out.println();
        System.out.println("== S9: synthetic guard fixture -- two independent same-column tables, 120pt apart ==");
        twoTableFixtureCheck(breuel);

        // ------------------------------------------------------------------- S8: prose FP check
        System.out.println();
        System.out.println("== S8: prose false-positive rate (same sample logic as BakeOffHarness) ==");
        List<Path> prose = sampleProse();
        if (prose == null || prose.isEmpty()) {
            System.out.println("  prose corpus unavailable -- skipped");
        } else {
            System.out.println("  sample size: " + prose.size());
            int baseFp = 0, ruleFp = 0, mergeConfFp = 0;
            for (Path p : prose) {
                List<Page> pages = loadPages(p);
                if (pages.isEmpty()) continue;
                Page p1 = pages.get(0);
                if (p1.lines.isEmpty()) continue;
                if (!runPartition(p1, gapSplit(p1), breuel, StreamTableExtractor.STREAM_CONFIDENCE_MIN).isEmpty()) baseFp++;
                if (!runPartition(p1, colModelMerge2(p1, breuel, 2.0f, 0.5, 2.5f, true), breuel,
                        StreamTableExtractor.STREAM_CONFIDENCE_MIN).isEmpty()) ruleFp++;
                if (!runPartition(p1, confMerge(p1, breuel, 0.15), breuel,
                        StreamTableExtractor.STREAM_CONFIDENCE_MIN).isEmpty()) mergeConfFp++;
            }
            System.out.printf(Locale.ROOT, "  baseline proseFP        = %d/%d = %.3f%n", baseFp, prose.size(), baseFp / (double) prose.size());
            System.out.printf(Locale.ROOT, "  colModelMerge proseFP   = %d/%d = %.3f%n", ruleFp, prose.size(), ruleFp / (double) prose.size());
            System.out.printf(Locale.ROOT, "  confMerge(0.15) proseFP = %d/%d = %.3f%n", mergeConfFp, prose.size(), mergeConfFp / (double) prose.size());
        }
    }

    // ------------------------------------------------------------------------- oracle machinery

    static final class OracleResult {
        Agg agg; int single, merged; Map<String, Double> byPdf = new LinkedHashMap<>();
    }

    /** Upper bound: generate a hit for EVERY contiguous run of up to {@code maxRun} base blocks on
     *  every page, then let an oracle (which is allowed to look at ground truth) pick a
     *  block-DISJOINT subset -- i.e. a selection that a real contiguous partition could actually
     *  have produced -- maximizing adjacency F1 per GT table, with every unchosen candidate
     *  suppressed for free. maxRun=1 therefore isolates "perfect pairing + perfect suppression of
     *  the CURRENT blocks"; maxRun>1 adds exactly the merging freedom this lever proposes. */
    static OracleResult oracle(List<BakeOffHarness.ScoreUnit> units, Map<String, List<Page>> pagesById,
                                GutterFinder finder, int maxRun) {
        OracleResult res = new OracleResult();
        res.agg = new Agg("oracle maxRun=" + maxRun);
        for (BakeOffHarness.ScoreUnit u : units) {
            List<Cand> cands = new ArrayList<>();
            for (Page p : pagesById.get(u.id())) {
                if (p.lines.isEmpty()) continue;
                List<List<StreamTableExtractor.Line>> base = gapSplit(p);
                int n = base.size();
                for (int st = 0; st < n; st++) {
                    List<StreamTableExtractor.Line> acc = new ArrayList<>();
                    for (int len = 1; len <= maxRun && st + len <= n; len++) {
                        acc.addAll(base.get(st + len - 1));
                        if (acc.size() < 3) continue;
                        TableExtractor.TableHit h = runOneBlock(p.pageNum, acc, finder, p.medianSpace,
                                StreamTableExtractor.STREAM_CONFIDENCE_MIN);
                        if (h != null) cands.add(new Cand(p.pageNum, st, st + len, h));
                    }
                }
            }
            List<Pair> pairs = new ArrayList<>();
            for (int gi = 0; gi < u.expected().size(); gi++) {
                for (int ci = 0; ci < cands.size(); ci++) {
                    TableScore.AdjResult a = TableScore.scoreAdjacency(u.expected().get(gi), cands.get(ci).hit.rows);
                    if (a.matched() > 0) pairs.add(new Pair(gi, ci, a.f1(), a.matched(), a.detectedTotal()));
                }
            }
            pairs.sort(Comparator.comparingDouble((Pair x) -> x.f1).reversed());
            boolean[] gtUsed = new boolean[u.expected().size()];
            List<Cand> taken = new ArrayList<>();
            int om = 0, od = 0, og = 0;
            for (Pair pr : pairs) {
                if (gtUsed[pr.gtIdx]) continue;
                Cand c = cands.get(pr.candIdx);
                boolean conflict = false;
                for (Cand tk : taken)
                    if (tk.page == c.page && c.start < tk.end && tk.start < c.end) { conflict = true; break; }
                if (conflict) continue;
                gtUsed[pr.gtIdx] = true;
                taken.add(c);
                om += pr.matched; od += pr.det;
                if (c.end - c.start == 1) res.single++; else res.merged++;
            }
            for (int gi = 0; gi < u.expected().size(); gi++)
                og += TableScore.relationCount(u.expected().get(gi).rows());
            double p = od == 0 ? 0 : (double) om / od, r = og == 0 ? 0 : (double) om / og;
            double f = om == 0 ? 0 : 2 * p * r / (p + r);
            res.agg.add(new Sc(om, od, og, f, taken.size(), 0, 0, 0));
            res.byPdf.put(u.id(), f);
        }
        return res;
    }

    static final class Cand {
        final int page, start, end; final TableExtractor.TableHit hit;
        Cand(int page, int start, int end, TableExtractor.TableHit hit) {
            this.page = page; this.start = start; this.end = end; this.hit = hit;
        }
    }

    static final class Pair {
        final int gtIdx, candIdx, matched, det; final double f1;
        Pair(int gtIdx, int candIdx, double f1, int matched, int det) {
            this.gtIdx = gtIdx; this.candIdx = candIdx; this.f1 = f1; this.matched = matched; this.det = det;
        }
    }

    // ------------------------------------------------------------------------- prose FP sample

    private static final Path PHISH_ROOT = Path.of("/home/coz/Downloads/phishpdfs");
    private static final int PROSE_SAMPLE_CAP = 200;

    /** Byte-for-byte the same sampling rule BakeOffHarness#sampleProsePdfs uses (it is private
     *  there, and it is corpus SELECTION, not scoring logic). */
    private static List<Path> sampleProse() throws IOException {
        if (!Files.isDirectory(PHISH_ROOT)) return null;
        List<Path> all;
        try (Stream<Path> s = Files.list(PHISH_ROOT)) {
            all = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        List<Path> pdfs = new ArrayList<>();
        for (Path p : all) {
            try (java.io.InputStream in = Files.newInputStream(p)) {
                byte[] buf = new byte[5];
                int n = in.read(buf);
                if (n >= 4 && buf[0] == '%' && buf[1] == 'P' && buf[2] == 'D' && buf[3] == 'F') pdfs.add(p);
            } catch (IOException e) { /* skip */ }
        }
        if (pdfs.size() <= PROSE_SAMPLE_CAP) return pdfs;
        int step = (int) Math.ceil(pdfs.size() / (double) PROSE_SAMPLE_CAP);
        List<Path> sample = new ArrayList<>();
        for (int i = 0; i < pdfs.size() && sample.size() < PROSE_SAMPLE_CAP; i += step) sample.add(pdfs.get(i));
        return sample;
    }

    /** Merge adjacent blocks purely on "the merged block still clears the gridness gate". */
    static List<List<StreamTableExtractor.Line>> mergedConfOnly(Page p, GutterFinder finder) {
        List<List<StreamTableExtractor.Line>> base = gapSplit(p);
        if (base.size() < 2) return base;
        List<List<StreamTableExtractor.Line>> out = new ArrayList<>();
        List<StreamTableExtractor.Line> cur = new ArrayList<>(base.get(0));
        for (int i = 1; i < base.size(); i++) {
            List<StreamTableExtractor.Line> cand = new ArrayList<>(cur);
            cand.addAll(base.get(i));
            if (blockConfidence(cand, finder, p.medianSpace) >= StreamTableExtractor.STREAM_CONFIDENCE_MIN) {
                cur = cand;
            } else { out.add(cur); cur = new ArrayList<>(base.get(i)); }
        }
        out.add(cur);
        return out;
    }

    static float pageMedianPitch(Page p) {
        List<StreamTableExtractor.Line> lines = p.lines;
        if (lines.size() < 2) return 1f;
        float[] pitches = new float[lines.size() - 1];
        for (int i = 1; i < lines.size(); i++) pitches[i - 1] = lines.get(i).yTop - lines.get(i - 1).yTop;
        java.util.Arrays.sort(pitches);
        return Math.max(pitches[pitches.length / 2], 0.5f);
    }

    /** colModelMerge plus two candidate guards: an upper bound on the vertical gap being bridged
     *  (as a multiple of the page's median line pitch) and a requirement that the MERGED block
     *  still clear the production gridness gate. */
    static List<List<StreamTableExtractor.Line>> colModelMerge2(Page p, GutterFinder finder, float tolFactor,
            double minFrac, float maxGapRatio, boolean requireMergedConf) {
        List<List<StreamTableExtractor.Line>> base = gapSplit(p);
        if (base.size() < 2) return base;
        float pitch = pageMedianPitch(p);
        List<List<StreamTableExtractor.Gutter>> gs = new ArrayList<>();
        for (List<StreamTableExtractor.Line> b : base) gs.add(blockGutters(b, finder, p.medianSpace));
        List<List<StreamTableExtractor.Line>> out = new ArrayList<>();
        List<StreamTableExtractor.Line> cur = new ArrayList<>(base.get(0));
        List<StreamTableExtractor.Gutter> curG = gs.get(0);
        for (int i = 1; i < base.size(); i++) {
            float gap = base.get(i).get(0).yTop - cur.get(cur.size() - 1).yTop;
            boolean ok = gap <= maxGapRatio * pitch
                    && colModelAgrees(curG, gs.get(i), tolFactor * p.medianSpace, minFrac);
            if (ok && requireMergedConf) {
                List<StreamTableExtractor.Line> cand = new ArrayList<>(cur);
                cand.addAll(base.get(i));
                ok = blockConfidence(cand, finder, p.medianSpace) >= StreamTableExtractor.STREAM_CONFIDENCE_MIN;
            }
            if (ok) {
                cur.addAll(base.get(i));
                if (gs.get(i).size() > curG.size()) curG = gs.get(i);
            } else {
                out.add(cur); cur = new ArrayList<>(base.get(i)); curG = gs.get(i);
            }
        }
        out.add(cur);
        return out;
    }

    /** GT-bbox-SUPERVISED partition: merge consecutive base blocks that fall inside the SAME
     *  ground-truth table bbox. Not implementable (uses ground truth) -- it is the honest ceiling
     *  of "segmentation only", because it is scored through the PRODUCTION pairing policy, unlike
     *  the S5 oracles which also get to re-pair. */
    static List<List<StreamTableExtractor.Line>> gtSupervised(Page p, List<GtGeom> geoms) {
        List<List<StreamTableExtractor.Line>> base = gapSplit(p);
        if (base.size() < 2) return base;
        int[] label = new int[base.size()];
        for (int i = 0; i < base.size(); i++) {
            label[i] = -1;
            List<StreamTableExtractor.Line> b = base.get(i);
            float bTop = b.get(0).yTop, bBot = b.get(b.size() - 1).yBot;
            double bestOv = 0;
            for (int gi = 0; gi < geoms.size(); gi++) {
                GtGeom g = geoms.get(gi);
                if (g.page() != p.pageNum) continue;
                float ov = Math.min(bBot, g.yBotTL()) - Math.max(bTop, g.yTopTL());
                if (ov > 0.5f * (bBot - bTop) && ov > bestOv) { bestOv = ov; label[i] = gi; }
            }
        }
        List<List<StreamTableExtractor.Line>> out = new ArrayList<>();
        List<StreamTableExtractor.Line> cur = new ArrayList<>(base.get(0));
        int curLabel = label[0];
        for (int i = 1; i < base.size(); i++) {
            if (label[i] >= 0 && label[i] == curLabel) {
                cur.addAll(base.get(i));
            } else {
                out.add(cur); cur = new ArrayList<>(base.get(i)); curLabel = label[i];
            }
        }
        out.add(cur);
        return out;
    }

    /** Rebuilds StreamSegmentationTest#multipleTablesOnOnePageAreDetectedSeparately's exact
     *  geometry (two 3-column tables at the SAME column x positions, 120pt apart, 20pt pitch) and
     *  reports what each candidate partition rule does to it. That test asserts hits.size()==2 and
     *  must not be weakened, so any merge rule that collapses this fixture is disqualified. */
    private static void twoTableFixtureCheck(GutterFinder finder) throws IOException {
        String[][] tableA = {{"Region","Votes","Pct"},{"North","1200","41.2"},{"South","900","30.9"},
                             {"East","450","15.4"},{"West","360","12.5"}};
        String[][] tableB = {{"Team","Wins","Losses"},{"Hawks","44","38"},{"Bulls","39","43"},{"Suns","51","31"}};
        Path tmp = Files.createTempFile("diagseg-twotable", ".pdf");
        try (org.apache.pdfbox.pdmodel.PDDocument doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page = new org.apache.pdfbox.pdmodel.PDPage(
                    new org.apache.pdfbox.pdmodel.common.PDRectangle(300, 500));
            doc.addPage(page);
            org.apache.pdfbox.pdmodel.font.PDType1Font f = new org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream cs =
                         new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.setFont(f, 11);
                float[] colX = {40, 150, 230};
                float yA = 440;
                for (int r = 0; r < tableA.length; r++) {
                    float y = yA - r * 20;
                    for (int c = 0; c < 3; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(tableA[r][c]); cs.endText();
                    }
                }
                float yB = yA - (tableA.length - 1) * 20 - 120;
                for (int r = 0; r < tableB.length; r++) {
                    float y = yB - r * 20;
                    for (int c = 0; c < 3; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(tableB[r][c]); cs.endText();
                    }
                }
            }
            doc.save(tmp.toFile());
        }
        List<Page> pages = loadPages(tmp);
        Page p = pages.get(0);
        System.out.println("  base blocks=" + gapSplit(p).size()
                + " pageMedianPitch=" + String.format(Locale.ROOT, "%.1f", pageMedianPitch(p))
                + " bridged-gap ratio=" + String.format(Locale.ROOT, "%.2f",
                        (gapSplit(p).size() > 1
                            ? (gapSplit(p).get(1).get(0).yTop
                               - gapSplit(p).get(0).get(gapSplit(p).get(0).size() - 1).yTop) / pageMedianPitch(p)
                            : 0f)));
        System.out.println("  baseline hits                            = "
                + runPartition(p, gapSplit(p), finder, StreamTableExtractor.STREAM_CONFIDENCE_MIN).size());
        System.out.println("  colModelMerge(no gap cap)+mergedConf hits = "
                + runPartition(p, colModelMerge2(p, finder, 2.0f, 0.5, 100f, true), finder,
                        StreamTableExtractor.STREAM_CONFIDENCE_MIN).size());
        System.out.println("  colModelMerge(gapCap=2.5)+mergedConf hits = "
                + runPartition(p, colModelMerge2(p, finder, 2.0f, 0.5, 2.5f, true), finder,
                        StreamTableExtractor.STREAM_CONFIDENCE_MIN).size());
        System.out.println("  confMerge(slack=0.15) hits                = "
                + runPartition(p, confMerge(p, finder, 0.15), finder,
                        StreamTableExtractor.STREAM_CONFIDENCE_MIN).size());
        Files.deleteIfExists(tmp);
    }

    interface PartitionRule {
        List<List<StreamTableExtractor.Line>> partition(Page p, GutterFinder f);
    }

    private Agg scoreRule(String label, List<BakeOffHarness.ScoreUnit> units,
                          Map<String, List<Page>> pagesById, GutterFinder finder, PartitionRule rule) {
        Agg agg = new Agg(label);
        for (BakeOffHarness.ScoreUnit u : units) {
            List<TableExtractor.TableHit> hits = new ArrayList<>();
            for (Page p : pagesById.get(u.id())) {
                if (p.lines.isEmpty()) continue;
                hits.addAll(runPartition(p, rule.partition(p, finder), finder,
                        StreamTableExtractor.STREAM_CONFIDENCE_MIN));
            }
            agg.add(score(u, hits));
        }
        return agg;
    }

    private static boolean sameRows(List<TableExtractor.TableHit> a, List<TableExtractor.TableHit> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!String.valueOf(a.get(i).rows).equals(String.valueOf(b.get(i).rows))) return false;
            if (a.get(i).page != b.get(i).page) return false;
        }
        return true;
    }
}
