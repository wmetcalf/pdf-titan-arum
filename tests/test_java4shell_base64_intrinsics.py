"""CVE-2026-114938 ("Java4Shell") mitigation: every worker JVM runs with the HotSpot Base64
intrinsic OFF (``-XX:-UseBASE64Intrinsics``).

The bug is a heap overflow in the AVX-512 VBMI Base64 *decode intrinsic* (JDK 17.0.6+ .. 27).
Hostile PDFs reach ``java.util.Base64`` in the worker (AcroForm ``/V`` Name payloads -- the
CVE-2026-34621 technique the worker decodes and saves), so a JVM that boots without the flag
can reach the stub on a VBMI-capable host once C2 compiles the decoder hot. These tests fail if
a launch path loses the flag, and prove Base64 content still decodes end-to-end with it set.

The end-to-end test is a FUNCTIONAL check (decoding still works with the flag), not an exploit
probe: the worker bundle is C1-only (-XX:TieredStopAtLevel=1, which never intrinsifies the
decoder) and one decode runs interpreted. The flag itself is guarded by the argv / env /
PrintFlagsFinal tests.
"""
from __future__ import annotations

import base64
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

from titanarum import engine as eng

FLAG = "-XX:-UseBASE64Intrinsics"
REPO = Path(__file__).resolve().parents[1]
JAR = REPO / "target" / "pdf-titan-arum-1.3.0.jar"


def _clear_env(monkeypatch: pytest.MonkeyPatch) -> None:
    for k in ("TITANARUM_JAVA_OPTS", "TITANARUM_JAVA_BIN", "TITANARUM_AOT_CACHE"):
        monkeypatch.delenv(k, raising=False)


# --- engine argv (cold + warm FC/gVisor all launch through _java_worker_argv) ----------------


def test_default_bundle_disables_base64_intrinsics(monkeypatch):
    _clear_env(monkeypatch)
    assert FLAG in eng._DEFAULT_JVM_FLAGS  # build-aot.sh bakes the AOT cache from this tuple
    argv = eng._java_worker_argv(Path("/scr"))
    assert FLAG in argv[: argv.index("-jar")]


def test_java_opts_override_cannot_reenable_the_intrinsic(monkeypatch):
    _clear_env(monkeypatch)
    monkeypatch.setenv("TITANARUM_JAVA_OPTS", "-Xmx1g -XX:+UseBASE64Intrinsics")
    argv = eng._java_worker_argv(Path("/scr"))
    jvm = argv[1 : argv.index("-jar")]
    assert [f for f in jvm if "UseBASE64Intrinsics" in f] == [FLAG]
    assert jvm == ["-Xmx1g", FLAG]


def test_build_aot_reads_the_flag_bundle_from_engine():
    # The AOT bake must boot with the same flags; build-aot.sh guarantees that by importing
    # _DEFAULT_JVM_FLAGS rather than hand-copying them. Keep that wiring.
    text = (REPO / "deploy/docker/build-aot.sh").read_text()
    assert "from titanarum.engine import _DEFAULT_JVM_FLAGS" in text


# --- static launch-path inventory -----------------------------------------------------------


@pytest.mark.parametrize(
    "dockerfile",
    ["deploy/docker/Dockerfile.titanarum-base", "deploy/aws/Dockerfile.titanarum-http-agent",
     "Dockerfile"],
)
def test_runtime_images_set_java_tool_options(dockerfile):
    text = (REPO / dockerfile).read_text()
    assert re.search(r'^ENV JAVA_TOOL_OPTIONS="[^"]*' + re.escape(FLAG), text, re.M), dockerfile


def test_cli_entrypoint_disables_the_intrinsic():
    text = (REPO / "deploy/docker/Dockerfile.titanarum-cli").read_text()
    ep = next(ln for ln in text.splitlines() if ln.startswith("ENTRYPOINT"))
    assert f'"{FLAG}"' in ep


def test_server_entrypoint_disables_the_intrinsic():
    text = (REPO / "docker-entrypoint.sh").read_text()
    launches = [ln for ln in text.splitlines() if re.match(r"\s*exec java\b", ln)]
    assert launches and all(FLAG in ln for ln in launches)


def test_fc_guest_env_sets_java_tool_options():
    # docker export drops image ENV; the guest env file is what the FC guest actually gets.
    text = (REPO / "deploy/firecracker/guest.titanarum.env").read_text()
    assert re.search(r"^export JAVA_TOOL_OPTIONS=\S*" + re.escape(FLAG), text, re.M)


# --- live JVM ----------------------------------------------------------------------------------


@pytest.mark.skipif(shutil.which("java") is None, reason="no java on PATH")
def test_live_jvm_reports_the_intrinsic_disabled(monkeypatch):
    _clear_env(monkeypatch)
    monkeypatch.setenv("TITANARUM_AOT_CACHE", "")
    argv = eng._java_worker_argv(Path("/scr"))
    jvm_flags = [f for f in argv[1 : argv.index("-jar")] if not f.startswith(("-Xms", "-Xmx"))]
    r = subprocess.run(["java", *jvm_flags, "-XX:+PrintFlagsFinal", "-version"],
                       capture_output=True, text=True, timeout=60, check=False)
    assert r.returncode == 0, r.stderr[-2000:]
    line = next(ln for ln in r.stdout.splitlines() if " UseBASE64Intrinsics " in ln)
    assert re.search(r"UseBASE64Intrinsics\s+=\s+false", line), line


def _pdf_with_base64_name_field(b64: str) -> bytes:
    # PDF Name syntax: '/' and '+' (and '#') must be #XX-escaped; '=' is a regular char.
    name = b64.replace("#", "#23").replace("/", "#2F").replace("+", "#2B")
    objs = [
        b"<</Type/Catalog/Pages 2 0 R/AcroForm<</Fields[4 0 R]>>>>",
        b"<</Type/Pages/Kids[3 0 R]/Count 1>>",
        b"<</Type/Page/Parent 2 0 R/MediaBox[0 0 612 792]/Annots[4 0 R]>>",
        b"<</Type/Annot/Subtype/Widget/FT/Tx/T(payload)/Rect[0 0 0 0]/P 3 0 R/V/"
        + name.encode("ascii") + b">>",
    ]
    out = bytearray(b"%PDF-1.7\n")
    offsets = []
    for i, body in enumerate(objs, start=1):
        offsets.append(len(out))
        out += b"%d 0 obj\n" % i + body + b"\nendobj\n"
    xref = len(out)
    out += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
    for off in offsets:
        out += b"%010d 00000 n \n" % off
    out += b"trailer<</Size %d/Root 1 0 R>>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, xref)
    return bytes(out)


@pytest.mark.jvm
def test_base64_form_payload_still_decodes_with_intrinsic_off(tmp_path, monkeypatch):
    if not shutil.which("java"):
        pytest.skip("no java on PATH")
    if not JAR.is_file():
        subprocess.run(["mvn", "-q", "-DskipTests", "package"], cwd=REPO, check=True)
    from blastbox.limits import Limits

    from titanarum.engine import TitanArumEngine

    _clear_env(monkeypatch)
    monkeypatch.setenv("TITANARUM_WORKER_JAR", str(JAR))
    monkeypatch.setenv("TITANARUM_SKIP_SCREENSHOTS", "1")
    monkeypatch.setenv("TITANARUM_SKIP_IMAGES", "1")
    assert FLAG in eng._java_worker_argv(tmp_path)

    # A few KiB of script text: the decode path the CVE lives on, exercised with the flag set.
    # Pad to a multiple of 3 so the base64 carries no '=' padding noise.
    payload = ("".join(f"app.alert('java4shell-canary-{i:04d}');\n" for i in range(150))).encode()
    payload += b" " * (-len(payload) % 3)
    b64 = base64.b64encode(payload).decode("ascii")
    assert "/" in b64 or "+" in b64 or len(b64) > 4096

    pdf = tmp_path / "in.pdf"
    pdf.write_bytes(_pdf_with_base64_name_field(b64))
    outdir = tmp_path / "out"
    outdir.mkdir()
    result = TitanArumEngine().detonate(pdf, outdir, Limits.from_env())
    assert result.status == "ok"

    report = (outdir / "titan" / "report.json").read_text()
    hits = [h for h in json.loads(report).get("formFields", [])
            if "base64_payload" in (h.get("flags") or [])]
    assert hits, f"no base64_payload form field in report: {report[:1500]}"
    assert hits[0]["decodedLength"] == len(payload)
    assert hits[0]["decodedSha256"] == hashlib.sha256(payload).hexdigest()


def test_readme_direct_jar_launches_disable_the_intrinsic():
    # A host `java -jar` launch gets neither the image's JAVA_TOOL_OPTIONS nor an entrypoint
    # flag, so every documented direct launch must carry the flag itself.
    text = (REPO / "README.md").read_text()
    launches = [ln for ln in text.splitlines() if re.match(r"\s*java\b.*-jar\b", ln)]
    assert launches
    assert all(FLAG in ln for ln in launches), [ln for ln in launches if FLAG not in ln]


def test_underscore_java_options_cannot_reenable_the_intrinsic(monkeypatch):
    # HotSpot applies _JAVA_OPTIONS AFTER the command line, so a +UseBASE64Intrinsics there beat
    # the argv flag. The worker env re-asserts the OFF flag last inside it.
    monkeypatch.setenv("_JAVA_OPTIONS", "-Xss2m -XX:+UseBASE64Intrinsics")
    env = eng._jvm_env()
    assert env["_JAVA_OPTIONS"].split()[-1] == FLAG
    assert "-Xss2m" in env["_JAVA_OPTIONS"].split()
    monkeypatch.delenv("_JAVA_OPTIONS")
    assert "_JAVA_OPTIONS" not in eng._jvm_env()  # absent stays absent


@pytest.mark.skipif(shutil.which("java") is None, reason="no java on PATH")
def test_live_jvm_underscore_java_options_override_is_neutralised(monkeypatch):
    monkeypatch.setenv("_JAVA_OPTIONS", "-XX:+UseBASE64Intrinsics")
    r = subprocess.run(["java", FLAG, "-XX:+PrintFlagsFinal", "-version"], capture_output=True,
                       text=True, timeout=60, check=False, env=eng._jvm_env())
    line = next(ln for ln in r.stdout.splitlines() if " UseBASE64Intrinsics " in ln)
    assert re.search(r"UseBASE64Intrinsics\s+=\s+false", line), line
