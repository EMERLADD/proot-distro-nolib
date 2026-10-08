# proot-distro-nolib engine

Current project version: **0.6.1**, based on **PRoot 5.4.0-pr**.
`--version`, `-V`, and `--about` display the slanted NoLib logo and the project
version on separate lines, followed by the base version and original copyright
and license information.

Version 0.2.0 adds a native local-rootfs frontend to the existing engine.
The same executable is built as `pdn` and `proot-distro-nolib`; either supports
`install`, `mirrors`, `login`, `exec`, `config`, `backup`, `restore`, `list` (alias `ls`), `uninstall` (alias `remove`), `help`, and `version`. No host Python, Bash, BusyBox, or app
package name is required. Android system libc/libdl are still required.

## Installing a distribution

```sh
export PDN_ROOTFS_DIR=/your/private/linux
pdn list --available
pdn install alpine
pdn install ubuntu
pdn install debian
pdn install arch
pdn login ubuntu
```

The default directory remains `$HOME/.local/share/pdn/rootfs`. Commands and
names ignore ASCII case, including `pdn INSTALL UBUNTU`. All downloads are
ARM64/AArch64 and have a fixed size and SHA256 compiled into the executable.

### Directory errors in Android shell

The current working directory does not select the rootfs location. When
`HOME=/`, the default location is under `/.local/share/pdn/rootfs`, which
may not be writable. Set `PDN_ROOTFS_DIR` to a location writable by the
current user. Installation creates this parent directory itself; do not
pre-create the distribution directory such as `linux/alpine`.

An explicitly supplied `PROOT_TMP_DIR` or `TMPDIR` must already exist and
be writable and searchable. Login does not create those explicit paths.
When neither variable is set, it creates `.pdn-tmp` inside the selected
rootfs. Directory failures report the selected path, source variable,
system error and errno; missing temporary directories include a `mkdir -p`
hint. Creating a rootfs directory does not create a separate temporary
directory.

For an Android shell that can write to `/data/local/tmp`, for example:

```sh
export PDN_ROOTFS_DIR=/data/local/tmp/pdn-test/linux
export PROOT_TMP_DIR=/data/local/tmp/pdn-test/tmp
mkdir -p "$PROOT_TMP_DIR"
pdn install alpine
pdn login alpine
```

| Name | Pinned base | Compressed size | Rootfs sources in fallback order |
| --- | --- | --- | --- |
| `alpine` | Alpine 3.24.2 | 3.8 MiB | tuna, ustc, nju, official, dotsrc |
| `ubuntu` | Ubuntu Base 24.04.5 LTS (noble) | 28.5 MiB | tuna, ustc, official |
| `debian` | Debian 13 trixie slim, debuerreotype 20261005 | 28.8 MiB | official, github |
| `arch` | Arch Linux ARM 2026.08 | 790.9 MiB | tuna, ustc, nju, official |

`arch` is Arch Linux ARM, not the x86-64 Arch Linux distribution. Its full
upstream filesystem is about 2 GiB unpacked and includes hardware-related
packages. Allow several GiB of free space for the archive, rootfs and updates.
Debian's two routes retrieve the same immutable upstream GitHub artifact;
they are not independent domestic mirrors. Rootfs provenance and checksums
are recorded in [the source catalogue](pdn-rootfs-sources.md).

```sh
pdn mirrors
pdn mirrors ubuntu
pdn install ubuntu --mirror ustc
pdn install DEBIAN --mirror OFFICIAL
pdn install arch --archive /path/to/ArchLinuxARM-2026.08-aarch64-rootfs.tar.gz
```

Network, HTTP, size and SHA256 errors reject a download and try the next
source with a fresh file. Manual `--mirror` selection tries only that source.
All sources for one distribution must supply identical pinned content.
Ctrl+C/SIGTERM stop the operation, without switching to another source.
This is fixed-order fallback, not automatic speed ranking or resumable transfer.

Connections time out after 8 seconds; a transfer below 1 KiB/s for 30 seconds
is stopped. Total transfer limits are 90 seconds for Alpine, 10 minutes for
Ubuntu/Debian and 30 minutes for Arch. If a dated archive is removed upstream,
update the catalogue; the installer will not silently accept a new checksum.

`--archive PATH` accepts only the exact pinned archive for that distribution
and copies it into staging before verification. It cannot be combined with
`--mirror`. Local installation uses the first listed source's package settings.
It is not a general importer for arbitrary rootfs archives.

Installation creates DNS configuration with 223.5.5.5 and 1.1.1.1. Alpine's apk
repositories and Ubuntu's apt repository follow the successful rootfs mirror.
Debian uses official Debian and Debian security repositories. Ubuntu/Debian
seed a CA bundle from Android's system certificates (or `PDN_CA_BUNDLE`) when
one is absent, and configure apt to use it explicitly with the root sandbox
user for PRoot compatibility. Debian's container-specific `docker-clean` apt
hooks are removed. Package signature verification stays enabled.

Arch writes a pacman mirror list with the selected mirror first and remaining
sources as fallback, keeps package signature verification, and disables the
pacman filesystem/syscall sandbox mechanisms unavailable inside PRoot. It runs
`pacman-key --init` and `pacman-key --populate archlinuxarm` inside the staged
rootfs before publishing the installation. No host Bash or GnuPG is required.
Without a supplied temporary directory, key initialization uses the rootfs
parent for shorter Unix socket paths. For unusually long host paths, set
`PROOT_TMP_DIR` to a short writable private directory. Upgrade Arch with
`pacman -Syu`; this installer supplies a pinned starting point for a rolling
release, not a permanently frozen package repository.

Existing entries, including files, symlinks and case variants, are never
deliberately replaced. An advisory lock excludes simultaneous installation
and uninstall operations. Extraction uses `.pdn-NAME-XXXXXX` staging under
the rootfs parent; the final name appears only after configuration and any
key initialization succeed. Normal errors, Ctrl+C and SIGTERM clean staging.
SIGKILL or power loss can leave hidden staging directories, removable after
confirming that no installation is running.

Extraction rejects absolute/traversing entry paths, writes through symlinks,
and device/FIFO entries. Hardlinks become relative symlink aliases because
Android app domains can prohibit native hardlink creation. Ordinary file
permissions and sticky bits are preserved; directories receive owner rwx
access for rootless operation, while setuid/setgid bits and host ownership are
not restored. A DNS symlink supplied by a distribution is replaced without
following its target. Unpacked data is bounded at 512 MiB for Alpine, 1 GiB for
Ubuntu/Debian and 4 GiB for Arch; entries and individual file sizes are bounded.

HTTPS certificate and hostname verification remain enabled. Downloads use
Android's `/system/etc/security/cacerts`; `PDN_CA_BUNDLE` optionally supplies a
PEM trust file. Host proxy settings follow libcurl behavior. No Android package
name or service request is needed, and existing installed systems are unchanged.

## Uninstalling a local rootfs

```sh
pdn uninstall alpine
pdn remove DEBIAN
pdn uninstall ubuntu --yes
```

Version 0.3.2 removes a named immediate child of `PDN_ROOTFS_DIR`, or the default
`$HOME/.local/share/pdn/rootfs`. This also supports manually extracted systems,
not just Alpine installed by pdn. The resolved path is displayed before removal.
Only `y` or `yes` (case-insensitive) confirms the prompt; other input or EOF
cancels with exit status 1. `--yes` / `-y` skips confirmation for callers that
already obtained the user's approval. Success returns 0; invalid input, locks
and removal errors return 2.

Removal deletes the whole rootfs, including software and user files. There is
no undo. Root symlinks, ambiguous case variants, non-directories, paths such as
`../debian`, and `/` as the rootfs parent are rejected. There is no arbitrary
`--rootfs` removal option. Symlinks inside the rootfs are unlinked without
traversing their targets. Other rootfs directories remain in place. Traversal
rejects directories on another filesystem and grants owner permissions to
readable directories when necessary; inaccessible directories can cause a
partial failure that is reported explicitly.

Install and uninstall share an exclusive advisory lock. New `pdn login`
sessions hold a shared root-directory lock, so uninstall refuses an active
session and new logins are blocked during confirmation/removal. Exit sessions
first; the command does not kill them. Older pdn binaries, the raw `proot`
entry point, and unrelated host programs do not participate in these locks:
stop them before uninstalling. This is not isolation against hostile host
renames or privileged bind mounts. Interruption or permission errors can leave
a partially deleted rootfs; correct the problem and repeat uninstall to finish.

## Local rootfs usage

For distributions outside the built-in catalogue, root filesystems
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

Since v0.5.0, `exec` runs an explicit command using the same environment and
session lock as `login`. It requires `-- COMMAND ARG...` and never falls back
to an interactive shell. Both commands accept repeatable `--bind` (`-b`)
options after the name or `--rootfs PATH`, before the command separator:

```sh
pdn login ubuntu --bind /sdcard:/mnt/shared
pdn exec ubuntu -- /usr/bin/id
pdn exec ubuntu -b '/sdcard/My Files:/mnt/shared' -- /bin/ls /mnt/shared
pdn exec --rootfs /your/private/linux/ubuntu -- /bin/sh -c 'echo hello; id'
```

Binding syntax is `HOST[:GUEST]`. The host must be an existing, app-accessible
regular file or directory. Relative host paths resolve from the caller's current
directory. The guest destination must be absolute; if omitted, it defaults to
the canonical host path. Quote paths containing spaces. Colons within paths
and PRoot's advanced `!` binding suffix are not supported by this frontend.
Explicit bindings follow the default `/dev`, `/proc`, `/sys` bindings.
Bindings are writable within the host app's permissions: guest writes affect
the original host files. They apply only to the current invocation and are
not saved for future sessions. This does not grant Android storage permission.

Since v0.4.1 fake-identity sessions start with an empty virtual supplementary
group list, so Android app groups are not exposed by `groups` or `id`. Guest
`setgroups` updates a private list, inherited across fork/exec, while the real
Android supplementary groups remain unchanged for host file/network access.
This applies to existing rootfs without rewriting `/etc/group`; replace the
binary and start a new login session. The native ARM64 path is regression-tested.

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

Automatic mirror speed ranking and additional distro installers are deferred. This release targets ARM64 Android hosts that permit the
existing engine to run; it does not add other operating-system/CPU support.

## Saved startup settings and users

```sh
pdn config ubuntu --bind /sdcard:/mnt/shared --work-dir /root --env LANG=C.UTF-8
pdn config ubuntu --show
pdn login ubuntu
pdn exec ubuntu --work-dir /tmp --env EXAMPLE='two words' -- /usr/bin/env
pdn login ubuntu --user root
pdn login ubuntu --no-config
pdn config ubuntu --clear
```

`config NAME [options]` replaces the entire saved configuration. With no options
or `--show`, it prints JSON; `--clear` removes it. The same `--rootfs PATH`
selector as login is accepted. Configuration is stored as bounded, non-executable
data in the rootfs `.pdn-config`, with mode 0600 and atomic replacement. Exit all
sessions before writing or clearing settings. Invalid files fail closed; use
`--no-config` to start a recovery session. Saved settings are removed with the
rootfs on uninstall. Configuration can contain environment values; `--show`
prints those values as supplied.

Supported startup options for config, login and exec:

| Option | Behavior |
| --- | --- |
| `--bind`, `-b HOST[:GUEST]` | Repeatable; canonical host paths saved. Invocation binds follow defaults. |
| `--user`, `-u USER[:GID]` | Guest username or numeric UID, optionally with numeric GID. |
| `--work-dir`, `-w /PATH` | Absolute guest initial directory, for both interactive and explicit commands. |
| `--env`, `-e KEY=VALUE` | Repeatable; exact values, last assignment to each key wins. |
| `--no-config` | Login/exec only: ignore all saved settings for this invocation. |

Invocation user, working directory and environment values override the saved
values. Bindings are appended, with later destinations taking precedence. A
missing saved bind source is an error; bypass with `--no-config` or replace the
configuration. Commands and option names ignore ASCII case; user names, paths,
environment keys and values retain exact case. Guest commands cannot be saved.

The default identity remains root. An explicitly selected username must exist
in the guest's regular `/etc/passwd`; neither the file nor its `etc` directory
may be a symlink. Numeric UIDs use matching passwd metadata when available;
otherwise HOME is `/`, SHELL is `/bin/sh`, USER/LOGNAME are the numeric ID and
GID defaults to that UID. An explicit numeric GID overrides it. This does not
create accounts or add supplementary group memberships. Identity is emulated
by PRoot and does not grant host permissions. Switching back to root is needed
for package management inside the guest.

Guest environment values are exported after the guest shell starts; they do not
reconfigure the host tracer. They may override HOME, USER and SHELL variables.
The chosen account's login shell still determines the executable shell.
An explicit work directory is resolved inside the guest after bindings are
applied; failure to enter it returns an error, without running the requested
command. Without an explicit directory, interactive login uses HOME (then `/`
as a fallback), while exec retains its existing `/` starting directory.

## Backing up and restoring

```sh
pdn backup ubuntu /sdcard/ubuntu.tar.gz
pdn restore ubuntu-copy /sdcard/ubuntu.tar.gz
pdn login ubuntu-copy
```

Backup requires a named real rootfs directory with no active pdn sessions.
It holds an exclusive root lock and the install/uninstall lock. Choose a new
output filename outside the source rootfs; existing files, symlinks and archive
names are never replaced. Gzip-compressed tar output is first written to a
private temporary file and published only on success. Host-side changes made
outside pdn are not covered by its advisory locks: keep the source quiescent.

Backups preserve regular file contents, ordinary file permissions, modification
times and symlinks. Internal PRoot hardlink-emulation links are made portable
and relocated on restore. Known hardlink backing paths outside the rootfs are
rejected instead of silently producing an incomplete portable backup. Raw
native hardlinks are copied as separate files; normal PRoot hardlink-emulation
links retain their shared data behavior. Files reached through symlinks and
host bind mounts are not copied into the archive.

Host-specific `.pdn-config*`, loader scratch `.pdn-tmp`, contents of `/dev`,
`/proc`, `/sys`, and sockets/FIFOs/device nodes are excluded. Restore does not
activate saved host settings from an input archive; configure new host paths
with `pdn config` after migration. Host ownership is not restored, setuid/setgid
bits are stripped, and directory owner rwx permission is ensured for rootless
operation and cleanup. This is a portable application rootfs backup, not a
privileged filesystem image.

Restore accepts uncompressed or gzip tar with the Linux rootfs directly at the
archive's top level. It works offline and performs no installation scripts,
package reconfiguration or catalogue checksum validation. Use archives you
intend to run as guest Linux. Destination names are checked case-insensitively;
a new name is required, so a failed restore cannot replace an existing system.
Only the configured rootfs parent is supported, not arbitrary destination paths.

Extraction occurs in a private staging directory, with traversal and symlink-write
protection. Archive hardlinks become relative aliases as in the installer.
Limits are 1,000,000 entries, 8 GiB per regular file and 64 GiB total declared
file data. Backup imposes the same limits and a maximum directory depth of 256.
Errors and handled SIGINT/SIGTERM remove partial output/staging; SIGKILL or power
loss can leave `.pdn-backup-*` or `.pdn-restore-*` scratch entries. Successful
publication requires the host's no-replace rename support; it fails instead of
falling back to overwriting a destination.

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


Version 0.3.1 passed 42 tests (15 engine, 14 login/list, 13 installer).
Installer line coverage is 96.44%. USTC and the official CDN were each used for
real installation and login, and the USTC-installed guest successfully updated
its apk indexes. Each candidate mirror's checksum file matched the pinned hash;
range downloads succeeded for USTC, NJU, the official CDN and dotsrc. TUNA had
one connection timeout during these probes, after successful use in v0.3.0.
Mirror availability varies by network; fallback does not imply a speed ranking.
