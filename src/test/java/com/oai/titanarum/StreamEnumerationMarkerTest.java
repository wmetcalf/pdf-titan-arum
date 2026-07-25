package com.oai.titanarum;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A NUMBERED LIST IS NOT A NUMERIC DATA COLUMN.
 *
 * <p>{@link StreamTableExtractor#isNumericToken} accepts "1." "2." "3." -- digits plus the
 * punctuation numbers are written with, plus at least one real digit -- so the MARKER column of an
 * enumerated list satisfied every numeric test in {@link StreamTableExtractor#scoreGrid}. That made
 * a two-column numbered list (markers on the left, the list item's sentence on the right) pass the
 * {@code cols == 2 && numericDataCols == 0} gate on numeric evidence it does not have, and a
 * numbered list is the single most common thing a borderless-table detector fabricates a table over.
 *
 * <p>The fixtures here are built from ADJUDICATED fabrications found in real data (see
 * {@code contentfp-report.md}): the Polish "download the update" instruction list from the
 * real-world sample, and the {@code (1)/(2)/(3)} criteria list on page 4 of ICDAR {@code us-027} --
 * the ONLY two candidates out of 208 measured (162 ICDAR corpus + 46 real-world) whose numeric
 * evidence is marker-only, and both are enumerated lists rather than tables.
 *
 * <p>The guard tests in the second half are the reason this is a MECHANISM fix and not a threshold:
 * every shape of two-column table that carries genuine numeric data -- currency, quantities,
 * bare small integers, decimals, and numbers in either column -- must keep passing.
 */
class StreamEnumerationMarkerTest {

    private static StreamTableExtractor.Word w(float x0, float x1, float yTop, String t) {
        StreamTableExtractor.Word wd = new StreamTableExtractor.Word();
        wd.x0 = x0; wd.x1 = x1; wd.y0 = yTop; wd.y1 = yTop + 10; wd.text = t;
        wd.numeric = StreamTableExtractor.isNumericToken(t);
        return wd;
    }

    /** Score a word bag exactly as production does: buildLines -> findGutters -> scoreGrid. */
    private static StreamTableExtractor.Grid score(List<StreamTableExtractor.Word> ws,
                                                  float x0, float x1) {
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        var g = StreamTableExtractor.findGutters(lines, x0, x1, 6f);
        return StreamTableExtractor.scoreGrid(lines, g, x0, x1);
    }

    /**
     * A multi-token sentence laid out left-to-right from {@code x0}, one 6pt-per-char token per
     * word with 4pt spaces -- so the cell is MANY SHORT tokens, the way real prose is, rather than
     * one long token (which is how every pre-existing prose fixture in StreamGridnessTest is built,
     * and the reason those fixtures could not see the cell-vs-token prose-fill defect).
     */
    private static void sentence(List<StreamTableExtractor.Word> ws, float x0, float y, String s) {
        float x = x0;
        for (String tok : s.split(" ")) {
            float wd = tok.length() * 6f;
            ws.add(w(x, x + wd, y, tok));
            x += wd + 4f;
        }
    }

    // ------------------------------------------------------------------ the adjudicated fabrications

    /**
     * The Polish instruction list ({@code bCscEoYI...bin}, real-world sample, confidence 0.7407 at
     * the defect): "1. | Pobierz uaktualnienie Adobe Acrobat Reader TUTAJ...". Column 0 holds only
     * the markers, so it is 100% "numeric" by token and passed the cols==2 numeric-evidence gate.
     */
    @Test
    void twoColumnNumberedListIsNotAdmittedOnMarkerOnlyNumericEvidence() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        String[] items = {
            "Pobierz uaktualnienie Adobe Acrobat Reader TUTAJ w przypadku przegladarki Google Chrome",
            "Uruchom Aktualizacje AdobeUpdater bat teraz",
            "Poczekaj na zaktualizowanie oprogramowania Adobe",
        };
        for (int r = 0; r < items.length; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 22, y, (r + 1) + "."));      // the marker column
            sentence(ws, 40, y, items[r]);
        }
        var grid = score(ws, 10, 560);
        assertEquals(2, grid.nCols, "fixture sanity: the list must be read as two columns");
        assertTrue(grid.confidence < 0.55,
                "a 2-column numbered list whose ONLY numeric evidence is its marker column must "
                + "not be admitted, got confidence " + grid.confidence
                + " hardReject=" + grid.hardReject);
    }

    /**
     * The ICDAR {@code us-027} page-4 criteria list: "(1) The incident occurred between January 1,
     * 1900 and December 31, 2008,". Parenthesised markers, and the item text itself contains real
     * numbers -- so this must be refused on the MARKER column's ineligibility, not on the absence
     * of digits anywhere in the block.
     */
    @Test
    void parenthesisedMarkerListWithNumbersInsideTheProseIsStillNotATable() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        // The item texts must NOT share aligned leading tokens, or the gutter finder legitimately
        // reads the shared prefix as further columns (a real paragraph has no such alignment --
        // verified: identical "The incident occurred" prefixes yield a 6-column read).
        String[] items = {
            "Occurred between January 1, 1900 and December 31, 2008 inclusive,",
            "In or around a non-campus facility, see Appendix A for the 3 definitions",
            "Within the United States and its 5 outlying territories.",
        };
        for (int r = 0; r < items.length; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 28, y, "(" + (r + 1) + ")"));
            sentence(ws, 46, y, items[r]);
        }
        var grid = score(ws, 10, 560);
        assertEquals(2, grid.nCols, "fixture sanity: two columns");
        assertTrue(grid.confidence < 0.55,
                "a parenthesised-marker list must not be admitted on its marker column, got "
                + grid.confidence + " hardReject=" + grid.hardReject);
    }

    // ------------------------------------------- guards: real numeric two-column tables still pass

    /** Currency: the receipt/order-summary shape the adjudication found is genuinely a table. */
    @Test
    void twoColumnCurrencyTableStillPasses() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        String[] labels = {"Subtotal", "FreeShipping", "EstimatedTax", "OrderTotal"};
        String[] amounts = {"$559.00", "$0.00", "$39.69", "$598.69"};
        for (int r = 0; r < labels.length; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 80, y, labels[r]));
            ws.add(w(120, 165, y, amounts[r]));
        }
        var grid = score(ws, 10, 165);
        assertTrue(grid.confidence >= 0.55,
                "a 2-column currency table must still clear the bar, got " + grid.confidence
                + " hardReject=" + grid.hardReject);
    }

    /**
     * BARE SMALL INTEGERS. The nearest neighbour of an enumeration marker: a quantity column
     * holding 1, 2, 3 with no list punctuation. It must keep counting as numeric data -- the
     * discriminator is the marker PUNCTUATION, not smallness of the number.
     */
    @Test
    void twoColumnBareSmallIntegerQuantityColumnStillPasses() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        String[] labels = {"Widgets", "Grommets", "Flanges", "Bushings", "Sprockets"};
        for (int r = 0; r < labels.length; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 80, y, labels[r]));
            ws.add(w(120, 132, y, String.valueOf(r + 1)));
        }
        var grid = score(ws, 10, 132);
        assertTrue(grid.confidence >= 0.55,
                "a bare small-integer quantity column is numeric DATA, not a list marker; got "
                + grid.confidence + " hardReject=" + grid.hardReject);
    }

    /**
     * Numbers in column 0 -- the mirror image guarded by
     * StreamGridnessTest#twoColumnNumericFirstColumnScoresHigh -- must not become collateral of a
     * rule that looks at the LEFT column for markers.
     */
    @Test
    void numbersInColumnZeroStillPass() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 45, y, String.valueOf(1000 + r * 7)));
            ws.add(w(70, 150, y, "LineItemLabel" + r));
        }
        var grid = score(ws, 10, 150);
        assertTrue(grid.confidence >= 0.55,
                "numbers in column 0 must still clear the bar, got " + grid.confidence
                + " hardReject=" + grid.hardReject);
    }

    /**
     * A MINORITY of marker-shaped tokens in an otherwise real numeric column must not disqualify
     * it: "1." can legitimately appear as a truncated/odd rendering inside a column of real values.
     */
    @Test
    void aMinorityOfMarkerShapedTokensDoesNotDisqualifyARealNumericColumn() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        String[] amounts = {"12.50", "3.", "44.75", "108.20", "9.99", "76.40"};
        for (int r = 0; r < amounts.length; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 80, y, "Item" + r));
            ws.add(w(120, 160, y, amounts[r]));
        }
        var grid = score(ws, 10, 160);
        assertTrue(grid.confidence >= 0.55,
                "one marker-shaped token among five real values must not disqualify the column, got "
                + grid.confidence + " hardReject=" + grid.hardReject);
    }

    // ------------------------------------------------------- the prose-CELL veto (content, not width)

    /**
     * The tax-refund prose block ({@code SR3q_xAV...bin}, adjudicated a fabrication, admitted at
     * confidence 0.5603 before this veto). Three lines of a running sentence broken across three
     * columns. The GEOMETRIC prose veto cannot see it: the columns are wide relative to their ragged
     * text so no column reaches {@link StreamTableExtractor#VETO_FILL_THRESHOLD} on a majority of its
     * rows. Counting TOKENS per cell does see it.
     */
    @Test
    void multiColumnRunningProseIsVetoedOnCellTokenCounts() {
        // Geometry deliberately gives every column a RAGGED right edge with ~40% slack, so the
        // pre-existing GEOMETRIC prose veto cannot fire (no column fills > VETO_FILL_THRESHOLD of
        // its width). If it fired anyway the assertion below would name it, which is the point:
        // this test must prove the CONTENT veto did the work.
        String[][] rows = {
            {"the year ending in 2017 saw", "all of the claims for a", "refund are allowed for some"},
            {"a duration of 48 hours is", "allowed when claiming your tax", "after we dispatch this mail"},
            {"in case of no claims are", "made for your outstanding tax", "the total will add up to"},
            {"into next year tax returns", "and no refund is then", "payable to you at all"},
        };
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < rows.length; r++) {
            float y = 20 + r * 15;
            sentence(ws, 10, y, rows[r][0]);
            sentence(ws, 350, y, rows[r][1]);
            sentence(ws, 680, y, rows[r][2]);
        }
        var grid = score(ws, 10, 1000);
        assertTrue(grid.tProseCellFrac > StreamTableExtractor.PROSE_CELL_MAJORITY_FRACTION,
                "fixture sanity: a majority of cells must be clause-length, got "
                + grid.tProseCellFrac);
        assertEquals("prose-cell-veto", grid.hardReject,
                "multi-column running prose must be vetoed on cell token counts, confidence "
                + grid.confidence);
    }

    /**
     * THE MARGIN. A genuine table whose cells ARE sentences must survive -- this is the shape of the
     * lower half of the real 3-column FDA table on page 4 of ICDAR {@code us-015}, which scores 0.489
     * and is the closest real table to the bar. The veto's whole justification is that it clears this
     * by construction rather than by tuning, so the case is asserted directly: a short label column
     * beside a long-text column keeps the clause-length cells to a MINORITY of all cells.
     */
    @Test
    void aRealTableWhoseCellsAreSentencesIsNotVetoed() {
        String[] labels = {"Content", "Construct", "Reliable", "Response"};
        String[] prose = {
            "evidence the instrument measures the concept",
            "relationships among items conform a priori",
            "stability of scores over time when steady",
            "ability to detect change over the period",
        };
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < labels.length; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 60, y, labels[r]));             // short label cell   (1 token)
            ws.add(w(350, 410, y, "Bullet" + r));        // short middle cell  (1 token)
            sentence(ws, 680, y, prose[r]);              // the long-text cell (6-7 tokens)
        }
        var grid = score(ws, 10, 1000);
        assertTrue(grid.tProseCellFrac <= StreamTableExtractor.PROSE_CELL_MAJORITY_FRACTION,
                "one long-text column out of three must stay a MINORITY of cells, got "
                + grid.tProseCellFrac);
        assertTrue(grid.confidence >= 0.55,
                "a real table with one sentence-length column must still clear the bar, got "
                + grid.confidence + " hardReject=" + grid.hardReject);
    }

    /** The measure itself: a minority of long cells is not a majority, whatever their length. */
    @Test
    void proseCellFractionCountsCellsNotWords() {
        List<StreamTableExtractor.Word> ws = new ArrayList<>();
        for (int r = 0; r < 4; r++) {
            float y = 20 + r * 15;
            ws.add(w(10, 60, y, "Label" + r));
            ws.add(w(120, 150, y, String.valueOf(r * 10)));
        }
        // one very long cell in an otherwise 1-token grid: 1 of 9 cells, nowhere near a majority
        sentence(ws, 200, 20, "an outlier wrapped cell with a great many tokens in it indeed");
        var lines = StreamTableExtractor.buildLines(ws, 10f);
        var g = StreamTableExtractor.findGutters(lines, 10, 560, 6f);
        var grid = StreamTableExtractor.scoreGrid(lines, g, 10, 560);
        assertTrue(grid.tProseCellFrac < 0.30,
                "one long cell among many short ones must stay a small fraction, got "
                + grid.tProseCellFrac);
        assertNotEquals("prose-cell-veto", grid.hardReject,
                "a single outlier wrapped cell must not trip the prose-cell veto");
    }

    // ------------------------------------------------------------------- the classifier in isolation

    @Test
    void enumerationMarkerRecognisesListPunctuationOnly() {
        for (String s : new String[]{"1.", "2.", "9.", "10.", "99.", "1)", "(1)", "(12)",
                                     "a.", "b)", "(c)", "iv.", "(vii)", "IV."}) {
            assertTrue(StreamTableExtractor.isEnumerationMarker(s), s + " is an enumeration marker");
        }
        for (String s : new String[]{"1", "12", "2008", "2008.", "12.6", "$100.00", "0.00",
                                     "1,250", "(0%)", "3.14159", "1.2.3", "Introduction",
                                     "", "1234.", "-5"}) {
            assertFalse(StreamTableExtractor.isEnumerationMarker(s),
                    "\"" + s + "\" must NOT be treated as an enumeration marker");
        }
    }
}
