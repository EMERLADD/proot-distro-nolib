#!/bin/sh
set -eu
probe_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$probe_dir/../.." && pwd)
release_dir=${1:-"$repo_dir/build/releases/v0.6.4"}
archive_path=${2:-"$repo_dir/build/pdn-sources/alpine-minirootfs-3.24.2-aarch64.tar.gz"}
mkdir -p "$probe_dir/app/libs" "$probe_dir/app/src/main/assets"
cp "$release_dir/pdn-engine-0.6.4.aar" "$probe_dir/app/libs/pdn-engine.aar"
rm -f "$probe_dir/app/src/main/assets/alpine.tar.gz"
cp "$archive_path" "$probe_dir/app/src/main/assets/alpine-rootfs.archive"
sha256sum "$release_dir/pdn-engine-0.6.4.aar" "$probe_dir/app/libs/pdn-engine.aar" "$archive_path"
