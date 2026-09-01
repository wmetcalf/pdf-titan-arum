// Adversarial DoS probe (gated -DgateDos=true) for the two-tier confidence bar.
//
// THREAT MODEL FOR THIS CHANGE. The bar is a comparison against a constant chosen by column count; it
// adds no loop, no allocation and no search. But it admits MORE candidates than before, and every
// admitted candidate costs a buildHit (cells + renderViews) and, in the merge pass, can extend a
// merge chain that a stricter bar would have cut. Two things therefore need bounding:
//
//   1. EMIT PATH: hits per page are capped at MAX_STREAM_TABLES_PER_PAGE and per-table cells at
//      TableExtractor.MAX_CELLS_PER_TABLE (which throws RulingOverflowException), and the per-page
//      block budget MAX_STREAM_PAGE_BLOCK_WORK is charged BEFORE the gate is consulted, so a lower
//      bar cannot buy an attacker more gutter-search or more block work than before. What it can buy
//      is more buildHit calls -- at most MAX_STREAM_TABLES_PER_PAGE of them, exactly as before.
//   2. MERGE PATH: Step A' charges obstacles against MAX_BLOCK_MERGE_WORK before every probe, and the
//      lower bar changes only which merges are ACCEPTED, never how many probes are run. Accepting more
//      merges cannot increase probe count either: the loop is a single left-to-right pass.
//
// This probe measures the worst case empirically rather than trusting that argument: a page built
// specifically to maximise the number of wide candidates sitting in the newly-admitting band, plus
// the standard dense adversarial shapes, timed under both gates.
package com.oai.titanarum;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class GateDosProbe {

    private static final StreamTableExtractor.ConfidenceBar FLAT =
            c -> StreamTableExtractor.STREAM_CONFIDENCE_MIN;
    private static final StreamTableExtractor.ConfidenceBar TWO_TIER =
            StreamTableExtractor.PRODUCTION_BAR;

    private static StreamTableExtractor.Word w(float x0, float x1, float yTop, String t) {
        StreamTableExtractor.Word wd = new StreamTableExtractor.Word();
        wd.x0 = x0; wd.x1 = x1; wd.y0 = yTop; wd.y1 = yTop + 8; wd.text = t;
        return wd;
    }

    /**
     * Worst case FOR THIS CHANGE: {@code blocks} separate wide sparse blocks, each one deliberately
     * scoring in [wide bar, flat bar) so the two-tier gate admits every single one and the flat gate
     * admits none. Blocks are separated by a big vertical gap so Step A splits them.
     */
    private static List<StreamTableExtractor.Word> maxAdmittedPage(int blocks, int rowsPerBlock,
                                                                  int cols) {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        float y = 10;
        for (int b = 0; b < blocks; b++) {
            for (int r = 0; r < rowsPerBlock; r++) {
                for (int c = 0; c < cols; c++) {
                    if (c != 0 && (c + r) % 2 != 0) continue;       // sparse -> colConsistency ~ 0
                    float x0 = 10 + c * 40;
                    ws.add(w(x0, x0 + 29, y, "L" + c + "_" + r));
                }
                y += 12;
            }
            y += 60;                                                 // block separator
        }
        return ws;
    }

    private static void time(String label, List<StreamTableExtractor.Word> words) {
        for (StreamTableExtractor.ConfidenceBar bar : List.of(FLAT, TWO_TIER)) {
            String which = bar == FLAT ? "flat" : "twoTier";
            // warm up
            for (int i = 0; i < 3; i++) run(words, bar);
            long best = Long.MAX_VALUE;
            int hits = 0;
            for (int i = 0; i < 5; i++) {
                long t0 = System.nanoTime();
                hits = run(words, bar);
                best = Math.min(best, System.nanoTime() - t0);
            }
            System.out.printf(Locale.ROOT, "  %-40s %-8s hits=%-4d best=%.1f ms%n",
                    label, which, hits, best / 1e6);
        }
    }

    /** Drives the same per-block pipeline extractPage drives, from pre-built words (so the probe
     *  measures the gate, not PDF parsing). Returns the number of hits emitted. */
    private static int run(List<StreamTableExtractor.Word> words,
                          StreamTableExtractor.ConfidenceBar bar) {
        GutterFinder finder = new BreuelGutterFinder();
        List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(words, 8f);
        float medianSpace = 4f;
        List<StreamTableExtractor.BlockGroup> blocks = StreamTableExtractor.mergeAgreeingBlocks(
                StreamTableExtractor.splitIntoBlocks(lines), finder, medianSpace,
                StreamTableExtractor.pageMedianPitch(lines),
                StreamTableExtractor.MAX_BLOCK_MERGE_WORK, bar);
        int hits = 0;
        long pageWork = 0;
        for (StreamTableExtractor.BlockGroup g : blocks) {
            if (hits >= StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE) break;
            if (g.lines.size() < 3) continue;
            long charge = g.lines.stream().mapToLong(l -> l.words.size()).sum();
            if (pageWork + charge > StreamTableExtractor.MAX_STREAM_PAGE_BLOCK_WORK) break;
            pageWork += charge;
            if (g.gutterSearchOverflowed) continue;
            try {
                float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE;
                for (StreamTableExtractor.Line l : g.lines) for (StreamTableExtractor.Word wd : l.words) {
                    x0 = Math.min(x0, wd.x0); x1 = Math.max(x1, wd.x1);
                }
                List<StreamTableExtractor.Gutter> gut = g.gutters != null ? g.gutters
                        : finder.find(g.lines, x0, x1, medianSpace);
                List<StreamTableExtractor.Line> trimmed =
                        StreamTableExtractor.trimEdgeLines(g.lines, gut, x0, x1, medianSpace);
                if (trimmed.size() < 3) continue;
                StreamTableExtractor.Grid grid = StreamTableExtractor.scoreGrid(trimmed, gut, x0, x1);
                if (grid.confidence < bar.barFor(grid.colBounds.length - 1)) continue;
                hits++;
            } catch (TableExtractor.RulingOverflowException ignored) { }
        }
        return hits;
    }

    @Test
    void run() {
        Assumptions.assumeTrue(Boolean.getBoolean("gateDos"), "set -DgateDos=true");
        System.out.println("=== worst case FOR THIS CHANGE: every block lands in the newly-admitting band ===");
        time("40 blocks x 8 rows x 9 cols", maxAdmittedPage(40, 8, 9));
        time("100 blocks x 8 rows x 9 cols", maxAdmittedPage(100, 8, 9));
        time("200 blocks x 6 rows x 12 cols", maxAdmittedPage(200, 6, 12));
        time("500 blocks x 4 rows x 9 cols", maxAdmittedPage(500, 4, 9));
        System.out.println("=== one huge wide sparse block (single-block merge path) ===");
        time("1 block x 800 rows x 12 cols", maxAdmittedPage(1, 800, 12));
        time("1 block x 2000 rows x 20 cols", maxAdmittedPage(1, 2000, 20));
    }
}
