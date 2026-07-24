package com.oai.titanarum;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Deliberate baseline: a faithful reimplementation of tabula-java's
 * {@code BasicExtractionAlgorithm.columnPositions} logic. Every word's horizontal span
 * [x0,x1], across ALL lines, is merged into a single whole-page occupancy union; the
 * complement of that union within the band is the set of full-height whitespace gutters.
 *
 * This intentionally inherits tabula's known weakness: a single line that spans the full band
 * width (e.g. a title/spanning header) merges into the union and erases every gutter beneath
 * it, collapsing the whole page to zero columns. That is not "fixed" here -- the point of this
 * finder in the bake-off is to measure exactly how often that weakness bites on the ground
 * truth corpus.
 */
final class OccupancyProjectionGutterFinder implements GutterFinder {

    // The only real work here is O(words) to collect spans plus O(words log words) to sort them
    // for the merge -- both strictly linear/linearithmic, with no quadratic-shaped risk (unlike
    // AlignmentEdgeGutterFinder's boundary x line coverage scan). This budget is therefore a
    // simple, generous linear cap: 25,000 gives >10x headroom over the largest legitimate
    // fixture in this suite (StreamGutterTest's 200x10 uniformGrid: 2,000 words) while still
    // being a hard, finite limit on a method that -- like its siblings -- may be invoked
    // directly (bypassing StreamTableExtractor's own upstream MAX_STREAM_WORDS gate) by the
    // bake-off harness or a future caller.
    static final long MAX_OCCUPANCY_WORDS = 25_000;

    @Override
    public String name() { return "occupancy"; }

    @Override
    public List<StreamTableExtractor.Gutter> find(List<StreamTableExtractor.Line> lines,
                                                    float bandX0, float bandX1, float medianSpace) {
        if (lines.isEmpty() || bandX1 - bandX0 <= 0) return List.of();

        List<float[]> spans = new ArrayList<>(); // {x0, x1}
        long work = 0;
        for (StreamTableExtractor.Line l : lines) {
            for (StreamTableExtractor.Word wd : l.words) {
                work++;
                if (work > MAX_OCCUPANCY_WORDS) throw new TableExtractor.RulingOverflowException();
                spans.add(new float[]{wd.x0, wd.x1});
            }
        }
        if (spans.isEmpty()) return List.of();

        spans.sort(Comparator.comparingDouble(s -> s[0]));
        List<float[]> merged = new ArrayList<>();
        for (float[] s : spans) {
            if (!merged.isEmpty() && s[0] <= merged.get(merged.size() - 1)[1]) {
                float[] last = merged.get(merged.size() - 1);
                last[1] = Math.max(last[1], s[1]);
            } else {
                merged.add(new float[]{s[0], s[1]});
            }
        }

        // Complement of the merged union within [bandX0, bandX1], in left->right order
        // already (merged is sorted), so no extra sort is needed before the cap below.
        List<StreamTableExtractor.Gutter> candidates = new ArrayList<>();
        float prevEnd = bandX0;
        for (float[] m : merged) {
            float gapStart = prevEnd, gapEnd = Math.min(m[0], bandX1);
            if (gapEnd - gapStart > medianSpace
                && gapStart > bandX0 + 0.5f && gapEnd < bandX1 - 0.5f) {
                StreamTableExtractor.Gutter g = new StreamTableExtractor.Gutter();
                g.x0 = gapStart; g.x1 = gapEnd; g.rowsCovered = lines.size();
                candidates.add(g);
            }
            prevEnd = Math.max(prevEnd, m[1]);
            if (prevEnd >= bandX1) break;
        }

        if (candidates.size() > StreamTableExtractor.MAX_GUTTER_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, StreamTableExtractor.MAX_GUTTER_CANDIDATES));
        }
        return candidates; // already sorted left->right
    }
}
