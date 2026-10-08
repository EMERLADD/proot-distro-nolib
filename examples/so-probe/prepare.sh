#!/bin/sh
set -eu
probe_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
repo_dir=$(CDPATH= cd -- "$probe_dir/../.." && pwd)
release_dir=${1:-"$repo_dir/build/releases/v0.6.4"}
archive_path=${2:-"$repo_dir/build/pdn-sources/alpine-minirootfs-3.24.2-aarch64.tar.gz"}
mkdir -p "$probe_dir/app/src/main/jniLibs/arm64-v8a" "$probe_dir/app/src/main/assets"
cp "$release_dir/libpdn.so" "$release_dir/libproot-loader.so" "$probe_dir/app/src/main/jniLibs/arm64-v8a/"
rm -f "$probe_dir/app/src/main/assets/alpine.tar.gz"
cp "$archive_path" "$probe_dir/app/src/main/assets/alpine-rootfs.archive"
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}
ndk_dir=${PDN_NDK_DIR:-$sdk_dir/ndk/26.3.11579264}
toolchain_dir=$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64
sysroot_dir=$toolchain_dir/sysroot
resource_dir=$toolchain_dir/lib/clang/17
compiler=${CC:-$toolchain_dir/bin/clang}
if [ -z "${CC:-}" ] && command -v clang >/dev/null 2>&1 && clang --help 2>/dev/null | grep -q -- '-fno-termux-rpath'; then compiler=clang; fi
termux_flag=
if "$compiler" --help 2>/dev/null | grep -q -- '-fno-termux-rpath'; then termux_flag=-fno-termux-rpath; fi
coverage_flags=
if [ "${PDN_PROBE_NATIVE_COVERAGE:-0}" = 1 ]; then
    resource_dir=$("$compiler" -print-resource-dir)
    coverage_flags='-fprofile-instr-generate -fcoverage-mapping -DPDN_PROBE_NATIVE_COVERAGE'
fi
"$compiler" $termux_flag $coverage_flags --target=aarch64-linux-android28 --sysroot="$sysroot_dir" -resource-dir="$resource_dir" -shared -fPIC -O2 -Wall -Wextra -Werror -L"$toolchain_dir/lib/clang/17/lib/linux/aarch64" -Wl,-z,max-page-size=16384 "$probe_dir/native/probepty.c" -o "$probe_dir/app/src/main/jniLibs/arm64-v8a/libprobepty.so"
sha256sum "$release_dir/libpdn.so" "$probe_dir/app/src/main/jniLibs/arm64-v8a/libpdn.so" "$release_dir/libproot-loader.so" "$probe_dir/app/src/main/jniLibs/arm64-v8a/libproot-loader.so" "$archive_path"
