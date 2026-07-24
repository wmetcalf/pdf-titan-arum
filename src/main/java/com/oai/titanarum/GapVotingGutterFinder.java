package com.oai.titanarum;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gap-voting column-gutter finder: every line "votes" at the midpoint of each of its
 * wider-than-normal inter-word gaps; votes are clustered by x across the whole band; a cluster
 * that (a) is corroborated by enough DISTINCT lines and (b) sits at a consistent x becomes a
 * gutter. Unlike {@link OccupancyProjectionGutterFinder}'s whole-page whitespace union, this
 * signal is per-line and per-gap, so a single full-width spanning row (which contributes no
 * internal gaps at all, being one word) cannot corrupt it.
 */
final class GapVotingGutterFinder implements GutterFinder {

    /** Fraction of DISTINCT lines that must contribute a vote to a cluster for it to become a
     *  gutter -- one line must not be able to manufacture a gutter by itself. */
    static final double MIN_LINE_SUPPORT_FRACTION = 0.60;

    /** Positional-consistency cap: a cluster's vote-x standard deviation must not exceed this
     *  multiple of medianSpace, or the "gutter" is really just scattered, coincidentally
     *  similarly-sized gaps rather than one real aligned column boundary. */
    static final double MAX_STDEV_MEDIAN_SPACE_MULT = 1.0;

    // Real work here is O(words) to build gap votes (one examination per adjacent-word pair)
    // plus O(V log V) to sort/cluster those votes (V <= examined gaps) -- both linear-ish and,
    // for any table actually seen in production, tiny (MAX_STREAM_WORDS=60,000 words caps the
    // upstream input already). This budget exists as a hard, finite cap on the ONE quantity
    // this method can be forced to do unbounded work on when called directly (e.g. by the
    // bake-off harness or future callers that don't route through StreamTableExtractor's own
    // upstream caps): the count of adjacent-word-pair gaps it examines. 20,000 gives >10x
    // headroom over the largest legitimate fixture exercised anywhere in this suite (the 200
    // rows x 10 cols uniformGrid in StreamGutterTest, ~1,800 gaps) while still being far below
    // the ~48,000 gaps a 2,000 x 25 dense adversarial page produces.
    static final long MAX_GAPVOTE_GAPS_EXAMINED = 20_000;

    @Override
    public String name() { return "gapvote"; }

    @Override
    public List<StreamTableExtractor.Gutter> find(List<StreamTableExtractor.Line> lines,
                                                    float bandX0, float bandX1, float medianSpace) {
        if (lines.isEmpty() || bandX1 - bandX0 <= 0) return List.of();

        // {voteX, lineIndex} -- lineIndex as float so this stays a single primitive-array vote
        // record, avoiding a small object per vote.
        List<float[]> votes = new ArrayList<>();
        long examined = 0;
        for (int li = 0; li < lines.size(); li++) {
            List<StreamTableExtractor.Word> ws = lines.get(li).words;
            for (int i = 1; i < ws.size(); i++) {
                examined++;
                if (examined > MAX_GAPVOTE_GAPS_EXAMINED) throw new TableExtractor.RulingOverflowException();
                StreamTableExtractor.Word prev = ws.get(i - 1);
                StreamTableExtractor.Word next = ws.get(i);
                float gap = next.x0 - prev.x1;
                if (gap > medianSpace) {
                    votes.add(new float[]{(prev.x1 + next.x0) * 0.5f, li});
                }
            }
        }
        if (votes.isEmpty()) return List.of();

        votes.sort(Comparator.comparingDouble(v -> v[0]));
        float tol = 0.5f * medianSpace;

        // Single-linkage clustering: start a new cluster when the next vote is more than tol
        // away from the RUNNING MEAN of the current cluster (not just its last member).
        List<List<float[]>> clusters = new ArrayList<>();
        List<float[]> cur = new ArrayList<>();
        double sum = 0; int count = 0;
        for (float[] v : votes) {
            if (count == 0 || Math.abs(v[0] - sum / count) <= tol) {
                cur.add(v); sum += v[0]; count++;
            } else {
                clusters.add(cur);
                cur = new ArrayList<>();
                cur.add(v); sum = v[0]; count = 1;
            }
        }
        clusters.add(cur);

        double minLineSupport = MIN_LINE_SUPPORT_FRACTION * lines.size();
        double maxStdev = MAX_STDEV_MEDIAN_SPACE_MULT * medianSpace;

        List<StreamTableExtractor.Gutter> candidates = new ArrayList<>();
        for (List<float[]> cluster : clusters) {
            Set<Integer> distinctLines = new HashSet<>();
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            double vsum = 0;
            for (float[] v : cluster) {
                distinctLines.add((int) v[1]);
                minX = Math.min(minX, v[0]);
                maxX = Math.max(maxX, v[0]);
                vsum += v[0];
            }
            if (distinctLines.size() < minLineSupport) continue;
            double mean = vsum / cluster.size();
            double variance = 0;
            for (float[] v : cluster) variance += (v[0] - mean) * (v[0] - mean);
            variance /= cluster.size();
            if (Math.sqrt(variance) > maxStdev) continue;

            float x0 = minX, x1 = maxX;
            if (x1 - x0 < medianSpace) {
                float mid = (x0 + x1) / 2f;
                x0 = mid - medianSpace / 2f;
                x1 = mid + medianSpace / 2f;
            }
            if (x0 <= bandX0 + 0.5f || x1 >= bandX1 - 0.5f) continue; // interior only

            StreamTableExtractor.Gutter g = new StreamTableExtractor.Gutter();
            g.x0 = x0; g.x1 = x1; g.rowsCovered = distinctLines.size();
            candidates.add(g);
        }

        candidates.sort((a, b) -> Integer.compare(b.rowsCovered, a.rowsCovered)); // highest support first
        if (candidates.size() > StreamTableExtractor.MAX_GUTTER_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, StreamTableExtractor.MAX_GUTTER_CANDIDATES));
        }
        candidates.sort(Comparator.comparingDouble(g -> g.x0)); // left->right for output
        return candidates;
    }
}
