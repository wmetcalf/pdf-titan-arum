"""End-to-end via the REAL pdf-titan-arum jar. Marked 'jvm' (deselect with -m 'not jvm')."""
import shutil
import subprocess
from pathlib import Path

import pytest

pytestmark = pytest.mark.jvm

REPO = Path(__file__).resolve().parents[1]
JAR = REPO / "target" / "pdf-titan-arum-1.3.0.jar"


def _make_pdf(dst: Path) -> None:
    # A minimal valid 1-page PDF.
    dst.write_bytes(
        b"%PDF-1.4\n"
        b"1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
        b"2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
        b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]>>endobj\n"
        b"xref\n0 4\n0000000000 65535 f \n0000000009 00000 n \n"
        b"0000000052 00000 n \n0000000101 00000 n \n"
        b"trailer<</Size 4/Root 1 0 R>>\nstartxref\n164\n%%EOF\n")


@pytest.fixture(scope="module")
def jar() -> Path:
    if not JAR.is_file():
        subprocess.run(["mvn", "-q", "-DskipTests", "package"], cwd=REPO, check=True)
    assert JAR.is_file(), "jar build failed"
    return JAR


def test_detonate_real_pdf(tmp_path, jar, monkeypatch):
    if not shutil.which("java"):
        pytest.skip("no java on PATH")
    from blastbox.limits import Limits

    from titanarum.engine import TitanArumEngine

    monkeypatch.setenv("TITANARUM_WORKER_JAR", str(jar))
    monkeypatch.delenv("TITANARUM_JAVA_OPTS", raising=False)
    monkeypatch.delenv("TITANARUM_AOT_CACHE", raising=False)
    # Keep the MVP fast/deterministic: no screenshots/images/OCR.
    monkeypatch.setenv("BLASTBOX_ENGINE_TITANARUM_PARAM_KEYS", "")  # no-op locally
    monkeypatch.setenv("TITANARUM_SKIP_SCREENSHOTS", "1")
    monkeypatch.setenv("TITANARUM_SKIP_IMAGES", "1")

    pdf = tmp_path / "in.pdf"
    _make_pdf(pdf)
    outdir = tmp_path / "out"
    outdir.mkdir()

    limits = Limits.from_env()
    result = TitanArumEngine().detonate(pdf, outdir, limits)

    assert result.status in ("ok", "rejected")
    assert result.detected.source == "titanarum"
    # report.json declared + present on disk
    report_arts = [a for a in result.artifacts if a.kind == "report"]
    assert report_arts, "report.json must be declared"
    assert (outdir / report_arts[0].path).is_file()
    # the JVM MUST NOT have written a metadata.json (harness owns it)
    assert not (outdir / "metadata.json").exists()


def test_detonate_real_pdf_produces_page(tmp_path, jar, monkeypatch):
    if not shutil.which("java"):
        pytest.skip("no java on PATH")
    from blastbox.contract import Page
    from blastbox.limits import Limits

    from titanarum.engine import TitanArumEngine

    monkeypatch.setenv("TITANARUM_WORKER_JAR", str(jar))
    monkeypatch.delenv("TITANARUM_JAVA_OPTS", raising=False)
    monkeypatch.delenv("TITANARUM_AOT_CACHE", raising=False)
    # Screenshots ON (this is the C1 regression surface); skip images for speed.
    monkeypatch.delenv("TITANARUM_SKIP_SCREENSHOTS", raising=False)
    monkeypatch.setenv("TITANARUM_SKIP_IMAGES", "1")
    # The minimal fixture PDF has no content stream, so it's blank; force a
    # screenshot to be taken anyway (blank pages are skipped by default).
    monkeypatch.setenv("TITANARUM_NO_SKIP_BLANKS", "1")

    pdf = tmp_path / "in.pdf"
    _make_pdf(pdf)
    outdir = tmp_path / "out"
    outdir.mkdir()

    limits = Limits.from_env()
    result = TitanArumEngine().detonate(pdf, outdir, limits)

    pages = [c for c in result.payload.children if isinstance(c, Page)]
    assert pages, "screenshots ON must produce at least one Page node"
    art_ids = {a.id for a in result.artifacts}
    assert pages[0].image.id in art_ids, "Page.image must reference a DECLARED artifact"
