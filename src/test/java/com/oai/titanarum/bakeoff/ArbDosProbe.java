// Physically under src/test/java/com/oai/titanarum/bakeoff/ but declares `package
// com.oai.titanarum;` so it can drive the package-private TableExtractor.arbitrate.
//
// PURPOSE: measure arbitration's THROUGHPUT so MAX_ARBITRATION_WORK is set from a measurement
// rather than a guess, and confirm the worst legitimate case is fast and the hostile case aborts
// fast. Gated: -DarbDos=true.
package com.oai.titanarum;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

class ArbDosProbe {

    private static TableExtractor.TableHit hit(String method, int page, int rows, int cols,
                                              int cells, Double conf) {
        TableExtractor.TableHit t = new TableExtractor.TableHit();
        t.extractionMethod = method;
        t.page = page;
        t.bbox = new float[]{0, 0, 100, 100};
        t.rowCount = rows;
        t.colCount = cols;
        t.confidence = conf;
        t.cells = new ArrayList<>();
        for (int i = 0; i < cells; i++) {
            TableExtractor.CellHit c = new TableExtractor.CellHit();
            c.row = i / Math.max(1, cols);
            c.col = i % Math.max(1, cols);
            c.rowSpan = 1; c.colSpan = 1; c.text = "x";
            c.bbox = new float[]{0, 0, 100, 100};
            t.cells.add(c);
        }
        return t;
    }

    @Test
    void measureWorstCaseThroughputAndAbortLatency() {
        Assumptions.assumeTrue(Boolean.getBoolean("arbDos"), "set -DarbDos=true to run");

        // ---- worst LEGITIMATE case: N pages each saturating both per-page candidate caps, every
        // candidate on a page overlapping every other (the densest possible contest graph).
        for (int pages : new int[]{50, 200, 800}) {
            List<TableExtractor.TableHit> ruled = new ArrayList<>();
            List<TableExtractor.TableHit> str = new ArrayList<>();
            for (int p = 1; p <= pages; p++) {
                for (int i = 0; i < TableExtractor.MAX_TABLES_PER_PAGE; i++) {
                    ruled.add(hit("lattice", p, 4, 4, 16, null));
                }
                for (int i = 0; i < StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE; i++) {
                    str.add(hit("stream", p, 4, 4, 16, 0.9));
                }
            }
            TableExtractor.arbitrate(ruled, str);   // warm
            long t0 = System.nanoTime();
            int reps = 5;
            for (int r = 0; r < reps; r++) TableExtractor.arbitrate(ruled, str);
            double ms = (System.nanoTime() - t0) / 1e6 / reps;
            long charged = (long) pages
                    * (TableExtractor.MAX_TABLES_PER_PAGE
                       * StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE
                       + (TableExtractor.MAX_TABLES_PER_PAGE
                          + StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE)
                         * TableExtractor.MAX_TABLES_PER_PAGE);
            System.out.printf("  %4d pages at the per-page caps (%d+%d/page): %.2f ms, "
                            + "~%d charged units -> %.1f M units/s%n",
                    pages, TableExtractor.MAX_TABLES_PER_PAGE,
                    StreamTableExtractor.MAX_STREAM_TABLES_PER_PAGE, ms, charged,
                    charged / (ms * 1000.0));
        }

        // ---- hostile case: a single page with a candidate list far past the caps. Must abort, and
        // abort FAST (the charge is levied before the adjacency matrix is even allocated).
        List<TableExtractor.TableHit> hostileR = new ArrayList<>();
        List<TableExtractor.TableHit> hostileS = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            hostileR.add(hit("lattice", 1, 2, 2, 4, null));
            hostileS.add(hit("stream", 1, 2, 2, 4, 0.9));
        }
        long t0 = System.nanoTime();
        boolean aborted = false;
        try {
            TableExtractor.arbitrate(hostileR, hostileS);
        } catch (TableExtractor.RulingOverflowException e) {
            aborted = true;
        }
        System.out.printf("  hostile 20000+20000 candidates on one page: aborted=%s in %.3f ms%n",
                aborted, (System.nanoTime() - t0) / 1e6);
    }
}
