package com.oai.titanarum;

import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FIX 1 reproducer/regression guard: a PDF structure tree is a DAG, not a tree --
 * {@code PDStructureElement.appendKid} allows the SAME child element to be referenced as a kid
 * of more than one parent. A "diamond" chain where each level references the same previous
 * element as BOTH of its kids causes 2^N visits with only a depth guard (REPRODUCED on real
 * fixtures: depth=22 -> collectByType 4.1s, collectGlyphs 11.2s, near the 15s hard-halt -- and
 * depth is attacker-controlled up to the depth-64 cap, reaching hours).
 *
 * <p>Driven directly against {@link TableExtractor#collectByType} / {@link
 * TableExtractor#collectGlyphs} with a synthetic, in-memory structure tree (no PDF file I/O
 * needed -- {@code PDStructureElement}/{@code PDStructureTreeRoot} are plain COS-backed objects).
 * Uses the package-private {@code structureNodesVisited} test instrumentation counter (same
 * "snapshot before/after" convention as the existing {@code taggedProcessPageCalls} counter) for
 * a fully deterministic assertion -- linear in distinct nodes, not exponential in depth -- rather
 * than a timing-based one.
 */
class TableStructureTreeDagTest {

    /**
     * Builds a depth-{@code depth} diamond DAG: level 0 is a single leaf element; level i
     * (i&gt;=1) is a NEW element whose kids are BOTH the SAME level (i-1) element (appended
     * twice). Without memoization, walking this from the top (level {@code depth}) does 2^depth
     * recursive visits; with identity memoization, each of the {@code depth} distinct
     * non-leaf-parent edges is walked exactly once.
     */
    private static PDStructureElement diamondDag(int depth) {
        PDStructureTreeRoot placeholder = new PDStructureTreeRoot(); // constructor arg only; no doc needed
        PDStructureElement level = new PDStructureElement("Leaf", placeholder);
        for (int i = 1; i <= depth; i++) {
            PDStructureElement next = new PDStructureElement("Node", placeholder);
            next.appendKid(level);
            next.appendKid(level); // the fan-in: both kids are the SAME previous-level element
            level = next;
        }
        return level;
    }

    @Test
    void collectByTypeVisitsEachDiamondNodeOnceNotExponentially() {
        int depth = 30; // pre-fix this would be 2^30 (~1.07 billion) visits
        PDStructureElement root = diamondDag(depth);

        long before = TableExtractor.structureNodesVisited;
        List<PDStructureElement> out = new ArrayList<>();
        long start = System.nanoTime();
        TableExtractor.collectByType(root, "NeverMatches", out, 0, Set.of());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long visited = TableExtractor.structureNodesVisited - before;

        assertEquals(depth, visited,
                "memoized walk must visit exactly the " + depth + " distinct descendant nodes once each, got " + visited);
        assertTrue(elapsedMs < 2000,
                "a depth-" + depth + " diamond must complete near-instantly once memoized, took " + elapsedMs + "ms");
        assertTrue(out.isEmpty(), "sanity: no node in this fixture matches the search type");
    }

    @Test
    void collectGlyphsVisitsEachDiamondNodeOnceNotExponentially() throws Exception {
        int depth = 30;
        PDStructureElement root = diamondDag(depth);

        long before = TableExtractor.structureNodesVisited;
        List<TextPosition> out = new ArrayList<>();
        long start = System.nanoTime();
        TableExtractor.collectGlyphs(root, null, out, new HashMap<>(), 0, new HashMap<>(), Set.of());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        long visited = TableExtractor.structureNodesVisited - before;

        assertEquals(depth, visited,
                "memoized walk must visit exactly the " + depth + " distinct descendant nodes once each, got " + visited);
        assertTrue(elapsedMs < 2000,
                "a depth-" + depth + " diamond must complete near-instantly once memoized, took " + elapsedMs + "ms");
        assertTrue(out.isEmpty(), "sanity: this fixture carries no MCID/glyph leaves");
    }

    @Test
    void collectByTypeStillFindsMatchesThroughADiamond() {
        // Regression guard for the memoization fix itself: a match reachable ONLY via the
        // (shared, doubly-referenced) leaf must still be found -- memoization must skip
        // RE-VISITING an already-seen node, not silently drop legitimate results.
        PDStructureTreeRoot placeholder = new PDStructureTreeRoot();
        PDStructureElement leaf = new PDStructureElement("TR", placeholder);
        PDStructureElement mid = new PDStructureElement("Node", placeholder);
        mid.appendKid(leaf);
        mid.appendKid(leaf);
        PDStructureElement root = new PDStructureElement("Node", placeholder);
        root.appendKid(mid);
        root.appendKid(mid);

        List<PDStructureElement> out = new ArrayList<>();
        TableExtractor.collectByType(root, "TR", out, 0, Set.of());
        assertEquals(1, out.size(), "the shared TR element must be found exactly once, not zero or twice");
    }
}
