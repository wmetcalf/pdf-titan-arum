"""Trust gate: validate the JVM worker's report.json before mapping/serving it.

A malicious PDF produces this report; we validate its SHAPE (strict Draft-2020-12)
before believing any field. Only `aiAnalysis` is an open object; every closed
record forbids additional properties.
"""
from __future__ import annotations

from typing import Any

from jsonschema import Draft202012Validator, FormatChecker


class SchemaValidationError(Exception):
    def __init__(self, path: str, reason: str) -> None:
        super().__init__(f"report.json invalid at {path}: {reason}")
        self.path = path
        self.reason = reason


# Reusable sub-schemas ------------------------------------------------------

_STR = {"type": "string"}
_INT = {"type": "integer"}
_NUM = {"type": "number"}
_BOOL = {"type": "boolean"}


def _arr(items: dict[str, Any]) -> dict[str, Any]:
    return {"type": "array", "items": items}


# Root schema. The ROOT object is closed (additionalProperties: false, see
# below) so an unexpected top-level key is rejected. Nested array-item
# schemas (urls, javascript, embeddedFiles, etc.) are intentionally
# permissive ({"type": "object"}) for now -- the mapper only reads the
# fields it knows about and ignores the rest.

_SCHEMA: dict[str, Any] = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "required": [
        "inputPdf", "outputDirectory", "generatedAt", "documentSha256",
        "pageCount", "blankPageCount", "pagesSpec", "dpi", "addLinkAnnotations",
        "revisionCount",
        "urls", "javascript", "launchActions", "actions", "xfaScripts",
        "embeddedFiles", "phoneNumbers", "emails", "pagePdfs", "screenshots",
        "renderedImages", "resourceImages", "pageTexts", "pageStats",
    ],
    "properties": {
        "inputPdf": _STR,
        "outputDirectory": _STR,
        "generatedAt": _STR,
        "documentSha256": {"type": "string", "pattern": "^[a-f0-9]{64}$"},
        "pdfObjectHash": {"type": "string", "pattern": "^[a-f0-9]{32}$"},
        "fileMagic": _STR,
        "parseError": _STR,
        "documentInfo": {"type": "object"},
        "pageCount": _INT,
        "blankPageCount": _INT,
        "blankPages": _arr(_INT),
        "blankRatio": _NUM,
        "pagesSpec": _STR,
        "pagesProcessed": _arr(_INT),
        "qrScanEnabled": _BOOL,
        "dpi": _NUM,
        "addLinkAnnotations": _BOOL,
        "syntheticLinksAdded": _INT,
        "modifiedPdf": _STR,
        "revisionCount": _INT,
        "fonts": _arr(_STR),
        "timedOut": _BOOL,
        "timedOutAfterMs": _INT,
        "urls": _arr({"type": "object"}),
        "javascript": _arr({"type": "object"}),
        "launchActions": _arr({"type": "object"}),
        "actions": _arr({"type": "object"}),
        "xfaScripts": _arr({"type": "object"}),
        "embeddedFiles": _arr({"type": "object"}),
        "phoneNumbers": _arr({"type": "object"}),
        "emails": _arr({"type": "object"}),
        "ocgLayers": _arr({"type": "object"}),
        "pagePdfs": _arr({"type": "object"}),
        "screenshots": _arr({"type": "object"}),
        "renderedImages": _arr({"type": "object"}),
        "resourceImages": _arr({"type": "object"}),
        "pageTexts": _arr({"type": "object"}),
        "pageStats": _arr({"type": "object"}),
        "revisions": _arr({"type": "object"}),
        "formFields": _arr({"type": "object"}),
        "streamLengthAnomalies": _arr({"type": "object"}),
        "structuralAnomalies": _arr({"type": "object"}),
        "metadataSpoofingIndicators": _arr({"type": "object"}),
        # Only aiAnalysis is a fully open object (free-form LLM reply).
        "aiAnalysis": {"type": "object", "additionalProperties": True},
    },
    # Reject unexpected TOP-LEVEL keys (defense-in-depth against a tampered report).
    "additionalProperties": False,
}

_VALIDATOR = Draft202012Validator(_SCHEMA, format_checker=FormatChecker())


def validate_report(report: dict) -> None:
    """Raise SchemaValidationError on the first schema violation."""
    if not isinstance(report, dict):
        raise SchemaValidationError("$", "top-level value is not an object")
    errors = sorted(_VALIDATOR.iter_errors(report), key=lambda e: list(e.path))
    if errors:
        first = errors[0]
        path = "$" + "".join(f"[{p!r}]" for p in first.path)
        raise SchemaValidationError(path, first.message)
