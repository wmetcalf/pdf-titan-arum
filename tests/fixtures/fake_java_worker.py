#!/usr/bin/env python3
"""Fake JVM worker: mimics `pdf-titan-arum.jar --run <scratch>` for IPC unit tests."""
import json
import sys
import time
from pathlib import Path


def main() -> int:
    argv = sys.argv[1:]
    scratch = Path(argv[argv.index("--run") + 1])
    control = scratch / "control"
    control.mkdir(parents=True, exist_ok=True)
    (control / "control.ready").touch()  # announce ready

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
        "generatedAt": "2026-07-10T12:00:00Z",
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
    }
    (out / "report.json").write_text(json.dumps(report))
    return 0


if __name__ == "__main__":
    sys.exit(main())
