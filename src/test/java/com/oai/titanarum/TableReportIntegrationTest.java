package com.oai.titanarum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
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

    // ------------------------------------------------------ --stream-tables (ON by default)

    @Test
    void borderlessTableIsPresentByDefault() throws Exception {
        // The default is ON: a page with no rulings and no tags still yields its table. Ruled/tagged
        // extraction alone scores 0.0000 on genuinely borderless documents, which is why this flipped.
        Path pdf = tmp.resolve("borderless.pdf");
        TableTestPdfs.borderless3Col(pdf);
        JsonNode report = runApp(pdf, tmp.resolve("outS1"));
        JsonNode tables = report.get("tables");
        assertNotNull(tables);
        assertEquals(1, tables.size(),
                "borderless extraction is ON by default, so the CLI must find this table with no "
                        + "flags at all");
        assertEquals("stream", tables.get(0).get("extractionMethod").asText());
    }

    /**
     * THE OFF SWITCH on the CLI. A default cannot be acceptable unless it is reversible per
     * invocation, and this fixture is only findable by the stream path -- so 0 tables here is proof
     * the stage really was disabled, not merely that nothing matched.
     */
    @Test
    void streamTablesCanBeTurnedOffPerInvocation() throws Exception {
        Path pdf = tmp.resolve("borderless.pdf");
        TableTestPdfs.borderless3Col(pdf);
        JsonNode report = runApp(pdf, tmp.resolve("outS1off"), "--stream-tables=false");
        assertNotNull(report.get("tables"));
        assertEquals(0, report.get("tables").size(),
                "--stream-tables=false must disable the borderless path entirely");
    }

    /**
     * REGRESSION GUARD for picocli's boolean inversion: a bare {@code @Option(defaultValue="true")}
     * makes the flag's own name turn the feature OFF. Passing the flag must still mean ON.
     */
    @Test
    void passingTheFlagExplicitlyStillMeansOn() throws Exception {
        Path pdf = tmp.resolve("borderless.pdf");
        TableTestPdfs.borderless3Col(pdf);
        JsonNode report = runApp(pdf, tmp.resolve("outS1on"), "--stream-tables");
        assertEquals(1, report.get("tables").size(),
                "--stream-tables must still ENABLE borderless extraction; if this emits 0 tables the "
                        + "option was turned into a plain boolean flag and picocli inverted it");
        assertEquals("stream", report.get("tables").get(0).get("extractionMethod").asText());
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
        // The blastbox engine writes job.json; stream_tables must ride the same path skip_tables
        // does ALL THE WAY to the extractor, not merely reach the setter -- a bare field store
        // (the old version of this test: assertDoesNotThrow(() -> app.setStreamTables(...))) cannot
        // fail no matter what setStreamTables does, so it covered nothing. This drives the real
        // job.json -> JobDescriptor -> setStreamTables -> callWith -> TableExtractor.extract path
        // against a borderless fixture and asserts the stream table actually comes out the other end.
        String json = "{\"input_path\":\"/x\",\"output_dir\":\"/y\",\"stream_tables\":true}";
        PdfTitanArumApp.JobDescriptor job =
                new ObjectMapper().readValue(json, PdfTitanArumApp.JobDescriptor.class);
        assertTrue(job.streamTables(), "job.json's stream_tables must bind to the descriptor");

        Path pdf = tmp.resolve("borderless.pdf");
        TableTestPdfs.borderless3Col(pdf);
        Path out = tmp.resolve("outJobDescriptor");
        Files.createDirectories(out);

        PdfTitanArumApp app = new PdfTitanArumApp();
        app.setSkipScreenshots(true);
        app.setSkipImages(true);
        app.setSkipPageExport(true);
        app.setStreamTables(job.streamTables()); // the line actually under test

        byte[] pdfBytes = Files.readAllBytes(pdf);
        PdfTitanArumApp.AnalysisReport report = app.callWith(
                pdfBytes, "borderless.pdf", out, 150f, "1",
                /* skipQrScan */ true, /* addLinkAnnotations */ false,
                /* modifiedPdfOutput */ null, /* password */ null);

        assertEquals(1, report.tables.size(),
                "job.json's stream_tables=true must reach TableExtractor.extract and surface the "
                        + "borderless table, not merely land in a setter: " + report.tables);
        TableExtractor.TableHit t = report.tables.get(0);
        assertEquals("stream", t.extractionMethod);
        assertNotNull(t.confidence, "a stream table emitted via the job.json path still carries confidence");
    }

    @Test
    void jobDescriptorResolvesAnAbsentStreamTablesToTheShippingDefault() throws Exception {
        PdfTitanArumApp.JobDescriptor job = new ObjectMapper()
                .readValue("{\"input_path\":\"/x\"}", PdfTitanArumApp.JobDescriptor.class);
        assertNull(job.streamTables(),
                "an absent key must remain distinguishable from an explicit false");
        assertEquals(PdfTitanArumApp.STREAM_TABLES_DEFAULT,
                PdfTitanArumApp.resolveStreamTables(job.streamTables()),
                "absent stream_tables must mean the shipping default, so a blastbox job matches an "
                        + "equivalent CLI run on the same binary");
    }

    /** ...and an explicit false in job.json must still win against a default-ON build. */
    @Test
    void jobDescriptorExplicitFalseTurnsTheStreamPathOff() throws Exception {
        PdfTitanArumApp.JobDescriptor job = new ObjectMapper()
                .readValue("{\"input_path\":\"/x\",\"stream_tables\":false}",
                        PdfTitanArumApp.JobDescriptor.class);
        assertFalse(PdfTitanArumApp.resolveStreamTables(job.streamTables()),
                "\"stream_tables\": false in job.json is the blastbox off switch");
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
