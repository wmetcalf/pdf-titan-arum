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

    /**
     * As {@link #find}, but ACCUMULATES this call's charged search work into {@code workOut[0]} so
     * the caller can levy a page- and document-level budget on gutter-search cost as well as on each
     * finder's own per-call budget. An aborted search still charges what it burned before aborting.
     *
     * <p>Default: report nothing. Only the PRODUCTION finder ({@link BreuelGutterFinder}, the one
     * {@code StreamTableExtractor#extractPage}'s 3-arg form and {@code
     * TableExtractor#extractStreamPage} hard-code) overrides it. The other three implementations
     * exist solely for the methodology bake-off, are never reachable from {@code TableExtractor}, and
     * so cannot contribute to a production document's budget -- a harness running them simply sees a
     * document-level budget that counts grid work only, which is the honest report of what is
     * measured rather than a fabricated number.
     */
    default java.util.List<StreamTableExtractor.Gutter> find(
        java.util.List<StreamTableExtractor.Line> lines,
        float bandX0, float bandX1, float medianSpace, long[] workOut) {
        return find(lines, bandX0, bandX1, medianSpace);
    }

    /** Stable short identifier used in bake-off reports, e.g. "breuel", "gapvote". */
    String name();
}
