package com.oai.titanarum.bakeoff;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Proves the ground-truth loading + scoring apparatus is trustworthy before
 * any extraction algorithm is judged against it.
 */
class GroundTruthTest {

    private static final Path CORPUS_ROOT =
            Path.of("corpus/tabula-java/src/test/resources/technology/tabula");
    private static final Path CSV_DIR = CORPUS_ROOT.resolve("csv");
    private static final Path ICDAR_DIR = CORPUS_ROOT.resolve("icdar2013-dataset");

    // --------------------------------------------------------------- CSV --

    @Test
    void animalSoundsCsvLoadsThreeColumnsWithWrappedCell() throws IOException {
        Path csv = CSV_DIR.resolve("AnimalSounds.csv");
        assumeTrue(Files.exists(csv), "corpus fixture missing: " + csv);

        GroundTruth.Table table = GroundTruth.fromCsv(csv);

        assertEquals(3, table.colCount(), "AnimalSounds.csv should have 3 columns");

        boolean foundWrappedWord = table.rows().stream()
                .flatMap(List::stream)
                .map(GroundTruth::normalizeCell)
                .anyMatch(s -> s.contains("parastratiosphecomyiastratiosphecomyioides"));
        assertTrue(foundWrappedWord,
                "expected a cell containing the word wrapped across physical lines "
                        + "inside a quoted CSV field (proves embedded-newline handling)");
    }

    @Test
    void multiColumnCsvLoadsNumericGrid() throws IOException {
        Path csv = CSV_DIR.resolve("MultiColumn.csv");
        assumeTrue(Files.exists(csv), "corpus fixture missing: " + csv);

        GroundTruth.Table table = GroundTruth.fromCsv(csv);

        assertEquals(3, table.colCount());
        assertTrue(table.rowCount() >= 1);
        List<String> firstRow = table.rows().get(0);
        assertEquals("1", GroundTruth.normalizeCell(firstRow.get(0)));
        assertEquals("100", GroundTruth.normalizeCell(firstRow.get(1)));
        assertEquals("200", GroundTruth.normalizeCell(firstRow.get(2)));
    }

    // ------------------------------------------------------------- ICDAR --

    @Test
    void icdarStructureXmlLoadsCells() throws IOException {
        Path strXml = ICDAR_DIR.resolve("competition-dataset-eu/eu-002-str.xml");
        assumeTrue(Files.exists(strXml), "corpus fixture missing: " + strXml);

        List<GroundTruth.Table> tables = GroundTruth.fromIcdarStructureXml(strXml);

        assertFalse(tables.isEmpty(), "expected at least one table in " + strXml);
        GroundTruth.Table first = tables.get(0);
        assertTrue(first.rowCount() >= 2, "expected >= 2 rows, got " + first.rowCount());
        assertTrue(first.colCount() >= 2, "expected >= 2 cols, got " + first.colCount());

        boolean hasTopLeftishContent = false;
        for (int r = 0; r < Math.min(2, first.rowCount()); r++) {
            for (int c = 0; c < Math.min(2, first.rows().get(r).size()); c++) {
                if (!GroundTruth.normalizeCell(first.rows().get(r).get(c)).isEmpty()) {
                    hasTopLeftishContent = true;
                }
            }
        }
        assertTrue(hasTopLeftishContent, "expected non-empty content near the top-left of the table");
    }

    @Test
    void icdarMultiRegionTableAppliesRowColIncrements() throws IOException {
        // us-035a-str.xml's table id=2 has three <region> blocks on the same
        // page with col-increment 0/2/4, meant to be merged side-by-side into
        // one wide table rather than kept as three separate 2-column tables.
        Path strXml = ICDAR_DIR.resolve("competition-dataset-us/us-035a-str.xml");
        assumeTrue(Files.exists(strXml), "corpus fixture missing: " + strXml);

        List<GroundTruth.Table> tables = GroundTruth.fromIcdarStructureXml(strXml);
        assertTrue(tables.size() >= 2, "expected multiple <table> elements in " + strXml);

        GroundTruth.Table merged = tables.get(1); // table id=2 (0-indexed second table)
        assertTrue(merged.colCount() >= 5,
                "expected regions merged side-by-side (col-increment 0,2,4) to produce >= 5 cols, got "
                        + merged.colCount());
    }

    // -------------------------------------------------------------- score --

    @Test
    void scorePerfectMatchIsF1One() {
        GroundTruth.Table expected = new GroundTruth.Table(List.of(
                List.of("A", "B"),
                List.of("C", "D")));
        List<List<String>> actual = List.of(
                List.of("A", "B"),
                List.of("C", "D"));

        TableScore.Result result = TableScore.score(expected, actual);

        assertEquals(4, result.truePositives());
        assertEquals(0, result.falsePositives());
        assertEquals(0, result.falseNegatives());
        assertEquals(1.0, result.precision(), 1e-9);
        assertEquals(1.0, result.recall(), 1e-9);
        assertEquals(1.0, result.f1(), 1e-9);
        assertTrue(result.dimsExactMatch());
    }

    @Test
    void scoreDisjointIsF1Zero() {
        GroundTruth.Table expected = new GroundTruth.Table(List.of(
                List.of("A", "B"),
                List.of("C", "D")));
        List<List<String>> actual = List.of(
                List.of("X", "Y"),
                List.of("Z", "W"));

        TableScore.Result result = TableScore.score(expected, actual);

        assertEquals(0, result.truePositives());
        assertEquals(4, result.falsePositives());
        assertEquals(4, result.falseNegatives());
        assertEquals(0.0, result.precision(), 1e-9);
        assertEquals(0.0, result.recall(), 1e-9);
        assertEquals(0.0, result.f1(), 1e-9);
    }

    @Test
    void scoreIgnoresEmptyInBoth() {
        // Sparse 3x3 grid: only the diagonal has content, both expected and
        // actual. If empty-vs-empty cells counted as true positives this
        // would misleadingly report near-perfect precision/recall even
        // though only 1 of 3 diagonal values actually agrees.
        GroundTruth.Table expected = new GroundTruth.Table(List.of(
                List.of("A", "", ""),
                List.of("", "B", ""),
                List.of("", "", "C")));
        List<List<String>> actual = new ArrayList<>();
        actual.add(new ArrayList<>(List.of("A", "", "")));
        actual.add(new ArrayList<>(List.of("", "", "")));   // missing B
        actual.add(new ArrayList<>(List.of("", "", "Z")));  // wrong text instead of C

        TableScore.Result result = TableScore.score(expected, actual);

        // Only (0,0)="A" agrees.
        assertEquals(1, result.truePositives());
        assertEquals(1, result.falsePositives());  // (2,2)="Z" is spurious
        assertEquals(2, result.falseNegatives());  // "B" and "C" both missed
        assertEquals(0.5, result.precision(), 1e-9);
        assertEquals(1.0 / 3.0, result.recall(), 1e-9);
        assertTrue(result.f1() > 0.0 && result.f1() < 1.0);
    }

    // ---------------------------------------------------- normalizeCell --

    @Test
    void normalizeCellIsWhitespaceInsensitive() {
        String base = GroundTruth.normalizeCell("abcdef");
        assertEquals(base, GroundTruth.normalizeCell("abc def"));
        assertEquals(base, GroundTruth.normalizeCell("abc\ndef"));
        assertEquals(base, GroundTruth.normalizeCell(" ABC  DEF "));
    }

    @Test
    void whitespaceOnlyCellCountsAsEmpty() {
        // A cell that is whitespace-only on one side and truly empty on the
        // other must normalize to empty on both sides and be ignored
        // entirely -- not treated as a false positive/negative.
        GroundTruth.Table expected = new GroundTruth.Table(List.of(
                List.of("A", "   ")));
        List<List<String>> actual = List.of(
                List.of("A", ""));

        TableScore.Result result = TableScore.score(expected, actual);

        assertEquals(1, result.truePositives());
        assertEquals(0, result.falsePositives());
        assertEquals(0, result.falseNegatives());
        assertEquals(1.0, result.f1(), 1e-9);
    }

    @Test
    void scoreMatchesAcrossWrapJoinDifference() {
        // PDF line-wrapping can legitimately produce either "Hello World" (a
        // wrapped word boundary) or "HelloWorld" (a mid-token wrap) from the
        // same underlying content, and which shape is "correct" is
        // ambiguous from the data alone. Scoring must not penalize an
        // extractor for picking one shape when ground truth encodes the
        // other -- this is exactly the bias being fixed.
        GroundTruth.Table expectedSpaced = new GroundTruth.Table(List.of(
                List.of("Hello World")));
        List<List<String>> actualJoined = List.of(
                List.of("HelloWorld"));

        TableScore.Result spacedVsJoined = TableScore.score(expectedSpaced, actualJoined);
        assertEquals(1, spacedVsJoined.truePositives(), "expected/actual differing only by a wrap-join space must match");
        assertEquals(0, spacedVsJoined.falsePositives());
        assertEquals(0, spacedVsJoined.falseNegatives());

        GroundTruth.Table expectedJoined = new GroundTruth.Table(List.of(
                List.of("HelloWorld")));
        List<List<String>> actualSpaced = List.of(
                List.of("Hello World"));

        TableScore.Result joinedVsSpaced = TableScore.score(expectedJoined, actualSpaced);
        assertEquals(1, joinedVsSpaced.truePositives(), "reverse direction must also match");
        assertEquals(0, joinedVsSpaced.falsePositives());
        assertEquals(0, joinedVsSpaced.falseNegatives());
    }

    // ------------------------------------ negative start-row + region row-increment --

    /**
     * A cell may declare a NEGATIVE {@code start-row}/{@code start-col}; the enclosing
     * {@code <region>}'s {@code row-increment}/{@code col-increment} rebases it. The official ICDAR
     * 2013 evaluator adds the increment BEFORE placing the cell (dataset-tools
     * {@code Table.addCell}: {@code startRow += rowIncrement; ... setCell(c, r, textObj)}), so such a
     * cell is an ordinary cell, not a malformed one.
     *
     * <p>This is not hypothetical: {@code us-019-str.xml}'s first table is a
     * {@code <region row-increment='1'>} whose header row is declared at {@code start-row='-1'}.
     * Rejecting the raw coordinate silently dropped that header row and 3 ground-truth relations with
     * it -- the whole residual disagreement between this loader, an independent Python port of the
     * official algorithm and the official tool's own printed output.
     */
    @Test
    void negativeStartRowIsRebasedByTheRegionRowIncrementNotDiscarded() throws IOException {
        Path tmp = Files.createTempFile("gt-rowinc", "-str.xml");
        try {
            Files.writeString(tmp, """
                    <document filename='x.pdf'>
                      <table id='1'>
                        <region id='1' col-increment='0' row-increment='1' page='2'>
                          <cell id='1' start-row='-1' start-col='0'>
                            <bounding-box x1='40' y1='729' x2='72' y2='739'/>
                            <content>Variable</content>
                          </cell>
                          <cell id='2' start-row='-1' start-col='1'>
                            <bounding-box x1='517' y1='729' x2='566' y2='739'/>
                            <content>Assumption</content>
                          </cell>
                          <cell id='3' start-row='0' start-col='0'>
                            <bounding-box x1='40' y1='714' x2='156' y2='724'/>
                            <content>Fertility</content>
                          </cell>
                          <cell id='4' start-row='0' start-col='1'>
                            <bounding-box x1='318' y1='714' x2='563' y2='724'/>
                            <content>2.0</content>
                          </cell>
                        </region>
                      </table>
                    </document>
                    """);

            List<GroundTruth.Table> tables = GroundTruth.fromIcdarStructureXml(tmp);
            assertEquals(1, tables.size());
            GroundTruth.Table t = tables.get(0);

            assertEquals(4, t.cells().size(),
                    "all four cells must survive: start-row='-1' + row-increment='1' = row 0");
            assertEquals(2, t.rowCount(), "the rebased grid is 2 rows, not 1");
            assertEquals(2, t.colCount());
            assertTrue(t.cells().stream().anyMatch(c -> "Variable".equals(c.text())),
                    "the header cell declared at start-row='-1' must be present");
            assertTrue(t.cells().stream().allMatch(c -> c.startRow() >= 0 && c.startCol() >= 0),
                    "and every surviving cell must sit at a non-negative rebased coordinate");

            // 2x2 fully populated -> 2 RIGHT + 2 DOWN = 4 relations. Dropping the header row would
            // leave a 1x2 grid and only 1 relation, which is exactly the loss this test guards.
            assertEquals(4, TableScore.officialRelationCount(
                            TableScore.gridCellsFromGroundTruth(t), false,
                            TableScore.Semantics.MULTISET),
                    "the rebased header row contributes its RIGHT relation and both DOWN relations");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    /** A cell with NO start-row attribute at all is still discarded -- absent != negative. */
    @Test
    void cellWithMissingStartRowAttributeIsStillDiscarded() throws IOException {
        Path tmp = Files.createTempFile("gt-noattr", "-str.xml");
        try {
            Files.writeString(tmp, """
                    <document filename='x.pdf'>
                      <table id='1'>
                        <region id='1' col-increment='0' row-increment='0' page='1'>
                          <cell id='1' start-col='0'>
                            <content>NoRowAttribute</content>
                          </cell>
                          <cell id='2' start-row='0' start-col='1'>
                            <content>Keep</content>
                          </cell>
                        </region>
                      </table>
                    </document>
                    """);

            List<GroundTruth.Table> tables = GroundTruth.fromIcdarStructureXml(tmp);
            assertEquals(1, tables.size());
            List<GroundTruth.Cell> cells = tables.get(0).cells();
            assertEquals(1, cells.size(),
                    "the cell with no start-row is unusable and must still be skipped");
            assertEquals("Keep", cells.get(0).text());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
