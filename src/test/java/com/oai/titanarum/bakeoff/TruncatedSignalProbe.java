// Physically under src/test/java/com/oai/titanarum/bakeoff/ but declares `package com.oai.titanarum;`
// (same convention/reason as BakeOffHarness / BaselineHarness / PerDocArbProbe: it needs
// package-private access to TableExtractor/StreamTableExtractor and reuses BakeOffHarness's exact
// corpus-discovery logic byte-for-byte).
//
// PURPOSE. Measures how much of the tablesTruncated signal arbitration produces is genuine
// incompleteness vs. noise from its own ordinary, healthy operation (the ruled/tagged side winning
// a contest, which changes nothing about the emitted output). Across the 77-PDF ICDAR/tabula
// scoring corpus and the deterministic 200-PDF real-world prose sample
// (/home/coz/Downloads/phishpdfs), reports how many documents TableExtractor.extract(..., true)
// (i.e. --stream-tables) sets Result.truncated on, and for each, whether that document ever had a
// genuine arbitration displacement of a RULED/TAGGED candidate (content actually missing from the
// flag-off output, anyDisplacedRuled) vs. only a STREAM candidate losing (output for that region
// identical to flag-off, anyDisplacedStream). Not part of the normal test suite -- gated by
// -DtruncProbe=true, same convention as PerDocArbProbe -- so a future change to arbitrate()'s
// truncated policy can be checked against these same two corpora without re-deriving the
// methodology from scratch.
//
//   mvn -q -o test -Dtest=TruncatedSignalProbe -DtruncProbe=true
package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class TruncatedSignalProbe {

    @Test
    void run() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("truncProbe"), "set -DtruncProbe=true");

        StringBuilder notes = new StringBuilder();
        List<BakeOffHarness.ScoreUnit> units = BakeOffHarness.buildScoringSet(notes).units;
        System.out.println("=== 77-CORPUS (n=" + units.size() + ") ===");
        int corpusTrunc = 0;
        for (BakeOffHarness.ScoreUnit u : units) {
            Result r = scoreOne(u.pdf());
            if (r == null) continue;
            if (r.truncated) corpusTrunc++;
            System.out.printf(java.util.Locale.ROOT,
                    "DOC\t%s\ttruncated=%b\tarbDisplaced=%d\tanyDisplacedRuled=%b\tanyDisplacedStream=%b%n",
                    u.id(), r.truncated, r.arbitrationDisplaced, r.anyDisplacedRuled, r.anyDisplacedStream);
        }
        System.out.println("CORPUS_TOTAL\t" + units.size());
        System.out.println("CORPUS_TRUNCATED\t" + corpusTrunc);

        List<Path> prose = BakeOffHarness.sampleProsePdfs();
        if (prose == null) {
            System.out.println("=== 200-PDF PROSE SAMPLE: not found, skipped ===");
            return;
        }
        System.out.println("=== 200-PDF PROSE SAMPLE (n=" + prose.size() + ") ===");
        int proseTrunc = 0;
        for (Path p : prose) {
            Result r = scoreOne(p);
            if (r == null) continue;
            if (r.truncated) proseTrunc++;
            System.out.printf(java.util.Locale.ROOT,
                    "PROSE\t%s\ttruncated=%b\tarbDisplaced=%d\tanyDisplacedRuled=%b\tanyDisplacedStream=%b%n",
                    p.getFileName(), r.truncated, r.arbitrationDisplaced, r.anyDisplacedRuled, r.anyDisplacedStream);
        }
        System.out.println("PROSE_TOTAL\t" + prose.size());
        System.out.println("PROSE_TRUNCATED\t" + proseTrunc);
    }

    private record Result(boolean truncated, int arbitrationDisplaced,
                           boolean anyDisplacedRuled, boolean anyDisplacedStream) {}

    private static Result scoreOne(Path pdf) {
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<Integer> pages = new ArrayList<>();
            Map<Integer, List<TextPosition>> byPage = new LinkedHashMap<>();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                pages.add(p);
                byPage.put(p, TableTestPdfs.harvestGlyphs(doc, p - 1));
            }
            TableExtractor.Result res = TableExtractor.extract(doc, pages, byPage, true);
            boolean anyRuled = false, anyStream = false;
            for (TableExtractor.TableHit h : res.tables) {
                if (Boolean.TRUE.equals(h.displacedRuledCandidate)) anyRuled = true;
                if (Boolean.TRUE.equals(h.displacedStreamCandidate)) anyStream = true;
            }
            return new Result(res.truncated, res.arbitrationDisplaced, anyRuled, anyStream);
        } catch (Throwable e) {
            System.out.println("ERR " + pdf + " " + e);
            return null;
        }
    }
}
