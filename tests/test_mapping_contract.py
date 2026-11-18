"""Mapper behaviour behind blastbox-engine.toml: warning budget, root summary, detection,
and the gVisor stale-readdir reconstruction declaring what a real listing declares."""
import json
from pathlib import Path

from titanarum import engine as eng

FIX = Path(__file__).parent / "fixtures" / "report.min.json"


def _report(**over) -> dict:
    r = json.loads(FIX.read_text())
    r.update(over)
    return r


# --- warning-cap-hides-findings ------------------------------------------------------------

def test_warning_cap_keeps_every_finding_family():
    """300 low-signal form fields must not push the rarer, higher-severity families out."""
    report = _report(
        formFields=[{"name": f"f{i}", "flags": []} for i in range(300)],
        ocgLayers=[{"name": "hid", "defaultState": "OFF", "suspicious": True}],
        urls=[{"url": "https://x.example", "flags": ["mismatch"]}],
        embeddedFiles=[{"originalName": "a.pdf", "mimeTypeMismatch": "exe"}],
        launchActions=[{"operation": "open", "file": "cmd.exe", "parameters": "/c"}],
    )
    warns = eng._build_warnings(report)
    codes = [w.code for w in warns]
    assert len(warns) <= 200
    for code in ("hidden_layer", "url_flag", "embedded_mismatch", "launch_action", "form_field"):
        assert code in codes, code
    assert codes[-1] == "warnings_truncated"


def test_warnings_are_ordered_high_severity_first():
    report = _report(
        formFields=[{"name": "f", "flags": []}],
        structuralAnomalies=[{"type": "missing_eof", "detail": "x"}],
        urls=[{"url": "https://x.example", "flags": ["f"]}],
        jsIndicators=[{"type": "suspicious_api", "indicator": "eval", "detail": "d"}],
    )
    codes = [w.code for w in eng._build_warnings(report)]
    assert codes == ["js_indicator.suspicious_api", "url_flag", "structural.missing_eof",
                     "form_field"]


def test_one_family_fills_the_budget_when_alone():
    report = _report(formFields=[{"name": f"f{i}", "flags": []} for i in range(300)])
    codes = [w.code for w in eng._build_warnings(report)]
    assert codes.count("form_field") == 199 and codes[-1] == "warnings_truncated"


def test_no_truncation_marker_under_the_cap():
    report = _report(formFields=[{"name": f"f{i}", "flags": []} for i in range(10)])
    codes = [w.code for w in eng._build_warnings(report)]
    assert codes == ["form_field"] * 10


# --- root-fields-unversioned ---------------------------------------------------------------

_ALWAYS = ("schema_version", "pdf_object_hash", "file_magic", "doc_pdf_version", "doc_title",
           "doc_author", "doc_producer", "doc_creator", "n_urls", "n_javascript",
           "n_launch_actions", "n_actions", "n_embedded_files", "n_js_indicators",
           "n_structural_anomalies", "n_stream_length_anomalies",
           "n_metadata_spoofing_indicators", "n_form_fields", "n_tables")


def test_summary_is_versioned_and_every_field_is_always_present():
    bare = _report()
    bare.pop("pdfObjectHash")
    bare.pop("fileMagic")
    bare["documentInfo"] = {}
    fields = eng._summary_fields(bare)
    for name in _ALWAYS:
        assert name in fields, name
    assert fields["schema_version"] == eng.SUMMARY_SCHEMA_VERSION == "1"
    assert fields["pdf_object_hash"] == "" and fields["doc_title"] == "" and fields["n_urls"] == 0


# --- detected-is-constant ------------------------------------------------------------------

def test_detection_is_a_type_and_a_mime():
    det = eng._build_detection(_report(fileMagic="%PDF-1.6"))
    assert (det.label, det.mime, det.confidence) == ("pdf", "application/pdf", 1.0)


def test_detection_of_a_non_pdf_is_not_pdf():
    det = eng._build_detection(_report(fileMagic="Zip archive (DOCX/XLSX/JAR/APK)"))
    assert (det.label, det.mime, det.confidence) == ("unknown", "application/octet-stream", 0.0)
    det = eng._build_detection(_report(fileMagic=""))
    assert det.label == "unknown"


# --- reconstruct-drops-families ------------------------------------------------------------

def test_reconstruction_declares_what_the_listing_declares(tmp_path):
    outdir = tmp_path / "out"
    rd = outdir / "titan"
    files = [
        "screenshots/page-0001.png", "url_crops/url-p0001-0001.png",
        "launch_actions/launch-0001.bin", "images_rendered/drawn-page-0001-image-0001.png",
        "images_rendered/drawn-page-0001-image-0001-original.jpx",
        "images_resources/resource-page-0001-Im0.png",
        "images_resources/resource-page-0001-Im0-original.jb2",
        "revisions/rev-001/page-0001.png", "revisions/rev-001/url_crops/url-p0001-0001.png",
        "scripts/field-payload-decoded.js.txt", "xfa/xfa.xml",
    ]
    for f in files:
        (rd / f).parent.mkdir(parents=True, exist_ok=True)
        (rd / f).write_bytes(b"x")
    report = _report(
        screenshots=[{"page": 1, "path": "screenshots/page-0001.png"}],
        urls=[{"url": "https://a", "cropPath": "url_crops/url-p0001-0001.png"},
              {"url": "https://b", "cropPath": "url_crops/url-p0001-0001.png"}],
        launchActions=[{"operation": "open", "artifact": "launch_actions/launch-0001.bin"}],
        formFields=[{"name": "payload", "flags": ["base64_payload"],
                     "decodedArtifact": "scripts/field-payload-decoded.js.txt"}],
        renderedImages=[{
            "path": "images_rendered/drawn-page-0001-image-0001.png",
            "originalPath": "images_rendered/drawn-page-0001-image-0001-original.jpx"}],
        resourceImages=[{"path": "images_resources/resource-page-0001-Im0.png",
                         "originalPath": "images_resources/resource-page-0001-Im0-original.jb2"}],
        revisions=[{"revision": 1,
                    "screenshots": [{"page": 1, "path": "revisions/rev-001/page-0001.png"}],
                    "urls": [{"url": "https://a",
                              "cropPath": "revisions/rev-001/url_crops/url-p0001-0001.png"}]}],
    )
    (rd / "report.json").write_text(json.dumps(report))

    listed = eng._enumerate_artifacts(outdir, rd, report)
    rebuilt = eng._reconstruct_artifacts_from_report(outdir, rd, report, set())
    as_set = lambda arts: {(a.path, a.kind) for a in arts}  # noqa: E731
    assert as_set(rebuilt) == as_set(listed)
    assert len({a.id for a in rebuilt}) == len(rebuilt)


def test_detection_of_a_pdf_behind_leading_junk_is_pdf():
    # the JVM accepts %PDF within the first 1024 bytes and records a header_offset anomaly; its
    # fileMagic is taken from byte 0, so it names the junk, not the PDF it parsed
    det = eng._build_detection(_report(
        fileMagic="unknown",
        structuralAnomalies=[{"type": "header_offset", "detail": "%PDF header at byte 7",
                              "offset": 7}]))
    assert (det.label, det.mime) == ("pdf", "application/pdf")
    assert 0.0 < det.confidence < 1.0
