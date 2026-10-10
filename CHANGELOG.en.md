# proot-distro-nolib changelog

[简体中文](CHANGELOG.md) | English · [README](README.en.md)

These entries describe the scope and verification of each historical release. A result from an earlier release is not acceptance evidence for a later binary.

## v0.6.13 — 2026-10-10

- Add `clone SOURCE TARGET`, `rename SOURCE TARGET` and matching Java/Kotlin methods. Source lookup accepts legacy names; new targets use the 128-character naming limit.
- Clone creates an independent rootfs, new ID/time and `source=clone`, retaining known provenance. Rename preserves ID, creation time and provenance while changing the name.
- Migrate rootfs-owned host-absolute symlinks, link2symlink backing and bind paths. Fix clone rejecting its own `.l2s` backing when an App uses `/data/user/0` but the engine resolves `/data/data`. Rootfs symlink aliases are supported; neighboring instances and external binds are unchanged.
- Hold the global installation lock and exclusive source rootfs lock. Refuse overwrites, active sessions, malformed metadata and unsafe configuration; support case-only rename.
- Journal rename links, configuration and metadata for rollback on ordinary failure or SIGINT/SIGTERM. Preserve snapshot bytes and modes; retain recovery files and report incomplete rollback. SIGKILL/power-loss recovery remains unimplemented.

Verification: 275 native passes / 2 existing environment skips; SDK 100/100; 102 targeted coverage checks passed. Modified C line coverage 97.62%, SDK 86.37%. AAR Debug/R8 Release passed 52/52 each; direct `.so` passed 44/44 each. MT and Android shell ELF checks and both Release GUI paths passed. Packaging/release boundaries passed 14/14.

## v0.6.12 — 2026-10-10

- Add `install DISTRO --name INSTANCE` for multiple independent instances of the same distribution, retaining existing install calls.
- Save a stable random ID, name, provenance, version, architecture, digest and creation time. JSON and AAR queries accept legacy rootfs without metadata and do not modify them.
- Write metadata in staging and publish without replacement. Backups retain provenance; restore creates a new instance ID, name and time.
- Add Java/Kotlin `installAs` and immutable `PdnInstanceInfo`; synchronize the raw ELF, direct `.so` integration and both independent test Apps.

Verification: 254 native passes / 2 environment skips, SDK 97/97, packaging/release boundaries 14/14. New metadata module line coverage 100%, modified C 98.38%, SDK 86.33%. Four-path and GUI results are in the [test records](docs/pdn-error-testing.en.md).

## v0.6.11 — 2026-10-10

- Add optional ARM64 guest `/dev/full` compatibility, enabled with `PROOT_EMULATE_DEV_FULL=1`; default sessions do not add read/write syscall tracing.
- Support zero reads, ENOSPC writes, access modes, device information and common synchronous I/O. Use actual inode identity across fd duplication, inheritance, reuse and passing.
- Cover tagged pointers, protected buffers, vector limits and statx validation; synchronize ELF, `.so` and AAR artifacts.
- Reads may be short, up to 64 KiB. Only the native 64-bit guest ABI is supported; v2 positioned vector I/O and asynchronous I/O are outside scope.

Verification: 238 native passes / 2 skips; new extension line coverage 86.78%. AAR APK 50/50 and direct `.so` APK 42/42. Both READMEs add the highlighted MT terminal-to-Ubuntu screenshot.

## v0.6.10 — 2026-10-10

- Fix seccomp-blocked `faccessat2` and `renameat2` handling. Return `ENOSYS` for caller fallback rather than silently discarding access-check or rename flags.
- Add real inherited BPF TRAP regressions checking original contents, explicit legacy-call fallback and temporary-file cleanup.
- Synchronize ELF, `.so` and AAR versions; close the LD_PRELOAD report after tracing it to Claude configuration injection.

## v0.6.9 — 2026-10-10

- Fix incorrect argument conversion when an inherited Android App filter blocks `openat2`. Return `ENOSYS` so callers choose fallback without losing resolution constraints.
- Add real BPF TRAP regression and both test APKs' App seccomp acceptance; update raw ELF, `.so` and local AAR.
- Add offline API acceptance using a bundled official archive in both independent test APKs.
- GNU tar extraction and the 2050-file / 50-link stress test passed in the original MT environment; AAR 49/49 and direct `.so` 41/41 passed. Release included two ordinary Debug test APKs, not production Apps. Tested APKs were built locally; native release assets were built by CI.

## v0.6.6 — 2026-10-09

- Refresh unversioned local ELF, `.so` and AAR aliases, verifying `version` and `--version` to avoid stale 0.6.0 copies.
- Remove legacy pr-cli, standalone legacy PRoot, BusyBox and Bash from every AAR. Retain PDN, loader, PTY JNI, PDN's PRoot capability, Java/Kotlin APIs and all terminal functions.
- Keep `pdn-engine-lite-0.6.6.aar` as a byte-identical alias of the standard AAR.
- Package legacy native components separately in the original App. Retain legacy classes, but hosts must supply components required by old pr-cli methods.
- Filter native entries again during release packaging and verify the three required files, PTY JNI, metadata and SHA256. This round validated AARs without rebuilding APKs.

## v0.6.5 — 2026-10-09

- Add immutable AAR configuration and Java/Kotlin constructors for identity, guest work directory, binds and exact environment values.
- Add background tasks, cancellation, timeout, waiting and serial Executor callbacks. Bound the queue and complete after reclaiming PRoot/guest processes.
- Give each terminal its own PID/fd, with input, resize, waiting, termination and idempotent close. Distinguish normal exits from signals and retain actual JNI errno/startup failures.
- Add structured distribution/mirror queries with strict JSON, UTF-8, version and type validation, preserving unknown historical metadata.
- Add `pdn-engine-lite-0.6.5.aar` containing only PDN, loader and PTY JNI; full AAR, `.so` and ELF artifacts remain available.
- Independent Java App with lite AAR and Kotlin standard library passed 33/33 in ordinary `untrusted_app`, including GUI, real installation, async cancellation/timeout and two terminals. JNI device line coverage 269/292 (92.12%).
- JVM 90 passed; native PDN 203 passed / 2 skipped, PRoot 16 passed, packaging 9 passed. Combined SDK unit/device line coverage 944/1044 (90.42%); new/modified native executable lines 94/96 (97.92%).
- Release/R8 acceptance and Maven publication were outside this round. See the [AAR API](docs/pdn-aar-api.en.md).

## v0.6.4 — 2026-10-09

- Classify failures in PRoot startup, loader, initial guest shell, ELF interpreter and actual login shell, with actual errno and suggestions.
- Report startup-child failures to the parent through a separate pipe. The parent writes JSONL, avoiding duplicate started/result events or disrupted sequence numbers.
- Preserve `guest_exit` for nonzero guest commands, 126/127 and signals. Do not classify by stderr wording or infer SELinux/seccomp solely from permission denial.
- Retain protocol v1 and Java/Kotlin compatibility; generate matching AAR, `.so` and ELF, and validate both independent integration APKs without rebuilding the original pr App.
- Release artifacts passed three device paths: rish ELF 28/28, independent AAR APK 19/19, direct `.so` APK 24/24. APKs ran as ordinary Android 14 Apps with targetSdk 35; initialization, installation, commands, events and PTY passed.

Verification: 211 native tests, 209 passed / 2 environment skips; all 41 startup tests passed, including real musl login and controlled loader syscalls. JVM 54 and packaging 6 passed. New/modified native executable line coverage 95.05%, events 98.09%, Java/Kotlin 96.11%. AAR native bytes matched ELF files. See [error verification](docs/pdn-error-testing.en.md) for injection and post-loading diagnostic scope.

## v0.6.3 — 2026-10-08

- Classify directories, bind sources, configuration, user lookup, removal, install conflicts and locks at actual failure sites. Reserve busy-lock classification for flock conflicts; use `directory_not_directory` consistently.
- Distinguish DNS, connection, timeout, TLS, HTTP and other transfer errors. File write/close failures prioritize permission, read-only, space and I/O causes with captured errno.
- Distinguish size, SHA256 and digest-calculation failures, plus corrupt, unsafe, unsupported and oversized archives; preserve refusal to overwrite and rollback.
- Preserve specific diagnostics through generic wrappers; clear recovered mirror failures so they cannot mask subsequent configuration errors.
- Add Java `PdnHostException extends IOException` for cache, event channel, process startup, streams and cleanup, retaining cause, callback exceptions and interruption. Cleanup failures are suppressed exceptions.
- Show Java host classification and suggestions in the GUI. Set original App metadata to 1.0.3 / versionCode 4, without building its APK.
- Document each error trigger, real versus injected failures and remaining scope. More detailed PRoot/guest startup classification was deferred.

Verification: 170 native tests, 168 passed / 2 environment skips for real `/dev/full` and hardlink channels. JVM 53 passed, including real PDN/guest integration. Native line coverage: frontend 94.13%, configuration 100%, removal 93.84%, events 97.66%, backup/restore 95.75%; same-source installer harness 99.67%. Java/Kotlin 96.11%, host exception 100%. Original App Kotlin compilation and final AAR/ELF consistency passed. Injected read-only, space or OOM failures do not mean a device was remounted, filled or exhausted; see [per-error verification](docs/pdn-error-testing.en.md).

## v0.6.2 — 2026-10-08

- Publish v0.6.1/v0.6.2 releases and include matching AAR, `.so`, ELF, source bundle and SHA256 in subsequent releases; reject native/AAR version mismatch.
- Add optional JSONL stages, download/archive progress, diagnostics and final results on a separate channel from guest stdout/stderr.
- Add Java operations/listener/event/result classes and Java overloads for Kotlin defaults.
- Distinguish manager/startup errors, guest nonzero exits, signals and protocol failures, retaining actual process exit codes.
- Wire GUI stage/progress callbacks and display classification, reason and suggestions on final failure.
- Use a private cache file, verify event version/order, bound records and stream buffers, and clean resources after exceptions/interruption.
- Document Java/Kotlin integration, protocol and actual AAR structure.
- Set App metadata to 1.0.2 / versionCode 3 and generate the engine Debug AAR; this stage did not build the original App APK.
- Add an independent Java validation App 0.1.0 with only the generated AAR and Kotlin standard library, initialization, installation, exec, events and PTY.
- Manual independent-App validation entered Alpine and installed nano through the interactive terminal.

Verification: 144 native tests, 143 passed / 1 hardlink environment skip; JVM 42 passed, including real PDN/Linux Java integration. Native line coverage: events 97.27%, frontend 98.43%, installer 98.81%, backup/restore 97.07%; Java/Kotlin 96.44%. GUI Kotlin compilation, AAR/native hashes, independent APK build, signature and Manifest checks passed; normal APKs exclude JaCoCo. Automated device acceptance and coverage were not collected in this round; manual testing did not replace them.

## v0.6.1 — 2026-10-08

- Keep Debian's `official` direct source and remove the duplicate `github` route redirecting to the same file; retain pinned version/size/SHA256.
- Add Alpine GUI package installation, index updates and installed-package queries via Kotlin exec, with progress and actual exit status.
- Add PdnRuntime helpers for install/login/exec/remove/list/mirrors/backup/restore/config, returning configurable ProcessBuilders.
- Support explicit official sources and offline archives, verify all four official ARM64 sources, and add source documentation/regressions.
- Integrate standalone PDN in the Android example using host program/data/cache/project paths.
- Add terminal font sizing, keyboard avoidance and measured rows/columns; remove the old workspace screen.
- Fix repeated explicit-root identity options; add directory path, variable and actual system reason to errors.
- Include `libpdn.so`, `libproot-loader.so` and jniLibs layout in release output, with Android integration documentation.

Verification: 101 native regressions, including 85 PDN tests, passed; frontend line coverage 99.14%, installer 98.76%. Host paths/API/Alpine helpers passed 20 unit tests; Runtime 98.68%, AlpinePackages 100%. APK build/signature passed. GUI installed curl in Alpine and `curl -v https://example.com/` succeeded. rish Android shell version/install passed; rish login remained pending in this round.

## v0.6.0 — 2026-10-06

- Add per-rootfs `config NAME` for binds, user, work directory and environment, with show/clear and login no-config options.
- Add user/work-dir/env options to login/exec; invocation values override saved defaults and apply inside the guest.
- Store bounded non-executable configuration data, replace atomically, reject linked configuration, and protect writes/clear with session locks.
- Add offline backup/restore without replacement, publishing only completed temporary archives/rootfs and cleaning on interruption.
- Migrate internal absolute PRoot hardlink-emulation paths so restored instances independently retain read/write/link behavior.
- Exclude host configuration, loader scratch, runtime directory contents and special nodes; preserve ordinary data/modes/times, ensure owner rwx, and omit host ownership/setid.

Verification: 93 tests passed; frontend 99.11%, configuration 100%, backup/restore 96.88% line coverage. User/work-dir/environment checks passed in four existing systems; real Alpine backup, renamed restore and configured startup passed.

## v0.5.0 — 2026-10-06

- Add `pdn exec NAME -- COMMAND ARG...` with login environment/locks, exact argv, standard streams and exit status.
- Support repeated bind options for files, directories and paths with spaces in login/exec.
- Apply binds only for the invocation, with writes reaching host files; do not save configuration, change App identity or add Termux dependencies.
- Retain login-with-command and explicit-rootfs forms; reject invalid arguments.

Verification: 70 tests passed, frontend line coverage 98.91%. Exec and bound file reads/writes passed in existing Alpine, Ubuntu, Debian and Arch systems.

## v0.4.1 — 2026-10-06

- Fix Android supplementary groups leaking into guest login and producing unknown group-name warnings.
- Virtualize supplementary groups in PRoot fake-id, including query, set, clear, validation and fork/exec inheritance.
- Retain real Android group permissions and guest `/etc/group`; replacing the binary and starting a new session applies the fix to existing systems.

Verification: 61 tests passed; new group module line coverage 92.59%. Existing Alpine/Ubuntu/Debian/Arch logins showed only root with no unknown-group warning; group files were unchanged. Ubuntu apt update passed. The native ARM64 path was verified.

## v0.4.0 — 2026-10-06

- Add ARM64 Ubuntu, Debian and Arch installers alongside Alpine, with case-insensitive commands/names.
- Pin Ubuntu Base 24.04.5 LTS, Debian 13 trixie slim 20261005 and Arch Linux ARM 2026.08; verify exact size/SHA256.
- Configure TUNA/USTC/official Ubuntu, TUNA/USTC/NJU/US Arch, and two Debian routes to the same fixed upstream GitHub commit.
- Add available-list and per-distribution mirrors; support fallback, manual mirror and verified local archive installation.
- Configure apt sources, Android CA and PRoot behavior; remove Debian container cache-cleanup hooks.
- Initialize Arch pacman keys, retaining signatures and fallback repositories.
- Convert archive hardlinks to relative symlinks, ensure directory access, and safely replace DNS symlinks without following targets.
- Set per-distribution timeouts and extraction limits. Arch archive is about 791 MiB, approximately 2 GiB unpacked.
- Preserve existing-rootfs protection, install/removal locks, sessions and independence from Termux runtime.

Verification: 59 tests passed; frontend 98.65%, installer 98.72%, catalogue 100% line coverage. Each new distribution passed real download/install/login, package-index update and installing/running tree. Arch's official PGP signature was verified. Runtime still depends only on Android libc/libdl. See [rootfs sources](docs/pdn-rootfs-sources.md); supplementary-group warnings were fixed in v0.4.1.

## v0.3.2 — 2026-10-06

- Add case-insensitive uninstall/remove commands.
- Show the actual path, require y/yes confirmation, and support yes/y options for already-confirmed App operations.
- Remove manually deployed distributions under the configured parent, including all guest files.
- Refuse traversal, ambiguous names, root symlinks, root parent and cross-filesystem traversal; unlink internal symlinks without following them.
- Share the installation lock; new login sessions lock the directory and prevent removal while busy. Do not kill sessions.
- Report nonzero removal failures and possible partial deletion, without undo. Legacy/raw-engine sessions do not participate in locks and must be stopped first.

Verification: 51 tests passed; frontend 98.64%, removal module 94.59% line coverage. Covered cancellation, EOF, target protection, read-only/permission failures, locks and replaced directories during confirmation. Only temporary rootfs were used. MT trial succeeded on 2026-10-06.

## v0.3.1 — 2026-10-06

- Add USTC, NJU, official CDN and dotsrc after TUNA in Alpine fallback order.
- Fall back after network/HTTP/size/SHA256 failure, freshly downloading identical pinned content.
- Add mirror listing and explicit source selection, with case-insensitive names.
- Follow successful mirror package settings; local archives retain TUNA settings.
- Stop fallback on cancellation, preserve existing systems and clean failed staging.
- No automatic speed ranking, resume or additional installers in this release.

Verification: 42 tests passed; USTC and official install/login and USTC apk update passed. Installer line coverage 96.44%. MT trial remained pending in this round.

## v0.3.0 — 2026-10-06

- Install Alpine 3.24.2 ARM64 minirootfs from TUNA after size/SHA256 verification, or from the matching official local archive.
- Use PDN_ROOTFS_DIR, TUNA apk repositories and basic DNS.
- Add installation locks, existing-target protection, temporary staging and failure/interruption cleanup.
- Enable existing hardlink compatibility for apk index writes/package installation at default login.
- Statically link HTTPS/extraction components; runtime still needs only Android libc/libdl.
- Retain login, explicit commands, list/ls, case-insensitive lookup and the raw PRoot entry.

Verification: 39 tests passed; frontend line coverage 98.48%, installer 95.92%. Real TUNA/local installs, Alpine login, apk update and installing/running tree passed. MT trial succeeded on 2026-10-06.

Implementation commit: `8948b58`.
ARM64 pdn/proot-distro-nolib SHA256:

```text
33d316cefc00535334989fa8b95b86dd393a85b67bf87882d0f55edb5628b8e2
```
