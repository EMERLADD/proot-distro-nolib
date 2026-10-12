# proot-distro-nolib changelog

[简体中文](CHANGELOG.md) | English · [README](README.en.md)

## v0.6.18 — 2026-10-12

Add explicit ordinary task stdin through `startInteractive()`, UTF-8/binary `writeStdin()`, ordered EOF via `closeStdin()` and `isStdinOpen()`. Bounded background input reports rejection and pipe failures; exit, cancellation and timeout clean up the pipe. Default stdin closure and full PTY support are preserved.

## v0.6.17 — 2026-10-12

Add interrupted-install inspection and explicit cleanup with persistent UUID, PID, start ticks and boot ID plus installation/session locks. Preserve unknown entries, installed systems and download cache. Java/Kotlin queries and cleanup builders are available, with example App controls.

## v0.6.16 — 2026-10-11

Add AAR document URI, descriptor and stream import/export for installation, restore and backup. Shell install/restore accept stdin and backup supports binary stdout.

## v0.6.15 — 2026-10-11

Add download-cache listing and clearing with distribution filters and byte usage. Provide typed Java/Kotlin queries and clearing methods; cleanup respects installation locks and preserves installed systems.

## v0.6.14 — 2026-10-11

Resume interrupted online installations by repeating the install; cached archives still require full SHA256 verification. Fix HTTPS proxy resume and partial-download error handling.

## v0.6.13 — 2026-10-10

Add instance cloning and renaming with Java/Kotlin APIs, identity handling and migration of internal links and saved binds.

## v0.6.12 — 2026-10-10

Add installation aliases and instance metadata for multiple independent environments from one distribution.

## v0.6.11 — 2026-10-10

Add optional guest /dev/full compatibility.

## v0.6.10 — 2026-10-10

Fix compatibility for Android-blocked access checks and rename operations without discarding flags.

## v0.6.9 — 2026-10-10

Fix GNU tar extraction failures in ordinary Android Apps and provide independent test Apps for both integration paths.

## v0.6.6 — 2026-10-09

Limit AAR native contents to PDN, loader and PTY JNI while retaining all terminal functionality; lite is an identical alias. Synchronize version reporting.

## v0.6.5 — 2026-10-09

Add AAR configuration, asynchronous tasks, cancellation, timeouts, independent terminals and distribution/mirror queries.

## v0.6.4 — 2026-10-09

Classify PRoot, loader and guest-command startup failures with Java/Kotlin handling interfaces.

## v0.6.3 — 2026-10-08

Add directory, network, storage and archive error classifications with recovery suggestions.

## v0.6.2 — 2026-10-08

Add a separate JSONL event channel and Java event, progress and result APIs.

## v0.6.1 — 2026-10-08

Add Kotlin management/execution wrappers and graphical Alpine package management; keep one official Debian source.

## v0.6.0 — 2026-10-06

Add accounts, working-directory/environment settings, backup and restore.

## v0.5.0 — 2026-10-06

Add exec and repeatable bind mounts.

## v0.4.1 — 2026-10-06

Fix unknown group-name warnings caused by Android supplementary groups.

## v0.4.0 — 2026-10-06

Add Ubuntu, Debian and Arch Linux ARM installers and sources.

## v0.3.2 — 2026-10-06

Add distribution removal and confirmation.

## v0.3.1 — 2026-10-06

Add Alpine mirrors, source selection and failure fallback.

## v0.3.0 — 2026-10-06

Add Alpine ARM64 online/offline installation with size and SHA256 verification.
