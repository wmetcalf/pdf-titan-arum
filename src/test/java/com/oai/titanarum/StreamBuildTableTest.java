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
}
