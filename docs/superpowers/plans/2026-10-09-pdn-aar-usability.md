# PDN AAR usability implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement and review each bounded task.

**Goal:** Implement the six approved AAR improvements while preserving existing Java/Kotlin callers and keeping formal Release/R8/Maven validation outside this task.

**Architecture:** Keep ProcessBuilder and synchronous PdnOperations.run compatible. Add immutable per-operation configuration and asynchronous task handles, then introduce independent terminal sessions with private event channels and structured exit/error callbacks. Obtain distro metadata through optional native JSON query output and package a separate lightweight Debug AAR alongside the existing full artifact.

**Tech Stack:** Java 17, Kotlin standard library, Android library Gradle module, C JNI/PRoot, JSONL v1, JUnit/JaCoCo and LLVM coverage.

## Task 1: Configuration and asynchronous operations

Files: PdnRuntime.kt, PdnListener.java, PdnOperations.java; new PdnConfiguration/PdnBind and PdnTask classes; engine JVM tests.

- [x] Test exact argv, independent configuration copies, custom user/work-dir/binds/guest environment, and Java-compatible overloads.
- [x] Preserve all existing constructors and methods. Configuration applies to exec/login and terminal builders without requiring manual command concatenation.
- [x] Add PdnOperations.start with a task handle supporting cancel, await, completion state and optional timeout. Existing run stays synchronous.
- [x] Preserve ordered event/output callbacks; optional callback Executor is serialized. Add an optional default listener failure callback, with typed cancellation/timeout causes.
- [x] Test completion, queued/running cancellation, timeout, callback failure, executor rejection and concurrent operations. Cancellation must finish process cleanup before await returns.
- [x] Run JVM tests and require at least 80% line coverage on new/touched SDK code.

## Task 2: Native JSON metadata and SDK objects

Files: native pdn.c/pdn_install.c and native tests; new PdnCatalog and immutable Java metadata models; PdnRuntime query builders and JVM tests.

- [x] Add opt-in `list --json`, `list --available --json`, `mirrors [NAME] --json`; keep human output unchanged and JSONL events separate.
- [x] Data envelope is `{"version":1,"distributions":[...]}` or `{"version":1,"mirrors":[...]}`. Available entries include name/version/architecture/download_size; installed entries include name/rootfs; mirror entries include distro/name/base_url/url/priority/official.
- [x] Test empty installed state, valid/invalid aliases, paths requiring JSON escaping, metadata precision, unknown mirror distro and extra argument rejection.
- [x] Expose typed available/installed/mirror query results so hosts do not parse stdout. Do not infer installed version from the current downloadable version.
- [x] Reject malformed or unsupported data envelopes with a typed error; preserve native failure result details.

## Task 3: Independent terminal sessions and structured errors

Files: PtyNative.kt, ProotLauncher.kt, ptyjni.c; new PdnTerminal/PdnTerminalListener; JVM and independent App acceptance.

- [x] Introduce native spawn returning per-session fd and PID atomically; preserve legacy forkPty/getPid methods for existing callers.
- [x] New sessions own their PID/fd, support read/write/resize, input, wait, terminate and idempotent close, and expose normal/signal exit distinctly.
- [x] Report native PTY setup/exec errors with real errno and advice. Validate JNI arrays and dimensions; avoid global-PID races and fd reuse races.
- [x] New high-level terminal uses runtime configuration and a private JSONL channel, forwards native startup diagnostics, output and exactly one completion/failure callback.
- [x] Test two simultaneous terminals, guest exit 0/nonzero/signal, malformed/missing shell, loader failure, repeated close, input after close and interruption cleanup.
- [x] Verify callbacks and real PTY behavior in an untrusted_app process; collect Java and JNI line coverage.

## Task 4: Lightweight AAR and integration

Files: engine Gradle/build packaging scripts, package tests, CI artifact assembly, example probe and docs.

- [x] Keep existing full AAR and old interfaces; add an explicitly named lightweight Debug AAR containing libpdn.so, libproot-loader.so and libptyjni.so only.
- [x] Exclude legacy pr-cli/proot/BusyBox native executables from the lightweight artifact, and verify exact library membership plus native byte/version consistency.
- [x] Build both AARs, test the new SDK through the independent AAR App, and retain default `.so`/ELF release delivery. Do not introduce Maven or R8 validation.
- [x] Increment PDN patch version to 0.6.5; keep User-Agent, current-version docs/tests and delivered native/AAR artifacts consistent.
- [x] Complete spec and quality reviews, document Java/Kotlin usage, callback/lifecycle semantics, cancellation limits and both artifact layouts.
- [x] Commit and push completed work; existing CI delivery policy applies.

No UI framework, automatic Activity lifecycle ownership, background-service policy, distro package-manager abstraction or local PRoot updater is added by this plan. Hosts retain control of their UI and task/session lifetime.

Validation: JVM 90 pass; PDN 203 pass/2 skip; PRoot 16 pass; packaging 9 pass; independent App 33/33 for instrumented and normal lightweight AAR. Full SDK JVM lines 85.54%, unit+device 90.42%, JNI 92.12%, touched native lines 97.92%, packaging Python lines 96.23%.
