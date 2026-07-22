package com.oai.titanarum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Full app run: tables must land in report.json; --skip-tables must empty them. */
class TableReportIntegrationTest {

    @TempDir
    Path tmp;

    private JsonNode runApp(Path pdf, Path out, String... extraArgs) throws Exception {
        String[] base = {"--input", pdf.toString(), "--output", out.toString(),
                "--skip-screenshots", "--skip-images", "--skip-page-export", "--skip-qr"};
        String[] args = java.util.stream.Stream.concat(
                java.util.Arrays.stream(base), java.util.Arrays.stream(extraArgs)).toArray(String[]::new);
        int exit = new CommandLine(new PdfTitanArumApp()).execute(args);
        assertEquals(0, exit);
        return new ObjectMapper().readTree(out.resolve("report.json").toFile());
    }

    @Test
    void ruledTableAppearsInReport() throws Exception {
        Path pdf = tmp.resolve("grid.pdf");
        TableTestPdfs.ruled3x3(pdf);
        JsonNode report = runApp(pdf, tmp.resolve("out1"));
        JsonNode tables = report.get("tables");
        assertNotNull(tables, "report must always contain tables[]");
        assertEquals(1, tables.size());
        JsonNode t = tables.get(0);
        assertEquals("lattice", t.get("extractionMethod").asText());
        assertEquals(3, t.get("rowCount").asInt());
        assertEquals("R1C1", t.get("rows").get(0).get(0).asText());
        assertEquals("R2C2", t.get("rows").get(1).get(1).asText());
        assertTrue(t.get("markdown").asText().contains("| R1C1 |"));
        assertFalse(report.has("tablesTruncated"), "flag only appears when truncation happened");
    }

    @Test
    void skipTablesFlagEmptiesTables() throws Exception {
        Path pdf = tmp.resolve("grid.pdf");
        TableTestPdfs.ruled3x3(pdf);
        JsonNode report = runApp(pdf, tmp.resolve("out2"), "--skip-tables");
        assertNotNull(report.get("tables"));
        assertEquals(0, report.get("tables").size());
    }

    @Test
    void tablesStillExtractedWithSkipTextUrls() throws Exception {
        // region-strip fallback path through the real app
        Path pdf = tmp.resolve("grid.pdf");
        TableTestPdfs.ruled3x3(pdf);
        JsonNode report = runApp(pdf, tmp.resolve("out3"), "--skip-text-urls");
        assertEquals(1, report.get("tables").size());
        assertEquals("R3C3", report.get("tables").get(0).get("rows").get(2).get(2).asText());
    }
}
