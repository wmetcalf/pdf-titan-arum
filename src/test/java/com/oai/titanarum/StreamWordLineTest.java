package com.oai.titanarum;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamWordLineTest {

    /** Build a fake Word directly (bypasses PDFBox) to unit-test line clustering. */
    private static StreamTableExtractor.Word w(float x0, float y0, float x1, float y1, String t) {
        StreamTableExtractor.Word wd = new StreamTableExtractor.Word();
        wd.x0=x0; wd.y0=y0; wd.x1=x1; wd.y1=y1; wd.text=t;
        wd.numeric = t.matches("[-+(]?[\\d.,%$)]+");
        return wd;
    }

    @Test
    void wordsOnSameBaselineClusterIntoOneLine() {
        List<StreamTableExtractor.Word> words = new ArrayList<>(List.of(
            w(10, 100, 40, 112, "Alpha"),
            w(80, 100, 95, 112, "12"),
            w(150,100,175,112, "34"),
            w(10, 130, 45, 142, "Beta"),   // next row (lower on page = larger y)
            w(80, 130, 96, 142, "56")
        ));
        List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(words, 12f);
        assertEquals(2, lines.size());
        assertEquals(3, lines.get(0).words.size());
        assertEquals("Alpha", lines.get(0).words.get(0).text);
        assertEquals(2, lines.get(1).words.size());
    }

    @Test
    void glyphCapThrows() {
        assertThrows(TableExtractor.RulingOverflowException.class, () -> {
            // 300_001 dummy glyphs -> over MAX_STREAM_GLYPHS
            StreamTableExtractor.enforceGlyphCap(StreamTableExtractor.MAX_STREAM_GLYPHS + 1);
        });
    }
}
