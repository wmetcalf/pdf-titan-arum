package com.oai.titanarum.server;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Job(
    UUID id,
    String filename,
    String fileHash,
    String pdfObjectHash,
    OffsetDateTime submittedAt,
    OffsetDateTime startedAt,
    OffsetDateTime finishedAt,
    String status,
    String workerHost,
    String errorText,
    String reportJson,
    String artifactRoot,
    boolean skipScreenshots,
    boolean skipImages,
    boolean skipPhones,
    boolean skipPageExport,
    boolean skipTextUrls,
    boolean skipQr,
    boolean ocrScreenshots,
    boolean ocrUrlCrops,
    String password,
    Float dpi,
    String pagesSpec,
    boolean addLinkAnnotations,
    boolean noSkipBlanks,
    String ocrLang,
    Integer timeoutSeconds
) {}
