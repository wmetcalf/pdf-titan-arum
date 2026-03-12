package com.oai.titanarum;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PhoneExtractionTest {

    @SuppressWarnings("unchecked")
    private List<PdfTitanArumApp.PhoneHit> extractFromText(
            String text, String source, Integer page, String context) throws Exception {
        Method m = PdfTitanArumApp.class.getDeclaredMethod(
            "extractPhonesFromText", String.class, String.class, Integer.class, String.class);
        m.setAccessible(true);
        return (List<PdfTitanArumApp.PhoneHit>) m.invoke(null, text, source, page, context);
    }

    @Test void usNumberWithCountryCode() throws Exception {
        var hits = extractFromText("Call us at +1 415-555-0100 today", "visible_text", 1, null);
        assertEquals(1, hits.size());
        assertEquals("+14155550100", hits.get(0).e164);
        assertEquals("US", hits.get(0).countryCode);
    }
    @Test void usNumberWithoutCountryCodeDefaultsToUS() throws Exception {
        var hits = extractFromText("Reach us: (415) 555-0100", "visible_text", 2, null);
        assertEquals(1, hits.size());
        assertEquals("US", hits.get(0).countryCode);
    }
    @Test void geocodePopulated() throws Exception {
        var hits = extractFromText("+1 212-555-0100", "visible_text", 1, null);
        assertEquals(1, hits.size());
        assertNotNull(hits.get(0).geocode);
    }
    @Test void noPhoneInText() throws Exception {
        var hits = extractFromText("No phone numbers here", "visible_text", 1, null);
        assertTrue(hits.isEmpty());
    }
    @Test void internationalNumber() throws Exception {
        var hits = extractFromText("+44 20 7123 4567", "javascript", null, "js:openAction");
        assertEquals(1, hits.size());
        assertEquals("GB", hits.get(0).countryCode);
        assertNull(hits.get(0).page);
        assertEquals("js:openAction", hits.get(0).context);
    }
}
