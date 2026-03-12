package com.oai.titanarum.server;

import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;

public class JobRepository {

    private final HikariDataSource ds;

    public JobRepository(HikariDataSource ds) {
        this.ds = ds;
    }

    public UUID insert(String filename, String fileHash,
                       boolean skipScreenshots, boolean skipImages, boolean skipPhones,
                       boolean skipPageExport, boolean skipTextUrls, boolean skipQr,
                       boolean ocrScreenshots, boolean ocrUrlCrops,
                       String password,
                       Float dpi, String pagesSpec,
                       boolean addLinkAnnotations, boolean noSkipBlanks,
                       String ocrLang, Integer timeoutSeconds) throws SQLException {
        String sql = """
            INSERT INTO jobs (filename, file_hash,
                skip_screenshots, skip_images, skip_phones,
                skip_page_export, skip_text_urls, skip_qr,
                ocr_screenshots, ocr_url_crops, password,
                dpi, pages_spec, add_link_annotations, no_skip_blanks,
                ocr_lang, timeout_seconds)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
            """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, filename);
            ps.setString(2, fileHash);
            ps.setBoolean(3, skipScreenshots);
            ps.setBoolean(4, skipImages);
            ps.setBoolean(5, skipPhones);
            ps.setBoolean(6, skipPageExport);
            ps.setBoolean(7, skipTextUrls);
            ps.setBoolean(8, skipQr);
            ps.setBoolean(9, ocrScreenshots);
            ps.setBoolean(10, ocrUrlCrops);
            ps.setString(11, (password != null && !password.isEmpty()) ? password : null);
            if (dpi != null) ps.setFloat(12, dpi); else ps.setNull(12, java.sql.Types.REAL);
            ps.setString(13, (pagesSpec != null && !pagesSpec.isBlank()) ? pagesSpec : null);
            ps.setBoolean(14, addLinkAnnotations);
            ps.setBoolean(15, noSkipBlanks);
            ps.setString(16, (ocrLang != null && !ocrLang.isBlank()) ? ocrLang : null);
            if (timeoutSeconds != null) ps.setInt(17, timeoutSeconds); else ps.setNull(17, java.sql.Types.INTEGER);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject(1);
            }
        }
    }

    /** Clears the stored password once the worker has read it — minimise dwell time in DB. */
    public void clearPassword(UUID id) throws SQLException {
        String sql = "UPDATE jobs SET password = NULL WHERE id = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }
    }

    public Optional<Job> claimNext(String workerHost) throws SQLException {
        String sql = """
            UPDATE jobs
            SET status = 'processing', worker_host = ?, started_at = now()
            WHERE id = (
                SELECT id FROM jobs
                WHERE status = 'pending'
                ORDER BY submitted_at
                LIMIT 1
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workerHost);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRow(rs));
            }
        }
    }

    public void markDone(UUID id, String reportJson, String artifactRoot, String pdfObjectHash) throws SQLException {
        String sql = """
            UPDATE jobs SET status = 'done', finished_at = now(),
                report = ?::jsonb, artifact_root = ?, pdf_object_hash = ?
            WHERE id = ?
            """;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reportJson);
            ps.setString(2, artifactRoot);
            ps.setString(3, pdfObjectHash);
            ps.setObject(4, id);
            ps.executeUpdate();
        }
    }

    public void markFailed(UUID id, String errorText) throws SQLException {
        String sql = "UPDATE jobs SET status = 'failed', finished_at = now(), error_text = ? WHERE id = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, errorText);
            ps.setObject(2, id);
            ps.executeUpdate();
        }
    }

    public Optional<Job> findById(UUID id) throws SQLException {
        String sql = "SELECT * FROM jobs WHERE id = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                return Optional.of(mapRow(rs));
            }
        }
    }

    public List<Job> list(int page, int pageSize, String statusFilter) throws SQLException {
        pageSize = Math.min(pageSize, 100);
        String sql = statusFilter == null
            ? "SELECT * FROM jobs ORDER BY submitted_at DESC LIMIT ? OFFSET ?"
            : "SELECT * FROM jobs WHERE status = ? ORDER BY submitted_at DESC LIMIT ? OFFSET ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (statusFilter != null) {
                ps.setString(1, statusFilter);
                ps.setInt(2, pageSize);
                ps.setLong(3, (long) page * pageSize); // M6: prevent int overflow on large page numbers
            } else {
                ps.setInt(1, pageSize);
                ps.setLong(2, (long) page * pageSize); // M6: prevent int overflow on large page numbers
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<Job> result = new ArrayList<>();
                while (rs.next()) result.add(mapRow(rs));
                return result;
            }
        }
    }

    public boolean delete(UUID id) throws SQLException {
        String sql = "DELETE FROM jobs WHERE id = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            return ps.executeUpdate() > 0;
        }
    }

    public int count(String statusFilter) throws SQLException {
        String sql = statusFilter == null
            ? "SELECT COUNT(*) FROM jobs"
            : "SELECT COUNT(*) FROM jobs WHERE status = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            if (statusFilter != null) ps.setString(1, statusFilter);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    public void resetOrphanedJobs(String workerHost) throws SQLException {
        String sql = "UPDATE jobs SET status = 'pending', worker_host = NULL, started_at = NULL WHERE status = 'processing' AND worker_host = ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, workerHost);
            int n = ps.executeUpdate();
            if (n > 0) System.err.println("INFO: reset " + n + " orphaned jobs from previous run");
        }
    }

    private Job mapRow(ResultSet rs) throws SQLException {
        return new Job(
            (UUID) rs.getObject("id"),
            rs.getString("filename"),
            rs.getString("file_hash"),
            rs.getString("pdf_object_hash"),
            rs.getObject("submitted_at", OffsetDateTime.class),
            rs.getObject("started_at", OffsetDateTime.class),
            rs.getObject("finished_at", OffsetDateTime.class),
            rs.getString("status"),
            rs.getString("worker_host"),
            rs.getString("error_text"),
            rs.getString("report"),
            rs.getString("artifact_root"),
            rs.getBoolean("skip_screenshots"),
            rs.getBoolean("skip_images"),
            rs.getBoolean("skip_phones"),
            rs.getBoolean("skip_page_export"),
            rs.getBoolean("skip_text_urls"),
            rs.getBoolean("skip_qr"),
            rs.getBoolean("ocr_screenshots"),
            rs.getBoolean("ocr_url_crops"),
            rs.getString("password"),
            (Float) rs.getObject("dpi"),
            rs.getString("pages_spec"),
            rs.getBoolean("add_link_annotations"),
            rs.getBoolean("no_skip_blanks"),
            rs.getString("ocr_lang"),
            (Integer) rs.getObject("timeout_seconds")
        );
    }
}
