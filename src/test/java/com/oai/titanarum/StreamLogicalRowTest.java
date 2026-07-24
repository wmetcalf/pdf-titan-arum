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
     * wrapped DATA cell (no header special-casing), this must merge into ONE logical row.
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
