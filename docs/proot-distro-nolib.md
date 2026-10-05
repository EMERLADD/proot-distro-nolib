# proot-distro-nolib engine

Current project version: **0.1.1**, based on **PRoot 5.4.0-pr**.
`--version`, `-V`, and `--about` display the slanted NoLib logo and the project
version on separate lines, followed by the base version and original copyright
and license information.

This stage cleans the existing PRoot execution engine for use in independent
Android CLI hosts. Its interface remains `proot [options] [command]`.
Distribution management commands such as `pd install` and `pd login` are a
separate, still-to-be-planned stage.

## Build in this repository

```sh
make
make test
make help
make clean
```

The default target builds ARM64 for Android API 24 and later. It uses Clang,
LLVM binary tools, Make, and an Android NDK sysroot; tests also require Python 3
and an ARM64 Android host that permits PRoot tracing. Tests use the repository's
existing BusyBox binary as a guest fixture, not as a new release dependency.

The Makefile discovers the NDK under `ANDROID_SDK_ROOT/ndk`, falling back to
`ANDROID_HOME/ndk` and then `$HOME/android-sdk/ndk`. `ANDROID_NDK_HOME` or an
explicit `NDK_PATH` can select an installation:

```sh
make NDK_PATH=/path/to/android-ndk CC=clang
```

The compiler must run on the build host. On ARM64 Android, use a native Clang
with the NDK sysroot and compiler runtime; the NDK's Linux x86_64 Clang cannot
execute natively there. The build disables compiler-injected runtime search
paths when that compiler exposes the corresponding option.

The build copies the working source into a temporary directory under its output
directory and rebuilds talloc there. It does not modify vendor sources, replace
the existing source-tree executable, install packages, or change shell settings.
`make clean` removes only this repository's `build/proot-distro-nolib` directory.

## Outputs

All release files are under `build/proot-distro-nolib/arm64/`:

- `proot-distro-nolib`: stripped PRoot engine, linked only to Android's `libc.so`
  and `libdl.so`, using `/system/bin/linker64`.
- `proot-loader`: optional external ARM64 loader; the engine also contains its
  embedded loaders.
- `proot-distro-nolib.debug`: unstripped executable for investigation.
- `SHA256SUMS`: checksums of the engine and external loader.
- `dynamic.txt`: recorded ELF dynamic-section inspection.

The build rejects release binaries containing RPATH/RUNPATH, unexpected dynamic
dependencies, or residual Termux/app-private/build-home paths. The original
binary in Download is not overwritten.

## Runtime behavior

Temporary directory selection is now:

1. A nonempty `PROOT_TMP_DIR`.
2. A nonempty `TMPDIR`.
3. `/tmp`.

The selected directory must already exist and be writable by the host App.
Many Android hosts do not provide `/tmp`; set one of the environment variables
to a private directory in that host. An invalid explicitly selected directory
is reported rather than silently replaced.

SIGSYS diagnostics use PRoot's existing stderr logging at verbosity 1 or higher
(`-v 1` or `PROOT_VERBOSE=1`). They no longer write to a fixed App's cache.
The SIGSYS emulation handlers remain intact. Port-remapping messages refer to
PRoot rather than a particular host App. Upstream attribution and license
references remain in the source.

## Verification

The regression suite checks ELF dependencies, clean-environment startup,
temporary-directory precedence and failures, embedded and external loaders,
relocation into a directory with spaces, fake root/kernel values, guest file
creation, exit status, missing commands, IPv4/IPv6 port mapping, and quiet/verbose
SIGSYS handling using a guest-installed seccomp trap.

The initial cleanup passed all 15 tests. LLVM source coverage exercised all
8 added executable C lines in this cleanup (100% changed-line coverage); this
is not a claim of 100% coverage for the entire engine. The original binary was
reported working in MT Manager by the user. The rebuilt binary has passed the
local Android tests and still needs a repeat check in MT Manager.
