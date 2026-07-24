package com.oai.titanarum;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StreamGridnessTest {
    private static StreamTableExtractor.Word w(float x0, float x1, float yTop, String t) {
        StreamTableExtractor.Word wd = new StreamTableExtractor.Word();
        wd.x0=x0; wd.x1=x1; wd.y0=yTop; wd.y1=yTop+10; wd.text=t;
        wd.numeric = t.matches("[-+(]?[\\d.,%$)]+") && t.chars().anyMatch(Character::isDigit);
        return wd;
    }

    @Test
    void numericGridScoresHigh() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 45, y, "Candidate" + r));
            ws.add(w(70, 88, y, String.valueOf(100 + r)));
            ws.add(w(130,150, y, String.valueOf(r) + ".5"));
        }
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        var g = StreamTableExtractor.findGutters(lines, 10, 150, 6f);
        var grid = StreamTableExtractor.scoreGrid(lines, g, 10, 150);
        assertTrue(grid.confidence >= 0.55, "numeric 3-col grid must clear threshold, got " + grid.confidence);
    }

    @Test
    void twoColumnProseScoresBelowThreshold() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 10; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 95, y, "leftcolumnfullofprosethatwraps"));   // fills column width
            ws.add(w(110,195, y, "rightcolumnfullofprosethatwraps"));
        }
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        var g = StreamTableExtractor.findGutters(lines, 10, 195, 6f);
        var grid = StreamTableExtractor.scoreGrid(lines, g, 10, 195);
        assertTrue(grid.confidence < 0.55, "two-column prose must be suppressed, got " + grid.confidence);
    }

    @Test
    void twoColumnNumericFirstColumnScoresHigh() {
        // Finding 1: numeric-ness must be checked on ALL columns, not just c=1..cols-1.
        // Here the NUMBERS are in column 0 and the labels are in column 1 -- the mirror image
        // of a typical "label, then number" financial table. The spec never dictates which
        // side holds the numbers, so this must clear the cols==2 gate exactly like a
        // number-in-column-1 table would.
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 45, y, String.valueOf(1000 + r * 7)));
            ws.add(w(70, 150, y, "LineItemLabel" + r));
        }
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        var g = StreamTableExtractor.findGutters(lines, 10, 150, 6f);
        var grid = StreamTableExtractor.scoreGrid(lines, g, 10, 150);
        assertTrue(grid.confidence >= 0.55,
            "2-col numeric table with numbers in column 0 must clear threshold, got " + grid.confidence);
        assertTrue(grid.numericLeanColumn, "numericLeanColumn must be set when column 0 is numeric-leaning");
    }

    @Test
    void threeColumnProseScoresBelowThreshold() {
        // Finding 2: the prose firewall's weighted proseScore path was never exercised --
        // the old prose test was 2-column and short-circuited to confidence 0 via the
        // cols==2 hard gate before the weighted sum ever ran. 3+-column prose (newsletter /
        // multi-column article layout) is a realistic false-positive source.
        //
        // Columns have unequal paragraph lengths (a caption/pull-quote breaks up columns 2
        // and 3 for several rows) as real multi-column body text does -- this is also
        // necessary to get colConsistency below its "full credit at 85%" ceiling: a
        // perfectly row-aligned 3-column block scores >=0.625 from colConsistency +
        // violationScore + the column-count bonus ALONE, before proseScore is even applied,
        // so proseScore can never be the deciding factor for perfectly-aligned text.
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 18; r++) {
            float y = 20 + r * 15;
            boolean captionGap = r >= 5 && r <= 12; // photo/pull-quote breaks columns 2 & 3
            ws.add(w(10, 95, y, "leftcolumnprosewordthatwrapsfully"));
            if (!captionGap) {
                ws.add(w(110, 195, y, "middlecolumnprosewordthatwraps"));
                ws.add(w(210, 295, y, "rightcolumnprosewordthatwrapsfully"));
            }
        }
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        var g = StreamTableExtractor.findGutters(lines, 10, 295, 6f);
        var grid = StreamTableExtractor.scoreGrid(lines, g, 10, 295);
        assertTrue(grid.confidence < 0.55, "three-column prose must be suppressed, got " + grid.confidence);
    }

    @Test
    void occasionalStraddlerDoesNotDisqualifyRealTable() {
        // Finding 3: the violation ramp had no tolerance plateau, so a single straddling word
        // out of ~24 (violation ~4%) cost ~0.21 confidence off a term weighted at 0.25 --
        // enough to sink a legitimate wrapped-cell table (Animal|Action|Result, one very long
        // token that overruns its column into the next) that sits thin above threshold.
        //
        // Gutters are supplied explicitly (rather than via findGutters) because findGutters
        // is itself self-correcting: given a single-row obstruction, it simply narrows the
        // accepted gutter to dodge it, so no word ever registers a "straddle" against a
        // *discovered* gutter. That self-correction is a property of findGutters, not of
        // scoreGrid's violation tolerance, which is what this test isolates: a genuine straddle
        // against the table's real (already-resolved) column boundaries.
        String[] animals = {"Cat","Dog","Fox","Owl","Bee","Ant","Cow","Pig"};
        String[] actions = {"Runs","Jumps","Flies","Hunts","Hides","Digs","Grazes","Roots"};
        String[] results = {"Fast","Far","High","Silent","Well","Deep","Slow","Muddy"};
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 60, y, animals[r]));
            if (r == 4) {
                // one very long wrapped-cell token straddling the col1/col2 gutter (cx=180)
                ws.add(w(140, 200, y, "Burrowsintotheground"));
            } else {
                ws.add(w(90, 150, y, actions[r]));
            }
            ws.add(w(190, 235, y, results[r]));
        }
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        List<StreamTableExtractor.Gutter> gutters = List.of(gutter(60, 90), gutter(170, 190));
        var grid = StreamTableExtractor.scoreGrid(lines, gutters, 10, 260);
        assertTrue(grid.confidence >= 0.55,
            "one straddling word out of ~24 must not disqualify a real table, got " + grid.confidence);
    }

    @Test
    void wellAlignedThreeColumnProseIsVetoed() {
        // The exact "0.625 before proseScore" case from the design-defect report: a
        // perfectly row-aligned, zero-violation 3-column block (no caption/pull-quote gaps
        // unlike threeColumnProseScoresBelowThreshold below) where colConsistency +
        // violationScore + the column-count bonus alone already clear 0.55. Every column is
        // populated and near-full-width on EVERY row -- the majority-of-columns,
        // majority-of-rows-per-column prose signature -- so the hard veto must fire and zero
        // the score outright, regardless of how the graded terms alone would have scored it.
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 18; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 95, y, "leftcolumnprosewordthatwrapsfully"));
            ws.add(w(110, 195, y, "middlecolumnprosewordthatwraps"));
            ws.add(w(210, 295, y, "rightcolumnprosewordthatwrapsfully"));
        }
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        var g = StreamTableExtractor.findGutters(lines, 10, 295, 6f);
        var grid = StreamTableExtractor.scoreGrid(lines, g, 10, 295);
        assertEquals(0.0, grid.confidence, 1e-9,
            "well-aligned zero-violation 3-col prose must be hard-vetoed, got " + grid.confidence);
        assertTrue(grid.confidence < 0.55);
    }

    @Test
    void wrappedCellTableIsNotVetoed() {
        // Guard against an over-broad veto: a real borderless Animal | Action | Result
        // table. Column 1 ("Action") is short on EVERY row ("Says"). Column 0 ("Animal")
        // is short on every row except one, which holds an extremely long single-token
        // cell ("Parastratiosphecomyiastratiosphecomyioides") wide enough to nearly fill
        // its column. Column 2 ("Result") is short on every row except one, whose text
        // wraps across two physical lines (a continuation line with only that column
        // populated -- "Ring-dingdingding..." running onto a second line). Because the
        // wide/wrapped cells are a MINORITY of each column's own rows (not the column's
        // predominant behavior), and "Action" never qualifies at all, no column -- let
        // alone a majority of columns -- is "prose-like", so the veto must NOT fire and
        // this real table must still clear the confidence threshold.
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        String[] animals = {"Cat", "Dog", "Owl", "Fox",
            "Parastratiosphecomyiastratiosphecomyioides", "Bee", "Ant", "Cow"};
        String[] results = {"Meow", "Woof", "Hoot", "Yip",
            "Ring-dingdingding", "Buzz", "March", "Moo"};
        for (int r = 0; r < animals.length; r++) {
            float y = 20 + r * 15;
            if (r == 4) {
                ws.add(w(10, 148, y, animals[r]));           // fills col0 (extremely long token)
            } else {
                ws.add(w(10, 40, y, animals[r]));             // short, every other row
            }
            ws.add(w(160, 195, y, "Says"));                   // short on EVERY row
            ws.add(w(230, 270, y, results[r]));
            if (r == 4) {
                // wrapped continuation line for the Result cell only: same column,
                // no sibling columns populated -- a real wrapped-cell table's signature,
                // not the majority-of-columns / majority-of-rows prose signature.
                ws.add(w(230, 390, y + 7, "dingdingding-dingdingding-dingdingding"));
            }
        }
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        List<StreamTableExtractor.Gutter> gutters = List.of(gutter(150, 160), gutter(220, 230));
        var grid = StreamTableExtractor.scoreGrid(lines, gutters, 10, 400);
        assertTrue(grid.confidence >= 0.55,
            "a real wrapped-cell table must not be vetoed as prose, got " + grid.confidence);
    }

    private static StreamTableExtractor.Gutter gutter(float x0, float x1) {
        StreamTableExtractor.Gutter g = new StreamTableExtractor.Gutter();
        g.x0 = x0; g.x1 = x1;
        return g;
    }
}
