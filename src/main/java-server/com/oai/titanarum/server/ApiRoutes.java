package com.oai.titanarum.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;
import io.javalin.http.sse.SseClient;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public class ApiRoutes {

    private static final long MAX_UPLOAD_BYTES = 500L * 1024 * 1024;

    public static void wire(Javalin app, JobRepository repo, ArtifactStore store, WorkerPool pool) {

        // POST /api/jobs — submit a PDF
        app.post("/api/jobs", ctx -> {
            // M1: require XMLHttpRequest marker to block naive cross-origin form CSRF
            String xrw = ctx.header("X-Requested-With");
            if (!"XMLHttpRequest".equals(xrw)) {
                ctx.status(HttpStatus.FORBIDDEN).result("{\"error\":\"Missing X-Requested-With header\"}");
                return;
            }
            UploadedFile file = ctx.uploadedFile("file");
            if (file == null) {
                ctx.status(HttpStatus.BAD_REQUEST).result("{\"error\":\"No file field in request\"}");
                return;
            }
            String filename = sanitizeFilename(file.filename());
            // H2: read actual bytes and check real length (don't trust Content-Length)
            byte[] pdfBytes = file.content().readNBytes((int) MAX_UPLOAD_BYTES + 1);
            if (pdfBytes.length > MAX_UPLOAD_BYTES) {
                ctx.status(HttpStatus.CONTENT_TOO_LARGE).result("{\"error\":\"File exceeds 500MB limit\"}");
                return;
            }
            String hash = sha256(pdfBytes);

            UUID jobId = repo.insert(filename, hash,
                    boolForm(ctx, "skipScreenshots"),
                    boolForm(ctx, "skipImages"),
                    boolForm(ctx, "skipPhones"),
                    boolForm(ctx, "skipPageExport"),
                    boolForm(ctx, "skipTextUrls"),
                    boolForm(ctx, "skipQr"),
                    boolForm(ctx, "ocrScreenshots"),
                    boolForm(ctx, "ocrUrlCrops"),
                    ctx.formParam("password"),
                    floatForm(ctx, "dpi", 50f, 600f),
                    ctx.formParam("pagesSpec"),
                    boolForm(ctx, "addLinkAnnotations"),
                    boolForm(ctx, "noSkipBlanks"),
                    ctx.formParam("ocrLang"),
                    intForm(ctx, "timeoutSeconds", 5, 3600));
            Path outputDir = store.resolveArtifactRoot(jobId);
            Files.write(outputDir.resolve("__upload.pdf"), pdfBytes);
            pool.submit(jobId);

            ctx.status(HttpStatus.ACCEPTED)
                    .json(Map.of("id", jobId.toString(), "status", "pending", "filename", filename));
        });

        // GET /api/jobs — list jobs
        app.get("/api/jobs", ctx -> {
            int page = intParam(ctx, "page", 0);
            int size = intParam(ctx, "size", 25);
            String statusFilter = ctx.queryParam("status");
            List<Job> jobs = repo.list(page, size, statusFilter);
            ctx.json(jobs.stream().map(ApiRoutes::jobSummary).toList());
        });

        // GET /api/jobs/{id} — job detail
        app.get("/api/jobs/{id}", ctx -> {
            UUID id = parseUuid(ctx);
            if (id == null)
                return;
            var job = repo.findById(id);
            if (job.isEmpty()) {
                ctx.status(HttpStatus.NOT_FOUND).result("{}");
                return;
            }
            ctx.json(jobDetail(job.get()));
        });

        // DELETE /api/jobs/{id}
        app.delete("/api/jobs/{id}", ctx -> {
            UUID id = parseUuid(ctx);
            if (id == null)
                return;
            var job = repo.findById(id);
            if (job.isEmpty()) {
                ctx.status(HttpStatus.NOT_FOUND).result("{}");
                return;
            }
            store.deleteJob(id);
            repo.delete(id);
            ctx.status(HttpStatus.NO_CONTENT);
        });

        // GET /api/jobs/{id}/download — ZIP of all artifacts
        app.get("/api/jobs/{id}/download", ctx -> {
            UUID id = parseUuid(ctx);
            if (id == null)
                return;
            var job = repo.findById(id);
            if (job.isEmpty()) {
                ctx.status(HttpStatus.NOT_FOUND).result("{}");
                return;
            }
            if (!"done".equals(job.get().status())) {
                ctx.status(HttpStatus.CONFLICT).result("{\"error\":\"Job not done yet\"}");
                return;
            }

            Path root = Path.of(job.get().artifactRoot()).toAbsolutePath().normalize();
            // Verify the DB-stored artifact root is still within the configured store root
            if (!root.startsWith(store.getRootPath())) {
                ctx.status(HttpStatus.FORBIDDEN).result("{\"error\":\"Invalid artifact root\"}");
                return;
            }

            String fname = job.get().filename().replaceAll("[^A-Za-z0-9._-]", "_");
            ctx.header("Content-Disposition", "attachment; filename=\"" + fname + ".zip\"");
            ctx.contentType("application/zip");
            store.zipJob(root, ctx.outputStream());
        });

        // GET /api/jobs/{id}/artifacts/* — serve individual artifact file
        app.get("/api/jobs/{id}/artifacts/*", ctx -> {
            UUID id = parseUuid(ctx);
            if (id == null)
                return;
            var job = repo.findById(id);
            if (job.isEmpty() || job.get().artifactRoot() == null) {
                ctx.status(HttpStatus.NOT_FOUND).result("{}");
                return;
            }
            // Derive wildcard segment from request path minus the prefix up to /artifacts/
            String requestPath = ctx.path();
            int artifactsIdx = requestPath.indexOf("/artifacts/");
            if (artifactsIdx < 0) {
                ctx.status(HttpStatus.BAD_REQUEST).result("{}");
                return;
            }
            String reqPath = requestPath.substring(artifactsIdx + "/artifacts/".length());
            if (reqPath.isEmpty()) {
                ctx.status(HttpStatus.BAD_REQUEST).result("{}");
                return;
            }
            Path root = Path.of(job.get().artifactRoot()).toAbsolutePath().normalize();
            // Verify the DB-stored artifact root is still within the configured store root
            // to prevent a compromised DB entry from exposing arbitrary filesystem paths.
            if (!root.startsWith(store.getRootPath())) {
                ctx.status(HttpStatus.FORBIDDEN).result("{\"error\":\"Invalid artifact root\"}");
                return;
            }
            Path target = root.resolve(reqPath).normalize();
            if (!target.startsWith(root)) {
                ctx.status(HttpStatus.FORBIDDEN).result("{\"error\":\"Path traversal denied\"}");
                return;
            }
            // Only serve rendered/safe artifacts. Block raw extracted content (original
            // images,
            // embedded files, page PDFs, uploaded source PDF) from direct browser access.
            // ZIP download is unaffected — originals remain in the archive.
            boolean allowed = reqPath.startsWith("screenshots/")
                    || reqPath.startsWith("url_crops/")
                    || (reqPath.startsWith("images_rendered/") && reqPath.endsWith(".png"))
                    || (reqPath.startsWith("images_resources/") && reqPath.endsWith(".png"))
                    || (reqPath.startsWith("revisions/") && reqPath.endsWith(".png"));
            if (!allowed) {
                ctx.status(HttpStatus.FORBIDDEN)
                        .result("{\"error\":\"Direct access to original artifacts is not permitted\"}");
                return;
            }
            if (!Files.isRegularFile(target)) {
                ctx.status(HttpStatus.NOT_FOUND).result("{}");
                return;
            }
            // L8: don't trust OS probeContentType — allowlist is already PNG-only paths
            String mime = reqPath.endsWith(".png") ? "image/png" : Files.probeContentType(target);
            if (mime != null)
                ctx.contentType(mime);
            ctx.result(Files.newInputStream(target));
        });

        // GET /api/jobs/{id}/status — SSE live status stream
        app.sse("/api/jobs/{id}/status", client -> {
            Context ctx = client.ctx();
            UUID id;
            try {
                id = UUID.fromString(ctx.pathParam("id"));
            } catch (IllegalArgumentException e) {
                client.sendEvent("error", "invalid_id");
                return;
            }
            try {
                for (int i = 0; i < 120; i++) {
                    var job = repo.findById(id);
                    if (job.isEmpty()) {
                        client.sendEvent("error", "not_found");
                        break;
                    }
                    String status = job.get().status();
                    client.sendEvent("status", status);
                    if ("done".equals(status) || "failed".equals(status))
                        break;
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                System.err.println("SSE error for job " + id + ": " + e.getMessage());
                client.sendEvent("error", "internal_error");
            }
        });
    }

    private static UUID parseUuid(Context ctx) {
        try {
            return UUID.fromString(ctx.pathParam("id"));
        } catch (IllegalArgumentException e) {
            ctx.status(HttpStatus.BAD_REQUEST).result("{\"error\":\"Invalid job ID\"}");
            return null;
        }
    }

    private static boolean boolForm(Context ctx, String name) {
        String v = ctx.formParam(name);
        return "true".equals(v) || "on".equals(v) || "1".equals(v);
    }

    /** Returns a validated float from a form field, or null if absent/invalid/out-of-range. */
    private static Float floatForm(Context ctx, String name, float min, float max) {
        String v = ctx.formParam(name);
        if (v == null || v.isBlank()) return null;
        try {
            float f = Float.parseFloat(v);
            return (f >= min && f <= max) ? f : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Returns a validated int from a form field, or null if absent/invalid/out-of-range. */
    private static Integer intForm(Context ctx, String name, int min, int max) {
        String v = ctx.formParam(name);
        if (v == null || v.isBlank()) return null;
        try {
            int i = Integer.parseInt(v);
            return (i >= min && i <= max) ? i : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intParam(Context ctx, String name, int def) {
        String v = ctx.queryParam(name);
        if (v == null)
            return def;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank())
            return "upload.pdf";
        String base = Path.of(name.replace('\\', '/')).getFileName().toString();
        return base.replaceAll("[^A-Za-z0-9._() -]", "_");
    }

    private static Map<String, Object> jobSummary(Job j) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", j.id().toString());
        m.put("filename", j.filename());
        m.put("status", j.status());
        m.put("submittedAt", j.submittedAt() != null ? j.submittedAt().toString() : null);
        if (j.fileHash() != null) m.put("fileHash", j.fileHash());
        if (j.pdfObjectHash() != null) m.put("pdfObjectHash", j.pdfObjectHash());
        return m;
    }

    private static Map<String, Object> jobDetail(Job j) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", j.id().toString());
        m.put("filename", j.filename());
        m.put("fileHash", j.fileHash());
        m.put("pdfObjectHash", j.pdfObjectHash());
        m.put("status", j.status());
        m.put("submittedAt", j.submittedAt() != null ? j.submittedAt().toString() : null);
        m.put("startedAt", j.startedAt() != null ? j.startedAt().toString() : null);
        m.put("finishedAt", j.finishedAt() != null ? j.finishedAt().toString() : null);
        m.put("errorText", j.errorText());
        if (j.reportJson() != null) {
            try {
                m.put("report", new ObjectMapper().readValue(j.reportJson(), Object.class));
            } catch (Exception e) {
                m.put("report", null);
            }
        }
        return m;
    }
}
