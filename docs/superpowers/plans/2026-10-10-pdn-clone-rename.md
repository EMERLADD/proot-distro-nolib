# PDN clone and rename implementation plan

> Use Superpowers subagent-driven-development for bounded native, SDK and regression work, followed by review and delivery verification.

**Goal:** Clone and rename installed instances through CLI and Java/Kotlin APIs with safe names, locks, identity and link migration.

**Architecture:** Clone reuses bounded archive staging. Rename uses directory publication with a rollback journal for links, validated configuration and identity. Existing locks and protocol v1 remain authoritative.

**Tech stack:** C/PRoot, libarchive, Python unittest, Java/Kotlin, LLVM/JaCoCo, Android Gradle/R8.

- [x] Add failing native clone/rename regressions covering content, metadata, symlinks, config, busy locks, names, invalid input and cleanup.
- [x] Implement native clone, rename and metadata helpers; integrate CLI dispatch and operation events; inspect failure rollback and cancellation.
- [x] Add Java/Kotlin and SO helpers, nullable clone provenance parsing and ABI/argv/validation tests.
- [x] Extend App probes with clone/rename checks; sync patch version 0.6.13 and test App versions.
- [x] Run native and SDK regressions, collect >=80% touched coverage and review implementation against design.
- [x] Build matching AAR and four APKs; verify signatures, mapping, manifest and native bytes; run real device and MT tests.
- [x] Update bilingual docs and TODO, commit/push, publish only accepted artifacts, refresh delivery aliases and clean owned test files.

Acceptance complete: native 275 passed / 2 environment skips, SDK 100 passed, modified C coverage 97.62%, AAR Debug/R8 52 each, direct SO Debug/R8 44 each, both Release GUI button paths, MT and Android shell. All 14 public v0.6.13 assets matched local SHA256; delivery aliases refreshed and owned temporary files removed.
