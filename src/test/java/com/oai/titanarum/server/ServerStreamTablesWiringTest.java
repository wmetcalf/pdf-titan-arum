package com.oai.titanarum.server;

import com.oai.titanarum.PdfTitanArumApp;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The HTTP server's leg of the borderless ("stream") table flag.
 *
 * <p>WHY THIS EXISTS. The server configures {@link PdfTitanArumApp} by DIRECT SETTERS, not by
 * deserialising a {@code JobDescriptor}, so it does not inherit the {@code @JsonProperty} bindings
 * that carry the flag on the blastbox file-IPC path. Before this class, {@code setStreamTables} was
 * never called anywhere under {@code src/main/java-server}: an operator who ticked the box got no
 * error, no warning and no stream extraction. A missing setter on this path FAILS SILENTLY, which
 * is precisely the failure mode a test has to pin.
 *
 * <p>Note that the sibling flag {@code skipTables} had NO server-level test at all — this class adds
 * coverage the sibling lacked, and covers both flags so the two cannot be confused for each other.
 *
 * <p>Two seams are pinned here, both reachable without a database:
 * <ol>
 *   <li>{@link WorkerPool#configureApp} — the DB row's flags actually land on the app instance;</li>
 *   <li>{@code JobRepository.mapRow} — the {@code jobs} row is mapped onto the {@link Job} record
 *       components in the right ORDER. Inserting {@code stream_tables} in the middle of a 29-column
 *       positional constructor is exactly the edit that silently shifts every later boolean by one,
 *       and boolean/boolean shifts cannot be caught by the compiler.</li>
 * </ol>
 */
class ServerStreamTablesWiringTest {

    // ------------------------------------------------------------------------------------ helpers

    /** Reads a private field off the triage app — none of the field-only options have getters. */
    private static boolean appFlag(PdfTitanArumApp app, String field) throws Exception {
        Field f = PdfTitanArumApp.class.getDeclaredField(field);
        f.setAccessible(true);
        return (boolean) f.get(app);
    }

    private static int appInt(PdfTitanArumApp app, String field) throws Exception {
        Field f = PdfTitanArumApp.class.getDeclaredField(field);
        f.setAccessible(true);
        return (int) f.get(app);
    }

    private static String appString(PdfTitanArumApp app, String field) throws Exception {
        Field f = PdfTitanArumApp.class.getDeclaredField(field);
        f.setAccessible(true);
        return (String) f.get(app);
    }

    /**
     * A {@link Job} with every non-flag column fixed and the boolean flags supplied. Flag order
     * matches the record's component order so a caller reads like the row does.
     */
    private static Job job(boolean skipScreenshots, boolean skipImages, boolean skipPhones,
                           boolean skipTables, boolean streamTables,
                           boolean skipPageExport, boolean skipTextUrls, boolean skipQr,
                           boolean ocrScreenshots, boolean ocrUrlCrops,
                           boolean addLinkAnnotations, boolean noSkipBlanks,
                           String ocrLang, Integer timeoutSeconds) {
        return new Job(
            UUID.randomUUID(), "in.pdf", "deadbeef", null,
            null, null, null, "pending", null, null, null, null,
            skipScreenshots, skipImages, skipPhones, skipTables, streamTables,
            skipPageExport, skipTextUrls, skipQr, ocrScreenshots, ocrUrlCrops,
            null, null, null, addLinkAnnotations, noSkipBlanks, ocrLang, timeoutSeconds);
    }

    /** The all-false job an operator who ticks nothing produces. */
    private static Job defaultJob() {
        return job(false, false, false, false, false, false, false, false, false, false,
                   false, false, null, null);
    }

    // --------------------------------------------------- 1. the flag reaches the app instance

    @Test
    void streamTablesTrueReachesTheAppInstance() throws Exception {
        PdfTitanArumApp app = new PdfTitanArumApp();
        WorkerPool.configureApp(app, job(false, false, false, false, true,
                false, false, false, false, false, false, false, null, null), "eng", 60);
        assertTrue(appFlag(app, "streamTables"),
                "a job with stream_tables=true must call setStreamTables(true)");
    }

    @Test
    void defaultJobLeavesStreamTablesOff() throws Exception {
        PdfTitanArumApp app = new PdfTitanArumApp();
        assertFalse(appFlag(app, "streamTables"),
                "a fresh triage app must default the stream path OFF");
        WorkerPool.configureApp(app, defaultJob(), "eng", 60);
        assertFalse(appFlag(app, "streamTables"),
                "the default server path must still yield streamTables=false");
    }

    /**
     * The two table flags are ORTHOGONAL, and the obvious copy-paste bug when adding the second one
     * is to wire it to the first. All four combinations must survive the trip independently.
     */
    @Test
    void skipTablesAndStreamTablesAreIndependent() throws Exception {
        for (boolean skip : new boolean[]{false, true}) {
            for (boolean stream : new boolean[]{false, true}) {
                PdfTitanArumApp app = new PdfTitanArumApp();
                WorkerPool.configureApp(app, job(false, false, false, skip, stream,
                        false, false, false, false, false, false, false, null, null), "eng", 60);
                assertEquals(skip, appFlag(app, "skipTables"),
                        "skipTables lost for (skip=" + skip + ", stream=" + stream + ")");
                assertEquals(stream, appFlag(app, "streamTables"),
                        "streamTables lost for (skip=" + skip + ", stream=" + stream + ")");
            }
        }
    }

    /**
     * REGRESSION GUARD for the whole setter block, not just the new flag. {@code streamTables} was
     * dropped here because nothing asserted the block was complete; alternating values mean any
     * flag wired to its neighbour, or omitted entirely, shows up as a failure.
     */
    @Test
    void everyJobFlagReachesTheAppInstance() throws Exception {
        PdfTitanArumApp app = new PdfTitanArumApp();
        WorkerPool.configureApp(app, job(
                /* skipScreenshots  */ true,
                /* skipImages       */ false,
                /* skipPhones       */ true,
                /* skipTables       */ false,
                /* streamTables     */ true,
                /* skipPageExport   */ false,
                /* skipTextUrls     */ true,
                /* skipQr           */ false,   // not a setter — passed to callWith, see below
                /* ocrScreenshots   */ true,
                /* ocrUrlCrops      */ false,
                /* addLinkAnnots    */ false,   // not a setter — passed to callWith
                /* noSkipBlanks     */ true,
                /* ocrLang          */ "deu",
                /* timeoutSeconds   */ 42), "eng", 60);

        assertTrue(appFlag(app, "skipScreenshots"));
        assertFalse(appFlag(app, "skipImages"));
        assertTrue(appFlag(app, "skipPhones"));
        assertFalse(appFlag(app, "skipTables"));
        assertTrue(appFlag(app, "streamTables"));
        assertFalse(appFlag(app, "skipPageExport"));
        assertTrue(appFlag(app, "skipTextUrls"));
        assertTrue(appFlag(app, "ocrScreenshots"));
        assertFalse(appFlag(app, "ocrUrlCrops"));
        assertTrue(appFlag(app, "noSkipBlanks"));
        assertEquals("deu", appString(app, "ocrLang"));
        assertEquals(42, appInt(app, "timeoutSeconds"));
    }

    /** The pool-level fallbacks {@code processJob} relied on must survive the extraction. */
    @Test
    void poolDefaultsApplyWhenTheJobDoesNotOverrideThem() throws Exception {
        PdfTitanArumApp app = new PdfTitanArumApp();
        WorkerPool.configureApp(app, defaultJob(), "eng+deu", 123);
        assertEquals("eng+deu", appString(app, "ocrLang"));
        assertEquals(123, appInt(app, "timeoutSeconds"));
    }

    // --------------------------------------------------- 2. the row maps onto the right component

    /** Column name -> value, backing a {@link ResultSet} stand-in for {@code mapRow}. */
    private static ResultSet rowOf(Map<String, Object> cols) {
        InvocationHandler h = (proxy, method, args) -> {
            String name = method.getName();
            if ("toString".equals(name)) return "FakeRow" + cols;
            if ("equals".equals(name)) return proxy == args[0];
            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
            if (args == null || args.length == 0 || !(args[0] instanceof String col)) {
                throw new UnsupportedOperationException("mapRow used " + name + " by index");
            }
            // Postgres raises this when a column is absent, so a mapper reading a column the
            // migration forgot to add fails loudly here rather than returning a silent false.
            if (!cols.containsKey(col)) throw new SQLException("column \"" + col + "\" does not exist");
            Object v = cols.get(col);
            return switch (name) {
                case "getBoolean" -> v != null && (Boolean) v;
                case "getString"  -> v;
                case "getObject"  -> (args.length == 2 && args[1] instanceof Class<?> t) ? t.cast(v) : v;
                default -> throw new UnsupportedOperationException("mapRow used " + name);
            };
        };
        return (ResultSet) Proxy.newProxyInstance(
                ServerStreamTablesWiringTest.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, h);
    }

    private static Job mapRow(ResultSet rs) throws Exception {
        Method m = JobRepository.class.getDeclaredMethod("mapRow", ResultSet.class);
        m.setAccessible(true);
        try {
            return (Job) m.invoke(new JobRepository(null), rs);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

    /** Alternating flags, so any one-column shift in the positional constructor is visible. */
    private static Map<String, Object> fullRow(boolean streamTables) {
        Map<String, Object> cols = new LinkedHashMap<>();
        cols.put("id", UUID.randomUUID());
        cols.put("filename", "in.pdf");
        cols.put("file_hash", "deadbeef");
        cols.put("pdf_object_hash", "cafebabe");
        cols.put("submitted_at", OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        cols.put("started_at", null);
        cols.put("finished_at", null);
        cols.put("status", "pending");
        cols.put("worker_host", "host-a");
        cols.put("error_text", null);
        cols.put("report", null);
        cols.put("artifact_root", "/artifacts/x");
        cols.put("skip_screenshots", true);
        cols.put("skip_images", false);
        cols.put("skip_phones", true);
        cols.put("skip_tables", false);
        cols.put("stream_tables", streamTables);
        cols.put("skip_page_export", false);
        cols.put("skip_text_urls", true);
        cols.put("skip_qr", false);
        cols.put("ocr_screenshots", true);
        cols.put("ocr_url_crops", false);
        cols.put("password", null);
        cols.put("dpi", 200f);
        cols.put("pages_spec", "1-3");
        cols.put("add_link_annotations", true);
        cols.put("no_skip_blanks", false);
        cols.put("ocr_lang", "deu");
        cols.put("timeout_seconds", 42);
        return cols;
    }

    @Test
    void mapRowReadsStreamTablesIntoTheRightComponent() throws Exception {
        Job on = mapRow(rowOf(fullRow(true)));
        assertTrue(on.streamTables(), "stream_tables=t must map onto Job.streamTables()");
        assertFalse(on.skipTables(), "skip_tables must not be shifted by the new column");

        Job off = mapRow(rowOf(fullRow(false)));
        assertFalse(off.streamTables(), "stream_tables=f must map onto Job.streamTables()");
    }

    @Test
    void mapRowKeepsEveryOtherColumnAligned() throws Exception {
        Job j = mapRow(rowOf(fullRow(true)));
        assertEquals("in.pdf", j.filename());
        assertEquals("deadbeef", j.fileHash());
        assertEquals("cafebabe", j.pdfObjectHash());
        assertEquals("pending", j.status());
        assertEquals("host-a", j.workerHost());
        assertEquals("/artifacts/x", j.artifactRoot());
        assertTrue(j.skipScreenshots());
        assertFalse(j.skipImages());
        assertTrue(j.skipPhones());
        assertFalse(j.skipTables());
        assertTrue(j.streamTables());
        assertFalse(j.skipPageExport());
        assertTrue(j.skipTextUrls());
        assertFalse(j.skipQr());
        assertTrue(j.ocrScreenshots());
        assertFalse(j.ocrUrlCrops());
        assertEquals(Float.valueOf(200f), j.dpi());
        assertEquals("1-3", j.pagesSpec());
        assertTrue(j.addLinkAnnotations());
        assertFalse(j.noSkipBlanks());
        assertEquals("deu", j.ocrLang());
        assertEquals(Integer.valueOf(42), j.timeoutSeconds());
    }

    /**
     * A mapper that stopped reading {@code stream_tables} would be indistinguishable from one that
     * read a false — so prove the mapper really does touch the column, by taking it away.
     */
    @Test
    void mapRowActuallyReadsTheStreamTablesColumn() {
        Map<String, Object> withoutColumn = fullRow(true);
        withoutColumn.remove("stream_tables");
        SQLException e = assertThrows(SQLException.class, () -> mapRow(rowOf(withoutColumn)));
        assertTrue(e.getMessage().contains("stream_tables"),
                "mapRow must read stream_tables; got: " + e.getMessage());
    }
}
