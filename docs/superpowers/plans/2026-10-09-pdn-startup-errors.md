# PDN Startup Errors Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Give Java/Kotlin callers specific PRoot, loader and guest bootstrap failures without changing normal guest exit classification.

**Architecture:** Record errors at native launch, translation and tracing failure points through the existing JSONL event channel. Only classify bootstrap failures before the primary guest has loaded; carry fork-child launch errors to the parent so one process owns the event sequence. Retain guest_exit for commands which run inside a successfully loaded guest, including exit 126/127 and signals.

**Tech Stack:** C, Python unittest, Android ARM64, Java/Kotlin AAR, LLVM coverage.

### Task 1: Startup classification and regressions

**Files:** `src/proot/src/cli/pdn_events.c`, `pdn_events.h`, `src/proot/src/cli/pdn.c`, `cli.c`, `src/proot/src/tracee/event.c`, `src/proot/src/execve/enter.c`, `exit.c`, `tests/test_pdn_events.py`, `tests/test_pdn_system_errors.py`.

- [x] Add regressions using removed or non-executable `/bin/sh`, malformed ELF, missing ELF interpreter, missing/non-executable loader, and a guest script exiting 126/127. Run `python tests/test_pdn_startup.py -v` against the current binary and observe startup categories fail.
- [x] Add an event helper `pdn_events_startup_problem(const char *component, int saved_errno)` plus a bootstrap predicate, retaining errno in message and preserving a previous specific error. Map loader, guest executable/interpreter, process creation and tracing failures to explicit codes and advice.
- [x] Connect the helper only at actual failure returns. Carry launch-child errors to the parent using a small close-on-exec pipe, closing both ends correctly on success and failure; the child must not write duplicate final event records. Preserve ptrace/seccomp handlers and existing syscall behavior.
- [x] Test injected fork/ptrace failures separately from real filesystem/ELF failures. Validate sequence, exactly one result, suggestion, actual exit code and manager_error; keep guest exits and signal fields unchanged.
- [x] Run targeted tests, review the failure boundaries and measure touched helper coverage at least 80%.

### Task 2: Delivery

**Files:** `src/proot/src/cli/proot.h`, `src/proot/src/cli/pdn_install.c`, `tests/test_proot_nolib.py`, `README.md`, `CHANGELOG.md`, `docs/pdn-events.md`, `docs/pdn-error-testing.md` and current-version references.

- [x] Increment PDN 0.6.3 to 0.6.4, keeping User-Agent, tests and current-version docs consistent. Do not build an APK.
- [x] Run `make test` and the engine Java unit tests with the rebuilt native fixture; build AAR, verify packaged ELF bytes against native output, run release packaging checks.
- [x] Update the event API with all implemented startup codes and their diagnostic limits. Record each real, injected or mapping test accurately.
- [x] Review the diff for scope/privacy, run `git diff --check`, commit only project files, push origin/main and verify release AAR, .so and ELF assets.
