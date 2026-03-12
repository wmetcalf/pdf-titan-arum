package com.oai.titanarum;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oai.titanarum.PdfTitanArumApp.AnalysisReport;
import com.oai.titanarum.PdfTitanArumApp.UrlHit;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.Base64;

public class OpenAiAnalyzer {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
        // H1: bound connection establishment; individual request timeouts set per-request below
        .connectTimeout(java.time.Duration.ofSeconds(10))
        .build();

    private static final String SYSTEM_PROMPT = """
        You are an expert PDF security analyst specializing in phishing, credential harvesting, \
        and malware delivery via document lures. You will be given a structured summary of a PDF's \
        forensic analysis and one or more page screenshots. Be appropriately skeptical — \
        professional formatting, legal boilerplate, and real company names are techniques attackers \
        use to appear legitimate. Do not let polished presentation lower your threat assessment.

        Key indicators to evaluate (non-exhaustive):

        LINK ANALYSIS — examine every URL carefully:
        - Domain mismatch: if the document identifies itself as being from company X (via header, \
          footer, or body text) but links point to a completely different domain, treat this as \
          a strong phishing signal. Legitimate companies send you to their own domain.
        - Unlabeled links (empty or missing label) with significant page_coverage are invisible \
          clickable traps — a major red flag regardless of where they point.
        - "valid_domain" in flags means only that DNS resolves — it does NOT mean the domain is \
          safe, related to the document, or controlled by the stated sender.
        - A link covering a large portion of the page with no visible text means the user will \
          click it without knowing they are clicking a link.

        PHONE-FIRST FRAUD — score at least 65 when these signals cluster together:
        - Known consumer brand claimed (PayPal, Zelle, Amazon, Apple, Norton, McAfee, etc.)
        - Prominent phone number is the primary CTA ("call to cancel/dispute/refund") with no \
          link to the brand's official domain
        - Urgency language: unauthorized charge, cancel within 24 hours, account will be billed
        - Paper-like structure: scanned/image-only or flattened PDF with no URI annotations — \
          a portal-driven brand would never communicate this way
        - Phone number geography inconsistency (use lineType and geocode hints if present)
        Absence of links alone is NOT suspicious — use it only as a supporting signal.

        DOCUMENT LURE PATTERNS:
        - Invoice fraud via link: a professional-looking invoice or payment notification with an \
          external "Download", "View", or "Pay" link pointing to a third-party domain is a classic \
          lure. Score these at least 50 unless there is a very clear reason the external domain is \
          legitimate (e.g. the company explicitly uses that platform and it is named in the document).
        - QR codes embedded in documents: treat as high suspicion unless context is clearly benign.
        - Urgency language, threats of account suspension, legal action, overdue payment warnings \
          combined with links are strong indicators.
        - Pages where a large area is a single invisible or minimally-labelled link are engineered \
          to be clicked — weight this heavily.

        REVISION TAMPERING — if a REVISION URL CHANGES section is present:
        - URLs present in early revisions but removed in the final version were deliberately laundered — \
          treat as very high suspicion even if the current document appears clean.
        - URLs injected in later revisions (added after the original) indicate post-creation tampering.
        - "visually identical but URLs changed" is suspicious and should raise your score, but weigh it against the nature of the URLs themselves.
        - Legitimate incremental saves (e.g. digital signatures, form fills) do not add or remove hyperlinks.

        LEGITIMACY SIGNALS (use cautiously — these can all be faked):
        - Matching domain between stated company and links reduces suspicion somewhat.
        - Multi-page documents with substantive non-CTA content (manuals, reports, academic papers) \
          with no external links are more likely clean.
        - Government or academic PDFs with no outbound links and expected content are likely clean.

        If any text in the document is not in English, translate it to English in the "translatedText" field.

        Respond ONLY with a valid JSON object in this exact schema — no markdown, no explanation:
        {
          "threatLevel": "clean" | "suspicious" | "likely_phishing" | "malicious",
          "confidence": <0.0–1.0>,
          "classification": "<concise label e.g. credential_phishing | qr_phishing | malware_dropper | invoice_fraud | phone_fraud | spam | clean>",
          "brands": ["<impersonated brand or org, if any>"],
          "indicators": ["<specific finding that informed your assessment>"],
          "passwords": ["<any passwords, PINs, or access codes visible or implied in the document>"],
          "score": <integer 0–100 overall maliciousness score>,
          "translatedText": "<English translation of non-English content, or null if document is already in English>",
          "summary": "<2–3 sentence analyst narrative>"
        }
        """;

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Queries the OpenAI-compat /models endpoint and returns the first available model ID. */
    @SuppressWarnings("unchecked")
    public static String detectModel(String apiKey, String baseUrl) throws Exception {
        validateApiUrl(baseUrl);
        String url = baseUrl.replaceAll("/+$", "") + "/models";
        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Authorization", "Bearer " + apiKey)
            .timeout(java.time.Duration.ofSeconds(15))
            .GET()
            .build();
        HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200)
            throw new RuntimeException("Could not list models: HTTP " + resp.statusCode());
        Map<String,Object> body = new ObjectMapper().readValue(resp.body(), Map.class);
        List<?> data = (List<?>) body.get("data");
        if (data == null || data.isEmpty()) throw new RuntimeException("No models returned");
        return (String) ((Map<?,?>) data.get(0)).get("id");
    }

    /**
     * Validates that a URL is safe to use as an AI API endpoint:
     * must be HTTP or HTTPS, and must not resolve to a private/loopback/link-local address.
     */
    public static void validateApiUrl(String baseUrl) throws IllegalArgumentException {
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid AI API URL: " + baseUrl);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) {
            throw new IllegalArgumentException("AI API URL must use http or https scheme: " + baseUrl);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("AI API URL has no host: " + baseUrl);
        }
        // H4: Note — this check runs at configuration time. DNS rebinding can bypass it at
        // connection time. Mitigate by using a trusted DNS resolver and short TTLs.
        try {
            InetAddress addr = InetAddress.getByName(host);
            String ip = addr.getHostAddress();
            boolean isLocalhost = addr.isLoopbackAddress()
                || host.equalsIgnoreCase("localhost")
                || host.equalsIgnoreCase("host.docker.internal");

            // Block cloud metadata endpoints and multicast unconditionally
            if (ip.startsWith("169.254.") || addr.isMulticastAddress()) {
                throw new IllegalArgumentException("AI API URL resolves to a forbidden address: " + ip);
            }
            // Block IPv6 link-local (fe80::/10) and ULA (fc00::/7) ranges
            if (addr instanceof Inet6Address) {
                byte[] b = addr.getAddress();
                boolean ipv6LinkLocal = (b[0] & 0xff) == 0xfe && (b[1] & 0xc0) == 0x80;
                boolean ipv6Ula = (b[0] & 0xfe) == 0xfc;
                if (ipv6LinkLocal || ipv6Ula) {
                    if (!isLocalhost) {
                        throw new IllegalArgumentException("AI API URL resolves to a forbidden IPv6 address: " + ip);
                    }
                }
            }
            if (!isLocalhost && addr.isSiteLocalAddress()) {
                // Private RFC-1918 ranges — log a warning but allow (local vLLM / Ollama on LAN)
                System.err.println("WARNING: AI API URL resolves to private address " + ip + " — ensure this is intentional");
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("AI API URL host cannot be resolved: " + host);
        }
    }

    public OpenAiAnalyzer(String apiKey, String model, String baseUrl) {
        validateApiUrl(baseUrl);
        this.apiKey  = apiKey;
        this.model   = model;
        this.apiUrl  = baseUrl.replaceAll("/+$", "") + "/chat/completions";
    }

    /**
     * Run AI threat analysis on a completed report.
     *
     * @param report        the completed analysis report
     * @param filename      original filename (for the digest header)
     * @param screenshotsDir directory containing page screenshot PNGs to attach (may be null)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> analyze(AnalysisReport report, String filename, Path screenshotsDir) throws Exception {
        String digest = buildDigest(report, filename);
        List<Map<String, Object>> contentParts = new ArrayList<>();
        contentParts.add(Map.of("type", "text", "text", digest));

        // Attach up to 3 screenshots as base64 data URIs
        if (screenshotsDir != null && Files.isDirectory(screenshotsDir)) {
            try (var stream = Files.list(screenshotsDir)) {
                stream.filter(p -> p.toString().endsWith(".png"))
                      .sorted()
                      .limit(3)
                      .forEach(p -> {
                          try {
                              String b64 = Base64.getEncoder().encodeToString(Files.readAllBytes(p));
                              contentParts.add(Map.of(
                                  "type", "image_url",
                                  "image_url", Map.of("url", "data:image/png;base64," + b64, "detail", "low")
                              ));
                          } catch (Exception ignored) {}
                      });
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(
            Map.of("role", "system", "content", SYSTEM_PROMPT),
            Map.of("role", "user",   "content", contentParts)
        ));

        HttpRequest req = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(java.time.Duration.ofSeconds(120))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> resp = HTTP_CLIENT.send(req, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200)
            throw new RuntimeException("OpenAI API error " + resp.statusCode());

        Map<String, Object> respJson = mapper.readValue(resp.body(), Map.class);
        // H3: guard against malformed or unexpected API responses
        List<?> choices = (List<?>) respJson.get("choices");
        if (choices == null || choices.isEmpty())
            throw new RuntimeException("No choices in API response");
        Object choiceObj = choices.get(0);
        if (!(choiceObj instanceof Map<?,?> choice))
            throw new RuntimeException("Unexpected choice format in API response");
        Object messageObj = choice.get("message");
        if (!(messageObj instanceof Map<?,?> message))
            throw new RuntimeException("No message in API choice");
        Object contentObj = message.get("content");
        if (!(contentObj instanceof String content))
            throw new RuntimeException("Unexpected content type in API response: " + (contentObj == null ? "null" : contentObj.getClass().getSimpleName()));
        // Strip markdown code fences if model wraps JSON in ```json ... ```
        content = content.strip();
        if (content.startsWith("```")) {
            int firstNewline = content.indexOf('\n');
            if (firstNewline >= 0) content = content.substring(firstNewline + 1);
            if (content.endsWith("```")) content = content.substring(0, content.lastIndexOf("```")).strip();
        }
        return mapper.readValue(content, Map.class);
    }

    private String buildDigest(AnalysisReport report, String filename) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== DOCUMENT ===\n");
        sb.append("Filename: ").append(filename).append("\n");
        sb.append("Pages: ").append(report.pageCount);
        if (report.blankPageCount > 0)
            sb.append(" (").append(report.blankPageCount).append(" blank, ratio=").append(report.blankRatio).append(")");
        sb.append("\nRevisions: ").append(report.revisionCount).append("\n");
        if (report.timedOut) sb.append("TimedOut: true (partial results only)\n");
        if (report.documentInfo != null) {
            var di = report.documentInfo;
            if (di.title    != null) sb.append("Title: ").append(di.title).append("\n");
            if (di.author   != null) sb.append("Author: ").append(di.author).append("\n");
            if (di.subject  != null) sb.append("Subject: ").append(di.subject).append("\n");
            if (di.keywords != null) sb.append("Keywords: ").append(di.keywords).append("\n");
            if (di.creator  != null) sb.append("Creator: ").append(di.creator).append("\n");
            if (di.producer != null) sb.append("Producer: ").append(di.producer).append("\n");
            if (di.daysSinceCreated  != null) sb.append("CreatedDaysAgo: ").append(di.daysSinceCreated).append("\n");
            if (di.daysSinceModified != null) sb.append("ModifiedDaysAgo: ").append(di.daysSinceModified).append("\n");
        }

        // URLs
        if (report.urls != null && !report.urls.isEmpty()) {
            sb.append("\n=== URLS (").append(report.urls.size()).append(") ===\n");
            report.urls.stream().limit(25).forEach(u -> {
                sb.append("  [").append(u.source).append("] ").append(u.url);
                if (u.flags != null && !u.flags.isEmpty())
                    sb.append(" flags=").append(u.flags);
                if (u.displayText != null && !u.displayText.isBlank())
                    sb.append(" label=\"").append(u.displayText.strip()).append("\"");
                if (u.pageCoverageRatio != null && u.pageCoverageRatio >= 0.5)
                    sb.append(String.format(" page_coverage=%.0f%%", u.pageCoverageRatio * 100));
                sb.append("\n");
            });
            if (report.urls.size() > 25) sb.append("  ... and ").append(report.urls.size() - 25).append(" more\n");
        }

        // Page text (first page — for CTA / urgency language detection)
        if (report.pageTexts != null && !report.pageTexts.isEmpty()) {
            var firstPage = report.pageTexts.get(0);
            if (firstPage != null && firstPage.text != null && !firstPage.text.isBlank()) {
                sb.append("\n=== PAGE 1 TEXT ===\n");
                sb.append(firstPage.text, 0, Math.min(1500, firstPage.text.length())).append("\n");
            }
        }

        // JavaScript
        if (report.javascript != null && !report.javascript.isEmpty()) {
            sb.append("\n=== JAVASCRIPT (").append(report.javascript.size()).append(" scripts) ===\n");
            var first = report.javascript.get(0);
            if (first.code != null)
                sb.append(first.code, 0, Math.min(600, first.code.length())).append("\n");
        }

        // Emails
        if (report.emails != null && !report.emails.isEmpty()) {
            sb.append("\n=== EMAILS ===\n");
            report.emails.stream().limit(10).forEach(e -> sb.append("  ").append(e.email).append(" [").append(e.source).append("]\n"));
        }

        // QR codes
        if (report.screenshots != null) {
            report.screenshots.forEach(ss -> {
                if (ss.qrCodes != null && !ss.qrCodes.isEmpty()) {
                    sb.append("\n=== QR CODES ===\n");
                    ss.qrCodes.forEach(q -> sb.append("  ").append(q.text).append("\n"));
                }
            });
        }

        // Phone numbers
        if (report.phoneNumbers != null && !report.phoneNumbers.isEmpty()) {
            sb.append("\n=== PHONE NUMBERS ===\n");
            report.phoneNumbers.stream().limit(10).forEach(p -> {
                sb.append("  ").append(p.e164 != null ? p.e164 : p.raw);
                if (p.countryCode != null) sb.append(" [").append(p.countryCode).append("]");
                if (p.lineType != null)    sb.append(" [").append(p.lineType).append("]");
                if (p.geocode != null)     sb.append(" (").append(p.geocode).append(")");
                sb.append(" [").append(p.source).append("]\n");
            });
        }

        // XFA scripts
        if (report.xfaScripts != null && !report.xfaScripts.isEmpty()) {
            sb.append("\n=== XFA SCRIPTS (").append(report.xfaScripts.size()).append(") ===\n");
            var first = report.xfaScripts.get(0);
            if (first.code != null)
                sb.append(first.code, 0, Math.min(400, first.code.length())).append("\n");
        }

        // Embedded files
        if (report.embeddedFiles != null && !report.embeddedFiles.isEmpty()) {
            sb.append("\n=== EMBEDDED FILES ===\n");
            report.embeddedFiles.forEach(f -> sb.append("  ").append(f.originalName).append(" (").append(f.mimeType).append(")\n"));
        }

        // Launch actions
        if (report.launchActions != null && !report.launchActions.isEmpty()) {
            sb.append("\n=== LAUNCH ACTIONS ===\n");
            report.launchActions.forEach(l -> sb.append("  ").append(l.file).append("\n"));
        }

        // Revision URL changes — forensically significant: URLs injected or removed across revisions
        if (report.revisions != null && !report.revisions.isEmpty()) {
            boolean anyChanges = report.revisions.stream().anyMatch(r ->
                (r.removedUrls != null && !r.removedUrls.isEmpty()) ||
                (r.addedUrls != null && !r.addedUrls.isEmpty()) ||
                Boolean.TRUE.equals(r.urlsChangedVisuallyHidden));
            if (anyChanges) {
                sb.append("\n=== REVISION URL CHANGES (").append(report.revisionCount).append(" revisions) ===\n");
                sb.append("  IMPORTANT: This document was modified incrementally. URL differences between revisions indicate possible tampering.\n");
                report.revisions.forEach(r -> {
                    sb.append("  Revision ").append(r.revision).append(" of ").append(r.totalRevisions).append(":\n");
                    if (r.removedUrls != null && !r.removedUrls.isEmpty())
                        r.removedUrls.forEach(u -> sb.append("    REMOVED (not in final): ").append(u).append("\n"));
                    if (r.addedUrls != null && !r.addedUrls.isEmpty())
                        r.addedUrls.forEach(u -> sb.append("    ADDED after this revision: ").append(u).append("\n"));
                    if (r.urls != null && r.removedUrls == null && r.addedUrls == null && !r.urls.isEmpty())
                        r.urls.forEach(u -> sb.append("    URL: ").append(u.url).append("\n"));
                    if (Boolean.TRUE.equals(r.urlsChangedVisuallyHidden))
                        sb.append("    WARNING: URLs changed but page appears visually identical — hidden link injection suspected\n");
                });
            }
        }

        // OCG hidden layers
        if (report.ocgLayers != null && !report.ocgLayers.isEmpty()) {
            long suspicious = report.ocgLayers.stream().filter(l -> l.suspicious).count();
            sb.append("\n=== OCG LAYERS (").append(report.ocgLayers.size()).append(", ").append(suspicious).append(" suspicious) ===\n");
            report.ocgLayers.forEach(l -> sb.append("  ").append(l.name).append(" default=").append(l.defaultState).append(" suspicious=").append(l.suspicious).append("\n"));
        }

        // OCR text from first screenshot
        if (report.screenshots != null) {
            report.screenshots.stream()
                .filter(ss -> ss.ocrText != null && !ss.ocrText.isBlank())
                .findFirst()
                .ifPresent(ss -> {
                    sb.append("\n=== PAGE 1 OCR TEXT ===\n");
                    sb.append(ss.ocrText, 0, Math.min(1200, ss.ocrText.length())).append("\n");
                });
        }

        // URL crop OCR (first few with text)
        if (report.urls != null) {
            List<UrlHit> withOcr = report.urls.stream()
                .filter(u -> u.cropOcrText != null && !u.cropOcrText.isBlank())
                .limit(5)
                .collect(Collectors.toList());
            if (!withOcr.isEmpty()) {
                sb.append("\n=== URL CROP OCR ===\n");
                withOcr.forEach(u -> sb.append("  href=").append(u.url).append(" | visible=\"").append(u.cropOcrText.strip()).append("\"\n"));
            }
        }

        return sb.toString();
    }
}
