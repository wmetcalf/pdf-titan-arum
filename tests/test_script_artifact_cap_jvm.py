"""Script artifacts are bounded on disk at MAX_SCRIPT_BYTES (the contract's titan/scripts/*
max_bytes), on every path that writes one, while indicator analysis still sees the WHOLE payload.

Drives the real jar: a JavaScript COSString (not a stream) over the cap, and a base64 form-field
payload whose suspicious API sits past the cap.
"""
from __future__ import annotations

import base64
import json
import shutil
from pathlib import Path

import pytest

REPO = Path(__file__).resolve().parents[1]
JAR = REPO / "target" / "pdf-titan-arum-1.3.0.jar"
CAP = 10 * 1024 * 1024


def _pdf(objs: list[bytes]) -> bytes:
    out = bytearray(b"%PDF-1.7\n")
    offsets = []
    for i, body in enumerate(objs, start=1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % i + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
    for off in offsets:
        out += b"%010d 00000 n \n" % off
    out += b"trailer<</Size %d/Root 1 0 R>>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)
    return bytes(out)


def _run(tmp_path: Path, monkeypatch: pytest.MonkeyPatch, pdf_bytes: bytes) -> tuple[Path, dict]:
    if not shutil.which("java") or not JAR.is_file():
        pytest.skip("needs java and the built jar")
    from blastbox.limits import Limits

    from titanarum.engine import TitanArumEngine

    for k in ("TITANARUM_JAVA_OPTS", "TITANARUM_AOT_CACHE"):
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("TITANARUM_WORKER_JAR", str(JAR))
    monkeypatch.setenv("TITANARUM_SKIP_SCREENSHOTS", "1")
    monkeypatch.setenv("TITANARUM_SKIP_IMAGES", "1")
    pdf = tmp_path / "in.pdf"
    pdf.write_bytes(pdf_bytes)
    out = tmp_path / "out"
    out.mkdir()
    result = TitanArumEngine().detonate(pdf, out, Limits.from_env())
    assert result.status == "ok"
    return out / "titan", json.loads((out / "titan" / "report.json").read_text())


@pytest.mark.jvm
def test_a_javascript_string_over_the_cap_is_written_capped(tmp_path, monkeypatch):
    js = b"var a='" + b"A" * (CAP + 4096) + b"';"
    titan, report = _run(tmp_path, monkeypatch, _pdf([
        b"<</Type/Catalog/Pages 2 0 R/OpenAction 3 0 R>>",
        b"<</Type/Pages/Kids[]/Count 0>>",
        b"<</S/JavaScript/JS(" + js + b")>>",
    ]))
    files = list((titan / "scripts").glob("*"))
    assert files, report.get("javascript")
    assert all(f.stat().st_size <= CAP for f in files), [(f.name, f.stat().st_size) for f in files]


@pytest.mark.jvm
# a \x01 in the first 512 bytes classifies the payload binary (.bin)
@pytest.mark.parametrize("lead", [b"", b"\x01"])
def test_a_form_payload_past_the_cap_is_still_analysed(tmp_path, monkeypatch, lead):
    payload = lead + b" " * (CAP + 3) + b"this.exportDataObject({cName:'x'});"
    payload += b" " * (-len(payload) % 3)
    name = base64.b64encode(payload).replace(b"/", b"#2F").replace(b"+", b"#2B")
    titan, report = _run(tmp_path, monkeypatch, _pdf([
        b"<</Type/Catalog/Pages 2 0 R/AcroForm<</Fields[4 0 R]>>>>",
        b"<</Type/Pages/Kids[3 0 R]/Count 1>>",
        b"<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Annots[4 0 R]>>",
        b"<</Type/Annot/Subtype/Widget/FT/Tx/T(payload)/Rect[0 0 0 0]/P 3 0 R/V/" + name + b">>",
    ]))
    decoded = [f for f in (titan / "scripts").glob("field-*-decoded*")]
    assert decoded and all(f.stat().st_size <= CAP for f in decoded)
    hits = [j for j in report.get("jsIndicators") or []
            if j.get("indicator") == "exportDataObject"
            and "decodedPayload" in (j.get("context") or "")]
    assert hits, report.get("jsIndicators")


def _form_pdf(fields: list[tuple[bytes, bytes]]) -> bytes:
    """A one-page PDF whose AcroForm fields are (/T name, /V Name payload) widgets."""
    refs = b" ".join(b"%d 0 R" % (4 + i) for i in range(len(fields)))
    objs = [b"<</Type/Catalog/Pages 2 0 R/AcroForm<</Fields[" + refs + b"]>>>>",
            b"<</Type/Pages/Kids[3 0 R]/Count 1>>",
            b"<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Annots[" + refs + b"]>>"]
    for t, payload in fields:
        name = base64.b64encode(payload).replace(b"/", b"#2F").replace(b"+", b"#2B")
        objs.append(b"<</Type/Annot/Subtype/Widget/FT/Tx/T(" + t
                    + b")/Rect[0 0 0 0]/P 3 0 R/V/" + name + b">>")
    return _pdf(objs)


def _indicators(report: dict, field: str) -> set[str]:
    return {j.get("indicator") for j in report.get("jsIndicators") or []
            if f"formField[{field}]" in (j.get("context") or "")}


@pytest.mark.jvm
def test_a_binary_payload_with_invalid_utf8_is_still_analysed(tmp_path, monkeypatch):
    # a control byte makes it .bin; an invalid UTF-8 byte inside a JS comment made the strict
    # re-read throw, so the payload was not analysed at all
    payload = b"/*\x01\xff*/ this.exportDataObject({cName:'x', nLaunch:2});" + b" " * 64
    _, report = _run(tmp_path, monkeypatch, _form_pdf([(b"p", payload)]))
    assert "exportDataObject" in _indicators(report, "p")


@pytest.mark.jvm
def test_same_named_binary_payloads_keep_both_files(tmp_path, monkeypatch):
    a = b"/*\x01*/ this.exportDataObject({cName:'x'});" + b" " * 64
    b = b"/*\x01*/ var x = 1;" + b" " * 64
    titan, report = _run(tmp_path, monkeypatch, _form_pdf([(b"a b", a), (b"a_b", b)]))
    arts = [f.get("decodedArtifact") for f in report.get("formFields") or []]
    assert len(arts) == 2 and len(set(arts)) == 2, arts
    assert "exportDataObject" in _indicators(report, "a b")


def _run_full(tmp_path: Path, monkeypatch: pytest.MonkeyPatch,
              pdf_bytes: bytes) -> tuple[Path, dict]:
    """Like _run, with screenshots on (URL crops need them)."""
    monkeypatch.setenv("TITANARUM_SKIP_SCREENSHOTS", "0")
    if not shutil.which("java") or not JAR.is_file():
        pytest.skip("needs java and the built jar")
    from blastbox.limits import Limits

    from titanarum.engine import TitanArumEngine

    for k in ("TITANARUM_JAVA_OPTS", "TITANARUM_AOT_CACHE"):
        monkeypatch.delenv(k, raising=False)
    monkeypatch.setenv("TITANARUM_WORKER_JAR", str(JAR))
    monkeypatch.setenv("TITANARUM_SKIP_IMAGES", "1")
    pdf = tmp_path / "in.pdf"
    pdf.write_bytes(pdf_bytes)
    out = tmp_path / "out"
    out.mkdir()
    TitanArumEngine().detonate(pdf, out, Limits.from_env())
    return out / "titan", json.loads((out / "titan" / "report.json").read_text())


def _referenced(report: dict, key: str, field: str) -> set[str]:
    return {h[field] for h in report.get(key) or [] if isinstance(h, dict) and h.get(field)}


@pytest.mark.jvm
def test_collapsed_duplicate_attachments_leave_no_unreferenced_file(tmp_path, monkeypatch):
    # the JVM wrote every attachment, then collapsed same name + sha256 hits into one: the second
    # file stayed on disk with no report reference, so a report-driven rebuild could not declare it
    titan, report = _run(tmp_path, monkeypatch, _pdf([
        b"<</Type/Catalog/Pages 2 0 R/Names<</EmbeddedFiles<</Names[(a) 3 0 R (b) 4 0 R]>>>>>>",
        b"<</Type/Pages/Kids[]/Count 0>>",
        b"<</Type/Filespec/F(a.txt)/UF(a.txt)/EF<</F 5 0 R>>>>",
        b"<</Type/Filespec/F(a.txt)/UF(a.txt)/EF<</F 5 0 R>>>>",
        b"<</Type/EmbeddedFile/Length 5>>stream\nhello\nendstream",
    ]))
    on_disk = {f"attachments/{p.name}" for p in (titan / "attachments").glob("*")}
    refs = _referenced(report, "embeddedFiles", "file")
    assert on_disk and on_disk == refs, (on_disk, report.get("embeddedFiles"))


@pytest.mark.jvm
def test_url_crops_are_deduplicated_like_the_report(tmp_path, monkeypatch):
    # crops were keyed on raw float bounds, the report's URL hits on bounds rounded to 2 decimals:
    # two near-identical links left a crop file no report entry names
    annot = (b"<</Type/Annot/Subtype/Link/Rect[100 %s 300 120]/Border[0 0 0]"
             b"/A<</S/URI/URI(https://example.com/x)>>>>")
    titan, report = _run_full(tmp_path, monkeypatch, _pdf([
        b"<</Type/Catalog/Pages 2 0 R>>",
        b"<</Type/Pages/Kids[3 0 R]/Count 1>>",
        b"<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Annots[4 0 R 5 0 R]>>",
        annot % b"100.001",
        annot % b"100.002",
    ]))
    on_disk = {f"url_crops/{p.name}" for p in (titan / "url_crops").glob("*")}
    assert on_disk == _referenced(report, "urls", "cropPath"), (on_disk, report.get("urls"))
