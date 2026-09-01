package com.oai.titanarum;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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

    @Test
    void buildWordsHandlesCtmScaledText() throws Exception {
        // Reviewer-verified follow-up: TextPosition.getFontSizeInPt() is computed from the Tm-only
        // matrix (PDFBox's own javadoc: "the actual rendering may appear bigger or smaller
        // depending on the [cm] transformation matrix") -- it is CTM-BLIND. getTextMatrix() is (per
        // its own javadoc) the effective text RENDERING matrix (Tfs-scaled Tm x CTM), so its Y
        // scaling factor alone IS the correct device-space font size. buildWords' newLine test
        // (Math.abs(gy0 - prevBaseline) > 0.5f * fs) has no ctm-aware floor (unlike `space`, which
        // is floored against the ctm-aware getWidthOfSpace()), so using the CTM-blind
        // getFontSizeInPt() alone under a scaling `cm` produces a wrong (too-small, here) threshold.
        //
        // Fixture: a 2x magnifying `cm` (device space = 2x text space) wraps three groups of real,
        // PDFBox-rendered glyphs (harvested via TableTestPdfs.harvestGlyphs, not hand-built doubles):
        //   1) "RowOne" and "RowTwo": two genuinely separate rows, 30 text-space units (60 device
        //      units) apart -- comfortably past BOTH the wrong (ctm-blind) and correct (ctm-aware)
        //      thresholds, so they must never merge into one word either way (regression guard).
        //   2) "A" then "B": one intended SINGLE word, drawn as two adjacent single-character text
        //      runs (zero x-gap: 'B' starts exactly at 'A''s own measured advance) with a small
        //      y offset between them -- 3.5 text-space units, i.e. 7 device units. That sits
        //      strictly between the WRONG half-threshold (0.5 * getFontSizeInPt()=10 -> 5, since
        //      Tm's own scale is 1 and getFontSizeInPt() never sees the 2x cm) and the CORRECT
        //      half-threshold (0.5 * effective device fs=20 -> 10). Pre-fix, 7 > 5 so newLine
        //      erroneously fires between 'A' and 'B', shattering one word into two single-character
        //      words. Post-fix, 7 is not > 10, so newLine correctly stays quiet and (with the x-gap
        //      at ~0, well under the space-gap floor) 'A' and 'B' correctly accumulate into one "AB"
        //      word.
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(600, 600));
            doc.addPage(page);
            PDType1Font f = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            float fontSize = 10f;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.transform(Matrix.getScaleInstance(2f, 2f)); // cm: device = 2x text space

                cs.beginText(); cs.setFont(f, fontSize);
                cs.newLineAtOffset(10, 100); cs.showText("RowOne"); cs.endText();

                cs.beginText(); cs.setFont(f, fontSize);
                cs.newLineAtOffset(10, 70); cs.showText("RowTwo"); cs.endText();

                float aWidth = f.getStringWidth("A") / 1000f * fontSize;
                cs.beginText(); cs.setFont(f, fontSize);
                cs.newLineAtOffset(10, 30); cs.showText("A"); cs.endText();

                cs.beginText(); cs.setFont(f, fontSize);
                cs.newLineAtOffset(10 + aWidth, 30 - 3.5f); cs.showText("B"); cs.endText();
            }

            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<StreamTableExtractor.Word> words = StreamTableExtractor.buildWords(glyphs);
            List<String> texts = new ArrayList<>();
            for (StreamTableExtractor.Word w : words) texts.add(w.text);

            assertTrue(texts.contains("RowOne"), "expected a 'RowOne' word, got " + texts);
            assertTrue(texts.contains("RowTwo"), "expected a 'RowTwo' word, got " + texts);
            assertFalse(texts.contains("RowOneRowTwo"),
                    "two visually separate rows must not merge into one word: " + texts);

            assertTrue(texts.contains("AB"),
                    "row's words must not be over-split into single characters under ctm-scaled "
                            + "text (pre-fix ctm-blind fs shatters 'AB' into 'A'+'B'): " + texts);
            assertEquals(3, words.size(), "expected exactly 3 words (RowOne, RowTwo, AB): " + texts);
        }
    }
}
