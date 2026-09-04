#!/usr/bin/env bash
# Build and export pdf-titan-arum's images. Thin wrapper around `blastbox
# build-images`, which reads blastbox-images.toml in this repo.
#
# The bash that used to live here (and its three near-copies in the other
# engine repos) is gone. It was correct; the problem was that there were four
# of it and they had drifted -- a different ARG name here, a missing pull
# there, a rootfs exported from an image nobody had verified. The declaration
# is now the single description of the chain and one implementation executes it.
#
# Usage:
#   scripts/build_images.sh <tag> [blastbox-version] [--dry-run]
#
# Env: TITANARUM_FC_DIR      where the Firecracker rootfs is written
#                            (default $HOME/titanarum-bb-fc)
#      TITANARUM_GVISOR_DIR   where the gVisor tree is written
#                            (default $HOME/titanarum-bb-gvisor)
#
# There is no BLASTBOX_SRC here, unlike redtusk: THIS engine's warm Dockerfiles
# live in THIS repo. That distinction is what the 2026-09-02 outage was about --
# the Firecracker rootfs was built from inputs looked for in the wrong tree, so
# the guest had no /init and every warm guest hung to the boot timeout.
set -euo pipefail

# Declared ONCE, above the first message that mentions it. Written out by hand
# in two places, this drifted before: the script told an operator to install a
# version it then rejected.
BB_MIN=0.1.35

TAG="${1:?usage: build_images.sh <tag> [blastbox-version] [--dry-run]}"
shift
REPO="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

# A bare version argument is accepted, because that is how this script was
# called before it became a wrapper. Anything beginning with `-` is a flag and
# is passed straight through.
version_arg=()
if [ $# -gt 0 ] && [ "${1#-}" = "$1" ]; then
  version_arg=(--blastbox-version "$1")
  shift
fi

command -v blastbox >/dev/null || {
  echo "blastbox CLI not found. This script needs a blastbox providing" >&2
  echo "an executing \`blastbox build-images\` (>= $BB_MIN):" >&2
  echo "  pip install 'blastbox>=$BB_MIN'" >&2
  exit 2
}
# Having the SUBCOMMAND is not the same as having a version that can run it:
# 0.1.33 has `build-images` and it only validates, so an older blastbox exits 2
# saying execution is not implemented -- which reads like a broken script.
BB_HAVE="$(blastbox version 2>/dev/null | grep -oE '[0-9]+(\.[0-9]+)+' | head -1 || true)"
[ -n "$BB_HAVE" ] || {
  echo "this blastbox has no usable \`version\` output; need >= $BB_MIN" >&2
  exit 2
}
# sort -V puts the smaller first, so the minimum leading means it is satisfied.
# The regex already reduced a PEP 440 local version (0.1.34+gabc) to its release
# segment, so a source build of the minimum counts as meeting it.
[ "$(printf '%s\n%s\n' "$BB_MIN" "$BB_HAVE" | sort -V | head -1)" = "$BB_MIN" ] || {
  echo "blastbox $BB_HAVE is too old; need >= $BB_MIN." >&2
  echo "Earlier versions have \`build-images\` but only validate the plan." >&2
  echo "  pip install --upgrade 'blastbox>=$BB_MIN'" >&2
  exit 2
}

# `${a[@]+"${a[@]}"}` rather than `"${a[@]}"`: bash before 4.4 treats an empty
# array as unset under `set -u` and aborts. Bash 5 does not, so the test suite
# here cannot tell the two apart -- that mutant survives, and the guard is kept
# for the older shells rather than because a local test justifies it.
exec blastbox build-images "$REPO" --tag "$TAG" ${version_arg[@]+"${version_arg[@]}"} "$@"
