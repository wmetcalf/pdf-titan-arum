package com.oai.titanarum;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamBuildTableTest {

    /** Render a borderless numeric grid to a real PDF, harvest its glyphs, run extractPage. */
    @Test
    void extractsBorderlessNumericGrid() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 300));
            doc.addPage(page);
            PDType1Font f = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            String[][] data = {
                {"Region","Votes","Pct"},
                {"North","1200","41.2"},
                {"South","900","30.9"},
                {"East","450","15.4"},
                {"West","360","12.5"}
            };
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(f, 11);
                float[] colX = {40, 150, 230};
                for (int r = 0; r < data.length; r++) {
                    float y = 260 - r * 30;      // PDF-native bottom-left origin
                    for (int c = 0; c < 3; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y);
                        cs.showText(data[r][c]); cs.endText();
                    }
                }
            }
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<TableExtractor.TableHit> hits = StreamTableExtractor.extractPage(1, glyphs);
            assertEquals(1, hits.size(), "expected one stream table");
            TableExtractor.TableHit t = hits.get(0);
            assertEquals("stream", t.extractionMethod);
            assertNotNull(t.confidence);
            assertTrue(t.confidence >= 0.55);
            assertEquals(3, t.colCount);
            assertEquals(5, t.rowCount);
            assertEquals("Region", t.rows.get(0).get(0));
            assertEquals("1200", t.rows.get(1).get(1));
        }
    }

    /**
     * A borderless numeric grid (same shape/spirit as {@link #extractsBorderlessNumericGrid}: 3
     * columns, 5 rows, no rulings), routed through the 3-arg {@link
     * StreamTableExtractor#extractPage(int, List, GutterFinder)} overload with a non-default
     * {@link GapVotingGutterFinder} -- proves the pluggable-finder seam the bake-off harness needs
     * actually drives the full pipeline (not just {@code findGutters} in isolation).
     *
     * <p>Deliberately uses fixed-DIGIT-COUNT numbers in every column (unlike {@link
     * #extractsBorderlessNumericGrid}'s proportional-width city names), so every row's per-column
     * glyph width -- and therefore every row's inter-word gap-vote midpoint -- is (near-)identical
     * (Helvetica digits are fixed-width; verified empirically: two 3-digit numbers land on the
     * exact same x1). {@code GapVotingGutterFinder} clusters votes with a tight tolerance (0.5 *
     * medianSpace) relative to the RUNNING MEAN of each cluster (single-linkage): real-world
     * proportional-width labels (e.g. "Region"/"North"/"East") spread each column's gap-vote
     * midpoints by several points across rows -- comfortably more than that tolerance -- which
     * fragments the vote cluster into several sub-clusters, none of which alone reaches
     * gapvote's own 60%-of-lines support floor, so gapvote finds ZERO gutters on that literal
     * fixture (confirmed empirically) even though breuel finds both cleanly. That is a genuine,
     * useful bake-off finding about gapvote's real-world robustness (see
     * GutterFinderContractTest for the dedicated per-finder contract coverage) -- not a defect in
     * this seam. This fixture exists solely to exercise the SEAM in isolation, so its geometry is
     * tuned to a case gapvote's own algorithm handles cleanly.
     */
    @Test
    void extractPageAcceptsAlternateGutterFinder() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 300));
            doc.addPage(page);
            PDType1Font f = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            String[][] data = {
                {"100","1200","41"},
                {"200","1100","30"},
                {"300","1000","15"},
                {"400","1300","12"},
                {"500","1250","10"}
            };
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(f, 11);
                float[] colX = {40, 150, 230};
                for (int r = 0; r < data.length; r++) {
                    float y = 260 - r * 30;      // PDF-native bottom-left origin
                    for (int c = 0; c < 3; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y);
                        cs.showText(data[r][c]); cs.endText();
                    }
                }
            }
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<TableExtractor.TableHit> hits =
                    StreamTableExtractor.extractPage(1, glyphs, new GapVotingGutterFinder());
            assertEquals(1, hits.size(), "expected one stream table via the alternate gutter finder");
            TableExtractor.TableHit t = hits.get(0);
            assertEquals("stream", t.extractionMethod);
            assertEquals(3, t.colCount);
            assertEquals(5, t.rowCount);
            assertEquals("100", t.rows.get(0).get(0));
            assertEquals("1100", t.rows.get(1).get(1));
        }
    }
}
