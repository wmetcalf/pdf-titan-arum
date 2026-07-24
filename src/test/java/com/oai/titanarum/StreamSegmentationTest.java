package com.oai.titanarum;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 9d: region-segmentation tests. {@code extractPage} previously treated an entire page as
 * ONE candidate table -- pooling every line (title/caption/footnote included) into a single
 * {@code findGutters}/{@code scoreGrid}/{@code buildHit} call. The dominant, measured consequence
 * (see {@code .superpowers/sdd/task-9c-diagnosis-report.md}) was that row 0 of the produced table
 * was the page's title/running-header text, not the table's real header row -- which, because
 * {@code TableScore} matches cells by exact (row, col, text), shifts every subsequent row index
 * and drives F1 toward 0. These tests exercise the new block-splitting (Step A), per-block
 * detection (Step B), and non-conforming-edge trimming (Step C) that fixes it.
 */
class StreamSegmentationTest {

    private static PDType1Font helv() { return new PDType1Font(Standard14Fonts.FontName.HELVETICA); }

    /**
     * Title + a clean 3-column numeric table + footnote, all on one page. The title/footnote
     * gaps (24pt) are deliberately kept BELOW the block-split threshold (1.6 x the in-table
     * pitch of 20pt = 32pt) -- i.e. small enough that Step A's vertical-gap block splitter does
     * NOT separate them from the table into their own blocks (median gap across all 6 line-to-
     * line transitions is 20, so the threshold is 32; the 24pt title/footnote gaps sit under
     * it). This means title + table + footnote land in ONE block, exactly like the real
     * us-020/us-007/eu-027 cases in the task-9c diagnosis report (single-spaced report pages
     * where the running header/caption sit close enough to the table that no big gap separates
     * them) -- so the regression can ONLY be fixed by Step C's edge-trimming, not by block
     * splitting alone. The title and footnote are each two words placed entirely inside column
     * 0's x-range (left-aligned, well short of the first gutter), while every real table row
     * occupies all 3 columns -- the "all words fall into a single column while the block's
     * other lines occupy 2+" non-conforming rule (Step C) is what must drop them.
     */
    @Test
    void pageTitleIsNotRow0() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 400));
            doc.addPage(page);
            PDType1Font f = helv();
            String[][] data = {
                {"Region", "Votes", "Pct"},
                {"North", "1200", "41.2"},
                {"South", "900", "30.9"},
                {"East", "450", "15.4"},
                {"West", "360", "12.5"}
            };
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(f, 11);
                float[] colX = {40, 150, 230};

                // Title: 2 words, both well inside column 0's x-range (< ~x=100, comfortably
                // left of where the col0/col1 gutter will land near x=110-130).
                float yTitle = 340;
                cs.beginText(); cs.newLineAtOffset(colX[0], yTitle); cs.showText("NET TOTAL"); cs.endText();

                // Table: header + 4 data rows, pitch 20pt, gap from title = 24pt (< 32 threshold).
                float yHeaderRow = yTitle - 24;
                for (int r = 0; r < data.length; r++) {
                    float y = yHeaderRow - r * 20;
                    for (int c = 0; c < 3; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(data[r][c]); cs.endText();
                    }
                }

                // Footnote: 2 words, also inside column 0's x-range, gap from last data row =
                // 24pt (< 32 threshold).
                float yLastRow = yHeaderRow - (data.length - 1) * 20;
                float yFooter = yLastRow - 24;
                cs.beginText(); cs.newLineAtOffset(colX[0], yFooter); cs.showText("SEE NOTES"); cs.endText();
            }
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<TableExtractor.TableHit> hits = StreamTableExtractor.extractPage(1, glyphs);
            assertEquals(1, hits.size(), "expected exactly one stream table (title/footnote trimmed, not separate tables)");
            TableExtractor.TableHit t = hits.get(0);
            assertEquals(3, t.colCount, "colCount must be the table's real 3 columns");
            assertEquals(5, t.rowCount, "rowCount must be the table's real 5 rows (header+4 data), NOT including title/footnote");
            assertEquals("Region", t.rows.get(0).get(0), "row 0 must be the table's header row, not the title");
            assertEquals("Votes", t.rows.get(0).get(1));
            assertEquals("North", t.rows.get(1).get(0), "row 1 must be the first real data row");
        }
    }

    /**
     * Two independent 3-column numeric tables on one page, separated by a large vertical gap
     * (120pt, far more than 1.6x the 20pt in-table pitch's ~32pt threshold) -- Step A's block
     * splitter must separate them into two blocks, and Step B must detect and score each one
     * independently, yielding 2 stream {@code TableHit}s (a page can legitimately contain
     * multiple borderless tables; the old whole-page pipeline could only ever emit one hit for
     * the entire page).
     */
    @Test
    void multipleTablesOnOnePageAreDetectedSeparately() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 500));
            doc.addPage(page);
            PDType1Font f = helv();
            String[][] tableA = {
                {"Region", "Votes", "Pct"},
                {"North", "1200", "41.2"},
                {"South", "900", "30.9"},
                {"East", "450", "15.4"},
                {"West", "360", "12.5"}
            };
            String[][] tableB = {
                {"Team", "Wins", "Losses"},
                {"Hawks", "44", "38"},
                {"Bulls", "39", "43"},
                {"Suns", "51", "31"}
            };
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(f, 11);
                float[] colX = {40, 150, 230};

                float yA = 440;
                for (int r = 0; r < tableA.length; r++) {
                    float y = yA - r * 20;
                    for (int c = 0; c < 3; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(tableA[r][c]); cs.endText();
                    }
                }
                float yALast = yA - (tableA.length - 1) * 20;
                float yB = yALast - 120; // large gap: forces a separate block
                for (int r = 0; r < tableB.length; r++) {
                    float y = yB - r * 20;
                    for (int c = 0; c < 3; c++) {
                        cs.beginText(); cs.newLineAtOffset(colX[c], y); cs.showText(tableB[r][c]); cs.endText();
                    }
                }
            }
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<TableExtractor.TableHit> hits = StreamTableExtractor.extractPage(1, glyphs);
            assertEquals(2, hits.size(), "expected 2 separately-detected stream tables");
            TableExtractor.TableHit first = hits.get(0);
            TableExtractor.TableHit second = hits.get(1);
            assertEquals(5, first.rowCount, "table A: header + 4 data rows");
            assertEquals(3, first.colCount);
            assertEquals("Region", first.rows.get(0).get(0));
            assertEquals(4, second.rowCount, "table B: header + 3 data rows");
            assertEquals(3, second.colCount);
            assertEquals("Team", second.rows.get(0).get(0));
        }
    }

    /**
     * A page of only scattered prose "paragraphs" -- 4 separate 2-line blocks (each well below
     * the 3-line-per-block minimum from Step B), spaced far enough apart (100pt gaps vs. a
     * 15pt in-paragraph pitch, comfortably over the 1.6x-median-pitch split threshold) that
     * Step A correctly splits them into 4 distinct blocks, each of which must be rejected by
     * Step B's "at least 3 lines" floor. No stream table should be produced.
     */
    @Test
    void blockWithTooFewLinesIsRejected() throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(300, 600));
            doc.addPage(page);
            PDType1Font f = helv();
            String[][] paragraphs = {
                {"This is a simple prose line", "continuing on for a bit more"},
                {"Another short paragraph begins", "and wraps onto a second line"},
                {"A third little snippet starts", "then keeps going a while longer"},
                {"Finally a fourth paragraph here", "trails off after this second line"}
            };
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(f, 11);
                float y = 560;
                for (String[] para : paragraphs) {
                    for (String line : para) {
                        cs.beginText(); cs.newLineAtOffset(40, y); cs.showText(line); cs.endText();
                        y -= 15;
                    }
                    y -= 100; // large inter-paragraph gap
                }
            }
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
            List<TableExtractor.TableHit> hits = StreamTableExtractor.extractPage(1, glyphs);
            assertEquals(0, hits.size(), "scattered prose paragraphs (each < 3 lines per block) must yield no stream tables");
        }
    }
}
