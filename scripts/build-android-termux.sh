#!/bin/sh
set -eu

repo_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/android-sdk}}
ndk_dir=${PDN_NDK_DIR:-$sdk_dir/ndk/26.3.11579264}
sysroot_dir=$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/sysroot
unwind_dir=$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/17/lib/linux/aarch64
native_dir=$repo_dir/build/android-termux/native
obj_dir=$repo_dir/build/android-termux/obj
termlib_dir=${PDN_TERMLIB_SOURCE_DIR:-$repo_dir/vendor/termlib}
program_dir=${PDN_PROGRAM_DIR:-$repo_dir/build/proot-distro-nolib/arm64}
clang_bin=${CC:-clang}
clangxx_bin=${CXX:-clang++}

if [ ! -f "$termlib_dir/lib/src/main/cpp/Terminal.cpp" ]; then
    termlib_dir=$repo_dir/build/termlib-source
    mkdir -p "$termlib_dir"
    git -C "$repo_dir/vendor/termlib" archive HEAD | tar -x -C "$termlib_dir"
fi
cpp_dir=$termlib_dir/lib/src/main/cpp
mkdir -p "$native_dir/engine/arm64-v8a" "$native_dir/app/arm64-v8a" "$native_dir/termlib/arm64-v8a" "$obj_dir"
[ -d "$sysroot_dir" ] || { echo "NDK sysroot missing: $sysroot_dir" >&2; exit 1; }
[ -f "$program_dir/pdn" ] && [ -f "$program_dir/proot-loader" ] || {
    echo "Build pdn and matching loader first: scripts/build-proot-nolib.sh" >&2
    exit 1
}

"$clang_bin" -fno-termux-rpath --target=aarch64-linux-android28 --sysroot="$sysroot_dir" -fPIC -shared -L"$unwind_dir" -std=c11 -Wall -Wextra \
    -Wl,-z,max-page-size=16384 "$repo_dir/android/proot-engine/src/main/cpp/ptyjni.c" \
    -llog -o "$native_dir/engine/arm64-v8a/libptyjni.so"
for source_file in encoding keyboard mouse parser pen screen state unicode vterm; do
    "$clang_bin" -fno-termux-rpath --target=aarch64-linux-android28 --sysroot="$sysroot_dir" -fPIC -O2 -DVTERM_STATIC \
        -I"$cpp_dir/libvterm/include" -c "$cpp_dir/libvterm/src/$source_file.c" -o "$obj_dir/$source_file.o"
done
"$clangxx_bin" -fno-termux-rpath --target=aarch64-linux-android28 --sysroot="$sysroot_dir" -fPIC -shared -L"$unwind_dir" -O2 -std=c++17 -nostdinc++ -nostdlib++ \
    -isystem "$sysroot_dir/usr/include/c++/v1" -I"$cpp_dir/libvterm/include" \
    "$cpp_dir/Terminal.cpp" "$cpp_dir/mutf8.cpp" "$obj_dir"/*.o \
    "$sysroot_dir/usr/lib/aarch64-linux-android/libc++_static.a" \
    "$sysroot_dir/usr/lib/aarch64-linux-android/libc++abi.a" \
    -landroid -llog -lm -ldl -Wl,-z,max-page-size=16384 -o "$native_dir/termlib/arm64-v8a/libjni_cb_term.so"
for legacy_file in libbusybox.so libpr-cli.so libproot.so libbash.so; do
    legacy_path=$repo_dir/android/proot-engine/src/main/jniLibs/arm64-v8a/$legacy_file
    rm -f "$native_dir/engine/arm64-v8a/$legacy_file"
    if [ -f "$legacy_path" ]; then
        cp -f "$legacy_path" "$native_dir/app/arm64-v8a/$legacy_file"
    else
        rm -f "$native_dir/app/arm64-v8a/$legacy_file"
    fi
done
cp -f "$program_dir/pdn" "$native_dir/engine/arm64-v8a/libpdn.so"
cp -f "$program_dir/proot-loader" "$native_dir/engine/arm64-v8a/libproot-loader.so"
cmp "$program_dir/pdn" "$native_dir/engine/arm64-v8a/libpdn.so"
cmp "$program_dir/proot-loader" "$native_dir/engine/arm64-v8a/libproot-loader.so"
md5sum "$program_dir/pdn" "$native_dir/engine/arm64-v8a/libpdn.so" \
    "$program_dir/proot-loader" "$native_dir/engine/arm64-v8a/libproot-loader.so"
for native_file in "$native_dir"/*/arm64-v8a/*.so; do
    llvm-readelf -h "$native_file" | sed -n '/Class:/p; /Machine:/p'
    llvm-readelf -d "$native_file" | sed -n '/NEEDED/p'
    if llvm-readelf -d "$native_file" | grep -E 'NEEDED.*(libc\+\+_shared|libtermux)|RPATH|RUNPATH' >/dev/null; then
        echo "Unexpected runtime dependency: $native_file" >&2
        exit 1
    fi
done

if [ "${1:-}" = "--native-only" ]; then exit 0; fi
if [ -n "${PDN_GRADLE:-}" ]; then
    gradle_bin=$PDN_GRADLE
elif [ -d "$HOME/.gradle/wrapper/dists/gradle-8.11.1-bin" ]; then
    gradle_bin=$repo_dir/android/gradlew
else
    gradle_bin=$(find "$HOME/.gradle/wrapper/dists/gradle-8.13-bin" -path '*/bin/gradle' -type f | head -n 1)
    [ -n "$gradle_bin" ] || gradle_bin=$repo_dir/android/gradlew
fi
if [ "$#" -eq 0 ]; then set -- assembleDebug; fi
rm -rf "$repo_dir/android/app/build"
cd "$repo_dir/android"
ANDROID_HOME="$sdk_dir" "$gradle_bin" \
    -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" \
    -PtermuxNativeLibsDir="$native_dir" -PtermlibSourceDir="$termlib_dir" "$@"
