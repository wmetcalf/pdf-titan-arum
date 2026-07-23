package com.oai.titanarum;

import com.oai.titanarum.PdfTitanArumApp.AnalysisReport;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * report.tables must be surfaced into the AI digest so table-borne phishing payloads (fake wire /
 * routing / fee grids) are visible to the AI verdict -- but bounded, since a hostile PDF can carry
 * up to 50 tables/page across many pages, each with up to 10k cells.
 */
class OpenAiAnalyzerDigestTest {

    private static String buildDigest(AnalysisReport report, String filename) throws Exception {
        OpenAiAnalyzer analyzer = new OpenAiAnalyzer("test-key", "test-model", "http://localhost:1234/v1");
        Method m = OpenAiAnalyzer.class.getDeclaredMethod("buildDigest", AnalysisReport.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(analyzer, report, filename);
    }

    private static TableExtractor.TableHit smallTable(int page, String... cellText) {
        TableExtractor.TableHit t = new TableExtractor.TableHit();
        t.page = page;
        t.extractionMethod = "lattice";
        t.rowCount = 1;
        t.colCount = cellText.length;
        t.rows = new ArrayList<>();
        t.rows.add(List.of(cellText));
        StringBuilder md = new StringBuilder();
        for (String c : cellText) md.append("| ").append(c).append(" ");
        md.append("|");
        t.markdown = md.toString();
        return t;
    }

    private static TableExtractor.TableHit largeTable(int page) {
        TableExtractor.TableHit t = new TableExtractor.TableHit();
        t.page = page;
        t.extractionMethod = "tagged";
        t.rowCount = 50;
        t.colCount = 20;
        StringBuilder md = new StringBuilder();
        for (int r = 0; r < 50; r++) {
            for (int c = 0; c < 20; c++) md.append("cell").append(r).append('_').append(c).append(" | ");
            md.append('\n');
        }
        t.markdown = md.toString();
        return t;
    }

    private static AnalysisReport baseReport() {
        AnalysisReport report = new AnalysisReport();
        report.pageCount = 1;
        return report;
    }

    @Test
    void twoSmallTablesAppearInDigestWithCellText() throws Exception {
        AnalysisReport report = baseReport();
        report.tables.add(smallTable(1, "Routing #", "021000021"));
        report.tables.add(smallTable(1, "Fee", "$980.00"));

        String digest = buildDigest(report, "wire-instructions.pdf");

        assertTrue(digest.contains("=== TABLES (2) ==="), "digest should contain TABLES section header:\n" + digest);
        assertTrue(digest.contains("$980.00"), "digest should contain the distinctive cell value:\n" + digest);
    }

    @Test
    void manyLargeTablesStayBudgetBoundedAndNoteOmission() throws Exception {
        AnalysisReport report = baseReport();
        for (int i = 0; i < 60; i++) {
            report.tables.add(largeTable((i % 20) + 1));
        }

        String withTables = buildDigest(report, "many-tables.pdf");

        AnalysisReport empty = baseReport();
        String withoutTables = buildDigest(empty, "many-tables.pdf");

        int tablesSectionGrowth = withTables.length() - withoutTables.length();
        assertTrue(tablesSectionGrowth < 5000,
            "tables section should be budget-bounded, grew by " + tablesSectionGrowth + " chars");
        assertTrue(withTables.contains("omitted for length"),
            "digest should note omission when caps are hit:\n" + withTables.substring(0, Math.min(2000, withTables.length())));
        assertTrue(withTables.contains("=== TABLES (60) ==="));
    }

    @Test
    void tablesTruncatedFlagAddsCapNote() throws Exception {
        AnalysisReport report = baseReport();
        report.tables.add(smallTable(1, "A", "B"));
        report.tablesTruncated = Boolean.TRUE;

        String digest = buildDigest(report, "capped.pdf");

        assertTrue(digest.contains("table extraction hit safety caps"),
            "digest should note extraction was capped:\n" + digest);
    }

    @Test
    void nullOrEmptyTablesProduceNoSection() throws Exception {
        AnalysisReport reportNull = baseReport();
        reportNull.tables = null;
        String digestNull = buildDigest(reportNull, "none.pdf");
        assertFalse(digestNull.contains("=== TABLES"), "null tables should produce no TABLES section");

        AnalysisReport reportEmpty = baseReport();
        String digestEmpty = buildDigest(reportEmpty, "none.pdf");
        assertFalse(digestEmpty.contains("=== TABLES"), "empty tables should produce no TABLES section");
    }

    @Test
    void tableMarkdownTruncationDoesNotSplitSurrogatePair() throws Exception {
        // An astral-plane glyph (emoji, 2 UTF-16 chars) placed exactly at the per-table
        // 800-char cap boundary must not be cut mid-pair (a lone high surrogate becomes '?'
        // once the HTTP body is UTF-8 encoded).
        String emoji = new String(Character.toChars(0x1F600)); // 2 chars: high+low surrogate
        StringBuilder md = new StringBuilder();
        while (md.length() < 799) md.append('x');            // fill to index 799
        md.append(emoji).append("yyyy");                     // high surrogate lands at index 799
        TableExtractor.TableHit t = new TableExtractor.TableHit();
        t.page = 1; t.extractionMethod = "lattice"; t.rowCount = 1; t.colCount = 1;
        t.rows = new ArrayList<>(); t.rows.add(List.of("x"));
        t.markdown = md.toString();
        AnalysisReport report = baseReport();
        report.tables = new ArrayList<>();
        report.tables.add(t);

        String digest = buildDigest(report, "surrogate.pdf");
        // The digest, once UTF-8 round-tripped (as the HTTP body would be), must contain no
        // U+FFFD / '?' corruption from a split pair: every char is a valid scalar value.
        byte[] utf8 = digest.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String roundTrip = new String(utf8, java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(digest, roundTrip, "digest must survive UTF-8 round-trip with no surrogate corruption");
        assertFalse(digest.chars().anyMatch(c -> Character.isHighSurrogate((char) c)
                && digest.indexOf(c) == digest.length() - 1),
                "no trailing lone high surrogate");
        // Sanity: no unpaired surrogate anywhere.
        for (int i = 0; i < digest.length(); i++) {
            char c = digest.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < digest.length() && Character.isLowSurrogate(digest.charAt(i + 1)),
                        "high surrogate at " + i + " must be followed by a low surrogate");
            }
        }
    }
}
