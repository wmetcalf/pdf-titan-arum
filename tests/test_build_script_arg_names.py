"""`scripts/build_images.sh` must pin bases with ARGs docker will honour.

Docker WARNS and IGNORES a `--build-arg` the Dockerfile never declares, and
declaring one is not enough: an ARG inside a stage cannot parameterize a FROM,
and in a multi-stage build only the LAST stage becomes the image. Any of those
leaves the build resolving a mutable tag while the stamp claims a pinned base.

Two design points, both learned on RedTusk before this file existed:

* The (Dockerfile, base-arg) pairs are READ OUT OF THE SCRIPT rather than
  written down here. A hand-maintained table only catches a rename on the
  Dockerfile side; a typo in the script's own argument -- the failure this
  guards -- sails straight through it.
* The Dockerfile parsing is imported from blastbox, not reimplemented. A second
  copy drifts from the one that actually gates builds.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest
from blastbox.host.stamp import StampError, assert_arg_selects_base

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts" / "build_images.sh"
TEXT = SCRIPT.read_text(encoding="utf-8")

# Continuations joined the way the shell joins them, then each logical line
# matched whole -- a two-line call otherwise swallows the next line's first word.
LOGICAL = re.sub(r"\\\n[ \t]*", " ", TEXT)

_CALL = re.compile(
    r"^[ \t]*stamp_flags[ \t]+(?P<df>\"[^\"]*\"|\S+)[ \t]+(?P<base>\"[^\"]*\"|\S+)"
    r"(?:[ \t]+(?P<arg>[A-Za-z_]\w*))?(?:[ \t]+(?:\"[^\"]*\"|\S+))?[ \t]*$",
    re.MULTILINE,
)
CALLS = [
    (m.group("df").strip('"'), (m.group("arg") or "BASE_IMAGE"))
    for m in _CALL.finditer(LOGICAL)
]


def test_the_script_actually_stamps_something() -> None:
    """If the call shape changes, every check below passes vacuously."""
    assert CALLS, "no `stamp_flags <dockerfile> <base>` calls found in build_images.sh"


@pytest.mark.parametrize("dockerfile,base_arg", CALLS)
def test_each_arg_the_script_passes_selects_that_dockerfiles_base(
    dockerfile: str, base_arg: str
) -> None:
    path = ROOT / dockerfile
    assert path.is_file(), f"build_images.sh stamps {dockerfile}, which does not exist"
    try:
        assert_arg_selects_base(path, base_arg)
    except StampError as exc:
        pytest.fail(f"build_images.sh passes --base-arg {base_arg} for {dockerfile}: {exc}")


def test_every_docker_build_is_stamped() -> None:
    """An unstamped image is the whole problem; one must not sneak back in."""
    builds = [
        b.strip('"')
        for b in re.findall(r"^[ \t]*docker build -f (\"[^\"]*\"|\S+)", LOGICAL, re.MULTILINE)
    ]
    stamped = {df for df, _ in CALLS}
    assert builds, "no `docker build` lines found; this test asserts nothing"
    assert set(builds) <= stamped, f"built but never stamped: {sorted(set(builds) - stamped)}"


def test_a_refused_stamp_aborts_instead_of_building_unstamped() -> None:
    """`set -e` DISCARDS the status of a `$(...)` in a command's arguments.

    Left that way a refusing stamp lets the build run with no labels and no
    --build-arg, so the worker falls back to its Dockerfile default -- a mutable
    tag pointing at whatever stale base is on the box.
    """
    assert not re.search(r"docker build[^\n]*\$\(stamp_flags", TEXT)
    assert "read -r -a flags" in TEXT, "stamp output must be read into an array"
    body = re.search(r"^stamp_flags\(\) \{(.*?)^\}", TEXT, re.MULTILINE | re.DOTALL)
    assert body, "stamp_flags is no longer a function this test can read"
    assert re.search(r"exit\s+1", body.group(1)), "a refusing stamp must abort"


def test_the_script_verifies_what_it_stamped() -> None:
    assert re.search(r"^\s*blastbox stamp --read", TEXT, re.MULTILINE), (
        "build_images.sh must read every stamp back"
    )
    gate = re.search(r'\[ "\$rc" -eq 0 \][^\n]*\|\|\s*\{(.*?)^\}', TEXT, re.MULTILINE | re.DOTALL)
    assert gate, "the read-back results are no longer gated the way this test reads"
    assert re.search(r"exit\s+1", gate.group(1)), "a failed verification must fail the build"


@pytest.mark.parametrize(
    "var,dockerfile",
    [("WORKER_BASE", "deploy/docker/Dockerfile.titanarum-base"),
     ("HOST_BASE", "deploy/docker/Dockerfile.titanarum-host")],
)
def test_the_scripts_default_base_matches_the_dockerfiles_own(
    var: str, dockerfile: str
) -> None:
    """If they drift, a plain build and a stamped build differ while both look fine."""
    m = re.search(rf'^{var}="\$\{{{var}:-([^}}]+)\}}"', TEXT, re.MULTILINE)
    assert m, f"{var} is no longer set the way this test reads it"
    declared = re.search(
        r"^\s*ARG\s+BASE_IMAGE=(\S+)",
        (ROOT / dockerfile).read_text(encoding="utf-8"),
        re.MULTILINE,
    )
    assert declared, f"{dockerfile} no longer defaults ARG BASE_IMAGE"
    assert m.group(1) == declared.group(1), (
        f"build_images.sh defaults {var}={m.group(1)!r} but {dockerfile} defaults "
        f"ARG BASE_IMAGE={declared.group(1)!r}"
    )


def test_the_warm_rootfs_comes_from_its_own_stamped_image() -> None:
    """Each tier exports ITS OWN warm image, not the cold worker.

    Exporting the cold worker is not a shortcut: the Firecracker guest boots
    /init, which execs run_guest.py against a baked guest.env, and those are
    what deploy/firecracker/Dockerfile.titanarum adds. A bare cold-worker export
    boots and never signals READY -- measured on toolz2, the warm base timed out
    at 120s and every job fell back to cold while the dispatcher looked healthy.
    """
    export = (ROOT / "scripts" / "export_warm_rootfs.sh").read_text(encoding="utf-8")
    assert 'GV_IMAGE="titanarum-warm:gvisor-$TAG"' in export
    assert 'FC_IMAGE="titanarum-fc-worker:$TAG"' in export
    assert "titanarum-cold-worker" not in export, (
        "the rootfs must come from the warm images, not the cold worker"
    )
    assert "docker build" not in export, (
        "the export script must not BUILD; that would bypass the stamps"
    )


def test_the_fc_export_refuses_an_image_that_cannot_boot() -> None:
    """Belt and braces: even the right image is checked for /init."""
    export = (ROOT / "scripts" / "export_warm_rootfs.sh").read_text(encoding="utf-8")
    assert "-f /init" in export, "the FC export must check the image can boot"
    assert "exit 3" in export, "the check must abort rather than warn"
    assert export.index(">> gvisor rootfs") < export.index("-f /init"), (
        "the /init gate must not block the gVisor export, which needs no init"
    )


def test_no_build_arg_the_dockerfiles_ignore() -> None:
    """docker SILENTLY ignores an undeclared --build-arg.

    titanarum's Dockerfiles declare neither BLASTBOX_VERSION nor BLASTBOX_WHEEL
    (RedTusk's do), so passing either would look like it pinned the install
    while doing nothing -- the class of lie this script exists to prevent.
    """
    script = SCRIPT.read_text(encoding="utf-8")
    for arg in ("BLASTBOX_VERSION", "BLASTBOX_WHEEL"):
        declared = any(
            f"ARG {arg}" in (ROOT / "deploy" / "docker" / d).read_text(encoding="utf-8")
            for d in ("Dockerfile.titanarum-cold-worker", "Dockerfile.titanarum-host")
        )
        passed = f"--build-arg \"{arg}=" in script or f"{arg}=$" in script
        assert declared or not passed, (
            f"the script passes {arg} but no Dockerfile declares it"
        )
