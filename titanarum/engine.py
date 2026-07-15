"""titanarum blastbox engine — drives the pdf-titan-arum JVM worker over file-IPC.

The disposable blastbox worker cannot nest `docker run`, so we launch the JVM
IN-PROCESS (`java … -jar pdf-titan-arum.jar --run <scratch>`) and hand it one job
via a control-file handshake, mirroring RedTusk.
"""
from __future__ import annotations

import json
import math
import os
import re
import shlex
import shutil
import subprocess
import tempfile
import threading
import time
from dataclasses import dataclass
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

# Warm tier: a FIXED scratch (not per-job tempdir) so a checkpoint/restore
# sandbox can bind-mount the same path across the JVM's pre-boot and its one
# dispatched job. warmup() never raises, so a slow/failed boot just degrades
# to cold with no fixed-scratch side effect other than an unused directory.
_DEFAULT_WARM_SCRATCH = "/tmp/titanarum-warm"
_WARMUP_READY_TIMEOUT = 60.0
_WARMUP_POLL_S = 0.05

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


def _worker_timeouts(effective: float) -> tuple[int, float]:
    """Split one budget into (JVM self-limit seconds, Python subprocess backstop seconds).

    The JVM's own watchdog must fire BELOW the Python SIGKILL so it can flush a partial
    `timedOut=true` report before it dies (otherwise a hung PDF is SIGKILLed with no output
    and re-run cold, burning ~2x the budget). The backstop therefore adds the hard-halt
    margin -- TITANARUM_HARD_TIMEOUT_MS, the very env the JVM clamps its hard-halt on -- plus
    a small buffer so the cooperative flush (or, worst case, halt(3)) and JVM exit complete
    before Python's kill.
    """
    # Round the JVM self-limit UP: truncating a fractional budget would fire the cooperative
    # watchdog before `effective` and cut off a valid PDF that used to finish (the JVM ran
    # unbounded until the Python timeout). The <1s of extra headroom stays under the backstop.
    jvm_s = max(1, math.ceil(effective))
    try:
        hard_ms = int(os.environ.get("TITANARUM_HARD_TIMEOUT_MS", "") or 15000)
    except ValueError:
        hard_ms = 15000
    grace_s = max(1, hard_ms) / 1000.0 + 5.0
    return jvm_s, float(effective) + grace_s


def _build_job(in_path: Path, report_outdir: Path, sha256: str,
               *, jvm_timeout_s: int = 0) -> dict[str, Any]:
    filename_hint = in_path.name or "input.pdf"
    return {
        **_DEFAULT_JOB,
        **_env_param_overrides(),
        "input_path": str(in_path),
        "output_dir": str(report_outdir),
        "sha256": sha256,
        "filename_hint": filename_hint,
        # Forward the JVM self-limit LAST so it wins over _DEFAULT_JOB's 0 ("no limit"): the
        # worker arms its cooperative + hard-halt watchdogs only when timeout_seconds > 0.
        "timeout_seconds": jvm_timeout_s,
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

        jvm_s, subproc_s = _worker_timeouts(timeout)
        job = _build_job(staged_in, report_outdir, sha256, jvm_timeout_s=jvm_s)

        _stop = threading.Event()

        def _signal_worker() -> None:
            ready_file = control_dir / "control.ready"
            deadline = time.monotonic() + subproc_s
            while time.monotonic() < deadline:
                if _stop.is_set():
                    return
                if ready_file.exists():
                    break
                time.sleep(_READY_POLL_S)
            if _stop.is_set():
                return
            (control_dir / "job.json").write_text(
                json.dumps(job, ensure_ascii=False), encoding="utf-8")
            (control_dir / "control.go").touch()

        signaller = threading.Thread(target=_signal_worker, daemon=True)
        signaller.start()

        argv = _java_worker_argv(scratch)
        try:
            result = subprocess.run(argv, capture_output=True, timeout=subproc_s)
        finally:
            # Stop + join the signaller BEFORE the enclosing tempdir is torn down, so it can't
            # write control files into a directory being rmtree'd. subprocess.run(timeout=...)
            # raises TimeoutExpired, which would otherwise skip the join below and leave the
            # thread writing into the deleted scratch (race).
            _stop.set()
            signaller.join(timeout=5)

        # A nonzero exit with a flushed report.json is the I2 hard-halt path: the JVM's hard
        # watchdog writes a partial `timedOut=true` report and then halt(3) (exit 3). Consume
        # that partial (the downstream trust gate re-validates it) instead of discarding it and
        # failing. Only a nonzero exit with NO report is a genuine crash -> fail closed.
        if result.returncode != 0 and not (report_outdir / "report.json").is_file():
            stderr = result.stderr.decode("utf-8", "replace")[-2000:]
            raise RuntimeError(
                f"titanarum-worker (jvm) exited {result.returncode}: {stderr}")


@dataclass
class _WarmWorker:
    """A persistent JVM booted by `warmup()`, blocked on `control.go`, good for
    exactly one job (fixed scratch, one-shot handshake mirrors the cold path)."""

    proc: subprocess.Popen[bytes]
    scratch: Path
    in_dir: Path
    control_dir: Path


def _run_warm_job(warm: _WarmWorker, input_path: Path, report_dir: Path, *,
                  timeout: float, sha256: str) -> None:
    """Hand ONE job to an already-booted, already-blocked-on-go warm JVM.

    Raises on any failure (timeout, nonzero exit, missing report.json); the
    caller is expected to catch and fail closed to `_run_worker` (cold)."""
    report_dir.mkdir(parents=True, exist_ok=True)
    filename_hint = input_path.name or "input.pdf"
    staged_in = warm.in_dir / filename_hint
    staged_in.write_bytes(input_path.read_bytes())

    jvm_s, subproc_s = _worker_timeouts(timeout)
    job = _build_job(staged_in, report_dir, sha256, jvm_timeout_s=jvm_s)
    (warm.control_dir / "job.json").write_text(
        json.dumps(job, ensure_ascii=False), encoding="utf-8")
    (warm.control_dir / "control.go").touch()

    try:
        warm.proc.communicate(timeout=subproc_s)
    except subprocess.TimeoutExpired:
        warm.proc.kill()
        try:
            warm.proc.communicate(timeout=5)
        except Exception:  # noqa: BLE001 - best-effort reap, we're failing closed anyway
            pass
        raise

    # A hard-halt (exit 3) flushes a partial `timedOut=true` report before halt(3); a valid
    # report.json on disk is the result regardless of exit code (the trust gate re-validates
    # it). Raising here would make _produce_report rmtree the partial and re-run the same hung
    # PDF cold -- the ~2x-budget, discarded-partial outcome the hard-halt flush exists to avoid.
    if not (report_dir / "report.json").is_file():
        raise RuntimeError(
            f"titanarum-warm-worker (jvm) exited {warm.proc.returncode} without report.json"
            if warm.proc.returncode != 0
            else "titanarum-warm-worker did not produce report.json")


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
            # val is attacker-controlled: a non-string (int/list/dict from a hostile report)
            # would make `report_dir / val` below raise TypeError. Require a non-empty string.
            if not isinstance(val, str) or not val:
                continue
            # val is attacker-controlled PDF content (embeddedFiles[].file,
            # screenshot/image paths, etc.) - confine fp to report_dir. An
            # absolute value or one containing ".." must never escape, and
            # relative_to() must never be allowed to throw.
            fp = report_dir / val
            # resolve() itself can throw on a hostile value (e.g. an embedded NUL ->
            # ValueError 'embedded null byte'); _rel_for guards its resolve() the same way.
            try:
                resolved = fp.resolve()
            except (ValueError, OSError):
                continue
            if not resolved.is_relative_to(report_dir.resolve()):
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
        "dpi": _as_float(report.get("dpi"), 0.0),  # finite-guard: dpi is only root-typed as number
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


def _as_str(v: Any, default: str = "") -> str:
    """Coerce an untrusted report field to str; hostile reports may emit any JSON type."""
    return v if isinstance(v, str) else default


def _as_int(v: Any, default: int) -> int:
    # OverflowError (NOT a ValueError subclass) escapes on int(float('inf')): json.loads
    # accepts the bare `Infinity`/`NaN` tokens by default, and the array-item schema places
    # no constraint on a screenshot's numeric fields, so a hostile report can reach here.
    try:
        return int(v)
    except (TypeError, ValueError, OverflowError):
        return default


def _as_float(v: Any, default: float) -> float:
    # float(10**400) raises OverflowError (json integer literals are unbounded Python ints);
    # inf/nan parse without error but are not sane dimensions -- drop them to the default so a
    # non-finite value can never reach a contract field or JSON re-serialization downstream.
    try:
        f = float(v)
    except (TypeError, ValueError, OverflowError):
        return default
    return f if math.isfinite(f) else default


def _as_record_value(v: Any) -> Any:
    """Coerce an untrusted report field into a Record-safe value.

    Record.fields is a pydantic dict[str, Scalar | list[Scalar] | Record]; a hostile report can
    put a nested object or mixed array here, which matches no union member and raises
    ValidationError at Record construction. Keep scalars (and flat lists of scalars) as-is and
    stringify anything else so it can never crash the mapper.
    """
    if v is None or isinstance(v, (str, int, float, bool)):
        return v
    if isinstance(v, list) and all(x is None or isinstance(x, (str, int, float, bool)) for x in v):
        return v
    return str(v)[:1024]


def _hashes_from(hashresult: dict) -> list[Hash]:
    out: list[Hash] = []
    if not isinstance(hashresult, dict):   # hostile report: `hashes` may be a non-object
        return out
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
        ct = (_as_str(hit.get("detectedMimeType")) or _as_str(hit.get("mimeType"))
              or "application/octet-stream")
        meta = Record(fields={
            k: _as_record_value(v) for k, v in {
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
            embedded_path=(_as_str(hit.get("originalName")) or "/")[:4096],
            content_type=ct[:255], depth=1, metadata=meta, children=[]))

    # Page children (screenshots; 1-based -> 0-based index)
    for ss in report.get("screenshots", []) or []:
        rel = _rel_for(ss.get("path"), outdir, report_dir)
        art_id = by_path.get(rel)
        if art_id is None:
            continue  # can only reference a DECLARED artifact
        page_1based = _as_int(ss.get("page"), 1)
        w = _as_float(ss.get("width") or 1, 1.0)
        h = _as_float(ss.get("height") or 1, 1.0)
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
        # _clip: timedOutAfterMs is a root-typed but UNBOUNDED integer, so a hostile report can
        # blow this past Warning.message's 2000-char cap and crash construction (like parse_error).
        warns.append(Warning(code="timed_out",
                             message=_clip(f"partial results after {report.get('timedOutAfterMs')} ms", 2000)))

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

    def __init__(self) -> None:
        self._warm: _WarmWorker | None = None

    def warmup(self) -> None:
        """Pre-boot a persistent JVM in `--run` mode and block it on the go-wait
        (unbounded per W-1, since `TITANARUM_WARM=1` is set). Never raises: a
        failed/slow boot just leaves `self._warm` None, which degrades the next
        `detonate()` to the cold path."""
        self._warm = None
        try:
            scratch = Path(_DEFAULT_WARM_SCRATCH)
            in_dir = scratch / "in"
            control_dir = scratch / "control"
            in_dir.mkdir(parents=True, exist_ok=True)
            control_dir.mkdir(parents=True, exist_ok=True)
            # Clear stale control files from a prior life of this fixed scratch
            # so the new JVM's ready/go handshake can't be short-circuited.
            for stale in ("control.ready", "control.go", "job.json"):
                (control_dir / stale).unlink(missing_ok=True)

            argv = _java_worker_argv(scratch)
            env = dict(os.environ)
            env["TITANARUM_WARM"] = "1"
            # Boot/job stdout+stderr go to a FILE (not DEVNULL) so a failed or
            # wedged warm boot leaves a post-mortem trail -- a file, unlike a
            # pipe, has no pipe-buffer deadlock risk, so it's safe alongside
            # the unbounded go-wait. .stdout/.stderr on the Popen stay None
            # (same as DEVNULL), so communicate()/wait() are unaffected.
            log_path = scratch / "warm-boot.log"
            with log_path.open("wb") as log_file:
                proc = subprocess.Popen(
                    argv, stdout=log_file, stderr=subprocess.STDOUT, env=env)

            ready_file = control_dir / "control.ready"
            deadline = time.monotonic() + _WARMUP_READY_TIMEOUT
            while True:
                if ready_file.exists():
                    self._warm = _WarmWorker(proc=proc, scratch=scratch,
                                             in_dir=in_dir, control_dir=control_dir)
                    return
                if proc.poll() is not None:
                    return  # died before announcing ready -> stays cold
                if time.monotonic() >= deadline:
                    proc.kill()
                    try:
                        proc.wait(timeout=5)
                    except Exception:  # noqa: BLE001
                        pass
                    return
                time.sleep(_WARMUP_POLL_S)
        except Exception:  # noqa: BLE001 - a raised warmup fails the slot; degrade instead
            self._warm = None

    def close(self) -> None:
        """Tear down an unused warm JVM (e.g. blastbox discards the slot without
        ever dispatching a job to it): kill the proc and clean up its scratch."""
        warm, self._warm = self._warm, None
        if warm is None:
            return
        try:
            if warm.proc.poll() is None:
                warm.proc.kill()
                warm.proc.wait(timeout=5)
        except Exception:  # noqa: BLE001 - best-effort teardown
            pass
        finally:
            shutil.rmtree(warm.scratch, ignore_errors=True)

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
        """Tier precedence: CRaC -> warm -> cold."""
        sha256 = _sha256_file(input)
        # The JVM's own watchdog fires at this budget; the Python subprocess backstop sits a
        # hard-halt margin above it (see _worker_timeouts) so a hung PDF flushes a partial
        # timedOut=true report and returns cleanly instead of being SIGKILLed and re-run cold.
        effective_timeout = timeout if timeout > 0 else 120.0

        # TODO(Task 14): TITANARUM_CRAC_CHECKPOINT restore-from-checkpoint tier
        # goes here, ahead of warm. Not implemented yet -> falls through below.

        warm = self._warm
        if warm is not None:
            # Always consume: a warm handle is tried at most once, whether it
            # turns out to be alive or already dead on arrival (Fix 4).
            self._warm = None
            if warm.proc.poll() is None:
                try:
                    _run_warm_job(warm, input, report_dir,
                                  timeout=effective_timeout, sha256=sha256)
                    return
                except Exception:  # noqa: BLE001 - fail-closed: fall through to cold
                    pass
            # Warm job failed, or the proc was dead on arrival: we are falling through to cold.
            # self._warm is already None, so close() can never reclaim this handle -- reap its
            # scratch here (the staged input PDF under in/, control files, boot log) so it does
            # not orphan in /tmp (tmpfs) across successive warm dispatches.
            shutil.rmtree(warm.scratch, ignore_errors=True)

        # Cold fallback (first attempt, or after a failed/dead warm attempt).
        # A warm attempt that died mid-processing may have left partial
        # attachment/image files under report_dir (the Java uniquePath()
        # appends -1/-2 rather than overwriting); clearing the dir before the
        # cold retry writes its own complete set prevents _enumerate_artifacts's
        # rglob sweep from picking up stale warm-run files that the cold
        # report.json never references, which would break cold-vs-warm parity
        # (Task 8). No-op when report_dir was never created (plain cold path
        # or a warm success, which already returned above).
        shutil.rmtree(report_dir, ignore_errors=True)
        _run_worker(input, report_dir, timeout=effective_timeout, sha256=sha256)


def _sha256_file(path: Path) -> str:
    import hashlib

    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()
