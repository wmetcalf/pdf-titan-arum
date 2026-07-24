package com.oai.titanarum;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamGutterTest {

    private static StreamTableExtractor.Word w(float x0, float x1, float yTop, String t) {
        StreamTableExtractor.Word wd = new StreamTableExtractor.Word();
        wd.x0=x0; wd.x1=x1; wd.y0=yTop; wd.y1=yTop+10; wd.text=t;
        wd.numeric = t.matches("[-+(]?[\\d.,%$)]+");
        return wd;
    }

    /** 3-column numeric grid: label | col1 | col2, clear gutters at ~x=60 and ~x=120. */
    private static List<StreamTableExtractor.Line> grid() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 6; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 45, y, "Row" + r));
            ws.add(w(70, 90, y, String.valueOf(r * 3)));
            ws.add(w(130,150, y, String.valueOf(r * 7)));
        }
        return StreamTableExtractor.buildLines(ws, 10f);
    }

    @Test
    void findsTwoInteriorGuttersInThreeColumnGrid() {
        List<StreamTableExtractor.Gutter> g =
            StreamTableExtractor.findGutters(grid(), 10, 150, 6f);
        assertEquals(2, g.size(), "expect 2 interior gutters -> 3 columns");
        assertTrue(g.get(0).cx() > 45 && g.get(0).cx() < 70);
        assertTrue(g.get(1).cx() > 90 && g.get(1).cx() < 130);
        assertTrue(g.get(0).rowsCovered >= 5);
    }

    @Test
    void singleCentralGutterInTwoColumnProse() {
        // two blocks of text with one central gutter -> exactly 1 interior gutter
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 90, y, "leftcolumnprosetext"));
            ws.add(w(110, 190, y, "rightcolumnprosetext"));
        }
        List<StreamTableExtractor.Gutter> g =
            StreamTableExtractor.findGutters(StreamTableExtractor.buildLines(ws, 10f), 10, 190, 6f);
        assertEquals(1, g.size());
    }
}
