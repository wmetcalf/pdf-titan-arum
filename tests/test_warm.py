"""W-4: TitanArumEngine.warmup() + the warm branch in _produce_report + fail-closed.

Reuses the fake-java-worker harness from test_fileipc.py: it announces
control.ready, blocks on control.go, reads job.json, writes report.json, and
exits -- exactly the one-job warm flow (boot once via warmup(), dispatch once
via detonate()).
"""
import json
import subprocess
import sys
from pathlib import Path

from blastbox.limits import Limits

from titanarum import engine as eng
from titanarum.engine import TitanArumEngine, _WarmWorker

FAKE = Path(__file__).parent / "fixtures" / "fake_java_worker.py"
FAKE_HARDHALT = Path(__file__).parent / "fixtures" / "fake_java_worker_hardhalt.py"


def _route_to_fake(monkeypatch) -> None:
    monkeypatch.setenv("TITANARUM_JAVA_BIN", sys.executable)
    monkeypatch.setenv("TITANARUM_JAVA_OPTS", str(FAKE))
    monkeypatch.setenv("TITANARUM_WORKER_JAR", "dummy.jar")


def test_warm_hardhalt_partial_report_is_consumed_not_discarded(tmp_path, monkeypatch):
    # The warm worker's hard-halt flushes a partial report.json then exit(3). _run_warm_job must
    # accept that partial (return without raising) so the caller keeps it -- previously the
    # nonzero exit raised, _produce_report rmtree'd the partial and re-ran the SAME hung PDF
    # cold, burning ~2x the budget and discarding the report the hard-halt deliberately saved.
    monkeypatch.setenv("TITANARUM_JAVA_BIN", sys.executable)
    monkeypatch.setenv("TITANARUM_JAVA_OPTS", str(FAKE_HARDHALT))
    monkeypatch.setenv("TITANARUM_WORKER_JAR", "dummy.jar")
    scratch = tmp_path / "warm-scratch"
    monkeypatch.setattr(eng, "_DEFAULT_WARM_SCRATCH", str(scratch))

    engine = TitanArumEngine()
    engine.warmup()
    assert engine._warm is not None
    warm = engine._warm

    pdf = tmp_path / "in.pdf"
    pdf.write_bytes(b"%PDF-1.4\n%%EOF\n")
    report_dir = tmp_path / "out" / "titan"

    # Must NOT raise despite the exit-3 hard-halt, and the flushed partial must be on disk.
    eng._run_warm_job(warm, pdf, report_dir, timeout=30.0, sha256="d" * 64)
    report = json.loads((report_dir / "report.json").read_text())
    assert report["timedOut"] is True
    assert report["documentSha256"] == "d" * 64


def test_warmup_then_detonate_feeds_the_preboot_worker(tmp_path, monkeypatch):
    _route_to_fake(monkeypatch)
    scratch = tmp_path / "warm-scratch"
    monkeypatch.setattr(eng, "_DEFAULT_WARM_SCRATCH", str(scratch))

    engine = TitanArumEngine()
    engine.warmup()
    assert engine._warm is not None, "warmup() should have booted + readied the fake worker"
    booted_proc = engine._warm.proc

    pdf = tmp_path / "in.pdf"
    pdf.write_bytes(b"%PDF-1.4\n%%EOF\n")
    outdir = tmp_path / "out"
    outdir.mkdir()

    result = engine.detonate(pdf, outdir, Limits())

    # The warm handle was consumed (one job per warm JVM) -- proof the WARM
    # branch ran, not a fresh cold boot.
    assert engine._warm is None
    report_path = outdir / "titan" / "report.json"
    assert report_path.is_file()
    # The staged input landed inside the pre-booted worker's fixed scratch,
    # not a throwaway cold tempdir.
    assert (scratch / "in" / "in.pdf").is_file()
    assert result.status in ("ok", "rejected")
    assert result.detected.source == "titanarum"
    # The pre-booted process ran the job to completion (communicate() reaped it).
    assert booted_proc.poll() == 0


def test_warmup_never_raises_on_bogus_worker(tmp_path, monkeypatch):
    monkeypatch.setenv("TITANARUM_JAVA_BIN", str(tmp_path / "no-such-java-binary"))
    monkeypatch.setenv("TITANARUM_WORKER_JAR", str(tmp_path / "no-such.jar"))
    monkeypatch.delenv("TITANARUM_JAVA_OPTS", raising=False)
    scratch = tmp_path / "warm-scratch"
    monkeypatch.setattr(eng, "_DEFAULT_WARM_SCRATCH", str(scratch))

    engine = TitanArumEngine()
    engine.warmup()  # must not raise

    assert engine._warm is None


def test_warmup_never_raises_when_worker_dies_before_ready(tmp_path, monkeypatch):
    # A "worker" that exits immediately without ever announcing control.ready.
    monkeypatch.setenv("TITANARUM_JAVA_BIN", sys.executable)
    monkeypatch.setenv("TITANARUM_JAVA_OPTS", "-c \"import sys; sys.exit(1)\"")
    monkeypatch.setenv("TITANARUM_WORKER_JAR", "dummy.jar")
    scratch = tmp_path / "warm-scratch"
    monkeypatch.setattr(eng, "_DEFAULT_WARM_SCRATCH", str(scratch))

    engine = TitanArumEngine()
    engine.warmup()  # must not raise

    assert engine._warm is None


def test_dead_warm_handle_falls_back_to_cold(tmp_path, monkeypatch):
    _route_to_fake(monkeypatch)

    dead_scratch = tmp_path / "dead-warm"
    in_dir = dead_scratch / "in"
    control_dir = dead_scratch / "control"
    in_dir.mkdir(parents=True)
    control_dir.mkdir(parents=True)

    dead_proc = subprocess.Popen([sys.executable, "-c", "pass"])
    dead_proc.wait(timeout=10)
    assert dead_proc.poll() is not None  # confirm it is in fact dead

    engine = TitanArumEngine()
    engine._warm = _WarmWorker(proc=dead_proc, scratch=dead_scratch,
                               in_dir=in_dir, control_dir=control_dir)

    pdf = tmp_path / "in.pdf"
    pdf.write_bytes(b"%PDF-1.4\n%%EOF\n")
    outdir = tmp_path / "out"
    outdir.mkdir()

    result = engine.detonate(pdf, outdir, Limits())

    # Cold fallback still produced a valid report, despite the dead warm handle.
    report_path = outdir / "titan" / "report.json"
    assert report_path.is_file()
    assert result.status in ("ok", "rejected")
    # The cold path stages into its own throwaway tempdir, never the dead
    # handle's fixed scratch.
    assert not (in_dir / "in.pdf").is_file()


def test_warm_job_failure_falls_back_to_cold(tmp_path, monkeypatch):
    """A warm JVM that is alive but then fails the job (nonzero exit) must
    fail closed to the cold _run_worker path rather than propagate."""
    monkeypatch.setenv("TITANARUM_JAVA_BIN", sys.executable)
    monkeypatch.setenv("TITANARUM_WORKER_JAR", "dummy.jar")

    scratch = tmp_path / "warm-scratch"
    monkeypatch.setattr(eng, "_DEFAULT_WARM_SCRATCH", str(scratch))
    in_dir = scratch / "in"
    control_dir = scratch / "control"
    in_dir.mkdir(parents=True)
    control_dir.mkdir(parents=True)
    (control_dir / "control.ready").touch()

    # A worker that announces ready, waits for go, then exits nonzero without
    # writing a report -- simulating a warm job that blows up mid-flight.
    failing_worker = tmp_path / "failing_worker.py"
    failing_worker.write_text(
        "import sys, time\n"
        "from pathlib import Path\n"
        "control = Path(sys.argv[sys.argv.index('--run') + 1]) / 'control'\n"
        "go = control / 'control.go'\n"
        "deadline = time.monotonic() + 10\n"
        "while time.monotonic() < deadline and not go.exists():\n"
        "    time.sleep(0.02)\n"
        "sys.exit(3)\n"
    )
    proc = subprocess.Popen(
        [sys.executable, str(failing_worker), "-jar", "dummy.jar", "--run", str(scratch)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    engine = TitanArumEngine()
    engine._warm = _WarmWorker(proc=proc, scratch=scratch, in_dir=in_dir, control_dir=control_dir)

    # Route the COLD fallback to the real fake worker so detonate() can still
    # succeed end-to-end.
    _route_to_fake(monkeypatch)

    pdf = tmp_path / "in.pdf"
    pdf.write_bytes(b"%PDF-1.4\n%%EOF\n")
    outdir = tmp_path / "out"
    outdir.mkdir()

    result = engine.detonate(pdf, outdir, Limits())

    assert engine._warm is None  # consumed by the (failed) warm attempt
    report_path = outdir / "titan" / "report.json"
    assert report_path.is_file()
    assert result.status in ("ok", "rejected")


def test_warm_job_timeout_falls_back_to_cold_and_kills_hung_proc(tmp_path, monkeypatch):
    """A warm JVM that is alive, accepts the job, but then HANGS past the
    communicate() timeout (e.g. wedged in a native call) must fail closed to
    the cold _run_worker path -- and the hung proc must be killed, not
    leaked."""
    scratch = tmp_path / "warm-scratch"
    monkeypatch.setattr(eng, "_DEFAULT_WARM_SCRATCH", str(scratch))
    in_dir = scratch / "in"
    control_dir = scratch / "control"
    in_dir.mkdir(parents=True)
    control_dir.mkdir(parents=True)
    (control_dir / "control.ready").touch()

    # A worker that announces ready, waits for go, then hangs forever instead
    # of ever exiting -- simulating a wedged warm job.
    hang_worker = tmp_path / "hang_worker.py"
    hang_worker.write_text(
        "import sys, time\n"
        "from pathlib import Path\n"
        "control = Path(sys.argv[sys.argv.index('--run') + 1]) / 'control'\n"
        "go = control / 'control.go'\n"
        "deadline = time.monotonic() + 30\n"
        "while time.monotonic() < deadline and not go.exists():\n"
        "    time.sleep(0.02)\n"
        "time.sleep(3600)\n"  # hang well past any test timeout; killed by the test
    )
    proc = subprocess.Popen(
        [sys.executable, str(hang_worker), "-jar", "dummy.jar", "--run", str(scratch)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    engine = TitanArumEngine()
    engine._warm = _WarmWorker(proc=proc, scratch=scratch, in_dir=in_dir, control_dir=control_dir)

    # Route the COLD fallback to the real fake worker so detonate() can still
    # succeed end-to-end.
    _route_to_fake(monkeypatch)

    pdf = tmp_path / "in.pdf"
    pdf.write_bytes(b"%PDF-1.4\n%%EOF\n")
    outdir = tmp_path / "out"
    outdir.mkdir()

    try:
        # A short timeout keeps the test fast: _run_warm_job's communicate()
        # will raise TimeoutExpired well before the worker's own 3600s sleep.
        result = engine.detonate(pdf, outdir, Limits(timeout_s=2))

        assert engine._warm is None  # consumed by the (timed-out) warm attempt
        report_path = outdir / "titan" / "report.json"
        assert report_path.is_file()
        assert result.status in ("ok", "rejected")
        # The hung warm proc was killed rather than leaked.
        assert proc.poll() is not None
    finally:
        if proc.poll() is None:
            proc.kill()
        proc.wait(timeout=5)


def test_warm_job_exits_zero_without_report_falls_back_to_cold(tmp_path, monkeypatch):
    """A warm JVM that is alive, accepts the job, and exits 0 -- but never
    writes report.json (a silent no-op instead of an error) -- must fail
    closed to the cold _run_worker path rather than surface a missing
    report.json all the way up to detonate()."""
    scratch = tmp_path / "warm-scratch"
    monkeypatch.setattr(eng, "_DEFAULT_WARM_SCRATCH", str(scratch))
    in_dir = scratch / "in"
    control_dir = scratch / "control"
    in_dir.mkdir(parents=True)
    control_dir.mkdir(parents=True)
    (control_dir / "control.ready").touch()

    # A worker that announces ready, waits for go, reads job.json, then exits
    # 0 WITHOUT ever writing report.json.
    noreport_worker = tmp_path / "noreport_worker.py"
    noreport_worker.write_text(
        "import json, sys, time\n"
        "from pathlib import Path\n"
        "control = Path(sys.argv[sys.argv.index('--run') + 1]) / 'control'\n"
        "go = control / 'control.go'\n"
        "deadline = time.monotonic() + 10\n"
        "while time.monotonic() < deadline and not go.exists():\n"
        "    time.sleep(0.02)\n"
        "job = json.loads((control / 'job.json').read_text())\n"
        "sys.exit(0)\n"
    )
    proc = subprocess.Popen(
        [sys.executable, str(noreport_worker), "-jar", "dummy.jar", "--run", str(scratch)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    engine = TitanArumEngine()
    engine._warm = _WarmWorker(proc=proc, scratch=scratch, in_dir=in_dir, control_dir=control_dir)

    # Route the COLD fallback to the real fake worker so detonate() can still
    # succeed end-to-end.
    _route_to_fake(monkeypatch)

    pdf = tmp_path / "in.pdf"
    pdf.write_bytes(b"%PDF-1.4\n%%EOF\n")
    outdir = tmp_path / "out"
    outdir.mkdir()

    result = engine.detonate(pdf, outdir, Limits())

    assert engine._warm is None  # consumed by the (report-less) warm attempt
    report_path = outdir / "titan" / "report.json"
    assert report_path.is_file()
    assert result.status in ("ok", "rejected")


def test_warm_failure_clears_stale_report_dir_before_cold_fallback(tmp_path, monkeypatch):
    """A warm attempt that dies mid-processing may leave partial output files
    under report_dir (e.g. an attachment written before the crash). The cold
    fallback must NOT inherit those stale files: _produce_report clears
    report_dir before invoking _run_worker, so _enumerate_artifacts's rglob
    sweep can't pick up a leftover file the cold report.json never
    references (parity-critical for the cold-vs-warm gate)."""
    scratch = tmp_path / "warm-scratch"
    monkeypatch.setattr(eng, "_DEFAULT_WARM_SCRATCH", str(scratch))
    in_dir = scratch / "in"
    control_dir = scratch / "control"
    in_dir.mkdir(parents=True)
    control_dir.mkdir(parents=True)
    (control_dir / "control.ready").touch()

    outdir = tmp_path / "out"
    outdir.mkdir()
    report_dir = outdir / "titan"

    # A worker that announces ready, waits for go, writes a STRAY partial file
    # directly under report_dir (as a mid-crash warm run might), then exits
    # nonzero without ever writing report.json.
    stray_worker = tmp_path / "stray_worker.py"
    stray_worker.write_text(
        "import sys, time\n"
        "from pathlib import Path\n"
        "control = Path(sys.argv[sys.argv.index('--run') + 1]) / 'control'\n"
        "go = control / 'control.go'\n"
        "deadline = time.monotonic() + 10\n"
        "while time.monotonic() < deadline and not go.exists():\n"
        "    time.sleep(0.02)\n"
        f"stray_dir = Path(r'{report_dir}') / 'attachments'\n"
        "stray_dir.mkdir(parents=True, exist_ok=True)\n"
        "(stray_dir / 'partial-1.bin').write_bytes(b'partial-warm-output')\n"
        "sys.exit(3)\n"
    )
    proc = subprocess.Popen(
        [sys.executable, str(stray_worker), "-jar", "dummy.jar", "--run", str(scratch)],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

    engine = TitanArumEngine()
    engine._warm = _WarmWorker(proc=proc, scratch=scratch, in_dir=in_dir, control_dir=control_dir)

    _route_to_fake(monkeypatch)

    pdf = tmp_path / "in.pdf"
    pdf.write_bytes(b"%PDF-1.4\n%%EOF\n")

    result = engine.detonate(pdf, outdir, Limits())

    assert engine._warm is None
    report_path = outdir / "titan" / "report.json"
    assert report_path.is_file()
    assert result.status in ("ok", "rejected")
    # The stray file left by the dying warm attempt must be gone -- the cold
    # fallback cleared report_dir before writing its own complete set.
    assert not (report_dir / "attachments" / "partial-1.bin").is_file()
    stray_rel = "titan/attachments/partial-1.bin"
    assert not any(a.path == stray_rel for a in result.artifacts)


def test_close_tears_down_unused_warm_worker(tmp_path, monkeypatch):
    _route_to_fake(monkeypatch)
    scratch = tmp_path / "warm-scratch"
    monkeypatch.setattr(eng, "_DEFAULT_WARM_SCRATCH", str(scratch))

    engine = TitanArumEngine()
    engine.warmup()
    assert engine._warm is not None
    proc = engine._warm.proc

    engine.close()

    assert engine._warm is None
    assert proc.poll() is not None  # killed
    assert not scratch.exists()  # scratch cleaned up
