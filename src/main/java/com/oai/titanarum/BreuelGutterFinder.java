package com.oai.titanarum;

import java.util.List;

/**
 * Adapter over the existing Breuel maximal-empty-rectangle branch-and-bound gutter finder
 * ({@link StreamTableExtractor#findGutters}). No logic of its own: all DoS bounding
 * ({@code MAX_GUTTER_SCAN_WORK}), quality thresholds, row-coverage merging, and edge-exclusion
 * are inherited unchanged from that method. Exists purely so the bake-off harness can address
 * the already-shipped algorithm through the same {@link GutterFinder} seam as its contenders.
 */
final class BreuelGutterFinder implements GutterFinder {

    @Override
    public List<StreamTableExtractor.Gutter> find(List<StreamTableExtractor.Line> lines,
                                                    float bandX0, float bandX1, float medianSpace) {
        return StreamTableExtractor.findGutters(lines, bandX0, bandX1, medianSpace);
    }

    /** The production finder DOES report its charged work, so the per-page and per-document stream
     *  budgets can be levied on gutter-search cost too (see {@link GutterFinder#find(List, float,
     *  float, float, long[])}). Same algorithm, same per-call budget -- only the accounting differs. */
    @Override
    public List<StreamTableExtractor.Gutter> find(List<StreamTableExtractor.Line> lines,
                                                    float bandX0, float bandX1, float medianSpace,
                                                    long[] workOut) {
        return StreamTableExtractor.findGutters(lines, bandX0, bandX1, medianSpace, workOut);
    }

    @Override
    public String name() { return "breuel"; }
}
