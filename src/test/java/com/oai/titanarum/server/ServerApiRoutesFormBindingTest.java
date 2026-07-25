package com.oai.titanarum.server;

import com.oai.titanarum.PdfTitanArumApp;
import io.javalin.Javalin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code POST /api/jobs} form binding, driven over real HTTP through the real
 * {@link ApiRoutes#wire} route so the multipart decoding, the {@code boolForm} coercion and the
 * POSITIONAL argument order of {@link JobRepository#insert} are all exercised together.
 *
 * <p>The positional call is the hazard: {@code insert} takes nine consecutive booleans, so adding
 * {@code streamTables} in the middle of the list can silently shift the operator's other choices by
 * one and the compiler cannot notice. Every request below therefore sends ALTERNATING values across
 * the whole form and asserts the whole recorded argument tuple, not just the new flag.
 *
 * <p>{@code skipTables} had no test at this level either — this is coverage the sibling lacked.
 */
class ServerApiRoutesFormBindingTest {

    /** Records the arguments {@link ApiRoutes} passes to {@code insert}. No database involved. */
    static final class RecordingRepo extends JobRepository {
        Map<String, Object> args;

        RecordingRepo() { super(null); }

        @Override
        public UUID insert(String filename, String fileHash,
                           boolean skipScreenshots, boolean skipImages, boolean skipPhones,
                           boolean skipTables, boolean streamTables,
                           boolean skipPageExport, boolean skipTextUrls, boolean skipQr,
                           boolean ocrScreenshots, boolean ocrUrlCrops,
                           String password,
                           Float dpi, String pagesSpec,
                           boolean addLinkAnnotations, boolean noSkipBlanks,
                           String ocrLang, Integer timeoutSeconds) throws SQLException {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("filename", filename);
            a.put("skipScreenshots", skipScreenshots);
            a.put("skipImages", skipImages);
            a.put("skipPhones", skipPhones);
            a.put("skipTables", skipTables);
            a.put("streamTables", streamTables);
            a.put("skipPageExport", skipPageExport);
            a.put("skipTextUrls", skipTextUrls);
            a.put("skipQr", skipQr);
            a.put("ocrScreenshots", ocrScreenshots);
            a.put("ocrUrlCrops", ocrUrlCrops);
            a.put("password", password);
            a.put("dpi", dpi);
            a.put("pagesSpec", pagesSpec);
            a.put("addLinkAnnotations", addLinkAnnotations);
            a.put("noSkipBlanks", noSkipBlanks);
            a.put("ocrLang", ocrLang);
            a.put("timeoutSeconds", timeoutSeconds);
            this.args = a;
            return UUID.randomUUID();
        }
    }

    /** Minimal {@link ArtifactStore} over a temp dir. */
    static final class TempStore implements ArtifactStore {
        private final Path root;
        TempStore(Path root) { this.root = root.toAbsolutePath().normalize(); }
        @Override public Path resolveArtifactRoot(UUID jobId) throws java.io.IOException {
            Path p = root.resolve(jobId.toString());
            Files.createDirectories(p);
            return p;
        }
        @Override public Path getRootPath() { return root; }
        @Override public void deleteJob(UUID jobId) { }
        @Override public void zipJob(Path artifactDir, OutputStream out) { }
    }

    @TempDir Path tmp;

    private Javalin app;
    private RecordingRepo repo;
    private int port;

    @BeforeEach
    void startServer() {
        repo = new RecordingRepo();
        TempStore store = new TempStore(tmp);
        // Constructed but never started(): no worker loop, no threads, submit() only enqueues.
        WorkerPool pool = new WorkerPool(repo, store, 1);
        app = Javalin.create(cfg -> cfg.http.maxRequestSize = 16L * 1024 * 1024);
        ApiRoutes.wire(app, repo, store, pool);
        app.start("127.0.0.1", 0);
        port = app.port();
    }

    @AfterEach
    void stopServer() {
        if (app != null) app.stop();
    }

    // ------------------------------------------------------------------------------------ helpers

    private static final String BOUNDARY = "----titanarumTestBoundary";

    /** Builds a multipart/form-data body: one PDF part plus the supplied text fields in order. */
    private static byte[] multipart(byte[] pdf, List<String[]> fields) throws Exception {
        var out = new ByteArrayOutputStream();
        var head = "--" + BOUNDARY + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"probe.pdf\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(pdf);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        for (String[] f : fields) {
            String part = "--" + BOUNDARY + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + f[0] + "\"\r\n\r\n"
                    + f[1] + "\r\n";
            out.write(part.getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private HttpResponse<String> postJob(List<String[]> fields) throws Exception {
        byte[] body = multipart("%PDF-1.4 probe".getBytes(StandardCharsets.UTF_8), fields);
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/jobs"))
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .header("X-Requested-With", "XMLHttpRequest")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static List<String[]> checked(String... names) {
        List<String[]> f = new ArrayList<>();
        for (String n : names) f.add(new String[]{n, "on"});   // what a browser checkbox sends
        return f;
    }

    // -------------------------------------------------------------------------------------- tests

    @Test
    void streamTablesCheckboxReachesTheRepository() throws Exception {
        HttpResponse<String> r = postJob(checked("streamTables"));
        assertEquals(202, r.statusCode(), r.body());
        assertNotNull(repo.args, "insert was never called");
        assertEquals(Boolean.TRUE, repo.args.get("streamTables"),
                "streamTables=on must bind to the streamTables argument");
        assertEquals(Boolean.FALSE, repo.args.get("skipTables"),
                "an unchecked skipTables must not be dragged true by the new field");
        assertEquals(Boolean.FALSE, repo.args.get("skipPageExport"),
                "the argument after streamTables must not be shifted");
    }

    /**
     * A raw API client that never sends the field -- e.g. every {@code curl} script written before the
     * flag existed -- must get the SHIPPING DEFAULT, not false. This is the declaration that actually
     * governs REST submissions: {@code JobRepository.insert} always supplies the column, so the
     * database's own DEFAULT never comes into play for a job submitted through this route.
     */
    @Test
    void omittingStreamTablesYieldsTheShippingDefault() throws Exception {
        HttpResponse<String> r = postJob(List.of());
        assertEquals(202, r.statusCode(), r.body());
        assertEquals(PdfTitanArumApp.STREAM_TABLES_DEFAULT, repo.args.get("streamTables"),
                "a form with no streamTables field must resolve to STREAM_TABLES_DEFAULT");
    }

    /** Every truthy encoding must reach the field. */
    @Test
    void streamTablesAcceptsEveryTruthyEncoding() throws Exception {
        for (String v : new String[]{"on", "true", "1", "yes"}) {
            repo.args = null;
            HttpResponse<String> r = postJob(List.<String[]>of(new String[]{"streamTables", v}));
            assertEquals(202, r.statusCode(), r.body());
            assertEquals(Boolean.TRUE, repo.args.get("streamTables"), "encoding rejected: " + v);
        }
    }

    /** THE REST OFF SWITCH: every falsey encoding must actually disable a default-ON flag. */
    @Test
    void streamTablesAcceptsEveryFalseyEncoding() throws Exception {
        for (String v : new String[]{"off", "false", "0", "no"}) {
            repo.args = null;
            HttpResponse<String> r = postJob(List.<String[]>of(new String[]{"streamTables", v}));
            assertEquals(202, r.statusCode(), r.body());
            assertEquals(Boolean.FALSE, repo.args.get("streamTables"),
                    "a default-ON flag must be switchable off per job; encoding failed: " + v);
        }
    }

    /**
     * An UNRECOGNISED value falls back to the default rather than silently disabling the stage. For a
     * default-ON detection stage a typo that quietly turns it off is the worse outcome.
     */
    @Test
    void anUnrecognisedStreamTablesValueFallsBackToTheDefault() throws Exception {
        HttpResponse<String> r = postJob(List.<String[]>of(new String[]{"streamTables", "maybe"}));
        assertEquals(202, r.statusCode(), r.body());
        assertEquals(PdfTitanArumApp.STREAM_TABLES_DEFAULT, repo.args.get("streamTables"));
    }

    /**
     * THE BROWSER'S ENCODING. An unchecked HTML checkbox submits nothing, so the template pairs a
     * hidden {@code false} with the checkbox and the LAST value wins. Both of the sequences a real
     * browser can produce are exercised here, because getting this wrong makes the UI checkbox
     * impossible to untick -- and {@code formParam} (first value) would do exactly that.
     */
    @Test
    void theHiddenFieldPlusCheckboxPairFromTheBrowserWorksBothWays() throws Exception {
        repo.args = null;
        assertEquals(202, postJob(List.<String[]>of(
                new String[]{"streamTables", "false"})).statusCode());
        assertEquals(Boolean.FALSE, repo.args.get("streamTables"),
                "hidden field alone (checkbox unticked) must mean OFF");

        repo.args = null;
        assertEquals(202, postJob(List.<String[]>of(
                new String[]{"streamTables", "false"},
                new String[]{"streamTables", "true"})).statusCode());
        assertEquals(Boolean.TRUE, repo.args.get("streamTables"),
                "hidden field THEN checkbox (ticked) must mean ON -- the last value has to win, so "
                        + "this field cannot be read with formParam()");
    }

    /**
     * FULL-FORM ALIGNMENT. Alternating checked/unchecked across every boolean, plus every scalar,
     * so a positional slip anywhere in the 19-argument insert call shows up.
     */
    @Test
    void everyFormFieldBindsToItsOwnArgument() throws Exception {
        List<String[]> fields = new ArrayList<>();
        fields.add(new String[]{"skipScreenshots", "on"});
        // skipImages omitted
        fields.add(new String[]{"skipPhones", "on"});
        // skipTables omitted
        fields.add(new String[]{"streamTables", "on"});
        // skipPageExport omitted
        fields.add(new String[]{"skipTextUrls", "on"});
        // skipQr omitted
        fields.add(new String[]{"ocrScreenshots", "on"});
        // ocrUrlCrops omitted
        fields.add(new String[]{"addLinkAnnotations", "on"});
        // noSkipBlanks omitted
        fields.add(new String[]{"password", "s3cret"});
        fields.add(new String[]{"dpi", "200"});
        fields.add(new String[]{"pagesSpec", "1-3"});
        fields.add(new String[]{"ocrLang", "deu"});
        fields.add(new String[]{"timeoutSeconds", "42"});

        HttpResponse<String> r = postJob(fields);
        assertEquals(202, r.statusCode(), r.body());

        assertEquals(Boolean.TRUE,  repo.args.get("skipScreenshots"));
        assertEquals(Boolean.FALSE, repo.args.get("skipImages"));
        assertEquals(Boolean.TRUE,  repo.args.get("skipPhones"));
        assertEquals(Boolean.FALSE, repo.args.get("skipTables"));
        assertEquals(Boolean.TRUE,  repo.args.get("streamTables"));
        assertEquals(Boolean.FALSE, repo.args.get("skipPageExport"));
        assertEquals(Boolean.TRUE,  repo.args.get("skipTextUrls"));
        assertEquals(Boolean.FALSE, repo.args.get("skipQr"));
        assertEquals(Boolean.TRUE,  repo.args.get("ocrScreenshots"));
        assertEquals(Boolean.FALSE, repo.args.get("ocrUrlCrops"));
        assertEquals(Boolean.TRUE,  repo.args.get("addLinkAnnotations"));
        assertEquals(Boolean.FALSE, repo.args.get("noSkipBlanks"));
        assertEquals("s3cret", repo.args.get("password"));
        assertEquals(Float.valueOf(200f), repo.args.get("dpi"));
        assertEquals("1-3", repo.args.get("pagesSpec"));
        assertEquals("deu", repo.args.get("ocrLang"));
        assertEquals(Integer.valueOf(42), repo.args.get("timeoutSeconds"));
        assertEquals("probe.pdf", repo.args.get("filename"));
    }

    /**
     * The UI is the only way most operators will reach the flag, so the checkbox has to exist and
     * carry the exact name {@code boolForm} looks up. A renamed checkbox fails silently.
     */
    @Test
    void jobListTemplateExposesTheCheckbox() throws Exception {
        Path template = Path.of("src/main/resources/templates/job-list.html");
        assertTrue(Files.isRegularFile(template), "template not found at " + template.toAbsolutePath());
        String html = Files.readString(template);
        assertTrue(html.contains("name=\"streamTables\""),
                "job-list.html must offer a streamTables control");
        assertTrue(html.contains("type=\"checkbox\" name=\"streamTables\" value=\"true\" checked"),
                "the streamTables checkbox must be CHECKED, matching the ON default, and must carry "
                        + "an explicit value=\"true\"");
        assertTrue(html.contains("type=\"hidden\" name=\"streamTables\" value=\"false\""),
                "the checkbox needs its paired hidden false: an unticked checkbox submits nothing, "
                        + "which for a default-ON flag is indistinguishable from 'field omitted' and "
                        + "would leave the box impossible to untick");
        assertTrue(html.indexOf("type=\"hidden\" name=\"streamTables\"")
                        < html.indexOf("type=\"checkbox\" name=\"streamTables\""),
                "the hidden field must come BEFORE the checkbox: browsers submit controls in document "
                        + "order and boolFormDefault takes the last value");
    }
}
