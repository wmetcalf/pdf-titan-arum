// MEASUREMENT ONLY (gated -DcontentFp=true). Step 2 of the content-level false-positive study.
//
// The shape-level study (see the MEASURED AND NOT SHIPPED block in StreamTableExtractor) exhausted
// grid-shape gates: every one either cost corpus quality or deleted table shapes the suite asserts
// are real. Its conclusion was that the residual fabrications are separable only on CONTENT. This
// probe measures candidate CONTENT features on BOTH sides at once:
//
//   -DcontentSide=fp      every PASSING stream candidate on a real-world document the flag ADDS
//   -DcontentSide=corpus  every PASSING stream candidate on the 77-PDF ICDAR corpus (the tables
//                         that must NOT be harmed)
//
// so a discriminator's separation is visible before any of it is wired into production.
//
// Physically under bakeoff/, declares `package com.oai.titanarum;` for package-private access,
// the same convention BaselineHarness and FpCharProbe use.
package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class ContentFpProbe {

    private final StringBuilder out = new StringBuilder();

    private void line(String fmt, Object... a) {
        out.append(String.format(Locale.ROOT, fmt, a)).append('\n');
    }

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("contentFp"), "set -DcontentFp=true");
        String side = System.getProperty("contentSide", "fp");
        line("CONTENT FEATURES -- side=%s", side);
        line("%-46s %5s %5s %6s  %6s %6s %6s %6s %6s %6s %6s %6s",
                "doc/page", "r", "c", "conf",
                "mnTok", "mxTok", "f>=5t", "fR>=6", "mTkLn", "enumR", "enum0", "nNoMk");

        List<Row> rows = new ArrayList<>();
        if (side.equals("fp")) {
            for (Path p : FpCharProbe.allProsePdfs()) collectFp(p, rows);
        } else {
            StringBuilder notes = new StringBuilder();
            for (BakeOffHarness.ScoreUnit u : BakeOffHarness.buildScoringSet(notes).units) {
                collectCorpus(u.pdf(), u.id(), rows);
            }
        }
        double dumpAbove = Double.parseDouble(System.getProperty("contentDumpAbove", "2"));
        for (Row r : rows) {
            line("%-46s %5d %5d %6.4f  %6.2f %6d %6.3f %6.3f %6.2f %6.3f %6.3f %6d",
                    r.id, r.rows, r.cols, r.conf,
                    r.meanTokPerCell, r.maxTokInCell, r.fracCellsGE5tok, r.fracRowsWithLongCell,
                    r.meanTokLen, r.enumRowFrac, r.enumCol0Frac, r.numDataColsExMarkers);
            if (r.fracRowsWithLongCell > dumpAbove || r.enumRowFrac > 0.5) {
                for (String t : r.textRows) line("        | %s", t);
            }
        }
        line("");
        line("TOTAL candidates: %d", rows.size());
        Path o = Path.of(System.getProperty("contentOut", "target/content-" + side + ".txt"));
        Files.createDirectories(o.toAbsolutePath().getParent());
        Files.writeString(o, out.toString());
        System.out.println("written to " + o.toAbsolutePath());
    }

    // ------------------------------------------------------------------------------- collection

    private void collectFp(Path pdf, List<Row> sink) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (doc.getNumberOfPages() < 1) return;
            List<Integer> pages = FpCharProbe.shippingPages(doc);
            Map<Integer, List<TextPosition>> byPage = new LinkedHashMap<>();
            for (int p : pages) byPage.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
            if (TableExtractor.extract(doc, pages, byPage, false).tables.size() > 0) return;
            if (TableExtractor.extract(doc, pages, byPage, true).tables.isEmpty()) return;
            String tag = pdf.getFileName().toString();
            tag = tag.substring(0, Math.min(8, tag.length()));
            GutterFinder breuel = new BreuelGutterFinder();
            for (int p : pages) {
                List<StreamTableExtractor.Candidate> sk = new ArrayList<>();
                StreamTableExtractor.extractPage(p, byPage.get(p), breuel,
                        StreamTableExtractor.PageFrame.IDENTITY,
                        StreamTableExtractor.PRODUCTION_BAR,
                        StreamTableExtractor.PRODUCTION_BAR, sk);
                for (StreamTableExtractor.Candidate c : sk) {
                    if (!c.passed || c.hit == null) continue;
                    sink.add(features(tag + " p" + p, c.grid, c.confidence));
                }
            }
        } catch (Throwable ignored) { /* unreadable: not this probe's subject */ }
    }

    private void collectCorpus(Path pdf, String id, List<Row> sink) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            GutterFinder breuel = new BreuelGutterFinder();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                List<TextPosition> g = TableTestPdfs.harvestGlyphs(doc, p - 1);
                if (g.isEmpty() || g.size() > StreamTableExtractor.MAX_STREAM_GLYPHS) continue;
                List<StreamTableExtractor.Candidate> sk = new ArrayList<>();
                StreamTableExtractor.extractPage(p, g, breuel,
                        StreamTableExtractor.PageFrame.IDENTITY,
                        StreamTableExtractor.PRODUCTION_BAR,
                        StreamTableExtractor.PRODUCTION_BAR, sk);
                for (StreamTableExtractor.Candidate c : sk) {
                    if (!c.passed || c.hit == null) continue;
                    String tail = id.length() > 20 ? id.substring(id.length() - 20) : id;
                    sink.add(features(tail + " p" + p, c.grid, c.confidence));
                }
            }
        } catch (Throwable ignored) { /* unreadable: not this probe's subject */ }
    }

    // ------------------------------------------------------------------------------ the features

    private static final class Row {
        String id; int rows, cols; double conf;
        double meanTokPerCell; int maxTokInCell;
        double fracCellsGE5tok, fracRowsWithLongCell, meanTokLen;
        double enumRowFrac, enumCol0Frac; int numDataColsExMarkers;
        List<String> textRows = new ArrayList<>();
    }

    static Row features(String id, StreamTableExtractor.Grid g, double conf) {
        Row r = new Row();
        r.id = id; r.rows = g.nRows; r.cols = g.nCols; r.conf = conf;
        float[] b = g.colBounds;
        int cols = b.length - 1;
        int cells = 0, tokTotal = 0, charTotal = 0, cellsGE5 = 0, rowsWithLong = 0;
        int enumRows = 0, enumCol0 = 0, col0Occupied = 0;
        for (StreamTableExtractor.Line l : g.rows) {
            int[] perCol = new int[cols];
            StreamTableExtractor.Word leftmost = null;
            StreamTableExtractor.Word col0Leftmost = null;
            boolean longCell = false;
            for (StreamTableExtractor.Word w : l.words) {
                int c = StreamTableExtractor.colOf(w.cx(), b);
                perCol[c]++;
                tokTotal++; charTotal += w.text.length();
                if (leftmost == null || w.x0 < leftmost.x0) leftmost = w;
                if (c == 0 && (col0Leftmost == null || w.x0 < col0Leftmost.x0)) col0Leftmost = w;
            }
            for (int c = 0; c < cols; c++) {
                if (perCol[c] == 0) continue;
                cells++;
                if (perCol[c] >= 5) cellsGE5++;
                if (perCol[c] >= 6) longCell = true;
            }
            if (longCell) rowsWithLong++;
            if (leftmost != null && isEnumMarker(leftmost.text)) enumRows++;
            if (col0Leftmost != null) {
                col0Occupied++;
                if (isEnumMarker(col0Leftmost.text)) enumCol0++;
            }
        }
        r.meanTokPerCell = cells == 0 ? 0 : tokTotal / (double) cells;
        r.maxTokInCell = maxTokInAnyCell(g.rows, b, cols);
        r.fracCellsGE5tok = cells == 0 ? 0 : cellsGE5 / (double) cells;
        r.fracRowsWithLongCell = g.nRows == 0 ? 0 : rowsWithLong / (double) g.nRows;
        r.meanTokLen = tokTotal == 0 ? 0 : charTotal / (double) tokTotal;
        r.enumRowFrac = g.nRows == 0 ? 0 : enumRows / (double) g.nRows;
        r.enumCol0Frac = col0Occupied == 0 ? 0 : enumCol0 / (double) col0Occupied;
        r.numDataColsExMarkers = numericDataColsExcludingMarkerColumns(g.rows, b);
        int shown = 0;
        for (StreamTableExtractor.Line l : g.rows) {
            if (shown++ >= 14) { r.textRows.add("... (" + (g.nRows - 14) + " more)"); break; }
            StringBuilder sb = new StringBuilder();
            int prev = -1;
            for (StreamTableExtractor.Word w : l.words) {
                int c = StreamTableExtractor.colOf(w.cx(), b);
                if (c != prev) { sb.append(prev < 0 ? "" : "  ~|~  "); prev = c; }
                else sb.append(' ');
                sb.append(w.text);
            }
            r.textRows.add(sb.toString());
        }
        return r;
    }

    private static int maxTokInAnyCell(List<StreamTableExtractor.Line> lines, float[] b, int cols) {
        int max = 0;
        for (StreamTableExtractor.Line l : lines) {
            int[] perCol = new int[cols];
            for (StreamTableExtractor.Word w : l.words) perCol[StreamTableExtractor.colOf(w.cx(), b)]++;
            for (int p : perCol) max = Math.max(max, p);
        }
        return max;
    }

    /**
     * CANDIDATE DISCRIMINATOR 1. An enumeration marker: a whole token that is only a small ordinal
     * plus its list punctuation -- "1." "2)" "(3)" "a)" "iv." -- which {@link
     * StreamTableExtractor#isNumericToken} currently reports as a NUMERIC token, so a numbered list's
     * marker column is counted as numeric DATA evidence. A numbered list is not a numeric data column.
     */
    static boolean isEnumMarker(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.length() > 6 || t.isEmpty()) return false;
        return t.matches("\\(?\\d{1,3}[.)]")           // 1.  2)  (3)
            || t.matches("\\(?[a-zA-Z][.)]")           // a.  b)  (c)
            || t.matches("\\(?[ivxIVX]{1,4}[.)]");     // iv.  (vii)
    }

    /**
     * CANDIDATE DISCRIMINATOR 1 (applied): {@link StreamTableExtractor#numericDataColumnCount} but a
     * column whose numeric tokens are PREDOMINANTLY enumeration markers does not count.
     */
    static int numericDataColsExcludingMarkerColumns(List<StreamTableExtractor.Line> lines, float[] b) {
        int cols = b.length - 1, n = 0;
        for (int c = 0; c < cols; c++) {
            int occupied = 0, allNum = 0, numTok = 0, markerTok = 0;
            for (StreamTableExtractor.Line l : lines) {
                int inCol = 0, inColNum = 0;
                for (StreamTableExtractor.Word w : l.words) {
                    if (StreamTableExtractor.colOf(w.cx(), b) != c) continue;
                    inCol++;
                    if (w.numeric) { inColNum++; numTok++; if (isEnumMarker(w.text)) markerTok++; }
                }
                if (inCol > 0) { occupied++; if (inColNum == inCol) allNum++; }
            }
            if (occupied == 0) continue;
            boolean numericData = (double) allNum / occupied >= StreamTableExtractor.NUMERIC_DATA_ROW_MAJORITY;
            boolean markerCol = numTok > 0 && markerTok * 2 > numTok;
            if (numericData && !markerCol) n++;
        }
        return n;
    }
}
