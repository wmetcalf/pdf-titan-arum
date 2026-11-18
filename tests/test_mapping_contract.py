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
    # a non-PDF is the JVM's "no %PDF header in the first 1024 bytes" report
    det = eng._build_detection(_report(fileMagic="Zip archive (DOCX/XLSX/JAR/APK)",
                                       parseError="File does not contain a PDF header"))
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


def test_detection_of_a_junk_prefixed_pdf_that_stopped_early_is_pdf():
    # encrypted / parse-error reports return before the structural anomalies are computed, so they
    # carry no header_offset; the JVM still found %PDF (else parseError says there is no header)
    det = eng._build_detection(_report(
        fileMagic="unknown (magic: 4a 55 4e 4b)",
        parseError="PDF is password-protected (use --password to supply one)"))
    assert (det.label, det.mime) == ("pdf", "application/pdf") and det.confidence < 1.0


def test_the_composite_js_findings_survive_a_flood_of_their_own_family():
    # the JVM appends the executable_dropper and CVE composite LAST; 250 ordinary suspicious_api
    # hits in the same family pushed both out of the capped warnings
    flood = [{"type": "suspicious_api", "indicator": "getField", "detail": "x"} for _ in range(250)]
    report = _report(jsIndicators=flood + [
        {"type": "executable_dropper", "indicator": "exportDataObject", "detail": "drop"},
        {"type": "cve_detection", "indicator": "CVE-2026-34261", "detail": "chain"}])
    codes = [w.code for w in eng._build_warnings(report)]
    assert "js_indicator.cve_detection" in codes and "js_indicator.executable_dropper" in codes



def test_every_non_flood_js_type_survives_a_suspicious_api_flood():
    # suspicious_action (UNC / NTLM theft) and suspicious_structure (CVE-2024-4367 FontMatrix,
    # CVE-2010-0188) are also appended after the per-script suspicious_api hits
    flood = [{"type": "suspicious_api", "indicator": "getField", "detail": "x"} for _ in range(240)]
    report = _report(jsIndicators=flood + [
        {"type": "suspicious_action", "indicator": "unc_path_gotor", "detail": "d"},
        {"type": "suspicious_structure", "indicator": "fontmatrix_injection", "detail": "d"}])
    codes = [w.code for w in eng._build_warnings(report)]
    assert "js_indicator.suspicious_action" in codes
    assert "js_indicator.suspicious_structure" in codes


def test_an_attachment_named_only_by_replaced_characters_keeps_its_family_id():
    # '@', '文件', 'файл': every character becomes '_', and stripping the trailing '_' left the id
    # 'titan_attachments', which the family glob titan_attachments_* does not match
    import fnmatch
    for name in ("@", "文件", "файл"):
        aid = eng._safe_artifact_id(f"titan/attachments/{name}", set())
        assert fnmatch.fnmatchcase(aid, "titan_attachments_*"), aid


def test_a_budget_trip_keeps_the_structural_slot_under_a_flood():
    # images_extracted_cap_exceeded / qr_scan_budget_exceeded say the report is INCOMPLETE; the JVM
    # appends them after the cosmetic file-structure anomalies
    report = _report(
        actions=[{"type": "URI", "detail": "x"} for _ in range(250)],
        structuralAnomalies=[{"type": "missing_binary_comment", "detail": "x"},
                             {"type": "images_extracted_cap_exceeded", "detail": "y"}])
    codes = [w.code for w in eng._build_warnings(report)]
    assert "structural.images_extracted_cap_exceeded" in codes, codes[-5:]


def test_no_single_js_flood_hides_another_indicator():
    # a flood is many hits of ONE indicator; every distinct indicator gets a slot before any repeat
    api = [{"type": "suspicious_api", "indicator": i, "detail": "d"}
           for i in ("exportDataObject", "nLaunch_autolaunch")]
    flood = [{"type": "suspicious_action", "indicator": "file_uri_action", "detail": "d"}] * 250
    struct = [{"type": "suspicious_structure", "indicator": "fontmatrix_injection", "detail": "d"}]
    codes = [w.code for w in eng._build_warnings(_report(jsIndicators=api + flood + struct))]
    assert codes.count("js_indicator.suspicious_api") == 2
    assert "js_indicator.suspicious_structure" in codes
