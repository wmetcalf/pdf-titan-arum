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
            // getTextMatrix() is (per its own javadoc) NOT the raw "Tm" operator matrix despite the
            // name -- it's the full effective text-RENDERING matrix (Trm = Tfs-scaled Tm x CTM), so
            // its Y scaling factor alone IS the correct device-space font size: it already folds in
            // the font size, the "Tm" operator's own scale, AND the "cm" operator's (CTM) scale.
            // getFontSizeInPt(), by contrast, is CTM-BLIND: per ITS OWN javadoc it's computed from
            // fontSize and the Tm-only matrix, and "the actual rendering may appear bigger or
            // smaller depending on the [cm] transformation matrix" -- i.e. any cm scale is NOT
            // folded in. A prior version of this line multiplied the two together
            // (getFontSizeInPt() * getTextMatrix().getScalingFactorY()), which double-counts the
            // font size for any genuinely-PDFBox-parsed TextPosition (e.g. fs=121 for an 11pt font,
            // instead of 11) -- inflating the newLine threshold (0.5*fs) enough that glyphs a full
            // row apart no longer triggered a line break, silently merging adjacent rows' words into
            // one. Dropping the multiplication (using getFontSizeInPt() alone) fixed that but left
            // this CTM-blind gap: for text drawn under a scaling `cm` (scaled form XObjects,
            // watermarks, embedded scaled content), getFontSizeInPt() omits the cm scale entirely,
            // so the newLine threshold is wrong -- unlike `space` below, which is floored against
            // the ctm-aware getWidthOfSpace(), newLine has no such floor. Using
            // getTextMatrix().getScalingFactorY() alone is correct in both the scaled and unscaled
            // case (see StreamWordLineTest.buildWordsHandlesCtmScaledText).
            float fs  = Math.max(1f, tp.getTextMatrix().getScalingFactorY());
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

    // Task 9e: raised from 16. A table needing K interior gutters needs K accepted rects, but
    // (per the coveredByAccepted doc below) a normal table with real per-row cell-width variance
    // legitimately produces MANY duplicate re-derivations of an already-known gutter before the
    // search moves on to the next one -- e.g. an 11-column dense numeric grid (10 interior
    // gutters, ~1.3x-medianSpace gutter width, the us-018 ICDAR shape) measured ~1,600 duplicates
    // PER accepted gutter even after the firstObstacleInside pivot fix below, i.e. ~16,000 total
    // before all 10 are found. 16 was nowhere near enough headroom for double-digit column
    // counts: it was exhausted by duplicates alone before a SECOND gutter could ever be accepted,
    // which is the direct mechanism behind the measured under-split (us-018: 2 gutters found vs.
    // 11 ground-truth columns). 20,000 gives ~25% headroom above the measured worst case while
    // MAX_GUTTER_SCAN_WORK (charged, not this count) remains the real DoS backstop -- see its own
    // doc for why raising this alone does not weaken that bound.
    static final int  MAX_GUTTER_CANDIDATES = 20_000;
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

    // Task 9e root-cause fix. The ORIGINAL version of this method returned the FIRST obstacle
    // found in obstacles' insertion order (line-by-line, i.e. row-major, top-to-bottom). That
    // pivot choice is what made the search on a dense many-row/many-column table (e.g. the
    // us-018 ICDAR shape: 11 columns, 40+ rows) combinatorially explode: splitting on a row-major
    // "first" obstacle only ever excludes ONE row's worth of y-extent per split (a word obstacle
    // is never taller than one row), so the "right" and "below" children of every pop stay nearly
    // full-width/full-height and have to be peeled again one row at a time. Verified directly
    // (temporarily instrumented, see task-9e report): an 11-col x 40-row dense numeric grid with
    // realistic per-row cell-width jitter NEVER accepted a single gutter -- accepted=0,
    // duplicatesSkipped=0, work=50,000,000,160 (100x the production MAX_GUTTER_SCAN_WORK) with
    // 326 MILLION rects still queued -- i.e. genuine unbounded exponential blowup, not merely
    // "narrow gutters lose the quality race" as originally hypothesized.
    //
    // The fix: among the obstacles that actually overlap r, pick the one whose CENTER (clamped
    // to r) is CLOSEST to r's own x-center, instead of the first found. This turns horizontal
    // narrowing into an approximately-balanced bisection (isolate each column's x-range in
    // ~O(log cols) splits instead of ~O(cols) row-major peeling), which collapses the
    // combinatorial blowup: the same 11x40 fixture went from never converging to accepted=11
    // (10 interior + the implicit outer margin), work=10,379,600, in 38ms -- comfortably inside
    // the existing MAX_GUTTER_SCAN_WORK budget. Cost per pop is unchanged (still a single
    // O(obstacles) linear scan, so the existing work-charging above remains accurate); only
    // WHICH obstacle gets returned changes, so this is a pure quality-of-search improvement, not
    // a new cost.
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

    // See the rationale comment at the violationScore computation in scoreGrid(): a small flat
    // plateau before the penalty ramp starts, tolerating the occasional straddling word that a
    // real wrapped-cell table produces without disqualifying it, while still ramping heavily
    // straddling ("really is prose") grids down to zero violationScore by the ceiling.
    static final double VIOLATION_TOLERANCE = 0.02;  // <=2% straddling words: no penalty
    static final double VIOLATION_CEILING   = 0.09;  // >=9% straddling words: violationScore = 0

    // Prose hard-veto constants. See the rationale comment at the veto site in scoreGrid().
    // VETO_FILL_THRESHOLD mirrors the spec's own wording ("fills >~85% of column width").
    static final double VETO_FILL_THRESHOLD          = 0.85;
    // A column counts as "prose-like" only if MORE than half of its own occupied rows hit
    // the fill threshold -- an occasional long cell (real wrapped-cell tables have these)
    // must not be enough to brand the whole column as running prose.
    static final double VETO_ROW_MAJORITY_FRACTION    = 0.50;
    // The veto fires only if MORE than half of all columns are prose-like -- a single
    // wide/wrapping column (e.g. a long-text "Result" column in an otherwise short-celled
    // table) must not be enough to veto the whole grid.
    static final double VETO_COLUMN_MAJORITY_FRACTION = 0.50;

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
        // A real wrapped-cell table (e.g. one very long token that overruns its column and
        // pokes across the neighboring gutter) can have an OCCASIONAL straddling word without
        // being prose -- straddling is a rendering artifact of long content, not proof the grid
        // is fake. Without tolerance, a single straddler in a ~24-word table (violation ~0.04)
        // already costs ~0.21 confidence off a term weighted at 0.25, enough to sink a
        // legitimate table that sits thin above STREAM_CONFIDENCE_MIN. So: no penalty at all up
        // to VIOLATION_TOLERANCE (occasional straddles are normal), then ramp down to zero by
        // VIOLATION_CEILING (heavy straddling -- most rows crossing gutters -- really is prose
        // or a bad column split and should still be punished hard).
        double violationScore;
        if (violation <= VIOLATION_TOLERANCE) {
            violationScore = 1;
        } else {
            violationScore = 1 - Math.min(1,
                (violation - VIOLATION_TOLERANCE) / (VIOLATION_CEILING - VIOLATION_TOLERANCE));
        }

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

        // numeric lean: fraction of ALL columns that are mostly numeric. The spec never says
        // which side holds the numbers -- a 2-column numeric table with the numbers in column
        // 0 and labels in column 1 is just as real as the reverse. Scanning every column (not
        // just c=1..cols-1) means the cols==2 gate below passes regardless of which column is
        // numeric-leaning.
        int numericCols = 0;
        for (int c = 0; c < cols; c++) {
            int tot = 0, num = 0;
            for (Line l : lines) for (Word w : l.words) if (colOf(w.cx(), bounds) == c) {
                tot++; if (w.numeric) num++;
            }
            if (tot > 0 && (double) num / tot >= 0.70) numericCols++;
        }
        double numericBonus = cols > 0 ? (double) numericCols / cols : 0;
        grid.numericLeanColumn = numericCols > 0;

        if (cols == 2 && numericBonus == 0) { grid.confidence = 0; return grid; }

        // Prose hard veto (spec: "reject if median cell text fills >~85% of column width
        // and wraps across lines -- the two-column-prose signature"). The graded proseScore
        // term above is NOT sufficient on its own to enforce this: for a perfectly
        // row-aligned 3+-column block with zero gutter violations, colConsistency +
        // violationScore + the column-count bonus alone already sum to 0.625 (>
        // STREAM_CONFIDENCE_MIN=0.55), so well-aligned multi-column prose clears the gate
        // no matter how prose-like its content is, before proseScore is ever applied. The
        // spec's condition list is an ALL-must-hold gate, so the prose signature must be a
        // hard veto (confidence=0), exactly like the cols<2 / rows<3 / cols==2-non-numeric
        // gates above -- in ADDITION to keeping proseScore as a graded term for borderline
        // prose-ish content that doesn't trip the veto.
        //
        // The veto must not fire on a real table that merely has ONE long wrapped cell
        // (e.g. Animal|Action|Result with an outlier long token/wrapped phrase in one or
        // two rows): such a table still has SHORT cells in its other columns and in the
        // rest of that same column's rows. The discriminator (per the design research) is
        // that genuine multi-column PROSE fills its column AND wraps on the MAJORITY of
        // *rows within that column* for a MAJORITY of *columns* -- an independent running
        // paragraph in every column, advancing one (near-full-width) line per row, on
        // nearly every row. A real wrapped-cell table instead has a MINORITY of rows in
        // any given column that are wide/wrapped; the rest of that column's rows (and the
        // sibling columns) stay short. So: per column, compute the fraction of that
        // column's OWN occupied rows whose text spans > VETO_FILL_THRESHOLD of the column
        // width (this doubles as the "wraps" signal too -- a column whose text keeps
        // re-filling the full width on row after row is exactly a paragraph re-wrapping
        // down the page, whether realized as one wide word/line per row or as an extra
        // continuation line with no sibling columns populated). A column counts as
        // "prose-like" only if that fraction exceeds VETO_ROW_MAJORITY_FRACTION (i.e. it's
        // the column's *predominant* behavior, not an occasional long cell). The veto then
        // fires only if a MAJORITY of columns (> VETO_COLUMN_MAJORITY_FRACTION) are
        // prose-like AND no column is numeric-leaning (a numeric column is conclusive
        // proof this is a data table, not prose, regardless of adjacent wide text columns).
        int proseColumns = 0;
        for (int c = 0; c < cols; c++) {
            int occupiedLines = 0, highFillLines = 0;
            float colW = bounds[c + 1] - bounds[c];
            for (Line l : lines) {
                float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
                boolean any = false;
                for (Word w : l.words) {
                    if (colOf(w.cx(), bounds) == c) {
                        any = true;
                        minX = Math.min(minX, w.x0);
                        maxX = Math.max(maxX, w.x1);
                    }
                }
                if (any) {
                    occupiedLines++;
                    if (colW > 0 && (maxX - minX) / colW > VETO_FILL_THRESHOLD) highFillLines++;
                }
            }
            double colFillFrac = occupiedLines > 0 ? (double) highFillLines / occupiedLines : 0;
            if (colFillFrac > VETO_ROW_MAJORITY_FRACTION) proseColumns++;
        }
        double proseColumnFraction = cols > 0 ? (double) proseColumns / cols : 0;
        if (proseColumnFraction > VETO_COLUMN_MAJORITY_FRACTION && numericCols == 0) {
            grid.confidence = 0;
            return grid;
        }

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

    static final int MAX_STREAM_TABLES_PER_PAGE = 20;

    // ---------------------------------------------------------------------- region segmentation
    //
    // Task 9d: extractPage used to feed EVERY line on the page into one findGutters/scoreGrid/
    // buildHit call, treating the whole page as a single candidate table. Measured consequence
    // (see .superpowers/sdd/task-9c-diagnosis-report.md): the page's title/running-header/
    // caption lines sit ABOVE the table's own y-range but get pooled in as "row 0" anyway (e.g.
    // us-020 produced row 0 = the running header "HIGHLIGHTS FROM PIRLS 2011", confidence 0.98),
    // which -- because TableScore matches cells by exact (row, col, text) -- shifts every
    // subsequent row index and alone explains F1~=0. A second, independent failure: pooling an
    // entire page's prose+multiple-small-tables into one findGutters call can blow
    // MAX_GUTTER_SCAN_WORK, and the resulting RulingOverflowException used to abort the WHOLE
    // page (see the old catch-clause below), silently losing every real table on it (eu-001).
    //
    // The fix below splits the page's lines into candidate blocks by vertical gap (Step A),
    // detects a table independently within each sufficiently-large block (Step B, capped at
    // MAX_STREAM_TABLES_PER_PAGE hits and a per-page work budget), and trims non-conforming
    // leading/trailing lines (title/footnote glued to a block with too small a gap to be split
    // by Step A alone) off each block before final scoring (Step C).

    // Step A: split the page's line list wherever the gap to the next line exceeds this factor
    // times the MEDIAN line-to-line pitch. Rationale for 1.6: real intra-table/intra-paragraph
    // row pitch is fairly uniform (typically <=1.3x variation even across wrapped multi-line
    // rows/headers -- see task-9c's spot checks against pdftotext -layout), while a genuine
    // paragraph-to-table (or table-to-footnote) transition typically inserts at least a
    // half-to-full blank-line's worth of extra leading, i.e. comfortably >=2x the body pitch.
    // 1.6 sits between those two regimes: high enough that normal within-block pitch jitter
    // never triggers a spurious split, low enough to catch genuine section breaks reliably.
    // Deliberately does NOT rely on catching every title/footnote this way, though (many
    // real-world report pages use uniform single-spacing throughout, so the gap above a title
    // can be smaller than this threshold) -- that residual case is exactly what Step C's
    // edge-trimming exists to clean up within a single surviving block.
    static final float BLOCK_GAP_FACTOR = 1.6f;

    // Step C: cap non-conforming-edge trimming at this many lines per end (front/back each),
    // so a pathological block (e.g. many consecutive non-conforming lines) can't be trimmed
    // down to nothing one line at a time in an unbounded loop. A real title/caption or
    // footnote is essentially always 1-3 physical lines (a wrapped 2-3 line caption at most,
    // per the us-007/us-020 samples in the task-9c report); 3 gives headroom above that
    // without allowing trimming to eat meaningfully into real table rows.
    static final int MAX_EDGE_TRIM_ITERATIONS = 3;

    // Step D: bounds the TOTAL per-page cost of per-block detection (gutter search +
    // gridness scoring + edge trimming) summed across every candidate block on one page.
    // Each individual block's own findGutters call is already independently bounded
    // (MAX_GUTTER_SCAN_WORK), and buildWords/buildLines already cap total page glyphs/words/
    // lines (MAX_STREAM_GLYPHS/MAX_STREAM_WORDS/MAX_STREAM_LINES) -- so the REMAINING linear
    // (scoreGrid/trimEdgeLines are O(block word count)) per-block work, summed across every
    // block on the page, can never exceed a small constant multiple of MAX_STREAM_WORDS
    // (60,000) even in the pathological case of thousands of tiny blocks each independently
    // processed. Charging each block's own word count against a running page total and
    // stopping once it's exhausted (WITHOUT discarding hits already built from prior blocks --
    // see the loop below) makes that bound explicit and enforced, rather than merely implied
    // by the upstream caps. Sized at 10x MAX_STREAM_WORDS: comfortably above the worst
    // legitimate case (every page word landing in blocks that all get fully processed, i.e.
    // a total charge of exactly MAX_STREAM_WORDS) while still being a real, finite ceiling.
    static final long MAX_STREAM_PAGE_BLOCK_WORK = 10L * MAX_STREAM_WORDS;

    /**
     * Step A: split an ordered (top-to-bottom) line list into candidate blocks wherever the
     * vertical gap to the next line exceeds {@link #BLOCK_GAP_FACTOR} times the median
     * line-to-line pitch. {@code lines} must already be sorted by {@code yTop} (as returned by
     * {@link #buildLines}).
     */
    static List<List<Line>> splitIntoBlocks(List<Line> lines) {
        List<List<Line>> blocks = new ArrayList<>();
        if (lines.isEmpty()) return blocks;
        if (lines.size() == 1) { blocks.add(new ArrayList<>(lines)); return blocks; }

        float[] pitches = new float[lines.size() - 1];
        for (int i = 1; i < lines.size(); i++) pitches[i - 1] = lines.get(i).yTop - lines.get(i - 1).yTop;
        float[] sortedPitches = pitches.clone();
        Arrays.sort(sortedPitches);
        // Degenerate-input floor (near-zero/overlapping line pitch): without it a zero-ish
        // median would make BLOCK_GAP_FACTOR*median ~0, so every nonzero gap -- however tiny --
        // would trigger a split, over-fragmenting into near-useless 1-2 line "blocks". This is
        // purely a defensive floor for pathological input, not expected on real PDFs (real line
        // pitch is always a real fraction of the font size).
        float medianPitch = Math.max(sortedPitches[sortedPitches.length / 2], 0.5f);
        float threshold = BLOCK_GAP_FACTOR * medianPitch;

        List<Line> cur = new ArrayList<>();
        cur.add(lines.get(0));
        for (int i = 1; i < lines.size(); i++) {
            float gap = lines.get(i).yTop - lines.get(i - 1).yTop;
            if (gap > threshold) {
                blocks.add(cur);
                cur = new ArrayList<>();
            }
            cur.add(lines.get(i));
        }
        blocks.add(cur);
        return blocks;
    }

    /**
     * Step C: iteratively drop leading/trailing lines of {@code block} that don't conform to
     * the column model implied by {@code gutters} -- a title/caption/footnote line glued to a
     * table block by a gap too small for Step A to have split off on its own. A line is
     * non-conforming if: it has fewer than 2 words, OR any of its words straddles a gutter
     * center, OR all its words fall into a single column while the block's OTHER (remaining)
     * lines occupy 2+ columns. Trimming is capped at {@link #MAX_EDGE_TRIM_ITERATIONS} per end.
     * {@code gutters}/{@code bandX0}/{@code bandX1} (the block's own column model) are NOT
     * recomputed as lines are dropped -- only the surviving line list changes.
     */
    static List<Line> trimEdgeLines(List<Line> block, List<Gutter> gutters, float bandX0, float bandX1) {
        List<Line> cur = new ArrayList<>(block);
        float[] bounds = colBoundsOf(gutters, bandX0, bandX1);
        for (int i = 0; i < MAX_EDGE_TRIM_ITERATIONS && cur.size() > 1; i++) {
            if (!isNonConformingEdge(cur.get(0), cur, bounds, gutters)) break;
            cur.remove(0);
        }
        for (int i = 0; i < MAX_EDGE_TRIM_ITERATIONS && cur.size() > 1; i++) {
            if (!isNonConformingEdge(cur.get(cur.size() - 1), cur, bounds, gutters)) break;
            cur.remove(cur.size() - 1);
        }
        return cur;
    }

    private static float[] colBoundsOf(List<Gutter> gutters, float bandX0, float bandX1) {
        float[] bounds = new float[gutters.size() + 2];
        bounds[0] = bandX0; bounds[bounds.length - 1] = bandX1;
        for (int i = 0; i < gutters.size(); i++) bounds[i + 1] = gutters.get(i).cx();
        return bounds;
    }

    private static boolean isNonConformingEdge(Line line, List<Line> context, float[] bounds, List<Gutter> gutters) {
        if (line.words.size() < 2) return true;
        for (Word w : line.words) {
            for (Gutter g : gutters) if (w.x0 < g.cx() && w.x1 > g.cx()) return true;
        }
        Set<Integer> ownCols = new HashSet<>();
        for (Word w : line.words) ownCols.add(colOf(w.cx(), bounds));
        if (ownCols.size() == 1) {
            Set<Integer> otherCols = new HashSet<>();
            for (Line l : context) {
                if (l == line) continue;
                for (Word w : l.words) otherCols.add(colOf(w.cx(), bounds));
            }
            if (otherCols.size() >= 2) return true;
        }
        return false;
    }

    static List<TableExtractor.TableHit> extractPage(int pageNum, List<TextPosition> glyphs) {
        return extractPage(pageNum, glyphs, new BreuelGutterFinder());
    }

    /**
     * Same per-page pipeline as {@link #extractPage(int, List)}, but with gutter detection routed
     * through the given {@link GutterFinder} instead of the hard-coded {@link #findGutters}. Lets a
     * bake-off harness run the full pipeline against any contender finder through this one seam.
     * The 2-arg overload delegates here with the production default ({@link BreuelGutterFinder});
     * that choice is unchanged pending bake-off results.
     *
     * <p>Pipeline (see the "region segmentation" block above for the Task 9d rationale): build
     * words/lines for the whole page (as before), split the lines into candidate blocks by
     * vertical gap (Step A), then for each block with >=3 lines independently find its own
     * band/gutters, trim non-conforming edge lines (Step C), and score/build a hit (Step B) --
     * capped at {@link #MAX_STREAM_TABLES_PER_PAGE} hits and a per-page work budget (Step D). A
     * DoS abort ({@link TableExtractor.RulingOverflowException}) from an individual block's
     * gutter search or cell-count cap only skips THAT block, not the whole page -- unlike the
     * page-global glyph/word/line caps in buildWords/buildLines, which (as before) abort the
     * whole page with no partial output, since a breach there means the line data itself
     * couldn't be safely built at all.
     */
    static List<TableExtractor.TableHit> extractPage(int pageNum, List<TextPosition> glyphs, GutterFinder finder) {
        List<Word> words;
        List<Line> lines;
        try {
            words = buildWords(glyphs);
            if (words.size() < 6) return List.of();           // too little to be a table
            float mfs = medianFontSize(words);
            lines = buildLines(words, mfs);
            if (lines.size() < 3) return List.of();
        } catch (TableExtractor.RulingOverflowException e) {
            return List.of();                                  // page-global DoS budget breached -> abort page
        }
        float medianSpace = 0.5f * medianFontSize(words);

        List<List<Line>> blocks = splitIntoBlocks(lines);
        List<TableExtractor.TableHit> hits = new ArrayList<>();
        long pageWork = 0;

        for (List<Line> block : blocks) {
            if (hits.size() >= MAX_STREAM_TABLES_PER_PAGE) break;
            if (block.size() < 3) continue;

            long charge = block.stream().mapToLong(l -> l.words.size()).sum();
            if (pageWork + charge > MAX_STREAM_PAGE_BLOCK_WORK) break; // page budget exhausted -> keep prior hits, stop
            pageWork += charge;

            try {
                float bandX0 = Float.MAX_VALUE, bandX1 = -Float.MAX_VALUE;
                for (Line l : block) for (Word w : l.words) {
                    bandX0 = Math.min(bandX0, w.x0); bandX1 = Math.max(bandX1, w.x1);
                }

                List<Gutter> gutters = finder.find(block, bandX0, bandX1, medianSpace);
                List<Line> trimmed = trimEdgeLines(block, gutters, bandX0, bandX1);
                if (trimmed.size() < 3) continue;               // Step C left too little to be a table -> reject block

                Grid grid = scoreGrid(trimmed, gutters, bandX0, bandX1);
                if (grid.confidence < STREAM_CONFIDENCE_MIN) continue;

                TableExtractor.TableHit hit = buildHit(pageNum, grid);
                if (hit != null) hits.add(hit);
            } catch (TableExtractor.RulingOverflowException e) {
                // this block's own gutter search or cell-count cap blew its budget -- skip only
                // this block (not the whole page), keeping whatever hits other blocks already
                // produced. This is the fix for the eu-001 failure mode in task-9c: a whole-page
                // search over 356 words/56 lines (prose + 7 small tables) used to blow
                // MAX_GUTTER_SCAN_WORK and silently lose every real table on the page.
            }
        }
        return hits;
    }

    // ------------------------------------------------------------- Task 9f: logical-row merging
    //
    // buildHit used to map each display Line 1:1 to a table row. Real tables have cells whose
    // text WRAPS across several display lines (long descriptions, multi-line headers), and every
    // wrapped cell manufactured a phantom row -- shifting every subsequent row index and, since
    // TableScore matches cells by exact (row, col, text), tanking F1 even when the extracted TEXT
    // was correct (measured: us-020 produced 49 rows vs. ground truth's 46; us-007, 44 vs. 36).
    //
    // Fix: cluster display lines into LOGICAL rows before ever building a cell, using an
    // anchor-column heuristic. Rationale: in a real table, the label/key column (or, for a
    // multi-line header, the FIRST header cell's own column) starts each new logical row; a
    // continuation line of a wrapped cell in some OTHER column leaves that anchor column empty.
    // Walking lines top-to-bottom, a line with anchor content starts a new row; a line without it
    // is folded into the row above. This handles wrapped data cells and multi-line headers with
    // the exact same rule -- no special-casing needed for headers.

    // Anchor column = the LEFTMOST column populated on at least this fraction of the block's
    // lines. 0.6 (not e.g. 0.5) deliberately requires a clear majority-plus-margin: a column
    // that's merely "more often populated than not" (just over 50%) is still plausibly itself a
    // wrapping/continuation-heavy column in a ragged table, whereas requiring 60% means the
    // chosen anchor is populated on a supermajority of rows -- consistent with it being the
    // record-starting key column rather than incidental content. Picking the LEFTMOST such
    // column (not just any) matches how real tables are laid out: the leading/label column is
    // conventionally first, and scanning left-to-right also means a mostly-numeric trailing
    // column that happens to clear the threshold never gets picked over a real label column to
    // its left.
    static final float ANCHOR_MIN_FILL = 0.6f;

    /**
     * Returns the index of the leftmost column populated on >= {@link #ANCHOR_MIN_FILL} of
     * {@code lines}, or -1 if no column meets that bar (caller must then fall back to one
     * logical row per display line -- guessing at an anchor from a weak signal risks merging
     * unrelated lines into one row, which is worse than the pre-fix behavior it would replace).
     */
    static int findAnchorColumn(List<Line> lines, float[] colBounds) {
        int cols = colBounds.length - 1;
        int n = lines.size();
        if (n == 0) return -1;
        for (int c = 0; c < cols; c++) {
            int filled = 0;
            for (Line l : lines) {
                for (Word w : l.words) {
                    if (colOf(w.cx(), colBounds) == c) { filled++; break; }
                }
            }
            if ((float) filled / n >= ANCHOR_MIN_FILL) return c;
        }
        return -1;
    }

    /**
     * Groups {@code lines} (top-to-bottom, as produced by {@link #buildLines}) into logical
     * rows. A line starts a NEW logical row if its anchor-column cell is non-empty; otherwise it
     * is a CONTINUATION of the current logical row (its content gets appended into whichever
     * column(s) it populates when the group is later flattened into cells). If the very first
     * line has no anchor content, it still starts a row on its own (there is no prior row to
     * continue). If {@link #findAnchorColumn} finds no qualifying column, falls back to one
     * logical row per display line -- i.e. the pre-Task-9f behavior -- for this block.
     */
    static List<List<Line>> groupLogicalRows(List<Line> lines, float[] colBounds) {
        List<List<Line>> groups = new ArrayList<>();
        int anchorCol = findAnchorColumn(lines, colBounds);
        if (anchorCol < 0) {
            for (Line l : lines) groups.add(List.of(l));      // fallback: one row per display line
            return groups;
        }
        List<Line> cur = null;
        for (Line l : lines) {
            boolean anchorPopulated = false;
            for (Word w : l.words) {
                if (colOf(w.cx(), colBounds) == anchorCol) { anchorPopulated = true; break; }
            }
            if (cur == null || anchorPopulated) {
                cur = new ArrayList<>();
                groups.add(cur);
            }
            cur.add(l);
        }
        return groups;
    }

    private static TableExtractor.TableHit buildHit(int pageNum, Grid grid) {
        int cols = grid.colBounds.length - 1;
        List<List<Line>> rowGroups = groupLogicalRows(grid.rows, grid.colBounds);
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
            // Cell bbox for a merged cell = union of the contributing words' boxes across ALL
            // its display lines (not just one). Text is joined across lines the same way it's
            // already joined WITHIN a line (single space between successive fragments), so a
            // cell's text reads naturally whether its words came from one display line or several.
            StringBuilder[] text = new StringBuilder[cols];
            float[][] box = new float[cols][]; // {x0,y0,x1,y1}
            for (Line line : rowGroups.get(r)) {
                for (Word w : line.words) {
                    int c = colOf(w.cx(), grid.colBounds);
                    if (text[c] == null) { text[c] = new StringBuilder(); box[c] = new float[]{w.x0,w.y0,w.x1,w.y1}; }
                    else { if (text[c].length() > 0) text[c].append(' '); }
                    text[c].append(w.text);
                    box[c][0]=Math.min(box[c][0],w.x0); box[c][1]=Math.min(box[c][1],w.y0);
                    box[c][2]=Math.max(box[c][2],w.x1); box[c][3]=Math.max(box[c][3],w.y1);
                }
            }
            for (int c = 0; c < cols; c++) {
                if (text[c] == null) continue;                // sparse cell -> omit (renderViews fills "")
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
        TableExtractor.renderViews(t);                        // fills rows + markdown
        return t;
    }
}
