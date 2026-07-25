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
// nothing about the definition of a relation or of adjacency matching (that definition was validated
// to within 3 relations of an independent port of the official ICDAR 2013 evaluator: 156 tables /
// 25,317 relations vs the port's 25,320). What it changes is PROTOCOL and ground-truth HYGIENE:
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

    /** The order every table in this report lists its four (protocol, ground-truth) combinations in:
     *  the primary first, the pre-correction baseline last. */
    private static final List<String[]> PROTOCOL_ORDER = List.of(
            new String[]{P_POOL, G_DEDUP},   // PRIMARY -- both artifacts removed
            new String[]{P_POOL, G_RAW},     // pooling only
            new String[]{P_PAIR, G_DEDUP},   // dedup only
            new String[]{P_PAIR, G_RAW});    // neither -- reproduces the pre-correction numbers

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
    private static final class Tally {
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
        int docs, covered, scoredTables;

        void addDoc(Tally t) {
            matched += t.matched; detected += t.detected; gt += t.gt;
            double p = t.detected == 0 ? 0.0 : (double) t.matched / t.detected;
            double r = t.gt == 0 ? 0.0 : (double) t.matched / t.gt;
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
     * END-TO-END, 1:1 GREEDY PAIRING (the pre-correction protocol, retained verbatim). Each expected
     * table is paired with whichever remaining hit maximises EXACT-CELL F1 and scored against it;
     * expected tables left unpaired contribute their whole relation count as recall loss, and hits
     * left over contribute theirs as precision loss.
     */
    private static Tally e2ePaired(List<TableExtractor.TableHit> hits,
                                    List<GroundTruth.Table> expected) {
        Tally t = new Tally();
        t.covered = !hits.isEmpty();
        List<TableExtractor.TableHit> available = new ArrayList<>(hits);
        for (GroundTruth.Table exp : expected) {
            List<TableScore.GridCell> gtCells = TableScore.gridCellsFromGroundTruth(exp);
            if (available.isEmpty()) {
                t.addGtOnly(TableScore.officialRelationCount(gtCells, false,
                        TableScore.Semantics.MULTISET));
                continue;
            }
            TableExtractor.TableHit best = null;
            double bestF1 = -1;
            for (TableExtractor.TableHit h : available) {
                double f1 = TableScore.score(exp, h.rows).f1();
                if (f1 > bestF1) { bestF1 = f1; best = h; }
            }
            available.remove(best);
            t.add(compare(rels(gtCells), rels(MetricFixHarness.cellsOf(best))));
            t.tables++;
        }
        for (TableExtractor.TableHit h : available) {
            t.addDetOnly(TableScore.officialRelationCount(MetricFixHarness.cellsOf(h), false,
                    TableScore.Semantics.MULTISET));
        }
        return t;
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
    private static Tally e2ePooled(List<TableExtractor.TableHit> hits,
                                    List<GroundTruth.Table> expected) {
        Tally t = new Tally();
        t.covered = !hits.isEmpty();
        List<TableScore.Relation> gt = new ArrayList<>();
        for (GroundTruth.Table exp : expected) gt.addAll(gtRels(exp));
        List<TableScore.Relation> det = new ArrayList<>();
        for (TableExtractor.TableHit h : hits) det.addAll(rels(MetricFixHarness.cellsOf(h)));
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

    // ---------------------------------------------------------------------------- per-document run

    private static DocResult measure(BakeOffHarness.ScoreUnit unit, List<GutterFinder> finders) {
        DocResult d = new DocResult();
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
            List<Integer> pageList = new ArrayList<>();
            Map<Integer, List<TextPosition>> glyphs = new LinkedHashMap<>();
            Map<Integer, PDRectangle> cropByPage = new LinkedHashMap<>();
            for (int p = 1; p <= pages; p++) {
                pageList.add(p);
                glyphs.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
                cropByPage.put(p, doc.getPage(p - 1).getCropBox());
            }

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

            // ---- stream, per finder ----
            Map<String, List<TableExtractor.TableHit>> streamByFinder = new LinkedHashMap<>();
            for (GutterFinder f : finders) {
                List<TableExtractor.TableHit> hits = new ArrayList<>();
                try {
                    for (int p : pageList) {
                        hits.addAll(StreamTableExtractor.extractPage(p, glyphs.get(p), f));
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
                for (GtView gt : gtViews) {
                    d.tallies.put(key(cfg.name(), M_E2E, P_PAIR, gt.name()),
                            e2ePaired(cfg.hits(), gt.tables()));
                    d.tallies.put(key(cfg.name(), M_E2E, P_POOL, gt.name()),
                            e2ePooled(cfg.hits(), gt.tables()));
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
        for (BakeOffHarness.ScoreUnit u : units) docs.add(measure(u, finders));

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
        printErrors(docs);
        printDedupAudit(docs);
        printGtInventory(docs);
        printClassification(docs);
        printHeadline(docs);
        printFullTable(docs);
        printDeltas(docs);
        printPoolingMechanism(docs);
        printProtocolSelfCheck(docs);
        printRegressions(docs);
        printProseAndTiming(finders, streamMs);
        printProtocolArgument();

        String path = System.getProperty("baselineOut", "target/baseline-report.md");
        Path outPath = Path.of(path).toAbsolutePath().normalize();
        Files.createDirectories(outPath.getParent());
        Files.writeString(outPath, "```\n" + out + "```\n", StandardCharsets.UTF_8);
        System.out.println();
        System.out.println("Report also written to " + outPath);
    }

    // ---------------------------------------------------------------------------------- reporting

    private void printErrors(List<DocResult> docs) {
        List<DocResult> bad = docs.stream().filter(d -> d.error != null).toList();
        line("");
        line("Documents with a measurement error: %d", bad.size());
        for (DocResult d : bad) line("  %s: %s", d.id, d.error);
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
        line("  25,320 relations to within 3 relations.");
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
        line("CORPUS CLASSIFICATION by TableExtractor.extract (tagged+lattice) output");
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

    /** The numbers the project should quote: PRIMARY protocol only, one line per configuration. */
    private void printHeadline(List<DocResult> docs) {
        line("");
        rule();
        line("HEADLINE -- PRIMARY PROTOCOL ONLY, DE-DUPLICATED GT, MACRO FIRST");
        rule();
        line("Primary protocol is POOLED end-to-end and PER-REGION (1:1) in region-given mode -- see");
        line("#primaryProtocol for why, and the FULL BASELINE table below for the other three");
        line("combinations of every row. The protocol column states it on every line.");
        line("");
        line("  %-26s %-18s %-11s %-7s %8s %8s %8s %8s %5s",
                "config", "mode", "subset", "protocol", "MACRO", "microP", "microR", "microF1", "docs");
        for (Subset s : subsets(docs)) {
            for (String config : List.of(C_FULL, C_FULL_ARB, C_LT, C_STREAM)) {
                for (String mode : List.of(M_E2E, M_REGION, M_RERUN)) {
                    if (mode.equals(M_RERUN) && !isStreamConfig(config)) continue;
                    String protocol = primaryProtocol(mode);
                    Acc a = aggregate(s.docs(), key(config, mode, protocol, G_DEDUP));
                    if (a.docs == 0) continue;
                    line("  %-26s %-18s %-11s %-7s %8.4f %8.4f %8.4f %8.4f %5d",
                            trim(config, 26), mode, s.name(), protocol,
                            a.macroF1(), a.microP(), a.microR(), a.microF1(), a.docs);
                }
            }
        }
        line("");
        line("  Published end-to-end calibration (all MACRO): KYTHE 0.5220, pdf2table 0.5850,");
        line("  TABFIND 0.6962, Nitro 0.7535, Acrobat 0.7685, TEXUS 0.8259, Nurminen 0.8374 (best");
        line("  pure heuristic), OmniPage 0.8420, FineReader 0.8772, GTE 0.9350.");
        line("  Published region-given: Nurminen 0.9460, GTE 0.9624.");
    }

    /** Every configuration under all four (protocol, GT) combinations. Primary row first. */
    private void printFullTable(List<DocResult> docs) {
        line("");
        rule();
        line("FULL BASELINE -- every configuration x mode x PROTOCOL x GROUND-TRUTH VIEW");
        rule();
        line("Row order within a block is fixed: POOLED/dedup (PRIMARY), POOLED/raw, 1:1/dedup,");
        line("1:1/raw (= the pre-correction baseline, reproduces MetricFixHarness exactly).");

        for (Subset s : subsets(docs)) {
            List<String> configs = s.name().equals("ALL-77") || s.name().equals("borderless")
                    ? reportConfigs() : List.of(C_FULL, C_FULL_ARB, C_LT, C_STREAM);
            line("");
            line("--- subset %s (n=%d PDFs) ---", s.name(), s.docs().size());
            line("  %-26s %-18s %-7s %-5s %8s %8s %8s %8s %5s %5s %5s",
                    "config", "mode", "protocol", "GT", "MACRO", "microP", "microR", "microF1",
                    "docs", "cov", "tbl");
            for (String config : configs) {
                for (String mode : List.of(M_E2E, M_REGION, M_RERUN)) {
                    if (mode.equals(M_RERUN) && !isStreamConfig(config)) continue;
                    for (String[] pg : PROTOCOL_ORDER) {
                        Acc a = aggregate(s.docs(), key(config, mode, pg[0], pg[1]));
                        if (a.docs == 0) continue;
                        line("  %-26s %-18s %-7s %-5s %8.4f %8.4f %8.4f %8.4f %5d %5d %5d",
                                trim(config, 26), mode, pg[0], pg[1],
                                a.macroF1(), a.microP(), a.microR(), a.microF1(),
                                a.docs, a.covered, a.scoredTables);
                    }
                }
            }
        }
    }

    /** What each correction is worth, per configuration, so neither is taken on faith. */
    private void printDeltas(List<DocResult> docs) {
        line("");
        rule();
        line("WHAT EACH CORRECTION IS WORTH (ALL-77 and borderless subsets)");
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
        line("HONESTY CHECK -- where a correction LOWERS the reported score (|delta| > %.3f)",
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
        line("WHERE THE POOLING GAIN COMES FROM (ALL-77, end-to-end, de-duplicated GT)");
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
        line("PROTOCOL SELF-CHECK");
        rule();
        int checked = 0, denomMismatch = 0, aliasMismatch = 0;
        List<String> configs = new ArrayList<>(List.of(C_FULL, C_FULL_ARB, C_LT, C_STREAM));
        for (String f : FINDER_NAMES) configs.add(C_STREAM + ":" + f);
        for (DocResult d : docs) {
            for (String config : configs) {
                for (String mode : List.of(M_E2E, M_REGION, M_RERUN)) {
                    if (mode.equals(M_RERUN) && !isStreamConfig(config)) continue;
                    for (String gt : List.of(G_DEDUP, G_RAW)) {
                        Tally pool = d.tallies.get(key(config, mode, P_POOL, gt));
                        Tally pair = d.tallies.get(key(config, mode, P_PAIR, gt));
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

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
