// Diagnostic probe (gated -DtwoCol=true). The gate's `cols==2 && no numeric column` hard reject is
// the single largest source of the oracle gap (it deletes 7 correct corpus tables worth +0.0364 MACRO
// on its own). This probe asks whether ANY simple admission rule over the 2-column non-numeric
// population separates the corpus's real tables from the real-world corpus's look-alikes: it lists
// every 2-column non-numeric candidate on both sides with its graded score and row count, then
// sweeps (score, rows) rules and reports the resulting corpus recall vs real-world flag count.
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.GtDedup;
import com.oai.titanarum.bakeoff.TableScore;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class TwoColProbe {

    record Cand(String src, double graded, int rows, double cc, double viol, double prose,
                double prec, long matched, long det) {}

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("twoCol"), "set -DtwoCol=true");
        GutterFinder finder = new BreuelGutterFinder();

        // ---------- corpus side: every 2-col non-numeric candidate, with its would-be precision ----
        List<Cand> corpus = new ArrayList<>();
        StringBuilder notes = new StringBuilder();
        for (BakeOffHarness.ScoreUnit u : BakeOffHarness.buildScoringSet(notes).units) {
            List<GroundTruth.Table> kept = GtDedup.dedup(u.expected()).kept();
            List<TableScore.Relation> gt = new ArrayList<>();
            for (GroundTruth.Table t : kept) {
                gt.addAll(TableScore.buildOfficialRelations(
                        TableScore.gridCellsFromGroundTruth(t), false).relations());
            }
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                    List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, p - 1);
                    List<StreamTableExtractor.Candidate> cands = new ArrayList<>();
                    StreamTableExtractor.extractPage(p, glyphs, finder, c -> -1e9,
                            StreamTableExtractor.PRODUCTION_BAR, cands);
                    for (StreamTableExtractor.Candidate c : cands) {
                        if (c.hit == null || !"cols==2-nonnumeric".equals(c.grid.hardReject)) continue;
                        GateOracleHarness.Score s = GateOracleHarness.rescore(c.grid,
                                new GateOracleHarness.Variant("x", false, 0.0, false));
                        List<TableScore.Relation> det = TableScore.buildOfficialRelations(
                                MetricFixHarness.cellsOf(c.hit), false).relations();
                        TableScore.AdjResult r = TableScore.compareRelations(gt, det,
                                TableScore.Semantics.MULTISET);
                        double prec = r.detectedTotal() == 0 ? 0 : (double) r.matched() / r.detectedTotal();
                        corpus.add(new Cand(shortName(u.id()) + " p" + p, s.graded(), c.grid.nRows,
                                c.grid.tColConsistency, c.grid.tViolation, c.grid.tProse, prec,
                                r.matched(), r.detectedTotal()));
                    }
                }
            } catch (Throwable ignored) { }
        }

        // ---------- real-world side: every page-1 2-col non-numeric candidate -------------------
        List<Cand> real = new ArrayList<>();
        List<Path> files = pdfs(Path.of("/home/coz/Downloads/phishpdfs"), Integer.getInteger("twoColCap", 1600));
        for (Path p : files) {
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                if (doc.getNumberOfPages() < 1) continue;
                List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
                List<StreamTableExtractor.Candidate> cands = new ArrayList<>();
                StreamTableExtractor.extractPage(1, glyphs, finder, c -> -1e9,
                        StreamTableExtractor.PRODUCTION_BAR, cands);
                for (StreamTableExtractor.Candidate c : cands) {
                    if (c.hit == null || !"cols==2-nonnumeric".equals(c.grid.hardReject)) continue;
                    GateOracleHarness.Score s = GateOracleHarness.rescore(c.grid,
                            new GateOracleHarness.Variant("x", false, 0.0, false));
                    real.add(new Cand(p.getFileName().toString(), s.graded(), c.grid.nRows,
                            c.grid.tColConsistency, c.grid.tViolation, c.grid.tProse, -1, 0, 0));
                }
            } catch (Throwable ignored) { }
        }

        System.out.printf(Locale.ROOT, "real-world files scanned: %d%n", files.size());
        System.out.printf(Locale.ROOT, "corpus 2-col non-numeric candidates: %d%n", corpus.size());
        System.out.printf(Locale.ROOT, "real   2-col non-numeric candidates: %d%n", real.size());

        System.out.println("=== corpus 2-col candidates (graded desc) ===");
        corpus.sort((a, b) -> Double.compare(b.graded(), a.graded()));
        System.out.printf(Locale.ROOT, "  %-30s %7s %5s %6s %6s %6s %7s %7s %7s%n",
                "where", "graded", "rows", "cc", "viol", "prose", "prec", "match", "det");
        for (Cand c : corpus) {
            System.out.printf(Locale.ROOT, "  %-30s %7.3f %5d %6.3f %6.3f %6.3f %7.3f %7d %7d%n",
                    c.src(), c.graded(), c.rows(), c.cc(), c.viol(), c.prose(), c.prec(),
                    c.matched(), c.det());
        }

        System.out.println("=== real-world 2-col candidates above 0.50 (graded desc) ===");
        real.sort((a, b) -> Double.compare(b.graded(), a.graded()));
        int shown = 0;
        for (Cand c : real) {
            if (c.graded() < 0.50) break;
            System.out.printf(Locale.ROOT, "  %-46s %7.3f rows=%d cc=%.3f viol=%.3f prose=%.3f%n",
                    c.src(), c.graded(), c.rows(), c.cc(), c.viol(), c.prose());
            if (++shown >= 40) { System.out.println("  ..."); break; }
        }

        System.out.println("=== rule sweep: admit 2-col iff graded>=G and rows>=R ===");
        System.out.printf(Locale.ROOT, "  %6s %5s | %s | %s%n", "G", "R",
                "corpus admitted (of " + corpus.size() + ")", "real-world FILES flagged (of " + files.size() + ")");
        for (double g : new double[]{0.55, 0.60, 0.62, 0.65, 0.68, 0.70, 0.72, 0.75}) {
            for (int r : new int[]{3, 4, 5, 6, 8, 10}) {
                long ca = corpus.stream().filter(c -> c.graded() >= g && c.rows() >= r).count();
                long cgood = corpus.stream().filter(c -> c.graded() >= g && c.rows() >= r && c.prec() >= 0.5).count();
                long rf = real.stream().filter(c -> c.graded() >= g && c.rows() >= r)
                        .map(Cand::src).distinct().count();
                System.out.printf(Locale.ROOT, "  %6.2f %5d | admitted=%d (prec>=0.5: %d) | files=%d (%.4f)%n",
                        g, r, ca, cgood, rf, (double) rf / files.size());
            }
        }
    }

    private static String shortName(String id) {
        int i = id.lastIndexOf('/');
        return i < 0 ? id : id.substring(Math.max(0, id.lastIndexOf('/', i - 1) + 1));
    }

    private static List<Path> pdfs(Path root, int cap) throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        List<Path> all;
        try (java.util.stream.Stream<Path> s = Files.list(root)) {
            all = s.filter(Files::isRegularFile).sorted().toList();
        }
        List<Path> out = new ArrayList<>();
        for (Path p : all) {
            if (out.size() >= cap) break;
            try (InputStream in = Files.newInputStream(p)) {
                byte[] b = new byte[5];
                int n = in.read(b);
                if (n >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') out.add(p);
            } catch (IOException ignored) { }
        }
        return out;
    }
}
