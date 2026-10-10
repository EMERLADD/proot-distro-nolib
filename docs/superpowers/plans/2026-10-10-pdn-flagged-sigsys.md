# PDN flagged syscall compatibility

## Scope

Close the stale LD_PRELOAD feedback record using the completed investigation. Audit blocked faccessat2 and renameat2, reproduce incorrect compatibility behavior with inherited seccomp TRAP filters, and preserve caller semantics instead of silently dropping flags. Leave App acceleration and workspace APIs unchanged.

## Acceptance

- Real inherited filters exercise both calls with explicit seccomp disable present and absent.
- Tests distinguish regular access and rename fallback from flagged requests; blocked unsupported requests cannot overwrite, exchange or otherwise mutate files.
- Invalid pointers are not dereferenced by an unsupported-call handler.
- Probe cleanup runs on success and failure.
- Unblocked behavior is unchanged.
- Targeted native and manager tests pass, with at least 80% coverage on changed executable lines.
- Synchronize native version, User-Agent and current version expectations if native code changes. Rebuild standard AAR with matching native files.
- Review specification compliance and then code quality; commit and push completed changes.

## Result

Completed: stale feedback closed; old-engine red regressions recorded; native 226 pass / 2 skip, changed C line coverage 100% (3/3), AAR APK 49/49 and direct .so APK 41/41. Specification and quality reviews approved. Delivery uses matching 0.6.10 native files.
