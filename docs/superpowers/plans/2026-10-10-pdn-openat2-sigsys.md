# Blocked openat2 fallback implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development to implement and review this narrow change.

**Goal:** Restore GNU tar extraction in Android App environments where inherited seccomp blocks openat2.

**Architecture:** Return ENOSYS for blocked openat2 instead of rewriting incompatible arguments to openat. The caller decides whether to fall back; unsupported resolve constraints are never silently discarded. Unblocked calls retain their existing behavior.

**Tech Stack:** C, Android NDK, Python unittest, LLVM coverage, MT Ubuntu over local SSH.

- [x] Add inherited BPF RET_TRAP regression for openat2, with directory/create/invalid-pointer requests and explicit openat fallback. Run against 0.6.8 and observe failure.
- [x] Replace PR_openat2 restart with `set_result_after_seccomp(tracee, -ENOSYS); break;` and verify the actual SIGSYS chain with and without PROOT_NO_SECCOMP.
- [x] Synchronize native patch version to 0.6.9, download User-Agent and current native version assertions/docs; preserve the existing published Release.
- [x] Build original ELF, run engine and manager tests, measure changed-line coverage at least 80%, perform spec and quality review.
- [x] Validate the new ELF in both ordinary App integration paths (AAR 49/49, direct .so 41/41), and GNU tar filesystem stress under inherited openat2 TRAP.
- [x] Connect to the original MT outer shell via socat, verify the 0.6.9 binary hash, start Ubuntu anew, pass the original diagnostic and full stress test, and clean owned fixtures.
- [x] Refresh native delivery aliases and versioned artifacts, verify version and --version, commit and push authorized changes.
