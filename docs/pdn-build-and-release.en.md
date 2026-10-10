# Building and releasing

[简体中文](pdn-build-and-release.md) | English · [Back to README](../README.en.md)

## Contents

- [Build the ELF locally](#build-the-elf-locally)
- [Tests and packaging](#tests-and-packaging)
- [Build the AAR](#build-the-aar)
- [GitHub builds](#github-builds)
- [Release files and corresponding source](#release-files-and-corresponding-source)
- [Origins and licenses](#origins-and-licenses)

## Build the ELF locally

Building standalone PDN does not require Java, Gradle or Rust. It requires Git, Make, Clang/LLVM tools, curl, tar, xz/bzip2, pkg-config and the Android NDK. The first build downloads dependency sources with pinned checksums.

```sh
git clone https://github.com/EMERLADD/proot-distro-nolib.git
cd proot-distro-nolib
git submodule update --init --depth 1 vendor/samba
make NDK_PATH=/your/Android/NDK/path
```

On a Linux x86_64 host, use the NDK LLVM tools:

```sh
export NDK_PATH="$HOME/Android/Sdk/ndk/26.3.11579264"
export PATH="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
make NDK_PATH="$NDK_PATH"
```

An ARM64 Android build requires Clang/LLVM tools that run on Android, using the NDK sysroot. It cannot run the Linux x86_64 compiler directly. Build tools may come from Termux; the output does not depend on Termux at runtime.

Output is under `build/proot-distro-nolib/arm64/`. The build checks dynamic dependencies, RPATH and embedded host paths. It does not install an App or replace host bin files.

## Tests and packaging

```sh
make help
make test
make package
make clean
```

Run `make test` in an ARM64 Android environment that permits PRoot execution, with Python 3 and the repository's BusyBox test fixture. Successful cross-compilation on Linux x86_64 is not an Android runtime test. See [test records (Chinese)](pdn-error-testing.md) and the [changelog](../CHANGELOG.md).

`make package` builds and packages committed sources and artifacts. Commit project files before packaging so the source corresponds to the current commit. Output is in `build/packages/`. Run `make clean` when changing the compiler, NDK or dependency flags to avoid reusing old static libraries.

## Build the AAR

Build PDN and loader first, then build the engine in `android/`:

```sh
cd android
sh gradlew -PpdnEngineOnly=true :proot-engine:bundleDebugAar
```

This also needs Gradle, Java, the Android SDK and a PTY JNI build environment. The original App GUI and terminal library modules need not be configured. See [Android embedding (Chinese)](android-embedding.md), the [AAR probe project](../examples/aar-probe/README.md) and the [AAR API (Chinese)](pdn-aar-api.md).

Since 0.6.6, both AAR variants contain only PDN, loader and PTY JNI as native components, retaining terminal support without legacy pr native components. `libpdn.so` and `libproot-loader.so` are renamed ELF executables; PTY JNI is the actual JVM-loaded JNI library. The AAR requires the Kotlin standard library and is currently a non-instrumented Debug engine build. The existing AAR passed acceptance in a nondebuggable Release test App with R8, Android default optimization/JNI rules and local test signing; direct `.so` integration also passed. See the [R8 acceptance record (Chinese)](pdn-error-testing.md#066-releaser8-混淆验收). Maven publication is not implemented.

## GitHub builds

Workflow: [`.github/workflows/ci.yml`](../.github/workflows/ci.yml). It is triggered by pushes to `main`, `v*` tags, Pull Requests and manual Run workflow actions.

Using Ubuntu 24.04 and pinned NDK `26.3.11579264`, it:

1. Checks out the repository and talloc submodule sources.
2. Downloads, verifies and statically builds pinned dependencies.
3. Builds ARM64 PDN and loader, checking dynamic dependencies and host paths.
4. Packages binaries, licenses, documentation and corresponding source, generating SHA256.
5. Builds the engine AAR, runs JVM tests and the coverage gate, compares its PDN/loader bytes with the original ELF files, and updates attachment checksums.
6. Uploads Actions artifacts with a 30-day retention period.
7. Creates a prerelease draft for the matching version when a `main` push contains a new version. Existing releases or drafts are skipped. Version-tag pushes may also create a draft, but the tag must match the code version.

Pull Requests and manual builds produce artifacts only. Draft creation runs on `main` or version-tag pushes; public publication waits for device acceptance. Linux runner checks do not replace Android device acceptance tests.

Development artifacts named `pdn-android-arm64-COMMIT` can be downloaded from successful [Actions runs](https://github.com/EMERLADD/proot-distro-nolib/actions/workflows/ci.yml). Downloads usually require a GitHub login and expire; these are not permanent Releases.

The original App's [Legacy pr Android App workflow](../.github/workflows/legacy-pr.yml) is manual-only and separate from standalone PDN's default build.

## Release files and corresponding source

The complete archive is `proot-distro-nolib-v0.6.6-android-arm64.tar.gz`. It contains:

| File | Contents |
| --- | --- |
| `pdn`, `proot-distro-nolib`, `proot-loader` | ARM64 executables and optional loader; the first two are identical |
| `jniLibs/arm64-v8a/` | `libpdn.so` and `libproot-loader.so`, matching the original ELF files |
| `SHA256SUMS` | Checksums for bundled executables and the two APK native files |
| `BUILD-INFO.txt` | Project version, source commit and talloc source commit |
| `README.md`, `README.en.md`, `CHANGELOG.md`, `docs/` | Project overview, changes and detailed guides |
| `LICENSE`, `licenses/` | License mapping and third-party license texts |
| `source.tar.gz` | Corresponding repository source, required talloc source and original archives of four dependencies |

Release attachments also include `pdn-engine-0.6.6.aar`, `pdn-engine-lite-0.6.6.aar`, `pdn`, `proot-loader`, `libpdn.so`, `libproot-loader.so` and the complete archive. External `SHA256SUMS` covers both AARs, original ELFs, the two `.so` files and the complete `.tar.gz`:

```sh
sha256sum -c SHA256SUMS
```

Download all files named in the same version's manifest into one directory first. Local and CI builds may differ byte-for-byte; use each build's own checksums rather than mixing artifacts.

The source archive can be extracted and built with `make` using the NDK toolchain above. It includes Mbed TLS, curl, libarchive and zlib source archives; their checksums are still verified. The Android SDK and NDK themselves are not included.

Deliver matching ELF, `.so`, AAR and loader files, refreshing unversioned aliases. Check that both `pdn version` and `pdn --version` report the current PDN version; the PRoot base version is maintained separately. Redistribution must retain corresponding source and license materials.

## Origins and licenses

- [oonid/pr](https://github.com/oonid/pr): project base, Android PRoot adaptations and original App/CLI.
- [PRoot](https://github.com/proot-me/proot), [Termux PRoot](https://github.com/termux/proot): engine and Android changes, retaining GPL-2.0-or-later declarations.
- [Termux proot-distro](https://github.com/termux/proot-distro): usage and management reference, not a runtime dependency.
- [talloc / Samba](https://www.samba.org/), [curl](https://curl.se/), [Mbed TLS](https://github.com/Mbed-TLS/mbedtls), [libarchive](https://www.libarchive.org/), [zlib](https://zlib.net/): build components. talloc sources declare LGPL-3.0-or-later; other license texts are bundled.

The entire project is not declared MIT. See [LICENSE](../LICENSE) and individual source declarations for scope. `nolib` means independence from Termux dynamic libraries at runtime, without removing upstream origins, copyrights or contributions.

## Device acceptance before publication

CI creates draft Releases. Before publishing, verify the exact candidate artifacts in four independent Android App builds: AAR Debug, AAR Release with R8, direct SO Debug, and direct SO Release with R8. Confirm actual class renaming, signatures, manifests and embedded native bytes, then exercise initialization, installation, commands, events and PTY on Android. Linux cross-compilation does not replace this gate. Release test Apps use a Debug test key and are not production-signed. Publish the draft only after all four pass.
