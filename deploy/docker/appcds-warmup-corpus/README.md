# AppCDS/AOT warmup corpus (Warm-Mode Task 6, `warm-plan.md` W-3)

`PdfTitanArumApp --appcds-warmup <dir>` (W-2, `PdfTitanArumApp.java:5563` `runAppcdsWarmup`) runs
every `*.pdf` file in this directory through the exact same `callWith(...)` entry point a real job
uses (QR scanning ON, OCR off, `password=null`), so an AOT-record build
(`-XX:AOTMode=record ... --appcds-warmup deploy/docker/appcds-warmup-corpus`) actually
class-loads and JITs titanarum's parser/render code paths before the AOT cache is created.
Without this, a warm snapshot only ever captures a JVM that booted and blocked on `control.go` —
PDFBox/JBIG2/JPEG2000/ZXing/phone/autolink classes load lazily on a document's *first* job, so
the sole restored job in a `jobs_per_recycle=1` pool would still cold-load and JIT the whole
pipeline (Finding C / risk register item 2 in `warm-plan.md`).

Every fixture here is small (under 2.5 KB) and built by the reproducible generator
`GenerateWarmupCorpus.java` in this same directory — nothing here is hand-edited.

## Regenerating the corpus

```sh
# from the repo root
mvn -q -DskipTests package                       # produces target/pdf-titan-arum-1.3.0.jar (shaded)

mkdir -p /tmp/warmup-gen-classes
javac -cp target/pdf-titan-arum-1.3.0.jar \
    -d /tmp/warmup-gen-classes \
    deploy/docker/appcds-warmup-corpus/GenerateWarmupCorpus.java

java -cp target/pdf-titan-arum-1.3.0.jar:/tmp/warmup-gen-classes \
    GenerateWarmupCorpus deploy/docker/appcds-warmup-corpus
```

The generator has no product-code dependency — only PDFBox and the two optional codec libraries
already in `pom.xml` (`jbig2-imageio`, `jai-imageio-jpeg2000`). It is compiled/run against the
project's own shaded jar purely because that jar already bundles those dependencies with their
`META-INF/services` ImageIO SPI registrations correctly merged (`ServicesResourceTransformer` in
the shade-plugin config) — the same mechanism the app itself relies on at runtime to discover the
JBIG2/JPEG2000 `ImageReader`/`ImageWriter` SPIs.

The QR fixture (`02-qr-code.pdf`) shells out to **system** `python3` (not the project venv, which
lacks the `qrcode` package) to render the QR PNG:
`python3 -c "import qrcode; qrcode.make('https://titanarum.warmup/qr').save(...)"`.
`python3` and the `qrcode` package must be available on `PATH` to regenerate this one fixture.

## Verifying consumption

```sh
mvn -q -DskipTests package
java -jar target/pdf-titan-arum-1.3.0.jar --appcds-warmup deploy/docker/appcds-warmup-corpus
```

Every file must print `APPCDS_WARMUP_OK <tmp-out-dir>` to stderr; the process must exit 0. A
`runAppcdsWarmup` per-file failure (`APPCDS_WARMUP_SKIP ...`) never aborts the run or fails the
exit code by design (a bad fixture should cost coverage, not the image build) — but for this
corpus, as generated, every file succeeds (no `APPCDS_WARMUP_SKIP` lines).

## Files and what each one exercises

| File | Exercises |
|---|---|
| `01-multipage-render.pdf` | **#1 priority.** 3 pages of distinct text. Forces `renderScreenshots()` → `PDFRenderer.renderImageWithDPI()` across multiple pages (the single most expensive code path — full page rasterization). Distinct text per page also avoids the pixel-identical-page screenshot dedup skip. |
| `02-qr-code.pdf` | A QR PNG (rendered via system `python3`+`qrcode`, encoding `https://titanarum.warmup/qr`) embedded as a `PDImageXObject` (via `LosslessFactory`) and drawn on the page. Exercises the drawn-image extraction path (`renderedImages`, source `drawn_xobject`) and `scanQrCodes()` → `ZXingReaderScanner.scan()` → `ProcessBuilder`-forked `ZXingReader` subprocess + JSON-line parsing. See "Known caveats" below re: QR *content* decode on this dev box. |
| `03-urls-phones.pdf` | One page of text with `https://` and `http://` URLs, a bare `www.` domain, and two international phone numbers (`+1 415…`, `+44 20…`). Exercises `autolink` URL extraction and `libphonenumber` phone-number extraction/geocoding. |
| `04-embedded-file.pdf` | **Two** embedded files: one via the catalog `/Names/EmbeddedFiles` name tree (`extractEmbeddedFileNameTree` → `extractNamedEmbeddedFiles`), one via a page `/Subtype /FileAttachment` annotation (`extractFileAttachmentAnnotations`). Both go through `saveEmbeddedFile`/`firstEmbeddedFile`/`PDComplexFileSpecification`/`PDEmbeddedFile`. |
| `05-javascript-openaction.pdf` | Catalog `/OpenAction` is a `PDActionJavaScript`. Exercises `extractJavaScript()` → `extractJavaScriptFromActionCos()` (catalog.openAction context) and the downstream `analyzeJavaScriptIndicators()` JS-indicator path. |
| `06-launch-action.pdf` | A page `PDAnnotationLink` whose `/A` is a `PDActionLaunch` (`/F (calc.exe)`, never executed — titanarum only parses the action dictionary). Exercises `extractLaunchActions()` → `extractLaunchFromActionCos()` on the per-page annotation-action context. |
| `07-encrypted.pdf` | `StandardProtectionPolicy` with a real owner password but a **blank** user password (128-bit, AES preferred). `--appcds-warmup` always calls `callWith(..., password=null)`; titanarum's load logic (`PdfTitanArumApp.java:512-529`) auto-retries a blank password on `InvalidPasswordException`, so this fixture **fully decrypts and runs the whole extraction pipeline** — not just the "wrong password" early-return branch — while still exercising the `StandardSecurityHandler`/decrypt-filter class-load path. See "Known gaps" for what this does *not* cover. |
| `08-acroform.pdf` | `extractFormFields()` (`PdfTitanArumApp.java:1559`) is titanarum's **CVE-2026-34261 detector**, not a generic field-value dumper: it only records a hit for a field whose `/V` is stored as a PDF Name (the exploit shape — a value normally expected to be a String, smuggled as a Name, optionally base64) or whose widget rect is near-zero-size ("hidden" field). A benign field with a short `COSString` value hits none of those branches. This fixture includes all three shapes the detector looks for: `applicant_name` (plain `Tx` field, but with a ≥40-char string value to trip the `large_string_value` branch), `payload_field` (`Tx` field whose `/V` is overwritten with a base64-looking `COSName` to trip the `base64_payload` branch → base64 decode + SHA-256 + text-sniff + artifact write), and `hidden_field` (0×0 rect widget, no `/V`, to trip the `hidden` branch). Plus `agree_to_terms`, an ordinary `PDCheckBox`/`Btn` field, for `PDCheckBox`/`PDButton` class-load breadth (its `/V` is always a short Name like `/Yes`, so it produces no hit — that's expected/correct AcroForm shape, not a gap). |
| `09-image-jpeg.pdf` | A synthetic RGB gradient encoded via `JPEGFactory.createFromImage()` and drawn on the page. Exercises the JPEG encode path used when building fixtures **and**, on extraction/render, PDFBox's built-in JPEG (`DCTDecode`) `ImageIO` decode path. |
| `10-image-jpeg2000.pdf` | **Attempted and achieved.** The same gradient encoded to a raw JP2 codestream via `ImageIO.write(img, "JPEG2000", ...)` (works because `jai-imageio-jpeg2000` registers **both** `J2KImageReaderSpi` and `J2KImageWriterSpi` — unlike `jbig2-imageio`, see below). The encoded bytes are wrapped directly in a `PDImageXObject` with an explicit `/JPXDecode` filter (there is no PDFBox `JPXFactory`; wiring goes through the public `PDImageXObject(document, encodedStream, COSName filter, w, h, bpc, colorSpace)` constructor) and drawn on the page. Exercises PDFBox's `JPXFilter` → `J2KImageReader` decode path on extraction/render. |

## Known gaps

**JBIG2: not synthesizable in this environment, by design of the dependency.** `org.apache.pdfbox:jbig2-imageio` ships **only** `JBIG2ImageReaderSpi` (`META-INF/services/javax.imageio.spi.ImageReaderSpi`) — there is no `ImageWriterSpi`, i.e. it is a decode-only ImageIO plugin, so `ImageIO.write(img, "jbig2", ...)` has no registered writer to call. This was verified by inspecting the jar contents directly (`jbig2-imageio-3.0.4.jar` has a `JBIG2ImageReader`/`JBIG2ImageReaderSpi` pair and no writer classes at all). Also checked and ruled out on this build host as alternate encode routes: no `jbig2enc` binary, Ghostscript (`gs` 10.02.1, present) has no JBIG2-encode `MonoImageFilter`/device for `pdfwrite` (its `libjbig2dec0` dependency is a *decoder* library only), and there is no JBIG2-encoding Python package installed. **Consequence:** the JBIG2 decode path (`org.apache.pdfbox.jbig2.JBIG2ImageReader`, reached via PDFBox's `JBIG2Filter` whenever a page image uses `/JBIG2Decode`) is *not* touched by this corpus and will not be AOT-linked by an AOT-record run over it. If JBIG2 coverage is later required, the practical options are: (a) source a small real-world JBIG2-encoded PDF fixture from an external corpus and hand-vet it, or (b) build/vendor a native `jbig2enc`-based encoder step outside this Java-only generator.

**Other titanarum code paths this corpus intentionally does NOT cover** (in scope terms: `--appcds-warmup` always calls `callWith(bytes, name, tmpOut, dpi, "default", skipQrScan=false, addLinkAnnotations=false, modifiedPdfOutput=null, password=null)`, so anything gated behind a different argument or a separate mode is out of reach from this harness no matter what the fixtures contain):

- **OCR** (`ocrScreenshots`/`ocrUrlCrops`/Tesseract) — both flags default `false` and `runAppcdsWarmup` never sets them.
- **AI digest** (`OpenAiAnalyzer`) — only invoked from the `--run`/CLI path when `--ai-url`/`OPENAI_API_KEY` is configured; `runAppcdsWarmup` never reaches that code.
- **`addLinkAnnotations=true`** (synthetic link-annotation insertion) and **`modifiedPdfOutput`** (rewritten-PDF output) — both hardcoded off/null in the warmup call.
- **Non-blank explicit `--password`** — the warmup harness only exercises the *blank-password auto-retry* branch (`PdfTitanArumApp.java:514-521`), never the `password != null && !password.isEmpty()` branch (`:512-513`) or the "wrong password supplied" early return (`:531-535`), since `password` is always `null`.
- **Corrupt/malformed-PDF lenient recovery** (xref rebuild by linear scan, `PdfTitanArumApp.java:536+`) and the **non-PDF-header** early return — all ten fixtures are well-formed, valid PDFs.
- **XFA scripts / dynamic XFA forms** (`extractXfaScripts`, `checkXfaImageFieldExploit` / CVE-2010-0188 `topmostSubform`+`ImageField` pattern) — not in this corpus's priority list; no XFA fixture included.
- **Multiple-revision / incremental-update documents** (`revisionTimeline`, cross-revision URL/screenshot diffing, `urlsChangedVisuallyHidden`) — every fixture here is a single-revision PDF.
- **Optional-content groups / layers** (`extractOcgLayers`) — not exercised.
- **Inline images** (`BI`/`ID`/`EI` content-stream operators) — all images here are XObjects; the inline-image decode branch is separate and not exercised.
- **I1 budget-exceeded paths** (`qr_scan_budget_exceeded`, `images_extracted_cap_exceeded` structural anomalies) and **I2's hard-halt watchdog** — these only fire on pathological/adversarial inputs (image floods, hangs), which would work against "small" fixtures; deliberately not simulated here.
- **CCITT Group 4 fax images** (`/CCITTFaxDecode`) — not attempted; PDFBox has native CCITT support so it wasn't a priority per the task's coverage list, but note it for completeness as a decode path this corpus doesn't touch.

## Known caveats (observed during local verification, not corpus defects)

- **QR *content* decode depends on the installed `ZXingReader` build.** On the machine this
  corpus was validated on, the system `ZXingReader` is zxing-cpp **2.2.1**, whose CLI does not
  support the `-json`/`-formats` flags `ZXingReaderScanner.buildCommand()` passes (it uses
  `-format` singular and has no JSON output mode). The subprocess still starts, titanarum still
  drains its stdout/stderr and attempts to parse it as JSON (`ZXingReaderScanner.scan()` /
  `parseJsonLines()`), and `callWith` still succeeds — `parseJsonLines` simply treats the
  usage/error text as non-JSON lines and returns zero results, which is the intended
  fail-open behavior. This means the `02-qr-code.pdf` fixture reliably exercises the
  fork/subprocess/stream-drain/JSON-parse code path (real AOT-cache value) but, on *this*
  particular host, does not actually decode `https://titanarum.warmup/qr` out of the image. A
  build image with a `zxing-cpp` version matching `ZXingReaderScanner`'s expected CLI (i.e. one
  that supports `-json -formats <FORMAT>`) will decode it end-to-end.
