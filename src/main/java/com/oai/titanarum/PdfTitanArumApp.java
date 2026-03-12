package com.oai.titanarum;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.action.PDAnnotationAdditionalActions;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.common.PDDestinationOrAction;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDXFAResource;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import com.google.common.net.InternetDomainName;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberMatch;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat;
import com.google.i18n.phonenumbers.Phonenumber.PhoneNumber;
import com.google.i18n.phonenumbers.geocoding.PhoneNumberOfflineGeocoder;
import org.nibor.autolink.LinkExtractor;
import org.nibor.autolink.LinkSpan;
import org.nibor.autolink.LinkType;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.NotFoundException;
import com.google.zxing.Result;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.multi.qrcode.QRCodeMultiReader;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.imageio.ImageIO;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.awt.geom.Point2D;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

@Command(name = "pdf-titan-arum",
        mixinStandardHelpOptions = true,
        version = "pdf-titan-arum 1.3.0",
        description = "Extract URLs, screenshots, images, embedded files, launch actions, JavaScript/XFA script, QR codes, selected pages, and perceptual hashes from a PDF.")
public class PdfTitanArumApp implements Callable<Integer> {

    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\b((?:https?|hxxps?)://[^\\s<>()\\[\\]{}]+|www\\.[^\\s<>()\\[\\]{}]+)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("(?i)\\b([a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,})\\b");
    private static final Pattern HASH_SX_PAT = Pattern.compile(
        "startxref[\\x00\\x09\\x0a\\x0c\\x0d\\x20]{1,}([0-9]{1,})[\\x00\\x09\\x0a\\x0c\\x0d\\x20]{1,}");
    private static final Pattern HASH_PREV_PAT = Pattern.compile("/Prev\\s+(\\d+)");
    private static final Pattern HASH_XREFSTM_PAT = Pattern.compile("/XRefStm\\s+(\\d+)");

    private static final LinkExtractor LINK_EXTRACTOR = LinkExtractor.builder()
            .linkTypes(EnumSet.of(LinkType.URL, LinkType.WWW))
            .build();

    private static final PhoneNumberUtil PHONE_UTIL = PhoneNumberUtil.getInstance();
    private static final PhoneNumberOfflineGeocoder PHONE_GEOCODER =
            PhoneNumberOfflineGeocoder.getInstance();

    // Resource limits
    private static final long MAX_PDF_BYTES           = 500L * 1024 * 1024; // 500 MB
    private static final float MAX_DPI                = 600.0f;
    private static final long MAX_EMBEDDED_FILE_BYTES = 100L * 1024 * 1024; // 100 MB
    private static final int MAX_SCRIPT_BYTES         = 10 * 1024 * 1024;   // 10 MB
    private static final int MAX_XFA_BYTES            = 10 * 1024 * 1024;   // 10 MB
    private static final int MAX_PAGES_ALL            = 1000;
    private static final int MAX_UNIQUE_PATH_ATTEMPTS = 10_000;
    private static final int MAX_NAME_TREE_DEPTH      = 50;
    private static final int MAX_DEREF_HOPS           = 50;

    @Option(names = {"-i", "--input"}, required = false, description = "Input PDF")
    private Path inputPdf;

    @Option(names = {"-o", "--output"}, required = false, description = "Output directory")
    private Path outputDir;

    @Option(names = {"--dpi"}, defaultValue = "150", description = "Render DPI for screenshots")
    private float dpi;

@Option(names = {"--pages"}, defaultValue = "default",
        description = "Pages to process for page-based extraction/rendering (qpdf-like). Examples: '1-4,z' or '2,5-7' or 'all'. Default: first 4 pages plus last page if it doesn't overlap.")
private String pagesSpec;

@Option(names = {"--skip-qr"}, defaultValue = "false", description = "Skip QR code detection for screenshots and extracted images")
private boolean skipQrScan;

    @Option(names = {"--skip-screenshots"}, defaultValue = "false", description = "Skip screenshot rendering, URL crop extraction, and QR code detection")
    private boolean skipScreenshots;

    @Option(names = {"--skip-images"}, defaultValue = "false", description = "Skip drawn and resource image extraction")
    private boolean skipImages;

    @Option(names = {"--skip-phones"}, defaultValue = "false", description = "Skip phone number extraction")
    private boolean skipPhones;

    @Option(names = {"--skip-page-export"}, defaultValue = "false", description = "Skip per-page PDF export")
    private boolean skipPageExport;

    @Option(names = {"--skip-text-urls"}, defaultValue = "false", description = "Skip text-based URL/phone extraction (PDFTextStripper); only annotation links are extracted")
    private boolean skipTextUrls;

    @Option(names = {"--profile"}, defaultValue = "false", description = "Print per-stage wall-clock timing to stderr")
    private boolean profile;

    @Option(names = {"--timeout"}, defaultValue = "0", description = "Hard time limit in seconds (0 = no limit). On timeout, partial results are written with timedOut=true.")
    private int timeoutSeconds;

    @Option(names = {"--ocr-screenshots"}, defaultValue = "false", description = "Run Tesseract OCR on each screenshot and embed extracted text in the report")
    private boolean ocrScreenshots;

    @Option(names = {"--ocr-url-crops"}, defaultValue = "false", description = "Run Tesseract OCR on each URL bounding-box crop image")
    private boolean ocrUrlCrops;

    @Option(names = {"--ocr-lang"}, defaultValue = "eng", description = "Tesseract language(s) to use for OCR, e.g. eng+deu+fra (default: eng)")
    private String ocrLang;

    @Option(names = {"--ai-url"}, description = "OpenAI-compatible API base URL for AI threat analysis, e.g. https://api.openai.com/v1")
    private String aiUrl;

    @Option(names = {"--ai-key"}, description = "API key for AI analysis (use 'none' for local models without auth)")
    private String aiKey;

    @Option(names = {"--ai-model"}, description = "Model to use for AI analysis (auto-detected from /models if not set)")
    private String aiModel;

    public void setSkipScreenshots(boolean v) { this.skipScreenshots = v; }
    public void setSkipImages(boolean v)      { this.skipImages = v; }
    public void setSkipPhones(boolean v)      { this.skipPhones = v; }
    public void setSkipPageExport(boolean v)  { this.skipPageExport = v; }
    public void setSkipTextUrls(boolean v)    { this.skipTextUrls = v; }
    public void setNoSkipBlanks(boolean v) { this.noSkipBlanks = v; }
    public void setTimeout(int seconds) { this.timeoutSeconds = seconds; }
    public void setOcrScreenshots(boolean v) { this.ocrScreenshots = v; }
    public void setOcrUrlCrops(boolean v) { this.ocrUrlCrops = v; }
    public void setOcrLang(String v) { this.ocrLang = v; }

    @Option(names = {"--no-skip-blanks"}, defaultValue = "false",
            description = "Disable blank-page replacement: process originally selected pages as-is, including blanks")
    private boolean noSkipBlanks;

    @Option(names = {"--add-link-annotations"}, defaultValue = "false", description = "Add clickable annotations for visible naked URLs")
    private boolean addLinkAnnotations;

    @Option(names = {"--save-modified-pdf"}, description = "Optional output path for a modified PDF with synthetic link annotations")
    private Path modifiedPdfOutput;

    @Option(names = {"--password"}, description = "Password for encrypted PDFs (also tried automatically for blank-password PDFs)")
    private String password;

    private final ObjectMapper mapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static void main(String[] args) {
        CommandLine cmd = new CommandLine(new PdfTitanArumApp());
        try {
            Class<?> serverClass = Class.forName("com.oai.titanarum.server.ServerCommand");
            cmd.addSubcommand("server", serverClass.getDeclaredConstructor().newInstance());
        } catch (ClassNotFoundException ignored) {
            // server mode not available in this build
        } catch (Exception e) {
            System.err.println("WARNING: could not load server command: " + e.getMessage());
        }
        int exit = cmd.execute(args);
        System.exit(exit);
    }

    @Override
    public Integer call() throws Exception {
        if (inputPdf == null) {
            System.err.println("ERROR: -i / --input is required");
            return 1;
        }
        if (outputDir == null) {
            System.err.println("ERROR: -o / --output is required");
            return 1;
        }
        if (dpi <= 0 || dpi > MAX_DPI) {
            System.err.println("ERROR: --dpi must be between 1 and " + MAX_DPI);
            return 1;
        }
        if (!Files.isRegularFile(inputPdf)) {
            System.err.println("ERROR: Input is not a regular file: " + inputPdf);
            return 1;
        }
        long pdfSize = Files.size(inputPdf);
        if (pdfSize > MAX_PDF_BYTES) {
            System.err.println("ERROR: Input file too large (" + pdfSize + " bytes, max " + MAX_PDF_BYTES + ")");
            return 1;
        }
        byte[] pdfBytes = Files.readAllBytes(inputPdf);
        AnalysisReport report = callWith(pdfBytes, inputPdf.getFileName().toString(),
                 outputDir, dpi, pagesSpec, skipQrScan, addLinkAnnotations, modifiedPdfOutput, password);
        if (aiUrl != null && !aiUrl.isBlank()) {
            String effectiveKey = (aiKey != null && !aiKey.isBlank()) ? aiKey : "none";
            try {
                String model = (aiModel != null && !aiModel.isBlank()) ? aiModel
                    : OpenAiAnalyzer.detectModel(effectiveKey, aiUrl);
                System.err.println("AI analysis: model=" + model + " url=" + aiUrl);
                Path screenshotsDir = outputDir.resolve("screenshots");
                report.aiAnalysis = new OpenAiAnalyzer(effectiveKey, model, aiUrl)
                    .analyze(report, inputPdf.getFileName().toString(), screenshotsDir);
            } catch (Exception e) {
                System.err.println("AI analysis failed: " + e.getMessage());
                report.aiAnalysis = java.util.Map.of("error", e.getMessage());
            }
            // Re-write report.json with AI results (success or error)
            mapper.writeValue(outputDir.resolve("report.json").toFile(), report);
        }
        return 0;
    }

    public AnalysisReport callWith(
            byte[] pdfBytes,
            String originalName,
            Path outputDir,
            float dpi,
            String pagesSpec,
            boolean skipQrScan,
            boolean addLinkAnnotations,
            Path modifiedPdfOutput,
            String password) throws Exception {
        // Sync instance fields so helper methods that reference this.xxx work correctly
        this.outputDir = outputDir.toAbsolutePath();
        this.dpi = dpi;
        this.pagesSpec = pagesSpec;
        this.skipQrScan = skipQrScan;
        this.addLinkAnnotations = addLinkAnnotations;
        this.modifiedPdfOutput = modifiedPdfOutput;
        this.password = password;
        outputDir = this.outputDir;
        Files.createDirectories(outputDir);
        Thread.interrupted(); // clear any stale interrupt from a previous timed-out job on this thread
        final Thread _processingThread = Thread.currentThread();
        final long _startNanos = System.nanoTime();
        final java.util.concurrent.ScheduledExecutorService _watchdog = timeoutSeconds > 0
            ? java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "timeout-watchdog"); t.setDaemon(true); return t; })
            : null;
        if (_watchdog != null)
            _watchdog.schedule(() -> _processingThread.interrupt(), timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        Path screenshotsDir = outputDir.resolve("screenshots");
        Path renderedImagesDir = outputDir.resolve("images_rendered");
        Path resourceImagesDir = outputDir.resolve("images_resources");
        Path attachmentsDir = outputDir.resolve("attachments");
        Path scriptsDir = outputDir.resolve("scripts");
        Path xfaDir = outputDir.resolve("xfa");
        Path pagesDir = outputDir.resolve("pages");
        Path launchesDir = outputDir.resolve("launch_actions");
        Path urlCropsDir = outputDir.resolve("url_crops");
        Files.createDirectories(screenshotsDir);
        Files.createDirectories(renderedImagesDir);
        Files.createDirectories(resourceImagesDir);
        Files.createDirectories(attachmentsDir);
        Files.createDirectories(scriptsDir);
        Files.createDirectories(xfaDir);
        Files.createDirectories(pagesDir);
        Files.createDirectories(launchesDir);
        Files.createDirectories(urlCropsDir);

        AnalysisReport report = new AnalysisReport();
        report.inputPdf = originalName;
        report.outputDirectory = outputDir.toAbsolutePath().toString();
        report.generatedAt = Instant.now().toString();
        report.dpi = dpi;
        report.addLinkAnnotations = addLinkAnnotations;
        report.documentSha256 = sha256(pdfBytes);
        report.fileMagic = detectFileMagic(pdfBytes);

        // Acrobat-style header search: find %PDF within first 1024 bytes.
        // If found at offset > 0, slice the array so PDFBox parses from the real header.
        byte[] originalPdfBytes = pdfBytes; // preserved for structural anomaly checks
        int pdfHeaderOffset = findPdfHeader(pdfBytes);
        if (pdfHeaderOffset < 0) {
            report.parseError = "File does not contain a PDF header";
            mapper.writeValue(outputDir.resolve("report.json").toFile(), report);
            System.out.println("Wrote report: " + outputDir.resolve("report.json") + " [not a PDF]");
            return report;
        }
        if (pdfHeaderOffset > 0) {
            System.err.println("WARNING: %PDF header found at offset " + pdfHeaderOffset + ", parsing from that offset");
            pdfBytes = Arrays.copyOfRange(pdfBytes, pdfHeaderOffset, pdfBytes.length);
        }

        // Per-stage profiling support: _pt[0] holds the reference timestamp for each tick.
        final long[] _pt = profile ? new long[]{System.nanoTime()} : null;

        // Try to load PDF — handle encryption and corruption gracefully
        PDDocument loadedDoc;
        try {
            if (password != null && !password.isEmpty()) {
                loadedDoc = Loader.loadPDF(pdfBytes, password);
            } else {
                PDDocument tmp;
                try {
                    tmp = Loader.loadPDF(pdfBytes);
                } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
                    // Try blank password — some PDFs are "encrypted" but with empty password
                    try {
                        tmp = Loader.loadPDF(pdfBytes, "");
                    } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e2) {
                        report.parseError = "PDF is password-protected (use --password to supply one)";
                        mapper.writeValue(outputDir.resolve("report.json").toFile(), report);
                        System.out.println("Wrote report: " + outputDir.resolve("report.json") + " [encrypted]");
                        return report;
                    }
                }
                loadedDoc = tmp;
            }
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            report.parseError = "Incorrect password supplied";
            mapper.writeValue(outputDir.resolve("report.json").toFile(), report);
            System.out.println("Wrote report: " + outputDir.resolve("report.json") + " [wrong password]");
            return report;
        } catch (IOException e) {
            // Strict parse failed — attempt lenient recovery (rebuilds xref by linear scan)
            String strictError = e.getMessage();
            try {
                org.apache.pdfbox.pdfparser.PDFParser parser = new org.apache.pdfbox.pdfparser.PDFParser(
                    new org.apache.pdfbox.io.RandomAccessReadBuffer(pdfBytes));
                loadedDoc = parser.parse(true); // lenient=true rebuilds xref by linear scan
                report.parseError = "Recovered (lenient parse): " + strictError;
                System.err.println("WARNING: strict parse failed (" + strictError + "), recovered via lenient parse");
            } catch (IOException e2) {
                report.parseError = "PDF parse error: " + strictError;
                mapper.writeValue(outputDir.resolve("report.json").toFile(), report);
                System.out.println("Wrote report: " + outputDir.resolve("report.json") + " [parse error]");
                return report;
            }
        }

        RevisionTimeline revisionTimeline = buildRevisionTimeline(pdfBytes);
        report.revisionCount = revisionTimeline.revisionCount;
        profTick(_pt, "load+revision");

        try (PDDocument document = loadedDoc) {
            report.pageCount = document.getNumberOfPages();
            report.documentInfo = extractDocumentInfo(document);
            List<Integer> pagesToProcess = computePagesToProcess(pagesSpec, report.pageCount);
            report.pagesSpec = pagesSpec;
            report.qrScanEnabled = !skipQrScan;

            // Blank page detection — run on all document pages before content extraction
            Set<Integer> blankPageNums = classifyBlankPages(document);
            report.blankPageCount = blankPageNums.size();
            if (!blankPageNums.isEmpty()) {
                report.blankPages = new ArrayList<>(blankPageNums);
                if (report.pageCount > 0) {
                    report.blankRatio = Math.round((double) blankPageNums.size() / report.pageCount * 100.0) / 100.0;
                }
            }
            if (!noSkipBlanks && !blankPageNums.isEmpty()) {
                pagesToProcess = fillBlankPages(pagesToProcess, blankPageNums, report.pageCount);
            }
            report.pagesProcessed = pagesToProcess;
            profTick(_pt, "blank detection");

            List<UrlHit> existingLinkUrls = extractExistingLinkAnnotations(document, pagesToProcess, revisionTimeline);
            report.urls.addAll(existingLinkUrls);
            profTick(_pt, "link annotations");

            // Single text-extraction pass per page — reused for URL detection, phone detection, and pageTexts
            // Skipped when --skip-text-urls is set; annotation-based extraction still runs above.
            List<PageTextData> pageTextData = skipTextUrls
                    ? List.of()
                    : stripTextPerPage(document, pagesToProcess);
            profTick(_pt, "text strip");

            List<UrlHit> visibleUrls = extractVisibleUrls(pageTextData);
            report.urls.addAll(visibleUrls);
            checkInterrupted();
            profTick(_pt, "visible URLs");

            if (addLinkAnnotations) {
                int added = addSyntheticLinkAnnotations(document, visibleUrls, existingLinkUrls, pagesToProcess);
                report.syntheticLinksAdded = added;
                if (modifiedPdfOutput != null) {
                    Path parent = modifiedPdfOutput.toAbsolutePath().getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    document.save(modifiedPdfOutput.toFile());
                    report.modifiedPdf = relativePath(modifiedPdfOutput);
                }
            }

            { List<String> fonts = extractFonts(document); if (!fonts.isEmpty()) report.fonts = fonts; }
            report.pdfObjectHash = computePdfObjectHash(pdfBytes);
            report.javascript.addAll(extractJavaScript(document, scriptsDir));
            report.launchActions.addAll(extractLaunchActions(document, launchesDir, pagesToProcess));
            report.actions.addAll(extractActions(document, pagesToProcess));
            { List<OcgLayer> ocgLayers = extractOcgLayers(document); if (!ocgLayers.isEmpty()) report.ocgLayers = ocgLayers; }
            report.xfaScripts.addAll(extractXfaScripts(document, xfaDir));
            report.embeddedFiles.addAll(extractNamedEmbeddedFiles(document, attachmentsDir));
            report.embeddedFiles.addAll(extractFileAttachmentAnnotations(document, attachmentsDir, pagesToProcess));
            checkInterrupted();

            for (JavaScriptHit js : report.javascript) {
                report.urls.addAll(extractUrlsFromCode(js.code, "javascript", null));
            }
            for (XfaScriptHit xfa : report.xfaScripts) {
                report.urls.addAll(extractUrlsFromCode(xfa.code, "xfa_script", null));
            }
            for (LaunchActionHit la : report.launchActions) {
                String launchPath = la.file != null ? la.file : la.parameters;
                report.urls.addAll(extractUrlsFromCode(launchPath, "launch_action", la.page));
            }
            for (ActionHit a : report.actions) {
                List<UrlHit> actionUrls = null;
                if ("SubmitForm".equals(a.type) && a.submitUrl != null)
                    actionUrls = extractUrlsFromCode(a.submitUrl, "submit_form", a.page);
                else if ("GoToR".equals(a.type) && a.remoteFile != null)
                    actionUrls = extractUrlsFromCode(a.remoteFile, "remote_goto", a.page);
                else if ("GoToE".equals(a.type) && a.remoteFile != null)
                    actionUrls = extractUrlsFromCode(a.remoteFile, "embedded_goto", a.page);
                else if ("URI".equals(a.type) && a.target != null)
                    actionUrls = extractUrlsFromCode(a.target, "action_uri", a.page);
                else if ("Rendition".equals(a.type) && a.target != null)
                    actionUrls = extractUrlsFromCode(a.target, "rendition", a.page);
                if (actionUrls != null) {
                    if (a.bounds != null) actionUrls.forEach(u -> { if (u.bounds == null) u.bounds = a.bounds; });
                    if (isSilentContext(a.context))
                        actionUrls.forEach(u -> { if (u.flags == null) u.flags = new ArrayList<>(); u.flags.add("silent_trigger"); });
                    report.urls.addAll(actionUrls);
                }
            }

            report.pageStats = computePageLinkStats(document, pagesToProcess);
            profTick(_pt, "JS/XFA/actions/embedded");

            if (!skipPhones) {
                report.phoneNumbers.addAll(extractTelAnnotations(document, pagesToProcess));
                report.phoneNumbers.addAll(extractVisiblePhones(pageTextData));

                profTick(_pt, "phones");
            }

            // Email extraction
            report.emails.addAll(extractMailtoAnnotations(document, pagesToProcess));
            report.emails.addAll(extractEmailsFromText(pageTextData));
            for (JavaScriptHit js : report.javascript) {
                if (js.code != null)
                    report.emails.addAll(extractEmailsFromCode(js.code, "javascript", null));
            }
            report.emails = dedupeEmails(report.emails);
            // Remove mailto: hits from URL list (they are emails, not web URLs)
            report.urls.removeIf(u -> u.url != null && u.url.toLowerCase(java.util.Locale.ROOT).startsWith("mailto:"));
            checkInterrupted();
            profTick(_pt, "emails");

            for (PageTextData ptd : pageTextData) {
                PageText pt = new PageText();
                pt.page = ptd.page();
                pt.text = ptd.stripper().getCollectedText().stripTrailing();
                report.pageTexts.add(pt);
            }

            if (!skipPageExport) {
                extractSelectedPages(document, pagesDir, pagesToProcess, report.pagePdfs);
                profTick(_pt, "page export");
            }
            if (!skipScreenshots) {
                renderScreenshots(document, screenshotsDir, pagesToProcess, report.screenshots);
                profTick(_pt, "screenshots");
                cropUrlRegions(report.urls, report.screenshots, urlCropsDir);
                profTick(_pt, "url crops");
            }
            // Process previous revisions: always extract URLs, render screenshots if not skipped
            if (report.revisionCount > 1) {
                List<Integer> eofBounds = findEofBoundaries(pdfBytes);
                if (eofBounds.size() > 1) {
                    Path revisionsDir = outputDir.resolve("revisions");
                    if (!skipScreenshots) Files.createDirectories(revisionsDir);
                    int total = eofBounds.size();
                    int maxPrev = 5;
                    int step = Math.max(1, (total - 1 + maxPrev - 1) / maxPrev);
                    // Build set of current URLs for diffing
                    Set<String> currentUrls = report.urls.stream()
                        .map(u -> u.url).collect(java.util.stream.Collectors.toSet());
                    List<RevisionArtifact> revList = new ArrayList<>();
                    // Iterate from second-newest down to oldest
                    for (int idx = total - 2; idx >= 0; idx -= step) {
                        checkInterrupted();
                        int boundary = eofBounds.get(idx);
                        byte[] revBytes = Arrays.copyOfRange(pdfBytes, 0, boundary);
                        PDDocument revDoc = null;
                        try {
                            try { revDoc = Loader.loadPDF(revBytes); }
                            catch (IOException e) {
                                revDoc = new org.apache.pdfbox.pdfparser.PDFParser(
                                    new org.apache.pdfbox.io.RandomAccessReadBuffer(revBytes)).parse(true);
                            }
                            int revNum = idx + 1; // 1 = oldest
                            RevisionArtifact ra = new RevisionArtifact();
                            ra.revision = revNum;
                            ra.totalRevisions = total;
                            ra.urls = extractRevisionUrls(revDoc);
                            // Compute URL diff vs final revision (by URL string)
                            Set<String> revUrlStrings = ra.urls.stream().map(u -> u.url).collect(java.util.stream.Collectors.toSet());
                            List<String> removed = ra.urls.stream().map(u -> u.url).filter(u -> !currentUrls.contains(u)).distinct().collect(java.util.stream.Collectors.toList());
                            List<String> added = currentUrls.stream().filter(u -> !revUrlStrings.contains(u)).collect(java.util.stream.Collectors.toList());
                            if (!removed.isEmpty()) ra.removedUrls = removed;
                            if (!added.isEmpty()) ra.addedUrls = added;
                            if (!skipScreenshots) {
                                Path revDir = revisionsDir.resolve(String.format(Locale.ROOT, "rev-%03d", revNum));
                                Path revCropsDir = revDir.resolve("url_crops");
                                Files.createDirectories(revDir);
                                Files.createDirectories(revCropsDir);
                                List<ScreenshotArtifact> revShots = new ArrayList<>();
                                int np = revDoc.getNumberOfPages();
                                List<Integer> revPages = new ArrayList<>();
                                for (int p = 1; p <= Math.min(4, np); p++) revPages.add(p);
                                if (np > 4) revPages.add(np);
                                renderScreenshots(revDoc, revDir, revPages, revShots);
                                if (!revShots.isEmpty()) {
                                    ra.screenshots = revShots;
                                    cropUrlRegions(ra.urls, revShots, revCropsDir);
                                    // Flag visually-hidden URL changes: screenshots pixel-identical but URLs differ
                                    boolean urlsDiffer = !removed.isEmpty() || !added.isEmpty();
                                    if (urlsDiffer && !report.screenshots.isEmpty()) {
                                        boolean visuallySame = report.screenshots.stream()
                                            .anyMatch(s -> s.hashes != null && s.hashes.sha256 != null
                                                && revShots.stream().anyMatch(r -> s.hashes.sha256.equals(
                                                    r.hashes != null ? r.hashes.sha256 : null)));
                                        if (visuallySame) ra.urlsChangedVisuallyHidden = true;
                                    }
                                }
                            }
                            if (!ra.urls.isEmpty() || (ra.screenshots != null && !ra.screenshots.isEmpty())) {
                                revList.add(ra);
                            }
                        } catch (Exception e) {
                            System.err.println("WARNING: revision " + (idx + 1) + " failed: " + e.getMessage());
                        } finally {
                            if (revDoc != null) try { revDoc.close(); } catch (IOException ignored) {}
                        }
                    }
                    if (!revList.isEmpty()) {
                        report.revisions = revList;
                        // Inject revision-only URLs into main URL list so they appear in the URL table
                        for (RevisionArtifact ra : revList) {
                            if (ra.removedUrls == null || ra.urls == null) continue;
                            for (UrlHit u : ra.urls) {
                                if (ra.removedUrls.contains(u.url)) {
                                    UrlHit copy = new UrlHit();
                                    copy.page = u.page;
                                    copy.url = u.url;
                                    copy.displayText = u.displayText;
                                    copy.source = u.source;
                                    copy.bounds = u.bounds;
                                    copy.flags = u.flags;
                                    copy.pageCoverageRatio = u.pageCoverageRatio;
                                    copy.cropPath = u.cropPath;
                                    copy.cropHashes = u.cropHashes;
                                    copy.fromRevision = ra.revision;
                                    report.urls.add(copy);
                                }
                            }
                        }
                        // Re-sort: current URLs first (by page), then removed revision URLs (by revision asc)
                        report.urls.sort(java.util.Comparator
                            .comparingInt((UrlHit u) -> u.fromRevision != null ? 1 : 0)
                            .thenComparingInt(u -> u.fromRevision != null ? u.fromRevision : 0)
                            .thenComparingInt(u -> u.page));
                    }
                    profTick(_pt, "revision processing");
                }
            }
            if (!skipImages) {
                Set<COSBase> drawnCos = extractDrawnImages(document, renderedImagesDir, pagesToProcess, report.renderedImages);
                profTick(_pt, "drawn images");
                extractResourceImages(document, resourceImagesDir, pagesToProcess, report.resourceImages, drawnCos);
                profTick(_pt, "resource images");
            }
        } catch (InterruptedException | java.io.InterruptedIOException e) {
            Thread.interrupted(); // clear flag
            report.timedOut = true;
            report.timedOutAfterMs = (System.nanoTime() - _startNanos) / 1_000_000;
        }
        if (_watchdog != null) _watchdog.shutdownNow();

        List<Path> hashTargets = new ArrayList<>();
        addPaths(hashTargets, report.screenshots);
        // renderedImages and resourceImages are hashed in-memory during extraction
        // Collect unique URL crop paths
        Map<String, List<UrlHit>> cropPathToHits = new LinkedHashMap<>();
        for (UrlHit hit : report.urls) {
            if (hit.cropPath != null)
                cropPathToHits.computeIfAbsent(hit.cropPath, k -> new ArrayList<>()).add(hit);
        }
        for (String cp : cropPathToHits.keySet())
            hashTargets.add(outputDir.resolve(cp));
        Map<String, HashResult> hashes = computeJavaHashes(hashTargets);
        attachHashes(report.screenshots, hashes);
        // Attach crop hashes to URL hits
        for (Map.Entry<String, List<UrlHit>> e : cropPathToHits.entrySet()) {
            Path resolved = outputDir.resolve(e.getKey()).toAbsolutePath().normalize();
            HashResult hr = hashes.get(resolved.toString());
            if (hr != null) e.getValue().forEach(h -> h.cropHashes = hr);
        }
        profTick(_pt, "screenshot hashing");

        // Extract URLs from QR code decoded text
        for (ScreenshotArtifact ss : report.screenshots) {
            if (ss.qrCodes != null) for (QrCodeHit qr : ss.qrCodes)
                report.urls.addAll(extractUrlsFromCode(qr.text, "qr_code", ss.page));
        }
        for (ImageArtifact img : report.renderedImages) {
            if (img.qrCodes != null) for (QrCodeHit qr : img.qrCodes)
                report.urls.addAll(extractUrlsFromCode(qr.text, "qr_code", img.page));
        }
        for (ImageArtifact img : report.resourceImages) {
            if (img.qrCodes != null) for (QrCodeHit qr : img.qrCodes)
                report.urls.addAll(extractUrlsFromCode(qr.text, "qr_code", img.page));
        }

        // Final dedup across all URL sources (annotation + text + JS etc.)
        report.urls = dedupeUrlHits(report.urls);

        // Stream length mismatch detection (raw-byte scan)
        List<StreamLengthHit> slh = checkStreamLengths(pdfBytes);
        if (!slh.isEmpty()) report.streamLengthAnomalies = slh;

        // Structural anomaly detection
        List<StructuralAnomalyHit> sah = checkStructuralAnomalies(originalPdfBytes, pdfHeaderOffset, pdfBytes);
        if (!sah.isEmpty()) report.structuralAnomalies = sah;

        // Metadata spoofing indicators
        if (report.documentInfo != null) {
            List<MetadataSpoofingHit> msh = checkMetadataSpoofing(report.documentInfo);
            if (!msh.isEmpty()) report.metadataSpoofingIndicators = msh;
        }

        Path reportPath = outputDir.resolve("report.json");
        mapper.writeValue(reportPath.toFile(), report);
        profTick(_pt, "report.json write");
        System.out.println("Wrote report: " + reportPath.toAbsolutePath());
        return report;
    }

    /**
     * Runs Tesseract OCR on a BufferedImage via the system tesseract CLI.
     * @param image  the image to OCR
     * @param lang   tesseract language string, e.g. "eng" or "eng+deu"
     * @param psm    page segmentation mode (3=auto, 7=single line)
     * @return trimmed OCR text, or null if empty/unavailable
     */
    private static final java.util.regex.Pattern OCR_LANG_PATTERN =
        java.util.regex.Pattern.compile("^[a-zA-Z]{2,8}(\\+[a-zA-Z]{2,8})*$");

    private static final int MAX_OCR_OUTPUT_BYTES = 10 * 1024 * 1024; // 10 MB

    private static String ocrImage(java.awt.image.BufferedImage image, String lang, int psm, Path tempDir) {
        String safeLang = (lang != null && OCR_LANG_PATTERN.matcher(lang).matches()) ? lang : "eng";
        java.io.File tmp = null;
        Process p = null;
        try {
            // L4: use job-private temp dir rather than world-readable /tmp
            tmp = Files.createTempFile(tempDir, "ocr-", ".png").toFile();
            javax.imageio.ImageIO.write(image, "PNG", tmp);
            p = new ProcessBuilder(
                "tesseract", tmp.getAbsolutePath(), "stdout",
                "-l", safeLang,
                "--psm", String.valueOf(psm))
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
            // C1: cap stdout before waitFor so a runaway process can't fill heap
            byte[] raw = p.getInputStream().readNBytes(MAX_OCR_OUTPUT_BYTES);
            // C1: close stream so Tesseract gets EPIPE if it was blocked on a full pipe
            //     (cap hit case) — otherwise waitFor blocks for 30s before destroyForcibly
            p.getInputStream().close();
            p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
            String trimmed = new String(raw, java.nio.charset.StandardCharsets.UTF_8).strip();
            return trimmed.isEmpty() ? null : trimmed;
        } catch (Exception e) {
            return null;
        } finally {
            // C1: ensure the process is always reaped even on exception or timeout
            if (p != null) p.destroyForcibly();
            if (tmp != null) tmp.delete();
        }
    }

    /** Throws InterruptedException if the current thread has been interrupted (timeout). */
    private static void checkInterrupted() throws InterruptedException {
        if (Thread.interrupted()) throw new InterruptedException("processing timed out");
    }

    /** Prints elapsed ms since last call (or since initialization) when --profile is active. */
    private static void profTick(long[] pt, String label) {
        if (pt == null) return;
        long now = System.nanoTime();
        System.err.printf("[PROFILE] %-26s %4dms%n", label, (now - pt[0]) / 1_000_000);
        pt[0] = now;
    }

    private void attachHashes(List<? extends HashedArtifact> artifacts, Map<String, HashResult> hashes) {
        for (HashedArtifact artifact : artifacts) {
            Path resolved = outputDir.resolve(artifact.getPath()).toAbsolutePath().normalize();
            HashResult hash = hashes.get(resolved.toString());
            if (hash != null) {
                artifact.setHashes(hash);
            }
        }
    }

    private void addPaths(List<Path> targets, List<? extends HashedArtifact> artifacts) {
        for (HashedArtifact artifact : artifacts) {
            if (artifact.getPath() != null) {
                targets.add(outputDir.resolve(artifact.getPath()));
            }
        }
    }

    private String relativePath(Path path) {
        try {
            return outputDir.toAbsolutePath().relativize(path.toAbsolutePath()).toString();
        } catch (IllegalArgumentException e) {
            // Paths on different roots (shouldn't happen) — fall back to filename only
            return path.getFileName().toString();
        }
    }

    /**
     * Extracts document-level metadata from the PDF's Info dictionary.
     * All string fields may be null if absent from the PDF.
     * Date fields use ISO-8601 format; daysSince values are computed relative to today (UTC).
     * Malformed or future dates produce a null daysSince value.
     */
    private static DocumentInfo extractDocumentInfo(PDDocument document) {
        PDDocumentInformation info = document.getDocumentInformation();
        DocumentInfo di = new DocumentInfo();

        // PDF spec version from the file header (e.g. %PDF-1.7 → "1.7")
        float ver = document.getVersion();
        di.pdfVersion = String.format(java.util.Locale.ROOT, "%.1f", ver);

        // String fields — PDFBox normalises PDF encoding (PDFDocEncoding or UTF-16BE)
        di.title    = blankToNull(info.getTitle());
        di.author   = blankToNull(info.getAuthor());
        di.subject  = blankToNull(info.getSubject());
        di.keywords = blankToNull(info.getKeywords());
        di.creator  = blankToNull(info.getCreator());
        di.producer = blankToNull(info.getProducer());

        // Date fields — getCreationDate() / getModificationDate() parse D:YYYYMMDDHHmmSSOHH'mm'
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant createdInstant = null;
        Instant modifiedInstant = null;

        try {
            java.util.Calendar cal = info.getCreationDate();
            if (cal != null) {
                createdInstant = cal.toInstant();
                di.creationDate = createdInstant.toString();
                long days = ChronoUnit.DAYS.between(createdInstant.atZone(ZoneOffset.UTC).toLocalDate(), today);
                di.daysSinceCreated = days >= 0 ? days : null; // null for future dates
            }
        } catch (Exception ignored) { /* malformed date string in PDF */ }

        try {
            java.util.Calendar cal = info.getModificationDate();
            if (cal != null) {
                modifiedInstant = cal.toInstant();
                di.modificationDate = modifiedInstant.toString();
                long days = ChronoUnit.DAYS.between(modifiedInstant.atZone(ZoneOffset.UTC).toLocalDate(), today);
                di.daysSinceModified = days >= 0 ? days : null;
            }
        } catch (Exception ignored) { /* malformed date string in PDF */ }

        if (createdInstant != null && modifiedInstant != null) {
            di.daysBetweenCreatedAndModified = ChronoUnit.DAYS.between(
                createdInstant.atZone(ZoneOffset.UTC).toLocalDate(),
                modifiedInstant.atZone(ZoneOffset.UTC).toLocalDate());
        }

        return di;
    }

    /** Returns null for null or blank strings. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private List<UrlHit> extractExistingLinkAnnotations(PDDocument document, List<Integer> pagesToProcess, RevisionTimeline revisionTimeline) throws IOException {
        List<UrlHit> results = new ArrayList<>();
        Set<Integer> pageSet = new HashSet<>(pagesToProcess);
        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            if (!pageSet.contains(pageNumber)) {
                continue;
            }
            for (PDAnnotation annotation : page.getAnnotations()) {
                if (annotation instanceof PDAnnotationLink link) {
                    PDAction action = link.getAction();
                    if (action instanceof PDActionURI uriAction) {
                        PDRectangle rect = link.getRectangle();
                        UrlHit hit = new UrlHit();
                        hit.page = pageNumber;
                        hit.url = normalizeUrl(uriAction.getURI());
                        hit.source = "annotation_uri";
                        hit.annotationAlreadyPresent = true;
                        hit.bounds = rect == null ? null : BoundingBox.fromPdfRect(rect);
                        PDRectangle mediaBox = page.getMediaBox();
                        if (rect != null && mediaBox != null) {
                            double pageArea = (double) mediaBox.getWidth() * mediaBox.getHeight();
                            double annotArea = (double) rect.getWidth() * rect.getHeight();
                            if (pageArea > 0) hit.pageCoverageRatio = annotArea / pageArea;
                        }
                        try {
                            org.apache.pdfbox.cos.COSBase cosBase = link.getCOSObject();
                            long objNum = -1;
                            if (cosBase instanceof org.apache.pdfbox.cos.COSObject cosObj) {
                                objNum = cosObj.getObjectNumber();
                            }
                            if (objNum >= 0) {
                                List<RevisionEntry> history = revisionTimeline.objectTimelines.get((int) objNum);
                                if (history != null && !history.isEmpty()) hit.revisionHistory = history;
                            }
                        } catch (Exception ignored) {}
                        enrichUrlHit(hit);
                        results.add(hit);
                    }
                }
            }
        }
        return results;
    }

    private List<PageTextData> stripTextPerPage(PDDocument document, List<Integer> pagesToProcess) throws IOException {
        List<PageTextData> result = new ArrayList<>();
        Set<Integer> pageSet = new HashSet<>(pagesToProcess);
        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            if (!pageSet.contains(pageNumber)) continue;
            PositionAwareTextStripper stripper = new PositionAwareTextStripper();
            stripper.setSortByPosition(true);
            stripper.setStartPage(pageNumber);
            stripper.setEndPage(pageNumber);
            stripper.getText(document);
            result.add(new PageTextData(pageNumber, page, stripper));
        }
        return result;
    }

    private List<UrlHit> extractVisibleUrls(List<PageTextData> pageTextData) {
        List<UrlHit> results = new ArrayList<>();
        for (PageTextData ptd : pageTextData) {
            String text = ptd.stripper().getCollectedText();
            for (LinkSpan link : LINK_EXTRACTOR.extractLinks(text)) {
                String raw = text.substring(link.getBeginIndex(), link.getEndIndex());
                List<TextPosition> positions = ptd.stripper().positionsForRange(link.getBeginIndex(), link.getEndIndex());
                BoundingBox box = BoundingBox.fromTextPositions(positions, ptd.pdfPage());
                UrlHit hit = new UrlHit();
                hit.page = ptd.page();
                hit.url = normalizeUrl(raw);
                hit.displayText = raw;
                hit.source = "visible_text";
                hit.bounds = box;
                enrichUrlHit(hit);
                results.add(hit);
            }
        }
        return dedupeUrlHits(results);
    }

    private List<UrlHit> dedupeUrlHits(List<UrlHit> hits) {
        List<UrlHit> deduped = new ArrayList<>();
        Map<String, UrlHit> seen = new LinkedHashMap<>();
        for (UrlHit hit : hits) {
            String key = hit.page + "|" + hit.url + "|" + (hit.bounds == null ? "" : hit.bounds.toKey());
            UrlHit existing = seen.get(key);
            if (existing == null) {
                seen.put(key, hit);
                deduped.add(hit);
            } else if (hit.flags != null) {
                // Merge flags from duplicate into the kept hit
                if (existing.flags == null) existing.flags = new ArrayList<>();
                for (String f : hit.flags)
                    if (!existing.flags.contains(f)) existing.flags.add(f);
            }
        }
        return deduped;
    }

    private int addSyntheticLinkAnnotations(PDDocument document, List<UrlHit> visibleUrls, List<UrlHit> existingLinks, List<Integer> pagesToProcess) throws IOException {
        Map<Integer, List<UrlHit>> existingByPage = new HashMap<>();
        Set<Integer> pageSet = new HashSet<>(pagesToProcess);
        for (UrlHit hit : existingLinks) {
            existingByPage.computeIfAbsent(hit.page, k -> new ArrayList<>()).add(hit);
        }

        int added = 0;
        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            if (!pageSet.contains(pageNumber)) {
                continue;
            }
            List<UrlHit> current = new ArrayList<>();
            for (UrlHit hit : visibleUrls) {
                if (hit.page == pageNumber && hit.bounds != null) {
                    current.add(hit);
                }
            }
            if (current.isEmpty()) {
                continue;
            }
            List<PDAnnotation> annotations = page.getAnnotations();
            for (UrlHit hit : current) {
                if (isAlreadyCovered(hit, existingByPage.getOrDefault(pageNumber, List.of()))) {
                    continue;
                }
                PDRectangle rect = hit.bounds.toPdfRectangle();
                PDAnnotationLink link = new PDAnnotationLink();
                link.setRectangle(rect);
                link.setQuadPoints(hit.bounds.toQuadPoints());
                PDActionURI action = new PDActionURI();
                action.setURI(hit.url);
                link.setAction(action);
                annotations.add(link);
                added++;
            }
        }
        return added;
    }

    private boolean isAlreadyCovered(UrlHit candidate, List<UrlHit> existingHits) {
        for (UrlHit existing : existingHits) {
            if (Objects.equals(existing.url, candidate.url)) {
                if (existing.bounds == null || candidate.bounds == null) {
                    return true;
                }
                if (existing.bounds.overlaps(candidate.bounds, 6f)) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<JavaScriptHit> extractJavaScript(PDDocument document, Path scriptsDir) throws IOException {
        List<JavaScriptHit> hits = new ArrayList<>();
        PDDocumentCatalog catalog = document.getDocumentCatalog();

        COSDictionary catalogDict = catalog.getCOSObject();
        extractJavaScriptFromActionCos(catalogDict.getDictionaryObject(COSName.OPEN_ACTION), "catalog.openAction", scriptsDir, hits, new HashSet<COSDictionary>());
        COSDictionary aa = asDictionary(catalogDict.getDictionaryObject(COSName.AA));
        if (aa != null) {
            for (COSName key : aa.keySet()) {
                extractJavaScriptFromActionCos(aa.getDictionaryObject(key), "catalog.additionalActions." + key.getName(), scriptsDir, hits, new HashSet<COSDictionary>());
            }
        }

        COSDictionary namesDict = asDictionary(catalogDict.getDictionaryObject(COSName.NAMES));
        if (namesDict != null) {
            extractJavaScriptNameTree(namesDict.getDictionaryObject(COSName.JAVA_SCRIPT), "catalog.names.javascript", scriptsDir, hits, 0);
        }

        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            COSDictionary pageDict = page.getCOSObject();
            COSDictionary pageAA = asDictionary(pageDict.getDictionaryObject(COSName.AA));
            if (pageAA != null) {
                for (COSName key : pageAA.keySet()) {
                    extractJavaScriptFromActionCos(pageAA.getDictionaryObject(key), "page[" + pageNumber + "].additionalActions." + key.getName(), scriptsDir, hits, new HashSet<COSDictionary>());
                }
            }
            int annotationIndex = 0;
            for (PDAnnotation annotation : page.getAnnotations()) {
                annotationIndex++;
                COSDictionary annotDict = annotation.getCOSObject();
                extractJavaScriptFromActionCos(annotDict.getDictionaryObject(COSName.A), "page[" + pageNumber + "].annotation[" + annotationIndex + "].action", scriptsDir, hits, new HashSet<COSDictionary>());
                COSDictionary annotAA = asDictionary(annotDict.getDictionaryObject(COSName.AA));
                if (annotAA != null) {
                    for (COSName key : annotAA.keySet()) {
                        extractJavaScriptFromActionCos(annotAA.getDictionaryObject(key), "page[" + pageNumber + "].annotation[" + annotationIndex + "].additionalActions." + key.getName(), scriptsDir, hits, new HashSet<COSDictionary>());
                    }
                }
            }
        }

        PDAcroForm acroForm = catalog.getAcroForm();
        if (acroForm != null) {
            COSDictionary acroDict = acroForm.getCOSObject();
            COSDictionary acroAA = asDictionary(acroDict.getDictionaryObject(COSName.AA));
            if (acroAA != null) {
                for (COSName key : acroAA.keySet()) {
                    extractJavaScriptFromActionCos(acroAA.getDictionaryObject(key), "acroForm.additionalActions." + key.getName(), scriptsDir, hits, new HashSet<COSDictionary>());
                }
            }

            for (PDField field : acroForm.getFieldTree()) {
                String fieldName = field.getFullyQualifiedName();
                COSDictionary fieldDict = field.getCOSObject();
                COSDictionary fieldAA = asDictionary(fieldDict.getDictionaryObject(COSName.AA));
                if (fieldAA != null) {
                    for (COSName key : fieldAA.keySet()) {
                        extractJavaScriptFromActionCos(fieldAA.getDictionaryObject(key), "field[" + fieldName + "].additionalActions." + key.getName(), scriptsDir, hits, new HashSet<COSDictionary>());
                    }
                }
                COSBase kidsBase = fieldDict.getDictionaryObject(COSName.KIDS);
                if (kidsBase instanceof COSArray kids) {
                    for (int i = 0; i < kids.size(); i++) {
                        COSDictionary kid = asDictionary(kids.getObject(i));
                        if (kid == null) {
                            continue;
                        }
                        extractJavaScriptFromActionCos(kid.getDictionaryObject(COSName.A), "field[" + fieldName + "].widget[" + i + "].action", scriptsDir, hits, new HashSet<COSDictionary>());
                        COSDictionary kidAA = asDictionary(kid.getDictionaryObject(COSName.AA));
                        if (kidAA != null) {
                            for (COSName key : kidAA.keySet()) {
                                extractJavaScriptFromActionCos(kidAA.getDictionaryObject(key), "field[" + fieldName + "].widget[" + i + "].additionalActions." + key.getName(), scriptsDir, hits, new HashSet<COSDictionary>());
                            }
                        }
                    }
                }
            }
        }

        return hits;
    }

    private void extractJavaScriptNameTree(COSBase base, String contextPrefix, Path scriptsDir, List<JavaScriptHit> hits, int depth) throws IOException {
        if (depth > MAX_NAME_TREE_DEPTH) {
            System.err.println("WARNING: name tree depth limit (" + MAX_NAME_TREE_DEPTH + ") reached at '" + contextPrefix + "', subtree skipped");
            return;
        }
        COSDictionary dict = asDictionary(base);
        if (dict == null) {
            return;
        }
        COSArray names = asArray(dict.getDictionaryObject(COSName.NAMES));
        if (names != null) {
            for (int i = 0; i + 1 < names.size(); i += 2) {
                COSBase nameBase = names.getObject(i);
                String name = cosToString(nameBase);
                COSBase actionBase = names.getObject(i + 1);
                extractJavaScriptFromActionCos(actionBase, contextPrefix + "[" + name + "]", scriptsDir, hits, new HashSet<COSDictionary>());
            }
        }
        COSArray kids = asArray(dict.getDictionaryObject(COSName.KIDS));
        if (kids != null) {
            for (int i = 0; i < kids.size(); i++) {
                extractJavaScriptNameTree(kids.getObject(i), contextPrefix + ".kid[" + i + "]", scriptsDir, hits, depth + 1);
            }
        }
    }

    private void extractJavaScriptFromActionCos(COSBase base, String context, Path scriptsDir, List<JavaScriptHit> hits, Set<COSDictionary> visited) throws IOException {
        base = deref(base);
        if (!(base instanceof COSDictionary dict)) {
            return;
        }
        if (!visited.add(dict)) return;  // already processed this dictionary — cycle detected
        COSName subtype = dict.getCOSName(COSName.S);
        if (COSName.JAVA_SCRIPT.equals(subtype)) {
            String code = readJavaScriptCode(dict.getDictionaryObject(COSName.JS));
            JavaScriptHit hit = new JavaScriptHit();
            hit.context = context;
            hit.subtype = "JavaScript";
            hit.code = code;
            hit.sha256 = sha256(code == null ? new byte[0] : code.getBytes(StandardCharsets.UTF_8));
            hit.file = writeTextArtifact(scriptsDir, sanitizeFileName(context) + ".js.txt", code == null ? "" : code);
            hits.add(hit);
        }
        COSBase next = dict.getDictionaryObject(COSName.NEXT);
        if (next instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                extractJavaScriptFromActionCos(array.getObject(i), context + ".next[" + i + "]", scriptsDir, hits, visited);
            }
        } else if (next != null) {
            extractJavaScriptFromActionCos(next, context + ".next", scriptsDir, hits, visited);
        }
    }

    private String readJavaScriptCode(COSBase base) throws IOException {
        base = deref(base);
        if (base instanceof COSString cosString) {
            return cosString.getString();
        }
        if (base instanceof COSStream stream) {
            try (InputStream in = stream.createInputStream()) {
                byte[] data = in.readNBytes(MAX_SCRIPT_BYTES);
                // Check if stream had more data
                if (in.read() != -1) {
                    System.err.println("WARNING: JavaScript stream truncated at " + MAX_SCRIPT_BYTES + " bytes");
                }
                return new String(data, StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private List<XfaScriptHit> extractXfaScripts(PDDocument document, Path xfaDir) throws Exception {
        List<XfaScriptHit> hits = new ArrayList<>();
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        PDAcroForm acroForm = catalog.getAcroForm();
        if (acroForm == null || !acroForm.hasXFA()) {
            return hits;
        }
        PDXFAResource xfa = acroForm.getXFA();
        if (xfa == null) {
            return hits;
        }
        byte[] xfaBytes = xfa.getBytes();
        if (xfaBytes == null || xfaBytes.length == 0) return hits;
        if (xfaBytes.length > MAX_XFA_BYTES) {
            System.err.println("WARNING: XFA document too large (" + xfaBytes.length + " bytes), skipping script extraction");
            return hits;
        }
        Path rawXfaPath = xfaDir.resolve("xfa.xml");
        Files.write(rawXfaPath, xfaBytes);

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        Document xml = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xfaBytes));

        XPath xpath = XPathFactory.newInstance().newXPath();
        NodeList scriptNodes = (NodeList) xpath.evaluate("//*[local-name()='script']", xml, XPathConstants.NODESET);
        for (int i = 0; i < scriptNodes.getLength(); i++) {
            Node node = scriptNodes.item(i);
            String contentType = node.getAttributes() != null && node.getAttributes().getNamedItem("contentType") != null
                    ? node.getAttributes().getNamedItem("contentType").getNodeValue() : null;
            String raw = node.getTextContent();
            XfaScriptHit hit = new XfaScriptHit();
            hit.context = "xfa.script[" + i + "]";
            hit.contentType = contentType;
            hit.code = raw;
            hit.sha256 = sha256(raw == null ? new byte[0] : raw.getBytes(StandardCharsets.UTF_8));
            hit.file = writeTextArtifact(xfaDir, "xfa-script-" + i + ".txt", raw == null ? "" : raw);
            hits.add(hit);
        }
        return hits;
    }

    private List<EmbeddedFileHit> extractNamedEmbeddedFiles(PDDocument document, Path attachmentsDir) throws IOException {
        List<EmbeddedFileHit> hits = new ArrayList<>();
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        PDDocumentNameDictionary names = catalog.getNames();
        if (names == null || names.getEmbeddedFiles() == null) {
            return hits;
        }
        extractEmbeddedFileNameTree(names.getEmbeddedFiles().getCOSObject(), "catalog.names.embeddedFiles", attachmentsDir, hits, 0);
        return hits;
    }

    private void extractEmbeddedFileNameTree(COSBase base, String contextPrefix, Path attachmentsDir, List<EmbeddedFileHit> hits, int depth) throws IOException {
        if (depth > MAX_NAME_TREE_DEPTH) {
            System.err.println("WARNING: name tree depth limit (" + MAX_NAME_TREE_DEPTH + ") reached at '" + contextPrefix + "', subtree skipped");
            return;
        }
        COSDictionary dict = asDictionary(base);
        if (dict == null) {
            return;
        }
        COSArray names = asArray(dict.getDictionaryObject(COSName.NAMES));
        if (names != null) {
            for (int i = 0; i + 1 < names.size(); i += 2) {
                String label = cosToString(names.getObject(i));
                COSDictionary fsDict = asDictionary(names.getObject(i + 1));
                if (fsDict != null) {
                    EmbeddedFileHit hit = extractFileSpec(fsDict, attachmentsDir, contextPrefix + "[" + label + "]");
                    if (hit != null) {
                        hits.add(hit);
                    }
                }
            }
        }
        COSArray kids = asArray(dict.getDictionaryObject(COSName.KIDS));
        if (kids != null) {
            for (int i = 0; i < kids.size(); i++) {
                extractEmbeddedFileNameTree(kids.getObject(i), contextPrefix + ".kid[" + i + "]", attachmentsDir, hits, depth + 1);
            }
        }
    }

    private List<EmbeddedFileHit> extractFileAttachmentAnnotations(PDDocument document, Path attachmentsDir, List<Integer> pagesToProcess) throws IOException {
        List<EmbeddedFileHit> hits = new ArrayList<>();
        Set<Integer> pageSet = new HashSet<>(pagesToProcess);
        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            if (!pageSet.contains(pageNumber)) {
                continue;
            }
            int annotationIndex = 0;
            for (PDAnnotation annotation : page.getAnnotations()) {
                annotationIndex++;
                if (annotation instanceof PDAnnotationFileAttachment fileAttachment) {
                    if (fileAttachment.getFile() instanceof PDComplexFileSpecification spec) {
                        EmbeddedFileHit hit = saveEmbeddedFile(spec, attachmentsDir,
                                "page[" + pageNumber + "].fileAttachment[" + annotationIndex + "]",
                                pageNumber,
                                annotation.getRectangle());
                        if (hit != null) {
                            hit.source = "file_attachment_annotation";
                            hits.add(hit);
                        }
                    }
                }
            }
        }
        return hits;
    }

    private EmbeddedFileHit extractFileSpec(COSDictionary fsDict, Path attachmentsDir, String context) throws IOException {
        PDComplexFileSpecification spec = new PDComplexFileSpecification(fsDict);
        EmbeddedFileHit hit = saveEmbeddedFile(spec, attachmentsDir, context, null, null);
        if (hit != null) {
            hit.source = "named_embedded_file";
        }
        return hit;
    }

    private EmbeddedFileHit saveEmbeddedFile(PDComplexFileSpecification spec, Path attachmentsDir, String context,
                                             Integer page, PDRectangle rect) throws IOException {
        PDEmbeddedFile embeddedFile = firstEmbeddedFile(spec);
        if (embeddedFile == null) {
            return null;
        }
        String name = spec.getFilename();
        if (name == null || name.isBlank()) {
            name = sanitizeFileName(context) + ".bin";
        }
        long declaredSize = embeddedFile.getSize();
        if (declaredSize > 0 && declaredSize > MAX_EMBEDDED_FILE_BYTES) {
            System.err.println("WARNING: embedded file '" + name + "' declared size " + declaredSize + " exceeds limit, skipping");
            return null;
        }
        Path out = uniquePath(attachmentsDir, sanitizeFileName(name));
        try (InputStream in = embeddedFile.createInputStream();
             OutputStream os = Files.newOutputStream(out)) {
            try {
                copyBounded(in, os, MAX_EMBEDDED_FILE_BYTES, name);
            } catch (IOException sizeEx) {
                System.err.println("WARNING: " + sizeEx.getMessage() + ", skipping");
                Files.deleteIfExists(out);
                return null;
            }
        }
        EmbeddedFileHit hit = new EmbeddedFileHit();
        hit.context = context;
        hit.originalName = name;
        hit.file = relativePath(out);
        hit.sha256 = sha256(Files.readAllBytes(out));
        hit.size = Files.size(out);
        hit.page = page;
        hit.bounds = rect == null ? null : BoundingBox.fromPdfRect(rect);
        String declaredMime = embeddedFile.getSubtype();
        if (declaredMime != null && !declaredMime.isBlank()) {
            hit.mimeType = declaredMime;
        } else {
            hit.mimeType = Files.probeContentType(out);
        }
        return hit;
    }

    private static void copyBounded(InputStream in, OutputStream out, long maxBytes, String label) throws IOException {
        byte[] buf = new byte[65536];
        long copied = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            copied += n;
            if (copied > maxBytes) {
                throw new IOException("Size limit exceeded for '" + label + "' (" + copied + " > " + maxBytes + " bytes)");
            }
            out.write(buf, 0, n);
        }
    }

    private PDEmbeddedFile firstEmbeddedFile(PDComplexFileSpecification spec) {
        if (spec.getEmbeddedFileUnicode() != null) return spec.getEmbeddedFileUnicode();
        if (spec.getEmbeddedFileDos() != null) return spec.getEmbeddedFileDos();
        if (spec.getEmbeddedFileMac() != null) return spec.getEmbeddedFileMac();
        if (spec.getEmbeddedFileUnix() != null) return spec.getEmbeddedFileUnix();
        return spec.getEmbeddedFile();
    }

    private static final int URL_CROP_PADDING_PX = 6;

    private void cropUrlRegions(List<UrlHit> urls, List<ScreenshotArtifact> screenshots,
                                 Path urlCropsDir) {
        // Build page → screenshot map for quick lookup
        Map<Integer, ScreenshotArtifact> pageToShot = new HashMap<>();
        for (ScreenshotArtifact ss : screenshots) pageToShot.put(ss.page, ss);

        // key: url + page + bbox coords → already-written cropPath
        Map<String, String> seen = new HashMap<>();
        // Cache decoded screenshot images so each PNG is read at most once per crop session
        Map<String, BufferedImage> shotCache = new HashMap<>();
        int idx = 0;
        for (UrlHit hit : urls) {
            if (hit.bounds == null) continue;
            ScreenshotArtifact ss = pageToShot.get(hit.page);
            if (ss == null) continue;

            String dedupeKey = hit.url + "|" + hit.page + "|"
                + hit.bounds.left + "," + hit.bounds.bottom + ","
                + hit.bounds.right + "," + hit.bounds.top;
            String existing = seen.get(dedupeKey);
            if (existing != null) {
                hit.cropPath = existing;
                continue;
            }

            // Use the per-screenshot render scale (px/pt) so crops align with the actual
            // saved PNG dimensions regardless of whether the screenshot was standardized.
            double scale = ss.renderScale;
            // Convert PDF points (bottom-left origin) to pixel coords (top-left origin)
            int x1 = (int) Math.max(0, hit.bounds.left  * scale - URL_CROP_PADDING_PX);
            int y1 = (int) Math.max(0, ss.height - hit.bounds.top * scale - URL_CROP_PADDING_PX);
            int x2 = (int) Math.min(ss.width,  hit.bounds.right  * scale + URL_CROP_PADDING_PX);
            int y2 = (int) Math.min(ss.height, ss.height - hit.bounds.bottom * scale + URL_CROP_PADDING_PX);
            int w = x2 - x1;
            int h = y2 - y1;
            if (w <= 0 || h <= 0) continue;

            try {
                BufferedImage full = shotCache.computeIfAbsent(ss.path, k -> {
                    try { return ImageIO.read(outputDir.resolve(k).toFile()); }
                    catch (IOException e) { return null; }
                });
                if (full == null) continue;
                w = Math.min(w, full.getWidth()  - x1);
                h = Math.min(h, full.getHeight() - y1);
                if (w <= 0 || h <= 0) continue;
                BufferedImage crop = full.getSubimage(x1, y1, w, h);
                String cropName = String.format("url-p%04d-%04d.png", hit.page, idx);
                Path cropFile = urlCropsDir.resolve(cropName);
                ImageIO.write(crop, "PNG", cropFile.toFile());
                hit.cropPath = relativePath(cropFile);
                if (ocrUrlCrops) hit.cropOcrText = ocrImage(crop, ocrLang, 3, urlCropsDir);
                seen.put(dedupeKey, hit.cropPath);
            } catch (IOException e) {
                System.err.println("WARNING: could not crop URL region: " + e.getMessage());
            }
            idx++;
        }
    }

    private void renderScreenshots(PDDocument document, Path screenshotsDir, List<Integer> pagesToProcess, List<ScreenshotArtifact> out) throws IOException, InterruptedException {

PDFRenderer renderer = new PDFRenderer(document);
Set<String> seenPixelHashes = new HashSet<>();
for (int pageNum : pagesToProcess) {
    if (Thread.interrupted()) throw new InterruptedException("timed out during screenshot rendering");
    int i = pageNum - 1;
    if (i < 0 || i >= document.getNumberOfPages()) {
        continue;
    }
    BufferedImage image = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);

    // Deduplicate: skip pages whose pixel content is identical to an already-captured screenshot.
    // Hash on the full-res render (before scaling) for maximum accuracy.
    String pixelHash = sha256OfPixels(image);
    if (pixelHash != null && !seenPixelHashes.add(pixelHash)) {
        continue; // exact duplicate page, skip screenshot
    }

    // QR scan on the full-resolution render before downscaling (better detection)
    List<QrCodeHit> qrCodes = null;
    if (!skipQrScan) {
        List<QrCodeHit> codes = scanQrCodes(image);
        if (!codes.isEmpty()) qrCodes = codes;
    }

    // Scale to standardized viewport width so screenshots look like a laptop Adobe Reader view.
    // renderScale (px/pt) tracks the mapping from PDF coords → screenshot pixels for crops.
    double renderScale = dpi / 72.0;
    if (image.getWidth() != SCREENSHOT_TARGET_WIDTH) {
        int tw = SCREENSHOT_TARGET_WIDTH;
        int th = (int) Math.round((double) image.getHeight() * tw / image.getWidth());
        BufferedImage scaled = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                           java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, tw, th, null);
        g.dispose();
        renderScale = renderScale * tw / image.getWidth();
        image = scaled;
    }

    Path file = screenshotsDir.resolve(String.format(Locale.ROOT, "page-%04d.png", pageNum));
    ImageIO.write(image, "PNG", file.toFile());

    ScreenshotArtifact artifact = new ScreenshotArtifact();
    artifact.page = pageNum;
    artifact.path = relativePath(file);
    artifact.width = image.getWidth();
    artifact.height = image.getHeight();
    artifact.renderScale = renderScale;
    artifact.qrCodes = qrCodes;
    if (ocrScreenshots) artifact.ocrText = ocrImage(image, ocrLang, 3, screenshotsDir);
    out.add(artifact);
}

    }
/**
     * If pdImage is a JPEG or JPEG2000 XObject, saves the original encoded bytes to a file
     * alongside the PNG rendering and returns the relative path. Returns null otherwise.
     */
    private String saveOriginalXObjectBytes(PDImage pdImage, Path outputDir, String baseName) {
        if (!(pdImage instanceof PDImageXObject xobj)) return null;
        String suffix = xobj.getSuffix();
        if (!"jpg".equals(suffix) && !"jp2".equals(suffix)) return null;
        COSBase cosBase = xobj.getCOSObject();
        if (!(cosBase instanceof COSStream cosStream)) return null;
        try {
            Path file = uniquePath(outputDir, baseName + "." + suffix);
            try (InputStream is = cosStream.createInputStream();
                 OutputStream os = Files.newOutputStream(file)) {
                // C2: use bounded copy — a decompression bomb in an XObject stream
                // could otherwise expand to gigabytes before hitting the filesystem.
                copyBounded(is, os, MAX_EMBEDDED_FILE_BYTES, baseName + "." + suffix);
            }
            return relativePath(file);
        } catch (IOException e) {
            System.err.println("WARNING: could not save original image bytes: " + e.getMessage());
            return null;
        }
    }

    private Set<COSBase> extractDrawnImages(PDDocument document, Path outputDir, List<Integer> pagesToProcess, List<ImageArtifact> out) throws IOException, InterruptedException {
        Set<COSBase> drawnCos = new HashSet<>();
        Set<Integer> pageSet = new HashSet<>(pagesToProcess);
        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            if (!pageSet.contains(pageNumber)) {
                continue;
            }
            if (Thread.interrupted()) throw new InterruptedException("timed out during image extraction");
            DrawnImageCollector collector = new DrawnImageCollector(page, pageNumber, outputDir, out, drawnCos);
            collector.processPage(page);
        }
        return drawnCos;
    }

    private void extractResourceImages(PDDocument document, Path outputDir, List<Integer> pagesToProcess, List<ImageArtifact> out, Set<COSBase> drawnCos) throws IOException, InterruptedException {
        Set<COSBase> seen = new HashSet<>(drawnCos);
        Set<Integer> pageSet = new HashSet<>(pagesToProcess);
        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            if (!pageSet.contains(pageNumber)) {
                continue;
            }
            if (Thread.interrupted()) throw new InterruptedException("timed out during resource image extraction");
            traverseResources(page.getResources(), outputDir, out, seen, "page[" + pageNumber + "].resources", pageNumber);
        }
    }

    private void traverseResources(PDResources resources, Path outputDir, List<ImageArtifact> out, Set<COSBase> seen,
                                   String context, int pageNumber) throws IOException {
        if (resources == null) {
            return;
        }
        for (COSName name : resources.getXObjectNames()) {
            PDXObject xObject = resources.getXObject(name);
            if (xObject == null) {
                continue;
            }
            COSBase cos = xObject.getCOSObject();
            if (seen.contains(cos)) {
                continue;
            }
            if (xObject instanceof PDImageXObject imageXObject) {
                seen.add(cos);
                BufferedImage image = imageXObject.getImage();
                Path file = uniquePath(outputDir, String.format(Locale.ROOT, "resource-page-%04d-%s.png", pageNumber, name.getName()));
                ImageIO.write(image, "PNG", file.toFile());
                ImageArtifact artifact = new ImageArtifact();
                artifact.page = pageNumber;
                artifact.path = relativePath(file);
                artifact.width = image.getWidth();
                artifact.height = image.getHeight();
                artifact.source = "resource_xobject";
                artifact.context = context + "." + name.getName();
                artifact.originalPath = saveOriginalXObjectBytes(imageXObject, outputDir,
                    String.format(Locale.ROOT, "resource-page-%04d-%s-original", pageNumber, name.getName()));
                // artifact.originalPath is relative to the main outputDir (outer class field), not the subdir param
                artifact.hashes = computeImageHashInMemory(image,
                    artifact.originalPath != null ? PdfTitanArumApp.this.outputDir.resolve(artifact.originalPath) : file);
                if (!skipQrScan) {
                    List<QrCodeHit> codes = scanQrCodes(image);
                    if (!codes.isEmpty()) {
                        artifact.qrCodes = codes;
                    }
                }
                out.add(artifact);
            } else if (xObject instanceof PDFormXObject formXObject) {
                traverseResources(formXObject.getResources(), outputDir, out, seen, context + "." + name.getName(), pageNumber);
            }
        }
    }

    private List<Integer> computePagesToProcess(String spec, int pageCount) {
        LinkedHashSet<Integer> pages = new LinkedHashSet<>();
        if (pageCount <= 0) {
            return new ArrayList<>();
        }
        String normalized = spec == null ? "default" : spec.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || normalized.equals("default")) {
            for (int i = 1; i <= Math.min(4, pageCount); i++) {
                pages.add(i);
            }
            if (pageCount > 4) {
                pages.add(pageCount);
            }
            return new ArrayList<>(pages);
        }
        if (normalized.equals("all")) {
            int limit = Math.min(pageCount, MAX_PAGES_ALL);
            if (pageCount > MAX_PAGES_ALL) {
                System.err.println("WARNING: --pages all capped at " + MAX_PAGES_ALL + " (document has " + pageCount + " pages)");
            }
            for (int i = 1; i <= limit; i++) {
                pages.add(i);
            }
            return new ArrayList<>(pages);
        }
        for (String rawPart : normalized.split(",")) {
            String part = rawPart.trim();
            if (part.isEmpty()) {
                continue;
            }
            boolean exclude = false;
            if (part.startsWith("x")) {
                exclude = true;
                part = part.substring(1);
            }
            String parity = null;
            int colon = part.indexOf(':');
            if (colon >= 0) {
                parity = part.substring(colon + 1).trim();
                part = part.substring(0, colon).trim();
            }
            List<Integer> resolved = new ArrayList<>();
            if (part.contains("-")) {
                String[] bits = part.split("-", 2);
                Integer start = resolvePageRef(bits[0].trim(), pageCount);
                Integer end = resolvePageRef(bits[1].trim(), pageCount);
                if (start == null || end == null) {
                    continue;
                }
                int step = start <= end ? 1 : -1;
                for (int i = start; ; i += step) {
                    resolved.add(i);
                    if (i == end) {
                        break;
                    }
                }
            } else {
                Integer one = resolvePageRef(part, pageCount);
                if (one != null) {
                    resolved.add(one);
                }
            }
            if (parity != null) {
                String p = parity.toLowerCase(Locale.ROOT);
                resolved.removeIf(v -> ("odd".equals(p) && v % 2 == 0) || ("even".equals(p) && v % 2 != 0));
            }
            for (Integer page : resolved) {
                if (page == null || page < 1 || page > pageCount) {
                    continue;
                }
                if (exclude) {
                    pages.remove(page);
                } else {
                    pages.add(page);
                }
            }
        }
        if (pages.isEmpty()) {
            for (int i = 1; i <= Math.min(4, pageCount); i++) {
                pages.add(i);
            }
            if (pageCount > 4) {
                pages.add(pageCount);
            }
        }
        return new ArrayList<>(pages);
    }

    private Integer resolvePageRef(String token, int pageCount) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String t = token.trim().toLowerCase(Locale.ROOT);
        if (t.equals("z")) {
            return pageCount;
        }
        if (t.startsWith("r")) {
            try {
                int fromEnd = Integer.parseInt(t.substring(1));
                if (fromEnd < 1) {
                    return null;
                }
                return pageCount - fromEnd + 1;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        try {
            return Integer.parseInt(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void extractSelectedPages(PDDocument document, Path pagesDir, List<Integer> pagesToProcess, List<PagePdfArtifact> out) throws IOException, InterruptedException {
        for (int pageNum : pagesToProcess) {
            if (Thread.interrupted()) throw new InterruptedException("timed out during page export");
            int idx = pageNum - 1;
            if (idx < 0 || idx >= document.getNumberOfPages()) {
                continue;
            }
            try (PDDocument single = new PDDocument()) {
                single.importPage(document.getPage(idx));
                Path file = pagesDir.resolve(String.format(Locale.ROOT, "page-%04d.pdf", pageNum));
                single.save(file.toFile());
                PagePdfArtifact artifact = new PagePdfArtifact();
                artifact.page = pageNum;
                artifact.path = relativePath(file);
                artifact.sha256 = sha256(Files.readAllBytes(file));
                out.add(artifact);
            }
        }
    }

    private List<LaunchActionHit> extractLaunchActions(PDDocument document, Path launchesDir, List<Integer> pagesToProcess) throws IOException {
        List<LaunchActionHit> hits = new ArrayList<>();
        Set<Integer> pageSet = new HashSet<>(pagesToProcess);
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        COSDictionary catalogDict = catalog.getCOSObject();
        extractLaunchFromActionCos(catalogDict.getDictionaryObject(COSName.OPEN_ACTION), "catalog.openAction", launchesDir, hits, null, null, new HashSet<COSDictionary>());
        COSDictionary aa = asDictionary(catalogDict.getDictionaryObject(COSName.AA));
        if (aa != null) {
            for (COSName key : aa.keySet()) {
                extractLaunchFromActionCos(aa.getDictionaryObject(key), "catalog.additionalActions." + key.getName(), launchesDir, hits, null, null, new HashSet<COSDictionary>());
            }
        }

        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            if (!pageSet.contains(pageNumber)) {
                continue;
            }
            COSDictionary pageDict = page.getCOSObject();
            COSDictionary pageAA = asDictionary(pageDict.getDictionaryObject(COSName.AA));
            if (pageAA != null) {
                for (COSName key : pageAA.keySet()) {
                    extractLaunchFromActionCos(pageAA.getDictionaryObject(key), "page[" + pageNumber + "].additionalActions." + key.getName(), launchesDir, hits, pageNumber, null, new HashSet<COSDictionary>());
                }
            }
            int annotationIndex = 0;
            for (PDAnnotation annotation : page.getAnnotations()) {
                annotationIndex++;
                COSDictionary annotDict = annotation.getCOSObject();
                BoundingBox bounds = annotation.getRectangle() == null ? null : BoundingBox.fromPdfRect(annotation.getRectangle());
                extractLaunchFromActionCos(annotDict.getDictionaryObject(COSName.A), "page[" + pageNumber + "].annotation[" + annotationIndex + "].action", launchesDir, hits, pageNumber, bounds, new HashSet<COSDictionary>());
                COSDictionary annotAA = asDictionary(annotDict.getDictionaryObject(COSName.AA));
                if (annotAA != null) {
                    for (COSName key : annotAA.keySet()) {
                        extractLaunchFromActionCos(annotAA.getDictionaryObject(key), "page[" + pageNumber + "].annotation[" + annotationIndex + "].additionalActions." + key.getName(), launchesDir, hits, pageNumber, bounds, new HashSet<COSDictionary>());
                    }
                }
            }
        }

        PDAcroForm acroForm = catalog.getAcroForm();
        if (acroForm != null) {
            COSDictionary acroDict = acroForm.getCOSObject();
            COSDictionary acroAA = asDictionary(acroDict.getDictionaryObject(COSName.AA));
            if (acroAA != null) {
                for (COSName key : acroAA.keySet()) {
                    extractLaunchFromActionCos(acroAA.getDictionaryObject(key), "acroForm.additionalActions." + key.getName(), launchesDir, hits, null, null, new HashSet<COSDictionary>());
                }
            }
            for (PDField field : acroForm.getFieldTree()) {
                String fieldName = field.getFullyQualifiedName();
                COSDictionary fieldDict = field.getCOSObject();
                COSDictionary fieldAA = asDictionary(fieldDict.getDictionaryObject(COSName.AA));
                if (fieldAA != null) {
                    for (COSName key : fieldAA.keySet()) {
                        extractLaunchFromActionCos(fieldAA.getDictionaryObject(key), "field[" + fieldName + "].additionalActions." + key.getName(), launchesDir, hits, null, null, new HashSet<COSDictionary>());
                    }
                }
                COSBase kidsBase = fieldDict.getDictionaryObject(COSName.KIDS);
                if (kidsBase instanceof COSArray kids) {
                    for (int i = 0; i < kids.size(); i++) {
                        COSDictionary kid = asDictionary(kids.getObject(i));
                        if (kid == null) {
                            continue;
                        }
                        extractLaunchFromActionCos(kid.getDictionaryObject(COSName.A), "field[" + fieldName + "].widget[" + i + "].action", launchesDir, hits, null, null, new HashSet<COSDictionary>());
                        COSDictionary kidAA = asDictionary(kid.getDictionaryObject(COSName.AA));
                        if (kidAA != null) {
                            for (COSName key : kidAA.keySet()) {
                                extractLaunchFromActionCos(kidAA.getDictionaryObject(key), "field[" + fieldName + "].widget[" + i + "].additionalActions." + key.getName(), launchesDir, hits, null, null, new HashSet<COSDictionary>());
                            }
                        }
                    }
                }
            }
        }
        return hits;
    }

    private void extractLaunchFromActionCos(COSBase base, String context, Path launchesDir, List<LaunchActionHit> hits, Integer page, BoundingBox bounds, Set<COSDictionary> visited) throws IOException {
        base = deref(base);
        if (!(base instanceof COSDictionary dict)) {
            return;
        }
        if (!visited.add(dict)) return;  // already processed this dictionary — cycle detected
        COSName subtype = dict.getCOSName(COSName.S);
        if (COSName.getPDFName("Launch").equals(subtype)) {
            LaunchActionHit hit = new LaunchActionHit();
            hit.context = context;
            hit.page = page;
            hit.bounds = bounds;
            hit.file = readFileSpecLike(dict.getDictionaryObject(COSName.F));
            hit.directory = cosToStringSafe(dict.getDictionaryObject(COSName.D));
            hit.operation = cosToStringSafe(dict.getDictionaryObject(COSName.O));
            hit.parameters = cosToStringSafe(dict.getDictionaryObject(COSName.P));
            COSBase newWindow = deref(dict.getDictionaryObject(COSName.NEW_WINDOW));
            if (newWindow instanceof COSBoolean b) {
                hit.newWindow = b.getValue();
            }
            COSDictionary win = asDictionary(dict.getDictionaryObject(COSName.WIN));
            if (win != null) {
                if (hit.file == null) hit.file = readFileSpecLike(win.getDictionaryObject(COSName.F));
                if (hit.directory == null) hit.directory = cosToStringSafe(win.getDictionaryObject(COSName.D));
                if (hit.operation == null) hit.operation = cosToStringSafe(win.getDictionaryObject(COSName.O));
                if (hit.parameters == null) hit.parameters = cosToStringSafe(win.getDictionaryObject(COSName.P));
            }
            String summary = "context=" + String.valueOf(hit.context) + "\n"
                    + "page=" + String.valueOf(hit.page) + "\n"
                    + "file=" + String.valueOf(hit.file) + "\n"
                    + "directory=" + String.valueOf(hit.directory) + "\n"
                    + "operation=" + String.valueOf(hit.operation) + "\n"
                    + "parameters=" + String.valueOf(hit.parameters) + "\n"
                    + "newWindow=" + String.valueOf(hit.newWindow) + "\n"
                    + "raw=" + dict.toString() + "\n";
            hit.artifact = writeTextArtifact(launchesDir, sanitizeFileName(context) + ".launch.txt", summary);
            hit.sha256 = sha256(summary.getBytes(StandardCharsets.UTF_8));
            hits.add(hit);
        }
        COSBase next = dict.getDictionaryObject(COSName.NEXT);
        if (next instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                extractLaunchFromActionCos(array.getObject(i), context + ".next[" + i + "]", launchesDir, hits, page, bounds, visited);
            }
        } else if (next != null) {
            extractLaunchFromActionCos(next, context + ".next", launchesDir, hits, page, bounds, visited);
        }
    }

    private List<ActionHit> extractActions(PDDocument document, List<Integer> pagesToProcess) throws IOException {
        List<ActionHit> hits = new ArrayList<>();
        Set<Integer> pageSet = new HashSet<>(pagesToProcess);
        PDDocumentCatalog catalog = document.getDocumentCatalog();
        COSDictionary catalogDict = catalog.getCOSObject();

        extractActionsFromActionCos(catalogDict.getDictionaryObject(COSName.OPEN_ACTION),
            "catalog.openAction", hits, null, null, new HashSet<>());
        COSDictionary aa = asDictionary(catalogDict.getDictionaryObject(COSName.AA));
        if (aa != null) {
            for (COSName key : aa.keySet()) {
                extractActionsFromActionCos(aa.getDictionaryObject(key),
                    "catalog.additionalActions." + key.getName(), hits, null, null, new HashSet<>());
            }
        }

        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            if (!pageSet.contains(pageNumber)) continue;
            COSDictionary pageDict = page.getCOSObject();
            COSDictionary pageAA = asDictionary(pageDict.getDictionaryObject(COSName.AA));
            if (pageAA != null) {
                for (COSName key : pageAA.keySet()) {
                    extractActionsFromActionCos(pageAA.getDictionaryObject(key),
                        "page[" + pageNumber + "].additionalActions." + key.getName(),
                        hits, pageNumber, null, new HashSet<>());
                }
            }
            int annotIdx = 0;
            for (PDAnnotation annot : page.getAnnotations()) {
                annotIdx++;
                COSDictionary annotDict = annot.getCOSObject();
                BoundingBox bounds = annot.getRectangle() == null ? null : BoundingBox.fromPdfRect(annot.getRectangle());
                String annotCtx = "page[" + pageNumber + "].annotation[" + annotIdx + "]";
                extractActionsFromActionCos(annotDict.getDictionaryObject(COSName.A),
                    annotCtx + ".action", hits, pageNumber, bounds, new HashSet<>());
                COSDictionary annotAA = asDictionary(annotDict.getDictionaryObject(COSName.AA));
                if (annotAA != null) {
                    for (COSName key : annotAA.keySet()) {
                        extractActionsFromActionCos(annotAA.getDictionaryObject(key),
                            annotCtx + ".additionalActions." + key.getName(),
                            hits, pageNumber, bounds, new HashSet<>());
                    }
                }
            }
        }

        PDAcroForm acroForm = catalog.getAcroForm();
        if (acroForm != null) {
            COSDictionary acroDict = acroForm.getCOSObject();
            COSDictionary acroAA = asDictionary(acroDict.getDictionaryObject(COSName.AA));
            if (acroAA != null) {
                for (COSName key : acroAA.keySet()) {
                    extractActionsFromActionCos(acroAA.getDictionaryObject(key),
                        "acroForm.additionalActions." + key.getName(), hits, null, null, new HashSet<>());
                }
            }
            for (PDField field : acroForm.getFieldTree()) {
                String fieldName = field.getFullyQualifiedName();
                COSDictionary fieldDict = field.getCOSObject();
                COSDictionary fieldAA = asDictionary(fieldDict.getDictionaryObject(COSName.AA));
                if (fieldAA != null) {
                    for (COSName key : fieldAA.keySet()) {
                        extractActionsFromActionCos(fieldAA.getDictionaryObject(key),
                            "field[" + fieldName + "].additionalActions." + key.getName(),
                            hits, null, null, new HashSet<>());
                    }
                }
                COSBase kidsBase = fieldDict.getDictionaryObject(COSName.KIDS);
                if (kidsBase instanceof COSArray kids) {
                    for (int i = 0; i < kids.size(); i++) {
                        COSDictionary kid = asDictionary(kids.getObject(i));
                        if (kid == null) continue;
                        String widgetCtx = "field[" + fieldName + "].widget[" + i + "]";
                        extractActionsFromActionCos(kid.getDictionaryObject(COSName.A),
                            widgetCtx + ".action", hits, null, null, new HashSet<>());
                        COSDictionary kidAA = asDictionary(kid.getDictionaryObject(COSName.AA));
                        if (kidAA != null) {
                            for (COSName key : kidAA.keySet()) {
                                extractActionsFromActionCos(kidAA.getDictionaryObject(key),
                                    widgetCtx + ".additionalActions." + key.getName(),
                                    hits, null, null, new HashSet<>());
                            }
                        }
                    }
                }
            }
        }
        return hits;
    }

    private List<String> extractFonts(PDDocument document) {
        Set<String> seen = new LinkedHashSet<>();
        try {
            for (PDPage page : document.getPages()) {
                PDResources res = page.getResources();
                if (res == null) continue;
                for (COSName name : res.getFontNames()) {
                    try {
                        org.apache.pdfbox.pdmodel.font.PDFont font = res.getFont(name);
                        if (font == null) continue;
                        String baseName = font.getName();
                        if (baseName != null && !baseName.isBlank()) {
                            // Strip subset prefix (e.g. "ABCDEF+Arial" → "Arial")
                            int plus = baseName.indexOf('+');
                            seen.add(plus >= 0 && plus < baseName.length() - 1 ? baseName.substring(plus + 1) : baseName);
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return new ArrayList<>(seen);
    }

    private List<OcgLayer> extractOcgLayers(PDDocument document) {
        List<OcgLayer> result = new ArrayList<>();
        PDOptionalContentProperties ocProps;
        try { ocProps = document.getDocumentCatalog().getOCProperties(); }
        catch (Exception e) { return result; }
        if (ocProps == null) return result;
        for (PDOptionalContentGroup ocg : ocProps.getOptionalContentGroups()) {
            OcgLayer layer = new OcgLayer();
            layer.name = ocg.getName();
            PDOptionalContentProperties.BaseState base = ocProps.getBaseState();
            layer.defaultState = (base == PDOptionalContentProperties.BaseState.OFF) ? "OFF" : "ON";
            COSDictionary d = ocg.getCOSObject();
            COSDictionary usage = asDictionary(d.getDictionaryObject(COSName.getPDFName("Usage")));
            if (usage != null) {
                COSDictionary viewDict = asDictionary(usage.getDictionaryObject(COSName.getPDFName("View")));
                COSDictionary printDict = asDictionary(usage.getDictionaryObject(COSName.getPDFName("Print")));
                if (viewDict != null) {
                    String vs = cosToStringSafe(viewDict.getDictionaryObject(COSName.getPDFName("ViewState")));
                    if (vs != null) layer.visibleInView = "ON".equalsIgnoreCase(vs);
                }
                if (printDict != null) {
                    String ps = cosToStringSafe(printDict.getDictionaryObject(COSName.getPDFName("PrintState")));
                    if (ps != null) layer.visibleInPrint = "ON".equalsIgnoreCase(ps);
                }
            }
            boolean hiddenByDefault = "OFF".equals(layer.defaultState);
            boolean printViewMismatch = layer.visibleInView != null && layer.visibleInPrint != null
                && !layer.visibleInView.equals(layer.visibleInPrint);
            layer.suspicious = hiddenByDefault || printViewMismatch;
            result.add(layer);
        }
        return result;
    }

    private static final Set<String> SILENT_AA_KEYS = Set.of("openAction", "O", "PO", "PC", "C", "E", "X", "Bl");
    private static boolean isSilentContext(String context) {
        if (context == null) return false;
        String last = context.substring(context.lastIndexOf('.') + 1);
        return SILENT_AA_KEYS.contains(last);
    }

    private void extractActionsFromActionCos(COSBase base, String context, List<ActionHit> hits,
                                              Integer page, BoundingBox bounds, Set<COSDictionary> visited) {
        base = deref(base);
        if (!(base instanceof COSDictionary dict)) return;
        if (!visited.add(dict)) return;

        COSName subtype = dict.getCOSName(COSName.S);
        if (subtype != null) {
            ActionHit hit = null;
            if (COSName.getPDFName("SubmitForm").equals(subtype)) {
                hit = new ActionHit();
                hit.type = "SubmitForm";
                hit.submitUrl = readFileSpecLike(dict.getDictionaryObject(COSName.F));
                COSBase flagsBase = deref(dict.getDictionaryObject(COSName.getPDFName("Flags")));
                if (flagsBase instanceof COSNumber n) hit.submitFlags = n.intValue();
                COSBase fieldsBase = deref(dict.getDictionaryObject(COSName.getPDFName("Fields")));
                if (fieldsBase instanceof COSArray fieldsArr) {
                    hit.fields = new ArrayList<>();
                    for (int i = 0; i < fieldsArr.size(); i++) {
                        COSBase item = deref(fieldsArr.getObject(i));
                        if (item instanceof COSDictionary fd) {
                            String t = cosToStringSafe(fd.getDictionaryObject(COSName.T));
                            if (t != null) hit.fields.add(t);
                        } else if (item instanceof COSString s) {
                            hit.fields.add(s.getString());
                        }
                    }
                }
            } else if (COSName.getPDFName("ImportData").equals(subtype)) {
                hit = new ActionHit();
                hit.type = "ImportData";
                hit.importFile = readFileSpecLike(dict.getDictionaryObject(COSName.F));
            } else if (COSName.getPDFName("GoToR").equals(subtype)) {
                hit = new ActionHit();
                hit.type = "GoToR";
                hit.remoteFile = readFileSpecLike(dict.getDictionaryObject(COSName.F));
                hit.destination = cosToStringSafe(dict.getDictionaryObject(COSName.D));
                COSBase nw = deref(dict.getDictionaryObject(COSName.NEW_WINDOW));
                if (nw instanceof COSBoolean b) hit.newWindow = b.getValue();
            } else if (COSName.getPDFName("GoToE").equals(subtype)) {
                hit = new ActionHit();
                hit.type = "GoToE";
                hit.remoteFile = readFileSpecLike(dict.getDictionaryObject(COSName.F));
                hit.destination = cosToStringSafe(dict.getDictionaryObject(COSName.D));
                hit.embeddedTarget = cosToStringSafe(dict.getDictionaryObject(COSName.T));
                COSBase nw = deref(dict.getDictionaryObject(COSName.NEW_WINDOW));
                if (nw instanceof COSBoolean b) hit.newWindow = b.getValue();
            } else if (COSName.getPDFName("URI").equals(subtype)) {
                hit = new ActionHit();
                hit.type = "URI";
                hit.target = cosToStringSafe(dict.getDictionaryObject(COSName.getPDFName("URI")));
            } else if (COSName.getPDFName("Named").equals(subtype)) {
                hit = new ActionHit();
                hit.type = "Named";
                hit.target = cosToStringSafe(dict.getDictionaryObject(COSName.getPDFName("N")));
            } else if (COSName.getPDFName("Rendition").equals(subtype)) {
                hit = new ActionHit();
                hit.type = "Rendition";
                // Walk: action /R rendition /C media-clip /D file-spec
                COSDictionary rendition = asDictionary(deref(dict.getDictionaryObject(COSName.getPDFName("R"))));
                if (rendition != null) {
                    COSDictionary clip = asDictionary(deref(rendition.getDictionaryObject(COSName.getPDFName("C"))));
                    if (clip != null) {
                        hit.target = readFileSpecLike(clip.getDictionaryObject(COSName.getPDFName("D")));
                        hit.contentType = cosToStringSafe(clip.getDictionaryObject(COSName.getPDFName("CT")));
                    }
                }
            } else if (COSName.getPDFName("Sound").equals(subtype)) {
                hit = new ActionHit();
                hit.type = "Sound";
                COSDictionary soundStream = asDictionary(deref(dict.getDictionaryObject(COSName.getPDFName("Sound"))));
                if (soundStream != null)
                    hit.target = readFileSpecLike(soundStream.getDictionaryObject(COSName.F));
            } else if (COSName.getPDFName("Movie").equals(subtype)) {
                hit = new ActionHit();
                hit.type = "Movie";
                // /Annotation ref → annotation dict → /Movie dict → /F file spec
                COSDictionary annotRef = asDictionary(deref(dict.getDictionaryObject(COSName.getPDFName("Annotation"))));
                if (annotRef != null) {
                    COSDictionary movie = asDictionary(deref(annotRef.getDictionaryObject(COSName.getPDFName("Movie"))));
                    if (movie != null)
                        hit.target = readFileSpecLike(movie.getDictionaryObject(COSName.F));
                }
            }
            if (hit != null) {
                hit.context = context;
                hit.page = page;
                hit.bounds = bounds;
                hits.add(hit);
            }
        }

        COSBase next = dict.getDictionaryObject(COSName.NEXT);
        if (next instanceof COSArray array) {
            for (int i = 0; i < array.size(); i++) {
                extractActionsFromActionCos(array.getObject(i),
                    context + ".next[" + i + "]", hits, page, bounds, visited);
            }
        } else if (next != null) {
            extractActionsFromActionCos(next, context + ".next", hits, page, bounds, visited);
        }
    }

    private String readFileSpecLike(COSBase base) {
        base = deref(base);
        if (base instanceof COSString s) {
            return s.getString();
        }
        COSDictionary dict = asDictionary(base);
        if (dict == null) {
            return cosToStringSafe(base);
        }
        for (COSName key : Arrays.asList(COSName.UF, COSName.F, COSName.DOS, COSName.MAC, COSName.UNIX)) {
            String value = cosToStringSafe(dict.getDictionaryObject(key));
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return dict.toString();
    }

    private String cosToStringSafe(COSBase base) {
        base = deref(base);
        if (base == null) {
            return null;
        }
        if (base instanceof COSString s) {
            return s.getString();
        }
        if (base instanceof COSName n) {
            return n.getName();
        }
        if (base instanceof COSNumber n) {
            return n.toString();
        }
        if (base instanceof COSBoolean b) {
            return Boolean.toString(b.getValue());
        }
        if (base instanceof COSDictionary d) {
            return d.toString();
        }
        return String.valueOf(base);
    }

    private List<QrCodeHit> scanQrCodes(BufferedImage input) {
        List<QrCodeHit> results = new ArrayList<>();
        if (input == null || input.getWidth() < 40 || input.getHeight() < 40) {
            return results;
        }
        BufferedImage image = input;
        int maxDim = Math.max(image.getWidth(), image.getHeight());
        if (maxDim > 1200) {
            double scale = 1200.0 / maxDim;
            int w = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(image.getHeight() * scale));
            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(image, 0, 0, w, h, null);
            g.dispose();
            image = scaled;
        }
        try {
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
            Map<DecodeHintType, Object> hints = new HashMap<>();
            hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
            Result[] decoded = new QRCodeMultiReader().decodeMultiple(bitmap, hints);
            if (decoded != null) {
                for (Result result : decoded) {
                    QrCodeHit hit = new QrCodeHit();
                    hit.text = result.getText();
                    hit.format = result.getBarcodeFormat() == null ? null : result.getBarcodeFormat().toString();
                    if (result.getResultPoints() != null && result.getResultPoints().length > 0) {
                        hit.points = new ArrayList<>();
                        for (ResultPoint point : result.getResultPoints()) {
                            if (point == null) continue;
                            QrPoint qp = new QrPoint();
                            qp.x = point.getX();
                            qp.y = point.getY();
                            hit.points.add(qp);
                        }
                    }
                    results.add(hit);
                }
            }
        } catch (NotFoundException ignored) {
            // no QR codes found
        } catch (Exception e) {
            QrCodeHit hit = new QrCodeHit();
            hit.error = e.getMessage();
            results.add(hit);
        }
        return results;
    }

    private static double sinc(double x) {
        if (x == 0.0) return 1.0;
        double px = Math.PI * x;
        return Math.sin(px) / px;
    }

    private static double lanczosKernel(double x) {
        double ax = Math.abs(x);
        if (ax < 3.0) return sinc(ax) * sinc(ax / 3.0);
        return 0.0;
    }

    private static double[][] lanczosResize(double[][] src, int srcW, int srcH, int dstW, int dstH) {
        // Precompute horizontal kernel weights once (independent of source row).
        // Weights are normalized so no per-row wSum division is needed in the inner loop.
        double scaleX = (double) srcW / dstW;
        double filterScaleX = Math.max(1.0, scaleX);
        double supportX = 3.0 * filterScaleX;
        int[]    hOff = new int[dstW];
        double[][] hW = new double[dstW][];
        for (int xd = 0; xd < dstW; xd++) {
            double center = (xd + 0.5) * scaleX;
            int start = Math.max(0,        (int) Math.ceil(center - supportX));
            int end   = Math.min(srcW - 1, (int) Math.floor(center + supportX));
            int len = end - start + 1;
            hOff[xd] = start;
            hW[xd] = new double[len];
            double wSum = 0;
            for (int i = 0; i < len; i++) {
                double w = lanczosKernel((start + i + 0.5 - center) / filterScaleX);
                hW[xd][i] = w;
                wSum += w;
            }
            if (wSum != 0) for (int i = 0; i < len; i++) hW[xd][i] /= wSum;
        }
        // Horizontal pass: pure MAC loop, no trig, no per-sample clamping
        double[][] hpass = new double[srcH][dstW];
        for (int y = 0; y < srcH; y++) {
            double[] row = src[y];
            double[] out = hpass[y];
            for (int xd = 0; xd < dstW; xd++) {
                double[] w = hW[xd];
                int off = hOff[xd];
                double v = 0;
                for (int i = 0; i < w.length; i++) v += w[i] * row[off + i];
                out[xd] = Math.max(0.0, Math.min(255.0, v));
            }
        }
        // Precompute vertical kernel weights
        double scaleY = (double) srcH / dstH;
        double filterScaleY = Math.max(1.0, scaleY);
        double supportY = 3.0 * filterScaleY;
        int[]    vOff = new int[dstH];
        double[][] vW = new double[dstH][];
        for (int yd = 0; yd < dstH; yd++) {
            double center = (yd + 0.5) * scaleY;
            int start = Math.max(0,        (int) Math.ceil(center - supportY));
            int end   = Math.min(srcH - 1, (int) Math.floor(center + supportY));
            int len = end - start + 1;
            vOff[yd] = start;
            vW[yd] = new double[len];
            double wSum = 0;
            for (int i = 0; i < len; i++) {
                double w = lanczosKernel((start + i + 0.5 - center) / filterScaleY);
                vW[yd][i] = w;
                wSum += w;
            }
            if (wSum != 0) for (int i = 0; i < len; i++) vW[yd][i] /= wSum;
        }
        // Vertical pass: pure MAC loop
        double[][] result = new double[dstH][dstW];
        for (int yd = 0; yd < dstH; yd++) {
            double[] w = vW[yd];
            int off = vOff[yd];
            double[] out = result[yd];
            for (int xd = 0; xd < dstW; xd++) {
                double v = 0;
                for (int i = 0; i < w.length; i++) v += w[i] * hpass[off + i][xd];
                out[xd] = Math.max(0.0, Math.min(255.0, v));
            }
        }
        return result;
    }

    private static void fft(double[] re, double[] im) {
        int N = re.length;
        int j = 0;
        for (int i = 1; i < N; i++) {
            int bit = N >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i]; re[i] = re[j]; re[j] = t;
                t = im[i]; im[i] = im[j]; im[j] = t;
            }
        }
        for (int len = 2; len <= N; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wRe = Math.cos(ang), wIm = Math.sin(ang);
            for (int i = 0; i < N; i += len) {
                double curRe = 1, curIm = 0;
                for (int k = 0; k < len / 2; k++) {
                    double uRe = re[i + k], uIm = im[i + k];
                    double vRe = re[i + k + len/2] * curRe - im[i + k + len/2] * curIm;
                    double vIm = re[i + k + len/2] * curIm + im[i + k + len/2] * curRe;
                    re[i + k] = uRe + vRe; im[i + k] = uIm + vIm;
                    re[i + k + len/2] = uRe - vRe; im[i + k + len/2] = uIm - vIm;
                    double newCurRe = curRe * wRe - curIm * wIm;
                    curIm = curRe * wIm + curIm * wRe;
                    curRe = newCurRe;
                }
            }
        }
    }

    private static double[] dct1d(double[] x) {
        int N = x.length;
        double[] re = new double[N];
        double[] im = new double[N];
        for (int n = 0; n < N / 2; n++) {
            re[n]       = x[2 * n];
            re[N - 1 - n] = x[2 * n + 1];
        }
        if ((N & 1) == 1) re[N / 2] = x[N - 1];
        fft(re, im);
        double[] y = new double[N];
        for (int k = 0; k < N; k++) {
            double angle = -Math.PI * k / (2.0 * N);
            y[k] = 2.0 * (re[k] * Math.cos(angle) - im[k] * Math.sin(angle));
        }
        return y;
    }

    // Pre-scale cap: images larger than this are fast-downscaled by Java2D before
    // our pure-Java Lanczos kernel runs, avoiding O(srcW*srcH*kernelSupport) blowup.
    private static final int PHASH_PRESIZE = 256;

    // Screenshot pages are scaled to this width (pixels) to simulate a typical laptop
    // viewport in Adobe Reader (e.g. 1200px ≈ a 1920×1080 laptop at "Fit Width").
    // Crops and extracted images are NOT affected — they retain their natural dimensions.
    private static final int SCREENSHOT_TARGET_WIDTH = 1200;

    private static String computePhash(BufferedImage img) {
        // Fast pre-downscale for large images using Java2D (C-optimized area averaging)
        if (img.getWidth() > PHASH_PRESIZE || img.getHeight() > PHASH_PRESIZE) {
            double scale = Math.min((double) PHASH_PRESIZE / img.getWidth(),
                                    (double) PHASH_PRESIZE / img.getHeight());
            int pw = Math.max(32, (int) (img.getWidth()  * scale));
            int ph = Math.max(32, (int) (img.getHeight() * scale));
            BufferedImage pre = new BufferedImage(pw, ph, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = pre.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                               java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(img, 0, 0, pw, ph, null);
            g.dispose();
            img = pre;
        }
        int srcW = img.getWidth();
        int srcH = img.getHeight();
        double[][] gray = new double[srcH][srcW];
        for (int y = 0; y < srcH; y++) {
            for (int x = 0; x < srcW; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8)  & 0xFF;
                int b =  rgb        & 0xFF;
                gray[y][x] = (r * 19595 + g * 38470 + b * 7471 + 32768) >> 16;
            }
        }
        double[][] resized = lanczosResize(gray, srcW, srcH, 32, 32);
        // Round to integer pixel values, matching PIL's integer-pixel storage
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++)
                resized[y][x] = Math.round(resized[y][x]);
        double[][] tmp = new double[32][32];
        for (int col = 0; col < 32; col++) {
            double[] column = new double[32];
            for (int row = 0; row < 32; row++) column[row] = resized[row][col];
            double[] dctCol = dct1d(column);
            for (int row = 0; row < 32; row++) tmp[row][col] = dctCol[row];
        }
        double[][] dct = new double[32][32];
        for (int row = 0; row < 32; row++) dct[row] = dct1d(tmp[row]);
        double[] lowfreq = new double[64];
        for (int r = 0; r < 8; r++)
            for (int c = 0; c < 8; c++)
                lowfreq[r * 8 + c] = dct[r][c];
        double[] sorted = lowfreq.clone();
        Arrays.sort(sorted);
        double median = (sorted[31] + sorted[32]) / 2.0;
        StringBuilder sb = new StringBuilder(16);
        for (int byteIdx = 0; byteIdx < 8; byteIdx++) {
            int bite = 0;
            for (int bit = 0; bit < 8; bit++) {
                if (lowfreq[byteIdx * 8 + bit] > median) bite |= (0x80 >> bit);
            }
            sb.append(String.format("%02x", bite));
        }
        return sb.toString();
    }

    private static final int COLOR_HASH_BINBITS = 4;  // 14 bins × 4 bits = 56 bits = 14 hex chars

    /**
     * Color hash with bit-level compatibility with Python imagehash.colorhash(img, binbits=4).
     *
     * 14 bins: 1 black fraction + 1 gray fraction + 6 faint-color hue bins + 6 bright-color hue bins.
     * Each bin value (0 to 2^binbits-1) encoded with the exact same quirky bit formula as imagehash.
     * PIL 'L' grayscale and PIL HSV conversion replicated to match Pillow's C implementation.
     */
    private static String computeColorHash(BufferedImage src) {
        // Normalize to TYPE_INT_RGB to handle alpha/unusual color models
        BufferedImage img = src;
        if (src.getType() != BufferedImage.TYPE_INT_RGB) {
            img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2 = img.createGraphics();
            g2.drawImage(src, 0, 0, null);
            g2.dispose();
        }
        int width = img.getWidth(), height = img.getHeight(), n = width * height;
        int blackCount = 0, grayNotBlackCount = 0, colorfulCount = 0;
        int[] faintBins  = new int[6];
        int[] brightBins = new int[6];
        float[] hsbBuf = new float[3];

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;

                // PIL 'L' grayscale — matches Pillow's fixed-point C formula exactly
                int intensity = (r * 19595 + g * 38470 + b * 7471 + 32768) >> 16;

                // PIL HSV — Color.RGBtoHSB uses the same algorithm as Pillow's rgb2hsv_row
                java.awt.Color.RGBtoHSB(r, g, b, hsbBuf);
                int hVal = Math.round(hsbBuf[0] * 255.0f);  // roundf, not truncation
                int sVal = Math.round(hsbBuf[1] * 255.0f);

                // Thresholds: 256//8=32, 256//3=85, 256*2//3=170 (Python integer division)
                if (intensity < 32) {
                    blackCount++;
                } else if (sVal < 85) {
                    grayNotBlackCount++;
                } else {
                    colorfulCount++;
                    int hueBin = Math.min(5, (int)(hVal * 6.0 / 255.0));
                    if      (sVal < 170) faintBins[hueBin]++;
                    else if (sVal > 170) brightBins[hueBin]++;
                    // sVal == 170: colorful but in neither histogram (matches Python's strict >)
                }
            }
        }

        int binbits = COLOR_HASH_BINBITS, maxVal = 1 << binbits;
        int c = Math.max(1, colorfulCount);
        int[] values = new int[14];
        values[0] = Math.min(maxVal - 1, (int)((double) blackCount       / n * maxVal));
        values[1] = Math.min(maxVal - 1, (int)((double) grayNotBlackCount / n * maxVal));
        for (int i = 0; i < 6; i++) {
            values[2 + i] = Math.min(maxVal - 1, (int)((double) faintBins[i]  * maxVal / c));
            values[8 + i] = Math.min(maxVal - 1, (int)((double) brightBins[i] * maxVal / c));
        }

        // Bit encoding — replicates Python exactly:
        // [v // (2**(binbits-i-1)) % 2**(binbits-i) > 0 for i in range(binbits)]
        // Note: this is NOT standard binary (e.g. value 4 with binbits=4 encodes as 0x6, not 0x4)
        int hexChars = (14 * binbits + 3) / 4;
        StringBuilder sb = new StringBuilder(hexChars);
        int bitBuf = 0, bitsInBuf = 0;
        for (int val : values) {
            for (int i = 0; i < binbits; i++) {
                int bit = (val / (1 << (binbits - i - 1))) % (1 << (binbits - i)) > 0 ? 1 : 0;
                bitBuf = (bitBuf << 1) | bit;
                if (++bitsInBuf == 4) {
                    sb.append(Integer.toHexString(bitBuf));
                    bitBuf = 0; bitsInBuf = 0;
                }
            }
        }
        if (bitsInBuf > 0) sb.append(Integer.toHexString(bitBuf << (4 - bitsInBuf)));
        return sb.toString();
    }

    /** SHA-256 of raw pixel data for a TYPE_INT_RGB BufferedImage (no I/O, no PNG encoding). */
    private static String sha256OfPixels(BufferedImage img) {
        try {
            int[] pixels = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
            byte[] bytes = new byte[pixels.length * 4];
            for (int k = 0, j = 0; k < pixels.length; k++) {
                int p = pixels[k];
                bytes[j++] = (byte)(p >> 24);
                bytes[j++] = (byte)(p >> 16);
                bytes[j++] = (byte)(p >> 8);
                bytes[j++] = (byte) p;
            }
            return sha256(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Computes hashes for an extracted image without re-decoding the PNG.
     * Phash uses the already-decoded BufferedImage (eliminates expensive PNG decode).
     * SHA-256 uses the saved file bytes (fast sequential read, no image decoding).
     * hashFile should be the original encoded file (JPEG/JP2) when available, else the PNG.
     */
    private HashResult computeImageHashInMemory(BufferedImage img, Path hashFile) {
        HashResult hr = new HashResult();
        hr.width     = img.getWidth();
        hr.height    = img.getHeight();
        hr.mode      = colorModelMode(img);
        hr.phash     = computePhash(img);
        hr.colorhash = computeColorHash(img);
        try {
            hr.sha256 = sha256(Files.readAllBytes(hashFile));
        } catch (IOException e) {
            hr.error = e.getMessage();
        }
        return hr;
    }

    private Map<String, HashResult> computeJavaHashes(List<Path> paths) throws IOException {
        Map<String, HashResult> results = new LinkedHashMap<>();
        for (Path path : paths) {
            String key = path.toAbsolutePath().normalize().toString();
            HashResult hr = new HashResult();
            hr.path = key;
            hr.filename = path.getFileName().toString();
            hr.exists = Files.exists(path);
            if (!Boolean.TRUE.equals(hr.exists)) {
                hr.error = "file does not exist";
                results.put(key, hr);
                continue;
            }
            try {
                hr.sha256 = sha256(Files.readAllBytes(path));
                BufferedImage img = ImageIO.read(path.toFile());
                if (img == null) throw new IOException("ImageIO could not decode " + path.getFileName());
                hr.width     = img.getWidth();
                hr.height    = img.getHeight();
                hr.mode      = colorModelMode(img);
                hr.phash     = computePhash(img);
                hr.colorhash = computeColorHash(img);
            } catch (Exception e) {
                hr.error = e.getMessage();
            }
            results.put(key, hr);
        }
        return results;
    }

    private static String colorModelMode(BufferedImage img) {
        return switch (img.getType()) {
            case BufferedImage.TYPE_INT_RGB,    BufferedImage.TYPE_3BYTE_BGR  -> "RGB";
            case BufferedImage.TYPE_INT_ARGB,   BufferedImage.TYPE_4BYTE_ABGR -> "RGBA";
            case BufferedImage.TYPE_BYTE_GRAY                                  -> "L";
            default                                                             -> "RGB";
        };
    }

    private static List<PhoneHit> extractPhonesFromText(
            String text, String source, Integer page, String context) {
        List<PhoneHit> hits = new ArrayList<>();
        if (text == null || text.isEmpty()) return hits;
        for (PhoneNumberMatch match : PHONE_UTIL.findNumbers(text, "US")) {
            hits.add(buildPhoneHit(match.rawString(), match.number(), source, page, context));
        }
        return hits;
    }

    private static PhoneHit buildPhoneHit(
            String raw, PhoneNumber number, String source, Integer page, String context) {
        PhoneHit hit = new PhoneHit();
        hit.raw = raw;
        hit.e164 = PHONE_UTIL.format(number, PhoneNumberFormat.E164);
        hit.nationalFormat = PHONE_UTIL.format(number, PhoneNumberFormat.NATIONAL);
        hit.countryCode = PHONE_UTIL.getRegionCodeForNumber(number);
        PhoneNumberUtil.PhoneNumberType numType = PHONE_UTIL.getNumberType(number);
        hit.lineType = phoneTypeToString(numType);
        if (numType == PhoneNumberUtil.PhoneNumberType.TOLL_FREE) {
            hit.geocode = "toll-free";
        } else {
            String geo = PHONE_GEOCODER.getDescriptionForNumber(number, Locale.ENGLISH);
            hit.geocode = (geo != null && !geo.isEmpty()) ? geo : null;
        }
        hit.source = source;
        hit.page = page;
        hit.context = context;
        return hit;
    }

    private static String phoneTypeToString(PhoneNumberUtil.PhoneNumberType type) {
        return switch (type) {
            case MOBILE              -> "mobile";
            case FIXED_LINE          -> "fixed_line";
            case FIXED_LINE_OR_MOBILE -> "fixed_line_or_mobile";
            case TOLL_FREE           -> "toll_free";
            case PREMIUM_RATE        -> "premium_rate";
            case SHARED_COST         -> "shared_cost";
            case VOIP                -> "voip";
            case PERSONAL_NUMBER     -> "personal_number";
            case PAGER               -> "pager";
            case UAN                 -> "uan";
            case VOICEMAIL           -> "voicemail";
            default                  -> "unknown";
        };
    }

    private List<EmailHit> extractMailtoAnnotations(PDDocument document, List<Integer> pagesToProcess) throws IOException {
        List<EmailHit> hits = new ArrayList<>();
        Set<Integer> pageSet = new HashSet<>(pagesToProcess);
        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            if (!pageSet.contains(pageNumber)) continue;
            for (PDAnnotation ann : page.getAnnotations()) {
                if (!(ann instanceof PDAnnotationLink link)) continue;
                PDAction action = link.getAction();
                if (!(action instanceof PDActionURI uriAction)) continue;
                String uri = uriAction.getURI();
                if (uri == null || !uri.toLowerCase(Locale.ROOT).startsWith("mailto:")) continue;
                String addr = uri.substring(7).split("[?&]")[0].trim();
                if (!addr.isEmpty() && addr.contains("@")) {
                    EmailHit hit = new EmailHit();
                    hit.email = addr.toLowerCase(Locale.ROOT);
                    hit.source = "annotation_mailto";
                    hit.page = pageNumber;
                    hits.add(hit);
                }
            }
        }
        return hits;
    }

    private List<EmailHit> extractEmailsFromText(List<PageTextData> pageTextData) {
        List<EmailHit> hits = new ArrayList<>();
        for (PageTextData ptd : pageTextData) {
            if (ptd.stripper() == null) continue;
            hits.addAll(extractEmailsFromCode(ptd.stripper().getCollectedText(), "text", ptd.page()));
        }
        return hits;
    }

    private static List<EmailHit> extractEmailsFromCode(String text, String source, Integer page) {
        List<EmailHit> hits = new ArrayList<>();
        if (text == null || text.isEmpty()) return hits;
        java.util.regex.Matcher m = EMAIL_PATTERN.matcher(text);
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        while (m.find()) {
            String addr = m.group(1).toLowerCase(Locale.ROOT);
            if (seen.add(addr + "|" + source + "|" + page)) {
                EmailHit hit = new EmailHit();
                hit.email = addr;
                hit.source = source;
                hit.page = page;
                hits.add(hit);
            }
        }
        return hits;
    }

    private static List<EmailHit> dedupeEmails(List<EmailHit> hits) {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        List<EmailHit> out = new ArrayList<>();
        for (EmailHit h : hits) {
            String key = h.email + "|" + h.source + "|" + h.page;
            if (seen.add(key)) out.add(h);
        }
        return out;
    }

    private List<PhoneHit> extractTelAnnotations(
            PDDocument document, List<Integer> pagesToProcess) throws IOException {
        List<PhoneHit> hits = new ArrayList<>();
        Set<Integer> pageSet = new HashSet<>(pagesToProcess);
        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            if (!pageSet.contains(pageNumber)) continue;
            for (PDAnnotation ann : page.getAnnotations()) {
                if (!(ann instanceof PDAnnotationLink link)) continue;
                PDAction action = link.getAction();
                if (!(action instanceof PDActionURI uriAction)) continue;
                String uri = uriAction.getURI();
                if (uri == null || !uri.toLowerCase(Locale.ROOT).startsWith("tel:")) continue;
                String phoneStr = uri.substring(4).trim();
                try {
                    PhoneNumber number = PHONE_UTIL.parse(phoneStr, "US");
                    if (PHONE_UTIL.isValidNumber(number))
                        hits.add(buildPhoneHit(phoneStr, number, "annotation_tel", pageNumber, null));
                } catch (NumberParseException ignored) {}
            }
        }
        return hits;
    }

    private List<PhoneHit> extractVisiblePhones(List<PageTextData> pageTextData) {
        List<PhoneHit> hits = new ArrayList<>();
        for (PageTextData ptd : pageTextData) {
            hits.addAll(extractPhonesFromText(
                ptd.stripper().getCollectedText(), "visible_text", ptd.page(), null));
        }
        return hits;
    }

    private static COSBase deref(COSBase base) {
        int maxHops = MAX_DEREF_HOPS;
        while (base instanceof COSObject obj && maxHops-- > 0) {
            base = obj.getObject();
        }
        if (base instanceof COSObject) {
            System.err.println("WARNING: deref() hit hop limit (" + MAX_DEREF_HOPS + "), returning unresolved object");
        }
        return base;
    }

    private static COSDictionary asDictionary(COSBase base) {
        base = deref(base);
        return base instanceof COSDictionary dict ? dict : null;
    }

    private static COSArray asArray(COSBase base) {
        base = deref(base);
        return base instanceof COSArray arr ? arr : null;
    }

    private static String cosToString(COSBase base) {
        base = deref(base);
        if (base instanceof COSString cs) {
            return cs.getString();
        }
        if (base instanceof COSName cn) {
            return cn.getName();
        }
        return String.valueOf(base);
    }

    private String writeTextArtifact(Path dir, String fileName, String content) throws IOException {
        Path out = uniquePath(dir, fileName);
        Files.writeString(out, content == null ? "" : content, StandardCharsets.UTF_8);
        return relativePath(out);
    }

    private static Path safeResolve(Path dir, String name) throws IOException {
        if (!dir.isAbsolute()) {
            throw new IllegalArgumentException("dir must be an absolute path: " + dir);
        }
        Path candidate = dir.resolve(name);
        Path normalized = candidate.normalize();
        if (!normalized.startsWith(dir.normalize())) {
            throw new IOException("Path traversal attempt detected: " + name);
        }
        return normalized;
    }

    private static Path uniquePath(Path dir, String fileName) throws IOException {
        Files.createDirectories(dir);
        String cleaned = sanitizeFileName(fileName);
        Path candidate = safeResolve(dir, cleaned);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String stem = cleaned;
        String ext = "";
        int dot = cleaned.lastIndexOf('.');
        if (dot > 0) {
            stem = cleaned.substring(0, dot);
            ext = cleaned.substring(dot);
        }
        for (int i = 1; i <= MAX_UNIQUE_PATH_ATTEMPTS; i++) {
            candidate = safeResolve(dir, stem + "-" + i + ext);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Too many collisions for '" + fileName + "' in " + dir + " (limit " + MAX_UNIQUE_PATH_ATTEMPTS + ")");
    }

    private static String sanitizeFileName(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        // First: strip path traversal by taking only the last safe path component
        String[] parts = normalized.split("[/\\\\]");
        String component = "artifact";
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].trim();
            if (!part.isEmpty() && !part.equals("..") && !part.equals(".")) {
                component = part;
                break;
            }
        }
        // Then: replace dangerous characters in the chosen component
        String cleaned = component.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", "_");
        cleaned = cleaned.replaceAll("_+", "_");
        if (cleaned.isBlank()) {
            cleaned = "artifact";
        }
        return cleaned.length() > 180 ? cleaned.substring(0, 180) : cleaned;
    }

    private static String normalizeUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        while (!value.isEmpty() && ").,]}>\"'".indexOf(value.charAt(value.length() - 1)) >= 0) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.toLowerCase(Locale.ROOT).startsWith("www.")) {
            value = "http://" + value;
        }
        if (value.toLowerCase(Locale.ROOT).startsWith("hxxp://")) {
            value = "http://" + value.substring(7);
        }
        if (value.toLowerCase(Locale.ROOT).startsWith("hxxps://")) {
            value = "https://" + value.substring(8);
        }
        return value;
    }

    private static String parseObfuscatedIp(String host) {
        if (host == null || host.isEmpty()) return null;
        String h = host.toLowerCase(Locale.ROOT).trim();
        if (h.startsWith("[")) return null;
        if (!h.contains(".")) {
            long val = -1;
            if (h.startsWith("0x")) {
                try { val = Long.parseUnsignedLong(h.substring(2), 16); } catch (NumberFormatException ignored) {}
            } else {
                try { val = Long.parseUnsignedLong(h); } catch (NumberFormatException ignored) {}
            }
            if (val >= 0 && val <= 0xFFFFFFFFL) return toQuad(val);
            return null;
        }
        String[] parts = h.split("\\.", -1);
        if (parts.length != 4) return null;
        int[] octets = new int[4];
        boolean anyNonDecimal = false;
        for (int i = 0; i < 4; i++) {
            String p = parts[i].trim();
            int val;
            if (p.startsWith("0x")) {
                try { val = Integer.parseInt(p.substring(2), 16); anyNonDecimal = true; }
                catch (NumberFormatException e) { return null; }
            } else if (p.startsWith("0") && p.length() > 1) {
                try { val = Integer.parseInt(p, 8); anyNonDecimal = true; }
                catch (NumberFormatException e) { return null; }
            } else {
                try { val = Integer.parseInt(p, 10); }
                catch (NumberFormatException e) { return null; }
            }
            if (val < 0 || val > 255) return null;
            octets[i] = val;
        }
        if (!anyNonDecimal) return null;
        return octets[0] + "." + octets[1] + "." + octets[2] + "." + octets[3];
    }

    private static String toQuad(long val) {
        return ((val >> 24) & 0xFF) + "." + ((val >> 16) & 0xFF) + "."
             + ((val >> 8) & 0xFF) + "." + (val & 0xFF);
    }

    private static boolean isPrivateIp(String quad) {
        if (quad == null) return false;
        String[] p = quad.split("\\.");
        if (p.length != 4) return false;
        try {
            int a = Integer.parseInt(p[0]);
            int b = Integer.parseInt(p[1]);
            int c = Integer.parseInt(p[2]);
            long ip = ((long) a << 24) | ((long) b << 16) | ((long) c << 8) | Integer.parseInt(p[3]);
            if ((ip & 0xFF000000L) == 0x0A000000L) return true;
            if ((ip & 0xFFF00000L) == 0xAC100000L) return true;
            if ((ip & 0xFFFF0000L) == 0xC0A80000L) return true;
            if ((ip & 0xFF000000L) == 0x7F000000L) return true;
            if ((ip & 0xFFFF0000L) == 0xA9FE0000L) return true;
            if ((ip & 0xFFC00000L) == 0x64400000L) return true;
            return false;
        } catch (NumberFormatException e) { return false; }
    }

    private static boolean isLikelyIpAddress(String host) {
        return host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    private static List<UrlHit> extractUrlsFromCode(String code, String source, Integer page) {
        List<UrlHit> hits = new ArrayList<>();
        if (code == null || code.isEmpty()) return hits;
        Matcher m = URL_PATTERN.matcher(code);
        while (m.find()) {
            String raw = m.group(1);
            UrlHit hit = new UrlHit();
            hit.url = normalizeUrl(raw);
            hit.displayText = raw;
            hit.source = source;
            hit.page = page == null ? 0 : page;
            enrichUrlHit(hit);
            hits.add(hit);
        }
        return hits;
    }

    private static double unionArea(List<PDRectangle> rects) {
        if (rects.isEmpty()) return 0.0;
        float[] xs = new float[rects.size() * 2];
        int xi = 0;
        for (PDRectangle r : rects) { xs[xi++] = r.getLowerLeftX(); xs[xi++] = r.getUpperRightX(); }
        Arrays.sort(xs, 0, xi);
        float[] uniqueXs = Arrays.copyOf(xs, xi);
        double area = 0.0;
        for (int i = 0; i < uniqueXs.length - 1; i++) {
            float x0 = uniqueXs[i], x1 = uniqueXs[i + 1];
            if (x0 >= x1) continue;
            float stripWidth = x1 - x0;
            List<float[]> yIntervals = new ArrayList<>();
            for (PDRectangle r : rects) {
                if (r.getLowerLeftX() <= x0 && r.getUpperRightX() >= x1)
                    yIntervals.add(new float[]{r.getLowerLeftY(), r.getUpperRightY()});
            }
            if (yIntervals.isEmpty()) continue;
            yIntervals.sort((a, b) -> Float.compare(a[0], b[0]));
            float yUnion = 0, curY0 = yIntervals.get(0)[0], curY1 = yIntervals.get(0)[1];
            for (int j = 1; j < yIntervals.size(); j++) {
                float[] iv = yIntervals.get(j);
                if (iv[0] <= curY1) curY1 = Math.max(curY1, iv[1]);
                else { yUnion += curY1 - curY0; curY0 = iv[0]; curY1 = iv[1]; }
            }
            yUnion += curY1 - curY0;
            area += stripWidth * yUnion;
        }
        return area;
    }

    private List<PageLinkStats> computePageLinkStats(
            PDDocument document, List<Integer> pagesToProcess) throws IOException {
        List<PageLinkStats> stats = new ArrayList<>();
        Set<Integer> pageSet = new HashSet<>(pagesToProcess);
        int pageNumber = 0;
        for (PDPage page : document.getPages()) {
            pageNumber++;
            if (!pageSet.contains(pageNumber)) continue;
            PDRectangle mediaBox = page.getMediaBox();
            if (mediaBox == null) continue;
            List<PDRectangle> linkRects = new ArrayList<>();
            for (PDAnnotation ann : page.getAnnotations()) {
                if (ann instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI) {
                    PDRectangle r = link.getRectangle();
                    if (r != null) linkRects.add(r);
                }
            }
            PageLinkStats s = new PageLinkStats();
            s.page = pageNumber;
            s.widthPt = mediaBox.getWidth();
            s.heightPt = mediaBox.getHeight();
            double pageArea = s.widthPt * s.heightPt;
            s.linkAnnotationCoverage = pageArea > 0 ? unionArea(linkRects) / pageArea : 0.0;
            stats.add(s);
        }
        return stats;
    }

    /** Extracts annotation URIs from every page with full bounds, coverage, and enrichment. */
    private List<UrlHit> extractRevisionUrls(PDDocument doc) {
        List<UrlHit> urls = new ArrayList<>();
        try {
            int pageNumber = 0;
            for (PDPage page : doc.getPages()) {
                pageNumber++;
                PDRectangle mediaBox = page.getMediaBox();
                for (PDAnnotation ann : page.getAnnotations()) {
                    if (!(ann instanceof PDAnnotationLink link)) continue;
                    PDAction action = link.getAction();
                    if (!(action instanceof PDActionURI uriAction)) continue;
                    String uri = uriAction.getURI();
                    if (uri == null || uri.isBlank()) continue;
                    UrlHit hit = new UrlHit();
                    hit.page = pageNumber;
                    hit.url = normalizeUrl(uri);
                    hit.source = "annotation_uri";
                    hit.annotationAlreadyPresent = true;
                    PDRectangle rect = link.getRectangle();
                    hit.bounds = rect == null ? null : BoundingBox.fromPdfRect(rect);
                    if (rect != null && mediaBox != null) {
                        double pageArea = (double) mediaBox.getWidth() * mediaBox.getHeight();
                        double annotArea = (double) rect.getWidth() * rect.getHeight();
                        if (pageArea > 0) hit.pageCoverageRatio = annotArea / pageArea;
                    }
                    enrichUrlHit(hit);
                    urls.add(hit);
                }
            }
        } catch (IOException ignored) {}
        return urls;
    }

    /**
     * Returns byte offsets just past each %%EOF marker in the PDF, in document order.
     * Each entry marks the end of one revision in an incrementally-updated PDF.
     */
    private static List<Integer> findEofBoundaries(byte[] pdf) {
        List<Integer> boundaries = new ArrayList<>();
        for (int i = 0; i <= pdf.length - 5; i++) {
            if (pdf[i] == '%' && pdf[i+1] == '%' && pdf[i+2] == 'E' && pdf[i+3] == 'O' && pdf[i+4] == 'F') {
                int end = i + 5;
                while (end < pdf.length && (pdf[end] == '\r' || pdf[end] == '\n')) end++;
                boundaries.add(end);
                i = end - 1; // skip past this marker
            }
        }
        return boundaries;
    }

    /**
     * Scans raw PDF bytes for stream objects whose declared /Length doesn't match the actual
     * byte count between the stream data start and the matching endstream keyword.
     * Skips XRef streams (/Type/XRef). Skips streams with indirect /Length references.
     * For filtered streams, locates endstream via declared length ±tolerance before scanning.
     */
    private static List<StreamLengthHit> checkStreamLengths(byte[] pdf) {
        List<StreamLengthHit> hits = new ArrayList<>();
        byte[] streamKw    = "stream".getBytes(StandardCharsets.ISO_8859_1);
        byte[] endstreamKw = "endstream".getBytes(StandardCharsets.ISO_8859_1);

        for (int i = 0; i <= pdf.length - streamKw.length; i++) {
            if (!regionMatches(pdf, i, streamKw)) continue;
            // Exclude "endstream" — the 's' in "endstream" would match "stream" at i-3
            if (i >= 3 && pdf[i-1] == 'd' && pdf[i-2] == 'n' && pdf[i-3] == 'e') continue;
            // PDF spec: "stream" must be followed by \r\n or \n exactly
            int eolLen;
            int afterKw = i + streamKw.length;
            if (afterKw < pdf.length && pdf[afterKw] == '\n') {
                eolLen = 1;
            } else if (afterKw + 1 < pdf.length && pdf[afterKw] == '\r' && pdf[afterKw+1] == '\n') {
                eolLen = 2;
            } else {
                continue;
            }
            int streamDataStart = afterKw + eolLen;

            // Walk backward from i to find ">>" ending the stream dictionary
            int dictEnd = -1;
            for (int j = i - 1; j >= Math.max(0, i - 65536); j--) {
                if (j + 1 < pdf.length && pdf[j] == '>' && pdf[j+1] == '>') { dictEnd = j + 2; break; }
            }
            if (dictEnd < 0) continue;

            // Find matching "<<" (accounting for nesting)
            int dictStart = -1, depth = 0;
            for (int j = dictEnd - 2; j >= Math.max(0, dictEnd - 65536); j--) {
                if (j + 1 < pdf.length && pdf[j] == '>' && pdf[j+1] == '>') { depth++; j--; continue; }
                if (j + 1 < pdf.length && pdf[j] == '<' && pdf[j+1] == '<') {
                    if (depth == 0) { dictStart = j; break; }
                    depth--; j--;
                }
            }
            if (dictStart < 0) continue;

            String dictStr = new String(pdf, dictStart + 2, dictEnd - 2 - (dictStart + 2), StandardCharsets.ISO_8859_1);

            // Skip XRef streams
            if (dictStr.replace(" ", "").contains("/Type/XRef")) continue;

            // Extract declared /Length — skip if indirect reference (won't match \d+)
            Long declaredLength = null;
            java.util.regex.Matcher lm = java.util.regex.Pattern.compile("/Length\\s+(\\d+)").matcher(dictStr);
            if (lm.find()) {
                try { declaredLength = Long.parseLong(lm.group(1)); } catch (NumberFormatException ignored) {}
            }
            if (declaredLength == null) continue;

            boolean hasFilter = dictStr.contains("/Filter");

            // Extract object number by scanning backward for "N G obj"
            Integer objectNumber = null;
            byte[] objKw = " obj".getBytes(StandardCharsets.ISO_8859_1);
            outer:
            for (int j = dictStart - 1; j >= Math.max(0, dictStart - 512); j--) {
                if (!regionMatches(pdf, j, objKw)) continue;
                // Found " obj" — scan back for "objnum gen" prefix
                int k = j - 1;
                while (k >= 0 && isWs(pdf[k])) k--;
                while (k >= 0 && pdf[k] >= '0' && pdf[k] <= '9') k--;
                while (k >= 0 && isWs(pdf[k])) k--;
                int numEnd = k;
                while (numEnd >= 0 && pdf[numEnd] >= '0' && pdf[numEnd] <= '9') numEnd--;
                if (numEnd < k) {
                    StringBuilder sb2 = new StringBuilder();
                    for (int m = numEnd + 1; m <= k; m++) sb2.append((char)pdf[m]);
                    try { objectNumber = Integer.parseInt(sb2.toString()); } catch (NumberFormatException ignored) {}
                }
                break;
            }

            // Locate endstream
            int endstreamPos = -1;
            if (hasFilter) {
                // For filtered streams trust declared length to locate endstream
                int expectedEnd = (int) Math.min((long)streamDataStart + declaredLength, pdf.length);
                int ep = expectedEnd;
                if (ep < pdf.length && pdf[ep] == '\r') ep++;
                if (ep < pdf.length && pdf[ep] == '\n') ep++;
                if (ep + endstreamKw.length <= pdf.length && regionMatches(pdf, ep, endstreamKw)) {
                    endstreamPos = ep;
                } else {
                    // Scan ±128 bytes around expected position
                    int scanFrom = Math.max(streamDataStart, expectedEnd - 128);
                    int scanTo   = Math.min(pdf.length, expectedEnd + 130 + endstreamKw.length);
                    for (int j = scanFrom; j + endstreamKw.length <= scanTo; j++) {
                        if (regionMatches(pdf, j, endstreamKw)) { endstreamPos = j; break; }
                    }
                }
            } else {
                // Unfiltered: scan forward up to declared+64KB
                int searchLimit = (int) Math.min((long)streamDataStart + declaredLength + 65536L, pdf.length);
                for (int j = streamDataStart; j + endstreamKw.length <= searchLimit; j++) {
                    if (regionMatches(pdf, j, endstreamKw)) { endstreamPos = j; break; }
                }
            }

            if (endstreamPos < 0) {
                StreamLengthHit hit = new StreamLengthHit();
                hit.streamOffset = i; hit.objectNumber = objectNumber;
                hit.declaredLength = declaredLength; hit.anomalyType = "missing_endstream";
                hits.add(hit);
                continue;
            }

            // Strip optional EOL before endstream keyword (per spec)
            int actualEnd = endstreamPos;
            if (actualEnd > streamDataStart && pdf[actualEnd-1] == '\n') actualEnd--;
            if (actualEnd > streamDataStart && pdf[actualEnd-1] == '\r') actualEnd--;
            long actualLength = actualEnd - streamDataStart;
            long delta = actualLength - declaredLength;
            if (Math.abs(delta) <= 1) continue; // within tolerance

            StreamLengthHit hit = new StreamLengthHit();
            hit.streamOffset = i; hit.objectNumber = objectNumber;
            hit.declaredLength = declaredLength; hit.actualLength = actualLength;
            hit.delta = delta; hit.anomalyType = delta < 0 ? "truncated" : "overflow";
            hits.add(hit);
        }
        return hits;
    }

    /**
     * Checks raw PDF bytes for structural anomalies: header offset, invalid version,
     * missing/malformed binary comment, missing %%EOF, and significant data after %%EOF.
     *
     * @param originalBytes  unsliced file bytes (used for header_offset check)
     * @param headerOffset   byte offset of %PDF in originalBytes (from findPdfHeader)
     * @param canonicalBytes post-slice bytes (always starts at %PDF)
     */
    private static List<StructuralAnomalyHit> checkStructuralAnomalies(
            byte[] originalBytes, int headerOffset, byte[] canonicalBytes) {
        List<StructuralAnomalyHit> hits = new ArrayList<>();

        // 1. Header not at byte 0
        if (headerOffset > 0) {
            StructuralAnomalyHit h = new StructuralAnomalyHit();
            h.type = "header_offset";
            h.detail = "%PDF header found at byte " + headerOffset + ", not at file start";
            h.offset = (long) headerOffset;
            hits.add(h);
        }

        // 2. Invalid PDF version (after "%PDF-")
        if (canonicalBytes.length >= 8) {
            int vEnd = 5;
            while (vEnd < canonicalBytes.length && canonicalBytes[vEnd] != '\r' && canonicalBytes[vEnd] != '\n') vEnd++;
            String version = new String(canonicalBytes, 5, vEnd - 5, StandardCharsets.ISO_8859_1).trim();
            if (!version.matches("[12]\\.\\d")) {
                StructuralAnomalyHit h = new StructuralAnomalyHit();
                h.type = "invalid_version";
                h.detail = "PDF version '" + version + "' is not a recognized version (expected 1.x or 2.x)";
                hits.add(h);
            }
        }

        // 3. Missing or malformed binary comment (line immediately after header line)
        int firstNl = 0;
        while (firstNl < canonicalBytes.length && canonicalBytes[firstNl] != '\n') firstNl++;
        firstNl++; // move past \n
        if (firstNl < canonicalBytes.length) {
            if (canonicalBytes[firstNl] != '%') {
                StructuralAnomalyHit h = new StructuralAnomalyHit();
                h.type = "missing_binary_comment";
                h.detail = "No binary comment line (%%<4 high-byte chars>) found after PDF header";
                hits.add(h);
            } else {
                int highCount = 0;
                for (int i = firstNl + 1; i < Math.min(firstNl + 5, canonicalBytes.length); i++) {
                    if ((canonicalBytes[i] & 0xFF) >= 128) highCount++;
                }
                if (highCount < 4) {
                    StructuralAnomalyHit h = new StructuralAnomalyHit();
                    h.type = "malformed_binary_comment";
                    h.detail = "Binary comment has only " + highCount + "/4 required high-byte chars";
                    hits.add(h);
                }
            }
        }

        // 4. Missing %%EOF / 5. Data after %%EOF
        List<Integer> eofBounds = findEofBoundaries(canonicalBytes);
        if (eofBounds.isEmpty()) {
            StructuralAnomalyHit h = new StructuralAnomalyHit();
            h.type = "missing_eof";
            h.detail = "No %%EOF marker found in file";
            hits.add(h);
        } else {
            int lastEofEnd = eofBounds.get(eofBounds.size() - 1);
            long trailing = canonicalBytes.length - lastEofEnd;
            if (trailing > 64) {
                StructuralAnomalyHit h = new StructuralAnomalyHit();
                h.type = "data_after_eof";
                h.detail = trailing + " bytes of data after last %%EOF (may indicate appended content or incremental update)";
                h.offset = (long) lastEofEnd;
                h.trailingBytes = trailing;
                hits.add(h);
            }
        }
        return hits;
    }

    /**
     * Checks already-extracted DocumentInfo for metadata spoofing indicators:
     * pre-1993 dates, future dates, creation-after-modification, and tool mismatches.
     */
    private static List<MetadataSpoofingHit> checkMetadataSpoofing(DocumentInfo di) {
        List<MetadataSpoofingHit> hits = new ArrayList<>();
        Instant createdInstant = null, modifiedInstant = null;
        if (di.creationDate != null) {
            try { createdInstant = Instant.parse(di.creationDate); } catch (Exception ignored) {}
        }
        if (di.modificationDate != null) {
            try { modifiedInstant = Instant.parse(di.modificationDate); } catch (Exception ignored) {}
        }
        Instant now = Instant.now();
        Instant pdfBirthday = Instant.parse("1993-06-01T00:00:00Z");

        if (createdInstant != null && createdInstant.isBefore(pdfBirthday)) {
            MetadataSpoofingHit h = new MetadataSpoofingHit();
            h.type = "predates_pdf_format";
            h.detail = "Creation date " + di.creationDate + " predates the PDF format (released June 1993)";
            hits.add(h);
        }
        if (createdInstant != null && createdInstant.isAfter(now)) {
            MetadataSpoofingHit h = new MetadataSpoofingHit();
            h.type = "future_creation_date";
            h.detail = "Creation date " + di.creationDate + " is in the future";
            hits.add(h);
        }
        if (modifiedInstant != null && modifiedInstant.isAfter(now)) {
            MetadataSpoofingHit h = new MetadataSpoofingHit();
            h.type = "future_modification_date";
            h.detail = "Modification date " + di.modificationDate + " is in the future";
            hits.add(h);
        }
        if (di.daysBetweenCreatedAndModified != null && di.daysBetweenCreatedAndModified < 0) {
            MetadataSpoofingHit h = new MetadataSpoofingHit();
            h.type = "creation_after_modification";
            h.detail = "Creation date is " + Math.abs(di.daysBetweenCreatedAndModified)
                + " day(s) after modification date";
            hits.add(h);
        }
        // Tool mismatch: conservative — only flag clear contradictions
        if (di.creator != null && di.producer != null) {
            String c = di.creator.toLowerCase(Locale.ROOT);
            String p = di.producer.toLowerCase(Locale.ROOT);
            boolean cIsWord = c.contains("microsoft word") || c.contains("ms word");
            boolean cIsLO   = c.contains("libreoffice") || c.contains("openoffice");
            boolean pIsLO   = p.contains("libreoffice") || p.contains("openoffice");
            boolean pIsWord = p.contains("microsoft word");
            if ((cIsWord && pIsLO) || (cIsLO && pIsWord)) {
                MetadataSpoofingHit h = new MetadataSpoofingHit();
                h.type = "tool_mismatch";
                h.detail = "Creator '" + di.creator + "' contradicts producer '" + di.producer + "'";
                hits.add(h);
            }
        }
        return hits;
    }

    private static RevisionTimeline buildRevisionTimeline(byte[] pdf) {
        List<Long> xrefOffsets = findXrefChain(pdf);
        if (xrefOffsets.size() <= 1) return RevisionTimeline.SINGLE;
        List<Long> ordered = new ArrayList<>(xrefOffsets);
        Collections.reverse(ordered);
        int totalRevisions = ordered.size();
        Map<Integer, List<RevisionEntry>> timelines = new LinkedHashMap<>();
        Map<Integer, Integer> firstSeen = new HashMap<>();
        for (int rev = 1; rev <= totalRevisions; rev++) {
            for (int objNum : parseTraditionalXrefObjects(pdf, ordered.get(rev - 1))) {
                RevisionEntry entry = new RevisionEntry();
                entry.revision = rev;
                entry.objectNumber = objNum;
                entry.event = firstSeen.containsKey(objNum) ? "modified" : "added";
                firstSeen.putIfAbsent(objNum, rev);
                timelines.computeIfAbsent(objNum, k -> new ArrayList<>()).add(entry);
            }
        }
        return new RevisionTimeline(totalRevisions, timelines);
    }

    private static List<Long> findXrefChain(byte[] pdf) {
        byte[] needle = "startxref".getBytes(StandardCharsets.ISO_8859_1);
        long lastOffset = -1;
        // Search the entire file backward (some PDFs have large null-byte or garbage padding
        // after %%EOF, so the startxref keyword may be far from the physical end).
        for (int i = pdf.length - needle.length; i >= 0; i--) {
            if (regionMatches(pdf, i, needle)) { lastOffset = readNextLong(pdf, i + needle.length); break; }
        }
        if (lastOffset < 0) return Collections.emptyList();
        // startxref beyond file end: reference calls parse_xref_table(offset) which fails,
        // then internally calls run_regex_xref_scan() → fallback scan runs.
        // Return empty list so fallback is allowed (same as empty chain).
        if (lastOffset >= pdf.length) return new ArrayList<>();
        // BFS: follow /Prev and /XRefStm from each xref (traditional table or xref stream).
        // Collect in order visited (newest first) for "oldest-wins" overwrite semantics.
        List<Long> offsets = new ArrayList<>();
        Set<Long> visited = new HashSet<>();
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        queue.add(lastOffset);
        while (!queue.isEmpty()) {
            long current = queue.poll();
            if (current < 0 || current >= pdf.length || !visited.add(current)) continue;
            offsets.add(current);
            for (long next : readXrefNextOffsets(pdf, current)) {
                if (!visited.contains(next)) queue.add(next);
            }
        }
        return offsets;
    }

    /**
     * Full-file scan fallback for corrupt PDFs where the startxref offset is invalid.
     * Scans the entire file for valid xref table blocks (looking for ws+"xref"+ws) and
     * XRef stream objects (N G obj ... /Type/XRef). Mirrors the reference's
     * run_regex_xref_scan(). Returns offsets to feed to pdfHashAddXrefEntries.
     */
    private static List<Long> findXrefByFullScan(byte[] pdf) {
        List<Long> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        byte[] xrefKw = "xref".getBytes(StandardCharsets.ISO_8859_1);
        byte[] trailerKw = "trailer".getBytes(StandardCharsets.ISO_8859_1);
        byte[] objKw = " obj".getBytes(StandardCharsets.ISO_8859_1);
        byte[] xrefTypeKw = "/XRef".getBytes(StandardCharsets.ISO_8859_1);
        // Traditional xref: reference uses [\s]xref[\s]+ ... trailer (whitespace before AND after xref)
        for (int i = 1; i < pdf.length - xrefKw.length - 1; i++) {
            if (isWs(pdf[i - 1]) && regionMatches(pdf, i, xrefKw) && isWs(pdf[i + 4])) {
                // Confirm there's a "trailer" within 500KB (large PDFs)
                int limit = Math.min(pdf.length, i + 524288);
                boolean hasTrailer = false;
                for (int j = i + 4; j < limit - trailerKw.length; j++) {
                    if (regionMatches(pdf, j, trailerKw)) { hasTrailer = true; break; }
                }
                if (hasTrailer && seen.add((long) i)) result.add((long) i);
            }
        }
        // XRef stream: reference uses [0-9]+ [0-9]+ obj[\s]+ .*? /XRef WITHOUT DOTALL.
        // That means '.' does not match '\n', so the /XRef search is bounded by the first
        // '\n' after the WS following "obj".
        for (int i = 0; i < pdf.length - objKw.length; i++) {
            if (regionMatches(pdf, i, objKw)) {
                // Require at least one whitespace after "obj" (reference: obj[\s]+)
                int afterObj = i + 4;
                if (afterObj >= pdf.length || !isWs(pdf[afterObj])) continue;
                // Scan back for start of "N G obj" (object number)
                int start = i - 1;
                while (start >= 0 && isWs(pdf[start])) start--;
                while (start >= 0 && pdf[start] >= '0' && pdf[start] <= '9') start--;
                while (start >= 0 && isWs(pdf[start])) start--;
                while (start >= 0 && pdf[start] >= '0' && pdf[start] <= '9') start--;
                if (start >= 0 && isWs(pdf[start])) {
                    long objStart = start + 1;
                    // Replicate reference's xrefstream_regex without DOTALL:
                    // skip WS (including \n) after "obj", then find first \n as search limit.
                    int wsEnd = afterObj;
                    while (wsEnd < pdf.length && isWs(pdf[wsEnd])) wsEnd++;
                    int nextNl = wsEnd;
                    while (nextNl < pdf.length && pdf[nextNl] != '\n') nextNl++;
                    for (int j = wsEnd; j + xrefTypeKw.length <= nextNl; j++) {
                        if (regionMatches(pdf, j, xrefTypeKw)) {
                            if (seen.add(objStart)) result.add(objStart);
                            break;
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * Returns true if any "startxref 0" (not "startxref 0NNN") block exists in the file.
     * This indicates a linearized PDF where the reference's parse_xref_table(0) fails,
     * triggering run_regex_xref_scan() which re-processes all XRef streams ascending.
     */
    private static boolean detectStartxrefZero(byte[] pdf) {
        byte[] needle = "startxref".getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i <= pdf.length - needle.length; i++) {
            if (!regionMatches(pdf, i, needle)) continue;
            int p = i + needle.length;
            if (p >= pdf.length || !isWs(pdf[p])) continue;
            while (p < pdf.length && isWs(pdf[p])) p++;
            if (p < pdf.length && pdf[p] == '0') {
                int q = p + 1;
                if (q >= pdf.length || !(pdf[q] >= '0' && pdf[q] <= '9')) {
                    if (q < pdf.length && isWs(pdf[q])) return true;
                }
            }
        }
        return false;
    }

    /**
     * Builds the start_list replicating reference's trailer_process.
     * Scans ALL "trailer...%%EOF" and "startxref...%%EOF" blocks, extracts all
     * startxref + /Prev + /XRefStm values in reverse file order, then deduplicates.
     * Returns null only when no blocks exist at all (full-file fallback needed).
     */
    private static List<Long> buildPdfHashStartList(byte[] pdf) {
        List<long[]> p1Matches = new ArrayList<>();  // trailer...%%EOF
        {
            byte[] trailerKw = "trailer".getBytes(StandardCharsets.ISO_8859_1);
            byte[] eofKw = "%%EOF".getBytes(StandardCharsets.ISO_8859_1);
            int pos = 0;
            while (pos <= pdf.length - trailerKw.length) {
                int tPos = -1;
                for (int i = pos; i <= pdf.length - trailerKw.length; i++) {
                    if (regionMatches(pdf, i, trailerKw)) { tPos = i; break; }
                }
                if (tPos < 0) break;
                int eofPos = -1;
                for (int i = tPos + trailerKw.length; i <= pdf.length - eofKw.length; i++) {
                    if (regionMatches(pdf, i, eofKw)) { eofPos = i; break; }
                }
                if (eofPos < 0) break;
                p1Matches.add(new long[]{tPos, eofPos + eofKw.length});
                pos = eofPos + eofKw.length;
            }
        }
        List<long[]> p2Matches = new ArrayList<>();  // startxref...%%EOF
        {
            byte[] sxKw = "startxref".getBytes(StandardCharsets.ISO_8859_1);
            byte[] eofKw = "%%EOF".getBytes(StandardCharsets.ISO_8859_1);
            int pos = 0;
            while (pos <= pdf.length - sxKw.length) {
                int sxPos = -1;
                for (int i = pos; i <= pdf.length - sxKw.length; i++) {
                    if (regionMatches(pdf, i, sxKw)) { sxPos = i; break; }
                }
                if (sxPos < 0) break;
                int eofPos = -1;
                for (int i = sxPos + sxKw.length; i <= pdf.length - eofKw.length; i++) {
                    if (regionMatches(pdf, i, eofKw)) { eofPos = i; break; }
                }
                if (eofPos < 0) break;
                p2Matches.add(new long[]{sxPos, eofPos + eofKw.length});
                pos = eofPos + eofKw.length;
            }
        }
        if (p1Matches.isEmpty() && p2Matches.isEmpty()) return null;

        List<long[]> allMatches = new ArrayList<>();
        allMatches.addAll(p1Matches);
        allMatches.addAll(p2Matches);

        List<Long> startList = new ArrayList<>();

        for (int i = allMatches.size() - 1; i >= 0; i--) {
            long[] m = allMatches.get(i);
            String matchStr = new String(pdf, (int) m[0], (int)(m[1] - m[0]), StandardCharsets.ISO_8859_1);
            Matcher sxm = HASH_SX_PAT.matcher(matchStr);
            if (sxm.find()) {
                try { startList.add(Long.parseLong(sxm.group(1))); } catch (Exception ignored) {}
            }
            Matcher pm = HASH_PREV_PAT.matcher(matchStr);
            if (pm.find()) {
                try { startList.add(Long.parseLong(pm.group(1))); } catch (Exception ignored) {}
            }
            Matcher xm = HASH_XREFSTM_PAT.matcher(matchStr);
            if (xm.find()) {
                try { startList.add(Long.parseLong(xm.group(1))); } catch (Exception ignored) {}
            }
        }

        // uniq_list: order-preserving dedup
        List<Long> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (long v : startList) { if (seen.add(v)) result.add(v); }
        return result.isEmpty() ? null : result;
    }

    /**
     * Extract /Prev offset from an XRef stream at xrefOffset.
     * Reference's parse_xref_table adds /Prev from XRef streams to start_list dynamically.
     * Returns > 0 if found, -1 otherwise.
     */
    private static long extractXrefStreamPrevOffset(byte[] pdf, long xrefOffset) {
        int pos = (int) xrefOffset;
        if (pos < 0 || pos >= pdf.length) return -1;
        while (pos < pdf.length && isWs(pdf[pos])) pos++;
        if (regionMatches(pdf, pos, "xref".getBytes(StandardCharsets.ISO_8859_1))) return -1;
        // Skip "N G obj"
        while (pos < pdf.length && (pdf[pos] >= '0' && pdf[pos] <= '9')) pos++;
        while (pos < pdf.length && isWs(pdf[pos])) pos++;
        while (pos < pdf.length && (pdf[pos] >= '0' && pdf[pos] <= '9')) pos++;
        while (pos < pdf.length && isWs(pdf[pos])) pos++;
        if (pos + 3 > pdf.length || pdf[pos] != 'o' || pdf[pos+1] != 'b' || pdf[pos+2] != 'j') return -1;
        pos += 3;
        while (pos < pdf.length && isWs(pdf[pos])) pos++;
        if (pos + 2 > pdf.length || pdf[pos] != '<' || pdf[pos+1] != '<') return -1;
        int dictStart = pos + 2;
        int depth = 1, dictEnd = -1, s = dictStart;
        int dictLimit = Math.min(dictStart + 32768, pdf.length);
        while (s < dictLimit - 1) {
            if (pdf[s] == '<' && pdf[s+1] == '<') { depth++; s += 2; }
            else if (pdf[s] == '>' && pdf[s+1] == '>') { depth--; if (depth == 0) { dictEnd = s; break; } s += 2; }
            else s++;
        }
        if (dictEnd < 0) return -1;
        String dictStr = new String(pdf, dictStart, dictEnd - dictStart, StandardCharsets.ISO_8859_1);
        if (!dictStr.replace(" ", "").contains("/Type/XRef")) return -1;
        java.util.regex.Matcher pm = java.util.regex.Pattern
            .compile("/Prev[\\x00\\x09\\x0a\\x0c\\x0d ]+([0-9]+)").matcher(dictStr);
        if (!pm.find()) return -1;
        return Long.parseLong(pm.group(1));
    }

    /**
     * Wrapper around pdfHashAddXrefEntries that returns true if content was recognized
     * as a valid XRef structure, false if unrecognizable (triggering fallback scan).
     */
    private static boolean pdfHashAddXrefEntriesTracked(byte[] pdf, long xrefOffset, Map<Integer, Long> map) {
        int pos = (int) xrefOffset;
        if (pos < 0 || pos >= pdf.length) return false;
        while (pos < pdf.length && isWs(pdf[pos])) pos++;
        // Traditional xref table: always recognized
        if (regionMatches(pdf, pos, "xref".getBytes(StandardCharsets.ISO_8859_1))) {
            pdfHashAddXrefEntries(pdf, xrefOffset, map);
            return true;
        }
        // Must be N G obj WS <<  to be recognized as an XRef stream object
        int p2 = pos;
        while (p2 < pdf.length && (pdf[p2] >= '0' && pdf[p2] <= '9')) p2++;
        while (p2 < pdf.length && isWs(pdf[p2])) p2++;
        while (p2 < pdf.length && (pdf[p2] >= '0' && pdf[p2] <= '9')) p2++;
        while (p2 < pdf.length && isWs(pdf[p2])) p2++;
        if (p2 + 3 > pdf.length || pdf[p2] != 'o' || pdf[p2+1] != 'b' || pdf[p2+2] != 'j') return false;
        p2 += 3;
        if (p2 >= pdf.length || !isWs(pdf[p2])) return false;
        while (p2 < pdf.length && isWs(pdf[p2])) p2++;
        if (p2 + 2 > pdf.length || pdf[p2] != '<' || pdf[p2+1] != '<') return false;
        // Recognized as an object dict; delegate to pdfHashAddXrefEntries for processing
        pdfHashAddXrefEntries(pdf, xrefOffset, map);
        return true;
    }

    /**
     * Returns all /Prev and /XRefStm offsets reachable from this xref position.
     * Handles both traditional xref tables (look for "trailer" keyword) and
     * cross-reference streams (read /Prev from stream dictionary).
     */
    private static List<Long> readXrefNextOffsets(byte[] pdf, long xrefOffset) {
        List<Long> result = new ArrayList<>();
        int pos = (int) xrefOffset;
        if (pos < 0 || pos >= pdf.length) return result;
        while (pos < pdf.length && isWs(pdf[pos])) pos++;

        if (regionMatches(pdf, pos, "xref".getBytes(StandardCharsets.ISO_8859_1))) {
            // Traditional xref table: search for "trailer" then /Prev and /XRefStm
            byte[] trailerKey = "trailer".getBytes(StandardCharsets.ISO_8859_1);
            int limit = Math.min(pdf.length, pos + 65536);
            for (int i = pos; i < limit - trailerKey.length; i++) {
                if (regionMatches(pdf, i, trailerKey)) {
                    int searchEnd = Math.min(pdf.length, i + 2048);
                    String trailerStr = new String(pdf, i, searchEnd - i, StandardCharsets.ISO_8859_1);
                    Matcher m = HASH_PREV_PAT.matcher(trailerStr);
                    if (m.find()) {
                        long v = Long.parseLong(m.group(1));
                        if (v > 0 && v < pdf.length) result.add(v);
                    }
                    m = HASH_XREFSTM_PAT.matcher(trailerStr);
                    if (m.find()) {
                        long v = Long.parseLong(m.group(1));
                        if (v > 0 && v < pdf.length) result.add(v);
                    }
                    break;
                }
            }
        } else {
            // Cross-reference stream: /Prev is in the stream dictionary
            // Skip "N G obj"
            while (pos < pdf.length && (pdf[pos] >= '0' && pdf[pos] <= '9')) pos++;
            while (pos < pdf.length && isWs(pdf[pos])) pos++;
            while (pos < pdf.length && (pdf[pos] >= '0' && pdf[pos] <= '9')) pos++;
            while (pos < pdf.length && isWs(pdf[pos])) pos++;
            if (pos + 3 > pdf.length || pdf[pos] != 'o' || pdf[pos+1] != 'b' || pdf[pos+2] != 'j') return result;
            pos += 3;
            while (pos < pdf.length && isWs(pdf[pos])) pos++;
            if (pos + 2 > pdf.length || pdf[pos] != '<' || pdf[pos+1] != '<') return result;
            int dictStart = pos + 2;
            int depth = 1, dictEnd = -1, s = dictStart;
            int dictLimit = Math.min(dictStart + 32768, pdf.length);
            while (s < dictLimit - 1) {
                if (pdf[s] == '<' && pdf[s+1] == '<') { depth++; s += 2; }
                else if (pdf[s] == '>' && pdf[s+1] == '>') {
                    depth--; if (depth == 0) { dictEnd = s; break; } s += 2;
                } else s++;
            }
            if (dictEnd < 0) return result;
            String dictStr = new String(pdf, dictStart, dictEnd - dictStart, StandardCharsets.ISO_8859_1);
            Matcher m = HASH_PREV_PAT.matcher(dictStr);
            if (m.find()) {
                long v = Long.parseLong(m.group(1));
                if (v > 0 && v < pdf.length) result.add(v);
            }
        }
        return result;
    }

    private static Set<Integer> parseTraditionalXrefObjects(byte[] pdf, long offset) {
        Set<Integer> result = new HashSet<>();
        int pos = (int) offset;
        if (pos < 0 || pos + 4 > pdf.length) return result;
        while (pos < pdf.length && isWs(pdf[pos])) pos++;
        if (!regionMatches(pdf, pos, "xref".getBytes(StandardCharsets.ISO_8859_1))) return result;
        pos += 4;
        while (pos < pdf.length) {
            while (pos < pdf.length && isWs(pdf[pos])) pos++;
            if (pos >= pdf.length) break;
            if (regionMatches(pdf, pos, "trailer".getBytes(StandardCharsets.ISO_8859_1))) break;
            long[] firstResult = readLongAt(pdf, pos);
            if (firstResult == null) break;
            long firstObjLong = firstResult[0];
            if (firstObjLong < 0 || firstObjLong > Integer.MAX_VALUE) break; // reject malformed xref
            int firstObj = (int) firstObjLong;
            pos = (int) firstResult[1];

            while (pos < pdf.length && isWs(pdf[pos])) pos++;
            long[] countResult = readLongAt(pdf, pos);
            if (countResult == null) break;
            long countLong = countResult[0];
            if (countLong < 0 || countLong > 65535) break; // sane limit; PDF xref sections rarely exceed this
            int count = (int) countLong;
            pos = (int) countResult[1];

            while (pos < pdf.length && pdf[pos] != '\n') pos++;
            pos++;
            for (int i = 0; i < count && pos + 20 <= pdf.length; i++) {
                if ((char) pdf[pos + 17] == 'n') {
                    long objNumLong = (long) firstObj + i;
                    if (objNumLong >= 0 && objNumLong <= Integer.MAX_VALUE) {
                        result.add((int) objNumLong);
                    }
                }
                pos += 20;
            }
        }
        return result;
    }

    private static boolean regionMatches(byte[] data, int offset, byte[] pattern) {
        if (offset + pattern.length > data.length) return false;
        for (int i = 0; i < pattern.length; i++) if (data[offset + i] != pattern[i]) return false;
        return true;
    }

    private static long readNextLong(byte[] data, int pos) {
        while (pos < data.length && isWs(data[pos])) pos++;
        long val = 0; boolean found = false;
        while (pos < data.length && data[pos] >= '0' && data[pos] <= '9') {
            val = val * 10 + (data[pos] - '0'); found = true; pos++;
        }
        return found ? val : -1;
    }

    private static long[] readLongAt(byte[] data, int pos) {
        while (pos < data.length && isWs(data[pos])) pos++;
        long val = 0; boolean found = false;
        while (pos < data.length && data[pos] >= '0' && data[pos] <= '9') {
            val = val * 10 + (data[pos] - '0'); found = true; pos++;
        }
        return found ? new long[]{val, pos} : null;
    }

    private static boolean isWs(byte b) {
        return b == ' ' || b == '\t' || b == '\r' || b == '\n';
    }

    private static void enrichUrlHit(UrlHit hit) {
        if (hit.url == null) return;
        List<String> flags = new ArrayList<>();
        String urlStr = hit.url;
        String host = null;
        try {
            java.net.URI uri = new java.net.URI(urlStr);
            host = uri.getHost();
        } catch (Exception ignored) {}
        if (host == null || host.isEmpty()) return;
        String decodedIp = parseObfuscatedIp(host);
        if (decodedIp != null) {
            flags.add("obfuscated_host");
            hit.normalizedUrl = urlStr.replace(host, decodedIp);
            if (isPrivateIp(decodedIp)) flags.add("private_ip");
            else flags.add("public_ip");
        } else if (isLikelyIpAddress(host)) {
            if (isPrivateIp(host)) flags.add("private_ip");
            else flags.add("public_ip");
        } else {
            try {
                InternetDomainName domain = InternetDomainName.from(host);
                if (domain.isUnderPublicSuffix()) flags.add("valid_domain");
                else flags.add("unknown_tld");
            } catch (IllegalArgumentException ignored) {}
        }
        if (!flags.isEmpty()) hit.flags = flags;
    }

    private static String detectFileMagic(byte[] b) {
        if (b == null || b.length < 4) return "unknown";
        // PDF (may have junk before header — already handled by findPdfHeader)
        if (startsWith(b, 0, 0x25, 0x50, 0x44, 0x46)) { // %PDF
            // extract version e.g. "%PDF-1.7"
            int end = 4;
            while (end < b.length && end < 12 && b[end] != '\r' && b[end] != '\n') end++;
            return new String(b, 0, end, java.nio.charset.StandardCharsets.US_ASCII);
        }
        // ZIP-based (DOCX, XLSX, PPTX, JAR, APK, ODF...)
        if (startsWith(b, 0, 0x50, 0x4B, 0x03, 0x04)) return "Zip archive (DOCX/XLSX/JAR/APK)";
        if (startsWith(b, 0, 0x50, 0x4B, 0x05, 0x06)) return "Zip archive (empty)";
        if (startsWith(b, 0, 0x50, 0x4B, 0x07, 0x08)) return "Zip archive (spanned)";
        // OLE2 compound (DOC, XLS, PPT, MSG)
        if (startsWith(b, 0, 0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1))
            return "OLE2 compound (DOC/XLS/PPT/MSG)";
        // RTF
        if (startsWith(b, 0, 0x7B, 0x5C, 0x72, 0x74, 0x66)) return "RTF document";
        // HTML
        if (b.length >= 5) {
            String head = new String(b, 0, Math.min(b.length, 20), java.nio.charset.StandardCharsets.US_ASCII).toLowerCase();
            if (head.startsWith("<html") || head.startsWith("<!doc")) return "HTML document";
        }
        // PostScript
        if (startsWith(b, 0, 0x25, 0x21, 0x50, 0x53)) return "PostScript";
        // RAR
        if (startsWith(b, 0, 0x52, 0x61, 0x72, 0x21, 0x1A, 0x07)) return "RAR archive";
        // 7-Zip
        if (startsWith(b, 0, 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C)) return "7-Zip archive";
        // JPEG
        if (startsWith(b, 0, 0xFF, 0xD8, 0xFF)) return "JPEG image";
        // PNG
        if (startsWith(b, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) return "PNG image";
        // GIF
        if (startsWith(b, 0, 0x47, 0x49, 0x46, 0x38)) return "GIF image";
        // PE/EXE
        if (startsWith(b, 0, 0x4D, 0x5A)) return "Windows PE executable";
        // ELF
        if (startsWith(b, 0, 0x7F, 0x45, 0x4C, 0x46)) return "ELF executable";
        // Cabinet
        if (startsWith(b, 0, 0x4D, 0x53, 0x43, 0x46)) return "Microsoft Cabinet";
        // XML
        if (startsWith(b, 0, 0x3C, 0x3F, 0x78, 0x6D, 0x6C)) return "XML document";
        return String.format("unknown (magic: %02x %02x %02x %02x)",
            b[0] & 0xFF, b[1] & 0xFF, b.length > 2 ? b[2] & 0xFF : 0, b.length > 3 ? b[3] & 0xFF : 0);
    }

    private static boolean startsWith(byte[] b, int offset, int... magic) {
        if (b.length < offset + magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if ((b[offset + i] & 0xFF) != magic[i]) return false;
        }
        return true;
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] result = digest.digest(data);
            StringBuilder sb = new StringBuilder(result.length * 2);
            for (byte b : result) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to compute SHA-256", e);
        }
    }

    /**
     * Compute a PDF Object Hash compatible with the Proofpoint/EmergingThreats algorithm.
     * Uses raw byte parsing (NOT PDFBox) to avoid PDFBox's /Length key reordering.
     *
     * Algorithm (matches pdf_lib.py / pdf_obj_hash.py exactly):
     *   1. Parse xref chain oldest→newest; latest revision of each object wins
     *   2. Collect type-1 (direct byte offset) entries; sort by file offset
     *   3. For each object at its byte offset, parse the raw << >> dictionary:
     *      - /Type + /Subtype present → "Type/Subtype"
     *      - /Type only             → "Type"
     *      - no /Type               → first key name in raw dict (e.g. "Length", "Filter")
     *      - no dict at all         → "None"
     *   4. Concatenate all tokens with trailing "|": "Catalog|Pages|Page|Font/Type1|"
     *   5. MD5-hash the UTF-8 string → 32 lowercase hex chars
     */
    private static String computePdfObjectHash(byte[] pdf) {
        try {
            // Build start_list replicating reference's trailer_process: scan ALL
            // "trailer...%%EOF" and "startxref...%%EOF" blocks, extract all startxref +
            // /Prev + /XRefStm values in reverse file order, then dedup. Returns null
            // when no such blocks exist → full-file fallback scan.
            List<Long> processList = buildPdfHashStartList(pdf);

            Map<Integer, Long> current = new LinkedHashMap<>();

            if (processList != null) {
                // Dynamic processing loop: list grows when fallback scan triggers.
                // Reference's parse_xref_table calls run_regex_xref_scan() on failure,
                // which appends ALL found XRef positions; Python for-loop over growing list.
                boolean fallbackTriggered = false;
                Set<Long> processedSet = new HashSet<>(processList);
                for (int i = 0; i < processList.size(); i++) {
                    long xo = processList.get(i);
                    if (xo == 0 || xo >= pdf.length) {
                        // Offset 0 or beyond file: parse fails → run_regex_xref_scan()
                        if (!fallbackTriggered) {
                            fallbackTriggered = true;
                            List<Long> fallback = findXrefByFullScan(pdf);
                            processList.addAll(fallback);
                        }
                        continue;
                    }
                    boolean valid = pdfHashAddXrefEntriesTracked(pdf, xo, current);
                    if (!valid && !fallbackTriggered) {
                        fallbackTriggered = true;
                        List<Long> fallback = findXrefByFullScan(pdf);
                        processList.addAll(fallback);
                    }
                    // Reference's parse_xref_table dynamically adds /Prev from XRef streams
                    // to start_list during iteration.
                    long prevOffset = extractXrefStreamPrevOffset(pdf, xo);
                    if (prevOffset > 0 && processedSet.add(prevOffset)) {
                        processList.add(prevOffset);
                    }
                }
                if (current.isEmpty()) {
                    List<Long> fallback = findXrefByFullScan(pdf);
                    for (long xo : fallback) pdfHashAddXrefEntries(pdf, xo, current);
                }
            } else {
                // No trailer/startxref blocks at all → full-file fallback scan
                List<Long> fallback = findXrefByFullScan(pdf);
                for (long xo : fallback) pdfHashAddXrefEntries(pdf, xo, current);
            }

            if (current.isEmpty()) return null;

            // Collect entries sorted by file offset. Use (objNum, offset) pairs to sort.
            List<Map.Entry<Integer, Long>> entries = new ArrayList<>(current.entrySet());
            entries.sort((a, b) -> Long.compare(a.getValue(), b.getValue()));

            // Build hash string with trailing "|" after every token (matches reference).
            // Reference skips objects with /Type/Sig (parse_pdf_object returns False for them).
            StringBuilder hashStr = new StringBuilder();
            byte[] typeSigKw = "/Type/Sig".getBytes(StandardCharsets.ISO_8859_1);
            byte[] endobjKw = "endobj".getBytes(StandardCharsets.ISO_8859_1);
            for (Map.Entry<Integer, Long> e : entries) {
                if (e.getValue() <= 0) continue;
                int objOffset = (int)(long) e.getValue();
                // Check for /Type/Sig within this object (up to endobj boundary)
                int scanMax = Math.min(objOffset + 131072, pdf.length);
                int endobjPos = -1;
                for (int k = objOffset; k <= scanMax - endobjKw.length; k++) {
                    if (regionMatches(pdf, k, endobjKw)) { endobjPos = k; break; }
                }
                int sigScanEnd = (endobjPos >= 0) ? endobjPos + endobjKw.length : scanMax;
                boolean isSig = false;
                for (int k = objOffset; k < sigScanEnd - typeSigKw.length + 1; k++) {
                    if (regionMatches(pdf, k, typeSigKw)) { isSig = true; break; }
                }
                if (isSig) continue;
                hashStr.append(pdfHashExtractObjectType(pdf, objOffset)).append('|');
            }
            if (hashStr.length() == 0) return null;

            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(hashStr.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(32);
            for (byte b : digest) hex.append(String.format(Locale.ROOT, "%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parse a traditional xref table OR cross-reference stream at `xrefOffset`,
     * updating `map` with {objNum → byteOffset} for in-use (type-1) entries.
     * Free (type-0) entries remove the object. Compressed (type-2) entries are
     * stored with a negative sentinel (-objstmNum - 1) so they're excluded from
     * the file-order sort but still tracked as current.
     */
    private static void pdfHashAddXrefEntries(byte[] pdf, long xrefOffset, Map<Integer, Long> map) {
        int pos = (int) xrefOffset;
        if (pos < 0 || pos >= pdf.length) return;
        while (pos < pdf.length && isWs(pdf[pos])) pos++;

        if (regionMatches(pdf, pos, "xref".getBytes(StandardCharsets.ISO_8859_1))) {
            // --- Traditional xref table ---
            pos += 4;
            while (pos < pdf.length) {
                while (pos < pdf.length && isWs(pdf[pos])) pos++;
                if (pos >= pdf.length) break;
                if (regionMatches(pdf, pos, "trailer".getBytes(StandardCharsets.ISO_8859_1))) break;
                long[] fr = readLongAt(pdf, pos); if (fr == null) break;
                int firstObj = (int) fr[0]; pos = (int) fr[1];
                while (pos < pdf.length && isWs(pdf[pos])) pos++;
                long[] cr = readLongAt(pdf, pos); if (cr == null) break;
                // H4: mirror the bounds check in parseTraditionalXrefObjects
                if (cr[0] < 0 || cr[0] > 65535) break;
                int count = (int) cr[0]; pos = (int) cr[1];
                // Skip section header EOL: may be \r, \n, or \r\n (CR-only is valid per PDF spec)
                while (pos < pdf.length && pdf[pos] != '\n' && pdf[pos] != '\r') pos++;
                if (pos < pdf.length && pdf[pos] == '\r') pos++;
                if (pos < pdf.length && pdf[pos] == '\n') pos++;
                for (int i = 0; i < count && pos + 20 <= pdf.length; i++) {
                    long off = 0;
                    for (int c = 0; c < 10; c++) off = off * 10 + (pdf[pos + c] - '0');
                    char flag = (char) (pdf[pos + 17] & 0xFF);
                    int objNum = firstObj + i;
                    if (objNum > 0 && flag == 'n') map.put(objNum, off);
                    // 'f' free entries: skip — oldest 'n' entry wins (last write)
                    pos += 20;
                }
            }
        } else {
            // --- Possible cross-reference stream (PDF 1.5+): "N G obj << ... >> stream ... endstream" ---
            // Skip "N G obj" (allow leading whitespace before object number)
            while (pos < pdf.length && isWs(pdf[pos])) pos++;
            while (pos < pdf.length && (pdf[pos] >= '0' && pdf[pos] <= '9')) pos++;
            while (pos < pdf.length && isWs(pdf[pos])) pos++;
            while (pos < pdf.length && (pdf[pos] >= '0' && pdf[pos] <= '9')) pos++;
            while (pos < pdf.length && isWs(pdf[pos])) pos++;
            if (pos + 3 > pdf.length || pdf[pos] != 'o' || pdf[pos+1] != 'b' || pdf[pos+2] != 'j') return;
            pos += 3;
            while (pos < pdf.length && isWs(pdf[pos])) pos++;
            if (pos + 2 > pdf.length || pdf[pos] != '<' || pdf[pos+1] != '<') return;
            int dictStart = pos + 2;

            // Find end of top-level dict ">>"
            int depth = 1, dictEnd = -1, s = dictStart;
            int dictLimit = Math.min(dictStart + 32768, pdf.length);
            while (s < dictLimit - 1) {
                if (pdf[s] == '<' && pdf[s+1] == '<') { depth++; s += 2; }
                else if (pdf[s] == '>' && pdf[s+1] == '>') {
                    depth--; if (depth == 0) { dictEnd = s; break; } s += 2;
                } else s++;
            }
            if (dictEnd < 0) return;
            String dictStr = new String(pdf, dictStart, dictEnd - dictStart, StandardCharsets.ISO_8859_1);

            // Verify it's an xref stream
            if (!dictStr.replace(" ", "").contains("/Type/XRef")) return;

            // Replicate reference's params_w regex failure on multi-line /W arrays.
            // Reference uses r'/W\s*\[(.+?)\]' without DOTALL → fails if \n inside brackets.
            java.util.regex.Matcher wLineCheck = java.util.regex.Pattern
                .compile("/W\\s*\\[([^\\]]+)\\]").matcher(dictStr);
            if (wLineCheck.find() && wLineCheck.group(1).contains("\n")) return;
            // Parse /W [w0 w1 w2]
            java.util.regex.Matcher wm = java.util.regex.Pattern
                .compile("/W\\s*\\[\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)").matcher(dictStr);
            if (!wm.find()) return;
            int w0 = Integer.parseInt(wm.group(1));
            int w1 = Integer.parseInt(wm.group(2));
            int w2 = Integer.parseInt(wm.group(3));
            int rowWidth = w0 + w1 + w2;
            if (rowWidth == 0) return;

            // Parse /Index [start count ...] (default: [0 /Size])
            List<int[]> indexPairs = new ArrayList<>();
            java.util.regex.Matcher im = java.util.regex.Pattern
                .compile("/Index\\s*\\[([^\\]]+)\\]").matcher(dictStr);
            if (im.find()) {
                String[] parts = im.group(1).trim().split("\\s+");
                for (int i = 0; i + 1 < parts.length; i += 2) {
                    try { indexPairs.add(new int[]{Integer.parseInt(parts[i]), Integer.parseInt(parts[i+1])}); }
                    catch (NumberFormatException ignored) {}
                }
            }
            if (indexPairs.isEmpty()) {
                java.util.regex.Matcher szm = java.util.regex.Pattern
                    .compile("/Size\\s+(\\d+)").matcher(dictStr);
                if (szm.find()) indexPairs.add(new int[]{0, Integer.parseInt(szm.group(1))});
                else return;
            }

            // Parse optional Predictor
            int predictor = 0;
            java.util.regex.Matcher pm = java.util.regex.Pattern
                .compile("/Predictor\\s+(\\d+)").matcher(dictStr);
            if (pm.find()) predictor = Integer.parseInt(pm.group(1));

            // Find "stream" keyword and decompress (FlateDecode assumed)
            int streamStart = dictEnd + 2;
            while (streamStart < pdf.length && isWs(pdf[streamStart])) streamStart++;
            if (!regionMatches(pdf, streamStart, "stream".getBytes(StandardCharsets.ISO_8859_1))) return;
            streamStart += 6;
            if (streamStart < pdf.length && pdf[streamStart] == '\r') streamStart++;
            if (streamStart < pdf.length && pdf[streamStart] == '\n') streamStart++;

            int streamEnd = -1;
            byte[] endKw = "endstream".getBytes(StandardCharsets.ISO_8859_1);
            for (int i = streamStart; i <= pdf.length - 9; i++) {
                if (regionMatches(pdf, i, endKw)) { streamEnd = i; break; }
            }
            if (streamEnd < 0) return;

            byte[] decompressed;
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                try (java.util.zip.InflaterInputStream iis = new java.util.zip.InflaterInputStream(
                        new java.io.ByteArrayInputStream(pdf, streamStart, streamEnd - streamStart))) {
                    byte[] buf = new byte[4096]; int n;
                    while ((n = iis.read(buf)) >= 0) baos.write(buf, 0, n);
                }
                decompressed = baos.toByteArray();
            } catch (Exception ignored) { return; }

            // Apply PNG Up predictor (predictor >= 10) if present
            if (predictor >= 10) {
                int stride = rowWidth + 1; // +1 for predictor byte
                if (decompressed.length % stride != 0) return;
                byte[] unpredicted = new byte[rowWidth * (decompressed.length / stride)];
                byte[] prev = new byte[rowWidth];
                int outPos = 0;
                for (int r = 0; r < decompressed.length; r += stride) {
                    // byte 0 is predictor type; we treat all as "Up" (add previous row)
                    for (int c = 0; c < rowWidth; c++) {
                        int val = (decompressed[r + 1 + c] & 0xFF) + (prev[c] & 0xFF);
                        unpredicted[outPos] = (byte)(val & 0xFF);
                        prev[c] = unpredicted[outPos];
                        outPos++;
                    }
                }
                decompressed = unpredicted;
                // Now rowWidth stays the same (predictor byte is stripped)
            }

            // Parse binary rows per /W.
            // Reference uses sequential numbering (seq = len(parsed_data)) ignoring /Index,
            // and does NOT skip objNum=0. For linearized PDFs, seq=0 in the newest XRef
            // points to a real object (e.g. Metadata/XML) and must be included.
            int offset = 0;
            int seq = 0;
            for (int[] pair : indexPairs) {
                int count = pair[1];
                for (int i = 0; i < count; i++) {
                    if (offset + rowWidth > decompressed.length) return;
                    int type = w0 > 0 ? (int) pdfHashReadBE(decompressed, offset, w0) : 1;
                    long val2 = w1 > 0 ? pdfHashReadBE(decompressed, offset + w0, w1) : 0;
                    int objNum = seq;  // sequential from 0, ignoring /Index (reference bug)
                    if (type == 1) map.put(objNum, val2);     // normal: val2 = byte offset
                    else if (type == 2) map.remove(objNum);   // compressed: exclude
                    // type=0 (free): reference registers (seq, val3) in registry but does NOT update
                    // current_objects. Only removes in-use entry if val3 == gen_in_use (typically 0).
                    // When val3=65535 (null object convention), different gen → in-use entry survives.
                    else if (type == 0) {
                        long val3 = w2 > 0 ? pdfHashReadBE(decompressed, offset + w0 + w1, w2) : 0;
                        if (val3 == 0) map.remove(objNum);
                    }
                    seq++;
                    offset += rowWidth;
                }
            }
        }
    }

    private static long pdfHashReadBE(byte[] data, int offset, int width) {
        long val = 0;
        for (int i = 0; i < width; i++) val = (val << 8) | (data[offset + i] & 0xFF);
        return val;
    }

    /**
     * Extract the object type token from raw PDF bytes at `offset` (the "N G obj" header).
     * Matches EmergingThreats parse_pdf_object logic exactly:
     *   - /Type + /Subtype → "Type/Subtype"
     *   - /Type only       → "Type"
     *   - dict, no /Type   → first /Key name in raw dict (preserves original file order,
     *                         avoids PDFBox's /Length-to-front reordering)
     *   - no dict / error  → "None"
     */
    private static String pdfHashExtractObjectType(byte[] pdf, int offset) {
        int pos = offset;
        int limit = Math.min(offset + 512, pdf.length);
        // Skip "N G obj" (allow leading whitespace — xref offsets sometimes point to \n before "N G obj")
        while (pos < limit && isWs(pdf[pos])) pos++;
        while (pos < limit && (pdf[pos] >= '0' && pdf[pos] <= '9')) pos++;
        while (pos < limit && isWs(pdf[pos])) pos++;
        while (pos < limit && (pdf[pos] >= '0' && pdf[pos] <= '9')) pos++;
        while (pos < limit && isWs(pdf[pos])) pos++;
        if (pos + 3 > limit || pdf[pos] != 'o' || pdf[pos+1] != 'b' || pdf[pos+2] != 'j') return "None";
        pos += 3;
        // Reference (object_pattern_big) requires at LEAST ONE whitespace between "obj" and the dict.
        // "1 0 obj<<...>>" with no whitespace → reference regex fails → returns "None".
        if (pos >= pdf.length || !isWs(pdf[pos])) return "None";
        while (pos < pdf.length && isWs(pdf[pos])) pos++;
        if (pos + 2 > pdf.length || pdf[pos] != '<' || pdf[pos+1] != '<') return "None";
        int dictStart = pos + 2;

        // Find end of top-level dict ">>"
        int depth = 1, dictEnd = -1, s = dictStart;
        // No fixed upper limit — some info objects have very large Keywords/Title strings (40KB+)
        int dictLimit = pdf.length;
        while (s < dictLimit - 1) {
            if (pdf[s] == '<' && pdf[s+1] == '<') { depth++; s += 2; }
            else if (pdf[s] == '>' && pdf[s+1] == '>') {
                depth--; if (depth == 0) { dictEnd = s; break; } s += 2;
            } else s++;
        }
        if (dictEnd < 0) return "None";

        // Reference compatibility: object_pattern_big requires at least one whitespace before "endobj".
        // Ghostscript writes ">>endobj" with no whitespace — the reference regex fails there and
        // returns "None". Replicate: if >> is immediately followed by endobj (no whitespace), return "None".
        int afterDict = dictEnd + 2;
        if (afterDict + 6 <= pdf.length && regionMatches(pdf, afterDict, "endobj".getBytes(StandardCharsets.ISO_8859_1))) {
            return "None";
        }

        String rawDict = new String(pdf, dictStart, dictEnd - dictStart, StandardCharsets.ISO_8859_1);

        // PDF name character class: any char except whitespace and delimiters ( ) < > [ ] { } / %
        // Matches the reference's parse_name() stop-set exactly.
        final String NAME_CHARS = "[^ \\t\\n\\r\\f\\x00()<>\\[\\]{}/%]+";

        // Replicate reference's parse_array bug: when parse_value returns None for 'null',
        // parse_array breaks WITHOUT consuming ']'. The unclosed ']' causes the enclosing dict's
        // parse_name to fail (']' is not '/'), breaking that dict too without consuming '>>'.
        // This propagates up through all ancestor dicts, preventing '/Type' from being found.
        // If any array containing 'null' appears in the raw dict before '/Type', return first key.
        int typePos = rawDict.indexOf("/Type");
        if (typePos < 0) typePos = rawDict.length();
        // Find [... null ...] where null is inside the array (no ] between [ and null)
        java.util.regex.Matcher nullInArr = java.util.regex.Pattern
            .compile("\\[[^\\]]*null").matcher(rawDict);
        if (nullInArr.find() && nullInArr.start() < typePos) {
            java.util.regex.Matcher firstKeyFallback = java.util.regex.Pattern
                .compile("/(" + NAME_CHARS + ")").matcher(rawDict);
            if (firstKeyFallback.find()) {
                return firstKeyFallback.group(1);
            }
        }

        // Flatten: remove nested << ... >> content so we only search top-level keys.
        // Without this, /Subtype in a nested dict (e.g. inline Annot) would be incorrectly matched.
        String flatDict = stripNestedDicts(rawDict);

        // Match /Type and /Subtype — use * (zero or more whitespace) to handle /Type/Page
        // (no whitespace between key and value) as well as /Type /Page (with whitespace).
        java.util.regex.Matcher tm = java.util.regex.Pattern
            .compile("/Type[\\x00\\x09\\x0a\\x0c\\x0d ]*+/(" + NAME_CHARS + ")").matcher(flatDict);
        java.util.regex.Matcher sm = java.util.regex.Pattern
            .compile("/Subtype[\\x00\\x09\\x0a\\x0c\\x0d ]*+/(" + NAME_CHARS + ")").matcher(flatDict);
        String type = tm.find() ? tm.group(1) : null;
        String subtype = sm.find() ? sm.group(1) : null;
        if (type != null && subtype != null) return type + "/" + subtype;
        if (type != null) return type;

        // No /Type: return first /Key name in raw dict (reference: next(iter(param_dict)))
        java.util.regex.Matcher fm = java.util.regex.Pattern
            .compile("/(" + NAME_CHARS + ")").matcher(flatDict);
        if (fm.find()) return fm.group(1);
        return "None";
    }

    /**
     * Remove nested << ... >> content from a dict string, leaving only top-level tokens.
     * Prevents matching /Subtype from inline nested objects (e.g. /Annots with /Subtype /Link).
     */
    private static String stripNestedDicts(String s) {
        StringBuilder sb = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<' && i + 1 < s.length() && s.charAt(i + 1) == '<') {
                depth++;
                i++;
            } else if (c == '>' && i + 1 < s.length() && s.charAt(i + 1) == '>') {
                if (depth > 0) depth--;
                i++;
            } else if (depth == 0) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Searches for a %PDF header within the first 1024 bytes, matching Acrobat's behaviour.
     * Returns the byte offset of the '%' in "%PDF", or -1 if not found.
     */
    private static int findPdfHeader(byte[] bytes) {
        int limit = Math.min(bytes.length - 4, 1024);
        for (int i = 0; i <= limit; i++) {
            if (bytes[i] == 0x25 && bytes[i+1] == 0x50 && bytes[i+2] == 0x44 && bytes[i+3] == 0x46) {
                return i;
            }
        }
        return -1;
    }

    private static class PositionAwareTextStripper extends PDFTextStripper {
        private final StringBuilder text = new StringBuilder();
        private final List<TextPosition> indexToPosition = new ArrayList<>();

        PositionAwareTextStripper() throws IOException {
            super();
        }

        @Override
        protected void writeString(String string, List<TextPosition> textPositions) {
            int consumed = 0;
            for (TextPosition position : textPositions) {
                String unicode = position.getUnicode();
                if (unicode == null || unicode.isEmpty()) {
                    continue;
                }
                for (int i = 0; i < unicode.length(); i++) {
                    char c = unicode.charAt(i);
                    text.append(c);
                    indexToPosition.add(position);
                    consumed++;
                }
            }
            if (consumed == 0 && string != null && !string.isEmpty()) {
                for (int i = 0; i < string.length(); i++) {
                    text.append(string.charAt(i));
                    indexToPosition.add(null);
                }
            }
            text.append('\n');
            indexToPosition.add(null);
        }

        String getCollectedText() {
            return text.toString();
        }

        List<TextPosition> positionsForRange(int start, int end) {
            List<TextPosition> out = new ArrayList<>();
            for (int i = start; i < end && i < indexToPosition.size(); i++) {
                TextPosition pos = indexToPosition.get(i);
                if (pos != null) {
                    out.add(pos);
                }
            }
            return out;
        }
    }

    /** Thrown by PageClassifier to short-circuit processing once a paint op is found. */
    private static final class PaintFoundSignal extends RuntimeException {
        PaintFoundSignal() { super(null, null, true, false); } // suppress stack trace for perf
    }

    /** Walks a page's content streams to detect any visible drawing operations. */
    private static class PageClassifier extends PDFGraphicsStreamEngine {
        PageClassifier(PDPage page) { super(page); }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            // Detect text-showing operators before the super call
            String op = operator.getName();
            if ("Tj".equals(op) || "TJ".equals(op) || "'".equals(op) || "\"".equals(op)) {
                throw new PaintFoundSignal();
            }
            super.processOperator(operator, operands);
        }

        @Override public void drawImage(PDImage pdImage)              { throw new PaintFoundSignal(); }
        @Override public void strokePath()                            { throw new PaintFoundSignal(); }
        @Override public void fillPath(int windingRule)               { throw new PaintFoundSignal(); }
        @Override public void fillAndStrokePath(int windingRule)      { throw new PaintFoundSignal(); }
        @Override public void shadingFill(COSName shadingName)        { throw new PaintFoundSignal(); }

        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {}
        @Override public void clip(int windingRule) {}
        @Override public void moveTo(float x, float y) {}
        @Override public void lineTo(float x, float y) {}
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}
        @Override public Point2D.Float getCurrentPoint() { return new Point2D.Float(0, 0); }
        @Override public void closePath() {}
        @Override public void endPath() {}
    }

    /**
     * Returns the set of 1-indexed page numbers that are visually blank:
     * no paint operations and no annotations.
     * Uses a short-circuiting operator walk — most blank pages are caught
     * structurally before any operator parsing happens.
     */

    /**
     * Replace blank pages in the selection with the next available non-blank pages (in page order).
     * If there are no more non-blank candidates to fill in, blank slots are simply dropped.
     */
    private List<Integer> fillBlankPages(List<Integer> pages, Set<Integer> blankPages, int totalPages) {
        Set<Integer> selected = new HashSet<>(pages);
        // Candidates: non-blank pages not already in the selection, in ascending order
        List<Integer> candidates = new ArrayList<>();
        for (int p = 1; p <= totalPages; p++) {
            if (!blankPages.contains(p) && !selected.contains(p)) {
                candidates.add(p);
            }
        }
        List<Integer> result = new ArrayList<>();
        Iterator<Integer> candIter = candidates.iterator();
        for (int p : pages) {
            if (!blankPages.contains(p)) {
                result.add(p);
            } else if (candIter.hasNext()) {
                result.add(candIter.next());
            }
            // else: blank with no replacement — drop it
        }
        Collections.sort(result);
        return result;
    }

    private Set<Integer> classifyBlankPages(PDDocument document) {
        Set<Integer> blank = new LinkedHashSet<>();
        int pageNum = 0;
        for (PDPage page : document.getPages()) {
            pageNum++;
            // Structural check: if no /Contents, skip operator walk
            COSBase contents = page.getCOSObject().getDictionaryObject(COSName.CONTENTS);
            boolean hasContents = contents != null
                    && !(contents instanceof COSArray arr && arr.size() == 0);
            // Check annotations — a page with annotations is never considered blank
            // (blank-looking pages can still carry invisible link traps)
            boolean hasAnnotations = false;
            try { hasAnnotations = !page.getAnnotations().isEmpty(); } catch (Exception ignored) {}
            if (hasAnnotations) continue;

            if (!hasContents) {
                blank.add(pageNum);
            } else {
                try {
                    new PageClassifier(page).processPage(page);
                    blank.add(pageNum); // processPage finished without PaintFoundSignal
                } catch (PaintFoundSignal ignored) {
                    // paint found — not blank
                } catch (Exception ignored) {
                    // failed to parse — assume not blank to be safe
                }
            }
        }
        return blank;
    }

    private class DrawnImageCollector extends PDFGraphicsStreamEngine {
        private final int pageNumber;
        private final Path outputDir;
        private final List<ImageArtifact> sink;
        private final Set<COSBase> drawnCos;
        private int imageCounter = 0;

        DrawnImageCollector(PDPage page, int pageNumber, Path outputDir, List<ImageArtifact> sink, Set<COSBase> drawnCos) {
            super(page);
            this.pageNumber = pageNumber;
            this.outputDir = outputDir;
            this.sink = sink;
            this.drawnCos = drawnCos;
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("timed out");
            super.processOperator(operator, operands);
        }

        @Override
        public void drawImage(PDImage pdImage) throws IOException {
            BufferedImage image = pdImage.getImage();
            if (image == null) {
                return;
            }
            if (pdImage instanceof PDImageXObject xobj) {
                drawnCos.add(xobj.getCOSObject());
            }
            imageCounter++;
            Path file = uniquePath(outputDir, String.format(Locale.ROOT, "drawn-page-%04d-image-%04d.png", pageNumber, imageCounter));
            ImageIO.write(image, "PNG", file.toFile());

            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            Point2D.Float p0 = ctm.transformPoint(0, 0);
            Point2D.Float p1 = ctm.transformPoint(1, 1);
            BoundingBox box = BoundingBox.fromDrawnImage(p0, p1);

            ImageArtifact artifact = new ImageArtifact();
            artifact.page = pageNumber;
            artifact.path = relativePath(file);
            artifact.width = image.getWidth();
            artifact.height = image.getHeight();
            artifact.source = pdImage instanceof PDImageXObject ? "drawn_xobject" : "drawn_inline_image";
            artifact.bounds = box;
            artifact.originalPath = saveOriginalXObjectBytes(pdImage, outputDir,
                String.format(Locale.ROOT, "drawn-page-%04d-image-%04d-original", pageNumber, imageCounter));
            // Compute hashes in-memory: phash from the already-decoded BufferedImage,
            // sha256 from the saved file (JPEG original when available, else PNG).
            // originalPath is relative to the outer class outputDir (main dir), not this.outputDir (subdir).
            artifact.hashes = computeImageHashInMemory(image,
                artifact.originalPath != null ? PdfTitanArumApp.this.outputDir.resolve(artifact.originalPath) : file);
            if (!skipQrScan) {
                List<QrCodeHit> codes = scanQrCodes(image);
                if (!codes.isEmpty()) {
                    artifact.qrCodes = codes;
                }
            }
            sink.add(artifact);
        }

        @Override public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {}
        @Override public void clip(int windingRule) {}
        @Override public void moveTo(float x, float y) {}
        @Override public void lineTo(float x, float y) {}
        @Override public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {}
        @Override public Point2D getCurrentPoint() { return null; }
        @Override public void closePath() {}
        @Override public void endPath() {}
        @Override public void strokePath() {}
        @Override public void fillPath(int windingRule) {}
        @Override public void fillAndStrokePath(int windingRule) {}
        @Override public void shadingFill(COSName shadingName) {}
    }

    interface HashedArtifact {
        String getPath();
        void setHashes(HashResult hashes);
    }

    /** Metadata from the PDF's document information dictionary (XMP / Info entry). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DocumentInfo {
        /** PDF specification version declared in the file header (e.g. "1.7", "2.0"). */
        public String pdfVersion;
        public String title;
        public String author;
        public String subject;
        public String keywords;
        /** Application that created the original document (e.g. "Microsoft Word"). */
        public String creator;
        /** PDF library / print driver that produced the PDF file (e.g. "Adobe PDF Library"). */
        public String producer;
        /** ISO-8601 creation timestamp declared in the PDF metadata. */
        public String creationDate;
        /** ISO-8601 last-modification timestamp declared in the PDF metadata. */
        public String modificationDate;
        /** Days elapsed since creationDate (null if date absent or unparseable). */
        public Long daysSinceCreated;
        /** Days elapsed since modificationDate (null if date absent or unparseable). */
        public Long daysSinceModified;
        /** Days between creationDate and modificationDate (positive = modified after creation). Null if either date is absent. */
        public Long daysBetweenCreatedAndModified;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AnalysisReport {
        public String inputPdf;
        public String outputDirectory;
        public String generatedAt;
        public String documentSha256;
        public String fileMagic;
        public String parseError;        // non-null when PDF loading failed
        public DocumentInfo documentInfo;
        public int pageCount;
        public int blankPageCount;
        public List<Integer> blankPages;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public Double blankRatio;
        public String pagesSpec;
        public List<Integer> pagesProcessed;
        public Boolean qrScanEnabled;
        public float dpi;
        public boolean addLinkAnnotations;
        public Integer syntheticLinksAdded;
        public String modifiedPdf;
        public List<UrlHit> urls = new ArrayList<>();
        public List<JavaScriptHit> javascript = new ArrayList<>();
        public List<LaunchActionHit> launchActions = new ArrayList<>();
        public List<ActionHit> actions = new ArrayList<>();
        public List<XfaScriptHit> xfaScripts = new ArrayList<>();
        public List<EmbeddedFileHit> embeddedFiles = new ArrayList<>();
        public List<PhoneHit> phoneNumbers = new ArrayList<>();
        public List<EmailHit> emails = new ArrayList<>();
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public List<OcgLayer> ocgLayers;
        public List<PagePdfArtifact> pagePdfs = new ArrayList<>();
        public List<ScreenshotArtifact> screenshots = new ArrayList<>();
        public List<ImageArtifact> renderedImages = new ArrayList<>();
        public List<ImageArtifact> resourceImages = new ArrayList<>();
        public List<PageText> pageTexts = new ArrayList<>();
        public int revisionCount;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public List<RevisionArtifact> revisions;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public List<String> fonts;
        public List<PageLinkStats> pageStats = new ArrayList<>();
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_DEFAULT)
        public boolean timedOut;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public Long timedOutAfterMs;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public java.util.Map<String,Object> aiAnalysis;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public String pdfObjectHash;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public List<StreamLengthHit> streamLengthAnomalies;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public List<StructuralAnomalyHit> structuralAnomalies;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public List<MetadataSpoofingHit> metadataSpoofingIndicators;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class UrlHit {
        public int page;
        public String url;
        public String displayText;
        public String source;
        public boolean annotationAlreadyPresent;
        public BoundingBox bounds;
        public String normalizedUrl;
        public List<String> flags;
        public Double pageCoverageRatio;
        public String cropPath;
        public HashResult cropHashes;
        public String cropOcrText;
        public List<RevisionEntry> revisionHistory;
        @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
        public Integer fromRevision; // set when URL comes from an older revision (not in final)
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JavaScriptHit {
        public String context;
        public String subtype;
        public String code;
        public String sha256;
        public String file;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class XfaScriptHit {
        public String context;
        public String contentType;
        public String code;
        public String sha256;
        public String file;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EmbeddedFileHit {
        public String source;
        public String context;
        public String originalName;
        public Integer page;
        public BoundingBox bounds;
        public String file;
        public long size;
        public String sha256;
        public String mimeType;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class LaunchActionHit {
        public String context;
        public Integer page;
        public BoundingBox bounds;
        public String file;
        public String directory;
        public String operation;
        public String parameters;
        public Boolean newWindow;
        public String artifact;
        public String sha256;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ActionHit {
        public String type;        // SubmitForm | ImportData | GoToR | GoToE | URI | Rendition | Named | Sound | Movie
        public String target;      // URI url, Rendition media url/file, Named action name, Sound/Movie file
        public String contentType; // Rendition media MIME type
        public String context;
        public Integer page;
        public BoundingBox bounds;
        // SubmitForm
        public String submitUrl;
        public List<String> fields;
        public Integer submitFlags;
        // ImportData
        public String importFile;
        // GoToR / GoToE
        public String remoteFile;
        public String destination;
        public String embeddedTarget;  // GoToE /T target path
        public Boolean newWindow;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PagePdfArtifact {
        public int page;
        public String path;
        public String sha256;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PageText {
        public int page;
        public String text;
    }

    private record PageTextData(int page, PDPage pdfPage, PositionAwareTextStripper stripper) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ScreenshotArtifact implements HashedArtifact {
        public int page;
        public String path;
        public int width;
        public int height;
        public HashResult hashes;
        public List<QrCodeHit> qrCodes;
        public String ocrText;
        /** Pixels per PDF point used when rendering this screenshot — used by cropUrlRegions. Not serialized. */
        @JsonIgnore public double renderScale;

        @Override public String getPath() { return path; }
        @Override public void setHashes(HashResult hashes) { this.hashes = hashes; }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RevisionArtifact {
        public int revision;       // 1 = oldest revision
        public int totalRevisions; // includes current
        public List<UrlHit> urls;
        public List<String> removedUrls;  // url strings present here but not in final revision
        public List<String> addedUrls;    // url strings in final revision not present here
        public Boolean urlsChangedVisuallyHidden; // urls differ but screenshots are pixel-identical
        public List<ScreenshotArtifact> screenshots;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ImageArtifact {
        public int page;
        public String source;
        public String context;
        public String path;         // rendered PNG (for display)
        public String originalPath; // original encoded file (jpg/jp2) when available
        public int width;
        public int height;
        public BoundingBox bounds;
        public HashResult hashes;
        public List<QrCodeHit> qrCodes;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class HashResult {
        @com.fasterxml.jackson.annotation.JsonIgnore
        public String path;
        public String filename;
        public Boolean exists;
        public Integer width;
        public Integer height;
        public String mode;
        public String sha256;
        public String average_hash;
        public String phash;
        public String dhash;
        public String whash;
        public String colorhash;
        public String error;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PhoneHit {
        public String raw;
        public String e164;
        public String nationalFormat;
        public String countryCode;
        public String geocode;
        /** Line type: mobile, fixed_line, fixed_line_or_mobile, toll_free, voip,
         *  premium_rate, shared_cost, personal_number, pager, uan, voicemail, unknown */
        public String lineType;
        public String source;
        public Integer page;
        public String context;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StreamLengthHit {
        /** Byte offset of the "stream" keyword in the file. */
        public long streamOffset;
        /** Object number from the preceding N G obj header, if parseable. */
        public Integer objectNumber;
        /** /Length value declared in the stream dictionary (null if absent or indirect ref). */
        public Long declaredLength;
        /** Actual byte count between stream data start and endstream (null if endstream missing). */
        public Long actualLength;
        /** "truncated" (actual < declared), "overflow" (actual > declared), "missing_endstream". */
        public String anomalyType;
        /** actualLength - declaredLength; negative = truncated, positive = overflow. */
        public Long delta;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StructuralAnomalyHit {
        /** header_offset | invalid_version | missing_binary_comment | malformed_binary_comment |
         *  missing_eof | data_after_eof */
        public String type;
        public String detail;
        /** Byte offset relevant to this finding (where the anomaly begins). */
        public Long offset;
        /** For data_after_eof: byte count of trailing data after last %%EOF. */
        public Long trailingBytes;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class MetadataSpoofingHit {
        /** predates_pdf_format | future_creation_date | future_modification_date |
         *  creation_after_modification | tool_mismatch */
        public String type;
        public String detail;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class EmailHit {
        public String email;
        public String source;
        public Integer page;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OcgLayer {
        public String name;
        public String defaultState;    // "ON" or "OFF"
        public Boolean visibleInView;  // null = unspecified
        public Boolean visibleInPrint; // null = unspecified (different from view = suspicious)
        public boolean suspicious;     // hidden by default or print≠view
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class RevisionEntry {
        public int revision;
        public String event;       // "added" or "modified"
        public int objectNumber;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class PageLinkStats {
        public int page;
        public float widthPt;
        public float heightPt;
        public double linkAnnotationCoverage;
    }

    static class RevisionTimeline {
        final int revisionCount;
        final Map<Integer, List<RevisionEntry>> objectTimelines;
        static final RevisionTimeline SINGLE = new RevisionTimeline(1, Collections.emptyMap());
        RevisionTimeline(int revisionCount, Map<Integer, List<RevisionEntry>> objectTimelines) {
            this.revisionCount = revisionCount;
            this.objectTimelines = objectTimelines;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class QrCodeHit {
    public String text;
    public String format;
    public List<QrPoint> points;
    public String error;
}

@JsonInclude(JsonInclude.Include.NON_NULL)
public static class QrPoint {
    public float x;
    public float y;
}

    public static class BoundingBox {
        public float left;
        public float bottom;
        public float right;
        public float top;

        static BoundingBox fromPdfRect(PDRectangle rect) {
            BoundingBox box = new BoundingBox();
            box.left = rect.getLowerLeftX();
            box.bottom = rect.getLowerLeftY();
            box.right = rect.getUpperRightX();
            box.top = rect.getUpperRightY();
            return box;
        }

        static BoundingBox fromTextPositions(List<TextPosition> positions, PDPage page) {
            if (positions == null || positions.isEmpty()) {
                return null;
            }
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float maxX = Float.MIN_VALUE;
            float maxY = Float.MIN_VALUE;
            for (TextPosition pos : positions) {
                minX = Math.min(minX, pos.getXDirAdj());
                minY = Math.min(minY, pos.getYDirAdj());
                maxX = Math.max(maxX, pos.getXDirAdj() + pos.getWidthDirAdj());
                maxY = Math.max(maxY, pos.getYDirAdj() + pos.getHeightDir());
            }
            PDRectangle cropBox = page.getCropBox();
            BoundingBox box = new BoundingBox();
            box.left = minX;
            box.right = maxX;
            box.top = cropBox.getHeight() - minY;
            box.bottom = cropBox.getHeight() - maxY;
            return box;
        }

        static BoundingBox fromDrawnImage(Point2D.Float p0, Point2D.Float p1) {
            BoundingBox box = new BoundingBox();
            box.left = Math.min(p0.x, p1.x);
            box.right = Math.max(p0.x, p1.x);
            box.bottom = Math.min(p0.y, p1.y);
            box.top = Math.max(p0.y, p1.y);
            return box;
        }

        PDRectangle toPdfRectangle() {
            return new PDRectangle(left, bottom, Math.max(1f, right - left), Math.max(1f, top - bottom));
        }

        float[] toQuadPoints() {
            return new float[]{left, top, right, top, left, bottom, right, bottom};
        }

        boolean overlaps(BoundingBox other, float tolerance) {
            return !(other.right < left - tolerance || other.left > right + tolerance || other.top < bottom - tolerance || other.bottom > top + tolerance);
        }

        String toKey() {
            return String.format(Locale.ROOT, "%.2f|%.2f|%.2f|%.2f", left, bottom, right, top);
        }
    }
}
