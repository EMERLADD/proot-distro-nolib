# Optional guest /dev/full compatibility

## Scope and design

Provide an opt-in ARM64 guest device compatibility extension, enabled by PROOT_EMULATE_DEV_FULL=1. Default sessions keep existing acceleration filters and behavior. Redirect the canonical host path /dev/full only when the native device is absent or inaccessible; explicit binds to another file retain their behavior.

Use one securely created temporary backing inode with lifetime shared across tracees. Identify guest descriptors by actual kernel inode, rather than remembering fd numbers, so duplication, close/reuse, fork, exec and descriptor transfer preserve identity. Emulate basic scalar/vectored/positioned reads and writes, seek and character-device metadata. Reject operations unsupported by a full device rather than silently treating the backing as a regular file. This is guest compatibility, not creation of a host kernel device or a complete virtual kernel. Emulated reads may return at most 64 KiB; native 64-bit guest ABI is supported, preadv2/pwritev2 return EOPNOTSUPP, and asynchronous I/O is outside scope.

## Acceptance

- Reads yield zeros; writable writes yield ENOSPC; access modes and invalid arguments remain meaningful.
- dup variants, fcntl duplication, fork/exec inheritance and fd reuse retain correct behavior; ordinary /dev/zero and files are unaffected.
- Relative paths, symlink aliases and explicit binds are tested.
- Native device and disabled feature paths preserve behavior.
- No root permissions or Termux runtime dependency.
- Real guest tests, manager regression, touched C line coverage at least 80%, then specification and quality review.
- Synchronize native version, User-Agent, tests and matching AAR/ELF/.so delivery; document activation and limits.

## Results

Native regression: 238 passed, 2 host-environment skips. New device groups: 12/12. Extension LLVM line coverage: 348/401 (86.78%); touched C scope: 353/406 (86.95%). Independent ordinary App checks: AAR 50/50, direct SO 42/42. APK signatures and embedded native bytes verified. Specification and quality reviews approved within the documented synchronous native64 scope. Existing accessible real host full-device branches remain untested without a device fixture. MT screenshot added to both README introductions, with original retained.
