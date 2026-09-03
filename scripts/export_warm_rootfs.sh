#!/usr/bin/env bash
# Turn the stamped cold worker into the rootfs artifacts the warm tiers boot.
#
# Both of titanarum's warm tiers boot a rootfs exported from the SAME image the
# cold tier runs -- gVisor an exported directory tree, Firecracker an ext4 file
# -- so the provenance of the cold worker carries through to both. Building the
# rootfs any other way produces an artifact whose contents nothing verified.
#
# Usage:  scripts/export_warm_rootfs.sh <tag>
# Env:    TITANARUM_GVISOR_DIR (default $HOME/titanarum-bb-gvisor)
#         TITANARUM_FC_DIR     (default $HOME/titanarum-bb-fc)
#         ROOTFS_MIB           (default: match the existing ext4, else 3072)
set -euo pipefail

TAG="${1:?usage: export_warm_rootfs.sh <tag>}"
GVISOR_DIR="${TITANARUM_GVISOR_DIR:-$HOME/titanarum-bb-gvisor}"
FC_DIR="${TITANARUM_FC_DIR:-$HOME/titanarum-bb-fc}"
IMAGE="titanarum-cold-worker:$TAG"

# The two destinations differ in ownership on a real node -- the gVisor tree is
# root-owned, the FC dir belongs to the deploy user -- so elevate per path
# instead of assuming either. Asking for sudo we do not need is as wrong as
# failing without it.
as_owner() {  # <dir> <cmd...>
  if [ -w "$1" ]; then shift; "$@"; else shift; sudo "$@"; fi
}

docker image inspect --format '{{.Id}}' "$IMAGE" >/dev/null 2>&1 || {
  echo "$IMAGE is not built. Run scripts/build_images.sh $TAG first." >&2
  exit 2
}

# `docker export | tar -x` over an EXISTING tree overwrites the members in the
# archive and leaves everything else behind: a file the new image deleted or
# renamed stays, and the guest boots a mixture of two builds. Extract into a
# fresh directory and swap, keeping the old one for rollback.
echo ">> gvisor rootfs <- $IMAGE"
staging="$GVISOR_DIR/rootfs-$TAG"
[ -d "$GVISOR_DIR" ] || sudo mkdir -p "$GVISOR_DIR"
as_owner "$GVISOR_DIR" rm -rf "$staging"
as_owner "$GVISOR_DIR" mkdir -p "$staging"
cid="$(docker create "$IMAGE")"
trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' EXIT
if [ -w "$GVISOR_DIR" ]; then docker export "$cid" | tar -x -C "$staging"
else docker export "$cid" | sudo tar -x -C "$staging"; fi
docker rm "$cid" >/dev/null; trap - EXIT
if [ -d "$GVISOR_DIR/rootfs" ]; then
  as_owner "$GVISOR_DIR" rm -rf "$GVISOR_DIR/rootfs.bak"
  as_owner "$GVISOR_DIR" mv "$GVISOR_DIR/rootfs" "$GVISOR_DIR/rootfs.bak"
fi
as_owner "$GVISOR_DIR" mv "$staging" "$GVISOR_DIR/rootfs"
echo "   $GVISOR_DIR/rootfs  (previous kept as rootfs.bak)"

# A Firecracker rootfs is not just the worker's filesystem: the guest boots
# /init, which execs blastbox's run_guest.py against a baked /opt/blastbox/
# guest.env. Those are added by blastbox's deploy/firecracker/Dockerfile.<engine>
# -- there is one for redtusk and clippyshot, and NOT one for titanarum.
#
# Exporting the bare cold worker produces an ext4 that boots and then never
# signals READY: measured on toolz2, the warm base timed out at 120s and the
# tier fell back to cold for every job while the dispatcher looked healthy. So
# this refuses rather than writing an artifact that cannot boot.
if ! docker run --rm --entrypoint test "$IMAGE" -f /init 2>/dev/null; then
  echo >&2
  echo "$IMAGE has no /init, so it cannot be a Firecracker rootfs." >&2
  echo "The gVisor tree above is exported and in place; the FC image needs" >&2
  echo "blastbox's deploy/firecracker/Dockerfile.titanarum (with a" >&2
  echo "guest.titanarum.env), which does not exist yet -- redtusk and" >&2
  echo "clippyshot have one. Leaving $FC_DIR untouched." >&2
  exit 3
fi

echo ">> firecracker rootfs <- $IMAGE"
out="$FC_DIR/titanarum-rootfs.ext4"
mib="${ROOTFS_MIB:-}"
if [ -z "$mib" ]; then
  if [ -f "$out" ]; then mib=$(( $(stat -c %s "$out") / 1024 / 1024 )); else mib=3072; fi
fi
rd="$(mktemp -d "${TMPDIR:-/tmp}/titanrootfs.XXXXXX")"
cid="$(docker create "$IMAGE")"
trap 'docker rm -f "$cid" >/dev/null 2>&1 || true; rm -rf "$rd"' EXIT
docker export "$cid" | tar -x -C "$rd"
docker rm "$cid" >/dev/null
mkdir -p "$FC_DIR"
[ -f "$out" ] && mv "$out" "$out.bak"
truncate -s "${mib}M" "$out"
# `mke2fs -d` populates the image directly: no mount, no root.
mkfs.ext4 -F -q -d "$rd" "$out"
rm -rf "$rd"; trap - EXIT
echo "   $out  (${mib} MiB, previous kept as titanarum-rootfs.ext4.bak)"

echo
echo "both warm rootfs artifacts replaced. Restart the fc (and gvisor, if run)"
echo "dispatchers to pick them up; the tiers boot the rootfs, not the image tag."
