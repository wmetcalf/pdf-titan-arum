"""Every TitanArumEngine.detonate() a test makes is checked against blastbox-engine.toml.

The checker (blastbox.contract.engine_contract) ships on an unreleased blastbox branch; on a
released blastbox the hook is a no-op and tests/test_engine_contract.py skips.
"""
from __future__ import annotations

from pathlib import Path

import pytest

try:
    from blastbox.contract.engine_contract import assert_conforms, find_contract, load_contract
except ImportError:  # released blastbox: no checker
    CONTRACT = None
else:
    CONTRACT = load_contract(find_contract(Path(__file__)))


@pytest.fixture(autouse=True)
def _detonate_conforms_to_contract(monkeypatch):
    if CONTRACT is None:
        yield
        return
    from titanarum.engine import TitanArumEngine

    real = TitanArumEngine.detonate

    def checked(self, input, outdir, limits):  # noqa: A002 - mirrors the engine signature
        result = real(self, input, outdir, limits)
        assert_conforms(CONTRACT, result, outdir)
        return result

    monkeypatch.setattr(TitanArumEngine, "detonate", checked)
    yield
