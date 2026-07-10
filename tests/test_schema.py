import json
from pathlib import Path

import pytest

from titanarum.schema import SchemaValidationError, validate_report

FIX = Path(__file__).parent / "fixtures" / "report.min.json"


def _load() -> dict:
    return json.loads(FIX.read_text())


def test_valid_report_passes():
    validate_report(_load())  # must not raise


def test_missing_required_key_rejected():
    report = _load()
    del report["documentSha256"]
    with pytest.raises(SchemaValidationError):
        validate_report(report)


def test_bad_sha256_rejected():
    report = _load()
    report["documentSha256"] = "NOT-A-HASH"
    with pytest.raises(SchemaValidationError):
        validate_report(report)


def test_ai_analysis_is_open_object():
    report = _load()
    report["aiAnalysis"] = {"threatLevel": "clean", "vendorSpecificKey": 1}
    validate_report(report)  # aiAnalysis allows additionalProperties


def test_unexpected_top_level_key_rejected():
    report = _load()
    report["unexpectedKey"] = 1
    with pytest.raises(SchemaValidationError):
        validate_report(report)
