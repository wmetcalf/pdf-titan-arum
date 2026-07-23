package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;

import java.nio.file.Path;
import java.util.List;

/**
 * Standalone probe launched as a CHILD JVM (with a small, fixed {@code -Xmx}) by {@code
 * TableLatticeTest}'s FIX A regression test, reproducing the reviewer's own OOM methodology:
 * {@code TableExtractor.fillCellsByRegion} (the {@code --skip-text-urls} fallback) called
 * directly, in-process, on a small-on-disk, one-page fixture whose content stream carries
 * millions of glyphs in a single Flate-compressed {@code Tj} string -- the same shape as the
 * reviewer's 12,483-byte / 6,000,000-glyph repro.
 *
 * <p>Exits 0 ("PROBE_OK") whether {@code fillCellsByRegion} completes normally or throws the
 * bounded {@link TableExtractor.RulingOverflowException} once {@link TableExtractor#MAX_REGION_GLYPHS}
 * trips -- both outcomes mean "did not OOM, did not hang". A crash (OutOfMemoryError, or any
 * other uncaught throwable) produces a non-zero exit / stack trace on stderr instead.
 */
public final class TableRegionGlyphBombProbe {
    private TableRegionGlyphBombProbe() {}

    public static void main(String[] args) throws Exception {
        int glyphCount = Integer.parseInt(args[0]);
        Path pdfPath = Path.of(args[1]);
        TableTestPdfs.manyGlyphsOnePage(pdfPath, glyphCount);

        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDPage page = doc.getPage(0);
            // A huge cell OVERLAPPING the entire glyph run (rather than far from it): this forces
            // PositionCollectingStripper to actually RETAIN glyphs (up to MAX_REGION_GLYPHS) rather
            // than discarding them via its combined-bbox pre-filter, proving the retention cap
            // itself -- not the pre-filter -- is what bounds memory here.
            TableExtractor.CellRect cell = new TableExtractor.CellRect();
            cell.x0 = -1_000f; cell.y0 = 700f; cell.x1 = 100_000_000f; cell.y1 = 800f;
            List<List<TableExtractor.CellRect>> tables = List.of(List.of(cell));
            TableExtractor.Result result = new TableExtractor.Result();
            try {
                TableExtractor.fillCellsByRegion(tables, page, result);
            } catch (TableExtractor.RulingOverflowException e) {
                // MAX_REGION_GLYPHS tripped -- a bounded, deliberate throw, not a crash. Either
                // outcome (completes, or throws this) proves the memory/CPU bound held.
            }
        }
        System.out.println("PROBE_OK");
    }
}
