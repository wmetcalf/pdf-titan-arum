package com.oai.titanarum;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** rows[][] and markdown views must be derivable from cells alone (spans anchored, covered = ""). */
class TableRenderTest {

    private static TableExtractor.CellHit cell(int row, int col, int rowSpan, int colSpan, String text) {
        TableExtractor.CellHit c = new TableExtractor.CellHit();
        c.row = row; c.col = col; c.rowSpan = rowSpan; c.colSpan = colSpan; c.text = text;
        return c;
    }

    @Test
    void plainGridRendersRowsAndMarkdown() {
        TableExtractor.TableHit t = new TableExtractor.TableHit();
        t.rowCount = 2; t.colCount = 2;
        t.cells = List.of(
                cell(0, 0, 1, 1, "A"), cell(0, 1, 1, 1, "B"),
                cell(1, 0, 1, 1, "C"), cell(1, 1, 1, 1, "D"));
        TableExtractor.renderViews(t);
        assertEquals(List.of(List.of("A", "B"), List.of("C", "D")), t.rows);
        assertEquals("| A | B |\n|---|---|\n| C | D |", t.markdown);
    }

    @Test
    void spansAnchorTextAndBlankCoveredPositions() {
        // "Invoice #" spans cols 0-1 of the header row.
        TableExtractor.TableHit t = new TableExtractor.TableHit();
        t.rowCount = 2; t.colCount = 3;
        t.cells = List.of(
                cell(0, 0, 1, 2, "Invoice #"), cell(0, 2, 1, 1, "Amount"),
                cell(1, 0, 1, 1, "1042"), cell(1, 1, 1, 1, "x"), cell(1, 2, 1, 1, "$980.00"));
        TableExtractor.renderViews(t);
        assertEquals(List.of(
                List.of("Invoice #", "", "Amount"),
                List.of("1042", "x", "$980.00")), t.rows);
        assertEquals("| Invoice # |  | Amount |\n|---|---|---|\n| 1042 | x | $980.00 |", t.markdown);
    }

    @Test
    void markdownEscapesPipesAndFlattensNewlines() {
        TableExtractor.TableHit t = new TableExtractor.TableHit();
        t.rowCount = 2; t.colCount = 1;
        t.cells = List.of(cell(0, 0, 1, 1, "a|b"), cell(1, 0, 1, 1, "two\nlines"));
        TableExtractor.renderViews(t);
        // rows keeps the faithful text; markdown escapes/flattens
        assertEquals(List.of(List.of("a|b"), List.of("two\nlines")), t.rows);
        assertEquals("| a\\|b |\n|---|\n| two lines |", t.markdown);
    }

    @Test
    void nullCellTextRendersEmpty() {
        TableExtractor.TableHit t = new TableExtractor.TableHit();
        t.rowCount = 1; t.colCount = 2;
        TableExtractor.CellHit empty = cell(0, 1, 1, 1, null);
        t.cells = List.of(cell(0, 0, 1, 1, "A"), empty);
        TableExtractor.renderViews(t);
        assertEquals(List.of(List.of("A", "")), t.rows);
        assertEquals("| A |  |", t.markdown);
    }
}
