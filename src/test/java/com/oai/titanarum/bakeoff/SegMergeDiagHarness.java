// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/ but declares
// `package com.oai.titanarum;` -- the same trick DiagSegHarness / BaselineHarness use, and for the
// same reason (StreamTableExtractor and its Word/Line/Gutter types are package-private).
//
// THROWAWAY DIAGNOSTIC for the region-level fragment re-merging lever. Purely observational: it
// modifies nothing, asserts nothing about score, and reads only the PRODUCTION Step A / Step A'
// partition through their existing package-private seams.
//
// QUESTION IT ANSWERS. Step A' (block re-merge) is already shipped. The census that motivated it
// said 23 of 163 geometry-bearing ground-truth tables were split across more than one Step A block.
// After Step A', WHICH of those splits survive, and WHY did Step A' refuse each surviving one? The
// four possible refusal reasons are mutually exclusive and are counted separately:
//
//   SHORT        one side of the boundary has fewer than 3 lines, so it has no gutter set at all and
//                columnModelsAgree can never return true for it. A one-line section subhead or a
//                two-line totals band inside a table falls here.
//   GAP          the vertical gap exceeded BLOCK_MERGE_MAX_GAP_FACTOR x the page median pitch.
//   NOAGREE      both sides had gutters but fewer than BLOCK_MERGE_MIN_AGREE_FRACTION of the
//                smaller set matched within tolerance.
//   BAR          the models agreed and the gap was small enough, but the MERGED block did not clear
//                the gridness bar (merge condition 3).
//
// Also reported: the reverse error (a production group straddling more than one ground-truth table),
// which is the thing any more aggressive merge rule risks making worse.
//
// Run: mvn -q -o test -Dtest=SegMergeDiagHarness -DsegMergeDiag=true
package com.oai.titanarum;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

class SegMergeDiagHarness {

    private static final Path TABULA_RESOURCES =
            Path.of("corpus/tabula-java/src/test/resources").toAbsolutePath().normalize();
    private static final Path ICDAR_ROOT =
            TABULA_RESOURCES.resolve("technology/tabula/icdar2013-dataset");

    /** Same PDF -> {@code *-str.xml} association DiagSegHarness uses (copied rather than shared so
     *  this throwaway file does not widen another harness's visibility). */
    private static Map<String, List<Path>> icdarXmlsByPdfId() throws Exception {
        List<Path> strXmls;
        try (java.util.stream.Stream<Path> walk = Files.walk(ICDAR_ROOT)) {
            strXmls = walk.filter(p -> p.getFileName().toString().endsWith("-str.xml"))
                    .sorted().toList();
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
            pdfToXmls.computeIfAbsent(candidate.toAbsolutePath().normalize(),
                    k -> new ArrayList<>()).add(xml);
        }
        Map<String, List<Path>> byId = new LinkedHashMap<>();
        for (Map.Entry<Path, List<Path>> e : pdfToXmls.entrySet()) {
            byId.put(TABULA_RESOURCES.relativize(e.getKey()).toString(), e.getValue());
        }
        return byId;
    }

    /** Merge condition 3 for a candidate block: band -> finder -> trim -> scoreGrid, against the
     *  production confidence bar -- the same sequence {@code StreamTableExtractor#probeBlock} runs. */
    private static boolean clearsBar(List<StreamTableExtractor.Line> block, GutterFinder finder,
                                     float medianSpace) {
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE;
        for (StreamTableExtractor.Line l : block) for (StreamTableExtractor.Word wd : l.words) {
            x0 = Math.min(x0, wd.x0); x1 = Math.max(x1, wd.x1);
        }
        try {
            List<StreamTableExtractor.Gutter> g = finder.find(block, x0, x1, medianSpace);
            List<StreamTableExtractor.Line> tr =
                    StreamTableExtractor.trimEdgeLines(block, g, x0, x1, medianSpace);
            if (tr.size() < 3) return false;
            StreamTableExtractor.Grid grid = StreamTableExtractor.scoreGrid(tr, g, x0, x1);
            return grid.confidence
                    >= StreamTableExtractor.PRODUCTION_BAR.barFor(grid.colBounds.length - 1);
        } catch (Throwable t) {
            return false;
        }
    }

    private final StringBuilder out = new StringBuilder();

    private void line(String fmt, Object... args) {
        String s = args.length == 0 ? fmt : String.format(Locale.ROOT, fmt, args);
        out.append(s).append('\n');
        System.out.println(s);
    }

    /** Which of a page's ground-truth table bboxes a block belongs to, or -1. Same >50%-vertical-
     *  overlap rule DiagSegHarness#gtSupervised uses, so the two censuses are comparable. */
    private static int labelOf(List<StreamTableExtractor.Line> b, List<DiagSegHarness.GtGeom> geoms,
                              int pageNum) {
        float bTop = b.get(0).yTop, bBot = b.get(b.size() - 1).yBot;
        int best = -1;
        double bestOv = 0;
        for (int gi = 0; gi < geoms.size(); gi++) {
            DiagSegHarness.GtGeom g = geoms.get(gi);
            if (g.page() != pageNum) continue;
            float ov = Math.min(bBot, g.yBotTL()) - Math.max(bTop, g.yTopTL());
            if (ov > 0.5f * (bBot - bTop) && ov > bestOv) { bestOv = ov; best = gi; }
        }
        return best;
    }

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("segMergeDiag"), "set -DsegMergeDiag=true");

        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        GutterFinder finder = new BreuelGutterFinder();

        Map<String, List<Path>> xmlById = icdarXmlsByPdfId();

        int gtSplitAfterA = 0, gtSplitAfterAPrime = 0, gtTables = 0, straddleAfterAPrime = 0;
        Map<String, Integer> why = new TreeMap<>();
        List<String> detail = new ArrayList<>();

        for (BakeOffHarness.ScoreUnit unit : corpus.units) {
            List<Path> xmls = xmlById.get(unit.id());
            if (xmls == null || xmls.isEmpty()) continue;         // CSV-ground-truth unit: no geometry
            List<DiagSegHarness.Page> pages = DiagSegHarness.loadPages(unit.pdf());
            Map<Integer, Float> heights = new LinkedHashMap<>();
            for (DiagSegHarness.Page p : pages) heights.put(p.pageNum, p.height);
            List<DiagSegHarness.GtGeom> geoms = new ArrayList<>();
            for (Path x : xmls) geoms.addAll(DiagSegHarness.gtGeoms(x, heights));

            for (DiagSegHarness.Page p : pages) {
                if (p.lines.isEmpty()) continue;
                List<List<StreamTableExtractor.Line>> base =
                        StreamTableExtractor.splitIntoBlocks(p.lines);
                float pitch = StreamTableExtractor.pageMedianPitch(p.lines);
                List<StreamTableExtractor.BlockGroup> groups =
                        StreamTableExtractor.mergeAgreeingBlocks(base, finder, p.medianSpace, pitch);

                // per-GT-table: how many base blocks / how many production groups touch it
                Map<Integer, Integer> baseCount = new TreeMap<>();
                Map<Integer, Integer> groupCount = new TreeMap<>();
                for (List<StreamTableExtractor.Line> b : base) {
                    int l = labelOf(b, geoms, p.pageNum);
                    if (l >= 0) baseCount.merge(l, 1, Integer::sum);
                }
                for (StreamTableExtractor.BlockGroup g : groups) {
                    int l = labelOf(g.lines, geoms, p.pageNum);
                    if (l >= 0) groupCount.merge(l, 1, Integer::sum);
                    // reverse error: does this group cover >1 GT table's rows?
                    int distinct = 0;
                    for (int gi = 0; gi < geoms.size(); gi++) {
                        DiagSegHarness.GtGeom gg = geoms.get(gi);
                        if (gg.page() != p.pageNum) continue;
                        float gTop = g.lines.get(0).yTop, gBot = g.lines.get(g.lines.size() - 1).yBot;
                        float ov = Math.min(gBot, gg.yBotTL()) - Math.max(gTop, gg.yTopTL());
                        if (ov > 0.5f * (gg.yBotTL() - gg.yTopTL())) distinct++;
                    }
                    if (distinct > 1) straddleAfterAPrime++;
                }
                for (int v : baseCount.values()) if (v > 1) gtSplitAfterA++;
                for (int v : groupCount.values()) if (v > 1) gtSplitAfterAPrime++;
                gtTables += baseCount.size();

                // WHY did each surviving same-GT-table boundary fail? Walk the BASE blocks, find
                // consecutive pairs with the same GT label that ended up in DIFFERENT groups.
                // Map base block -> group index by line identity of the first line.
                Map<StreamTableExtractor.Line, Integer> lineToGroup = new LinkedHashMap<>();
                for (int gi = 0; gi < groups.size(); gi++) {
                    for (StreamTableExtractor.Line l : groups.get(gi).lines) lineToGroup.put(l, gi);
                }
                float gapCap = StreamTableExtractor.BLOCK_MERGE_MAX_GAP_FACTOR * pitch;
                float tol = StreamTableExtractor.BLOCK_MERGE_GUTTER_TOL_FACTOR * p.medianSpace;
                for (int i = 1; i < base.size(); i++) {
                    List<StreamTableExtractor.Line> a = base.get(i - 1), b = base.get(i);
                    int la = labelOf(a, geoms, p.pageNum), lb = labelOf(b, geoms, p.pageNum);
                    if (la < 0 || la != lb) continue;
                    Integer ga = lineToGroup.get(a.get(0)), gb = lineToGroup.get(b.get(0));
                    if (ga == null || gb == null || ga.equals(gb)) continue;   // merged: fine
                    float gap = b.get(0).yTop - a.get(a.size() - 1).yTop;
                    String reason;
                    if (a.size() < 3 || b.size() < 3) {
                        reason = "SHORT(" + Math.min(a.size(), b.size()) + " lines)";
                    } else if (gap > gapCap) {
                        reason = "GAP";
                    } else {
                        List<StreamTableExtractor.Gutter> gsA =
                                DiagSegHarness.blockGutters(a, finder, p.medianSpace);
                        List<StreamTableExtractor.Gutter> gsB =
                                DiagSegHarness.blockGutters(b, finder, p.medianSpace);
                        reason = StreamTableExtractor.columnModelsAgree(gsA, gsB, tol) ? "BAR" : "NOAGREE";
                    }
                    String bucket = reason.startsWith("SHORT") ? "SHORT" : reason;
                    // Would BRIDGING recover this boundary? Simulate the merged candidate exactly
                    // as merge condition 3 would score it, and report whether it clears the bar.
                    if (bucket.equals("SHORT")) {
                        List<StreamTableExtractor.Line> cand = new ArrayList<>(a);
                        cand.addAll(b);
                        String verdict = gap > gapCap ? "gapAlsoBlocks"
                                : cand.size() < 3 ? "candStillShort"
                                : (clearsBar(cand, finder, p.medianSpace) ? "BRIDGE-OK" : "barBlocks");
                        why.merge("SHORT/" + verdict, 1, Integer::sum);
                        reason = reason + " " + verdict;
                    }
                    why.merge(bucket, 1, Integer::sum);
                    detail.add(String.format(Locale.ROOT,
                            "  %-14s p%-2d lines %2d+%2d  gap=%5.1f (%4.2fx pitch)  %s",
                            unit.id().substring(unit.id().lastIndexOf('/') + 1), p.pageNum,
                            a.size(), b.size(), gap, gap / pitch, reason));
                }
            }
        }

        line("================================================================================");
        line("STEP A' RESIDUAL FRAGMENTATION CENSUS (geometry-bearing ICDAR documents)");
        line("================================================================================");
        line("GT (table,page) units with block geometry            : %d", gtTables);
        line("... split across >1 Step A block   (before Step A')  : %d", gtSplitAfterA);
        line("... still split across >1 group    (after  Step A')  : %d", gtSplitAfterAPrime);
        line("production groups straddling >1 GT table (over-merge): %d", straddleAfterAPrime);
        line("");
        line("WHY each surviving same-GT-table block boundary was NOT merged:");
        int tot = 0;
        for (Map.Entry<String, Integer> e : why.entrySet()) {
            line("  %-10s %d", e.getKey(), e.getValue());
            tot += e.getValue();
        }
        line("  %-10s %d", "TOTAL", tot);
        line("");
        line("DETAIL (every refused same-table boundary):");
        for (String s : detail) line("%s", s);

        Path outPath = Path.of(System.getProperty("segMergeDiagOut",
                "target/segmerge-diag.txt")).toAbsolutePath().normalize();
        Files.createDirectories(outPath.getParent());
        Files.writeString(outPath, out.toString(), StandardCharsets.UTF_8);
        System.out.println("Written to " + outPath);
    }
}
