#!/usr/bin/env bash
# Two-phase JDK 25 AOT bake for the titanarum JVM worker (Warm-Mode Task 8).
#
# Phase 1 (record): run the AppCDS/AOT warmup corpus (W-2/W-3) through the
#                    real analysis pipeline to record which classes get
#                    loaded/linked.
# Phase 2 (create):  turn that recording into a loadable AOT cache
#                    (titanarum.aot), with -XX:+AOTClassLinking so the
#                    classes are pre-linked, not just pre-loaded.
# Phase 3 (probe):    boot the JVM with the finished cache attached and
#                    confirm it actually LOADS (JDK 25 does not fail loudly
#                    on a flag mismatch -- it silently drops back to an
#                    unshared boot and just logs an [error][aot] line under
#                    -Xlog:aot, so we must grep for that rather than trust
#                    the exit code).
# Phase 4 (evidence): re-run the corpus with the cache attached and
#                    -Xlog:class+load, then count how many PDFBox/JBIG2/
#                    JPEG2000/ZXing classes actually loaded "source: shared
#                    objects file" -- the concrete evidence that this cache
#                    is worth more than a bare JVM-boot snapshot (Finding C).
#
# --- THE LOAD-BEARING INVARIANT -------------------------------------------
# The JVM flags used here MUST byte-match titanarum/engine.py's
# _DEFAULT_JVM_FLAGS, or JDK 25 silently rejects the cache at load (see
# Phase 3 above). Rather than hand-copy those flags into this script (and
# risk drift the next time someone edits engine.py), we read them live from
# engine.py itself via the project's own venv. Empirically (see
# task-8-report.md), a mismatch in `--enable-native-access=ALL-UNNAMED`
# specifically is what triggers JDK 25's rejection on this build
# (`jdk.module.enable.native.access` is a module-system property baked into
# the archive's "full module graph"); heap-size (-Xmx) and GC algorithm
# mismatches were tolerated in local testing. We still byte-match the WHOLE
# bundle regardless, per the spec -- don't rely on which flags happened to
# be load-bearing on one JDK build.
#
# Usage:
#   deploy/docker/build-aot.sh [JAR] [CORPUS_DIR] [OUT_DIR]
#
# Env overrides:
#   TITANARUM_JAVA_BIN            java binary (default: java on PATH)
#   TITANARUM_JAVA_LIBRARY_PATH   -Djava.library.path value (default: /app,
#                                 matching engine.py's _DEFAULT_JAVA_LIBRARY_PATH)
#   PYTHON_BIN                    python used to read _DEFAULT_JVM_FLAGS
#                                 (default: <repo>/.venv/bin/python3)
#   TITANARUM_AOT_LOG_DIR         where to keep the phase logs (default:
#                                 <OUT_DIR>/aot-build-logs)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

JAR="${1:-$REPO_ROOT/target/pdf-titan-arum-1.3.0.jar}"
CORPUS_DIR="${2:-$REPO_ROOT/deploy/docker/appcds-warmup-corpus}"
OUT_DIR="${3:-$(cd "$(dirname "$JAR")" && pwd)}"
AOT_CACHE="$OUT_DIR/titanarum.aot"
LOG_DIR="${TITANARUM_AOT_LOG_DIR:-$OUT_DIR/aot-build-logs}"

JAVA_BIN="${TITANARUM_JAVA_BIN:-java}"
LIB_PATH="${TITANARUM_JAVA_LIBRARY_PATH:-/app}"

PYTHON_BIN="${PYTHON_BIN:-$REPO_ROOT/.venv/bin/python3}"
if [[ ! -x "$PYTHON_BIN" ]]; then
    PYTHON_BIN="$(command -v python3 || command -v python)"
fi

log() { echo "[build-aot] $*" >&2; }
die() { echo "[build-aot] ERROR: $*" >&2; exit 1; }

command -v "$JAVA_BIN" >/dev/null 2>&1 || die "java binary not found: $JAVA_BIN"
[[ -f "$JAR" ]] || die "jar not found: $JAR (build it first: mvn -q -DskipTests package)"
[[ -d "$CORPUS_DIR" ]] || die "warmup corpus dir not found: $CORPUS_DIR"
[[ -n "$(find "$CORPUS_DIR" -maxdepth 1 -name '*.pdf' -print -quit)" ]] \
    || die "no *.pdf fixtures found under $CORPUS_DIR"

mkdir -p "$OUT_DIR" "$LOG_DIR"

# --- read the EXACT flag bundle from engine.py (no hand-copy, no drift) ----
mapfile -t MATCHED_FLAGS < <(cd "$REPO_ROOT" && "$PYTHON_BIN" -c '
import sys
sys.path.insert(0, ".")
from titanarum.engine import _DEFAULT_JVM_FLAGS
for f in _DEFAULT_JVM_FLAGS:
    print(f)
')
[[ "${#MATCHED_FLAGS[@]}" -gt 0 ]] \
    || die "could not read _DEFAULT_JVM_FLAGS from titanarum/engine.py via $PYTHON_BIN"

log "matched JVM flags (read live from titanarum/engine.py _DEFAULT_JVM_FLAGS):"
printf '  %s\n' "${MATCHED_FLAGS[@]}" >&2

LIB_FLAG="-Djava.library.path=$LIB_PATH"
FULL_FLAGS=("$LIB_FLAG" "${MATCHED_FLAGS[@]}")

WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/titanarum-aot-bake.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT
AOT_CONF="$WORK_DIR/titanarum.aotconf"
rm -f "$AOT_CACHE"

# --- Phase 1: record -------------------------------------------------------
log "Phase 1/4: AOT record over corpus ($CORPUS_DIR)"
RECORD_LOG="$LOG_DIR/01-record.log"
if ! "$JAVA_BIN" -XX:AOTMode=record -XX:AOTConfiguration="$AOT_CONF" \
        "${FULL_FLAGS[@]}" \
        -jar "$JAR" --appcds-warmup "$CORPUS_DIR" >"$RECORD_LOG" 2>&1; then
    cat "$RECORD_LOG" >&2
    die "AOT record phase failed (exit != 0) -- see $RECORD_LOG"
fi
[[ -f "$AOT_CONF" ]] || { cat "$RECORD_LOG" >&2; die "record phase did not produce $AOT_CONF"; }
if ! grep -q '^APPCDS_WARMUP_OK' "$RECORD_LOG"; then
    cat "$RECORD_LOG" >&2
    die "record phase ran but no fixture reported APPCDS_WARMUP_OK -- corpus not actually exercised"
fi
if grep -q '^APPCDS_WARMUP_SKIP' "$RECORD_LOG"; then
    cat "$RECORD_LOG" >&2
    die "record phase reported APPCDS_WARMUP_SKIP for $(grep -c '^APPCDS_WARMUP_SKIP' "$RECORD_LOG") " \
        "fixture(s) -- a corpus fixture silently failed to process, reducing AOT " \
        "class-load coverage. Fix the fixture (or the parser) rather than let " \
        "coverage quietly shrink -- see $RECORD_LOG"
fi
log "  -> recorded $(grep -c '^APPCDS_WARMUP_OK' "$RECORD_LOG") fixture(s), config: $AOT_CONF"

# --- Phase 2: create --------------------------------------------------------
log "Phase 2/4: AOT create -> $AOT_CACHE"
CREATE_LOG="$LOG_DIR/02-create.log"
if ! "$JAVA_BIN" -XX:AOTMode=create -XX:AOTConfiguration="$AOT_CONF" \
        -XX:AOTCache="$AOT_CACHE" -XX:+AOTClassLinking \
        "${FULL_FLAGS[@]}" \
        -jar "$JAR" >"$CREATE_LOG" 2>&1; then
    cat "$CREATE_LOG" >&2
    die "AOT create phase failed (exit != 0) -- see $CREATE_LOG"
fi
[[ -f "$AOT_CACHE" ]] || { cat "$CREATE_LOG" >&2; die "create phase did not produce $AOT_CACHE"; }
log "  -> $(cat "$CREATE_LOG" | tail -1)"

# --- Phase 3: probe boot (does the cache actually LOAD?) --------------------
log "Phase 3/4: probe boot with -XX:AOTCache + -Xlog:aot"
PROBE_LOG="$LOG_DIR/03-probe.log"
set +e
"$JAVA_BIN" -XX:AOTCache="$AOT_CACHE" -Xlog:aot \
    "${FULL_FLAGS[@]}" \
    -jar "$JAR" --version >"$PROBE_LOG" 2>&1
PROBE_RC=$?
set -e
cat "$PROBE_LOG" >&2

if [[ $PROBE_RC -ne 0 ]]; then
    die "probe boot exited $PROBE_RC -- see $PROBE_LOG"
fi
# JDK 25 does NOT fail the process on a rejected/mismatched AOT cache; it logs
# an [error][aot] line and silently falls back to an unshared boot. Grep for
# that rather than trust the exit code (confirmed empirically -- see
# task-8-report.md).
if grep -q '\[error\]\[aot\]' "$PROBE_LOG"; then
    die "JVM logged an [error][aot] line -- the cache was REJECTED. See $PROBE_LOG"
fi
if ! grep -q 'Opened AOT cache' "$PROBE_LOG"; then
    die "probe log never reported opening the AOT cache -- see $PROBE_LOG"
fi
if ! grep -q 'Using AOT-linked classes: true' "$PROBE_LOG"; then
    die "probe log did not confirm AOT-linked classes are in use -- see $PROBE_LOG"
fi
log "  -> cache opened cleanly, AOT-linked classes confirmed in use"

# --- Phase 4: evidence (which classes actually came from the cache?) -------
log "Phase 4/4: re-run corpus with the cache attached, count AOT-linked classes"
EVIDENCE_LOG="$LOG_DIR/04-class-load.log"
if ! "$JAVA_BIN" -XX:AOTCache="$AOT_CACHE" -Xlog:class+load=info:file="$EVIDENCE_LOG" \
        "${FULL_FLAGS[@]}" \
        -jar "$JAR" --appcds-warmup "$CORPUS_DIR" >"$LOG_DIR/04-appcds-run.log" 2>&1; then
    cat "$LOG_DIR/04-appcds-run.log" >&2
    die "post-cache appcds-warmup run failed -- see $LOG_DIR/04-appcds-run.log"
fi

total_shared=$(grep -c 'source: shared objects file' "$EVIDENCE_LOG" || true)
pdfbox_shared=$(grep -i 'pdfbox' "$EVIDENCE_LOG" | grep -c 'source: shared objects file' || true)
zxing_shared=$(grep -i 'zxing' "$EVIDENCE_LOG" | grep -c 'source: shared objects file' || true)
jpeg2000_shared=$(grep -iE 'jaiimageio|j2k|jpeg2000' "$EVIDENCE_LOG" | grep -c 'source: shared objects file' || true)
jbig2_shared=$(grep -i 'jbig2' "$EVIDENCE_LOG" | grep -c 'source: shared objects file' || true)

log "  -> $total_shared total classes loaded from the AOT cache"
log "     pdfbox=$pdfbox_shared zxing=$zxing_shared jpeg2000/jai=$jpeg2000_shared jbig2=$jbig2_shared"

[[ "$total_shared" -gt 0 ]] || die "zero classes loaded from the AOT cache -- cache is not being used"
[[ "$pdfbox_shared" -gt 0 ]] || die "zero PDFBox classes loaded from the AOT cache"
if [[ "$zxing_shared" -eq 0 ]]; then
    log "  WARNING: zero ZXingReaderScanner classes AOT-linked"
fi
if [[ "$jpeg2000_shared" -eq 0 ]]; then
    log "  WARNING: zero JPEG2000/jai-imageio classes AOT-linked"
fi
if [[ "$jbig2_shared" -eq 0 ]]; then
    log "  WARNING: zero JBIG2 classes AOT-linked (corpus has no JBIG2-encoded fixture -- see appcds-warmup-corpus/README.md 'Known gaps')"
fi

log "AOT cache built and verified: $AOT_CACHE"
log "logs kept at: $LOG_DIR"
echo "$AOT_CACHE"
