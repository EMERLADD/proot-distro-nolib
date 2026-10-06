# Supplementary groups compatibility plan

> **For agentic workers:** Use superpowers:executing-plans in the authorized workspace.

**Goal:** Remove Android supplementary group leakage from fake-identity guest sessions across all distributions, including already installed rootfs.

**Architecture:** Emulate getgroups/setgroups in fake_id0, with an initially empty guest supplementary list. Preserve the real Android credentials for filesystem/network access. Store guest groups in the per-tracee config, copy on fork, and preserve through exec.

**Tech Stack:** C PRoot syscall interception, talloc, Android syscall probe, Python unittest, LLVM coverage.

- [x] Implement guest group state and native/legacy-width transfers, syscall argument/permission checks, and inheritance without touching host groups.
- [x] Test initial groups, errors, set/clear, ordering, fork isolation, exec inheritance, non-root rejection and both seccomp settings.
- [x] Rebuild and test login on existing Alpine, Ubuntu, Debian and Arch rootfs without editing their group files; verify network access.
- [x] Measure new/changed group handling coverage, run regression tests and record v0.4.1.
- [x] Commit, push and copy matching binaries to the established delivery directory.

Validation: 61 regression tests passed, including guest supplementary-group syscall semantics and unchanged host groups. Both seccomp environment states passed. New group helper line coverage 92.59%; four existing distributions logged in with only the root group and unchanged group files. Ubuntu apt update succeeded. Native ARM64 exercised; legacy 16-bit transfer branches are not device-tested.
