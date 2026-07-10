import json
from pathlib import Path

from blastbox.contract import DeclaredArtifact, Detection, EmbeddedResource, Warning

from titanarum import engine as eng

FIX = Path(__file__).parent / "fixtures" / "report.min.json"


def _report(**over) -> dict:
    r = json.loads(FIX.read_text())
    r.update(over)
    return r


def _make_tree(tmp_path: Path) -> tuple[Path, Path]:
    outdir = tmp_path / "out"
    report_dir = outdir / "titan"
    (report_dir / "screenshots").mkdir(parents=True)
    (report_dir / "report.json").write_text(json.dumps(_report()))
    (report_dir / "screenshots" / "page-0001.png").write_bytes(b"\x89PNG stub")
    return outdir, report_dir


def test_enumerate_declares_report_and_files(tmp_path):
    outdir, report_dir = _make_tree(tmp_path)
    arts = eng._enumerate_artifacts(outdir, report_dir, _report())
    paths = {a.path for a in arts}
    assert "titan/report.json" in paths
    assert "titan/screenshots/page-0001.png" in paths
    assert all(isinstance(a, DeclaredArtifact) for a in arts)
    # every id is contract-valid and unique
    ids = [a.id for a in arts]
    assert len(ids) == len(set(ids))
    report_art = next(a for a in arts if a.path == "titan/report.json")
    assert report_art.kind == "report"


def test_build_detection_defaults_to_pdf(tmp_path):
    det = eng._build_detection(_report())
    assert isinstance(det, Detection)
    assert det.source == "titanarum"
    assert det.confidence == 1.0
    assert det.mime  # non-empty


def test_build_warnings_maps_js_indicators():
    report = _report(jsIndicators=[
        {"type": "suspicious_api", "indicator": "SOAP.streamDecode",
         "detail": "present", "count": 1, "context": "doc"}])
    warns = eng._build_warnings(report)
    assert any(w.code.startswith("js_indicator.") for w in warns)
    assert all(isinstance(w, Warning) for w in warns)


def test_build_warnings_parse_error_and_timeout():
    report = _report(parseError="File does not contain a PDF header",
                     timedOut=True, timedOutAfterMs=1234)
    warns = eng._build_warnings(report)
    codes = {w.code for w in warns}
    assert "parse_error" in codes
    assert "timed_out" in codes


def test_build_warnings_capped():
    report = _report(urls=[{"url": f"http://x/{i}", "flags": ["suspicious"], "page": 1,
                            "source": "text", "annotationAlreadyPresent": False}
                           for i in range(1000)])
    warns = eng._build_warnings(report, cap=50)
    assert len(warns) <= 50


def test_status_rejected_for_encrypted():
    assert eng._status_from_report(
        _report(parseError="PDF is password-protected")) == "rejected"
    assert eng._status_from_report(_report()) == "ok"


def test_build_payload_is_embedded_resource_with_summary(tmp_path):
    outdir, report_dir = _make_tree(tmp_path)
    arts = eng._enumerate_artifacts(outdir, report_dir, _report())
    payload = eng._build_payload(_report(pageCount=3), arts)
    assert isinstance(payload, EmbeddedResource)
    assert payload.metadata is not None
    assert payload.metadata.fields["document_sha256"]
    assert payload.metadata.fields["page_count"] == 3
    # full report is NOT embedded by default
    assert "titanarum_report" not in payload.metadata.fields


def test_embedded_files_become_children(tmp_path):
    docx_mime = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    report = _report(embeddedFiles=[
        {"originalName": "evil.docx", "size": 42, "sha256": "b" * 64,
         "mimeType": "application/octet-stream",
         "detectedMimeType": docx_mime,
         "mimeTypeMismatch": "declared octet-stream, detected docx"}])
    payload = eng._build_payload(report, [])
    kids = [c for c in payload.children if isinstance(c, EmbeddedResource)]
    assert len(kids) == 1
    assert kids[0].content_type.endswith("wordprocessingml.document")
    assert kids[0].metadata.fields["original_name"] == "evil.docx"
