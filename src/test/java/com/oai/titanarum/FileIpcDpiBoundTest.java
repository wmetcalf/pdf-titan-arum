package com.oai.titanarum;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The render DPI a {@code job.json} may ask for.
 *
 * <p>The CLI rejects anything outside {@code 1..MAX_DPI}, but {@code callWith} — which the
 * file-IPC ({@code --run}) and pool paths use — applies no bound of its own. A submitted job
 * controls {@code TITANARUM_DPI} through the fleet's parameter allowlist, so an enormous value
 * reached PDFBox and allocated a gigapixel raster: the worker OOM'd instead of the job failing.
 */
class FileIpcDpiBoundTest {

    @Test
    void anEnormousRequestIsCappedAtTheRenderCeiling() {
        assertEquals(600.0f, PdfTitanArumApp.boundedDpi(1_000_000.0f),
                "a job must not be able to ask for a gigapixel raster");
        assertEquals(600.0f, PdfTitanArumApp.boundedDpi(601.0f));
    }

    @Test
    void aUsableRequestIsPassedThroughUnchanged() {
        assertEquals(150.0f, PdfTitanArumApp.boundedDpi(150.0f));
        assertEquals(600.0f, PdfTitanArumApp.boundedDpi(600.0f), "the ceiling itself is usable");
        assertEquals(1.0f, PdfTitanArumApp.boundedDpi(1.0f));
    }

    @Test
    void anAbsentOrUnusableValueBecomesTheDocumentedDefault() {
        // An absent `dpi` field deserialises to 0.0f, which is not a render setting.
        assertEquals(150.0f, PdfTitanArumApp.boundedDpi(0.0f));
        assertEquals(150.0f, PdfTitanArumApp.boundedDpi(-5.0f));
        assertEquals(150.0f, PdfTitanArumApp.boundedDpi(Float.NaN));
        assertEquals(150.0f, PdfTitanArumApp.boundedDpi(Float.POSITIVE_INFINITY));
        assertEquals(150.0f, PdfTitanArumApp.boundedDpi(Float.NEGATIVE_INFINITY));
    }
}
