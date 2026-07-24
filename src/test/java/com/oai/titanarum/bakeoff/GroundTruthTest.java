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
}
