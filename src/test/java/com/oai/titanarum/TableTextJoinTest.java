package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * joinText's line-grouping tests, driven against REAL (PDFBox-measured) TextPositions rather
 * than hand-constructed ones, so the exact getYDirAdj()/getHeightDir() geometry PDFBox actually
 * produces for a raised-baseline glyph is what's under test.
 */
class TableTextJoinTest {

    @TempDir
    Path tmp;

    private static List<TextPosition> allPositions(PDDocument doc) throws Exception {
        List<TextPosition> positions = new ArrayList<>();
        var stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String s, List<TextPosition> tps) {
                positions.addAll(tps);
            }
        };
        stripper.getText(doc);
        return positions;
    }

    @Test
    void superscriptStaysOnBaseLineInXOrderAndRealNextLineStillSplits() throws Exception {
        // Reviewer's reproducer (FIX 6): a fixed 2pt line-grouping tolerance is not scaled to
        // font size, so a superscripted glyph (footnote marker) whose baseline is raised more
        // than 2pt sorts as a separate, EARLIER line and is emitted BEFORE the text it
        // annotates -- "1\nTotal" instead of "Total1". This drives joinText with genuine
        // TextPositions (glyph heights/baselines exactly as PDFBox reports them) rather than
        // asserting against assumed geometry.
        Path pdf = tmp.resolve("superscript.pdf");
        TableTestPdfs.superscriptFootnote(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TextPosition> positions = allPositions(doc);
            String joined = TableExtractor.joinText(positions);
            assertEquals("Total1\nnext", joined,
                    "superscript must follow its base text on the SAME line, in x-order; "
                            + "the real second line must still split");
        }
    }

    @Test
    void normalTwoLineCellStillSplitsIntoTwoLines() throws Exception {
        // Regression guard: plain two-line text (no superscript at all) must still split on a
        // real line boundary -- the height-scaled tolerance must not swallow genuine line breaks.
        Path pdf = tmp.resolve("twoline.pdf");
        TableTestPdfs.superscriptFootnote(pdf); // reuse the same fixture, drop the superscript glyph
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TextPosition> positions = allPositions(doc);
            positions.removeIf(tp -> "1".equals(tp.getUnicode()));
            String joined = TableExtractor.joinText(positions);
            assertEquals("Total\nnext", joined, "no superscript present: two real lines, no merging");
        }
    }
}
