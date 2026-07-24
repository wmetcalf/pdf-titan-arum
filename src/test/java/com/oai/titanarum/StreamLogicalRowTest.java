package com.oai.titanarum;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 9f: {@code buildHit} used to map each display {@code Line} 1:1 to a table row. Real
 * tables have cells whose text WRAPS across several display lines (long descriptions, multi-line
 * headers), and every wrapped cell manufactured a phantom row -- shifting every subsequent row
 * index and, since {@code TableScore} matches cells by exact (row, col, text), tanking F1 even
 * when the extracted TEXT was correct (measured: us-020 produced 49 rows vs. ground truth's 46;
 * us-007, 44 vs. 36). These tests exercise the anchor-column-based logical-row merging fix.
 */
class StreamLogicalRowTest {

    private static PDType1Font helv() { return new PDType1Font(Standard14Fonts.FontName.HELVETICA); }

    /**
     * A 3-column Animal | Action | Result table where the SECOND data row's middle cell
     * ("Action") wraps across 3 display lines ("Barks loudly here" / "at every passing car" /
     * "throughout the night"), while its Animal ("Dog") and Result ("Far") cells are drawn ONCE,
     * on the wrapped cell's first display line only -- the realistic shape of a wrapped
     * description cell sitting next to short, single-line siblings. Physical line count is 7
     * (header + row1 + 3 wrapped lines for row2 + row3 + row4); the LOGICAL row count must be 5.
     *
     * <p>Anchor column selection: column 0 (Animal) is populated on 5 of the 7 physical lines
     * (header, row1, row2's first line, row3, row4 -- but NOT row2's two continuation lines),
     * i.e. 5/7 ~= 0.714 >= {@link StreamTableExtractor#ANCHOR_MIN_FILL} (0.6), so it qualifies as
     * the anchor and the two continuation lines correctly fold into row2 rather than each
     * spawning their own phantom row.
     */
    @Test
    void wrappedCellMergesIntoOneLogicalRow() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(600, 400));
            doc.addPage(page);
            PDType1Font f = helv();
            float[] colX = {40, 200, 400};
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(f, 11);
                float pitch = 16f;
                float y = 340;

                // header (1 physical line, all 3 columns populated)
                String[] header = {"Animal", "Action", "Result"};
                for (int c = 0; c < 3; c++) {
                    cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(header[c]); cs.endText();
                }
                y -= pitch;

                // row1: Cat | Runs | Fast (1 physical line)
                String[] row1 = {"Cat", "Runs", "Fast"};
                for (int c = 0; c < 3; c++) {
                    cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(row1[c]); cs.endText();
                }
                y -= pitch;

                // row2: Dog | <wraps 3 lines> | Far -- Animal/Result drawn only on the first line
                cs.beginText(); cs.newLineAtOffset(colX[0], y); cs.showText("Dog"); cs.endText();
                cs.beginText(); cs.newLineAtOffset(colX[1], y); cs.showText("Barks loudly here"); cs.endText();
                cs.beginText(); cs.newLineAtOffset(colX[2], y); cs.showText("Far"); cs.endText();
                y -= pitch;
                cs.beginText(); cs.newLineAtOffset(colX[1], y); cs.showText("at every passing car"); cs.endText();
                y -= pitch;
                cs.beginText(); cs.newLineAtOffset(colX[1], y); cs.showText("throughout the night"); cs.endText();
                y -= pitch;

                // row3: Owl | Hoots | High (1 physical line)
                String[] row3 = {"Owl", "Hoots", "High"};
                for (int c = 0; c < 3; c++) {
                    cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(row3[c]); cs.endText();
                }
                y -= pitch;

                // row4: Fox | Yips | Sharp (1 physical line)
                String[] row4 = {"Fox", "Yips", "Sharp"};
                for (int c = 0; c < 3; c++) {
                    cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(row4[c]); cs.endText();
                }
            }
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<TableExtractor.TableHit> hits = StreamTableExtractor.extractPage(1, glyphs);
            assertEquals(1, hits.size(), "expected one stream table");
            TableExtractor.TableHit t = hits.get(0);
            assertEquals(3, t.colCount);
            assertEquals(5, t.rowCount,
                "5 LOGICAL rows (header + 4 data rows), not 7 physical display lines");
            assertEquals("Dog", t.rows.get(2).get(0));
            assertEquals("Barks loudly here at every passing car throughout the night",
                t.rows.get(2).get(1),
                "wrapped cell's 3 fragments must be joined with single spaces");
            assertEquals("Far", t.rows.get(2).get(2));
        }
    }

    /**
     * A 3-column table whose HEADER labels wrap onto 2 lines: line 1 is "Animal Action Result"
     * (all 3 columns populated -- the ONLY line with anchor-column content), line 2 is
     * (blank col0) "Type" "Detail" (a continuation of the Action/Result header labels, but
     * column 0 -- the anchor -- has NOTHING on this line). By the exact same rule that merges a
     * wrapped DATA cell, this must merge into ONE logical row.
     *
     * <p>Task 9g note: this test's ORIGINAL docstring claimed "no header special-casing needed" --
     * that claim (from task 9f's brief) turned out to be wrong in general and caused a measured
     * regression on us-018's genuinely hierarchical header (see {@link #stackedHeaderRowsAreNotFlattened}
     * and the class-level fix doc on {@code StreamTableExtractor.groupLogicalRows}). This
     * SPECIFIC test, however, needs NO code or assertion change: "Animal" (the anchor column)
     * is already populated on line 1 -- the very FIRST physical line -- so there is no "leading
     * run before the first anchor-populated line" here at all, and the fold-forward rule for
     * line 2 applies exactly as before. It is kept as-is because it still correctly documents a
     * real, common case (a single wrapped header row whose OWN anchor cell already sits on its
     * first physical line), just not as a stand-in for every multi-line header shape.
     */
    @Test
    void multiLineHeaderMergesIntoOneRow() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(600, 400));
            doc.addPage(page);
            PDType1Font f = helv();
            float[] colX = {40, 200, 400};
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(f, 11);
                float pitch = 16f;
                float y = 340;

                // header line 1: Animal | Action | Result (anchor col0 populated)
                cs.beginText(); cs.newLineAtOffset(colX[0], y); cs.showText("Animal"); cs.endText();
                cs.beginText(); cs.newLineAtOffset(colX[1], y); cs.showText("Action"); cs.endText();
                cs.beginText(); cs.newLineAtOffset(colX[2], y); cs.showText("Result"); cs.endText();
                y -= pitch;
                // header line 2: (col0 empty) | Type | Detail -- continuation, anchor empty
                cs.beginText(); cs.newLineAtOffset(colX[1], y); cs.showText("Type"); cs.endText();
                cs.beginText(); cs.newLineAtOffset(colX[2], y); cs.showText("Detail"); cs.endText();
                y -= pitch;

                String[][] data = {
                    {"Cat", "Runs", "Fast"},
                    {"Dog", "Jumps", "Far"},
                    {"Owl", "Hoots", "High"},
                    {"Fox", "Yips", "Sharp"},
                };
                for (String[] row : data) {
                    for (int c = 0; c < 3; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(row[c]); cs.endText();
                    }
                    y -= pitch;
                }
            }
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<TableExtractor.TableHit> hits = StreamTableExtractor.extractPage(1, glyphs);
            assertEquals(1, hits.size(), "expected one stream table");
            TableExtractor.TableHit t = hits.get(0);
            assertEquals(3, t.colCount);
            assertEquals(5, t.rowCount, "5 LOGICAL rows (1 merged header + 4 data rows), not 6 physical lines");
            assertEquals("Animal", t.rows.get(0).get(0));
            assertEquals("Action Type", t.rows.get(0).get(1),
                "wrapped header fragments must be joined with a single space");
            assertEquals("Result Detail", t.rows.get(0).get(2));
            assertEquals("Cat", t.rows.get(1).get(0));
        }
    }

    /**
     * Task 9g regression test for defect 1. A 4-column Label | ColA | ColB | ColC table with a
     * genuinely STACKED (hierarchical) 2-line header, mirroring us-018-str.xml's rowspan/colspan
     * convention (its actual measured shape: a coarser group-label line populating columns
     * {2,4,5,6}, followed by a finer sub-label line populating a DIFFERENT set, {2,5} -- see the
     * class-level fix doc on {@code groupLogicalRows}). Here: header line 1 is a coarser
     * group-label row populating columns 1 AND 3 only ("GroupX", "GroupY" -- skipping column 2),
     * header line 2 is the finer sub-label row populating ALL THREE non-label columns ("A", "B",
     * "C") -- a DIFFERENT column set than line 1. The label column (the anchor) is empty on BOTH
     * header lines. Per the ICDAR evidence, ground truth models this as two SEPARATE rows, not
     * one flattened row -- exactly what task 9f's original "no special-casing" rule got wrong (it
     * would have folded line 2 into line 1 because line 2 has no anchor content either). Each
     * header line populates >=2 DISTINCT columns so neither is mistaken for a single-column
     * caption fragment by {@code trimEdgeLines}' own (unrelated) non-conformance check.
     */
    @Test
    void stackedHeaderRowsAreNotFlattened() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(600, 400));
            doc.addPage(page);
            PDType1Font f = helv();
            float[] colX = {40, 160, 280, 400};
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(f, 11);
                float pitch = 16f;
                float y = 340;

                // header line 1: (label empty) | "GroupX" | (empty) | "GroupY" -- coarser group
                // labels, populate columns 1 and 3 only (skip column 2).
                cs.beginText(); cs.newLineAtOffset(colX[1], y); cs.showText("GroupX"); cs.endText();
                cs.beginText(); cs.newLineAtOffset(colX[3], y); cs.showText("GroupY"); cs.endText();
                y -= pitch;
                // header line 2: (label empty) | "A" | "B" | "C" -- finer sub-labels, populate
                // ALL THREE non-label columns -- a DIFFERENT set than line 1's {1,3}.
                cs.beginText(); cs.newLineAtOffset(colX[1], y); cs.showText("A"); cs.endText();
                cs.beginText(); cs.newLineAtOffset(colX[2], y); cs.showText("B"); cs.endText();
                cs.beginText(); cs.newLineAtOffset(colX[3], y); cs.showText("C"); cs.endText();
                y -= pitch;

                String[][] data = {
                    {"X1", "10", "20", "30"},
                    {"X2", "40", "50", "60"},
                    {"X3", "70", "80", "90"},
                    {"X4", "11", "12", "13"},
                };
                for (String[] row : data) {
                    for (int c = 0; c < 4; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(row[c]); cs.endText();
                    }
                    y -= pitch;
                }
            }
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<TableExtractor.TableHit> hits = StreamTableExtractor.extractPage(1, glyphs);
            assertEquals(1, hits.size(), "expected one stream table");
            TableExtractor.TableHit t = hits.get(0);
            assertEquals(4, t.colCount);
            assertEquals(6, t.rowCount,
                "6 LOGICAL rows: the 2-line stacked header stays as TWO rows (not flattened into "
                    + "one) + 4 data rows");
            assertEquals("GroupX", t.rows.get(0).get(1), "header row 0 is the coarser group-label row alone");
            assertEquals("", t.rows.get(0).get(0), "label column stays empty on the group-label row");
            assertEquals("GroupY", t.rows.get(0).get(3));
            assertEquals("A", t.rows.get(1).get(1), "header row 1 is the finer sub-label row, kept separate");
            assertEquals("B", t.rows.get(1).get(2));
            assertEquals("C", t.rows.get(1).get(3));
            assertEquals("X1", t.rows.get(2).get(0), "first data row starts right after the 2-row header");
        }
    }

    /**
     * Task 9g test for defect 2 (anchor value on a record's second physical line). A record
     * whose sibling-column content is drawn on its FIRST physical line while its anchor
     * (label-column) value is drawn on its SECOND physical line -- e.g. "90" (Score) alone on
     * line k, then "Carol" (Name, the anchor) plus "B" (Grade) on line k+1.
     *
     * <p><b>This test is currently RED and intentionally left {@code @Disabled}.</b> The natural
     * fix -- reattach the isolated line to the anchor line AFTER it instead of leaving it folded
     * into whatever preceded it -- was implemented and measured, then reverted: it is
     * irreconcilable with {@link #multiLineHeaderMergesIntoOneRow}'s "Type"/"Detail" line, which
     * has the EXACT SAME physical shape (one non-anchor line between two anchor-populated lines)
     * but must fold BACKWARD into the header line before it, not defer to the row after it --
     * the shape alone cannot tell the two cases apart, and there is no other signal available
     * from word geometry alone that does. Every safe restriction tried collapses to a no-op (see
     * the "Task 9g defect 2" note on {@code StreamTableExtractor.groupLogicalRows} for the proof
     * and task-9g-header-and-trim-report.md for the measured evidence: enabling ANY reachable
     * form of this join breaks the test above, and restricting it enough to avoid that leaves it
     * provably dead code). Kept here (disabled, not deleted) as the RED evidence the task asked
     * for, and so a future attempt with a genuinely new distinguishing signal has a ready
     * regression test to check against.
     */
    @org.junit.jupiter.api.Disabled(
        "defect 2 (backwards join) is not implemented -- see javadoc: irreconcilable with "
            + "multiLineHeaderMergesIntoOneRow's identical physical shape")
    @Test
    void anchorOnSecondLineJoinsBackwards() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(600, 400));
            doc.addPage(page);
            PDType1Font f = helv();
            float[] colX = {40, 200, 400};
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(f, 11);
                float pitch = 16f;
                float y = 340;

                // header (1 physical line, anchor col0 populated -> no leading run at all)
                String[] header = {"Name", "Score", "Grade"};
                for (int c = 0; c < 3; c++) {
                    cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(header[c]); cs.endText();
                }
                y -= pitch;

                // row1: Alice | 90 | A (1 physical line, ordinary record)
                String[] row1 = {"Alice", "90", "A"};
                for (int c = 0; c < 3; c++) {
                    cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(row1[c]); cs.endText();
                }
                y -= pitch;

                // row2: split across 2 physical lines -- Score ("85") drawn FIRST, with the
                // anchor (Name="Carol") and Grade ("B") drawn on the SECOND physical line.
                cs.beginText(); cs.newLineAtOffset(colX[1], y); cs.showText("85"); cs.endText();
                y -= pitch;
                cs.beginText(); cs.newLineAtOffset(colX[0], y); cs.showText("Carol"); cs.endText();
                cs.beginText(); cs.newLineAtOffset(colX[2], y); cs.showText("B"); cs.endText();
                y -= pitch;

                // row3: Dan | 70 | C (1 physical line, ordinary record, so row2's split doesn't
                // just coincidentally look like the LAST row of the table)
                String[] row3 = {"Dan", "70", "C"};
                for (int c = 0; c < 3; c++) {
                    cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(row3[c]); cs.endText();
                }
            }
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<TableExtractor.TableHit> hits = StreamTableExtractor.extractPage(1, glyphs);
            assertEquals(1, hits.size(), "expected one stream table");
            TableExtractor.TableHit t = hits.get(0);
            assertEquals(3, t.colCount);
            assertEquals(4, t.rowCount,
                "4 LOGICAL rows (header + Alice + Carol + Dan): the split Score/Carol/Grade "
                    + "lines must join into ONE row for Carol, not two");
            assertEquals("Carol", t.rows.get(2).get(0));
            assertEquals("85", t.rows.get(2).get(1), "Score drawn on the FIRST physical line must still land in Carol's row");
            assertEquals("B", t.rows.get(2).get(2));
            assertEquals("Dan", t.rows.get(3).get(0));
        }
    }

    /**
     * Unit-level fallback documentation test: a block where NO column is populated on >=
     * {@link StreamTableExtractor#ANCHOR_MIN_FILL} (0.6) of its lines -- content is staggered so
     * every column sits at exactly 50%. {@link StreamTableExtractor#findAnchorColumn} must return
     * -1, and {@link StreamTableExtractor#groupLogicalRows} must fall back to exactly one logical
     * row per input line, in the same order -- i.e. identical to the pre-Task-9f one-row-per-line
     * behavior.
     */
    @Test
    void blockWithNoAnchorColumnFallsBackToOneRowPerLine() {
        // 2 columns, 4 lines: col0 populated on lines 0,2 only (2/4=0.5); col1 populated on
        // lines 1,3 only (2/4=0.5). Neither reaches the 0.6 bar.
        StreamTableExtractor.Line l0 = lineWith(word(10, 40, 0, "A"));
        StreamTableExtractor.Line l1 = lineWith(word(110, 140, 10, "B"));
        StreamTableExtractor.Line l2 = lineWith(word(10, 40, 20, "C"));
        StreamTableExtractor.Line l3 = lineWith(word(110, 140, 30, "D"));
        List<StreamTableExtractor.Line> lines = List.of(l0, l1, l2, l3);
        float[] colBounds = {0, 100, 200}; // 2 columns: [0,100), [100,200)

        int anchor = StreamTableExtractor.findAnchorColumn(lines, colBounds);
        assertEquals(-1, anchor, "no column clears the 60% fill bar -> no anchor");

        List<List<StreamTableExtractor.Line>> groups =
            StreamTableExtractor.groupLogicalRows(lines, colBounds);
        assertEquals(4, groups.size(), "fallback must produce exactly one logical row per line");
        for (int i = 0; i < lines.size(); i++) {
            assertEquals(1, groups.get(i).size(), "each fallback group must hold exactly one line");
            assertSame(lines.get(i), groups.get(i).get(0), "fallback must preserve line identity/order");
        }
    }

    private static StreamTableExtractor.Word word(float x0, float x1, float yTop, String text) {
        StreamTableExtractor.Word w = new StreamTableExtractor.Word();
        w.x0 = x0; w.x1 = x1; w.y0 = yTop; w.y1 = yTop + 10; w.text = text;
        return w;
    }

    private static StreamTableExtractor.Line lineWith(StreamTableExtractor.Word w) {
        StreamTableExtractor.Line l = new StreamTableExtractor.Line();
        l.yTop = w.y0; l.yBot = w.y1;
        l.words.add(w);
        return l;
    }
}
