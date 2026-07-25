// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/ (matching
// BakeOffHarness / RescopeHarness / MetricFixHarness's own convention -- see their headers), but
// declares `package com.oai.titanarum;` because it needs direct access to package-private production
// types (TableExtractor, TableExtractor.TableHit, StreamTableExtractor, GutterFinder and the four
// finder implementations), the package-private test helper TableTestPdfs.harvestGlyphs, and
// MetricFixHarness's region-given machinery. GroundTruth, GtDedup and TableScore are public and
// imported normally from com.oai.titanarum.bakeoff below.
//
// PURPOSE. This harness removes the two CONFIRMED MEASUREMENT ARTIFACTS from the project's reference
// baseline and re-reports every configuration under the corrected protocol. It changes NOTHING about
// extraction -- no file under src/main is touched by the change that added it -- and it changes
// nothing about the definition of a relation or of adjacency matching (that definition is validated
// against the OFFICIAL ICDAR 2013 evaluator's own printed output: 156 tables / 25,320 relations,
// agreeing exactly, per document, with the `GT size:` lines the official JAR emits on this corpus,
// and with an independent Python port of the same algorithm). What it changes is PROTOCOL and
// ground-truth HYGIENE:
//
//   ARTIFACT 1 -- 1:1 greedy table pairing. Every previous harness paired each ground-truth table to
//   at most one detected table (greedily, by exact-cell F1), counted unpaired ground-truth tables as
//   pure recall loss and unpaired detected tables as pure precision loss. That protocol charges the
//   extractor for disagreeing with the annotator about where one table ends and the next begins --
//   a judgement the corpus itself is not consistent about (adjacent mini-tables are sometimes one
//   annotated table, sometimes several). Adjacency relations are content-identified and
//   translation-invariant, so DOCUMENT-POOLED comparison -- all of a document's ground-truth
//   relations against all of its detected relations, with no table correspondence at all -- measures
//   whether the page's content structure was recovered without also demanding that it be partitioned
//   the annotator's way. Pooling is added here as the PRIMARY protocol; 1:1 greedy is retained as a
//   selectable secondary so every previously published number stays reproducible. Both are reported
//   for every configuration, and every printed number states which protocol produced it.
//
//   Note on what was already ruled out: it is POOLING that matters, not pairing order. Substituting
//   geometric-IoU pairing for F1 pairing moved lattice+tagged micro F1 by 0.0008 (0.2434 -> 0.2442),
//   and even an ORACLE best-adjacency pairing only reached 0.3018, still short of pooling's 0.3115.
//   So this harness does not bother with alternative pairing rules; it reports the two protocols that
//   actually differ.
//
//   ARTIFACT 2 -- duplicate ground-truth annotations. Four *b-str.xml files annotate the same PDFs as
//   their "a" siblings, so 7 of 173 expected-table entries are a SECOND annotation of a table that is
//   already in the list (see GtDedup for the full argument). Because every correspondence protocol
//   consumes a detected table at most once, at least half of each duplicated pair can never pair and
//   is scored as a total miss regardless of extraction quality. GtDedup removes them by bbox IoU and
//   this harness prints the exact audit list. Both the de-duplicated and the RAW ground truth are
//   reported for every configuration.
//
// Gated by -Dbaseline=true AND named so Surefire's default includes ({@code **/Test*.java},
// {@code **}/{@code *Test.java}, {@code **}/{@code *Tests.java}, {@code **}/{@code *TestCase.java})
// never discover it. Run:
//   mvn -q -o test -Dtest=BaselineHarness -Dbaseline=true
// Optionally -DbaselineOut=<path> to also write the report to a file (default target/baseline-report.md).
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.GtDedup;
import com.oai.titanarum.bakeoff.TableScore;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

class BaselineHarness {

    // ------------------------------------------------------------------------------------- keys

    /** End-to-end: the extractor must FIND the table as well as recover its structure. */
    private static final String M_E2E = "e2e";
    /** Region given, output cropped to it: the ICDAR 2013 "structure only" sub-task's scope. */
    private static final String M_REGION = "region-given";
    /** Region given, stream path RE-RUN on region-restricted glyphs (stream configurations only). */
    private static final String M_RERUN = "region-given-rerun";

    /** PRIMARY protocol: pool a document's relations on both sides, no table correspondence. */
    private static final String P_POOL = "POOLED";
    /** Secondary protocol, retained for reproducibility: greedy 1:1 table pairing on exact-cell F1. */
    private static final String P_PAIR = "1:1";
    /**
     * The OFFICIAL 1:1 protocol: greedy per-page pairing on CORRECT ADJACENCY RELATIONS, transcribed
     * from the ICDAR 2013 evaluator's own source. This is the row that is comparable to the published
     * end-to-end column; {@link #P_PAIR} is the same shape with a foreign pairing criterion.
     * @see #e2ePairedOfficial
     */
    private static final String P_PAIR_OFF = "1:1off";
    /**
     * MEASUREMENT ONLY, never a headline: {@link #P_PAIR_OFF} plus the reference pipeline's free pass
     * for a ground-truth table that has no candidate on its page. Reported by
     * {@link #printOfficialLeniency} so the size of the leniency we DECLINE is on the record, and
     * excluded from {@link #PROTOCOL_ORDER} so it can never be mistaken for a baseline row.
     */
    private static final String P_LENIENT = "1:1off-lenient";

    /** PRIMARY ground truth: duplicate annotations of the same physical table removed. */
    private static final String G_DEDUP = "dedup";
    /** Secondary ground truth, retained for reproducibility: the corpus exactly as shipped. */
    private static final String G_RAW = "raw";

    private static final String C_FULL   = "full(tagged+lattice+stream)";
    /** The same three paths, merged by {@link TableExtractor#arbitrate} (per-region path arbitration
     *  on extraction-time signals) instead of by the positional overlap-drop {@link #C_FULL} uses.
     *  Added ALONGSIDE C_FULL, never replacing it, so both merge rules are reported side by side and
     *  every pre-existing row of this report is unchanged. */
    private static final String C_FULL_ARB = "full+arbitration";
    private static final String C_LT     = "lattice+tagged";
    private static final String C_STREAM = "stream";
    private static final List<String> FINDER_NAMES =
            List.of("breuel", "gapvote", "alignedge", "occupancy");

    // ------------------------------------------------------------------------------- page scope

    /** ALL pages of every document -- the scope every figure this project has published used. */
    private static final String S_ALL  = "all-pages";
    /**
     * Exactly the pages the shipping CLI processes at its DEFAULT {@code --pages default}: first four
     * plus the last, with blank pages substituted by the next non-blank ones. Not re-implemented here
     * -- see {@link ShippingPages}, which reflects into production's own selection code.
     *
     * <p>WHY THIS ROW EXISTS. Every published table figure scored all pages; the shipping default
     * does not. A user running {@code pdf-titan-arum} with no {@code --pages} flag gets the SHIPPING
     * number, so that is the number they are owed. Ground-truth tables on unprocessed pages are
     * charged as full recall loss here, because that is exactly what happens in the field.
     */
    private static final String S_SHIP = "shipping-dflt";

    private static final List<String> SCOPES = List.of(S_ALL, S_SHIP);

    /** The order every table in this report lists its four (protocol, ground-truth) combinations in:
     *  the primary first, the pre-correction baseline last. */
    private static final List<String[]> PROTOCOL_ORDER = List.of(
            new String[]{P_POOL, G_DEDUP},       // PRIMARY -- both artifacts removed
            new String[]{P_POOL, G_RAW},         // pooling only
            new String[]{P_PAIR_OFF, G_DEDUP},   // official 1:1 (the published-comparable row)
            new String[]{P_PAIR_OFF, G_RAW},
            new String[]{P_PAIR, G_DEDUP},       // dedup only
            new String[]{P_PAIR, G_RAW});        // neither -- reproduces the pre-correction numbers

    private static String key(String config, String mode, String protocol, String gtSet) {
        return config + " | " + mode + " | " + protocol + " | " + gtSet;
    }

    private static boolean isStreamConfig(String config) {
        return config.equals(C_STREAM) || config.startsWith(C_STREAM + ":");
    }

    /**
     * The protocol this report treats as PRIMARY for a given mode.
     *
     * <p>END-TO-END: pooled. The extractor had to decide the table boundaries itself, and the corpus
     * is not self-consistent about them, so charging a boundary disagreement as a content error is the
     * artifact being removed.
     *
     * <p>REGION-GIVEN (and its rerun variant): per-region, i.e. 1:1. Handing over the region hands
     * over the segmentation, so there is nothing left for pooling to forgive and its justification
     * lapses; the per-region row is also the one that is comparable to the published region-given
     * column. Measured here at &lt;=0.0002 MACRO apart from the pooled row either way, so this choice
     * changes no conclusion -- it is made for correctness of interpretation, not for the number.
     */
    private static String primaryProtocol(String mode) {
        return mode.equals(M_E2E) ? P_POOL : P_PAIR;
    }

    /** Below this, an F1 move is reported as noise rather than as a regression/improvement. */
    private static final double MATERIALITY = 0.002;

    // ------------------------------------------------------------------------------ output sink

    private final StringBuilder out = new StringBuilder();

    private void line(String s) {
        out.append(s).append('\n');
        System.out.println(s);
    }

    private void line(String fmt, Object... args) {
        line(String.format(Locale.ROOT, fmt, args));
    }

    private void rule() {
        line("================================================================================");
    }

    // ------------------------------------------------------------------------------ accumulators

    /** One document's (matched, detected, gt) for one key. */
    static final class Tally {
        long matched, detected, gt;
        int tables;
        boolean covered;

        void add(TableScore.AdjResult r) {
            matched += r.matched();
            detected += r.detectedTotal();
            gt += r.gtTotal();
        }
        void addGtOnly(int n) { gt += n; }
        void addDetOnly(int n) { detected += n; }
    }

    /**
     * Micro sums plus the per-document F1 list MACRO is the mean of. Macro is reported first
     * everywhere because the published ICDAR 2013 figures are per-document averages.
     */
    private static final class Acc {
        long matched, detected, gt;
        final List<Double> perDocF1 = new ArrayList<>();
        final List<Double> perDocP = new ArrayList<>();
        final List<Double> perDocR = new ArrayList<>();
        int docs, covered, scoredTables;

        void addDoc(Tally t) {
            matched += t.matched; detected += t.detected; gt += t.gt;
            double p = t.detected == 0 ? 0.0 : (double) t.matched / t.detected;
            double r = t.gt == 0 ? 0.0 : (double) t.matched / t.gt;
            perDocP.add(p);
            perDocR.add(r);
            perDocF1.add(t.matched == 0 ? 0.0 : 2 * p * r / (p + r));
            docs++;
            if (t.covered) covered++;
            scoredTables += t.tables;
        }
        double microP() { return detected == 0 ? 0.0 : (double) matched / detected; }
        double microR() { return gt == 0 ? 0.0 : (double) matched / gt; }
        double microF1() {
            double p = microP(), r = microR();
            return (p + r) == 0 ? 0.0 : 2 * p * r / (p + r);
        }
        double macroF1() {
            return perDocF1.isEmpty() ? 0.0
                    : perDocF1.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
        private static double mean(List<Double> xs) {
            return xs.isEmpty() ? 0.0 : xs.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        }
        double macroP() { return mean(perDocP); }
        double macroR() { return mean(perDocR); }

        /**
         * The MACRO F1 the published ICDAR 2013 per-document averages actually are: the harmonic mean
         * of the mean per-document precision and the mean per-document recall, NOT the mean of the
         * per-document F1 scores. Verified against the reference pipeline's own printed output
         * ({@code mnamysl/tabrec-sncs}, {@code evaluation/ICDAR2013/eval.py} lines 151-156:
         * {@code Precision = perdoc_precision/num_docs; Recall = perdoc_recall/num_docs;
         * F1 = 2PR/(P+R)}), whose logged run prints
         * {@code Precision: 0.8714227705826723; Recall: 0.8467831413095247; F1: 0.8589262858140391}
         * -- and 2*0.87142277*0.84678314/(0.87142277+0.84678314) = 0.85893, which is that F1 to 5
         * decimal places, while the mean of the per-document F1 values is a different number.
         *
         * <p>{@link #macroF1} (mean of per-document F1) is what every figure this project has
         * published used. Both are printed side by side; neither is silently substituted for the
         * other, because F1-of-means is systematically the LARGER of the two whenever per-document
         * precision and recall are anti-correlated, and that difference flatters us.
         */
        double macroF1Official() {
            double p = macroP(), r = macroR();
            return (p + r) == 0 ? 0.0 : 2 * p * r / (p + r);
        }
    }

    /**
     * A document contributes to a key unless it has nothing at all on either side. Identical to
     * MetricFixHarness#aggregate's rule, so the (1:1, raw) rows of this report reproduce that
     * harness's numbers exactly rather than approximately.
     */
    private static Acc aggregate(List<DocResult> docs, String key) {
        Acc a = new Acc();
        for (DocResult d : docs) {
            Tally t = d.tallies.get(key);
            if (t == null) continue;
            if (t.gt == 0 && t.detected == 0 && t.tables == 0) continue;
            a.addDoc(t);
        }
        return a;
    }

    // --------------------------------------------------------------------------- per-document data

    private static final class DocResult {
        String id, source, bucket;
        /** This document's OWN tagged/lattice classification under THIS scope's page selection.
         *  {@link #bucket} may have been overwritten with the all-pages classification so that the
         *  {@code borderless} subset means the same set of documents in both scopes. */
        String nativeBucket;
        String scope;
        int pageCount, blankPages;
        List<Integer> selectedPages = List.of();
        int taggedCount, latticeCount, rawStreamHits, keptStreamHits;
        int gtTablesRaw, gtTablesDedup;
        int gtRelRaw, gtRelDedup;
        List<GtDedup.Duplicate> removedDuplicates = List.of();
        String error;
        double streamMs;
        final Map<String, Tally> tallies = new LinkedHashMap<>();
        /** config -> how many table hits that configuration produced on this document. Used only by
         *  #printPoolingMechanism, to check that the pooled/1:1 gap really is a segmentation effect. */
        final Map<String, Integer> hitCounts = new LinkedHashMap<>();
        /** config -> the {@link PairAudit} of the CURRENT 1:1 rule on this document, dedup GT. */
        final Map<String, PairAudit> audits = new LinkedHashMap<>();
    }

    /** One ground-truth view of a document: the tables to score against, and their RAW indices
     *  (needed to look up the per-table region re-run cache, which is keyed on the raw list). */
    private record GtView(String name, List<GroundTruth.Table> tables, List<Integer> rawIndices) {}

    // ------------------------------------------------------------------------------ relation glue

    /** Official-definition relations of a cell collection. The ONLY relation builder used anywhere in
     *  this harness -- both protocols compare relations produced by this exact call. */
    private static List<TableScore.Relation> rels(List<TableScore.GridCell> cells) {
        return TableScore.buildOfficialRelations(cells, false).relations();
    }

    private static List<TableScore.Relation> gtRels(GroundTruth.Table t) {
        return rels(TableScore.gridCellsFromGroundTruth(t));
    }

    private static TableScore.AdjResult compare(List<TableScore.Relation> gt,
                                                 List<TableScore.Relation> det) {
        return TableScore.compareRelations(gt, det, TableScore.Semantics.MULTISET);
    }

    // -------------------------------------------------------------------------------- END-TO-END

    /**
     * One detected-table candidate, reduced to exactly what a correspondence rule may look at: the
     * page it sits on, its text grid (what the legacy exact-cell pairing rule scores) and its cells
     * with spans (what the adjacency metric scores). Introduced so the SAME pairing code can be fed
     * real extractor output and the synthetic/degraded detectors of {@link #printPairingControls} --
     * a protocol cannot be validated against a deliberately-bad detector if the protocol is welded
     * to {@code TableExtractor.TableHit}.
     */
    record Cand(int page, List<List<String>> rows, List<TableScore.GridCell> cells) {
        static Cand of(TableExtractor.TableHit h) {
            return new Cand(h.page, h.rows, MetricFixHarness.cellsOf(h));
        }
        static List<Cand> of(List<TableExtractor.TableHit> hits) {
            List<Cand> out = new ArrayList<>(hits.size());
            for (TableExtractor.TableHit h : hits) out.add(of(h));
            return out;
        }
    }

    /**
     * The page a ground-truth table is annotated on, as the OFFICIAL evaluator reads it: the
     * {@code page} attribute of the table's FIRST {@code <region>} element
     * ({@code Table.java:38}, {@code pageNo = parseInt(regionElement.getAttribute("page"))}).
     * Our {@code GroundTruth.Cell} carries that attribute per cell, so the first cell that declares
     * a positive page is the same value.
     *
     * <p>Returns {@link #PAGE_UNKNOWN} for CSV-sourced ground truth, which has no page attribute at
     * all; {@link #e2ePairedOfficial} then declines to apply the page filter for that table rather
     * than inventing a page number.
     */
    private static final int PAGE_UNKNOWN = 0;

    private static int gtPageOf(GroundTruth.Table t) {
        for (GroundTruth.Cell c : t.cells()) {
            if (c.page() > 0) return c.page();
        }
        return PAGE_UNKNOWN;
    }

    /**
     * END-TO-END, 1:1 GREEDY PAIRING ON EXACT-CELL F1 (the pre-correction protocol, retained
     * verbatim so every previously published figure stays reproducible). Each expected table is
     * paired with whichever remaining hit maximises EXACT-CELL F1 and scored against it; expected
     * tables left unpaired contribute their whole relation count as recall loss, and hits left over
     * contribute theirs as precision loss.
     *
     * <p><b>This rule is NOT the official one</b> -- see {@link #e2ePairedOfficial}. Exact-cell F1
     * appears nowhere in the official evaluator, and because it is a position-identified metric it
     * returns 0 for every candidate whose row/column indices are offset from the annotator's, which
     * makes the {@code f1 > bestF1} comparison degenerate into "keep the first hit in enumeration
     * order" exactly on the documents where the correspondence matters most. Kept as a measured
     * secondary, not as a claim about the published protocol.
     */
    static Tally e2ePaired(List<Cand> hits, List<GroundTruth.Table> expected) {
        Tally t = new Tally();
        t.covered = !hits.isEmpty();
        List<Cand> available = new ArrayList<>(hits);
        for (GroundTruth.Table exp : expected) {
            List<TableScore.GridCell> gtCells = TableScore.gridCellsFromGroundTruth(exp);
            if (available.isEmpty()) {
                t.addGtOnly(TableScore.officialRelationCount(gtCells, false,
                        TableScore.Semantics.MULTISET));
                continue;
            }
            Cand best = null;
            double bestF1 = -1;
            for (Cand h : available) {
                double f1 = TableScore.score(exp, h.rows()).f1();
                if (f1 > bestF1) { bestF1 = f1; best = h; }
            }
            available.remove(best);
            t.add(compare(rels(gtCells), rels(best.cells())));
            t.tables++;
        }
        for (Cand h : available) {
            t.addDetOnly(TableScore.officialRelationCount(h.cells(), false,
                    TableScore.Semantics.MULTISET));
        }
        return t;
    }

    /**
     * END-TO-END, 1:1 GREEDY PAIRING EXACTLY AS THE OFFICIAL ICDAR 2013 EVALUATOR DOES IT.
     *
     * <p>Transcribed from {@code MeasureRecognitionPerformance.evaluateResultStr} in
     * {@code github.com/tamirhassan/dataset-tools} (Apache-2.0, "as used in the ICDAR 2013 Table
     * Competition"), read from a local clone rather than reconstructed from prose. The four rules
     * that differ from {@link #e2ePaired} are, verbatim from that source:
     *
     * <ol>
     *   <li><b>The correspondence is scored on CORRECT ADJACENCY RELATIONS, not on exact-cell F1.</b>
     *       {@code int corrDec = compareARs(gtAR, resultAR, false, normRule);
     *       if (corrDec > highestCorr) { highestCorr = corrDec; matchingResult = resultAR; }} --
     *       i.e. argmax over the count {@code compareARs} returns, which is the same multiset
     *       intersection {@link #compare} computes as {@code AdjResult#matched}. Unnormalised: a
     *       larger candidate with more correct relations beats a smaller, cleaner one, and this
     *       harness reproduces that rather than "improving" it to argmax-F1.</li>
     *   <li><b>Candidates are restricted to the ground-truth table's own PAGE</b>, by default:
     *       {@code if (!pageCheck || resultTable.pageNo == gtTable.pageNo) resultsOnPage.add(...)}
     *       with {@code boolean pageCheck = true} unless {@code -nopage} is passed
     *       ({@code MeasureRecognitionPerformance.java:96,119,677}).</li>
     *   <li><b>{@code highestCorr} starts at -1</b>, so the first candidate on the page is paired
     *       even when it has ZERO correct relations. {@link #e2ePaired}'s {@code bestF1 = -1} has the
     *       same effect, so this is the one property the two rules share.</li>
     *   <li><b>Ties keep the FIRST candidate</b> ({@code >}, not {@code >=}) -- reproduced. The
     *       official tool counts ties in {@code numHighest} and then ignores the count; so does this,
     *       except that {@link #printPairingDegeneracy} reports how often a tie decided the pairing,
     *       because an unreported tie-break is how the artifact under investigation hid.</li>
     * </ol>
     *
     * <p>{@code lenientMissedTables} selects the one remaining behaviour of the official
     * MEASUREMENT PIPELINE that this harness does NOT adopt for any headline figure. When a
     * ground-truth table has no candidate on its page, the tool prints {@code "no matching result
     * found"} with no numbers, and the reference aggregation script ({@code mnamysl/tabrec-sncs},
     * {@code evaluation/ICDAR2013/eval.py}) sums only the {@code Table n:} lines -- so that table
     * contributes NOTHING to the recall denominator and a completely missed table is not penalised
     * at all. {@code false} (used everywhere except the explicit measurement in
     * {@link #printOfficialLeniency}) charges it, as every other protocol in this harness does.
     * The reason for declining it is in that method: it makes emitting nothing on a page score
     * strictly better than emitting something wrong, which is a perverse incentive, not a metric.
     */
    static Tally e2ePairedOfficial(List<Cand> hits, List<GroundTruth.Table> expected,
                                            boolean lenientMissedTables) {
        Tally t = new Tally();
        t.covered = !hits.isEmpty();
        List<Cand> remaining = new ArrayList<>(hits);
        for (GroundTruth.Table exp : expected) {
            List<TableScore.GridCell> gtCells = TableScore.gridCellsFromGroundTruth(exp);
            List<TableScore.Relation> gtRel = rels(gtCells);
            int gtPage = gtPageOf(exp);
            Cand best = null;
            int bestCorr = -1;                       // official: highestCorr = -1
            for (Cand h : remaining) {
                if (gtPage != PAGE_UNKNOWN && h.page() != gtPage) continue;
                int corr = compare(gtRel, rels(h.cells())).matched();
                if (corr > bestCorr) { bestCorr = corr; best = h; }
            }
            if (best == null) {                      // official: "no matching result found"
                if (!lenientMissedTables) t.addGtOnly(gtRel.size());
                continue;                            // no `Table n:` line -> not a scored table
            }
            remaining.remove(best);
            t.add(compare(gtRel, rels(best.cells())));
            t.tables++;
        }
        for (Cand h : remaining) {                   // official: "FP table with N adjacency relations"
            t.addDetOnly(TableScore.officialRelationCount(h.cells(), false,
                    TableScore.Semantics.MULTISET));
        }
        return t;
    }

    /**
     * PER-DOCUMENT DIAGNOSIS of the CURRENT ({@link #P_PAIR}) correspondence rule -- the audit that
     * decides whether the reported degeneracy is real and how widespread it is. Walks exactly the same
     * greedy loop {@link #e2ePaired} walks and, at each step, records what the exact-cell-F1 argmax
     * did against what the count of correct adjacency relations says about the SAME availability set.
     *
     * <p>Two degenerate outcomes are counted separately because they are different failures:
     *
     * <ul>
     *   <li><b>tie at zero</b> -- two or more candidates were available and EVERY one of them scored
     *       exact-cell F1 = 0.0, so {@code f1 > bestF1} never fired after the first candidate and the
     *       pairing was decided by enumeration order alone. Exact-cell F1 is position-identified, so
     *       any candidate whose row/column indices are offset from the annotator's scores 0 no matter
     *       how much content it recovered; a whole page of good candidates can tie at zero.</li>
     *   <li><b>mis-assignment</b> -- the candidate actually chosen has strictly FEWER correct
     *       adjacency relations than another candidate that was still available at that step. This is
     *       the "left a clearly-better candidate unpaired" case, and {@code lostRelations} is how many
     *       correct relations the choice cost, summed over the document.</li>
     * </ul>
     *
     * <p>Deliberately measured over the SAME candidate set the current rule saw -- no page filter --
     * so that what is being isolated is the CRITERION, not the page restriction the official rule
     * also applies. The two effects are separated again in the report.
     */
    static final class PairAudit {
        int gtTables, tieAtZeroTables, misassignedTables, lostRelations;
        int currentCorr, bestStepwiseCorr;

        boolean degenerate() { return tieAtZeroTables > 0 || misassignedTables > 0; }
    }

    static PairAudit auditPairing(List<Cand> hits, List<GroundTruth.Table> expected) {
        PairAudit a = new PairAudit();
        List<Cand> available = new ArrayList<>(hits);
        for (GroundTruth.Table exp : expected) {
            a.gtTables++;
            if (available.isEmpty()) continue;
            List<TableScore.Relation> gtRel = rels(TableScore.gridCellsFromGroundTruth(exp));

            Cand chosen = null;
            double bestF1 = -1;
            int nonZeroF1 = 0;
            for (Cand h : available) {
                double f1 = TableScore.score(exp, h.rows()).f1();
                if (f1 > 0) nonZeroF1++;
                if (f1 > bestF1) { bestF1 = f1; chosen = h; }
            }
            int bestCorr = -1;
            for (Cand h : available) {
                bestCorr = Math.max(bestCorr, compare(gtRel, rels(h.cells())).matched());
            }
            int chosenCorr = compare(gtRel, rels(chosen.cells())).matched();
            a.currentCorr += chosenCorr;
            a.bestStepwiseCorr += Math.max(bestCorr, 0);
            if (available.size() > 1 && nonZeroF1 == 0) a.tieAtZeroTables++;
            if (bestCorr > chosenCorr) {
                a.misassignedTables++;
                a.lostRelations += bestCorr - chosenCorr;
            }
            available.remove(chosen);
        }
        return a;
    }

    /**
     * END-TO-END, DOCUMENT-POOLED (the primary protocol). Every relation of every expected table in
     * the document goes into one multiset; every relation of every detected hit in the document goes
     * into another; the two are compared once with the same multiset comparison the 1:1 protocol uses.
     *
     * <p>There is no table correspondence, so a detected table that merges two annotated tables (or
     * splits one) is neither rewarded nor punished for that choice -- only for the relations it got
     * right or wrong. Spurious tables are still fully charged: their relations enter the detected
     * multiset and match nothing, exactly as the 1:1 protocol's unpaired-hit bookkeeping intends.
     * Missed tables are still fully charged: their relations sit in the ground-truth multiset
     * unmatched.
     */
    private static Tally e2ePooled(List<Cand> hits, List<GroundTruth.Table> expected) {
        Tally t = new Tally();
        t.covered = !hits.isEmpty();
        List<TableScore.Relation> gt = new ArrayList<>();
        for (GroundTruth.Table exp : expected) gt.addAll(gtRels(exp));
        List<TableScore.Relation> det = new ArrayList<>();
        for (Cand h : hits) det.addAll(rels(h.cells()));
        t.add(compare(gt, det));
        t.tables = expected.size();
        return t;
    }

    // ------------------------------------------------------------------------------ REGION-GIVEN

    /**
     * REGION-GIVEN, per-region (which IS the 1:1 protocol here -- being handed the region fixes the
     * correspondence, so there is nothing left for a pairing rule to decide). Each expected table
     * with usable geometry is scored against the cells the configuration produced inside that table's
     * own region; anything produced outside every region is ignored. Expected tables with no geometry
     * (CSV ground truth, always) are EXCLUDED from this mode rather than counted as misses -- the
     * printed {@code docs} column shows the resulting smaller denominator.
     */
    private static Tally regionPaired(List<TableExtractor.TableHit> hits, GtView gt,
                                       Map<Integer, PDRectangle> cropByPage) {
        Tally t = new Tally();
        for (GroundTruth.Table exp : gt.tables()) {
            List<MetricFixHarness.Region> regions = MetricFixHarness.regionsOf(exp, cropByPage);
            if (regions.isEmpty()) continue;
            List<TableScore.GridCell> detCells = MetricFixHarness.regionGivenCells(hits, regions, null);
            List<TableScore.GridCell> gtCells = TableScore.gridCellsFromGroundTruth(exp);
            if (detCells.isEmpty()) {
                t.addGtOnly(TableScore.officialRelationCount(gtCells, false,
                        TableScore.Semantics.MULTISET));
            } else {
                t.covered = true;
                t.add(compare(rels(gtCells), rels(detCells)));
            }
            t.tables++;
        }
        return t;
    }

    /**
     * REGION-GIVEN, POOLED. Same region-restricted candidates, but the document's expected relations
     * and its region-restricted detected relations are pooled before comparing.
     *
     * <p>Honest caveat, stated here and again in the report: pooling is much less consequential in
     * this mode, and its justification is weaker. The argument for pooling is that table SEGMENTATION
     * is ambiguous -- but region-given mode has already handed the segmentation over, so there is
     * little left for pooling to forgive. It is reported for completeness and symmetry, and because
     * the pooled/per-region delta is itself evidence about how disjoint the annotated regions are.
     */
    private static Tally regionPooled(List<TableExtractor.TableHit> hits, GtView gt,
                                       Map<Integer, PDRectangle> cropByPage) {
        Tally t = new Tally();
        List<TableScore.Relation> gtAll = new ArrayList<>();
        List<TableScore.Relation> detAll = new ArrayList<>();
        for (GroundTruth.Table exp : gt.tables()) {
            List<MetricFixHarness.Region> regions = MetricFixHarness.regionsOf(exp, cropByPage);
            if (regions.isEmpty()) continue;
            gtAll.addAll(gtRels(exp));
            List<TableScore.GridCell> detCells = MetricFixHarness.regionGivenCells(hits, regions, null);
            if (!detCells.isEmpty()) {
                t.covered = true;
                detAll.addAll(rels(detCells));
            }
            t.tables++;
        }
        t.add(compare(gtAll, detAll));
        return t;
    }

    // ------------------------------------------------------------------- REGION-GIVEN, STREAM RERUN

    /** REGION-GIVEN RERUN, per-region: the stream path re-run on region-restricted glyphs. */
    private static Tally rerunPaired(GtView gt, List<GroundTruth.Table> allRaw,
                                      Map<Integer, List<TableExtractor.TableHit>> rerunByRawIndex) {
        Tally t = new Tally();
        for (int i = 0; i < gt.tables().size(); i++) {
            int raw = gt.rawIndices().get(i);
            List<TableExtractor.TableHit> hits = rerunByRawIndex.get(raw);
            if (hits == null) continue;   // no usable geometry -> excluded, as in region-given mode
            List<TableScore.GridCell> gtCells =
                    TableScore.gridCellsFromGroundTruth(allRaw.get(raw));
            List<TableScore.GridCell> detCells = MetricFixHarness.stackAll(hits);
            if (detCells.isEmpty()) {
                t.addGtOnly(TableScore.officialRelationCount(gtCells, false,
                        TableScore.Semantics.MULTISET));
            } else {
                t.covered = true;
                t.add(compare(rels(gtCells), rels(detCells)));
            }
            t.tables++;
        }
        return t;
    }

    /** REGION-GIVEN RERUN, pooled. */
    private static Tally rerunPooled(GtView gt, List<GroundTruth.Table> allRaw,
                                      Map<Integer, List<TableExtractor.TableHit>> rerunByRawIndex) {
        Tally t = new Tally();
        List<TableScore.Relation> gtAll = new ArrayList<>();
        List<TableScore.Relation> detAll = new ArrayList<>();
        for (int i = 0; i < gt.tables().size(); i++) {
            int raw = gt.rawIndices().get(i);
            List<TableExtractor.TableHit> hits = rerunByRawIndex.get(raw);
            if (hits == null) continue;
            gtAll.addAll(gtRels(allRaw.get(raw)));
            List<TableScore.GridCell> detCells = MetricFixHarness.stackAll(hits);
            if (!detCells.isEmpty()) {
                t.covered = true;
                detAll.addAll(rels(detCells));
            }
            t.tables++;
        }
        t.add(compare(gtAll, detAll));
        return t;
    }

    // -------------------------------------------------------------- shipping page selection

    /**
     * The SHIPPING page selection, taken from PRODUCTION ITSELF rather than re-derived.
     *
     * <p>{@code PdfTitanArumApp#computePagesToProcess}, {@code #classifyBlankPages} and
     * {@code #fillBlankPages} are private, so they are reached reflectively. That is deliberate: a
     * transcription of "first four pages plus the last" into this harness would be a SECOND
     * implementation of the shipping default, free to drift from it silently -- and this whole class
     * exists because the harness had drifted from production once already. Reflection cannot drift;
     * it either resolves the real member or fails loudly.
     *
     * <p>No {@code src/main} file is touched, and nothing about production's behaviour changes: these
     * are read-only calls on a throwaway instance whose picocli option fields are never consulted by
     * any of the three methods.
     */
    private static final class ShippingPages {
        private static final Object APP;
        private static final java.lang.reflect.Method COMPUTE, CLASSIFY, FILL;

        static {
            try {
                java.lang.reflect.Constructor<PdfTitanArumApp> ctor =
                        PdfTitanArumApp.class.getDeclaredConstructor();
                ctor.setAccessible(true);
                APP = ctor.newInstance();
                COMPUTE = PdfTitanArumApp.class
                        .getDeclaredMethod("computePagesToProcess", String.class, int.class);
                CLASSIFY = PdfTitanArumApp.class
                        .getDeclaredMethod("classifyBlankPages", PDDocument.class);
                FILL = PdfTitanArumApp.class
                        .getDeclaredMethod("fillBlankPages", List.class, Set.class, int.class);
                COMPUTE.setAccessible(true);
                CLASSIFY.setAccessible(true);
                FILL.setAccessible(true);
            } catch (ReflectiveOperationException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        /** {blank page count, selected pages} for the CLI's default {@code --pages default}. */
        @SuppressWarnings("unchecked")
        static Selection select(PDDocument doc) {
            try {
                int pageCount = doc.getNumberOfPages();
                List<Integer> pages = (List<Integer>) COMPUTE.invoke(APP, "default", pageCount);
                // Production applies blank-page substitution unless --no-skip-blanks; the default is
                // to apply it, and only when something was actually classified blank.
                Set<Integer> blank = (Set<Integer>) CLASSIFY.invoke(APP, doc);
                List<Integer> selected = blank.isEmpty()
                        ? pages : (List<Integer>) FILL.invoke(APP, pages, blank, pageCount);
                return new Selection(pageCount, blank.size(), selected);
            } catch (ReflectiveOperationException e) {
                // Never silently substitute an approximation: a guessed page selection would make the
                // shipping row a fiction, which is the exact class of defect this harness fixes.
                throw new IllegalStateException("cannot reach production's own page selection", e);
            }
        }
    }

    private record Selection(int pageCount, int blankPages, List<Integer> pages) {}

    // ---------------------------------------------------------------------------- per-document run

    private static DocResult measure(BakeOffHarness.ScoreUnit unit, List<GutterFinder> finders,
                                     String scope) {
        DocResult d = new DocResult();
        d.scope = scope;
        d.id = unit.id();
        d.source = unit.id().contains("competition-dataset-us") ? "icdar-us"
                : unit.id().contains("competition-dataset-eu") ? "icdar-eu" : "csv";

        List<GroundTruth.Table> raw = unit.expected();
        d.gtTablesRaw = raw.size();
        GtDedup.Result dedup = GtDedup.dedup(raw);
        d.removedDuplicates = dedup.removed();
        d.gtTablesDedup = dedup.kept().size();

        Set<Integer> removedIdx = new HashSet<>();
        for (GtDedup.Duplicate dup : dedup.removed()) removedIdx.add(dup.removedIndex());
        List<Integer> rawIdxAll = new ArrayList<>();
        List<Integer> rawIdxDedup = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            rawIdxAll.add(i);
            if (!removedIdx.contains(i)) rawIdxDedup.add(i);
        }
        List<GroundTruth.Table> keptTables = new ArrayList<>();
        for (int i : rawIdxDedup) keptTables.add(raw.get(i));

        for (GroundTruth.Table t : raw) d.gtRelRaw += gtRels(t).size();
        for (GroundTruth.Table t : keptTables) d.gtRelDedup += gtRels(t).size();

        List<GtView> gtViews = List.of(
                new GtView(G_DEDUP, keptTables, rawIdxDedup),
                new GtView(G_RAW, raw, rawIdxAll));

        try (PDDocument doc = Loader.loadPDF(unit.pdf().toFile())) {
            int pages = doc.getNumberOfPages();
            d.pageCount = pages;

            // The pages this scope actually processes. Under S_SHIP the glyph map is built ONLY for
            // those pages, exactly as production does (PdfTitanArumApp#stripTextPerPage skips every
            // page outside the selection), so an unprocessed page's tables are genuinely invisible to
            // every path -- including the lattice path, which is handed a null glyph list for pages
            // absent from the map.
            List<Integer> pageList = new ArrayList<>();
            if (scope.equals(S_SHIP)) {
                Selection sel = ShippingPages.select(doc);
                d.blankPages = sel.blankPages();
                pageList.addAll(sel.pages());
            } else {
                for (int p = 1; p <= pages; p++) pageList.add(p);
            }
            d.selectedPages = List.copyOf(pageList);

            Map<Integer, List<TextPosition>> glyphs = new LinkedHashMap<>();
            for (int p : pageList) glyphs.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
            // cropByPage is page GEOMETRY, not content: the region-given modes need a crop box for
            // every page a ground-truth region names, whether or not this scope processed it. A
            // region on an unprocessed page therefore still counts as a MISS (no glyphs -> no
            // candidates), which is the honest accounting, rather than being silently excluded.
            Map<Integer, PDRectangle> cropByPage = new LinkedHashMap<>();
            for (int p = 1; p <= pages; p++) cropByPage.put(p, doc.getPage(p - 1).getCropBox());

            // ---- tagged + lattice: production TableExtractor.extract, untouched ----
            List<TableExtractor.TableHit> taggedLattice = new ArrayList<>();
            try {
                taggedLattice.addAll(TableExtractor.extract(doc, pageList, glyphs).tables);
            } catch (Throwable t) {
                d.error = "extract: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            d.taggedCount = (int) taggedLattice.stream()
                    .filter(h -> "tagged".equals(h.extractionMethod)).count();
            d.latticeCount = (int) taggedLattice.stream()
                    .filter(h -> "lattice".equals(h.extractionMethod)).count();
            d.bucket = d.taggedCount > 0 && d.latticeCount > 0 ? "both"
                    : d.taggedCount > 0 ? "tagged" : d.latticeCount > 0 ? "lattice" : "neither";
            d.nativeBucket = d.bucket;

            // ---- stream, per finder ----
            // Honours production's DOCUMENT-LEVEL stream budget (TableExtractor#MAX_STREAM_PAGES_PER_DOC)
            // and its per-page glyph cap, charged the way TableExtractor#extractStreamPage charges
            // them: a page with no glyphs, or one refused by the glyph cap, does no detection work and
            // is therefore not charged. Without this the harness would run the whitespace detector on
            // pages production never reaches on a long document -- another off-distribution feed.
            Map<String, List<TableExtractor.TableHit>> streamByFinder = new LinkedHashMap<>();
            for (GutterFinder f : finders) {
                List<TableExtractor.TableHit> hits = new ArrayList<>();
                int streamPagesRun = 0;
                try {
                    for (int p : pageList) {
                        List<TextPosition> pg = glyphs.get(p);
                        if (pg == null || pg.isEmpty()) continue;
                        if (pg.size() > StreamTableExtractor.MAX_STREAM_GLYPHS) continue;
                        if (streamPagesRun >= TableExtractor.MAX_STREAM_PAGES_PER_DOC) break;
                        streamPagesRun++;
                        hits.addAll(StreamTableExtractor.extractPage(p, pg, f));
                    }
                } catch (Throwable t) {
                    d.error = (d.error == null ? "" : d.error + "; ")
                            + "stream/" + f.name() + ": " + t.getClass().getSimpleName();
                }
                streamByFinder.put(f.name(), hits);
            }
            List<TableExtractor.TableHit> streamDefault = streamByFinder.get("breuel");
            d.rawStreamHits = streamDefault.size();

            List<TableExtractor.TableHit> keptStream = new ArrayList<>();
            for (TableExtractor.TableHit s : streamDefault) {
                if (!MetricFixHarness.overlapsSubstantially(s, taggedLattice)) keptStream.add(s);
            }
            d.keptStreamHits = keptStream.size();

            List<TableExtractor.TableHit> full = new ArrayList<>(taggedLattice);
            full.addAll(keptStream);

            // ---- the SAME three paths, merged by production per-region arbitration instead ----
            // TableExtractor.arbitrate is a pure function of extraction-time signals (grid occupancy,
            // row/column counts, the stream path's gridness confidence). It sees no ground truth.
            // A RulingOverflowException from its work budget cannot happen on this corpus (measured
            // by ArbRuleHarness: the densest page carries 9 ruling and 4 stream candidates, against
            // per-page caps of 50 and 20 and a budget with ~8900 saturated pages of headroom), but is
            // handled the conservative way -- fall back to the positional merge -- rather than
            // losing the document.
            List<TableExtractor.TableHit> fullArb;
            try {
                fullArb = TableExtractor.arbitrate(taggedLattice, streamDefault);
            } catch (TableExtractor.RulingOverflowException e) {
                fullArb = full;
                d.error = (d.error == null ? "" : d.error + "; ") + "arbitrate: work budget";
            }
            // Production COMPOSES MAX_TABLES_PER_PAGE over the merged list (TableExtractor#extract ->
            // #capTablesPerPage) after arbitration. A no-op on this corpus -- the densest page carries
            // 9 ruling + 4 stream candidates against a ceiling of 50 -- but the harness must not be
            // able to emit a candidate list production would have refused.
            fullArb = TableExtractor.capTablesPerPage(fullArb, new TableExtractor.Result());

            // ---- per-finder region RERUN, cached per RAW ground-truth table index ----
            Map<String, Map<Integer, List<TableExtractor.TableHit>>> rerunByFinder =
                    new LinkedHashMap<>();
            for (GutterFinder f : finders) {
                Map<Integer, List<TableExtractor.TableHit>> byTable = new LinkedHashMap<>();
                for (int i = 0; i < raw.size(); i++) {
                    List<MetricFixHarness.Region> regions =
                            MetricFixHarness.regionsOf(raw.get(i), cropByPage);
                    if (regions.isEmpty()) continue;
                    List<TableExtractor.TableHit> hits = new ArrayList<>();
                    for (MetricFixHarness.Region reg : regions) {
                        List<TextPosition> page = glyphs.get(reg.page());
                        if (page == null) continue;
                        List<TextPosition> inRegion = new ArrayList<>();
                        for (TextPosition tp : page) {
                            float cx = tp.getXDirAdj() + tp.getWidthDirAdj() / 2f;
                            float cy = tp.getYDirAdj() + Math.max(1f, tp.getHeightDir()) / 2f;
                            if (reg.containsCentre(cx, cy)) inRegion.add(tp);
                        }
                        if (inRegion.isEmpty()) continue;
                        try {
                            hits.addAll(StreamTableExtractor.extractPage(reg.page(), inRegion, f));
                        } catch (Throwable ignored) {
                            // extractPage's contract is that it never throws; if it ever does, this
                            // region simply produced nothing rather than losing the document.
                        }
                    }
                    byTable.put(i, hits);
                }
                rerunByFinder.put(f.name(), byTable);
            }

            // ---- score every configuration x mode x protocol x ground-truth view ----
            record Cfg(String name, List<TableExtractor.TableHit> hits, String finder) {}
            List<Cfg> cfgs = new ArrayList<>();
            cfgs.add(new Cfg(C_FULL, full, null));
            cfgs.add(new Cfg(C_FULL_ARB, fullArb, null));
            cfgs.add(new Cfg(C_LT, taggedLattice, null));
            cfgs.add(new Cfg(C_STREAM, streamDefault, "breuel"));
            for (GutterFinder f : finders) {
                cfgs.add(new Cfg(C_STREAM + ":" + f.name(), streamByFinder.get(f.name()), f.name()));
            }

            for (Cfg cfg : cfgs) {
                d.hitCounts.put(cfg.name(), cfg.hits().size());
                List<Cand> cands = Cand.of(cfg.hits());
                d.audits.put(cfg.name(), auditPairing(cands, keptTables));
                for (GtView gt : gtViews) {
                    d.tallies.put(key(cfg.name(), M_E2E, P_PAIR, gt.name()),
                            e2ePaired(cands, gt.tables()));
                    d.tallies.put(key(cfg.name(), M_E2E, P_PAIR_OFF, gt.name()),
                            e2ePairedOfficial(cands, gt.tables(), false));
                    d.tallies.put(key(cfg.name(), M_E2E, P_POOL, gt.name()),
                            e2ePooled(cands, gt.tables()));
                    d.tallies.put(key(cfg.name(), M_E2E, P_LENIENT, gt.name()),
                            e2ePairedOfficial(cands, gt.tables(), true));
                    d.tallies.put(key(cfg.name(), M_REGION, P_PAIR, gt.name()),
                            regionPaired(cfg.hits(), gt, cropByPage));
                    d.tallies.put(key(cfg.name(), M_REGION, P_POOL, gt.name()),
                            regionPooled(cfg.hits(), gt, cropByPage));
                    if (cfg.finder() != null) {
                        Map<Integer, List<TableExtractor.TableHit>> rerun =
                                rerunByFinder.get(cfg.finder());
                        d.tallies.put(key(cfg.name(), M_RERUN, P_PAIR, gt.name()),
                                rerunPaired(gt, raw, rerun));
                        d.tallies.put(key(cfg.name(), M_RERUN, P_POOL, gt.name()),
                                rerunPooled(gt, raw, rerun));
                    }
                }
            }
        } catch (Throwable t) {
            d.error = (d.error == null ? "" : d.error + "; ")
                    + "load: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            d.bucket = d.bucket == null ? "neither" : d.bucket;
        }
        return d;
    }

    // -------------------------------------------------------------------------------------- test

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("baseline"), "set -Dbaseline=true to run");

        List<GutterFinder> finders = List.of(
                new BreuelGutterFinder(), new GapVotingGutterFinder(),
                new AlignmentEdgeGutterFinder(), new OccupancyProjectionGutterFinder());

        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        List<BakeOffHarness.ScoreUnit> units = corpus.units;

        List<DocResult> docs = new ArrayList<>();
        for (BakeOffHarness.ScoreUnit u : units) docs.add(measure(u, finders, S_ALL));

        // SHIPPING CONFIG: the same corpus, the same protocols, but only the pages the CLI's default
        // --pages selection processes. Measured as a SECOND full pass rather than derived from the
        // all-pages pass, because a page selection changes what every path sees, not just which hits
        // are kept.
        List<DocResult> shipDocs = new ArrayList<>();
        for (BakeOffHarness.ScoreUnit u : units) shipDocs.add(measure(u, finders, S_SHIP));
        // Subset MEMBERSHIP is pinned to the all-pages classification so that "borderless" names the
        // same documents in both scopes and the all-pages/shipping delta is attributable to the page
        // selection alone. Each shipping document's own classification is kept in nativeBucket and
        // reported by #printPageScope.
        for (int i = 0; i < shipDocs.size(); i++) shipDocs.get(i).bucket = docs.get(i).bucket;
        Map<String, List<DocResult>> byScope = new LinkedHashMap<>();
        byScope.put(S_ALL, docs);
        byScope.put(S_SHIP, shipDocs);

        // Stream-path timing, measured exactly the way BakeOffHarness measures it (whole PDF,
        // including load and glyph harvest) so the p50/p95 stay comparable to that report's.
        List<Double> streamMs = new ArrayList<>();
        GutterFinder breuel = finders.get(0);
        for (int i = 0; i < units.size(); i++) {
            BakeOffHarness.RunResult r = BakeOffHarness.runFinderOnPdf(breuel, units.get(i).pdf());
            double ms = r.elapsedNanos() / 1_000_000.0;
            docs.get(i).streamMs = ms;
            streamMs.add(ms);
        }

        rule();
        line("CONSOLIDATED REFERENCE BASELINE -- pdf-titan-arum table extraction");
        rule();
        line("Corpus: %d PDFs (%d ICDAR + %d CSV-matched, %d overlap dropped)",
                units.size(), corpus.icdarCount, corpus.csvCount, corpus.overlapCount);
        line("Metric: ICDAR 2013 adjacency relations, OFFICIAL definition (cell identity with spans,");
        line("        parallel-link dedup, blank count NOT in identity), MULTISET comparison.");
        line("        Relation definition and adjacency matching are UNCHANGED by this run.");
        line("PRIMARY protocol = POOLED + dedup. Every row states its own protocol and GT view.");
        line("PAGE SCOPE is reported TWICE for every configuration: all-pages (what every figure this");
        line("        project published used) and shipping-dflt (what `--pages default` actually");
        line("        processes). Both are labelled on every row; neither is a substitute for the other.");
        printGlyphSourceNote();
        printErrors(docs);
        printErrors(shipDocs);
        printDedupAudit(docs);
        printGtInventory(docs);
        printGtScopeControl(docs, shipDocs);
        printPageScope(docs, shipDocs);
        printClassification(docs);
        printHeadline(byScope);
        printOfficialProtocolFinding();
        printPairingDegeneracy(docs);
        printPairingFixEffect(byScope);
        printPublishedComparable(byScope);
        printOfficialLeniency(byScope);
        printPairingControls(units);
        printShippingDelta(byScope);
        printFullTable(byScope);
        printDeltas(docs);
        printPoolingMechanism(docs);
        printProtocolSelfCheck(docs);
        printProtocolSelfCheck(shipDocs);
        printRegressions(docs);
        printProseAndTiming(finders, streamMs);
        printProtocolArgument();

        String path = System.getProperty("baselineOut", "target/baseline-report.md");
        Path outPath = Path.of(path).toAbsolutePath().normalize();
        Files.createDirectories(outPath.getParent());
        Files.writeString(outPath, "```\n" + out + "```\n", StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("Report also written to " + outPath);

        writePerDocTsv(byScope);
    }

    /**
     * OPT-IN PER-DOCUMENT DUMP, {@code -DperdocOut=<path>}. Writes one TSV row per
     * (document, scope, config, mode, protocol, ground-truth view) with that document's raw
     * (matched, detected, gt) tally and the per-document F1 the MACRO average is the mean of.
     *
     * <p>Purely additive and off by default: it prints nothing, asserts nothing, and reads only the
     * tallies the report itself already aggregated, so no row of the report can change because this
     * exists. It is here because MACRO deltas restricted to a SUBSET of documents (e.g. the 67 whose
     * ground truth carries cell geometry) and grouped leave-one-out validation over publication
     * groups cannot be derived from the aggregated report -- both need the per-document terms.
     */
    private static void writePerDocTsv(Map<String, List<DocResult>> byScope) throws Exception {
        String p = System.getProperty("perdocOut");
        if (p == null || p.isBlank()) return;
        StringBuilder tsv = new StringBuilder("doc\tscope\tconfig\tmode\tprotocol\tgt\t"
                + "matched\tdetected\tgtTotal\tf1\tgtTablesDedup\tgtTablesRaw\n");
        for (Map.Entry<String, List<DocResult>> e : byScope.entrySet()) {
            for (DocResult d : e.getValue()) {
                for (Map.Entry<String, Tally> t : d.tallies.entrySet()) {
                    String[] k = t.getKey().split(" \\| ");
                    Tally v = t.getValue();
                    double pr = v.detected == 0 ? 0.0 : (double) v.matched / v.detected;
                    double rc = v.gt == 0 ? 0.0 : (double) v.matched / v.gt;
                    double f1 = v.matched == 0 ? 0.0 : 2 * pr * rc / (pr + rc);
                    tsv.append(d.id).append('\t').append(e.getKey()).append('\t')
                            .append(k[0]).append('\t').append(k[1]).append('\t')
                            .append(k[2]).append('\t').append(k[3]).append('\t')
                            .append(v.matched).append('\t').append(v.detected).append('\t')
                            .append(v.gt).append('\t')
                            .append(String.format(Locale.ROOT, "%.6f", f1)).append('\t')
                            .append(d.gtTablesDedup).append('\t').append(d.gtTablesRaw).append('\n');
                }
            }
        }
        Path out = Path.of(p).toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, tsv.toString(), StandardCharsets.UTF_8);
        System.out.println("Per-document tallies written to " + out);
    }

    // ---------------------------------------------------------------------------------- reporting

    private void printErrors(List<DocResult> docs) {
        List<DocResult> bad = docs.stream().filter(d -> d.error != null).toList();
        String scope = docs.isEmpty() ? "?" : docs.get(0).scope;
        line("");
        line("Documents with a measurement error (page scope %s): %d", scope, bad.size());
        for (DocResult d : bad) line("  %s: %s", d.id, d.error);
    }

    /**
     * THE GLYPH SOURCE. Stated in the report itself, because the number every reader takes away
     * depends on it and it was WRONG in every earlier report.
     */
    private void printGlyphSourceNote() {
        line("");
        rule();
        line("GLYPH SOURCE -- now identical to the shipping pipeline's (was not, before this run)");
        rule();
        line("  TableTestPdfs.harvestGlyphs feeds every configuration below. It now reproduces");
        line("  PdfTitanArumApp#stripTextPerPage exactly: PDFTextStripper with setSortByPosition(true),");
        line("  glyphs with null/empty getUnicode() DROPPED, one entry per non-empty-unicode glyph");
        line("  (production's per-character index collapsed by dedupeConsecutiveTextPositionRefs).");
        line("  Previously it used DEFAULT (content-stream) ordering and kept empty-unicode glyphs, so");
        line("  the extractor was scored on a glyph sequence production never produces. Parity is");
        line("  pinned by HarvestGlyphsProductionParityTest, which builds the production side by");
        line("  REFLECTING into PositionAwareTextStripper rather than re-implementing it.");
        line("  This is an INSTRUMENT correction. No src/main file changed and no threshold was");
        line("  re-tuned, so any movement below is the previously published figures being restated,");
        line("  not extraction getting better or worse.");
    }

    /**
     * CONTROL. Ground truth comes from the ICDAR XML / tabula CSV -- never from glyphs and never from
     * a page selection -- so its table and relation counts MUST be bit-identical across page scopes.
     * If they are not, the glyph/page-scope work leaked into the metric's definition and every number
     * in this report is void.
     */
    private void printGtScopeControl(List<DocResult> all, List<DocResult> ship) {
        line("");
        rule();
        line("CONTROL -- GROUND TRUTH IS INDEPENDENT OF GLYPHS AND OF PAGE SCOPE");
        rule();
        int aT = all.stream().mapToInt(d -> d.gtTablesDedup).sum();
        int sT = ship.stream().mapToInt(d -> d.gtTablesDedup).sum();
        int aR = all.stream().mapToInt(d -> d.gtRelDedup).sum();
        int sR = ship.stream().mapToInt(d -> d.gtRelDedup).sum();
        int aRawT = all.stream().mapToInt(d -> d.gtTablesRaw).sum();
        int sRawT = ship.stream().mapToInt(d -> d.gtTablesRaw).sum();
        int aRawR = all.stream().mapToInt(d -> d.gtRelRaw).sum();
        int sRawR = ship.stream().mapToInt(d -> d.gtRelRaw).sum();
        line("  all-77 dedup   : tables %d vs %d, relations %d vs %d   %s",
                aT, sT, aR, sR, aT == sT && aR == sR ? "IDENTICAL (required)" : "*** BUG ***");
        line("  all-77 raw     : tables %d vs %d, relations %d vs %d   %s",
                aRawT, sRawT, aRawR, sRawR,
                aRawT == sRawT && aRawR == sRawR ? "IDENTICAL (required)" : "*** BUG ***");
        int icdarT = all.stream().filter(d -> !d.source.equals("csv"))
                .mapToInt(d -> d.gtTablesDedup).sum();
        int icdarR = all.stream().filter(d -> !d.source.equals("csv"))
                .mapToInt(d -> d.gtRelDedup).sum();
        // PIN: 156 tables / 25,320 relations. This is no longer merely "our number" -- it is the
        // number the OFFICIAL evaluator itself prints on this corpus. The reference run logged in
        // mnamysl/tabrec-sncs (results/icdar2013/eval_ours_ctn_thresh=0.85.log) emits a
        // `GT size: n` per table; summing those gives 26,036 over all 163 annotated tables and
        // 25,320 once the four *b-str.xml duplicate-annotation files (716 relations) are removed,
        // and an independent Python port of the algorithm reports 25,320 too. Every one of the 67
        // ICDAR documents now agrees with the official tool's per-document total EXACTLY (the last
        // disagreement was us-019, 3 relations, fixed in GroundTruth#scanCellsInto -- see its
        // comment on negative start-row plus region row-increment).
        line("  ICDAR subset, de-duplicated: %d tables / %d relations  %s",
                icdarT, icdarR, icdarT == 156 && icdarR == 25320
                        ? "== the pinned reference (156 / 25,320 -- the official tool's own total)"
                        : "*** MOVED from the pinned 156 / 25,320 -- the relation definition or GT "
                          + "loading changed and this report is NOT comparable ***");
    }

    /**
     * PAGE-SELECTION INVENTORY. How much of the corpus the shipping default never looks at, and
     * whether the shipping selection re-classifies any document's extraction path.
     */
    private void printPageScope(List<DocResult> all, List<DocResult> ship) {
        line("");
        rule();
        line("PAGE-SELECTION INVENTORY -- all-pages vs the shipping `--pages default`");
        rule();
        line("Shipping selection = first min(4, pageCount) pages plus the last, blank pages replaced by");
        line("the next non-blank ones. Taken from production's OWN computePagesToProcess /");
        line("classifyBlankPages / fillBlankPages by reflection, not re-derived here.");
        line("");
        int over = 0, totalPages = 0, shipPages = 0, maxPages = 0, blankDocs = 0, reclassified = 0;
        List<String> detail = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            DocResult a = all.get(i), s = ship.get(i);
            totalPages += a.pageCount;
            shipPages += s.selectedPages.size();
            maxPages = Math.max(maxPages, a.pageCount);
            if (s.blankPages > 0) blankDocs++;
            if (a.pageCount > s.selectedPages.size()) {
                over++;
                detail.add(String.format(Locale.ROOT,
                        "  %-46s pages=%-4d shipping=%-24s gtTables=%d",
                        tail(s.id, 46), a.pageCount, s.selectedPages, s.gtTablesDedup));
            }
            if (!java.util.Objects.equals(a.nativeBucket, s.nativeBucket)) {
                reclassified++;
                detail.add(String.format(Locale.ROOT,
                        "  %-46s RECLASSIFIED by page scope: %s (all) -> %s (shipping)",
                        tail(s.id, 46), a.nativeBucket, s.nativeBucket));
            }
        }
        line("  documents whose shipping selection is SMALLER than the document : %d of %d",
                over, all.size());
        line("  corpus pages, all-pages scope                                   : %d (max %d in one doc)",
                totalPages, maxPages);
        line("  corpus pages, shipping scope                                    : %d (%.1f%% of the corpus)",
                shipPages, totalPages == 0 ? 0.0 : 100.0 * shipPages / totalPages);
        line("  documents with >=1 page classified blank by production           : %d", blankDocs);
        line("  documents whose tagged/lattice classification CHANGES           : %d", reclassified);
        line("  MAX_STREAM_PAGES_PER_DOC = %d; longest corpus document = %d pages, so the "
                        + "document-level stream budget %s on this corpus.",
                TableExtractor.MAX_STREAM_PAGES_PER_DOC, maxPages,
                maxPages > TableExtractor.MAX_STREAM_PAGES_PER_DOC ? "*** BINDS ***" : "never binds");
        line("");
        for (String s : detail) line(s);
    }

    private void printDedupAudit(List<DocResult> docs) {
        line("");
        rule();
        line("ARTIFACT 2 -- DUPLICATE GROUND-TRUTH ANNOTATIONS REMOVED (full audit list)");
        rule();
        line("Test: same declared page(s) AND union-of-cell-bbox IoU >= %.2f, in the ICDAR file's own",
                GtDedup.DEFAULT_IOU_THRESHOLD);
        line("coordinate space. First occurrence in list order is kept (= the 'a' annotation).");
        line("relJaccard is corroborating evidence only, never the test.");
        line("");
        int tables = 0, relations = 0, affectedDocs = 0;
        for (DocResult d : docs) {
            if (d.removedDuplicates.isEmpty()) continue;
            affectedDocs++;
            line("  %s", d.id);
            for (GtDedup.Duplicate dup : d.removedDuplicates) {
                line("      %s", dup.describe());
                tables++;
                relations += dup.relations();
            }
        }
        int gtRaw = docs.stream().mapToInt(d -> d.gtTablesRaw).sum();
        int gtDedup = docs.stream().mapToInt(d -> d.gtTablesDedup).sum();
        line("");
        line("  REMOVED: %d ground-truth tables carrying %d relations, across %d documents.",
                tables, relations, affectedDocs);
        line("  Expected-table count: %d (raw) -> %d (dedup).", gtRaw, gtDedup);
        line("  Cross-check: the four *b-str.xml duplicate-annotation files contribute exactly 7");
        line("  tables / 716 relations (see MetricFixHarness#printPortReconciliation), and removing");
        line("  them lands the ICDAR inventory on the independent evaluator port's 156 tables /");
        line("  25,320 relations EXACTLY -- and on the official evaluator's own printed total, which");
        line("  is 26,036 over all 163 annotated tables and 25,320 with those four files removed.");
    }

    private void printGtInventory(List<DocResult> docs) {
        line("");
        rule();
        line("GROUND-TRUTH RELATION INVENTORY, raw vs de-duplicated");
        rule();
        for (String scope : List.of("icdar", "all")) {
            List<DocResult> sel = scope.equals("icdar")
                    ? docs.stream().filter(d -> !d.source.equals("csv")).toList() : docs;
            line("  scope=%-6s docs=%-3d  tables %d -> %d   relations %d -> %d  (removed %d)",
                    scope, sel.size(),
                    sel.stream().mapToInt(d -> d.gtTablesRaw).sum(),
                    sel.stream().mapToInt(d -> d.gtTablesDedup).sum(),
                    sel.stream().mapToInt(d -> d.gtRelRaw).sum(),
                    sel.stream().mapToInt(d -> d.gtRelDedup).sum(),
                    sel.stream().mapToInt(d -> d.gtRelRaw - d.gtRelDedup).sum());
        }
    }

    private void printClassification(List<DocResult> docs) {
        line("");
        rule();
        line("CORPUS CLASSIFICATION by TableExtractor.extract (tagged+lattice), ALL-PAGES scope");
        rule();
        Map<String, Integer> buckets = new LinkedHashMap<>();
        for (String b : List.of("lattice", "tagged", "both", "neither")) buckets.put(b, 0);
        for (DocResult d : docs) buckets.merge(d.bucket, 1, Integer::sum);
        line("  %s  TOTAL=%d", buckets, docs.size());
        Map<String, Map<String, Integer>> bySource = new TreeMap<>();
        for (DocResult d : docs) {
            bySource.computeIfAbsent(d.source, k -> new LinkedHashMap<>()).merge(d.bucket, 1, Integer::sum);
        }
        for (Map.Entry<String, Map<String, Integer>> e : bySource.entrySet()) {
            line("  %-9s %s", e.getKey(), e.getValue());
        }
        line("  borderless subset (bucket=neither): %d PDFs",
                docs.stream().filter(d -> "neither".equals(d.bucket)).count());
    }

    // ---- subsets ----

    /**
     * The configurations the tables below enumerate. {@code stream} IS the default (Breuel) finder, so
     * the redundant {@code stream:breuel} block is left out of the tables -- it is still measured, and
     * {@link #printProtocolSelfCheck} asserts the two are identical, which is the only reason to keep
     * computing it.
     */
    private static List<String> reportConfigs() {
        List<String> out = new ArrayList<>(List.of(C_FULL, C_FULL_ARB, C_LT, C_STREAM));
        for (String f : FINDER_NAMES) {
            if (!f.equals("breuel")) out.add(C_STREAM + ":" + f);
        }
        return out;
    }

    private record Subset(String name, List<DocResult> docs) {}

    private static List<Subset> subsets(List<DocResult> docs) {
        return List.of(
                new Subset("ALL-77", docs),
                new Subset("borderless", docs.stream().filter(d -> "neither".equals(d.bucket)).toList()),
                new Subset("icdar-US", docs.stream().filter(d -> "icdar-us".equals(d.source)).toList()),
                new Subset("icdar-EU", docs.stream().filter(d -> "icdar-eu".equals(d.source)).toList()));
    }

    /** The same subset rule applied to another page scope's document list. Because
     *  {@code DocResult#bucket} is pinned to the all-pages classification for both scopes (see
     *  {@link #run}), a subset names the SAME documents in every scope. */
    private static List<DocResult> subsetOf(List<DocResult> docs, String name) {
        for (Subset s : subsets(docs)) if (s.name().equals(name)) return s.docs();
        throw new IllegalArgumentException("unknown subset: " + name);
    }

    /** The numbers the project should quote: PRIMARY protocol only, one line per configuration. */
    private void printHeadline(Map<String, List<DocResult>> byScope) {
        line("");
        rule();
        line("HEADLINE -- PRIMARY PROTOCOL ONLY, DE-DUPLICATED GT, MACRO FIRST");
        rule();
        line("Primary protocol is POOLED end-to-end and PER-REGION (1:1) in region-given mode -- see");
        line("#primaryProtocol for why, and the FULL BASELINE table below for the other three");
        line("combinations of every row. The protocol column states it on every line.");
        line("EVERY configuration appears TWICE: once per page scope. all-pages is the scope every");
        line("published figure used; shipping-dflt is what a user with no --pages flag gets.");
        line("");
        line("  %-26s %-13s %-18s %-11s %-7s %8s %8s %8s %8s %5s",
                "config", "page scope", "mode", "subset", "protocol",
                "MACRO", "microP", "microR", "microF1", "docs");
        for (Subset s : subsets(byScope.get(S_ALL))) {
            for (String config : List.of(C_FULL, C_FULL_ARB, C_LT, C_STREAM)) {
                for (String mode : List.of(M_E2E, M_REGION, M_RERUN)) {
                    if (mode.equals(M_RERUN) && !isStreamConfig(config)) continue;
                    String protocol = primaryProtocol(mode);
                    for (String scope : SCOPES) {
                        List<DocResult> sel = subsetOf(byScope.get(scope), s.name());
                        Acc a = aggregate(sel, key(config, mode, protocol, G_DEDUP));
                        if (a.docs == 0) continue;
                        line("  %-26s %-13s %-18s %-11s %-7s %8.4f %8.4f %8.4f %8.4f %5d",
                                trim(config, 26), scope, mode, s.name(), protocol,
                                a.macroF1(), a.microP(), a.microR(), a.microF1(), a.docs);
                    }
                }
            }
        }
        line("");
        line("  Published end-to-end calibration (all MACRO): KYTHE 0.5220, pdf2table 0.5850,");
        line("  TABFIND 0.6962, Nitro 0.7535, Acrobat 0.7685, TEXUS 0.8259, Nurminen 0.8374 (best");
        line("  pure heuristic), OmniPage 0.8420, FineReader 0.8772, GTE 0.9350.");
        line("  Published region-given: Nurminen 0.9460, GTE 0.9624.");
    }

    /**
     * SHIPPING-CONFIG DELTA. What the default page selection costs, per configuration, at the primary
     * protocol. This is the single number a user is owed: they run the CLI with no {@code --pages}
     * flag and get the shipping column, not the all-pages column every published figure quoted.
     */
    private void printShippingDelta(Map<String, List<DocResult>> byScope) {
        line("");
        rule();
        line("WHAT THE SHIPPING PAGE SELECTION COSTS (PRIMARY protocol, de-duplicated GT)");
        rule();
        line("dScope = shipping-dflt minus all-pages. NEGATIVE is expected and is not a regression in");
        line("extraction: ground-truth tables on pages `--pages default` never opens cannot be found.");
        line("It is the price of the default, and it belongs next to every headline figure.");
        line("");
        line("  %-11s %-26s %-18s %9s %9s %9s %9s %9s",
                "subset", "config", "mode", "ALL MACRO", "SHIP MAC", "dMACRO", "ALL mic", "dmicro");
        for (Subset s : subsets(byScope.get(S_ALL))) {
            for (String config : reportConfigs()) {
                for (String mode : List.of(M_E2E, M_REGION, M_RERUN)) {
                    if (mode.equals(M_RERUN) && !isStreamConfig(config)) continue;
                    String protocol = primaryProtocol(mode);
                    Acc a = aggregate(s.docs(), key(config, mode, protocol, G_DEDUP));
                    Acc b = aggregate(subsetOf(byScope.get(S_SHIP), s.name()),
                            key(config, mode, protocol, G_DEDUP));
                    if (a.docs == 0 || b.docs == 0) continue;
                    line("  %-11s %-26s %-18s %9.4f %9.4f %+9.4f %9.4f %+9.4f",
                            s.name(), trim(config, 26), mode,
                            a.macroF1(), b.macroF1(), b.macroF1() - a.macroF1(),
                            a.microF1(), b.microF1() - a.microF1());
                }
            }
        }
    }

    /** Every configuration under all four (protocol, GT) combinations, in both page scopes. */
    private void printFullTable(Map<String, List<DocResult>> byScope) {
        line("");
        rule();
        line("FULL BASELINE -- every configuration x PAGE SCOPE x mode x PROTOCOL x GROUND-TRUTH VIEW");
        rule();
        line("Row order within a block is fixed: POOLED/dedup (PRIMARY), POOLED/raw, 1:1/dedup,");
        line("1:1/raw (= the pre-correction baseline, reproduces MetricFixHarness exactly).");
        line("The all-pages rows are the ones comparable to every previously published figure; the");
        line("shipping-dflt rows are the ones a user actually gets.");

        for (Subset s : subsets(byScope.get(S_ALL))) {
            List<String> configs = s.name().equals("ALL-77") || s.name().equals("borderless")
                    ? reportConfigs() : List.of(C_FULL, C_FULL_ARB, C_LT, C_STREAM);
            line("");
            line("--- subset %s (n=%d PDFs) ---", s.name(), s.docs().size());
            line("  %-26s %-13s %-18s %-7s %-5s %8s %8s %8s %8s %5s %5s %5s",
                    "config", "page scope", "mode", "protocol", "GT",
                    "MACRO", "microP", "microR", "microF1", "docs", "cov", "tbl");
            for (String config : configs) {
                for (String mode : List.of(M_E2E, M_REGION, M_RERUN)) {
                    if (mode.equals(M_RERUN) && !isStreamConfig(config)) continue;
                    for (String[] pg : PROTOCOL_ORDER) {
                        for (String scope : SCOPES) {
                            Acc a = aggregate(subsetOf(byScope.get(scope), s.name()),
                                    key(config, mode, pg[0], pg[1]));
                            if (a.docs == 0) continue;
                            line("  %-26s %-13s %-18s %-7s %-5s %8.4f %8.4f %8.4f %8.4f %5d %5d %5d",
                                    trim(config, 26), scope, mode, pg[0], pg[1],
                                    a.macroF1(), a.microP(), a.microR(), a.microF1(),
                                    a.docs, a.covered, a.scoredTables);
                        }
                    }
                }
            }
        }
    }

    /** What each correction is worth, per configuration, so neither is taken on faith. */
    private void printDeltas(List<DocResult> docs) {
        line("");
        rule();
        line("WHAT EACH PROTOCOL CORRECTION IS WORTH (ALL-77 and borderless, ALL-PAGES scope)");
        rule();
        line("  dPool  = POOLED minus 1:1, at fixed de-duplicated GT.");
        line("  dDedup = dedup minus raw, at fixed POOLED protocol.");
        line("  dBoth  = PRIMARY (POOLED+dedup) minus pre-correction (1:1+raw).");
        line("");
        line("  %-11s %-26s %-18s %16s %16s %16s",
                "subset", "config", "mode", "dPool (MACRO/mic)", "dDedup(MACRO/mic)",
                "dBoth (MACRO/mic)");
        for (Subset s : subsets(docs)) {
            if (!s.name().equals("ALL-77") && !s.name().equals("borderless")) continue;
            for (String config : reportConfigs()) {
                for (String mode : List.of(M_E2E, M_REGION, M_RERUN)) {
                    if (mode.equals(M_RERUN) && !isStreamConfig(config)) continue;
                    Acc pd = aggregate(s.docs(), key(config, mode, P_POOL, G_DEDUP));
                    Acc pr = aggregate(s.docs(), key(config, mode, P_POOL, G_RAW));
                    Acc ad = aggregate(s.docs(), key(config, mode, P_PAIR, G_DEDUP));
                    Acc ar = aggregate(s.docs(), key(config, mode, P_PAIR, G_RAW));
                    if (pd.docs == 0) continue;
                    line("  %-11s %-26s %-18s %+7.4f/%+7.4f %+7.4f/%+7.4f %+7.4f/%+7.4f",
                            s.name(), trim(config, 26), mode,
                            pd.macroF1() - ad.macroF1(), pd.microF1() - ad.microF1(),
                            pd.macroF1() - pr.macroF1(), pd.microF1() - pr.microF1(),
                            pd.macroF1() - ar.macroF1(), pd.microF1() - ar.microF1());
                }
            }
        }
    }

    /**
     * Honesty check: every place a "correction" makes a configuration look WORSE by more than
     * {@link #MATERIALITY}. Sub-threshold moves are counted, not listed -- at 4 decimal places a
     * handful of relations moving between documents produces -0.0001 macro noise in dozens of rows,
     * and listing those would bury the two effects that are real.
     */
    private void printRegressions(List<DocResult> docs) {
        line("");
        rule();
        line("HONESTY CHECK -- where a PROTOCOL correction LOWERS the score (|delta| > %.3f, all-pages)",
                MATERIALITY);
        rule();
        int[] counts = new int[]{0, 0};   // {material, noise}
        for (Subset s : subsets(docs)) {
            for (String config : reportConfigs()) {
                for (String mode : List.of(M_E2E, M_REGION, M_RERUN)) {
                    if (mode.equals(M_RERUN) && !isStreamConfig(config)) continue;
                    Acc pd = aggregate(s.docs(), key(config, mode, P_POOL, G_DEDUP));
                    Acc pr = aggregate(s.docs(), key(config, mode, P_POOL, G_RAW));
                    Acc ad = aggregate(s.docs(), key(config, mode, P_PAIR, G_DEDUP));
                    Acc ar = aggregate(s.docs(), key(config, mode, P_PAIR, G_RAW));
                    if (pd.docs == 0) continue;
                    reportIfWorse(counts, s, config, mode, "POOLED vs 1:1  (dedup GT)", pd, ad);
                    reportIfWorse(counts, s, config, mode, "dedup vs raw   (POOLED)  ", pd, pr);
                    reportIfWorse(counts, s, config, mode, "dedup vs raw   (1:1)     ", ad, ar);
                    reportIfWorse(counts, s, config, mode, "PRIMARY vs pre-correction", pd, ar);
                }
            }
        }
        if (counts[0] == 0) {
            line("  No material regression: neither correction lowers MACRO or micro F1 by more than");
            line("  %.3f anywhere.", MATERIALITY);
        }
        line("  (%d further comparisons moved DOWN by less than %.3f -- rounding-scale noise.)",
                counts[1], MATERIALITY);
        line("");
        line("  Why de-duplication CAN lower a micro F1, and why that is not a defect: in region-given");
        line("  mode a duplicated ground-truth table is scored against the SAME region candidate twice,");
        line("  so it contributes matched, gt AND detected relations twice and its F1 is not penalised");
        line("  at all -- dropping one copy simply removes that table's weight from the corpus micro");
        line("  average. Where the duplicated table happened to be extracted BETTER than the corpus");
        line("  average (us-035a's 421-relation table, mostly), removing it pulls micro down. The");
        line("  duplicate's real cost is in END-TO-END mode, where it cannot pair at all; there,");
        line("  de-duplication only ever helps. A region-given row that moves down should be read as");
        line("  'de-duplication is a wash in this mode', not as 'de-duplication is wrong'.");
    }

    private void reportIfWorse(int[] counts, Subset s, String config, String mode, String what,
                                Acc newer, Acc older) {
        double dMacro = newer.macroF1() - older.macroF1();
        double dMicro = newer.microF1() - older.microF1();
        if (dMacro >= 0 && dMicro >= 0) return;
        if (dMacro > -MATERIALITY && dMicro > -MATERIALITY) {
            counts[1]++;
            return;
        }
        counts[0]++;
        line("  %-11s %-26s %-18s %s  MACRO %.4f -> %.4f (%+.4f)  micro %.4f -> %.4f (%+.4f)",
                s.name(), trim(config, 26), mode, what,
                older.macroF1(), newer.macroF1(), dMacro,
                older.microF1(), newer.microF1(), dMicro);
    }

    // ============================================================ PAIRING-PROTOCOL INVESTIGATION ==

    /**
     * WHAT THE OFFICIAL EVALUATOR ACTUALLY DOES, and which of our deviations from it this harness
     * adopts. Printed rather than left in a comment because every number below depends on it.
     */
    private void printOfficialProtocolFinding() {
        line("");
        rule();
        line("THE OFFICIAL ICDAR 2013 PROTOCOL, READ FROM ITS OWN SOURCE");
        rule();
        line("Source: github.com/tamirhassan/dataset-tools (Apache-2.0, \"as used in the ICDAR 2013");
        line("Table Competition\"), MeasureRecognitionPerformance.evaluateResultStr + Table.java, read");
        line("from a local clone. Aggregation: github.com/mnamysl/tabrec-sncs evaluation/ICDAR2013/");
        line("eval.py, the published script that drives that JAR and reproduces Namysl et al.'s table.");
        line("");
        line("  #  official rule (verbatim behaviour)                     ours before   ours now");
        line("  -  -----------------------------------------------------  ------------  --------");
        line("  1  correspondence = argmax over CORRECT ADJACENCY");
        line("     RELATIONS: `int corrDec = compareARs(gtAR, resultAR..);");
        line("     if (corrDec > highestCorr) {..matchingResult=resultAR;}` exact-cell F1  ADOPTED");
        line("  2  candidates restricted to the GT table's own PAGE:");
        line("     `if (!pageCheck || resultTable.pageNo == gtTable.pageNo)`");
        line("     with `boolean pageCheck = true` unless -nopage        no page filter  ADOPTED");
        line("  3  highestCorr starts at -1, so the FIRST candidate on");
        line("     the page pairs even at zero correct relations         same (bestF1=-1) same");
        line("  4  ties keep the FIRST candidate (`>` not `>=`)          same            same");
        line("  5  a GT table with no candidate on its page prints");
        line("     \"no matching result found\" with NO numbers, and");
        line("     eval.py sums only the `Table n:` lines -> it is");
        line("     absent from the recall denominator entirely          charged         STILL CHARGED");
        line("  6  headline MACRO = 2*mean(P)*mean(R)/(mean(P)+mean(R)),");
        line("     i.e. F1 OF THE MEANS, not the mean of per-doc F1     mean of F1      BOTH PRINTED");
        line("");
        line("  Rules 1 and 2 are adopted: they are the correspondence rule, they are what the defect");
        line("  report was about, and exact-cell F1 appears NOWHERE in the official tool -- there is no");
        line("  reading of the source under which our old criterion was the published one.");
        line("");
        line("  Rule 5 is DECLINED even though it is what the reference pipeline does, and declining it");
        line("  costs us score. Reason in #printOfficialLeniency: adopting it would make emitting");
        line("  NOTHING on a page score strictly better than emitting something wrong, so it rewards");
        line("  under-detection. Its size is measured there so the conservatism is quantified, not hidden.");
        line("");
        line("  Rule 6 is printed alongside ours, never substituted for it. F1-of-means is the LARGER");
        line("  of the two here, so quoting it would flatter us; it is nonetheless the formula the");
        line("  published column was computed with, so a comparison against Nurminen et al. must use it.");
        line("");
        line("  Verification that rule 6 is read correctly: eval.py's own logged run prints");
        line("  `Precision: 0.8714227705826723; Recall: 0.8467831413095247; F1: 0.8589262858140391`,");
        line("  and 2PR/(P+R) on those two means = 0.858926..., matching to 6 decimal places.");
        line("  Its bulk line `Num of GT: 25319; DET: 24097; CORR: 22722` reproduces its own micro");
        line("  F1 0.9196211753278292 exactly, which pins the micro formula too.");
    }

    /**
     * IS THE DEGENERACY REAL, AND HOW WIDESPREAD? Per-document audit of the CURRENT exact-cell-F1
     * correspondence rule, from {@link #auditPairing}. This is the evidence for (or against) the
     * defect report, and it is deliberately reported BEFORE any corrected score, so the claim can be
     * judged on the mechanism rather than on the number it produces.
     */
    private void printPairingDegeneracy(List<DocResult> docs) {
        line("");
        rule();
        line("DEGENERACY AUDIT OF THE CURRENT 1:1 RULE (all-pages, dedup GT, per document)");
        rule();
        line("Walks the SAME greedy loop #e2ePaired walks. At each step, over the SAME availability set");
        line("(no page filter -- this isolates the CRITERION, not the official page restriction):");
        line("  tie@0  = >=2 candidates available and EVERY one scored exact-cell F1 = 0.0, so the");
        line("           pairing was decided purely by enumeration order.");
        line("  misasg = the chosen candidate has strictly FEWER correct adjacency relations than");
        line("           another candidate still available at that step (\"better candidate left");
        line("           unpaired\"), and lostRel is how many correct relations that cost.");
        line("");
        for (String config : List.of(C_FULL_ARB, C_FULL, C_STREAM, C_LT)) {
            int degen = 0, tieDocs = 0, misDocs = 0, tie = 0, mis = 0, lost = 0, corr = 0, best = 0;
            int gtTables = 0;
            for (DocResult d : docs) {
                PairAudit a = d.audits.get(config);
                if (a == null) continue;
                gtTables += a.gtTables;
                tie += a.tieAtZeroTables;
                mis += a.misassignedTables;
                lost += a.lostRelations;
                corr += a.currentCorr;
                best += a.bestStepwiseCorr;
                if (a.tieAtZeroTables > 0) tieDocs++;
                if (a.misassignedTables > 0) misDocs++;
                if (a.degenerate()) degen++;
            }
            line("  %-26s docs with a degenerate pairing: %2d / %d", trim(config, 26), degen, docs.size());
            line("  %-26s   tie@0 : %2d docs, %3d of %3d GT tables", "", tieDocs, tie, gtTables);
            line("  %-26s   misasg: %2d docs, %3d of %3d GT tables, %d correct relations lost",
                    "", misDocs, mis, gtTables, lost);
            line("  %-26s   correct relations kept by current rule %d vs %d if each step had taken",
                    "", corr, best);
            line("  %-26s   the max-correct candidate = %.1f%% of the stepwise ceiling",
                    "", best == 0 ? 100.0 : 100.0 * corr / best);
        }
        line("");
        line("  WORST DOCUMENTS for %s (by correct relations lost to the criterion):", C_FULL_ARB);
        line("    %-26s %5s %6s %7s %8s %9s %9s",
                "document", "gtTbl", "tie@0", "misasg", "lostRel", "curCorr", "bestCorr");
        docs.stream()
                .filter(d -> d.audits.get(C_FULL_ARB) != null
                        && d.audits.get(C_FULL_ARB).degenerate())
                .sorted((x, y) -> Integer.compare(y.audits.get(C_FULL_ARB).lostRelations,
                        x.audits.get(C_FULL_ARB).lostRelations))
                .forEach(d -> {
                    PairAudit a = d.audits.get(C_FULL_ARB);
                    line("    %-26s %5d %6d %7d %8d %9d %9d", trim(shortId(d.id), 26),
                            a.gtTables, a.tieAtZeroTables, a.misassignedTables,
                            a.lostRelations, a.currentCorr, a.bestStepwiseCorr);
                });
    }

    /**
     * THE EFFECT OF THE PAIRING FIX ON EVERY CONFIGURATION, INCLUDING WHERE IT LOWERS THE SCORE.
     * {@link #P_PAIR} -> {@link #P_PAIR_OFF} at fixed page scope, mode and ground-truth view, so the
     * only thing that changed is the correspondence rule (criterion + page filter).
     */
    private void printPairingFixEffect(Map<String, List<DocResult>> byScope) {
        line("");
        rule();
        line("EFFECT OF THE PAIRING FIX -- EVERY CONFIGURATION, BOTH SCOPES, BOTH GT VIEWS");
        rule();
        line("1:1 = exact-cell-F1 correspondence (the old rule). 1:1off = the official rule.");
        line("Nothing else differs. Negative deltas are printed, not filtered.");
        line("");
        line("  %-26s %-13s %-5s %9s %9s %8s %9s %9s %8s",
                "config", "page scope", "GT", "1:1 MAC", "off MAC", "dMACRO",
                "1:1 mic", "off mic", "dmicro");
        for (String config : reportConfigs()) {
            for (String scope : SCOPES) {
                for (String gt : List.of(G_DEDUP, G_RAW)) {
                    List<DocResult> sel = byScope.get(scope);
                    Acc old = aggregate(sel, key(config, M_E2E, P_PAIR, gt));
                    Acc neo = aggregate(sel, key(config, M_E2E, P_PAIR_OFF, gt));
                    if (old.docs == 0 || neo.docs == 0) continue;
                    line("  %-26s %-13s %-5s %9.4f %9.4f %+8.4f %9.4f %9.4f %+8.4f",
                            trim(config, 26), scope, gt,
                            old.macroF1(), neo.macroF1(), neo.macroF1() - old.macroF1(),
                            old.microF1(), neo.microF1(), neo.microF1() - old.microF1());
                }
            }
        }
        line("");
        line("  REGION-GIVEN and REGION-GIVEN-RERUN modes are absent by construction, not omitted:");
        line("  being handed the region FIXES the correspondence, so there is no pairing rule left to");
        line("  change and those rows are numerically identical to the ones already reported.");
    }

    /**
     * THE ONE TABLE THAT IS COMPARABLE TO PUBLISHED WORK, with every aggregation spelled out. The
     * published ICDAR 2013 end-to-end column is a per-document average computed as F1-of-means over a
     * 1:1 per-page correspondence, so the comparable cell is (end-to-end, 1:1off, MACROoff).
     */
    private void printPublishedComparable(Map<String, List<DocResult>> byScope) {
        line("");
        rule();
        line("PUBLISHED-COMPARABLE FIGURE, ALL AGGREGATIONS SIDE BY SIDE (end-to-end, dedup GT)");
        rule();
        line("MACRO    = mean of per-document F1 (what this project has always reported).");
        line("MACROoff = 2*mean(P)*mean(R)/(mean(P)+mean(R)) -- the formula eval.py uses and therefore");
        line("           the one the published column was computed with. Larger here; reported for");
        line("           comparability, and NOT used as this project's headline.");
        line("");
        line("  %-26s %-13s %-8s %8s %9s %8s %8s %8s",
                "config", "page scope", "protocol", "MACRO", "MACROoff", "macroP", "macroR", "microF1");
        for (String config : List.of(C_FULL_ARB, C_FULL, C_LT, C_STREAM)) {
            for (String scope : SCOPES) {
                for (String proto : List.of(P_PAIR, P_PAIR_OFF, P_POOL)) {
                    Acc a = aggregate(byScope.get(scope), key(config, M_E2E, proto, G_DEDUP));
                    if (a.docs == 0) continue;
                    line("  %-26s %-13s %-8s %8.4f %9.4f %8.4f %8.4f %8.4f",
                            trim(config, 26), scope, proto, a.macroF1(), a.macroF1Official(),
                            a.macroP(), a.macroR(), a.microF1());
                }
            }
        }
        line("");
        line("  Published end-to-end (per-document averages): KYTHE 0.5220, pdf2table 0.5850,");
        line("  TABFIND 0.6962, Nitro 0.7535, Acrobat 0.7685, TEXUS 0.8259, Nurminen 0.8374,");
        line("  OmniPage 0.8420, FineReader 0.8772, GTE 0.9350. Those numbers ALSO carry the rule-5");
        line("  leniency we decline (see below), so even the MACROoff column understates the gap");
        line("  closure relative to a like-for-like reference run.");
    }

    /**
     * THE SIZE OF THE OFFICIAL LENIENCY WE DECLINE (rule 5), and why declining it is not timidity.
     *
     * <p>Under the reference pipeline a ground-truth table with no candidate on its page vanishes from
     * the recall denominator. That produces a strict perverse ordering: on a page holding one
     * annotated table, emitting NOTHING costs zero recall, while emitting one wrong table pairs (rule
     * 3 pairs the first candidate at zero correct) and charges that table's whole relation count. So
     * a detector that gives up on hard pages outscores an identical detector that guesses. A protocol
     * with that property cannot be used to rank detectors, which is what a benchmark is for.
     */
    private void printOfficialLeniency(Map<String, List<DocResult>> byScope) {
        line("");
        rule();
        line("THE OFFICIAL LENIENCY THIS HARNESS DECLINES, MEASURED (end-to-end, dedup GT)");
        rule();
        line("1:1off        = official pairing, missed GT tables STILL CHARGED (what we report).");
        line("1:1off-lenient= official pairing + the reference pipeline's free pass for a GT table with");
        line("                no candidate on its page (what eval.py over the official JAR computes).");
        line("The gap is how much of our reported number is us declining a leniency, not extraction.");
        line("");
        line("  %-26s %-13s %9s %9s %8s %9s %9s",
                "config", "page scope", "1:1off", "lenient", "dMACRO", "off micR", "len micR");
        for (String config : reportConfigs()) {
            for (String scope : SCOPES) {
                List<DocResult> sel = byScope.get(scope);
                Acc strict = aggregate(sel, key(config, M_E2E, P_PAIR_OFF, G_DEDUP));
                Acc len = aggregate(sel, key(config, M_E2E, P_LENIENT, G_DEDUP));
                if (strict.docs == 0) continue;
                line("  %-26s %-13s %9.4f %9.4f %+8.4f %9.4f %9.4f",
                        trim(config, 26), scope, strict.macroF1(), len.macroF1(),
                        len.macroF1() - strict.macroF1(), strict.microR(), len.microR());
            }
        }
        line("");
        line("  The lenient column is NOT a figure this project reports anywhere. It is here so that");
        line("  the direction and size of our remaining conservatism against the published pipeline is");
        line("  on the record: every published end-to-end number in the calibration table was produced");
        line("  by a pipeline with this leniency, so our comparable figure is understated by roughly");
        line("  the dMACRO column and we are choosing not to claim it.");
    }

    /**
     * MANDATORY ADVERSARIAL SELF-CHECK. A protocol change that raises our own score is only
     * defensible if it does NOT also raise the score of a detector that is objectively worse. So the
     * three protocols are re-run against five SYNTHETIC detectors built from the ground truth itself,
     * whose quality ordering is known a priori and independent of any metric:
     *
     * <ol>
     *   <li><b>perfect</b> -- ground truth as its own detector. Any protocol worth using must return
     *       1.0 here; anything less is the protocol failing, not the detector.</li>
     *   <li><b>drop-last-row</b> -- every table minus its final row. Strictly less content, same
     *       segmentation. Must score below perfect under every protocol.</li>
     *   <li><b>split-every-table</b> -- every table cut in half by row. Content complete, segmentation
     *       destroyed. This is the control that exposes a segmentation-blind protocol.</li>
     *   <li><b>merge-all-on-page</b> -- every table on a page stacked into one candidate. Content
     *       complete, segmentation destroyed the other way.</li>
     *   <li><b>drop-one-table</b> -- the first annotated table of each document simply not emitted.
     *       Strictly less content. The control for the leniency of rule 5.</li>
     * </ol>
     *
     * <p>The test the new protocol has to pass: it must not RE-ORDER these against the old protocol in
     * a way that promotes a worse detector, and it must not compress the perfect/degraded gap to the
     * point that degradation stops being visible.
     */
    private void printPairingControls(List<BakeOffHarness.ScoreUnit> units) {
        line("");
        rule();
        line("ADVERSARIAL CONTROL -- SYNTHETIC DETECTORS OF KNOWN QUALITY, ALL THREE PROTOCOLS");
        rule();
        line("Detectors are built from the DE-DUPLICATED ground truth of the same 77 documents and");
        line("scored against that same ground truth, so extraction is out of the picture entirely and");
        line("only the protocol is under test. A protocol that ranks a degraded detector above");
        line("`perfect`, or that cannot separate `perfect` from `split`/`merge`, is disqualified.");
        line("");
        line("  %-20s %-8s %9s %9s %9s %9s %9s",
                "detector", "protocol", "MACRO", "MACROoff", "microP", "microR", "microF1");
        for (String det : List.of("perfect", "drop-last-row", "drop-one-table",
                "split-every-table", "merge-all-on-page")) {
            for (String proto : List.of(P_PAIR, P_PAIR_OFF, P_LENIENT, P_POOL)) {
                Acc a = new Acc();
                for (BakeOffHarness.ScoreUnit u : units) {
                    List<GroundTruth.Table> exp =
                            GtDedup.dedup(u.expected()).kept();
                    List<Cand> cands = syntheticDetector(det, exp);
                    Tally t = switch (proto) {
                        case P_PAIR -> e2ePaired(cands, exp);
                        case P_PAIR_OFF -> e2ePairedOfficial(cands, exp, false);
                        case P_LENIENT -> e2ePairedOfficial(cands, exp, true);
                        default -> e2ePooled(cands, exp);
                    };
                    if (t.gt == 0 && t.detected == 0 && t.tables == 0) continue;
                    a.addDoc(t);
                }
                line("  %-20s %-8s %9.4f %9.4f %9.4f %9.4f %9.4f",
                        det, proto, a.macroF1(), a.macroF1Official(),
                        a.microP(), a.microR(), a.microF1());
            }
        }
    }

    /**
     * Builds one synthetic detector's candidate list for a document from its ground-truth tables.
     * Every candidate carries the ground truth's own page number, so the official rule's page filter
     * is exercised rather than bypassed.
     */
    private static List<Cand> syntheticDetector(String kind, List<GroundTruth.Table> expected) {
        List<Cand> out = new ArrayList<>();
        switch (kind) {
            case "perfect" -> {
                for (GroundTruth.Table t : expected) out.add(candOf(t, 0, Integer.MAX_VALUE));
            }
            case "drop-last-row" -> {
                for (GroundTruth.Table t : expected) {
                    int rows = t.rowCount();
                    out.add(candOf(t, 0, Math.max(0, rows - 2)));
                }
            }
            case "drop-one-table" -> {
                for (int i = 1; i < expected.size(); i++) {
                    out.add(candOf(expected.get(i), 0, Integer.MAX_VALUE));
                }
            }
            case "split-every-table" -> {
                for (GroundTruth.Table t : expected) {
                    int rows = t.rowCount();
                    int mid = rows / 2;
                    if (rows < 2) { out.add(candOf(t, 0, Integer.MAX_VALUE)); continue; }
                    out.add(candOf(t, 0, mid - 1));
                    out.add(candOf(t, mid, Integer.MAX_VALUE));
                }
            }
            default -> {   // merge-all-on-page
                Map<Integer, List<GroundTruth.Table>> byPage = new TreeMap<>();
                for (GroundTruth.Table t : expected) {
                    byPage.computeIfAbsent(gtPageOf(t), k -> new ArrayList<>()).add(t);
                }
                for (Map.Entry<Integer, List<GroundTruth.Table>> e : byPage.entrySet()) {
                    List<TableScore.GridCell> merged = new ArrayList<>();
                    List<List<String>> mergedRows = new ArrayList<>();
                    int rowOffset = 0;
                    for (GroundTruth.Table t : e.getValue()) {
                        for (TableScore.GridCell c : TableScore.gridCellsFromGroundTruth(t)) {
                            merged.add(new TableScore.GridCell(c.startRow() + rowOffset,
                                    c.startCol(), c.endRow() + rowOffset, c.endCol(), c.text()));
                        }
                        mergedRows.addAll(t.rows());
                        rowOffset += Math.max(1, t.rowCount());
                    }
                    out.add(new Cand(e.getKey(), mergedRows, merged));
                }
            }
        }
        return out;
    }

    /** One ground-truth table, restricted to grid rows {@code [rowFrom, rowTo]} and re-based to 0. */
    private static Cand candOf(GroundTruth.Table t, int rowFrom, int rowTo) {
        List<TableScore.GridCell> cells = new ArrayList<>();
        for (TableScore.GridCell c : TableScore.gridCellsFromGroundTruth(t)) {
            int r0 = Math.max(c.startRow(), rowFrom);
            int r1 = Math.min(c.endRow(), rowTo);
            if (r1 < r0) continue;
            cells.add(new TableScore.GridCell(r0 - rowFrom, c.startCol(), r1 - rowFrom,
                    c.endCol(), c.text()));
        }
        List<List<String>> rows = new ArrayList<>();
        for (int r = rowFrom; r <= Math.min(rowTo, t.rows().size() - 1); r++) {
            rows.add(t.rows().get(r));
        }
        return new Cand(gtPageOf(t), rows, cells);
    }

    /**
     * WHERE THE POOLING GAIN COMES FROM. The pooled/1:1 gap is large for the end-to-end
     * configurations (full pipeline: +0.10 MACRO, +0.20 micro), which is exactly the size of claim
     * that should not be taken on trust. If pooling really only forgives table SEGMENTATION, then the
     * gain must live almost entirely in the documents where the number of detected tables differs from
     * the number of annotated tables -- i.e. where a correspondence had to throw hits away or leave
     * ground-truth tables unpaired. Documents where the counts already agree should barely move.
     *
     * <p>This splits the corpus into three buckets and reports both protocols in each:
     *
     * <ol>
     *   <li><b>one GT table, one hit.</b> There is no correspondence to get wrong -- the only possible
     *       pairing IS the pooled comparison -- so the delta here MUST be exactly zero. A non-zero
     *       delta would mean the pooled implementation is not merely dropping the correspondence, and
     *       every other number in this report would be suspect. This bucket is the control.</li>
     *   <li><b>counts agree, more than one table.</b> A correspondence still has to be chosen, and
     *       greedy exact-cell F1 can choose wrongly (pair table A with the hit that actually recovered
     *       table B), which zeroes BOTH. Any delta here is mis-assignment, not fragmentation.</li>
     *   <li><b>counts differ.</b> Fragmentation or merging: the correspondence must discard hits or
     *       leave ground-truth tables unpaired, and everything discarded is charged.</li>
     * </ol>
     */
    private void printPoolingMechanism(List<DocResult> docs) {
        line("");
        rule();
        line("WHERE THE POOLING GAIN COMES FROM (ALL-77, end-to-end, dedup GT, ALL-PAGES scope)");
        rule();
        line("Bucket 1 is a CONTROL: with one ground-truth table and one hit there is no correspondence");
        line("to get wrong, so pooling must change nothing at all there. Buckets 2 and 3 separate the");
        line("two ways a correspondence can lose real matches: mis-assignment vs fragmentation.");
        line("");
        line("  %-26s %-26s %5s %9s %9s %8s %9s %9s %8s",
                "config", "bucket", "docs", "1:1 MACRO", "PL MACRO", "dMACRO",
                "1:1 micro", "PL micro", "dmicro");
        for (String config : List.of(C_FULL, C_FULL_ARB, C_LT, C_STREAM)) {
            for (int bucket = 0; bucket < 3; bucket++) {
                final int b = bucket;
                List<DocResult> sel = docs.stream()
                        .filter(d -> bucketOf(d, config) == b).toList();
                if (sel.isEmpty()) continue;
                Acc pair = aggregate(sel, key(config, M_E2E, P_PAIR, G_DEDUP));
                Acc pool = aggregate(sel, key(config, M_E2E, P_POOL, G_DEDUP));
                String label = switch (bucket) {
                    case 0 -> "1 GT table, 1 hit (CONTROL)";
                    case 1 -> "counts agree, >1 table";
                    default -> "counts differ";
                };
                line("  %-26s %-26s %5d %9.4f %9.4f %+8.4f %9.4f %9.4f %+8.4f",
                        trim(config, 26), label, sel.size(),
                        pair.macroF1(), pool.macroF1(), pool.macroF1() - pair.macroF1(),
                        pair.microF1(), pool.microF1(), pool.microF1() - pair.microF1());
            }
        }
    }

    private static int bucketOf(DocResult d, String config) {
        int hits = d.hitCounts.getOrDefault(config, 0);
        if (hits != d.gtTablesDedup) return 2;
        return d.gtTablesDedup == 1 ? 0 : 1;
    }

    /**
     * PROTOCOL SELF-CHECK. Two properties must hold if the pooled protocol really only drops the table
     * CORRESPONDENCE and changes nothing else:
     *
     * <ol>
     *   <li>Both denominators are identical between the protocols at a fixed ground-truth view. The
     *       ground-truth relation total cannot depend on pairing, and neither can the detected total:
     *       under 1:1 it is (paired hits' relations + unpaired hits' relations) = every hit's
     *       relations, which is exactly what pooling sums. So the ONLY thing pooling may move is the
     *       matched count -- if a denominator moves, the pooled implementation is doing something
     *       extra and the comparison is not clean.</li>
     *   <li>{@code stream} and {@code stream:breuel} are the same configuration (the default finder),
     *       so every one of their tallies must agree exactly. This catches wiring mistakes in the
     *       per-finder plumbing.</li>
     * </ol>
     */
    private void printProtocolSelfCheck(List<DocResult> docs) {
        line("");
        rule();
        line("PROTOCOL SELF-CHECK (page scope %s)", docs.isEmpty() ? "?" : docs.get(0).scope);
        rule();
        int checked = 0, denomMismatch = 0, aliasMismatch = 0;
        int offChecked = 0, offDenomMismatch = 0;
        long lenientGt = 0, strictGt = 0;
        List<String> configs = new ArrayList<>(List.of(C_FULL, C_FULL_ARB, C_LT, C_STREAM));
        for (String f : FINDER_NAMES) configs.add(C_STREAM + ":" + f);
        for (DocResult d : docs) {
            for (String config : configs) {
                for (String mode : List.of(M_E2E, M_REGION, M_RERUN)) {
                    if (mode.equals(M_RERUN) && !isStreamConfig(config)) continue;
                    for (String gt : List.of(G_DEDUP, G_RAW)) {
                        Tally pool = d.tallies.get(key(config, mode, P_POOL, gt));
                        Tally pair = d.tallies.get(key(config, mode, P_PAIR, gt));
                        // Same invariant for the OFFICIAL pairing: a correspondence rule may move only
                        // the MATCHED count. Under 1:1off every GT table's relations land in `gt`
                        // (paired or charged as a miss) and every candidate's relations land in
                        // `detected` (paired or charged as an FP table), so both denominators must
                        // equal the pooled ones exactly. If the page filter or the argmax ever dropped
                        // a candidate silently, this is the check that catches it.
                        Tally off = d.tallies.get(key(config, mode, P_PAIR_OFF, gt));
                        if (pool != null && off != null) {
                            offChecked++;
                            if (pool.gt != off.gt || pool.detected != off.detected) {
                                offDenomMismatch++;
                                if (offDenomMismatch <= 5) {
                                    line("  1:1off DENOMINATOR MISMATCH %s %s/%s/%s: pooled gt=%d "
                                                    + "det=%d vs 1:1off gt=%d det=%d",
                                            d.id, config, mode, gt,
                                            pool.gt, pool.detected, off.gt, off.detected);
                                }
                            }
                        }
                        Tally len = d.tallies.get(key(config, mode, P_LENIENT, gt));
                        if (off != null && len != null) {
                            strictGt += off.gt;
                            lenientGt += len.gt;
                        }
                        if (pool == null || pair == null) continue;
                        checked++;
                        if (pool.gt != pair.gt || pool.detected != pair.detected) {
                            denomMismatch++;
                            if (denomMismatch <= 5) {
                                line("  DENOMINATOR MISMATCH %s %s/%s/%s: pooled gt=%d det=%d "
                                                + "vs 1:1 gt=%d det=%d",
                                        d.id, config, mode, gt,
                                        pool.gt, pool.detected, pair.gt, pair.detected);
                            }
                        }
                        Tally alias = d.tallies.get(key(C_STREAM + ":breuel", mode, P_POOL, gt));
                        Tally base = d.tallies.get(key(C_STREAM, mode, P_POOL, gt));
                        if (alias != null && base != null
                                && (alias.matched != base.matched || alias.gt != base.gt
                                    || alias.detected != base.detected)) {
                            aliasMismatch++;
                        }
                    }
                }
            }
        }
        line("  (protocol, GT, config, mode, document) tallies compared : %d", checked);
        line("  denominator mismatches between POOLED and 1:1           : %d  %s",
                denomMismatch, denomMismatch == 0
                        ? "(as required -- pooling moves only the MATCHED count)" : "*** BUG ***");
        line("  stream vs stream:breuel tally mismatches                : %d  %s",
                aliasMismatch, aliasMismatch == 0 ? "(identical, as expected)" : "*** BUG ***");
        line("  tallies compared, POOLED vs 1:1off                      : %d", offChecked);
        line("  denominator mismatches between POOLED and 1:1off        : %d  %s",
                offDenomMismatch, offDenomMismatch == 0
                        ? "(as required -- the official pairing moves only MATCHED)" : "*** BUG ***");
        line("  GT relations in denominator, 1:1off strict vs lenient   : %d vs %d (%.2f%% of GT is",
                strictGt, lenientGt, strictGt == 0 ? 0.0 : 100.0 * (strictGt - lenientGt) / strictGt);
        line("    forgiven by the reference pipeline's rule-5 free pass and is NOT forgiven here)");
    }

    /**
     * Page 1 of one prose PDF through the whole pipeline, both merge rules.
     * Returns {latticeTaggedTables, positionalMergeTables, arbitratedTables, ruledCount, streamCount}.
     */
    private static int[] fullPipelinePage1(GutterFinder finder, Path pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (doc.getNumberOfPages() < 1) return new int[]{0, 0, 0, 0, 0};
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            Map<Integer, List<TextPosition>> byPage = new LinkedHashMap<>();
            byPage.put(1, glyphs);
            List<TableExtractor.TableHit> ruled =
                    new ArrayList<>(TableExtractor.extract(doc, List.of(1), byPage).tables);
            List<TableExtractor.TableHit> str = StreamTableExtractor.extractPage(1, glyphs, finder);
            List<TableExtractor.TableHit> positional = new ArrayList<>(ruled);
            for (TableExtractor.TableHit s : str) {
                if (!MetricFixHarness.overlapsSubstantially(s, ruled)) positional.add(s);
            }
            int arb;
            try {
                arb = TableExtractor.arbitrate(ruled, str).size();
            } catch (TableExtractor.RulingOverflowException e) {
                arb = positional.size();
            }
            return new int[]{ruled.size(), positional.size(), arb, ruled.size(), str.size()};
        } catch (Throwable t) {
            return new int[]{0, 0, 0, 0, 0};   // unreadable prose file -> conservatively "no table"
        }
    }

    /**
     * The whole shipping pipeline on ONE prose PDF, over exactly the pages
     * {@code --pages default} would process, via the PRODUCTION wired call rather than a harness
     * re-assembly of it. Returns {tables with the flag OFF, tables with the flag ON, pages processed}.
     *
     * <p>WHY THIS IS REPORTED. The published prose false-positive rate (0.060) is a PAGE 1 ONLY
     * measurement, but the shipping default processes up to five pages. A prose PDF whose page 1 is
     * clean and whose page 3 is not was counted clean. This row measures the rate on the page set the
     * product actually opens.
     */
    private static int[] fullPipelineShipping(Path pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            if (doc.getNumberOfPages() < 1) return new int[]{0, 0, 0};
            List<Integer> pages = ShippingPages.select(doc).pages();
            Map<Integer, List<TextPosition>> byPage = new LinkedHashMap<>();
            for (int p : pages) byPage.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
            int off = TableExtractor.extract(doc, pages, byPage, false).tables.size();
            int on = TableExtractor.extract(doc, pages, byPage, true).tables.size();
            return new int[]{off, on, pages.size()};
        } catch (Throwable t) {
            return new int[]{0, 0, 0};   // unreadable prose file -> conservatively "no table"
        }
    }

    private void printProseAndTiming(List<GutterFinder> finders, List<Double> streamMs) {
        line("");
        rule();
        line("HOSTILE-INPUT GUARDRAILS (protocol-independent -- no ground truth involved)");
        rule();
        List<Path> prose = null;
        try {
            prose = BakeOffHarness.sampleProsePdfs();
        } catch (Exception e) {
            line("  prose sampling failed: %s", e);
        }
        if (prose == null || prose.isEmpty()) {
            line("  prose corpus unavailable -- prose false-positive rate NOT measured this run.");
        } else {
            line("  prose false-positive rate, page 1 of %d real-world PDFs "
                    + "(/home/coz/Downloads/phishpdfs):", prose.size());
            for (GutterFinder f : finders) {
                int flagged = 0;
                for (Path p : prose) {
                    if (BakeOffHarness.hasStreamTableOnPage1(f, p)) flagged++;
                }
                line("    %-10s %d/%d = %.4f", f.name(), flagged, prose.size(),
                        flagged / (double) prose.size());
            }
            // The rows above are the STREAM PATH ALONE (the historical watch item). Per-region
            // arbitration changes what the FULL pipeline emits, so the full pipeline's own
            // false-positive rate is measured too, under BOTH merge rules, on the same 200 files.
            // Arbitration can only SELECT among candidates the two paths already produced -- it can
            // never invent one -- so the arbitrated rate can differ from the positional rate only by
            // trading a lattice false table for a stream one, or vice versa, on the same page.
            int posFp = 0, arbFp = 0, latFp = 0, maxRuledPerPage = 0, maxStreamPerPage = 0;
            GutterFinder breuel = finders.get(0);
            for (Path p : prose) {
                int[] r = fullPipelinePage1(breuel, p);
                if (r[0] > 0) latFp++;
                if (r[1] > 0) posFp++;
                if (r[2] > 0) arbFp++;
                maxRuledPerPage = Math.max(maxRuledPerPage, r[3]);
                maxStreamPerPage = Math.max(maxStreamPerPage, r[4]);
            }
            line("  full-pipeline false-positive rate on the same %d prose PDFs (page 1, >=1 table"
                    + " emitted):", prose.size());
            line("    lattice+tagged only          %d/%d = %.4f", latFp, prose.size(),
                    latFp / (double) prose.size());
            line("    full, positional merge       %d/%d = %.4f", posFp, prose.size(),
                    posFp / (double) prose.size());
            line("    full, per-region arbitration %d/%d = %.4f", arbFp, prose.size(),
                    arbFp / (double) prose.size());
            line("  arbitration DoS headroom on prose: max ruling candidates on one page=%d,"
                    + " max stream candidates=%d", maxRuledPerPage, maxStreamPerPage);

            // SHIPPING PAGE SELECTION. Every rate above is page 1 only; the shipping default opens up
            // to five pages, so it can find a false table the page-1 rate never sees. Measured with
            // the production wired call TableExtractor.extract(doc, pages, glyphs, streamTables).
            int shipOff = 0, shipOn = 0, shipPages = 0;
            for (Path p : prose) {
                int[] r = fullPipelineShipping(p);
                if (r[0] > 0) shipOff++;
                if (r[1] > 0) shipOn++;
                shipPages += r[2];
            }
            line("  SAME sample under the SHIPPING page selection (%d pages total, %.2f/doc), via the",
                    shipPages, shipPages / (double) prose.size());
            line("  production wired call TableExtractor.extract(doc, pages, glyphs, streamTables):");
            line("    flag OFF (tagged+lattice)    %d/%d = %.4f", shipOff, prose.size(),
                    shipOff / (double) prose.size());
            line("    flag ON  (+stream+arbitr.)   %d/%d = %.4f   <-- the rate the DEFAULT run has",
                    shipOn, prose.size(), shipOn / (double) prose.size());
            line("  The page-1 rows above are NOT the shipping rate; they are a page-1 measurement.");
        }
        List<Double> sorted = new ArrayList<>(streamMs);
        Collections.sort(sorted);
        line("  stream-path wall time per PDF (breuel, whole document incl. load+glyphs): "
                + "p50=%.1fms p95=%.1fms max=%.1fms",
                pct(sorted, 50), pct(sorted, 95), sorted.isEmpty() ? 0.0 : sorted.get(sorted.size() - 1));
    }

    private static double pct(List<Double> sorted, int p) {
        if (sorted.isEmpty()) return 0.0;
        int i = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    private void printProtocolArgument() {
        line("");
        rule();
        line("WHY POOLED IS THE PRIMARY PROTOCOL (and where it is NOT the right one)");
        rule();
        line("  1. The metric's own unit is content-identified. An adjacency relation names two cell");
        line("     CONTENTS and a direction; it is invariant to where the table sits and to how the");
        line("     page is partitioned into tables. Pooling scores exactly that unit.");
        line("  2. The corpus is genuinely ambiguous about table boundaries -- adjacent mini-tables");
        line("     are sometimes annotated as one table and sometimes as several. 1:1 pairing turns");
        line("     that annotator judgement into a scored error for the extractor.");
        line("  3. Pooling does NOT forgive real errors. A spurious table's relations enter the");
        line("     detected multiset and match nothing (precision loss); a missed table's relations");
        line("     sit unmatched in the ground-truth multiset (recall loss). Only the CORRESPONDENCE");
        line("     is dropped, not the accounting.");
        line("  4. It is not a pairing-order effect: IoU pairing instead of F1 pairing moved");
        line("     lattice+tagged micro by +0.0008, and an oracle best-adjacency pairing reached only");
        line("     0.3018 -- below pooling. The gain is structural, not a search artifact.");
        line("");
        line("  WHERE IT IS NOT THE RIGHT PROTOCOL: in region-given mode the region -- and therefore");
        line("  the segmentation -- has already been handed over, so there is nothing for pooling to");
        line("  forgive and its justification lapses. Both are reported above; prefer the per-region");
        line("  (1:1) row when comparing to the published region-given column, and read the pooled");
        line("  row there as an upper bound.");
        line("");
        line("  CAVEAT that pooling does not fix: pooling makes the metric blind to table");
        line("  SEGMENTATION quality. If segmentation ever becomes a product requirement it needs a");
        line("  separate measurement; the 1:1 rows above are the closest available proxy.");
    }

    /** Last path segment of a corpus id, so the per-document audit table stays readable. */
    private static String shortId(String id) {
        int slash = id.lastIndexOf('/');
        return slash < 0 ? id : id.substring(slash + 1);
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    /** Keep the LAST n characters -- corpus ids share a long directory prefix, so the tail is the
     *  part that identifies the document. */
    private static String tail(String s, int n) {
        return s.length() <= n ? s : s.substring(s.length() - n);
    }
}
