package com.oai.titanarum;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class PathSafetyTest {

    private String sanitize(String input) throws Exception {
        Method m = PdfTitanArumApp.class.getDeclaredMethod("sanitizeFileName", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, input);
    }

    private Path safeResolve(Path dir, String name) throws Exception {
        Method m = PdfTitanArumApp.class.getDeclaredMethod("safeResolve", Path.class, String.class);
        m.setAccessible(true);
        try {
            return (Path) m.invoke(null, dir, name);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void sanitize_dotdotSlash_returnsFilenameOnly() throws Exception {
        assertEquals("passwd", sanitize("../../etc/passwd"));
    }

    @Test
    void sanitize_dotdot_fallsBackToArtifact() throws Exception {
        assertEquals("artifact", sanitize(".."));
    }

    @Test
    void sanitize_dot_fallsBackToArtifact() throws Exception {
        assertEquals("artifact", sanitize("."));
    }

    @Test
    void sanitize_blank_fallsBackToArtifact() throws Exception {
        assertEquals("artifact", sanitize("   "));
    }

    @Test
    void sanitize_empty_fallsBackToArtifact() throws Exception {
        assertEquals("artifact", sanitize(""));
    }

    @Test
    void sanitize_normalFilename_preserved() throws Exception {
        assertEquals("report.pdf", sanitize("report.pdf"));
    }

    @Test
    void sanitize_slashInMiddle_takesLastComponent() throws Exception {
        assertEquals("c", sanitize("a/b/c"));
    }

    @Test
    void safeResolve_cleanName_succeeds(@TempDir Path dir) throws Exception {
        Path result = safeResolve(dir.toAbsolutePath(), "file.txt");
        assertTrue(result.startsWith(dir.toAbsolutePath()));
    }

    @Test
    void safeResolve_traversalName_throwsIOException(@TempDir Path dir) {
        assertThrows(IOException.class, () -> safeResolve(dir.toAbsolutePath(), "../escape.txt"));
    }

    @Test
    void safeResolve_relativeDir_throwsIllegalArgument(@TempDir Path dir) {
        Path rel = Path.of("relative");
        assertThrows(IllegalArgumentException.class, () -> safeResolve(rel, "file.txt"));
    }
}
