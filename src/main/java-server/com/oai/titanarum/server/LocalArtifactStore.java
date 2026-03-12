package com.oai.titanarum.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.UUID;
import net.lingala.zip4j.io.outputstream.ZipOutputStream;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.CompressionMethod;
import net.lingala.zip4j.model.enums.EncryptionMethod;

public class LocalArtifactStore implements ArtifactStore {

    private final Path artifactRoot;

    public LocalArtifactStore(Path artifactRoot) {
        this.artifactRoot = artifactRoot.toAbsolutePath();
    }

    @Override
    public Path getRootPath() {
        return artifactRoot;
    }

    @Override
    public Path resolveArtifactRoot(UUID jobId) throws IOException {
        Path dir = artifactRoot.resolve(jobId.toString());
        Files.createDirectories(dir);
        return dir;
    }

    @Override
    public void deleteJob(UUID jobId) throws IOException {
        Path dir = artifactRoot.resolve(jobId.toString());
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                // Delete symlinks as well as regular files, but don't follow them
                Files.deleteIfExists(f);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Override
    public void zipJob(Path dir, OutputStream out) throws IOException {
        if (!Files.isDirectory(dir)) return;
        ZipParameters params = new ZipParameters();
        params.setEncryptFiles(true);
        params.setEncryptionMethod(EncryptionMethod.AES);
        params.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);
        params.setCompressionMethod(CompressionMethod.DEFLATE);
        try (ZipOutputStream zos = new ZipOutputStream(out, "infected".toCharArray())) {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                    if (Files.isSymbolicLink(f)) return FileVisitResult.CONTINUE;
                    params.setFileNameInZip(dir.relativize(f).toString());
                    zos.putNextEntry(params);
                    Files.copy(f, zos);
                    zos.closeEntry();
                    return FileVisitResult.CONTINUE;
                }
            });
        }
    }
}
