import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionJavaScript;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionLaunch;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationFileAttachment;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Warm-Mode Task 6 (W-3, warm-plan.md): builds the AppCDS/AOT warmup PDF corpus consumed by
 * {@code PdfTitanArumApp --appcds-warmup <dir>}. Each PDF is a small, purpose-built fixture that
 * forces class-load + JIT of one (or a couple) of titanarum's expensive parser/render code paths,
 * so an AOT-record run over this directory actually links those classes into the cache instead of
 * capturing a JVM that only ever booted.
 *
 * <p>This is a reproducible generator, not a fixture to hand-edit: re-running it regenerates the
 * whole corpus from scratch. It has NO dependency on titanarum's product code — only on PDFBox and
 * the two optional image-codec libraries already in {@code pom.xml} (jbig2-imageio,
 * jai-imageio-jpeg2000) — so it is safe to compile/run against the project's own shaded jar
 * (which bundles both, with their {@code META-INF/services} SPI registrations merged by the
 * shade plugin's ServicesResourceTransformer).
 *
 * <p>Build + run (from the repo root, after {@code mvn -q -DskipTests package} has produced
 * {@code target/pdf-titan-arum-1.3.0.jar}):
 * <pre>
 *   javac -cp target/pdf-titan-arum-1.3.0.jar \
 *       -d /tmp/warmup-gen-classes \
 *       deploy/docker/appcds-warmup-corpus/GenerateWarmupCorpus.java
 *   java -cp target/pdf-titan-arum-1.3.0.jar:/tmp/warmup-gen-classes \
 *       GenerateWarmupCorpus deploy/docker/appcds-warmup-corpus
 * </pre>
 *
 * <p>See {@code deploy/docker/appcds-warmup-corpus/README.md} for exactly which titanarum code
 * path each generated PDF is meant to exercise, and which paths are deliberately NOT covered.
 */
public class GenerateWarmupCorpus {

    private static final PDRectangle PAGE_SIZE = PDRectangle.LETTER;

    public static void main(String[] args) throws Exception {
        Path outDir = Paths.get(args.length > 0 ? args[0] : "deploy/docker/appcds-warmup-corpus");
        Files.createDirectories(outDir);

        System.out.println("Generating AppCDS warmup corpus into " + outDir.toAbsolutePath());

        buildMultiPageRender(outDir.resolve("01-multipage-render.pdf"));
        buildQrCode(outDir.resolve("02-qr-code.pdf"));
        buildUrlsAndPhones(outDir.resolve("03-urls-phones.pdf"));
        buildEmbeddedFile(outDir.resolve("04-embedded-file.pdf"));
        buildJsOpenAction(outDir.resolve("05-javascript-openaction.pdf"));
        buildLaunchAction(outDir.resolve("06-launch-action.pdf"));
        buildEncrypted(outDir.resolve("07-encrypted.pdf"));
        buildAcroForm(outDir.resolve("08-acroform.pdf"));
        buildJpegImage(outDir.resolve("09-image-jpeg.pdf"));
        boolean jp2Ok = buildJpeg2000Image(outDir.resolve("10-image-jpeg2000.pdf"));

        System.out.println("JPEG2000 fixture generated: " + jp2Ok);
        System.out.println("JBIG2 fixture: NOT generated -- jbig2-imageio (org.apache.pdfbox:jbig2-imageio)"
                + " ships a decode-only ImageIO SPI (JBIG2ImageReaderSpi, no writer); no JBIG2 encoder is"
                + " available on this project's classpath or on this build host (checked: no jbig2enc, no"
                + " gs pdfwrite JBIG2Encode filter, no Python jbig2 encoder). See README.md 'Known gaps'.");
        System.out.println("Done.");
    }

    // ---------------------------------------------------------------------------------------
    // 1. Multi-page rendered (#1 priority -- the screenshot RENDER path, PDFRenderer.renderImageWithDPI)
    // ---------------------------------------------------------------------------------------
    private static void buildMultiPageRender(Path out) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            String[] pages = {
                "Page one of a multi-page warmup fixture.\nThis page exists purely to force the"
                    + " PDFRenderer screenshot-render path across more than one page.",
                "Page two. Different text content so page-dedup (identical-pixel screenshot"
                    + " skip) in renderScreenshots() does not fire.",
                "Page three. The default --appcds-warmup pagesSpec processes the first four"
                    + " pages (or all of them, if fewer), so all three pages here get rendered."
            };
            for (String text : pages) {
                addTextPage(doc, text);
            }
            doc.save(out.toFile());
        }
        System.out.println("wrote " + out);
    }

    // ---------------------------------------------------------------------------------------
    // 2. QR-bearing (ZXingReader subprocess path via scanQrCodes)
    // ---------------------------------------------------------------------------------------
    private static void buildQrCode(Path out) throws Exception {
        Path qrPng = Files.createTempFile("titanarum-warmup-qr-", ".png");
        try {
            // Per task instructions: the project venv lacks the `qrcode` package, but system
            // python3 has it. Shell out to system python3 to render the QR PNG.
            ProcessBuilder pb = new ProcessBuilder(
                    "python3", "-c",
                    "import qrcode; qrcode.make('https://titanarum.warmup/qr').save('" + qrPng.toAbsolutePath() + "')");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            byte[] procOutput = p.getInputStream().readAllBytes();
            int rc = p.waitFor();
            if (rc != 0) {
                throw new IllegalStateException("system python3 qrcode generation failed, rc=" + rc
                        + " output=" + new String(procOutput, StandardCharsets.UTF_8));
            }

            BufferedImage qrImage = ImageIO.read(qrPng.toFile());
            if (qrImage == null) {
                throw new IllegalStateException("ImageIO could not read generated QR PNG " + qrPng);
            }

            try (PDDocument doc = new PDDocument()) {
                PDPage page = new PDPage(PAGE_SIZE);
                doc.addPage(page);
                PDImageXObject xobj = LosslessFactory.createFromImage(doc, qrImage);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, PAGE_SIZE.getHeight() - 72);
                    cs.showText("QR-bearing warmup fixture -- encodes https://titanarum.warmup/qr");
                    cs.endText();
                    float size = 200;
                    cs.drawImage(xobj, 72, PAGE_SIZE.getHeight() - 72 - 24 - size, size, size);
                }
                doc.save(out.toFile());
            }
            System.out.println("wrote " + out);
        } finally {
            Files.deleteIfExists(qrPng);
        }
    }

    // ---------------------------------------------------------------------------------------
    // 3. Text with URLs + phone numbers (autolink + libphonenumber extraction path)
    // ---------------------------------------------------------------------------------------
    private static void buildUrlsAndPhones(Path out) throws IOException {
        String text = "Contact titanarum support at https://titanarum.example.com/support or write to\n"
                + "http://example.org/docs/warmup for background reading.\n"
                + "US office: +1 (415) 555-0182.  UK office: +44 20 7946 0958.\n"
                + "Also reachable via www.titanarum-warmup.example for a bare-domain autolink case.";
        try (PDDocument doc = new PDDocument()) {
            addTextPage(doc, text);
            doc.save(out.toFile());
        }
        System.out.println("wrote " + out);
    }

    // ---------------------------------------------------------------------------------------
    // 4. Embedded file (catalog Names/EmbeddedFiles tree AND a FileAttachment annotation --
    //    exercises both extractEmbeddedFileNameTree() and extractFileAttachmentAnnotations())
    // ---------------------------------------------------------------------------------------
    private static void buildEmbeddedFile(Path out) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PAGE_SIZE);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, PAGE_SIZE.getHeight() - 72);
                cs.showText("Embedded-file warmup fixture: one catalog-level attachment, one annotation attachment.");
                cs.endText();
            }

            // Catalog-level (Names/EmbeddedFiles) attachment.
            byte[] catalogAttachmentBytes =
                    "This is a catalog-level embedded attachment used for AOT warmup.\n"
                            .getBytes(StandardCharsets.UTF_8);
            PDComplexFileSpecification catalogSpec = new PDComplexFileSpecification();
            catalogSpec.setFile("notes.txt");
            PDEmbeddedFile catalogEmbedded = new PDEmbeddedFile(doc,
                    new ByteArrayInputStream(catalogAttachmentBytes));
            catalogEmbedded.setSubtype("text/plain");
            catalogSpec.setEmbeddedFile(catalogEmbedded);

            PDEmbeddedFilesNameTreeNode efTree = new PDEmbeddedFilesNameTreeNode();
            efTree.setNames(java.util.Map.of("notes.txt", catalogSpec));
            PDDocumentNameDictionary names = new PDDocumentNameDictionary(doc.getDocumentCatalog());
            names.setEmbeddedFiles(efTree);
            doc.getDocumentCatalog().setNames(names);

            // Page-level FileAttachment annotation.
            byte[] annotationAttachmentBytes =
                    "This is an annotation file-attachment used for AOT warmup.\n"
                            .getBytes(StandardCharsets.UTF_8);
            PDComplexFileSpecification annotSpec = new PDComplexFileSpecification();
            annotSpec.setFile("annotation-attachment.txt");
            PDEmbeddedFile annotEmbedded = new PDEmbeddedFile(doc,
                    new ByteArrayInputStream(annotationAttachmentBytes));
            annotEmbedded.setSubtype("text/plain");
            annotSpec.setEmbeddedFile(annotEmbedded);

            PDAnnotationFileAttachment attachment = new PDAnnotationFileAttachment();
            attachment.setFile(annotSpec);
            attachment.setRectangle(new PDRectangle(72, PAGE_SIZE.getHeight() - 140, 20, 20));
            attachment.setAttachmentName(PDAnnotationFileAttachment.ATTACHMENT_NAME_PAPERCLIP);
            page.getAnnotations().add(attachment);

            doc.save(out.toFile());
        }
        System.out.println("wrote " + out);
    }

    // ---------------------------------------------------------------------------------------
    // 5a. JavaScript / OpenAction (JS-indicator extraction path, extractJavaScript())
    // ---------------------------------------------------------------------------------------
    private static void buildJsOpenAction(Path out) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            addTextPage(doc, "JavaScript/OpenAction warmup fixture -- see catalog /OpenAction.");
            PDActionJavaScript js = new PDActionJavaScript("app.alert('titanarum warmup');");
            doc.getDocumentCatalog().setOpenAction(js);
            doc.save(out.toFile());
        }
        System.out.println("wrote " + out);
    }

    // ---------------------------------------------------------------------------------------
    // 5b. Launch action (extractLaunchActions() path)
    // ---------------------------------------------------------------------------------------
    private static void buildLaunchAction(Path out) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PAGE_SIZE);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, PAGE_SIZE.getHeight() - 72);
                cs.showText("Launch-action warmup fixture -- link annotation with a /Launch action.");
                cs.endText();
            }

            PDActionLaunch launch = new PDActionLaunch();
            launch.setF("calc.exe"); // never executed -- titanarum only parses the action dictionary

            PDAnnotationLink link = new PDAnnotationLink();
            link.setRectangle(new PDRectangle(72, PAGE_SIZE.getHeight() - 100, 200, 20));
            link.setAction(launch);
            page.getAnnotations().add(link);

            doc.save(out.toFile());
        }
        System.out.println("wrote " + out);
    }

    // ---------------------------------------------------------------------------------------
    // 6. Encrypted (password-protected) PDF -- crypto/security-handler class-load path.
    //    Owner password set, USER password left BLANK: --appcds-warmup always calls callWith()
    //    with password=null, and PdfTitanArumApp's load logic auto-retries a blank password on
    //    InvalidPasswordException (PdfTitanArumApp.java ~518-522) -- so this fixture decrypts
    //    successfully and the full extraction pipeline runs on it, in addition to touching the
    //    StandardSecurityHandler/decrypt-filter classes.
    // ---------------------------------------------------------------------------------------
    private static void buildEncrypted(Path out) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            addTextPage(doc, "Encrypted warmup fixture, page 1 of 2.");
            addTextPage(doc, "Encrypted warmup fixture, page 2 of 2.");

            AccessPermission permission = new AccessPermission();
            StandardProtectionPolicy policy = new StandardProtectionPolicy(
                    "titanarum-owner-pw-warmup", "", permission);
            policy.setEncryptionKeyLength(128);
            policy.setPreferAES(true);
            doc.protect(policy);

            doc.save(out.toFile());
        }
        System.out.println("wrote " + out);
    }

    // ---------------------------------------------------------------------------------------
    // 7. AcroForm form fields. extractFormFields() (PdfTitanArumApp.java ~1559) is NOT a generic
    //    field-value dumper -- it is titanarum's CVE-2026-34261 detector: it only records a hit
    //    for a field whose /V is stored as a PDF Name (the exploit signature: a value normally
    //    expected to be a String, smuggled as a Name -- optionally hex-escaped/base64) or whose
    //    widget rect is near-zero-size ("hidden" field). A plain benign field with a short
    //    COSString value produces NO hit and skips the interesting branches entirely, so this
    //    fixture deliberately includes all three shapes the detector looks for:
    //      - "applicant_name": ordinary Tx field, but with a >=40-char COSString value, to trip
    //        the large_string_value branch.
    //      - "payload_field": Tx field whose /V is overwritten (bypassing PDField.setValue(),
    //        which would coerce it to a COSString) with a base64-looking COSName -- exercises
    //        the base64-decode + sha256 + text-sniff + artifact-write branch.
    //      - "hidden_field": a widget with a 0x0 rect and no /V -- exercises the "hidden" branch.
    //    Plus a checkbox (Btn field, /V is always a Name like /Yes -- normal AcroForm shape, but
    //    too short to trip any flag) for class-load breadth on PDCheckBox/PDButton.
    // ---------------------------------------------------------------------------------------
    private static void buildAcroForm(Path out) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PAGE_SIZE);
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, PAGE_SIZE.getHeight() - 72);
                cs.showText("AcroForm warmup fixture -- text field, checkbox, base64-name field, hidden field.");
                cs.endText();
            }

            PDAcroForm acroForm = new PDAcroForm(doc);
            doc.getDocumentCatalog().setAcroForm(acroForm);
            PDResources resources = new PDResources();
            resources.put(COSName.HELV, new PDType1Font(Standard14Fonts.FontName.HELVETICA));
            acroForm.setDefaultResources(resources);
            acroForm.setDefaultAppearance("/Helv 12 Tf 0 g");

            PDTextField textField = new PDTextField(acroForm);
            textField.setPartialName("applicant_name");
            acroForm.getFields().add(textField);
            PDAnnotationWidget textWidget = textField.getWidgets().get(0);
            textWidget.setRectangle(new PDRectangle(72, PAGE_SIZE.getHeight() - 120, 200, 20));
            textWidget.setPage(page);
            page.getAnnotations().add(textWidget);
            // >=40 chars (MIN_BASE64_NAME_LEN) so extractFormFields' large_string_value branch fires.
            textField.setValue("Jane Warmup -- a value long enough to trip the large-string-value check");

            PDCheckBox checkBox = new PDCheckBox(acroForm);
            checkBox.setPartialName("agree_to_terms");
            acroForm.getFields().add(checkBox);
            PDAnnotationWidget checkWidget = checkBox.getWidgets().get(0);
            checkWidget.setRectangle(new PDRectangle(72, PAGE_SIZE.getHeight() - 150, 20, 20));
            checkWidget.setPage(page);
            page.getAnnotations().add(checkWidget);
            checkBox.check();

            PDTextField payloadField = new PDTextField(acroForm);
            payloadField.setPartialName("payload_field");
            acroForm.getFields().add(payloadField);
            PDAnnotationWidget payloadWidget = payloadField.getWidgets().get(0);
            payloadWidget.setRectangle(new PDRectangle(72, PAGE_SIZE.getHeight() - 180, 200, 20));
            payloadWidget.setPage(page);
            page.getAnnotations().add(payloadWidget);
            String base64Payload = java.util.Base64.getEncoder().encodeToString(
                    "AOT warmup base64 payload simulating CVE-2026-34261".getBytes(StandardCharsets.UTF_8));
            payloadField.getCOSObject().setItem(COSName.V, COSName.getPDFName(base64Payload));

            PDTextField hiddenField = new PDTextField(acroForm);
            hiddenField.setPartialName("hidden_field");
            acroForm.getFields().add(hiddenField);
            PDAnnotationWidget hiddenWidget = hiddenField.getWidgets().get(0);
            hiddenWidget.setRectangle(new PDRectangle(72, PAGE_SIZE.getHeight() - 200, 0, 0));
            hiddenWidget.setPage(page);
            page.getAnnotations().add(hiddenWidget);

            doc.save(out.toFile());
        }
        System.out.println("wrote " + out);
    }

    // ---------------------------------------------------------------------------------------
    // 8a. JPEG image (JPEGFactory encode path + ImageIO JPEG decode on extraction/render)
    // ---------------------------------------------------------------------------------------
    private static void buildJpegImage(Path out) throws IOException {
        BufferedImage img = gradientImage(120, 90);
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PAGE_SIZE);
            doc.addPage(page);
            PDImageXObject xobj = JPEGFactory.createFromImage(doc, img);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, PAGE_SIZE.getHeight() - 72);
                cs.showText("JPEG image warmup fixture.");
                cs.endText();
                cs.drawImage(xobj, 72, PAGE_SIZE.getHeight() - 72 - 24 - 180, 240, 180);
            }
            doc.save(out.toFile());
        }
        System.out.println("wrote " + out);
    }

    // ---------------------------------------------------------------------------------------
    // 8b. JPEG2000 image (attempt): jai-imageio-jpeg2000 ships BOTH J2KImageReaderSpi and
    //     J2KImageWriterSpi (unlike jbig2-imageio, which is decode-only), so ImageIO.write(...,
    //     "JPEG2000", ...) can actually encode a .jp2 codestream here. The raw encoded bytes are
    //     then wrapped directly in a PDImageXObject with the /JPXDecode filter (there is no
    //     PDFBox factory for this -- JPXFactory does not exist -- so the filter + raw stream are
    //     wired up via the public PDImageXObject(document, encodedStream, filter, w, h, bpc, cs)
    //     constructor). Returns false (fixture NOT written) if JPEG2000 encoding is unavailable.
    // ---------------------------------------------------------------------------------------
    private static boolean buildJpeg2000Image(Path out) throws IOException {
        BufferedImage img = gradientImage(120, 90);
        ByteArrayOutputStream jp2Bytes = new ByteArrayOutputStream();
        boolean encoded = ImageIO.write(img, "JPEG2000", jp2Bytes);
        if (!encoded) {
            System.out.println("SKIP " + out + " -- no registered ImageIO writer for JPEG2000 on this classpath");
            return false;
        }

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PAGE_SIZE);
            doc.addPage(page);
            PDImageXObject xobj = new PDImageXObject(doc,
                    new ByteArrayInputStream(jp2Bytes.toByteArray()),
                    COSName.JPX_DECODE,
                    img.getWidth(), img.getHeight(),
                    8, PDDeviceRGB.INSTANCE);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, PAGE_SIZE.getHeight() - 72);
                cs.showText("JPEG2000 (JPX) image warmup fixture.");
                cs.endText();
                cs.drawImage(xobj, 72, PAGE_SIZE.getHeight() - 72 - 24 - 180, 240, 180);
            }
            doc.save(out.toFile());
        }
        System.out.println("wrote " + out);
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Shared helpers
    // ---------------------------------------------------------------------------------------
    private static void addTextPage(PDDocument doc, String text) throws IOException {
        PDPage page = new PDPage(PAGE_SIZE);
        doc.addPage(page);
        try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.newLineAtOffset(72, PAGE_SIZE.getHeight() - 72);
            float leading = 16f;
            for (String line : text.split("\n")) {
                cs.showText(line);
                cs.newLineAtOffset(0, -leading);
            }
            cs.endText();
        }
    }

    private static BufferedImage gradientImage(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int r = (int) (255.0 * x / w);
                int g = (int) (255.0 * y / h);
                int b = 128;
                img.setRGB(x, y, new Color(r, g, b).getRGB());
            }
        }
        return img;
    }
}
