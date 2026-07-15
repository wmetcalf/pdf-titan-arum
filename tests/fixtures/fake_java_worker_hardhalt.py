#!/usr/bin/env python3
"""Fake JVM worker that mimics the I2 HARD-HALT path: it flushes a valid partial
`report.json` (timedOut=true) and then exits 3 -- exactly what
PdfTitanArumApp's hard watchdog does (`Runtime.getRuntime().halt(3)` after
writing the partial report). The host must CONSUME that flushed report rather
than treating exit 3 as a fatal failure.
"""
import json
import sys
import time
from pathlib import Path


def main() -> int:
    argv = sys.argv[1:]
    scratch = Path(argv[argv.index("--run") + 1])
    control = scratch / "control"
    control.mkdir(parents=True, exist_ok=True)
    (control / "control.ready").touch()

    go = control / "control.go"
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline and not go.exists():
        time.sleep(0.02)
    if not go.exists():
        return 2

    job = json.loads((control / "job.json").read_text())
    out = Path(job["output_dir"])
    out.mkdir(parents=True, exist_ok=True)
    report = {
        "inputPdf": job.get("filename_hint", "in.pdf"),
        "outputDirectory": str(out),
        "generatedAt": "2026-07-15T12:00:00Z",
        "documentSha256": job.get("sha256") or ("0" * 64),
        "pdfObjectHash": "d41d8cd98f00b204e9800998ecf8427e",
        "fileMagic": "255044462d312e34",
        "documentInfo": {"pdfVersion": "1.4"},
        "pageCount": 1, "blankPageCount": 0, "pagesSpec": job.get("pages", "default"),
        "pagesProcessed": [1], "qrScanEnabled": not job.get("skip_qr", False),
        "dpi": job.get("dpi", 150.0), "addLinkAnnotations": job.get("add_link_annotations", False),
        "revisionCount": 0,
        "urls": [], "javascript": [], "launchActions": [], "actions": [],
        "xfaScripts": [], "embeddedFiles": [], "phoneNumbers": [], "emails": [],
        "pagePdfs": [], "screenshots": [], "renderedImages": [], "resourceImages": [],
        "pageTexts": [], "pageStats": [],
        "timedOut": True, "timedOutAfterMs": 1234,  # the hallmark of a hard-halt partial
    }
    (out / "report.json").write_text(json.dumps(report))
    return 3  # halt(3): partial report is on disk, but the JVM exits nonzero


if __name__ == "__main__":
    sys.exit(main())
