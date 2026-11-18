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
def test_a_form_payload_past_the_cap_is_still_analysed(tmp_path, monkeypatch):
    payload = b" " * (CAP + 3) + b"this.exportDataObject({cName:'x'});"
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
