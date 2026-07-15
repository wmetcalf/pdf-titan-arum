package com.oai.titanarum;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * I2 (warm-plan.md): a JVM hard-halt watchdog in {@code PdfTitanArumApp.callWith}. The existing
 * cooperative watchdog only {@code Thread.interrupt()}s the processing thread at {@code
 * --timeout} seconds; parser loops cooperatively check {@code Thread.interrupted()}. That does
 * NOT cover a hang that ignores interrupt outright -- e.g. a wedged native JBIG2/JPEG2000
 * decode, a {@code ZXingReader} {@code waitFor} that swallows {@code InterruptedException}, or
 * catastrophic regex backtracking. Cold mode has Python's {@code subprocess.run(timeout=)}
 * SIGKILL backstop; a persistent/warm JVM has no in-JVM hard bound without this watchdog.
 *
 * <p>These tests drive a REAL child JVM: {@code Runtime.getRuntime().halt(3)} would otherwise
 * kill the Surefire JVM running this very test. The child is launched with a test-only,
 * env-gated seam ({@code TITANARUM_TEST_HANG_SPIN}) that spins forever swallowing {@code
 * Thread.interrupt()}, simulating exactly that class of hang.
 */
class HardWatchdogTest {

    @Test
    void hangIgnoringInterrupt_isHardHalted_withPartialReport(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdf.toFile());
        }
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);

        List<String> cmd = new ArrayList<>();
        cmd.add(javaBinary());
        cmd.add("-cp");
        cmd.add(currentTestClasspath());
        cmd.add("com.oai.titanarum.PdfTitanArumApp");
        cmd.add("-i");
        cmd.add(pdf.toString());
        cmd.add("-o");
        cmd.add(outDir.toString());
        cmd.add("--timeout");
        cmd.add("1"); // cooperative deadline: irrelevant here, the spin ignores interrupt

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("TITANARUM_TEST_HANG_SPIN", "1");
        pb.environment().put("TITANARUM_HARD_TIMEOUT_MS", "1500"); // hard deadline ~= 1s + 1.5s
        pb.redirectErrorStream(true);
        pb.redirectOutput(tmp.resolve("child.log").toFile());

        Process proc = pb.start();
        try {
            long startNanos = System.nanoTime();
            boolean exited = proc.waitFor(30, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

            assertTrue(exited, "the hung child JVM must hard-halt itself; it must not need to be killed by the test");
            assertEquals(3, proc.exitValue(), "Runtime.getRuntime().halt(3) must produce exit code 3");
            assertTrue(elapsedMs < 15_000,
                    "the hard halt must fire close to the configured deadline (~2.5s), took " + elapsedMs + "ms");

            Path report = outDir.resolve("report.json");
            assertTrue(Files.isRegularFile(report), "a partial report.json must be flushed before halt(3)");
            String body = Files.readString(report);
            assertTrue(body.contains("\"documentSha256\""),
                    "partial report must contain fields computed before the hang: " + body);
            assertTrue(body.replaceAll("\\s", "").contains("\"timedOut\":true"),
                    "partial report flushed by the hard watchdog must be marked timedOut: " + body);
        } finally {
            // Fix 5 (I2 review): if a future regression means the watchdog never fires,
            // waitFor(...) above returns false and the assertTrue on `exited` throws --
            // without this, the still-spinning child (TITANARUM_TEST_HANG_SPIN busy-loops
            // forever, ignoring interrupt) would leak as an orphaned process in CI.
            proc.destroyForcibly();
        }
    }

    /**
     * Fix 3 (I2 review): the safety-critical "no false halt on a healthy job" scenario. This
     * runs {@code callWith} in-process (no child JVM needed -- a normal job never halts) with a
     * GENEROUS cooperative timeout on a minimal valid PDF that completes in well under a second.
     * The hard watchdog is still armed (timeoutSeconds > 0), so this exercises that it is
     * correctly disarmed ({@code _hardWatchdog.shutdownNow()} in callWith's finally) on normal
     * completion rather than firing spuriously. If the watchdog ever mis-fired here, {@code
     * Runtime.getRuntime().halt(3)} would kill this very test JVM immediately -- so every
     * assertion below executing at all is itself part of the proof that no halt occurred.
     */
    @Test
    void healthyJob_withGenerousCooperativeTimeout_completesNormally_noFalseHalt(@TempDir Path tmp) throws Exception {
        Path pdf = tmp.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdf.toFile());
        }
        Path out = tmp.resolve("out");
        Files.createDirectories(out);

        PdfTitanArumApp app = new PdfTitanArumApp();
        app.setTimeout(30); // generous cooperative deadline; job finishes in well under 1s
        app.setSkipScreenshots(true);
        app.setSkipPhones(true);
        app.setSkipTextUrls(true);
        app.setSkipPageExport(true);

        byte[] pdfBytes = Files.readAllBytes(pdf);
        long startNanos = System.nanoTime();
        PdfTitanArumApp.AnalysisReport report = app.callWith(
                pdfBytes, "in.pdf", out, 150f, "1",
                /* skipQrScan */ true, /* addLinkAnnotations */ false,
                /* modifiedPdfOutput */ null, /* password */ null);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertNotNull(report, "a healthy job on a generous cooperative timeout must return a report normally");
        assertTrue(elapsedMs < 5_000,
                "sanity check that this job is actually fast (well under both the 30s cooperative "
                        + "and hard deadlines), took " + elapsedMs + "ms");
        assertFalse(report.timedOut,
                "the report returned to the caller must not be marked timedOut for a healthy job");

        Path reportPath = out.resolve("report.json");
        assertTrue(Files.isRegularFile(reportPath), "the normal (non-watchdog) report.json write must have happened");
        String body = Files.readString(reportPath);
        assertFalse(body.replaceAll("\\s", "").contains("\"timedOut\":true"),
                "the persisted report.json must not be marked timedOut for a healthy job: " + body);
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    /**
     * Builds the classpath to hand to a freshly-launched child JVM so it can find {@code
     * PdfTitanArumApp} and its dependencies (PDFBox, Jackson, picocli, ...). Surefire sometimes
     * forks its own test JVM using a single manifest-only "booter" jar (to dodge OS command-line
     * length limits) whose {@code java.class.path} system property is therefore just that one
     * jar; the real classpath lives in its {@code Class-Path} manifest attribute. Handle both
     * cases so this test is robust to Surefire's forking strategy.
     */
    private static String currentTestClasspath() throws IOException {
        String cp = System.getProperty("java.class.path");
        String[] entries = cp.split(File.pathSeparator);
        if (entries.length == 1 && entries[0].toLowerCase(Locale.ROOT).endsWith(".jar")) {
            try (JarFile jf = new JarFile(entries[0])) {
                String classPathAttr = jf.getManifest() == null ? null
                        : jf.getManifest().getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
                if (classPathAttr != null && !classPathAttr.isBlank()) {
                    StringBuilder sb = new StringBuilder(entries[0]);
                    for (String part : classPathAttr.split(" ")) {
                        if (part.isBlank()) continue;
                        sb.append(File.pathSeparator).append(new File(URI.create(part)).getAbsolutePath());
                    }
                    return sb.toString();
                }
            }
        }
        return cp;
    }
}
