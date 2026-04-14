package com.oai.titanarum.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.pebbletemplates.pebble.PebbleEngine;
import io.pebbletemplates.pebble.template.PebbleTemplate;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.io.StringWriter;
import java.util.*;

public class WebRoutes {

    private static final PebbleEngine ENGINE = new PebbleEngine.Builder()
        .newLineTrimming(false)
        .autoEscaping(true)
        .defaultEscapingStrategy("html")
        .build();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void wire(Javalin app, JobRepository repo) {

        // GET / — job list
        app.get("/", ctx -> {
            int page = intParam(ctx, "page", 0);
            String statusFilter = ctx.queryParam("status");
            int pageSize = 50;
            List<Job> jobs = repo.list(page, pageSize, statusFilter);
            int total = repo.count(statusFilter);
            int totalPages = Math.max(1, (total + pageSize - 1) / pageSize);
            Map<String, Object> model = new HashMap<>();
            model.put("jobs", jobs.stream().map(WebRoutes::jobToMap).toList());
            model.put("page", page);
            model.put("totalPages", totalPages);
            model.put("totalCount", total);
            model.put("statusFilter", statusFilter);
            render(ctx, "job-list.html", model);
        });

        // GET /jobs/{id} — job detail
        app.get("/jobs/{id}", ctx -> {
            UUID id;
            try { id = UUID.fromString(ctx.pathParam("id")); }
            catch (IllegalArgumentException e) { ctx.status(HttpStatus.BAD_REQUEST).result("Invalid ID"); return; }

            var jobOpt = repo.findById(id);
            if (jobOpt.isEmpty()) { ctx.status(HttpStatus.NOT_FOUND).result("Job not found"); return; }

            Job job = jobOpt.get();
            Map<String, Object> model = new HashMap<>();
            model.put("job", jobToMap(job));

            if (job.reportJson() != null) {
                try {
                    model.put("report", MAPPER.readValue(job.reportJson(), Map.class));
                    model.put("hasReport", true);
                    model.put("reportJson", MAPPER.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(MAPPER.readTree(job.reportJson())));
                } catch (Exception e) {
                    model.put("hasReport", false);
                }
            } else {
                model.put("hasReport", false);
            }

            render(ctx, "job-detail.html", model);
        });
    }

    private static void render(Context ctx, String template, Map<String, Object> model) throws Exception {
        PebbleTemplate t = ENGINE.getTemplate("templates/" + template);
        StringWriter sw = new StringWriter();
        t.evaluate(sw, model);
        ctx.contentType("text/html").result(sw.toString());
    }

    @SuppressWarnings("unchecked")
    private static boolean hasQrCodes(Map<?,?> report) {
        for (String key : List.of("screenshots", "renderedImages", "resourceImages")) {
            var list = (List<?>) report.get(key);
            if (list == null) continue;
            for (Object item : list) {
                if (item instanceof Map<?,?> m) {
                    var qr = (List<?>) m.get("qrCodes");
                    if (qr != null && !qr.isEmpty()) return true;
                }
            }
        }
        return false;
    }

    private static int intParam(Context ctx, String name, int def) {
        String v = ctx.queryParam(name);
        if (v == null) return def;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return def; }
    }

    /** Convert a Job record to a plain Map so Pebble can access fields by name. */
    private static Map<String, Object> jobToMap(Job j) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", j.id().toString());
        m.put("filename", j.filename());
        m.put("fileHash", j.fileHash());
        m.put("pdfObjectHash", j.pdfObjectHash());
        m.put("status", j.status());
        m.put("submittedAt", j.submittedAt() != null ? j.submittedAt().toString() : null);
        m.put("startedAt", j.startedAt() != null ? j.startedAt().toString() : null);
        m.put("finishedAt", j.finishedAt() != null ? j.finishedAt().toString() : null);
        m.put("workerHost", j.workerHost());
        m.put("errorText", j.errorText());
        if (j.reportJson() != null) {
            try {
                var report = MAPPER.readValue(j.reportJson(), Map.class);
                m.put("blankRatio", report.get("blankRatio"));
                m.put("blankPageCount", report.get("blankPageCount"));
                m.put("pageCount", report.get("pageCount"));
                boolean hasQr = hasQrCodes(report);
                if (hasQr) m.put("hasQr", true);
                var emails = (java.util.List<?>) report.get("emails");
                if (emails != null && !emails.isEmpty()) m.put("hasEmails", true);
                var ocgLayers = (java.util.List<?>) report.get("ocgLayers");
                if (ocgLayers != null && !ocgLayers.isEmpty()) {
                    m.put("hasOcg", true);
                    boolean suspiciousOcg = ocgLayers.stream().anyMatch(l ->
                        l instanceof java.util.Map<?,?> lm && Boolean.TRUE.equals(lm.get("suspicious")));
                    if (suspiciousOcg) m.put("hasSuspiciousOcg", true);
                }
                var urls = (java.util.List<?>) report.get("urls");
                if (urls != null && !urls.isEmpty()) m.put("urlCount", urls.stream()
                    .filter(u -> u instanceof java.util.Map<?,?> um && um.get("fromRevision") == null)
                    .count());
                var js = (java.util.List<?>) report.get("javascript");
                if (js != null && !js.isEmpty()) m.put("jsCount", js.size());
                var embedded = (java.util.List<?>) report.get("embeddedFiles");
                if (embedded != null && !embedded.isEmpty()) m.put("embeddedCount", embedded.size());
                var revisionCount = report.get("revisionCount");
                if (revisionCount instanceof Number n && n.intValue() > 1) m.put("revisionCount", n.intValue());
                var jsIndicators = (java.util.List<?>) report.get("jsIndicators");
                if (jsIndicators != null && !jsIndicators.isEmpty()) {
                    m.put("jsIndicatorCount", jsIndicators.size());
                    for (Object ji : jsIndicators) {
                        if (ji instanceof java.util.Map<?,?> jim) {
                            if ("cve_detection".equals(jim.get("type"))) {
                                m.put("cveDetection", jim.get("indicator"));
                                break;
                            }
                        }
                    }
                }
                var formFields = (java.util.List<?>) report.get("formFields");
                if (formFields != null && !formFields.isEmpty()) m.put("formFieldCount", formFields.size());
                var aiAnalysis = report.get("aiAnalysis");
                if (aiAnalysis instanceof java.util.Map<?,?> ai) {
                    var score = ai.get("score");
                    if (score instanceof Number s) m.put("aiScore", s.intValue());
                    var level = ai.get("threatLevel");
                    if (level instanceof String sl) m.put("aiThreatLevel", sl);
                }
            } catch (Exception ignored) {}
        }
        return m;
    }
}
