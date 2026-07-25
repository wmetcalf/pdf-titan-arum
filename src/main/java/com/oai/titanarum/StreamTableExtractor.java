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

    /**
     * The page's /Rotate frame, so this class can report geometry in the SAME visual, /Rotate-applied
     * top-left frame every other path in {@link TableExtractor} reports in.
     *
     * <p>WHY THIS EXISTS. {@link #buildWords} reads {@code getXDirAdj()/getYDirAdj()}, which are
     * /Rotate-BLIND -- they track each glyph's own text DIRECTION, not the page's {@code /Rotate}
     * (see {@link TableExtractor#applyPageRotation}'s doc). The lattice path maps both rulings and
     * text through {@code applyPageRotation}; the tagged path was explicitly fixed to do the same
     * (see {@link TableExtractor}'s "FIX 1 (Codex P2)" writeup). Without this, a {@code /Rotate 90}
     * page put stream boxes in a DIFFERENT frame than the lattice/tagged boxes in the very same
     * report.json -- measured: the same table reported at [170,30,255.8,355] by lattice and
     * [30,50,355,135.8] by stream, whose x1 exceeds the visual page width -- so
     * {@code contestsSameRegion} (a bbox intersection ACROSS the two frames) saw no contest and both
     * contradictory answers were emitted.
     *
     * <p>Detection itself deliberately still runs in the {@code getXDirAdj()/getYDirAdj()} frame:
     * that frame is the one in which a line of text reads left-to-right, which is what line
     * clustering and vertical-gutter search require. Only the FINAL reported geometry (each
     * {@link TableExtractor.CellHit#bbox} and the {@link TableExtractor.TableHit#bbox}) is mapped,
     * exactly as {@link TableExtractor#splitClumpedCells} already maps {@code buildWords} output
     * before comparing it against visual-frame cell rects.
     */
    static final class PageFrame {
        final int rotation;
        final float unrotatedW, unrotatedH;
        PageFrame(int rotation, float unrotatedW, float unrotatedH) {
            this.rotation = rotation; this.unrotatedW = unrotatedW; this.unrotatedH = unrotatedH;
        }
        /** No /Rotate: the mapping is the identity, i.e. exactly the pre-fix behaviour. */
        static final PageFrame IDENTITY = new PageFrame(0, 0f, 0f);

        /** Maps an axis-aligned box, normalising the corners (a 90/270 rotation swaps the axes). */
        float[] map(float x0, float y0, float x1, float y1) {
            if (rotation == 0) return new float[]{x0, y0, x1, y1};
            float[] a = TableExtractor.applyPageRotation(x0, y0, rotation, unrotatedW, unrotatedH);
            float[] b = TableExtractor.applyPageRotation(x1, y1, rotation, unrotatedW, unrotatedH);
            return new float[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]),
                               Math.max(a[0], b[0]), Math.max(a[1], b[1])};
        }
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
            // FRAME (fix): getYDirAdj() is pageHeight - textMatrix.getTranslateY(), and the text
            // matrix' translate is the glyph ORIGIN -- i.e. the BASELINE in the top-left, y-down
            // frame, NOT the top of the glyph. The rest of this codebase already treats it that way:
            // TableExtractor#fillCellsFromPositions uses getYDirAdj() - getHeightDir()/2 as the glyph
            // MIDPOINT, and TableExtractor#resolveCellText builds the tagged bbox as [getYDirAdj()-h,
            // getYDirAdj()]. This method used to add the height DOWNWARD from getYDirAdj(), which put
            // every stream Word (and so every stream CellHit/TableHit bbox in report.json) roughly one
            // glyph height BELOW the ink it describes -- almost entirely outside it (measured on a
            // 12pt Helvetica glyph: reported [50.00, 56.94] against true ink [41.38, 52.48]).
            // TableExtractor#contestsSameRegion compares these boxes against correctly-framed lattice
            // boxes, so every arbitration contest was biased by about half a row.
            //
            // MEASURED CONSEQUENCE OF THE FIX, stated plainly because it is NOT confined to reporting.
            // A Word's y extent also feeds buildLines' line clustering, and the fix flips the
            // direction a Line's box grows: the old box ran from the min BASELINE down to max
            // baseline+h (reaching into the gap BELOW, covering no ink there), the fixed one runs from
            // min baseline-h up to the max baseline (covering the real ink). Where a line mixes glyph
            // heights that changes which neighbouring row's words it absorbs, which changes Step A/A'
            // block segmentation. On the 77-document benchmark 16 documents move: 14 up (us-022
            // +0.3295, the largest -- it now reads 13 rows where it read 8), 2 down badly (us-015
            // -0.4417 and eu-011 -0.3386, both over-merges; eu-011 gains a candidate on a page whose
            // ground truth has no table). Net: stream alone 0.6562 -> 0.6575 all-pages, positional
            // merge 0.7393 -> 0.7429, region-given 0.6622 -> 0.6931, but full+ARBITRATION 0.8118 ->
            // 0.8070 and the prose false-positive rate 13/200 -> 15/200. In other words the extracted
            // CONTENT improved on every protocol that does not arbitrate, and only the arbitration
            // DECISION got worse -- because Step A/A' and the arbitration thresholds were all
            // calibrated against the biased frame. Shipped anyway: the frame was simply wrong, and
            // re-calibrating those constants against the corrected frame is a separate change with its
            // own search and its own tests (the natural candidate is making buildLines cluster on
            // BASELINES, which is frame-invariant, so the two concerns stop being coupled).
            float gBaseline = tp.getYDirAdj();           // baseline, top-left origin
            float gh  = Math.max(1f, tp.getHeightDir());
            float gy0 = gBaseline - gh;                  // top of glyph
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
            // NOTE the newLine test (and prevBaseline below) deliberately still compare BASELINES,
            // exactly as before the frame fix -- comparing glyph TOPS instead would make the line
            // break depend on font-size changes within one line. Word GROUPING is therefore
            // byte-identical to the pre-fix behaviour; only each Word's y extent moved.
            boolean newLine = cur != null && Math.abs(gBaseline - prevBaseline) > 0.5f * fs;
            boolean gap     = cur != null && (gx0 - prevX1) > 0.30f * space;
            if (cur == null || whitespace || newLine || gap) {
                if (cur != null) {
                    finishWord(cur, out);
                    if (out.size() > MAX_STREAM_WORDS) throw new TableExtractor.RulingOverflowException();
                }
                if (whitespace) { cur = null; continue; }
                cur = new Word();
                cur.x0 = gx0; cur.y0 = gy0; cur.x1 = gx0 + gw; cur.y1 = gBaseline;
                cur.text = u;
            } else {
                cur.x1 = gx0 + gw;
                cur.y0 = Math.min(cur.y0, gy0);
                cur.y1 = Math.max(cur.y1, gBaseline);
                cur.text += u;
            }
            prevX1 = gx0 + gw; prevBaseline = gBaseline;
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
        w.numeric = isNumericToken(w.text);
        out.add(w);
    }

    /**
     * This class's single definition of a NUMERIC token: digits plus the punctuation numbers are
     * actually written with, and at least one real digit (so "%", "$", "(.)" alone are not numeric).
     * Extracted verbatim out of {@link #finishWord} -- unchanged behaviour -- so {@link
     * TableExtractor#splitClumpedCells}' numeric-majority guard asks the SAME question about a
     * token that word building already asks, instead of re-deriving a second, drifting definition.
     */
    static boolean isNumericToken(String s) {
        return s.matches("[-+(]?[\\d.,%$)]+") && s.chars().anyMatch(Character::isDigit);
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

    // Task 9l. Corpus decomposition (task-9k's own diagnosis, re-examined) found column
    // UNDER-SPLITTING is 50% of all within-table false positives, and dumping the real geometry
    // of the corpus's own technology/tabula/spanning_cells.pdf fixture found the actual
    // mechanism: the true inter-column gaps there are ~11x medianSpace -- enormously wide,
    // nothing to do with minGutterW -- but a spanning sub-header word crosses the gutter's
    // x-range on a FEW rows, fragmenting the merged gutter's row-coverage bitset (see the
    // "merged" / "mergedRows" loop above) below the row-coverage acceptance bar, so the whole
    // gutter is rejected and the two columns merge. This directly contradicts the feature's own
    // design intent (derive gutters from the MAJORITY of rows, treating a spanning cell as a
    // colspan -- real structure -- not as proof the gutter is fake) and the whole reason a
    // per-row-empty Breuel search was chosen over a whole-page whitespace projection (a gutter
    // need only be empty over the rows it actually separates).
    //
    // Fix: a row is EXCLUDED from a merged gutter's coverage denominator (neither numerator nor
    // denominator) if some word on that row STRADDLES the gutter's own finalized x-range (starts
    // before its right edge and ends after its left edge) -- see the coverage computation in the
    // final merge loop below. Such a row is not evidence against the boundary: every row NOT
    // straddled is either accepted-clean (supports the gutter) or genuinely obstacle-crossing
    // elsewhere (a real miss, still counted against it) -- straddling rows are simply neutral,
    // matching the spec's own "colspan, not proof of one column" framing.
    //
    // Two guards keep this from ever inventing a gutter inside a genuinely single, undivided
    // column (e.g. twoColumnProse, or a real single wide column with a few short/ragged lines
    // creating incidental whitespace): both must hold before the exclusion applies at all;
    // failing either falls back to scoring against the ORIGINAL full row count, i.e. exactly the
    // pre-fix rule (which correctly rejects a real single column).
    //
    // GUTTER_MIN_NONSTRADDLING_ROWS_FOR_EXCLUSION: mirrors scoreGrid's own "rows < 3" minimum
    // significance floor elsewhere in this file -- fewer than 3 non-straddling rows is too small
    // a sample to trust a coverage fraction computed over it, regardless of how high that
    // fraction is.
    static final int GUTTER_MIN_NONSTRADDLING_ROWS_FOR_EXCLUSION = 3;
    // GUTTER_MAX_STRADDLE_FRACTION_FOR_EXCLUSION: straddling rows must remain a MINORITY of the
    // block's rows for the exclusion to apply. A real spanning sub-header interrupts a small
    // handful of rows (spanning is the exception, not the rule); a genuine single, undivided
    // column instead straddles on most/all of its rows. 0.50 is the natural minority/majority
    // split -- measured directly against the reproduction fixture (task-9l's
    // gutterSurvivesSpanningHeaderRows: 9/20 = 45% straddling rows, a minority, must be
    // excluded and recover the gutter; StreamGutterTest's own diagnostic sweep confirmed the
    // pre-fix rule flips from accept to reject exactly at the 60%-of-total-rows boundary as the
    // straddling fraction crosses ~40-45%, i.e. well before it becomes a majority) and against
    // twoColumnProse/the gridness+prose suite, which must still see zero straddling rows (a
    // clean two-block prose page has no word crossing its own central gutter at all) and so are
    // completely unaffected by this constant either way.
    static final float GUTTER_MAX_STRADDLE_FRACTION_FOR_EXCLUSION = 0.50f;
    // The original row-coverage acceptance bar (fraction of the EFFECTIVE row count -- i.e. of
    // the full row count when the guards above disqualify exclusion, or of the non-straddling
    // row count when they don't). Unchanged in VALUE from the pre-task-9l inline 0.60f -- see the
    // task-9l report for the sensitivity check against alternative values.
    static final float GUTTER_MIN_COVER_FRACTION = 0.60f;

    // Task 9k. Corpus-wide adjacency-F1 diagnosis (77-PDF ICDAR/tabula scoring set) decomposed
    // every false positive/negative for the production "breuel" finder: 88.8% of all FNs and
    // 56.7% of all FPs occur INSIDE tables already correctly located, i.e. the grid built
    // within a correctly-found table is wrong. Of those within-table FPs, 50.0% (4,295/8,597)
    // are column UNDER-SPLITTING: two adjacent ground-truth columns get detected as one and
    // their text gets joined into a single cell -- the single largest contributor identified.
    // Root cause: minGutterW (== medianSpace, see above) is a hard floor -- the branch-and-bound
    // above (line ~225) drops any candidate rect narrower than it BEFORE ever scoring it, so a
    // real but physically narrow inter-column gap (common for numeric columns, which often sit
    // closer together than prose word-spacing) never even becomes a candidate, let alone gets
    // accepted. The fix below (findAlignmentNarrowGutters) is a SEPARATE, bounded secondary pass
    // run once after the primary width-gated search completes: for each column segment the
    // primary search left un-split, it looks for a narrower-than-minGutterW empty strip that
    // nonetheless has strong ALIGNMENT evidence -- a consistent left/right word-edge pair,
    // recurring on a high fraction of that segment's rows, with low position variance -- and
    // promotes it to a real gutter. It is a strictly additive pass (never removes or narrows a
    // primary-found gutter) run in its own charged, independently-bounded budget
    // (MAX_ALIGNMENT_GUTTER_WORK) so it cannot itself become a new DoS vector, and it is only
    // ever reached after the primary search has ALREADY completed within its own budget, so the
    // existing findGuttersAbortsOnDenseAdversarialPage behavior (primary budget still fires
    // first on that fixture) is unaffected.

    // Absolute floor, expressed as a fraction of medianSpace: no strip narrower than this is EVER
    // accepted, no matter how strong its alignment evidence, so this fix can never degenerate
    // into splitting inside a single word/kerning run (which is always << medianSpace wide) --
    // ordinary intra-word glyph-to-glyph gaps are a small fraction of medianSpace, while a real
    // (if tight) inter-column gap is a meaningful fraction of it. 0.30 sits comfortably below the
    // narrow-but-real gap this fix targets (measured/constructed at ~0.58x medianSpace, see
    // StreamGutterTest#narrowlySpacedAdjacentColumnsAreSplit) while staying well above
    // kerning-scale noise.
    static final float NARROW_GUTTER_ABS_FLOOR_FACTOR = 0.30f;

    // Clustering tolerance (fraction of medianSpace) for grouping candidate narrow-gap
    // midpoints that recur across different rows into one alignment signal, mirroring
    // AlignmentEdgeGutterFinder's own edge-clustering tolerance (0.4x medianSpace) for the same
    // underlying phenomenon (real glyph metrics vary slightly row-to-row even for a genuinely
    // aligned column boundary).
    static final float NARROW_GUTTER_CLUSTER_TOL_FACTOR = 0.5f;

    // Once a cluster is found, its member rows' own left-edge (word-ending) and right-edge
    // (word-starting) positions must ALSO individually vary by no more than this fraction of
    // medianSpace -- the "low variance" requirement from the spec, checked directly on the raw
    // edge positions rather than inferred solely from the clustering tolerance above (clustering
    // groups by MIDPOINT, which could mask one side drifting while the other compensates).
    static final float NARROW_GUTTER_ALIGN_TOL_FACTOR = 0.35f;

    // Required fraction of a segment's own populated rows that must exhibit the aligned
    // narrow-gap signal before it is promoted to a gutter. Set well above the primary search's
    // own minCover (0.60, see above) because this is intrinsically weaker/riskier evidence (a
    // narrower empty strip is more easily produced by coincidence than a wide one) -- the higher
    // bar is what keeps this fix from raising prose false positives.
    static final float NARROW_GUTTER_MIN_ROW_FRACTION = 0.70f;

    // A numeric column boundary (right- or decimal-aligned digits on one or both sides of the
    // gap) is much stronger evidence than an arbitrary word boundary -- real prose essentially
    // never produces a recurring narrow gap flanked by numbers -- so a numeric-backed cluster is
    // held to a relaxed (but still majority) row-fraction bar instead of the stricter default.
    static final float NARROW_GUTTER_MIN_ROW_FRACTION_NUMERIC = 0.55f;

    // Bounds the REAL work of the secondary pass: every word examined while building per-segment
    // gap observations, plus one O(obstacles) global straddle check per surviving cluster, is
    // charged. Sized far below the primary search's own MAX_GUTTER_SCAN_WORK (500,000,000)
    // because this pass is a small, targeted addition, not a second full search -- 5,000,000
    // gives generous headroom over every legitimate fixture in this suite (e.g. the 200x10
    // uniformGrid: 2,000 words scanned once across ~10 segments, each segment producing zero
    // observations since its columns are already fully split by the primary pass) while still
    // being a real, finite, charged ceiling that throws RulingOverflowException (not merely
    // returns fewer results) if a pathological input somehow drives it up.
    static final long MAX_ALIGNMENT_GUTTER_WORK = 5_000_000L;

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
            // Task 9l: a row NOT covered by any accepted (obstacle-free) fragment might still be
            // a legitimate spanning cell rather than real evidence against this boundary -- count
            // how many of the block's rows have a word that STRADDLES this merged gutter's own
            // finalized x-range [m[0], m[1]] (starts before its right edge, ends after its left
            // edge). Note this is NOT guaranteed disjoint from "covered": mergedRows marks a row
            // covered on ANY Y-overlap with an accepted rect (see the loop above), which can be a
            // partial overlap against a row whose OWN content pokes into the gutter elsewhere in
            // its height (verified directly against real corpus geometry, e.g.
            // technology/tabula/spanning_cells.pdf, where one merged gutter measured cover=20,
            // straddling=3 against only 21 total rows -- 2 rows counted as both). That is harmless
            // here: coverFraction can then modestly exceed 1.0, which only makes the >=
            // GUTTER_MIN_COVER_FRACTION check below even easier to satisfy for a row that is ALSO
            // straddling on some other word -- it does not change what the check is testing
            // (whether the genuinely-clean rows are a strong majority). Charged against the same
            // MAX_GUTTER_SCAN_WORK budget as the search above -- this is a second, smaller, but
            // still real O(rows x words-per-row) pass, so it must be bounded the same way.
            int totalRows = lines.size();
            int straddling = 0;
            for (Line l : lines) {
                for (Word w : l.words) {
                    work++;
                    if (work > MAX_GUTTER_SCAN_WORK) throw new TableExtractor.RulingOverflowException();
                    if (w.x0 < m[1] && w.x1 > m[0]) { straddling++; break; }
                }
            }
            int nonStraddling = totalRows - straddling;
            // Exclude straddling rows from the denominator ONLY when both guards hold (see the
            // GUTTER_MIN_NONSTRADDLING_ROWS_FOR_EXCLUSION / GUTTER_MAX_STRADDLE_FRACTION_FOR_
            // EXCLUSION docs above); otherwise fall back to the original full-row-count fraction,
            // which is exactly the pre-task-9l rule and correctly rejects a genuine single column.
            double coverFraction;
            if (straddling > 0
                    && nonStraddling >= GUTTER_MIN_NONSTRADDLING_ROWS_FOR_EXCLUSION
                    && straddling < GUTTER_MAX_STRADDLE_FRACTION_FOR_EXCLUSION * totalRows) {
                coverFraction = nonStraddling > 0 ? (double) cover / nonStraddling : 0;
            } else {
                coverFraction = totalRows > 0 ? (double) cover / totalRows : 0;
            }
            if (coverFraction < GUTTER_MIN_COVER_FRACTION) continue;
            if (m[0] <= bandX0 + 0.5f || m[1] >= bandX1 - 0.5f) continue; // edge margin, not interior
            Gutter g = new Gutter(); g.x0 = m[0]; g.x1 = m[1]; g.rowsCovered = cover;
            gutters.add(g);
        }

        // Task 9k secondary pass: promote narrower-than-minGutterW empty strips backed by
        // strong cross-row alignment evidence -- see findAlignmentNarrowGutters and the
        // NARROW_GUTTER_* constants' docs above for the full rationale. Strictly additive: never
        // removes or narrows anything the primary search above already found.
        List<Gutter> narrow = findAlignmentNarrowGutters(lines, obstacles, gutters, bandX0, bandX1, medianSpace, minGutterW);
        if (!narrow.isEmpty()) {
            gutters.addAll(narrow);
            gutters.sort(Comparator.comparingDouble(g -> g.x0));
        }
        return gutters;
    }

    /**
     * Task 9k. Secondary, bounded pass run once after the primary width-gated branch-and-bound
     * in {@link #findGutters} completes: looks, within each column segment the primary search
     * left un-split (i.e. between/around its own {@code primaryGutters}), for a narrower-than-
     * {@code minGutterW} empty strip that nonetheless has strong alignment evidence -- a
     * consistent word-ending/word-starting edge pair recurring on a high fraction of the
     * segment's own populated rows, with low position variance on both edges, and never actually
     * straddled by any word anywhere in the block. See the {@code NARROW_GUTTER_*} constants
     * above for every threshold's own rationale.
     *
     * <p>Only words FULLY CONTAINED in a segment (not merely overlapping it) contribute
     * observations -- this is what keeps a full-width spanning header/title row (which overlaps
     * every segment) from ever manufacturing a bogus narrow-gap observation.
     *
     * <p>Charged against {@link #MAX_ALIGNMENT_GUTTER_WORK}; throws {@link
     * TableExtractor.RulingOverflowException} on overflow, same discipline as the primary search.
     */
    private static List<Gutter> findAlignmentNarrowGutters(List<Line> lines, List<float[]> obstacles,
            List<Gutter> primaryGutters, float bandX0, float bandX1, float medianSpace, float minGutterW) {
        List<Gutter> found = new ArrayList<>();
        float absFloor = NARROW_GUTTER_ABS_FLOOR_FACTOR * medianSpace;
        if (absFloor <= 0 || absFloor >= minGutterW) return found; // nothing narrower to look for

        // Segments = the x-ranges the primary search did NOT already claim as a gutter: before
        // the first primary gutter, between each consecutive pair, and after the last.
        List<Gutter> sortedPrimary = new ArrayList<>(primaryGutters);
        sortedPrimary.sort(Comparator.comparingDouble(g -> g.x0));
        List<float[]> segments = new ArrayList<>(); // {segX0, segX1}
        float prevX1 = bandX0;
        for (Gutter pg : sortedPrimary) {
            segments.add(new float[]{prevX1, pg.x0});
            prevX1 = pg.x1;
        }
        segments.add(new float[]{prevX1, bandX1});

        long work = 0;
        float clusterTol = NARROW_GUTTER_CLUSTER_TOL_FACTOR * medianSpace;
        float alignTol = NARROW_GUTTER_ALIGN_TOL_FACTOR * medianSpace;

        for (float[] seg : segments) {
            float segX0 = seg[0], segX1 = seg[1];
            if (segX1 - segX0 < 2 * absFloor) continue; // no room for an interior narrow gutter

            // Per-line candidate observations: an adjacent-word gap, fully inside this segment,
            // narrower than minGutterW but at least the absolute floor.
            List<float[]> obs = new ArrayList<>(); // {leftEdge(x1 of left word), rightEdge(x0 of right word), lineIdx, numericVotes}
            int populatedRows = 0;
            for (int li = 0; li < lines.size(); li++) {
                List<Word> segWords = new ArrayList<>();
                for (Word w : lines.get(li).words) {
                    work++;
                    if (work > MAX_ALIGNMENT_GUTTER_WORK) throw new TableExtractor.RulingOverflowException();
                    if (w.x0 >= segX0 - 0.01f && w.x1 <= segX1 + 0.01f) segWords.add(w);
                }
                if (!segWords.isEmpty()) populatedRows++;
                for (int i = 1; i < segWords.size(); i++) {
                    Word left = segWords.get(i - 1), right = segWords.get(i);
                    float gap = right.x0 - left.x1;
                    if (gap >= absFloor && gap < minGutterW) {
                        int numericVotes = (left.numeric ? 1 : 0) + (right.numeric ? 1 : 0);
                        obs.add(new float[]{left.x1, right.x0, li, numericVotes});
                    }
                }
            }
            if (obs.isEmpty() || populatedRows == 0) continue;

            // Single-linkage cluster by gap midpoint (same discipline as AlignmentEdgeGutterFinder).
            obs.sort(Comparator.comparingDouble(o -> (o[0] + o[1]) / 2f));
            List<List<float[]>> clusters = new ArrayList<>();
            List<float[]> cur = new ArrayList<>();
            double sumMid = 0; int cnt = 0;
            for (float[] o : obs) {
                float mid = (o[0] + o[1]) / 2f;
                if (cnt == 0 || Math.abs(mid - sumMid / cnt) <= clusterTol) {
                    cur.add(o); sumMid += mid; cnt++;
                } else {
                    clusters.add(cur);
                    cur = new ArrayList<>(); cur.add(o); sumMid = mid; cnt = 1;
                }
            }
            if (!cur.isEmpty()) clusters.add(cur);

            for (List<float[]> cluster : clusters) {
                Set<Integer> distinctLines = new HashSet<>();
                float minLeft = Float.MAX_VALUE, maxLeft = -Float.MAX_VALUE;
                float minRight = Float.MAX_VALUE, maxRight = -Float.MAX_VALUE;
                int numericVotes = 0;
                for (float[] o : cluster) {
                    distinctLines.add((int) o[2]);
                    minLeft = Math.min(minLeft, o[0]); maxLeft = Math.max(maxLeft, o[0]);
                    minRight = Math.min(minRight, o[1]); maxRight = Math.max(maxRight, o[1]);
                    if (o[3] >= 1) numericVotes++;
                }
                int support = distinctLines.size();
                double rowFraction = (double) support / populatedRows;
                boolean numericBacked = numericVotes * 2 >= cluster.size();
                double minFraction = numericBacked ? NARROW_GUTTER_MIN_ROW_FRACTION_NUMERIC
                                                    : NARROW_GUTTER_MIN_ROW_FRACTION;
                if (rowFraction < minFraction) continue;
                // "Low variance" only needs to hold on AT LEAST ONE side, not both: real adjacent
                // right-aligned numeric columns (the common case this fix targets) keep the LEFT
                // column's right edge (leftEdge) essentially constant regardless of digit count
                // (that's what right-alignment means), while the RIGHT column's own left edge
                // (rightEdge) legitimately drifts with ITS digit count -- requiring both sides
                // tight would reject exactly the common real case. Whichever side IS the stable
                // alignment reference is what matters; the other side's drift is already made
                // safe by the intersection below (gx0/gx1), which never crosses into any cluster
                // member's actual word regardless of how much the unaligned side moves.
                boolean leftAligned = (maxLeft - minLeft) <= alignTol;
                boolean rightAligned = (maxRight - minRight) <= alignTol;
                if (!leftAligned && !rightAligned) continue;

                float gx0 = maxLeft;   // rightmost "ending" edge across the cluster -> clear of every member's left word
                float gx1 = minRight;  // leftmost "starting" edge across the cluster -> clear of every member's right word
                if (gx1 - gx0 < absFloor) continue; // degenerate after intersection

                boolean straddled = false;
                for (float[] o : obstacles) {
                    work++;
                    if (work > MAX_ALIGNMENT_GUTTER_WORK) throw new TableExtractor.RulingOverflowException();
                    if (o[0] < gx1 && o[2] > gx0) { straddled = true; break; }
                }
                if (straddled) continue;
                if (gx0 <= bandX0 + 0.5f || gx1 >= bandX1 - 0.5f) continue; // interior margin

                Gutter g = new Gutter(); g.x0 = gx0; g.x1 = gx1; g.rowsCovered = support;
                found.add(g);
            }
        }
        return found;
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

    // ------------------------------------------------------------------ column-count-scaled bar
    //
    // MEASURED PROBLEM (gate-oracle measurement, 77-PDF corpus + 1,599 real-world PDFs). A single
    // flat admission bar is mis-calibrated for wide grids, because scoreGrid's own terms move the
    // WRONG WAY as the column count rises:
    //
    //   * the column-count credit, 0.15 * min(1, (cols-2)/2), SATURATES at cols=4. A 9-column grid
    //     earns exactly as much for its column count as a 4-column one, even though nine columns
    //     require EIGHT simultaneous full-height whitespace gutters -- a coincidence prose does not
    //     produce, and by far the strongest structural evidence the stream path has;
    //   * colConsistency actively PENALISES width: it counts a row as consistent only if at least
    //     cols-1 of the cols columns are populated, so as cols grows the requirement grows with it
    //     and any legitimately SPARSE wide table (blank cells -- extremely common in wide tables)
    //     drives the term towards 0. Adding one CORRECT column raises the requirement from cols-1 to
    //     cols and can therefore LOWER the confidence of a table that just got better, which is the
    //     mechanism behind the "a correct improvement deleted the whole table" reports.
    //
    // Net effect: past ~5 columns the evidence rises while the score falls, so a bar tuned on narrow
    // grids deletes correct wide ones. Measured on the corpus: correct 6-to-9-column tables scoring
    // 0.405-0.516 were being deleted outright (eu-026 p4 and p6, eu-002 p1, eu-012 p4, us-012 p1,
    // tabula/twotables p1).
    //
    // FIX: keep the 0.55 bar EXACTLY as it is for grids narrower than WIDE_GRID_MIN_COLS -- that is
    // where the false-positive risk lives (ordinary 2-to-4-column prose, forms, nav bars, receipt
    // headers) and it is doing its job -- and use a lower bar only from WIDE_GRID_MIN_COLS columns up.
    //
    // WHY NOT SIMPLY LOWER THE FLAT BAR (measured and rejected): a flat 0.40 bar reaches ALL-77 stream
    // e2e MACRO 0.6486 but costs 68/1599 = 0.0425 real-world false positives against a 50/1599 =
    // 0.0313 baseline. Almost every real-world look-alike a flat bar admits is a 3-or-4-column layout
    // (receipt headers, forms, nav bars) -- exactly what the two-tier bar still rejects, at 53/1599 =
    // 0.0331.
    //
    // WHY 7 COLUMNS AND 0.40 -- the measured sweep (POOLED official adjacency relations, de-duplicated
    // GT, MACRO first; "full" = the shipped tagged+lattice+non-overlapping-stream combination):
    //
    //   gate              stream ALL-77   full ALL-77     borderless-22   real-world FP
    //   flat 0.55         0.6260 / .7638  0.6822 / .7582  0.6836          50/1599 = 0.0313
    //   cols>=5 @ 0.40    0.6488 / .7658  0.6824 / .7569  0.7042          54/1599 = 0.0338
    //   cols>=6 @ 0.40    0.6488 / .7658  0.6824 / .7569  0.7042          54/1599 = 0.0338
    //   cols>=7 @ 0.40    0.6344 / .7645  0.6884 / .7589  0.7053          53/1599 = 0.0331
    //   cols>=8 @ 0.40    0.6323 / .7642  0.6861 / .7585  0.6976          53/1599 = 0.0331
    //   cols>=7 @ 0.45    0.6282 / .7638  0.6822 / .7582  0.6836          52/1599 = 0.0325
    //   cols>=7 @ 0.35    0.6254 / .7596  0.6861 / .7550  0.6985          53/1599 = 0.0331
    //
    // cols>=6 wins on stream-alone-over-all-77 but that configuration scores the stream path on the
    // 55 documents where the lattice path already recovers the table, so it is diagnostic rather than
    // a product configuration. On the two configurations that ARE the product -- the full pipeline,
    // and the borderless subset where stream is the only path -- cols>=7 wins on MACRO *and* on micro
    // *and* has the lower false-positive rate, so cols>=7 is the choice.
    //
    // The single document that decides 6 vs 7 is eu-002 p1: at 6 columns the gate admits a candidate
    // that swallows five rows of running PROSE above the real table and chops them into six fake
    // columns (confidence 0.451, adjacency precision 0.278). The lattice path already recovers that
    // page's table at F1 0.9307, and the spurious block's relations flood the pooled multiset, taking
    // the document to 0.4949. Requiring seven columns excludes it while keeping the wide tables that
    // motivated the change (eu-026 p6 at 9 columns, confidence 0.405, precision 0.853; eu-026 p4 at
    // 7 columns, 0.430, 0.659). Confidence alone cannot separate those -- the prose blob scores
    // HIGHER than both -- so the column count is the only signal available that does.
    //
    // 0.40 rather than 0.35/0.45: 0.45 sits above the eu-026 candidates (0.405, 0.430) and recovers
    // nothing at all (full ALL-77 identical to baseline), while 0.35 starts admitting wide junk and
    // is worse on every corpus aggregate. 0.40 sits just below the lowest correct wide-table reject
    // measured on the corpus (0.405) and above the wide-junk population.
    //
    // ------------------------------------------------------------------ RE-CALIBRATION (Lever5RecalHarness)
    //
    // The sweep table above is STALE IN TWO WAYS and is kept only for its reasoning. It was measured
    // (a) on the benchmark's old glyph feed, corrected in ec93b10, and (b) through the POSITIONAL
    // merge, which stopped being the shipping pipeline when per-region arbitration landed -- its
    // "full ALL-77" column scores a merge production no longer performs. The gate was therefore
    // re-searched THROUGH ARBITRATION over a 100-point grid (narrow bar x wide-tier column threshold
    // x wide bar), under the primary protocol, at both page scopes, with leave-one-document-out:
    //
    //   gate                       all-pages MACRO   shipping MACRO   prose FP (1599)   (200-sample)
    //   cols>=7 @ 0.40, narrow .55   0.8118  <-argmax   0.7927 <-argmax   132 = 0.0826   13 = 0.0650
    //   cols>=7 @ 0.40, narrow .60   0.8118             0.7927            126 = 0.0788   13 = 0.0650
    //   cols>=7 @ 0.40, narrow .65   0.8020             0.7828            117 = 0.0732   12 = 0.0600
    //   cols>=5 @ 0.40, narrow .55   0.8115             0.7924            132 = 0.0826   13 = 0.0650
    //   FLAT 0.55 (no wide tier)     0.8056             0.7843            132 = 0.0826   13 = 0.0650
    //   FLAT 0.60 (no wide tier)     0.8005             0.7792            126 = 0.0788   13 = 0.0650
    //
    // These three values are the STRICT in-sample argmax at BOTH page scopes and are tied for best in
    // 75 of the 77 leave-one-out folds (leave-one-out 0.8044 all-pages / 0.7831 shipping). The wide
    // tier still earns its keep: removing it (FLAT 0.55) costs 0.0062 MACRO for no false-positive
    // benefit at all. NOTHING MOVED, so nothing here changed.
    //
    // ONE MEASURED PARETO CANDIDATE, deliberately NOT taken: narrow bar 0.60 with the same wide tier
    // is POINTWISE IDENTICAL to 0.55 on all 77 corpus documents at both scopes (no corpus candidate
    // scores in [0.55, 0.60), so the corpus cannot separate them) while rejecting 6 more prose false
    // positives out of 1,599 -- 0.0826 -> 0.0788. It is a precision-only tightening in the direction
    // this project's threat model prefers, but the corpus provides ZERO evidence for it either way,
    // the tracked 200-PDF watch item does not move (13/200 both ways), and leave-one-out does not
    // improve. It therefore needs its own decision and its own tests, not a silent ride on a
    // recalibration that found nothing to change.
    static final int WIDE_GRID_MIN_COLS = 7;
    static final double STREAM_CONFIDENCE_MIN_WIDE = 0.40;

    /** The admission bar a grid with {@code cols} columns must clear. */
    static double confidenceFloorFor(int cols) {
        return cols >= WIDE_GRID_MIN_COLS ? STREAM_CONFIDENCE_MIN_WIDE : STREAM_CONFIDENCE_MIN;
    }

    /** Production admission decision for a scored grid. */
    static boolean acceptsGrid(Grid grid) {
        return grid.confidence >= confidenceFloorFor(grid.colBounds.length - 1);
    }

    /** The admission bar as a function of column count, so a diagnostic harness can substitute one.
     *  Production always uses {@link #PRODUCTION_BAR}. */
    interface ConfidenceBar { double barFor(int cols); }

    static final ConfidenceBar PRODUCTION_BAR = StreamTableExtractor::confidenceFloorFor;

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

        // DIAGNOSTIC ONLY. Recorded by scoreGrid so a harness can attribute a confidence to its
        // terms; never read by production logic. hardReject names the all-or-nothing gate that
        // zeroed the confidence, or is null when the graded formula produced it.
        double tColConsistency, tViolation, tProse, tColCount, tNumeric, tProseColFrac;
        int nCols, nRows, nNumericCols, nNumericDataCols;
        String hardReject;
    }

    /**
     * Drop every DECLARED column that ends up with no word in it, returning the surviving gutter
     * list (the caller's list itself when nothing was dropped, so the common case allocates nothing).
     *
     * <p>WHY THIS IS NEEDED. {@code extractPage} computes the band from the UNTRIMMED block and then
     * scores the TRIMMED lines, so a column whose only content was a trimmed edge line survives in
     * {@code colBounds} with ZERO words. {@link #scoreGrid}'s column-consistency term only requires
     * {@code filled >= max(2, cols-1)}, so ONE permanently empty column is free, and it still counts
     * towards {@code colCount} -- which {@link TableExtractor#arbitrate}'s clause 5 reads as "stream
     * resolved a column the rulings missed", and {@code confidenceFloorFor} reads as evidence of a
     * wide grid. REPRODUCED: a grid with an entirely empty 4th declared column scored 0.950 and took
     * a region away from a fully-ruled, complete 3-column lattice table.
     *
     * <p>An empty column is removed by dropping ONE of its two boundaries, merging its (empty) span
     * into a neighbour -- never by moving a boundary, so no word ever changes column. Two linear
     * passes, no iteration to a fixed point: the forward pass merges each empty column rightwards
     * (carrying, so a run of empty columns collapses in one go) and the final column, which has no
     * boundary to its right, is merged leftwards afterwards. O(words + cols).
     */
    static List<Gutter> dropEmptyColumns(List<Line> lines, List<Gutter> gutters,
                                         float bandX0, float bandX1) {
        int cols = gutters.size() + 1;
        if (cols < 2) return gutters;
        float[] bounds = new float[cols + 1];
        bounds[0] = bandX0; bounds[cols] = bandX1;
        for (int i = 0; i < gutters.size(); i++) bounds[i + 1] = gutters.get(i).cx();

        int[] counts = new int[cols];
        for (Line l : lines) for (Word w : l.words) counts[colOf(w.cx(), bounds)]++;

        boolean anyEmpty = false;
        for (int c = 0; c < cols; c++) if (counts[c] == 0) { anyEmpty = true; break; }
        if (!anyEmpty) return gutters;

        // Forward pass: boundary index c+1 is gutters[c]. Dropping it merges column c into c+1.
        List<Gutter> keep = new ArrayList<>(gutters.size());
        for (int c = 0; c < cols - 1; c++) {
            if (counts[c] != 0) keep.add(gutters.get(c));
        }
        // The last column has no boundary to its right; merge it LEFTwards by dropping the last
        // surviving gutter. (If nothing survives there is only one column left and nothing to do.)
        if (counts[cols - 1] == 0 && !keep.isEmpty()) keep.remove(keep.size() - 1);
        return keep;
    }

    static Grid scoreGrid(List<Line> lines, List<Gutter> gutters, float bandX0, float bandX1) {
        Grid grid = new Grid();
        gutters = dropEmptyColumns(lines, gutters, bandX0, bandX1);
        grid.gutters = gutters; grid.rows = lines;
        int cols = gutters.size() + 1;
        int rows = lines.size();
        float[] bounds = new float[cols + 1];
        bounds[0] = bandX0; bounds[cols] = bandX1;
        for (int i = 0; i < gutters.size(); i++) bounds[i + 1] = gutters.get(i).cx();
        grid.colBounds = bounds;
        grid.nCols = cols; grid.nRows = rows;
        if (cols < 2 || rows < 3) { grid.confidence = 0; grid.hardReject = "cols<2||rows<3"; return grid; }

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
        // The cols==2 gate below asks a different question than the numericBonus term does, and needs
        // its own, row-based, answer -- see numericDataColumnCount's doc.
        int numericDataCols = numericDataColumnCount(lines, bounds);
        grid.nNumericDataCols = numericDataCols;
        grid.numericLeanColumn = numericCols > 0 || numericDataCols > 0;

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
        grid.tColConsistency = colConsistency;
        grid.tViolation = violationScore;
        grid.tProse = proseScore;
        grid.tColCount = Math.min(1, (cols - 2) / 2.0);
        grid.tNumeric = numericBonus;
        grid.tProseColFrac = proseColumnFraction;
        grid.nNumericCols = numericCols;

        // The two remaining ALL-OR-NOTHING gates. Deliberately applied AFTER every term above has
        // been recorded on the Grid, so a diagnostic harness can see what a hard-rejected candidate
        // would have graded at. Behaviour is unchanged: either gate still zeroes the confidence.
        // The cols==2 gate's own numeric test is numericDataColumnCount (row-based), NOT numericBonus
        // (word-based). See that method's doc for the measured reason; numericBonus keeps its
        // word-based definition everywhere else so this change cannot move any other term.
        if (cols == 2 && numericDataCols == 0) {
            grid.confidence = 0; grid.hardReject = "cols==2-nonnumeric"; return grid;
        }
        if (proseColumnFraction > VETO_COLUMN_MAJORITY_FRACTION && numericCols == 0) {
            grid.confidence = 0;
            grid.hardReject = "prose-veto";
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

    // ------------------------------------------------------- the cols==2 gate's own numeric test
    //
    // MEASURED PROBLEM (lever-3 residual measurement: 77-PDF corpus + the 1,599-PDF real-world
    // sample). The full pipeline's residual gate ceiling -- what a ground-truth oracle could still
    // add by overriding the gate -- is +0.0211 MACRO (0.6884 -> 0.7094, POOLED + dedup, ALL-77), and
    // 4 of the 5 candidates that make it up are hard-rejected by the cols==2 gate. Dumping their
    // actual extracted content split them cleanly into two kinds:
    //
    //   * TWO GENUINE TABLES whose numeric data column is diluted below the gate's 0.70 bar by that
    //     column's own multi-word HEADER LABEL:
    //       eu-006 p2  "Retailer | Own Brands Market Shares" over Monoprix|28%, Casino|25%,
    //                  Intermarche|23%, Carrefour|22%, Auchan|19%, Leclerc|10%
    //                  -> 6 numeric words against 4 header words = 0.600 < 0.70, REJECTED
    //       eu-014 p2  "Indicators | Weight of indicator in 2006" over Employment|40,
    //                  Further studies|15, Dropping out|15, ... -> 0.636 < 0.70, REJECTED
    //   * TWO FABRICATIONS: us-011a p2 and p3, which are running PROSE paragraphs cut in half by a
    //     spurious gutter ("The U.S. General Services Administration's (GSA) Office of Citizen ||
    //     Services and"). They score a positive adjacency delta only because a real mini-table
    //     ("Program | Budget", "Performance.gov | $1.1M") happens to sit lower in the same block.
    //     Those two must STAY rejected -- admitting them is exactly the "fabricate a table over
    //     prose" failure this project refuses.
    //
    // So the addressable defect is precisely the header dilution, and the fix is to ask the gate's
    // question ("is this two-column block a data table?") in the unit a table is actually made of:
    // ROWS, not words. A column is a numeric DATA column when at least
    // NUMERIC_DATA_ROW_MAJORITY of its own OCCUPIED ROWS are ENTIRELY numeric. A four-word header
    // label then contributes exactly one row to the denominator instead of four words to it, so it
    // can no longer outvote six rows of data; and prose carrying incidental numerals -- which is
    // what produces a middling numeric WORD fraction -- has no all-numeric row at all, so it scores
    // 0 under this test rather than creeping towards the bar.
    //
    // MEASURED SEPARABILITY over every 2-column all-non-numeric candidate the gate rejects (66 on
    // the corpus, 12 on the 200-PDF real-world prose sample):
    //
    //   definition                                     genuine  prose-fabrication  corpus-other  PROSE
    //   word fraction >= 0.70 (production, pre-fix)        0/2           0/2            0/62      0/12
    //   row majority  >= 0.70 (this method)                2/2           0/2            2/62      0/12
    //   word fraction, first row skipped                   2/2           0/2            2/62      1/12
    //
    // i.e. the row-majority definition is the only one of the three that recovers both genuine
    // tables while flagging NO page of the real-world prose sample; the "skip the header row" variant
    // reaches the same corpus recall but leaks a real-world false positive, which is why the test is
    // row-majority rather than a header exclusion. The two corpus-other candidates it newly admits
    // (eu-015 p1, us-009 p1) are worth -0.0014 and -0.0435 adjacency F1 on their own documents, an
    // order of magnitude less than the +0.8462 and +0.0698 the two genuine tables bring.
    //
    // DELIBERATELY NARROW: this test is consulted ONLY by the cols==2 gate. numericBonus (the graded
    // 0.10-weighted term) and the prose veto's own numericCols==0 condition keep the word-based
    // definition unchanged, so no other term of the confidence formula and no other gate moves --
    // the alternative (redefining numeric-ness globally) would perturb every grid's confidence and
    // therefore every Step A' merge decision on every page, for no measured benefit.
    //
    // COST: no new scan of anything unbounded -- one extra O(rows x words-per-row) pass over the
    // SAME block scoreGrid is already iterating several times, so the per-block cost is a small
    // constant multiple of what it already was and stays inside Step D's own
    // MAX_STREAM_PAGE_BLOCK_WORK page budget (which charges block word counts, not passes).

    /** A numeric DATA column must be entirely numeric on at least this fraction of its own occupied
     *  rows. Deliberately the SAME 0.70 as the word-based numericBonus test above -- this change is
     *  about which UNIT the fraction is measured in (rows vs. words), not about relaxing the bar, and
     *  reusing the value keeps that explicit. Measured sensitivity on the corpus's own 66 rejected
     *  2-column candidates: the two genuine tables sit at 0.857 and 0.857, and the nearest negative
     *  under this definition sits at 0.750, so 0.70 admits both genuine tables with margin while any
     *  value above 0.75 would lose them along with the negative. */
    static final double NUMERIC_DATA_ROW_MAJORITY = 0.70;

    /**
     * How many of the grid's columns are numeric DATA columns: entirely numeric on at least {@link
     * #NUMERIC_DATA_ROW_MAJORITY} of the rows they actually occupy. A row counts towards the
     * numerator only if EVERY word of that column on that row is numeric -- one word of prose in the
     * cell disqualifies the row, which is what makes prose-with-numerals score 0 rather than
     * middling. See the block comment above for the measurement behind this.
     */
    static int numericDataColumnCount(List<Line> lines, float[] bounds) {
        int cols = bounds.length - 1;
        int numericDataCols = 0;
        for (int c = 0; c < cols; c++) {
            int occupied = 0, allNumeric = 0;
            for (Line l : lines) {
                int inCol = 0, inColNumeric = 0;
                for (Word w : l.words) {
                    if (colOf(w.cx(), bounds) != c) continue;
                    inCol++;
                    if (w.numeric) inColNumeric++;
                }
                if (inCol > 0) {
                    occupied++;
                    if (inColNumeric == inCol) allNumeric++;
                }
            }
            if (occupied > 0 && (double) allNumeric / occupied >= NUMERIC_DATA_ROW_MAJORITY) {
                numericDataCols++;
            }
        }
        return numericDataCols;
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
    // down to nothing one line at a time in an unbounded loop. A real title/caption is
    // essentially always 1-3 physical lines (a wrapped 2-3 line caption at most, per the
    // us-007/us-020 samples in the task-9c report); 3 gives headroom above that without
    // allowing trimming to eat meaningfully into real table rows.
    //
    // Task 9g measured that simply RAISING this shared cap to reach a real 10-line footnote
    // block (us-007) is a net loss: several of us-018's OWN tables have their genuine LAST
    // data/summary rows sit close enough to this same "few non-conforming-looking lines" bar
    // that a cap of just 5 already starts eating them (measured: us-018 F1 0.190 -> 0.041 at
    // cap=5, vs. 0.352 for us-007 at the same cap -- a bad trade against the higher-priority
    // fixture). See {@link #stripTrailingFootnoteBlock} for the targeted fix used instead,
    // which does not touch this cap at all.
    static final int MAX_EDGE_TRIM_ITERATIONS = 3;

    // Task 9h: exempts a leading/trailing candidate line from the straddles-a-gutter check below
    // when it has the GEOMETRIC SHAPE of a real (if visually sparse) table row rather than prose
    // -- specifically, at least one gap between two adjacent words on the line that is much wider
    // than ordinary word-to-word reading-flow spacing. Measured directly off this corpus
    // (task-9h-debris-trim-report.md): ordinary prose/caption/title lines -- even ones that run
    // the full width of the table and so incidentally straddle several gutters -- have UNIFORMLY
    // small inter-word gaps (~1x medianSpace: us-018's own title line measures 2.50pt gaps against
    // a medianSpace of 2.27, ratio 1.1x; its widest single gap, where "years" wraps to "2003–04",
    // is still only 4.55x). A genuine table row/header line, even a 2-word merged/spanning header
    // label, ALWAYS has at least one gap that is the visual GUTTER between two cells -- tens of
    // points wide (us-018's "Actual    Projected" header: 194.9pt = 85.9x; eu-004's "1996  1991
    // growth in share" header: 101.1pt = 33.2x; the narrowest genuine-row gap measured in this
    // corpus, eu-004's "national   stores   national   stores" header fragment, is still 34.4pt =
    // 11.3x). 8x sits with comfortable margin between the largest measured prose-line ratio (4.55x)
    // and the smallest measured genuine-row ratio (11.3x). This is what lets a genuinely stacked
    // header line -- multiple columns, individual labels centered over their own column-group and
    // so incidentally straddling their span's OWN internal gutter -- survive trimming without
    // reopening the header-flattening regression this exemption must not touch: it only WAIVES the
    // straddle check, it does not waive the single-column-while-body-is-multi-column check below,
    // which is what still catches a genuinely single-column caption fragment regardless of its own
    // internal word spacing.
    static final float EDGE_COLUMN_GAP_FACTOR = 8f;

    // Task 9h: a SECOND, independent debris signal for leading/trailing lines that neither
    // straddle a gutter NOR populate just one column, but are still clearly not a table row --
    // e.g. eu-004's "(measured in hundreds)" unit-caption line, which sits at 2 populated columns
    // (not 1) and straddles nothing (its few words happen to land cleanly inside existing column
    // bands), yet is followed by a distinctly larger-than-usual vertical gap before the real
    // header line resumes (measured: 26.6pt vs. this block's own 13.6pt median row pitch, a 1.95x
    // outlier) -- the residual "half blank line" of extra leading a caption block typically gets,
    // just under Step A's own BLOCK_GAP_FACTOR (1.6x) which would otherwise have split it off as
    // its own block. Deliberately requires BOTH signals together (not either alone): a large gap
    // alone is not decisive (a real header can legitimately have a bit more leading before the
    // data rows start), and few words alone is not decisive either (a real, sparse numeric table
    // can easily have short 2-3-word data rows) -- but a short line ALSO sitting hard against an
    // outlier gap is not part of the table's own row rhythm. EDGE_LINE_GAP_OUTLIER_FACTOR reuses
    // BLOCK_GAP_FACTOR's own value/rationale (same phenomenon, a section break too small for Step
    // A to have caught on its own) rather than inventing a second, differently-tuned threshold for
    // the same thing. EDGE_FEW_WORDS_FACTOR=0.75 is the smallest value that still catches
    // "(measured in hundreds)" (3 words against this block's 4-word median, ratio 0.75) without
    // going low enough to flag a typical short-but-real data row's word count on its own (this
    // signal never fires alone -- see the AND above).
    static final float EDGE_LINE_GAP_OUTLIER_FACTOR = BLOCK_GAP_FACTOR;
    static final float EDGE_FEW_WORDS_FACTOR = 0.75f;

    // Bounds the footnote-marker scan below: how many of the block's OWN trailing lines (after
    // the ordinary capped trim above has already run) get examined for a footnote/legend-start
    // marker. Generous (a real footnote block is rarely more than a handful of lines, but this
    // gives headroom) while remaining a small, explicit, O(block size) constant -- the scan
    // itself is a single linear pass, so this only bounds how far into the block it may reach,
    // not the cost per line examined.
    static final int MAX_FOOTNOTE_MARKER_SCAN = 40;

    // A footnote/legend block's own first line is often introduced by a short, ALL-CAPS "label:"
    // token -- "KEY:", "NOTE:", "SOURCE:" (see us-007's and us-018's own fixtures). Once such a line
    // is found near a block's tail, everything from it to the end of the block is the footnote --
    // true regardless of whether each individual trailing line would, on its own, straddle a gutter
    // or look single-column (several of us-007's legend lines wrap onto a second physical line
    // and populate 2+ columns, so {@link #isNonConformingEdge} alone under-trims them one at a
    // time; this marker instead removes the whole block in one decisive cut).
    //
    // THE MARKER ALONE IS NOT ENOUGH -- see #isFootnoteShapedLine. This comment used to claim such a
    // token appears "nowhere else in real tabular data in this corpus". The corpus it was calibrated
    // on is 77 ACADEMIC papers. In the population this tool actually triages -- invoices, remittance
    // advices, mail attachments -- "TOTAL:", "SUBTOTAL:", "VAT:", "REF:", "IBAN:", "BIC:", "DUE:"
    // are exactly how the most important ROWS are labelled, and the trim runs before scoreGrid /
    // buildHit, so a matched row never becomes a cell and nothing sets `truncated`. REPRODUCED: a
    // 7-row invoice emitted a 5x4 table, with "TOTAL: 77 283.75" and "VAT: 56.75" present on the page
    // and absent from report.json. A second, unrelated instance was measured on the 200-PDF
    // real-world sample: a letterhead block's "PH: 281-219-0465" / "Fax 281-219-0484" pair.
    private static final java.util.regex.Pattern FOOTNOTE_MARKER =
        java.util.regex.Pattern.compile("^[A-Z]{2,}:$");

    // #isFootnoteShapedLine's word-count requirement for a NON-bare marker line, i.e. one that
    // carries footnote text on the marker's own line. A footnote is a continuous prose run; a data
    // row labelled "TOTAL:" is a label plus one or two values.
    //
    // MEASURED, over every marker line the rule fired on across the whole 77-document scoring corpus
    // AND the 200-PDF real-world sample (36 distinct lines). Genuine footnote markers are either BARE
    // (exactly one word: "KEY:", "PIN:") or long prose runs -- the shortest genuine multi-word marker
    // line in the entire population is 5 words ("SOURCE: CONAB - Suvey: Jun/2013"), and the next is
    // 11. The ONLY 2-word marker line measured anywhere is the "PH: 281-219-0465" false positive. 4
    // therefore separates the two populations with a word of headroom on each side and changes
    // NOTHING the corpus can see (no corpus marker line has 2, 3 or 4 words).
    static final int FOOTNOTE_MIN_PROSE_WORDS = 4;

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
     * Median line-to-line pitch of an ordered (top-to-bottom) line list -- the one shared
     * definition of "the page's typical line spacing", used both by Step A's split threshold and
     * Step A''s merge cap so the two can never drift apart.
     * <p>The 0.5f floor is a degenerate-input guard (near-zero/overlapping line pitch): without
     * it a zero-ish median would make {@link #BLOCK_GAP_FACTOR}*median ~0, so every nonzero gap
     * -- however tiny -- would trigger a split, over-fragmenting into near-useless 1-2 line
     * "blocks". This is purely a defensive floor for pathological input, not expected on real
     * PDFs (real line pitch is always a real fraction of the font size).
     */
    static float pageMedianPitch(List<Line> lines) {
        if (lines.size() < 2) return 1f;
        float[] pitches = new float[lines.size() - 1];
        for (int i = 1; i < lines.size(); i++) pitches[i - 1] = lines.get(i).yTop - lines.get(i - 1).yTop;
        Arrays.sort(pitches);
        return Math.max(pitches[pitches.length / 2], 0.5f);
    }

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

        float medianPitch = pageMedianPitch(lines);
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
     * non-conforming if: it has fewer than 2 words; OR any of its words straddles a gutter
     * center AND it lacks the wide-internal-gap shape of a real (if sparse) row/header line
     * ({@link #EDGE_COLUMN_GAP_FACTOR} -- see that field's doc for why this exemption cannot
     * reopen the header-flattening regression); OR all its words fall into a single column
     * while the block's OTHER (remaining) lines occupy 2+ columns; OR it sits hard against a
     * larger-than-typical vertical gap to its remaining neighbor AND has very few words relative
     * to the block's own median ({@link #EDGE_LINE_GAP_OUTLIER_FACTOR}/{@link
     * #EDGE_FEW_WORDS_FACTOR} -- a second, independent signal for debris that neither straddles
     * a gutter nor is single-column, e.g. a short unit-caption line like "(measured in
     * hundreds)"). Trimming is capped at {@link #MAX_EDGE_TRIM_ITERATIONS} per end. {@code
     * gutters}/{@code bandX0}/{@code bandX1} (the block's own column model) and the block-wide
     * median line pitch/words-per-line stats are all computed ONCE, up front, from the original
     * (untrimmed) {@code block} -- NOT recomputed as lines are dropped -- only the surviving
     * line list changes.
     */
    static List<Line> trimEdgeLines(List<Line> block, List<Gutter> gutters, float bandX0, float bandX1,
                                     float medianSpace) {
        List<Line> cur = new ArrayList<>(block);
        float[] bounds = colBoundsOf(gutters, bandX0, bandX1);
        EdgeStats stats = EdgeStats.of(block);
        for (int i = 0; i < MAX_EDGE_TRIM_ITERATIONS && cur.size() > 1; i++) {
            float neighborGap = cur.get(1).yTop - cur.get(0).yTop;
            if (!isNonConformingEdge(cur.get(0), cur, bounds, gutters, medianSpace, stats, neighborGap)) break;
            cur.remove(0);
        }
        for (int i = 0; i < MAX_EDGE_TRIM_ITERATIONS && cur.size() > 1; i++) {
            float neighborGap = cur.get(cur.size() - 1).yTop - cur.get(cur.size() - 2).yTop;
            if (!isNonConformingEdge(cur.get(cur.size() - 1), cur, bounds, gutters, medianSpace, stats, neighborGap)) break;
            cur.remove(cur.size() - 1);
        }
        return stripTrailingFootnoteBlock(cur, medianSpace);
    }

    /** Block-wide median line-pitch and median words-per-line, computed once from the ORIGINAL
     *  (untrimmed) block -- the reference stats {@link #isNonConformingEdge}'s two new signals
     *  compare each edge candidate against. Kept as a tiny immutable holder rather than two loose
     *  parameters threaded through every call. */
    private static final class EdgeStats {
        final float medianPitch;
        final float medianWords;
        private EdgeStats(float medianPitch, float medianWords) {
            this.medianPitch = medianPitch; this.medianWords = medianWords;
        }
        static EdgeStats of(List<Line> lines) {
            if (lines.size() < 2) return new EdgeStats(1f, 1f);
            float[] pitches = new float[lines.size() - 1];
            for (int i = 1; i < lines.size(); i++) pitches[i - 1] = lines.get(i).yTop - lines.get(i - 1).yTop;
            Arrays.sort(pitches);
            float medianPitch = Math.max(pitches[pitches.length / 2], 0.5f);
            int[] wordCounts = new int[lines.size()];
            for (int i = 0; i < lines.size(); i++) wordCounts[i] = lines.get(i).words.size();
            Arrays.sort(wordCounts);
            float medianWords = Math.max(wordCounts[wordCounts.length / 2], 1f);
            return new EdgeStats(medianPitch, medianWords);
        }
    }

    /** True if {@code line} has at least one adjacent-word gap wider than {@link
     *  #EDGE_COLUMN_GAP_FACTOR} times {@code medianSpace} -- the shape of a real column gutter
     *  between two cells, as opposed to ordinary reading-flow word spacing. {@code line.words}
     *  is already sorted by {@code x0} (see {@link #buildLines}), so a single adjacent pass
     *  suffices. */
    private static boolean hasColumnShapedGap(Line line, float medianSpace) {
        float threshold = EDGE_COLUMN_GAP_FACTOR * Math.max(medianSpace, 0.1f);
        List<Word> words = line.words;
        for (int i = 1; i < words.size(); i++) {
            if (words.get(i).x0 - words.get(i - 1).x1 > threshold) return true;
        }
        return false;
    }

    /**
     * Task 9g fix for defect 3 (the dominant remaining defect on us-007): find the FIRST line,
     * within the last {@link #MAX_FOOTNOTE_MARKER_SCAN} lines of {@code lines}, whose first word
     * matches {@link #FOOTNOTE_MARKER} (e.g. "KEY:", "NOTE:", "SOURCE:") <b>and which is itself
     * shaped like a footnote rather than like one of this block's data rows</b> ({@link
     * #isFootnoteShapedLine}), and drop it and every line after it -- a footnote/legend block, once
     * it starts, runs to the end of the block. If no such line is found, {@code lines} is returned
     * unchanged. Never trims below 3 lines (a conforming table needs at least that many to be worth
     * keeping at all -- matches the pre-existing {@code size() > 1} guards above and the {@code
     * trimmed.size() < 3} reject in {@code extractPage}).
     *
     * <p>The shape test is what stops this rule eating real invoice rows -- see {@link
     * #FOOTNOTE_MARKER}'s own comment for the reproduced content loss it caused without one. A line
     * that fails the shape test does NOT abort the scan: a genuine footnote further down the tail is
     * still found and still trimmed (an invoice whose totals row is followed by a real "NOTE:" block
     * keeps the totals and loses the note).
     */
    private static List<Line> stripTrailingFootnoteBlock(List<Line> lines, float medianSpace) {
        int scanFrom = Math.max(0, lines.size() - MAX_FOOTNOTE_MARKER_SCAN);
        for (int i = scanFrom; i < lines.size(); i++) {
            List<Word> words = lines.get(i).words;
            if (words.isEmpty()) continue;
            if (i < 3) continue;                           // never truncate down to <3 real rows
            if (FOOTNOTE_MARKER.matcher(words.get(0).text).matches()
                    && isFootnoteShapedLine(lines.get(i), medianSpace)) {
                return new ArrayList<>(lines.subList(0, i));
            }
        }
        return lines;
    }

    /**
     * Is {@code line} shaped like the FIRST LINE OF A FOOTNOTE rather than like a data row that
     * merely happens to be labelled with an ALL-CAPS "WORD:" token? Both conditions must hold:
     *
     * <ol>
     *   <li><b>It is one continuous text run, not a row of cells.</b> Reuses {@link
     *       #hasColumnShapedGap} -- the same already-load-bearing, already-calibrated discriminator
     *       {@link #isNonConformingEdge} uses: a real row of cells ALWAYS has at least one
     *       inter-cell gutter gap over {@link #EDGE_COLUMN_GAP_FACTOR} (8x) the median space, while
     *       running prose does not (measured maxima: 4.55x for prose, 11.3x for the narrowest
     *       genuine row in the corpus). Across the 36 distinct marker lines measured on the corpus
     *       plus the 200-PDF sample, every genuine footnote's widest internal gap is at most 8pt --
     *       roughly 1.5x a median space -- while "TOTAL: 77 283.75" has ~100pt gaps at its
     *       gutters.</li>
     *   <li><b>It is either a BARE marker or a real prose run</b> ({@link
     *       #FOOTNOTE_MIN_PROSE_WORDS}) -- a label plus one or two values is a row, not a footnote.
     *       This is what protects a narrow "TOTAL: 283.75" whose two words happen to sit inside one
     *       wide column (so condition 1 alone would not save it) and the measured real-world
     *       "PH: 281-219-0465".</li>
     * </ol>
     *
     * <p>DELIBERATELY NOT USED: an unconditional "never trim a line with a numeric word in another
     * column" veto. It reads well but was MEASURED to break genuine footnotes on this corpus -- e.g.
     * "NOTE: Detail may not sum to 100 percent due to rounding." carries the numeric token "100"
     * mid-sentence, several columns away from the marker. Condition 1 already refuses every numeric
     * data row measured, because a numeric CELL is separated from its label by a gutter and a numeric
     * word in prose is not.
     */
    static boolean isFootnoteShapedLine(Line line, float medianSpace) {
        if (hasColumnShapedGap(line, medianSpace)) return false;
        return line.words.size() == 1 || line.words.size() >= FOOTNOTE_MIN_PROSE_WORDS;
    }

    private static float[] colBoundsOf(List<Gutter> gutters, float bandX0, float bandX1) {
        float[] bounds = new float[gutters.size() + 2];
        bounds[0] = bandX0; bounds[bounds.length - 1] = bandX1;
        for (int i = 0; i < gutters.size(); i++) bounds[i + 1] = gutters.get(i).cx();
        return bounds;
    }

    private static boolean isNonConformingEdge(Line line, List<Line> context, float[] bounds,
            List<Gutter> gutters, float medianSpace, EdgeStats stats, float neighborGap) {
        // REVIEWED for the same over-reach the footnote rule had (does this eat a real trailing row
        // that carries a single value, e.g. a grand total whose label is on the line above?) and left
        // alone, because it is REDUNDANT for exactly that case rather than load-bearing: a one-word
        // line has ownCols.size() == 1, so the single-column-while-the-body-is-multi-column rule below
        // already returns true for it in every block whose other lines span 2+ columns -- which is
        // every real table. MEASURED: relaxing this to {@code words.isEmpty()} leaves the 77-document
        // corpus and the 200-PDF real-world sample BYTE-IDENTICAL at both page scopes (macro 0.8070
        // all-pages / 0.7878 shipping, prose FP 15/200, all unchanged to four decimals). It is
        // therefore not a second instance of the footnote defect, and changing it would be churn with
        // nothing to pin it.
        if (line.words.size() < 2) return true;

        if (!hasColumnShapedGap(line, medianSpace)) {
            for (Word w : line.words) {
                for (Gutter g : gutters) if (w.x0 < g.cx() && w.x1 > g.cx()) return true;
            }
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

        boolean bigGap = neighborGap > EDGE_LINE_GAP_OUTLIER_FACTOR * stats.medianPitch;
        boolean fewWords = line.words.size() <= EDGE_FEW_WORDS_FACTOR * stats.medianWords;
        if (bigGap && fewWords) return true;

        return false;
    }

    // ------------------------------------------------------------ Step A': block re-merge (9m)
    //
    // Step A splits on ONE signal only -- a vertical gap over BLOCK_GAP_FACTOR (1.6x) times the
    // page's median line pitch. That signal is right about where a page's REGIONS change, but it
    // is blind to the commonest real-world layout: a single table with an internal band gap
    // (above a totals row, below a header band, around a section subhead). Measured against the
    // ICDAR 2013 -str.xml cell geometry (each GT table's own bbox, converted into our top-left
    // coords), 23 of the 163 geometry-carrying ground-truth tables -- on 13 of the 77 scored PDFs
    // -- are split across MORE THAN ONE Step A block (us-002 p1 and p3: 5 blocks each inside one
    // GT table; us-032 p1: 5; us-001 p1: 4; us-018 p4-p7 and us-017 p2-p4: 2 each). The reverse
    // error is rare (only 8 blocks straddle >1 GT table). Those 13 PDFs score adjacency F1 0.352
    // (macro 0.275) against 0.673 (0.568) for the 54 cleanly-segmented ones.
    //
    // Step A' is a second pass that re-merges left-to-right (top-to-bottom) adjacent blocks when
    // the evidence says the table CONTINUES across the gap. All THREE conditions below were
    // measured independently load-bearing on the real bake-off; dropping any one loses real
    // score, and two of them are what keep the pass from FABRICATING a table out of two genuine
    // siblings (the failure mode this tool cares about most):
    //
    //   1. the gap is at most BLOCK_MERGE_MAX_GAP_FACTOR x the page median pitch -- an inter-
    //      REGION gap on a real page is much wider than an intra-table band gap;
    //   2. the two blocks' COLUMN MODELS AGREE (their independently-found gutter centres line
    //      up) -- the direct evidence of structural continuity. Without this test the corpus
    //      micro-F1 still rises (0.548) but the MACRO collapses to 0.489, i.e. below baseline:
    //      it wins on a few big files by merging indiscriminately and wrecks many small ones;
    //   3. the MERGED block, scored on its own band exactly as the per-block loop would score it,
    //      still clears STREAM_CONFIDENCE_MIN. Two grids that agree on gutters can still form a
    //      non-grid when unioned (e.g. sibling tables whose numeric column is in a DIFFERENT
    //      place, so the union has no numeric column left and trips the prose veto). Dropping
    //      this gate costs 0.018 micro and, measured on the corpus canaries, is what neutralises
    //      eu-020 (-0.212 -> 0.000), us-025 (-0.179 -> +0.028) and eu-003 (-0.184 -> -0.001).
    //
    // Cashable ceiling for this lever, two independent estimates: a GT-bbox-SUPERVISED merge
    // (merge consecutive blocks that sit inside the same GT bbox) scores adjacency F1 0.560 under
    // production pairing; this rule scores 0.566. They agree, i.e. the rule already captures
    // essentially all of the available segmentation prize. Reference points: no Step A split at
    // all scores 0.273 (Step A itself must stay), and a confidence-only agglomeration (no column
    // test) scores 0.541 while DOUBLING the prose false-positive rate to 0.060.

    // Merge condition 1. Sensitivity (real bake-off, breuel, adjacency micro-F1): gap cap
    // 2.0x -> 0.549, 2.5x -> 0.566, 3.0x -> 0.566, uncapped -> 0.565. 2.5x is the SMALLEST value
    // that both keeps the full corpus gain and keeps a genuine sibling-table gap unmerged: the
    // gap in StreamSegmentationTest#multipleTablesOnOnePageAreDetectedSeparately measures 6.00x
    // the median pitch, and siblingTablesJustOverTheGapCapStaySeparate pins the 3.0x boundary the
    // corpus itself cannot test (the entire ICDAR corpus contains only 9 adjacent block pairs
    // that span two DIFFERENT ground-truth tables). The cap costs nothing on the corpus (0.5656
    // with it, 0.5653 without) and is pure over-merge insurance. Measured separability of the
    // pairs the corpus DOES have: SAME-table pairs (n=83) have median gap 1.81x the pitch
    // (p10..p90 1.66..2.07); DIFFERENT-table pairs (n=9) median 2.00x with p90 3.19x.
    static final float BLOCK_MERGE_MAX_GAP_FACTOR = 2.5f;

    // Merge condition 2's matching tolerance, in multiples of medianSpace: two gutters are "the
    // same column boundary" if their centres are within this distance. Sensitivity: 0.5x -> 0.546,
    // 1.0x -> 0.554, 2.0x -> 0.566. A real table's column boundaries wander slightly between
    // bands (a header band's gutter is bounded by different words than the body's), so the
    // tolerance has to be about a space wide, not sub-point.
    static final float BLOCK_MERGE_GUTTER_TOL_FACTOR = 2.0f;

    // Merge condition 2's agreement threshold: this fraction (rounded up, minimum 1) of the
    // SMALLER gutter set must have a counterpart in the other. Sensitivity: 0.34 -> 0.566,
    // 0.5 -> 0.566, 1.0 (every gutter must match) -> 0.517. Requiring a total match is too
    // strict: a header band legitimately resolves fewer columns than the body it heads, so its
    // gutter set is a coarse SUBSET, and demanding all of them match refuses exactly the merges
    // this pass exists for.
    static final float BLOCK_MERGE_MIN_AGREE_FRACTION = 0.5f;

    // Step A''s own charged work budget, in the same "real work" currency the rest of this file
    // uses: obstacles (words) handed to a bounded sub-search. The pass runs the gutter finder once
    // per base block PLUS once per merge probe, and each probe in a chain runs on a LARGER block
    // than the last -- so on a page of K mutually-agreeing blocks totalling W words the probe
    // chain charges roughly W*K/2, i.e. it is QUADRATIC in the block count even though every
    // individual finder call is independently bounded by MAX_GUTTER_SCAN_WORK. That composite
    // cost is the one real DoS surface this pass adds, and it needs a budget of its own; on
    // exhaustion the pass returns the UNMERGED partition -- byte-identical to pre-Step-A'
    // behaviour -- never a partial merge and never a thrown page.
    //
    // SIZING RULE: the merge pass may not scan more obstacles than the base per-block pass itself
    // could, i.e. one MAX_STREAM_WORDS-worth of words searched once. That is a hard, principled
    // ceiling rather than a guess, and it is generous against measured reality: the worst-case
    // charge (base pass plus a full all-pairs-merge probe chain, an upper bound since a real
    // page's gap/column tests reject most attempts) over 301 real pages -- the whole 77-PDF
    // bake-off corpus plus the 200-PDF real-world prose sample -- is 11,386 (us-021 p3), mean
    // 1,576, with exactly ONE page over 10,000 and none over 100,000. So this budget leaves 5.3x
    // headroom over the worst real page while capping the pathological
    // thousands-of-tiny-mutually-agreeing-blocks page. Measured effect of that cap: an adversarial
    // 200-block page (3600 words, every pair agreeing) charges ~364,000 and takes ~4.6s of
    // branch-and-bound if allowed to run; under this budget it is cut off at ~0.7s and returns the
    // unmerged partition. A page's REAL total gutter work is therefore at most roughly doubled by
    // Step A', not multiplied by the block count.
    static final long MAX_BLOCK_MERGE_WORK = MAX_STREAM_WORDS;

    /**
     * One output group of Step A': the (possibly merged) block plus the gutter set already
     * computed for it, on its OWN band, by the merge pass -- so the per-block loop in {@link
     * #extractPage} never re-runs the finder on a block Step A' has already searched. Without
     * this cache the added cost would be a second full gutter search per block (~2x page work)
     * instead of just the merge probes.
     *
     * <p>{@code gutters} is null when the pass did not compute them (a sub-3-line block, which
     * the per-block loop rejects anyway; a single-block page, where there is nothing to merge; or
     * a block reached after the work budget ran out) -- the caller then finds them itself, as
     * before. {@code gutterSearchOverflowed} records that the finder threw {@link
     * TableExtractor.RulingOverflowException} for this block: the caller must skip the block,
     * exactly as its own catch clause would have.
     */
    /** DIAGNOSTIC ONLY. One scored block candidate, as handed to the confidence gate. Produced only
     *  when a harness passes a sink to the 6-arg {@link #extractPage}; production never builds these. */
    static final class Candidate {
        final int page;
        final double confidence;
        final boolean passed;                       // did it clear the emit gate it was scored against?
        final TableExtractor.TableHit hit;          // may be null (buildHit found no cells)
        final Grid grid;
        Candidate(int page, double confidence, boolean passed,
                  TableExtractor.TableHit hit, Grid grid) {
            this.page = page; this.confidence = confidence; this.passed = passed;
            this.hit = hit; this.grid = grid;
        }
    }

    static final class BlockGroup {
        final List<Line> lines;
        final List<Gutter> gutters;                 // null = not precomputed; caller must find them
        final boolean gutterSearchOverflowed;       // true = finder aborted on this block; skip it
        BlockGroup(List<Line> lines, List<Gutter> gutters, boolean gutterSearchOverflowed) {
            this.lines = lines; this.gutters = gutters; this.gutterSearchOverflowed = gutterSearchOverflowed;
        }
    }

    /** The x-extent of a block's own words -- the band every per-block stage is measured against. */
    private static float[] bandOf(List<Line> block) {
        float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE;
        for (Line l : block) for (Word w : l.words) { x0 = Math.min(x0, w.x0); x1 = Math.max(x1, w.x1); }
        return new float[]{x0, x1};
    }

    private static long obstacleCountOf(List<Line> block) {
        long n = 0;
        for (Line l : block) n += l.words.size();
        return n;
    }

    /** Result of one Step A' probe: the gutters found on that block's own band, plus its gridness
     *  confidence (or -1 when the probe could not be completed -- overflowed, or trimmed away to
     *  fewer than 3 lines -- which always means "do not merge"). */
    private static final class Probe {
        final List<Gutter> gutters; final double confidence; final int cols;
        Probe(List<Gutter> gutters, double confidence, int cols) {
            this.gutters = gutters; this.confidence = confidence; this.cols = cols;
        }
        static final Probe FAILED = new Probe(null, -1, 0);
    }

    /** Runs exactly the per-block detection stages {@link #extractPage} runs (band -> finder ->
     *  {@link #trimEdgeLines} -> {@link #scoreGrid}), so a merge decision is made against the
     *  score the merged block would ACTUALLY get. A DoS abort from the finder means "do not
     *  merge" -- never lose the page. */
    private static Probe probeBlock(List<Line> block, GutterFinder finder, float medianSpace) {
        try {
            float[] b = bandOf(block);
            List<Gutter> gutters = finder.find(block, b[0], b[1], medianSpace);
            if (block.size() < 3) return new Probe(gutters, -1, gutters.size() + 1);
            List<Line> trimmed = trimEdgeLines(block, gutters, b[0], b[1], medianSpace);
            if (trimmed.size() < 3) return new Probe(gutters, -1, gutters.size() + 1);
            Grid g = scoreGrid(trimmed, gutters, b[0], b[1]);
            return new Probe(gutters, g.confidence, g.colBounds.length - 1);
        } catch (TableExtractor.RulingOverflowException e) {
            return Probe.FAILED;
        }
    }

    /**
     * Merge condition 2: do two independently-found gutter sets describe the SAME column model?
     * Both must be non-empty, and at least {@link #BLOCK_MERGE_MIN_AGREE_FRACTION} (rounded up,
     * minimum 1) of the smaller set's gutters must have a counterpart in the other within
     * {@code tol} of their centre.
     */
    static boolean columnModelsAgree(List<Gutter> a, List<Gutter> b, float tol) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return false;
        int matches = 0;
        for (Gutter x : a) {
            for (Gutter y : b) if (Math.abs(x.cx() - y.cx()) <= tol) { matches++; break; }
        }
        int minSize = Math.min(a.size(), b.size());
        return matches >= Math.max(1, (int) Math.ceil(BLOCK_MERGE_MIN_AGREE_FRACTION * minSize));
    }

    /** Step A' with the production work budget ({@link #MAX_BLOCK_MERGE_WORK}). */
    static List<BlockGroup> mergeAgreeingBlocks(List<List<Line>> base, GutterFinder finder,
                                                float medianSpace, float medianPitch) {
        return mergeAgreeingBlocks(base, finder, medianSpace, medianPitch, MAX_BLOCK_MERGE_WORK);
    }

    static List<BlockGroup> mergeAgreeingBlocks(List<List<Line>> base, GutterFinder finder,
                                                float medianSpace, float medianPitch, long maxWork) {
        return mergeAgreeingBlocks(base, finder, medianSpace, medianPitch, maxWork, PRODUCTION_BAR);
    }

    /**
     * Step A': re-merge adjacent Step A blocks whose column model is continuous across the gap.
     * See the block comment above {@link #BLOCK_MERGE_MAX_GAP_FACTOR} for the evidence and the
     * three conditions. {@code maxWork} is the charged budget (obstacles handed to the finder);
     * on exhaustion this returns the UNMERGED partition, keeping whatever gutter caches were
     * already validly computed -- i.e. the fail-safe is exactly pre-Step-A' behaviour.
     */
    static List<BlockGroup> mergeAgreeingBlocks(List<List<Line>> base, GutterFinder finder,
                                                float medianSpace, float medianPitch, long maxWork,
                                                ConfidenceBar mergeBar) {
        if (base.size() < 2) return unmerged(base, null);

        // Per-base-block column models. Sub-3-line blocks are never merge candidates (the
        // per-block loop rejects them outright, and an empty gutter set can never agree), so they
        // are not searched at all.
        List<Probe> baseProbes = new ArrayList<>(base.size());
        long work = 0;
        for (List<Line> b : base) {
            if (b.size() < 3) { baseProbes.add(new Probe(List.of(), -1, 0)); continue; }
            long cost = obstacleCountOf(b);
            if (work + cost > maxWork) return unmerged(base, baseProbes);   // budget out -> no merging
            work += cost;
            baseProbes.add(probeBlock(b, finder, medianSpace));
        }

        float gapCap = BLOCK_MERGE_MAX_GAP_FACTOR * medianPitch;
        float tol = BLOCK_MERGE_GUTTER_TOL_FACTOR * medianSpace;
        List<BlockGroup> out = new ArrayList<>();
        List<Line> cur = new ArrayList<>(base.get(0));
        Probe curProbe = baseProbes.get(0);
        // The group's REFERENCE column model for the next agreement test. On a merge this stays
        // the richer (larger) of the two base sets rather than becoming the merged block's own
        // freshly-found set: a merged band can resolve spurious extra gutters, and chaining off
        // those would let a group drift away from the column model it started with.
        List<Gutter> refGutters = curProbe.gutters;
        for (int i = 1; i < base.size(); i++) {
            List<Line> next = base.get(i);
            Probe nextProbe = baseProbes.get(i);
            float gap = next.get(0).yTop - cur.get(cur.size() - 1).yTop;
            boolean merged = false;
            if (gap <= gapCap && columnModelsAgree(refGutters, nextProbe.gutters, tol)) {
                List<Line> candidate = new ArrayList<>(cur);
                candidate.addAll(next);
                long cost = obstacleCountOf(candidate);
                if (work + cost > maxWork) return unmerged(base, baseProbes);  // budget out -> no merging
                work += cost;
                Probe cand = probeBlock(candidate, finder, medianSpace);
                if (cand.confidence >= mergeBar.barFor(cand.cols)) {
                    cur = candidate;
                    curProbe = cand;
                    if (nextProbe.gutters != null && refGutters != null
                            && nextProbe.gutters.size() > refGutters.size()) refGutters = nextProbe.gutters;
                    merged = true;
                }
            }
            if (!merged) {
                out.add(groupOf(cur, curProbe));
                cur = new ArrayList<>(next);
                curProbe = nextProbe;
                refGutters = nextProbe.gutters;
            }
        }
        out.add(groupOf(cur, curProbe));
        return out;
    }

    /** A group whose gutters are already known (or known to have overflowed) from {@code probe}. */
    private static BlockGroup groupOf(List<Line> lines, Probe probe) {
        if (probe == null) return new BlockGroup(lines, null, false);
        if (probe.gutters == null) return new BlockGroup(lines, null, true);   // finder aborted
        if (lines.size() < 3) return new BlockGroup(lines, null, false);       // never searched
        return new BlockGroup(lines, probe.gutters, false);
    }

    /** The fail-safe / no-op partition: every base block on its own, carrying only the gutter
     *  caches that were already validly computed ({@code probes} may be null or shorter than
     *  {@code base} if the budget ran out partway). */
    private static List<BlockGroup> unmerged(List<List<Line>> base, List<Probe> probes) {
        List<BlockGroup> out = new ArrayList<>(base.size());
        for (int i = 0; i < base.size(); i++) {
            Probe p = probes != null && i < probes.size() ? probes.get(i) : null;
            out.add(groupOf(base.get(i), p));
        }
        return out;
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
     * vertical gap (Step A), re-merge adjacent blocks whose column model is continuous across
     * the gap ({@link #mergeAgreeingBlocks}, Step A'), then for each block with >=3 lines
     * independently find its own band/gutters (reusing the set Step A' already found for it),
     * trim non-conforming edge lines (Step C), and score/build a hit (Step B) --
     * capped at {@link #MAX_STREAM_TABLES_PER_PAGE} hits and a per-page work budget (Step D). A
     * DoS abort ({@link TableExtractor.RulingOverflowException}) from an individual block's
     * gutter search or cell-count cap only skips THAT block, not the whole page -- unlike the
     * page-global glyph/word/line caps in buildWords/buildLines, which (as before) abort the
     * whole page with no partial output, since a breach there means the line data itself
     * couldn't be safely built at all.
     */
    static List<TableExtractor.TableHit> extractPage(int pageNum, List<TextPosition> glyphs, GutterFinder finder) {
        return extractPage(pageNum, glyphs, finder, PageFrame.IDENTITY);
    }

    /**
     * As {@link #extractPage(int, List, GutterFinder)}, reporting geometry in {@code frame}'s visual,
     * /Rotate-applied top-left frame -- the frame every other path in {@link TableExtractor} reports
     * in. THIS is the production entry point ({@code TableExtractor#extractStreamPage} passes the
     * page's own rotation/cropBox); the overloads without a frame keep the identity mapping, which is
     * exactly the previous behaviour and correct for every unrotated page. See {@link PageFrame}.
     */
    static List<TableExtractor.TableHit> extractPage(int pageNum, List<TextPosition> glyphs,
                                                     GutterFinder finder, PageFrame frame) {
        return extractPage(pageNum, glyphs, finder, frame, PRODUCTION_BAR, PRODUCTION_BAR, null);
    }

    /**
     * DIAGNOSTIC SEAM (measurement only; production always calls the 4-arg overload, which passes
     * {@link #PRODUCTION_BAR} for both gates and a null sink).
     *
     * <p>{@code emitBar} is the gate the per-block loop applies before emitting a hit; {@code mergeBar}
     * is the gate Step A' applies before accepting a merge. {@code sink}, when non-null, receives EVERY
     * candidate the pipeline scored -- including the ones {@code emitBar} rejected -- so a harness can
     * measure what the gate suppresses. Candidates are appended in page order; the hit inside a
     * candidate is fully built (so it can be scored) regardless of whether it was emitted.
     */
    static List<TableExtractor.TableHit> extractPage(int pageNum, List<TextPosition> glyphs,
                                                     GutterFinder finder, ConfidenceBar emitBar,
                                                     ConfidenceBar mergeBar, List<Candidate> sink) {
        return extractPage(pageNum, glyphs, finder, PageFrame.IDENTITY, emitBar, mergeBar, sink);
    }

    /** Full form: the diagnostic seam above plus the reporting {@link PageFrame}. */
    static List<TableExtractor.TableHit> extractPage(int pageNum, List<TextPosition> glyphs,
                                                     GutterFinder finder, PageFrame frame,
                                                     ConfidenceBar emitBar,
                                                     ConfidenceBar mergeBar, List<Candidate> sink) {
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

        List<BlockGroup> blocks = mergeAgreeingBlocks(splitIntoBlocks(lines), finder, medianSpace,
                                                      pageMedianPitch(lines), MAX_BLOCK_MERGE_WORK,
                                                      mergeBar);
        List<TableExtractor.TableHit> hits = new ArrayList<>();
        long pageWork = 0;

        for (BlockGroup group : blocks) {
            List<Line> block = group.lines;
            if (hits.size() >= MAX_STREAM_TABLES_PER_PAGE) break;
            if (block.size() < 3) continue;

            long charge = block.stream().mapToLong(l -> l.words.size()).sum();
            if (pageWork + charge > MAX_STREAM_PAGE_BLOCK_WORK) break; // page budget exhausted -> keep prior hits, stop
            pageWork += charge;

            // Step A' already ran the finder on this block's own band; skipping an overflowed
            // block here is exactly what the catch clause below would have done.
            if (group.gutterSearchOverflowed) continue;

            try {
                float bandX0 = Float.MAX_VALUE, bandX1 = -Float.MAX_VALUE;
                for (Line l : block) for (Word w : l.words) {
                    bandX0 = Math.min(bandX0, w.x0); bandX1 = Math.max(bandX1, w.x1);
                }

                List<Gutter> gutters = group.gutters != null ? group.gutters
                                     : finder.find(block, bandX0, bandX1, medianSpace);
                List<Line> trimmed = trimEdgeLines(block, gutters, bandX0, bandX1, medianSpace);
                if (trimmed.size() < 3) continue;               // Step C left too little to be a table -> reject block

                Grid grid = scoreGrid(trimmed, gutters, bandX0, bandX1);
                boolean pass = grid.confidence >= emitBar.barFor(grid.colBounds.length - 1);
                if (!pass && sink == null) continue;

                TableExtractor.TableHit hit = buildHit(pageNum, grid, frame);
                if (sink != null) sink.add(new Candidate(pageNum, grid.confidence, pass, hit, grid));
                if (pass && hit != null) hits.add(hit);
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
    // is folded into the row above.
    //
    // Task 9g correction (see .superpowers/sdd/task-9g-header-and-trim-report.md): task 9f's own
    // comment above used to end "...This handles wrapped data cells and multi-line headers with
    // the exact same rule -- no special-casing needed for headers." THAT premise was wrong and is
    // the direct cause of a measured regression (us-018 F1 0.190 -> 0.070). Verified in the ICDAR
    // structure XML itself (us-018-str.xml): a stacked/hierarchical header is annotated with
    // start-row='1', '2', '3' -- i.e. ground truth treats each physical header line as a
    // GENUINELY SEPARATE row, not one flattened logical row. Blindly folding every anchor-empty
    // line into the row above (as task 9f did) collapses that 3-row header into 1, desyncing
    // every subsequent row index for the rest of the table. The leading run of lines BEFORE the
    // first anchor-populated line must instead each stay their own row (see groupLogicalRows).

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

    private static boolean isAnchorPopulated(Line l, int anchorCol, float[] colBounds) {
        for (Word w : l.words) if (colOf(w.cx(), colBounds) == anchorCol) return true;
        return false;
    }

    /** The set of distinct column indices {@code l} has any word in. */
    private static java.util.Set<Integer> populatedColumns(Line l, float[] colBounds) {
        java.util.Set<Integer> cols = new java.util.HashSet<>();
        for (Word w : l.words) cols.add(colOf(w.cx(), colBounds));
        return cols;
    }

    /**
     * Groups {@code lines} (top-to-bottom, as produced by {@link #buildLines}) into logical
     * rows, in two parts:
     *
     * <ol>
     *   <li><b>Leading run (task 9g fix for the header-flattening regression):</b> lines BEFORE
     *       the first anchor-populated line are never folded into the row that comes after them
     *       (that would blur a stacked header into the first data/anchor row), but they DO still
     *       fold into EACH OTHER when consecutive leading-run lines populate the exact same set
     *       of (non-anchor) columns -- that is the signature of ordinary wrapped multi-column
     *       header text (each physical line contributes another line of the SAME cells' labels,
     *       e.g. "Number of" / "participants (*)" both landing in the same two columns). Two
     *       consecutive leading-run lines populating DIFFERENT column sets, by contrast, is the
     *       signature of a genuinely hierarchical/spanned header (ICDAR's own convention: see
     *       the class-level comment above and us-018-str.xml's start-row='1','2' cells) -- a
     *       coarser group-label line ("Number of teachers" / "Number of new teacher hires",
     *       columns {2,4,5,6}) followed by a finer sub-label line ("Control" / "Control",
     *       columns {2,5}) is NOT the same set, so it starts a new row instead of merging.
     *       Measured: this exact-set-equality test is what keeps a plain 2-line wrapped header
     *       (eu-013, us-007: identical column sets on both physical lines) merging into one row
     *       while us-018's rowspan header (different column sets line-to-line) stays split --
     *       a cruder "no line before the anchor may ever merge with another" rule was measured
     *       to fix us-018 but broke the corpus broadly (aggregate breuel microF1 0.224 -> 0.153,
     *       collateral damage on eu-004/eu-005/eu-013/eu-021/us-017/us-025/etc., all plain
     *       wrapped-header tables it wrongly split into desynced extra rows).</li>
     *   <li><b>From the first anchor-populated line onward:</b> the original task-9f rule -- a
     *       line with anchor content starts a new row, a line without it folds into the row
     *       above. This is what correctly merges a wrapped DATA cell (the wrapped-cell case
     *       {@code wrappedCellMergesIntoOneLogicalRow} exercises) and a header whose OWN anchor
     *       cell already sits on the very first physical line ({@code
     *       multiLineHeaderMergesIntoOneRow}: "Animal" is populated on line 1, so there is no
     *       leading run at all, and the fold-forward rule applies unchanged from line 1).</li>
     * </ol>
     *
     * <p><b>Task 9g defect 2 (anchor value on a record's second physical line) was attempted and
     * deliberately NOT shipped.</b> The natural fix -- a bounded backwards join that reattaches
     * an isolated non-anchor line to the anchor-populated line AFTER it instead of leaving it
     * folded into whatever came before -- is irreconcilable with the fold-forward rule in part
     * (2) above for the exact same physical shape (one non-anchor line between two
     * anchor-populated ones): {@code multiLineHeaderMergesIntoOneRow}'s "Type"/"Detail" line
     * has that identical shape and MUST stay folded into the header line before it, not deferred
     * to the data row after it. Every variant tried (lookback at the join point, lookahead
     * before folding) either reached that same ambiguous shape and broke the existing (correct)
     * behavior, or -- once restricted enough to never reach it -- was PROVABLY a no-op (a
     * non-anchor line in the post-leading-run region always folds forward before any join check
     * could run, so no line is ever left as a "lone orphan" for a lookback to find). See
     * task-9g-header-and-trim-report.md for the measured evidence. us-007's and us-020's
     * corresponding residual row(s) remain unresolved as a result.</p>
     *
     * If {@link #findAnchorColumn} finds no qualifying column, falls back to one logical row per
     * display line -- i.e. the pre-Task-9f behavior -- for this block.
     */
    static List<List<Line>> groupLogicalRows(List<Line> lines, float[] colBounds) {
        List<List<Line>> groups = new ArrayList<>();
        int anchorCol = findAnchorColumn(lines, colBounds);
        if (anchorCol < 0) {
            for (Line l : lines) groups.add(List.of(l));      // fallback: one row per display line
            return groups;
        }

        int n = lines.size();
        boolean[] anchorPop = new boolean[n];
        for (int i = 0; i < n; i++) anchorPop[i] = isAnchorPopulated(lines.get(i), anchorCol, colBounds);

        int firstAnchorIdx = n;                                // no anchor line at all -> whole block is "leading run"
        for (int i = 0; i < n; i++) if (anchorPop[i]) { firstAnchorIdx = i; break; }

        for (int i = 0; i < n; i++) {
            Line l = lines.get(i);
            boolean inLeadingRun = i < firstAnchorIdx;
            boolean startsNewRow;
            if (inLeadingRun) {
                // Fold into the previous leading-run line only if it populates the EXACT same
                // column set -- see the class doc above for why exact-set-equality (not mere
                // overlap) is the signal that discriminates a wrapped header from a hierarchical
                // one, and why it must compare against the immediately preceding PHYSICAL line
                // (not the accumulated group) so a chain of equal-set lines still folds together.
                startsNewRow = groups.isEmpty()
                    || !populatedColumns(l, colBounds).equals(populatedColumns(lines.get(i - 1), colBounds));
            } else {
                startsNewRow = groups.isEmpty() || anchorPop[i];
            }

            if (startsNewRow) {
                groups.add(new ArrayList<>(List.of(l)));
            } else {
                groups.get(groups.size() - 1).add(l);
            }
        }
        return groups;
    }

    private static TableExtractor.TableHit buildHit(int pageNum, Grid grid) {
        return buildHit(pageNum, grid, PageFrame.IDENTITY);
    }

    private static TableExtractor.TableHit buildHit(int pageNum, Grid grid, PageFrame frame) {
        int cols = grid.colBounds.length - 1;
        List<List<Line>> rowGroups = groupLogicalRows(grid.rows, grid.colBounds);
        int rows = rowGroups.size();
        if ((long) rows * cols > TableExtractor.MAX_CELLS_PER_TABLE)
            throw new TableExtractor.RulingOverflowException();

        TableExtractor.TableHit t = new TableExtractor.TableHit();
        t.page = pageNum;
        t.extractionMethod = "stream";
        t.confidence = Math.round(grid.confidence * 1000.0) / 1000.0;
        // The REPORTED confidence stays rounded to 3 decimals (report.json readability); the exact
        // value is kept alongside it, unserialized, because arbitration's confidence FLOOR must be
        // the floor the calibration actually chose. Comparing the rounded value against
        // ARB_MIN_STREAM_CONFIDENCE=0.65 made the effective floor 0.6495, admitting candidates the
        // calibration excluded. See TableExtractor.TableHit#confidenceUnrounded.
        t.confidenceUnrounded = grid.confidence;
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
                // SPANS ARE DELIBERATELY NOT EMITTED, and this is a measured decision rather than an
                // oversight. Each word is assigned to ONE column by its CENTRE, so a spanning header
                // lands wholly in the column containing its centre and the other columns it visually
                // covers are emitted empty. The task-9l reasoning above is right that a word crossing
                // a gutter is "a colspan -- real structure", so emitting one was PROTOTYPED (colSpan =
                // the number of column bands the cell's own word extent covers, truncated at the next
                // populated column in that row) and MEASURED on the full benchmark. It LOSES on every
                // primary figure: full+arbitration MACRO 0.8070 -> 0.8066 all-pages and 0.7878 ->
                // 0.7874 shipping; stream alone 0.6575 -> 0.6573 and 0.6404 -> 0.6402; region-given
                // 0.6931 -> 0.6930; prose false-positive rate unchanged at 15/200. That reproduces the
                // earlier finding that spans are worth about +0.075 MICRO while REVERSING on MACRO, so
                // it is not shipped. Emitting spans correctly would need the row's cells built from
                // column RANGES rather than per-column accumulation (two words in one row can claim
                // overlapping ranges), i.e. its own change with its own calibration.
                cell.row = r; cell.col = c; cell.rowSpan = 1; cell.colSpan = 1;
                cell.text = text[c].toString();
                // Into the page's VISUAL (/Rotate-applied) frame -- the one every lattice/tagged box
                // in the same report.json lives in, and the one contestsSameRegion intersects. The
                // identity for an unrotated page. See PageFrame.
                cell.bbox = frame.map(box[c][0], box[c][1], box[c][2], box[c][3]);
                t.cells.add(cell);
                tx0=Math.min(tx0,cell.bbox[0]); ty0=Math.min(ty0,cell.bbox[1]);
                tx1=Math.max(tx1,cell.bbox[2]); ty1=Math.max(ty1,cell.bbox[3]);
            }
        }
        if (t.cells.isEmpty()) return null;
        t.bbox = new float[]{tx0, ty0, tx1, ty1};
        TableExtractor.renderViews(t);                        // fills rows + markdown
        return t;
    }
}
