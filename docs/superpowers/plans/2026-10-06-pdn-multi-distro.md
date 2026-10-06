# Multi-distribution install implementation plan

> **For agentic workers:** Use superpowers:executing-plans in the authorized project workspace.

**Goal:** Ship v0.4.0 with Alpine, Ubuntu, Debian and Arch Linux ARM installation using the same native executable.

**Architecture:** Move pinned ARM64 artifact metadata into a small distro catalogue. Parameterize the existing HTTPS, checksum, extraction and staging flow; keep per-distro configuration separate from transfers. Preserve confirmation-based uninstall and session locks.

**Tech Stack:** C, static curl/MbedTLS/libarchive/zlib, Python unittest, Android NDK sysroot.

- [x] Verify primary sources, fixed archive sizes and SHA256; inspect archive layout. Ubuntu Base 24.04.5, Debian trixie slim debuerreotype build 20261005 at immutable commit, Arch Linux ARM dated 2026.08 archive. Avoid Termux downloads/runtime dependencies.
- [x] Add `src/proot/src/cli/pdn_distros.h` for metadata and mirror tables, update `pdn_install.c` to select a distro, per-distro limits, safe hardlink conversion and network/package configuration. Do not overwrite installed systems.
- [x] Update `pdn.c`: `install NAME`, `mirrors [NAME]`, `list --available`; retain case-insensitivity, local pinned archive option and manual mirror selection.
- [x] Extend installer tests with all distro profiles, configuration, hardlinks, malformed paths, mirror fallback, wrong archives, cancellation and collisions; verify existing login/uninstall tests.
- [x] Build and run real local/online installs of all added distros in disposable build directories, login, verify architecture/os-release, and exercise package manager updates/install where network allows. Keep package signature checks enabled.
- [x] Run `make test`, measure touched C code coverage (at least 80%, preserve existing coverage), and check only libc/libdl dependencies and no host paths.
- [x] Update version, usage, provenance and changelog; record confirmed MT success for v0.3.2. Commit and push specific files and deliver matching binaries/licenses to `/sdcard/yyd/PDN` using existing authorization.

Arch's archive is large; use longer download timeout and bounded extraction sizes. Debian uses two upstream GitHub download routes rather than pretending an unverified domestic rootfs mirror exists. Ubuntu and Arch retain domestic/international mirror choices. Existing systems are untouched.

Validation: 59 automatic tests passed. Frontend line coverage 98.65%, installer 98.72%, catalogue 100%. Ubuntu and Debian downloads, Arch USTC download, all three logins and tree package operations verified. Arch PGP signature matched the upstream build-system fingerprint. The final Debian profile removes docker-clean and sets an explicit CA path; Arch key initialization uses a short temporary path and retains signature checking.
