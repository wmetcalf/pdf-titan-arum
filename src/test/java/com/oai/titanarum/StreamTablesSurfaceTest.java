package com.oai.titanarum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CROSS-SURFACE AUDIT of the borderless-table opt-in. The flag is expressible on three
 * independent Java-side surfaces, and each one carries its OWN copy of the default:
 *
 * <ol>
 *   <li>the CLI, {@code --stream-tables} -- default from picocli's {@code defaultValue="false"},
 *       applied only when picocli parses;</li>
 *   <li>the blastbox file-IPC worker, {@code --run <scratch>} + {@code control/job.json}'s
 *       {@code stream_tables} -- default from Jackson leaving a {@code boolean} record component
 *       at {@code false} when the key is absent (that IS the old-job.json compatibility path);</li>
 *   <li>the programmatic entry ({@code new PdfTitanArumApp()} + {@code setStreamTables} +
 *       {@code callWith}) that the REST server's {@code WorkerPool} uses -- default from the Java
 *       field initializer, i.e. {@code false}, and NOT from the {@code @Option} annotation.</li>
 * </ol>
 *
 * <p>Because those three defaults are independent, editing {@code defaultValue} alone would flip
 * the CLI while leaving the worker and the server on the old default -- a silent per-surface
 * divergence. {@link #everySurfaceAgreesOnTheDefault()} pins them equal, so a future default flip
 * has to be made deliberately on every surface (and in {@code titanarum/engine.py}'s
 * {@code _DEFAULT_JOB}, which {@code tests/test_fileipc.py} pins on the Python side).
 *
 * <p>The worker-mode tests here drive the REAL {@code --run} control loop (pre-staged
 * {@code job.json} + {@code control.go}) rather than calling a setter, so they cover the whole
 * job.json -> JobDescriptor -> setStreamTables -> callWith -> TableExtractor path end to end.
 */
class StreamTablesSurfaceTest {

    @TempDir
    Path tmp;

    private static boolean streamTablesOf(PdfTitanArumApp app) throws Exception {
        Field f = PdfTitanArumApp.class.getDeclaredField("streamTables");
        f.setAccessible(true);
        return (boolean) f.get(app);
    }

    /** Every key titanarum/engine.py's _DEFAULT_JOB wrote BEFORE stream_tables existed. */
    private static Map<String, Object> preFeatureJob(Path pdf, Path outDir) {
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("input_path", pdf.toString());
        job.put("output_dir", outDir.toString());
        job.put("filename_hint", "borderless.pdf");
        job.put("sha256", "a".repeat(64));
        job.put("dpi", 150.0);
        job.put("pages", "default");
        job.put("skip_qr", true);
        job.put("skip_screenshots", true);
        job.put("skip_images", true);
        job.put("skip_phones", false);
        job.put("skip_tables", false);
        job.put("skip_page_export", true);
        job.put("skip_text_urls", false);
        job.put("no_skip_blanks", false);
        job.put("ocr_screenshots", false);
        job.put("ocr_url_crops", false);
        job.put("ocr_lang", "eng");
        job.put("add_link_annotations", false);
        job.put("timeout_seconds", 0);
        job.put("titanarum_version", "1.3.0");
        return job;
    }

    /** Drives the real file-IPC worker over one job.json and returns the report it wrote. */
    private JsonNode runWorker(Map<String, Object> job, String tag) throws Exception {
        Path scratch = tmp.resolve("scratch-" + tag);
        Path control = scratch.resolve("control");
        Files.createDirectories(control);
        new ObjectMapper().writeValue(control.resolve("job.json").toFile(), job);
        Files.createFile(control.resolve("control.go"));

        int exit = new CommandLine(new PdfTitanArumApp()).execute("--run", scratch.toString());
        assertEquals(0, exit, "worker mode must exit 0 after writing report.json");
        Path report = Path.of(job.get("output_dir").toString()).resolve("report.json");
        assertTrue(Files.isRegularFile(report), "worker must write report.json");
        return new ObjectMapper().readTree(report.toFile());
    }

    private static List<String> methods(JsonNode report) {
        JsonNode tables = report.get("tables");
        assertNotNull(tables, "report must always carry tables[]");
        List<String> out = new ArrayList<>();
        tables.forEach(t -> out.add(t.get("extractionMethod").asText()));
        return out;
    }

    private Map<String, Object> jobFor(String tag, Boolean streamTables) throws Exception {
        Path pdf = tmp.resolve(tag + ".pdf");
        TableTestPdfs.borderless3Col(pdf);
        Path outDir = tmp.resolve("out-" + tag);
        Files.createDirectories(outDir);
        Map<String, Object> job = preFeatureJob(pdf, outDir);
        if (streamTables != null) job.put("stream_tables", streamTables);
        return job;
    }

    // ------------------------------------------------------------------ surface 1: the CLI banner

    @Test
    void usageBannerAdvertisesBothTableFlags() {
        // A user's only discovery path for an opt-in flag is --help. This also fails loudly if the
        // flag is ever renamed on one surface (job.json's stream_tables) but not the other.
        String usage = new CommandLine(new PdfTitanArumApp()).getUsageMessage(CommandLine.Help.Ansi.OFF);
        assertTrue(usage.contains("--stream-tables"), "--help must advertise --stream-tables:\n" + usage);
        assertTrue(usage.contains("--skip-tables"), "--help must advertise --skip-tables:\n" + usage);
    }

    // ------------------------------------------------------- the default, on all three surfaces

    @Test
    void everySurfaceAgreesOnTheDefault() throws Exception {
        // (1) CLI: picocli's defaultValue, applied on parse.
        PdfTitanArumApp parsed = new PdfTitanArumApp();
        new CommandLine(parsed).parseArgs("--input", tmp.resolve("x.pdf").toString(),
                "--output", tmp.resolve("y").toString());
        boolean cliDefault = streamTablesOf(parsed);

        // (3) programmatic/server: the field initializer, with NO picocli parse -- this is the value
        //     src/main/java-server's WorkerPool.processJob runs with (it never calls
        //     setStreamTables at all, so the REST surface cannot express the flag today).
        boolean programmaticDefault = streamTablesOf(new PdfTitanArumApp());

        // (2) worker: Jackson's absent-boolean default for job.json.
        boolean jobJsonDefault = new ObjectMapper()
                .readValue("{\"input_path\":\"/x\",\"output_dir\":\"/y\"}",
                        PdfTitanArumApp.JobDescriptor.class)
                .streamTables();

        // Parity first: a deliberate future default flip should be told WHICH other surfaces it
        // still has to change, not merely that the value moved.
        assertEquals(cliDefault, programmaticDefault,
                "the @Option defaultValue and the FIELD INITIALIZER are independent defaults: a "
                        + "default flip done only in the annotation would leave every programmatic "
                        + "caller (WorkerPool.processJob, callWith) on the old default");
        assertEquals(cliDefault, jobJsonDefault,
                "job.json's absent-key default is a third independent default: flipping the CLI "
                        + "without changing this would make a blastbox job behave differently from "
                        + "the same invocation on the CLI");
        assertFalse(cliDefault,
                "the shipping default is OFF on every surface; flipping it is a product decision "
                        + "that also has to move titanarum/engine.py's _DEFAULT_JOB and the "
                        + "README, so this assertion is the tripwire, not a typo");
    }

    @Test
    void preFeatureJobJsonStillDeserializesAndIsOff() throws Exception {
        // OLD-JOB COMPATIBILITY: a job.json written before stream_tables existed must still bind
        // every field it does carry, and must behave as default-off (not merely "not crash").
        Map<String, Object> old = preFeatureJob(Path.of("/x.pdf"), Path.of("/y"));
        String json = new ObjectMapper().writeValueAsString(old);
        PdfTitanArumApp.JobDescriptor job =
                new ObjectMapper().readValue(json, PdfTitanArumApp.JobDescriptor.class);

        assertFalse(job.streamTables(), "absent stream_tables must mean OFF");
        assertEquals(150.0f, job.dpi(), "an old job.json's other fields must still bind");
        assertEquals("default", job.pages());
        assertTrue(job.skipQr());
        assertFalse(job.skipTables());
        assertEquals("eng", job.ocrLang());
        // titanarum_version is not a JobDescriptor component; unknown keys must not break parsing.
        assertEquals("borderless.pdf", job.filenameHint());
    }

    // -------------------------------------------- surface 2: the real --run file-IPC worker loop

    @Test
    void workerModeStreamTablesTrueEmitsStreamTable() throws Exception {
        JsonNode report = runWorker(jobFor("on", true), "on");
        assertEquals(List.of("stream"), methods(report),
                "job.json stream_tables=true must reach TableExtractor through the real worker loop");
        JsonNode t = report.get("tables").get(0);
        assertTrue(t.has("confidence"), "a stream hit from the worker path still carries confidence");
        double conf = t.get("confidence").asDouble();
        assertTrue(conf > 0.0 && conf <= 1.0, "confidence must be a probability, got " + conf);
    }

    @Test
    void workerModeStreamTablesFalseEmitsNoStreamTable() throws Exception {
        JsonNode report = runWorker(jobFor("off", false), "off");
        assertFalse(methods(report).contains("stream"),
                "stream_tables=false must not produce a stream table: " + methods(report));
        assertEquals(List.of(), methods(report),
                "the borderless fixture has no rulings and no tags, so OFF means no tables at all");
    }

    @Test
    void workerModeLegacyJobJsonWithoutTheFieldIsOff() throws Exception {
        // The pre-feature job.json shape, byte-for-byte missing the key -- the case a deployed
        // dispatcher/queue can still hand this worker after an upgrade.
        JsonNode report = runWorker(jobFor("legacy", null), "legacy");
        assertFalse(methods(report).contains("stream"),
                "a job.json with no stream_tables key must behave as default-OFF: " + methods(report));
    }
}
