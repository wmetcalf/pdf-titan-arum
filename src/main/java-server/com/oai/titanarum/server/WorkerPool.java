package com.oai.titanarum.server;

import com.oai.titanarum.PdfTitanArumApp;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.*;

public class WorkerPool {

    private final JobRepository repo;
    private final ArtifactStore store;
    private final int workerCount;
    private int timeoutSeconds = 300;
    private final ExecutorService executor;
    private final BlockingQueue<UUID> queue;
    private final ObjectMapper mapper;
    private String workerHost;

    public WorkerPool(JobRepository repo, ArtifactStore store, int workerCount) {
        this.repo = repo;
        this.store = store;
        this.workerCount = workerCount;
        this.executor = Executors.newFixedThreadPool(workerCount);
        this.queue = new LinkedBlockingQueue<>();
        this.mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    private String ocrLang = "eng";
    public void setOcrLang(String s) { this.ocrLang = s; }
    private String openAiKey;
    private String openAiModel = "gpt-5-nano";
    private String openAiBaseUrl = "https://api.openai.com";
    public void setOpenAiKey(String s) { this.openAiKey = s; }
    public void setOpenAiModel(String s) { this.openAiModel = s; }
    public void setOpenAiBaseUrl(String s) { this.openAiBaseUrl = s; }
    public void setTimeoutSeconds(int s) { this.timeoutSeconds = s; }

    public void start(String workerHost) {
        this.workerHost = workerHost;
        for (int i = 0; i < workerCount; i++) {
            executor.submit(this::workerLoop);
        }
    }

    public void submit(UUID jobId) {
        queue.offer(jobId);
    }

    private void workerLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                queue.poll(5, TimeUnit.SECONDS); // wakeup signal; null = timeout, still try DB

                var jobOpt = repo.claimNext(workerHost);
                if (jobOpt.isEmpty()) continue;
                processJob(jobOpt.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                System.err.println("Worker error: " + e.getMessage());
            }
        }
    }

    private void processJob(Job job) {
        try {
            Path outputDir = store.resolveArtifactRoot(job.id());
            Path uploadedPdf = outputDir.resolve("__upload.pdf");
            if (!Files.isRegularFile(uploadedPdf)) {
                repo.markFailed(job.id(), "Uploaded PDF not found");
                return;
            }
            byte[] pdfBytes = Files.readAllBytes(uploadedPdf);
            // M2: verify upload integrity before processing
            if (job.fileHash() != null) {
                String actualHash = sha256Hex(pdfBytes);
                if (!job.fileHash().equals(actualHash)) {
                    repo.markFailed(job.id(), "Upload integrity check failed (hash mismatch)");
                    return;
                }
            }
            // Read password before clearing it from the DB to minimise dwell time
            String jobPassword = job.password();
            repo.clearPassword(job.id());

            PdfTitanArumApp triageApp = new PdfTitanArumApp();
            configureApp(triageApp, job, ocrLang, timeoutSeconds);
            float jobDpi = job.dpi() != null ? job.dpi() : 150f;
            String jobPages = job.pagesSpec() != null ? job.pagesSpec() : "default";
            PdfTitanArumApp.AnalysisReport report = triageApp.callWith(
                pdfBytes, job.filename(), outputDir,
                jobDpi, jobPages, job.skipQr(), job.addLinkAnnotations(), null, jobPassword
            );

            if (openAiKey != null && !openAiKey.isBlank()) {
                try {
                    Path screenshotsDir = store.resolveArtifactRoot(job.id()).resolve("screenshots");
                    var analysis = new com.oai.titanarum.OpenAiAnalyzer(openAiKey, openAiModel, openAiBaseUrl)
                        .analyze(report, job.filename(), screenshotsDir);
                    report.aiAnalysis = analysis;
                } catch (Exception e) {
                    System.err.println("AI analysis failed for " + job.filename() + ": " + e.getMessage());
                    String aiErr = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    report.aiAnalysis = java.util.Map.of("error", aiErr);
                }
            }

            Files.deleteIfExists(uploadedPdf);

            // PostgreSQL jsonb rejects \u0000 null bytes — strip them before storing
            String reportJson = mapper.writeValueAsString(report).replace("\\u0000", "");
            repo.markDone(job.id(), reportJson, outputDir.toString(), report.pdfObjectHash);

        } catch (Exception e) {
            System.err.println("Job failed [" + job.filename() + "]: " + e);
            e.printStackTrace(System.err);
            try {
                // M8: scrub filesystem paths from error messages before storing
                String msg = e.getClass().getSimpleName();
                if (e.getMessage() != null) {
                    String sanitized = e.getMessage().replaceAll("/[^\\s,;\"']+", "<path>");
                    msg += ": " + sanitized;
                }
                repo.markFailed(job.id(), msg);
            } catch (SQLException se) {
                System.err.println("Could not mark job failed: " + se.getMessage());
            }
        }
    }

    /**
     * Copies every field-only option a {@link Job} carries onto a fresh {@link PdfTitanArumApp}.
     *
     * <p>This is the ONLY place the HTTP server's per-job options reach the triage app: unlike the
     * blastbox file-IPC worker, this path never constructs a {@code JobDescriptor}, so the
     * {@code @JsonProperty} bindings in {@code PdfTitanArumApp} are not involved and a flag that is
     * missing here is silently dropped rather than defaulted loudly. Keep this block in step with
     * {@code PdfTitanArumApp.runWorker}'s equivalent block; anything present there and absent here
     * is a flag the server cannot express.
     *
     * <p>Extracted from {@link #processJob} (behaviour-preserving) so the wiring is testable without
     * a database, an artifact store or a running worker loop.
     *
     * @param defaultOcrLang        pool-wide OCR language, used when the job does not override it
     * @param defaultTimeoutSeconds pool-wide per-job timeout, used when the job does not override it
     */
    static void configureApp(PdfTitanArumApp app, Job job, String defaultOcrLang, int defaultTimeoutSeconds) {
        app.setSkipScreenshots(job.skipScreenshots());
        app.setSkipImages(job.skipImages());
        app.setSkipPhones(job.skipPhones());
        app.setSkipTables(job.skipTables());
        app.setStreamTables(job.streamTables());
        app.setSkipPageExport(job.skipPageExport());
        app.setSkipTextUrls(job.skipTextUrls());
        app.setNoSkipBlanks(job.noSkipBlanks());
        app.setTimeout(job.timeoutSeconds() != null ? job.timeoutSeconds() : defaultTimeoutSeconds);
        app.setOcrScreenshots(job.ocrScreenshots());
        app.setOcrUrlCrops(job.ocrUrlCrops());
        app.setOcrLang(job.ocrLang() != null ? job.ocrLang() : defaultOcrLang);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(bytes));
        } catch (Exception e) { return null; }
    }
}
