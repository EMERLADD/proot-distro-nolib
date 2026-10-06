# proot-distro-nolib engine

Current project version: **0.3.0**, based on **PRoot 5.4.0-pr**.
`--version`, `-V`, and `--about` display the slanted NoLib logo and the project
version on separate lines, followed by the base version and original copyright
and license information.

Version 0.2.0 adds a native local-rootfs frontend to the existing engine.
The same executable is built as `pdn` and `proot-distro-nolib`; either supports
`install`, `login`, `list` (alias `ls`), `help`, and `version`. No host Python, Bash, BusyBox, or app
package name is required. Android system libc/libdl are still required.

## Alpine installation

```sh
export PDN_ROOTFS_DIR=/your/private/linux
pdn install alpine
pdn login alpine
```

The default directory remains `$HOME/.local/share/pdn/rootfs`. Commands and
`alpine` ignore ASCII case. Installation downloads the fixed ARM64 Alpine 3.24.2
Mini Root Filesystem from Tsinghua TUNA over verified HTTPS, checks its exact
size and compiled-in SHA256, extracts it, and configures the v3.24 TUNA main
and community apk repositories. DNS defaults to 223.5.5.5 and 1.1.1.1; these
can be edited in the installed rootfs's `etc/resolv.conf` for your network.

A previously downloaded copy of the same official archive can be used offline:

```sh
pdn install alpine --archive /path/to/alpine-minirootfs-3.24.2-aarch64.tar.gz
```

Local archives are copied into staging before verification. This is not a
universal archive importer: other versions and modified archives fail the check.

Existing Alpine directories, symlinks and files (including case variants) are
never deliberately replaced. An advisory lock excludes simultaneous installs
through pdn. Extraction occurs in a hidden temporary directory beneath the
rootfs parent and is renamed to `alpine` only after successful initialization.
Normal errors, Ctrl+C and SIGTERM clean up staging. SIGKILL or power loss can
leave `.pdn-alpine-*` directories; after confirming no install is active, these
may be removed manually. They are not listed as installed distributions.

Extraction rejects absolute/traversing entry paths, writes through symlinks,
and unsupported device/FIFO entries. Ordinary permissions, sticky bits and
symlinks are preserved; setuid/setgid bits and host ownership are not restored.
An interrupted installation never becomes a completed rootfs.

TLS uses Android's `/system/etc/security/cacerts`. Set `PDN_CA_BUNDLE` to a PEM
CA file if your host needs another trust source. Certificate and hostname
verification remain enabled. Host proxy environment variables follow libcurl's
behavior. No Android package name or service request is needed.

Only Alpine installation is included. Mirror racing, resumable downloads and
other distro installers remain deferred. Existing local rootfs login still
works independently of installation.

## Local rootfs usage

For distributions other than the built-in Alpine installer, root filesystems
must already be extracted into app-accessible directories.
For example, with `/your/private/linux/Ubuntu/bin/sh` present:

```sh
export PDN_ROOTFS_DIR=/your/private/linux
pdn list
pdn login ubuntu
pdn LOGIN UBUNTU
pdn login --rootfs '/your/private/another rootfs'
pdn login ubuntu -- /usr/bin/id
pdn login ubuntu -- /bin/sh -c 'echo hello; uname -r'
pdn version
```

Without `PDN_ROOTFS_DIR`, named lookup uses `$HOME/.local/share/pdn/rootfs`.
There is no registration or copying step: each immediate child directory is
one rootfs; symlinks to existing rootfs directories are also accepted. Names
allow ASCII letters, digits, dots, underscores and hyphens, excluding leading
dots. ASCII command and name matching ignores case. Paths, environment variable
names and guest command arguments retain their exact spelling. If two entries
differ only in case, login reports ambiguity; use `--rootfs` to select one.
`list` lists local directories; it does not validate or download distributions.

Login enables the existing `--link2symlink` and `-L` compatibility options,
including support for apk temporary-file linking on Android. It starts as fake
root, binds `/dev`, `/proc`, `/sys`, and uses the existing
fake kernel release `6.17.0-pr`. It enters `/root` when available, otherwise `/`,
and selects guest `/bin/bash` if executable, falling back to `/bin/sh`.
Explicit commands start in `/` and preserve argument boundaries via guest
`/bin/sh` and `exec "$@"`. Shell expressions require explicit `/bin/sh -c` as
shown above. Standard input/output and the guest exit status are preserved.
Background guest processes are terminated when the primary command exits.

The frontend sets guest HOME/USER/LOGNAME/PATH/SHELL/TMPDIR and removes host
LD_PRELOAD, LD_LIBRARY_PATH, ENV and BASH_ENV. Other variables are inherited;
this is not an environment isolation or security boundary. The guest needs
its own `/bin/sh`, libraries and normal rootfs directories such as `/tmp`.
It does not overwrite DNS, account files or package-manager configuration.

For loader temporary files, login uses nonempty `PROOT_TMP_DIR`, then `TMPDIR`;
otherwise it creates `.pdn-tmp` within the selected rootfs with mode 0700.
Explicit temporary directories must already exist and be writable.
`PROOT_LOADER` remains available for hosts needing the external loader;
`PROOT_NO_SECCOMP` defaults to 1 for login and can be explicitly overridden.
Paths come from the caller or filesystem, never from a hardcoded app package.
An Android service integration would need the host app's actual interface;
a package name alone does not provide such an interface.

The legacy engine interface remains available as `pdn proot [options] [command]`
or `proot-distro-nolib [options] [command]`. Bare `pdn` and `pdn --help` show
manager help. `pdn proot --help` shows the engine's options.

Automatic mirror selection and installers for other distributions are deferred. This release targets ARM64 Android hosts that permit the
existing engine to run; it does not add other operating-system/CPU support.

## Build in this repository

```sh
make
make test
make help
make clean
```

The default target builds ARM64 for Android API 24 and later. It uses Clang,
LLVM binary tools, Make, a POSIX build shell, curl, tar, and an Android NDK
sysroot; tests also require Python 3
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

- `pdn` and `proot-distro-nolib`: identical stripped frontend/engine binaries,
  linked only to Android's `libc.so`
  and `libdl.so`, using `/system/bin/linker64`. Installer components are static.
- `proot-loader`: optional external ARM64 loader; the engine also contains its
  embedded loaders.
- `proot-distro-nolib.debug`: unstripped executable for investigation.
- `SHA256SUMS`: checksums of the engine and external loader.
- `dynamic.txt`: recorded ELF dynamic-section inspection.
- `licenses/`: license texts for the statically linked installer components.

The build rejects release binaries containing RPATH/RUNPATH, unexpected dynamic
dependencies, or residual Termux/app-private/build-home paths. The original
binary in Download is not overwritten.

## Runtime behavior

For direct legacy engine calls, temporary directory selection is:

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


The v0.2.0 frontend tests use a minimal BusyBox guest rootfs and cover named and
explicit-path login, case matching and ambiguity, default directory lookup,
argument quoting, stdin, shell selection, guest writes, exit codes, temp paths,
relocation, external loader and input errors. They do not establish that every
Ubuntu/Debian/Arch/Alpine/Kali release or package manager has been tested.

Version 0.2.0 passed 15 engine and 13 frontend tests. LLVM coverage of the new
`cli/pdn.c` frontend measured 98.40% lines and 88.61% branches; this does not
represent whole-engine coverage.


## Installer build dependencies

The first build downloads checksum-pinned official source releases for curl
8.22.0, Mbed TLS 3.6.7, libarchive 3.8.9 and zlib 1.3.2. They are compiled with
the Android sysroot into static archives under `build/proot-distro-nolib/deps`.
Subsequent builds reuse them. `make clean` also clears this cache; use it when
changing the dependency toolchain or build settings. Build tools may come from
any suitable host; no host curl, tar, Python or TLS shared library is needed at
runtime. Include the output `licenses/` directory when redistributing binaries.

Pinned rootfs source:
https://mirrors.tuna.tsinghua.edu.cn/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz

SHA256: `9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773`


Version 0.3.0 validation passed 39 tests (15 engine, 14 login/list, 10 installer).
LLVM line coverage was 98.48% for the frontend and 95.92% for the installer;
these are module figures, not whole-engine or third-party-library coverage.
A real TUNA download and a verified local-archive install both produced working
Alpine 3.24.2 guests. The default login successfully ran `apk update`, installed
`tree` from TUNA and executed it in a minimal host environment.

The standalone build enables `PDN_WITH_INSTALL=1`. The original engine build
remains available without that option and does not require the installer
libraries. On 2026-10-06, the user confirmed that the v0.3.0 release worked
in their MT Manager setup.
