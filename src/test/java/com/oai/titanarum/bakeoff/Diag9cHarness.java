// NOTE ON PACKAGE: this file physically lives under src/test/java/com/oai/titanarum/bakeoff/ (to
// keep it alongside the Task 9b bake-off harness per the task-9c diagnostic-brief instructions),
// but declares `package com.oai.titanarum;` -- exactly the same trick BakeOffHarness itself uses
// (see that file's own NOTE ON PACKAGE), for exactly the same reason: StreamTableExtractor, its
// Word/Line/Gutter/Grid types, and TableExtractor.TableHit are all package-private, and this is
// the only way to observe the real pipeline's internals without widening any production class's
// visibility. Nothing in src/main was touched to build this harness -- see the report this harness
// produced (.superpowers/sdd/task-9c-diagnosis-report.md) for the "what was added" accounting.
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 9c diagnostic harness -- NOT part of the bake-off scoring, and does NOT change any
 * extraction behavior. Dumps the intermediate state of {@link StreamTableExtractor}'s pipeline
 * (words/lines, band, gutters, gridness sub-scores, and the actual production hit if one is
 * produced) for 5 representative ICDAR-2013 PDFs with {@code -str.xml} ground truth, side by
 * side with the ground-truth table's own geometry, so the dominant root cause of the bake-off's
 * near-zero F1 / 0.000 dims-exact-match can be diagnosed from real data instead of guessed at.
 *
 * <p>All gridness sub-score math below (violation/violationScore, colConsistency, proseScore,
 * numericBonus, the prose veto) is a faithful line-for-line COPY of the private computation
 * inside {@link StreamTableExtractor#scoreGrid}, re-derived here from the same package-visible
 * inputs ({@code Grid.colBounds}, {@code Grid.gutters}, {@code Grid.rows}) purely for
 * observability -- {@code scoreGrid} itself, and every other production class, is untouched.
 *
 * <p>Run explicitly (not discovered by a bare {@code mvn test} since "Diag9cHarness" matches
 * none of Surefire's default test-inclusion globs, and the one {@code @Test} method is also
 * gated behind a system property as a second layer):
 * {@code mvn -q test -Dtest=Diag9cHarness -Ddiag9c=true}
 */
class Diag9cHarness {

    private static final Path TABULA_RESOURCES =
            Path.of("corpus/tabula-java/src/test/resources").toAbsolutePath().normalize();
    private static final Path ICDAR_ROOT =
            TABULA_RESOURCES.resolve("technology/tabula/icdar2013-dataset");

    /** One representative target: (stem under ICDAR_ROOT, 1-based PDF page holding the GT table). */
    private record Target(String stem, int page) {}

    private static final List<Target> TARGETS = List.of(
            new Target("competition-dataset-eu/eu-001", 1),   // all-zero F1 across every finder
            new Target("competition-dataset-us/us-007", 2),   // breuel DID produce a hit (F1=0.148)
            new Target("competition-dataset-us/us-020", 2),   // breuel+gapvote both produced hits
            new Target("competition-dataset-eu/eu-027", 3),   // only gapvote scored nonzero, breuel=0
            new Target("competition-dataset-us/us-018", 1)    // breuel scored ~0 (0.000, effectively nil)
    );

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("diag9c"), "set -Ddiag9c=true to run");
        for (Target t : TARGETS) {
            diagnoseOne(t);
        }
    }

    // ------------------------------------------------------------------------------ per-PDF dump

    private void diagnoseOne(Target target) throws Exception {
        Path pdf = ICDAR_ROOT.resolve(target.stem() + ".pdf");
        Path xml = ICDAR_ROOT.resolve(target.stem() + "-str.xml");

        System.out.println();
        System.out.println("================================================================================");
        System.out.println("PDF: " + target.stem() + "     ground-truth page: " + target.page());
        System.out.println("================================================================================");

        String xmlText = Files.readString(xml, StandardCharsets.UTF_8);
        List<GtTable> allTables = parseTablesWithGeometry(xmlText);
        List<GtTable> onPage = new ArrayList<>();
        for (GtTable g : allTables) if (g.page == target.page()) onPage.add(g);
        if (onPage.isEmpty()) {
            System.out.println("!! No ground-truth table geometry parsed for page " + target.page()
                    + " (found tables on pages: " + allTables.stream().map(g -> g.page).distinct().toList() + ")");
            return;
        }

        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int pageIdx = target.page() - 1;
            PDPage page = doc.getPage(pageIdx);
            float pageH = page.getMediaBox().getHeight();
            float pageW = page.getMediaBox().getWidth();

            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, pageIdx);

            // ---------------------------------------------------------------------- 1. words/lines
            List<StreamTableExtractor.Word> words = StreamTableExtractor.buildWords(glyphs);
            float mfs = StreamTableExtractor.medianFontSize(words);
            List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(words, mfs);
            System.out.println("[1] words=" + words.size() + "  lines=" + lines.size()
                    + "  medianFontSize=" + mfs);
            System.out.println("    First 5 of our Line clusters (concatenated text):");
            for (int i = 0; i < Math.min(5, lines.size()); i++) {
                StreamTableExtractor.Line l = lines.get(i);
                System.out.println("      L" + i + " y=[" + fmt(l.yTop) + "," + fmt(l.yBot) + "]  \""
                        + concatLine(l) + "\"");
            }
            System.out.println("    pdftotext -layout, same page (for spot-check):");
            printPdftotext(pdf, target.page());

            // ------------------------------------------------------------------------------ 2. band
            float bandX0 = Float.MAX_VALUE, bandX1 = -Float.MAX_VALUE;
            for (StreamTableExtractor.Word w : words) { bandX0 = Math.min(bandX0, w.x0); bandX1 = Math.max(bandX1, w.x1); }
            System.out.println("[2] extractPage's band: bandX0=" + fmt(bandX0) + " bandX1=" + fmt(bandX1)
                    + "  (band width=" + fmt(bandX1 - bandX0) + " / page width=" + fmt(pageW)
                    + " = " + pct(bandX1 - bandX0, pageW) + "%)");
            for (GtTable g : onPage) {
                System.out.println("    ground-truth table x-extent: x0=" + fmt(g.x0) + " x1=" + fmt(g.x1)
                        + "  (table width=" + fmt(g.x1 - g.x0) + " / page width = " + pct(g.x1 - g.x0, pageW) + "%)");
                System.out.println("    => band is " + pct(bandX1 - bandX0, g.x1 - g.x0)
                        + "% the width of the actual GT table (100% would mean the band == the table's x-extent)");
            }

            // ------------------------------------------------------- 3. lines fed to scorer vs GT
            for (GtTable g : onPage) {
                float topY = pageH - g.y1;   // ICDAR bbox is bottom-left-origin; ours is top-left-origin
                float botY = pageH - g.y0;
                int inTable = 0;
                for (StreamTableExtractor.Line l : lines) {
                    float mid = (l.yTop + l.yBot) / 2f;
                    if (mid >= topY - 2 && mid <= botY + 2) inTable++;
                }
                System.out.println("[3] ALL " + lines.size() + " page lines are fed to findGutters/scoreGrid "
                        + "(no region segmentation). Ground truth table has " + g.table.rowCount()
                        + " rows; our lines whose y-midpoint falls inside the GT table's y-range: " + inTable
                        + " (" + pct(inTable, lines.size()) + "% of all lines fed to the scorer)");
            }

            // ---------------------------------------------------------------------------- 4. gutters
            float medianSpace = 0.5f * mfs;
            List<StreamTableExtractor.Gutter> gutters;
            try {
                gutters = StreamTableExtractor.findGutters(lines, bandX0, bandX1, medianSpace);
            } catch (TableExtractor.RulingOverflowException e) {
                System.out.println("[4] findGutters THREW RulingOverflowException (DoS budget breach on the "
                        + "whole-page band) -- extractPage's catch(RulingOverflowException) swallows this and "
                        + "returns List.of() for the WHOLE PAGE, i.e. this is itself sufficient to explain a "
                        + "zero-hit page regardless of any gridness score.");
                System.out.println("[5]/[6] skipped (no gutters/grid to score; production hit count = 0)");
                return;
            }
            System.out.println("[4] findGutters (Breuel, production default) returned " + gutters.size() + " gutter(s):");
            for (StreamTableExtractor.Gutter g : gutters) {
                System.out.println("      x=[" + fmt(g.x0) + "," + fmt(g.x1) + "] cx=" + fmt(g.cx())
                        + " rowsCovered=" + g.rowsCovered + "/" + lines.size());
            }
            for (GtTable g : onPage) {
                List<Float> gtBounds = approxColBoundaries(g);
                System.out.println("    ground-truth approx column boundaries (n=" + (gtBounds.size()) + "): "
                        + fmtList(gtBounds) + "   [GT table has " + g.table.colCount() + " columns]");
            }

            // --------------------------------------------------------------------------- 5. gridness
            StreamTableExtractor.Grid grid = StreamTableExtractor.scoreGrid(lines, gutters, bandX0, bandX1);
            System.out.println("[5] scoreGrid: cols=" + (grid.colBounds.length - 1) + " rows=" + grid.rows.size()
                    + "  confidence=" + grid.confidence + "  (gate=" + StreamTableExtractor.STREAM_CONFIDENCE_MIN
                    + ", numericLeanColumn=" + grid.numericLeanColumn + ")");
            dumpSubscores(lines, gutters, grid.colBounds);

            // ------------------------------------------------------------------- 6. production hit
            List<TableExtractor.TableHit> hits;
            try {
                hits = StreamTableExtractor.extractPage(target.page(), glyphs);
            } catch (TableExtractor.RulingOverflowException e) {
                System.out.println("[6] extractPage threw RulingOverflowException directly (unexpected -- it should "
                        + "swallow this itself); treating as 0 hits.");
                hits = List.of();
            }
            System.out.println("[6] StreamTableExtractor.extractPage (production, gate applied): "
                    + hits.size() + " hit(s) on this page");
            for (TableExtractor.TableHit h : hits) {
                System.out.println("    HIT rowCount=" + h.rowCount + " colCount=" + h.colCount
                        + " confidence=" + h.confidence);
            }
            for (GtTable g : onPage) {
                System.out.println("    GROUND TRUTH rowCount=" + g.table.rowCount() + " colCount=" + g.table.colCount());
            }
            if (!hits.isEmpty()) {
                TableExtractor.TableHit h = hits.get(0);
                GtTable g = onPage.get(0);
                System.out.println("    --- first 3 rows: OURS vs GROUND TRUTH ---");
                for (int r = 0; r < 3; r++) {
                    String ours = r < h.rows.size() ? h.rows.get(r).toString() : "(no row)";
                    String gt = r < g.table.rowCount() ? g.table.rows().get(r).toString() : "(no row)";
                    System.out.println("      row " + r + " OURS: " + ours);
                    System.out.println("      row " + r + " GT  : " + gt);
                }
            } else {
                System.out.println("    (no hit produced -- nothing to compare row-by-row; see [5] for why confidence fell short)");
            }
        }
    }

    // ------------------------------------------------------------------- gridness sub-score replay

    /**
     * Re-derives every sub-score {@link StreamTableExtractor#scoreGrid} computes internally, using
     * ONLY the same package-visible data scoreGrid itself was given ({@code lines}, {@code gutters},
     * {@code colBounds}) -- a line-for-line copy of that method's math, for observability only. If
     * this harness's numbers ever disagree with the real {@code grid.confidence} logged in step
     * [5], that disagreement itself would be a bug in THIS harness (not in production), since
     * {@code scoreGrid} is never modified.
     */
    private static void dumpSubscores(List<StreamTableExtractor.Line> lines,
                                       List<StreamTableExtractor.Gutter> gutters, float[] bounds) {
        int cols = gutters.size() + 1;
        int rows = lines.size();
        if (cols < 2 || rows < 3) {
            System.out.println("    SUBSCORES: cols=" + cols + " rows=" + rows
                    + " -- hard-gated to confidence=0 before any sub-score is computed (cols<2 or rows<3).");
            return;
        }

        long wordsN = 0, viol = 0;
        for (StreamTableExtractor.Line l : lines) for (StreamTableExtractor.Word w : l.words) {
            wordsN++;
            for (StreamTableExtractor.Gutter g : gutters) if (w.x0 < g.cx() && w.x1 > g.cx()) { viol++; break; }
        }
        double violation = wordsN == 0 ? 1 : (double) viol / wordsN;
        double violationScore;
        if (violation <= StreamTableExtractor.VIOLATION_TOLERANCE) {
            violationScore = 1;
        } else {
            violationScore = 1 - Math.min(1,
                    (violation - StreamTableExtractor.VIOLATION_TOLERANCE)
                            / (StreamTableExtractor.VIOLATION_CEILING - StreamTableExtractor.VIOLATION_TOLERANCE));
        }

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
        double colConsistency = (double) consistentRows / rows;
        double colConsistencyScored = Math.min(1, colConsistency / 0.85);

        List<Double> fills = new ArrayList<>();
        for (StreamTableExtractor.Line l : lines) {
            double maxFill = 0;
            for (StreamTableExtractor.Word w : l.words) {
                int c = colOf(w.cx(), bounds);
                float colW = bounds[c + 1] - bounds[c];
                if (colW > 0) maxFill = Math.max(maxFill, w.width() / colW);
            }
            fills.add(maxFill);
        }
        java.util.Collections.sort(fills);
        double medianFill = fills.isEmpty() ? 0 : fills.get(fills.size() / 2);
        double proseScore = clamp01((0.85 - medianFill) / 0.25);

        int numericCols = 0;
        for (int c = 0; c < cols; c++) {
            int tot = 0, num = 0;
            for (StreamTableExtractor.Line l : lines) for (StreamTableExtractor.Word w : l.words)
                if (colOf(w.cx(), bounds) == c) { tot++; if (w.numeric) num++; }
            if (tot > 0 && (double) num / tot >= 0.70) numericCols++;
        }
        double numericBonus = cols > 0 ? (double) numericCols / cols : 0;

        System.out.println("    SUBSCORES: words=" + wordsN + " straddling-gutter=" + viol
                + " violation=" + fmt4(violation) + " -> violationScore=" + fmt4(violationScore) + " (weight 0.25)");
        System.out.println("               consistentRows=" + consistentRows + "/" + rows
                + " -> colConsistency=" + fmt4(colConsistency) + " -> scored=" + fmt4(colConsistencyScored) + " (weight 0.30)");
        System.out.println("               medianFill=" + fmt4(medianFill) + " -> proseScore=" + fmt4(proseScore) + " (weight 0.20)");
        System.out.println("               cols=" + cols + " -> colsBonus=" + fmt4(Math.min(1, (cols - 2) / 2.0)) + " (weight 0.15)");
        System.out.println("               numericCols=" + numericCols + "/" + cols + " -> numericBonus=" + fmt4(numericBonus) + " (weight 0.10)");

        if (cols == 2 && numericBonus == 0) {
            System.out.println("               VETO: cols==2 and numericBonus==0 -> hard confidence=0 "
                    + "(two-column non-numeric gate) BEFORE the prose-fill veto is even evaluated.");
            return;
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
        boolean vetoFires = proseColumnFraction > StreamTableExtractor.VETO_COLUMN_MAJORITY_FRACTION && numericCols == 0;
        System.out.println("               prose-veto: proseColumns=" + proseColumns + "/" + cols
                + " -> fraction=" + fmt4(proseColumnFraction) + " numericCols=" + numericCols
                + " -> PROSE VETO " + (vetoFires ? "FIRED (confidence forced to 0)" : "did not fire"));
        if (vetoFires) return;

        double confidence = 0.30 * colConsistencyScored + 0.25 * violationScore + 0.20 * proseScore
                + 0.15 * Math.min(1, (cols - 2) / 2.0) + 0.10 * numericBonus;
        System.out.println("               recomputed confidence = " + fmt4(confidence)
                + "  (should match [5]'s grid.confidence)");
    }

    private static int colOf(float x, float[] bounds) {
        for (int c = 0; c < bounds.length - 1; c++) if (x < bounds[c + 1]) return c;
        return bounds.length - 2;
    }

    private static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }

    // -------------------------------------------------------------------------------------- utils

    private static String concatLine(StreamTableExtractor.Line l) {
        StringBuilder sb = new StringBuilder();
        for (StreamTableExtractor.Word w : l.words) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(w.text);
        }
        return sb.toString();
    }

    private static void printPdftotext(Path pdf, int page) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "pdftotext", "-layout", "-f", String.valueOf(page), "-l", String.valueOf(page),
                    pdf.toString(), "-");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            String[] linesArr = out.split("\n");
            int shown = 0;
            for (String s : linesArr) {
                if (s.isBlank()) continue;
                System.out.println("      | " + s);
                if (++shown >= 25) { System.out.println("      | ... (truncated)"); break; }
            }
        } catch (IOException | InterruptedException e) {
            System.out.println("      (pdftotext unavailable: " + e + ")");
        }
    }

    private static String fmt(float v) { return String.format(Locale.ROOT, "%.1f", v); }
    private static String fmt4(double v) { return String.format(Locale.ROOT, "%.4f", v); }
    private static String pct(float num, float den) { return den == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", 100.0 * num / den); }
    private static String pct(int num, int den) { return den == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f", 100.0 * num / den); }
    private static String fmtList(List<Float> vs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vs.size(); i++) { if (i > 0) sb.append(", "); sb.append(fmt(vs.get(i))); }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------- ground-truth geometry parsing

    /** One ground-truth table with both its text grid (via {@link GroundTruth}) and its geometry
     *  (page + bbox in native ICDAR bottom-left-origin coordinates), parsed independently of
     *  GroundTruth's own text-only model since that model discards position after building rows. */
    private static final class GtTable {
        int page;
        float x0, x1, y0, y1;  // bottom-left-origin (native PDF / ICDAR space)
        GroundTruth.Table table;
        List<float[]> cellGeo = new ArrayList<>(); // {startCol, endCol, x1, x2} per cell, this table only
    }

    private static final Pattern TABLE_START = Pattern.compile("<table\\b");
    private static final Pattern REGION_START = Pattern.compile("<region\\b([^>]*)>", Pattern.DOTALL);
    private static final Pattern CELL_START = Pattern.compile("<cell\\b([^>]*)>", Pattern.DOTALL);
    private static final Pattern BBOX = Pattern.compile("<bounding-box\\b([^>]*)/>", Pattern.DOTALL);
    private static final Pattern ATTR = Pattern.compile("(\\S+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')");

    private static List<GtTable> parseTablesWithGeometry(String xmlText) throws IOException {
        List<GtTable> out = new ArrayList<>();
        List<Integer> starts = new ArrayList<>();
        Matcher tm = TABLE_START.matcher(xmlText);
        while (tm.find()) starts.add(tm.start());
        if (starts.isEmpty()) return out;

        // GroundTruth.fromIcdarStructureXml parses the WHOLE file and returns one Table per
        // <table> element in document order (skipping any with rowCount==0) -- reuse it as-is
        // (rather than re-implementing cell-content/entity decoding here) and align by index.
        // fromIcdarStructureXml takes a Path, so write the (already-in-memory) xml to a temp file.
        Path tmp = Files.createTempFile("diag9c", ".xml");
        Files.writeString(tmp, xmlText, StandardCharsets.UTF_8);
        List<GroundTruth.Table> textTables = GroundTruth.fromIcdarStructureXml(tmp);
        Files.deleteIfExists(tmp);

        int textIdx = 0;
        for (int t = 0; t < starts.size(); t++) {
            int start = starts.get(t);
            int end = (t + 1 < starts.size()) ? starts.get(t + 1) : xmlText.length();
            String chunk = xmlText.substring(start, end);

            GtTable g = new GtTable();
            g.x0 = Float.MAX_VALUE; g.x1 = -Float.MAX_VALUE; g.y0 = Float.MAX_VALUE; g.y1 = -Float.MAX_VALUE;
            g.page = -1;

            Matcher rm = REGION_START.matcher(chunk);
            if (rm.find()) {
                var attrs = parseAttrs(rm.group(1));
                String pageStr = attrs.get("page");
                if (pageStr != null) g.page = Integer.parseInt(pageStr.trim());
            }

            Matcher cm = CELL_START.matcher(chunk);
            List<Integer> cellEnds = new ArrayList<>();
            List<String> cellAttrs = new ArrayList<>();
            while (cm.find()) { cellEnds.add(cm.end()); cellAttrs.add(cm.group(1)); }
            boolean anyBbox = false;
            for (int i = 0; i < cellEnds.size(); i++) {
                int segStart = cellEnds.get(i);
                int segEnd = (i + 1 < cellEnds.size()) ? cellEnds.get(i + 1) : chunk.length();
                String seg = chunk.substring(segStart, segEnd);
                Matcher bm = BBOX.matcher(seg);
                if (!bm.find()) continue;
                var battrs = parseAttrs(bm.group(1));
                Float x1 = parseFloatOrNull(battrs.get("x1"));
                Float x2 = parseFloatOrNull(battrs.get("x2"));
                Float y1 = parseFloatOrNull(battrs.get("y1"));
                Float y2 = parseFloatOrNull(battrs.get("y2"));
                if (x1 == null || x2 == null || y1 == null || y2 == null) {
                    continue; // malformed bounding-box in the fixture (e.g. "26ß" for x1 in
                              // us-018-str.xml) -- skip this cell's geometry rather than crash
                              // a purely observational harness on a ground-truth fixture typo.
                }
                g.x0 = Math.min(g.x0, x1); g.x1 = Math.max(g.x1, x2);
                g.y0 = Math.min(g.y0, y1); g.y1 = Math.max(g.y1, y2);
                anyBbox = true;

                var cattrs = parseAttrs(cellAttrs.get(i));
                int startCol = parseIntAttr(cattrs, "start-col", -1);
                int endCol = parseIntAttr(cattrs, "end-col", startCol);
                if (startCol >= 0) g.cellGeo.add(new float[]{startCol, endCol, x1, x2});
            }
            if (!anyBbox) continue; // no geometry for this <table> chunk; skip (keeps textIdx aligned below only if this table also had rowCount 0 -- checked next)

            // Align with textTables: GroundTruth skips a chunk only if its parsed rowCount==0.
            // Every chunk we kept here had >=1 cell with a bbox, so it necessarily has rowCount>=1
            // too (GroundTruth counts the same <cell> tags), so a 1:1 index alignment holds.
            if (textIdx < textTables.size()) {
                g.table = textTables.get(textIdx);
                textIdx++;
            } else {
                continue;
            }
            out.add(g);
        }
        return out;
    }

    /** Approximates ground-truth column boundaries as midpoints between adjacent non-spanning
     *  cells' observed x-extents, grouped by start-col (only cells with start-col==end-col are
     *  used, to avoid a header's multi-column span skewing a single column's extent). Coarse by
     *  design -- good enough to compare against our own gutters' cx() by eye. */
    private static List<Float> approxColBoundaries(GtTable g) {
        java.util.Map<Integer, float[]> perCol = new java.util.TreeMap<>(); // col -> {minX1, maxX2}
        for (float[] c : g.cellGeo) {
            int startCol = (int) c[0], endCol = (int) c[1];
            if (startCol != endCol) continue;
            float[] cur = perCol.get(startCol);
            if (cur == null) perCol.put(startCol, new float[]{c[2], c[3]});
            else { cur[0] = Math.min(cur[0], c[2]); cur[1] = Math.max(cur[1], c[3]); }
        }
        List<Integer> cols = new ArrayList<>(perCol.keySet());
        java.util.Collections.sort(cols);
        List<Float> boundaries = new ArrayList<>();
        for (int i = 0; i + 1 < cols.size(); i++) {
            float[] a = perCol.get(cols.get(i));
            float[] b = perCol.get(cols.get(i + 1));
            boundaries.add((a[1] + b[0]) / 2f);
        }
        return boundaries;
    }

    private static java.util.Map<String, String> parseAttrs(String attrText) {
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        if (attrText == null) return map;
        Matcher am = ATTR.matcher(attrText);
        while (am.find()) {
            String name = am.group(1);
            String value = am.group(2) != null ? am.group(2) : am.group(3);
            map.put(name, value);
        }
        return map;
    }

    private static int parseIntAttr(java.util.Map<String, String> attrs, String name, int fallback) {
        String v = attrs.get(name);
        if (v == null) return fallback;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    private static Float parseFloatOrNull(String v) {
        if (v == null) return null;
        try { return Float.parseFloat(v.trim()); } catch (NumberFormatException e) { return null; }
    }
}
