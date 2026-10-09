#!/bin/sh
set -eu
probe_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$probe_dir/../.." && pwd)
version=$(sed -n 's/^#define PDN_VERSION "\([0-9.]*\)"/\1/p' "$repo_dir/src/proot/src/cli/proot.h")
release_dir=${1:-"$repo_dir/build/releases/v$version"}
aar_path=${PDN_AAR_PATH:-"$release_dir/pdn-engine-lite-$version.aar"}
archive_path=${2:-"$repo_dir/build/pdn-sources/alpine-minirootfs-3.24.2-aarch64.tar.gz"}
mkdir -p "$probe_dir/app/libs" "$probe_dir/app/src/main/assets"
cp "$aar_path" "$probe_dir/app/libs/pdn-engine.aar"
rm -f "$probe_dir/app/src/main/assets/alpine.tar.gz"
cp "$archive_path" "$probe_dir/app/src/main/assets/alpine-rootfs.archive"
sha256sum "$aar_path" "$probe_dir/app/libs/pdn-engine.aar" "$archive_path"
