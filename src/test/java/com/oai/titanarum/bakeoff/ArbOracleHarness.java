// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/ (matching
// BaselineHarness / MetricFixHarness's own convention) but declares `package com.oai.titanarum;`
// because it needs package-private production types (TableExtractor, TableHit,
// StreamTableExtractor, GutterFinder) and the package-private test helper
// TableTestPdfs.harvestGlyphs.
//
// PURPOSE: measure the ORACLE CEILING of per-region path arbitration (lattice/tagged vs stream)
// under the PRIMARY protocol -- document-POOLED, de-duplicated ground truth, MACRO first, 77
// scoring units. Scores NOTHING with a shipping rule; every number here is either the existing
// merge, a fixed global policy, or a ground-truth-informed ORACLE. The oracle NEVER ships; it only
// bounds the lever.
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.GtDedup;
import com.oai.titanarum.bakeoff.TableScore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

class ArbOracleHarness {

    /** Same 0.5 directional coverage threshold the production/harness merge already uses. */
    private static final float DROP = 0.5f;

    private final StringBuilder out = new StringBuilder();

    private void line(String fmt, Object... a) {
        out.append(a.length == 0 ? fmt : String.format(fmt, a)).append('\n');
        System.out.println(a.length == 0 ? fmt : String.format(fmt, a));
    }

    // ------------------------------------------------------------------ scoring (protocol-faithful)

    private static List<TableScore.Relation> rels(List<TableScore.GridCell> cells) {
        return TableScore.buildOfficialRelations(cells, false).relations();
    }

    /** Document-POOLED end-to-end F1 -- byte-identical accounting to BaselineHarness#e2ePooled. */
    private static double pooledF1(List<TableExtractor.TableHit> hits,
                                   List<TableScore.Relation> gtPooled) {
        List<TableScore.Relation> det = new ArrayList<>();
        for (TableExtractor.TableHit h : hits) det.addAll(rels(cellsOf(h)));
        TableScore.AdjResult r =
                TableScore.compareRelations(gtPooled, det, TableScore.Semantics.MULTISET);
        long m = r.matched();
        if (m == 0) return 0.0;
        double p = r.detectedTotal() == 0 ? 0.0 : (double) m / r.detectedTotal();
        double rec = r.gtTotal() == 0 ? 0.0 : (double) m / r.gtTotal();
        return (p + rec) == 0 ? 0.0 : 2 * p * rec / (p + rec);
    }

    /** The SAME cell projection BaselineHarness scores with -- reused, never re-implemented, so the
     *  baseline row of this report has to reproduce BaselineHarness's own full-pipeline number. */
    static List<TableScore.GridCell> cellsOf(TableExtractor.TableHit h) {
        return MetricFixHarness.cellsOf(h);
    }

    // ------------------------------------------------------------------------------ overlap grouping

    private static float coverage(TableExtractor.TableHit a, TableExtractor.TableHit b) {
        if (a.bbox == null || b.bbox == null || a.page != b.page) return 0f;
        float area = Math.max(0f, a.bbox[2] - a.bbox[0]) * Math.max(0f, a.bbox[3] - a.bbox[1]);
        if (area <= 0f) return 0f;
        float x0 = Math.max(a.bbox[0], b.bbox[0]);
        float y0 = Math.max(a.bbox[1], b.bbox[1]);
        float x1 = Math.min(a.bbox[2], b.bbox[2]);
        float y1 = Math.min(a.bbox[3], b.bbox[3]);
        if (x1 <= x0 || y1 <= y0) return 0f;
        return ((x1 - x0) * (y1 - y0)) / area;
    }

    /** True iff the two hits contest the same region: either one's area is >DROP covered by the other. */
    private static boolean contests(TableExtractor.TableHit s, TableExtractor.TableHit t) {
        return coverage(s, t) > DROP || coverage(t, s) > DROP;
    }

    /** One contested region: the lattice/tagged hits and the stream hits that overlap each other. */
    private static final class Group {
        final List<TableExtractor.TableHit> lat = new ArrayList<>();
        final List<TableExtractor.TableHit> str = new ArrayList<>();
    }

    /**
     * Connected components of the bipartite "contests" relation between taggedLattice hits and
     * stream hits. A hit with no cross-path partner is UNCONTESTED and always kept -- there is
     * nothing to arbitrate.
     */
    private static List<Group> groups(List<TableExtractor.TableHit> lat,
                                      List<TableExtractor.TableHit> str,
                                      List<TableExtractor.TableHit> uncontested) {
        int nl = lat.size(), ns = str.size();
        boolean[][] adj = new boolean[nl][ns];
        boolean[] latHas = new boolean[nl];
        boolean[] strHas = new boolean[ns];
        for (int i = 0; i < nl; i++) {
            for (int j = 0; j < ns; j++) {
                if (contests(str.get(j), lat.get(i))) {
                    adj[i][j] = true; latHas[i] = true; strHas[j] = true;
                }
            }
        }
        for (int i = 0; i < nl; i++) if (!latHas[i]) uncontested.add(lat.get(i));
        for (int j = 0; j < ns; j++) if (!strHas[j]) uncontested.add(str.get(j));

        boolean[] seenL = new boolean[nl], seenS = new boolean[ns];
        List<Group> gs = new ArrayList<>();
        for (int i0 = 0; i0 < nl; i0++) {
            if (!latHas[i0] || seenL[i0]) continue;
            Group g = new Group();
            // BFS over the bipartite graph
            List<Integer> ql = new ArrayList<>(), qs = new ArrayList<>();
            ql.add(i0); seenL[i0] = true;
            while (!ql.isEmpty() || !qs.isEmpty()) {
                if (!ql.isEmpty()) {
                    int i = ql.remove(ql.size() - 1);
                    g.lat.add(lat.get(i));
                    for (int j = 0; j < ns; j++) if (adj[i][j] && !seenS[j]) { seenS[j] = true; qs.add(j); }
                } else {
                    int j = qs.remove(qs.size() - 1);
                    g.str.add(str.get(j));
                    for (int i = 0; i < nl; i++) if (adj[i][j] && !seenL[i]) { seenL[i] = true; ql.add(i); }
                }
            }
            gs.add(g);
        }
        return gs;
    }

    // -------------------------------------------------------------------------- per-document record

    private static final class Doc {
        String id, source, bucket;
        int nGroups;
        double fBaseline, fLatOnly, fStrOnly, fBoth, fStreamPref, fOracle;
        String oracleChoices = "";
        String signals = "";
        String err;
    }

    /** Baseline merge, verbatim: drop a stream hit whose area is >DROP covered by a taggedLattice hit. */
    private static List<TableExtractor.TableHit> baselineMerge(List<TableExtractor.TableHit> lat,
                                                              List<TableExtractor.TableHit> str) {
        List<TableExtractor.TableHit> full = new ArrayList<>(lat);
        for (TableExtractor.TableHit s : str) {
            boolean drop = false;
            for (TableExtractor.TableHit t : lat) if (coverage(s, t) > DROP) { drop = true; break; }
            if (!drop) full.add(s);
        }
        return full;
    }

    private static List<TableExtractor.TableHit> assemble(List<TableExtractor.TableHit> uncontested,
                                                         List<Group> gs, int[] choice) {
        List<TableExtractor.TableHit> hits = new ArrayList<>(uncontested);
        for (int k = 0; k < gs.size(); k++) {
            Group g = gs.get(k);
            if (choice[k] == 0 || choice[k] == 2) hits.addAll(g.lat);
            if (choice[k] == 1 || choice[k] == 2) hits.addAll(g.str);
        }
        return hits;
    }

    private Doc measure(BakeOffHarness.ScoreUnit unit, GutterFinder finder) {
        Doc d = new Doc();
        d.id = unit.id();
        d.source = unit.id().contains("competition-dataset-us") ? "icdar-us"
                : unit.id().contains("competition-dataset-eu") ? "icdar-eu" : "csv";
        try (PDDocument doc = Loader.loadPDF(unit.pdf().toFile())) {
            int pages = doc.getNumberOfPages();
            List<Integer> pageList = new ArrayList<>();
            Map<Integer, List<TextPosition>> glyphs = new LinkedHashMap<>();
            for (int p = 1; p <= pages; p++) {
                pageList.add(p);
                glyphs.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
            }
            List<TableExtractor.TableHit> lat =
                    new ArrayList<>(TableExtractor.extract(doc, pageList, glyphs).tables);
            long nLat = lat.stream().filter(h -> "lattice".equals(h.extractionMethod)).count();
            long nTag = lat.stream().filter(h -> "tagged".equals(h.extractionMethod)).count();
            d.bucket = nTag > 0 && nLat > 0 ? "both" : nTag > 0 ? "tagged" : nLat > 0 ? "lattice" : "neither";

            List<TableExtractor.TableHit> str = new ArrayList<>();
            for (int p : pageList) str.addAll(StreamTableExtractor.extractPage(p, glyphs.get(p), finder));

            // de-duplicated ground truth, pooled
            List<GroundTruth.Table> raw = unit.expected();
            GtDedup.Result dd = GtDedup.dedup(raw);
            Set<Integer> removed = new HashSet<>();
            for (GtDedup.Duplicate x : dd.removed()) removed.add(x.removedIndex());
            List<TableScore.Relation> gtPooled = new ArrayList<>();
            for (int i = 0; i < raw.size(); i++) {
                if (removed.contains(i)) continue;
                gtPooled.addAll(rels(TableScore.gridCellsFromGroundTruth(raw.get(i))));
            }

            List<TableExtractor.TableHit> uncontested = new ArrayList<>();
            List<Group> gs = groups(lat, str, uncontested);
            d.nGroups = gs.size();

            d.fBaseline = pooledF1(baselineMerge(lat, str), gtPooled);
            d.fLatOnly = pooledF1(lat, gtPooled);
            d.fStrOnly = pooledF1(str, gtPooled);

            int[] all = new int[gs.size()];
            Arrays.fill(all, 2);
            d.fBoth = pooledF1(assemble(uncontested, gs, all), gtPooled);
            int[] sp = new int[gs.size()];
            Arrays.fill(sp, 1);
            d.fStreamPref = pooledF1(assemble(uncontested, gs, sp), gtPooled);

            // ORACLE over per-group choices {0=lattice only, 1=stream only, 2=both}.
            int[] best = new int[gs.size()];
            double bestF;
            if (gs.size() <= 8) {                          // 3^8 = 6561, exhaustive
                int[] c = new int[gs.size()];
                bestF = pooledF1(assemble(uncontested, gs, c), gtPooled);
                long total = 1;
                for (int i = 0; i < gs.size(); i++) total *= 3;
                for (long code = 1; code < total; code++) {
                    long v = code;
                    for (int i = 0; i < gs.size(); i++) { c[i] = (int) (v % 3); v /= 3; }
                    double f = pooledF1(assemble(uncontested, gs, c), gtPooled);
                    if (f > bestF) { bestF = f; best = c.clone(); }
                }
            } else {                                        // coordinate ascent, 4 sweeps
                bestF = pooledF1(assemble(uncontested, gs, best), gtPooled);
                for (int sweep = 0; sweep < 4; sweep++) {
                    boolean moved = false;
                    for (int k = 0; k < gs.size(); k++) {
                        int keep = best[k];
                        for (int v = 0; v < 3; v++) {
                            if (v == keep) continue;
                            best[k] = v;
                            double f = pooledF1(assemble(uncontested, gs, best), gtPooled);
                            if (f > bestF + 1e-12) { bestF = f; keep = v; moved = true; }
                        }
                        best[k] = keep;
                    }
                    if (!moved) break;
                }
            }
            d.fOracle = bestF;
            StringBuilder ch = new StringBuilder();
            StringBuilder sig = new StringBuilder();
            for (int k = 0; k < gs.size(); k++) {
                ch.append(best[k] == 0 ? 'L' : best[k] == 1 ? 'S' : 'B');
                Group g = gs.get(k);
                sig.append(String.format("[g%d lat=%s str=%s]", k,
                        describe(g.lat), describe(g.str)));
            }
            d.oracleChoices = ch.toString();
            d.signals = sig.toString();
        } catch (Throwable t) {
            d.err = t.getClass().getSimpleName() + ": " + t.getMessage();
            if (d.bucket == null) d.bucket = "neither";
        }
        return d;
    }

    private static String describe(List<TableExtractor.TableHit> hs) {
        StringBuilder b = new StringBuilder();
        for (TableExtractor.TableHit h : hs) {
            int cells = h.cells == null ? 0 : h.cells.size();
            int nonEmpty = 0;
            if (h.cells != null) {
                for (TableExtractor.CellHit c : h.cells) {
                    if (c.text != null && !c.text.isBlank()) nonEmpty++;
                }
            }
            b.append(String.format("{%s %dx%d cells=%d fill=%.2f conf=%s}",
                    h.extractionMethod, h.rowCount, h.colCount, cells,
                    cells == 0 ? 0.0 : (double) nonEmpty / cells,
                    h.confidence == null ? "-" : String.format("%.3f", h.confidence)));
        }
        return b.length() == 0 ? "{}" : b.toString();
    }

    // ------------------------------------------------------------------------------------- the test

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("arbOracle"), "set -DarbOracle=true to run");
        GutterFinder finder = new BreuelGutterFinder();
        List<BakeOffHarness.ScoreUnit> units =
                BakeOffHarness.buildScoringSet(new StringBuilder()).units;
        List<Doc> docs = new ArrayList<>();
        for (BakeOffHarness.ScoreUnit u : units) docs.add(measure(u, finder));

        line("================================================================================");
        line("PER-REGION PATH ARBITRATION -- ORACLE CEILING (PRIMARY protocol: POOLED, dedup GT,");
        line("MACRO first, %d scoring units, end-to-end)", docs.size());
        line("================================================================================");
        line("Configurations. baseline = the merge in force today (a stream hit is dropped when >50%%");
        line("of its area is covered by a tagged/lattice hit). latOnly/strOnly = single path. both =");
        line("emit every candidate of every contested region. streamPref = in every contested region");
        line("keep stream and DROP the lattice/tagged candidate. oracle = per-contested-region choice");
        line("of {lattice-only, stream-only, both} maximising THAT DOCUMENT's pooled F1, chosen WITH");
        line("ground truth. The oracle is a ceiling only and can never ship.");
        line("");
        line("  %-14s %7s %7s %7s %7s %7s %7s %5s", "config", "MACRO", "", "", "", "", "", "docs");
        line("  %-14s %7.4f", "baseline", macro(docs, d -> d.fBaseline));
        line("  %-14s %7.4f", "latOnly", macro(docs, d -> d.fLatOnly));
        line("  %-14s %7.4f", "strOnly", macro(docs, d -> d.fStrOnly));
        line("  %-14s %7.4f", "both", macro(docs, d -> d.fBoth));
        line("  %-14s %7.4f", "streamPref", macro(docs, d -> d.fStreamPref));
        line("  %-14s %7.4f", "ORACLE", macro(docs, d -> d.fOracle));
        line("");
        line("  ORACLE CEILING over baseline: %+.4f MACRO", macro(docs, d -> d.fOracle) - macro(docs, d -> d.fBaseline));
        line("  best FIXED policy over baseline: %+.4f MACRO (streamPref)",
                macro(docs, d -> d.fStreamPref) - macro(docs, d -> d.fBaseline));
        line("  'both' over baseline:            %+.4f MACRO",
                macro(docs, d -> d.fBoth) - macro(docs, d -> d.fBaseline));
        line("");

        int contested = 0, groupsTotal = 0, cntL = 0, cntS = 0, cntB = 0;
        for (Doc d : docs) {
            if (d.nGroups > 0) contested++;
            groupsTotal += d.nGroups;
            for (char c : d.oracleChoices.toCharArray()) {
                if (c == 'L') cntL++; else if (c == 'S') cntS++; else cntB++;
            }
        }
        line("ADDRESSABLE SET");
        line("  documents with >=1 CONTESTED region : %d / %d", contested, docs.size());
        line("  contested regions total             : %d", groupsTotal);
        line("  oracle picks  lattice-only=%d  stream-only=%d  both=%d", cntL, cntS, cntB);
        line("  (baseline always picks lattice-only, so stream-only+both = %d regions where the",
                cntS + cntB);
        line("   current default is oracle-suboptimal -- an upper bound on regions a rule could fix.)");
        line("");

        line("PER-DOCUMENT, only documents with a contested region (sorted by oracle-baseline gain)");
        line("  %-28s %-10s %2s  %8s %8s %8s %8s %8s %8s %s",
                "doc", "bucket", "G", "baseline", "latOnly", "strOnly", "both", "strPref", "ORACLE", "choices");
        List<Doc> c2 = new ArrayList<>(docs.stream().filter(d -> d.nGroups > 0).toList());
        c2.sort((a, b) -> Double.compare((b.fOracle - b.fBaseline), (a.fOracle - a.fBaseline)));
        for (Doc d : c2) {
            line("  %-28s %-10s %2d  %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %s",
                    d.id, d.bucket, d.nGroups, d.fBaseline, d.fLatOnly, d.fStrOnly,
                    d.fBoth, d.fStreamPref, d.fOracle, d.oracleChoices);
        }
        line("");
        line("SIGNALS at each contested region (extraction-time only; this is the design input)");
        for (Doc d : c2) line("  %-28s %s", d.id, d.signals);
        line("");
        List<Doc> bad = docs.stream().filter(d -> d.err != null).toList();
        line("documents with a measurement error: %d", bad.size());
        for (Doc d : bad) line("    %s : %s", d.id, d.err);

        Path p = Path.of("target/arb-oracle-report.md");
        Files.createDirectories(p.getParent());
        Files.writeString(p, "```\n" + out + "```\n");
        System.out.println("Report written to " + p.toAbsolutePath());
    }

    private static double macro(List<Doc> docs, java.util.function.ToDoubleFunction<Doc> f) {
        return docs.stream().mapToDouble(f).average().orElse(0.0);
    }
}
