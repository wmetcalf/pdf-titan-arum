// THROWAWAY DIAGNOSTIC (shading/fill lever, phase 2). Declares `package com.oai.titanarum;` for the
// same reason as BakeOffHarness/Diag9jHarness. Purely observational; no production class touched.
//
// What it does:
//  1. Replicates extractPage's pipeline EXACTLY, but with the row-grouping step swappable. The
//     "prod" strategy must reproduce the published baseline micro adjF1 (0.498) -- that equality is
//     the self-check that both the pipeline replica and the scoring replica are faithful.
//  2. Variants:
//       oracleGtRows  - group display lines by GROUND-TRUTH row membership (ICDAR cell bboxes).
//                       UPPER BOUND on any row-evidence lever (shading included).
//       bandSplit     - prod grouping + force a row break at every fill-band edge.
//       bandMerge     - prod grouping + force lines inside one fill band into one row.
//       bandBoth      - both.
//  3. Band <-> GT-row alignment stats, so we can see whether shading edges even coincide with
//     ground-truth row boundaries.
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.TableScore;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class DiagShade2Harness {

    private static final Path TABULA_RESOURCES =
            Path.of("corpus/tabula-java/src/test/resources").toAbsolutePath().normalize();
    private static final Path ICDAR_ROOT = TABULA_RESOURCES.resolve("technology/tabula/icdar2013-dataset");

    // -------------------------------------------------------------------------- strategies

    enum Strat { PROD, ORACLE_GT, BAND_SPLIT, BAND_MERGE, BAND_BOTH }

    /** Per-page inputs the variant strategies need beyond the production pipeline. */
    private record PageAux(List<DiagShadeHarness.Fill> bands, List<float[]> gtRowIntervals) {}

    // ------------------------------------------------------------------ pipeline replica

    /**
     * Byte-for-byte replica of {@link StreamTableExtractor#extractPage(int, List, GutterFinder)}
     * with the single row-grouping call swapped for {@code strat}. Everything else (word/line
     * building, block split, band, finder, trim, gridness gate, cell building, renderViews) is the
     * SAME production code path.
     */
    private static List<TableExtractor.TableHit> extractPageWith(
            int pageNum, List<TextPosition> glyphs, GutterFinder finder, Strat strat, PageAux aux) {
        List<StreamTableExtractor.Word> words;
        List<StreamTableExtractor.Line> lines;
        try {
            words = StreamTableExtractor.buildWords(glyphs);
            if (words.size() < 6) return List.of();
            float mfs = StreamTableExtractor.medianFontSize(words);
            lines = StreamTableExtractor.buildLines(words, mfs);
            if (lines.size() < 3) return List.of();
        } catch (TableExtractor.RulingOverflowException e) {
            return List.of();
        }
        float medianSpace = 0.5f * StreamTableExtractor.medianFontSize(words);

        List<List<StreamTableExtractor.Line>> blocks = StreamTableExtractor.splitIntoBlocks(lines);
        List<TableExtractor.TableHit> hits = new ArrayList<>();
        long pageWork = 0;
        for (List<StreamTableExtractor.Line> block : blocks) {
            if (hits.size() >= StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE) break;
            if (block.size() < 3) continue;
            long charge = block.stream().mapToLong(l -> l.words.size()).sum();
            if (pageWork + charge > StreamTableExtractor.MAX_STREAM_PAGE_BLOCK_WORK) break;
            pageWork += charge;
            try {
                float bandX0 = Float.MAX_VALUE, bandX1 = -Float.MAX_VALUE;
                for (StreamTableExtractor.Line l : block) for (StreamTableExtractor.Word w : l.words) {
                    bandX0 = Math.min(bandX0, w.x0); bandX1 = Math.max(bandX1, w.x1);
                }
                List<StreamTableExtractor.Gutter> gutters = finder.find(block, bandX0, bandX1, medianSpace);
                List<StreamTableExtractor.Line> trimmed =
                        StreamTableExtractor.trimEdgeLines(block, gutters, bandX0, bandX1, medianSpace);
                if (trimmed.size() < 3) continue;
                StreamTableExtractor.Grid grid = StreamTableExtractor.scoreGrid(trimmed, gutters, bandX0, bandX1);
                if (grid.confidence < StreamTableExtractor.STREAM_CONFIDENCE_MIN) continue;

                List<List<StreamTableExtractor.Line>> rowGroups =
                        group(strat, grid, aux, bandX0, bandX1);
                TableExtractor.TableHit hit = buildHit(pageNum, grid, rowGroups);
                if (hit != null) hits.add(hit);
            } catch (TableExtractor.RulingOverflowException e) {
                // skip this block only -- same as production
            }
        }
        return hits;
    }

    private static List<List<StreamTableExtractor.Line>> group(
            Strat strat, StreamTableExtractor.Grid grid, PageAux aux, float bandX0, float bandX1) {
        List<StreamTableExtractor.Line> ls = grid.rows;
        List<List<StreamTableExtractor.Line>> prod =
                StreamTableExtractor.groupLogicalRows(ls, grid.colBounds);
        if (strat == Strat.PROD || aux == null) return prod;

        if (strat == Strat.ORACLE_GT) {
            if (aux.gtRowIntervals().isEmpty()) return prod;
            // group by which GT row interval each display line's y-center falls in (nearest
            // interval when it falls in none) -- consecutive lines mapping to the same GT row
            // become one logical row.
            List<List<StreamTableExtractor.Line>> out = new ArrayList<>();
            int prevIdx = Integer.MIN_VALUE;
            for (StreamTableExtractor.Line l : ls) {
                int idx = nearestInterval(aux.gtRowIntervals(), 0.5f * (l.yTop + l.yBot));
                if (out.isEmpty() || idx != prevIdx) out.add(new ArrayList<>(List.of(l)));
                else out.get(out.size() - 1).add(l);
                prevIdx = idx;
            }
            return out;
        }

        // band strategies: only bands that horizontally cover most of this block's band and
        // vertically overlap it count as row evidence for this block.
        float blockTop = Float.MAX_VALUE, blockBot = -Float.MAX_VALUE;
        for (StreamTableExtractor.Line l : ls) { blockTop = Math.min(blockTop, l.yTop); blockBot = Math.max(blockBot, l.yBot); }
        List<DiagShadeHarness.Fill> use = new ArrayList<>();
        for (DiagShadeHarness.Fill f : aux.bands()) {
            float ox = Math.min(f.x1, bandX1) - Math.max(f.x0, bandX0);
            if (ox < 0.6f * (bandX1 - bandX0)) continue;
            if (f.y1 < blockTop - 2 || f.y0 > blockBot + 2) continue;
            use.add(f);
        }
        if (use.isEmpty()) return prod;

        boolean doSplit = strat == Strat.BAND_SPLIT || strat == Strat.BAND_BOTH;
        boolean doMerge = strat == Strat.BAND_MERGE || strat == Strat.BAND_BOTH;

        // prod's own boundary decisions, as a per-line "startsNewRow" bitmap
        boolean[] startsNew = new boolean[ls.size()];
        int i = 0;
        for (List<StreamTableExtractor.Line> g : prod) { startsNew[i] = true; i += g.size(); }

        List<Float> edges = new ArrayList<>();
        for (DiagShadeHarness.Fill f : use) { edges.add(f.y0); edges.add(f.y1); }

        List<List<StreamTableExtractor.Line>> out = new ArrayList<>();
        for (int k = 0; k < ls.size(); k++) {
            StreamTableExtractor.Line l = ls.get(k);
            boolean newRow = startsNew[k];
            if (k > 0) {
                float cPrev = 0.5f * (ls.get(k - 1).yTop + ls.get(k - 1).yBot);
                float cCur = 0.5f * (l.yTop + l.yBot);
                boolean edgeBetween = false;
                for (float e : edges) if (e > Math.min(cPrev, cCur) && e < Math.max(cPrev, cCur)) { edgeBetween = true; break; }
                boolean sameBand = false;
                for (DiagShadeHarness.Fill f : use) {
                    if (cPrev >= f.y0 && cPrev <= f.y1 && cCur >= f.y0 && cCur <= f.y1) { sameBand = true; break; }
                }
                if (doSplit && edgeBetween) newRow = true;
                if (doMerge && sameBand && !edgeBetween) newRow = false;
            }
            if (out.isEmpty() || newRow) out.add(new ArrayList<>(List.of(l)));
            else out.get(out.size() - 1).add(l);
        }
        return out;
    }

    private static int nearestInterval(List<float[]> intervals, float y) {
        int best = -1; float bestD = Float.MAX_VALUE;
        for (int i = 0; i < intervals.size(); i++) {
            float[] iv = intervals.get(i);
            float d = (y >= iv[0] && y <= iv[1]) ? 0 : Math.min(Math.abs(y - iv[0]), Math.abs(y - iv[1]));
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    /** Replica of StreamTableExtractor.buildHit (private) with the row groups supplied. */
    private static TableExtractor.TableHit buildHit(int pageNum, StreamTableExtractor.Grid grid,
                                                    List<List<StreamTableExtractor.Line>> rowGroups) {
        int cols = grid.colBounds.length - 1;
        int rows = rowGroups.size();
        if ((long) rows * cols > TableExtractor.MAX_CELLS_PER_TABLE)
            throw new TableExtractor.RulingOverflowException();
        TableExtractor.TableHit t = new TableExtractor.TableHit();
        t.page = pageNum;
        t.extractionMethod = "stream";
        t.confidence = Math.round(grid.confidence * 1000.0) / 1000.0;
        t.rowCount = rows; t.colCount = cols;
        t.cells = new ArrayList<>();
        float tx0=Float.MAX_VALUE, ty0=Float.MAX_VALUE, tx1=-Float.MAX_VALUE, ty1=-Float.MAX_VALUE;
        for (int r = 0; r < rows; r++) {
            StringBuilder[] text = new StringBuilder[cols];
            float[][] box = new float[cols][];
            for (StreamTableExtractor.Line line : rowGroups.get(r)) {
                for (StreamTableExtractor.Word w : line.words) {
                    int c = colOf(w.cx(), grid.colBounds);
                    if (text[c] == null) { text[c] = new StringBuilder(); box[c] = new float[]{w.x0,w.y0,w.x1,w.y1}; }
                    else { if (text[c].length() > 0) text[c].append(' '); }
                    text[c].append(w.text);
                    box[c][0]=Math.min(box[c][0],w.x0); box[c][1]=Math.min(box[c][1],w.y0);
                    box[c][2]=Math.max(box[c][2],w.x1); box[c][3]=Math.max(box[c][3],w.y1);
                }
            }
            for (int c = 0; c < cols; c++) {
                if (text[c] == null) continue;
                TableExtractor.CellHit cell = new TableExtractor.CellHit();
                cell.row = r; cell.col = c; cell.rowSpan = 1; cell.colSpan = 1;
                cell.text = text[c].toString();
                cell.bbox = box[c];
                t.cells.add(cell);
                tx0=Math.min(tx0,box[c][0]); ty0=Math.min(ty0,box[c][1]);
                tx1=Math.max(tx1,box[c][2]); ty1=Math.max(ty1,box[c][3]);
            }
        }
        if (t.cells.isEmpty()) return null;
        t.bbox = new float[]{tx0, ty0, tx1, ty1};
        TableExtractor.renderViews(t);
        return t;
    }

    /** Replica of StreamTableExtractor.colOf (private). */
    private static int colOf(float x, float[] bounds) {
        for (int c = 0; c + 1 < bounds.length; c++) if (x < bounds[c + 1]) return c;
        return bounds.length - 2;
    }

    // ---------------------------------------------------------------- scoring replica (validated)

    private record Agg(int tp, int fp, int fn, int adjMatched, int adjDet, int adjGt, double adjF1) {}

    /** Replica of BakeOffHarness.scoreUnit's pairing/scoring, but over a supplied hit list. */
    private static Agg score(BakeOffHarness.ScoreUnit unit, List<TableExtractor.TableHit> hits) {
        List<TableExtractor.TableHit> available = new ArrayList<>(hits);
        int tp = 0, fp = 0, fn = 0, adjM = 0, adjD = 0, adjG = 0;
        for (GroundTruth.Table expected : unit.expected()) {
            if (available.isEmpty()) {
                fn += nonEmptyCellCount(expected.rows());
                adjG += TableScore.relationCount(expected.rows());
                continue;
            }
            TableExtractor.TableHit best = null; TableScore.Result bestResult = null; double bestF1 = -1;
            for (TableExtractor.TableHit h : available) {
                TableScore.Result r = TableScore.score(expected, h.rows);
                if (r.f1() > bestF1) { bestF1 = r.f1(); best = h; bestResult = r; }
            }
            available.remove(best);
            tp += bestResult.truePositives(); fp += bestResult.falsePositives(); fn += bestResult.falseNegatives();
            TableScore.AdjResult a = TableScore.scoreAdjacency(expected, best.rows);
            adjM += a.matched(); adjD += a.detectedTotal(); adjG += a.gtTotal();
        }
        for (TableExtractor.TableHit h : available) {
            fp += nonEmptyCellCount(h.rows);
            adjD += TableScore.relationCount(h.rows);
        }
        double p = adjD == 0 ? 0 : (double) adjM / adjD;
        double r = adjG == 0 ? 0 : (double) adjM / adjG;
        double f1 = adjM == 0 ? 0 : 2 * p * r / (p + r);
        return new Agg(tp, fp, fn, adjM, adjD, adjG, f1);
    }

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

    // ------------------------------------------------------------------- GT row intervals (ICDAR)

    private static final Pattern REGION = Pattern.compile("<region\\b[^>]*page='(\\d+)'[^>]*>");
    private static final Pattern CELL = Pattern.compile(
            "<cell\\b[^>]*start-row='(-?\\d+)'[^>]*?>\\s*<bounding-box x1='(-?[\\d.]+)' y1='(-?[\\d.]+)' x2='(-?[\\d.]+)' y2='(-?[\\d.]+)'");

    /** pageNum(1-based) -> list of GT row y-intervals in TOP-LEFT page space, sorted. */
    private static Map<Integer, List<float[]>> gtRowIntervals(List<Path> xmls, PDDocument doc) throws IOException {
        Map<Integer, Map<Integer, float[]>> byPageRow = new TreeMap<>();
        for (Path xml : xmls) {
            String s = Files.readString(xml, StandardCharsets.ISO_8859_1);
            // split into regions; each region carries its own page number
            List<int[]> spans = new ArrayList<>();
            List<Integer> pageOf = new ArrayList<>();
            Matcher rm = REGION.matcher(s);
            List<Integer> starts = new ArrayList<>();
            while (rm.find()) { starts.add(rm.end()); pageOf.add(Integer.parseInt(rm.group(1))); }
            for (int i = 0; i < starts.size(); i++) {
                int end = (i + 1 < starts.size()) ? starts.get(i + 1) : s.length();
                spans.add(new int[]{starts.get(i), end});
            }
            for (int i = 0; i < spans.size(); i++) {
                int page = pageOf.get(i);
                String body = s.substring(spans.get(i)[0], spans.get(i)[1]);
                Matcher cm = CELL.matcher(body);
                while (cm.find()) {
                    int row = Integer.parseInt(cm.group(1));
                    float y1 = Float.parseFloat(cm.group(3)), y2 = Float.parseFloat(cm.group(5));
                    if (page < 1 || page > doc.getNumberOfPages()) continue;
                    PDPage p = doc.getPage(page - 1);
                    PDRectangle cb = p.getCropBox();
                    float topA = cb.getUpperRightY() - Math.max(y1, y2);
                    float topB = cb.getUpperRightY() - Math.min(y1, y2);
                    Map<Integer, float[]> rows = byPageRow.computeIfAbsent(page, k -> new TreeMap<>());
                    float[] iv = rows.get(row);
                    if (iv == null) rows.put(row, new float[]{topA, topB});
                    else { iv[0] = Math.min(iv[0], topA); iv[1] = Math.max(iv[1], topB); }
                }
            }
        }
        Map<Integer, List<float[]>> out = new TreeMap<>();
        for (Map.Entry<Integer, Map<Integer, float[]>> e : byPageRow.entrySet()) {
            List<float[]> l = new ArrayList<>(e.getValue().values());
            l.sort(Comparator.comparingDouble(a -> a[0]));
            out.put(e.getKey(), l);
        }
        return out;
    }

    private static Map<String, List<Path>> icdarXmlsByPdfId() throws IOException {
        Map<String, List<Path>> out = new LinkedHashMap<>();
        List<Path> xmls;
        try (Stream<Path> w = Files.walk(ICDAR_ROOT)) {
            xmls = w.filter(p -> p.getFileName().toString().endsWith("-str.xml")).sorted().collect(Collectors.toList());
        }
        for (Path xml : xmls) {
            String base = xml.getFileName().toString();
            base = base.substring(0, base.length() - "-str.xml".length());
            Path dir = xml.getParent();
            Path pdf = dir.resolve(base + ".pdf");
            if (!Files.exists(pdf)) {
                char last = base.charAt(base.length() - 1);
                if (Character.isLowerCase(last) && last != 'a') {
                    Path alt = dir.resolve(base.substring(0, base.length() - 1) + "a.pdf");
                    if (Files.exists(alt)) pdf = alt;
                }
            }
            if (!Files.exists(pdf)) continue;
            out.computeIfAbsent(TABULA_RESOURCES.relativize(pdf.toAbsolutePath().normalize()).toString(),
                    k -> new ArrayList<>()).add(xml);
        }
        return out;
    }

    // ------------------------------------------------------------------------------- main run

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("diagShade2"), "set -DdiagShade2=true to run");

        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        List<BakeOffHarness.ScoreUnit> units = corpus.units;
        Map<String, List<Path>> xmlsById = icdarXmlsByPdfId();
        GutterFinder breuel = new BreuelGutterFinder();

        Map<Strat, int[]> totals = new LinkedHashMap<>();   // strat -> {adjM, adjD, adjG, tp, fp, fn}
        for (Strat s : Strat.values()) totals.put(s, new int[6]);
        Map<Strat, int[]> bandOnly = new LinkedHashMap<>(); // same, restricted to band PDFs
        for (Strat s : Strat.values()) bandOnly.put(s, new int[6]);

        // band <-> GT row alignment tallies
        int bandsTotal = 0, bands1to1 = 0, bandsMultiRow = 0, bandsNoRow = 0;
        int gtBoundaries = 0, gtBoundariesWithBandEdge = 0;
        List<String> changedPdfs = new ArrayList<>();

        System.out.printf(Locale.ROOT, "%-34s %7s %7s %7s %7s %7s  %s%n",
                "pdf", "prod", "oracle", "bSplit", "bMerge", "bBoth", "bands");

        for (BakeOffHarness.ScoreUnit unit : units) {
            Map<Strat, List<TableExtractor.TableHit>> hitsByStrat = new LinkedHashMap<>();
            for (Strat s : Strat.values()) hitsByStrat.put(s, new ArrayList<>());
            int nBandsPdf = 0;

            try (PDDocument doc = Loader.loadPDF(unit.pdf().toFile())) {
                Map<Integer, List<float[]>> gtRows =
                        xmlsById.containsKey(unit.id()) ? gtRowIntervals(xmlsById.get(unit.id()), doc) : Map.of();
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    PDPage page = doc.getPage(i);
                    DiagShadeHarness.FillCollector fc = new DiagShadeHarness.FillCollector(page);
                    try { fc.processPage(page); } catch (Throwable ignored) {}
                    PDRectangle cb = page.getCropBox();
                    float pw = cb.getWidth(), ph = cb.getHeight();
                    if (page.getRotation() == 90 || page.getRotation() == 270) { float t = pw; pw = ph; ph = t; }
                    float pageArea = pw * ph;

                    List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, i);
                    float pitch = 12f;
                    try {
                        List<StreamTableExtractor.Word> ws = StreamTableExtractor.buildWords(glyphs);
                        if (ws.size() >= 6) pitch = DiagShadeHarness.medianPitch(
                                StreamTableExtractor.buildLines(ws, StreamTableExtractor.medianFontSize(ws)));
                    } catch (Throwable ignored) {}

                    List<DiagShadeHarness.Fill> bands = new ArrayList<>();
                    for (DiagShadeHarness.Fill f : fc.fills) {
                        if (DiagShadeHarness.isWhitish(f.rgb)) continue;
                        if (f.area() >= 0.65f * pageArea) continue;
                        if (Math.min(f.w(), f.h()) <= TableExtractor.THIN_FILL_MAX) continue;
                        if (f.w() < 0.30f * pw) continue;
                        if (f.h() > 3.0f * pitch) continue;      // taller = region fill, not a row band
                        bands.add(f);
                    }
                    nBandsPdf += bands.size();

                    List<float[]> gtIv = gtRows.getOrDefault(i + 1, List.of());
                    PageAux aux = new PageAux(bands, gtIv);

                    for (Strat s : Strat.values()) {
                        hitsByStrat.get(s).addAll(extractPageWith(i + 1, glyphs, breuel, s, aux));
                    }

                    // ---- alignment tallies (only where we have GT bboxes on this page)
                    if (!gtIv.isEmpty()) {
                        for (DiagShadeHarness.Fill f : bands) {
                            bandsTotal++;
                            int overlapped = 0;
                            for (float[] iv : gtIv) {
                                float ov = Math.min(f.y1, iv[1]) - Math.max(f.y0, iv[0]);
                                if (ov > 0.5f * (iv[1] - iv[0])) overlapped++;
                            }
                            if (overlapped == 0) bandsNoRow++;
                            else if (overlapped == 1) bands1to1++;
                            else bandsMultiRow++;
                        }
                        for (int r = 1; r < gtIv.size(); r++) {
                            float boundary = 0.5f * (gtIv.get(r - 1)[1] + gtIv.get(r)[0]);
                            gtBoundaries++;
                            boolean covered = false;
                            for (DiagShadeHarness.Fill f : bands) {
                                if (Math.abs(f.y0 - boundary) <= 2.5f || Math.abs(f.y1 - boundary) <= 2.5f) { covered = true; break; }
                            }
                            if (covered) gtBoundariesWithBandEdge++;
                        }
                    }
                }
            } catch (Throwable t) {
                System.out.println("  !! " + unit.id() + ": " + t);
            }

            Map<Strat, Agg> aggs = new LinkedHashMap<>();
            for (Strat s : Strat.values()) aggs.put(s, score(unit, hitsByStrat.get(s)));
            for (Strat s : Strat.values()) {
                Agg a = aggs.get(s);
                int[] t = totals.get(s);
                t[0] += a.adjMatched(); t[1] += a.adjDet(); t[2] += a.adjGt();
                t[3] += a.tp(); t[4] += a.fp(); t[5] += a.fn();
                if (nBandsPdf > 0) {
                    int[] b = bandOnly.get(s);
                    b[0] += a.adjMatched(); b[1] += a.adjDet(); b[2] += a.adjGt();
                    b[3] += a.tp(); b[4] += a.fp(); b[5] += a.fn();
                }
            }
            boolean changed = aggs.get(Strat.BAND_BOTH).adjMatched() != aggs.get(Strat.PROD).adjMatched()
                    || aggs.get(Strat.BAND_BOTH).adjDet() != aggs.get(Strat.PROD).adjDet();
            if (changed) changedPdfs.add(DiagShadeHarness.shortId(unit.id()));

            System.out.printf(Locale.ROOT, "%-34s %7.3f %7.3f %7.3f %7.3f %7.3f  %d%s%n",
                    DiagShadeHarness.shortId(unit.id()),
                    aggs.get(Strat.PROD).adjF1(), aggs.get(Strat.ORACLE_GT).adjF1(),
                    aggs.get(Strat.BAND_SPLIT).adjF1(), aggs.get(Strat.BAND_MERGE).adjF1(),
                    aggs.get(Strat.BAND_BOTH).adjF1(), nBandsPdf, changed ? "  <-CHANGED" : "");
        }

        System.out.println();
        System.out.println("==== CORPUS MICRO (all " + units.size() + " PDFs) ====");
        for (Strat s : Strat.values()) printMicro(s.name(), totals.get(s));
        System.out.println();
        System.out.println("==== CORPUS MICRO (band PDFs only) ====");
        for (Strat s : Strat.values()) printMicro(s.name(), bandOnly.get(s));

        System.out.println();
        System.out.println("==== BAND <-> GT ROW ALIGNMENT (pages with GT bboxes) ====");
        System.out.println("bands examined                     : " + bandsTotal);
        System.out.println("  covering exactly 1 GT row        : " + bands1to1);
        System.out.println("  covering >1 GT row               : " + bandsMultiRow);
        System.out.println("  covering no GT row               : " + bandsNoRow);
        System.out.println("GT row boundaries examined          : " + gtBoundaries);
        System.out.println("  with a band edge within 2.5pt    : " + gtBoundariesWithBandEdge);
        System.out.println("PDFs whose output CHANGED under bandBoth: " + changedPdfs.size() + " " + changedPdfs);
    }

    private static void printMicro(String name, int[] t) {
        double p = t[1] == 0 ? 0 : (double) t[0] / t[1];
        double r = t[2] == 0 ? 0 : (double) t[0] / t[2];
        double f1 = t[0] == 0 ? 0 : 2 * p * r / (p + r);
        double ep = (t[3] + t[4]) == 0 ? 0 : (double) t[3] / (t[3] + t[4]);
        double er = (t[3] + t[5]) == 0 ? 0 : (double) t[3] / (t[3] + t[5]);
        double ef1 = t[3] == 0 ? 0 : 2 * ep * er / (ep + er);
        System.out.printf(Locale.ROOT, "%-11s adjP %.3f adjR %.3f adjF1 %.3f  (M=%d D=%d G=%d)  exactF1 %.3f%n",
                name, p, r, f1, t[0], t[1], t[2], ef1);
    }
}
