# PDN uninstall implementation plan

> **For agentic workers:** Use superpowers:executing-plans to implement these steps in the current authorized workspace.

**Goal:** Add confirmed removal of a named local rootfs, with `remove` as an alias.

**Architecture:** Keep the frontend native C. Resolve one immediate child of the configured rootfs directory without following its symlink; remove descendants using directory descriptors and `unlinkat`. Share the install lock and add shared login/exclusive uninstall locks on the rootfs directory.

**Tech Stack:** Android Bionic, POSIX directory APIs, flock, Python unittest, LLVM coverage.

## Tasks

- [x] Add failing tests in `tests/test_pdn.py`: confirmation/cancellation/EOF, case-insensitive aliases, default HOME, malformed names/options, missing/ambiguous entries, root symlinks, nested symlink preservation, read-only directories, installer/session locks, target replacement while awaiting confirmation.
- [x] Run `python3 tests/test_pdn.py -v`; new uninstall tests must fail against v0.3.1.
- [x] Implement `pdn uninstall NAME [--yes|-y]` and `pdn remove NAME [--yes|-y]` in `src/proot/src/cli/pdn.c`. Accept only `y`/`yes` (ASCII case-insensitive); EOF/other answers return nonzero without removing files. Show the resolved path and data-loss warning. Reject `/` as the base, path arguments, ambiguous names, non-directory roots and root symlinks. Never traverse descendant symlinks or a different filesystem. Report partial removal errors without claiming success. Lock the root directory during login and uninstall; revalidate its inode after confirmation.
- [x] Bump `PDN_VERSION`, download user agent and version assertions to 0.3.2; document usage and limits in `README.md`, `docs/proot-distro-nolib.md`, `CHANGELOG.md`.
- [x] Run `make test` with Android build tools on PATH. Measure touched frontend line coverage with an instrumented build (at least 80%, preserve or improve existing coverage). Tests use disposable rootfs fixtures only.
- [x] Review diff, commit specific project files, push to the user's origin and copy verified matching executables to `/sdcard/yyd/PDN` under the user's existing delivery authorization.

## Limits

The command removes a complete rootfs including its user files. It does not accept an arbitrary `--rootfs` path, follow a root symlink, kill sessions, provide undo, or promise atomic rollback after interruption. Session exclusion applies to the new `pdn login`; older binaries, raw `proot` and unrelated host writers must be stopped before uninstalling. Root-owned mounts and hostile concurrent host renames are outside this rootless manager's isolation guarantees.

Validation: 51 tests passed with `make test`. Frontend line coverage 98.64%; new removal module 94.59%. Host hard-link creation is prohibited by this Android environment; external file and directory symlink protection was exercised, and the existing guest hard-link compatibility test remains passing.
