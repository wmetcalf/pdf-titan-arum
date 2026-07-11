package com.oai.titanarum;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.*;

class RunWorkerModeTest {

    @Test
    void runMode_readsJobJson_writesReport(@TempDir Path tmp) throws Exception {
        // A real 1-page PDF (valid, minimal).
        Path pdf = tmp.resolve("in.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(pdf.toFile());
        }
        Path outDir = tmp.resolve("out");
        Files.createDirectories(outDir);

        Path scratch = tmp.resolve("scratch");
        Path control = scratch.resolve("control");
        Files.createDirectories(control);

        // Pre-stage job.json + control.go so the worker's wait loop proceeds immediately.
        Map<String, Object> job = Map.of(
                "input_path", pdf.toString(),
                "output_dir", outDir.toString(),
                "filename_hint", "in.pdf",
                "dpi", 150.0,
                "pages", "default",
                "skip_qr", true,
                "skip_screenshots", true,
                "skip_images", true,
                "add_link_annotations", false);
        new ObjectMapper().writeValue(control.resolve("job.json").toFile(), job);
        Files.createFile(control.resolve("control.go"));

        int exit = new CommandLine(new PdfTitanArumApp())
                .execute("--run", scratch.toString());

        assertEquals(0, exit, "worker mode should exit 0 after writing report.json");
        Path report = outDir.resolve("report.json");
        assertTrue(Files.isRegularFile(report), "report.json must be written to output_dir");
        String body = Files.readString(report);
        assertTrue(body.contains("\"documentSha256\""), "report must contain documentSha256");
        // Worker announced readiness in the control subdir.
        assertTrue(Files.exists(control.resolve("control.ready")), "control.ready must be created");
    }

    // --- W-1 (warm-plan.md FINDING B): the go-wait must use a monotonic clock and must
    // not be defeated by a clock that has jumped forward across a warm-tier snapshot
    // restore. These tests exercise the extracted awaitGoSignal seam directly with an
    // injected LongSupplier so we can simulate the jump without a real 600s sleep.

    @Test
    void awaitGoSignal_warmMode_ignoresClockJumpAndStillWaitsForGoFile(@TempDir Path tmp) throws Exception {
        File go = tmp.resolve("control.go").toFile();

        // A "clock" that already reads far in the future on every call -- simulates a
        // monotonic reading taken long after a warm-tier restore, i.e. the exact
        // condition that used to blow through the old wall-clock deadline instantly.
        LongSupplier jumpedClock = () -> Long.MAX_VALUE - 1;

        AtomicBoolean delivered = new AtomicBoolean(false);
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                delivered.set(PdfTitanArumApp.awaitGoSignal(go, true, jumpedClock));
            } catch (Exception e) {
                failure.set(e);
            }
        });
        waiter.start();

        // Give the poll loop several iterations to prove it really is still blocked --
        // i.e. it did NOT give up early because of the huge/jumped clock reading.
        Thread.sleep(250);
        assertTrue(waiter.isAlive(), "warm go-wait must still be blocked (unbounded) despite a far-future clock reading");

        Files.createFile(go.toPath());
        waiter.join(5_000);

        assertNull(failure.get(), "awaitGoSignal must not throw");
        assertFalse(waiter.isAlive(), "waiter must finish once control.go appears");
        assertTrue(delivered.get(),
                "warm go-wait must report the job as delivered once control.go appears -- "
                        + "the clock jump must not be able to defeat delivery");
    }

    @Test
    void awaitGoSignal_coldMode_staysBoundedByMonotonicDeadline(@TempDir Path tmp) throws Exception {
        File go = tmp.resolve("control.go").toFile(); // never created

        // First call establishes the deadline (t=0); every call thereafter reports a
        // monotonic reading ~700s later -- i.e. already past the legacy 600s cold bound.
        AtomicLong calls = new AtomicLong(0);
        LongSupplier pastDeadlineClock = () -> calls.getAndIncrement() == 0 ? 0L : 700_000_000_000L;

        boolean delivered = PdfTitanArumApp.awaitGoSignal(go, false, pastDeadlineClock);

        assertFalse(delivered, "cold go-wait must remain bounded by the 600s deadline, now on the monotonic clock");
    }
}
