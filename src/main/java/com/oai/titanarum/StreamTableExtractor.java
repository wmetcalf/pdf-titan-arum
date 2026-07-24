package com.oai.titanarum;

import org.apache.pdfbox.text.TextPosition;
import java.util.*;

/**
 * Borderless ("stream") table extraction: real glyph positions, no rulings, no tags.
 * Detect-then-extract with a hard gridness gate. Precision over recall; every stage is
 * work-budgeted and aborts (throwing {@link TableExtractor.RulingOverflowException}) rather
 * than degrade. See docs/superpowers/specs/2026-07-24-borderless-stream-table-extraction-design.md.
 */
final class StreamTableExtractor {

    static final int MAX_STREAM_GLYPHS = 300_000;
    static final int MAX_STREAM_WORDS  = 60_000;
    static final int MAX_STREAM_LINES  = 8_000;

    private StreamTableExtractor() {}

    static final class Word {
        float x0, y0, x1, y1;   // top-left-origin points
        String text;
        boolean numeric;
        float width()  { return x1 - x0; }
        float height() { return y1 - y0; }
        float cx()     { return (x0 + x1) * 0.5f; }
    }

    static final class Line {
        float yTop, yBot;
        final List<Word> words = new ArrayList<>();
    }

    static void enforceGlyphCap(int n) {
        if (n > MAX_STREAM_GLYPHS) throw new TableExtractor.RulingOverflowException();
    }

    static float medianFontSize(List<Word> words) {
        if (words.isEmpty()) return 12f;
        float[] h = new float[words.size()];
        for (int i = 0; i < h.length; i++) h[i] = Math.max(1f, words.get(i).height());
        Arrays.sort(h);
        return h[h.length / 2];
    }

    static List<Word> buildWords(List<TextPosition> glyphs) {
        enforceGlyphCap(glyphs.size());
        List<Word> out = new ArrayList<>();
        Word cur = null;
        float prevX1 = 0, prevBaseline = 0;
        for (TextPosition tp : glyphs) {
            String u = tp.getUnicode();
            if (u == null || u.isEmpty()) continue;
            float gx0 = tp.getXDirAdj();
            float gy0 = tp.getYDirAdj();                 // top of glyph, top-left origin
            float gh  = Math.max(1f, tp.getHeightDir());
            float gw  = tp.getWidthDirAdj();
            float fs  = Math.max(1f, tp.getFontSizeInPt() * tp.getTextMatrix().getScalingFactorY());
            float space = Math.max(tp.getWidthOfSpace(), 0.25f * fs);
            boolean whitespace = u.trim().isEmpty();
            boolean newLine = cur != null && Math.abs(gy0 - prevBaseline) > 0.5f * fs;
            boolean gap     = cur != null && (gx0 - prevX1) > 0.30f * space;
            if (cur == null || whitespace || newLine || gap) {
                if (cur != null) {
                    finishWord(cur, out);
                    if (out.size() > MAX_STREAM_WORDS) throw new TableExtractor.RulingOverflowException();
                }
                if (whitespace) { cur = null; continue; }
                cur = new Word();
                cur.x0 = gx0; cur.y0 = gy0; cur.x1 = gx0 + gw; cur.y1 = gy0 + gh;
                cur.text = u;
            } else {
                cur.x1 = gx0 + gw;
                cur.y0 = Math.min(cur.y0, gy0);
                cur.y1 = Math.max(cur.y1, gy0 + gh);
                cur.text += u;
            }
            prevX1 = gx0 + gw; prevBaseline = gy0;
        }
        if (cur != null) {
            finishWord(cur, out);
            if (out.size() > MAX_STREAM_WORDS) throw new TableExtractor.RulingOverflowException();
        }
        return out;
    }

    private static void finishWord(Word w, List<Word> out) {
        w.text = w.text.trim();
        if (w.text.isEmpty()) return;
        w.numeric = w.text.matches("[-+(]?[\\d.,%$)]+") && w.text.chars().anyMatch(Character::isDigit);
        out.add(w);
    }

    static List<Line> buildLines(List<Word> words, float medianFontSize) {
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingDouble(a -> a.y0));
        List<Line> lines = new ArrayList<>();
        for (Word w : sorted) {
            Line target = null;
            for (int i = lines.size() - 1; i >= 0 && i >= lines.size() - 3; i--) { // recent lines only
                Line ln = lines.get(i);
                float ov = Math.min(w.y1, ln.yBot) - Math.max(w.y0, ln.yTop);
                float minH = Math.min(w.height(), ln.yBot - ln.yTop);
                if (ov > 0.4f * Math.max(1f, minH)) { target = ln; break; }
            }
            if (target == null) {
                target = new Line(); target.yTop = w.y0; target.yBot = w.y1;
                lines.add(target);
                if (lines.size() > MAX_STREAM_LINES) throw new TableExtractor.RulingOverflowException();
            } else {
                target.yTop = Math.min(target.yTop, w.y0);
                target.yBot = Math.max(target.yBot, w.y1);
            }
            target.words.add(w);
        }
        lines.sort(Comparator.comparingDouble(l -> l.yTop));
        for (Line l : lines) l.words.sort(Comparator.comparingDouble(a -> a.x0));
        return lines;
    }

    static final int  MAX_GUTTER_CANDIDATES = 16;
    // Bounds the REAL cost of the branch-and-bound below, not the pop count: every surviving
    // pop runs an O(obstacles) linear scan (firstObstacleInside), so the true cost per pop is
    // obstacles.size(), and that's what gets charged.
    //
    // Retuned from an earlier 20,000,000. The redundant-re-derivation fix below (coveredByAccepted
    // + duplicatesSkipped) removes the wasted O(obstacles) SCAN for a duplicate gutter re-
    // derivation, but a duplicate is still correctly counted against MAX_GUTTER_CANDIDATES (see
    // that field's doc), so tables whose search legitimately needs to walk through more
    // candidates before settling (e.g. real cell-width variance across hundreds of rows, or
    // gutters narrower than the 3x-median-space quality cap needing several row-peeled
    // fragments before the widest-row boundary dominates) still consume real, charged pops.
    // 500,000,000 gives multiple-hundred-ms headroom for such legitimate large/narrow-gutter
    // tables (measured: a 150-row x 6-col table with a gutter *below* the quality cap, previously
    // throwing at the old budget, now completes in ~70ms) while an adversarial page near
    // MAX_STREAM_WORDS (no row ever leaves a gutter clean, so nothing is ever a duplicate or
    // accepted) still aborts in a few thousand pops -- a few ms of CPU, since each pop is a cheap
    // O(obstacles) scan and the budget directly caps pop count at budget/obstacles regardless.
    static final long MAX_GUTTER_SCAN_WORK  = 500_000_000;

    static final class Gutter {
        float x0, x1;
        int rowsCovered;
        float cx() { return (x0 + x1) * 0.5f; }
    }

    private static final class Rect {
        float x0, y0, x1, y1;
        Rect(float a, float b, float c, float d) { x0=a; y0=b; x1=c; y1=d; }
        float w() { return x1 - x0; }
        float h() { return y1 - y0; }
    }

    static List<Gutter> findGutters(List<Line> lines, float bandX0, float bandX1, float medianSpace) {
        if (lines.isEmpty() || bandX1 - bandX0 <= 0) return List.of();
        float yTop = Float.MAX_VALUE, yBot = -Float.MAX_VALUE;
        List<float[]> obstacles = new ArrayList<>(); // {x0,y0,x1,y1}
        for (Line l : lines) {
            yTop = Math.min(yTop, l.yTop); yBot = Math.max(yBot, l.yBot);
            for (Word w : l.words) obstacles.add(new float[]{w.x0, w.y0, w.x1, w.y1});
        }
        final float bandH = yBot - yTop;
        final float minGutterW = Math.max(medianSpace, 1f);
        final float minCover = 0.60f * lines.size();

        // best-first branch & bound
        PriorityQueue<Rect> pq = new PriorityQueue<>(
            (a, b) -> Float.compare(quality(b, medianSpace), quality(a, medianSpace)));
        pq.add(new Rect(bandX0, yTop, bandX1, yBot));
        List<Rect> accepted = new ArrayList<>();
        // Any candidate fully CONTAINED (x AND y, within a small float-slop epsilon) by an
        // already-accepted rect is provably empty too -- a sub-rectangle of a region already
        // verified obstacle-free is itself obstacle-free, no re-scan needed. Without this, the
        // search re-derives the SAME already-known gutter over and over: e.g. a full-height
        // gutter gets accepted once, but the above/below splits of unrelated sibling rects
        // (peeling the table one row at a time off a full-width "remainder") keep re-isolating
        // that identical x-interval at ever-shrinking y-ranges, each a strict subset of the
        // rect already accepted for it. Every such re-derivation burns a real O(obstacles) scan
        // for zero new information; skipping it outright (not just deprioritizing) removes
        // that wasted scan cost.
        //
        // A detected duplicate still counts against MAX_GUTTER_CANDIDATES via duplicatesSkipped
        // (the loop bound below), rather than being invisible to it: this preserves the search's
        // existing termination behavior for ordinary tables with natural per-row width variance
        // (different digit counts etc.), where a handful of near-boundary duplicates are a
        // normal, expected part of converging on the true (widest-row) safe gutter -- NOT
        // counting them (i.e. only skipping the wasted scan without also bounding the search by
        // it) was measured to make the search run substantially longer per candidate on such
        // ordinary tables and reintroduce exactly the completion regression this fix targets.
        int duplicatesSkipped = 0;
        long work = 0;
        while (!pq.isEmpty() && accepted.size() + duplicatesSkipped < MAX_GUTTER_CANDIDATES) {
            Rect r = pq.poll();
            if (r.w() < minGutterW || r.h() < 0.60f * bandH) continue;
            if (coveredByAccepted(r, accepted)) { duplicatesSkipped++; continue; }
            // Real cost is the O(obstacles) linear scan below, run once per surviving pop --
            // charge that (not the pop itself) so the budget bounds actual CPU.
            work += obstacles.size();
            if (work > MAX_GUTTER_SCAN_WORK) throw new TableExtractor.RulingOverflowException();
            float[] pivot = firstObstacleInside(r, obstacles);
            if (pivot == null) {
                accepted.add(r);                       // maximal empty & tall enough
                continue;
            }
            // split into up-to-4 maximal sub-rects excluding the pivot. The pivot only
            // OVERLAPS r (it need not be contained by it), so clamp the above/below
            // children's y-range to the parent's -- otherwise pivot[1]/pivot[3] can land
            // outside [r.y0, r.y1] and produce an inverted (negative-height) rect.
            if (pivot[0] - r.x0 >= minGutterW) pq.add(new Rect(r.x0, r.y0, pivot[0], r.y1)); // left
            if (r.x1 - pivot[2] >= minGutterW) pq.add(new Rect(pivot[2], r.y0, r.x1, r.y1)); // right
            float aboveY1 = Math.min(r.y1, Math.max(r.y0, pivot[1]));
            float belowY0 = Math.max(r.y0, Math.min(r.y1, pivot[3]));
            pq.add(new Rect(r.x0, r.y0, r.x1, aboveY1)); // above, clamped
            pq.add(new Rect(r.x0, belowY0, r.x1, r.y1)); // below, clamped
        }
        // Merge x-overlapping accepted rects FIRST (union x-extent, accumulate the set of
        // rows each merged group covers), THEN apply the row-coverage threshold to the
        // merged total. The branch-and-bound above can split a genuine full-height gutter
        // into several vertically-fragmented rects (e.g. one stray glyph poking into the
        // gutter for a handful of rows forces an above/below split); each fragment alone can
        // fail minCover while their union covers plenty of rows. Filtering per-fragment
        // before merging would silently drop such (especially narrow) gutters.
        accepted.sort(Comparator.comparingDouble(a -> a.x0));
        List<float[]> merged = new ArrayList<>();  // {x0, x1}
        List<BitSet> mergedRows = new ArrayList<>();
        for (Rect r : accepted) {
            BitSet rows = new BitSet(lines.size());
            for (int i = 0; i < lines.size(); i++) {
                Line l = lines.get(i);
                if (l.yTop < r.y1 && l.yBot > r.y0) rows.set(i);
            }
            if (!merged.isEmpty() && r.x0 <= merged.get(merged.size() - 1)[1]) { // overlaps previous -> merge
                float[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], r.x1);
                mergedRows.get(mergedRows.size() - 1).or(rows);
            } else {
                merged.add(new float[]{r.x0, r.x1});
                mergedRows.add(rows);
            }
        }
        List<Gutter> gutters = new ArrayList<>();
        for (int i = 0; i < merged.size(); i++) {
            float[] m = merged.get(i);
            int cover = mergedRows.get(i).cardinality();
            if (cover < minCover) continue;
            if (m[0] <= bandX0 + 0.5f || m[1] >= bandX1 - 0.5f) continue; // edge margin, not interior
            Gutter g = new Gutter(); g.x0 = m[0]; g.x1 = m[1]; g.rowsCovered = cover;
            gutters.add(g);
        }
        return gutters;
    }

    private static float quality(Rect r, float medianSpace) {
        return r.h() * Math.min(r.w(), 3f * medianSpace);
    }

    private static float[] firstObstacleInside(Rect r, List<float[]> obstacles) {
        for (float[] o : obstacles) {
            if (o[0] < r.x1 && o[2] > r.x0 && o[1] < r.y1 && o[3] > r.y0) return o;
        }
        return null;
    }

    private static final float RESOLVED_EPS = 0.01f;

    /** True if r lies entirely inside (within a small float-slop epsilon of, in BOTH x and y)
     * some already-accepted rect. Any such r is a sub-rectangle of a region already verified
     * obstacle-free, so it is necessarily obstacle-free itself -- re-scanning it can never
     * find a pivot, and it is safe to prune outright rather than merely deprioritize. */
    private static boolean coveredByAccepted(Rect r, List<Rect> accepted) {
        for (Rect g : accepted) {
            if (r.x0 >= g.x0 - RESOLVED_EPS && r.x1 <= g.x1 + RESOLVED_EPS
                && r.y0 >= g.y0 - RESOLVED_EPS && r.y1 <= g.y1 + RESOLVED_EPS) return true;
        }
        return false;
    }

    static final double STREAM_CONFIDENCE_MIN = 0.55;

    static final class Grid {
        List<Gutter> gutters;
        List<Line> rows;
        float[] colBounds;          // length cols+1: [bandX0, g0.cx, g1.cx, ..., bandX1]
        double confidence;
        boolean numericLeanColumn;
    }

    static Grid scoreGrid(List<Line> lines, List<Gutter> gutters, float bandX0, float bandX1) {
        Grid grid = new Grid();
        grid.gutters = gutters; grid.rows = lines;
        int cols = gutters.size() + 1;
        int rows = lines.size();
        float[] bounds = new float[cols + 1];
        bounds[0] = bandX0; bounds[cols] = bandX1;
        for (int i = 0; i < gutters.size(); i++) bounds[i + 1] = gutters.get(i).cx();
        grid.colBounds = bounds;
        if (cols < 2 || rows < 3) { grid.confidence = 0; return grid; }

        // gutter violations
        long words = 0, viol = 0;
        for (Line l : lines) for (Word w : l.words) {
            words++;
            for (Gutter g : gutters) if (w.x0 < g.cx() && w.x1 > g.cx()) { viol++; break; }
        }
        double violation = words == 0 ? 1 : (double) viol / words;
        double violationScore = 1 - Math.min(1, violation / 0.05);

        // column consistency: rows with exactly one word-cluster per column and no straddle
        int consistentRows = 0;
        for (Line l : lines) {
            int[] perCol = new int[cols];
            boolean straddle = false;
            for (Word w : l.words) {
                int c = colOf(w.cx(), bounds);
                perCol[c]++;
                for (Gutter g : gutters) if (w.x0 < g.cx() && w.x1 > g.cx()) straddle = true;
            }
            int filled = 0; for (int p : perCol) if (p >= 1) filled++;
            if (!straddle && filled >= Math.max(2, cols - 1)) consistentRows++;
        }
        double colConsistency = (double) consistentRows / rows;
        colConsistency = Math.min(1, colConsistency / 0.85); // full credit at 85%

        // prose fill: median widest-cell fill fraction of its column width
        List<Double> fills = new ArrayList<>();
        for (Line l : lines) {
            double maxFill = 0;
            for (Word w : l.words) {
                int c = colOf(w.cx(), bounds);
                float colW = bounds[c + 1] - bounds[c];
                if (colW > 0) maxFill = Math.max(maxFill, w.width() / colW);
            }
            fills.add(maxFill);
        }
        Collections.sort(fills);
        double medianFill = fills.get(fills.size() / 2);
        double proseScore = clamp01((0.85 - medianFill) / 0.25);

        // numeric lean: fraction of interior columns that are mostly numeric
        int numericCols = 0;
        for (int c = 1; c < cols; c++) {               // interior columns (skip col 0 = labels)
            int tot = 0, num = 0;
            for (Line l : lines) for (Word w : l.words) if (colOf(w.cx(), bounds) == c) {
                tot++; if (w.numeric) num++;
            }
            if (tot > 0 && (double) num / tot >= 0.70) numericCols++;
        }
        double numericBonus = cols > 1 ? (double) numericCols / (cols - 1) : 0;
        grid.numericLeanColumn = numericCols > 0;

        if (cols == 2 && numericBonus == 0) { grid.confidence = 0; return grid; }

        grid.confidence = 0.30 * colConsistency
                        + 0.25 * violationScore
                        + 0.20 * proseScore
                        + 0.15 * Math.min(1, (cols - 2) / 2.0)
                        + 0.10 * numericBonus;
        return grid;
    }

    private static int colOf(float x, float[] bounds) {
        for (int c = 0; c < bounds.length - 1; c++) if (x < bounds[c + 1]) return c;
        return bounds.length - 2;
    }

    private static double clamp01(double v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
}
