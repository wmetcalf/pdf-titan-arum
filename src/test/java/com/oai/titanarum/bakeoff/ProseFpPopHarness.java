// NOTE ON PACKAGE: physically under src/test/java/com/oai/titanarum/bakeoff/ but declares
// `package com.oai.titanarum;` -- same reason as its neighbours (package-private production types).
//
// THROWAWAY DIAGNOSTIC. The real-world prose FALSE-POSITIVE rate over the WHOLE 1,599-PDF
// population under production's own default page selection, with the stream flag OFF and ON, using
// the production wired call path (TableExtractor.extract -> per-page stream -> arbitrate ->
// capTablesPerPage). BakeOffHarness/BaselineHarness report this on a strided 200-PDF sample only,
// and a handful of documents' difference is inside that sample's noise.
//
// Run: mvn -q -o test -Dtest=ProseFpPopHarness -DproseFpPop=true
package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

class ProseFpPopHarness {

    /** Production's OWN default page selection, reached reflectively -- the same technique
     *  BaselineHarness#ShippingPages uses (that class is private to it), so this harness cannot
     *  silently drift from the shipping `--pages default`. */
    private static List<Integer> shippingPages(PDDocument doc) throws Exception {
        java.lang.reflect.Constructor<PdfTitanArumApp> ctor =
                PdfTitanArumApp.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object app = ctor.newInstance();
        java.lang.reflect.Method compute = PdfTitanArumApp.class
                .getDeclaredMethod("computePagesToProcess", String.class, int.class);
        java.lang.reflect.Method classify = PdfTitanArumApp.class
                .getDeclaredMethod("classifyBlankPages", PDDocument.class);
        java.lang.reflect.Method fill = PdfTitanArumApp.class
                .getDeclaredMethod("fillBlankPages", List.class, Set.class, int.class);
        compute.setAccessible(true);
        classify.setAccessible(true);
        fill.setAccessible(true);
        int pageCount = doc.getNumberOfPages();
        @SuppressWarnings("unchecked")
        List<Integer> pages = (List<Integer>) compute.invoke(app, "default", pageCount);
        @SuppressWarnings("unchecked")
        Set<Integer> blank = (Set<Integer>) classify.invoke(app, doc);
        if (blank.isEmpty()) return pages;
        @SuppressWarnings("unchecked")
        List<Integer> filled = (List<Integer>) fill.invoke(app, pages, blank, pageCount);
        return filled;
    }

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("proseFpPop"), "set -DproseFpPop=true");
        Path root = Path.of("/home/coz/Downloads/phishpdfs");
        Assumptions.assumeTrue(Files.isDirectory(root), "prose corpus absent");

        List<Path> all;
        try (java.util.stream.Stream<Path> s = Files.list(root)) {
            all = s.filter(Files::isRegularFile).sorted().toList();
        }
        List<Path> prose = new ArrayList<>();
        for (Path p : all) {
            byte[] magic = new byte[5];
            try (var in = Files.newInputStream(p)) {
                if (in.read(magic) == 5 && new String(magic).equals("%PDF-")) prose.add(p);
            } catch (Exception ignored) { }
        }
        List<Path> tracked = BakeOffHarness.sampleProsePdfs();
        Set<Path> trackedSet = tracked == null ? Set.of() : new HashSet<>(tracked);

        int fpOff = 0, fpOn = 0, off200 = 0, on200 = 0, n200 = 0, failed = 0, pages = 0;
        GutterFinder finder = new BreuelGutterFinder();
        for (Path p : prose) {
            boolean inTracked = trackedSet.contains(p);
            if (inTracked) n200++;
            try (PDDocument doc = Loader.loadPDF(p.toFile())) {
                List<Integer> pageList = shippingPages(doc);
                Map<Integer, List<TextPosition>> glyphs = new LinkedHashMap<>();
                for (int q : pageList) glyphs.put(q, TableTestPdfs.harvestGlyphs(doc, q - 1));
                pages += pageList.size();
                List<TableExtractor.TableHit> lat = new ArrayList<>();
                try {
                    lat.addAll(TableExtractor.extract(doc, pageList, glyphs).tables);
                } catch (Throwable ignored) { }
                if (!lat.isEmpty()) { fpOff++; if (inTracked) off200++; }
                List<TableExtractor.TableHit> str = new ArrayList<>();
                int run = 0;
                try {
                    for (int q : pageList) {
                        List<TextPosition> pg = glyphs.get(q);
                        if (pg == null || pg.isEmpty()) continue;
                        if (pg.size() > StreamTableExtractor.MAX_STREAM_GLYPHS) continue;
                        if (run >= TableExtractor.MAX_STREAM_PAGES_PER_DOC) break;
                        run++;
                        str.addAll(StreamTableExtractor.extractPage(q, pg, finder));
                    }
                } catch (Throwable ignored) { }
                List<TableExtractor.TableHit> merged;
                try {
                    merged = TableExtractor.arbitrate(lat, str);
                } catch (TableExtractor.RulingOverflowException e) {
                    merged = new ArrayList<>(lat);
                }
                merged = TableExtractor.capTablesPerPage(merged, new TableExtractor.Result());
                if (!merged.isEmpty()) { fpOn++; if (inTracked) on200++; }
            } catch (Throwable t) {
                failed++;
            }
        }
        String s = String.format(Locale.ROOT,
                "POPULATION PROSE FP (shipping page selection)%n"
                        + "  population %d PDFs, %d pages, %d load failures%n"
                        + "  flag OFF : %d/%d = %.4f   (tracked-200: %d/%d)%n"
                        + "  flag ON  : %d/%d = %.4f   (tracked-200: %d/%d)%n",
                prose.size(), pages, failed,
                fpOff, prose.size(), (double) fpOff / prose.size(), off200, n200,
                fpOn, prose.size(), (double) fpOn / prose.size(), on200, n200);
        System.out.println(s);
        Path out = Path.of(System.getProperty("proseFpPopOut", "target/prose-fp-pop.txt"))
                .toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, s);
        System.out.println("Written to " + out);
    }
}
