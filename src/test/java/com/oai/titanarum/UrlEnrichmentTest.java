package com.oai.titanarum;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UrlEnrichmentTest {

    private String parseObfuscatedIp(String host) throws Exception {
        Method m = PdfTitanArumApp.class.getDeclaredMethod("parseObfuscatedIp", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, host);
    }

    private boolean isPrivateIp(String ip) throws Exception {
        Method m = PdfTitanArumApp.class.getDeclaredMethod("isPrivateIp", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, ip);
    }

    @Test void uint32ToIp() throws Exception {
        assertEquals("192.168.1.1", parseObfuscatedIp("3232235777"));
    }
    @Test void hexIpNoSeparator() throws Exception {
        assertEquals("192.168.1.1", parseObfuscatedIp("0xC0A80101"));
    }
    @Test void octalOctets() throws Exception {
        assertEquals("192.168.1.1", parseObfuscatedIp("0300.0250.01.01"));
    }
    @Test void mixedBaseOctets() throws Exception {
        assertEquals("192.168.1.1", parseObfuscatedIp("0xC0.0250.1.1"));
    }
    @Test void standardDottedQuadReturnsNull() throws Exception {
        assertNull(parseObfuscatedIp("192.168.1.1"));
    }
    @Test void nonIpHostReturnsNull() throws Exception {
        assertNull(parseObfuscatedIp("evil.com"));
    }
    @Test void rfc1918IsPrivate() throws Exception {
        assertTrue(isPrivateIp("192.168.1.1"));
        assertTrue(isPrivateIp("10.0.0.1"));
        assertTrue(isPrivateIp("172.16.0.1"));
    }
    @Test void loopbackIsPrivate() throws Exception {
        assertTrue(isPrivateIp("127.0.0.1"));
    }
    @Test void publicIpIsNotPrivate() throws Exception {
        assertFalse(isPrivateIp("8.8.8.8"));
    }

    private PdfTitanArumApp.UrlHit enrichHit(String url) throws Exception {
        PdfTitanArumApp.UrlHit hit = new PdfTitanArumApp.UrlHit();
        hit.url = url;
        Method m = PdfTitanArumApp.class.getDeclaredMethod("enrichUrlHit", PdfTitanArumApp.UrlHit.class);
        m.setAccessible(true);
        m.invoke(null, hit);
        return hit;
    }

    @Test void obfuscatedHostUint32() throws Exception {
        var hit = enrichHit("http://3232235777/evil");
        assertEquals("http://192.168.1.1/evil", hit.normalizedUrl);
        assertTrue(hit.flags.contains("obfuscated_host"));
        assertTrue(hit.flags.contains("private_ip"));
    }
    @Test void obfuscatedHostHex() throws Exception {
        var hit = enrichHit("http://0xC0A80101/path");
        assertTrue(hit.flags.contains("obfuscated_host"));
        assertTrue(hit.flags.contains("private_ip"));
    }
    @Test void githubIoDomainValid() throws Exception {
        var hit = enrichHit("https://foo.github.io/bar");
        assertNotNull(hit.flags);
        assertTrue(hit.flags.contains("valid_domain"));
    }
    @Test void publicDomainValidFlag() throws Exception {
        var hit = enrichHit("https://example.com/page");
        assertNotNull(hit.flags);
        assertTrue(hit.flags.contains("valid_domain"));
    }
    @Test void unknownTld() throws Exception {
        var hit = enrichHit("http://malware.invalidtld/");
        assertTrue(hit.flags.contains("unknown_tld"));
    }

    @Test void extractUrlsFromCode() throws Exception {
        Method m = PdfTitanArumApp.class.getDeclaredMethod(
            "extractUrlsFromCode", String.class, String.class, Integer.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        var hits = (List<PdfTitanArumApp.UrlHit>) m.invoke(null,
            "app.launchURL('https://evil.com/payload', true);",
            "javascript", null);
        assertEquals(1, hits.size());
        assertEquals("https://evil.com/payload", hits.get(0).url);
        assertEquals("javascript", hits.get(0).source);
    }

    @Test void rectangleUnionAreaNonOverlapping() throws Exception {
        Method m = PdfTitanArumApp.class.getDeclaredMethod("unionArea", List.class);
        m.setAccessible(true);
        var r1 = new org.apache.pdfbox.pdmodel.common.PDRectangle(0, 0, 100, 100);
        var r2 = new org.apache.pdfbox.pdmodel.common.PDRectangle(200, 0, 100, 100);
        double area = (double) m.invoke(null, List.of(r1, r2));
        assertEquals(20000.0, area, 1.0);
    }
    @Test void rectangleUnionAreaFullyOverlapping() throws Exception {
        Method m = PdfTitanArumApp.class.getDeclaredMethod("unionArea", List.class);
        m.setAccessible(true);
        var r1 = new org.apache.pdfbox.pdmodel.common.PDRectangle(0, 0, 100, 100);
        var r2 = new org.apache.pdfbox.pdmodel.common.PDRectangle(0, 0, 100, 100);
        double area = (double) m.invoke(null, List.of(r1, r2));
        assertEquals(10000.0, area, 1.0);
    }

    @Test void singleRevisionReturnsRevisionCount1() throws Exception {
        Method m = PdfTitanArumApp.class.getDeclaredMethod("buildRevisionTimeline", byte[].class);
        m.setAccessible(true);
        byte[] minPdf = ("%%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n"
            + "xref\n0 2\n0000000000 65535 f \n0000000009 00000 n \n"
            + "trailer\n<< /Size 2 /Root 1 0 R >>\nstartxref\n9\n%%EOF\n")
            .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        var timeline = (PdfTitanArumApp.RevisionTimeline) m.invoke(null, (Object) minPdf);
        assertEquals(1, timeline.revisionCount);
        assertTrue(timeline.objectTimelines.isEmpty());
    }
}
