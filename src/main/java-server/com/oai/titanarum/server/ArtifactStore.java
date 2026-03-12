package com.oai.titanarum.server;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.UUID;

public interface ArtifactStore {
    Path resolveArtifactRoot(UUID jobId) throws IOException;
    /** Returns the configured top-level artifact root directory (absolute, normalized). */
    Path getRootPath();
    void deleteJob(UUID jobId) throws IOException;
    void zipJob(Path artifactDir, OutputStream out) throws IOException;
}
