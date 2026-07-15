package com.oai.titanarum;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * computePagesToProcess must clamp a requested page RANGE to the document before materializing it.
 * A dispatcher-forwarded pages spec like "1-2147483647" would otherwise build ~2.1e9 Integer
 * entries in the loop BEFORE the per-page validity filter runs -- an out-of-memory DoS.
 */
class PagesRangeTest {

    @SuppressWarnings("unchecked")
    private static List<Integer> compute(String spec, int pageCount) throws Exception {
        PdfTitanArumApp app = new PdfTitanArumApp();
        Method m = PdfTitanArumApp.class.getDeclaredMethod(
                "computePagesToProcess", String.class, int.class);
        m.setAccessible(true);
        return (List<Integer>) m.invoke(app, spec, pageCount);
    }

    @Test
    void absurdRangeIsClampedToDocument_noOOM() throws Exception {
        // Without the clamp this materializes ~2.1 billion entries and OOMs before filtering.
        List<Integer> pages = compute("1-2147483647", 5);
        assertEquals(List.of(1, 2, 3, 4, 5), pages);
    }

    @Test
    void descendingAbsurdRangeIsAlsoBounded() throws Exception {
        // "2147483647-1" would count DOWN from 2.1e9; both endpoints must clamp to the document.
        List<Integer> pages = compute("2147483647-1", 3);
        assertEquals(List.of(3, 2, 1), pages);
    }

    @Test
    void normalRangeUnaffected() throws Exception {
        assertEquals(List.of(2, 3, 4), compute("2-4", 10));
    }
}
