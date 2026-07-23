package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
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
        buildManyGlyphsOnePagePdf(pdfPath, glyphCount);

        try (PDDocument doc = Loader.loadPDF(pdfPath.toFile())) {
            PDPage page = doc.getPage(0);
            // One small cell, far from the default (0,0)-ish text-render position: exercises the
            // real streaming region-strip pass (non-empty `tables`) while keeping matched-glyph
            // retention at ~zero, proving memory stays flat regardless of the glyph count.
            TableExtractor.CellRect cell = new TableExtractor.CellRect();
            cell.x0 = 600; cell.y0 = 780; cell.x1 = 601; cell.y1 = 781;
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

    /** Builds a one-page PDF whose content stream is a single {@code (AAAA...) Tj}, deflated by
     * PDFBox's own {@code createOutputStream(FLATE_DECODE)} as it is written -- millions of
     * identical bytes compress to a few KB on disk, matching how a hostile few-KB PDF can carry
     * millions of glyphs. */
    private static void buildManyGlyphsOnePagePdf(Path pdf, int glyphCount) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            PDResources resources = new PDResources();
            resources.put(COSName.getPDFName("F1"), new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            page.setResources(resources);

            COSStream cosStream = doc.getDocument().createCOSStream();
            try (OutputStream os = cosStream.createOutputStream(COSName.FLATE_DECODE)) {
                os.write("BT /F1 12 Tf (".getBytes(StandardCharsets.ISO_8859_1));
                byte[] chunk = new byte[1 << 16];
                Arrays.fill(chunk, (byte) 'A');
                int remaining = glyphCount;
                while (remaining > 0) {
                    int n = Math.min(chunk.length, remaining);
                    os.write(chunk, 0, n);
                    remaining -= n;
                }
                os.write(") Tj ET".getBytes(StandardCharsets.ISO_8859_1));
            }
            page.getCOSObject().setItem(COSName.CONTENTS, cosStream);
            doc.save(pdf.toFile());
        }
    }
}
