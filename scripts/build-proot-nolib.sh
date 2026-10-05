#!/bin/sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
: "${NDK_PATH:?Set NDK_PATH to an Android NDK installation}"
CC=${CC:-clang}
AR=${AR:-llvm-ar}
STRIP=${STRIP:-llvm-strip}
OBJCOPY=${OBJCOPY:-llvm-objcopy}
OBJDUMP=${OBJDUMP:-llvm-objdump}
READELF=${READELF:-readelf}
API_LEVEL=${API_LEVEL:-24}
OUT_DIR=${OUT_DIR:-$PROJECT_ROOT/build/proot-distro-nolib/arm64}
mkdir -p "$OUT_DIR"
OUT_DIR=$(CDPATH= cd -- "$OUT_DIR" && pwd)
WORK_DIR=$(mktemp -d "$OUT_DIR/work.XXXXXX")
trap 'rm -rf "$WORK_DIR"' EXIT

set -- "$NDK_PATH"/toolchains/llvm/prebuilt/*/sysroot
[ "$#" -eq 1 ] && [ -d "$1" ] || exit 1
SYSROOT=$1
set -- "${SYSROOT%/sysroot}"/lib/clang/*
[ "$#" -eq 1 ] && [ -d "$1" ] || exit 1
RESOURCE_DIR=$1

CC_FLAGS="--target=aarch64-linux-android$API_LEVEL --sysroot=$SYSROOT -resource-dir=$RESOURCE_DIR"
if "$CC" --help | grep -q -- '-fno-termux-rpath'; then
    CC_FLAGS="$CC_FLAGS -fno-termux-rpath"
fi
CC_FLAGS="$CC_FLAGS -ffile-prefix-map=$PROJECT_ROOT=. -ffile-prefix-map=$NDK_PATH=ndk"
TALLOC_SRC=$PROJECT_ROOT/vendor/samba/lib/talloc

"$CC" $CC_FLAGS -O2 -DNO_CONFIG_H=1 -D__STDC_WANT_LIB_EXT1__=1 \
    -I"$PROJECT_ROOT/src/proot/lib/talloc" -I"$TALLOC_SRC" \
    -c "$TALLOC_SRC/talloc.c" -o "$WORK_DIR/talloc.o"
"$AR" rcs "$WORK_DIR/libtalloc.a" "$WORK_DIR/talloc.o"

cp -R "$PROJECT_ROOT/src/proot/src" "$WORK_DIR/src"
make -C "$WORK_DIR/src" clean
make -C "$WORK_DIR/src" -j"${JOBS:-2}" \
    CC="$CC $CC_FLAGS" AR="$AR" STRIP="$STRIP" \
    OBJCOPY="$OBJCOPY" OBJDUMP="$OBJDUMP" GIT=true \
    CFLAGS="-Wall -Wextra -O2 -I$TALLOC_SRC ${EXTRA_CFLAGS:-}" \
    LDFLAGS="-L$WORK_DIR -ltalloc -Wl,-z,noexecstack,-z,max-page-size=16384 ${EXTRA_LDFLAGS:-}" \
    proot

cp "$WORK_DIR/src/proot" "$OUT_DIR/proot-distro-nolib.debug"
"$STRIP" -o "$OUT_DIR/proot-distro-nolib" "$WORK_DIR/src/proot"
"$STRIP" -o "$OUT_DIR/proot-loader" "$WORK_DIR/src/loader/loader"
chmod 755 "$OUT_DIR/proot-distro-nolib" "$OUT_DIR/proot-loader"

"$READELF" -d "$OUT_DIR/proot-distro-nolib" > "$OUT_DIR/dynamic.txt"
if grep -E 'RPATH|RUNPATH' "$OUT_DIR/dynamic.txt"; then
    exit 1
fi
if grep NEEDED "$OUT_DIR/dynamic.txt" | grep -Ev '\[(libc|libdl)\.so\]'; then
    exit 1
fi
if LC_ALL=C grep -aiE 'termux|/data/data/|/data/user/|/home/' \
    "$OUT_DIR/proot-distro-nolib" "$OUT_DIR/proot-loader" > /dev/null; then
    echo 'Unexpected host path or dependency in release binaries' >&2
    exit 1
fi
cp "$OUT_DIR/proot-distro-nolib" "$OUT_DIR/pdn"
(cd "$OUT_DIR" && sha256sum proot-distro-nolib pdn proot-loader > SHA256SUMS)
echo "Built: $OUT_DIR/proot-distro-nolib"
