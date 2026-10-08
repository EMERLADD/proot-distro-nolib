# PDN W2 Events Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development for the Java bridge and review, and test-driven-development for native integration. The primary agent owns integration, versioning and Git delivery.

**Goal:** Deliver versioned native operation events and Java/Kotlin listener APIs without mixing guest output with metadata.

**Architecture:** PDN writes JSONL to a dedicated private regular file selected by `PDN_EVENT_FILE`; `PDN_OPERATION_ID` identifies the operation. The Android bridge creates the file in the host-selected cache, drains stdout and stderr independently, tails complete event lines and delivers typed callbacks. Guest load/exit observations distinguish startup failures from guest outcomes without changing guest exit codes or ptrace behavior.

**Tech Stack:** C, POSIX file descriptors, Java 17-compatible POJOs/listeners, Android JSONObject, Kotlin/Compose; cached JVM org.json test implementation.

## Protocol v1

Every record has `version:1`, `operation_id`, increasing `sequence`, `operation` and `type`. Types: `started`, `stage`, `progress`, `error`, `result`. Optional fields: `stage`, `current`, `total`, `percent`, `code`, `message`, `suggestion`, `outcome`, `exit_code`, `guest_exit_code`, `guest_signal`, `signal`. Outcomes: `success`, `manager_error`, `guest_exit`, `cancelled`.

A result is emitted exactly once after cleanup. Error records are diagnostics and may precede a successful mirror fallback; only result is final. Missing progress totals use -1/omit percent. Guest bytes stay on original stdout/stderr. Native events contain no guest argv or environment values. Callers treat raw logs separately. Event progress is throttled; records are bounded and UTF-8, and complete newline-terminated records are required.

`PDN_EVENT_FILE` must name a private, empty, owned regular file (or a new file); no symlinks, special files or shared-mode files. Native opens with close-on-exec and unsets protocol environment before guest launch. Invalid requested channels fail before operation side effects. An absent channel preserves existing CLI behavior. Native `PDN_EVENT_FD` is not required for this implementation.

## Tasks

- [x] Native producer: add `pdn_events.[ch]`; test opt-in/disabled output, IDs, escaping, channel rejection and exactly one final result. Wrap command dispatch; add install phases/download progress, backup/restore byte events, root/temp classified errors and observational guest load/termination hooks.
- [x] Java bridge: `PdnEvent`, `PdnResult`, `PdnListener`, `PdnOperations`. Blocking `run(ProcessBuilder, PdnListener)` returns `PdnResult`; caller runs off main thread. Independently drain both guest streams, tail bounded JSONL records and preserve actual process status. Explicit fallback errors for missing/invalid events. Callbacks serialized on run's calling thread; callback exceptions clean up and propagate. Add `@JvmOverloads` for existing runtime default-argument APIs and a Java compilation/behavior test.
- [x] GUI: use listener API in existing operation worker; show native phase, determinate/indeterminate progress, classified failure reason and suggestion. Keep guest logs read-only and bound retained text.
- [x] Verify: native regressions and instrumented coverage >=80%; Java/Kotlin tests and bridge coverage >=80%; compile App Kotlin without assembling APK. Run a native-producer to Java-consumer contract test.
- [x] Docs/delivery: protocol reference and Java/Kotlin examples; bump PDN to 0.6.2 and App metadata to 1.0.2/code3; update README/CHANGELOG; commit and push. Do not build APK or claim on-device UI verification.

## Verification

144 native tests ran: 143 passed; 1 hardlink fixture skipped because this Android environment cannot create the required link. Native line coverage: producer 97.27%, frontend 98.43%, installer 98.81%, archive 97.07%. JVM: 42 tests passed, 325/337 lines covered (96.44%), including native guest success/nonzero/signal and external-loader contracts. App Kotlin compiled; Debug AAR contents and bundled PDN/loader hashes verified. No APK assembled and no new on-device GUI claim.
