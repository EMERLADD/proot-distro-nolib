# PDN test and version verification records

[简体中文](pdn-error-testing.md) | English · [Back to README](../README.en.md)

This document collects version-specific results, environments, coverage and failure triggers. The README and integration guides explain usage. These records retain the relationship between historical versions and actual artifacts; results from an older version do not constitute device acceptance for a newer one.

## Results overview

| Version | Scope | Results |
| --- | --- | --- |
| 0.6.13 | Instance clone / rename, path aliases and rollback | Native: 275 passed / 2 skipped; SDK: 100/100; targeted coverage collection: 102 passed; interface and PTY acceptance passed for all four APKs |
| 0.6.12 | Installation aliases and stable instance metadata | Native: 254 passed / 2 skipped; SDK: 97/97; metadata module line coverage: 100%; changed C lines: 98.38% |
| 0.6.11 | Optional guest /dev/full | Native: 238 passed / 2 skipped; AAR APK: 50/50; direct .so APK: 42/42; new extension line coverage: 86.78%; combined C scope: 86.95% |
| 0.6.10 | Inherited faccessat2 / renameat2 SIGSYS | Native: 226 passed / 2 skipped; changed-line coverage: 100% (3/3); AAR APK: 49/49; direct .so APK: 41/41 |
| 0.6.9 | Inherited openat2 SIGSYS and both App integrations | Native: 224 passed / 2 skipped; AAR APK: 49/49; direct .so APK: 41/41; GNU tar stress test passed; changed-line coverage: 100%; MT diagnosis and full stress test passed with the new ELF |
| 0.6.8 | Entry argument recovery for inherited statx SIGSYS | Native: 223 passed / 2 skipped; 5 filesystem stress groups; changed-line coverage: 100%; AAR/APKs not rebuilt |
| 0.6.7 | Raw ELF seccomp: Shell acceleration and safe fallback | Native: 222 passed / 2 skipped; paths: 14/14; changed-line coverage: 85.71%; AAR/APKs not rebuilt |
| 0.6.3 | Native error classification, JVM and additional rish device tests | Native: 168 passed / 2 skipped; JVM: 53/53; rish: 40/40 |
| 0.6.4 | Startup errors, Release raw ELF, independent AAR APK and direct `.so` APK | Native: 209 passed / 2 skipped; JVM: 54/54; rish: 28/28; AAR: 19/19; `.so`: 24/24 |
| 0.6.5 | Independent App using a local AAR: asynchronous tasks, configuration, queries and two terminals | Device: 33/33; combined SDK line coverage: 90.42% |
| 0.6.6 | AAR components, Ubuntu debugging and device acceptance of three integration paths | SDK: 90/90; packaging: 9/9; native regression: 16/16; rish: 14/14; AAR: 47/47; `.so`: 38/38; reproduced successfully in MT |

The two independent APKs were accepted on Android 14 (SDK 34), targetSdk 35, in ordinary `untrusted_app` processes. PDN/loader inside the 0.6.4 APKs matched the Release originals byte for byte. The 0.6.5 results concern local builds. APKs were not reaccepted during the 0.6.6 component reduction round. Later tests of Release originals are recorded in the path-boundary section below.

## 0.6.13 Instance cloning, renaming and rollback

Native manager regression ran 254 tests: 252 passed and 2 were skipped because of the existing host hardlink and host `/dev/full` restrictions. PRoot engine tests passed 23/23, giving 275 passed / 2 skipped overall. SDK unit tests passed 100/100. Targeted coverage collection passed 102 tests covering instance operations, metadata, installation, backup/restore and JSONL results; this does not mean 102 new tests were added.

Instance checks cover file isolation after clone, a new ID and creation time, retained known provenance, and stable ID/time/source plus continued guest execution after rename. Cloning a legacy rootfs creates metadata with unknown provenance; renaming a legacy rootfs preserves its lack of metadata. Name boundaries, case conflicts, case-only rename, occupied source/global locks, symlink sources, damaged configuration and non-regular metadata all check rejection and preservation of existing files.

Symlink and bind migration checks cover the exact rootfs path, descendants, similar prefixes, missing suffixes and aliases of the rootfs directory. Clone converts its own host-absolute symlinks into guest paths and points link2symlink backing at the new rootfs. Rename rewrites host paths owned by the source. Guest-absolute links, relative links, adjacent instance paths and external links are preserved; external binds remain shared. Actual guest reads/writes and host file checks verify isolation rather than only checking strings.

Controlled publication failures and SIGTERM verify clone staging cleanup and rename restoration of symlinks, configuration and metadata, including actual exit codes and the final JSONL result. Rename rollback also checks original bytes and permissions of manually arranged, valid configuration and metadata. If rollback restoration itself fails, snapshots are retained and `rollback_failed` is reported. Persistent recovery after SIGKILL or power loss is outside this round's guarantee.

JaCoCo SDK line coverage is 963/1115 (86.37%); changed Runtime: 112/120 (93.33%); Catalog: 87/88 (98.86%). LLVM coverage of changed mapped lines is 328/336 (97.62%), not whole-engine coverage. PDN/loader in the raw ELF, AAR and four test APKs were checked byte for byte. Signatures, minSdk 28, targetSdk 35 and actual R8 mappings were checked.

All four test APKs use the same new ELF. Interface and PTY acceptance ran on an Android device:

| Integration | Debug interface/PTY | R8 Release interface/PTY | R8 Release GUI |
| --- | --- | --- | --- |
| AAR | 52/52 | 52/52 | Passed |
| Direct .so | 44/44 | 44/44 | Passed |

Both R8 Release GUIs passed actual button-click acceptance: initialization, duplicate-install refusal, command execution, terminal open/close, input, resize, Ctrl-C, refusal to send after closure and the full acceptance button. The original Instrumentation Activity-start wait did not return in this round's device environment. UIAutomator clicked the same accepted APKs instead; neither the engine nor test build was replaced. These Apps use test signatures and are ordinary test builds only.

## 0.6.12 Installation aliases and instance metadata

Native regression ran 256 tests: 254 passed and 2 were skipped because of existing host hardlink and host `/dev/full` restrictions. The 16 new instance tests passed, covering two instances of one distribution, independent files, stable IDs, provenance, name boundaries/conflicts, read-only legacy queries, damaged/non-regular metadata files, installation rollback and a new identity after restore. SDK unit tests passed 97/97; packaging and publication-boundary tests passed 14/14.

LLVM line coverage: new `pdn_instance.c`, 167/167 (100%); changed mapped lines across the new module and existing C files, 243/247 (98.38%), not whole-engine coverage. Uncovered lines are the network download User-Agent and the retained legacy C installation wrapper. Overall JaCoCo SDK: 960/1112 (86.33%); changed Runtime: 109/117; Catalog: 87/88; DistributionInfo: 16/16; InstanceInfo: 22/22.

Raw ELF acceptance in Android shell passed: two Alpine aliases were installed in an independent temporary directory, file isolation, JSON metadata and backup/restore were checked, then rootfs files were removed. The same checks passed in MT Manager's ordinary terminal using a temporary directory in MT's private HOME. Existing PDN/rootfs files were not replaced, and temporary files were removed afterward.

The test APKs use the same local native ELF and loader. All four passed ZIP, signature, package name, minSdk 28, targetSdk 35, Debug/non-debuggable Release flags and native-byte checks. Release enables R8, optimization and resource shrinking. Mapping confirms 91 renamed classes including dependencies in the AAR path and 34 in the direct .so path. Test APKs use a Debug test key and are ordinary test builds, not production applications.

| Integration | Debug interface/PTY | R8 Release interface/PTY | R8 Release GUI |
| --- | --- | --- | --- |
| AAR | 51/51 | 51/51 | Passed |
| Direct .so | 43/43 | 43/43 | Passed |

New device checks include two named Alpine instances, stable distinct IDs, strictly typed metadata queries (AAR), case-conflict refusal, independent files and a new identity after restore. Existing GUI buttons perform initialization, installation, execution, terminal input, resize, Ctrl-C and closure. Alias methods were accepted through interfaces; no new alias GUI controls are claimed. Reports and coverage records are in `build/instances-v0612/report/`.

## 0.6.11 Four App variants using Release originals

Four test APKs were rebuilt from the actual GitHub Release 0.6.11 AAR, PDN and loader. Download checksums and PDN/loader bytes inside APKs passed verification. Earlier local-build results are recorded separately from this acceptance of published originals.

| Integration | Unminified Debug | Release / R8 | Actually renamed classes |
| --- | --- | --- | --- |
| AAR | 50/50 | 50/50 | 32 SDK classes |
| Direct `.so` | 42/42 | 42/42 | 34 example classes |

All four passed initialization, installation from the bundled official archive, commands, events, errors, PTY, tar and optional /dev/full checks in independent ordinary Apps. These used suiteOnly instrumentation; interface/PTY acceptance is not described as GUI button acceptance. Release enables R8 obfuscation, optimization and resource shrinking with default optimization/JNI rules. Mappings confirm actual class renaming. ZIP, signatures, package names, minSdk 28, targetSdk 35 and Debug/non-debuggable Release flags passed. APKs use a local Debug test key, not production signatures. Reports, mappings and checksums are in `build/v0611-release-matrix/`.

The two R8 Release APKs also passed actual GUI button acceptance, AAR 50/50 and direct `.so` 42/42, both with `gui_controls_tested=true`. Initialization, install-button handling, commands, terminal opening, input, resize, Ctrl-C, closure and full acceptance passed. When background startup was restricted, the test Activity was first brought forward. Acceptance resumed after a Shizuku disconnect was restored; connection problems were not counted as engine failures.

MT's ordinary terminal ran the same published PDN/loader in an independent temporary directory and entered the existing Ubuntu. Device type, device number 1:7, zero reads, dd writes returning ENOSPC and writes through duplicated descriptors passed. A tar.gz round trip of 2000 data files and one executable passed extraction, directory comparison, relative symlink and execution/read checks. Temporary host/guest trees were automatically removed; existing MT binaries and distributions were not replaced. A wrongly specified relative link in the first fixture was corrected to source's bin/codex, then the tests passed.

This round found CI had automatically published 0.6.11 native attachments before four-way device acceptance. The publication script now creates only draft Releases, made public after four-way acceptance of candidate originals; default commits and pushes remain automatic. Five checks passed for draft creation, refusing to overwrite existing versions, missing attachments, digest mismatch and version-tag mismatch; existing packaging tests passed 9/9.

## 0.6.11 Optional guest /dev/full

`PROOT_EMULATE_DEV_FULL=1` enables the virtual device; other values or an unset variable retain default behavior. Actual guest calls cover zero reads; scalar, vector and positioned writes returning ENOSPC; read-only/write-only/O_PATH; protected and invalid buffers; tagged large malloc pointers; vector count/length overflow; short reads across pages; device stat/statx; argument validation; seek; mmap/truncate/ioctl/sync refusal; and unsupported v2/data-transfer calls.

Descriptors are identified by actual inode. Tests cover dup/dup2/dup3/fcntl duplication, replacement of an old descriptor, reuse after close, fork/exec/CLOEXEC and Unix socket SCM_RIGHTS passing. Relative openat, symlinks, ordinary /dev/zero, and explicit regular-file or /dev/zero bind overrides passed. BusyBox reads, device checks and write-error output in a minimal guest passed. Each group checks both explicit seccomp disablement and automatic policy. No `pdn-full*` files remained after testing.

New device tests passed 12/12. The manager including new tests passed 215 / 2 skipped; engine: 23/23; total: 238 passed / 2 skipped. LLVM new extension line coverage: 348/401 (86.78%); including other changed mapped executable C lines: 353/406 (86.95%), not whole-engine coverage. Initialization failure was actually triggered with a nonexistent temporary directory. User-Agent coverage used a nonexistent CA file to trigger failure. Neither counts as a successful installation.

Both independent ordinary Debug APKs used the same local new PDN/loader; signatures, ZIP integrity and native bytes passed verification. AAR APK: 50/50; direct `.so` APK: 42/42. New guest device checks cover character-device type, device number 1:7, failed redirected/duplicated-fd writes, explicit dd ENOSPC output and zero reads. Alpine shell builtin printf checks only the failed exit code; dd checks error text. Initialization, installation, commands, events, asynchronous tasks, PTY and tar regression also passed. MT was not reaccepted in this round. These results concern locally tested artifacts; independently built CI attachments require separate verification.

The Android host currently returns EACCES when querying `/dev/full`; guest emulation under that condition was accepted. Visible accessible real host `/dev/full`, and a visible real device whose open is denied, were not tested with device-node fixtures. Explicit /dev/zero bind override was tested. The two historical skips remain host hardlinks and host `/dev/full`; guest emulation does not change their conditions.

Scope is common synchronous ARM64 I/O. Reads may return short reads of up to 64 KiB, which callers must handle normally. Native 32-bit guest ABI, positioned vector v2 and asynchronous I/O are unsupported; full kernel-device compatibility is not claimed. Enabling this adds tracing overhead, so default sessions leave it disabled.

## 0.6.10 Access and rename flag compatibility

Actual inherited BPF `RET_TRAP` filters intercept `faccessat2` and `renameat2` separately. Identical cases run with and without `PROOT_NO_SECCOMP`. All four old 0.6.9 groups failed: access checks discarded flags, and rename returned success while changing files. The new version returns ENOSYS only on the intercepted path, leaving fallback decisions to callers; unintercepted paths remain unchanged.

Access tests cover zero flags, AT_EACCESS, AT_SYMLINK_NOFOLLOW, unknown flags and missing targets, with exactly five SIGSYS events. File contents, inode, mode, size and link targets remain unchanged, and explicit faccessat fallback works. Rename tests cover zero flags, NOREPLACE, EXCHANGE, WHITEOUT, unknown flags and a new target, with exactly six SIGSYS events. Every case checks unchanged source/target contents and no accidental target creation; explicit renameat fallback succeeds. Invalid path pointers get EFAULT during existing entry translation and are excluded from SIGSYS counts. Tests neither inject return values nor add fault switches to released programs.

Engine: 23/23; manager: 203 passed / 2 skipped; total: 226 passed / 2 skipped. LLVM changed-line coverage: 100% (the two ENOSYS returns and User-Agent, three lines), not whole-engine coverage. User-Agent coverage triggers download failure with a controlled nonexistent CA file and does not count as successful-download acceptance. Temporary test trees were cleaned.

Both ordinary Debug test APKs used the same new PDN/loader. ZIP integrity, signatures and bundled native bytes passed. AAR APK: 49/49; direct `.so` APK: 41/41. They verify initialization, installation, commands, events, tasks, PTY and previous openat2/tar regression. New access/rename flag cases are covered by the actual inherited-filter tests above; they were not added to APK checks this round, and MT was not reaccepted.

## 0.6.9 Android App openat2 fallback

Local SSH into Ubuntu launched by MT reproduced GNU tar 1.35 extraction failure: regular-file creation, stat and archive creation worked, but extraction reported `Cannot open: Is a directory`. Recorded calls showed tar opening `bin/` and `voice/` with `openat2`, flags `0x28c000`, mode 0, resolve `RESOLVE_BENEATH`, size 24, returning EISDIR. The old SIGSYS conversion to openat did not parse open_how and treated its structure pointer as flags. A temporary diagnostic library in only that session made openat2 return ENOSYS; tar fell back to openat and both tar and directory comparison exited 0. The diagnostic library and test trees were removed; system and released programs were unchanged.

The fix makes intercepted openat2 return ENOSYS and leaves fallback decisions to callers; resolve constraints are not silently weakened. Programs requiring openat2 without fallback remain unsupported. Calls not intercepted by the filter retain existing handling.

Automated regression installs actual inherited BPF RET_TRAP filters with PROOT_NO_SECCOMP retained/removed. Directory opens, file creation and invalid structure pointers all return ENOSYS, with exactly three openat2 SIGSYS events. Tests check no accidental file creation, then use openat to open directories, create, write, read and check permissions/cleanup. Both old 0.6.8 groups failed; both new 0.6.9 groups passed. Engine: 21/21; manager: 203 passed / 2 skipped; LLVM coverage of this round's two executable C changes (SIGSYS return and User-Agent): 100%, not whole-engine coverage.

Native ELF/loader in both independent Debug test APKs matched this round's originals byte for byte. Signatures and ZIP integrity passed. The standard AAR still contains only PDN, loader and PTY JNI; its compatible lite filename is byte-identical to the standard AAR.

| Integration | Acceptance | openat2 path |
| --- | --- | --- |
| Raw ELF / Ubuntu launched by ordinary MT App | Original diagnosis and full stress test passed | Actual inherited filter; GNU tar extraction and directory comparison work; no fault injection |
| AAR / ordinary App, targetSdk 35 | 49/49 | Inherited Seccomp 2; three actual SIGSYS events; ENOSYS; openat create/read/write fallback; tar round trip |
| Direct .so / ordinary App, targetSdk 35 | 41/41 | Actual App filter and fallback verified; Shell success is not used as a substitute |

This round used `am instrument -e suiteOnly true -w -r PACKAGE/.ProbeInstrumentation` with the official Alpine archive inside the APK for complete interface acceptance, including events and PTY. GUI online-download waiting is excluded, and a complete GUI clicking sequence is not claimed. The probe is an independent static Bionic ELF with 64-byte TLS alignment, bundled only in test APK assets. Released ELF/AAR files contain no fault switches.

An Android shell launcher also installed an actual openat2 TRAP before running the same 0.6.9 ELF against the existing Ubuntu and the full filesystem stress script: 2050/2050 files, 50/50 links, Errors 0, RESULT PASS, exit code 0, matching directory comparison and SHA256 manifests. All work files were isolated through a dedicated temporary bind directory and removed afterward. This is inherited-filter GNU tar regression, not a replacement for acceptance in a new original MT session.

Finally, socat connected to MT's outer ordinary Android shell, confirmed PDN 0.6.9 and a SHA256 matching delivered originals, and launched a fresh Ubuntu from that shell to run the original diagnosis and full stress script. The process actually inherited Seccomp 2 / one filter. GNU tar 1.35 creation, listing and extraction all exited 0; file types/contents and directory comparison were correct. Full stress output again showed Errors 0, 2050/2050 files, 50/50 links, RESULT PASS and exit 0. No diagnostic library or extra filter was injected. Retesting the new version in the original MT environment is complete. Logs are in delivery `v0.6.9/report/mt-diagnose.log` and `mt-stress.log`. The dedicated temporary bind directory was removed; existing Ubuntu and the original diagnostic script were retained.

## 0.6.8 statx SIGSYS and filesystem stress tests

The Codex installation report from MT's ordinary terminal was `tar ... Cannot open: Is a directory`. This round compared published 0.6.6, raw ELF 0.6.7 with automatic acceleration and 0.6.7 with acceleration disabled in the existing Ubuntu. Direct regular-file creation and GNU tar extraction of a small archive passed in all three groups. The report cannot be directly attributed to a 0.6.7 acceleration regression.

Following the supplied full script, each group creates 200 group directories, 2000 text files, 50 executables and 50 symlinks, then compresses, extracts, compares trees, checks permissions/links and SHA256 manifests. The script is `scripts/test-pdn-filesystem-stress.sh`. Output is inspected with `grep -E 'FAIL:|tar:|RESULT:|Errors:' LOG | head -80`, alongside the full log and actual exit code.

| Raw ELF / conditions | Errors | Files (source/extracted) | Links (source/extracted) | Result |
| --- | ---: | ---: | ---: | --- |
| 0.6.6 / Android shell | 0 | 2050/2050 | 50/50 | PASS |
| 0.6.7 / automatic acceleration | 0 | 2050/2050 | 50/50 | PASS |
| 0.6.7 / acceleration disabled | 0 | 2050/2050 | 50/50 | PASS |
| 0.6.8 / automatic acceleration | 0 | 2050/2050 | 50/50 | PASS |
| 0.6.8 / inherited statx TRAP, full tracing | 0 | 2050/2050 | 50/50 | PASS |

The complete official Codex 0.162.1 ARM64 musl package was also downloaded, checked against official SHA256 and extracted with GNU tar 1.35. The 0.6.6 and 0.6.7 automatic/disabled groups all succeeded and verified content digests of 6 files. Only extraction and verification ran; Codex was neither installed nor executed. Fixed 0.6.8 passed the same checks under inherited statx TRAP.

**A separate bug was reproduced and fixed:** a test launcher installs a real BPF filter making statx trigger SIGSYS. Before the fix, both 0.6.6 and 0.6.7 reported ENOTDIR for relative paths; absolute paths could also return wrong sizes. ARM64 x0 represents both the first argument and return value, so the syscall exit before SIGSYS overwrites the directory fd in CURRENT. Full tracing now reads statx arguments from the ORIGINAL entry snapshot. The accelerated path retains its previous selection because a valid entry snapshot may be absent; support for filters installed by guests was not expanded.

The new probe checks AT_FDCWD, real directory fds and file fd + AT_EMPTY_PATH, verifies type, permissions, size and 24-byte contents, and confirms actual SIGSYS, inherited mode 2 and safe fallback. Normal ELF engine: 20/20; manager: 203 passed / 2 skipped; both changed executable C lines (statx argument selection and User-Agent) covered by LLVM, not whole-engine coverage. Specification and quality reviews passed.

This round did not directly reproduce the original tar error inside the MT process. Simulating a statx restriction is not equivalent to MT's complete system policy. The original report needs retesting in MT with the new ELF; Shell success alone cannot establish complete resolution. AAR/APKs were not rebuilt; the published AAR remained 0.6.6. Delivery: `/sdcard/yyd/PDN/v0.6.8/`. Test directories, downloaded archives, extracted copies, compiled fixtures and raw coverage data were removed after acceptance; scripts, logs and summaries were retained.

<a id="native-seccomp-acceleration"></a>

## 0.6.7 Raw ELF seccomp acceleration

This round updates only the raw ELF, matching loader and renamed `.so` copies. Existing AAR and published Release remain 0.6.6; it does not establish acceleration in ordinary Apps. The LD_PRELOAD report remains recorded only.

PRoot attempts to install its filter when host `PR_GET_SECCOMP == 0` and `PROOT_NO_SECCOMP` is absent. Existing filters, query failure, explicit disablement or installation failure retain full tracing. Actual Android shell showed `Seccomp: 0`, and verbose logs confirmed `ptrace acceleration ... enabled`; this was not inferred only from compiled capabilities in version output.

| Check | Result |
| --- | --- |
| Engine regression | 19/19 |
| Manager, events, startup, configuration, installation, archives and system errors | 203 passed, 2 skipped |
| Android shell policy and functionality | 10/10: automatic enablement, disabling values 1/0/empty, inherited filters, query EPERM, SIGSYS, installation, identity, file read/write/delete |
| Accelerated path boundaries | 14/14: guest /usr, nested binds, path-component boundaries, symlinks, missing targets and others |
| LLVM changed-line coverage | seccomp: 6/6 executable lines; including uncovered User-Agent constant change: 6/7 (85.71%), not whole-engine coverage |
| Review | Specification and quality reviews passed |

Performance comparison alternated automatic acceleration and explicit disablement, three runs each, using the same candidate ELF and Alpine 3.24.2 rootfs in one already-connected Android shell. Timing includes one `pdn exec` and the guest command, excluding rish connection, installation and downloads. The 64 MiB file contains zeros; SHA256 is checked after compression/extraction. File tasks check 1000 files, read the last one and remove the directory.

| Task | Automatic acceleration median | Explicitly disabled median | Time reduction |
| --- | ---: | ---: | ---: |
| Start `/bin/sh -c true` | 29 ms | 37 ms | 21.62% |
| 64 MiB tar/gzip compression/extraction | 1706 ms | 3679 ms | 53.63% |
| Create/check/delete 1000 files | 2133 ms | 3403 ms | 37.32% |

This compares acceleration/disablement within one candidate. Termux proot-distro was not retested, nor were C compilation or Claude download speeds measured this round. Small samples and scheduling/cache variation prevent treating these as fixed gains for all tasks or ordinary Apps. An ordinary MT terminal inheriting an Android seccomp filter still uses the compatibility path.

Reproduction: `scripts/test-pdn-seccomp.sh PDN LOADER ALPINE_ARCHIVE PROBE NEW_DIRECTORY`; the probe is compiled by `tests/test_proot_nolib.py`. The script requires a host without an inherited filter and fails unless automatic acceleration is enabled. Inherited-filter and query-failure cases use probes installing actual kernel BPF filters; released programs have no test switches. Path tests reuse `scripts/test-pdn-paths.sh`; this round's copy only removed its forced-disable variable and unset it before execution.

Ordinary LLVM instrumentation cannot write a child's counters after exec. A supplemental collection build uses linker `--wrap=execvp` solely to call `__llvm_profile_write_file()` before the original function. The hook exists only in test builds; normal released ELF files do not link it. Execution counts were checked against source lines. Raw collection caches were deleted after acceptance; normal artifacts and coverage summaries were retained.

Versioned artifacts and reports are in `/sdcard/yyd/PDN/v0.6.7/`; top-level raw ELF/loader/`.so` aliases were refreshed. AAR/APKs were not built locally, and no new GitHub Release was published.

## Events and error classification

The event protocol is v1. `outcome` identifies the final result, `code` the specific failure reason, and `message` and `suggestion` provide explanations and advice. Linux command exits remain `guest_exit`; coincidentally matching exit codes do not turn them into manager errors.

## Test methods

- **Real failures:** execute actual file operations, flock, curl or libarchive with controlled inputs, then check actual exit codes, final JSONL results, suggestions and files after rollback.
- **Fault injection:** compile and run dedicated test programs in native Termux, making selected functions return predetermined errors; check classification, suggestions, exit status and file preservation. All 9 lock-error combinations passed; released programs have no injection switches.
- **Mapping verification:** pass errno directly to public event functions and check classification, suggestions and event sequences. This does not verify that the filesystem or allocator can actually produce that errno.

Tests do not modify error text, depend on accidental Internet outages, fill device storage or change system mounts.

## Native manager errors

| Classification | Trigger and verification | Verification type |
| --- | --- | --- |
| `invalid_argument` | Missing arguments, invalid bind syntax, environment variables and configuration arguments; check nonzero exit and classification | Actual argument validation |
| `rootfs_missing` | Nonexistent rootfs; nonexistent names for backup, configuration or uninstall | Actual file operations |
| `directory_missing` | Missing rootfs parent or explicit temporary directory | Actual file operations |
| `directory_not_directory` | Regular file used as rootfs, parent or temporary directory | Actual file operations |
| `directory_permission` | Remove directory access for an unprivileged process, attempt access, then restore permissions | Actual permission failure |
| `directory_read_only` | Test rootfs error function receives EROFS; no controlled read-only test directory was mounted | Mapping verification; actual read-only directories not verified |
| `directory_unavailable` | Retain fallback for unknown directory errors; do not infer causes from unobserved errno | No per-errno device verification |
| `bind_source_missing` / `bind_source_unavailable` | Missing bind source or unsupported source type such as FIFO | Actual file operations and type validation |
| `name_ambiguous` | Create two distribution directories differing only in case, then configure, back up or uninstall | Actual directory scanning |
| `rootfs_exists` / `file_exists` | Install/restore to an existing name or back up to an existing file; verify original content is preserved | Actual conflicts |
| `operation_busy` | A test process holds actual flock while another PDN process installs, configures, uninstalls, backs up or restores | Actual cross-process lock conflict |
| `lock_failed` | Rejected symlink lock file; test flock also returns ENOLCK/EIO/EINTR, checking these are not mislabeled busy and errno is preserved | Actual safety validation plus fault injection |
| `distro_unknown` / `mirror_invalid` | Select nonexistent distribution or mirror names | Actual argument validation |
| `resolution_failed` | Test curl returns host-resolution failure; assert no server request was made | Fault injection; no dependence on actual DNS failure |
| `connection_failed` | Request a local port without a listener | Actual curl connection failure |
| `download_timeout` | Local HTTPS server delays its response; test distribution timeout is 1 second | Actual curl timeout with shortened test wait |
| `tls_failed` | Local certificate matches only localhost; request by IP with certificate verification enabled | Actual TLS hostname verification failure |
| `http_error` | Local HTTPS server returns 404 | Actual HTTP failure |
| `download_failed` | Test curl returns receive failure; retain unknown-transfer fallback | Fault injection |
| `file_missing` | Missing local archive, restore file or directory required by configuration | Actual file operations |
| `file_permission` | Remove access to configuration, backup source or directory; additionally inject EACCES in download writes | Actual permission failure plus fault injection |
| `file_read_only` | Test download writes return EROFS; verify final result is not a network error | Fault injection; no actual read-only mount verification |
| `storage_full` | Test download writes and file close return ENOSPC; public mapping checks EDQUOT | Fault injection plus mapping verification; device not filled |
| `file_io_failed` | Test write/close returns EIO; preserve cause | Fault injection; fallback for other unspecialized I/O failures |
| `out_of_memory` | Public event and test rootfs error functions receive ENOMEM; check code and suggestion | Mapping verification; no device memory exhaustion or injection at every allocation point |
| `archive_size_mismatch` | Shorten local archive by one byte; server separately sends more than the fixed size | Actual size verification and download limit |
| `archive_checksum_mismatch` | Flip one content byte without changing file size | Actual SHA256 mismatch |
| `archive_hash_failed` | Test digest function fails | Fault injection |
| `archive_corrupt` | Non-archive data, damaged or truncated archives; verify rootfs is not published | Actual libarchive failure |
| `archive_unsafe` | Archive contains `../`, dangerous links, or backup destination is inside rootfs; check external files are unchanged | Actual safety validation |
| `archive_unsupported` | Archive contains unsupported FIFO entries | Actual type validation |
| `archive_limit_exceeded` | Reduce test installation extraction limit; restore archive header exceeds per-file limit without generating a huge file | Actual limit validation; installation uses test parameters |
| `archive_invalid` | Restore input is not a regular file or archive lacks a top-level Linux rootfs | Actual input and structure validation |
| `extraction_failed` | Test archive-entry write fails | Fault injection; specific file/archive causes take priority |
| `rootfs_configuration_failed` | Test configuration write fails, including mirror failure followed by successful fallback then configuration failure | Fault injection; check old download error cannot hide new cause |
| `keyring_initialization_failed` | Test initialization subprocess fails; verify Arch rootfs is not published | Fault injection; not an actual device pacman keyring failure |
| `publish_failed` | Test installation rename fails; verify staged installation is cleaned | Fault injection; actual publication syscall unchanged |
| `config_invalid` / `config_unsafe` | Damaged, oversized or invalid configuration; configuration entry is symlink or FIFO | Actual parsing and safety validation |
| `config_read_failed` / `config_write_failed` | Configuration I/O fallback without a specific errno classification; actual permission failures prioritize `file_permission` | Fallback classification; no injection at every read/write call |
| `user_invalid` | Invalid UID/GID, nonexistent username or damaged passwd | Actual parsing and user lookup |
| `uninstall_failed` / `uninstall_incomplete` | Connected removal failure points track whether entries were actually removed; specific system causes may prioritize file errors | Partial-removal faults not separately injected; complete acceptance cannot be claimed |
| `manager_failed` | Unspecialized manager failure; test program returns nonzero without reporting an earlier error | Fallback verification; 0.6.3 excluded internal PRoot/guest startup details; see 0.6.4 additions below |

The former `not_directory` is standardized as `directory_not_directory`. Temporary-directory permission and read-only errors are reported separately instead of all becoming `directory_not_writable`. Older stage categories such as `verification_failed`, `configuration_failed` and `archive_failed` remain compatibility fallbacks when no more specific cause is available.

## Java host errors

`PdnHostException` extends `IOException` and retains the original cause. It adds code and suggestion without requiring existing catch clauses to switch exception families.

| Classification | Trigger and verification | Verification type |
| --- | --- | --- |
| `host_cache_failed` | Cache path is a regular file | Actual file operations |
| `host_event_channel_failed` | Cache directory under `/proc/self`, where files cannot be created; test also reads after closing the event file | Actual file operations plus fault injection |
| `host_process_start_failed` | Start nonexistent executable; test process launcher also throws SecurityException | Actual process-start failure plus fault injection |
| `host_output_failed` | Test Process input close fails, output read fails or read stalls beyond drain deadline | Fault injection |
| `host_cleanup_failed` | Test Process refuses exit or stream close fails; replacing event file with nonempty directory also causes actual deletion failure | Fault injection plus actual file operations |

Checks also verify existing `IOException` catches remain valid, the cause object is preserved, listener exceptions propagate unchanged, thread interruption still throws `InterruptedException`, and cleanup errors are attached as suppressed exceptions without replacing the original failure.

## Success and exit-status regression

- First mirror returns 404 and second serves the correct archive: earlier error diagnostics are allowed, but the final result is success without stale errors.
- Configuration fails after successful mirror fallback: final result is the configuration error, not the recovered HTTP error.
- Linux commands exit 0, 37 or by signal: actual PDN/guest calls check stdout, stderr, guest status and actual process exit code.
- Overwrite refusal, unsafe archives and installation/archive cancellation: existing content and external files are preserved, and temporary files are reclaimed.

## Test entry points

Routine Release verification needs no fault-injection switches: controlled missing rootfs, temporary-directory or bind-source inputs verify real classification and suggestions. Difficult-to-produce allocation failures and specific errno are covered by development tests. Injection verifies handling branches; actual failures verify device operations, with results recorded separately.

In Termux with source, Python, Clang and NDK, run an individual injection test:

```sh
NDK_PATH=/your/NDK/directory CC=clang python tests/test_pdn_system_errors.py -v
```

The script temporarily compiles a dedicated program replacing `flock()` with a fixed-failure function, then checks results and cleans test directories. Test variables affect only this program, not released PDN. SSH can provide entry into Termux but cannot give released programs injection switches.

Native tests require ARM64 Android, an executable BusyBox fixture and NDK. The usual entry point is `make test`; additional error-handling tests are in `tests/test_pdn_system_errors.py`. Java entry points are `:proot-engine:testDebugUnitTest` and `:proot-engine:pdnCoverage`; actual native integration also requires `PDN_NATIVE_FIXTURE` and `PDN_GUEST_FIXTURE`.

This environment lacks `/dev/full`, so actual ENOSPC testing is skipped. The hardlink event-channel test is also skipped because the filesystem refuses hardlinks. Fault-injection results cannot replace independent Android App device coverage reports.

## 0.6.3 Verification records

Of 170 native tests, 168 passed and 2 were skipped for the reasons above. All 53 Java/Kotlin tests passed with actual PDN/guest fixtures enabled. Original App Kotlin compilation, AAR ZIP integrity, new exception classes and native byte equality passed; the original App APK was not built.

Line coverage: frontend 94.13%, configuration 100%, uninstall 93.84%, events 97.66%, backup/restore 95.75%, same-source installer harness 99.67%; Java/Kotlin 96.11%, new host exception class 100%. These are module line coverage figures, not claims that every error category occurred on a real device.

The installer harness uses the same C source and actual curl/libarchive, replacing fixed rootfs size, digest and download URLs with small local archives and a local HTTPS service. Functions marked injected in the table are additionally replaced in this test build. Released programs contain no TEST_* fault switches, and released installation limits are not replaced with test values.

## rish / Shizuku additional device tests

On 2026-10-08, the original GitHub Release v0.6.3 `pdn` and matching `proot-loader` ran through one persistent rish session, as Android `uid=2000(shell)`, SELinux `u:r:shell:s0`. Programs ran from an independent `/data/local/tmp` test directory without Termux's `$PREFIX`, downloader or shell. BusyBox only constructed test locks and packaged logs.

After correcting the test script's uninstall confirmation and lock holding, the final suite passed **40/40**. Where the shell could not create FIFOs, `/dev/null` tested unsupported bind-source types; FIFO archive entries were still checked through actual restore operations.

| Device checks | Trigger and result |
| --- | --- |
| Initialization and installation | Version 0.6.3; download official Alpine 3.24.2 ARM64, verify, extract and install; duplicate install returns `rootfs_exists` |
| Login and commands | Login `id -u` and `pwd` return 0 and `/root`; exec preserves a single argument containing spaces and a semicolon, with separate stdout/stderr |
| Workspace | Host directory bound to `/workspace`; guest writes match Android shell reads |
| Linux exit status | Normal exit is success; exit 37 is `guest_exit` with `guest_exit_code=37`; SIGTERM returns 255 and records `guest_signal=15` |
| Rootfs and temporary directories | Missing rootfs, file in place of directory, actual denial after chmod 000; missing/file explicit temporary paths return specific directory errors and suggestions |
| Arguments and binds | Unknown distribution/mirror, missing bind source and `/dev/null` bind return their classifications |
| Configuration and names | Invalid/missing users, damaged configuration, configuration symlink, chmod 000 configuration and two case-only directory names verify user/configuration/permission/ambiguity errors |
| Lock conflicts | Independent BusyBox holds actual flock; after readiness, install/backup/restore all return `operation_busy`; symlink lock entry returns `lock_failed` |
| Backup, restore and uninstall | Actual Alpine backup, restore, restored exec and `--yes` uninstall pass; existing backup is preserved and restored target actually removed |
| Archive errors | Missing input, `../` paths, FIFO entries, missing Linux structure, 9 GiB declared header and non-archive data return file-missing or specific archive errors |
| Rollback and events | No restore/install staging remains; unsafe paths create no external files; each operation checks increasing sequence, operation_id, unique final result, actual exit code and outcome; errors include message and suggestion |

This covers 40 operation scenarios and 23 specific codes. Raw JSONL and stdout/stderr are in `/sdcard/yyd/PDN/rish-v063/results.tar.gz`; the per-item index is `manifest.tsv` in the same directory. Programs were unchanged and APKs were not rebuilt.

These tests establish released ELF behavior under Android shell identity. Java host exceptions still rely on earlier JVM tests; the AAR App was not rerun. Network subcategories still rely on local HTTPS tests and explicitly marked injection. Storage-full, read-only mounts and memory exhaustion were not created on-device and do not count as rish acceptance.

## 0.6.4 Startup-error verification

New `tests/test_pdn_startup.py` runs 41 startup tests. 18 filesystem, ELF, login and controlled-loader scenarios each run against normal and link-substituted programs; 5 further test methods cover multiple process/tracing and extraction-failure conditions. Link substitution exists only for injection; released ELF files contain neither `PDN_FIXTURE_*` switches nor replacement functions.

| Check | Trigger | Type |
| --- | --- | --- |
| Initial shell missing, non-executable or malformed | Remove fixture `/bin/sh`, remove execute permission or write non-ELF content | Actual file/execution failures |
| Bad shell fallback exits 0 | Executable text without shebang contains `exit 0`; final result remains nonzero manager_error | Actual execution fallback; success cannot mask failure |
| ELF interpreter | PT_INTERP points to missing, non-executable or damaged interpreter, or declares length beyond EOF | Actual ELF parsing/file failures; truncation is format error |
| Interpreter read I/O | Link substitution makes pread for selected PT_INTERP return EIO | Fault injection with original errno |
| External loader | Missing, non-executable or non-ELF file | Actual execution failure |
| Embedded loader extraction | Only loader mkstemp/fchmod/ELF-data write are matched; return ENOSPC/EACCES/ENOSPC respectively | Fault injection; device storage not filled |
| Loader open/mmap/close | Controlled ARM64 loader fixture makes actual failing syscalls | Actual kernel errors with controlled loader; not random device failures |
| Loader exits normally without starting guest | Controlled loader exits 0 without load notification; final result guest_start_failed | Actual child exit; no fabricated errno |
| Actual interactive login shell | passwd selects missing, non-executable or malformed shell, including text fallback exit 0 | Actual login failure; wrapper success does not mean login success |
| Normal Linux exit | Login exits 126 and 127 or runs missing command; existing exec signal/exit regression | Actual guest_exit, not startup error |
| Dynamic-library search avoids false errors | Copy actual Alpine musl BusyBox/linker/libz to private fixture, put dependency in `/usr/lib`, allow missing `/lib` probes first | Actual musl login; existing Alpine unchanged |
| Process and tracing startup | Link substitutions make fork, pipe2, ptrace TRACEME/SETOPTIONS/resume return selected errno | Fault injection; check nonzero exit, cleanup and no hang |
| Java native-protocol integration | PdnOperations parses actual missing-native-shell result; checks category, errno, suggestion and unique final result | Actual native → Java integration |

Tests explicitly skip when the dynamic Alpine fixture is absent. It was available here and both dynamic-login tests passed. Each startup failure checks matching actual exit/event results, continuous sequence and a unique final result; later generic errors cannot overwrite specific diagnostics.

These tests ran ARM64 Android native programs and JVM tests in Termux without repeating independent AAR App device acceptance or building App APKs. See the next section for 0.6.4 rish tests. After load notification, dynamic-linker missing-library reports and guest initialization script failures remain guest_exit, with details in stderr; this round does not parse their wording.

Summary: 195 PDN tests plus 16 PRoot regressions, 211 native tests overall, 209 passed / 2 skipped for hardlink-channel and `/dev/full` restrictions. All 54 JVM tests and 6 release-packaging checks passed. AAR ZIP integrity and bundled pdn/loader equality with matching local ELF passed.

LLVM line coverage: new/modified native executable lines 192/202 (95.05%), whole event module 98.09%; Java/Kotlin 371/386 (96.11%). New-line statistics combine normal and same-source injected builds, excluding test replacement functions from the native-source denominator. This is not 95.05% whole-engine coverage or every-error acceptance under actual device policy. Child `_exit` coverage uses test profile-flush substitution; released programs retain `_exit` behavior.

## 0.6.4 Additional rish tests

On 2026-10-09, `sh ~/rish` established a persistent Shizuku shell session, running original GitHub Release v0.6.4 `pdn` and matching `proot-loader` as Android `uid=2000(shell)`. Both deployed ELF SHA256 values matched downloaded files. Programs ran in `/data/local/tmp/pdn-rish-v064`; rootfs, temporary directories and workspace were newly created independent directories, preserving existing distributions.

**28/28 scenarios passed**, each with a 20-second operation timeout; none timed out.

| Device checks | Scenarios | Trigger and result |
| --- | --- | --- |
| Version, installation, exec, login | 4 | Version 0.6.4; install local official Alpine 3.24.2 ARM64 archive; exec/login pass, fake root UID 0, host-readable workspace and separate stdout/stderr |
| Guest exit | 4 | exec exit 127/SIGTERM; login exit 126 and 127; all guest_exit retaining guest_exit_code or guest_signal=15 without startup codes |
| Initial shell | 4 | Missing file, removed execute permission, bad format and no-shebang fallback exit 0 return matching guest_shell categories; fallback exit 0 remains nonzero manager_error |
| External loader | 3 | Missing, non-executable and bad format return proot_loader categories and actual errno |
| ELF interpreter | 4 | PT_INTERP points to missing, non-executable or malformed files or declares length beyond ELF EOF; matching guest_interpreter categories |
| Actual login shell | 4 | passwd selects missing/non-executable/bad-format/text-fallback-exit-0 shell; guest_login_shell categories, without treating wrapper startup as login success |
| Loader runtime | 4 | Controlled ARM64 loader makes actual failing open/mmap/close, retaining EISDIR/EBADF; exit 0 without starting guest returns guest_start_failed without fabricated errno |
| Dynamic-library search | 1 | musl probes missing `/lib` then finds libz in `/usr/lib`; interactive login succeeds without false loader errors |

All 28 JSONL records check version=1, operation_id, continuous sequence, unique final result at the end and actual exit-code/outcome consistency. All 19 startup failures contain exactly one error with specific code, message and suggestion; 18 include actual errno, while direct loader exit reports only actual exit status. Success/guest_exit cases contain no error events or startup codes.

Raw events, stdout/stderr and workspace-write evidence are in `/sdcard/yyd/PDN/rish-v064/results.tar.gz`, indexed by `manifest.tsv` in the same directory. Installation used a previously downloaded official archive without retesting online download. BusyBox supplies fixtures, timeouts and log packaging; PDN does not depend on Termux's `$PREFIX`, downloader or shell.

This establishes released ELF behavior under Android shell identity. It does not revalidate an untrusted_app AAR App or create device fork/pipe/ptrace policy denial; those branches still rely on explicitly marked injection tests. Documentation changes do not change program version; APKs were not built.

<a id="apk-integration-acceptance"></a>

## 0.6.4 APK integration acceptance

On 2026-10-09, two independent Java Android Apps were built and installed for automated acceptance on Android 14 (SDK 34), ARM64. Both use minSdk 28, targetSdk 35, compileSdk 36 and Android platform widgets. Actual operation processes were verified as SELinux `untrusted_app`. rish only installs, starts instrumentation and exports reports; it cannot substitute for App execution identity.

| Path | Dependencies and results |
| --- | --- |
| AAR | Independent `examples/aar-probe` imports only Release `pdn-engine-0.6.4.aar` and Kotlin standard library; 19/19 checks pass, executing Java APIs, event callbacks and AAR PTY JNI |
| Direct `.so` | Independent `examples/so-probe` imports no AAR, Kotlin or engine source modules; ProcessBuilder launches Release ELF, its own reader processes JSONL and a small custom PTY JNI runs; 24/24 checks pass |

Both APKs pass signatures, ZIP integrity and Manifest SDK/entry/native-extraction checks. Bundled `libpdn.so` and `libproot-loader.so` match GitHub Release originals byte for byte. Native runtime dependencies contain no Termux libraries or RPATH/RUNPATH; custom PTY JNI depends only on Android libc/libdl, with 16 KiB LOAD alignment. Acceptance APKs use Debug signatures and collect coverage; Release AAR and PDN/loader themselves were not reinstrumented.

Both paths actually click initialization, online official Alpine installation, command execution, terminal opening, input, resize, Ctrl-C, close and refusal of input after close. Full acceptance additionally creates a new rootfs from official Alpine 3.24.2 ARM64, checking UID 0, exact argv, stdout/stderr separation, persistent workspace, real PTY, continuous I/O, `stty size` 32×96, normal exit and event callback thread.

Private fixtures trigger missing/non-executable/bad-ELF/no-shebang-exit-0 initial shells and missing/non-executable/bad-format passwd-selected login shells; specific codes, actual errno and suggestions are verified. No-shebang fallback exit 0 remains manager_error. Missing loader and denied execution from App data pass; the latter is actually EACCES, so damaged content cannot count as ENOEXEC coverage. Guest exits 17 and 127 and SIGTERM remain guest_exit. Normal operations check continuous sequence, operation_id and unique started/result; only manager_error has a unique error.

The direct `.so` project also passes invalid-JNI-argument checks and four event-tail faults constructed by actual `/system/bin/sh`: damaged JSON, events after result, truncated lines and invalid UTF-8. These verify the example host reader rejects malformed protocols; they are not PDN native injection.

| Device line coverage | Covered/executable lines | Percentage |
| --- | --- | --- |
| AAR verification project Java | 425/454 | 93.61% |
| Direct `.so` verification project Java | 569/593 | 95.95% |
| Custom PTY JNI | 92/103 | 89.32% |

JaCoCo collects ordinary App Java execution; optional LLVM instrumentation collects custom JNI, with 9/9 functions executed. These figures cover verification projects, not Release engine coverage, and do not establish all memory-exhaustion/system-policy-denial branches.

Two host test issues were fixed: build tools decompressed and renamed the `.gz` archive asset, so `.archive` is used and raw APK bytes checked; Alpine `/bin/sh` is a guest-absolute symlink, so entry checks use NOFOLLOW_LINKS while actual executability is verified by guest exec. Neither changed native PDN code. After starting instrumentation, the Activity must explicitly be opened before acceptance continues; see both example project guides for UI startup.

Official archive input SHA256: `9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773`; PDN installation continues checking built-in size and SHA256. Raw reports, Java `.ec`, JNI `.profraw` and both test APKs are in `/sdcard/yyd/PDN/apk-v064/`; local analysis is in `build/apk-v064/`. Existing distributions are unchanged and PDN remains 0.6.4.

## 0.6.5 AAR interface device tests

On 2026-10-09, an independent Java App importing only the locally built 0.6.5 lite AAR and Kotlin standard library was accepted on Android 14 (SDK 34), ARM64, targetSdk 35, as `untrusted_app`. rish only installed, launched and exported results. Both the coverage APK and final uninstrumented APK passed **33/33**, including actual GUI buttons. Both contain only PDN, loader and PTY JNI native files. Local-build results are not represented as byte-for-byte acceptance of GitHub Release originals.

New checks exercise actual APIs: configuration passes guest environment values containing spaces, quotes, dollar signs, Chinese characters and emoji unchanged, and checks custom bind files. Structured queries check installed paths, unknown version fields and Debian's single official source. Two asynchronous commands check distinct operation_id, the specified Executor and unique final callbacks. Cancellation/timeouts use real guest shells and background sleep; after obtaining PIDs, tests verify both disappeared and wait until cleanup completes.

Two real PTYs run simultaneously, checking distinct PID/fd, independent I/O, TTY, resize, normal/nonzero/signal exit, repeated close and refused input after close. High-level terminals check native shell startup errors agree with event callbacks; listener-thrown Error checks cleanup and unique failure notification. After clearing ProcessBuilder's environment, bare terminals receive only explicit variables; the old launcher still inherits the host environment. JNI invalid arguments, Unicode, exec/chdir failure, old-interface adaptation and actual errno have separate checks.

| Line coverage scope | Covered/executable lines | Percentage |
| --- | --- | --- |
| All SDK classes, JVM unit tests | 893/1044 | 85.54% |
| All SDK classes, combined unit/device | 944/1044 | 90.42% |
| Engine PTY JNI, device LLVM | 269/292 | 92.12% |
| New/modified native executable lines this round | 94/96 | 97.92% |
| AAR packaging script, Python line trace | 51/53 | 96.23% |

All 90 JVM, 16 PRoot and 9 packaging checks passed. Of 205 native PDN tests, 203 passed / 2 were skipped for environment restrictions. These are line coverage figures; JNI branch coverage is 62.06%, with no claim of all exceptional branches or all Android systems. Optional terminal JNI instrumentation also collects fork-child paths; final AAR and delivered APKs were restored to uninstrumented builds.

Delivered APKs, both AAR variants, the 33-check report and instructions are in `/sdcard/yyd/PDN/apk-v065/`. Local coverage/build records are in `build/apk-v065/` and `build/pdn-v065-coverage/`. Formal Maven publication and Release/R8 obfuscation validation were outside this round.

## 0.6.6 AAR components and packaging verification

Standard AAR and lite alias are byte-identical, containing only PDN, loader and PTY JNI. All `PdnTerminal` APIs remain; hosts provide terminal UI. Legacy pr native components are packaged separately by the repository's original App.

- Packaging regression **9/9**; packaging script line coverage **51/55 (92.73%)**.
- SDK unit tests **90/90**; line coverage **893/1044 (85.54%)**.
- Native regression **16/16**; actual Debug AAR file list, terminal classes and alias byte equality passed.
- Legacy-component staging for Termux App and standard engine staging passed. Standard App staging was not executed because vendor termlib's specified NDK 27.0.12077973 was missing; that branch received only code review of relevant paths and filtering rules.

No APKs were built this round. These results do not replace independent App device acceptance of 0.6.6.

<a id="ubuntu-android-commands"></a>

## 0.6.6 Ubuntu calling Android commands

On 2026-10-09, ARM64 Android, PDN 0.6.6 and Ubuntu Base 24.04.5 LTS: Ubuntu launched after acquiring actual `uid=2000(shell)` from Shizuku. Android property/package queries and system-setting reads/writes passed and were reproduced in MT Manager.

| Check | Result |
| --- | --- |
| Raw ELF `version` and `--version` | Both 0.6.6 |
| Ubuntu download, SHA256 and installation | Passed |
| Guest fake root versus actual Android identity | Guest shows root; actual UID remains shell |
| Android commands after binding `/system`, `/apex` and linker configuration | `getprop` returns SDK 34; `cmd package path android` returns framework path |
| Write test setting in Ubuntu, then check from Android shell after exit | Values match; deletion yields `null` |
| Custom `rish()` wrapper | `-c`, interactive mode, spaces/quotes in arguments and relogin after saving `.bashrc` all pass |
| MT Manager reproduction | Android commands and settings in Ubuntu pass |

The function executes Android commands through inherited shell privileges without reconnecting to Shizuku. Stable connection of the original Shizuku client launched again inside the guest was not verified. Standalone Linux adb clients and actual Android root are outside the passed scope. The authorized host disconnected when backgrounded; battery "Unrestricted" did not solve this. The tutorial requires keeping the host foregrounded throughout.

Early manual independent-AAR-App acceptance also completed `apk add nano` and checked normal APK signatures, Manifest and native digests. HTTPS access after GUI installation of curl was verified. See the [Android shell tutorial](pdn-shizuku-android-shell.en.md) and [AAR example](../examples/aar-probe/README.en.md).

## 0.6.6 Path boundaries and three integration paths

On 2026-10-10, original GitHub Release 0.6.6 artifacts passed path-boundary acceptance on ARM64 Android 14 (SDK 34). Raw ELF ran through rish under actual Android shell identity; two independent APKs ran as ordinary `untrusted_app`, minSdk 28 and targetSdk 35. AAR App depends only on the published AAR and Kotlin standard library. Direct `.so` App uses published ELF and its own event/PTY adapters. PDN and loader in both APKs match Release originals byte for byte.

| Integration path | New path cases | Full acceptance |
| --- | --- | --- |
| Raw ELF / Android shell | **14/14** | Dedicated path cases only this round |
| Independent AAR APK | **14/14** | **47/47**, including existing interface, GUI and terminal acceptance |
| Independent direct `.so` APK | **14/14** | **38/38**, including existing process, event, GUI and terminal acceptance |

| Case | Result |
| --- | --- |
| Guest `/usr` read/write mapping | Guest marker reads/writes correct; isolated host `/usr` fixture marker unchanged |
| Parent bind first or child bind first | Deeper directory wins in either argument order; writes reach actual child-bind source |
| Path-component boundary | `inner` bind does not cover `innerish` |
| Absolute and relative symlinks | Targets resolve within guest paths with correct reads/writes |
| Cross-rootfs link with explicit bind | Specified target in another rootfs is readable/writable |
| Cross-rootfs link without bind | No automatic access to another rootfs |
| Link directly to another rootfs's host-absolute path | Read fails without corresponding bind; target unchanged |
| Create target through dangling link | Writing creates guest target and preserves link |
| Symlink loop | Read fails; guest exits normally |
| Missing file | Read fails without creating file |
| Existing parent, missing target file | Creation succeeds inside guest rootfs |
| Missing parent as well | Creation fails without unexpected host files |

Negative cases first perform a positive read, establishing guest and read-tool functionality. Each App case additionally checks structured success result, unique completion marker and host file state so startup failure cannot count as path-test success. App cases use independent fixtures; Shell uses a new directory without touching existing distributions.

Device Java line coverage: AAR App **768/815 (94.23%)**, direct `.so` App **667/703 (94.88%)**; both new path-test classes **105/109 (96.33%)**. This covers verification Apps, not PRoot path-translation code.

The host `/usr` comparison uses a dedicated writable fixture without modifying actual Android/Termux system directories. These results verify path mapping, not PRoot as a security sandbox; explicit binds can still modify host files.

Reproduction: `scripts/test-pdn-paths.sh` accepts PDN, loader, official Alpine archive and a nonexistent new directory. Both Apps' full-acceptance buttons include the same path scenarios. Reports, APKs and logs are in `/sdcard/yyd/PDN/path-boundary-v066/`; local coverage is in `build/path-boundary/`.

<a id="alpine-startup-benchmark"></a>

## 0.6.6 Alpine startup timing comparison

On 2026-10-10, the same ARM64 Android environment and official Alpine 3.24.2 archive were used. Timing begins at launcher invocation and ends after the guest executes `printf PDN_BENCH_READY` and exits. It includes launcher, PRoot, guest shell and process cleanup, excluding rish connection, download, installation and terminal UI rendering.

| Environment | Samples | Median | Range |
| --- | --- | --- | --- |
| Original Termux + proot-distro 5.9.0 | 40 | **272.2 ms** | 191.3–286.1 ms |
| Cleared environment, new HOME + proot-distro 5.9.0 | 20 | **268.6 ms** | 263.4–274.8 ms |
| Same clean Termux + PDN Release 0.6.6 | 20 | **35.5 ms** | 30.4–66.0 ms |
| Android shell + PDN Release 0.6.6 | 40 | **40.9 ms** | 31.3–74.9 ms |

The two main groups each ran 21 times per batch with order reversed between batches. First observations were recorded separately; the remaining 40 samples form the statistics. First observations: proot-distro 692.2 / 709.8 ms, PDN Android shell 42.5 / 104.3 ms. These are not strict cold starts after clearing system caches. Environment-cleaning controls each ran 21 times, excluding the first to retain 20 samples.

Cleaning uses `env -i` with only PATH, HOME, TMPDIR, PREFIX, TERMUX paths, LANG and TERM; PDN additionally receives rootfs, loader, temporary-directory and seccomp configuration. New HOME does not read original terminal configuration; inherited LD_PRELOAD, LD_LIBRARY_PATH, ENV and BASH_ENV are removed. Newly installed test Alpine is used without modifying original distributions or Termux configuration. This isolates variables and HOME rather than reinstalling Termux.

Ordinary versus clean proot-distro differs by about **3.7 ms**, insufficient to explain its roughly **230 ms** gap from PDN. PDN in the same clean Termux is about 35 ms, showing fast startup does not require rish. Installed proot-distro 5.9.0 has a Python entry point importing multiple command modules. Launcher and default mounts plausibly contribute, but their individual costs were not isolated; the gap is not attributed to the PRoot engine itself.

Both use default login configuration, with differing mounts and extensions. This compares actual entry-point startup costs, not engine microbenchmarks with identical PRoot arguments, GUI opening speed or long Linux workloads.

Timing reproduction: `scripts/benchmark-pdn-startup.sh OUTPUT_CSV REPETITIONS COMMAND [ARGS...]`. Both use the same Android `date` nanosecond script, including the same small sampling overhead. Raw CSV and summaries are in `build/startup-benchmark/`; delivery copies are in `/sdcard/yyd/PDN/path-boundary-v066/`.

## 0.6.6 Short, medium and long workloads

On 2026-10-10, clean Termux proot-distro 5.9.0 and Android shell PDN Release 0.6.6 used the same Alpine 3.24.2 ARM64 archive, sources and identical guest package versions. Both passed `apk update` and installation of curl, nano, Python, GCC, musl-dev, make and CA certificates. **All 9 task categories passed on both paths.**

Python's monotonic clock inside an already-entered Linux measures tasks, excluding launcher, rish and SSH connection. All programs use guest-absolute paths; PATH excludes Termux program directories so host curl/nano cannot substitute for Alpine programs. Reads, writes and deletions affect only new `/tmp/pdn-workloads-*` fixtures, reclaimed afterward.

| Task | Samples | Termux proot-distro | Android shell PDN | Result |
| --- | --- | --- | --- | --- |
| Write, read, rm deletion | 15 | 13.8 ms | 42.8 ms | Passed |
| apk installed-package query | 10 | 45.1 ms | 101.6 ms | Passed |
| apk add already-installed packages | 5 | 615.8 ms | 1124.8 ms | Passed |
| nano input, save, read, delete | 3 | 465.9 ms | 552.5 ms | Passed |
| curl HTTPS request | 3 | 330.0 ms | 585.3 ms | Passed |
| 64 MiB tar/gzip compression/extraction | 6 | 2110.9 ms | 3374.3 ms | Passed |
| 512 MiB SHA256 calculation | 3 | 715.2 ms | 710.8 ms | Passed |
| Write/stat/read/delete 3000 files | 3 | 8878.7 ms | 9555.6 ms | Passed |
| Compile/link/run 25 C files | 6 | 3650.5 ms | 7105.8 ms | Passed |

Values are medians. Compression and compilation first ran Termux→PDN, then reversed order, combining 6 samples each. Other tasks use the listed counts. CPU frequency and system load were uncontrolled, so this is not a performance guarantee across devices.

- nano uses real PTY input, Ctrl-O/Enter save and Ctrl-X normal exit; saved bytes are checked, then guest cat/rm run. Fixed automation waits are included; this is functional acceptance, not editor-response comparison.
- curl requests `https://example.com/` inside Linux: HTTP 200, TLS verification 0 and correct content. Network variation is included; online apk download likewise does not judge manager performance.
- Compression uses 64 MiB compressible repeated-byte data and checks SHA256 after extraction; this does not represent general file-compression performance.
- Hashing processes 512 MiB and compares with the digest computed before timing; compute performance is essentially equal.
- File batches create 3000 files each round, stat/read/check every file, then actually delete recursively.
- The C project has 25 compilation units; each round compiles, links and executes the result, checking its calculation.

**Faster startup does not imply faster sustained execution.** With this round's default configuration, PDN compression, compilation and some short tasks were slower; pure hashing was similar.

A further control sets only Termux proot-distro's `PROOT_NO_SECCOMP=1`: compression median **3338.3 ms**, compilation **6880.3 ms**, close to PDN's **3374.3 / 7105.8 ms**. At that historical stage the PDN manager disabled PRoot's own seccomp acceleration by default, a major contributor in these two scenarios. This differs from Android zygote seccomp restrictions. Released program/AAR default policy was not changed this round.

Guest package versions include curl 8.22.0-r0, nano 9.2-r0, Python 3.14.8-r0, GCC 15.2.0-r5 and musl 1.2.6-r2. Reproduction script `scripts/test-pdn-workloads.py` supports `--route`, `--report`, selected-task `--only` and repeat-count `--repetitions`. Reports/logs are in `build/task-benchmark/`; delivery copies are in `/sdcard/yyd/PDN/task-benchmark-v066/`.

Separate Python trace collection verifies script line coverage **185/196 (94.39%)**, with all 9 task categories passing. Instrumented results are excluded from performance comparison and do not represent PRoot engine coverage.

Timing-script correction: Android mksh integer arithmetic overflows for nanosecond differences longer than about 2.1 seconds. External timing now uses Android `expr` for 64-bit subtraction; a 3-second regression passes. Earlier startup samples are all below 1 second and unaffected. This round's tasks use the guest monotonic clock; online installation records success without comparing download durations.

## 0.6.6 Native Termux fault-injection tests

On 2026-10-10, native Termux ran `tests/test_pdn_system_errors.py`, temporarily compiling dedicated programs with Python, Clang and NDK; exit code 0.

**1 test method, 9 combinations passed:** ENOLCK, EIO and EINTR lock errors, each across exec/config/uninstall, checking `lock_failed`, original errno, suggestions and rootfs preservation. Temporary test directories were automatically removed; released programs were unchanged.

Log: `build/task-benchmark/ssh-lock-harness.log`.

<a id="r8-release-acceptance"></a>

## 0.6.6 Release/R8 obfuscation acceptance

On 2026-10-10, two independent probe Apps enabled R8 obfuscation, optimization and resource shrinking with Android's default optimization/JNI rules and no additional whole-SDK keep rules. APKs are non-debuggable Release builds signed by a local Debug test key, for acceptance/demonstration rather than production signatures. AAR integration uses the existing Release 0.6.6 AAR; PDN was unchanged and the engine was not republished.

| Integration | App version | R8 device result | Actual obfuscation |
| --- | --- | --- | --- |
| AAR | 0.1.4 / code 5 | **47/47** | 32 engine SDK classes renamed, including PdnConfiguration and PdnCatalog |
| Direct .so | 0.1.2 / code 3 | **39/39** | Classes including NativeRuntime, NativeOperations and ProbeTerminal renamed |

Ordinary App processes test GUI initialization/install/commands/terminal input/resize/close, events and path boundaries. AAR also covers configuration, queries, asynchronous cancellation/timeouts and dual terminals. Both report `passed=true`, `gui_controls_tested=true`, with final instrumentation status `INSTRUMENTATION_CODE: -1`. Release cannot use run-as; full JSON reports are read from instrumentation output.

### Issues found and fixed this round

The old direct .so example PTY adapter depended on `/proc/<pid>` to determine child state. Existence checks failed in non-debuggable Release, preventing GUI terminal opening. Old waitPid returned 0 both for running and successful exit, requiring that extra check. The fix returns -2 for running, -1 for error, normal exit code or 128+signal through waitpid, retries EINTR and caches completion in Java sessions. Full Release acceptance passed after removing /proc dependence.

A new actual-child regression uses an input gate to keep an Android shell child running, then verifies exit 0, exit 37, SIGTERM and error after reaping. It uses real waitpid rather than injection. Only the direct .so example adapter changed; AAR's existing independent session wait mechanism was unchanged.

An additional collection-enabled Debug App passed 39/39 after the fix. Java line coverage **695/731 (95.08%)**: NativePty 91.67%, ProbeTerminal 96.74%, ProbeSuite 97.08%; PTY JNI **94/105 (89.52%)**. Collection builds measure coverage and are not final delivery. Final Release Apps and native inputs were restored to uninstrumented versions. AAR SDK was unchanged this round and retains its earlier coverage above 80%.

### Build and artifact checks

Both APKs passed ZIP, signatures, package name, minSdk 28, targetSdk 35, non-debuggable and native-extraction checks. PDN/loader match original Release bytes; AAR PTY JNI matches its AAR original; direct .so PTY JNI matches this round's uninstrumented example build. Final APKs contain no JaCoCo/LLVM collection components. R8 mapping, configuration, usage, build logs and reports are in `build/r8-acceptance/`; local artifacts/records are in `/sdcard/yyd/PDN/r8-v066/`.

This verifies host Release/R8 integration; the AAR's Debug build type does not mean a new Release AAR was published. It does not establish Maven publication, acceptance on every ROM or arbitrary reflective integration. See both probe READMEs for builds, test signatures and report-reading methods.
