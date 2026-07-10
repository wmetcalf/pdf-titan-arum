"""titanarum blastbox engine — drives the pdf-titan-arum JVM worker over file-IPC.

The disposable blastbox worker cannot nest `docker run`, so we launch the JVM
IN-PROCESS (`java … -jar pdf-titan-arum.jar --run <scratch>`) and hand it one job
via a control-file handshake, mirroring RedTusk.
"""
from __future__ import annotations

import json
import os
import shlex
import subprocess
import tempfile
import threading
import time
from pathlib import Path
from typing import Any

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
