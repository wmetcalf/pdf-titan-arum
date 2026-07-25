// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/, but declares
// `package com.oai.titanarum;` -- same trick BakeOffHarness / Diag9cHarness / Diag9jHarness use,
// for the same reason (StreamTableExtractor and its Word/Line/Gutter/Grid types, GutterFinder,
// BreuelGutterFinder, TableExtractor.TableHit/CellHit are all package-private).
//
// THROWAWAY DIAGNOSTIC (font/weight/style lever). Purely observational: does NOT modify
// StreamTableExtractor, TableScore, or GroundTruth. Gated by -DdiagFont=true and named so
// Surefire's default include patterns never discover it.
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.TableScore;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

class DiagFontHarness {

    // ------------------------------------------------------------------------------ style model

    /** Per-glyph / per-word style key. size is quantized to 0.5pt to absorb float noise. */
    record Style(String font, float size, boolean bold, boolean italic) {
        String key() {
            return font + "@" + String.format(Locale.ROOT, "%.1f", size) + (bold ? "/B" : "") + (italic ? "/I" : "");
        }
        /** Coarse "is this visually a different register" key: weight+size only, family ignored. */
        String weightKey() {
            return (bold ? "B" : "n") + (italic ? "I" : "n") + "@" + String.format(Locale.ROOT, "%.1f", size);
        }
    }

    static Style styleOf(TextPosition tp) {
        String name = "?";
        boolean bold = false, italic = false;
        try {
            PDFont f = tp.getFont();
            if (f != null) {
                String n = f.getName();
                if (n != null && !n.isEmpty()) name = n;
                PDFontDescriptor d = null;
                try { d = f.getFontDescriptor(); } catch (Throwable ignore) { }
                if (d != null) {
                    try { if (d.isForceBold()) bold = true; } catch (Throwable ignore) { }
                    try { if (d.getFontWeight() >= 600f) bold = true; } catch (Throwable ignore) { }
                    try { if (d.isItalic()) italic = true; } catch (Throwable ignore) { }
                    try { if (Math.abs(d.getItalicAngle()) > 1f) italic = true; } catch (Throwable ignore) { }
                }
                String lo = name.toLowerCase(Locale.ROOT);
                if (lo.contains("bold") || lo.contains("black") || lo.contains("heavy")
                        || lo.contains("semib") || lo.endsWith(",b") || lo.endsWith("-bd")
                        || lo.endsWith(",bi")) bold = true;
                if (lo.contains("italic") || lo.contains("oblique") || lo.endsWith(",i")
                        || lo.endsWith(",bi")) italic = true;
            }
        } catch (Throwable ignore) { }
        float size = 12f;
        try { size = Math.max(1f, tp.getTextMatrix().getScalingFactorY()); } catch (Throwable ignore) { }
        return new Style(name, Math.round(size * 2f) / 2f, bold, italic);
    }

    /** A word's aggregated style: the dominant (most glyphs) per-glyph style + bold char fraction. */
    static final class WStyle {
        Style dominant;
        int chars;
        int boldChars;
        String text;
    }

    /**
     * Mirrors {@link StreamTableExtractor#buildWords} EXACTLY (same segmentation predicates, same
     * empty-word dropping) but accumulates style instead of geometry, so the i-th element of this
     * list corresponds to the i-th Word buildWords returns. Verified per-PDF by comparing text.
     */
    static List<WStyle> styledWords(List<TextPosition> glyphs) {
        List<WStyle> out = new ArrayList<>();
        StringBuilder curText = null;
        Map<String, int[]> curCounts = null;      // style key -> count
        Map<String, Style> curStyles = null;
        int curBold = 0, curChars = 0;
        float prevX1 = 0, prevBaseline = 0;
        boolean have = false;
        for (TextPosition tp : glyphs) {
            String u = tp.getUnicode();
            if (u == null || u.isEmpty()) continue;
            float gx0 = tp.getXDirAdj();
            float gy0 = tp.getYDirAdj();
            float gw = tp.getWidthDirAdj();
            float fs = Math.max(1f, tp.getTextMatrix().getScalingFactorY());
            float space = Math.max(tp.getWidthOfSpace(), 0.25f * fs);
            boolean whitespace = u.trim().isEmpty();
            boolean newLine = have && Math.abs(gy0 - prevBaseline) > 0.5f * fs;
            boolean gap = have && (gx0 - prevX1) > 0.30f * space;
            if (!have || whitespace || newLine || gap) {
                if (have) finish(out, curText, curCounts, curStyles, curBold, curChars);
                if (whitespace) { have = false; prevX1 = gx0 + gw; prevBaseline = gy0; continue; }
                curText = new StringBuilder();
                curCounts = new HashMap<>();
                curStyles = new HashMap<>();
                curBold = 0; curChars = 0;
                have = true;
            }
            Style st = styleOf(tp);
            curText.append(u);
            curCounts.computeIfAbsent(st.key(), k -> new int[1])[0] += u.length();
            curStyles.putIfAbsent(st.key(), st);
            curChars += u.length();
            if (st.bold()) curBold += u.length();
            prevX1 = gx0 + gw; prevBaseline = gy0;
        }
        if (have) finish(out, curText, curCounts, curStyles, curBold, curChars);
        return out;
    }

    private static void finish(List<WStyle> out, StringBuilder text, Map<String, int[]> counts,
                               Map<String, Style> styles, int boldChars, int chars) {
        String t = text.toString().trim();
        if (t.isEmpty()) return;                        // mirrors finishWord's drop
        WStyle w = new WStyle();
        w.text = t;
        w.chars = chars;
        w.boldChars = boldChars;
        String bestKey = null; int best = -1;
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            if (e.getValue()[0] > best) { best = e.getValue()[0]; bestKey = e.getKey(); }
        }
        w.dominant = styles.get(bestKey);
        out.add(w);
    }

    // ------------------------------------------------------------------- replicated pipeline bits

    private static int colOf(float x, float[] bounds) {                   // copy of the private one
        for (int c = 0; c < bounds.length - 1; c++) if (x < bounds[c + 1]) return c;
        return bounds.length - 2;
    }

    /** Copy of StreamTableExtractor.buildHit's body, parameterized by an arbitrary row grouping. */
    static TableExtractor.TableHit buildHit(int pageNum, StreamTableExtractor.Grid grid, boolean[] newRow) {
        int cols = grid.colBounds.length - 1;
        List<List<StreamTableExtractor.Line>> rowGroups = new ArrayList<>();
        for (int i = 0; i < grid.rows.size(); i++) {
            if (rowGroups.isEmpty() || newRow[i]) rowGroups.add(new ArrayList<>());
            rowGroups.get(rowGroups.size() - 1).add(grid.rows.get(i));
        }
        int rows = rowGroups.size();
        if ((long) rows * cols > TableExtractor.MAX_CELLS_PER_TABLE) return null;

        TableExtractor.TableHit t = new TableExtractor.TableHit();
        t.page = pageNum;
        t.extractionMethod = "stream";
        t.confidence = Math.round(grid.confidence * 1000.0) / 1000.0;
        t.rowCount = rows; t.colCount = cols;
        t.cells = new ArrayList<>();
        float tx0 = Float.MAX_VALUE, ty0 = Float.MAX_VALUE, tx1 = -Float.MAX_VALUE, ty1 = -Float.MAX_VALUE;
        for (int r = 0; r < rows; r++) {
            StringBuilder[] text = new StringBuilder[cols];
            float[][] box = new float[cols][];
            for (StreamTableExtractor.Line line : rowGroups.get(r)) {
                for (StreamTableExtractor.Word w : line.words) {
                    int c = colOf(w.cx(), grid.colBounds);
                    if (text[c] == null) { text[c] = new StringBuilder(); box[c] = new float[]{w.x0, w.y0, w.x1, w.y1}; }
                    else { if (text[c].length() > 0) text[c].append(' '); }
                    text[c].append(w.text);
                    box[c][0] = Math.min(box[c][0], w.x0); box[c][1] = Math.min(box[c][1], w.y0);
                    box[c][2] = Math.max(box[c][2], w.x1); box[c][3] = Math.max(box[c][3], w.y1);
                }
            }
            for (int c = 0; c < cols; c++) {
                if (text[c] == null) continue;
                TableExtractor.CellHit cell = new TableExtractor.CellHit();
                cell.row = r; cell.col = c; cell.rowSpan = 1; cell.colSpan = 1;
                cell.text = text[c].toString();
                cell.bbox = box[c];
                t.cells.add(cell);
                tx0 = Math.min(tx0, box[c][0]); ty0 = Math.min(ty0, box[c][1]);
                tx1 = Math.max(tx1, box[c][2]); ty1 = Math.max(ty1, box[c][3]);
            }
        }
        if (t.cells.isEmpty()) return null;
        t.bbox = new float[]{tx0, ty0, tx1, ty1};
        TableExtractor.renderViews(t);
        return t;
    }

    /** One candidate block that cleared the production confidence gate. */
    static final class BlockCtx {
        int pageNum;
        StreamTableExtractor.Grid grid;
        boolean[] prodNewRow;
        boolean[] curNewRow;
        boolean[] oracleNewRow;
        List<LineStyle> lineStyles = new ArrayList<>();   // parallel to grid.rows
        int anchorCol;
        int firstAnchorIdx;
        String modalKey;
    }

    static final class LineStyle {
        String key;              // dominant style key over the line's chars
        String weightKey;
        double boldFrac;
        float size;
        boolean bold;
        String firstWordKey = "?";      // style of the line's leftmost word (label column)
        Set<String> allKeys = new HashSet<>();   // every style key present on the line
    }

    static final class PdfCtx {
        BakeOffHarness.ScoreUnit unit;
        List<BlockCtx> blocks = new ArrayList<>();
        List<TableExtractor.TableHit> prodHits = new ArrayList<>();   // from the REAL extractPage
        boolean replicaMatchesProduction = true;
    }

    // ------------------------------------------------------------------------------ scoring copy

    record Sc(int tp, int fp, int fn, int paired, int adjMatched, int adjDet, int adjGt, double adjF1,
              int spuriousHits, int unpairedGt) {}

    /** Copy of BakeOffHarness.scoreUnit's scoring/pairing half, over a supplied hit list. */
    static Sc score(List<GroundTruth.Table> expected, List<TableExtractor.TableHit> hits) {
        List<TableExtractor.TableHit> available = new ArrayList<>(hits);
        int tp = 0, fp = 0, fn = 0, paired = 0, unpairedGt = 0;
        int adjMatched = 0, adjDet = 0, adjGt = 0;
        for (GroundTruth.Table exp : expected) {
            if (available.isEmpty()) {
                fn += nonEmptyCellCount(exp.rows());
                adjGt += TableScore.relationCount(exp.rows());
                unpairedGt++;
                continue;
            }
            TableExtractor.TableHit best = null; TableScore.Result bestR = null; double bestF1 = -1;
            for (TableExtractor.TableHit h : available) {
                TableScore.Result r = TableScore.score(exp, h.rows);
                if (r.f1() > bestF1) { bestF1 = r.f1(); best = h; bestR = r; }
            }
            available.remove(best);
            tp += bestR.truePositives(); fp += bestR.falsePositives(); fn += bestR.falseNegatives();
            paired++;
            TableScore.AdjResult a = TableScore.scoreAdjacency(exp, best.rows);
            adjMatched += a.matched(); adjDet += a.detectedTotal(); adjGt += a.gtTotal();
        }
        int spurious = available.size();
        for (TableExtractor.TableHit h : available) {
            fp += nonEmptyCellCount(h.rows);
            adjDet += TableScore.relationCount(h.rows);
        }
        double p = adjDet == 0 ? 0 : (double) adjMatched / adjDet;
        double r = adjGt == 0 ? 0 : (double) adjMatched / adjGt;
        double f1 = adjMatched == 0 ? 0 : 2 * p * r / (p + r);
        return new Sc(tp, fp, fn, paired, adjMatched, adjDet, adjGt, f1, spurious, unpairedGt);
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

    // --------------------------------------------------------------------------------- the test

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("diagFont"), "set -DdiagFont=true to run");
        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        List<BakeOffHarness.ScoreUnit> units = corpus.units;
        System.out.println("Scoring set: " + units.size() + " PDFs");

        GutterFinder finder = new BreuelGutterFinder();
        List<PdfCtx> ctxs = new ArrayList<>();
        for (BakeOffHarness.ScoreUnit u : units) ctxs.add(buildCtx(u, finder));

        // ---------------- A: replication check ----------------
        int replicaOk = 0, replicaBad = 0;
        long mA = 0, dA = 0, gA = 0;      // production hits, micro adjacency
        long mR = 0, dR = 0, gR = 0;      // replicated hits, micro adjacency
        List<Double> perPdfProd = new ArrayList<>();
        Map<String, Sc> prodScores = new TreeMap<>();
        for (PdfCtx c : ctxs) {
            if (c.replicaMatchesProduction) replicaOk++; else replicaBad++;
            Sc sp = score(c.unit.expected(), c.prodHits);
            mA += sp.adjMatched(); dA += sp.adjDet(); gA += sp.adjGt();
            perPdfProd.add(sp.adjF1());
            prodScores.put(c.unit.id(), sp);
            Sc sr = score(c.unit.expected(), rebuildAll(c));
            mR += sr.adjMatched(); dR += sr.adjDet(); gR += sr.adjGt();
        }
        System.out.println();
        System.out.println("=== A. replication check ===");
        System.out.println("PDFs whose replicated hits == production hits (row grids): " + replicaOk
                + " ok / " + replicaBad + " MISMATCH");
        System.out.println("production   micro adjF1 = " + microF1(mA, dA, gA)
                + "   (matched=" + mA + " det=" + dA + " gt=" + gA + ")");
        System.out.println("replica-prod micro adjF1 = " + microF1(mR, dR, gR));
        System.out.println("macro (per-PDF mean) adjF1 = " + fmt(mean(perPdfProd)));

        // ---------------- B: row-grouping policy sweep + oracle ----------------
        System.out.println();
        System.out.println("=== B. row-grouping policy sweep (all other stages untouched) ===");
        policySweep(ctxs, "P0 production      ", 0);
        policySweep(ctxs, "P1 one-row-per-line", 1);
        policySweep(ctxs, "P2 fold-all (9f)   ", 2);

        // oracle: hill-climb every per-line new-row bit to maximize that PDF's adjacency F1
        long mO = 0, dO = 0, gO = 0;
        List<Double> perPdfOracle = new ArrayList<>();
        int totalFlips = 0, totalFlippableLines = 0, pdfsImproved = 0;
        Map<String, Double> oracleGain = new TreeMap<>();
        List<int[]> flipStyleStats = new ArrayList<>();   // per flip: {styleDiffPrev, dirToNewRow}
        int flipStyleDiff = 0, flipStyleSame = 0;
        int allLinesStyleDiff = 0, allLines = 0;
        for (PdfCtx c : ctxs) {
            Sc before = score(c.unit.expected(), rebuildAll(c));
            Sc after = hillClimb(c, ANY);
            mO += after.adjMatched(); dO += after.adjDet(); gO += after.adjGt();
            perPdfOracle.add(after.adjF1());
            if (after.adjF1() > before.adjF1() + 1e-9) pdfsImproved++;
            oracleGain.put(c.unit.id(), after.adjF1() - before.adjF1());
            for (BlockCtx b : c.blocks) {
                b.oracleNewRow = b.curNewRow.clone();
                for (int i = 1; i < b.prodNewRow.length; i++) {
                    totalFlippableLines++;
                    boolean diff = !b.lineStyles.get(i).key.equals(b.lineStyles.get(i - 1).key);
                    allLines++;
                    if (diff) allLinesStyleDiff++;
                    if (b.prodNewRow[i] != b.curNewRow[i]) {
                        totalFlips++;
                        if (diff) flipStyleDiff++; else flipStyleSame++;
                        flipStyleStats.add(new int[]{diff ? 1 : 0, b.curNewRow[i] ? 1 : 0});
                    }
                }
            }
        }
        System.out.println("P* ORACLE hill-climb : micro adjF1 = " + microF1(mO, dO, gO)
                + "   macro = " + fmt(mean(perPdfOracle))
                + "   (matched=" + mO + " det=" + dO + " gt=" + gO + ")");
        System.out.println("  PDFs improved by ANY row-grouping change: " + pdfsImproved + " / " + ctxs.size());
        System.out.println("  per-line decisions flipped: " + totalFlips + " / " + totalFlippableLines
                + " flippable lines");

        System.out.println();
        System.out.println("=== C. does a style discontinuity predict the oracle's flips? ===");
        System.out.println("  lines whose dominant style key != previous line's: " + allLinesStyleDiff
                + " / " + allLines + " (" + pct(allLinesStyleDiff, allLines) + ")  <- base rate");
        System.out.println("  oracle-flipped lines with style discontinuity: " + flipStyleDiff
                + " / " + totalFlips + " (" + pct(flipStyleDiff, totalFlips) + ")");
        System.out.println("  oracle-flipped lines with NO style change:     " + flipStyleSame);
        int toNewRowDiff = 0, toNewRowTotal = 0, toMergeDiff = 0, toMergeTotal = 0;
        for (int[] f : flipStyleStats) {
            if (f[1] == 1) { toNewRowTotal++; if (f[0] == 1) toNewRowDiff++; }
            else { toMergeTotal++; if (f[0] == 1) toMergeDiff++; }
        }
        System.out.println("  flips toward NEW ROW (split): " + toNewRowTotal + ", of which style-differs "
                + toNewRowDiff + " (" + pct(toNewRowDiff, toNewRowTotal) + ")");
        System.out.println("  flips toward MERGE          : " + toMergeTotal + ", of which style-differs "
                + toMergeDiff + " (" + pct(toMergeDiff, toMergeTotal) + ")");

        // ------------- C2: RESTRICTED oracles = ceiling of a rule that can only fire on X -------
        System.out.println();
        System.out.println("=== C2. restricted oracles (ceiling of any rule whose trigger is X) ===");
        boolean quick = Boolean.getBoolean("diagFontQuick");
        if (quick) System.out.println("  [skipped: -DdiagFontQuick=true]");
        if (!quick) {
        System.out.println("  production baseline                            micro=" + microF1(mA, dA, gA)
                + " macro=" + fmt(mean(perPdfProd)));
        restrictedOracle(ctxs, "oracle: unrestricted (any line)", ANY);
        restrictedOracle(ctxs, "oracle: only lines w/ style key != prev line", STYLE_DIFF_PREV);
        restrictedOracle(ctxs, "oracle: only lines w/ weight/size != prev", WEIGHT_DIFF_PREV);
        restrictedOracle(ctxs, "oracle: only lines touching non-modal style", STYLE_DIFF_MODAL);
        restrictedOracle(ctxs, "oracle: only header/leading run (i<=1stAnchor)", LEADING_RUN);
        restrictedOracle(ctxs, "oracle: only first 4 lines of block", FIRST4);
        restrictedOracle(ctxs, "oracle: header run AND style != prev  <= LEVER (a)",
                (b, i) -> LEADING_RUN.allowed(b, i) && STYLE_DIFF_PREV.allowed(b, i));
        restrictedOracle(ctxs, "oracle: header run AND style == prev (complement)",
                (b, i) -> LEADING_RUN.allowed(b, i) && !STYLE_DIFF_PREV.allowed(b, i));
        restrictedOracle(ctxs, "oracle: body only (i>1stAnchor), any style",
                (b, i) -> !LEADING_RUN.allowed(b, i));
        }

        // ------------- C3: concrete deployable font rules -------------
        System.out.println();
        System.out.println("=== C3. concrete (non-oracle) font-driven row-grouping rules ===");
        RULE_BASELINE = prodScores;
        rulePolicy(ctxs, "R0 production rule (control)", (b, i) -> b.prodNewRow[i]);
        rulePolicy(ctxs, "R1 new row iff style != prev line", (b, i) ->
                !b.lineStyles.get(i).key.equals(b.lineStyles.get(i - 1).key));
        rulePolicy(ctxs, "R2 prod, but header lines split iff style change", (b, i) -> {
            if (i <= b.firstAnchorIdx) return !b.lineStyles.get(i).key.equals(b.lineStyles.get(i - 1).key);
            return b.prodNewRow[i];
        });
        rulePolicy(ctxs, "R3 prod, but MERGE when style==prev & fewer cols", (b, i) -> {
            if (!b.prodNewRow[i]) return false;
            boolean sameStyle = b.lineStyles.get(i).key.equals(b.lineStyles.get(i - 1).key);
            if (sameStyle && popCols(b, i) < popCols(b, i - 1)) return false;   // merge
            return true;
        });
        rulePolicy(ctxs, "R4 prod, but split when style != prev anywhere", (b, i) ->
                b.prodNewRow[i] || !b.lineStyles.get(i).key.equals(b.lineStyles.get(i - 1).key));
        rulePolicy(ctxs, "R7 prod, but split at bold->non-bold transition", (b, i) ->
                b.prodNewRow[i] || (b.lineStyles.get(i - 1).bold && !b.lineStyles.get(i).bold));
        rulePolicy(ctxs, "R8 prod, but split at weight/size change", (b, i) ->
                b.prodNewRow[i] || !b.lineStyles.get(i).weightKey.equals(b.lineStyles.get(i - 1).weightKey));
        rulePolicy(ctxs, "R9 prod, but MERGE when style != prev (inverse)", (b, i) ->
                b.prodNewRow[i] && b.lineStyles.get(i).key.equals(b.lineStyles.get(i - 1).key));
        rulePolicy(ctxs, "R5 fold-all, header split iff style change", (b, i) -> {
            if (i <= b.firstAnchorIdx) return !b.lineStyles.get(i).key.equals(b.lineStyles.get(i - 1).key);
            return anchorPopulated(b, i);
        });

        // ---------------- D: raw font-signal availability ----------------
        System.out.println();
        System.out.println("=== D. font-signal availability inside detected table blocks ===");
        int blocks = 0, blocksMultiStyle = 0, blocksAnyBold = 0, blocksFirstLineDiffers = 0,
            blocksFirstLineBoldBodyNot = 0, blocksSizeVaries = 0, blocksSingleStyle = 0,
            blocksUnknownFont = 0;
        Map<Integer, Integer> distinctKeyHist = new TreeMap<>();
        for (PdfCtx c : ctxs) {
            for (BlockCtx b : c.blocks) {
                blocks++;
                Set<String> keys = new HashSet<>();
                Set<Float> sizes = new HashSet<>();
                boolean anyBold = false, unknown = false;
                for (LineStyle ls : b.lineStyles) {
                    keys.add(ls.key); sizes.add(ls.size);
                    if (ls.bold) anyBold = true;
                    if (ls.key.startsWith("?@")) unknown = true;
                }
                distinctKeyHist.merge(Math.min(keys.size(), 6), 1, Integer::sum);
                if (keys.size() > 1) blocksMultiStyle++; else blocksSingleStyle++;
                if (anyBold) blocksAnyBold++;
                if (sizes.size() > 1) blocksSizeVaries++;
                if (unknown) blocksUnknownFont++;
                // modal body style = mode over lines 1..n-1
                Map<String, Integer> modeCount = new HashMap<>();
                for (int i = 1; i < b.lineStyles.size(); i++)
                    modeCount.merge(b.lineStyles.get(i).key, 1, Integer::sum);
                String modal = null; int mc = -1;
                for (Map.Entry<String, Integer> e : modeCount.entrySet())
                    if (e.getValue() > mc) { mc = e.getValue(); modal = e.getKey(); }
                if (modal != null && !modal.equals(b.lineStyles.get(0).key)) blocksFirstLineDiffers++;
                if (b.lineStyles.get(0).bold && modal != null && !modal.contains("/B"))
                    blocksFirstLineBoldBodyNot++;
            }
        }
        System.out.println("  candidate blocks that produced a hit: " + blocks);
        System.out.println("  blocks with >1 distinct line style key: " + blocksMultiStyle + " ("
                + pct(blocksMultiStyle, blocks) + ")");
        System.out.println("  blocks with exactly ONE style everywhere (font signal absent): "
                + blocksSingleStyle + " (" + pct(blocksSingleStyle, blocks) + ")");
        System.out.println("  blocks with any bold line: " + blocksAnyBold + " (" + pct(blocksAnyBold, blocks) + ")");
        System.out.println("  blocks with >1 font size: " + blocksSizeVaries + " (" + pct(blocksSizeVaries, blocks) + ")");
        System.out.println("  blocks where line 0 style != modal body style: " + blocksFirstLineDiffers
                + " (" + pct(blocksFirstLineDiffers, blocks) + ")");
        System.out.println("  blocks where line 0 is bold and body is not: " + blocksFirstLineBoldBodyNot
                + " (" + pct(blocksFirstLineBoldBodyNot, blocks) + ")");
        System.out.println("  blocks with an unresolvable font name (\"?\"): " + blocksUnknownFont);
        System.out.println("  distinct-style-key histogram (capped at 6): " + distinctKeyHist);

        // ---------------- E: table-count mismatch bucket + spurious-hit style ----------------
        System.out.println();
        System.out.println("=== E. spurious/unpaired tables and their style homogeneity ===");
        int countMatch = 0, countMismatch = 0;
        List<Double> matchF1 = new ArrayList<>(), mismatchF1 = new ArrayList<>();
        for (PdfCtx c : ctxs) {
            Sc s = prodScores.get(c.unit.id());
            if (c.prodHits.size() == c.unit.expected().size()) { countMatch++; matchF1.add(s.adjF1()); }
            else { countMismatch++; mismatchF1.add(s.adjF1()); }
        }
        System.out.println("  table-count match: " + countMatch + " PDFs, mean adjF1 " + fmt(mean(matchF1)));
        System.out.println("  table-count mismatch: " + countMismatch + " PDFs, mean adjF1 " + fmt(mean(mismatchF1)));

        // per-hit style homogeneity, split by whether the hit got paired
        List<Double> pairedKeys = new ArrayList<>(), spuriousKeys = new ArrayList<>();
        for (PdfCtx c : ctxs) {
            Set<TableExtractor.TableHit> paired = pairedHits(c.unit.expected(), c.prodHits);
            for (int bi = 0; bi < c.blocks.size(); bi++) {
                BlockCtx b = c.blocks.get(bi);
                Set<String> keys = new HashSet<>();
                for (LineStyle ls : b.lineStyles) keys.add(ls.key);
                TableExtractor.TableHit h = bi < c.prodHits.size() ? c.prodHits.get(bi) : null;
                if (h != null && paired.contains(h)) pairedKeys.add((double) keys.size());
                else spuriousKeys.add((double) keys.size());
            }
        }
        System.out.println("  mean distinct style keys, PAIRED hits:   " + fmt(mean(pairedKeys))
                + "  (n=" + pairedKeys.size() + ")");
        System.out.println("  mean distinct style keys, SPURIOUS hits: " + fmt(mean(spuriousKeys))
                + "  (n=" + spuriousKeys.size() + ")");

        // ---------------- F: named fixtures ----------------
        System.out.println();
        System.out.println("=== F. named fixtures: per-block style vs oracle flips ===");
        for (PdfCtx c : ctxs) {
            String id = c.unit.id();
            if (!(id.contains("spanning_cells") || id.contains("us-018") || id.contains("us-013")
                    || id.contains("us-020") || id.contains("eu-027") || id.contains("us-012")
                    || id.contains("eu-004") || id.contains("us-017"))) continue;
            Sc sp = prodScores.get(id);
            System.out.println();
            System.out.println("--- " + id + "  prodAdjF1=" + fmt(sp.adjF1()) + " oracleGain="
                    + fmt(oracleGain.getOrDefault(id, 0.0)) + " hits=" + c.prodHits.size()
                    + " gtTables=" + c.unit.expected().size() + " ---");
            for (BlockCtx b : c.blocks) {
                System.out.println("  block p" + b.pageNum + " lines=" + b.grid.rows.size()
                        + " cols=" + (b.grid.colBounds.length - 1) + " anchorCol=" + b.anchorCol
                        + " conf=" + fmt(b.grid.confidence));
                for (int i = 0; i < b.grid.rows.size() && i < 14; i++) {
                    LineStyle ls = b.lineStyles.get(i);
                    StringBuilder txt = new StringBuilder();
                    for (StreamTableExtractor.Word w : b.grid.rows.get(i).words) {
                        if (txt.length() > 62) { txt.append("..."); break; }
                        txt.append(w.text).append(' ');
                    }
                    System.out.println(String.format(Locale.ROOT,
                            "    L%-2d prod=%s oracle=%s style=%-34s bold=%.2f | %s",
                            i, b.prodNewRow[i] ? "NEW" : "cont", b.oracleNewRow[i] ? "NEW" : "cont",
                            ls.key.length() > 34 ? ls.key.substring(ls.key.length() - 34) : ls.key,
                            ls.boldFrac, txt.toString().trim()));
                }
            }
        }

        // ---------------- I: what DO the oracle's 841 merge flips look like? -------------------
        System.out.println();
        System.out.println("=== I. feature profile of the oracle's flips (merge flips = 91% of the prize) ===");
        int[] mergeAnchor = new int[2], splitAnchor = new int[2], noflipAnchor = new int[2];
        List<Double> mergePitch = new ArrayList<>(), noflipPitch = new ArrayList<>();
        int mergeFewerCols = 0, mergeTotal = 0, noflipFewerCols = 0, noflipTotal = 0;
        int mergeStyleDiff = 0, noflipStyleDiff = 0;
        int mergeFirstWordDiff = 0, noflipFirstWordDiff = 0, mergeAnyKeyDiff = 0, noflipAnyKeyDiff = 0;
        for (PdfCtx c : ctxs) {
            for (BlockCtx b : c.blocks) {
                float medPitch = medianPitch(b);
                for (int i = 1; i < b.prodNewRow.length; i++) {
                    boolean flipped = b.prodNewRow[i] != b.oracleNewRow[i];
                    boolean toMerge = flipped && !b.oracleNewRow[i];
                    boolean anchor = anchorPopulated(b, i);
                    double pitch = (b.grid.rows.get(i).yTop - b.grid.rows.get(i - 1).yTop) / Math.max(0.01f, medPitch);
                    boolean fewer = popCols(b, i) < popCols(b, i - 1);
                    boolean sdiff = !b.lineStyles.get(i).key.equals(b.lineStyles.get(i - 1).key);
                    boolean fwDiff = !b.lineStyles.get(i).firstWordKey.equals(b.lineStyles.get(i - 1).firstWordKey);
                    boolean anyDiff = !b.lineStyles.get(i).allKeys.equals(b.lineStyles.get(i - 1).allKeys);
                    if (toMerge) {
                        mergeAnchor[anchor ? 1 : 0]++; mergePitch.add(pitch); mergeTotal++;
                        if (fewer) mergeFewerCols++;
                        if (sdiff) mergeStyleDiff++;
                        if (fwDiff) mergeFirstWordDiff++;
                        if (anyDiff) mergeAnyKeyDiff++;
                    } else if (flipped) {
                        splitAnchor[anchor ? 1 : 0]++;
                    } else {
                        noflipAnchor[anchor ? 1 : 0]++; noflipPitch.add(pitch); noflipTotal++;
                        if (fewer) noflipFewerCols++;
                        if (sdiff) noflipStyleDiff++;
                        if (fwDiff) noflipFirstWordDiff++;
                        if (anyDiff) noflipAnyKeyDiff++;
                    }
                }
            }
        }
        System.out.println("  MERGE-flip lines (n=" + mergeTotal + "): anchorPopulated=" + mergeAnchor[1]
                + " (" + pct(mergeAnchor[1], mergeTotal) + "), fewerColsThanPrev=" + mergeFewerCols
                + " (" + pct(mergeFewerCols, mergeTotal) + "), styleDiffPrev=" + mergeStyleDiff
                + " (" + pct(mergeStyleDiff, mergeTotal) + "), mean pitch/medianPitch="
                + fmt(mean(mergePitch)));
        System.out.println("  NON-flipped lines (n=" + noflipTotal + "): anchorPopulated=" + noflipAnchor[1]
                + " (" + pct(noflipAnchor[1], noflipTotal) + "), fewerColsThanPrev=" + noflipFewerCols
                + " (" + pct(noflipFewerCols, noflipTotal) + "), styleDiffPrev=" + noflipStyleDiff
                + " (" + pct(noflipStyleDiff, noflipTotal) + "), mean pitch/medianPitch="
                + fmt(mean(noflipPitch)));
        System.out.println("  SPLIT-flip lines: anchorPopulated=" + splitAnchor[1] + " / notPopulated=" + splitAnchor[0]);
        System.out.println("  MAX-SENSITIVITY style tests (merge-flip vs non-flip):");
        System.out.println("    leftmost-word style differs from prev line's leftmost word: "
                + pct(mergeFirstWordDiff, mergeTotal) + " vs " + pct(noflipFirstWordDiff, noflipTotal));
        System.out.println("    full SET of style keys on the line differs from prev line's: "
                + pct(mergeAnyKeyDiff, mergeTotal) + " vs " + pct(noflipAnyKeyDiff, noflipTotal));

        // rows we emit vs rows ground truth has, for paired tables
        int over = 0, under = 0, equal = 0; long rowDelta = 0;
        for (PdfCtx c : ctxs) {
            List<TableExtractor.TableHit> avail = new ArrayList<>(c.prodHits);
            for (GroundTruth.Table exp : c.unit.expected()) {
                if (avail.isEmpty()) break;
                TableExtractor.TableHit best = null; double bf = -1;
                for (TableExtractor.TableHit h : avail) {
                    double f1 = TableScore.score(exp, h.rows).f1();
                    if (f1 > bf) { bf = f1; best = h; }
                }
                avail.remove(best);
                int d0 = best.rows.size() - exp.rowCount();
                rowDelta += d0;
                if (d0 > 0) over++; else if (d0 < 0) under++; else equal++;
            }
        }
        System.out.println("  paired tables where we emit MORE rows than GT: " + over
                + ", FEWER: " + under + ", EQUAL: " + equal + ", summed row delta=" + rowDelta);

        // ---------------- H: LEVER (b) -- style as a block-boundary signal, end to end ----------
        System.out.println();
        System.out.println("=== H. LEVER (b): style change as a block/region boundary signal ===");
        if (quick) System.out.println("  [skipped: -DdiagFontQuick=true]");
        else for (int mode = 0; mode <= 3; mode++) segVariant(units, finder, mode);

        System.out.println();
        System.out.println("=== H2. prose false-positive rate for the segmentation variants ===");
        List<java.nio.file.Path> prose = quick ? null : proseSample();
        if (prose == null || prose.isEmpty()) {
            System.out.println("  prose corpus not found -- skipped");
        } else {
            for (int mode = 0; mode <= 3; mode++) {
                int flagged = 0;
                for (java.nio.file.Path p : prose) if (segHasHitOnPage1(p, finder, mode)) flagged++;
                System.out.println("  " + segLabel(mode) + " proseFP = "
                        + String.format(Locale.ROOT, "%.3f", flagged / (double) prose.size())
                        + " (" + flagged + "/" + prose.size() + ")");
            }
        }

        // ---------------- J: oracle validity spot-check (is it real, or metric gaming?) ---------
        System.out.println();
        System.out.println("=== J. oracle validity spot-check: GT rows vs production rows vs oracle rows ===");
        for (PdfCtx c : ctxs) {
            String id = c.unit.id();
            if (!(id.contains("us-013") || id.contains("us-024"))) continue;
            System.out.println();
            System.out.println("--- " + id + " ---");
            GroundTruth.Table exp = c.unit.expected().get(0);
            System.out.println("  GT table 0 (" + exp.rowCount() + " rows):");
            for (int r = 0; r < Math.min(8, exp.rowCount()); r++)
                System.out.println("    GT r" + r + ": " + exp.rows().get(r));
            if (!c.blocks.isEmpty()) {
                BlockCtx b = c.blocks.get(0);
                TableExtractor.TableHit ph = buildHit(b.pageNum, b.grid, b.prodNewRow);
                TableExtractor.TableHit oh = buildHit(b.pageNum, b.grid, b.oracleNewRow);
                System.out.println("  PROD block 0 (" + ph.rows.size() + " rows):");
                for (int r = 0; r < Math.min(8, ph.rows.size()); r++)
                    System.out.println("    P r" + r + ": " + ph.rows.get(r));
                System.out.println("  ORACLE block 0 (" + oh.rows.size() + " rows):");
                for (int r = 0; r < Math.min(8, oh.rows.size()); r++)
                    System.out.println("    O r" + r + ": " + oh.rows.get(r));
            }
        }

        // ---------------- G: biggest oracle gains ----------------
        System.out.println();
        System.out.println("=== G. PDFs with the largest oracle row-grouping gain ===");
        oracleGain.entrySet().stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .limit(20)
                .forEach(e -> System.out.println(String.format(Locale.ROOT, "  %-58s prod=%s -> +%s",
                        e.getKey(), fmt(prodScores.get(e.getKey()).adjF1()), fmt(e.getValue()))));
    }

    // ---------------------------------------------------------------------------------- helpers

    private static Set<TableExtractor.TableHit> pairedHits(List<GroundTruth.Table> expected,
                                                           List<TableExtractor.TableHit> hits) {
        List<TableExtractor.TableHit> available = new ArrayList<>(hits);
        Set<TableExtractor.TableHit> paired = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (GroundTruth.Table exp : expected) {
            if (available.isEmpty()) break;
            TableExtractor.TableHit best = null; double bestF1 = -1;
            for (TableExtractor.TableHit h : available) {
                double f1 = TableScore.score(exp, h.rows).f1();
                if (f1 > bestF1) { bestF1 = f1; best = h; }
            }
            available.remove(best);
            paired.add(best);
        }
        return paired;
    }

    private static void policySweep(List<PdfCtx> ctxs, String label, int policy) {
        long m = 0, d = 0, g = 0;
        List<Double> per = new ArrayList<>();
        for (PdfCtx c : ctxs) {
            List<TableExtractor.TableHit> hits = new ArrayList<>();
            for (BlockCtx b : c.blocks) {
                boolean[] v = new boolean[b.prodNewRow.length];
                switch (policy) {
                    case 0 -> System.arraycopy(b.prodNewRow, 0, v, 0, v.length);
                    case 1 -> java.util.Arrays.fill(v, true);
                    case 2 -> {                                     // fold-all: anchor-only splits
                        for (int i = 0; i < v.length; i++) v[i] = anchorPopulated(b, i);
                        v[0] = true;
                    }
                    default -> throw new IllegalStateException();
                }
                TableExtractor.TableHit h = buildHit(b.pageNum, b.grid, v);
                if (h != null) hits.add(h);
            }
            Sc s = score(c.unit.expected(), hits);
            m += s.adjMatched(); d += s.adjDet(); g += s.adjGt();
            per.add(s.adjF1());
        }
        System.out.println(label + ": micro adjF1 = " + microF1(m, d, g) + "   macro = " + fmt(mean(per)));
    }

    // ------------------------------------------------------- LEVER (b): segmentation variants

    private static String segLabel(int mode) {
        return switch (mode) {
            case 0 -> "S0 production splitIntoBlocks (control)   ";
            case 1 -> "S1 + also split at any style-key change   ";
            case 2 -> "S2 + also split at weight/size change only";
            case 3 -> "S3 merge adjacent blocks w/ equal modal style";
            default -> "?";
        };
    }

    /** Runs the WHOLE per-page pipeline with an alternative block segmentation, scores the corpus. */
    private static void segVariant(List<BakeOffHarness.ScoreUnit> units, GutterFinder finder, int mode) {
        long m = 0, d = 0, g = 0;
        List<Double> per = new ArrayList<>();
        int totalHits = 0, countMatch = 0;
        for (BakeOffHarness.ScoreUnit u : units) {
            List<TableExtractor.TableHit> hits = new ArrayList<>();
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    List<TextPosition> glyphs;
                    try { glyphs = TableTestPdfs.harvestGlyphs(doc, i); } catch (Throwable t) { continue; }
                    hits.addAll(segPage(i + 1, glyphs, finder, mode));
                }
            } catch (Throwable t) { /* same as production: unreadable -> no hits */ }
            Sc s = score(u.expected(), hits);
            m += s.adjMatched(); d += s.adjDet(); g += s.adjGt();
            per.add(s.adjF1());
            totalHits += hits.size();
            if (hits.size() == u.expected().size()) countMatch++;
        }
        System.out.println(String.format(Locale.ROOT, "  %s micro=%s macro=%s hits=%d countMatchPdfs=%d",
                segLabel(mode), microF1(m, d, g), fmt(mean(per)), totalHits, countMatch));
    }

    private static List<TableExtractor.TableHit> segPage(int pageNum, List<TextPosition> glyphs,
                                                        GutterFinder finder, int mode) {
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
        List<WStyle> styled = styledWords(glyphs);
        IdentityHashMap<StreamTableExtractor.Word, WStyle> styleOfWord = new IdentityHashMap<>();
        if (styled.size() == words.size())
            for (int i = 0; i < words.size(); i++) styleOfWord.put(words.get(i), styled.get(i));

        float medianSpace = 0.5f * StreamTableExtractor.medianFontSize(words);
        List<List<StreamTableExtractor.Line>> blocks = StreamTableExtractor.splitIntoBlocks(lines);
        blocks = transformBlocks(blocks, styleOfWord, mode);

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
                TableExtractor.TableHit h = buildHit(pageNum, grid, prodVector(grid));
                if (h != null) hits.add(h);
            } catch (TableExtractor.RulingOverflowException e) {
                // skip this block only, as production does
            }
        }
        return hits;
    }

    private static List<List<StreamTableExtractor.Line>> transformBlocks(
            List<List<StreamTableExtractor.Line>> blocks,
            IdentityHashMap<StreamTableExtractor.Word, WStyle> styleOfWord, int mode) {
        if (mode == 0) return blocks;
        List<List<StreamTableExtractor.Line>> out = new ArrayList<>();
        if (mode == 1 || mode == 2) {
            for (List<StreamTableExtractor.Line> b : blocks) {
                List<StreamTableExtractor.Line> cur = new ArrayList<>();
                String prev = null;
                for (StreamTableExtractor.Line l : b) {
                    LineStyle ls = lineStyle(l, styleOfWord);
                    String key = mode == 1 ? ls.key : ls.weightKey;
                    if (prev != null && !key.equals(prev) && !cur.isEmpty()) { out.add(cur); cur = new ArrayList<>(); }
                    cur.add(l);
                    prev = key;
                }
                if (!cur.isEmpty()) out.add(cur);
            }
            return out;
        }
        // mode 3: merge adjacent blocks whose modal line-style key is equal
        List<StreamTableExtractor.Line> cur = null;
        String curKey = null;
        for (List<StreamTableExtractor.Line> b : blocks) {
            Map<String, Integer> mc = new HashMap<>();
            for (StreamTableExtractor.Line l : b) mc.merge(lineStyle(l, styleOfWord).key, 1, Integer::sum);
            String modal = null; int best = -1;
            for (Map.Entry<String, Integer> e : mc.entrySet())
                if (e.getValue() > best) { best = e.getValue(); modal = e.getKey(); }
            if (cur != null && modal != null && modal.equals(curKey)) { cur.addAll(b); }
            else { if (cur != null) out.add(cur); cur = new ArrayList<>(b); curKey = modal; }
        }
        if (cur != null) out.add(cur);
        return out;
    }

    private static List<java.nio.file.Path> proseSample() {
        java.nio.file.Path root = java.nio.file.Path.of("/home/coz/Downloads/phishpdfs");
        if (!java.nio.file.Files.isDirectory(root)) return null;
        List<java.nio.file.Path> all = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(root)) {
            s.filter(java.nio.file.Files::isRegularFile).sorted().forEach(all::add);
        } catch (Exception e) { return null; }
        List<java.nio.file.Path> pdfs = new ArrayList<>();
        for (java.nio.file.Path p : all) {
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(p)) {
                byte[] buf = new byte[5];
                int n = in.read(buf);
                if (n >= 4 && buf[0] == '%' && buf[1] == 'P' && buf[2] == 'D' && buf[3] == 'F') pdfs.add(p);
            } catch (Exception ignore) { }
        }
        if (pdfs.size() <= 200) return pdfs;
        int step = (int) Math.ceil(pdfs.size() / 200.0);
        List<java.nio.file.Path> sample = new ArrayList<>();
        for (int i = 0; i < pdfs.size() && sample.size() < 200; i += step) sample.add(pdfs.get(i));
        return sample;
    }

    private static boolean segHasHitOnPage1(java.nio.file.Path pdf, GutterFinder finder, int mode) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (doc.getNumberOfPages() < 1) return false;
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            return !segPage(1, glyphs, finder, mode).isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private static float medianPitch(BlockCtx b) {
        int n = b.grid.rows.size();
        if (n < 2) return 1f;
        float[] p = new float[n - 1];
        for (int i = 1; i < n; i++) p[i - 1] = b.grid.rows.get(i).yTop - b.grid.rows.get(i - 1).yTop;
        java.util.Arrays.sort(p);
        return Math.max(0.01f, p[p.length / 2]);
    }

    private static int popCols(BlockCtx b, int i) {
        Set<Integer> cols = new HashSet<>();
        for (StreamTableExtractor.Word w : b.grid.rows.get(i).words) cols.add(colOf(w.cx(), b.grid.colBounds));
        return cols.size();
    }

    private static boolean anchorPopulated(BlockCtx b, int i) {
        if (b.anchorCol < 0) return true;
        for (StreamTableExtractor.Word w : b.grid.rows.get(i).words)
            if (colOf(w.cx(), b.grid.colBounds) == b.anchorCol) return true;
        return false;
    }

    private static List<TableExtractor.TableHit> rebuildAll(PdfCtx c) {
        List<TableExtractor.TableHit> hits = new ArrayList<>();
        for (BlockCtx b : c.blocks) {
            TableExtractor.TableHit h = buildHit(b.pageNum, b.grid, b.curNewRow);
            if (h != null) hits.add(h);
        }
        return hits;
    }

    interface FlipFilter { boolean allowed(BlockCtx b, int i); }

    static final FlipFilter ANY = (b, i) -> true;
    /** style key differs from the previous physical line's -- the only trigger a "style
     *  discontinuity" rule could ever fire on. */
    static final FlipFilter STYLE_DIFF_PREV =
            (b, i) -> !b.lineStyles.get(i).key.equals(b.lineStyles.get(i - 1).key);
    /** coarser: weight/size register differs from the previous line (family ignored). */
    static final FlipFilter WEIGHT_DIFF_PREV =
            (b, i) -> !b.lineStyles.get(i).weightKey.equals(b.lineStyles.get(i - 1).weightKey);
    /** either line is in a different style register than the block's modal body style. */
    static final FlipFilter STYLE_DIFF_MODAL =
            (b, i) -> !b.lineStyles.get(i).key.equals(b.modalKey) || !b.lineStyles.get(i - 1).key.equals(b.modalKey);
    /** header region only: the production leading run (before the first anchor-populated line). */
    static final FlipFilter LEADING_RUN = (b, i) -> i <= b.firstAnchorIdx;
    /** first 4 physical lines of the block (a generous "header region"). */
    static final FlipFilter FIRST4 = (b, i) -> i < 4;

    /** Greedy per-line hill-climb on this PDF's adjacency F1. Leaves c.curNewRow at the optimum. */
    private static Sc hillClimb(PdfCtx c, FlipFilter filter) {
        for (BlockCtx b : c.blocks) b.curNewRow = b.prodNewRow.clone();
        Sc best = score(c.unit.expected(), rebuildAll(c));
        for (int pass = 0; pass < 4; pass++) {
            boolean improved = false;
            for (BlockCtx b : c.blocks) {
                for (int i = 1; i < b.curNewRow.length; i++) {
                    if (!filter.allowed(b, i)) continue;
                    b.curNewRow[i] = !b.curNewRow[i];
                    Sc cand = score(c.unit.expected(), rebuildAll(c));
                    if (cand.adjF1() > best.adjF1() + 1e-9) { best = cand; improved = true; }
                    else b.curNewRow[i] = !b.curNewRow[i];
                }
            }
            if (!improved) break;
        }
        return best;
    }

    /** Aggregate a restricted oracle over the whole corpus. */
    private static void restrictedOracle(List<PdfCtx> ctxs, String label, FlipFilter filter) {
        long m = 0, d = 0, g = 0; int flips = 0, improved = 0;
        List<Double> per = new ArrayList<>();
        for (PdfCtx c : ctxs) {
            Sc before = score(c.unit.expected(), rebuildAll(c));
            Sc after = hillClimb(c, filter);
            m += after.adjMatched(); d += after.adjDet(); g += after.adjGt();
            per.add(after.adjF1());
            if (after.adjF1() > before.adjF1() + 1e-9) improved++;
            for (BlockCtx b : c.blocks)
                for (int i = 1; i < b.prodNewRow.length; i++)
                    if (b.prodNewRow[i] != b.curNewRow[i]) flips++;
            for (BlockCtx b : c.blocks) b.curNewRow = b.prodNewRow.clone();   // reset
        }
        System.out.println(String.format(Locale.ROOT, "  %-46s micro=%s macro=%s flips=%d pdfsImproved=%d",
                label, microF1(m, d, g), fmt(mean(per)), flips, improved));
    }

    private static Map<String, Sc> RULE_BASELINE;

    /** A concrete (non-oracle) row-grouping rule, evaluated corpus-wide. */
    private static void rulePolicy(List<PdfCtx> ctxs, String label, java.util.function.BiFunction<BlockCtx, Integer, Boolean> newRowAt) {
        long m = 0, d = 0, g = 0;
        List<Double> per = new ArrayList<>();
        int improved = 0, regressed = 0, unchanged = 0;
        List<String> worst = new ArrayList<>();
        List<Object[]> gains = new ArrayList<>();
        for (PdfCtx c : ctxs) {
            List<TableExtractor.TableHit> hits = new ArrayList<>();
            for (BlockCtx b : c.blocks) {
                boolean[] v = new boolean[b.prodNewRow.length];
                v[0] = true;
                for (int i = 1; i < v.length; i++) v[i] = newRowAt.apply(b, i);
                TableExtractor.TableHit h = buildHit(b.pageNum, b.grid, v);
                if (h != null) hits.add(h);
            }
            Sc s = score(c.unit.expected(), hits);
            m += s.adjMatched(); d += s.adjDet(); g += s.adjGt();
            per.add(s.adjF1());
            if (RULE_BASELINE != null) {
                double base = RULE_BASELINE.get(c.unit.id()).adjF1();
                if (s.adjF1() > base + 1e-9) { improved++; gains.add(new Object[]{c.unit.id(), s.adjF1() - base}); }
                else if (s.adjF1() < base - 1e-9) { regressed++; worst.add(c.unit.id() + " " + fmt(s.adjF1() - base)); }
                else unchanged++;
            }
        }
        System.out.println(String.format(Locale.ROOT, "  %-46s micro=%s macro=%s  +%d/-%d/=%d", label,
                microF1(m, d, g), fmt(mean(per)), improved, regressed, unchanged));
        if (!worst.isEmpty() && worst.size() <= 8) System.out.println("      regressions: " + worst);
        if (!gains.isEmpty()) {
            gains.sort((a, b) -> Double.compare((Double) b[1], (Double) a[1]));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(5, gains.size()); i++)
                sb.append(shortId((String) gains.get(i)[0])).append(' ').append(fmt((Double) gains.get(i)[1])).append("  ");
            System.out.println("      top gains: " + sb);
        }
    }

    private static PdfCtx buildCtx(BakeOffHarness.ScoreUnit unit, GutterFinder finder) {
        PdfCtx ctx = new PdfCtx();
        ctx.unit = unit;
        try (PDDocument doc = Loader.loadPDF(unit.pdf().toFile())) {
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                List<TextPosition> glyphs;
                try { glyphs = TableTestPdfs.harvestGlyphs(doc, i); } catch (Throwable t) { continue; }
                ctx.prodHits.addAll(StreamTableExtractor.extractPage(i + 1, glyphs, finder));
                replicatePage(ctx, i + 1, glyphs, finder);
            }
        } catch (Throwable t) {
            ctx.replicaMatchesProduction = false;
            return ctx;
        }
        // replication check: same number of hits, same row grids
        List<TableExtractor.TableHit> replica = rebuildAll(ctx);
        if (replica.size() != ctx.prodHits.size()) ctx.replicaMatchesProduction = false;
        else for (int i = 0; i < replica.size(); i++)
            if (!String.valueOf(replica.get(i).rows).equals(String.valueOf(ctx.prodHits.get(i).rows)))
                ctx.replicaMatchesProduction = false;
        return ctx;
    }

    /** Replicates extractPage's own control flow, keeping the Grid + per-line style for each hit. */
    private static void replicatePage(PdfCtx ctx, int pageNum, List<TextPosition> glyphs, GutterFinder finder) {
        List<StreamTableExtractor.Word> words;
        List<StreamTableExtractor.Line> lines;
        try {
            words = StreamTableExtractor.buildWords(glyphs);
            if (words.size() < 6) return;
            float mfs = StreamTableExtractor.medianFontSize(words);
            lines = StreamTableExtractor.buildLines(words, mfs);
            if (lines.size() < 3) return;
        } catch (TableExtractor.RulingOverflowException e) {
            return;
        }
        List<WStyle> styled = styledWords(glyphs);
        IdentityHashMap<StreamTableExtractor.Word, WStyle> styleOfWord = new IdentityHashMap<>();
        if (styled.size() == words.size()) {
            for (int i = 0; i < words.size(); i++) {
                if (!styled.get(i).text.equals(words.get(i).text)) ctx.replicaMatchesProduction = false;
                styleOfWord.put(words.get(i), styled.get(i));
            }
        } else {
            ctx.replicaMatchesProduction = false;
        }

        float medianSpace = 0.5f * StreamTableExtractor.medianFontSize(words);
        List<List<StreamTableExtractor.Line>> blocks = StreamTableExtractor.splitIntoBlocks(lines);
        long pageWork = 0;
        int hitsThisPage = 0;
        for (List<StreamTableExtractor.Line> block : blocks) {
            if (ctx.blocks.size() >= 10_000) break;
            if (hitsThisPage >= StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE) break;
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

                BlockCtx b = new BlockCtx();
                b.pageNum = pageNum;
                b.grid = grid;
                b.anchorCol = StreamTableExtractor.findAnchorColumn(grid.rows, grid.colBounds);
                b.prodNewRow = prodVector(grid);
                b.curNewRow = b.prodNewRow.clone();
                for (StreamTableExtractor.Line l : grid.rows) b.lineStyles.add(lineStyle(l, styleOfWord));
                b.firstAnchorIdx = grid.rows.size();
                for (int i = 0; i < grid.rows.size(); i++)
                    if (anchorPopulated(b, i)) { b.firstAnchorIdx = i; break; }
                Map<String, Integer> mc = new HashMap<>();
                for (int i = 1; i < b.lineStyles.size(); i++) mc.merge(b.lineStyles.get(i).key, 1, Integer::sum);
                int bestC = -1;
                for (Map.Entry<String, Integer> e : mc.entrySet())
                    if (e.getValue() > bestC) { bestC = e.getValue(); b.modalKey = e.getKey(); }
                if (b.modalKey == null) b.modalKey = b.lineStyles.get(0).key;
                if (buildHit(pageNum, grid, b.prodNewRow) == null) continue;   // matches buildHit==null skip
                ctx.blocks.add(b);
                hitsThisPage++;
            } catch (TableExtractor.RulingOverflowException e) {
                // same as production: skip this block only
            }
        }
    }

    private static boolean[] prodVector(StreamTableExtractor.Grid grid) {
        List<List<StreamTableExtractor.Line>> groups =
                StreamTableExtractor.groupLogicalRows(grid.rows, grid.colBounds);
        boolean[] v = new boolean[grid.rows.size()];
        int idx = 0;
        for (List<StreamTableExtractor.Line> g : groups) {
            v[idx] = true;
            idx += g.size();
        }
        return v;
    }

    private static LineStyle lineStyle(StreamTableExtractor.Line l,
                                       IdentityHashMap<StreamTableExtractor.Word, WStyle> map) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Style> byKey = new HashMap<>();
        int chars = 0, bold = 0;
        LineStyle ls = new LineStyle();
        for (StreamTableExtractor.Word w : l.words) {
            WStyle ws = map.get(w);
            if (ws == null || ws.dominant == null) continue;
            counts.merge(ws.dominant.key(), ws.chars, Integer::sum);
            byKey.putIfAbsent(ws.dominant.key(), ws.dominant);
            chars += ws.chars;
            bold += ws.boldChars;
            ls.allKeys.add(ws.dominant.key());
            if ("?".equals(ls.firstWordKey)) ls.firstWordKey = ws.dominant.key();
        }
        String bestKey = "?@0.0"; int best = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet())
            if (e.getValue() > best) { best = e.getValue(); bestKey = e.getKey(); }
        ls.key = bestKey;
        Style st = byKey.get(bestKey);
        ls.weightKey = st == null ? "?" : st.weightKey();
        ls.size = st == null ? 0 : st.size();
        ls.bold = st != null && st.bold();
        ls.boldFrac = chars == 0 ? 0 : (double) bold / chars;
        return ls;
    }

    private static String microF1(long m, long d, long g) {
        double p = d == 0 ? 0 : (double) m / d;
        double r = g == 0 ? 0 : (double) m / g;
        return fmt(m == 0 ? 0 : 2 * p * r / (p + r));
    }

    private static double mean(List<Double> xs) {
        if (xs.isEmpty()) return 0;
        double s = 0; for (double x : xs) s += x; return s / xs.size();
    }

    private static String shortId(String id) {
        int i = id.lastIndexOf('/');
        return i < 0 ? id : id.substring(i + 1);
    }

    private static String fmt(double v) { return String.format(Locale.ROOT, "%.4f", v); }

    private static String pct(int a, int b) {
        return b == 0 ? "n/a" : String.format(Locale.ROOT, "%.1f%%", 100.0 * a / b);
    }
}
