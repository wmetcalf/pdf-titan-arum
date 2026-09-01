// THROWAWAY DIAGNOSTIC (shading lever, phase 4): false-positive EXPOSURE of fill-based evidence.
// Replicates BakeOffHarness's own prose sampling (same root, same %PDF magic test, same 200 cap /
// stride) and asks how many real-world prose page-1s carry shaded boxes that a fill-driven
// region/row rule would have to resist. Purely observational.
package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

class DiagShade4Harness {

    private static final Path PHISH_ROOT = Path.of("/home/coz/Downloads/phishpdfs");
    private static final int CAP = 200;

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("diagShade4"), "set -DdiagShade4=true to run");
        Assumptions.assumeTrue(Files.isDirectory(PHISH_ROOT), "prose corpus missing");

        List<Path> all;
        try (Stream<Path> s = Files.list(PHISH_ROOT)) {
            all = s.filter(Files::isRegularFile).sorted().collect(Collectors.toList());
        }
        List<Path> pdfs = new ArrayList<>();
        for (Path p : all) if (looksLikePdf(p)) pdfs.add(p);
        List<Path> sample = new ArrayList<>();
        if (pdfs.size() <= CAP) sample = pdfs;
        else {
            int step = (int) Math.ceil(pdfs.size() / (double) CAP);
            for (int i = 0; i < pdfs.size() && sample.size() < CAP; i += step) sample.add(pdfs.get(i));
        }
        System.out.println("prose sample: " + sample.size());

        int anyNonThin = 0, wideBand = 0, tallRegion = 0, zebra = 0, currentlyFlagged = 0,
                wideBandAndNotFlagged = 0;
        GutterFinder breuel = new BreuelGutterFinder();
        for (Path p : sample) {
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                if (doc.getNumberOfPages() < 1) continue;
                PDPage page = doc.getPage(0);
                DiagShadeHarness.FillCollector fc = new DiagShadeHarness.FillCollector(page);
                try { fc.processPage(page); } catch (Throwable ignored) {}
                PDRectangle cb = page.getCropBox();
                float pw = cb.getWidth(), ph = cb.getHeight();
                if (page.getRotation() == 90 || page.getRotation() == 270) { float t = pw; pw = ph; ph = t; }
                float pitch = 12f;
                List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, 0);
                try {
                    List<StreamTableExtractor.Word> ws = StreamTableExtractor.buildWords(glyphs);
                    if (ws.size() >= 6) pitch = DiagShadeHarness.medianPitch(
                            StreamTableExtractor.buildLines(ws, StreamTableExtractor.medianFontSize(ws)));
                } catch (Throwable ignored) {}

                int nBand = 0; boolean nonThin = false, region = false;
                for (DiagShadeHarness.Fill f : fc.fills) {
                    if (DiagShadeHarness.isWhitish(f.rgb)) continue;
                    if (f.area() >= 0.65f * pw * ph) continue;
                    if (Math.min(f.w(), f.h()) <= TableExtractor.THIN_FILL_MAX) continue;
                    nonThin = true;
                    if (f.w() < 0.30f * pw) continue;
                    if (f.h() <= 3.0f * pitch) nBand++;
                    else region = true;
                }
                if (nonThin) anyNonThin++;
                if (nBand > 0) wideBand++;
                if (nBand >= 3) zebra++;
                if (region) tallRegion++;
                boolean flagged = !StreamTableExtractor.extractPage(1, glyphs, breuel).isEmpty();
                if (flagged) currentlyFlagged++;
                if ((nBand > 0 || region) && !flagged) wideBandAndNotFlagged++;
            } catch (Throwable ignored) {}
        }
        System.out.println("==== PROSE FILL EXPOSURE (page 1) ====");
        System.out.printf(Locale.ROOT, "with >=1 visible non-thin fill      : %d (%.1f%%)%n", anyNonThin, 100.0 * anyNonThin / sample.size());
        System.out.printf(Locale.ROOT, "with >=1 wide band fill             : %d (%.1f%%)%n", wideBand, 100.0 * wideBand / sample.size());
        System.out.printf(Locale.ROOT, "with >=3 wide band fills (zebra-ish): %d (%.1f%%)%n", zebra, 100.0 * zebra / sample.size());
        System.out.printf(Locale.ROOT, "with >=1 tall wide region fill      : %d (%.1f%%)%n", tallRegion, 100.0 * tallRegion / sample.size());
        System.out.printf(Locale.ROOT, "currently flagged as a table (proseFP baseline): %d (%.3f)%n",
                currentlyFlagged, currentlyFlagged / (double) sample.size());
        System.out.printf(Locale.ROOT, "shaded (band or region) but NOT currently flagged: %d (%.1f%% of sample)"
                + " <- the new FP exposure a fill-driven region/row rule must resist%n",
                wideBandAndNotFlagged, 100.0 * wideBandAndNotFlagged / sample.size());
    }

    private static boolean looksLikePdf(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            byte[] buf = new byte[5];
            int n = in.read(buf);
            return n >= 4 && buf[0] == '%' && buf[1] == 'P' && buf[2] == 'D' && buf[3] == 'F';
        } catch (IOException e) { return false; }
    }
}
