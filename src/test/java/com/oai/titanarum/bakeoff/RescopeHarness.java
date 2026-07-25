// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/ (matching
// BakeOffHarness/Diag9jHarness's own convention -- see their headers), but declares `package
// com.oai.titanarum;` because it needs direct access to package-private production types
// (TableExtractor, TableExtractor.TableHit, StreamTableExtractor) and the package-private test
// helper TableTestPdfs.harvestGlyphs. GroundTruth and TableScore (the actually-reusable scoring
// apparatus) are public and imported normally from com.oai.titanarum.bakeoff below, exactly as
// BakeOffHarness/Diag9jHarness already do.
//
// PURPOSE (measurement-scope correction): BakeOffHarness's `run()` scores ONLY the stream path
// (StreamTableExtractor.extractPage) against the FULL 77-PDF ICDAR/tabula corpus -- but that
// corpus is mostly ruled/tagged (verified directly by this harness's own Task 1 below), so
// grading the stream path against it measures the wrong thing. This harness does NOT change
// BakeOffHarness, TableScore, GroundTruth, StreamTableExtractor, or TableExtractor in any way --
// it is read-only measurement code that:
//   1. Classifies all 77 corpus PDFs by what TableExtractor.extract (tagged+lattice, the ONLY
//      two methods currently wired into production `extract`) actually finds on them.
//   2. Scores the FULL pipeline (tagged+lattice+stream, combined here since stream isn't wired
//      into TableExtractor.extract yet) against ICDAR adjacency, both micro and macro.
//   3. Scores the lattice+tagged path ALONE (no stream) the same way.
//   4. Builds the genuinely-borderless subset (PDFs where tagged+lattice find nothing) and scores
//      the stream path alone on ONLY that subset.
//   5. Splits (2)/(3)/(1) by ICDAR US vs EU subset.
//
// Reuses BakeOffHarness#buildScoringSet/ScoreUnit (package-private, already widened for
// Diag9jHarness's reuse -- see that file's own header) so the discovered 77-PDF corpus and its
// per-PDF ground truth are BYTE-IDENTICAL to every prior bake-off run, never re-derived.
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.TableScore;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Corrected-scope bake-off measurement. NOT part of the normal test suite: gated by the {@code
 * rescope} system property, AND named so it does not match Surefire's default test-inclusion
 * patterns (mirrors BakeOffHarness's own convention -- see that class's header for the exact
 * pattern list). Run explicitly: {@code mvn -q -o test -Dtest=RescopeHarness -Drescope=true}.
 */
class RescopeHarness {

    // A "substantial overlap" threshold for the stream/tagged-lattice merge rule used by Task 2's
    // full-pipeline combination (see #overlapsSubstantially below) -- 0.5, matching the value
    // TableExtractor's own (private, inaccessible from here) CELL_FOOTPRINT_COVERAGE_THRESHOLD
    // uses for its analogous tagged-vs-lattice advisory-dedup signal. IMPORTANT DIFFERENCE (stated
    // here, and again in the written report): TableExtractor's real signal sums the tagged table's
    // own CELL rectangles' intersection with the candidate, not the tagged table's outer bbox --
    // this harness cannot reuse that private method, so it approximates with plain OUTER-BBOX
    // intersection-over-candidate-area instead. Outer bbox is a superset of the cell footprint, so
    // this approximation can only find EQUAL or MORE overlap than the real per-cell rule would --
    // i.e. it can only be as-or-more aggressive about dropping a stream candidate, never less. This
    // is a measurement approximation of "the intended production merge rule" described in the
    // stream-table design doc, not that rule verbatim.
    private static final float STREAM_DROP_OVERLAP_THRESHOLD = 0.5f;

    // ------------------------------------------------------------------------- one PDF's measurement

    /** Everything measured for one corpus PDF, computed ONCE and reused by every task's scoring
     *  below (avoids re-parsing/re-extracting the same PDF 5 times over). */
    private record UnitMeasurement(
            BakeOffHarness.ScoreUnit unit,
            List<TableExtractor.TableHit> taggedLatticeHits,   // TableExtractor.extract's own output (tagged+lattice)
            boolean taggedLatticeTruncated,
            String taggedLatticeError,                          // non-null only if extract() itself threw (it shouldn't -- see its own javadoc)
            List<TableExtractor.TableHit> rawStreamHits,        // StreamTableExtractor.extractPage, every page, default (Breuel) finder
            List<TableExtractor.TableHit> keptStreamHits,       // rawStreamHits minus any dropped by the overlap rule
            String streamError,
            String loadError) {

        int taggedCount() {
            return (int) taggedLatticeHits.stream().filter(h -> "tagged".equals(h.extractionMethod)).count();
        }
        int latticeCount() {
            return (int) taggedLatticeHits.stream().filter(h -> "lattice".equals(h.extractionMethod)).count();
        }
        String bucket() {
            boolean t = taggedCount() > 0, l = latticeCount() > 0;
            if (t && l) return "both";
            if (t) return "tagged";
            if (l) return "lattice";
            return "neither";
        }
        /** "icdar-us" / "icdar-eu" / "csv", derived from ScoreUnit#id()'s path (see
         *  BakeOffHarness#buildScoringSet -- ICDAR ids are relativized under
         *  icdar2013-dataset/competition-dataset-{us,eu}/, CSV ids are not). */
        String source() {
            String id = unit.id();
            if (id.contains("competition-dataset-us")) return "icdar-us";
            if (id.contains("competition-dataset-eu")) return "icdar-eu";
            return "csv";
        }
    }

    private static UnitMeasurement measure(BakeOffHarness.ScoreUnit unit) {
        List<TableExtractor.TableHit> taggedLattice = new ArrayList<>();
        boolean truncated = false;
        String tlError = null;
        List<TableExtractor.TableHit> rawStream = new ArrayList<>();
        String streamError = null;
        String loadError = null;

        try (PDDocument doc = Loader.loadPDF(unit.pdf().toFile())) {
            int pages = doc.getNumberOfPages();
            List<Integer> pageList = new ArrayList<>();
            Map<Integer, List<TextPosition>> positions = new LinkedHashMap<>();
            for (int p = 1; p <= pages; p++) {
                pageList.add(p);
                positions.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
            }
            try {
                TableExtractor.Result r = TableExtractor.extract(doc, pageList, positions);
                taggedLattice.addAll(r.tables);
                truncated = r.truncated;
            } catch (Throwable t) {
                // extract()'s own javadoc says it never throws (every stage has an internal
                // catch); this is defensive only -- if it DOES ever throw, record it as an error
                // rather than let it kill the whole harness run, and surface it in the report.
                tlError = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            try {
                for (int p : pageList) {
                    rawStream.addAll(StreamTableExtractor.extractPage(p, positions.get(p)));
                }
            } catch (Throwable t) {
                streamError = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        } catch (Throwable t) {
            loadError = t.getClass().getSimpleName() + ": " + t.getMessage();
        }

        List<TableExtractor.TableHit> kept = new ArrayList<>();
        for (TableExtractor.TableHit s : rawStream) {
            if (!overlapsSubstantially(s, taggedLattice)) kept.add(s);
        }
        return new UnitMeasurement(unit, taggedLattice, truncated, tlError, rawStream, kept, streamError, loadError);
    }

    /** The Task 2 merge rule: true when {@code candidate}'s (a stream hit's) bbox has its area
     *  MAJORITY covered by a same-page tagged/lattice hit's OUTER bbox -- see the class-level note
     *  on {@link #STREAM_DROP_OVERLAP_THRESHOLD} for exactly how this differs from production's
     *  (private, inaccessible) per-cell-footprint signal. */
    private static boolean overlapsSubstantially(TableExtractor.TableHit candidate,
                                                   List<TableExtractor.TableHit> taggedLattice) {
        if (candidate.bbox == null) return false;
        float area = bboxArea(candidate.bbox);
        if (area <= 0f) return false;
        for (TableExtractor.TableHit t : taggedLattice) {
            if (t.page != candidate.page) continue;
            if (t.bbox == null) continue;
            float inter = intersectionArea(candidate.bbox, t.bbox);
            if (inter / area > STREAM_DROP_OVERLAP_THRESHOLD) return true;
        }
        return false;
    }

    private static float bboxArea(float[] b) {
        if (b == null) return 0f;
        return Math.max(0f, b[2] - b[0]) * Math.max(0f, b[3] - b[1]);
    }

    private static float intersectionArea(float[] a, float[] b) {
        if (a == null || b == null) return 0f;
        float x0 = Math.max(a[0], b[0]);
        float y0 = Math.max(a[1], b[1]);
        float x1 = Math.min(a[2], b[2]);
        float y1 = Math.min(a[3], b[3]);
        if (x1 <= x0 || y1 <= y0) return 0f;
        return (x1 - x0) * (y1 - y0);
    }

    // --------------------------------------------------------------------- generic adjacency scoring

    /** One PDF's adjacency-relation score against a given candidate hit list, using the EXACT same
     *  greedy pairing policy as {@code BakeOffHarness#scoreUnit} (decide the pairing on exact-cell
     *  F1, then score adjacency against that same pairing; unpaired GT tables count as pure recall
     *  loss, unpaired hits as pure precision loss) -- generalized here so it can be run against
     *  three different hit lists (tagged+lattice+stream, tagged+lattice alone, stream alone)
     *  without duplicating the policy three times with subtle drift between copies. */
    private record UnitAdjResult(long matched, long detectedTotal, long gtTotal, double f1, int pairedTables) {}

    private static UnitAdjResult scoreUnitAdjacency(List<TableExtractor.TableHit> hits,
                                                      List<GroundTruth.Table> expected) {
        List<TableExtractor.TableHit> available = new ArrayList<>(hits);
        long matched = 0, detectedTotal = 0, gtTotal = 0;
        int paired = 0;
        for (GroundTruth.Table exp : expected) {
            if (available.isEmpty()) {
                gtTotal += TableScore.relationCount(exp.rows());
                continue;
            }
            TableExtractor.TableHit best = null;
            double bestF1 = -1;
            for (TableExtractor.TableHit h : available) {
                TableScore.Result r = TableScore.score(exp, h.rows);
                if (r.f1() > bestF1) {
                    bestF1 = r.f1();
                    best = h;
                }
            }
            available.remove(best);
            paired++;
            TableScore.AdjResult adj = TableScore.scoreAdjacency(exp, best.rows);
            matched += adj.matched();
            detectedTotal += adj.detectedTotal();
            gtTotal += adj.gtTotal();
        }
        for (TableExtractor.TableHit h : available) {
            detectedTotal += TableScore.relationCount(h.rows);
        }
        double precision = detectedTotal == 0 ? 0.0 : (double) matched / detectedTotal;
        double recall = gtTotal == 0 ? 0.0 : (double) matched / gtTotal;
        double f1 = matched == 0 ? 0.0 : 2 * precision * recall / (precision + recall);
        return new UnitAdjResult(matched, detectedTotal, gtTotal, f1, paired);
    }

    private static final class AdjAgg {
        long matched, detectedTotal, gtTotal;
        final List<Double> perUnitF1 = new ArrayList<>();
        int n;
        int coveredUnits;   // units whose hit list was non-empty
        int pairedUnits;    // units where >=1 GT table got paired to a hit

        void add(UnitAdjResult r, boolean covered) {
            matched += r.matched();
            detectedTotal += r.detectedTotal();
            gtTotal += r.gtTotal();
            perUnitF1.add(r.f1());
            n++;
            if (covered) coveredUnits++;
            if (r.pairedTables() > 0) pairedUnits++;
        }
        double microP() { return detectedTotal == 0 ? 0.0 : (double) matched / detectedTotal; }
        double microR() { return gtTotal == 0 ? 0.0 : (double) matched / gtTotal; }
        double microF1() {
            double p = microP(), r = microR();
            return (p + r) == 0 ? 0.0 : 2 * p * r / (p + r);
        }
        double macroF1() {
            return perUnitF1.isEmpty() ? 0.0 : perUnitF1.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
    }

    // -------------------------------------------------------------------------------- the test

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("rescope"), "set -Drescope=true to run");

        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        List<BakeOffHarness.ScoreUnit> units = corpus.units;
        System.out.println("Rescope corpus: " + units.size() + " PDFs (" + corpus.icdarCount
                + " ICDAR + " + corpus.csvCount + " CSV-matched, " + corpus.overlapCount + " overlap)");

        List<UnitMeasurement> measurements = new ArrayList<>();
        for (BakeOffHarness.ScoreUnit unit : units) {
            measurements.add(measure(unit));
        }

        printLoadErrors(measurements);
        printTask1Classification(measurements);
        printTask2FullPipeline(measurements);
        printTask3LatticeTaggedAlone(measurements);
        printTask4Borderless(measurements);
        printTask5UsEuSplit(measurements);
    }

    private void printLoadErrors(List<UnitMeasurement> measurements) {
        List<UnitMeasurement> bad = measurements.stream()
                .filter(m -> m.loadError() != null || m.taggedLatticeError() != null || m.streamError() != null)
                .toList();
        if (!bad.isEmpty()) {
            System.out.println();
            System.out.println("WARNING: " + bad.size() + " PDF(s) hit an error during measurement:");
            for (UnitMeasurement m : bad) {
                System.out.println("  " + m.unit().id() + ": load=" + m.loadError()
                        + " taggedLattice=" + m.taggedLatticeError() + " stream=" + m.streamError());
            }
        }
    }

    // ============================================================================ Task 1: classify

    private void printTask1Classification(List<UnitMeasurement> measurements) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("TASK 1: corpus classification by TableExtractor.extract (tagged+lattice) output");
        System.out.println("================================================================================");

        Map<String, Integer> bucketCounts = new LinkedHashMap<>();
        for (String b : List.of("lattice", "tagged", "both", "neither")) bucketCounts.put(b, 0);
        for (UnitMeasurement m : measurements) {
            bucketCounts.merge(m.bucket(), 1, Integer::sum);
        }
        System.out.println();
        System.out.println(String.format(Locale.ROOT, "%-10s %6s", "bucket", "count"));
        for (Map.Entry<String, Integer> e : bucketCounts.entrySet()) {
            System.out.println(String.format(Locale.ROOT, "%-10s %6d", e.getKey(), e.getValue()));
        }
        System.out.println(String.format(Locale.ROOT, "%-10s %6d", "TOTAL", measurements.size()));

        System.out.println();
        System.out.println("Per-source breakdown (source x bucket):");
        Map<String, Map<String, Integer>> bySource = new LinkedHashMap<>();
        for (UnitMeasurement m : measurements) {
            bySource.computeIfAbsent(m.source(), k -> new LinkedHashMap<>())
                    .merge(m.bucket(), 1, Integer::sum);
        }
        for (String src : List.of("icdar-us", "icdar-eu", "csv")) {
            Map<String, Integer> row = bySource.getOrDefault(src, Map.of());
            System.out.println(String.format(Locale.ROOT, "  %-10s lattice=%-3d tagged=%-3d both=%-3d neither=%-3d total=%-3d",
                    src, row.getOrDefault("lattice", 0), row.getOrDefault("tagged", 0),
                    row.getOrDefault("both", 0), row.getOrDefault("neither", 0),
                    row.values().stream().mapToInt(Integer::intValue).sum()));
        }

        System.out.println();
        System.out.println("Full per-PDF manifest (id, source, taggedCount, latticeCount, bucket):");
        for (UnitMeasurement m : measurements) {
            System.out.println(String.format(Locale.ROOT, "  %-70s %-9s tagged=%-3d lattice=%-3d %s",
                    m.unit().id(), m.source(), m.taggedCount(), m.latticeCount(), m.bucket()));
        }
    }

    // ======================================================================= Task 2: full pipeline

    private void printTask2FullPipeline(List<UnitMeasurement> measurements) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("TASK 2: FULL PIPELINE (tagged + lattice + stream, overlap-drop merge) vs ICDAR");
        System.out.println("        adjacency. This is the number comparable to published end-to-end results.");
        System.out.println("================================================================================");

        AdjAgg agg = new AdjAgg();
        int droppedStreamTotal = 0;
        int rawStreamTotal = 0;
        for (UnitMeasurement m : measurements) {
            List<TableExtractor.TableHit> combined = new ArrayList<>(m.taggedLatticeHits());
            combined.addAll(m.keptStreamHits());
            droppedStreamTotal += (m.rawStreamHits().size() - m.keptStreamHits().size());
            rawStreamTotal += m.rawStreamHits().size();
            UnitAdjResult r = scoreUnitAdjacency(combined, m.unit().expected());
            agg.add(r, !combined.isEmpty());
        }
        System.out.println();
        System.out.println("Raw stream hits produced across all 77 PDFs (all pages, default Breuel finder): "
                + rawStreamTotal);
        System.out.println("Of those, dropped by the overlap-with-tagged/lattice merge rule (threshold="
                + STREAM_DROP_OVERLAP_THRESHOLD + " of candidate's own bbox area, outer-bbox approximation -- "
                + "see class header): " + droppedStreamTotal + " (kept: " + (rawStreamTotal - droppedStreamTotal) + ")");
        printAgg(agg, measurements.size());
    }

    // ============================================================ Task 3: lattice+tagged alone

    private void printTask3LatticeTaggedAlone(List<UnitMeasurement> measurements) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("TASK 3: LATTICE+TAGGED ALONE (no stream) vs ICDAR adjacency -- the mature path's");
        System.out.println("        own grade on this mostly-ruled corpus.");
        System.out.println("================================================================================");

        AdjAgg agg = new AdjAgg();
        for (UnitMeasurement m : measurements) {
            UnitAdjResult r = scoreUnitAdjacency(m.taggedLatticeHits(), m.unit().expected());
            agg.add(r, !m.taggedLatticeHits().isEmpty());
        }
        printAgg(agg, measurements.size());
    }

    // ================================================================ Task 4: borderless subset

    private void printTask4Borderless(List<UnitMeasurement> measurements) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("TASK 4: genuinely-BORDERLESS subset (tagged+lattice find NOTHING) -- stream path");
        System.out.println("        alone, scored ONLY on this subset. This is the stream path's real grade.");
        System.out.println("================================================================================");

        List<UnitMeasurement> borderless = measurements.stream()
                .filter(m -> m.taggedLatticeHits().isEmpty())
                .toList();

        Map<String, Integer> bySource = new LinkedHashMap<>();
        for (UnitMeasurement m : borderless) bySource.merge(m.source(), 1, Integer::sum);

        System.out.println();
        System.out.println("Borderless subset size: " + borderless.size() + " / " + measurements.size() + " PDFs");
        System.out.println("  by source: icdar-us=" + bySource.getOrDefault("icdar-us", 0)
                + " icdar-eu=" + bySource.getOrDefault("icdar-eu", 0)
                + " csv=" + bySource.getOrDefault("csv", 0));
        if (borderless.size() < 15) {
            System.out.println("  *** SMALL SAMPLE (<15 PDFs): any F1 measured on this subset has very limited");
            System.out.println("      statistical power -- a handful of PDFs can swing it by many points. ***");
        }
        System.out.println();
        System.out.println("Borderless-subset manifest:");
        for (UnitMeasurement m : borderless) {
            System.out.println("  " + m.unit().id() + " (" + m.source() + ")");
        }

        AdjAgg agg = new AdjAgg();
        for (UnitMeasurement m : borderless) {
            UnitAdjResult r = scoreUnitAdjacency(m.rawStreamHits(), m.unit().expected());
            agg.add(r, !m.rawStreamHits().isEmpty());
        }
        System.out.println();
        System.out.println("Stream-alone adjacency score on the borderless subset only (n=" + borderless.size() + "):");
        printAgg(agg, borderless.size());
    }

    // =================================================================== Task 5: US vs EU split

    private void printTask5UsEuSplit(List<UnitMeasurement> measurements) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("TASK 5: ICDAR US vs EU split (CSV-sourced PDFs excluded -- they carry no ICDAR");
        System.out.println("        US/EU designation)");
        System.out.println("================================================================================");

        List<UnitMeasurement> us = measurements.stream().filter(m -> "icdar-us".equals(m.source())).toList();
        List<UnitMeasurement> eu = measurements.stream().filter(m -> "icdar-eu".equals(m.source())).toList();
        System.out.println();
        System.out.println("US subset: " + us.size() + " PDFs.  EU subset: " + eu.size() + " PDFs.");

        for (var pair : List.of(Map.entry("US", us), Map.entry("EU", eu))) {
            String label = pair.getKey();
            List<UnitMeasurement> subset = pair.getValue();

            Map<String, Integer> bucketCounts = new LinkedHashMap<>();
            for (String b : List.of("lattice", "tagged", "both", "neither")) bucketCounts.put(b, 0);
            for (UnitMeasurement m : subset) bucketCounts.merge(m.bucket(), 1, Integer::sum);
            System.out.println();
            System.out.println(label + " classification: " + bucketCounts);

            AdjAgg fullAgg = new AdjAgg();
            AdjAgg ltAgg = new AdjAgg();
            for (UnitMeasurement m : subset) {
                List<TableExtractor.TableHit> combined = new ArrayList<>(m.taggedLatticeHits());
                combined.addAll(m.keptStreamHits());
                fullAgg.add(scoreUnitAdjacency(combined, m.unit().expected()), !combined.isEmpty());
                ltAgg.add(scoreUnitAdjacency(m.taggedLatticeHits(), m.unit().expected()), !m.taggedLatticeHits().isEmpty());
            }
            System.out.println(label + " FULL PIPELINE adjacency:");
            printAgg(fullAgg, subset.size());
            System.out.println(label + " LATTICE+TAGGED ALONE adjacency:");
            printAgg(ltAgg, subset.size());
        }
    }

    // --------------------------------------------------------------------------------- print helper

    private void printAgg(AdjAgg agg, int totalUnits) {
        System.out.println(String.format(Locale.ROOT,
                "  n=%-4d covered=%-4d paired=%-4d  microP=%.4f microR=%.4f MICRO-F1=%.4f  MACRO-F1=%.4f",
                agg.n, agg.coveredUnits, agg.pairedUnits, agg.microP(), agg.microR(), agg.microF1(), agg.macroF1()));
    }
}
