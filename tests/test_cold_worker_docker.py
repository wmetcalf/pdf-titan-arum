"""Cold-worker image smoke test. Marked 'docker' (deselect with -m 'not docker')."""
import shutil
import subprocess

import pytest

pytestmark = pytest.mark.docker


def _docker() -> str:
    exe = shutil.which("docker")
    if not exe:
        pytest.skip("docker not available")
    return exe


def test_image_imports_engine_and_cold():
    docker = _docker()
    # Build base + overlay (fast if cached).
    subprocess.run([docker, "build", "-f", "deploy/docker/Dockerfile.titanarum-base",
                    "-t", "pdf-titan-arum-base:dev", "."], check=True)
    subprocess.run([docker, "build", "-f",
                    "deploy/docker/Dockerfile.titanarum-cold-worker",
                    "-t", "titanarum-cold-worker:dev", "."], check=True)
    # The image's python must import both modules (baked check already runs at build,
    # this re-verifies the runtime entrypoint interpreter).
    out = subprocess.run(
        [docker, "run", "--rm", "--entrypoint", "/opt/titanarum/bin/python",
         "titanarum-cold-worker:dev", "-c",
         "import blastbox.worker.cold, titanarum.engine; print('ok')"],
        capture_output=True, text=True, check=True)
    assert "ok" in out.stdout
