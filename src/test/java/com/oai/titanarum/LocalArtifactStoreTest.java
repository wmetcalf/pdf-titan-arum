package com.oai.titanarum;

import com.oai.titanarum.server.LocalArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.UUID;
import net.lingala.zip4j.io.inputstream.ZipInputStream;
import net.lingala.zip4j.model.LocalFileHeader;
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

        // zipJob writes AES-encrypted entries (password "infected", the standard
        // convention for shipping malware artifacts). java.util.zip cannot read those --
        // it throws "encrypted ZIP entry not supported" -- so read it the way a consumer
        // actually does, with zip4j and the password. Reading the CONTENT back, not just
        // the entry names, is what proves the archive is decryptable rather than merely
        // well-formed.
        var entries = new ArrayList<String>();
        String reportBody = null;
        try (var zis = new ZipInputStream(
                new java.io.ByteArrayInputStream(baos.toByteArray()), "infected".toCharArray())) {
            LocalFileHeader e;
            while ((e = zis.getNextEntry()) != null) {
                entries.add(e.getFileName());
                if (e.getFileName().equals("report.json")) {
                    reportBody = new String(zis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            }
        }
        assertTrue(entries.contains("report.json"));
        assertTrue(entries.stream().anyMatch(n -> n.contains("page-0001.png")));
        assertEquals("{\"test\":1}", reportBody,
                "the encrypted entry must decrypt back to exactly what was written");
    }
}
