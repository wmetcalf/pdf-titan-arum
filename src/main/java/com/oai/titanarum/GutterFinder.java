package com.oai.titanarum;

/**
 * Pluggable column-gutter detector: one contender in the borderless (stream) table-extraction
 * methodology bake-off (see docs/superpowers/specs/2026-07-24-borderless-stream-table-extraction-design.md).
 * All implementations consume the same package-private {@link StreamTableExtractor.Word}/
 * {@link StreamTableExtractor.Line} geometry and produce the same {@link StreamTableExtractor.Gutter}
 * output, so they can be scored against the same ground truth with no other code path change.
 */
interface GutterFinder {
    /** Detect interior column gutters, sorted left->right. Must be DoS-bounded:
     *  throw TableExtractor.RulingOverflowException on budget breach, never degrade. */
    java.util.List<StreamTableExtractor.Gutter> find(
        java.util.List<StreamTableExtractor.Line> lines,
        float bandX0, float bandX1, float medianSpace);

    /** Stable short identifier used in bake-off reports, e.g. "breuel", "gapvote". */
    String name();
}
