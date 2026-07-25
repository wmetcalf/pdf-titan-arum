// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/ (matching
// BakeOffHarness/RescopeHarness's own convention -- see their headers), but declares `package
// com.oai.titanarum;` because it needs direct access to package-private production types
// (TableExtractor, TableExtractor.TableHit, StreamTableExtractor, GutterFinder and the four finder
// implementations) plus the package-private test helper TableTestPdfs.harvestGlyphs. GroundTruth and
// TableScore are public and imported normally from com.oai.titanarum.bakeoff below.
//
// PURPOSE (metric correction + region-given scoring). This harness changes NOTHING about extraction
// -- no file under src/main is touched by the change that added it. It is read-only measurement code
// that re-baselines every configuration under a CORRECTED adjacency metric and adds a REGION-GIVEN
// scoring mode, so our numbers can be compared to the published ICDAR 2013 figures on the same
// footing. What it fixes / adds, and why each matters, is documented at the method that implements
// it; the short version:
//
//   1. Relation generation now follows the OFFICIAL ICDAR 2013 definition (cell identity with
//      spans, parallel-link dedup, blank counting) instead of being derived from an expanded text
//      grid. The old grid derivation charged us for an ENCODING difference: GroundTruth repeats a
//      spanning cell's text into every covered position, while TableHit#rows puts the text on the
//      span anchor and blanks the rest, so ground truth carried self-relations ("A right-of A")
//      that a perfect extraction could never produce. See TableScore#buildOfficialRelations.
//   2. Multiset (bag) comparison, which TableScore has always used, is now also measurable against
//      the SET reading so the size of that bias is reported rather than asserted.
//   3. The blank-skip count (noBlanks) is measured BOTH in and out of relation identity.
//   4. REGION-GIVEN mode restricts scoring to each ground-truth table's own region (union of its
//      ICDAR cell bounding boxes, y-flipped into our top-left-origin space), which is the scope the
//      published "structure only" column (Nurminen 0.9460 etc.) was measured in.
//   5. MACRO (per-document mean) is reported FIRST everywhere, because the published ICDAR figures
//      are per-document averages.
//
// Gated by -DmetricFix=true AND named so Surefire's default includes never discover it. Run:
//   mvn -q -o test -Dtest=MetricFixHarness -DmetricFix=true
package com.oai.titanarum;

import com.oai.titanarum.bakeoff.GroundTruth;
import com.oai.titanarum.bakeoff.TableScore;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

class MetricFixHarness {

    /** Same stream-vs-tagged/lattice overlap-drop threshold and OUTER-BBOX approximation
     *  RescopeHarness uses, kept identical so the full-pipeline configuration measured here is the
     *  same combination measured there (see RescopeHarness's class header for why it is an
     *  approximation of production's private per-cell-footprint rule, and in which direction). */
    private static final float STREAM_DROP_OVERLAP_THRESHOLD = 0.5f;

    /** Slack, in points, added around a ground-truth region before testing whether a detected cell
     *  (or a glyph) belongs to it. Ground-truth boxes are tight around GLYPH ink; our cell boxes are
     *  ruling-to-ruling or line-band rectangles that routinely stick out by a point or two, and a
     *  cell whose centre lands a hair outside a tight ink box is still unambiguously that table's
     *  cell. Centre-based membership plus a small pad is deliberately chosen over any
     *  area-overlap rule so that one oversized cell cannot drag a whole table into a region. */
    private static final float REGION_PAD = 2f;

    // ---------------------------------------------------------------------------- keys / variants

    // Metric variants. "official*" use TableScore#buildOfficialRelations (cell identity, spans,
    // parallel-link dedup); "legacy*" use the pre-existing grid derivation, kept so every number
    // that moves can be attributed to the metric change rather than to anything else.
    private static final String V_OFFICIAL      = "official";      // multiset, noBlanks NOT in identity
    private static final String V_OFFICIAL_NB   = "official+nb";   // multiset, noBlanks IN identity
    private static final String V_OFFICIAL_SET  = "official-SET";  // set semantics (bias probe only)
    private static final String V_LEGACY        = "legacy-grid";    // multiset, old grid derivation
    private static final String V_LEGACY_SET    = "legacy-grid-SET";
    private static final List<String> VARIANTS = List.of(
            V_OFFICIAL, V_OFFICIAL_NB, V_OFFICIAL_SET, V_LEGACY, V_LEGACY_SET);

    private static final String M_E2E    = "e2e";           // end-to-end: we must find the table too
    private static final String M_REGION = "region-given";  // region handed to us, structure only
    private static final String M_REGION_RERUN = "region-given-rerun"; // stream re-run on region glyphs

    private static final String C_FULL   = "full(tagged+lattice+stream)";
    private static final String C_LT     = "lattice+tagged";
    private static final String C_STREAM = "stream";
    private static final List<String> FINDERS = List.of("breuel", "gapvote", "alignedge", "occupancy");

    private static String key(String config, String mode, String variant) {
        return config + " | " + mode + " | " + variant;
    }

    // ------------------------------------------------------------------------------ accumulators

    /** Micro sums plus the per-document F1 list that MACRO is the mean of. Macro is the primary
     *  aggregation (published ICDAR figures are per-document averages); micro is reported second. */
    private static final class Acc {
        long matched, detected, gt;
        final List<Double> perDocF1 = new ArrayList<>();
        int docs;          // documents contributing at all
        int covered;       // documents where the configuration produced >=1 candidate cell
        int scoredTables;  // ground-truth tables that got a candidate (region-given) / pairing (e2e)

        void addDoc(long m, long d, long g, boolean cov, int tables) {
            matched += m; detected += d; gt += g;
            double p = d == 0 ? 0.0 : (double) m / d;
            double r = g == 0 ? 0.0 : (double) m / g;
            perDocF1.add(m == 0 ? 0.0 : 2 * p * r / (p + r));
            docs++;
            if (cov) covered++;
            scoredTables += tables;
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

    /** One document's (matched, detected, gt) for one key, before it is folded into an {@link Acc}. */
    private static final class Tally {
        long matched, detected, gt;
        int tables;
        boolean covered;
        void add(TableScore.AdjResult r) { matched += r.matched(); detected += r.detectedTotal(); gt += r.gtTotal(); }
        void addGtOnly(int n) { gt += n; }
        void addDetOnly(int n) { detected += n; }
    }

    // --------------------------------------------------------------------------- ground-truth geom

    /** A ground-truth table's region on ONE page, already converted to OUR coordinate space
     *  (top-left origin, y increasing downward, cropbox-relative -- the space
     *  {@code TextPosition#getXDirAdj/getYDirAdj} and {@code TableHit#bbox} both live in). */
    private record Region(int page, float x0, float y0, float x1, float y1) {
        boolean containsCentre(float cx, float cy) {
            return cx >= x0 - REGION_PAD && cx <= x1 + REGION_PAD
                    && cy >= y0 - REGION_PAD && cy <= y1 + REGION_PAD;
        }
    }

    /**
     * Derives a ground-truth table's region(s) from the union of its cells' ICDAR bounding boxes,
     * one region per page the table declares cells on (an ICDAR table may be annotated across
     * several {@code <region page=...>} blocks, including on different pages -- a continued table).
     *
     * <p><b>The y-axis origin difference is the whole subtlety here.</b> ICDAR boxes are PDF
     * user-space points with the origin at the page's BOTTOM-left and y increasing UPWARD, so a
     * cell's {@code y2} is its TOP. Our side is top-left origin with y increasing DOWNWARD and is
     * measured relative to the CROP box (that is what PDFBox's text stripper reports). The
     * conversion is therefore {@code ourY = cropBox.upperRightY - icdarY} (which also flips which of
     * y1/y2 is the top) and {@code ourX = icdarX - cropBox.lowerLeftX}. Getting this backwards does
     * not produce a subtly-worse score, it produces a region in the wrong half of the page and a
     * near-zero one; {@link #printCoordinateSanityCheck} measures it against the alternative
     * (no-flip) reading on real data so the convention is verified, not assumed.
     */
    private static List<Region> regionsOf(GroundTruth.Table table, Map<Integer, PDRectangle> cropByPage) {
        Map<Integer, float[]> unionByPage = new LinkedHashMap<>(); // page -> {x0,y0,x1,y1} ours
        for (GroundTruth.Cell c : table.cells()) {
            if (!c.hasBox() || c.page() <= 0) continue;
            PDRectangle crop = cropByPage.get(c.page());
            if (crop == null) continue;
            float ourX0 = c.x1() - crop.getLowerLeftX();
            float ourX1 = c.x2() - crop.getLowerLeftX();
            float ourY0 = crop.getUpperRightY() - c.y2();  // ICDAR top    -> our smaller y
            float ourY1 = crop.getUpperRightY() - c.y1();  // ICDAR bottom -> our larger y
            float[] u = unionByPage.get(c.page());
            if (u == null) {
                unionByPage.put(c.page(), new float[]{ourX0, ourY0, ourX1, ourY1});
            } else {
                u[0] = Math.min(u[0], ourX0);
                u[1] = Math.min(u[1], ourY0);
                u[2] = Math.max(u[2], ourX1);
                u[3] = Math.max(u[3], ourY1);
            }
        }
        List<Region> out = new ArrayList<>();
        for (Map.Entry<Integer, float[]> e : unionByPage.entrySet()) {
            float[] u = e.getValue();
            out.add(new Region(e.getKey(), u[0], u[1], u[2], u[3]));
        }
        out.sort(Comparator.comparingInt(Region::page));
        return out;
    }

    // ----------------------------------------------------------------------------- cell plumbing

    /** A detected table's cells as span-carrying {@link TableScore.GridCell}s. Falls back to 1x1
     *  cells derived from the text grid if a hit carries no cell list (no production path does, but
     *  the metric must not depend on that). */
    private static List<TableScore.GridCell> cellsOf(TableExtractor.TableHit h) {
        if (h.cells == null || h.cells.isEmpty()) {
            return TableScore.gridCellsFromRows(h.rows);
        }
        List<TableScore.GridCell> out = new ArrayList<>(h.cells.size());
        for (TableExtractor.CellHit c : h.cells) {
            int rs = Math.max(1, c.rowSpan);
            int cs = Math.max(1, c.colSpan);
            out.add(new TableScore.GridCell(c.row, c.col, c.row + rs - 1, c.col + cs - 1,
                    c.text == null ? "" : c.text));
        }
        return out;
    }

    /**
     * Re-indexes a cropped/merged cell collection onto a dense 0-based grid, preserving order and
     * spans. Cropping a table to a region leaves gaps in the row/column numbering; those gaps are
     * HOLES, and a hole counts as a skipped blank in the official relation builder, so leaving them
     * in would fabricate blank counts that the ground truth does not have. Re-indexing removes that
     * artifact. (It cannot change which cells are adjacent to which -- only how far apart their
     * indices are.)
     */
    private static List<TableScore.GridCell> denseReindex(List<TableScore.GridCell> cells) {
        Set<Integer> rowsUsed = new java.util.TreeSet<>();
        Set<Integer> colsUsed = new java.util.TreeSet<>();
        for (TableScore.GridCell c : cells) {
            for (int r = Math.min(c.startRow(), c.endRow()); r <= Math.max(c.startRow(), c.endRow()); r++) {
                rowsUsed.add(r);
            }
            for (int cc = Math.min(c.startCol(), c.endCol()); cc <= Math.max(c.startCol(), c.endCol()); cc++) {
                colsUsed.add(cc);
            }
        }
        Map<Integer, Integer> rowMap = new LinkedHashMap<>();
        int i = 0;
        for (int r : rowsUsed) rowMap.put(r, i++);
        Map<Integer, Integer> colMap = new LinkedHashMap<>();
        i = 0;
        for (int c : colsUsed) colMap.put(c, i++);

        List<TableScore.GridCell> out = new ArrayList<>(cells.size());
        for (TableScore.GridCell c : cells) {
            out.add(new TableScore.GridCell(
                    rowMap.get(Math.min(c.startRow(), c.endRow())),
                    colMap.get(Math.min(c.startCol(), c.endCol())),
                    rowMap.get(Math.max(c.startRow(), c.endRow())),
                    colMap.get(Math.max(c.startCol(), c.endCol())),
                    c.text()));
        }
        return out;
    }

    /**
     * REGION-GIVEN candidate construction: every cell, from every hit in the configuration, whose
     * centre lies inside one of the ground-truth table's regions, stacked into one grid.
     *
     * <p>Hits are stacked in reading order (page, then top edge, then left edge) with each hit's row
     * indices offset past the previous hit's, and each hit's OWN column indices kept. Stacking is
     * the correct region-given behaviour: the region is given, so everything we produced inside it
     * is our answer for that one table -- including the common case where we split one real table
     * into several hits, which is exactly the table-FINDING failure that region-given scoring is
     * meant to stop charging us for. Columns are kept rather than offset because a vertically-split
     * table's fragments share a column layout; offsetting them would guarantee zero column
     * alignment. Where several hits genuinely overlap the same region (e.g. tagged and lattice both
     * finding it), stacking duplicates content and costs PRECISION -- reported, not hidden.
     */
    private static List<TableScore.GridCell> regionGivenCells(List<TableExtractor.TableHit> hits,
                                                               List<Region> regions,
                                                               int[] fragmentCountOut) {
        if (regions.isEmpty()) return List.of();
        record Frag(TableExtractor.TableHit hit, int page, float top, float left,
                     List<TableScore.GridCell> cells) {}
        List<Frag> frags = new ArrayList<>();
        for (Region reg : regions) {
            for (TableExtractor.TableHit h : hits) {
                if (h.page != reg.page()) continue;
                List<TableScore.GridCell> kept = new ArrayList<>();
                if (h.cells != null) {
                    for (TableExtractor.CellHit c : h.cells) {
                        if (c.bbox == null) continue;
                        float cx = (c.bbox[0] + c.bbox[2]) / 2f;
                        float cy = (c.bbox[1] + c.bbox[3]) / 2f;
                        if (!reg.containsCentre(cx, cy)) continue;
                        int rs = Math.max(1, c.rowSpan);
                        int cs = Math.max(1, c.colSpan);
                        kept.add(new TableScore.GridCell(c.row, c.col, c.row + rs - 1, c.col + cs - 1,
                                c.text == null ? "" : c.text));
                    }
                }
                if (kept.isEmpty()) continue;
                float top = h.bbox == null ? 0f : h.bbox[1];
                float left = h.bbox == null ? 0f : h.bbox[0];
                frags.add(new Frag(h, reg.page(), top, left, kept));
            }
        }
        if (fragmentCountOut != null) fragmentCountOut[0] = frags.size();
        if (frags.isEmpty()) return List.of();
        frags.sort(Comparator.<Frag>comparingInt(Frag::page)
                .thenComparingDouble(Frag::top).thenComparingDouble(Frag::left));

        List<TableScore.GridCell> merged = new ArrayList<>();
        int rowOffset = 0;
        for (Frag f : frags) {
            int maxRow = 0;
            for (TableScore.GridCell c : f.cells()) {
                merged.add(new TableScore.GridCell(c.startRow() + rowOffset, c.startCol(),
                        c.endRow() + rowOffset, c.endCol(), c.text()));
                maxRow = Math.max(maxRow, Math.max(c.startRow(), c.endRow()));
            }
            rowOffset += maxRow + 1;
        }
        return denseReindex(merged);
    }

    /** Stacks whole hits (no region filter) -- used by the stream region-RERUN mode, where the
     *  input glyphs were already restricted to the region, so every produced cell is in-region. */
    private static List<TableScore.GridCell> stackAll(List<TableExtractor.TableHit> hits) {
        if (hits.isEmpty()) return List.of();
        List<TableExtractor.TableHit> ordered = new ArrayList<>(hits);
        ordered.sort(Comparator.<TableExtractor.TableHit>comparingInt(h -> h.page)
                .thenComparingDouble(h -> h.bbox == null ? 0 : h.bbox[1])
                .thenComparingDouble(h -> h.bbox == null ? 0 : h.bbox[0]));
        List<TableScore.GridCell> merged = new ArrayList<>();
        int rowOffset = 0;
        for (TableExtractor.TableHit h : ordered) {
            int maxRow = 0;
            for (TableScore.GridCell c : cellsOf(h)) {
                merged.add(new TableScore.GridCell(c.startRow() + rowOffset, c.startCol(),
                        c.endRow() + rowOffset, c.endCol(), c.text()));
                maxRow = Math.max(maxRow, Math.max(c.startRow(), c.endRow()));
            }
            rowOffset += maxRow + 1;
        }
        return merged.isEmpty() ? List.of() : denseReindex(merged);
    }

    // ------------------------------------------------------------------------------- scoring core

    /** Scores one (gt cells, detected cells) pair under one variant. */
    private static TableScore.AdjResult scoreVariant(String variant,
                                                      GroundTruth.Table gt, List<TableScore.GridCell> gtCells,
                                                      List<List<String>> detRows, List<TableScore.GridCell> detCells) {
        switch (variant) {
            case V_OFFICIAL:     return TableScore.scoreAdjacencyOfficial(gtCells, detCells, false, TableScore.Semantics.MULTISET);
            case V_OFFICIAL_NB:  return TableScore.scoreAdjacencyOfficial(gtCells, detCells, true,  TableScore.Semantics.MULTISET);
            case V_OFFICIAL_SET: return TableScore.scoreAdjacencyOfficial(gtCells, detCells, false, TableScore.Semantics.SET);
            case V_LEGACY:       return TableScore.scoreAdjacency(gt, detRows, TableScore.Semantics.MULTISET);
            case V_LEGACY_SET:   return TableScore.scoreAdjacency(gt, detRows, TableScore.Semantics.SET);
            default: throw new IllegalArgumentException(variant);
        }
    }

    private static int gtOnlyCount(String variant, GroundTruth.Table gt, List<TableScore.GridCell> gtCells) {
        switch (variant) {
            case V_OFFICIAL:     return TableScore.officialRelationCount(gtCells, false, TableScore.Semantics.MULTISET);
            case V_OFFICIAL_NB:  return TableScore.officialRelationCount(gtCells, true,  TableScore.Semantics.MULTISET);
            case V_OFFICIAL_SET: return TableScore.officialRelationCount(gtCells, false, TableScore.Semantics.SET);
            case V_LEGACY:       return TableScore.relationCount(gt.rows());
            case V_LEGACY_SET:   return TableScore.scoreAdjacency(gt, List.of(), TableScore.Semantics.SET).gtTotal();
            default: throw new IllegalArgumentException(variant);
        }
    }

    private static int detOnlyCount(String variant, TableExtractor.TableHit h) {
        switch (variant) {
            case V_OFFICIAL:     return TableScore.officialRelationCount(cellsOf(h), false, TableScore.Semantics.MULTISET);
            case V_OFFICIAL_NB:  return TableScore.officialRelationCount(cellsOf(h), true,  TableScore.Semantics.MULTISET);
            case V_OFFICIAL_SET: return TableScore.officialRelationCount(cellsOf(h), false, TableScore.Semantics.SET);
            case V_LEGACY:       return TableScore.relationCount(h.rows);
            case V_LEGACY_SET:   return TableScore.scoreAdjacency(new GroundTruth.Table(List.of()), h.rows,
                                          TableScore.Semantics.SET).detectedTotal();
            default: throw new IllegalArgumentException(variant);
        }
    }

    /**
     * END-TO-END scoring of one document for one configuration, for every variant at once.
     *
     * <p>Pairing policy is UNCHANGED from BakeOffHarness/RescopeHarness: each expected table is
     * greedily paired with whichever remaining hit maximises EXACT-CELL F1, and every adjacency
     * variant is then scored against that same pairing. Keeping the pairing decision on the old
     * exact-cell metric is deliberate -- it means any movement in the reported numbers is
     * attributable to the metric change alone and not to a different table correspondence.
     */
    private static Map<String, Tally> scoreEndToEnd(List<TableExtractor.TableHit> hits,
                                                      List<GroundTruth.Table> expected) {
        Map<String, Tally> out = new LinkedHashMap<>();
        for (String v : VARIANTS) out.put(v, new Tally());

        List<TableExtractor.TableHit> available = new ArrayList<>(hits);
        for (GroundTruth.Table exp : expected) {
            List<TableScore.GridCell> gtCells = TableScore.gridCellsFromGroundTruth(exp);
            if (available.isEmpty()) {
                for (String v : VARIANTS) out.get(v).addGtOnly(gtOnlyCount(v, exp, gtCells));
                continue;
            }
            TableExtractor.TableHit best = null;
            double bestF1 = -1;
            for (TableExtractor.TableHit h : available) {
                double f1 = TableScore.score(exp, h.rows).f1();
                if (f1 > bestF1) { bestF1 = f1; best = h; }
            }
            available.remove(best);
            List<TableScore.GridCell> detCells = cellsOf(best);
            for (String v : VARIANTS) {
                Tally t = out.get(v);
                t.add(scoreVariant(v, exp, gtCells, best.rows, detCells));
                t.tables++;
            }
        }
        for (TableExtractor.TableHit h : available) {
            for (String v : VARIANTS) out.get(v).addDetOnly(detOnlyCount(v, h));
        }
        boolean covered = !hits.isEmpty();
        for (Tally t : out.values()) t.covered = covered;
        return out;
    }

    /**
     * REGION-GIVEN scoring of one document for one configuration.
     *
     * <p>No pairing and no spurious-table penalty: each ground-truth table is scored against
     * whatever the configuration produced INSIDE that table's own region, and anything produced
     * outside every region is ignored entirely. That is precisely the ICDAR 2013 structure
     * sub-task's scope -- participants were handed the table region and graded only on the structure
     * they recovered within it -- and is what makes the resulting number comparable to the published
     * region-given column (Nurminen 0.9460 and friends) rather than to the end-to-end column.
     *
     * <p>A ground-truth table with NO usable geometry (no cell boxes, or no page attribute -- CSV
     * ground truth, always) cannot be region-scored and is EXCLUDED from this mode rather than
     * scored as a miss; the number of such tables is reported so the exclusion is visible.
     * A ground-truth table that HAS geometry but inside whose region we produced nothing still
     * scores zero -- being handed the region does not excuse finding no structure in it.
     */
    private static Map<String, Tally> scoreRegionGiven(List<TableExtractor.TableHit> hits,
                                                        List<GroundTruth.Table> expected,
                                                        Map<Integer, PDRectangle> cropByPage,
                                                        int[] skippedNoGeometry,
                                                        DocResult stats) {
        Map<String, Tally> out = new LinkedHashMap<>();
        for (String v : VARIANTS) out.put(v, new Tally());
        boolean anyCovered = false;

        for (GroundTruth.Table exp : expected) {
            List<Region> regions = regionsOf(exp, cropByPage);
            if (regions.isEmpty()) {
                if (skippedNoGeometry != null) skippedNoGeometry[0]++;
                continue;
            }
            List<TableScore.GridCell> gtCells = TableScore.gridCellsFromGroundTruth(exp);
            int[] frags = new int[1];
            List<TableScore.GridCell> detCells = regionGivenCells(hits, regions, frags);
            if (stats != null) {
                if (regions.size() > 1) stats.regionMultiPageTables++;
                if (frags[0] == 0) stats.regionTablesWithNoCandidate++;
                else if (frags[0] == 1) stats.regionTablesFromOneHit++;
                else stats.regionTablesFromManyHits++;
            }
            List<List<String>> detRows = rowsFromCells(detCells);
            if (!detCells.isEmpty()) anyCovered = true;
            for (String v : VARIANTS) {
                Tally t = out.get(v);
                if (detCells.isEmpty()) {
                    t.addGtOnly(gtOnlyCount(v, exp, gtCells));
                } else {
                    t.add(scoreVariant(v, exp, gtCells, detRows, detCells));
                }
                t.tables++;
            }
        }
        for (Tally t : out.values()) t.covered = anyCovered;
        return out;
    }

    /** Renders span-carrying cells back into the expanded text grid the LEGACY variants need, using
     *  the same "repeat the text across the span" expansion GroundTruth applies to ICDAR truth, so
     *  the legacy comparison stays apples-to-apples on both sides. */
    private static List<List<String>> rowsFromCells(List<TableScore.GridCell> cells) {
        int maxRow = -1, maxCol = -1;
        for (TableScore.GridCell c : cells) {
            maxRow = Math.max(maxRow, Math.max(c.startRow(), c.endRow()));
            maxCol = Math.max(maxCol, Math.max(c.startCol(), c.endCol()));
        }
        if (maxRow < 0) return List.of();
        List<List<String>> rows = new ArrayList<>(maxRow + 1);
        for (int r = 0; r <= maxRow; r++) {
            List<String> row = new ArrayList<>(maxCol + 1);
            for (int c = 0; c <= maxCol; c++) row.add("");
            rows.add(row);
        }
        for (TableScore.GridCell c : cells) {
            for (int r = Math.min(c.startRow(), c.endRow()); r <= Math.max(c.startRow(), c.endRow()); r++) {
                for (int cc = Math.min(c.startCol(), c.endCol()); cc <= Math.max(c.startCol(), c.endCol()); cc++) {
                    rows.get(r).set(cc, c.text());
                }
            }
        }
        return rows;
    }

    // ---------------------------------------------------------------------------- per-document run

    /** Everything measured for one corpus PDF. Glyphs are NOT retained -- every score that needs
     *  them is computed while the document is open, so peak memory stays bounded across 77 PDFs. */
    private static final class DocResult {
        String id;
        String source;   // icdar-us | icdar-eu | csv
        String bucket;   // lattice | tagged | both | neither
        int taggedCount, latticeCount;
        int rawStreamHits, keptStreamHits;
        int gtTables, gtTablesWithGeometry;
        String error;
        /** key -> per-document tally, for every (config, mode, variant) this document contributes to. */
        final Map<String, Tally> tallies = new LinkedHashMap<>();
        /** Ground-truth relation inventory contributions (ICDAR documents only). */
        int gtRelTotal, gtRelRight, gtRelDown, gtRelZeroBlank, gtRelParallelSuppressed, gtRelDistinct;
        int gtTablesWithDuplicateRelation;
        /** Coordinate-convention sanity: for each non-blank GT cell, whether the glyphs inside its
         *  converted box actually SPELL that cell's text -- measured under the correct y-flip and
         *  under the deliberately-wrong no-flip control. */
        /** Region-given bookkeeping, for the FULL configuration only: how many ground-truth tables
         *  had their region-given candidate assembled from one hit vs several (several = we split
         *  the table and region-given mode forgave it), and how many had no candidate at all. */
        int regionTablesFromOneHit, regionTablesFromManyHits, regionTablesWithNoCandidate;
        int regionMultiPageTables;
        int geomCellsChecked;
        int geomFlipAnyGlyph, geomFlipTextEqual, geomFlipTextOverlap;
        int geomUnflipAnyGlyph, geomUnflipTextEqual, geomUnflipTextOverlap;
    }

    private static DocResult measure(BakeOffHarness.ScoreUnit unit, List<GutterFinder> finders) {
        DocResult d = new DocResult();
        d.id = unit.id();
        d.source = unit.id().contains("competition-dataset-us") ? "icdar-us"
                : unit.id().contains("competition-dataset-eu") ? "icdar-eu" : "csv";
        d.gtTables = unit.expected().size();

        try (PDDocument doc = Loader.loadPDF(unit.pdf().toFile())) {
            int pages = doc.getNumberOfPages();
            List<Integer> pageList = new ArrayList<>();
            Map<Integer, List<TextPosition>> glyphs = new LinkedHashMap<>();
            Map<Integer, PDRectangle> cropByPage = new LinkedHashMap<>();
            for (int p = 1; p <= pages; p++) {
                pageList.add(p);
                glyphs.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
                PDPage page = doc.getPage(p - 1);
                cropByPage.put(p, page.getCropBox());
            }

            // ---- tagged + lattice (production TableExtractor.extract, untouched) ----
            List<TableExtractor.TableHit> taggedLattice = new ArrayList<>();
            try {
                taggedLattice.addAll(TableExtractor.extract(doc, pageList, glyphs).tables);
            } catch (Throwable t) {
                d.error = "extract: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            }
            d.taggedCount = (int) taggedLattice.stream().filter(h -> "tagged".equals(h.extractionMethod)).count();
            d.latticeCount = (int) taggedLattice.stream().filter(h -> "lattice".equals(h.extractionMethod)).count();
            d.bucket = d.taggedCount > 0 && d.latticeCount > 0 ? "both"
                    : d.taggedCount > 0 ? "tagged" : d.latticeCount > 0 ? "lattice" : "neither";

            // ---- stream, per finder ----
            Map<String, List<TableExtractor.TableHit>> streamByFinder = new LinkedHashMap<>();
            for (GutterFinder f : finders) {
                List<TableExtractor.TableHit> hits = new ArrayList<>();
                try {
                    for (int p : pageList) hits.addAll(StreamTableExtractor.extractPage(p, glyphs.get(p), f));
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
                if (!overlapsSubstantially(s, taggedLattice)) keptStream.add(s);
            }
            d.keptStreamHits = keptStream.size();

            List<TableExtractor.TableHit> full = new ArrayList<>(taggedLattice);
            full.addAll(keptStream);

            // ---- ground-truth geometry availability + relation inventory ----
            int[] skipped = new int[1];
            for (GroundTruth.Table exp : unit.expected()) {
                if (!regionsOf(exp, cropByPage).isEmpty()) d.gtTablesWithGeometry++;
                TableScore.RelationBuild b = TableScore.buildOfficialRelations(
                        TableScore.gridCellsFromGroundTruth(exp), false);
                d.gtRelTotal += b.relations().size();
                d.gtRelRight += b.rightCount();
                d.gtRelDown += b.downCount();
                d.gtRelParallelSuppressed += b.parallelLinksSuppressed();
                TableScore.RelationBuild withNb = TableScore.buildOfficialRelations(
                        TableScore.gridCellsFromGroundTruth(exp), true);
                d.gtRelZeroBlank += withNb.zeroBlankCount();
                int distinct = new LinkedHashSet<>(b.relations()).size();
                d.gtRelDistinct += distinct;
                if (distinct < b.relations().size()) d.gtTablesWithDuplicateRelation++;
            }

            // ---- coordinate-convention sanity check (flip vs no-flip control) ----
            countGeomHits(d, unit, glyphs, cropByPage);

            // ---- scores ----
            record Cfg(String name, List<TableExtractor.TableHit> hits) {}
            List<Cfg> cfgs = new ArrayList<>();
            cfgs.add(new Cfg(C_FULL, full));
            cfgs.add(new Cfg(C_LT, taggedLattice));
            cfgs.add(new Cfg(C_STREAM, streamDefault));
            for (GutterFinder f : finders) {
                cfgs.add(new Cfg(C_STREAM + ":" + f.name(), streamByFinder.get(f.name())));
            }
            for (Cfg cfg : cfgs) {
                for (Map.Entry<String, Tally> e : scoreEndToEnd(cfg.hits(), unit.expected()).entrySet()) {
                    d.tallies.put(key(cfg.name(), M_E2E, e.getKey()), e.getValue());
                }
                for (Map.Entry<String, Tally> e :
                        scoreRegionGiven(cfg.hits(), unit.expected(), cropByPage, skipped,
                                cfg.name().equals(C_FULL) ? d : null).entrySet()) {
                    d.tallies.put(key(cfg.name(), M_REGION, e.getKey()), e.getValue());
                }
            }

            // ---- stream REGION-RERUN: re-run the stream path on region-restricted glyphs only ----
            for (GutterFinder f : finders) {
                Map<String, Tally> t = scoreStreamRegionRerun(unit, glyphs, cropByPage, f);
                for (Map.Entry<String, Tally> e : t.entrySet()) {
                    d.tallies.put(key(C_STREAM + ":" + f.name(), M_REGION_RERUN, e.getKey()), e.getValue());
                }
                if (f.name().equals("breuel")) {
                    for (Map.Entry<String, Tally> e : t.entrySet()) {
                        d.tallies.put(key(C_STREAM, M_REGION_RERUN, e.getKey()), e.getValue());
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

    /**
     * REGION-GIVEN, glyph-restricted: for each ground-truth table with geometry, hand the stream
     * path ONLY the glyphs whose centres fall inside that table's region and score whatever it
     * produces. This is the truest analogue of the ICDAR structure sub-task for the stream path --
     * that path consumes nothing but glyph positions, so restricting its input to the given region
     * is exactly "you are told where the table is". (The same trick is NOT meaningful for lattice:
     * lattice derives its grid from drawn rulings read off the page content stream, not from the
     * glyph list, so filtering glyphs would leave its ruling grid intact and merely blank out text.
     * The lattice/tagged configurations are therefore region-scored by cropping their OUTPUT, which
     * is what {@link #scoreRegionGiven} does.)
     */
    private static Map<String, Tally> scoreStreamRegionRerun(BakeOffHarness.ScoreUnit unit,
                                                              Map<Integer, List<TextPosition>> glyphs,
                                                              Map<Integer, PDRectangle> cropByPage,
                                                              GutterFinder finder) {
        Map<String, Tally> out = new LinkedHashMap<>();
        for (String v : VARIANTS) out.put(v, new Tally());
        boolean anyCovered = false;

        for (GroundTruth.Table exp : unit.expected()) {
            List<Region> regions = regionsOf(exp, cropByPage);
            if (regions.isEmpty()) continue;
            List<TableExtractor.TableHit> hits = new ArrayList<>();
            for (Region reg : regions) {
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
                    hits.addAll(StreamTableExtractor.extractPage(reg.page(), inRegion, finder));
                } catch (Throwable ignored) {
                    // extractPage's contract is that it never throws; if it ever does, treat this
                    // region as producing nothing rather than losing the whole document's numbers.
                }
            }
            List<TableScore.GridCell> gtCells = TableScore.gridCellsFromGroundTruth(exp);
            List<TableScore.GridCell> detCells = stackAll(hits);
            List<List<String>> detRows = rowsFromCells(detCells);
            if (!detCells.isEmpty()) anyCovered = true;
            for (String v : VARIANTS) {
                Tally t = out.get(v);
                if (detCells.isEmpty()) {
                    t.addGtOnly(gtOnlyCount(v, exp, gtCells));
                } else {
                    t.add(scoreVariant(v, exp, gtCells, detRows, detCells));
                }
                t.tables++;
            }
        }
        for (Tally t : out.values()) t.covered = anyCovered;
        return out;
    }

    /**
     * Validates the ICDAR-to-ours coordinate conversion at the CONTENT level, which is the only
     * check strong enough to be worth trusting: for each non-blank ground-truth cell, collect the
     * glyphs whose centres fall inside its converted box and ask whether they SPELL that cell's
     * text. "Does the box contain any glyph at all" is far too weak on a text-dense page -- a box
     * placed in the wrong half of the page still lands on some text. The deliberately-wrong
     * no-flip reading is measured alongside as a control; if the flip were the wrong choice, the
     * control would be the one whose text matched.
     */
    private static void countGeomHits(DocResult d, BakeOffHarness.ScoreUnit unit,
                                       Map<Integer, List<TextPosition>> glyphs,
                                       Map<Integer, PDRectangle> cropByPage) {
        for (GroundTruth.Table exp : unit.expected()) {
            for (GroundTruth.Cell c : exp.cells()) {
                if (!c.hasBox() || c.page() <= 0) continue;
                String want = GroundTruth.normalizeCell(c.text());
                if (want.isEmpty()) continue;
                PDRectangle crop = cropByPage.get(c.page());
                List<TextPosition> page = glyphs.get(c.page());
                if (crop == null || page == null) continue;
                d.geomCellsChecked++;
                float x0 = c.x1() - crop.getLowerLeftX() - REGION_PAD;
                float x1 = c.x2() - crop.getLowerLeftX() + REGION_PAD;
                float fy0 = crop.getUpperRightY() - c.y2() - REGION_PAD;
                float fy1 = crop.getUpperRightY() - c.y1() + REGION_PAD;
                float uy0 = c.y1() - REGION_PAD;   // control: no flip at all
                float uy1 = c.y2() + REGION_PAD;

                String gotFlip = textInBox(page, x0, fy0, x1, fy1);
                String gotUnflip = textInBox(page, x0, uy0, x1, uy1);
                if (!gotFlip.isEmpty()) d.geomFlipAnyGlyph++;
                if (!gotUnflip.isEmpty()) d.geomUnflipAnyGlyph++;
                if (gotFlip.equals(want)) d.geomFlipTextEqual++;
                if (gotUnflip.equals(want)) d.geomUnflipTextEqual++;
                if (!gotFlip.isEmpty() && (gotFlip.contains(want) || want.contains(gotFlip))) {
                    d.geomFlipTextOverlap++;
                }
                if (!gotUnflip.isEmpty() && (gotUnflip.contains(want) || want.contains(gotUnflip))) {
                    d.geomUnflipTextOverlap++;
                }
            }
        }
    }

    /** Normalized concatenation of the glyphs whose centres lie in the given rectangle, read in
     *  top-to-bottom / left-to-right order. */
    private static String textInBox(List<TextPosition> page, float x0, float y0, float x1, float y1) {
        List<TextPosition> in = new ArrayList<>();
        for (TextPosition tp : page) {
            float cx = tp.getXDirAdj() + tp.getWidthDirAdj() / 2f;
            float cy = tp.getYDirAdj() + Math.max(1f, tp.getHeightDir()) / 2f;
            if (cx >= x0 && cx <= x1 && cy >= y0 && cy <= y1) in.add(tp);
        }
        if (in.isEmpty()) return "";
        in.sort(Comparator.<TextPosition>comparingDouble(t -> Math.round(t.getYDirAdj() * 2f))
                .thenComparingDouble(TextPosition::getXDirAdj));
        StringBuilder sb = new StringBuilder();
        for (TextPosition tp : in) sb.append(tp.getUnicode() == null ? "" : tp.getUnicode());
        return GroundTruth.normalizeCell(sb.toString());
    }

    private static boolean overlapsSubstantially(TableExtractor.TableHit candidate,
                                                  List<TableExtractor.TableHit> taggedLattice) {
        if (candidate.bbox == null) return false;
        float area = Math.max(0f, candidate.bbox[2] - candidate.bbox[0])
                * Math.max(0f, candidate.bbox[3] - candidate.bbox[1]);
        if (area <= 0f) return false;
        for (TableExtractor.TableHit t : taggedLattice) {
            if (t.page != candidate.page || t.bbox == null) continue;
            float x0 = Math.max(candidate.bbox[0], t.bbox[0]);
            float y0 = Math.max(candidate.bbox[1], t.bbox[1]);
            float x1 = Math.min(candidate.bbox[2], t.bbox[2]);
            float y1 = Math.min(candidate.bbox[3], t.bbox[3]);
            if (x1 <= x0 || y1 <= y0) continue;
            if (((x1 - x0) * (y1 - y0)) / area > STREAM_DROP_OVERLAP_THRESHOLD) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------- test

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("metricFix"), "set -DmetricFix=true to run");

        List<GutterFinder> finders = List.of(
                new BreuelGutterFinder(), new GapVotingGutterFinder(),
                new AlignmentEdgeGutterFinder(), new OccupancyProjectionGutterFinder());

        StringBuilder notes = new StringBuilder();
        BakeOffHarness.CorpusResult corpus = BakeOffHarness.buildScoringSet(notes);
        List<BakeOffHarness.ScoreUnit> units = corpus.units;
        System.out.println("MetricFix corpus: " + units.size() + " PDFs ("
                + corpus.icdarCount + " ICDAR + " + corpus.csvCount + " CSV-matched, "
                + corpus.overlapCount + " overlap)");

        List<DocResult> docs = new ArrayList<>();
        for (BakeOffHarness.ScoreUnit u : units) docs.add(measure(u, finders));

        printErrors(docs);
        printGtInventory(docs);
        printPortReconciliation(docs);
        printCoordinateSanityCheck(docs);
        printClassification(docs);
        printMainTable(docs);
        printBorderless(docs);
        printUsEuSplit(docs);
        printSetVsMultisetDelta(docs);
    }

    // ------------------------------------------------------------------------------- reporting

    private static void printErrors(List<DocResult> docs) {
        List<DocResult> bad = docs.stream().filter(d -> d.error != null).toList();
        System.out.println();
        System.out.println("Documents with a measurement error: " + bad.size());
        for (DocResult d : bad) System.out.println("  " + d.id + ": " + d.error);
    }

    /**
     * Ground-truth relation inventory, printed so it can be checked against the independent faithful
     * port of the official evaluator. Any disagreement here is a disagreement about the METRIC, and
     * must be resolved before any score built on it means anything.
     */
    private static void printGtInventory(List<DocResult> docs) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("GROUND-TRUTH RELATION INVENTORY (official definition) -- cross-check vs the");
        System.out.println("independent port of Table.findAdjacencyRelations + AdjacencyRelation.normalize");
        System.out.println("================================================================================");
        for (String scope : List.of("icdar", "all")) {
            List<DocResult> sel = scope.equals("icdar")
                    ? docs.stream().filter(d -> !d.source.equals("csv")).toList() : docs;
            long total = sel.stream().mapToLong(d -> d.gtRelTotal).sum();
            long right = sel.stream().mapToLong(d -> d.gtRelRight).sum();
            long down = sel.stream().mapToLong(d -> d.gtRelDown).sum();
            long zero = sel.stream().mapToLong(d -> d.gtRelZeroBlank).sum();
            long par = sel.stream().mapToLong(d -> d.gtRelParallelSuppressed).sum();
            long distinct = sel.stream().mapToLong(d -> d.gtRelDistinct).sum();
            long tables = sel.stream().mapToLong(d -> d.gtTables).sum();
            long dupTables = sel.stream().mapToLong(d -> d.gtTablesWithDuplicateRelation).sum();
            System.out.println();
            System.out.println("scope=" + scope + "  documents=" + sel.size() + "  gtTables=" + tables);
            System.out.printf(Locale.ROOT, "  relations (after parallel-link dedup) : %d%n", total);
            System.out.printf(Locale.ROOT, "  parallel links suppressed             : %d%n", par);
            System.out.printf(Locale.ROOT, "  direction split                       : RIGHT %d (%.2f%%) / DOWN %d (%.2f%%)%n",
                    right, total == 0 ? 0 : 100.0 * right / total, down, total == 0 ? 0 : 100.0 * down / total);
            System.out.printf(Locale.ROOT, "  noBlanks==0                           : %d (%.2f%%)%n",
                    zero, total == 0 ? 0 : 100.0 * zero / total);
            System.out.printf(Locale.ROOT, "  distinct-by-value (SET) relations     : %d  => SET discards %d (%.2f%%)%n",
                    distinct, total - distinct, total == 0 ? 0 : 100.0 * (total - distinct) / total);
            System.out.printf(Locale.ROOT, "  tables with >=1 duplicate-by-value    : %d / %d (%.1f%%)%n",
                    dupTables, tables, tables == 0 ? 0 : 100.0 * dupTables / tables);
        }
    }

    private static void printCoordinateSanityCheck(List<DocResult> docs) {
        long checked = docs.stream().mapToLong(d -> d.geomCellsChecked).sum();
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("COORDINATE-CONVENTION SANITY CHECK (ICDAR y-up vs our y-down)");
        System.out.println("================================================================================");
        System.out.println("For each non-blank GT cell, the glyphs inside its CONVERTED box are read out and");
        System.out.println("compared to that cell's own text. 'anyGlyph' is the weak test (box lands on some");
        System.out.println("text at all); 'textEqual'/'textOverlap' are the ones that actually establish the");
        System.out.println("conversion is right. UNFLIPPED is a deliberately-wrong control reading.");
        System.out.printf(Locale.ROOT, "  cells checked                     : %d%n", checked);
        printGeomRow("y-FLIPPED (used)", checked,
                docs.stream().mapToLong(d -> d.geomFlipAnyGlyph).sum(),
                docs.stream().mapToLong(d -> d.geomFlipTextEqual).sum(),
                docs.stream().mapToLong(d -> d.geomFlipTextOverlap).sum());
        printGeomRow("UNFLIPPED (control)", checked,
                docs.stream().mapToLong(d -> d.geomUnflipAnyGlyph).sum(),
                docs.stream().mapToLong(d -> d.geomUnflipTextEqual).sum(),
                docs.stream().mapToLong(d -> d.geomUnflipTextOverlap).sum());
    }

    private static void printGeomRow(String label, long checked, long any, long eq, long ov) {
        System.out.printf(Locale.ROOT,
                "  %-33s anyGlyph=%.2f%%  textEqual=%.2f%%  textOverlap=%.2f%%%n",
                label,
                checked == 0 ? 0 : 100.0 * any / checked,
                checked == 0 ? 0 : 100.0 * eq / checked,
                checked == 0 ? 0 : 100.0 * ov / checked);
    }

    /**
     * Reconciles our ground-truth relation inventory against the independent port's reported
     * figures. The port measured 156 tables / 25,320 relations on this corpus; we measure 163 /
     * 26,033 over the ICDAR documents. The difference is NOT a metric disagreement: our corpus
     * builder folds the four {@code *b-str.xml} files (us-011b, us-031b, us-035b, eu-009b -- second
     * structure annotations of the same underlying PDF as their {@code a} sibling, which has no PDF
     * of its own; see BakeOffHarness#buildScoringSet) into their sibling's expected-table list, so
     * our inventory is a strict SUPERSET by exactly those files' tables. This prints their tables
     * and relations so the subtraction can be checked rather than asserted.
     */
    private static void printPortReconciliation(List<DocResult> docs) {
        List<DocResult> icdar = docs.stream().filter(d -> !d.source.equals("csv")).toList();
        int oursTables = icdar.stream().mapToInt(d -> d.gtTables).sum();
        int oursRelations = icdar.stream().mapToInt(d -> d.gtRelTotal).sum();
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("RECONCILIATION with the independent port's 156 tables / 25,320 relations");
        System.out.println("================================================================================");
        java.nio.file.Path root = java.nio.file.Path.of(
                "corpus/tabula-java/src/test/resources/technology/tabula/icdar2013-dataset")
                .toAbsolutePath().normalize();
        List<String> extras = List.of(
                "competition-dataset-us/us-011b-str.xml",
                "competition-dataset-us/us-031b-str.xml",
                "competition-dataset-us/us-035b-str.xml",
                "competition-dataset-eu/eu-009b-str.xml");
        int tables = 0, relations = 0;
        for (String rel : extras) {
            java.nio.file.Path p = root.resolve(rel);
            if (!java.nio.file.Files.exists(p)) {
                System.out.println("  MISSING: " + p);
                continue;
            }
            try {
                for (GroundTruth.Table t : GroundTruth.fromIcdarStructureXml(p)) {
                    tables++;
                    relations += TableScore.buildOfficialRelations(
                            TableScore.gridCellsFromGroundTruth(t), false).relations().size();
                }
            } catch (Exception e) {
                System.out.println("  FAILED to read " + p + ": " + e);
            }
        }
        System.out.printf(Locale.ROOT,
                "  ours (ICDAR documents)                             : %d tables, %d relations%n",
                oursTables, oursRelations);
        System.out.printf(Locale.ROOT,
                "  duplicate-annotation files (*b-str.xml) contribute : %d tables, %d relations%n",
                tables, relations);
        System.out.printf(Locale.ROOT,
                "  ours minus those                                   : %d tables, %d relations%n",
                oursTables - tables, oursRelations - relations);
        System.out.println("  port reported                                      : 156 tables, 25320 relations");
    }

    private static void printClassification(List<DocResult> docs) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("CORPUS CLASSIFICATION by TableExtractor.extract (tagged+lattice) output");
        System.out.println("================================================================================");
        Map<String, Integer> buckets = new LinkedHashMap<>();
        for (String b : List.of("lattice", "tagged", "both", "neither")) buckets.put(b, 0);
        for (DocResult d : docs) buckets.merge(d.bucket, 1, Integer::sum);
        System.out.println("  " + buckets + "  TOTAL=" + docs.size());
        Map<String, Map<String, Integer>> bySource = new TreeMap<>();
        for (DocResult d : docs) bySource.computeIfAbsent(d.source, k -> new LinkedHashMap<>()).merge(d.bucket, 1, Integer::sum);
        for (Map.Entry<String, Map<String, Integer>> e : bySource.entrySet()) {
            System.out.println("  " + e.getKey() + ": " + e.getValue());
        }
        long one = docs.stream().mapToLong(d -> d.regionTablesFromOneHit).sum();
        long many = docs.stream().mapToLong(d -> d.regionTablesFromManyHits).sum();
        long none = docs.stream().mapToLong(d -> d.regionTablesWithNoCandidate).sum();
        long multipage = docs.stream().mapToLong(d -> d.regionMultiPageTables).sum();
        System.out.println("  region-given candidate assembly, FULL config: from 1 hit=" + one
                + "  from >1 hit (fragmentation forgiven)=" + many + "  no candidate at all=" + none);
        System.out.println("  GT tables whose region spans >1 page: " + multipage);
        long withGeom = docs.stream().mapToLong(d -> d.gtTablesWithGeometry).sum();
        long allTables = docs.stream().mapToLong(d -> d.gtTables).sum();
        System.out.printf(Locale.ROOT,
                "  GT tables with usable region geometry (region-given mode's denominator): %d / %d%n",
                withGeom, allTables);
    }

    private static Acc aggregate(List<DocResult> docs, String key) {
        Acc a = new Acc();
        for (DocResult d : docs) {
            Tally t = d.tallies.get(key);
            if (t == null) continue;
            if (t.gt == 0 && t.detected == 0 && t.tables == 0) continue; // contributes nothing
            a.addDoc(t.matched, t.detected, t.gt, t.covered, t.tables);
        }
        return a;
    }

    private static void printRow(String label, Acc a) {
        System.out.printf(Locale.ROOT,
                "  %-58s MACRO-F1=%.4f  micro P=%.4f R=%.4f F1=%.4f  docs=%-3d cov=%-3d tbl=%-4d%n",
                label, a.macroF1(), a.microP(), a.microR(), a.microF1(), a.docs, a.covered, a.scoredTables);
    }

    private static void printMainTable(List<DocResult> docs) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("CORRECTED BASELINE -- MACRO FIRST (published ICDAR figures are per-document means)");
        System.out.println("================================================================================");
        for (String config : List.of(C_FULL, C_LT, C_STREAM)) {
            System.out.println();
            System.out.println("--- " + config + " (full 77-PDF corpus) ---");
            for (String mode : List.of(M_E2E, M_REGION, M_REGION_RERUN)) {
                for (String v : VARIANTS) {
                    String k = key(config, mode, v);
                    if (docs.stream().noneMatch(d -> d.tallies.containsKey(k))) continue;
                    printRow(mode + " / " + v, aggregate(docs, k));
                }
            }
        }
        System.out.println();
        System.out.println("--- per-finder stream, full 77-PDF corpus (end-to-end, official variant) ---");
        for (String f : FINDERS) {
            printRow(f + " e2e", aggregate(docs, key(C_STREAM + ":" + f, M_E2E, V_OFFICIAL)));
        }
        System.out.println("--- per-finder stream, full 77-PDF corpus (region-given crop, official) ---");
        for (String f : FINDERS) {
            printRow(f + " region", aggregate(docs, key(C_STREAM + ":" + f, M_REGION, V_OFFICIAL)));
        }
        System.out.println("--- per-finder stream, full 77-PDF corpus (region-given RERUN, official) ---");
        for (String f : FINDERS) {
            printRow(f + " region-rerun", aggregate(docs, key(C_STREAM + ":" + f, M_REGION_RERUN, V_OFFICIAL)));
        }
    }

    private static void printBorderless(List<DocResult> docs) {
        List<DocResult> borderless = docs.stream().filter(d -> "neither".equals(d.bucket)).toList();
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("BORDERLESS SUBSET (tagged+lattice find nothing) -- stream path's own scope");
        System.out.println("================================================================================");
        Map<String, Integer> bySource = new TreeMap<>();
        for (DocResult d : borderless) bySource.merge(d.source, 1, Integer::sum);
        System.out.println("  subset size: " + borderless.size() + " / " + docs.size() + "  by source: " + bySource);
        if (borderless.size() < 25) {
            System.out.println("  *** SMALL SAMPLE: a handful of PDFs can swing any F1 here by many points. ***");
        }
        for (String mode : List.of(M_E2E, M_REGION, M_REGION_RERUN)) {
            System.out.println();
            System.out.println("  stream alone on the borderless subset, mode=" + mode + ":");
            for (String v : VARIANTS) {
                String k = key(C_STREAM, mode, v);
                if (borderless.stream().noneMatch(d -> d.tallies.containsKey(k))) continue;
                printRow(v, aggregate(borderless, k));
            }
        }
        System.out.println();
        System.out.println("  per-finder on the borderless subset (official variant):");
        for (String f : FINDERS) {
            printRow(f + " e2e", aggregate(borderless, key(C_STREAM + ":" + f, M_E2E, V_OFFICIAL)));
        }
        for (String f : FINDERS) {
            printRow(f + " region-rerun", aggregate(borderless, key(C_STREAM + ":" + f, M_REGION_RERUN, V_OFFICIAL)));
        }
    }

    private static void printUsEuSplit(List<DocResult> docs) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("US vs EU SPLIT (CSV-sourced PDFs carry no ICDAR US/EU designation, excluded)");
        System.out.println("================================================================================");
        for (String src : List.of("icdar-us", "icdar-eu")) {
            List<DocResult> sel = docs.stream().filter(d -> d.source.equals(src)).toList();
            Map<String, Integer> buckets = new LinkedHashMap<>();
            for (String b : List.of("lattice", "tagged", "both", "neither")) buckets.put(b, 0);
            for (DocResult d : sel) buckets.merge(d.bucket, 1, Integer::sum);
            System.out.println();
            System.out.println("--- " + src + " (n=" + sel.size() + ") classification " + buckets + " ---");
            for (String config : List.of(C_FULL, C_LT, C_STREAM)) {
                printRow(config + " e2e/official", aggregate(sel, key(config, M_E2E, V_OFFICIAL)));
                printRow(config + " region/official", aggregate(sel, key(config, M_REGION, V_OFFICIAL)));
            }
        }
    }

    /**
     * The set-vs-multiset delta, measured on OUR scores rather than only on the ground-truth
     * inventory: how much the reported F1 of each configuration moves if relations are compared as
     * sets instead of bags. Reported for both relation definitions so the two corrections
     * (definition, semantics) can be told apart.
     */
    private static void printSetVsMultisetDelta(List<DocResult> docs) {
        System.out.println();
        System.out.println("================================================================================");
        System.out.println("SET vs MULTISET, and OFFICIAL vs LEGACY-GRID: what each correction is worth");
        System.out.println("================================================================================");
        System.out.printf(Locale.ROOT, "  %-34s %-14s %9s %9s %9s%n",
                "config / mode", "variant", "MACRO", "microF1", "gtRels");
        for (String config : List.of(C_FULL, C_LT, C_STREAM)) {
            for (String mode : List.of(M_E2E, M_REGION)) {
                for (String v : VARIANTS) {
                    String k = key(config, mode, v);
                    if (docs.stream().noneMatch(d -> d.tallies.containsKey(k))) continue;
                    Acc a = aggregate(docs, k);
                    System.out.printf(Locale.ROOT, "  %-34s %-14s %9.4f %9.4f %9d%n",
                            trim(config, 20) + "/" + mode, v, a.macroF1(), a.microF1(), a.gt);
                }
            }
        }
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }
}
