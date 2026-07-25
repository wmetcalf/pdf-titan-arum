"""Every shipped stack must FORWARD the table knobs.

blastbox's dispatcher applies ``BLASTBOX_ENGINE_TITANARUM_PARAM_KEYS`` as a default-DENY
allowlist (blastbox.host.dispatch.Dispatcher._sanitize_params): a job param whose key is not
in the set is dropped and the job runs anyway -- the drop is a dispatcher-log warning, never a
job error and never anything the caller sees. So a stack that forgets ``TITANARUM_STREAM_TABLES``
does not fail; it silently ignores the operator's request to enable borderless extraction, and
the only symptom is a report with fewer tables than expected.

These tests are file-driven (globbed, not enumerated) so a NEW compose overlay that configures
the titanarum engine cannot ship without the same allowlist.
"""
import re
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]

# The two knobs this test exists for. Both are opt-IN/opt-OUT extraction toggles a caller has to
# be able to set per job; neither is a code-exec vector (those live in RESERVED_KEYS).
TABLE_KEYS = ("TITANARUM_STREAM_TABLES", "TITANARUM_SKIP_TABLES")

# `KEY=A,B,C` or the compose idiom `KEY=${KEY:-A,B,C}` -- capture the effective key list.
_ALLOWLIST_RE = re.compile(
    r"BLASTBOX_ENGINE_TITANARUM_PARAM_KEYS="
    r"(?:\$\{BLASTBOX_ENGINE_TITANARUM_PARAM_KEYS:-)?([A-Z0-9_,]*)\}?"
)
_RESERVED_RE = re.compile(
    r"BLASTBOX_ENGINE_TITANARUM_RESERVED_KEYS="
    r"(?:\$\{BLASTBOX_ENGINE_TITANARUM_RESERVED_KEYS:-)?([A-Z0-9_,]*)\}?"
)


def _stack_files() -> list[Path]:
    """Every shipped compose file plus the operator .env template."""
    files = sorted(REPO.glob("deploy/**/docker-compose*.yml"))
    files += sorted(REPO.glob("docker-compose*.yml"))
    env_example = REPO / "deploy" / "docker" / ".env.example"
    if env_example.is_file():
        files.append(env_example)
    return files


def _active_lines(path: Path) -> list[str]:
    """Non-comment lines (a commented-out allowlist configures nothing)."""
    out = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("#"):
            continue
        out.append(line.lstrip("- ").strip() if line.startswith("- ") else line)
    return out


def test_stack_files_are_actually_found():
    # Guard against the glob silently matching nothing (which would make every test below pass
    # vacuously) and against a stack being renamed out of the audit.
    names = {p.name for p in _stack_files()}
    assert {"docker-compose.yml", "docker-compose.gvisor.yml",
            "docker-compose.firecracker.yml", ".env.example"} <= names, names


def test_every_titanarum_engine_service_sets_the_param_allowlist():
    for path in _stack_files():
        lines = _active_lines(path)
        engines = [ln for ln in lines if "BLASTBOX_ENGINES=titanarum=" in ln]
        allowlists = [ln for ln in lines if "BLASTBOX_ENGINE_TITANARUM_PARAM_KEYS=" in ln]
        if not engines:
            continue
        assert len(allowlists) >= len(engines), (
            f"{path.relative_to(REPO)} declares the titanarum engine {len(engines)}x but sets "
            f"BLASTBOX_ENGINE_TITANARUM_PARAM_KEYS {len(allowlists)}x -- a service with no "
            f"allowlist runs the legacy shape+denylist gate, a different security posture"
        )


def test_every_param_allowlist_forwards_the_table_knobs():
    seen = 0
    for path in _stack_files():
        for line in _active_lines(path):
            m = _ALLOWLIST_RE.search(line)
            if not m:
                continue
            seen += 1
            keys = {k for k in m.group(1).split(",") if k}
            for want in TABLE_KEYS:
                assert want in keys, (
                    f"{path.relative_to(REPO)}: {want} missing from "
                    f"BLASTBOX_ENGINE_TITANARUM_PARAM_KEYS ({sorted(keys)}) -- the dispatcher "
                    f"would drop it and the job would run with the feature off, silently"
                )
    assert seen >= 5, f"expected an allowlist in every stack service, found {seen}"


def test_no_allowlist_entry_is_a_typo_engine_py_never_reads():
    # A misspelled key can never match a client param, so it is a silent no-op: the operator
    # believes the knob is forwardable and it is not. Every entry must be an env var
    # titanarum/engine.py actually looks at.
    engine_src = (REPO / "titanarum" / "engine.py").read_text(encoding="utf-8")
    known = set(re.findall(r"TITANARUM_[A-Z0-9_]+", engine_src))
    assert "TITANARUM_STREAM_TABLES" in known, "sanity: engine.py must read the env var at all"
    for path in _stack_files():
        for line in _active_lines(path):
            m = _ALLOWLIST_RE.search(line)
            if not m:
                continue
            for key in (k for k in m.group(1).split(",") if k):
                assert key in known, (
                    f"{path.relative_to(REPO)}: allowlisted {key} is read nowhere in "
                    f"titanarum/engine.py -- forwarding it does nothing"
                )


def test_table_knobs_are_not_also_reserved():
    # RESERVED_KEYS is dropped UNCONDITIONALLY, even when allowlisted. A knob in both lists is
    # allowlisted-but-dead, which is exactly the failure this whole file is about.
    for path in _stack_files():
        for line in _active_lines(path):
            m = _RESERVED_RE.search(line)
            if not m:
                continue
            reserved = {k for k in m.group(1).split(",") if k}
            for key in TABLE_KEYS:
                assert key not in reserved, (
                    f"{path.relative_to(REPO)}: {key} is in RESERVED_KEYS, which is dropped "
                    f"unconditionally -- it can never reach a worker"
                )
