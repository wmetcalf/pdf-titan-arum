package com.oai.titanarum;

import com.oai.titanarum.server.LocalArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import static org.junit.jupiter.api.Assertions.*;

class LocalArtifactStoreTest {

    @Test
    void resolveCreatesDirectory(@TempDir Path tmp) throws Exception {
        var store = new LocalArtifactStore(tmp);
        UUID id = UUID.randomUUID();
        Path root = store.resolveArtifactRoot(id);
        assertTrue(Files.isDirectory(root));
        assertEquals(tmp.resolve(id.toString()), root);
    }

    @Test
    void deleteRemovesDirectory(@TempDir Path tmp) throws Exception {
        var store = new LocalArtifactStore(tmp);
        UUID id = UUID.randomUUID();
        Path root = store.resolveArtifactRoot(id);
        Files.writeString(root.resolve("report.json"), "{}");
        store.deleteJob(id);
        assertFalse(Files.exists(root));
    }

    @Test
    void zipJobContainsFiles(@TempDir Path tmp) throws Exception {
        var store = new LocalArtifactStore(tmp);
        UUID id = UUID.randomUUID();
        Path root = store.resolveArtifactRoot(id);
        Files.writeString(root.resolve("report.json"), "{\"test\":1}");
        Path sub = root.resolve("screenshots");
        Files.createDirectories(sub);
        Files.writeString(sub.resolve("page-0001.png"), "fakepng");

        var baos = new ByteArrayOutputStream();
        store.zipJob(root, baos);
        assertTrue(baos.size() > 0);

        var entries = new ArrayList<String>();
        try (var zis = new ZipInputStream(new java.io.ByteArrayInputStream(baos.toByteArray()))) {
            java.util.zip.ZipEntry e;
            while ((e = zis.getNextEntry()) != null) entries.add(e.getName());
        }
        assertTrue(entries.contains("report.json"));
        assertTrue(entries.stream().anyMatch(n -> n.contains("page-0001.png")));
    }
}
