package com.oai.titanarum;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Decodes barcodes by shelling out to the zxing-cpp {@code ZXingReader -json} binary. */
final class ZXingReaderScanner {

    /** One decoded symbol (or an error entry). */
    record QrResult(String text, String format, String position, String error) {}

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_STDOUT = 1 << 20; // 1 MiB

    private final String executable;
    private final String formats;   // comma-separated; "" = all
    private final long timeoutMillis;

    ZXingReaderScanner() {
        String bin = System.getenv("TITANARUM_ZXING_BIN");
        this.executable = (bin == null || bin.isBlank()) ? "ZXingReader" : bin;
        String fmt = System.getenv("TITANARUM_ZXING_FORMATS");
        this.formats = (fmt == null) ? "QRCode" : fmt;   // preserves current QR-only behavior
        this.timeoutMillis = 60_000L;
    }

    List<String> buildCommand(Path image) {
        List<String> cmd = new ArrayList<>();
        cmd.add(executable);
        cmd.add("-json");
        if (formats != null && !formats.isBlank()) {
            cmd.add("-formats");
            cmd.add(formats);
        }
        cmd.add(image.toAbsolutePath().toString());
        return cmd;
    }

    List<QrResult> scan(Path image) {
        ProcessBuilder pb = new ProcessBuilder(buildCommand(image));
        pb.redirectErrorStream(false);
        Process proc = null;
        try {
            proc = pb.start();
            byte[] out = readCapped(proc.getInputStream());
            boolean done = proc.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!done) {
                proc.destroyForcibly();
                return List.of();
            }
            return parseJsonLines(new String(out, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } finally {
            if (proc != null && proc.isAlive()) proc.destroyForcibly();
        }
    }

    /** Parse ZXingReader {@code -json} output: one JSON object per line. */
    static List<QrResult> parseJsonLines(String stdout) {
        List<QrResult> results = new ArrayList<>();
        if (stdout == null || stdout.isBlank()) return results;
        for (String line : stdout.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            try {
                JsonNode n = JSON.readTree(t);
                String format = n.path("Format").asText(null);
                if (format == null || format.isBlank()) continue;   // require Format
                results.add(new QrResult(
                        n.path("Text").asText(null),
                        format,
                        n.path("Position").asText(null),
                        n.path("Error").asText(null)));
            } catch (IOException ignore) {
                // non-JSON line (e.g. a version banner) -> skip
            }
        }
        return results;
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) != -1 && buf.size() < MAX_STDOUT) {
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }
}
