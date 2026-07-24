package com.oai.titanarum;

import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
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

    // ---------------------------------------------------------------- buildWords, driven on
    // ---------------------------------------------------------------- real TextPosition objects
    //
    // TextPosition (org.apache.pdfbox.text.TextPosition) is a final class with a public
    // constructor -- no PDF rendering/parsing is needed to get REAL instances that
    // getXDirAdj()/getYDirAdj()/getWidthDirAdj()/getHeightDir()/getFontSizeInPt()/
    // getWidthOfSpace() answer exactly as buildWords expects, so these tests build them
    // directly rather than harvesting glyphs from a rendered PDF (no such helper exists yet).
    //
    // With page rotation 0 and an unrotated (scale=1, no shear) text matrix, TextPosition's own
    // getDir() resolves to 0 ("left to right"), which makes:
    //   getXDirAdj()      == textMatrix.getTranslateX()                  (== x we pass in)
    //   getYDirAdj()      == pageHeight - textMatrix.getTranslateY()     (== yTop we pass in)
    //   getWidthDirAdj()  == |endX - textMatrix.getTranslateX()|         (== width we pass in)
    //   getHeightDir()    == maxHeight                                   (== height we pass in)
    //   getFontSizeInPt() * getTextMatrix().getScalingFactorY() == fontSize (scaleY resolves to 1)
    // so every input buildWords reads is under direct, exact control.

    private static final float PAGE_W = 2_000_000f;
    private static final float PAGE_H = 1_000f;

    private static TextPosition glyph(float x, float yTop, float width, float height, float fontSize,
                                       float spaceWidth, String unicode) {
        Matrix textMatrix = Matrix.getTranslateInstance(x, PAGE_H - yTop);
        return new TextPosition(0, PAGE_W, PAGE_H, textMatrix, x + width, PAGE_H - yTop, height, width,
                spaceWidth, unicode, new int[]{unicode.codePointAt(0)}, null, fontSize, (int) fontSize);
    }

    /** One "letter glyph, explicit space glyph" pair per word, all on one line, immediately
     * adjacent in x (so a word only ever ends via the WHITESPACE branch, never via the
     * newLine/gap branches) -- exactly how most real PDFs encode inter-word spaces. */
    private static List<TextPosition> wordsSeparatedByExplicitSpaceGlyphs(int wordCount) {
        List<TextPosition> glyphs = new ArrayList<>(wordCount * 2);
        float x = 0;
        for (int i = 0; i < wordCount; i++) {
            glyphs.add(glyph(x, 100, 5, 10, 10, 3, "a"));
            x += 5;
            glyphs.add(glyph(x, 100, 3, 10, 10, 3, " "));
            x += 3;
        }
        return glyphs;
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

    @Test
    void buildWordsSplitsOnExplicitWhitespaceGlyphs() {
        // Sanity/end-to-end check: buildWords, driven on real TextPosition objects, actually
        // terminates each word at its explicit space glyph (not just at gaps/newlines) and
        // produces one Word per letter-space pair, in order.
        List<TextPosition> glyphs = wordsSeparatedByExplicitSpaceGlyphs(5);
        List<StreamTableExtractor.Word> words = StreamTableExtractor.buildWords(glyphs);
        assertEquals(5, words.size());
        for (StreamTableExtractor.Word word : words) assertEquals("a", word.text);
    }

    @Test
    void buildWordsEnforcesCapOnWhitespaceTerminatedWords() {
        // Finding 1 regression test. Every word here is appended to `out` from the
        // WHITESPACE-termination branch (a letter glyph immediately followed by an explicit
        // space glyph, on one line, no gap/newline break) -- the branch whose `continue` used to
        // jump past the MAX_STREAM_WORDS cap check.
        //
        // Note on why the word count is EXACTLY MAX_STREAM_WORDS + 1 (not some larger overage):
        // pre-fix, a whitespace-terminated word that pushes `out` over the cap is only "missed"
        // by the cap check for the ONE iteration in which its trailing space glyph is processed
        // (that check is deferred, not skipped forever) -- the very next glyph in the list, being
        // non-whitespace, falls into the `cur == null` branch and reaches the cap check at the
        // bottom of the loop body anyway, so the bug is invisible unless the list ends before any
        // such "next glyph" exists. Making the (MAX_STREAM_WORDS + 1)-th word's trailing space the
        // LAST glyph in the entire list removes that safety net: pre-fix, the loop ends (via
        // `continue`) immediately after this word's finishWord() call with no further iteration
        // to retroactively catch the overflow, and the unchecked post-loop `finishWord` call is
        // also a no-op here (cur is null) -- so pre-fix, buildWords returns normally with 60_001
        // words instead of throwing. Post-fix, the cap check runs immediately after every
        // finishWord() call (including this one), so it throws right away.
        int wordCount = StreamTableExtractor.MAX_STREAM_WORDS + 1;
        List<TextPosition> glyphs = wordsSeparatedByExplicitSpaceGlyphs(wordCount);
        assertTrue(glyphs.size() < StreamTableExtractor.MAX_STREAM_GLYPHS,
                "sanity: must stay under the glyph cap so only the word cap can explain the throw");
        assertThrows(TableExtractor.RulingOverflowException.class,
                () -> StreamTableExtractor.buildWords(glyphs),
                "word cap must be enforced even when the overflowing word terminates on an explicit "
                        + "whitespace glyph at the very end of the glyph list");
    }
}
