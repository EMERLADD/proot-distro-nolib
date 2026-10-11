# Source builds and release files

[简体中文](pdn-build-and-release.md) | English · [README](../README.en.md)

## Build the raw ELF

Standalone PDN needs Git, Make, Clang/LLVM, curl, tar, xz/bzip2, pkg-config and an Android NDK, without Java, Gradle or Rust. The first build downloads checksum-pinned dependency sources.

```sh
git clone https://github.com/EMERLADD/proot-distro-nolib.git
cd proot-distro-nolib
git submodule update --init --depth 1 vendor/samba
make NDK_PATH=/path/to/android-ndk
```

Linux x86_64 hosts can use the NDK LLVM tools. Native ARM64 Android builds need Clang/LLVM executables that run on Android together with the NDK sysroot; the Linux x86_64 compiler cannot run directly on ARM64 Android. Build tools may come from Termux, but the output does not depend on Termux at runtime.

Outputs are under `build/proot-distro-nolib/arm64/`. Run `make clean` before rebuilding with a different toolchain or dependency compiler settings.

## Build the AAR and App

The AAR additionally needs Java, Gradle, the Android SDK and a PTY JNI build environment. Build PDN and loader first, then run:

```sh
cd android
sh gradlew -PpdnEngineOnly=true :proot-engine:bundleReleaseAar
```

See [App integration](android-embedding.en.md) for native file preparation, AAR import and APK packaging. Runnable projects are provided as the [AAR example](../examples/aar-probe/README.en.md) and [.so example](../examples/so-probe/README.en.md).

## Downloads and verification

Get matching attachments from [Releases](https://github.com/EMERLADD/proot-distro-nolib/releases):

| File | Contents |
| --- | --- |
| `pdn / proot-loader` | Raw ARM64 ELF |
| `libpdn.so / libproot-loader.so` | ELF executables named for APK native-library packaging |
| `pdn-engine-VERSION.aar` | Java/Kotlin API, PDN, loader and PTY JNI |
| `pdn-engine-lite-VERSION.aar` | Byte-identical compatibility alias for the standard AAR |
| `proot-distro-nolib-vVERSION-android-arm64.tar.gz` | Native programs, corresponding source, dependency source, guides and licenses |
| `pdn-*-probe-VERSION-*-test.apk` | Ordinary AAR / .so Debug and R8 Release test Apps, using test signing keys |
| `SHA256SUMS` | Attachment SHA256 checksums |

Place all files listed by the matching manifest in one directory, then verify:

```sh
sha256sum -c SHA256SUMS
```

Use each build's own checksum manifest rather than mixing files. Replace PDN and loader together and close old sessions before starting again; existing rootfs do not need reinstalling.

## Corresponding source and licenses

The full bundle includes `BUILD-INFO.txt`, `LICENSE`, `licenses/` and `source.tar.gz`. The source archive includes talloc source and the original Mbed TLS, curl, libarchive and zlib source archives, but not the Android SDK/NDK.

When redistributing binaries, also provide corresponding source and third-party licenses. The engine retains GPL-2.0-or-later, talloc uses LGPL-3.0-or-later and other components retain their own licenses. The project cannot be uniformly declared MIT. See [LICENSE](../LICENSE) for scope.
