// Pre-implementation CEILING / COST sweep (gated -DlatticeSweep=true). For each candidate
// suppression rule this prints, WITHOUT changing any production code:
//   (a) the file-level prose false-positive count over the 200-PDF sample, for lattice+tagged and
//       for the full arbitrated pipeline, when tables matching the rule are dropped post hoc;
//   (b) how many tables the rule would drop on the 77-PDF ICDAR scoring corpus, by method --
//       a rule that drops ZERO corpus tables cannot move corpus macro at all (proof, not estimate).
//
// IMPORTANT once the rules SHIPPED: this probe drops tables post hoc from whatever
// TableExtractor.extract already returns, so its "R0 none" row is the CURRENT behaviour, not the
// pre-change baseline. To reproduce the original 21/200 and 25/200 figures, disable
// MIN_TAGGED_RANK and MIN_LATTICE_TEXTFUL_COLUMNS in TableExtractor first.
//
// Physically under bakeoff/ but declares `package com.oai.titanarum;` (BaselineHarness convention).
//   mvn -q -o test -Dtest=LatticeFpSweep -DlatticeSweep=true
package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

class LatticeFpSweep {

    /** A candidate rule: returns true when the table should be SUPPRESSED. */
    record Rule(String name, Predicate<TableExtractor.TableHit> drop) {}

    private static int nonEmpty(TableExtractor.TableHit t) {
        int n = 0;
        for (TableExtractor.CellHit c : t.cells) {
            if (c.text != null && !c.text.trim().isEmpty()) n++;
        }
        return n;
    }

    private static int chars(TableExtractor.TableHit t) {
        int n = 0;
        for (TableExtractor.CellHit c : t.cells) {
            if (c.text != null) n += c.text.trim().length();
        }
        return n;
    }

    /** Fraction of the grid product actually carried by a CellRect/TD. */
    private static double occupancy(TableExtractor.TableHit t) {
        long prod = (long) t.rowCount * t.colCount;
        return prod == 0 ? 0 : t.cells.size() / (double) prod;
    }

    /** Distinct anchor columns carrying non-blank text. */
    private static int textfulCols(TableExtractor.TableHit t) {
        java.util.Set<Integer> cols = new java.util.LinkedHashSet<>();
        for (TableExtractor.CellHit c : t.cells) {
            if (c.text != null && !c.text.trim().isEmpty()) cols.add(c.col);
        }
        return cols.size();
    }

    private static double fillRatio(TableExtractor.TableHit t) {
        long prod = (long) t.rowCount * t.colCount;
        return prod == 0 ? 0 : nonEmpty(t) / (double) prod;
    }

    private static List<Rule> rules() {
        List<Rule> rs = new ArrayList<>();
        rs.add(new Rule("R0 none", t -> false));
        rs.add(new Rule("R1 tagged rank<2x2",
                t -> "tagged".equals(t.extractionMethod) && (t.rowCount < 2 || t.colCount < 2)));
        rs.add(new Rule("R3 lattice all-empty cells",
                t -> "lattice".equals(t.extractionMethod) && nonEmpty(t) == 0));
        rs.add(new Rule("R4 lattice occupancy<0.5",
                t -> "lattice".equals(t.extractionMethod) && occupancy(t) < 0.5));
        rs.add(new Rule("R5 lattice occupancy<0.75",
                t -> "lattice".equals(t.extractionMethod) && occupancy(t) < 0.75));
        rs.add(new Rule("R6 lattice fillRatio<0.5",
                t -> "lattice".equals(t.extractionMethod) && fillRatio(t) < 0.5));
        rs.add(new Rule("R13 lattice textful cols<2 (SHIPPED, textless exempt)",
                t -> "lattice".equals(t.extractionMethod) && textfulCols(t) == 1));
        rs.add(new Rule("R14 R1+R13 (SHIPPED)", t -> ("tagged".equals(t.extractionMethod)
                        && (t.rowCount < 2 || t.colCount < 2))
                        || ("lattice".equals(t.extractionMethod) && textfulCols(t) == 1)));
        rs.add(new Rule("R7 lattice nonEmpty<4",
                t -> "lattice".equals(t.extractionMethod) && nonEmpty(t) < 4));
        rs.add(new Rule("R9 R1+R3", t -> ("tagged".equals(t.extractionMethod)
                        && (t.rowCount < 2 || t.colCount < 2))
                        || ("lattice".equals(t.extractionMethod) && nonEmpty(t) == 0)));
        rs.add(new Rule("R10 R1+R4", t -> ("tagged".equals(t.extractionMethod)
                        && (t.rowCount < 2 || t.colCount < 2))
                        || ("lattice".equals(t.extractionMethod) && occupancy(t) < 0.5)));
        rs.add(new Rule("R11 R1+R7", t -> ("tagged".equals(t.extractionMethod)
                        && (t.rowCount < 2 || t.colCount < 2))
                        || ("lattice".equals(t.extractionMethod) && nonEmpty(t) < 4)));
        rs.add(new Rule("R12 R1+R4+R7", t -> ("tagged".equals(t.extractionMethod)
                        && (t.rowCount < 2 || t.colCount < 2))
                        || ("lattice".equals(t.extractionMethod)
                            && (occupancy(t) < 0.5 || nonEmpty(t) < 4))));
        return rs;
    }

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("latticeSweep"), "set -DlatticeSweep=true to run");
        List<Rule> rules = rules();

        // ---------------------------------------------------------------- prose sample
        List<Path> prose = BakeOffHarness.sampleProsePdfs();
        // per file: the lattice+tagged hits and the arbitrated hits
        List<List<TableExtractor.TableHit>> ltPerFile = new ArrayList<>();
        List<List<TableExtractor.TableHit>> arbPerFile = new ArrayList<>();
        GutterFinder breuel = new BreuelGutterFinder();
        for (Path p : prose) {
            List<TableExtractor.TableHit> lt = new ArrayList<>();
            List<TableExtractor.TableHit> arb = new ArrayList<>();
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                if (doc.getNumberOfPages() >= 1) {
                    List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
                    Map<Integer, List<TextPosition>> byPage = new LinkedHashMap<>();
                    byPage.put(1, glyphs);
                    lt.addAll(TableExtractor.extract(doc, List.of(1), byPage).tables);
                    List<TableExtractor.TableHit> str =
                            StreamTableExtractor.extractPage(1, glyphs, breuel);
                    try {
                        arb.addAll(TableExtractor.arbitrate(lt, str));
                    } catch (TableExtractor.RulingOverflowException e) {
                        arb.addAll(lt);
                        for (TableExtractor.TableHit s : str) {
                            if (!MetricFixHarness.overlapsSubstantially(s, lt)) arb.add(s);
                        }
                    }
                }
            } catch (Throwable ignored) { }
            ltPerFile.add(lt);
            arbPerFile.add(arb);
        }

        System.out.printf(Locale.ROOT, "%n=== PROSE FP (file-level, page 1, %d PDFs) ===%n", prose.size());
        System.out.printf(Locale.ROOT, "  %-42s %10s %10s%n", "rule", "lat+tag", "full-arb");
        for (Rule r : rules) {
            int lt = 0, arb = 0;
            for (int i = 0; i < prose.size(); i++) {
                if (ltPerFile.get(i).stream().anyMatch(t -> !r.drop().test(t))) lt++;
                if (arbPerFile.get(i).stream().anyMatch(t -> !r.drop().test(t))) arb++;
            }
            System.out.printf(Locale.ROOT, "  %-42s %4d/%d %.4f %4d/%d %.4f%n", r.name(),
                    lt, prose.size(), lt / (double) prose.size(),
                    arb, prose.size(), arb / (double) prose.size());
        }

        // ---------------------------------------------------------------- ICDAR corpus cost
        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        Map<String, int[]> dropped = new TreeMap<>();   // rule -> {tagged, lattice}
        int totalTagged = 0, totalLattice = 0;
        List<String> droppedIds = new ArrayList<>();
        for (BakeOffHarness.ScoreUnit u : corpus.units) {
            List<TableExtractor.TableHit> lt = new ArrayList<>();
            try (PDDocument doc = Loader.loadPDF(u.pdf().toFile())) {
                List<Integer> pages = new ArrayList<>();
                Map<Integer, List<TextPosition>> byPage = new LinkedHashMap<>();
                for (int i = 0; i < doc.getNumberOfPages(); i++) {
                    pages.add(i + 1);
                    byPage.put(i + 1, TableTestPdfs.harvestGlyphs(doc, i));
                }
                lt.addAll(TableExtractor.extract(doc, pages, byPage).tables);
            } catch (Throwable t) {
                System.out.println("  corpus load error " + u.id() + ": " + t);
            }
            for (TableExtractor.TableHit t : lt) {
                if ("tagged".equals(t.extractionMethod)) totalTagged++;
                else totalLattice++;
            }
            for (Rule r : rules) {
                int[] d = dropped.computeIfAbsent(r.name(), k -> new int[2]);
                for (TableExtractor.TableHit t : lt) {
                    if (r.drop().test(t)) {
                        if ("tagged".equals(t.extractionMethod)) d[0]++; else d[1]++;
                        if (r.name().startsWith("R14")) {
                            droppedIds.add(String.format(Locale.ROOT,
                                    "%s %s %dx%d cells=%d nonEmpty=%d occ=%.2f",
                                    u.id(), t.extractionMethod, t.rowCount, t.colCount,
                                    t.cells.size(), nonEmpty(t), occupancy(t)));
                        }
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT,
                "%n=== ICDAR CORPUS COST (%d PDFs; total tagged=%d lattice=%d tables) ===%n",
                corpus.units.size(), totalTagged, totalLattice);
        System.out.printf(Locale.ROOT, "  %-42s %14s %14s%n", "rule", "tagged dropped", "lattice dropped");
        for (Rule r : rules) {
            int[] d = dropped.get(r.name());
            System.out.printf(Locale.ROOT, "  %-42s %14d %14d%n", r.name(), d[0], d[1]);
        }
        System.out.println("=== tables the SHIPPED rule pair would drop on the corpus ===");
        for (String s : droppedIds) System.out.println("   " + s);
    }
}
