package com.oai.titanarum;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The precision fix the stream-path false-positive study shipped: {@code scoreGrid}'s prose-fill
 * term measures the widest CELL, not the widest WORD.
 *
 * <p>WHY THE EXISTING PROSE FIXTURES NEVER CAUGHT THIS. Every prose fixture in {@link
 * StreamGridnessTest} renders each cell as ONE very long token that spans the whole column
 * ({@code w(10, 95, y, "leftcolumnfullofprosethatwraps")}), so the widest TOKEN and the widest CELL
 * are the same box and the two definitions agree exactly. Real prose is several SHORT tokens that
 * together span the column, and that is the case these tests add.
 */
class StreamProseFillAndEvidenceTest {

    private static StreamTableExtractor.Word w(float x0, float x1, float yTop, String t) {
        StreamTableExtractor.Word wd = new StreamTableExtractor.Word();
        wd.x0 = x0; wd.x1 = x1; wd.y0 = yTop; wd.y1 = yTop + 10; wd.text = t;
        wd.numeric = t.matches("[-+(]?[\\d.,%$)]+") && t.chars().anyMatch(Character::isDigit);
        return wd;
    }

    /**
     * One line of running prose inside a cell spanning {@code x0..x1}: two short tokens whose
     * SPLIT POINT moves with the row, because real prose has no vertical whitespace channel inside
     * a paragraph -- word boundaries land somewhere different on every line. (A fixture whose
     * intra-cell token boundaries DO align row-to-row is not prose: the gutter finder correctly
     * reads it as extra columns, which is what a first attempt at this fixture proved.)
     */
    private static void proseCell(List<StreamTableExtractor.Word> ws, float x0, float x1, float y,
                                  int row, String tag) {
        float split = x0 + (x1 - x0) * 0.30f + (row % 5) * 3f;
        ws.add(w(x0, split, y, tag + row + "a"));
        ws.add(w(split + 5, x1, y, tag + row + "b"));
    }

    /**
     * A three-column block whose cells are built from SEVERAL SHORT TOKENS that together span ~70%
     * of their column. That is below the prose HARD VETO's 0.85 fill threshold, so the graded
     * prose-fill term is the only thing standing between this layout and admission -- and it must
     * see the 0.70 cell fill, not the ~0.20 fill of the widest individual token.
     */
    @Test
    void proseFillTermMeasuresTheWidestCellNotTheWidestWord() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 12; r++) {
            float y = 20 + r * 15;
            // each cell spans ~0.72 of its column -- ABOVE what a data cell fills, but BELOW the
            // prose hard veto's 0.85, so the graded prose-fill term is the only defence left.
            proseCell(ws, 10, 69, y, r, "alpha");
            proseCell(ws, 110, 182, y, r, "bravo");
            proseCell(ws, 210, 284, y, r, "charlie");
        }
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        var g = StreamTableExtractor.findGutters(lines, 10, 295, 6f);
        var grid = StreamTableExtractor.scoreGrid(lines, g, 10, 295);

        assertEquals(3, grid.nCols, "fixture must really be three columns");
        assertEquals(12, grid.nRows, "fixture must really be twelve rows");
        // The widest single token is ~18pt in a ~92pt column (fill ~0.20), which the word-based
        // definition scores as FULL "not prose" credit. The cells themselves span ~0.70 of their
        // columns, which must cost real credit.
        assertTrue(grid.tProse < 0.85,
                "prose-fill term must reflect the ~0.70 CELL fill, not the ~0.20 widest-token "
                        + "fill (which would score 1.000); got tProse=" + grid.tProse);
    }

    /**
     * The companion no-regression case: a genuinely short-celled numeric table -- cells occupying a
     * small part of a generously spaced column -- must still receive full prose credit, so the fix
     * cannot be a blanket penalty on every table with more than one word in a cell.
     */
    @Test
    void shortCelledNumericTableStillGetsFullProseCredit() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 40, y, "Row" + r));
            ws.add(w(110, 132, y, String.valueOf(100 + r)));
            ws.add(w(210, 232, y, String.valueOf(200 + r)));
        }
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        var g = StreamTableExtractor.findGutters(lines, 10, 295, 6f);
        var grid = StreamTableExtractor.scoreGrid(lines, g, 10, 295);
        assertEquals(1.0, grid.tProse, 1e-9,
                "a short-celled numeric table must keep full prose credit");
        assertTrue(grid.confidence >= StreamTableExtractor.STREAM_CONFIDENCE_MIN,
                "a short-celled numeric 3-column table must still be admitted, got "
                        + grid.confidence);
    }

}
