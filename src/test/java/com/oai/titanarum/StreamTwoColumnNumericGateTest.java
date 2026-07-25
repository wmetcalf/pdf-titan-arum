package com.oai.titanarum;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code cols==2-nonnumeric} hard gate asks "is this two-column block a data table?" and answers
 * it with a WORD-fraction test: a column counts as numeric only if >= 70% of the WORDS landing in it
 * are numeric. Measured against the ICDAR corpus (see the lever-3 residual report), that word
 * fraction is diluted below the bar by the column's own multi-word HEADER LABEL, so genuine numeric
 * two-column tables are hard-rejected:
 *
 *   eu-006 p2  "Retailer | Own Brands Market Shares" over Monoprix|28%, Casino|25%, ...
 *              -> 6 numeric words vs. 4 header words = 0.600, below 0.70, REJECTED
 *   eu-014 p2  "Indicators | Weight of indicator in 2006" over Employment|40, Dropping out|15, ...
 *              -> 0.636, below 0.70, REJECTED
 *
 * A ROW-majority test asks the same question in the unit a table is actually made of: what fraction
 * of the column's OWN OCCUPIED ROWS are entirely numeric. A multi-word header contributes exactly one
 * row to that denominator instead of four words to it, so it can no longer outvote the data.
 *
 * <p>These tests pin BOTH directions: the genuine numeric table must be admitted, and every negative
 * the corpus and the real-world prose sample contain must still be rejected -- in particular a
 * two-column block of running PROSE with an incidental numeral in it (the shape that scores a
 * non-trivial numeric WORD fraction while having no numeric ROW at all).
 */
class StreamTwoColumnNumericGateTest {

    private static StreamTableExtractor.Word w(float x0, float x1, float yTop, String t) {
        StreamTableExtractor.Word wd = new StreamTableExtractor.Word();
        wd.x0 = x0; wd.x1 = x1; wd.y0 = yTop; wd.y1 = yTop + 10; wd.text = t;
        wd.numeric = t.matches("[-+(]?[\\d.,%$)]+") && t.chars().anyMatch(Character::isDigit);
        return wd;
    }

    private static StreamTableExtractor.Gutter gutter(float x0, float x1) {
        StreamTableExtractor.Gutter g = new StreamTableExtractor.Gutter();
        g.x0 = x0; g.x1 = x1;
        return g;
    }

    /** The eu-006 p2 shape: a real 2-column numeric table under a FOUR-WORD header label. */
    @Test
    void twoColumnNumericTableUnderMultiWordHeaderIsAdmitted() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        // header row: one word left, four words right -- the dilution source
        ws.add(w(10, 55, 20, "Retailer"));
        ws.add(w(100, 118, 20, "Own"));
        ws.add(w(122, 152, 20, "Brands"));
        ws.add(w(156, 186, 20, "Market"));
        ws.add(w(190, 220, 20, "Shares"));
        String[] names = {"Monoprix", "Casino", "Intermarche", "Carrefour", "Auchan", "Leclerc"};
        String[] pcts  = {"28%", "25%", "23%", "22%", "19%", "10%"};
        for (int r = 0; r < names.length; r++) {
            float y = 40 + r * 15;
            ws.add(w(10, 60, y, names[r]));
            ws.add(w(100, 122, y, pcts[r]));
        }
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        var grid = StreamTableExtractor.scoreGrid(lines, List.of(gutter(62, 98)), 10, 220);

        // The word fraction of the right column really is below the production 0.70 bar -- this is
        // the measured defect, not an artificial fixture.
        assertTrue(numericWordFraction(lines, grid.colBounds, 1) < 0.70,
                "fixture must reproduce the dilution: right-column numeric WORD fraction is "
                        + numericWordFraction(lines, grid.colBounds, 1));

        assertNull(grid.hardReject,
                "a 2-column table whose right column is numeric on 6 of its 7 rows must not be "
                        + "hard-rejected as non-numeric; hardReject=" + grid.hardReject);
        assertTrue(grid.confidence >= StreamTableExtractor.STREAM_CONFIDENCE_MIN,
                "and it must clear the admission bar, got " + grid.confidence);
        assertTrue(grid.numericLeanColumn, "its numeric data column must be recognised");
    }

    /** Two-column running prose carrying incidental numerals must STILL be hard-rejected: it has a
     *  non-trivial numeric word fraction but no fully-numeric row at all. */
    @Test
    void twoColumnProseWithIncidentalNumeralsIsStillRejected() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 95, y, "leftcolumnprosethatwrapsacrosslines"));
            // right column: a numeral plus prose on every row -- never an all-numeric row
            ws.add(w(110, 130, y, String.valueOf(2000 + r)));
            ws.add(w(134, 195, y, "continuingprosetext"));
        }
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        var grid = StreamTableExtractor.scoreGrid(lines, List.of(gutter(96, 108)), 10, 195);
        assertEquals(0.0, grid.confidence, 1e-9,
                "2-column prose with incidental numerals must stay suppressed, got " + grid.confidence);
    }

    /** A single numeric row inside an otherwise textual column is not a numeric data column. */
    @Test
    void twoColumnTableWithOneStrayNumericRowIsStillRejected() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        String[] left  = {"Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel"};
        String[] right = {"Ready", "Ready", "Pending", "Ready", "Pending", "Ready", "Ready", "12"};
        for (int r = 0; r < left.length; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 60, y, left[r]));
            ws.add(w(100, 140, y, right[r]));
        }
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        var grid = StreamTableExtractor.scoreGrid(lines, List.of(gutter(62, 98)), 10, 140);
        assertEquals(0.0, grid.confidence, 1e-9,
                "one stray numeric row out of 8 is not a numeric data column, got " + grid.confidence);
        assertEquals("cols==2-nonnumeric", grid.hardReject);
    }

    /** The pre-existing all-non-numeric 2-column case is unchanged. */
    @Test
    void twoColumnAllTextIsStillRejected() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 60, y, "Label" + r));
            ws.add(w(100, 150, y, "Value" + r));
        }
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        var grid = StreamTableExtractor.scoreGrid(lines, List.of(gutter(62, 98)), 10, 150);
        assertEquals(0.0, grid.confidence, 1e-9);
        assertEquals("cols==2-nonnumeric", grid.hardReject);
    }

    /** Production's own word-fraction definition, for the fixture's self-check above. */
    private static double numericWordFraction(List<StreamTableExtractor.Line> lines,
                                              float[] bounds, int col) {
        int tot = 0, num = 0;
        for (StreamTableExtractor.Line l : lines) {
            for (StreamTableExtractor.Word wd : l.words) {
                int c = 0;
                while (c < bounds.length - 2 && wd.cx() >= bounds[c + 1]) c++;
                if (c != col) continue;
                tot++;
                if (wd.numeric) num++;
            }
        }
        return tot == 0 ? 0 : (double) num / tot;
    }
}
