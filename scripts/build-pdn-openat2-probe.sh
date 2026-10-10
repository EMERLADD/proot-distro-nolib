#!/bin/sh
set -eu
repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}
ndk_dir=${PDN_NDK_DIR:-$sdk_dir/ndk/26.3.11579264}
toolchain_dir=$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64
compiler=${CC:-$toolchain_dir/bin/clang}
if [ -z "${CC:-}" ] && command -v clang >/dev/null 2>&1 && clang --help 2>/dev/null | grep -q -- '-fno-termux-rpath'; then compiler=clang; fi
output=${1:-$repo_dir/build/openat2-probe}
mkdir -p "$(dirname -- "$output")"
termux_flag=
if "$compiler" --help 2>/dev/null | grep -q -- '-fno-termux-rpath'; then termux_flag=-fno-termux-rpath; fi
"$compiler" $termux_flag --target=aarch64-linux-android28 --sysroot="$toolchain_dir/sysroot" -resource-dir="$toolchain_dir/lib/clang/17" -static -O2 "$repo_dir/tests/proot_nolib_probe.c" -o "$output"
python3 - "$output" <<'PY'
from pathlib import Path
import struct
import sys
path = Path(sys.argv[1])
data = bytearray(path.read_bytes())
if data[:6] != b'\x7fELF\x02\x01':
    raise SystemExit('Expected little-endian ELF64')
phoff = struct.unpack_from('<Q', data, 32)[0]
phsize, phcount = struct.unpack_from('<HH', data, 54)
for index in range(phcount):
    offset = phoff + index * phsize
    if struct.unpack_from('<I', data, offset)[0] == 7:
        alignment = struct.unpack_from('<Q', data, offset + 48)[0]
        if alignment < 64:
            struct.pack_into('<Q', data, offset + 48, 64)
path.write_bytes(data)
PY
