package com.oai.titanarum;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Camelot/pdfplumber-style alignment-edge column-gutter finder. Builds three independent
 * x-coordinate populations across every word -- left edges (x0), right edges (x1), and centers
 * (cx()) -- clusters each with a tight tolerance, and keeps only clusters with pdfplumber's
 * default {@code min_words_vertical=3} support. Whichever alignment type (left/right/center)
 * has the most supported edges is taken as this region's dominant alignment; column boundaries
 * are the midpoints between its consecutive supported edges.
 */
final class AlignmentEdgeGutterFinder implements GutterFinder {

    /** pdfplumber's default min_words_vertical: an edge needs at least this many distinct words
     *  sharing it before it counts as a real alignment, not coincidence. */
    static final int MIN_WORDS_VERTICAL = 3;

    // Two distinct real-work sources are charged into the SAME counter:
    //  1. Building the three x-populations is O(words).
    //  2. Computing rowsCovered per candidate boundary scans every line (O(lines)) -- and the
    //     number of boundaries is itself driven by word count (as few as 3 words per supported
    //     cluster, so up to ~words/3 edges -> ~words/3 boundaries). That second source is this
    //     method's real quadratic-shaped risk (boundaries * lines), unlike GapVoting's or
    //     Occupancy's strictly-linear cost, so it must be bounded here even though the
    //     population-build alone rarely gets close on real input.
    // 30,000 gives comfortable headroom over the largest legitimate fixture in this suite
    // (StreamGutterTest's 200x10 uniformGrid: 2,000 words, at most ~9 boundaries x 200 lines =
    // 1,800 boundary-scan work) while sitting well below the ~50,000-word / up to ~16,600-
    // boundary dense adversarial page this bake-off's contract test exercises.
    static final long MAX_ALIGNEDGE_WORK = 30_000;

    @Override
    public String name() { return "alignedge"; }

    @Override
    public List<StreamTableExtractor.Gutter> find(List<StreamTableExtractor.Line> lines,
                                                    float bandX0, float bandX1, float medianSpace) {
        if (lines.isEmpty() || bandX1 - bandX0 <= 0) return List.of();

        long work = 0;
        List<float[]> lefts = new ArrayList<>();   // {x, lineIndex}
        List<float[]> rights = new ArrayList<>();
        List<float[]> centers = new ArrayList<>();
        for (int li = 0; li < lines.size(); li++) {
            for (StreamTableExtractor.Word wd : lines.get(li).words) {
                work++;
                if (work > MAX_ALIGNEDGE_WORK) throw new TableExtractor.RulingOverflowException();
                lefts.add(new float[]{wd.x0, li});
                rights.add(new float[]{wd.x1, li});
                centers.add(new float[]{wd.cx(), li});
            }
        }

        float tol = 0.4f * medianSpace;
        List<List<float[]>> leftSupported = supportedClusters(cluster(lefts, tol));
        List<List<float[]>> rightSupported = supportedClusters(cluster(rights, tol));
        List<List<float[]>> centerSupported = supportedClusters(cluster(centers, tol));

        List<List<float[]>> dominant = leftSupported;
        if (rightSupported.size() > dominant.size()) dominant = rightSupported;
        if (centerSupported.size() > dominant.size()) dominant = centerSupported;

        if (dominant.size() < 2) return List.of(); // need >=2 aligned edges to bound an interior column

        List<Float> edgeX = new ArrayList<>();
        for (List<float[]> c : dominant) {
            double sum = 0;
            for (float[] v : c) sum += v[0];
            edgeX.add((float) (sum / c.size()));
        }
        Collections.sort(edgeX);

        List<StreamTableExtractor.Gutter> candidates = new ArrayList<>();
        for (int i = 0; i < edgeX.size() - 1; i++) {
            float mid = (edgeX.get(i) + edgeX.get(i + 1)) / 2f;
            float gx0 = mid - medianSpace / 2f, gx1 = mid + medianSpace / 2f;
            if (gx0 <= bandX0 + 0.5f || gx1 >= bandX1 - 0.5f) continue; // interior only

            int cover = 0;
            for (StreamTableExtractor.Line l : lines) {
                work++;
                if (work > MAX_ALIGNEDGE_WORK) throw new TableExtractor.RulingOverflowException();
                boolean leftSide = false, rightSide = false;
                for (StreamTableExtractor.Word wd : l.words) {
                    float cx = wd.cx();
                    if (cx < mid) leftSide = true; else if (cx > mid) rightSide = true;
                    if (leftSide && rightSide) break;
                }
                if (leftSide && rightSide) cover++;
            }

            StreamTableExtractor.Gutter g = new StreamTableExtractor.Gutter();
            g.x0 = gx0; g.x1 = gx1; g.rowsCovered = cover;
            candidates.add(g);
        }

        candidates.sort((a, b) -> Integer.compare(b.rowsCovered, a.rowsCovered)); // highest support first
        if (candidates.size() > StreamTableExtractor.MAX_GUTTER_CANDIDATES) {
            candidates = new ArrayList<>(candidates.subList(0, StreamTableExtractor.MAX_GUTTER_CANDIDATES));
        }
        candidates.sort(Comparator.comparingDouble(g -> g.x0)); // left->right for output
        return candidates;
    }

    /** Single-linkage clustering by running-mean distance, same discipline as GapVotingGutterFinder. */
    private static List<List<float[]>> cluster(List<float[]> pts, float tol) {
        List<float[]> sorted = new ArrayList<>(pts);
        sorted.sort(Comparator.comparingDouble(v -> v[0]));
        List<List<float[]>> clusters = new ArrayList<>();
        List<float[]> cur = new ArrayList<>();
        double sum = 0; int count = 0;
        for (float[] v : sorted) {
            if (count == 0 || Math.abs(v[0] - sum / count) <= tol) {
                cur.add(v); sum += v[0]; count++;
            } else {
                clusters.add(cur);
                cur = new ArrayList<>();
                cur.add(v); sum = v[0]; count = 1;
            }
        }
        if (!cur.isEmpty()) clusters.add(cur);
        return clusters;
    }

    private static List<List<float[]>> supportedClusters(List<List<float[]>> clusters) {
        List<List<float[]>> out = new ArrayList<>();
        for (List<float[]> c : clusters) if (c.size() >= MIN_WORDS_VERTICAL) out.add(c);
        return out;
    }
}
