"""titanarum blastbox engine — drives the pdf-titan-arum JVM worker over file-IPC.

The disposable blastbox worker cannot nest `docker run`, so we launch the JVM
IN-PROCESS (`java … -jar pdf-titan-arum.jar --run <scratch>`) and hand it one job
via a control-file handshake, mirroring RedTusk.
"""
from __future__ import annotations

import json
import os
import re
import shlex
import subprocess
import tempfile
import threading
import time
from pathlib import Path
from typing import TYPE_CHECKING, Any, Literal

from blastbox.contract import (
    ArtifactRef,
    DeclaredArtifact,
    Detection,
    Dimensions,
    EmbeddedResource,
    ExtractedText,
    Hash,
    Page,
    Record,
    Warning,
)

from titanarum.schema import validate_report

if TYPE_CHECKING:
    from blastbox.worker.engine import DetonationResult

# --- JVM launch configuration (all env-overridable) ------------------------

_DEFAULT_JAVA_BIN = "java"
_DEFAULT_WORKER_JAR = "/app/pdf-titan-arum.jar"
_DEFAULT_AOT_CACHE = "/app/titanarum.aot"
_DEFAULT_JAVA_LIBRARY_PATH = "/app"

# MUST match the flags the AOT cache (if any) was built with, or JDK 25 rejects it.
_DEFAULT_JVM_FLAGS: tuple[str, ...] = (
    "-XX:+UseSerialGC",
    "-XX:TieredStopAtLevel=1",
    "-Xms1g",
    "-Xmx4g",
    "-XX:+AlwaysPreTouch",
    "--enable-native-access=ALL-UNNAMED",
)

_READY_POLL_S = 0.1

# Warm-friendly defaults: screenshots/images ON, OCR OFF. No AI keys (network=none).
_DEFAULT_JOB: dict[str, Any] = {
    "dpi": 150.0,
    "pages": "default",
    "skip_qr": False,
    "skip_screenshots": False,
    "skip_images": False,
    "skip_phones": False,
    "skip_page_export": False,
    "skip_text_urls": False,
    "no_skip_blanks": False,
    "ocr_screenshots": False,
    "ocr_url_crops": False,
    "ocr_lang": "eng",
    "add_link_annotations": False,
    "password": None,
    "timeout_seconds": 0,
    "titanarum_version": "1.3.0",
}


def _java_worker_argv(scratch: Path) -> list[str]:
    java_bin = os.environ.get("TITANARUM_JAVA_BIN", _DEFAULT_JAVA_BIN)
    jar = os.environ.get("TITANARUM_WORKER_JAR", _DEFAULT_WORKER_JAR)
    lib_path = os.environ.get("TITANARUM_JAVA_LIBRARY_PATH", _DEFAULT_JAVA_LIBRARY_PATH)
    aot_cache = os.environ.get("TITANARUM_AOT_CACHE", _DEFAULT_AOT_CACHE)
    opts = os.environ.get("TITANARUM_JAVA_OPTS")
    if opts is not None:
        jvm_flags = shlex.split(opts)  # replaces the entire default bundle
    else:
        jvm_flags = [f"-Djava.library.path={lib_path}", *_DEFAULT_JVM_FLAGS]
        if aot_cache and Path(aot_cache).is_file():
            jvm_flags.insert(0, f"-XX:AOTCache={aot_cache}")
    return [java_bin, *jvm_flags, "-jar", jar, "--run", str(scratch)]


def _env_param_overrides() -> dict[str, Any]:
    """Map allowlisted TITANARUM_* env vars (forwarded by the dispatcher) onto job keys."""
    def _flag(n: str) -> bool | None:
        v = os.environ.get(n)
        return None if not v else v.strip().lower() in ("1", "true", "yes", "on")

    def _float(n: str) -> float | None:
        v = os.environ.get(n)
        try:
            return float(v) if v else None
        except ValueError:
            return None

    def _str(n: str) -> str | None:
        return os.environ.get(n) or None

    out: dict[str, Any] = {}
    for env_key, job_key in (
        ("TITANARUM_SKIP_QR", "skip_qr"),
        ("TITANARUM_SKIP_SCREENSHOTS", "skip_screenshots"),
        ("TITANARUM_SKIP_IMAGES", "skip_images"),
        ("TITANARUM_SKIP_PHONES", "skip_phones"),
        ("TITANARUM_SKIP_PAGE_EXPORT", "skip_page_export"),
        ("TITANARUM_SKIP_TEXT_URLS", "skip_text_urls"),
        ("TITANARUM_NO_SKIP_BLANKS", "no_skip_blanks"),
        ("TITANARUM_OCR_SCREENSHOTS", "ocr_screenshots"),
        ("TITANARUM_OCR_URL_CROPS", "ocr_url_crops"),
        ("TITANARUM_ADD_LINK_ANNOTATIONS", "add_link_annotations"),
    ):
        f = _flag(env_key)
        if f is not None:
            out[job_key] = f
    if (lang := _str("TITANARUM_OCR_LANG")):
        out["ocr_lang"] = lang
    if (pages := _str("TITANARUM_PAGES")):
        out["pages"] = pages
    if (dpi := _float("TITANARUM_DPI")) is not None:
        out["dpi"] = dpi
    if (pw := _str("TITANARUM_PASSWORD")):
        out["password"] = pw
    return out


def _build_job(in_path: Path, report_outdir: Path, sha256: str) -> dict[str, Any]:
    filename_hint = in_path.name or "input.pdf"
    return {
        **_DEFAULT_JOB,
        **_env_param_overrides(),
        "input_path": str(in_path),
        "output_dir": str(report_outdir),
        "sha256": sha256,
        "filename_hint": filename_hint,
    }


def _run_worker(input_path: Path, report_outdir: Path, *,
                timeout: float = 120.0, sha256: str = "") -> None:
    """Cold path: boot a fresh JVM in --run mode, hand it one job via control files."""
    report_outdir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as scratch_base:
        scratch = Path(scratch_base) / "slot"
        in_dir = scratch / "in"
        control_dir = scratch / "control"
        in_dir.mkdir(parents=True)
        control_dir.mkdir(parents=True)

        filename_hint = input_path.name or "input.pdf"
        staged_in = in_dir / filename_hint
        staged_in.write_bytes(input_path.read_bytes())

        job = _build_job(staged_in, report_outdir, sha256)

        def _signal_worker() -> None:
            ready_file = control_dir / "control.ready"
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                if ready_file.exists():
                    break
                time.sleep(_READY_POLL_S)
            (control_dir / "job.json").write_text(
                json.dumps(job, ensure_ascii=False), encoding="utf-8")
            (control_dir / "control.go").touch()

        signaller = threading.Thread(target=_signal_worker, daemon=True)
        signaller.start()

        argv = _java_worker_argv(scratch)
        result = subprocess.run(argv, capture_output=True, timeout=timeout)
        signaller.join(timeout=5)

        if result.returncode != 0:
            stderr = result.stderr.decode("utf-8", "replace")[-2000:]
            raise RuntimeError(
                f"titanarum-worker (jvm) exited {result.returncode}: {stderr}")


# --- report.json -> DetonationResult mapping --------------------------------

_HASH_ALGOS: frozenset[Literal["sha256", "phash", "dhash", "colorhash"]] = frozenset(
    {"sha256", "phash", "dhash", "colorhash"})  # contract Hash.algo (ahash n/a)
_ID_RE = re.compile(r"[^A-Za-z0-9._-]")


def _safe_artifact_id(rel_path: str, used: set[str]) -> str:
    base = _ID_RE.sub("_", rel_path).strip("_") or "artifact"
    base = base[:120]
    cand = base
    i = 1
    while cand in used:
        suffix = f"_{i}"
        cand = base[: 128 - len(suffix)] + suffix
        i += 1
    used.add(cand)
    return cand


def _enumerate_artifacts(outdir: Path, report_dir: Path,
                         report: dict) -> list[DeclaredArtifact]:
    """Declare every regular file under report_dir. report.json -> kind='report'."""
    kind_by_dir = {
        "screenshots": "screenshot", "pages": "page_pdf", "scripts": "script",
        "xfa": "xfa", "attachments": "embedded_file", "images_rendered": "image",
        "images_resources": "image", "url_crops": "url_crop",
        "launch_actions": "launch_action", "revisions": "revision",
    }
    used: set[str] = set()
    arts: list[DeclaredArtifact] = []

    def _declare(fp: Path) -> DeclaredArtifact:
        rel = fp.relative_to(outdir).as_posix()
        if fp.name == "report.json" and fp.parent == report_dir:
            kind = "report"
        else:
            top = fp.relative_to(report_dir).parts
            kind = kind_by_dir.get(top[0], "file") if len(top) > 1 else "file"
        return DeclaredArtifact(id=_safe_artifact_id(rel, used), path=rel, kind=kind)

    listed = sorted(p for p in report_dir.rglob("*") if p.is_file())
    for fp in listed:
        arts.append(_declare(fp))

    # gVisor C/R stale-readdir sentinel: if we KNOW report.json exists but rglob
    # missed it, the listing is untrustworthy -> reconstruct from the report.
    known = report_dir / "report.json"
    if known.is_file() and not any(a.path.endswith("titan/report.json") for a in arts):
        arts = _reconstruct_artifacts_from_report(outdir, report_dir, report, used)
    return arts


def _reconstruct_artifacts_from_report(outdir: Path, report_dir: Path,
                                       report: dict, used: set[str]) -> list[DeclaredArtifact]:
    arts: list[DeclaredArtifact] = []
    rel_report = (report_dir / "report.json").relative_to(outdir).as_posix()
    arts.append(DeclaredArtifact(id=_safe_artifact_id(rel_report, used),
                                 path=rel_report, kind="report"))
    field_kind = [
        ("screenshots", "path", "screenshot"), ("pagePdfs", "path", "page_pdf"),
        ("javascript", "file", "script"), ("xfaScripts", "file", "xfa"),
        ("embeddedFiles", "file", "embedded_file"),
        ("renderedImages", "path", "image"), ("resourceImages", "path", "image"),
    ]
    for arr_key, field, kind in field_kind:
        for hit in report.get(arr_key, []) or []:
            val = hit.get(field)
            if not val:
                continue
            # val is attacker-controlled PDF content (embeddedFiles[].file,
            # screenshot/image paths, etc.) - confine fp to report_dir. An
            # absolute value or one containing ".." must never escape, and
            # relative_to() must never be allowed to throw.
            fp = report_dir / val
            if not fp.resolve().is_relative_to(report_dir.resolve()):
                continue
            if fp.is_file():
                rel = fp.relative_to(outdir).as_posix()
                arts.append(DeclaredArtifact(id=_safe_artifact_id(rel, used),
                                             path=rel, kind=kind))
    return arts


def _summary_fields(report: dict) -> dict[str, Any]:
    """Compact, metadata-budget-safe summary (NOT the whole report)."""
    di = report.get("documentInfo") or {}
    fields: dict[str, Any] = {
        "input_pdf": report.get("inputPdf", ""),
        "document_sha256": report.get("documentSha256", ""),
        "generated_at": report.get("generatedAt", ""),
        "page_count": report.get("pageCount", 0),
        "blank_page_count": report.get("blankPageCount", 0),
        "revision_count": report.get("revisionCount", 0),
        "dpi": report.get("dpi", 0.0),
    }
    if report.get("pdfObjectHash"):
        fields["pdf_object_hash"] = report["pdfObjectHash"]
    if report.get("fileMagic"):
        fields["file_magic"] = report["fileMagic"]
    for k in ("pdfVersion", "title", "author", "producer", "creator"):
        if di.get(k):
            fields[f"doc_{_snake(k)}"] = str(di[k])[:255]
    # counts of high-signal arrays (cheap triage)
    for arr in ("urls", "javascript", "launchActions", "actions", "embeddedFiles",
                "jsIndicators", "structuralAnomalies", "streamLengthAnomalies",
                "metadataSpoofingIndicators", "formFields"):
        n = len(report.get(arr, []) or [])
        if n:
            fields[f"n_{_snake(arr)}"] = n
    return fields


def _snake(camel: str) -> str:
    return re.sub(r"(?<!^)(?=[A-Z])", "_", camel).lower()


def _hashes_from(hashresult: dict) -> list[Hash]:
    out: list[Hash] = []
    for algo in _HASH_ALGOS:
        v = hashresult.get(algo)
        if isinstance(v, str) and v:
            try:
                out.append(Hash(algo=algo, value=v))
            except Exception:  # noqa: BLE001 - skip a hash of the wrong length
                pass
    return out


def _build_payload(report: dict, artifacts: list[DeclaredArtifact],
                   outdir: Path, report_dir: Path) -> EmbeddedResource:
    by_path = {a.path: a.id for a in artifacts}
    children: list[Any] = []

    # EmbeddedResource children (embedded files)
    for hit in report.get("embeddedFiles", []) or []:
        ct = (hit.get("detectedMimeType") or hit.get("mimeType")
              or "application/octet-stream")
        meta = Record(fields={
            k: v for k, v in {
                "original_name": hit.get("originalName"),
                "size_bytes": hit.get("size"),
                "sha256": hit.get("sha256"),
                "declared_mime": hit.get("mimeType"),
                "detected_mime": hit.get("detectedMimeType"),
                "mime_type_mismatch": hit.get("mimeTypeMismatch"),
                "file_magic": hit.get("fileMagic"),
            }.items() if v is not None
        })
        children.append(EmbeddedResource(
            embedded_path=(hit.get("originalName") or "/")[:4096],
            content_type=ct[:255], depth=1, metadata=meta, children=[]))

    # Page children (screenshots; 1-based -> 0-based index)
    for ss in report.get("screenshots", []) or []:
        rel = _rel_for(ss.get("path"), outdir, report_dir)
        art_id = by_path.get(rel)
        if art_id is None:
            continue  # can only reference a DECLARED artifact
        page_1based = int(ss.get("page", 1))
        w = float(ss.get("width") or 1)
        h = float(ss.get("height") or 1)
        if w <= 0 or h <= 0:
            # skip rather than crash: contract Dimensions requires > 0
            # (matches the Hash skip-don't-crash pattern)
            continue
        page_children: list[Any] = []
        if ss.get("ocrText"):
            txt = str(ss["ocrText"])
            page_children.append(ExtractedText(text=txt[:10_000_000], char_count=len(txt)))
        children.append(Page(
            index=max(0, page_1based - 1),
            dims=Dimensions(width=w, height=h, unit="px"),
            image=ArtifactRef(id=art_id),
            hashes=_hashes_from(ss.get("hashes") or {}),
            children=page_children))

    return EmbeddedResource(
        embedded_path=report.get("inputPdf", "document.pdf")[:4096],
        content_type="application/pdf", depth=0,
        metadata=Record(fields=_summary_fields(report)),
        children=children)


def _rel_for(path_value: str | None, outdir: Path, report_dir: Path) -> str:
    """Normalize a report path field to the outdir-relative path used as a
    DeclaredArtifact key. The JVM emits screenshot/image paths RELATIVE to
    report_dir (= outdir/titan), e.g. 'screenshots/page-0001.png'."""
    if not path_value:
        return ""
    p = Path(str(path_value))
    base = p if p.is_absolute() else (report_dir / p)
    try:
        return base.resolve().relative_to(outdir.resolve()).as_posix()
    except ValueError:
        return ""  # outside the output tree -> won't match any declared artifact


def _build_detection(report: dict) -> Detection:
    magic = (report.get("fileMagic") or "").split(";")[0].strip()
    label = (magic or "pdf")[:64]
    return Detection(
        label=label,
        mime=(report.get("fileMagic") or "application/pdf")[:255],
        confidence=1.0,
        source="titanarum")


def _clip(s: Any, n: int) -> str:
    return str(s)[:n]


def _build_warnings(report: dict, *, cap: int = 200) -> list[Warning]:
    warns: list[Warning] = []

    if report.get("parseError"):
        warns.append(Warning(code="parse_error", message=_clip(report["parseError"], 2000)))
    if report.get("timedOut"):
        warns.append(Warning(code="timed_out",
                             message=f"partial results after {report.get('timedOutAfterMs')} ms"))

    def _emit(items, make):
        for it in items or []:
            # Reserve the last slot for the truncation marker itself, so the
            # total (items + marker) never exceeds `cap`.
            if len(warns) >= max(cap - 1, 0):
                warns.append(Warning(code="warnings_truncated",
                                     message=f"warning list capped at {cap}"))
                return True
            warns.append(make(it))
        return False

    seqs = [
        (report.get("jsIndicators"),
         lambda i: Warning(code=_clip(f"js_indicator.{i.get('type','')}", 64),
                           message=_clip(f"{i.get('indicator','')}: {i.get('detail','')}", 2000),
                           context=_clip(i.get("context", ""), 255) or None)),
        (report.get("launchActions"),
         lambda i: Warning(code="launch_action",
                           message=_clip(f"{i.get('operation','')} {i.get('file','')} "
                                         f"{i.get('parameters','')}", 2000))),
        (report.get("actions"),
         lambda i: Warning(code=_clip(f"action.{i.get('type','')}", 64),
                           message=_clip(i.get("target") or i.get("submitUrl")
                                         or i.get("remoteFile") or "", 2000))),
        (report.get("streamLengthAnomalies"),
         lambda i: Warning(code=_clip(f"stream_anomaly.{i.get('anomalyType','')}", 64),
                           message=_clip(f"obj={i.get('objectNumber')} "
                                         f"delta={i.get('delta')}", 2000))),
        (report.get("structuralAnomalies"),
         lambda i: Warning(code=_clip(f"structural.{i.get('type','')}", 64),
                           message=_clip(i.get("detail", ""), 2000))),
        (report.get("metadataSpoofingIndicators"),
         lambda i: Warning(code=_clip(f"metadata_spoof.{i.get('type','')}", 64),
                           message=_clip(i.get("detail", ""), 2000))),
        (report.get("formFields"),
         lambda i: Warning(code="form_field",
                           message=_clip(f"{i.get('name','')} {i.get('flags',[])}", 2000))),
        ([o for o in (report.get("ocgLayers") or []) if o.get("suspicious")],
         lambda i: Warning(code="hidden_layer",
                           message=_clip(f"{i.get('name','')} "
                                         f"default={i.get('defaultState','')}", 2000))),
        ([u for u in (report.get("urls") or []) if u.get("flags")],
         lambda i: Warning(code="url_flag",
                           message=_clip(f"{i.get('url','')} {i.get('flags',[])}", 2000))),
        ([e for e in (report.get("embeddedFiles") or []) if e.get("mimeTypeMismatch")],
         lambda i: Warning(code="embedded_mismatch",
                           message=_clip(f"{i.get('originalName','')}: "
                                         f"{i.get('mimeTypeMismatch','')}", 2000))),
    ]
    for items, make in seqs:
        if _emit(items, make):
            break
    return warns


_ENCRYPTED_MARKERS = ("password-protected", "incorrect password")
_NOT_PDF_MARKERS = ("does not contain a pdf header", "pdf parse error")


def _status_from_report(report: dict) -> Literal["ok", "rejected"]:
    err = (report.get("parseError") or "").lower()
    if not err:
        return "ok"
    if any(m in err for m in _ENCRYPTED_MARKERS) or any(m in err for m in _NOT_PDF_MARKERS):
        return "rejected"
    return "ok"  # e.g. "Recovered (lenient parse)" -> ok + a Warning


# --- Engine adapter ----------------------------------------------------------

class TitanArumEngine:
    """blastbox Engine (structural Protocol) fronting the pdf-titan-arum JVM analyzer."""

    name: str = "titanarum"
    formats: frozenset[str] = frozenset({"pdf"})

    def detonate(self, input: Path, outdir: Path, limits) -> DetonationResult:  # noqa: A002
        from blastbox.worker.engine import DetonationResult

        report_dir = outdir / "titan"
        self._produce_report(input, report_dir, timeout=float(limits.timeout_s))

        report_path = report_dir / "report.json"
        if not report_path.is_file():
            raise RuntimeError("titanarum worker did not produce report.json")
        report = json.loads(report_path.read_bytes())
        validate_report(report)

        artifacts = _enumerate_artifacts(outdir, report_dir, report)
        payload = _build_payload(report, artifacts, outdir, report_dir)
        detected = _build_detection(report)
        warnings = _build_warnings(report)
        status = _status_from_report(report)
        return DetonationResult(payload=payload, artifacts=artifacts,
                                detected=detected, warnings=warnings, status=status)

    def _produce_report(self, input: Path, report_dir: Path, timeout: float) -> None:
        # Phase 1: cold only. Phase 2 adds CRaC -> warm -> cold tier selection here.
        sha256 = _sha256_file(input)
        # titanarum's own watchdog slightly below the subprocess timeout to keep partial output.
        _run_worker(input, report_dir, timeout=timeout if timeout > 0 else 120.0,
                    sha256=sha256)


def _sha256_file(path: Path) -> str:
    import hashlib

    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()
