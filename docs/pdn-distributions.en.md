# Distribution management

[简体中文](pdn-distributions.md) | English · [Back to README](../README.en.md)

## Contents

- [Directories and existing rootfs](#directories-and-existing-rootfs)
- [Installation and mirrors](#installation-and-mirrors)
- [Commands, binds and saved configuration](#commands-binds-and-saved-configuration)
- [Backup and restore](#backup-and-restore)
- [Command index](#command-index)

## Directories and existing rootfs

The default storage directory is `$HOME/.local/share/pdn/rootfs`. You can select a directory accessible to the host:

```sh
export PDN_ROOTFS_DIR="$HOME/linux"
pdn list --available
pdn install alpine
pdn login alpine
```

Android shell may set `HOME=/`. In that environment, select a directory such as `/data/local/tmp/pdn/linux` and explicitly set a writable `PROOT_TMP_DIR`. See the [Android shell tutorial](pdn-shizuku-android-shell.en.md).

The rootfs filesystem must support Unix permissions and symbolic links. Do not extract it directly onto `/sdcard`; use shared storage for archives, backups and shared files. Exit the relevant Linux session before management operations.

```sh
pdn install Ubuntu
pdn install debian
pdn install arch
pdn ls
pdn login ubuntu
```

Commands and distribution names accept ASCII case variations. Place an existing rootfs in `$PDN_ROOTFS_DIR/name/`, or log into its directory directly:

```sh
pdn login --rootfs /full/rootfs/path
```

## Installation and mirrors

| Name | Pinned version | Architecture |
| --- | --- | --- |
| Alpine | 3.24.2 | ARM64 |
| Ubuntu Base | 24.04.5 LTS | ARM64 |
| Debian slim | 13 trixie, 20261005 | ARM64 |
| Arch Linux ARM | 2026.08 | ARM64 |

All four distributions include an `official` source. Alpine, Ubuntu and Arch try Chinese mirrors first, then fall back to the official source. Debian has one official Docker rootfs source; GitHub `/raw/` redirects to the same file and is not an independent fallback.

| Distribution | Official source |
| --- | --- |
| Alpine | [Alpine CDN](https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/) |
| Ubuntu | [Ubuntu Base](https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/) |
| Debian | [debuerreotype official Docker builds](https://github.com/debuerreotype/docker-debian-artifacts) |
| Arch | [Arch Linux ARM mirror](https://fl.us.mirror.archlinuxarm.org/os/multi/) |

```sh
pdn mirrors alpine
pdn install alpine --mirror official
pdn mirrors ubuntu
pdn install ubuntu --mirror tuna
pdn install alpine --archive /path/to/matching-pinned-rootfs.tar.gz
```

An explicit mirror selects only that source. Downloads must match the pinned size and SHA256 before extraction; PDN does not switch to an unchecked `latest` archive. `install --archive` also requires the built-in version's checksums and does not import arbitrary archives. See the [rootfs source catalog](pdn-rootfs-sources.md).

Mirror latency ranking and resumed downloads are not implemented. The Arch archive is about 791 MiB; reserve several GiB for installation. Kotlin can prepare an operation with `pdn.install("alpine", mirror = "official")`; it returns a `ProcessBuilder` that must be started or passed to the asynchronous task API.

## Commands, binds and saved configuration

```sh
pdn exec ubuntu -- /usr/bin/id
pdn exec ubuntu -- /bin/sh -c 'echo hello; uname -r'
pdn login ubuntu --bind /sdcard:/mnt/shared
pdn config ubuntu --bind /sdcard:/mnt/shared --work-dir /root --env LANG=C.UTF-8
pdn login ubuntu
```

Repeat `--bind` / `-b` for multiple mounts. Writing a bound file in Linux changes the host file. Temporary mounts apply to the current session; saved mounts are loaded for later login and execution.

```sh
pdn config ubuntu --show
pdn login ubuntu --no-config
pdn config ubuntu --clear
pdn login ubuntu --user root --work-dir /tmp --env EXAMPLE='two words'
```

Each save replaces the complete default configuration. Command-line account, working directory and environment values override defaults; binds are appended. `--user` selects an existing guest account or numeric UID, optionally with a numeric GID, and does not create accounts. The default root identity is simulated by PRoot and does not increase actual Android privileges.

For App configuration and command execution, see [Android embedding](android-embedding.en.md) and the [AAR API](pdn-aar-api.en.md).

## Backup and restore

Exit the relevant Linux session first:

```sh
pdn backup ubuntu /sdcard/ubuntu.tar.gz
pdn restore ubuntu-copy /sdcard/ubuntu.tar.gz
pdn login ubuntu-copy
```

The backup must be outside its source rootfs. Restore requires a new name; neither operation overwrites an existing destination. Backups retain Linux data and PRoot internal link relationships, excluding host startup configuration, temporary loaders, runtime directory contents and special nodes. Configure mounts again when moving to another App.

Directory owner rwx permissions are added where necessary; host ownership and setuid/setgid are not restored. This is a rootfs backup for migration without real root. Archive restrictions, interruption handling and more options are in the [full manual](proot-distro-nolib.md).

## Command index

| Command | Purpose |
| --- | --- |
| `install` | Install a built-in distribution |
| `mirrors` | Show rootfs download sources |
| `list` / `ls` | Show installed systems; `--available` lists available versions |
| `login` | Interactive login, also supports `-- COMMAND` |
| `exec` | Execute a guest command |
| `config` | Save, show or clear default startup options |
| `backup` / `restore` | Back up and restore under a new name |
| `uninstall` / `remove` | Delete a system and its data after confirmation; `--yes` skips confirmation |
| `version` / `help` | Show version or help |
| `proot` | Use underlying PRoot options directly |

Structured queries support `list --json`, `list --available --json` and `mirrors NAME --json`. GUI hosts can use typed AAR queries and events instead of parsing human-readable terminal output.

## Installation aliases and metadata

```sh
pdn install alpine --name ai-python --mirror official
pdn install alpine --archive /path/alpine.tar.gz --name ai-node
pdn exec ai-python -- /bin/sh -c 'echo hello'
pdn list --json
pdn backup ai-python /path/ai-python.tar.gz
pdn restore ai-restored /path/ai-python.tar.gz
```

New installations store a stable ID and provenance in `.pdn-instance`. Legacy rootfs entries have JSON `instance: null`; queries are read only. Restore creates a fresh identity and retains known provenance, refusing existing names. Cloning and renaming are described below.

## Clone and rename

```sh
pdn clone ai-python ai-python-test
pdn rename ai-python-test workspace-python
pdn login workspace-python
```

Exit active source sessions first. Clone creates an independent rootfs, fresh ID and timestamp, following backup portability limits. It needs space for a temporary archive plus the target rootfs; devices, FIFOs, sockets, runtime directory contents and temporary config are excluded. Native hardlinks become independent files under the existing archive rules; PRoot .l2s backing relationships are migrated. Saved config is retained, rootfs-owned bind paths move, and external project binds remain shared.

Rename moves the directory, preserving ID, time and provenance while migrating rootfs-owned host-absolute symlinks/.l2s links and bind paths. Arbitrary file contents, external links and environment strings are not rewritten. Case-only changes are allowed, conflicts refuse overwrites, and active sessions report operation_busy. Ordinary failures and handled cancellation roll back; rollback_failed retains recovery snapshots. SIGKILL/power-loss transaction recovery is still a limitation.
