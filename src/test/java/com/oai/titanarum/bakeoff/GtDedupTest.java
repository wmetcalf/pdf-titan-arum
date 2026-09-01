package com.oai.titanarum.bakeoff;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GtDedup}, the ground-truth hygiene filter that removes second annotations of
 * the same physical table. These are synthetic fixtures on purpose: the corpus behaviour (exactly 7
 * tables / 716 relations removed, landing on the independent evaluator port's inventory) is verified by
 * BaselineHarness on real data, while these pin the DECISION RULE -- what counts as a duplicate, what
 * does not, and which member of a pair survives -- so the rule cannot loosen without a test failing.
 */
class GtDedupTest {

    /** A 2x2 table whose four cells all carry the same bounding box (only the union matters here). */
    private static GroundTruth.Table table(String label, int page,
                                            float x1, float y1, float x2, float y2) {
        List<List<String>> rows = List.of(
                List.of(label + "-a", label + "-b"),
                List.of(label + "-c", label + "-d"));
        List<GroundTruth.Cell> cells = new ArrayList<>();
        int i = 0;
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
                cells.add(new GroundTruth.Cell(r, c, r, c, rows.get(r).get(c), page,
                        x1, y1, x2, y2, true));
                i++;
            }
        }
        return new GroundTruth.Table(rows, List.copyOf(cells));
    }

    /** Same geometry as {@link #table} but with no bounding boxes at all -- the CSV-sourced shape. */
    private static GroundTruth.Table tableWithoutGeometry(String label) {
        return new GroundTruth.Table(List.of(
                List.of(label + "-a", label + "-b"),
                List.of(label + "-c", label + "-d")));
    }

    @Test
    void identicalGeometryIsADuplicateAndTheFirstEntryIsKept() {
        GroundTruth.Table first = table("A", 1, 10, 10, 110, 60);
        GroundTruth.Table second = table("B", 1, 10, 10, 110, 60);

        GtDedup.Result r = GtDedup.dedup(List.of(first, second));

        assertEquals(1, r.kept().size(), "one of the two annotations must survive");
        assertSame(first, r.kept().get(0), "the FIRST occurrence is the one kept");
        assertEquals(1, r.removed().size());
        assertEquals(1, r.removed().get(0).removedIndex());
        assertEquals(0, r.removed().get(0).keptIndex());
        assertEquals(1.0, r.removed().get(0).iou(), 1e-9);
    }

    @Test
    void disjointTablesOnTheSamePageAreBothKept() {
        GroundTruth.Table top = table("A", 1, 10, 200, 110, 250);
        GroundTruth.Table bottom = table("B", 1, 10, 10, 110, 60);

        GtDedup.Result r = GtDedup.dedup(List.of(top, bottom));

        assertEquals(2, r.kept().size());
        assertTrue(r.removed().isEmpty());
        assertEquals(0.0, GtDedup.iou(top, bottom), 1e-9);
    }

    @Test
    void tablesOnDifferentPagesAreNeverDuplicates() {
        GroundTruth.Table p1 = table("A", 1, 10, 10, 110, 60);
        GroundTruth.Table p2 = table("B", 2, 10, 10, 110, 60);

        assertEquals(0.0, GtDedup.iou(p1, p2), 1e-9,
                "same rectangle on a different page is a different table");
        assertEquals(2, GtDedup.dedup(List.of(p1, p2)).kept().size());
    }

    @Test
    void overlapBelowThresholdIsKept() {
        // 100x50 box vs a 100x50 box shifted 20pt right: intersection 80x50=4000,
        // union 2*5000-4000=6000, IoU = 0.667 -- well below 0.9.
        GroundTruth.Table a = table("A", 1, 0, 0, 100, 50);
        GroundTruth.Table b = table("B", 1, 20, 0, 120, 50);

        assertEquals(4000.0 / 6000.0, GtDedup.iou(a, b), 1e-6);
        assertEquals(2, GtDedup.dedup(List.of(a, b)).kept().size(),
                "a two-thirds overlap is NOT evidence of the same table");
    }

    @Test
    void overlapJustAboveThresholdIsRemoved() {
        // 100x50 vs the same box shifted 5pt right: intersection 95x50=4750,
        // union 10000-4750=5250, IoU = 0.9048 -- just over the 0.90 threshold.
        GroundTruth.Table a = table("A", 1, 0, 0, 100, 50);
        GroundTruth.Table b = table("B", 1, 5, 0, 105, 50);

        double iou = GtDedup.iou(a, b);
        assertTrue(iou > GtDedup.DEFAULT_IOU_THRESHOLD && iou < 0.91, "IoU was " + iou);
        assertEquals(1, GtDedup.dedup(List.of(a, b)).kept().size());
    }

    @Test
    void thresholdIsHonouredExactly() {
        GroundTruth.Table a = table("A", 1, 0, 0, 100, 50);
        GroundTruth.Table b = table("B", 1, 20, 0, 120, 50);   // IoU 0.667

        assertEquals(2, GtDedup.dedup(List.of(a, b), 0.7).kept().size(),
                "0.667 < 0.7 -> kept");
        assertEquals(1, GtDedup.dedup(List.of(a, b), 0.6).kept().size(),
                "0.667 >= 0.6 -> removed");
    }

    @Test
    void threeAnnotationsOfOneTableCollapseToOne() {
        GroundTruth.Table a = table("A", 1, 10, 10, 110, 60);
        GroundTruth.Table b = table("B", 1, 10, 10, 110, 60);
        GroundTruth.Table c = table("C", 1, 10, 10, 110, 60);

        GtDedup.Result r = GtDedup.dedup(List.of(a, b, c));

        assertEquals(1, r.kept().size());
        assertSame(a, r.kept().get(0));
        assertEquals(List.of(1, 2), r.removed().stream().map(GtDedup.Duplicate::removedIndex).toList());
        assertEquals(List.of(0, 0), r.removed().stream().map(GtDedup.Duplicate::keptIndex).toList(),
                "both removals point at the surviving entry, not at each other");
    }

    @Test
    void tableWithoutGeometryIsNeverRemoved() {
        GroundTruth.Table csvA = tableWithoutGeometry("A");
        GroundTruth.Table csvB = tableWithoutGeometry("A");   // identical CONTENT, no geometry

        GtDedup.Result r = GtDedup.dedup(List.of(csvA, csvB));

        assertEquals(2, r.kept().size(),
                "with no bounding boxes there is no evidence of sameness -- never guess");
        assertTrue(r.removed().isEmpty());
        assertEquals(0.0, GtDedup.iou(csvA, csvB), 1e-9);
    }

    @Test
    void emptyAndSingletonInputsAreUnchanged() {
        assertTrue(GtDedup.dedup(List.of()).kept().isEmpty());
        assertTrue(GtDedup.dedup(List.of()).removed().isEmpty());
        GroundTruth.Table only = table("A", 1, 0, 0, 10, 10);
        assertEquals(1, GtDedup.dedup(List.of(only)).kept().size());
    }

    @Test
    void auditRecordCarriesTheRemovedTablesRelationCount() {
        GroundTruth.Table a = table("A", 1, 10, 10, 110, 60);
        GroundTruth.Table b = table("B", 1, 10, 10, 110, 60);

        GtDedup.Result r = GtDedup.dedup(List.of(a, b));
        GtDedup.Duplicate d = r.removed().get(0);

        int expected = TableScore.buildOfficialRelations(
                TableScore.gridCellsFromGroundTruth(b), false).relations().size();
        assertEquals(expected, d.relations(),
                "the audit must state exactly how many relations left the ground truth");
        assertEquals(expected, r.removedRelations());
        assertEquals(2, d.rows());
        assertEquals(2, d.cols());
        assertEquals(List.of(1), d.pages());
        assertTrue(d.describe().contains("bboxIoU"), "describe() is the audit line: " + d.describe());
    }

    @Test
    void relationJaccardIsOneForByteIdenticalAnnotationsAndLessOtherwise() {
        GroundTruth.Table a = table("A", 1, 10, 10, 110, 60);
        GroundTruth.Table sameContent = table("A", 1, 10, 10, 110, 60);
        GroundTruth.Table otherContent = table("Z", 1, 10, 10, 110, 60);

        assertEquals(1.0, GtDedup.dedup(List.of(a, sameContent)).removed().get(0).relationJaccard(),
                1e-9);
        assertEquals(0.0, GtDedup.dedup(List.of(a, otherContent)).removed().get(0).relationJaccard(),
                1e-9, "geometry decides removal; relation similarity is only reported");
    }
}
