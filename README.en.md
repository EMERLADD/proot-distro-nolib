# proot-distro-nolib

[简体中文](README.md) | English

**A standalone Android Linux distribution manager based on [pr](https://github.com/oonid/pr), with a role similar to Termux's proot-distro, without depending on the Termux environment or its dynamic libraries.**

For Android apps that provide a terminal or command execution: put `pdn` in a directory where the app can execute it, then install, log into, and manage Linux with simple commands. No prior Termux installation is needed, and the host does not need to provide Bash, Python, curl, or tar.

Current version: **v0.6.6 · ARM64 Android · early testing release**.

> I have used it in **MT Manager**, where the current features work. This is my first tool of this kind. Please report problems and suggestions through [Issues](https://github.com/EMERLADD/proot-distro-nolib/issues) to help improve it.
>
> MT Manager is a tested host environment. This does not mean every Android app or device has been tested.

## Contents

- [Three tested integration paths](#three-tested-integration-paths)
- [What this is](#what-this-is)
- [Requirements](#requirements)
- [Download and installation](#download-and-installation)
- [Shizuku and Android shell](#shizuku-and-android-shell)
- [ARM64 download sources](#arm64-download-sources)
- [Embedding in an Android app](#embedding-in-an-android-app)
- [Quick start](#quick-start)
- [Local builds](#local-builds)
- [GitHub automated builds](#github-automated-builds)
- [Release materials and source](#release-materials-and-source)
- [Origins and acknowledgments](#origins-and-acknowledgments)
- [Feedback](#feedback)

## Three tested integration paths

On 2026-10-09, device tests used the original files from GitHub Release **v0.6.4**:

| Integration | Actual runtime | Result |
| --- | --- | --- |
| Raw ELF | Android shell through rish / Shizuku, UID 2000 | **28/28 passed** |
| AAR | Independent app importing only the AAR and Kotlin standard library | **19/19 passed** |
| Direct `.so` packaging | Independent app launching the ELF directly, reading events and integrating PTY itself | **24/24 passed** |

All three paths verified Alpine installation, login/execution, and startup error classification. Both APKs also verified initialization, event callbacks, workspaces, and terminal interaction. APK tests ran in ordinary `untrusted_app` processes on Android 14 (SDK 34), with targetSdk 35; packaged PDN/loader files matched the Release originals byte for byte. See [test scope and methods](docs/pdn-error-testing.md), the [AAR example](examples/aar-probe/README.md), and the [.so example](examples/so-probe/README.md).

## What this is

As a file, `pdn` is an **ARM64 Android ELF executable**. As a tool, it is a **command-line Linux distribution manager** combining the PRoot engine and common management operations in one program.

- **Independent of Termux**: its only runtime dynamic dependencies are Android's `libc.so` and `libdl.so`; download, TLS, extraction, and related components are statically linked.
- **Simple commands**: install with `pdn install ubuntu`, log in with `pdn login ubuntu`. Names and commands accept ASCII case variations.
- **One program to manage Linux**: command execution, directory mounts, saved default configuration, guest account selection, backup/restore, and removal.
- **Different host apps**: paths come from environment variables or command arguments, without a hardcoded app package. Terminal input and app buttons can call the same program.
- **Android adaptations from pr**: retains its PRoot, loader, and Android syscall adaptations, along with the original copyrights and licenses.

`nolib` means no dependency on Termux dynamic libraries, not no libraries at all. It does not include a graphical terminal interface; the host app provides the interactive terminal. The repository retains pr's Android app and Rust CLI sources. They are not prerequisites for standalone `pdn`, and their presence does not mean all their features have been ported to `pdn`.

## Requirements

- ARM64 / AArch64 Android. The build targets Android API 24 and newer; actual availability also depends on host permissions and system restrictions.
- The host must allow program execution, the process tracing and syscalls needed by PRoot, and access to the Linux storage directory.
- Store the Linux rootfs in a host-accessible directory supporting Unix permissions and symbolic links, such as an app-private directory or Android shell’s `/data/local/tmp`. `/sdcard` is suitable for downloads, backups, and shared files, but not for directly extracting a rootfs.
- The host needs network permission and appropriate permissions for shared storage.

Using it wherever a terminal is available is the intended workflow, not a guarantee of bypassing Android permissions. For apps with execution restrictions, developers may need to deploy the programs through the APK's `nativeLibraryDir` and set `PROOT_LOADER`. See [Android loader notes](docs/targetsdk35-compatibility.md). PRoot is not a security isolation boundary and does not provide real root privileges.

## Download and installation

Download standalone **`pdn`** from [Releases](https://github.com/EMERLADD/proot-distro-nolib/releases), or download the complete release archive with source and licensing materials. `pdn` is a ready-to-run executable; extracting a rootfs or compiling it yourself is not required first. Each release includes SHA256 checksums for the raw files and complete archive.

Development builds are also available: open the [Build pdn workflow](https://github.com/EMERLADD/proot-distro-nolib/actions/workflows/ci.yml), select a successful run, and download `pdn-android-arm64-COMMIT` under **Artifacts**. Artifact downloads usually require signing into GitHub. Automated artifacts have a retention period and are not permanent Releases.

Extract the downloaded artifact, then extract its `proot-distro-nolib-v0.6.6-android-arm64.tar.gz`. It contains:

- `pdn`: the recommended command name.
- `proot-distro-nolib`: identical to `pdn`; choose either one, without putting both in bin.
- `proot-loader`: available for hosts requiring an external loader; ordinary setups can start with the embedded loader.
- `jniLibs/arm64-v8a/libpdn.so` and `libproot-loader.so`: for APK packaging, identical to `pdn` and `proot-loader`, respectively.

Put `pdn` in a bin directory where the host permits execution, then run there:

```sh
chmod 755 pdn
./pdn version
```

If that bin directory is already in `PATH`, simply run `pdn`. Shared storage may prohibit execution; `chmod` alone cannot change that restriction.

### MT Manager example

Download `pdn` from a Release and copy it with MT Manager to:

```text
/data/user/0/bin.mt.plus/files/term/bin/pdn
```

This is MT Manager's own terminal bin directory. Assuming the download is at `/sdcard/Download/pdn`, you can also run this in **MT Manager's terminal**:

```sh
cp /sdcard/Download/pdn /data/user/0/bin.mt.plus/files/term/bin/pdn
chmod 755 /data/user/0/bin.mt.plus/files/term/bin/pdn
pdn version
pdn install alpine
pdn login alpine
```

Change the source path in `cp` if your download is elsewhere. To update, exit the old Linux session, replace the program, and grant execution permission again. Existing Linux installations do not need reinstalling. If `pdn` cannot be found, run `/data/user/0/bin.mt.plus/files/term/bin/pdn version` with the full path and check the terminal's PATH.

This path is an MT Manager example, not hardcoded into `pdn`. Other apps should use their own executable directory. The raw `pdn` file is provided for convenient download; redistribution should also retain the corresponding source and licenses from the complete archive.

## Shizuku and Android shell

PDN can run from an Android shell with the required execution, directory access, and process tracing permissions; MT Manager is not required. When Android shell privileges are needed, first enter a real shell through Shizuku/rish or ADB, then start Linux. The root identity displayed inside Ubuntu remains simulated.

**If rish already works, run it and skip initial setup.** Prepare the original rish client's private directory in the host app's original terminal. After entering the UID 2000 Android shell, do not assume it can access app-private directories. Keep the Shizuku-authorized host in the foreground throughout use. In the tested environment, moving it to the background disconnected it even with the battery setting set to “Unrestricted.”

After deploying matching PDN and loader versions, setting rootfs and temporary directories, and installing Ubuntu in the Android shell, log in with Android runtime paths mounted:

```sh
./pdn login ubuntu --bind /system --bind /apex --bind /linkerconfig/ld.config.txt
```

Inside Ubuntu, give the long command a name of your choice, for example:

```sh
rish() {
    /system/bin/sh -c 'export PATH=/system/bin:/system/xbin; exec /system/bin/sh "$@"' -- "$@"
}
rish -c 'getprop ro.build.version.sdk'
rish -c 'cmd package path android'
```

Here, `rish` is a custom Bash function invoking Android's shell with already inherited permissions. It does not reconnect to Shizuku. Nested startup of the original client inside the shell did not work in testing; these are distinct mechanisms.

On 2026-10-09, this flow was tested and successfully reproduced in MT Manager: a dedicated Android setting was written inside Ubuntu, read back from the Android shell after exiting Ubuntu, and then deleted. See the [Android shell tutorial](docs/pdn-shizuku-android-shell.en.md) for directory layout, installation, persistent functions, setting verification, cleanup, and troubleshooting. MT is the example host; not all hosts or systems have been verified.

## ARM64 download sources

All four distributions include an `official` source. Alpine, Ubuntu, and Arch try Chinese mirrors first by default, then fall back to the official source. Debian retains only one `official` source, downloading the official Docker rootfs build directly. GitHub repository `/raw/` URLs redirect to the same file and are not treated as a second fallback source.

| Distribution | Official source |
| --- | --- |
| Alpine | [Alpine CDN](https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/) |
| Ubuntu | [Ubuntu Base](https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/) |
| Debian | [Official debuerreotype Docker builds](https://github.com/debuerreotype/docker-debian-artifacts) |
| Arch | [Official Arch Linux ARM mirror](https://fl.us.mirror.archlinuxarm.org/os/multi/) |

```sh
pdn mirrors alpine
pdn install alpine --mirror official
```

`--mirror official` uses only that source. All sources download the same pinned ARM64 version, checking the expected size and SHA256 before extraction; there is no fallback to an unverified `latest` archive. In Kotlin, call `pdn.install("alpine", mirror = "official")`.

## Embedding in an Android app

Java/Kotlin can use `PdnRuntime` to prepare installation, execution, and other operations, then `PdnOperations` to receive stages, progress, error suggestions, and final results. A GUI does not need to parse CLI output; Linux stdout/stderr stay separate. See [Java/Kotlin event API and AAR structure](docs/pdn-events.md).

v0.6.4 adds specific classifications for PRoot, loader, initial guest shell, and login shell startup failures. Nonzero exits and signal termination of Linux commands that have already started continue to use `guest_exit`; Java/Kotlin obtains the reason and suggestion from the existing `PdnResult`.

v0.6.3 classifies directory, lock, network, checksum, archive, configuration, removal, and Java host failures at their specific failure points, with corresponding suggestions. See [error classifications and individual verification](docs/pdn-error-testing.md) for how each test was triggered, where fault injection was used, and what has not been tested.

**The AAR has been tested successfully**: a new independent Android app importing only the generated AAR and Kotlin standard library entered Alpine and successfully ran `apk add nano` in its interactive terminal. See the [AAR verification app](examples/aar-probe/README.md) for the independent project and build instructions.

0.6.4 passed device acceptance in ordinary app processes: 19/19 for AAR integration and 24/24 for direct `.so` packaging. Initialization, official archive installation, command execution, event callbacks, PTY interaction, and startup error classification all passed; online installation from the official source through the GUI also succeeded. See the [.so verification app](examples/so-probe/README.md) and [APK integration tests](docs/pdn-error-testing.md#064-apk-接入实测) for the environment and coverage.

Each Release provides `pdn-engine-VERSION.aar`, `pdn-engine-lite-VERSION.aar`, `libpdn.so`, `libproot-loader.so`, and raw ELF files `pdn` and `proot-loader`. The AAR is a non-instrumented Debug engine build containing the wrapper API and ARM64 native programs; the host must provide the Kotlin standard library. Alternatively, put the two `.so` files in the app's `jniLibs/arm64-v8a/` and launch them as processes after Android extracts them to `nativeLibraryDir`. They are native executables, not a PDN JNI API loaded with `System.loadLibrary`.

Replace ELF, `.so`, and AAR files together when updating, to avoid continuing to use files from an old directory. Both `pdn version` and `pdn --version` should report the current PDN version. `Based on PRoot 5.4.0-pr` is the underlying PRoot version, maintained separately from the PDN version.

The 0.6.5 lightweight AAR passed **33/33** checks in an independent Java app's ordinary process, including asynchronous cancellation/timeouts, configuration and structured queries, two terminals, and GUI operations. See [0.6.5 AAR tests](docs/pdn-error-testing.md#065-aar-接口实测) for scope and coverage.

0.6.5 adds cancellable tasks with timeouts, immutable configuration, independent terminal sessions, structured distribution/mirror queries, and a lightweight AAR with only three native files. Starting with 0.6.6, every AAR contains only PDN, loader, and PTY JNI, retaining all terminal functionality. Legacy pr native components are packaged separately by the original app. See the [AAR API](docs/pdn-aar-api.md) for Java/Kotlin calls, AAR structure, and task lifecycles.

`PdnRuntime` provides `install("alpine")`, `login("alpine")`, `exec("alpine", listOf("/bin/echo", "hello"))`, and `remove("alpine")`. They return a `ProcessBuilder`; call `.start()` to launch it.

The example app includes an Alpine package installation panel: enter a package name or select a common package, then click Install. It updates the index automatically and can display installed packages and execution logs. The interface calls Linux's `apk` through the Kotlin `exec()` API.

Tested: `curl` was installed in Alpine through the GUI, then `curl -v https://example.com/` worked inside Alpine. GUI installation, Linux program execution, and HTTPS access all worked.

See [Android app integration](docs/android-embedding.md) for directory layout, Gradle packaging, Kotlin APIs, installation/execution, and PTY examples. When redistributing these files, also provide the corresponding source and licensing materials from the complete release archive.

## Quick start

The default Linux storage directory is `$HOME/.local/share/pdn/rootfs`. You can instead select a private directory accessible to the host:

```sh
export PDN_ROOTFS_DIR="$HOME/linux"
pdn list --available
pdn install alpine
pdn login alpine
```

Also supported:

```sh
pdn install Ubuntu
pdn install debian
pdn install arch
pdn ls
pdn login ubuntu
```

Exit Linux before running management operations. An existing rootfs can be placed at `$PDN_ROOTFS_DIR/NAME/`, or opened with `pdn login --rootfs /full/rootfs/path`, without reinstalling.

### Supported distributions and downloads

| Name | Pinned version | Architecture |
| --- | --- | --- |
| Alpine | 3.24.2 | ARM64 |
| Ubuntu Base | 24.04.5 LTS | ARM64 |
| Debian slim | 13 trixie, 20261005 | ARM64 |
| Arch Linux ARM | 2026.08 | ARM64 |

Downloads check a pinned size and SHA256. Some distributions have multiple Chinese and international sources, tried sequentially on failure. Latency-based ranking and resumable downloads are not implemented yet. Debian has only one official upstream endpoint. The Arch archive is about 791 MiB; reserve several GiB of space.

```sh
pdn mirrors ubuntu
pdn install ubuntu --mirror tuna
pdn install alpine --archive /path/to/matching-pinned-rootfs.tar.gz
```

`install --archive` still requires checksums matching the built-in version; it is not an arbitrary archive importer. See the [rootfs source catalog](docs/pdn-rootfs-sources.md) for sources and checksums.

### Execution, mounts, and default configuration

```sh
pdn exec ubuntu -- /usr/bin/id
pdn exec ubuntu -- /bin/sh -c 'echo hello; uname -r'
pdn login ubuntu --bind /sdcard:/mnt/shared
pdn config ubuntu --bind /sdcard:/mnt/shared --work-dir /root --env LANG=C.UTF-8
pdn login ubuntu
```

`--bind` / `-b` can be repeated. Guest modifications to mounted files directly modify host files. Temporary mounts apply only to the current session; mounts saved with `config` are loaded automatically on subsequent login and execution.

```sh
pdn config ubuntu --show
pdn login ubuntu --no-config
pdn config ubuntu --clear
pdn login ubuntu --user root --work-dir /tmp --env EXAMPLE='two words'
```

Each `config` save replaces the entire default configuration. Command-line account, working directory, and environment values override defaults; mounts are appended. `--user` selects an existing guest account name or numeric UID, optionally with a numeric GID. The default is simulated root; it does not create an account.

### Backup and restore

First exit sessions for that Linux installation:

```sh
pdn backup ubuntu /sdcard/ubuntu.tar.gz
pdn restore ubuntu-copy /sdcard/ubuntu.tar.gz
pdn login ubuntu-copy
```

The backup file must be outside the source rootfs, and restoration requires a new name. Neither operation overwrites an existing target. Backups preserve Linux file data and relocation relationships for PRoot internal links, excluding host startup configuration, temporary loaders, runtime directory contents, and special nodes. Reconfigure mount directories after changing apps.

Directory owner rwx permissions are ensured; host ownership and setuid/setgid are not restored. This is a rootfs backup intended for migration without root. See the [full manual](docs/proot-distro-nolib.md) for detailed boundaries, archive restrictions, and interruption handling.

### Command index

| Command | Purpose |
| --- | --- |
| `install` | Install a built-in distribution |
| `mirrors` | Show rootfs download sources |
| `list` / `ls` | Show installed systems; `--available` shows installable versions |
| `login` | Interactive login; also supports `-- COMMAND` |
| `exec` | Execute a specified guest command |
| `config` | Save, show, or clear default startup options |
| `backup` / `restore` | Back up and restore under a new name |
| `uninstall` / `remove` | Delete the system and all its data after confirmation; `--yes` skips confirmation |
| `version` / `help` | Show version or help |
| `proot` | Use underlying PRoot options directly |

## Local builds

Building standalone `pdn` does not require Java, Gradle, or Rust. It requires Git, Make, Clang/LLVM tools, curl, tar, xz/bzip2, pkg-config, and the Android NDK. The first build needs network access to download dependency sources with pinned checksums.

```sh
git clone https://github.com/EMERLADD/proot-distro-nolib.git
cd proot-distro-nolib
git submodule update --init --depth 1 vendor/samba
make NDK_PATH=/your/Android/NDK/directory
```

On a Linux x86_64 host, use the LLVM tools bundled with the NDK, for example:

```sh
export NDK_PATH="$HOME/Android/Sdk/ndk/26.3.11579264"
export PATH="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64/bin:$PATH"
make NDK_PATH="$NDK_PATH"
```

For local compilation on ARM64 Android, use Clang/LLVM tools that can run on Android together with the NDK sysroot; the Linux x86_64 compiler above cannot run directly. Build tools may come from Termux, but the resulting programs do not depend on Termux at runtime.

Outputs are in `build/proot-distro-nolib/arm64/`. The build checks ELF dynamic dependencies, RPATH, and leftover host paths. It does not install an app or modify the host bin directory.

```sh
make help
make test
make package
make clean
```

`make test` must run in an ARM64 Android environment that permits PRoot execution. It also needs Python 3 and the repository tests' BusyBox fixture. See the [changelog](CHANGELOG.md) for regression and coverage records. Core Ubuntu/Debian/Arch/Alpine flows have been verified; this does not establish compatibility with all devices.

`make package` builds first, then packages committed source and artifacts. Commit project files before packaging so the source corresponds to the current commit. Outputs are in `build/packages/`. Run `make clean` when changing compiler, NDK, or dependency build flags to avoid reusing old static libraries.

## GitHub automated builds

Workflow: [`.github/workflows/ci.yml`](.github/workflows/ci.yml). Pushes to `main`, `v*` tag pushes, Pull Requests, and manual **Run workflow** invocations trigger it.

The workflow uses Ubuntu 24.04 and pinned NDK `26.3.11579264`:

1. Fetch the repository and the talloc submodule source required for building.
2. Download, verify, and statically compile dependencies from pinned sources.
3. Build ARM64 `pdn` and loader, checking dynamic dependencies and host paths.
4. Package binaries, licenses, usage materials, and corresponding source; generate SHA256 checksums.
5. Build the engine AAR separately, compare its PDN and loader to the raw ELF files byte for byte, and update attachment checksums.
6. Upload an Actions artifact retained for 30 days.
7. When a `main` push contains an unpublished version, automatically create the corresponding `vVERSION` tag and prerelease. Already published versions are skipped; increment the patch version for the next release. `v*` tag pushes can also publish, but the tag must match the code version.

The Linux runner performs cross-compilation and artifact checks. **It is not counted as a passing Android runtime test.** Pull Requests and manual builds generate artifacts only; publication runs after `main` or version tag pushes.

To build only the engine, run `sh gradlew -PpdnEngineOnly=true :proot-engine:bundleDebugAar` in `android/`, without configuring GUI or terminal library modules. First build native PDN and loader as described above.

The original pr app build remains in [Legacy pr Android App](.github/workflows/legacy-pr.yml), triggered manually only. It is not part of standalone `pdn`'s default build flow.

## Release materials and source

The release archive contains:

| File | Content |
| --- | --- |
| `pdn`, `proot-distro-nolib`, `proot-loader` | ARM64 executables and optional loader |
| `jniLibs/arm64-v8a/` | APK-ready `libpdn.so` and matching `libproot-loader.so` |
| `SHA256SUMS` | Checksums for archive programs and the two APK native executable filenames |
| `BUILD-INFO.txt` | Project version, source commit, and talloc source commit |
| `README.md`, `README.en.md`, `CHANGELOG.md`, `docs/` | Project overview, changelog, and detailed usage documentation |
| `LICENSE`, `licenses/` | Project license mapping and third-party license texts |
| `source.tar.gz` | Corresponding repository source, required talloc source, and original source archives for four dependencies |

The release directory also provides separate `pdn-engine-VERSION.aar`, `pdn`, `proot-loader`, `libpdn.so`, and `libproot-loader.so` files. The external `SHA256SUMS` covers AARs, raw ELFs, both `.so` files, and the complete `.tar.gz`. Run `sha256sum -c SHA256SUMS` where available. The source archive can be extracted independently and built with `make` using the NDK toolchain above. All four dependency archives are included and their checksums are still verified during compilation. The Android SDK/NDK itself is not included.

When redistributing binaries, retain these materials, corresponding source, and third-party licenses rather than only a renamed program. The project inherits upstream licenses; it does not claim every component is MIT. See [LICENSE](LICENSE) and declarations in source files for the applicable scopes.

## Origins and acknowledgments

- [oonid/pr](https://github.com/oonid/pr): the project base, providing Android PRoot adaptations and the original app/CLI implementation.
- [PRoot](https://github.com/proot-me/proot) and [Termux PRoot](https://github.com/termux/proot): the PRoot engine and Android-related changes. Engine source in this repository retains its GPL-2.0-or-later declaration.
- [Termux proot-distro](https://github.com/termux/proot-distro): a reference for usage and distribution management ideas. Standalone `pdn` does not require installing it.
- [talloc / Samba](https://www.samba.org/), [curl](https://curl.se/), [Mbed TLS](https://github.com/Mbed-TLS/mbedtls), [libarchive](https://www.libarchive.org/), and [zlib](https://zlib.net/): components used in the build. talloc source files declare LGPL-3.0-or-later; other component licenses accompany the release archive.

`nolib` means runtime independence from Termux, without erasing code origins or upstream contributions. Original author copyrights, licenses, and base information in `pdn version` remain intact.

## Feedback

Please open an [Issue](https://github.com/EMERLADD/proot-distro-nolib/issues) with the host app, Android version, `pdn version`, reproduction commands, and error output. Do not include passwords, tokens, device serial numbers, or other private information.

Ideas, suggestions, and documentation improvements are welcome too. OCI/Docker image installation, cross-architecture emulation, automatic mirror latency testing, and background session management are not currently supported; further work will follow practical needs.
