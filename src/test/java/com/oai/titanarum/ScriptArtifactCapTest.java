package com.oai.titanarum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * blastbox-engine.toml declares max_bytes for titan/scripts/* and titan/xfa/*. A JavaScript COSString (not a
 * stream) and a base64 form payload reached writeTextArtifact uncapped, and re-encoding invalid UTF-8 as U+FFFD
 * can grow a capped stream's bytes up to 3x, so the emitted file could exceed the declared cap. The writer
 * itself must bound the bytes it puts on disk.
 */
class ScriptArtifactCapTest {

    private static byte[] capUtf8(String s, int max) throws Exception {
        Method m = PdfTitanArumApp.class.getDeclaredMethod("capUtf8", String.class, int.class);
        m.setAccessible(true);
        return (byte[]) m.invoke(null, s, max);
    }

    @Test
    void underCapIsUnchanged() throws Exception {
        assertArrayEquals("app.alert(1)".getBytes(StandardCharsets.UTF_8), capUtf8("app.alert(1)", 64));
    }

    @Test
    void overCapIsTruncatedToTheCap() throws Exception {
        assertEquals(10, capUtf8("x".repeat(100), 10).length);
    }

    @Test
    void replacementCharExpansionStaysUnderCap() throws Exception {
        // 10 invalid bytes decode to 10 U+FFFD = 30 UTF-8 bytes
        String s = new String(new byte[]{(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff}, StandardCharsets.UTF_8);
        byte[] out = capUtf8(s, 10);
        assertTrue(out.length <= 10, "got " + out.length);
    }

    @Test
    void neverSplitsACodePoint() throws Exception {
        String s = "ab" + "😀".repeat(4);   // 2 + 4*4 bytes
        byte[] out = capUtf8(s, 7);                   // 2 + one emoji = 6 fits, 7 would split the second
        assertEquals(6, out.length);
        assertEquals("ab😀", new String(out, StandardCharsets.UTF_8));
    }

    @Test
    void writerBoundsTheFileOnDisk(@TempDir Path dir) throws Exception {
        PdfTitanArumApp app = new PdfTitanArumApp();
        java.lang.reflect.Field f = PdfTitanArumApp.class.getDeclaredField("outputDir");
        f.setAccessible(true);
        f.set(app, dir);
        Method m = PdfTitanArumApp.class.getDeclaredMethod("writeCappedTextArtifact",
                Path.class, String.class, String.class, int.class);
        m.setAccessible(true);
        m.invoke(app, dir, "big.js.txt", "y".repeat(5000), 1024);
        Path out = dir.resolve("big.js.txt");
        assertTrue(Files.isRegularFile(out));
        assertEquals(1024, Files.size(out));
    }

    @Test
    void longNamesKeepTheirExtensionWhenTruncated() throws Exception {
        // a cut at 180 that dropped '.png' let a hostile XObject name end the decode's file name in
        // '-original.jpg', moving an uncapped PNG into the original-codestream family
        Method m = PdfTitanArumApp.class.getDeclaredMethod("sanitizeFileName", String.class);
        m.setAccessible(true);
        String name = "resource-page-0001--original" + "A".repeat(139) + "-original.jpgZZZZ.png";
        String out = (String) m.invoke(null, name);
        assertTrue(out.length() <= 180, out);
        assertTrue(out.endsWith(".png"), out);
        assertEquals("short.png", m.invoke(null, "short.png"));
    }

    @Test
    void truncationNeverSplitsASurrogatePairAndLoneSurrogatesAreReplaced(@TempDir Path dir) throws Exception {
        // a cut through an emoji left a lone high surrogate: Path.resolve threw InvalidPathException,
        // which escaped callWith (no report.json, engine_error)
        Method m = PdfTitanArumApp.class.getDeclaredMethod("sanitizeFileName", String.class);
        m.setAccessible(true);
        for (int a = 170; a <= 182; a++) {
            String name = "A".repeat(a) + "\uD83D\uDE00" + "B".repeat(20) + ".js.txt";
            String out = (String) m.invoke(null, name);
            for (int i = 0; i < out.length(); i++) {
                char c = out.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    assertTrue(i + 1 < out.length() && Character.isLowSurrogate(out.charAt(i + 1)), out);
                    i++;
                } else {
                    assertFalse(Character.isLowSurrogate(c), out);
                }
            }
            dir.resolve(out);   // must not throw
        }
        String lone = (String) m.invoke(null, "x\uD800y.js");
        dir.resolve(lone);
    }

    @Test
    void multibyteNamesFitNameMaxInBytes(@TempDir Path dir) throws Exception {
        // NAME_MAX is 255 BYTES: 75+ CJK characters fit a 180-char cap but not the filesystem, and
        // the FileSystemException escaped callWith (no report.json, engine_error)
        Method m = PdfTitanArumApp.class.getDeclaredMethod("sanitizeFileName", String.class);
        m.setAccessible(true);
        for (String name : new String[]{"\u6F22".repeat(75) + ".js.txt", "\u6F22".repeat(180) + ".pdf",
                "\uD83D\uDE00".repeat(90) + ".png", "a".repeat(300)}) {
            String out = (String) m.invoke(null, name);
            assertTrue(out.getBytes(StandardCharsets.UTF_8).length <= 230, out);
            Files.createFile(dir.resolve(out));
            Files.createFile(dir.resolve(out.replaceFirst("(\\.[^.]*)?$", "-9999$1")));   // uniquePath's suffix fits too
        }
        String ext = (String) m.invoke(null, "\u6F22".repeat(180) + ".pdf");
        assertTrue(ext.endsWith(".pdf"), ext);
    }
}
