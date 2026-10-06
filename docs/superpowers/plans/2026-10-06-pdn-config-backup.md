# PDN saved sessions and backup implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development for implementation and separate specification/code review.

**Goal:** Release v0.6.0 with saved startup defaults, user/cwd/environment options, and portable rootfs backups.

**Architecture:** Reuse the frontend login path and native PRoot. Store bounded, non-executable per-rootfs configuration atomically. Reuse statically linked libarchive for tar.gz, physical filesystem reads, and staged safe extraction. Hold exclusive rootfs locks for configuration writes and backups; restore shares the install/uninstall lock.

**Tech Stack:** C, Android NDK, libarchive, Python unittest, LLVM coverage.

## Interface

```sh
pdn config ubuntu --bind /sdcard:/mnt/shared --work-dir /root --env LANG=C.UTF-8
pdn config ubuntu --show
pdn config ubuntu --clear
pdn login ubuntu --no-config
pdn exec ubuntu --user root --work-dir /tmp --env EXAMPLE=value -- /usr/bin/env
pdn backup ubuntu /sdcard/ubuntu.tar.gz
pdn restore ubuntu-copy /sdcard/ubuntu.tar.gz
```

Config replaces all defaults; repeated saved binds precede invocation binds, and
invocation user/cwd/env overrides saved values. No saved guest command. Config
is inside `.pdn-config`, bounded, mode 0600, atomic and never shell-sourced.
Backup excludes host-specific `.pdn-config*`, `.pdn-tmp`, runtime dev/proc/sys
contents and special files. Restore accepts tar or gzip tar, publishes only a
new name, validates paths, preserves regular data, ordinary file modes, times and symlinks and uses
relative symlink aliases for archive hardlinks. No host ownership restoration; strip setid and ensure directory owner rwx for rootless operation.
Backup/restore refuse overwrites and clean incomplete staging on normal failure
or handled signals. No reinstallation/configuration scripts execute on restore.

## Tasks

- [x] Implement config/session option module and frontend integration with tests.
- [x] Implement backup/restore and archive tests: roundtrip, symlinks, traversal,
  locks, exclusions, unchanged existing data, cancellation, corrupt inputs.
- [x] Review requirements and code quality, resolve actionable findings.
- [x] Run complete tests, instrument new modules, achieve >=80% line coverage.
- [x] Verify installed Linux login/options and real backup/restored login.
- [x] Update usage, changelog and version, commit/push task files, deliver binaries
  to `/sdcard/yyd/PDN` and verify checksums.

## Validation

- Production tests: engine 16, frontend 33, installer 21, archive 13, config 10; all 93 passed.
- LLVM line coverage: frontend 334/337 (99.11%), config 281/281 (100%), archive 310/320 (96.88%).
- All four existing distributions passed explicit user, work directory and exact environment checks.
- A real Alpine rootfs was backed up, restored under a new name and started with saved defaults.
- Guest-created hardlink tests moved the original rootfs offline before verifying shared writes and further ln/rm operations.
- SIGTERM during backup and restore removed partial output/staging without publication.
- Dependency audit retained only Android libc/libdl, with no Termux paths or RPATH.
- Specification review found internal hardlink portability and permission-contract issues; relocation was fixed and permissions documented explicitly. Re-review approved.
- Final code-quality review approved without further findings.
