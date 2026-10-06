#!/bin/sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT_DIR=${OUT_DIR:-$PROJECT_ROOT/build/proot-distro-nolib/arm64}
PACKAGE_DIR=$PROJECT_ROOT/build/packages
VERSION=$(sed -n 's/^#define PDN_VERSION "\([^"]*\)"/\1/p' "$PROJECT_ROOT/src/proot/src/cli/proot.h")
case "$VERSION" in ''|*[!0-9.]* ) echo 'Invalid project version' >&2; exit 1;; esac
COMMIT=$(git -C "$PROJECT_ROOT" rev-parse HEAD)
if ! git -C "$PROJECT_ROOT" diff --quiet HEAD -- . ':!vendor/bionic' ':!vendor/termlib'; then
    echo 'Commit tracked project changes before packaging corresponding source.' >&2
    exit 1
fi
if [ -n "$(git -C "$PROJECT_ROOT" ls-files --others --exclude-standard -- src/proot scripts .github Makefile licenses README.md CHANGELOG.md LICENSE docs)" ]; then
    echo 'Commit untracked project files before packaging corresponding source.' >&2
    exit 1
fi
SAMBA_COMMIT=$(git -C "$PROJECT_ROOT" ls-tree HEAD vendor/samba | awk '{print $3}')
[ "$SAMBA_COMMIT" = "$(git -C "$PROJECT_ROOT/vendor/samba" rev-parse HEAD)" ]
[ -z "$(git -C "$PROJECT_ROOT/vendor/samba" status --porcelain -- lib/talloc COPYING)" ]
(cd "$OUT_DIR" && sha256sum -c SHA256SUMS)
mkdir -p "$PACKAGE_DIR"
STAGE=$(mktemp -d "$PROJECT_ROOT/build/.pdn-package.XXXXXX")
trap 'rm -rf "$STAGE"' EXIT HUP INT TERM
NAME=proot-distro-nolib-v$VERSION-android-arm64
BUNDLE=$STAGE/$NAME
SOURCE=$STAGE/proot-distro-nolib-source
mkdir -p "$BUNDLE/licenses" "$BUNDLE/docs" "$SOURCE"
cp "$OUT_DIR/pdn" "$OUT_DIR/proot-distro-nolib" "$OUT_DIR/proot-loader" "$OUT_DIR/SHA256SUMS" "$BUNDLE/"
cp "$PROJECT_ROOT/README.md" "$PROJECT_ROOT/CHANGELOG.md" "$PROJECT_ROOT/LICENSE" "$BUNDLE/"
cp -R "$PROJECT_ROOT/docs/." "$BUNDLE/docs/"
cp "$OUT_DIR/licenses/"* "$BUNDLE/licenses/"
cp "$PROJECT_ROOT/src/proot/COPYING" "$BUNDLE/licenses/proot-GPL-2.0.txt"
cp "$PROJECT_ROOT/vendor/samba/COPYING" "$BUNDLE/licenses/GPL-3.0.txt"
cp "$PROJECT_ROOT/licenses/LGPL-3.0.txt" "$BUNDLE/licenses/talloc-LGPL-3.0.txt"
printf 'Project: proot-distro-nolib\nVersion: %s\nCommit: %s\ntalloc source commit: %s\n' "$VERSION" "$COMMIT" "$SAMBA_COMMIT" > "$BUNDLE/BUILD-INFO.txt"
git -C "$PROJECT_ROOT" archive --format=tar HEAD > "$STAGE/project.tar"
tar -xf "$STAGE/project.tar" -C "$SOURCE"
git -C "$PROJECT_ROOT/vendor/samba" archive --format=tar "$SAMBA_COMMIT" lib/talloc COPYING > "$STAGE/talloc.tar"
mkdir -p "$SOURCE/vendor/samba" "$SOURCE/build/proot-distro-nolib/deps/downloads"
tar -xf "$STAGE/talloc.tar" -C "$SOURCE/vendor/samba"
for dependency in mbedtls.tar.bz2 curl.tar.xz libarchive.tar.xz zlib.tar.gz; do
    cp "$PROJECT_ROOT/build/proot-distro-nolib/deps/downloads/$dependency" "$SOURCE/build/proot-distro-nolib/deps/downloads/"
done
cp "$BUNDLE/BUILD-INFO.txt" "$SOURCE/BUILD-INFO.txt"
tar -czf "$BUNDLE/source.tar.gz" -C "$STAGE" proot-distro-nolib-source
tar -czf "$PACKAGE_DIR/$NAME.tar.gz" -C "$STAGE" "$NAME"
(cd "$PACKAGE_DIR" && sha256sum "$NAME.tar.gz" > SHA256SUMS)
printf 'Packaged: %s/%s.tar.gz\n' "$PACKAGE_DIR" "$NAME"
