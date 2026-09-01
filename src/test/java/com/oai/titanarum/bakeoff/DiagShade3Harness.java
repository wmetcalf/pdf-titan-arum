// THROWAWAY DIAGNOSTIC (shading/fill lever, phase 3). `package com.oai.titanarum;` for the usual
// reason. Purely observational.
//
//  A. mass accounting: where the adjacency FN/FP mass actually sits (zero-hit PDFs, band PDFs).
//  B. COLUMN evidence: GT column boundaries our grid MISSES (the #1 within-table FP source) --
//     how many have a visible fill EDGE within 3pt (i.e. could shading supply the missing split?).
//  C. ROW evidence: GT row boundaries our row grouping MISSES -- same question for fill bands.
//  D. per-PDF fill inventory dump for the interesting cases (0-hit PDFs with lots of fills).
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

class DiagShade3Harness {

    private static final Path TABULA_RESOURCES =
            Path.of("corpus/tabula-java/src/test/resources").toAbsolutePath().normalize();
    private static final Path ICDAR_ROOT = TABULA_RESOURCES.resolve("technology/tabula/icdar2013-dataset");

    private static final Set<String> DEEP_DIVE = Set.of(
            "us-011a.pdf", "us-010.pdf", "us-036.pdf", "us-012.pdf", "us-005.pdf",
            "spanning_cells.pdf", "us-018.pdf", "eu-001.pdf", "frx_2012_disclosure.pdf",
            "us-029.pdf", "us-020.pdf", "us-006.pdf");

    // ---------------------------------------------------------------- GT structure with geometry

    /** One ground-truth region (table) with per-row / per-column geometry in TOP-LEFT page space. */
    private static final class GtRegion {
        int page;
        final Map<Integer, float[]> rowIv = new TreeMap<>();  // start-row -> {yTop,yBot}
        final Map<Integer, float[]> colIv = new TreeMap<>();  // start-col -> {x0,x1} (non-spanning cells only)
        float x0 = Float.MAX_VALUE, y0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, y1 = -Float.MAX_VALUE;
    }

    private static final Pattern REGION = Pattern.compile("<region\\b[^>]*page='(\\d+)'[^>]*>");
    private static final Pattern CELL = Pattern.compile(
            "<cell\\b([^>]*)>\\s*<bounding-box x1='(-?[\\d.]+)' y1='(-?[\\d.]+)' x2='(-?[\\d.]+)' y2='(-?[\\d.]+)'");
    private static final Pattern ATTR = Pattern.compile("([a-z-]+)='(-?\\d+)'");

    private static List<GtRegion> gtRegions(List<Path> xmls, PDDocument doc) throws IOException {
        List<GtRegion> out = new ArrayList<>();
        for (Path xml : xmls) {
            String s = Files.readString(xml, StandardCharsets.ISO_8859_1);
            Matcher rm = REGION.matcher(s);
            List<Integer> starts = new ArrayList<>();
            List<Integer> pages = new ArrayList<>();
            while (rm.find()) { starts.add(rm.end()); pages.add(Integer.parseInt(rm.group(1))); }
            for (int i = 0; i < starts.size(); i++) {
                int end = (i + 1 < starts.size()) ? starts.get(i + 1) : s.length();
                String body = s.substring(starts.get(i), end);
                int page = pages.get(i);
                if (page < 1 || page > doc.getNumberOfPages()) continue;
                PDRectangle cb = doc.getPage(page - 1).getCropBox();
                GtRegion g = new GtRegion();
                g.page = page;
                Matcher cm = CELL.matcher(body);
                while (cm.find()) {
                    Map<String, Integer> attrs = new LinkedHashMap<>();
                    Matcher am = ATTR.matcher(cm.group(1));
                    while (am.find()) attrs.put(am.group(1), Integer.parseInt(am.group(2)));
                    Integer sr = attrs.get("start-row"), sc = attrs.get("start-col");
                    if (sr == null || sc == null) continue;
                    int er = attrs.getOrDefault("end-row", sr), ec = attrs.getOrDefault("end-col", sc);
                    float gx1 = Float.parseFloat(cm.group(2)), gy1 = Float.parseFloat(cm.group(3));
                    float gx2 = Float.parseFloat(cm.group(4)), gy2 = Float.parseFloat(cm.group(5));
                    float lx = Math.min(gx1, gx2) - cb.getLowerLeftX(), rx = Math.max(gx1, gx2) - cb.getLowerLeftX();
                    float ty = cb.getUpperRightY() - Math.max(gy1, gy2), by = cb.getUpperRightY() - Math.min(gy1, gy2);
                    g.x0 = Math.min(g.x0, lx); g.x1 = Math.max(g.x1, rx);
                    g.y0 = Math.min(g.y0, ty); g.y1 = Math.max(g.y1, by);
                    if (er == sr) merge(g.rowIv, sr, ty, by);
                    if (ec == sc) merge(g.colIv, sc, lx, rx);
                }
                if (!g.rowIv.isEmpty()) out.add(g);
            }
        }
        return out;
    }

    private static void merge(Map<Integer, float[]> m, int k, float a, float b) {
        float[] v = m.get(k);
        if (v == null) m.put(k, new float[]{a, b});
        else { v[0] = Math.min(v[0], a); v[1] = Math.max(v[1], b); }
    }

    /** Midpoint boundaries between consecutive (sorted-by-position) intervals. */
    private static List<Float> boundaries(Map<Integer, float[]> ivs) {
        List<float[]> l = new ArrayList<>(ivs.values());
        l.sort(Comparator.comparingDouble(a -> a[0]));
        List<Float> out = new ArrayList<>();
        for (int i = 1; i < l.size(); i++) {
            float prevEnd = l.get(i - 1)[1], curStart = l.get(i)[0];
            // Intervals may OVERLAP (our own row/col extents come from glyph boxes, which include
            // ascender/descender, so adjacent rows' boxes can touch). Midpoint is still the best
            // estimate of the separating line; skipping overlaps would inflate "missed" counts.
            out.add(0.5f * (prevEnd + curStart));
        }
        return out;
    }

    // -------------------------------------------------------------------------------- the run

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("diagShade3"), "set -DdiagShade3=true to run");

        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        List<BakeOffHarness.ScoreUnit> units = corpus.units;
        Map<String, List<Path>> xmlsById = icdarXmlsByPdfId();
        GutterFinder breuel = new BreuelGutterFinder();

        int gTotal = 0, mTotal = 0, dTotal = 0;
        int gZeroHit = 0, fnZeroHit = 0;
        int gBand = 0, fnBand = 0, fpBand = 0, fnAll = 0, fpAll = 0;

        // column / row boundary analysis tallies
        int colBoundsGt = 0, colBoundsMissed = 0, colMissedWithFillEdge = 0, colMissedWithThinFillEdge = 0;
        int rowBoundsGt = 0, rowBoundsMissed = 0, rowMissedWithBandEdge = 0;
        int regionsPaired = 0, regionsUnpaired = 0;
        int regionsWithNonThinFill = 0, regionsWithWideBand = 0;
        final int[] rowMissedWithAnyFillEdgeLocal = new int[1];
        final int[] colMissedWithVertRuling = new int[1];
        final int[] colGtWithVertRuling = new int[1];
        final int[] rowMissedWithHorizRuling = new int[1];
        List<String> colFixablePdfs = new ArrayList<>();
        List<String> rowFixablePdfs = new ArrayList<>();

        System.out.printf(Locale.ROOT, "%-24s %6s %6s %6s %6s %5s %5s | %6s %6s %6s | %6s %6s %6s%n",
                "pdf", "adjF1", "M", "D", "G", "hits", "gt", "gtCol", "miss", "fixbl", "gtRow", "miss", "fixbl");

        for (BakeOffHarness.ScoreUnit unit : units) {
            BakeOffHarness.PdfScore sc = BakeOffHarness.scoreUnit(breuel, unit);
            List<TableExtractor.TableHit> hits = BakeOffHarness.runFinderOnPdf(breuel, unit.pdf()).hits();
            gTotal += sc.adjGtTotal(); mTotal += sc.adjMatched(); dTotal += sc.adjDetectedTotal();
            int fn = sc.adjGtTotal() - sc.adjMatched(), fp = sc.adjDetectedTotal() - sc.adjMatched();
            fnAll += fn; fpAll += fp;
            if (hits.isEmpty()) { gZeroHit += sc.adjGtTotal(); fnZeroHit += fn; }

            int pdfColGt = 0, pdfColMiss = 0, pdfColFix = 0, pdfRowGt = 0, pdfRowMiss = 0, pdfRowFix = 0;
            int nBands = 0, nVisFills = 0;
            StringBuilder deep = new StringBuilder();

            try (PDDocument doc = Loader.loadPDF(unit.pdf().toFile())) {
                List<GtRegion> regions = xmlsById.containsKey(unit.id())
                        ? gtRegions(xmlsById.get(unit.id()), doc) : List.of();
                Map<Integer, List<DiagShadeHarness.Fill>> fillsByPage = new TreeMap<>();
                Map<Integer, List<DiagShadeHarness.Fill>> bandsByPage = new TreeMap<>();
                Map<Integer, List<TableExtractor.Ruling>> rulingsByPage = new TreeMap<>();
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    PDPage page = doc.getPage(i);
                    DiagShadeHarness.FillCollector fc = new DiagShadeHarness.FillCollector(page);
                    try { fc.processPage(page); } catch (Throwable ignored) {}
                    PDRectangle cb = page.getCropBox();
                    float pw = cb.getWidth(), ph = cb.getHeight();
                    if (page.getRotation() == 90 || page.getRotation() == 270) { float t = pw; pw = ph; ph = t; }
                    float pitch = 12f;
                    try {
                        List<TextPosition> gl = TableTestPdfs.harvestGlyphs(doc, i);
                        List<StreamTableExtractor.Word> ws = StreamTableExtractor.buildWords(gl);
                        if (ws.size() >= 6) pitch = DiagShadeHarness.medianPitch(
                                StreamTableExtractor.buildLines(ws, StreamTableExtractor.medianFontSize(ws)));
                    } catch (Throwable ignored) {}
                    List<DiagShadeHarness.Fill> vis = new ArrayList<>(), bands = new ArrayList<>();
                    for (DiagShadeHarness.Fill f : fc.fills) {
                        if (DiagShadeHarness.isWhitish(f.rgb)) continue;
                        if (f.area() >= 0.65f * pw * ph) continue;
                        vis.add(f);
                        if (Math.min(f.w(), f.h()) > TableExtractor.THIN_FILL_MAX
                                && f.w() >= 0.30f * pw && f.h() <= 3.0f * pitch) bands.add(f);
                    }
                    fillsByPage.put(i + 1, vis); bandsByPage.put(i + 1, bands);
                    try {
                        rulingsByPage.put(i + 1, TableExtractor.normalize(TableExtractor.collectRulings(page)));
                    } catch (Throwable t) { rulingsByPage.put(i + 1, List.of()); }
                    nVisFills += vis.size(); nBands += bands.size();
                }

                for (GtRegion g : regions) {
                    // geometric pairing: our hit on the same page with max IoU
                    TableExtractor.TableHit best = null; double bestIoU = 0;
                    for (TableExtractor.TableHit h : hits) {
                        if (h.page != g.page || h.bbox == null) continue;
                        double iou = iou(h.bbox, new float[]{g.x0, g.y0, g.x1, g.y1});
                        if (iou > bestIoU) { bestIoU = iou; best = h; }
                    }
                    if (best == null || bestIoU < 0.10) { regionsUnpaired++; continue; }
                    regionsPaired++;

                    Map<Integer, float[]> ourCols = new TreeMap<>(), ourRows = new TreeMap<>();
                    for (TableExtractor.CellHit c : best.cells) {
                        merge(ourCols, c.col, c.bbox[0], c.bbox[2]);
                        merge(ourRows, c.row, c.bbox[1], c.bbox[3]);
                    }
                    List<Float> ourColB = boundaries(ourCols), ourRowB = boundaries(ourRows);
                    List<Float> gtColB = boundaries(g.colIv), gtRowB = boundaries(g.rowIv);
                    List<DiagShadeHarness.Fill> vis = fillsByPage.getOrDefault(g.page, List.of());
                    List<DiagShadeHarness.Fill> bands = bandsByPage.getOrDefault(g.page, List.of());
                    List<TableExtractor.Ruling> rul = rulingsByPage.getOrDefault(g.page, List.of());

                    boolean anyNonThinOverRegion = false, anyWideBandOverRegion = false;
                    for (DiagShadeHarness.Fill f : vis) {
                        if (Math.min(f.w(), f.h()) <= TableExtractor.THIN_FILL_MAX) continue;
                        float ox = Math.min(f.x1, g.x1) - Math.max(f.x0, g.x0);
                        float oy = Math.min(f.y1, g.y1) - Math.max(f.y0, g.y0);
                        if (ox <= 0 || oy <= 0) continue;
                        anyNonThinOverRegion = true;
                        if (ox >= 0.6f * (g.x1 - g.x0)) anyWideBandOverRegion = true;
                    }
                    if (anyNonThinOverRegion) regionsWithNonThinFill++;
                    if (anyWideBandOverRegion) regionsWithWideBand++;

                    if (Boolean.getBoolean("shadeRowDebug")
                            && DEEP_DIVE.contains(DiagShadeHarness.shortId(unit.id()))) {
                        System.out.printf(Locale.ROOT,
                                "    ROWDBG %s p%d ourRows=%d gtRows=%d%n      ourB=%s%n      gtB =%s%n",
                                DiagShadeHarness.shortId(unit.id()), g.page, ourRows.size(), g.rowIv.size(),
                                fmt(ourRowB), fmt(gtRowB));
                    }
                    float colTol = Math.max(6f, 0.45f * medianSize(g.colIv));
                    float colOff = registerOffset(ourColB, gtColB, 2f * colTol);
                    float rowTolBase = Math.max(4f, 0.45f * medianSize(g.rowIv));
                    float rowOff = registerOffset(ourRowB, gtRowB, 2f * rowTolBase);
                    for (float b : gtColB) {
                        if (b < best.bbox[0] || b > best.bbox[2]) continue;   // outside our extent
                        pdfColGt++;
                        for (TableExtractor.Ruling r : rul) {
                            if (r.horizontal()) continue;
                            float ry0 = Math.min(r.y1, r.y2), ry1 = Math.max(r.y1, r.y2);
                            float ov = Math.min(ry1, g.y1) - Math.max(ry0, g.y0);
                            if (ov <= 0.20f * (g.y1 - g.y0)) continue;
                            if (Math.abs(r.x1 - b) <= 3f) { colGtWithVertRuling[0]++; break; }
                        }
                        final float bc = b + colOff;
                        boolean have = ourColB.stream().anyMatch(o -> Math.abs(o - bc) <= colTol);
                        if (have) continue;
                        pdfColMiss++;
                        boolean fillEdge = false, thinEdge = false;
                        for (DiagShadeHarness.Fill f : vis) {
                            float ov = Math.min(f.y1, g.y1) - Math.max(f.y0, g.y0);
                            if (ov <= 0.20f * (g.y1 - g.y0)) continue;        // must span a real part of the table
                            boolean near = Math.abs(f.x0 - b) <= 3f || Math.abs(f.x1 - b) <= 3f;
                            if (!near) continue;
                            if (Math.min(f.w(), f.h()) <= TableExtractor.THIN_FILL_MAX) thinEdge = true;
                            else fillEdge = true;
                        }
                        if (fillEdge) pdfColFix++;
                        if (thinEdge) colMissedWithThinFillEdge++;
                        for (TableExtractor.Ruling r : rul) {
                            if (r.horizontal()) continue;
                            float ry0 = Math.min(r.y1, r.y2), ry1 = Math.max(r.y1, r.y2);
                            float ov = Math.min(ry1, g.y1) - Math.max(ry0, g.y0);
                            if (ov <= 0.20f * (g.y1 - g.y0)) continue;
                            if (Math.abs(r.x1 - b) <= 3f) { colMissedWithVertRuling[0]++; break; }
                        }
                    }
                    for (float b : gtRowB) {
                        if (b < best.bbox[1] || b > best.bbox[3]) continue;
                        pdfRowGt++;
                        final float br = b + rowOff;
                        boolean have = ourRowB.stream().anyMatch(o -> Math.abs(o - br) <= rowTolBase);
                        if (have) continue;
                        pdfRowMiss++;
                        boolean bandEdge = false;
                        for (DiagShadeHarness.Fill f : bands) {
                            float ox = Math.min(f.x1, g.x1) - Math.max(f.x0, g.x0);
                            if (ox <= 0.4f * (g.x1 - g.x0)) continue;
                            if (Math.abs(f.y0 - b) <= 3f || Math.abs(f.y1 - b) <= 3f) bandEdge = true;
                        }
                        if (bandEdge) pdfRowFix++;
                        for (TableExtractor.Ruling r : rul) {
                            if (!r.horizontal()) continue;
                            float rx0 = Math.min(r.x1, r.x2), rx1 = Math.max(r.x1, r.x2);
                            float ox = Math.min(rx1, g.x1) - Math.max(rx0, g.x0);
                            if (ox <= 0.4f * (g.x1 - g.x0)) continue;
                            if (Math.abs(r.y1 - b) <= 3f) { rowMissedWithHorizRuling[0]++; break; }
                        }
                        // BROAD variant: ANY visible non-thin fill (no width requirement at all)
                        for (DiagShadeHarness.Fill f : vis) {
                            if (Math.min(f.w(), f.h()) <= TableExtractor.THIN_FILL_MAX) continue;
                            if (Math.abs(f.y0 - b) <= 3f || Math.abs(f.y1 - b) <= 3f) { rowMissedWithAnyFillEdgeLocal[0]++; break; }
                        }
                    }
                }

                if (DEEP_DIVE.contains(DiagShadeHarness.shortId(unit.id()))) {
                    deep.append("  DEEP ").append(DiagShadeHarness.shortId(unit.id()))
                        .append(" hits=").append(hits.size())
                        .append(" gtRegions=").append(regions.size()).append('\n');
                    for (Map.Entry<Integer, List<DiagShadeHarness.Fill>> e : fillsByPage.entrySet()) {
                        List<DiagShadeHarness.Fill> vis = e.getValue();
                        if (vis.isEmpty()) continue;
                        List<DiagShadeHarness.Fill> nonThin = vis.stream()
                                .filter(f -> Math.min(f.w(), f.h()) > TableExtractor.THIN_FILL_MAX)
                                .sorted(Comparator.comparingDouble((DiagShadeHarness.Fill f) -> -f.area()))
                                .limit(8).collect(Collectors.toList());
                        deep.append("    p").append(e.getKey()).append(" vis=").append(vis.size())
                            .append(" nonThin=").append(vis.stream().filter(f -> Math.min(f.w(), f.h()) > TableExtractor.THIN_FILL_MAX).count())
                            .append(" biggest: ");
                        for (DiagShadeHarness.Fill f : nonThin) {
                            deep.append(String.format(Locale.ROOT, "[%.0f,%.0f %.0fx%.0f #%06X]",
                                    f.x0, f.y0, f.w(), f.h(), f.rgb));
                        }
                        deep.append('\n');
                    }
                    for (GtRegion g : regions) {
                        deep.append(String.format(Locale.ROOT, "    GT p%d bbox=[%.0f,%.0f %.0fx%.0f] rows=%d cols=%d%n",
                                g.page, g.x0, g.y0, g.x1 - g.x0, g.y1 - g.y0, g.rowIv.size(), g.colIv.size()));
                    }
                    for (TableExtractor.TableHit h : hits) {
                        deep.append(String.format(Locale.ROOT, "    HIT p%d bbox=[%.0f,%.0f %.0fx%.0f] %dx%d conf=%.3f%n",
                                h.page, h.bbox[0], h.bbox[1], h.bbox[2] - h.bbox[0], h.bbox[3] - h.bbox[1],
                                h.rowCount, h.colCount, h.confidence));
                    }
                }
            } catch (Throwable t) {
                System.out.println("  !! " + unit.id() + ": " + t);
            }

            colBoundsGt += pdfColGt; colBoundsMissed += pdfColMiss; colMissedWithFillEdge += pdfColFix;
            rowBoundsGt += pdfRowGt; rowBoundsMissed += pdfRowMiss; rowMissedWithBandEdge += pdfRowFix;
            if (pdfColFix > 0) colFixablePdfs.add(DiagShadeHarness.shortId(unit.id()) + ":" + pdfColFix);
            if (pdfRowFix > 0) rowFixablePdfs.add(DiagShadeHarness.shortId(unit.id()) + ":" + pdfRowFix);
            if (nBands > 0) { gBand += sc.adjGtTotal(); fnBand += fn; fpBand += fp; }

            System.out.printf(Locale.ROOT, "%-24s %6.3f %6d %6d %6d %5d %5d | %6d %6d %6d | %6d %6d %6d  %s%n",
                    DiagShadeHarness.shortId(unit.id()), sc.adjF1(), sc.adjMatched(), sc.adjDetectedTotal(),
                    sc.adjGtTotal(), hits.size(), unit.expected().size(),
                    pdfColGt, pdfColMiss, pdfColFix, pdfRowGt, pdfRowMiss, pdfRowFix,
                    nBands > 0 ? ("bands=" + nBands) : "");
            if (deep.length() > 0) System.out.print(deep);
        }

        System.out.println();
        System.out.println("==== A. MASS ACCOUNTING ====");
        System.out.printf(Locale.ROOT, "corpus: M=%d D=%d G=%d  FN=%d FP=%d%n", mTotal, dTotal, gTotal, fnAll, fpAll);
        System.out.printf(Locale.ROOT, "zero-hit PDFs: G=%d (%.1f%% of GT relations), FN=%d (%.1f%% of all FN)%n",
                gZeroHit, 100.0 * gZeroHit / gTotal, fnZeroHit, 100.0 * fnZeroHit / fnAll);
        System.out.printf(Locale.ROOT, "PDFs with >=1 band fill: G=%d (%.1f%%), FN=%d (%.1f%% of FN), FP=%d (%.1f%% of FP)%n",
                gBand, 100.0 * gBand / gTotal, fnBand, 100.0 * fnBand / fnAll, fpBand, 100.0 * fpBand / fpAll);

        System.out.println();
        System.out.println("==== B. COLUMN boundaries (paired GT regions only) ====");
        System.out.println("GT col boundaries inside our extent : " + colBoundsGt);
        System.out.println("  MISSED by our grid (under-split)  : " + colBoundsMissed);
        System.out.println("  ...with a non-thin fill edge <=3pt: " + colMissedWithFillEdge + "  " + colFixablePdfs);
        System.out.println("  ...with a THIN fill edge <=3pt    : " + colMissedWithThinFillEdge);
        System.out.println("  ...with a VERTICAL RULING <=3pt    : " + colMissedWithVertRuling[0] + "   <-- adjacent lever");
        System.out.println("ALL GT col boundaries with a vertical ruling <=3pt : " + colGtWithVertRuling[0]);
        System.out.println();
        System.out.println("==== C. ROW boundaries (paired GT regions only) ====");
        System.out.println("GT row boundaries inside our extent : " + rowBoundsGt);
        System.out.println("  MISSED by our grouping (merged)   : " + rowBoundsMissed);
        System.out.println("  ...with a band edge <=3pt         : " + rowMissedWithBandEdge + "  " + rowFixablePdfs);
        System.out.println("  ...with ANY non-thin fill edge    : " + rowMissedWithAnyFillEdgeLocal[0]);
        System.out.println("  ...with a HORIZONTAL RULING <=3pt  : " + rowMissedWithHorizRuling[0] + "   <-- adjacent lever");
        System.out.println("GT regions paired=" + regionsPaired + " unpaired=" + regionsUnpaired);
        System.out.println("paired GT regions overlapped by >=1 visible non-thin fill : " + regionsWithNonThinFill);
        System.out.println("paired GT regions with a >=60%-width non-thin fill        : " + regionsWithWideBand);
    }

    /**
     * Our own row/col extents come from GLYPH boxes (ascender..descender), ICDAR's from tight
     * content boxes, so the two boundary sets carry a systematic offset (measured: ~+7pt vertically
     * on us-018/us-006, ~-5pt on us-020). Estimate that offset robustly (median of nearest-neighbour
     * deltas within a generous window) so "boundary missed" means genuinely missed, not misregistered.
     */
    private static float registerOffset(List<Float> ours, List<Float> gt, float window) {
        List<Float> deltas = new ArrayList<>();
        for (float b : gt) {
            float best = Float.NaN, bestD = Float.MAX_VALUE;
            for (float o : ours) {
                float d = Math.abs(o - b);
                if (d < bestD) { bestD = d; best = o; }
            }
            if (!Float.isNaN(best) && bestD <= window) deltas.add(best - b);
        }
        if (deltas.isEmpty()) return 0f;
        deltas.sort(Comparator.naturalOrder());
        return deltas.get(deltas.size() / 2);
    }

    private static float medianSize(Map<Integer, float[]> ivs) {
        List<Float> h = new ArrayList<>();
        for (float[] v : ivs.values()) h.add(v[1] - v[0]);
        if (h.isEmpty()) return 12f;
        h.sort(Comparator.naturalOrder());
        return Math.max(4f, h.get(h.size() / 2));
    }

    private static String fmt(List<Float> l) {
        StringBuilder sb = new StringBuilder();
        for (float f : l) sb.append(String.format(Locale.ROOT, "%.0f ", f));
        return sb.toString();
    }

    private static double iou(float[] a, float[] b) {
        float ix = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
        float iy = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
        if (ix <= 0 || iy <= 0) return 0;
        double inter = (double) ix * iy;
        double ua = (double) (a[2] - a[0]) * (a[3] - a[1]), ub = (double) (b[2] - b[0]) * (b[3] - b[1]);
        return inter / (ua + ub - inter);
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
}
