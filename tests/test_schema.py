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


def test_timed_out_partial_without_pagesspec_is_accepted():
    # A hard-halt TIMEOUT can flush a partial report before pagesSpec is assigned (the JVM writes
    # pagesSpec only after a successful parse). Such a timedOut=true partial must still validate so
    # the host can return it, not fail closed -- the same exemption parse-error reports get.
    report = _load()
    report.pop("pagesSpec", None)
    report.pop("parseError", None)
    report["timedOut"] = True
    validate_report(report)  # must not raise


def test_normal_report_without_pagesspec_still_rejected():
    # The timeout exemption must NOT weaken the gate for an ordinary successful report.
    report = _load()
    report.pop("pagesSpec", None)
    report.pop("parseError", None)
    report.pop("timedOut", None)
    with pytest.raises(SchemaValidationError):
        validate_report(report)


def test_timed_out_false_without_pagesspec_still_rejected():
    # timedOut present-but-false is an ordinary report: pagesSpec is still required.
    report = _load()
    report.pop("pagesSpec", None)
    report.pop("parseError", None)
    report["timedOut"] = False
    with pytest.raises(SchemaValidationError):
        validate_report(report)


def test_tables_key_accepted():
    report = _load()
    report["tables"] = [{
        "page": 1, "extractionMethod": "lattice",
        "bbox": [50.0, 100.0, 350.0, 190.0],
        "rowCount": 2, "colCount": 2,
        "rows": [["A", "B"], ["C", "D"]],
        "cells": [{"row": 0, "col": 0, "rowSpan": 1, "colSpan": 1, "text": "A"}],
        "markdown": "| A | B |\n|---|---|\n| C | D |",
    }]
    report["tablesTruncated"] = True
    validate_report(report)  # must not raise
