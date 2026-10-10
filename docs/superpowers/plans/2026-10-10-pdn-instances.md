# PDN named instances implementation plan

> **For agentic workers:** Use superpowers:subagent-driven-development for bounded implementation and specification/quality review.

**Goal:** Install multiple named instances of a distro and expose stable identity and provenance to Java/Kotlin Apps.

**Architecture:** A bounded private metadata module writes inside staged rootfs before publication; existing paths and locks remain the source of instance selection. Structured list adds optional instance data without changing protocol v1. Archive restore resets identity; SDK methods retain old overloads.

**Tech Stack:** C/PRoot, Python unittest, Java/Kotlin, Android Gradle/R8, LLVM/Jacoco coverage.

- [x] Implement and test `cli/pdn_instance.c/.h` bounded create/read/JSON helpers; link in GNUmakefile. Install/archive harness link the module without changing legacy pdn_install wrapper signature.
- [x] Add pdn_install_as(name, archive, mirror, alias), preserving pdn_install wrapper. CLI parses --name plus mutually exclusive --archive/--mirror and duplicate validation. Publish under alias and write metadata before publish.
- [x] Extend installed list JSON with optional instance object; retain plain names and legacy metadata=null; avoid partial JSON on failure. Integrate archive restore with fresh identity and provenance preservation.
- [x] Add SDK immutable PdnInstanceInfo, installed metadata getters and installAs overloads, plus strict optional-object parsing and focused JVM tests. Direct SO helper and both probes test independent alias installs and restore identities.
- [x] Sync 0.6.12 version/User-Agent/test expectations and App patch/code metadata. Build native, run targeted then full suites, collect touched LLVM and SDK coverage, resolve specification and quality reviews.
- [x] Build matching AAR and four Debug/R8 test APKs, verify bytes/mapping/signatures/manifests and device results. Commit/push, obtain draft candidate, validate candidate bytes or rebuild Apps for CI candidates before public release. Deliver versioned and alias artifacts to /sdcard/yyd/PDN, update docs and clean owned temporary fixtures.
