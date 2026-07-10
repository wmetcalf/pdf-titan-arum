package com.oai.titanarum;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
}
