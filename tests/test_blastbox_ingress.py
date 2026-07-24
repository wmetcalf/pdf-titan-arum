"""Tests for titanarum's blastbox ingress extension (the report.json route).

Clone of RedTusk's tests/http/test_blastbox_ingress.py, adapted to titanarum's
report_dir convention (outdir/titan) and its ``/v1/jobs/{id}/report`` route.

Builds the shared blastbox ingress with titanarum's ``IngressExtension`` mounted
and asserts the product route ``GET /v1/jobs/{id}/report`` serves the canonical
report document (``titan/report.json``) from a DONE job's output dir — reusing
the core's confinement via ``app.state.serve_artifact_file`` (DONE-gate,
containment, no-symlink-follow).

Self-contained: builds its own app via ``build_app`` and does not depend on any
bespoke-api fixtures. Output lives under ``<job_root>/<id>/output`` — this test
passes ``job_root=tmp_path/"jobs"``.
"""

from __future__ import annotations

import json
import time
from pathlib import Path

import pytest

pytest.importorskip("blastbox.host.ingress.app")

from blastbox.host.ingress.app import build_app  # noqa: E402
from blastbox.host.jobs.base import Job, JobStatus  # noqa: E402
from blastbox.host.jobs.memory import InMemoryJobStore  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

from titanarum.blastbox_ingress import _STATIC_DIR, make_extension  # noqa: E402

_REPORT_BYTES = (
    b'{"inputPdf":"in.pdf","documentSha256":"' + b"a" * 64 + b'","pageCount":1,'
    b'"urls":[],"tables":[]}'
)


def _make_client(tmp_path: Path) -> tuple[TestClient, InMemoryJobStore]:
    store = InMemoryJobStore()
    app = build_app(
        job_store=store,
        job_root=tmp_path / "jobs",
        allowed_engines={"titanarum"},
        extension=make_extension(),
    )
    return TestClient(app, raise_server_exceptions=False), store


def _make_done_job(tmp_path: Path, store: InMemoryJobStore, *, write_report: bool = True) -> Job:
    """Create a DONE titanarum job with titan/report.json under its output dir."""
    job = Job.new(engine="titanarum", filename="test.pdf")
    output_dir = tmp_path / "jobs" / job.job_id / "output"
    (output_dir / "titan").mkdir(parents=True)
    if write_report:
        (output_dir / "titan" / "report.json").write_bytes(_REPORT_BYTES)

    # Dispatcher-sealed envelope manifest. titanarum's engine (_enumerate_artifacts)
    # DOES declare titan/report.json (kind="report"), so it goes through the trust
    # gate; the fixed-filename /report route serves ONLY paths declared here.
    (output_dir / "metadata.json").write_text(
        json.dumps({"artifacts": [{"path": "titan/report.json"}]})
    )

    job.result_dir = str(output_dir)
    job.input_sha256 = "a" * 64
    job.status = JobStatus.DONE
    job.finished_at = time.time()
    store.create(job)
    return job


def test_report_route_served(tmp_path):
    client, store = _make_client(tmp_path)
    job = _make_done_job(tmp_path, store)
    resp = client.get(f"/v1/jobs/{job.job_id}/report")
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("application/json")
    assert resp.content == _REPORT_BYTES


def test_report_missing_returns_404(tmp_path):
    client, store = _make_client(tmp_path)
    job = _make_done_job(tmp_path, store, write_report=False)
    resp = client.get(f"/v1/jobs/{job.job_id}/report")
    assert resp.status_code == 404


def test_report_409_when_not_done(tmp_path):
    """The core DONE-gate (via serve_artifact_file) applies to the product route."""
    client, store = _make_client(tmp_path)
    job = Job.new(engine="titanarum", filename="test.pdf")
    store.create(job)  # QUEUED
    resp = client.get(f"/v1/jobs/{job.job_id}/report")
    assert resp.status_code == 409


def test_report_404_for_unknown_job(tmp_path):
    client, _ = _make_client(tmp_path)
    resp = client.get("/v1/jobs/00000000-0000-0000-0000-000000000000/report")
    assert resp.status_code == 404


def test_spa_deeplink_serves_index_when_ui_packaged():
    client, _ = _make_client_static()
    resp = client.get("/jobs/00000000-0000-0000-0000-000000000000")
    if (_STATIC_DIR / "index.html").is_file():
        assert resp.status_code == 200
        assert resp.headers["content-type"].startswith("text/html")
    else:  # pragma: no cover - depends on packaging
        assert resp.status_code == 404


def _make_client_static() -> tuple[TestClient, InMemoryJobStore]:
    store = InMemoryJobStore()
    app = build_app(
        job_store=store,
        job_root=Path("/tmp/titanarum-ingress-test-jobs"),
        allowed_engines={"titanarum"},
        extension=make_extension(),
    )
    return TestClient(app, raise_server_exceptions=False), store


def test_make_extension_wires_report_route():
    ext = make_extension()
    assert len(ext.routers) == 1
    paths = {r.path for router in ext.routers for r in router.routes}
    assert "/v1/jobs/{job_id}/report" in paths
    assert "/jobs/{job_id}" in paths


def test_make_extension_returns_static_ui_when_serve_ui_on_and_index_exists():
    ext = make_extension()
    if (_STATIC_DIR / "index.html").is_file():
        assert ext.static_ui is not None
    else:  # pragma: no cover - depends on packaging
        assert ext.static_ui is None


def test_serve_ui_toggle(monkeypatch):
    monkeypatch.setenv("TITANARUM_SERVE_UI", "0")
    assert make_extension().static_ui is None
    monkeypatch.setenv("TITANARUM_SERVE_UI", "1")
    ext = make_extension()
    if (_STATIC_DIR / "index.html").is_file():
        assert ext.static_ui is not None
    else:  # pragma: no cover - depends on packaging
        assert ext.static_ui is None


def test_module_imports():
    import titanarum.blastbox_ingress as m

    assert hasattr(m, "make_extension")
    assert hasattr(m, "router")
