"""titanarum CLI (lean: version + selftest).

The operator runs `blastbox serve` / `blastbox dispatch` directly; this CLI keeps
only dependency-light, in-process commands. The engine adapter
(`titanarum.engine:TitanArumEngine`) is what blastbox's workers load.
"""
from __future__ import annotations

import sys

import click

from titanarum._version import __version__


@click.group()
def cli() -> None:
    """titanarum — pdf-titan-arum PDF forensic engine for blastbox."""


@cli.command()
def version() -> None:
    """Print version and exit."""
    click.echo(f"titanarum {__version__}")


@cli.command()
def selftest() -> None:
    """Import the engine adapter and resolve blastbox limits."""
    try:
        from blastbox.limits import Limits

        from titanarum.engine import TitanArumEngine

        limits = Limits.from_env()
        engine = TitanArumEngine()
        click.echo(
            f"Engine OK: name={engine.name!r} formats={sorted(engine.formats)} "
            f"timeout_s={limits.timeout_s}"
        )
        click.echo("Self-test passed.")
    except Exception as e:  # noqa: BLE001 - selftest surfaces any failure
        click.echo(f"Self-test FAILED: {e}", err=True)
        sys.exit(1)
