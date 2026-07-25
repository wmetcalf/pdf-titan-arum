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

    // -------------------------------------------------------------- --stream-tables (opt-in)

    @Test
    void borderlessTableIsAbsentByDefault() throws Exception {
        // The conservative default for a security-triage tool: borderless extraction is OFF, so a
        // page with no rulings and no tags emits no table at all unless asked for.
        Path pdf = tmp.resolve("borderless.pdf");
        TableTestPdfs.borderless3Col(pdf);
        JsonNode report = runApp(pdf, tmp.resolve("outS1"));
        assertNotNull(report.get("tables"));
        assertEquals(0, report.get("tables").size(),
                "borderless extraction must be opt-in, not the default");
    }

    @Test
    void streamTablesFlagSurfacesBorderlessTableWithConfidence() throws Exception {
        Path pdf = tmp.resolve("borderless.pdf");
        TableTestPdfs.borderless3Col(pdf);
        JsonNode report = runApp(pdf, tmp.resolve("outS2"), "--stream-tables");
        JsonNode tables = report.get("tables");
        assertEquals(1, tables.size());
        JsonNode t = tables.get(0);
        assertEquals("stream", t.get("extractionMethod").asText());
        assertEquals(3, t.get("colCount").asInt());
        assertEquals(5, t.get("rowCount").asInt());
        assertEquals("Region", t.get("rows").get(0).get(0).asText());
        assertTrue(t.has("confidence"),
                "downstream consumers must be able to filter stream tables by confidence");
        double conf = t.get("confidence").asDouble();
        assertTrue(conf > 0.0 && conf <= 1.0, "confidence must be a real probability, got " + conf);
    }

    @Test
    void skipTablesBeatsStreamTables() throws Exception {
        // --skip-tables disables ALL table extraction; the two flags are orthogonal and the skip
        // wins, so an operator cannot re-enable a stage they just turned off.
        Path pdf = tmp.resolve("borderless.pdf");
        TableTestPdfs.borderless3Col(pdf);
        JsonNode report = runApp(pdf, tmp.resolve("outS3"), "--stream-tables", "--skip-tables");
        assertNotNull(report.get("tables"));
        assertEquals(0, report.get("tables").size());
    }

    @Test
    void streamTablesDoesNotDisturbRuledExtraction() throws Exception {
        Path pdf = tmp.resolve("grid.pdf");
        TableTestPdfs.ruled3x3(pdf);
        JsonNode report = runApp(pdf, tmp.resolve("outS4"), "--stream-tables");
        assertEquals(1, report.get("tables").size());
        JsonNode t = report.get("tables").get(0);
        assertEquals("lattice", t.get("extractionMethod").asText());
        assertFalse(t.has("confidence"), "lattice tables must not grow a confidence field");
    }

    @Test
    void streamTablesFlowsThroughTheJobDescriptor() throws Exception {
        // The blastbox engine writes job.json; stream_tables must ride the same path skip_tables does.
        String json = "{\"input_path\":\"/x\",\"output_dir\":\"/y\",\"stream_tables\":true}";
        PdfTitanArumApp.JobDescriptor job =
                new ObjectMapper().readValue(json, PdfTitanArumApp.JobDescriptor.class);
        assertTrue(job.streamTables(), "job.json's stream_tables must bind to the descriptor");
        PdfTitanArumApp app = new PdfTitanArumApp();
        assertDoesNotThrow(() -> app.setStreamTables(job.streamTables()));
    }

    @Test
    void jobDescriptorDefaultsStreamTablesOff() throws Exception {
        PdfTitanArumApp.JobDescriptor job = new ObjectMapper()
                .readValue("{\"input_path\":\"/x\"}", PdfTitanArumApp.JobDescriptor.class);
        assertFalse(job.streamTables(), "absent stream_tables must mean OFF, never on");
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
