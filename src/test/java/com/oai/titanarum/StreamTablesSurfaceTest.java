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
 * CROSS-SURFACE AUDIT of the borderless-table flag, which is ON by default
 * ({@link PdfTitanArumApp#STREAM_TABLES_DEFAULT}). It is expressible on three independent Java-side
 * surfaces, and each one used to carry its OWN copy of the default:
 *
 * <ol>
 *   <li>the CLI, {@code --stream-tables} -- default from picocli's {@code defaultValue}, applied only
 *       when picocli parses;</li>
 *   <li>the blastbox file-IPC worker, {@code --run <scratch>} + {@code control/job.json}'s
 *       {@code stream_tables} -- default from {@link PdfTitanArumApp#resolveStreamTables} when
 *       Jackson leaves the boxed component {@code null} because the key is absent (that IS the
 *       old-job.json compatibility path);</li>
 *   <li>the programmatic entry ({@code new PdfTitanArumApp()} + {@code setStreamTables} +
 *       {@code callWith}) that the REST server's {@code WorkerPool} uses -- default from the Java
 *       field initializer, and NOT from the {@code @Option} annotation.</li>
 * </ol>
 *
 * <p>All three now read one constant, so they cannot drift; {@code StreamTablesDefaultCoherenceTest}
 * is the tripwire for that (and for the two declarations that are not Java at all -- the
 * {@code jobs.stream_tables} column default and {@code titanarum/engine.py}'s {@code _DEFAULT_JOB}).
 * What THIS class adds is behaviour: it drives each surface for real and checks what actually comes
 * out, in both directions, because agreeing on a constant is not the same as honouring it.
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
        //     src/main/java-server's WorkerPool starts a fresh app from before configureApp copies
        //     the DB row's choice over it.
        boolean programmaticDefault = streamTablesOf(new PdfTitanArumApp());

        // (2) worker: job.json's absent-key resolution.
        boolean jobJsonDefault = PdfTitanArumApp.resolveStreamTables(new ObjectMapper()
                .readValue("{\"input_path\":\"/x\",\"output_dir\":\"/y\"}",
                        PdfTitanArumApp.JobDescriptor.class)
                .streamTables());

        // Parity first: a deliberate future default flip should be told WHICH other surfaces it
        // still has to change, not merely that the value moved.
        assertEquals(cliDefault, programmaticDefault,
                "the @Option defaultValue and the FIELD INITIALIZER are independent defaults: a "
                        + "default flip done only in the annotation would leave every programmatic "
                        + "caller (WorkerPool, callWith) on the old default");
        assertEquals(cliDefault, jobJsonDefault,
                "job.json's absent-key default is a third independent default: flipping the CLI "
                        + "without changing this would make a blastbox job behave differently from "
                        + "the same invocation on the CLI");
        assertEquals(PdfTitanArumApp.STREAM_TABLES_DEFAULT, cliDefault,
                "every Java surface must read STREAM_TABLES_DEFAULT rather than restating it. The "
                        + "shipping default is ON; the DB column default and "
                        + "titanarum/engine.py's _DEFAULT_JOB are the two declarations that cannot "
                        + "share this constant, and StreamTablesDefaultCoherenceTest pins those.");
    }

    @Test
    void preFeatureJobJsonStillDeserializesAndTakesTheShippingDefault() throws Exception {
        // OLD-JOB COMPATIBILITY: a job.json written before stream_tables existed must still bind
        // every field it does carry, and must take the SHIPPING DEFAULT for the key it lacks -- not a
        // hardcoded false. A dispatcher too old to know about the flag is asking for "whatever this
        // binary does by default", which is the same thing the CLI and the REST server would do.
        Map<String, Object> old = preFeatureJob(Path.of("/x.pdf"), Path.of("/y"));
        String json = new ObjectMapper().writeValueAsString(old);
        PdfTitanArumApp.JobDescriptor job =
                new ObjectMapper().readValue(json, PdfTitanArumApp.JobDescriptor.class);

        assertNull(job.streamTables(), "an absent stream_tables key must stay distinguishable from "
                + "an explicit false, which is why the component is a boxed Boolean");
        assertEquals(PdfTitanArumApp.STREAM_TABLES_DEFAULT,
                PdfTitanArumApp.resolveStreamTables(job.streamTables()),
                "absent stream_tables must resolve to the shipping default");
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

    /**
     * ROLLING DEPLOY, new worker + old job.json. The pre-feature job.json shape, byte-for-byte
     * missing the key -- the case a deployed dispatcher/queue can still hand this worker after an
     * upgrade. It must behave like the shipping default, i.e. identically to a CLI run on the same
     * binary, rather than silently inheriting the pre-flip behaviour.
     *
     * <p>This is also the test that pins {@code runWorker} to
     * {@link PdfTitanArumApp#resolveStreamTables} -- a {@code runWorker} that resolved the absent key
     * itself (e.g. {@code job.streamTables() != null && job.streamTables()}) would pass every
     * constant-comparison test and still strand old dispatchers on the old default.
     */
    @Test
    void workerModeLegacyJobJsonWithoutTheFieldTakesTheShippingDefault() throws Exception {
        JsonNode report = runWorker(jobFor("legacy", null), "legacy");
        assertEquals(PdfTitanArumApp.STREAM_TABLES_DEFAULT, methods(report).contains("stream"),
                "a job.json with no stream_tables key must behave as the shipping default ("
                        + PdfTitanArumApp.STREAM_TABLES_DEFAULT + "), got " + methods(report));
    }
}
