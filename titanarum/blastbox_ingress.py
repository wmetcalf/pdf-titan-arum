"""titanarum product routes for the shared blastbox ingress.

Mounts titanarum's rich results web UI (job list + per-job detail: screenshots,
extracted tables, URLs, phone numbers, document metadata, AI verdict) on top of
the generic blastbox ingress (submit/status/list/artifacts/result/auth/health/
metrics) via the ``IngressExtension`` seam, resolved by
``BLASTBOX_INGRESS_EXTENSION=titanarum.blastbox_ingress:make_extension``.

Clone of RedTusk's ``redtusk.blastbox_ingress`` (see that module for the
seam's rationale). The one titanarum-specific data route the generic core
can't already serve by a stable name is the **report document**
(``titan/report.json`` — the JVM worker's full forensic report: urls,
javascript, phone numbers, tables, screenshots, document metadata, AI
analysis, etc., kept in sync with ``schema.py`` / ``engine.py``'s
``report_dir = outdir / "titan"``). titanarum's engine (``_enumerate_artifacts``)
DOES declare it as a ``DeclaredArtifact`` (``kind="report"``) — unlike
RedTusk's rmeta, which is deliberately undeclared — so it's already reachable
via the generic ``/v1/jobs/{id}/artifacts/{artifact_id}`` route once the
caller knows its id. This route serves it by the FIXED relative path instead,
so the front-end never has to resolve the id through ``metadata.json`` first.
It goes through ``request.app.state.serve_artifact_file`` — the core helper
that owns the DONE-gate, ``resolve()+relative_to()`` containment, and
no-symlink-follow. This router adds NO security logic of its own and inherits
the app's auth middleware.

Everything else (job lifecycle, per-artifact fetch by id, the encrypted
``/v1/jobs/{id}/result`` zip) is already provided by the generic core.
"""

from __future__ import annotations

import os
from pathlib import Path as _FsPath

from blastbox.host.ingress.extension import IngressExtension, StaticUI
from fastapi import APIRouter, HTTPException, Request
from fastapi.responses import FileResponse

router = APIRouter()

# titanarum's packaged web UI (served at GET / + /assets via the StaticUI seam).
_STATIC_DIR = _FsPath(__file__).resolve().parent / "static"

# Relative path (under the job output dir) of the report document the engine's
# TitanArumEngine.detonate writes — kept in sync with engine.py's
# ``report_dir = outdir / "titan"`` and _enumerate_artifacts's report.json -> kind="report".
_REPORT_REL = "titan/report.json"


@router.get("/v1/jobs/{job_id}/report")
def get_report(job_id: str, request: Request) -> FileResponse:
    """Stream the canonical titanarum report document (report.json)."""
    # app.state.serve_artifact_file is untyped (Any); launder through a typed
    # local so strict mypy keeps the FileResponse return type.
    resp: FileResponse = request.app.state.serve_artifact_file(
        job_id,
        _REPORT_REL,
        media_type="application/json",
        filename=f"{job_id}.report.json",
    )
    return resp


@router.get("/jobs/{job_id}")
def spa_deeplink(job_id: str) -> FileResponse:
    """Serve the SPA shell for client-routed deep-links (``/jobs/<id>``) so a hard
    refresh / bookmark / share of a job-detail URL works — the front-end then routes
    on ``window.location.pathname``. Distinct from ``/v1/jobs/{id}`` (the JSON API);
    the StaticUI seam only registers ``GET /`` + ``/assets``, so without this a
    refresh on a detail URL 404s. 404 when the UI isn't packaged (TITANARUM_SERVE_UI=0)."""
    index = _STATIC_DIR / "index.html"
    if not index.is_file():
        raise HTTPException(status_code=404)
    return FileResponse(str(index), media_type="text/html")


def make_extension() -> IngressExtension:
    """Factory resolved by ``BLASTBOX_INGRESS_EXTENSION``.

    Returns an :class:`IngressExtension` carrying titanarum's report route and
    (unless ``TITANARUM_SERVE_UI=0``) its packaged web UI, mounted on the shared
    blastbox ingress by ``build_app``.
    """
    serve_ui = os.environ.get("TITANARUM_SERVE_UI", "1").strip().lower() not in {
        "0",
        "false",
        "no",
    }
    static_ui = (
        StaticUI(directory=str(_STATIC_DIR))
        if serve_ui and (_STATIC_DIR / "index.html").is_file()
        else None
    )
    return IngressExtension(routers=(router,), static_ui=static_ui)
