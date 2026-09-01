package com.oai.titanarum;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RulingCollectorTest {

    @TempDir
    Path tmp;

    @Test
    void collectsStrokedGridInTopLeftSpace() throws Exception {
        Path pdf = tmp.resolve("grid.pdf");
        TableTestPdfs.ruled3x3(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            long horiz = rulings.stream().filter(TableExtractor.Ruling::horizontal).count();
            long vert = rulings.stream().filter(TableExtractor.Ruling::vertical).count();
            assertEquals(4, horiz);
            assertEquals(4, vert);
            // Page height 792: bottom-left y=700 becomes top-left y=92.
            assertTrue(rulings.stream().anyMatch(r -> r.horizontal() && Math.abs(r.y1 - 92) <= 2),
                    "y must be flipped into top-left-origin space");
        }
    }

    @Test
    void treatsThinFilledRectsAsLines() throws Exception {
        Path pdf = tmp.resolve("fills.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // 1pt-tall filled rect = horizontal line; 300x200 filled rect = NOT a line
                cs.addRect(50, 700, 300, 1);
                cs.fill();
                cs.addRect(50, 400, 300, 200);
                cs.fill();
                // a stroked vertical so the horizontal isn't dropped later (not needed for collect)
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            assertEquals(1, rulings.size(), "only the thin fill is a ruling; the big rect is ignored");
            assertTrue(rulings.get(0).horizontal());
        }
    }

    @Test
    void batchedStrokedSubpathsAllYieldRulings() throws Exception {
        // Three m/l horizontal subpaths batched before a single S — the common generator
        // pattern (build the whole path, then one paint op) that a naive "clear on moveTo"
        // implementation would collapse down to just the last subpath.
        Path pdf = tmp.resolve("batched-stroke.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(1f);
                cs.moveTo(50, 700); cs.lineTo(200, 700);
                cs.moveTo(50, 670); cs.lineTo(200, 670);
                cs.moveTo(50, 640); cs.lineTo(200, 640);
                cs.stroke();
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            assertEquals(3, rulings.size(), "all three batched subpaths must survive, not just the last");
            assertTrue(rulings.stream().allMatch(TableExtractor.Ruling::horizontal));
        }
    }

    @Test
    void batchedFilledThinRectsAllYieldRulings() throws Exception {
        // Three re thin-rects batched before a single f — same batching pattern, fill path.
        Path pdf = tmp.resolve("batched-fill.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.addRect(50, 700, 100, 1);
                cs.addRect(50, 650, 100, 1);
                cs.addRect(50, 600, 100, 1);
                cs.fill();
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            assertEquals(3, rulings.size(), "each batched thin rect must be evaluated as its own ruling");
            assertTrue(rulings.stream().allMatch(TableExtractor.Ruling::horizontal));
        }
    }

    @Test
    void nonOriginCropBoxIsAccountedFor() throws Exception {
        Path pdf = tmp.resolve("cropbox.pdf");
        try (PDDocument doc = new PDDocument()) {
            // MediaBox must be >= the CropBox we set below: PDPage.getCropBox() clips to
            // the MediaBox, so a CropBox exceeding US Letter would silently shrink back down.
            PDPage page = new PDPage(new PDRectangle(800, 1000));
            // Lower-left (100,100), upper-right (700,900).
            page.setCropBox(new PDRectangle(100, 100, 600, 800));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                TableTestPdfs.line(cs, 150, 800, 350, 800);
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            // x' = x - 100 -> 50..250; y' = 900 - 800 = 100.
            assertTrue(rulings.stream().anyMatch(r -> r.horizontal()
                            && Math.abs(r.x1 - 50) <= 2 && Math.abs(r.x2 - 250) <= 2
                            && Math.abs(r.y1 - 100) <= 2),
                    "cropBox-relative origin must be applied, not just page height");
        }
    }

    @Test
    void unboundedPathIsDroppedNotThrown() throws Exception {
        Path pdf = tmp.resolve("giant-path.pdf");
        int n = TableExtractor.MAX_PATH_POINTS + 1000;
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.moveTo(10, 10);
                for (int i = 0; i < n; i++) {
                    cs.lineTo(10 + (i % 500), 10 + (i % 100));
                }
                cs.stroke();
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            assertEquals(0, rulings.size(), "an over-cap path is dropped silently, not converted to rulings");
        }
    }

    @Test
    void curveEndpointDoesNotEmitPhantomStraightRuling() throws Exception {
        // PR re-review P2 (correctness -- false positives) reproducer: strokePath used to blindly
        // connect every pair of CONSECUTIVE buffered path points with addRuling, regardless of
        // whether pdfbox reached the second point via a straight lineTo or a Bezier curveTo. A
        // curve's own endpoint is kept in the path for continuity (so a SUBSEQUENT lineTo from it
        // still connects correctly), so a curve whose start and end happen to share a y (or x)
        // coordinate -- a common shape, e.g. a rounded-rect corner returning to the straight edge's
        // own baseline -- injected a bogus straight-line "ruling" along the curve's chord, even
        // though nothing straight was ever drawn there.
        Path pdf = tmp.resolve("curve.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(1f);
                // Genuine straight rulings elsewhere on the page (a small ruled 2x2 grid) -- proves
                // ordinary lineTo rulings still survive this fix.
                for (float y : new float[]{700, 670, 640}) TableTestPdfs.line(cs, 50, y, 150, y);
                for (float x : new float[]{50, 100, 150}) TableTestPdfs.line(cs, x, 700, x, 640);

                // A Bezier curve, well away from the grid above, whose endpoint (500, 700) is
                // axis-aligned (same y) with its own start point (400, 700) -- the straight chord
                // between them must NOT be reported as a ruling.
                cs.moveTo(400, 700);
                cs.curveTo(430, 760, 470, 760, 500, 700);
                cs.stroke();
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));

            assertTrue(rulings.stream().noneMatch(r -> r.horizontal()
                            && Math.abs(r.x1 - 400) <= 2 && Math.abs(r.x2 - 500) <= 2),
                    "the curve's start-to-end chord must not be emitted as a straight ruling: " + rulings);

            long horiz = rulings.stream().filter(TableExtractor.Ruling::horizontal).count();
            long vert = rulings.stream().filter(TableExtractor.Ruling::vertical).count();
            assertEquals(3, horiz, "the genuine 2x2 grid's horizontal rulings must still be collected");
            assertEquals(3, vert, "the genuine 2x2 grid's vertical rulings must still be collected");
        }
    }

    @Test
    void straightLineViaCurveOperatorWithCollinearControlsIsCollectedAsRuling() throws Exception {
        // PR re-review, round 2 (recall regression in round 1's fix): round 1 suppressed EVERY
        // curveTo-authored segment purely because of which operator produced it -- but a genuinely
        // STRAIGHT line drawn via the `c` operator with control points collinear with its own
        // endpoints (a common vector-editor-export -- Illustrator/Inkscape -- and report-generator
        // pattern) is geometrically indistinguishable from a lineTo-drawn line and must still be
        // collected as a ruling.
        Path pdf = tmp.resolve("straight-via-curve.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(1f);
                TableTestPdfs.curveLine(cs, 50, 700, 250, 700); // horizontal, drawn via `c`
                TableTestPdfs.curveLine(cs, 50, 700, 50, 600);  // vertical, drawn via `c`
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            assertTrue(rulings.stream().anyMatch(r -> r.horizontal()
                            && Math.abs(r.x1 - 50) <= 2 && Math.abs(r.x2 - 250) <= 2),
                    "a straight horizontal line authored via the c operator with collinear "
                            + "controls must still be collected as a ruling: " + rulings);
            assertTrue(rulings.stream().anyMatch(r -> r.vertical()
                            && Math.abs(r.y1 - 92) <= 2 && Math.abs(r.y2 - 192) <= 2),
                    "a straight vertical line authored via the c operator with collinear "
                            + "controls must still be collected as a ruling: " + rulings);
        }
    }

    @Test
    void curvedBezierEndpointStillSuppressedAlongsideCollinearStraightCurves() throws Exception {
        // Companion sanity check for the round-2 fix: in ONE page, a genuinely curved Bezier
        // (control points well off the chord) and a straight line authored via curveTo with
        // collinear controls must be told apart correctly -- the curved one's chord must NOT be a
        // ruling, the collinear one's chord MUST be, even though both are dispatched through the
        // exact same curveTo override.
        Path pdf = tmp.resolve("mixed-curve.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(1f);
                // Genuinely curved: control points bulge well off the P0->P3 chord.
                cs.moveTo(400, 700);
                cs.curveTo(430, 760, 470, 760, 500, 700);
                cs.stroke();
                // Straight-via-curve: collinear controls.
                TableTestPdfs.curveLine(cs, 50, 500, 250, 500);
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            assertTrue(rulings.stream().noneMatch(r -> r.horizontal()
                            && Math.abs(r.x1 - 400) <= 2 && Math.abs(r.x2 - 500) <= 2),
                    "the genuinely curved Bezier's chord must still not be a ruling: " + rulings);
            assertTrue(rulings.stream().anyMatch(r -> r.horizontal()
                            && Math.abs(r.x1 - 50) <= 2 && Math.abs(r.x2 - 250) <= 2),
                    "the collinear-control straight line must still be a ruling: " + rulings);
        }
    }

    @Test
    void fullLatticeTableDrawnEntirelyViaCollinearControlCurvesIsStillDetected() throws Exception {
        // "Ideally assert a full lattice table..." -- end-to-end companion: a genuine 3x3 ruled
        // grid whose every border is authored via the c operator (collinear controls) must still
        // extract as a full 9-cell lattice table, exactly like the lineTo-drawn equivalent.
        Path pdf = tmp.resolve("ruled3x3_via_curves.pdf");
        TableTestPdfs.ruled3x3ViaCollinearCurves(pdf);
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            TableExtractor.Result r = TableExtractor.extract(doc, List.of(1), Map.of());
            assertEquals(1, r.tables.size(),
                    "a genuinely straight ruled grid authored entirely via curveTo must still be "
                            + "detected as one lattice table: " + r.tables);
            TableExtractor.TableHit t = r.tables.get(0);
            assertEquals("lattice", t.extractionMethod);
            assertEquals(3, t.rowCount);
            assertEquals(3, t.colCount);
            assertEquals(9, t.cells.size());
            assertEquals(List.of(
                    List.of("R1C1", "R1C2", "R1C3"),
                    List.of("R2C1", "R2C2", "R2C3"),
                    List.of("R3C1", "R3C2", "R3C3")), t.rows);
        }
    }

    @Test
    void fillAndStrokeThinBarEmitsOneRulingNotEdgesPlusCenterline() throws Exception {
        // PR re-review P2 reproducer: a table border commonly drawn as a thin filled+stroked
        // rectangle (the `B` operator) -- e.g. a shaded/colored bar used as a rule line. Before
        // the fix, fillAndStrokePath unconditionally emitted BOTH the stroked rectangle's two
        // long parallel edges (addRulingsForSubpath: bottom + top, the short left/right edges
        // fall under MIN_RULING_LEN and are dropped) AND the thin-fill centerline
        // (emitThinFillAsRuling) for the SAME bar -- 3 near-parallel horizontal rulings
        // (bottom edge, top edge, centerline) all within the bar's own thin height of each
        // other, which is itself chosen here (2pt) to sit right at the SNAP grid interval so
        // they'd normalize into distinct micro-rulings rather than collapsing back into one.
        // A thin fill-and-stroke bar is visually ONE logical border and must contribute
        // exactly ONE ruling: the single centerline.
        Path pdf = tmp.resolve("fill-and-stroke-thin-bar.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.75f);
                cs.addRect(50, 700, 300, 2); // 2pt-tall bar, right at the SNAP (2pt) interval
                cs.fillAndStroke(); // `B`: fill AND stroke the same rectangle
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            assertEquals(1, rulings.size(),
                    "a thin fill-and-stroke bar must yield exactly one ruling (the centerline), "
                            + "not the stroked edges plus the centerline: " + rulings);
            assertTrue(rulings.get(0).horizontal());
        }
    }

    @Test
    void fillAndStrokeWideRectStillEmitsStrokedEdgesNotCollapsedToCenterline() throws Exception {
        // Control for the fix above: a WIDE filled-and-stroked rectangle (e.g. a real shaded
        // cell background drawn with `B`, not a thin rule line) is NOT a thin bar per
        // emitThinFillAsRuling's own test, so it must keep contributing its stroked edges
        // exactly as before -- must NOT be mis-collapsed down to a single centerline.
        Path pdf = tmp.resolve("fill-and-stroke-wide-rect.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setLineWidth(0.75f);
                cs.addRect(50, 400, 300, 200); // wide AND tall: not a thin bar in either dimension
                cs.fillAndStroke();
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            List<TableExtractor.Ruling> rulings = TableExtractor.collectRulings(doc.getPage(0));
            assertEquals(4, rulings.size(),
                    "a wide filled-and-stroked rect's four stroked edges must survive, not "
                            + "collapse to a centerline: " + rulings);
            long horiz = rulings.stream().filter(TableExtractor.Ruling::horizontal).count();
            long vert = rulings.stream().filter(TableExtractor.Ruling::vertical).count();
            assertEquals(2, horiz, "top and bottom stroked edges");
            assertEquals(2, vert, "left and right stroked edges");
        }
    }

    @Test
    void capThrowsOnRulingBomb() throws Exception {
        Path pdf = tmp.resolve("bomb.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.LETTER);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int i = 0; i < TableExtractor.MAX_RULINGS_PER_PAGE + 100; i++) {
                    float y = 10 + (i % 770);
                    TableTestPdfs.line(cs, 10, y, 30, y);
                }
            }
            doc.save(pdf.toFile());
        }
        try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
            assertThrows(TableExtractor.RulingOverflowException.class,
                    () -> TableExtractor.collectRulings(doc.getPage(0)));
        }
    }
}
