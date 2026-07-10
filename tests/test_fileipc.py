import json
import sys
from pathlib import Path

from titanarum import engine as eng

FAKE = Path(__file__).parent / "fixtures" / "fake_java_worker.py"


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
