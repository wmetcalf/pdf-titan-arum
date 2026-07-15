#!/usr/bin/env bash
# Build a blastbox FC worker rootfs (ext4) from the Docker image.
#
# No mount, no root: we `docker export` the image to a directory and let
# `mke2fs -d` populate the ext4 image directly — mirroring the host-side rdump
# discipline (never mount an untrusted/handled disk).
#
# Usage:  deploy/firecracker/build-rootfs.sh [output.ext4]
# Env:    ROOTFS_MIB (default 768)   DOCKER (default: docker)
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
IMG="${1:-$HERE/rootfs.ext4}"
SIZE_MIB="${ROOTFS_MIB:-1024}"
DOCKER="${DOCKER:-docker}"
# ENGINE is a blastbox engine spec baked into the rootfs (module:Class). The default builds
# the titanarum PDF engine; override for the generic FC test engines (probe / pdfrasterize).
ENGINE="${ENGINE:-titanarum.engine:TitanArumEngine}"
DOCKERFILE="${DOCKERFILE:-deploy/firecracker/Dockerfile.titanarum}"
# Sanitize ENGINE into a valid Docker tag: the module:Class spec contains ':' / '.' / uppercase,
# none of which are legal in the tag component (only [A-Za-z0-9_.-], and ':' would split it).
ENGINE_TAG="$(printf '%s' "$ENGINE" | tr ':/.' '___' | tr '[:upper:]' '[:lower:]')"
TAG="blastbox-fc-worker:${ENGINE_TAG}"

command -v mkfs.ext4 >/dev/null || { echo "need mkfs.ext4 (e2fsprogs)"; exit 1; }
command -v truncate  >/dev/null || { echo "need truncate (coreutils)"; exit 1; }

cd "$REPO"
echo ">> docker build $TAG (engine=$ENGINE, dockerfile=$DOCKERFILE)"
build_args="--build-arg ENGINE=$ENGINE"
[ -n "${BASE_IMAGE:-}" ] && build_args="$build_args --build-arg BASE_IMAGE=$BASE_IMAGE"
# shellcheck disable=SC2086
"$DOCKER" build $build_args -f "$DOCKERFILE" -t "$TAG" .

# Hardening audit: the rootfs must have NO setuid/setgid binaries.
echo ">> audit: setuid/setgid binaries (expect none)"
suid="$("$DOCKER" run --rm --entrypoint find "$TAG" / -xdev -type f -perm /6000 2>/dev/null || true)"
if [ -n "$suid" ]; then
    echo "!! setuid/setgid binaries remain in the rootfs:" >&2
    echo "$suid" >&2
    exit 1
fi
echo "   clean — no setuid/setgid binaries"

cid="$("$DOCKER" create "$TAG")"
rootdir="$(mktemp -d)"
cleanup() { "$DOCKER" rm -f "$cid" >/dev/null 2>&1 || true; rm -rf "$rootdir"; }
trap cleanup EXIT

echo ">> export rootfs -> $rootdir"
"$DOCKER" export "$cid" | tar -x -C "$rootdir"

echo ">> mke2fs -d (no mount, no root) -> $IMG (${SIZE_MIB} MiB)"
rm -f "$IMG"
truncate -s "${SIZE_MIB}M" "$IMG"
# -F force (regular file), -q quiet, -d populate from directory.
mkfs.ext4 -F -q -d "$rootdir" "$IMG"

echo ">> done: $IMG"
ls -lh "$IMG"
