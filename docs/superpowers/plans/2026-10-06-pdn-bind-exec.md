# PDN bind and exec implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development for implementation and review.

**Goal:** Expose temporary host bindings and explicit guest command execution in the standalone frontend.

**Architecture:** Extend the existing login parser and reuse its rootfs lookup, locks, environment, guest shell trampoline and PRoot engine. Bindings belong only to the invocation. Exec requires a command and preserves argument boundaries.

**Tech Stack:** C, Android NDK, Python unittest, LLVM coverage.

## Interface

```sh
pdn login ubuntu --bind /sdcard:/mnt/shared
pdn exec ubuntu --bind /sdcard:/mnt/shared -- /bin/ls /mnt/shared
pdn exec --rootfs /private/linux/ubuntu -- /bin/sh -c 'echo hello; id'
```

Allow repeated `--bind` or `-b` after the rootfs selector, before `--`.
Accept `HOST[:GUEST]`: existing regular file or directory, canonical host path,
absolute guest path; omitted guest defaults to canonical host path. Reject empty
components, extra colons and unsupported `!` suffixes. Explicit bindings follow
default bindings. No automatic host directory creation or persistent configuration.
Command/option/name matching ignores ASCII case; paths and guest argv are exact.
Exec requires `-- COMMAND ARG...`; login without a command remains interactive.

## Tasks

- [x] Extend `tests/test_pdn.py` with bind writeback, multiple bindings, file paths,
  spaces, invalid input, invocation isolation, exec argv/exit/stdin and direct rootfs.
- [x] Implement parser and dispatch in `src/proot/src/cli/pdn.c`, retain session locks.
- [x] Review specification, then code quality, fixing findings before release.
- [x] Set version 0.5.0 in CLI/version assertions and update README, manual, changelog.
- [x] Run `make test` with the installed NDK and measure frontend coverage >=80%.
- [x] Check a real installed rootfs using bind plus exec; build must preserve no-Termux dependency checks.
- [x] Commit only task files, push authorized origin/main, copy both binaries to
  `/sdcard/yyd/PDN`, compare SHA256, provide usage.

## Validation

- Production engine tests: 16 passed; final frontend tests: 33 passed; installer tests: 21 passed.
- LLVM frontend coverage: 272/275 lines (98.91%), 260/286 branches (90.91%).
- Existing Alpine, Ubuntu, Debian and Arch rootfs: exec status, host-file reads and writes, fake root groups passed.
- Specification review and final code-quality review approved with no findings.
- Standard release build passed dependency/path checks; no new runtime dependency.
