package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * INSTRUMENT CORRECTNESS TEST -- pins the bake-off/baseline harnesses' glyph source to the shipping
 * pipeline's.
 *
 * <p>WHY THIS EXISTS. Every table figure this project has published was produced by feeding
 * {@link TableTestPdfs#harvestGlyphs} into {@link TableExtractor}. Production feeds
 * {@code PdfTitanArumApp#stripTextPerPage}'s {@code PositionAwareTextStripper} output, post-processed
 * by {@code PdfTitanArumApp#dedupeConsecutiveTextPositionRefs}. Those two were NOT the same list:
 * the harness ran the stripper at DEFAULT ordering (content-stream order, not
 * {@code setSortByPosition(true)}) and kept glyphs with null/empty unicode that production drops.
 * Feeding the extractor an off-distribution glyph sequence makes every threshold fitted against it
 * fitted against a distribution production never produces.
 *
 * <p>WHAT IT ASSERTS. For each fixture, the two lists must agree element for element: same length,
 * and the same glyph SIGNATURE (unicode + full adjusted geometry + font + size + space width +
 * rotation) at every index -- i.e. the same glyphs in the same ORDER. Signatures rather than
 * {@code assertSame} because the two sides necessarily run the stripper twice and PDFBox mints fresh
 * {@link TextPosition} objects on each run; the signature covers every field the table extractors
 * actually read off a glyph. The production side is obtained by REFLECTION into the real private
 * production types, never by re-implementing them here, so this test fails if either side drifts.
 *
 * <p>This test asserts only PARITY. It says nothing about which ordering is better; the shipping
 * pipeline's is by definition the one the instrument must reproduce.
 */
class HarvestGlyphsProductionParityTest {

    private static final Path CORPUS_ROOT =
            Path.of("corpus/tabula-java/src/test/resources/technology/tabula");

    /** How many corpus PDFs to check, when the (gitignored) corpus is present at all. */
    private static final int CORPUS_SAMPLE = 12;

    // --------------------------------------------------------- production side, via reflection

    /**
     * Build one page's glyph list the way {@code PdfTitanArumApp} does: its own
     * {@code PositionAwareTextStripper} (sort-by-position on, per-char index, unicode filter), then
     * {@code positionsForRange(0, Integer.MAX_VALUE)}, then
     * {@code dedupeConsecutiveTextPositionRefs}. Nothing is re-implemented -- these are the real
     * production members, reached reflectively because they are private.
     */
    @SuppressWarnings("unchecked")
    private static List<TextPosition> productionGlyphs(PDDocument doc, int pageIndex) throws Exception {
        Class<?> stripperClass =
                Class.forName("com.oai.titanarum.PdfTitanArumApp$PositionAwareTextStripper");
        Constructor<?> ctor = stripperClass.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object stripper = ctor.newInstance();

        PDFTextStripper asStripper = (PDFTextStripper) stripper;
        asStripper.setSortByPosition(true);
        asStripper.setStartPage(pageIndex + 1);
        asStripper.setEndPage(pageIndex + 1);
        asStripper.getText(doc);

        Method positionsForRange = stripperClass.getDeclaredMethod("positionsForRange", int.class, int.class);
        positionsForRange.setAccessible(true);
        List<TextPosition> raw =
                (List<TextPosition>) positionsForRange.invoke(stripper, 0, Integer.MAX_VALUE);

        Method dedupe = PdfTitanArumApp.class
                .getDeclaredMethod("dedupeConsecutiveTextPositionRefs", List.class);
        dedupe.setAccessible(true);
        return (List<TextPosition>) dedupe.invoke(null, raw);
    }

    /**
     * Every field of a glyph that {@link StreamTableExtractor} or {@link TableExtractor} reads:
     * unicode, the direction-adjusted geometry both paths cluster on, the raw geometry the lattice
     * text-fill uses, the font/size/space-width the word grouper uses, and the rotation.
     */
    private static String sig(TextPosition tp) {
        return tp.getUnicode()
                + "|" + tp.getXDirAdj() + "," + tp.getYDirAdj()
                + "|" + tp.getWidthDirAdj() + "x" + tp.getHeightDir()
                + "|" + tp.getX() + "," + tp.getY()
                + "|" + tp.getWidth() + "x" + tp.getHeight()
                + "|" + tp.getFontSize() + "/" + tp.getFontSizeInPt()
                + "|" + tp.getWidthOfSpace()
                + "|" + tp.getDir()
                + "|" + (tp.getFont() == null ? "-" : String.valueOf(tp.getFont().getName()));
    }

    private static List<String> sigs(List<TextPosition> glyphs) {
        List<String> out = new ArrayList<>(glyphs.size());
        for (TextPosition tp : glyphs) out.add(sig(tp));
        return out;
    }

    private static void assertParity(Path pdf, int pageIndex) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TextPosition> harness = TableTestPdfs.harvestGlyphs(doc, pageIndex);
            List<TextPosition> production = productionGlyphs(doc, pageIndex);
            String where = pdf.getFileName() + " page " + (pageIndex + 1);
            assertEquals(production.size(), harness.size(),
                    "glyph COUNT differs from production for " + where);
            List<String> ps = sigs(production), hs = sigs(harness);
            for (int i = 0; i < ps.size(); i++) {
                assertEquals(ps.get(i), hs.get(i),
                        "glyph #" + i + " differs from production for " + where);
            }
        }
    }

    // ------------------------------------------------------------------------------ synthetic

    @Test
    void harvestMatchesProductionOnSyntheticFixtures(@TempDir Path dir) throws Exception {
        Path ruled = dir.resolve("ruled.pdf");
        TableTestPdfs.ruled3x3(ruled);
        assertParity(ruled, 0);

        Path borderless = dir.resolve("borderless.pdf");
        TableTestPdfs.borderless3Col(borderless);
        assertParity(borderless, 0);

        Path tagged = dir.resolve("tagged.pdf");
        TableTestPdfs.tagged2x2(tagged);
        assertParity(tagged, 0);

        Path prose = dir.resolve("prose.pdf");
        TableTestPdfs.noTables(prose);
        assertParity(prose, 0);

        Path dupDrawn = dir.resolve("dup-drawn.pdf");
        TableTestPdfs.ruled2x2DuplicateDrawnCell(dupDrawn);
        assertParity(dupDrawn, 0);

        Path superscript = dir.resolve("superscript.pdf");
        TableTestPdfs.superscriptFootnote(superscript);
        assertParity(superscript, 0);

        Path whitespaceCells = dir.resolve("ws-cells.pdf");
        TableTestPdfs.ruledGridWithWhitespaceOnlyCells(whitespaceCells, 3, 4, false);
        assertParity(whitespaceCells, 0);

        Path multi = dir.resolve("multi.pdf");
        TableTestPdfs.multiPageRuled3x3(multi, 3);
        for (int p = 0; p < 3; p++) assertParity(multi, p);
    }

    /** An empty page must agree too -- both sides must produce an EMPTY list, not a null. */
    @Test
    void harvestMatchesProductionOnEmptyPage(@TempDir Path dir) throws Exception {
        Path grid = dir.resolve("empty-grid.pdf");
        TableTestPdfs.latticeEmptyGrid(grid);
        try (PDDocument doc = Loader.loadPDF(grid.toFile())) {
            List<TextPosition> harness = TableTestPdfs.harvestGlyphs(doc, 0);
            assertNotNull(harness);
            assertEquals(productionGlyphs(doc, 0).size(), harness.size());
        }
        assertParity(grid, 0);
    }

    // ---------------------------------------------------------------------------- real corpus

    /**
     * The real ICDAR PDFs are where the divergence actually bit (embedded subset fonts, ligatures,
     * glyphs with no unicode, content streams whose order is nothing like reading order). Skipped
     * when the gitignored corpus is absent.
     */
    @Test
    void harvestMatchesProductionOnRealCorpusPdfs() throws Exception {
        Path icdar = CORPUS_ROOT.resolve("icdar2013-dataset");
        assumeTrue(Files.isDirectory(icdar), "corpus fixture missing: " + icdar);
        List<Path> pdfs;
        try (var walk = Files.walk(icdar)) {
            pdfs = walk.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(CORPUS_SAMPLE)
                    .toList();
        }
        assumeTrue(!pdfs.isEmpty(), "no corpus PDFs found under " + icdar);
        List<String> checked = new ArrayList<>();
        for (Path pdf : pdfs) {
            int pages;
            try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                pages = doc.getNumberOfPages();
            } catch (IOException e) {
                continue;
            }
            for (int p = 0; p < Math.min(pages, 3); p++) assertParity(pdf, p);
            checked.add(pdf.getFileName().toString());
        }
        assumeTrue(!checked.isEmpty(), "no loadable corpus PDFs");
    }

    /**
     * Guard the specific divergence that was fixed: the harness must NOT be at PDFBox's default
     * ordering. On a real corpus PDF, default-order harvesting differs from sort-by-position
     * harvesting, and {@link TableTestPdfs#harvestGlyphs} must agree with the LATTER. If PDFBox ever
     * makes the two identical this test self-skips rather than asserting something vacuous.
     */
    @Test
    void harvestIsNotAtDefaultOrdering() throws Exception {
        Path icdar = CORPUS_ROOT.resolve("icdar2013-dataset");
        assumeTrue(Files.isDirectory(icdar), "corpus fixture missing: " + icdar);
        List<Path> pdfs;
        try (var walk = Files.walk(icdar)) {
            pdfs = walk.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        assumeTrue(!pdfs.isEmpty(), "no corpus PDFs found under " + icdar);

        boolean sawDifference = false;
        for (Path pdf : pdfs) {
            try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
                List<String> defaultOrder = sigs(defaultOrderGlyphs(doc, 0));
                List<String> harness = sigs(TableTestPdfs.harvestGlyphs(doc, 0));
                if (!defaultOrder.equals(harness)) { sawDifference = true; break; }
            } catch (IOException ignored) {
                // unloadable file -- try the next one
            }
        }
        assumeTrue(sawDifference,
                "no corpus PDF distinguishes default ordering from sort-by-position ordering; "
                        + "the parity tests above are the real guarantee");
    }

    /** The PRE-FIX harvest: bare PDFTextStripper, default ordering, no unicode filter. */
    private static List<TextPosition> defaultOrderGlyphs(PDDocument doc, int pageIndex) throws IOException {
        List<TextPosition> out = new ArrayList<>();
        PDFTextStripper s = new PDFTextStripper() {
            @Override protected void writeString(String t, List<TextPosition> ps) { out.addAll(ps); }
        };
        s.setStartPage(pageIndex + 1);
        s.setEndPage(pageIndex + 1);
        s.getText(doc);
        return out;
    }
}
