import json
import sys
from pathlib import Path

from titanarum import engine as eng

FAKE = Path(__file__).parent / "fixtures" / "fake_java_worker.py"
FAKE_HARDHALT = Path(__file__).parent / "fixtures" / "fake_java_worker_hardhalt.py"


def test_java_worker_argv_shape(monkeypatch):
    monkeypatch.setenv("TITANARUM_JAVA_BIN", "java")
    monkeypatch.setenv("TITANARUM_JAVA_OPTS", "-Xmx1g -XX:+UseSerialGC")
    monkeypatch.setenv("TITANARUM_WORKER_JAR", "/app/pdf-titan-arum.jar")
    argv = eng._java_worker_argv(Path("/scr"))
    assert argv == ["java", "-Xmx1g", "-XX:+UseSerialGC",
                    "-jar", "/app/pdf-titan-arum.jar", "--run", "/scr"]


def test_run_worker_drives_handshake(tmp_path, monkeypatch):
    # The fake worker locates "--run" positionally in sys.argv, so we point
    # JAVA_BIN at the current python interpreter and route JAVA_OPTS at the
    # fake script itself: argv becomes [python, <fake>, -jar, dummy.jar,
    # --run, <scratch>], which the fake worker happily runs (ignoring the
    # "-jar dummy.jar" it doesn't understand).
    monkeypatch.setenv("TITANARUM_JAVA_BIN", sys.executable)
    monkeypatch.setenv("TITANARUM_JAVA_OPTS", str(FAKE))
    monkeypatch.setenv("TITANARUM_WORKER_JAR", "dummy.jar")

    inp = tmp_path / "in.pdf"
    inp.write_bytes(b"%PDF-1.4\n%%EOF\n")
    outdir = tmp_path / "titan"
    eng._run_worker(inp, outdir, timeout=30.0, sha256="a" * 64)
    report = json.loads((outdir / "report.json").read_text())
    assert report["documentSha256"] == "a" * 64
    assert report["inputPdf"] == "in.pdf"


def test_build_job_forwards_positive_jvm_timeout_below_subprocess_backstop(monkeypatch):
    # marla round-2: the JVM must receive a POSITIVE self-limit so its cooperative +
    # hard-halt watchdogs arm and it flushes a partial `timedOut=true` report; that limit
    # must sit below the Python subprocess backstop by at least the hard-halt margin
    # (TITANARUM_HARD_TIMEOUT_MS, the same env the JVM clamps on) so the JVM exits before
    # Python SIGKILLs it. Previously timeout_seconds was hardcoded 0 => JVM never self-limited.
    monkeypatch.setenv("TITANARUM_HARD_TIMEOUT_MS", "15000")
    jvm_s, subproc_s = eng._worker_timeouts(90.0)
    assert jvm_s == 90
    assert subproc_s >= 90 + 15  # backstop leaves room for the hard-halt margin + flush

    job = eng._build_job(Path("/in.pdf"), Path("/out"), "a" * 64, jvm_timeout_s=jvm_s)
    assert job["timeout_seconds"] == 90

    # A non-positive/degenerate budget must still yield a >=1s JVM limit (never 0 == "no limit"),
    # and the backstop must always exceed the JVM limit.
    jvm0, sub0 = eng._worker_timeouts(0.0)
    assert jvm0 >= 1
    assert sub0 > jvm0

    # A malformed hard-timeout env must not crash the split (falls back to the 15s default).
    monkeypatch.setenv("TITANARUM_HARD_TIMEOUT_MS", "not-a-number")
    jvm_bad, sub_bad = eng._worker_timeouts(60.0)
    assert jvm_bad == 60 and sub_bad > 60

    # A FRACTIONAL budget must round the JVM self-limit UP, never truncate down: truncating
    # would fire the cooperative watchdog before `effective`, cutting off a valid PDF that
    # previously completed (the JVM used to run unbounded until the Python timeout).
    jvm_frac, _ = eng._worker_timeouts(90.5)
    assert jvm_frac == 91


def test_run_worker_consumes_partial_report_on_hardhalt_exit(tmp_path, monkeypatch):
    # A hung PDF's hard-halt flushes a valid partial report.json (timedOut=true) and then
    # exit(3). The cold path must CONSUME that partial report, not raise on the nonzero exit
    # and discard it -- otherwise the fix that armed the JVM watchdog defeats its own purpose.
    monkeypatch.setenv("TITANARUM_JAVA_BIN", sys.executable)
    monkeypatch.setenv("TITANARUM_JAVA_OPTS", str(FAKE_HARDHALT))
    monkeypatch.setenv("TITANARUM_WORKER_JAR", "dummy.jar")

    inp = tmp_path / "in.pdf"
    inp.write_bytes(b"%PDF-1.4\n%%EOF\n")
    outdir = tmp_path / "titan"
    eng._run_worker(inp, outdir, timeout=30.0, sha256="b" * 64)  # must NOT raise on exit 3
    report = json.loads((outdir / "report.json").read_text())
    assert report["timedOut"] is True
    assert report["documentSha256"] == "b" * 64


def test_run_worker_still_raises_on_nonzero_exit_without_report(tmp_path, monkeypatch):
    # A genuine crash (nonzero exit, NO report.json) must still fail closed -- the partial-report
    # tolerance above must not mask real worker failures.
    crasher = tmp_path / "crasher.py"
    crasher.write_text(
        "import sys, time\n"
        "from pathlib import Path\n"
        "argv = sys.argv[1:]\n"
        "c = Path(argv[argv.index('--run')+1]) / 'control'\n"
        "c.mkdir(parents=True, exist_ok=True)\n"
        "(c/'control.ready').touch()\n"
        "d = time.monotonic()+30\n"
        "while time.monotonic() < d and not (c/'control.go').exists(): time.sleep(0.02)\n"
        "sys.exit(1)\n"  # exit nonzero, never write report.json
    )
    monkeypatch.setenv("TITANARUM_JAVA_BIN", sys.executable)
    monkeypatch.setenv("TITANARUM_JAVA_OPTS", str(crasher))
    monkeypatch.setenv("TITANARUM_WORKER_JAR", "dummy.jar")

    inp = tmp_path / "in.pdf"
    inp.write_bytes(b"%PDF-1.4\n%%EOF\n")
    outdir = tmp_path / "titan"
    import pytest
    with pytest.raises(RuntimeError):
        eng._run_worker(inp, outdir, timeout=30.0, sha256="c" * 64)


def test_env_dpi_is_clamped_finite_and_bounded(monkeypatch):
    # TITANARUM_DPI is dispatcher-forwarded (so potentially client-influenced) and worker mode
    # (callWith) does NOT re-apply the CLI's 1..MAX_DPI clamp. A huge DPI -> gigapixel raster ->
    # OOM; a non-finite DPI serializes as non-standard JSON and breaks job.json parsing. Clamp it
    # at the job boundary: drop non-finite, clamp finite values into [1, 600].
    for key in ("TITANARUM_SKIP_QR",):
        monkeypatch.delenv(key, raising=False)
    monkeypatch.setenv("TITANARUM_DPI", "100000")
    assert eng._env_param_overrides()["dpi"] == 600.0
    monkeypatch.setenv("TITANARUM_DPI", "0")
    assert eng._env_param_overrides()["dpi"] == 1.0
    monkeypatch.setenv("TITANARUM_DPI", "150")
    assert eng._env_param_overrides()["dpi"] == 150.0
    monkeypatch.setenv("TITANARUM_DPI", "Infinity")
    assert "dpi" not in eng._env_param_overrides()  # non-finite dropped -> _DEFAULT_JOB dpi used
    monkeypatch.setenv("TITANARUM_DPI", "NaN")
    assert "dpi" not in eng._env_param_overrides()


def test_env_param_overrides_allowlist(monkeypatch):
    monkeypatch.setenv("TITANARUM_SKIP_QR", "1")
    monkeypatch.setenv("TITANARUM_DPI", "200")
    monkeypatch.setenv("TITANARUM_OCR_LANG", "eng+deu")
    monkeypatch.delenv("TITANARUM_SKIP_IMAGES", raising=False)
    over = eng._env_param_overrides()
    assert over["skip_qr"] is True
    assert over["dpi"] == 200.0
    assert over["ocr_lang"] == "eng+deu"
    assert "skip_images" not in over  # unset keys are not injected


def test_skip_tables_env_override(monkeypatch):
    monkeypatch.setenv("TITANARUM_SKIP_TABLES", "1")
    over = eng._env_param_overrides()
    assert over["skip_tables"] is True


def test_skip_tables_default_off():
    assert eng._DEFAULT_JOB["skip_tables"] is False
