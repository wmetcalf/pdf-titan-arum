import json
from pathlib import Path

from blastbox.contract import (
    DeclaredArtifact,
    Detection,
    EmbeddedResource,
    ExtractedText,
    Page,
    Warning,
)

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


def test_reconstruct_rejects_path_escapes_but_keeps_legit_files(tmp_path):
    # gVisor C/R stale-readdir fallback: report field values are
    # attacker-controlled PDF content. An absolute path or a ".."-laden
    # value must never crash (relative_to raising ValueError) and must
    # never be declared as an artifact outside report_dir.
    outdir, report_dir = _make_tree(tmp_path)
    report = _report(
        embeddedFiles=[
            {"file": "../../../../etc/passwd", "originalName": "a"},
            {"file": "/etc/passwd", "originalName": "b"},
        ],
        screenshots=[{"page": 1, "path": "screenshots/page-0001.png"}],
    )
    arts = eng._reconstruct_artifacts_from_report(outdir, report_dir, report, set())
    paths = {a.path for a in arts}
    assert not any("passwd" in p for p in paths)
    for a in arts:
        resolved = (outdir / a.path).resolve()
        assert resolved.is_relative_to(report_dir.resolve())
    # legit reconstruct still finds the real on-disk artifact
    assert "titan/report.json" in paths
    assert "titan/screenshots/page-0001.png" in paths


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
    payload = eng._build_payload(_report(pageCount=3), arts, outdir, report_dir)
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
    outdir = tmp_path / "out"
    report_dir = outdir / "titan"
    payload = eng._build_payload(report, [], outdir, report_dir)
    kids = [c for c in payload.children if isinstance(c, EmbeddedResource)]
    assert len(kids) == 1
    assert kids[0].content_type.endswith("wordprocessingml.document")
    assert kids[0].metadata.fields["original_name"] == "evil.docx"


def test_screenshot_becomes_page(tmp_path):
    outdir = tmp_path / "out"
    report_dir = outdir / "titan"
    (report_dir / "screenshots").mkdir(parents=True)
    (report_dir / "screenshots" / "page-0001.png").write_bytes(b"\x89PNG stub")
    (report_dir / "report.json").write_text("{}")
    report = _report(screenshots=[{
        "page": 1,
        "path": "screenshots/page-0001.png",
        "width": 800, "height": 1000,
        "ocrText": "hello world",
    }])
    arts = eng._enumerate_artifacts(outdir, report_dir, report)
    payload = eng._build_payload(report, arts, outdir, report_dir)
    pages = [c for c in payload.children if isinstance(c, Page)]
    assert len(pages) == 1
    page = pages[0]
    assert page.index == 0                       # 1-based -> 0-based
    assert page.dims.width == 800 and page.dims.height == 1000
    assert page.image.id in {a.id for a in arts}  # ArtifactRef -> a DECLARED id
    texts = [c for c in page.children if isinstance(c, ExtractedText)]
    assert texts and texts[0].text == "hello world"


def test_build_detection_label_is_type_not_threat():
    # Decision (2026-07-10): Detection.label is a TYPE classifier (blastbox
    # convention); jsIndicators surface via Warnings, never in label.
    report = _report(fileMagic="application/pdf", jsIndicators=[
        {"type": "suspicious_api", "indicator": "SOAP.streamDecode",
         "detail": "present", "count": 1}])
    det = eng._build_detection(report)
    assert "SOAP" not in det.label
    assert det.label  # non-empty type label
    assert any(w.code.startswith("js_indicator.") for w in eng._build_warnings(report))


def test_mapper_survives_hostile_nested_field_types(tmp_path):
    # The trust gate (schema.py) deliberately leaves array-item FIELDS unconstrained
    # ({"type":"object"}); a compromised worker (the gate's whole threat model) can emit
    # wrong-typed nested fields. The host-side mapper must NOT crash on any of them.
    outdir = tmp_path / "out"
    report_dir = outdir / "titan"
    report_dir.mkdir(parents=True)
    hostile = _report(
        embeddedFiles=[{"originalName": 5, "detectedMimeType": 7, "mimeType": ["x"],
                        "size": {}, "sha256": 9, "file": 123}],
        screenshots=[{"path": {"nope": 1}, "page": {"a": 1}, "width": "NaNlike",
                      "height": None, "hashes": [1, 2], "ocrText": 42}],
        renderedImages=[{"path": [1, 2]}],
        resourceImages=[{"path": 999}],
    )
    # None of these may raise on a schema-valid-but-hostile report:
    arts = eng._reconstruct_artifacts_from_report(outdir, report_dir, hostile, set())
    payload = eng._build_payload(hostile, arts, outdir, report_dir)
    warnings = eng._build_warnings(hostile)
    assert isinstance(payload, EmbeddedResource)
    assert isinstance(warnings, list)


def test_mapper_survives_overflow_and_nullbyte_fields(tmp_path):
    # marla round-2 follow-on: the coercion helpers added in round 1 caught only
    # (TypeError, ValueError). Two hostile numerics slip past that:
    #   * json.loads() accepts `Infinity`/`NaN` and UNBOUNDED integer literals by
    #     default -> int(float('inf')) and float(10**400) raise OverflowError, which
    #     is NOT a subclass of ValueError, so it propagated and crashed detonate().
    #   * a path field carrying an embedded NUL survives isinstance(str) but makes
    #     Path.resolve() raise ValueError('embedded null byte') at the one resolve()
    #     call that _rel_for guards but _reconstruct_artifacts_from_report did not.
    outdir = tmp_path / "out"
    report_dir = outdir / "titan"
    ss_dir = report_dir / "screenshots"
    ss_dir.mkdir(parents=True)
    (ss_dir / "page-0001.png").write_bytes(b"\x89PNG\r\n")  # a genuinely-declared artifact
    arts = eng._enumerate_artifacts(outdir, report_dir, {})

    # (a) non-finite page + oversized/non-finite dimensions on a screenshot that
    #     references a DECLARED artifact (so _build_payload reaches the coercions).
    over = _report(screenshots=[{
        "path": "screenshots/page-0001.png",
        "page": float("inf"),   # int(inf) -> OverflowError
        "width": 10 ** 400,     # float(huge int) -> OverflowError
        "height": float("nan"),  # non-finite dimension must be dropped, not emitted
    }])
    payload = eng._build_payload(over, arts, outdir, report_dir)
    assert isinstance(payload, EmbeddedResource)

    # (b) embedded-NUL path strings must not crash artifact reconstruction.
    nul = _report(
        embeddedFiles=[{"file": "a\x00b"}],
        renderedImages=[{"path": "x\x00y"}],
        resourceImages=[{"path": "z\x00w"}],
    )
    arts2 = eng._reconstruct_artifacts_from_report(outdir, report_dir, nul, set())
    assert isinstance(arts2, list)


def test_build_payload_survives_hostile_embedded_metadata_types(tmp_path):
    # marla round-3: _build_payload hardened content_type/embedded_path via _as_str but passed
    # the embedded-file metadata field VALUES raw into Record(fields=...). Record.fields is a
    # pydantic dict[str, Scalar|list[Scalar]|Record]; a nested-object / mixed-array value matches
    # no union member and raises ValidationError -> uncaught host crash. Every metadata value
    # must be coerced to a Record-safe (scalar / list-of-scalars) shape.
    outdir = tmp_path / "out"
    report_dir = outdir / "titan"
    report_dir.mkdir(parents=True)
    hostile = _report(embeddedFiles=[{
        "originalName": {"x": 1},          # nested object
        "size": [[1, 2], {"a": 3}],        # nested/mixed array
        "sha256": {"nested": "obj"},
        "mimeType": [{"a": 1}],
        "detectedMimeType": {"deep": {"er": 1}},
        "mimeTypeMismatch": {"not": "a bool"},
        "fileMagic": [1, {"b": 2}],
    }])
    payload = eng._build_payload(hostile, [], outdir, report_dir)  # must NOT raise
    assert isinstance(payload, EmbeddedResource)
    assert payload.children and payload.children[0].metadata is not None


def test_build_warnings_clips_huge_timed_out_message():
    # timedOutAfterMs is root-typed but UNBOUNDED; the timed_out warning interpolated it into an
    # unclipped f-string while contract Warning.message caps at 2000 chars -> a hostile huge value
    # raised pydantic ValidationError out of detonate(). The message must be clipped like the rest.
    report = _report(timedOut=True, timedOutAfterMs=10 ** 2000)
    warnings = eng._build_warnings(report)  # must NOT raise
    timed = [w for w in warnings if w.code == "timed_out"]
    assert timed and len(timed[0].message) <= 2000


def test_reconstruct_survives_abs_path_with_leading_dotdot(tmp_path):
    # A hostile absolute path with a leading '..' resolves INTO report_dir (so it passes the
    # resolve()+confinement+is_file() checks) yet is not LEXICALLY under outdir, so the unguarded
    # fp.relative_to(outdir) on the raw (unresolved) path raised ValueError -> detonate crash.
    # Reconstruction must derive the relative path from the resolved (confined) path.
    outdir = tmp_path / "out"
    report_dir = outdir / "titan"
    (report_dir / "screenshots").mkdir(parents=True)
    f = report_dir / "screenshots" / "page-0001.png"
    f.write_bytes(b"\x89PNG\r\n")
    hostile = _report(screenshots=[{"path": "/.." + str(f)}])
    # must NOT raise
    arts = eng._reconstruct_artifacts_from_report(outdir, report_dir, hostile, set())
    assert any(a.path.endswith("titan/screenshots/page-0001.png") for a in arts)


def test_rel_for_survives_symlink_loop(tmp_path):
    # _rel_for guarded resolve() only against ValueError; a report path pointing at an on-disk
    # symlink LOOP makes resolve() raise OSError (ELOOP), which propagated and crashed detonate.
    # (Matches the (ValueError, OSError) guard in _reconstruct_artifacts_from_report.)
    outdir = tmp_path / "out"
    report_dir = outdir / "titan"
    report_dir.mkdir(parents=True)
    (report_dir / "loop").symlink_to(report_dir / "loop")  # self-referential -> ELOOP on resolve()
    assert eng._rel_for("loop", outdir, report_dir) == ""  # OSError must not propagate


def test_a_page_with_a_non_positive_dimension_is_skipped_not_emitted(tmp_path):
    """A screenshot whose width or height is <= 0 must not become a Page.

    The contract's Dimensions requires > 0, so emitting one produces a payload the
    consumer cannot parse -- the engine skips instead, the same skip-don't-crash
    pattern the Hash mapping uses. Nothing tested that: disabling the guard
    entirely (`if False:`) left the whole suite green.

    The positive control is in the same test: a well-formed screenshot alongside
    the bad one still becomes a Page, so this cannot pass by mapping nothing.
    """
    outdir = tmp_path / "out"
    report_dir = outdir / "titan"
    (report_dir / "screenshots").mkdir(parents=True)
    for name in ("page-0001.png", "page-0002.png", "page-0003.png", "page-0004.png"):
        (report_dir / "screenshots" / name).write_bytes(b"\x89PNG stub")
    (report_dir / "report.json").write_text("{}")
    report = _report(screenshots=[
        {"page": 1, "path": "screenshots/page-0001.png", "width": -5, "height": 1000},
        {"page": 2, "path": "screenshots/page-0002.png", "width": 800, "height": -1},
        # Exactly zero, and as a STRING: `ss.get("width") or 1` defaults a numeric 0
        # to 1 before the guard sees it, but "0" is truthy and survives to _as_float
        # as 0.0. That is the only input that separates `<= 0` from `< 0`, and
        # without it a mutant weakening the guard to `< 0` passes.
        {"page": 3, "path": "screenshots/page-0003.png", "width": "0", "height": 1000},
        {"page": 4, "path": "screenshots/page-0004.png", "width": 800, "height": 1000},
    ])
    arts = eng._enumerate_artifacts(outdir, report_dir, report)
    payload = eng._build_payload(report, arts, outdir, report_dir)

    pages = [c for c in payload.children if isinstance(c, Page)]
    assert [p.index for p in pages] == [3], (
        "only the well-formed screenshot may become a Page; got "
        f"{[(p.index, p.dims.width, p.dims.height) for p in pages]}"
    )
    assert pages[0].dims.width == 800 and pages[0].dims.height == 1000


def test_summary_fields_drops_non_finite_dpi():
    # _summary_fields fed report dpi straight into the Record metadata, bypassing the _as_float
    # non-finite guard the mapper applies everywhere else; a hostile dpi=Infinity would otherwise
    # reach the contract field (serializing to null), violating the mapper's finite invariant.
    import math
    fields = eng._summary_fields(_report(dpi=float("inf")))
    assert math.isfinite(fields["dpi"])
