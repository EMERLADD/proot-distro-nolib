# PDN inherited statx SIGSYS correction

Scope: native ELF only. Preserve enabled acceleration behavior for guest-installed filters; repair inherited-filter full tracing where ORIGINAL contains entry arguments. Do not claim this reproduces every MT tar error.

- [x] Add a real inherited statx TRAP launcher and path/fd statx probe; reproduce failure before repair.
- [x] Read ORIGINAL statx inputs in non-accelerated SIGSYS handling, preserve CURRENT behavior in enabled acceleration where entry snapshot may be stale.
- [x] Bump native ELF to 0.6.8, sync native version assertions/User-Agent/docs; existing AAR stays 0.6.6.
- [x] Build native ELF; targeted regressions, inherited-filter direct files/relative dirfd/empty fd/statx/tar stress and actual Codex archive verification.
- [x] Changed-line coverage >=80%, specification and quality reviews.
- [x] Deliver native files and summaries, cleanup only created fixtures, commit and push.

Evidence before repair: regular GNU tar stress passed old/new/disabled; inherited statx TRAP makes GNU stat fail relative path ENOTDIR on all three, and reports incorrect size for an absolute regular file. ARM64 CURRENT x0 aliases return value, so directory fd is lost after syscall exit. MT original Codex extraction failure is not yet directly reproduced. No APK/AAR rebuild requested.

Completed: native ELF 0.6.8 delivered; 223 regression tests pass, 2 skip; five full filesystem stress cases pass; real Codex archive content verified. Changed executable C lines 2/2 covered. MT original tar failure remains to be checked in MT. All created device test roots, downloaded archives, extraction copies and raw coverage profiles cleaned; scripts/logs/artifacts preserved.
