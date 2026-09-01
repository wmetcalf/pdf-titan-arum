"""Task 8: AOT bake + cold-vs-warm parity gate (the core warm-mode correctness
test). Marked 'jvm' (deselect with -m 'not jvm').

Builds the real jar and a real AOT cache (via deploy/docker/build-aot.sh)
once per test session, then for a handful of representative warmup-corpus
PDFs drives TitanArumEngine.detonate() twice: once cold (no warmup()), once
warm (warmup() called first, TITANARUM_AOT_CACHE set). The gate is:

  1. warmup() must actually succeed (engine._warm set) -- if the AOT cache
     were rejected at boot (flag mismatch -> JDK 25 silently falls back to
     an unshared boot, or worse, the boot altogether wedges/dies), that is a
     warm-defeating bug and this test must fail LOUDLY, not quietly compare
     cold-vs-cold and call it a pass.
  2. The SAME pre-booted JVM process must be the one that produced the warm
     report (proc.poll() == 0 after detonate(), input staged into the fixed
     warm scratch) -- proof the WARM branch executed, not a silent
     cold-fallback that happens to produce an identical report.
  3. report.json must be byte-identical cold vs warm after normalizing only
     the two known non-deterministic fields (generatedAt, outputDirectory).
  4. The mapped DetonationResult (payload tree / artifacts / warnings /
     status) must be structurally identical after the same normalization.
"""
from __future__ import annotations

import dataclasses
import json
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest
from blastbox.limits import Limits

from titanarum import engine as eng
from titanarum.engine import TitanArumEngine

pytestmark = pytest.mark.jvm

REPO = Path(__file__).resolve().parents[1]
JAR = REPO / "target" / "pdf-titan-arum-1.3.0.jar"
CORPUS_DIR = REPO / "deploy" / "docker" / "appcds-warmup-corpus"
BUILD_AOT = REPO / "deploy" / "docker" / "build-aot.sh"

# A handful of corpus fixtures that each produce a rich report (not the
# encrypted/acroform/launch-action ones, which are thin single-hit fixtures) --
# enough to exercise screenshots, URLs/phones, and embedded files without
# ballooning the AOT-record + 2x-detonate wall time of this test.
CORPUS_PDFS = [
    "01-multipage-render.pdf",
    "03-urls-phones.pdf",
    "04-embedded-file.pdf",
]

# The only fields report.json is allowed to differ on between a cold and a
# warm run: an absolute output-dir path (different tempdir per run) and a
# wall-clock timestamp. Every other field is process content and MUST match.
_NONDETERMINISTIC_REPORT_KEYS = {"generatedAt", "outputDirectory"}
_NONDETERMINISTIC_RESULT_KEYS = {"generated_at"}


@pytest.fixture(scope="module")
def jar() -> Path:
    if not JAR.is_file():
        subprocess.run(["mvn", "-q", "-DskipTests", "package"], cwd=REPO, check=True)
    assert JAR.is_file(), "jar build failed"
    return JAR


@pytest.fixture(scope="module")
def aot_cache(jar: Path) -> Path:
    if not shutil.which("java"):
        pytest.skip("no java on PATH")
    proc = subprocess.run(
        ["bash", str(BUILD_AOT), str(jar), str(CORPUS_DIR)],
        cwd=REPO, capture_output=True, text=True,
    )
    assert proc.returncode == 0, (
        f"build-aot.sh failed (exit {proc.returncode}):\n"
        f"--- stdout ---\n{proc.stdout}\n--- stderr ---\n{proc.stderr}"
    )
    lines = [line for line in proc.stdout.strip().splitlines() if line]
    assert lines, f"build-aot.sh produced no stdout:\n{proc.stderr}"
    cache_path = Path(lines[-1])
    assert cache_path.is_file(), (
        f"build-aot.sh did not produce a cache file at {cache_path}:\n{proc.stderr}"
    )
    return cache_path


def _normalize(node: Any, drop_keys: set[str]) -> Any:
    """Recursively replace the value of any dict key in `drop_keys` with a
    fixed placeholder, so the rest of the structure can be compared for
    byte/structural identity."""
    if isinstance(node, dict):
        out = {}
        for k, v in node.items():
            out[k] = f"<normalized:{k}>" if k in drop_keys else _normalize(v, drop_keys)
        return out
    if isinstance(node, list):
        return [_normalize(v, drop_keys) for v in node]
    return node


def _normalize_report(report: dict) -> dict:
    out = _normalize(report, _NONDETERMINISTIC_REPORT_KEYS)
    # KNOWN, PRE-EXISTING (non-warm) nondeterminism, verified cold-vs-cold
    # during Task 8: extractSelectedPages() (PdfTitanArumApp.java:2601)
    # imports each selected page into a brand-new `PDDocument` and calls
    # `.save(file)` with no file /ID ever set. PDFBox auto-generates a fresh,
    # effectively-random trailer /ID whenever a document has none, so
    # pages/page-NNNN.pdf (and therefore pagePdfs[].sha256, a hash of that
    # saved file) differs between ANY two runs of the identical input --
    # reproduced twice cold-vs-cold with zero warm involvement whatsoever.
    # It is unrelated to warmup()/AOT and out of scope for this task, so it
    # is normalized here rather than left to masquerade as a warm regression;
    # page/path identity for the same artifact is still compared strictly.
    for entry in out.get("pagePdfs") or []:
        if isinstance(entry, dict) and "sha256" in entry:
            entry["sha256"] = "<normalized:pagePdfs[].sha256-nondeterministic-pdfbox-file-id>"
    return out


def _plain(obj: Any) -> Any:
    """Convert a DetonationResult (a plain dataclass) whose fields are
    blastbox.contract pydantic models into a fully plain (dict/list/scalar)
    structure, so it can be normalized and compared with `==`."""
    if hasattr(obj, "model_dump"):
        return obj.model_dump()
    if dataclasses.is_dataclass(obj) and not isinstance(obj, type):
        return {f.name: _plain(getattr(obj, f.name)) for f in dataclasses.fields(obj)}
    if isinstance(obj, (list, tuple)):
        return [_plain(v) for v in obj]
    return obj


@pytest.mark.parametrize("pdf_name", CORPUS_PDFS)
def test_cold_vs_warm_parity(pdf_name: str, jar: Path, aot_cache: Path,
                             tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    if not shutil.which("java"):
        pytest.skip("no java on PATH")

    pdf = CORPUS_DIR / pdf_name
    assert pdf.is_file(), f"missing corpus fixture: {pdf}"

    monkeypatch.setenv("TITANARUM_WORKER_JAR", str(jar))
    monkeypatch.delenv("TITANARUM_JAVA_OPTS", raising=False)
    # Isolate this test's warm scratch from the module default (/tmp/titanarum-warm)
    # and from other parametrized cases running in the same session.
    warm_scratch = tmp_path / "warm-scratch"
    monkeypatch.setattr(eng, "_DEFAULT_WARM_SCRATCH", str(warm_scratch))

    # ---- cold: no warmup(), no AOT cache -----------------------------------
    monkeypatch.delenv("TITANARUM_AOT_CACHE", raising=False)
    outdir_cold = tmp_path / "out-cold"
    outdir_cold.mkdir()
    cold_engine = TitanArumEngine()
    cold_result = cold_engine.detonate(pdf, outdir_cold, Limits())

    # ---- warm: warmup() first, AOT cache present ---------------------------
    monkeypatch.setenv("TITANARUM_AOT_CACHE", str(aot_cache))
    outdir_warm = tmp_path / "out-warm"
    outdir_warm.mkdir()
    warm_engine = TitanArumEngine()
    warm_engine.warmup()

    # CRITICAL: prove warmup() actually succeeded. warmup() never raises on
    # failure -- it just leaves _warm None -- so if the AOT cache load-bearing
    # flag bundle in engine.py drifted from build-aot.sh's, or the cache was
    # otherwise rejected at boot, this is where that surfaces. Do NOT let the
    # test silently fall through to comparing cold-vs-cold.
    boot_log = warm_scratch / "warm-boot.log"
    boot_log_text = boot_log.read_text(errors="replace") if boot_log.is_file() else "<no boot log>"
    assert warm_engine._warm is not None, (
        "warmup() failed to boot/ready the warm JVM -- the AOT cache may have "
        "been rejected at boot (flag-bundle mismatch between engine.py "
        "_DEFAULT_JVM_FLAGS and the AOT bake), or the worker crashed before "
        f"announcing ready. warm-boot.log:\n{boot_log_text}"
    )
    # CRITICAL (self-contained AOT-loaded proof): the ready handshake above
    # proves the warm JVM booted, but NOT that it actually loaded the AOT
    # cache -- JDK 25 does not fail the boot (or this ready handshake) on a
    # flag-bundle mismatch, it silently falls back to an unshared boot and
    # logs exactly one "[error][aot]" line (verified empirically, see
    # build-aot.sh Phase 3 / task-8-report.md). That "[error]"-level line is
    # emitted even at the JVM's DEFAULT log level, with no -Xlog:aot needed
    # (also verified empirically: a clean cache load prints no aot lines at
    # all, while a broken flag bundle prints "[error][aot] ..." on stderr with
    # exit 0) -- so this warm boot's own log is enough, without adding any
    # -Xlog flag. Without this assertion, a regression that breaks AOT loading
    # while leaving the boot/handshake itself intact would still pass this
    # test as "warm", relying entirely on build-aot.sh's separate probe boot
    # (which could itself drift from the real runtime flags) to ever catch it.
    assert "[error][aot]" not in boot_log_text, (
        "warm-boot.log contains an [error][aot] line -- this warm boot's AOT "
        "cache was REJECTED (JDK 25 does this silently: exit 0, ready "
        "handshake still completes, only this log line is evidence). Likely a "
        "JVM flag-bundle drift between engine.py's _DEFAULT_JVM_FLAGS and the "
        f"AOT bake. warm-boot.log tail:\n{boot_log_text[-4000:]}"
    )
    booted_proc = warm_engine._warm.proc  # keep a reference; detonate() clears engine._warm

    warm_result = warm_engine.detonate(pdf, outdir_warm, Limits())

    # A warm handle is consumed unconditionally (success or fail-closed), so
    # `_warm is None` alone does NOT prove a warm run happened -- it's also
    # true after a silent cold fallback. The real proof: the SAME pre-booted
    # process is the one that ran the job to completion (exit 0).
    assert warm_engine._warm is None, "warm handle should be consumed by detonate()"
    assert booted_proc.poll() == 0, (
        "the pre-booted warm JVM did not exit 0 -- detonate() likely fell back "
        f"to a fresh cold boot instead of running the warm branch. warm-boot.log:\n{boot_log_text}"
    )
    # The fixed warm scratch (which staged the input under in/) is reaped on a successful warm
    # run, so the staged input must NOT linger in the shared scratch afterward.
    assert not (warm_scratch / "in" / pdf.name).is_file(), (
        "staged warm input must be reaped after a successful warm run, "
        "not left in the shared scratch"
    )

    # ---- parity gate 1: report.json byte-identical after normalization -----
    cold_report_path = outdir_cold / "titan" / "report.json"
    warm_report_path = outdir_warm / "titan" / "report.json"
    assert cold_report_path.is_file()
    assert warm_report_path.is_file()
    cold_report = json.loads(cold_report_path.read_bytes())
    warm_report = json.loads(warm_report_path.read_bytes())

    cold_norm = _normalize_report(cold_report)
    warm_norm = _normalize_report(warm_report)
    assert cold_norm == warm_norm, (
        "report.json content differs cold vs warm after normalizing only "
        "generatedAt/outputDirectory -- this is a warm-mode content bug, not "
        "an expected difference"
    )
    # Byte-identical re-serialization of the normalized structures (not just
    # dict equality) -- catches any stray formatting/ordering divergence too.
    assert (json.dumps(cold_norm, sort_keys=True, ensure_ascii=False)
            == json.dumps(warm_norm, sort_keys=True, ensure_ascii=False))

    # Sanity: the two really were non-deterministic on the fields we chose to
    # ignore (otherwise the normalization step above is vacuous and this test
    # would pass even if we'd forgotten to wire warm up correctly).
    assert cold_report["outputDirectory"] != warm_report["outputDirectory"]

    # ---- parity gate 2: DetonationResult structural identity ---------------
    cold_dict = _normalize(_plain(cold_result), _NONDETERMINISTIC_RESULT_KEYS)
    warm_dict = _normalize(_plain(warm_result), _NONDETERMINISTIC_RESULT_KEYS)
    assert cold_dict == warm_dict, (
        "DetonationResult (payload/artifacts/warnings/status) differs cold vs "
        "warm after normalization"
    )

    assert cold_result.status == warm_result.status
    assert [a.kind for a in cold_result.artifacts] == [a.kind for a in warm_result.artifacts]
    assert len(cold_result.artifacts) == len(warm_result.artifacts)
    assert [w.code for w in cold_result.warnings] == [w.code for w in warm_result.warnings]
