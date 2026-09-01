package com.oai.titanarum.bakeoff;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Ground-truth HYGIENE: detects and removes ground-truth tables that are a SECOND annotation of the
 * SAME physical table, so a corpus artifact stops being charged to the extractor.
 *
 * <p><b>The artifact.</b> The shipped ICDAR 2013 corpus contains four {@code *b-str.xml} files
 * ({@code us-011b}, {@code us-031b}, {@code us-035b}, {@code eu-009b}) that have no PDF of their own
 * -- they annotate the same PDF as their {@code a} sibling, and {@code BakeOffHarness#buildScoringSet}
 * therefore folds their tables into that PDF's expected-table list. The result is that a handful of
 * physical tables appear TWICE in the expected list, at (essentially) the same place on the same
 * page. Every table-correspondence protocol consumes a detected table at most once, so at least one
 * member of each duplicated pair can never be paired with anything: it is scored as a total miss no
 * matter how perfectly the extractor recovered that table. That is a pure scoring penalty for an
 * annotation artifact, not a measurement of extraction quality.
 *
 * <p><b>What this class does and does not do.</b> It is a read-only filter over a list of
 * {@link GroundTruth.Table}: it never mutates the tables, never touches {@link GroundTruth}'s
 * parsing, and never changes the definition of a relation or of adjacency matching. It only decides
 * which entries of an expected-table list describe the same physical table, and reports both the
 * surviving list and a full audit record of what it removed so the decision is checkable rather than
 * trusted.
 *
 * <p><b>The duplicate test</b> is geometric and deliberately strict: two tables are the same physical
 * table when they declare cells on exactly the same page(s) and the union of their cell bounding
 * boxes has an intersection-over-union of at least {@link #DEFAULT_IOU_THRESHOLD} on every such page
 * (aggregated as summed-intersection over summed-union). Geometry, not content, is the test, because
 * two independent annotators of the same table can legitimately disagree about its cell structure
 * (that is exactly why a second annotation exists) while they cannot disagree about where on the page
 * it sits. IoU is computed in the ICDAR file's OWN coordinate space -- no page height and no y-flip
 * is needed, since IoU is invariant under the translation/reflection that separates ICDAR's
 * bottom-left-origin space from ours.
 *
 * <p>A table with no usable geometry (no cell bounding boxes, or no declared page -- CSV-sourced
 * ground truth, always) is never treated as a duplicate of anything: with no boxes there is no
 * evidence of sameness, and inventing some would risk deleting real ground truth. The FIRST
 * occurrence in list order is always the one kept, which (given the corpus builder appends str.xml
 * files in sorted order) means the {@code a} annotation survives and the {@code b} annotation is
 * dropped.
 */
public final class GtDedup {

    private GtDedup() {
    }

    /** Minimum bbox intersection-over-union for two ground-truth tables to be the same table. */
    public static final double DEFAULT_IOU_THRESHOLD = 0.9;

    /**
     * One removal, with everything needed to audit it: where the removed entry sat in the input list,
     * which entry it duplicated, the pages involved, the geometric IoU that triggered the removal,
     * the removed table's shape and relation count (i.e. exactly how many relations leave the ground
     * truth with it), and -- purely as corroborating evidence, never as the test -- the multiset
     * Jaccard similarity between the two annotations' official adjacency relations.
     */
    public record Duplicate(int removedIndex, int keptIndex, List<Integer> pages, double iou,
                             int rows, int cols, int relations, double relationJaccard,
                             String firstCellText) {

        public String describe() {
            return String.format(Locale.ROOT,
                    "entry #%d duplicates entry #%d  page(s)=%s  bboxIoU=%.4f  %dx%d  "
                            + "relations=%d  relJaccard=%.4f  firstCell=\"%s\"",
                    removedIndex, keptIndex, pages, iou, rows, cols, relations, relationJaccard,
                    firstCellText);
        }
    }

    /** The surviving expected-table list plus the audit trail of what was dropped. */
    public record Result(List<GroundTruth.Table> kept, List<Duplicate> removed) {

        /** Total official-definition relations that left the ground truth with the removed tables. */
        public int removedRelations() {
            int n = 0;
            for (Duplicate d : removed) n += d.relations();
            return n;
        }
    }

    /** De-duplicates at {@link #DEFAULT_IOU_THRESHOLD}. */
    public static Result dedup(List<GroundTruth.Table> tables) {
        return dedup(tables, DEFAULT_IOU_THRESHOLD);
    }

    /**
     * De-duplicates {@code tables}, keeping the first member of every duplicate group.
     *
     * <p>A candidate is compared against the tables already KEPT (not against every earlier table),
     * so three annotations of one table collapse to one rather than to a chain. When a candidate is
     * above threshold against more than one kept table, the highest-IoU one is recorded as the
     * original -- which of them is recorded changes nothing about what is removed.
     */
    public static Result dedup(List<GroundTruth.Table> tables, double iouThreshold) {
        List<GroundTruth.Table> kept = new ArrayList<>();
        List<Integer> keptInputIndex = new ArrayList<>();
        List<Duplicate> removed = new ArrayList<>();

        for (int i = 0; i < tables.size(); i++) {
            GroundTruth.Table candidate = tables.get(i);
            int dupOf = -1;
            double dupIou = 0.0;
            for (int k = 0; k < kept.size(); k++) {
                double iou = iou(kept.get(k), candidate);
                if (iou >= iouThreshold && iou > dupIou) {
                    dupOf = k;
                    dupIou = iou;
                }
            }
            if (dupOf < 0) {
                kept.add(candidate);
                keptInputIndex.add(i);
                continue;
            }
            removed.add(new Duplicate(i, keptInputIndex.get(dupOf), pagesOf(candidate), dupIou,
                    candidate.rowCount(), candidate.colCount(), relationCount(candidate),
                    relationJaccard(kept.get(dupOf), candidate), firstCellText(candidate)));
        }
        return new Result(List.copyOf(kept), List.copyOf(removed));
    }

    // ------------------------------------------------------------------------------- geometry --

    /**
     * Bounding-box intersection-over-union of two ground-truth tables, in the ICDAR files' own
     * coordinate space, aggregated over pages: {@code sum(intersection) / sum(union)}.
     *
     * <p>Returns 0 when either table has no usable geometry, or when the two do not declare cells on
     * exactly the same set of pages (a one-page table and a two-page table are not the same table,
     * however well their first pages line up).
     */
    public static double iou(GroundTruth.Table a, GroundTruth.Table b) {
        Map<Integer, float[]> ba = unionBoxByPage(a);
        Map<Integer, float[]> bb = unionBoxByPage(b);
        if (ba.isEmpty() || bb.isEmpty()) return 0.0;
        if (!ba.keySet().equals(bb.keySet())) return 0.0;

        double inter = 0.0;
        double union = 0.0;
        for (Map.Entry<Integer, float[]> e : ba.entrySet()) {
            float[] x = e.getValue();
            float[] y = bb.get(e.getKey());
            double i = intersectionArea(x, y);
            inter += i;
            union += area(x) + area(y) - i;
        }
        return union <= 0.0 ? 0.0 : inter / union;
    }

    /** page -> {x1,y1,x2,y2} union of that page's cell boxes, in raw ICDAR coordinates. */
    private static Map<Integer, float[]> unionBoxByPage(GroundTruth.Table t) {
        Map<Integer, float[]> out = new LinkedHashMap<>();
        for (GroundTruth.Cell c : t.cells()) {
            if (!c.hasBox() || c.page() <= 0) continue;
            float[] u = out.get(c.page());
            if (u == null) {
                out.put(c.page(), new float[]{c.x1(), c.y1(), c.x2(), c.y2()});
            } else {
                u[0] = Math.min(u[0], c.x1());
                u[1] = Math.min(u[1], c.y1());
                u[2] = Math.max(u[2], c.x2());
                u[3] = Math.max(u[3], c.y2());
            }
        }
        return out;
    }

    private static double area(float[] b) {
        return Math.max(0f, b[2] - b[0]) * (double) Math.max(0f, b[3] - b[1]);
    }

    private static double intersectionArea(float[] a, float[] b) {
        float x0 = Math.max(a[0], b[0]);
        float y0 = Math.max(a[1], b[1]);
        float x1 = Math.min(a[2], b[2]);
        float y1 = Math.min(a[3], b[3]);
        if (x1 <= x0 || y1 <= y0) return 0.0;
        return (x1 - x0) * (double) (y1 - y0);
    }

    // ------------------------------------------------------------------------- audit helpers --

    private static List<Integer> pagesOf(GroundTruth.Table t) {
        Set<Integer> pages = new TreeSet<>();
        for (GroundTruth.Cell c : t.cells()) {
            if (c.hasBox() && c.page() > 0) pages.add(c.page());
        }
        return List.copyOf(pages);
    }

    private static int relationCount(GroundTruth.Table t) {
        return TableScore.buildOfficialRelations(
                TableScore.gridCellsFromGroundTruth(t), false).relations().size();
    }

    /** Corroborating evidence only: how similar the two annotations' relation multisets are. */
    private static double relationJaccard(GroundTruth.Table a, GroundTruth.Table b) {
        List<TableScore.Relation> ra = TableScore.buildOfficialRelations(
                TableScore.gridCellsFromGroundTruth(a), false).relations();
        List<TableScore.Relation> rb = TableScore.buildOfficialRelations(
                TableScore.gridCellsFromGroundTruth(b), false).relations();
        int matched = TableScore.compareRelations(ra, rb, TableScore.Semantics.MULTISET).matched();
        int union = ra.size() + rb.size() - matched;
        return union == 0 ? 0.0 : (double) matched / union;
    }

    private static String firstCellText(GroundTruth.Table t) {
        for (List<String> row : t.rows()) {
            for (String cell : row) {
                if (!GroundTruth.normalizeCell(cell).isEmpty()) {
                    String s = cell.replaceAll("\\s+", " ").trim();
                    return s.length() <= 40 ? s : s.substring(0, 40) + "...";
                }
            }
        }
        return "";
    }
}
