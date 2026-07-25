package com.oai.titanarum.server;

import com.oai.titanarum.PdfTitanArumApp;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code stream_tables} column end-to-end against a REAL PostgreSQL: the Flyway migration, the
 * {@link JobRepository} insert/read round trip, {@link JobRepository#claimNext} (the path the worker
 * actually takes), and backward compatibility for a database that already has rows.
 *
 * <p>SKIPPED unless a database is pointed at it, so the suite stays runnable with no infrastructure:
 * <pre>
 *   TITANARUM_TEST_PG_URL=jdbc:postgresql://127.0.0.1:5432/postgres \
 *   TITANARUM_TEST_PG_USER=postgres TITANARUM_TEST_PG_PASSWORD=postgres \
 *   mvn -o -Pserver test -Dtest=ServerJobRepositoryPostgresTest
 * </pre>
 * Each test works inside its own throwaway schema and drops it afterwards, so it never disturbs an
 * existing {@code jobs} table in the target database.
 */
class ServerJobRepositoryPostgresTest {

    private static final String URL  = System.getenv("TITANARUM_TEST_PG_URL");
    private static final String USER = System.getenv().getOrDefault("TITANARUM_TEST_PG_USER", "postgres");
    private static final String PASS = System.getenv().getOrDefault("TITANARUM_TEST_PG_PASSWORD", "postgres");

    private String schema;
    private HikariDataSource ds;

    @BeforeEach
    void createSchema() throws Exception {
        assumeTrue(URL != null && !URL.isBlank(),
                "TITANARUM_TEST_PG_URL not set — skipping PostgreSQL-backed migration test");
        schema = "titanarum_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = DriverManager.getConnection(URL, USER, PASS); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(URL);
        cfg.setUsername(USER);
        cfg.setPassword(PASS);
        cfg.setSchema(schema);
        cfg.setMaximumPoolSize(3);
        ds = new HikariDataSource(cfg);
    }

    @AfterEach
    void dropSchema() throws Exception {
        if (ds != null) ds.close();
        if (schema != null && URL != null && !URL.isBlank()) {
            try (Connection c = DriverManager.getConnection(URL, USER, PASS); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    // ------------------------------------------------------------------------------------ helpers

    private void migrate() {
        Flyway.configure().dataSource(ds).schemas(schema)
              .locations("classpath:db/migration").load().migrate();
    }

    private void migrateTo(String version) {
        Flyway.configure().dataSource(ds).schemas(schema)
              .locations("classpath:db/migration").target(version).load().migrate();
    }

    private boolean columnExists(String column) throws Exception {
        String sql = "SELECT 1 FROM information_schema.columns "
                + "WHERE table_schema = ? AND table_name = 'jobs' AND column_name = ?";
        try (Connection c = ds.getConnection(); var ps = c.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, column);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    /** The INSERT exactly as it stood BEFORE this change: 18 columns, no stream_tables. */
    private UUID insertLegacyRow(String filename) throws Exception {
        String sql = """
            INSERT INTO jobs (filename, file_hash,
                skip_screenshots, skip_images, skip_phones, skip_tables,
                skip_page_export, skip_text_urls, skip_qr,
                ocr_screenshots, ocr_url_crops, password,
                dpi, pages_spec, add_link_annotations, no_skip_blanks,
                ocr_lang, timeout_seconds)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """;
        try (Connection c = ds.getConnection(); var ps = c.prepareStatement(sql)) {
            ps.setString(1, filename);
            ps.setString(2, "legacyhash");
            ps.setBoolean(3, true);    // skip_screenshots
            ps.setBoolean(4, false);   // skip_images
            ps.setBoolean(5, true);    // skip_phones
            ps.setBoolean(6, false);   // skip_tables
            ps.setBoolean(7, false);   // skip_page_export
            ps.setBoolean(8, true);    // skip_text_urls
            ps.setBoolean(9, false);   // skip_qr
            ps.setBoolean(10, true);   // ocr_screenshots
            ps.setBoolean(11, false);  // ocr_url_crops
            ps.setString(12, null);
            ps.setFloat(13, 300f);
            ps.setString(14, "2-4");
            ps.setBoolean(15, true);   // add_link_annotations
            ps.setBoolean(16, false);  // no_skip_blanks
            ps.setString(17, "fra");
            ps.setInt(18, 77);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return (UUID) rs.getObject(1); }
        }
    }

    private static boolean appFlag(PdfTitanArumApp app, String field) throws Exception {
        Field f = PdfTitanArumApp.class.getDeclaredField(field);
        f.setAccessible(true);
        return (boolean) f.get(app);
    }

    // ----------------------------------------------------------------------- 1. the round trip

    @Test
    void streamTablesRoundTripsThroughPostgres() throws Exception {
        migrate();
        assertTrue(columnExists("stream_tables"), "V8 must add the stream_tables column");
        JobRepository repo = new JobRepository(ds);

        UUID on = repo.insert("on.pdf", "h1",
                /* skipScreenshots */ true, /* skipImages */ false, /* skipPhones */ true,
                /* skipTables */ false, /* streamTables */ true,
                /* skipPageExport */ false, /* skipTextUrls */ true, /* skipQr */ false,
                /* ocrScreenshots */ true, /* ocrUrlCrops */ false,
                "pw", 200f, "1-3", /* addLinkAnnotations */ true, /* noSkipBlanks */ false,
                "deu", 42);

        Job j = repo.findById(on).orElseThrow();
        assertTrue(j.streamTables(), "streamTables=true must survive the DB round trip");
        // Alternating neighbours: a positional slip in the INSERT shows up here, not in production.
        assertTrue(j.skipScreenshots());
        assertFalse(j.skipImages());
        assertTrue(j.skipPhones());
        assertFalse(j.skipTables());
        assertFalse(j.skipPageExport());
        assertTrue(j.skipTextUrls());
        assertFalse(j.skipQr());
        assertTrue(j.ocrScreenshots());
        assertFalse(j.ocrUrlCrops());
        assertEquals("pw", j.password());
        assertEquals(Float.valueOf(200f), j.dpi());
        assertEquals("1-3", j.pagesSpec());
        assertTrue(j.addLinkAnnotations());
        assertFalse(j.noSkipBlanks());
        assertEquals("deu", j.ocrLang());
        assertEquals(Integer.valueOf(42), j.timeoutSeconds());

        UUID off = repo.insert("off.pdf", "h2",
                false, false, false, false, /* streamTables */ false,
                false, false, false, false, false,
                null, null, null, false, false, null, null);
        assertFalse(repo.findById(off).orElseThrow().streamTables(),
                "streamTables=false must round trip as false");
    }

    /**
     * END-TO-END through the path the worker really takes: {@code claimNext} (not {@code findById})
     * hands the row to {@code processJob}, which configures the triage app. Proves the operator's
     * choice reaches {@code setStreamTables} with a real database in the loop.
     */
    @Test
    void claimNextCarriesStreamTablesAllTheWayToTheTriageApp() throws Exception {
        migrate();
        JobRepository repo = new JobRepository(ds);

        repo.insert("on.pdf", "h1", false, false, false, false, /* streamTables */ true,
                false, false, false, false, false, null, null, null, false, false, null, null);
        Job claimed = repo.claimNext("test-host").orElseThrow();
        assertTrue(claimed.streamTables(), "claimNext must carry stream_tables");
        PdfTitanArumApp app = new PdfTitanArumApp();
        WorkerPool.configureApp(app, claimed, "eng", 60);
        assertTrue(appFlag(app, "streamTables"),
                "a job submitted with streamTables=true must reach setStreamTables(true)");

        repo.insert("off.pdf", "h2", false, false, false, false, /* streamTables */ false,
                false, false, false, false, false, null, null, null, false, false, null, null);
        Job claimedOff = repo.claimNext("test-host").orElseThrow();
        assertFalse(claimedOff.streamTables());
        PdfTitanArumApp appOff = new PdfTitanArumApp();
        WorkerPool.configureApp(appOff, claimedOff, "eng", 60);
        assertFalse(appFlag(appOff, "streamTables"),
                "the default path must still yield streamTables=false");
    }

    // -------------------------------------------------------------- 2. backward compatibility

    /**
     * An EXISTING database — schema at V7, with rows — must migrate cleanly, and those pre-existing
     * rows must read back as the flag's default (off), i.e. keep the meaning they were created with.
     */
    @Test
    void existingDatabaseMigratesAndOldRowsReadAsTheDefault() throws Exception {
        migrateTo("7");
        assertFalse(columnExists("stream_tables"),
                "precondition: at V7 the column must not exist yet");
        UUID legacy = insertLegacyRow("legacy.pdf");

        // Now upgrade the populated database.
        migrate();
        assertTrue(columnExists("stream_tables"), "V8 must apply to a populated database");

        Job j = new JobRepository(ds).findById(legacy).orElseThrow();
        assertFalse(j.streamTables(),
                "a row inserted before V8 must read as the default (stream path OFF)");
        // ...and the migration must not have disturbed anything else about that row.
        assertEquals("legacy.pdf", j.filename());
        assertEquals("legacyhash", j.fileHash());
        assertTrue(j.skipScreenshots());
        assertFalse(j.skipImages());
        assertTrue(j.skipPhones());
        assertFalse(j.skipTables());
        assertFalse(j.skipPageExport());
        assertTrue(j.skipTextUrls());
        assertFalse(j.skipQr());
        assertTrue(j.ocrScreenshots());
        assertFalse(j.ocrUrlCrops());
        assertEquals(Float.valueOf(300f), j.dpi());
        assertEquals("2-4", j.pagesSpec());
        assertTrue(j.addLinkAnnotations());
        assertFalse(j.noSkipBlanks());
        assertEquals("fra", j.ocrLang());
        assertEquals(Integer.valueOf(77), j.timeoutSeconds());
    }

    /**
     * ROLLING DEPLOY, the other direction: an older application binary still issuing the 18-column
     * INSERT against a database already at V8. The column default has to make that work, otherwise
     * upgrading the DB ahead of the app takes writes down.
     */
    @Test
    void preMigrationInsertStatementStillWorksAgainstTheNewSchema() throws Exception {
        migrate();
        UUID id = insertLegacyRow("old-binary.pdf");
        Job j = new JobRepository(ds).findById(id).orElseThrow();
        assertFalse(j.streamTables(),
                "an INSERT that omits stream_tables must default it to off, not fail");
        assertEquals("old-binary.pdf", j.filename());
    }

    /** Re-running migrate() on an already-migrated database must be a no-op, not an error. */
    @Test
    void migrationIsIdempotent() throws Exception {
        migrate();
        migrate();
        assertTrue(columnExists("stream_tables"));
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT COUNT(*) FROM " + schema + ".flyway_schema_history WHERE version = '8'")) {
            rs.next();
            assertEquals(1, rs.getInt(1), "V8 must be recorded exactly once");
        }
    }
}
