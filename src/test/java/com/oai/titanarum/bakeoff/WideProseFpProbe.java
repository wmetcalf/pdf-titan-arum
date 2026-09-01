// Physically under src/test/java/com/oai/titanarum/bakeoff/ but declares `package com.oai.titanarum;`
// (same convention/reason as BakeOffHarness).
//
// PURPOSE. The project's official prose false-positive rate is over a 200-PDF sample, where one flag
// is worth 0.0050 -- too coarse to certify a change whose claim is "costs no false positives". This
// probe re-measures the SAME production decision (page 1, non-empty StreamTableExtractor.extractPage)
// over EVERY PDF in the real-world corpus, so a change can be checked before/after at 0.00063
// resolution. Read-only; no assertions about extraction. Gated by -DwideProse=true.
//
//   mvn -q -o test -Dtest=WideProseFpProbe -DwideProse=true
package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

class WideProseFpProbe {

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("wideProse"), "set -DwideProse=true to run");
        GutterFinder finder = new BreuelGutterFinder();
        List<Path> pdfs = sample(Integer.getInteger("wideProseCap", 2000));
        int flagged = 0, loadFailed = 0;
        List<String> names = new ArrayList<>();
        for (Path p : pdfs) {
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                if (doc.getNumberOfPages() < 1) continue;
                List<TextPosition> gl = TableTestPdfs.harvestGlyphs(doc, 0);
                if (!StreamTableExtractor.extractPage(1, gl, finder).isEmpty()) {
                    flagged++;
                    names.add(p.getFileName().toString());
                }
            } catch (Throwable t) {
                loadFailed++;
            }
        }
        System.out.printf(Locale.ROOT,
                "%nWIDE-PROSE-FP: %d/%d = %.5f   (loadFailed=%d, 1 flag = %.5f)%n",
                flagged, pdfs.size(), (double) flagged / pdfs.size(), loadFailed, 1.0 / pdfs.size());
        java.util.Collections.sort(names);
        for (String n : names) System.out.println("  FLAG " + n);
    }

    private static List<Path> sample(int cap) throws java.io.IOException {
        Path root = Path.of("/home/coz/Downloads/phishpdfs");
        if (!Files.isDirectory(root)) return List.of();
        List<Path> all;
        try (java.util.stream.Stream<Path> s = Files.list(root)) {
            all = s.filter(Files::isRegularFile).sorted().toList();
        }
        List<Path> out = new ArrayList<>();
        for (Path p : all) {
            if (out.size() >= cap) break;
            try (java.io.InputStream in = Files.newInputStream(p)) {
                byte[] b = new byte[5];
                int n = in.read(b);
                if (n >= 4 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') out.add(p);
            } catch (java.io.IOException ignored) { }
        }
        return out;
    }
}
