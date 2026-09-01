// THROWAWAY DIAGNOSTIC (task: shading/fill lever). Physically under
// src/test/java/com/oai/titanarum/bakeoff/ but declares `package com.oai.titanarum;` -- same trick
// BakeOffHarness/Diag9cHarness/Diag9jHarness use, for the same reason (StreamTableExtractor and its
// Word/Line types are package-private). Purely observational: reads the corpus, dumps geometry.
// Does NOT modify any production class, TableScore, or GroundTruth.
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class DiagShadeHarness {

    // ------------------------------------------------------------------ fill record + collector

    /** One filled axis-aligned rectangle, in the SAME visual top-left frame TextPositions use. */
    static final class Fill {
        float x0, y0, x1, y1;
        int rgb;
        boolean stroked;      // came from fillAndStrokePath (B) rather than fillPath (f)
        float w() { return x1 - x0; }
        float h() { return y1 - y0; }
        float area() { return w() * h(); }
    }

    /**
     * Mirrors TableExtractor.RulingCollector's coordinate handling EXACTLY (shiftX/flipY against the
     * cropBox + TableExtractor.applyPageRotation), but records EVERY filled axis-aligned rectangle
     * (including the non-thin ones the production collector discards) with its non-stroking colour.
     */
    static final class FillCollector extends PDFGraphicsStreamEngine {
        final List<Fill> fills = new ArrayList<>();
        private final PDRectangle cropBox;
        private final int rotation;
        private final List<List<float[]>> subpaths = new ArrayList<>();
        private List<float[]> current = new ArrayList<>();
        private int points = 0;

        FillCollector(PDPage page) {
            super(page);
            this.cropBox = page.getCropBox();
            this.rotation = page.getRotation();
        }

        private float shiftX(float x) { return x - cropBox.getLowerLeftX(); }
        private float flipY(float y) { return cropBox.getUpperRightY() - y; }

        private void add(float[] p) { if (points < 200_000) { current.add(p); points++; } }
        private void finish() { if (!current.isEmpty()) { subpaths.add(current); current = new ArrayList<>(); } }
        private void reset() { subpaths.clear(); current = new ArrayList<>(); points = 0; }

        @Override public void moveTo(float x, float y) { finish(); add(new float[]{x, y}); }
        @Override public void lineTo(float x, float y) { add(new float[]{x, y}); }
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            add(new float[]{x3, y3});
        }
        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            finish();
            add(new float[]{(float) p0.getX(), (float) p0.getY()});
            add(new float[]{(float) p1.getX(), (float) p1.getY()});
            add(new float[]{(float) p2.getX(), (float) p2.getY()});
            add(new float[]{(float) p3.getX(), (float) p3.getY()});
            add(new float[]{(float) p0.getX(), (float) p0.getY()});
        }
        @Override public void closePath() {
            if (!current.isEmpty()) { float[] f = current.get(0); add(new float[]{f[0], f[1]}); }
        }
        @Override public void strokePath() { reset(); }
        @Override public void fillPath(int windingRule) { emit(false); }
        @Override public void fillAndStrokePath(int windingRule) { emit(true); }
        @Override public void endPath() { reset(); }
        int shadingFills = 0, images = 0;
        @Override public void drawImage(PDImage pdImage) { images++; }
        @Override public void clip(int windingRule) {}
        @Override public void shadingFill(COSName shadingName) { shadingFills++; }
        @Override public Point2D.Float getCurrentPoint() {
            if (!current.isEmpty()) { float[] l = current.get(current.size() - 1); return new Point2D.Float(l[0], l[1]); }
            if (!subpaths.isEmpty()) {
                List<float[]> sp = subpaths.get(subpaths.size() - 1);
                float[] l = sp.get(sp.size() - 1);
                return new Point2D.Float(l[0], l[1]);
            }
            return new Point2D.Float(0, 0);
        }

        private void emit(boolean stroked) {
            finish();
            int rgb = 0xFFFFFF;
            try { rgb = getGraphicsState().getNonStrokingColor().toRGB(); } catch (Exception ignored) {}
            float uw = cropBox.getWidth(), uh = cropBox.getHeight();
            for (List<float[]> sp : subpaths) {
                if (sp.size() < 3) continue;
                float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
                for (float[] p : sp) {
                    minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]);
                    minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
                }
                float[] a = TableExtractor.applyPageRotation(shiftX(minX), flipY(minY), rotation, uw, uh);
                float[] b = TableExtractor.applyPageRotation(shiftX(maxX), flipY(maxY), rotation, uw, uh);
                Fill f = new Fill();
                f.x0 = Math.min(a[0], b[0]); f.x1 = Math.max(a[0], b[0]);
                f.y0 = Math.min(a[1], b[1]); f.y1 = Math.max(a[1], b[1]);
                f.rgb = rgb; f.stroked = stroked;
                if (f.w() > 0.05f && f.h() > 0.05f) fills.add(f);
            }
            reset();
        }
    }

    static boolean isWhitish(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return r >= 246 && g >= 246 && b >= 246;
    }

    // ------------------------------------------------------------------------------- the dump

    private record PageGeom(int pageNum, float pw, float ph, List<StreamTableExtractor.Line> lines,
                            List<List<StreamTableExtractor.Line>> blocks, float medianPitch,
                            List<Fill> fills) {}

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("diagShade"), "set -DdiagShade=true to run");

        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        List<BakeOffHarness.ScoreUnit> units = corpus.units;
        GutterFinder breuel = new BreuelGutterFinder();
        System.out.println("scoring set: " + units.size() + " PDFs");

        // aggregate counters
        int pdfsWithAnyVisibleFill = 0, pdfsWithBand = 0, pdfsWithZebra = 0, pdfsWithRegionFill = 0;
        int pdfsWithThinOnly = 0;
        List<String> bandPdfs = new ArrayList<>();
        List<String> zebraPdfs = new ArrayList<>();
        List<String> regionPdfs = new ArrayList<>();
        List<String> shPdfs = new ArrayList<>();
        List<String> imgPdfs = new ArrayList<>();

        System.out.println();
        System.out.printf(Locale.ROOT, "%-46s %6s %5s %5s %5s %5s %5s %5s %6s%n",
                "pdf", "adjF1", "fills", "vis", "thin", "band", "zebra", "regn", "hits/gt");

        Map<String, double[]> perPdf = new LinkedHashMap<>();
        for (BakeOffHarness.ScoreUnit unit : units) {
            BakeOffHarness.PdfScore score = BakeOffHarness.scoreUnit(breuel, unit);
            int nHits = BakeOffHarness.runFinderOnPdf(breuel, unit.pdf()).hits().size();

            int totalFills = 0, visFills = 0, thinFills = 0, bandFills = 0, zebraRuns = 0, regionFills = 0;
            int shFills = 0, imgs = 0;
            List<PageGeom> pages = new ArrayList<>();
            try (PDDocument doc = Loader.loadPDF(unit.pdf().toFile())) {
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    PDPage page = doc.getPage(i);
                    FillCollector fc = new FillCollector(page);
                    try { fc.processPage(page); } catch (Throwable ignored) {}
                    PDRectangle cb = page.getCropBox();
                    float pw = cb.getWidth(), ph = cb.getHeight();
                    if (page.getRotation() == 90 || page.getRotation() == 270) { float t = pw; pw = ph; ph = t; }

                    List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, i);
                    List<StreamTableExtractor.Line> lines = List.of();
                    List<List<StreamTableExtractor.Line>> blocks = List.of();
                    float pitch = 12f;
                    try {
                        List<StreamTableExtractor.Word> words = StreamTableExtractor.buildWords(glyphs);
                        if (words.size() >= 6) {
                            lines = StreamTableExtractor.buildLines(words, StreamTableExtractor.medianFontSize(words));
                            blocks = StreamTableExtractor.splitIntoBlocks(lines);
                            pitch = medianPitch(lines);
                        }
                    } catch (Throwable ignored) {}

                    pages.add(new PageGeom(i + 1, pw, ph, lines, blocks, pitch, fc.fills));
                    shFills += fc.shadingFills; imgs += fc.images;

                    float pageArea = pw * ph;
                    List<Fill> bands = new ArrayList<>();
                    for (Fill f : fc.fills) {
                        totalFills++;
                        boolean pageBg = f.area() >= 0.65f * pageArea;
                        boolean white = isWhitish(f.rgb);
                        if (white || pageBg) continue;
                        visFills++;
                        if (Math.min(f.w(), f.h()) <= TableExtractor.THIN_FILL_MAX) { thinFills++; continue; }
                        // band-shaped: wide relative to the page's text extent, ~1-3 line pitches tall
                        boolean wide = f.w() >= 0.30f * pw;
                        boolean rowTall = f.h() <= 3.0f * pitch;
                        if (wide && rowTall) { bandFills++; bands.add(f); }
                        else if (wide && f.h() > 3.0f * pitch) regionFills++;
                    }
                    zebraRuns += countZebraRuns(bands, pitch);
                }
            } catch (Throwable t) {
                System.out.println("  !! " + unit.id() + " load failed: " + t);
            }

            if (shFills > 0) shPdfs.add(shortId(unit.id()) + ":" + shFills);
            if (imgs > 0) imgPdfs.add(shortId(unit.id()) + ":" + imgs);
            if (visFills > 0) pdfsWithAnyVisibleFill++;
            if (visFills > 0 && bandFills == 0 && regionFills == 0) pdfsWithThinOnly++;
            if (bandFills > 0) { pdfsWithBand++; bandPdfs.add(unit.id()); }
            if (zebraRuns > 0) { pdfsWithZebra++; zebraPdfs.add(unit.id()); }
            if (regionFills > 0) { pdfsWithRegionFill++; regionPdfs.add(unit.id()); }

            perPdf.put(unit.id(), new double[]{score.adjF1(), totalFills, visFills, thinFills,
                    bandFills, zebraRuns, regionFills, nHits, unit.expected().size()});
            System.out.printf(Locale.ROOT, "%-46s %6.3f %5d %5d %5d %5d %5d %5d %3d/%-3d%n",
                    shortId(unit.id()), score.adjF1(), totalFills, visFills, thinFills,
                    bandFills, zebraRuns, regionFills, nHits, unit.expected().size());
        }

        System.out.println();
        System.out.println("==== PREVALENCE (of " + units.size() + " scoring PDFs) ====");
        System.out.println("PDFs with >=1 visible non-page-bg fill : " + pdfsWithAnyVisibleFill);
        System.out.println("  ...only THIN fills (already rulings)  : " + pdfsWithThinOnly);
        System.out.println("PDFs with >=1 band-shaped fill          : " + pdfsWithBand + "  " + bandPdfs);
        System.out.println("PDFs with a zebra run (>=3 bands)       : " + pdfsWithZebra + "  " + zebraPdfs);
        System.out.println("PDFs with >=1 tall wide region fill     : " + pdfsWithRegionFill + "  " + regionPdfs);

        System.out.println("PDFs using sh (gradient shading) ops   : " + shPdfs.size() + "  " + shPdfs);
        System.out.println("PDFs drawing images                     : " + imgPdfs.size() + "  " + imgPdfs);

        double bandAdj = 0, bandN = 0, noBandAdj = 0, noBandN = 0;
        for (Map.Entry<String, double[]> e : perPdf.entrySet()) {
            double[] v = e.getValue();
            boolean has = v[4] > 0 || v[6] > 0;
            if (has) { bandAdj += v[0]; bandN++; } else { noBandAdj += v[0]; noBandN++; }
        }
        System.out.printf(Locale.ROOT, "mean adjF1 WITH band/region fill: %.3f (n=%.0f)%n",
                bandN == 0 ? Double.NaN : bandAdj / bandN, bandN);
        System.out.printf(Locale.ROOT, "mean adjF1 WITHOUT             : %.3f (n=%.0f)%n",
                noBandN == 0 ? Double.NaN : noBandAdj / noBandN, noBandN);
    }

    private static int countZebraRuns(List<Fill> bands, float pitch) {
        if (bands.size() < 3) return 0;
        List<Fill> s = new ArrayList<>(bands);
        s.sort(Comparator.comparingDouble(f -> f.y0));
        int runs = 0, run = 1;
        for (int i = 1; i < s.size(); i++) {
            Fill a = s.get(i - 1), b = s.get(i);
            boolean sameHeight = Math.abs(a.h() - b.h()) <= 0.35f * Math.max(a.h(), b.h());
            boolean sameX = Math.abs(a.x0 - b.x0) <= 3f && Math.abs(a.x1 - b.x1) <= 3f;
            boolean nearby = (b.y0 - a.y1) <= 4.0f * pitch;
            if (sameHeight && sameX && nearby) run++;
            else { if (run >= 3) runs++; run = 1; }
        }
        if (run >= 3) runs++;
        return runs;
    }

    static float medianPitch(List<StreamTableExtractor.Line> lines) {
        if (lines.size() < 2) return 12f;
        float[] g = new float[lines.size() - 1];
        for (int i = 1; i < lines.size(); i++) g[i - 1] = lines.get(i).yTop - lines.get(i - 1).yTop;
        java.util.Arrays.sort(g);
        float m = g[g.length / 2];
        return m <= 0.5f ? 12f : m;
    }

    static String shortId(String id) {
        int i = id.lastIndexOf('/');
        return i < 0 ? id : id.substring(i + 1);
    }
}
