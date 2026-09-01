"""The viewer renders URLs pulled out of analyzed (hostile) documents.

``esc()`` neutralises HTML metacharacters, but a URL scheme needs none of them:
before ``safeUrl()`` existed, ``javascript:alert(1)`` travelled through ``esc()``
untouched and was emitted as a live ``href``, so a link in a malicious PDF ran
script in the analyst's browser on click.

This drives the REAL functions out of ``app.js`` under node rather than
re-implementing them, so the test tracks the shipped code.
"""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest

APP_JS = Path(__file__).resolve().parents[1] / "titanarum" / "static" / "assets" / "app.js"

# Schemes a browser will execute, plus the parser tricks that hide them: case,
# leading whitespace, embedded control characters (browsers strip these before
# resolving the scheme, so a naive prefix check misses them).
HOSTILE = [
    "javascript:alert(document.domain)",
    "JaVaScRiPt:alert(1)",
    "java\tscript:alert(1)",
    "  javascript:alert(1)",
    "\0javascript:alert(1)",
    "data:text/html,<script>alert(1)</script>",
    "vbscript:msgbox(1)",
    "file:///etc/passwd",
]
BENIGN = ["https://evil.example/a?b=1", "http://x.test", "mailto:a@b.c", "#", "/rel/path"]


def _extract(src: str, *names: str) -> str:
    """Slice named top-level declarations out of app.js by brace matching."""
    out = []
    for name in names:
        i = src.index(name)
        if name.startswith("const"):
            out.append(src[i : src.index("\n", i) + 1])
            continue
        depth = 0
        for k in range(src.index("{", i), len(src)):
            if src[k] == "{":
                depth += 1
            elif src[k] == "}":
                depth -= 1
                if depth == 0:
                    out.append(src[i : k + 1])
                    break
    return "\n".join(out)


def _render(urls: list[str]) -> list[str]:
    node = shutil.which("node")
    if node is None:  # pragma: no cover - depends on the environment
        pytest.skip("node is required to exercise app.js")
    harness = _extract(
        APP_JS.read_text(encoding="utf-8"),
        "function esc(",
        "const SAFE_URL_SCHEME",
        "function safeUrl(",
        "function extLink(",
    )
    harness += (
        f"\nconsole.log(JSON.stringify({json.dumps(urls)}"
        ".map(function (u) { return extLink(u); })));\n"
    )
    proc = subprocess.run([node, "-e", harness], capture_output=True, text=True, timeout=60)
    assert proc.returncode == 0, proc.stderr
    return json.loads(proc.stdout.strip().splitlines()[-1])


@pytest.mark.parametrize("url", HOSTILE)
def test_executable_scheme_never_becomes_a_link(url: str) -> None:
    html = _render([url])[0]
    assert "<a " not in html, f"{url!r} was emitted as a live link: {html}"
    assert "href" not in html, f"{url!r} produced an href: {html}"


@pytest.mark.parametrize("url", BENIGN)
def test_ordinary_urls_still_link(url: str) -> None:
    html = _render([url])[0]
    assert "<a " in html, f"{url!r} lost its link: {html}"


def test_blocked_url_is_still_shown_as_text() -> None:
    """An analyst must never lose sight of the IOC just because it is unsafe."""
    html = _render(["javascript:alert(1)"])[0]
    assert "javascript:alert(1)" in html
