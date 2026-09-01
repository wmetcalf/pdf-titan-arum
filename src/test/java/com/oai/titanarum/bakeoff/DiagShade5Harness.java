// THROWAWAY DIAGNOSTIC (shading lever, phase 5): why do the three zero-hit, heavily-shaded PDFs
// (us-011a, us-010, us-036) produce nothing? Dumps per-block band / gutter count / trim / gridness
// so we can tell whether fill evidence could have rescued them at all. Purely observational.
package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

class DiagShade5Harness {

    private static final Path ROOT = Path.of("corpus/tabula-java/src/test/resources/technology/tabula")
            .toAbsolutePath().normalize();

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("diagShade5"), "set -DdiagShade5=true to run");
        dump(ROOT.resolve("icdar2013-dataset/competition-dataset-us/us-011a.pdf"), 2);
        dump(ROOT.resolve("icdar2013-dataset/competition-dataset-us/us-011a.pdf"), 3);
        dump(ROOT.resolve("icdar2013-dataset/competition-dataset-us/us-010.pdf"), 2);
        dump(ROOT.resolve("icdar2013-dataset/competition-dataset-us/us-036.pdf"), 2);
        System.out.println();
        System.out.println("=== RULING CENSUS (top FP / near-miss PDFs): are these actually RULED docs?");
        rulings(ROOT.resolve("schools.pdf"));
        rulings(ROOT.resolve("Publication_of_award_of_Bids_for_Transport_Sector__August_2016.pdf"));
        rulings(ROOT.resolve("icdar2013-dataset/competition-dataset-us/us-012.pdf"));
        rulings(ROOT.resolve("icdar2013-dataset/competition-dataset-us/us-037.pdf"));
        rulings(ROOT.resolve("icdar2013-dataset/competition-dataset-us/us-001.pdf"));
        rulings(ROOT.resolve("icdar2013-dataset/competition-dataset-us/us-017.pdf"));
        rulings(ROOT.resolve("icdar2013-dataset/competition-dataset-us/us-018.pdf"));
        rulings(ROOT.resolve("spanning_cells.pdf"));
    }

    private void rulings(Path pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            int pages = Math.min(3, doc.getNumberOfPages());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pages; i++) {
                try {
                    List<TableExtractor.Ruling> rs = TableExtractor.normalize(
                            TableExtractor.collectRulings(doc.getPage(i)));
                    long h = rs.stream().filter(TableExtractor.Ruling::horizontal).count();
                    sb.append(String.format(Locale.ROOT, " p%d:h=%d,v=%d", i + 1, h, rs.size() - h));
                } catch (Throwable t) { sb.append(" p").append(i + 1).append(":ERR"); }
            }
            System.out.println("  " + pdf.getFileName() + sb);
        } catch (Throwable t) {
            System.out.println("  " + pdf.getFileName() + " load fail " + t);
        }
    }

    private void dump(Path pdf, int pageNum) throws Exception {
        System.out.println("=== " + pdf.getFileName() + " page " + pageNum);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TextPosition> glyphs = TableTestPdfs.harvestGlyphs(doc, pageNum - 1);
            List<StreamTableExtractor.Word> words = StreamTableExtractor.buildWords(glyphs);
            System.out.println("  words=" + words.size());
            if (words.size() < 6) return;
            float mfs = StreamTableExtractor.medianFontSize(words);
            List<StreamTableExtractor.Line> lines = StreamTableExtractor.buildLines(words, mfs);
            float medianSpace = 0.5f * mfs;
            List<List<StreamTableExtractor.Line>> blocks = StreamTableExtractor.splitIntoBlocks(lines);
            System.out.println("  lines=" + lines.size() + " blocks=" + blocks.size()
                    + " medianFontSize=" + mfs);
            GutterFinder f = new BreuelGutterFinder();
            int bi = 0;
            for (List<StreamTableExtractor.Line> block : blocks) {
                bi++;
                if (block.size() < 3) {
                    System.out.printf(Locale.ROOT, "  block%d lines=%d -> SKIPPED (<3 lines)%n", bi, block.size());
                    continue;
                }
                float x0 = Float.MAX_VALUE, x1 = -Float.MAX_VALUE, yt = Float.MAX_VALUE, yb = -Float.MAX_VALUE;
                for (StreamTableExtractor.Line l : block) {
                    yt = Math.min(yt, l.yTop); yb = Math.max(yb, l.yBot);
                    for (StreamTableExtractor.Word w : l.words) { x0 = Math.min(x0, w.x0); x1 = Math.max(x1, w.x1); }
                }
                try {
                    List<StreamTableExtractor.Gutter> gutters = f.find(block, x0, x1, medianSpace);
                    List<StreamTableExtractor.Line> trimmed =
                            StreamTableExtractor.trimEdgeLines(block, gutters, x0, x1, medianSpace);
                    StreamTableExtractor.Grid grid =
                            StreamTableExtractor.scoreGrid(trimmed, gutters, x0, x1);
                    System.out.printf(Locale.ROOT,
                            "  block%d lines=%d y=[%.0f..%.0f] x=[%.0f..%.0f] gutters=%d trimmed=%d conf=%.3f %s%n",
                            bi, block.size(), yt, yb, x0, x1, gutters.size(), trimmed.size(), grid.confidence,
                            grid.confidence < StreamTableExtractor.STREAM_CONFIDENCE_MIN ? "REJECTED" : "ACCEPTED");
                    if (block.size() >= 3 && gutters.isEmpty()) {
                        StringBuilder sb = new StringBuilder("      first lines: ");
                        for (int i = 0; i < Math.min(3, block.size()); i++) {
                            sb.append('|');
                            for (StreamTableExtractor.Word w : block.get(i).words) sb.append(w.text).append(' ');
                        }
                        System.out.println(sb);
                    }
                } catch (TableExtractor.RulingOverflowException e) {
                    System.out.printf(Locale.ROOT, "  block%d lines=%d -> DoS ABORT%n", bi, block.size());
                }
            }
        }
    }
}
