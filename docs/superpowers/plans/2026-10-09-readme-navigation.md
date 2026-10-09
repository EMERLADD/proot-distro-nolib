# README navigation refactor implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development for bounded implementation and independent reviews.

**Goal:** Lead both READMEs with “0. What is this?”, provide detailed quick-start entry points, and place current limitations after FAQ.

**Architecture:** Keep Chinese and English README pages as matching entry points with language switches. Move distribution procedures, build/release details and FAQs into paired docs; retain the verified Android shell tutorial and API guides. Preserve historical verification scopes and correct stale current instructions encountered at linked entry points.

**Tech Stack:** GitHub Markdown and existing shell/Java/Kotlin examples. Documentation only; keep PDN 0.6.6 and skip APK builds.

## Entry pages

- [x] Rewrite README.md with numbered sections 0–9, matching contents, an introduction that describes runtime independence, AAR and direct ELF integration, and actual Android shell debugging.
- [x] Expand quick start into shell deployment, distro management, Shizuku debugging, AAR integration and direct `.so` integration, each linked to its detailed guide.
- [x] Preserve version-specific MT/AAR/`.so` verification and put current limitations immediately after FAQ.
- [x] Translate the final structure into README.en.md with matching claims, code and navigation.

## Supporting guides

- [x] Move distribution commands to docs/pdn-distributions.md and its English counterpart, including fixed sources, execution, mounts/config, backup and command index.
- [x] Move build/CI/release and license details to docs/pdn-build-and-release.md and its English counterpart.
- [x] Add docs/pdn-faq.md and its English counterpart with practical troubleshooting and links to current API/shell documentation.
- [x] Correct stale current-version/source/AAR publication statements in existing linked manuals. Preserve historical test records.

## Verification

- [x] Specification review, then quality review, with no unrelated runtime changes.
- [x] Verify relative links/anchors, language navigation, shell syntax, original content coverage and matching README structure: 193 local links/anchors, 60 shell blocks, matching sections 0–9 and five quick-start branches.

Delivery verification: after committing the reviewed source, check both README pages and all six new guide files against the local release bundle and its corresponding source archive.

Delivery procedure: commit and push after validation, refresh /sdcard/yyd/ tutorial navigation as needed, and report the final directory structure. No new runtime tests for this documentation-only refactor.
