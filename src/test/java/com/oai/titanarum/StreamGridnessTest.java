package com.oai.titanarum;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamGridnessTest {
    private static StreamTableExtractor.Word w(float x0, float x1, float yTop, String t) {
        StreamTableExtractor.Word wd = new StreamTableExtractor.Word();
        wd.x0=x0; wd.x1=x1; wd.y0=yTop; wd.y1=yTop+10; wd.text=t;
        wd.numeric = t.matches("[-+(]?[\\d.,%$)]+") && t.chars().anyMatch(Character::isDigit);
        return wd;
    }

    @Test
    void numericGridScoresHigh() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 45, y, "Candidate" + r));
            ws.add(w(70, 88, y, String.valueOf(100 + r)));
            ws.add(w(130,150, y, String.valueOf(r) + ".5"));
        }
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        var g = StreamTableExtractor.findGutters(lines, 10, 150, 6f);
        var grid = StreamTableExtractor.scoreGrid(lines, g, 10, 150);
        assertTrue(grid.confidence >= 0.55, "numeric 3-col grid must clear threshold, got " + grid.confidence);
    }

    @Test
    void twoColumnProseScoresBelowThreshold() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 95, y, "leftcolumnfullofprosethatwraps"));   // fills column width
            ws.add(w(110,195, y, "rightcolumnfullofprosethatwraps"));
        }
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        var g = StreamTableExtractor.findGutters(lines, 10, 195, 6f);
        var grid = StreamTableExtractor.scoreGrid(lines, g, 10, 195);
        assertTrue(grid.confidence < 0.55, "two-column prose must be suppressed, got " + grid.confidence);
    }
}
