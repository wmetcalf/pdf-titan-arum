"""titanarum's output matches its contract (blastbox-engine.toml).

tests/conftest.py already checks every TitanArumEngine.detonate() the suite makes; this module
validates the contract itself and runs the engine over the AOT warmup corpus (real jar, default
job: screenshots and images ON) so every artifact family the corpus can produce is checked.

The checker ships on an unreleased blastbox branch, so this module skips on a released one.
"""
from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ec = pytest.importorskip("blastbox.contract.engine_contract")

from blastbox.limits import Limits  # noqa: E402

from titanarum.engine import TitanArumEngine  # noqa: E402

REPO = Path(__file__).resolve().parents[1]
JAR = REPO / "target" / "pdf-titan-arum-1.3.0.jar"
CORPUS = sorted((REPO / "deploy" / "docker" / "appcds-warmup-corpus").glob("*.pdf"))
FAKE = Path(__file__).parent / "fixtures" / "fake_java_worker.py"
CONTRACT_PATH = ec.find_contract(__file__)
CONTRACT = ec.load_contract(CONTRACT_PATH)


def test_contract_validates_and_names_this_engine():
    assert CONTRACT_PATH == REPO / "blastbox-engine.toml"
    assert ec.main(["validate", str(CONTRACT_PATH)]) == 0
    assert CONTRACT.engine.name == TitanArumEngine.name
    assert set(CONTRACT.engine.formats) == set(TitanArumEngine.formats)


def test_cold_fake_worker_result_conforms(tmp_path, monkeypatch):
    monkeypatch.setenv("TITANARUM_JAVA_BIN", sys.executable)
    monkeypatch.setenv("TITANARUM_JAVA_OPTS", str(FAKE))
    monkeypatch.setenv("TITANARUM_WORKER_JAR", "dummy.jar")
    pdf = tmp_path / "in.pdf"
    pdf.write_bytes(b"%PDF-1.4\n%%EOF\n")
    outdir = tmp_path / "out"
    outdir.mkdir()
    result = TitanArumEngine().detonate(pdf, outdir, Limits())
    ec.assert_conforms(CONTRACT, result, outdir)


@pytest.fixture(scope="module")
def jar() -> Path:
    if not JAR.is_file():
        subprocess.run(["mvn", "-q", "-DskipTests", "package"], cwd=REPO, check=True)
    assert JAR.is_file(), "jar build failed"
    return JAR


@pytest.mark.jvm
@pytest.mark.parametrize("pdf", CORPUS, ids=lambda p: p.stem)
def test_corpus_result_conforms(tmp_path, jar, monkeypatch, pdf):
    if not shutil.which("java"):
        pytest.skip("no java on PATH")
    monkeypatch.setenv("TITANARUM_WORKER_JAR", str(jar))
    for k in ("TITANARUM_JAVA_OPTS", "TITANARUM_AOT_CACHE", "TITANARUM_SKIP_SCREENSHOTS",
              "TITANARUM_SKIP_IMAGES"):
        monkeypatch.delenv(k, raising=False)
    outdir = tmp_path / "out"
    outdir.mkdir()
    result = TitanArumEngine().detonate(pdf, outdir, Limits.from_env())
    ec.assert_conforms(CONTRACT, result, outdir)
    if pdf.stem == "10-image-jpeg2000":
        # Evidence for gap loadout-refuses-jpx-jb2: a kind=image artifact that is neither PNG
        # nor JPEG, which Loadout's document_outputs.map_outputs refuses outright.
        assert "loadout-refuses-jpx-jb2" in {g.id for g in CONTRACT.gaps}
        jpx = [a for a in result.artifacts if a.kind == "image" and a.path.endswith(".jpx")]
        assert jpx, [a.path for a in result.artifacts]
        head = (outdir / jpx[0].path).read_bytes()[:8]
        assert not head.startswith((b"\x89PNG", b"\xff\xd8\xff")), head


def test_a_full_image_budget_fits_the_image_families():
    # the JVM's image budget counts IMAGES; each may write a PNG decode and an original codestream,
    # and a colliding original is renamed '...-original-1.jpg' (outside the -original family). A
    # budget-full document must not trip artifact_count, and a PNG named '*-original' must not fall
    # under the original-bytes byte cap.
    from titanarum import engine as eng
    used: set[str] = set()
    sfx = [""] + [f"-{i}" for i in range(1, 2000)]
    paths = [("titan/report.json", "report")]
    res = "titan/images_resources/resource-page-0001-Im0"
    paths += [(f"{res}{s}.png", "image") for s in sfx]
    paths += [(f"{res}-original{s}.jpg", "image") for s in sfx]
    arts = [(eng._safe_artifact_id(p, used), p, k, 1) for p, k in paths]
    v = ec._check(CONTRACT, engine=None, status="ok", artifacts=arts, payload=None)
    assert not [x for x in v if x.code == "artifact_count"], [str(x) for x in v]
    big_png = [(eng._safe_artifact_id("titan/images_rendered/x-original.png", set()),
                "titan/images_rendered/x-original.png", "image", 200 * 1024 * 1024)]
    v = ec._check(CONTRACT, engine=None, status="ok", artifacts=big_png, payload=None)
    assert not [x for x in v if "large" in x.code or "bytes" in x.code], [str(x) for x in v]
