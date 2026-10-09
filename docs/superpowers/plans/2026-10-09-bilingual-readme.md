# Bilingual README and Android shell tutorial implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Publish complete Chinese and English README pages with language links and the verified Shizuku → PDN → Ubuntu → Android command workflow.

**Architecture:** Keep README.md as the Chinese entry and add README.en.md with matching coverage. Store detailed tutorials under docs/ with matching language navigation. Include both READMEs in release bundles so relative language links also work offline.

**Tech Stack:** GitHub Markdown, POSIX sh snippets, Ubuntu Bash function.

## Task 1: Chinese entry and verified tutorial

- [x] Add language links and heading-based contents to README.md.
- [x] Add a short Android shell example and a link to docs/pdn-shizuku-android-shell.md.
- [x] Document that an already-working original rish skips setup; private-App initialization must precede entering UID 2000 shell.
- [x] Use the successfully reproduced Bash function with a separate `--` shell name and quoted arguments. Document actual Android setting writes and cleanup, inherited shell permissions, foreground requirement and original-client limitation.
- [x] Correct the stale README claim of two Debian routes to the current single official route.

## Task 2: English pages and release bundle

- [x] Translate the full final Chinese README into README.en.md, preserving technical facts, commands and historical verification scopes.
- [x] Translate the complete new tutorial into docs/pdn-shizuku-android-shell.en.md.
- [x] Add `README.en.md` to the release bundle copy in scripts/package-proot-nolib.sh.

## Task 3: Review and delivery

- [x] Review specification, then wording and command correctness independently: both reviews passed.
- [x] Check local Markdown targets/anchors, paired language navigation, complete section coverage and shell block syntax: 88 local links/anchors, 58 shell blocks; 14 top-level README sections and 10 tutorial sections per language. No new code tests for this documentation change.

Delivery procedure: commit the reviewed files, create the local source bundle and verify both README files and tutorials, then push to the established remote. Archive verification must compare each bundled document with the committed file.
- [x] Refresh standalone tutorials in /sdcard/yyd with reciprocal language links. No APK build or native version bump for this documentation-only change.
