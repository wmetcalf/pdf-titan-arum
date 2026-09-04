"""What blastbox-images.toml declares must match the Dockerfiles it names.

These used to assert the same things about scripts/build_images.sh. The bash is
gone -- `blastbox build-images` executes the declaration now -- but the failure
modes did not go anywhere: docker silently ignores a --build-arg the Dockerfile
does not declare, so a wrong `base_arg` pins nothing while the stamp claims a
digest the build never used.

The generic checks live in blastbox and are tested there. What is REPO business
is that this repo's declaration matches this repo's Dockerfiles.
"""

from __future__ import annotations

import re
from pathlib import Path
from typing import TYPE_CHECKING

import pytest

ROOT = Path(__file__).resolve().parents[1]

if TYPE_CHECKING:  # the real type, so mypy checks the attribute access below
    from blastbox.host.images import ImageSpec

# importorskip at RUNTIME: these tests must still collect where blastbox is not
# installed. The TYPE_CHECKING import is erased at runtime, so it cannot
# reintroduce the hard dependency importorskip exists to avoid.
images = pytest.importorskip("blastbox.host.images")

PLAN = images.load_plan(ROOT)
ALL = list(PLAN.images)


def test_the_declaration_names_every_tier() -> None:
    """A tier missing from the spec is a tier nothing rebuilds -- the fleet then
    runs two versions while every tag says one."""
    names = {i.name for i in PLAN.images}
    assert {"titanarum-base", "titanarum-cold-worker", "titanarum"} <= names
    assert {"titanarum-warm-gvisor", "titanarum-fc-worker"} <= names


def test_every_image_is_built_from_this_repo() -> None:
    """Unlike redtusk, THIS engine's warm Dockerfiles live here.

    That distinction is the 2026-09-02 outage: the Firecracker rootfs was built
    from inputs looked for in blastbox's tree instead of this one, the guest had
    no /init, and every warm guest hung to the boot timeout. Pinned so nobody
    "fixes" it by copying redtusk's spec.
    """
    foreign = [i.name for i in PLAN.images if i.context != "."]
    assert not foreign, f"built from another tree: {foreign}"


@pytest.mark.parametrize("spec", ALL, ids=lambda s: str(s.name))
def test_each_declared_base_arg_selects_that_dockerfiles_base(spec: ImageSpec) -> None:
    """docker discards a --build-arg the Dockerfile does not declare, so the
    build resolves its own default while the stamp claims the pinned base."""
    from blastbox.host.stamp import StampError, assert_arg_selects_base

    path = ROOT / spec.dockerfile
    assert path.is_file(), f"the plan names {spec.dockerfile}, which does not exist"
    try:
        assert_arg_selects_base(path, spec.base_arg)
    except StampError as exc:
        pytest.fail(f"blastbox-images.toml declares base_arg={spec.base_arg}: {exc}")


@pytest.mark.parametrize("spec", ALL, ids=lambda s: str(s.name))
def test_a_declared_upstream_base_matches_the_dockerfiles_own_default(
    spec: ImageSpec,
) -> None:
    """The plan pins these; the Dockerfile defaults them. They must agree, or a
    plain `docker build` and a planned build produce images on different bases
    while both look correct."""
    if spec.internal:  # a chain base has no upstream default to agree with
        pytest.skip(f"{spec.name} builds on {spec.base}, which this plan builds")
    declared = re.search(
        rf"^\s*ARG\s+{re.escape(spec.base_arg)}=(\S+)",
        (ROOT / spec.dockerfile).read_text(encoding="utf-8"),
        re.MULTILINE,
    )
    assert declared, f"{spec.dockerfile} no longer defaults ARG {spec.base_arg}"
    assert spec.base == declared.group(1), (
        f"the plan pins {spec.name} to {spec.base!r} but {spec.dockerfile} "
        f"defaults ARG {spec.base_arg}={declared.group(1)!r}"
    )


def test_the_builder_stages_are_declared() -> None:
    """titanarum-base COPIES artifacts out of both. Undeclared, they are mutable
    tags nothing pulls, resolves or records, so an upstream push changes what
    lands in the image while every label stays identical."""
    base = next(i for i in PLAN.images if i.name == "titanarum-base")
    assert {"JDK_BUILD_IMAGE", "ZXING_BUILD_IMAGE"} <= set(base.build_args)


def test_the_firecracker_rootfs_declares_what_it_must_contain() -> None:
    """The guest boots /init, which execs run_guest.py against a baked
    guest.env. A rootfs without them hangs every warm guest until the boot
    timeout -- which is exactly what happened, because nothing had written down
    what the artifact needed."""
    fc = [r for r in PLAN.rootfs if r.kind == "ext4"]
    assert len(fc) == 1
    assert {"/init", "/opt/blastbox/guest.env"} <= set(fc[0].requires)


def test_both_rootfs_artifacts_are_declared() -> None:
    assert {r.kind for r in PLAN.rootfs} == {"dir", "ext4"}


def test_the_floor_matches_what_pyproject_pins() -> None:
    """The wrapper's gate and the package's own floor must agree. Lower, and the
    script accepts a blastbox the package refuses to install alongside."""
    text = (ROOT / "scripts" / "build_images.sh").read_text(encoding="utf-8")
    floor = re.search(r"^BB_MIN=(\S+)", text, re.MULTILINE)
    assert floor, "build_images.sh no longer states a minimum the way this test reads it"
    pins = re.findall(
        r"blastbox(?:\[[^\]]*\])?>=(\d+\.\d+\.\d+)",
        (ROOT / "pyproject.toml").read_text(encoding="utf-8"),
    )
    assert pins, "pyproject no longer pins blastbox the way this test reads it"
    assert set(pins) == {floor.group(1)}, (
        f"build_images.sh requires >= {floor.group(1)} but pyproject pins {sorted(set(pins))}"
    )
