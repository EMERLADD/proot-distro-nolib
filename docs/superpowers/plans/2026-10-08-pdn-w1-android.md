# PDN W1 Android Integration Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development for the bounded Termux build task and execute the integration tasks sequentially.

**Goal:** Package the existing standalone pdn in the existing Android app and exercise installation, command execution and PTY login with host-supplied paths.

**Architecture:** Keep the existing pr-cli integration available for comparison. Add a pure Kotlin PdnRuntime that assembles argv/environment, prepares host directories and creates ProcessBuilder calls. The app defaults to a pdn screen and selects the pdn PTY launcher explicitly. Ship the matching native executable and loader through jniLibs.

**Tech Stack:** Kotlin, Compose, existing C JNI, Android NDK sysroot, Termux Clang, project Gradle wrapper.

## Task 1: Host configuration and command contract

- [x] Add failing assertions to ProotLauncherTest for PDN_ROOTFS_DIR and PROOT_LOADER; run the existing unit test before implementation.
- [x] Add ProotHost.nativeLibDir and override App's existing nativeLibDir property.
- [x] Implement PdnRuntime with default rootfs at prefixDir/var/lib/pdn/rootfs; accept a caller-selected rootfs/project directory.
- [x] Validate installed program and loader; prepare data/cache/project directories before launching.
- [x] Build command lists without splitting strings, pass HOME/PDN_ROOTFS_DIR/PROOT_TMP_DIR/PROOT_LOADER and compatible environment defaults.
- [x] Test custom paths, missing files, invalid directory targets, empty arguments and argv preservation; produce coverage for the runtime module.

## Task 2: Existing terminal and app integration

- [x] Add startPdnSession using the existing PTY bridge, the exact environment and an argv list from PdnRuntime.
- [x] Add a pdn selector to TerminalActivity while retaining the old launcher path.
- [x] Add PdnScreen with only alpine/ubuntu/debian/arch, list local roots, install/remove/login and an exec smoke check.
- [x] Install/remove use stdin closed and stream output on a worker thread; deletion is confirmed by UI before --yes.
- [x] Smoke test id/uname and argument preservation through pdn exec, avoiding unsupported pdn test/OCI commands.
- [x] Keep old workspace directories unchanged and legacy UI reachable.

## Task 3: Termux build support

- [x] Stage deleted termlib files from their local committed snapshot into build/ only.
- [x] Add opt-in Gradle paths for terminal sources and prebuilt JNI; preserve desktop CMake configuration.
- [x] Compile native libraries with Android sysroot and native Clang; stage fresh pdn and matching loader and verify hashes.
- [x] Use compatible Gradle and Termux-native aapt2 without a speculative dependency upgrade.
- [x] Clean app build outputs before assembling the APK.

## Task 4: Verification and delivery

- [x] Run runtime unit tests and coverage, requiring at least 80% on the new runtime.
- [x] Add Android instrumentation coverage for packaged pdn help/version, guest exec and PTY path if device automation is available.
- [x] Verify APK ZIP, signature, manifest package/targetSdk and native entry hashes.
- [x] Copy debug APK and checksum to /sdcard/yyd/PDN, verify copied digest.
- [x] Attempt the requested 3000 ms vibration after verified delivery.
- [x] Explain build and directory layout; leave README unchanged pending user's review.

## Verification evidence

- Runtime tests: 12 passed, 0 failures (11 PdnRuntime tests and 1 launcher environment test).
- PdnRuntime JaCoCo: 43/44 lines (97.7%), 22/24 branches (91.7%). The 80% line coverage gate passed.
- Native JNI libraries were built with Android NDK headers and native Clang; only Android system libraries are required.
- Android instrumentation tests cover packaged manager commands, guest argv/project persistence/exit status, and PTY startup. No ADB device was connected during this build, so these tests have not run.
- A read-only review identified and corrected the instrumentation PTY read argument count.
- APK and instrumentation APK assembled successfully with Gradle 8.13; Android package id.or.oo.pr, ARM64, minSdk 28, targetSdk 35, extractNativeLibs=true.
- APK ZIP integrity, v2 signatures and exact packaged PDN/loader/JNI bytes verified.
- Delivered /sdcard/yyd/PDN/pdn-w1-debug.apk (67,121,106 bytes), pdn-w1-androidTest.apk and SHA256SUMS; copied hashes matched.
- Original W1 APK SHA-256: fe0d4cf8074e016e9d570aa7731ff131c6fabf67a6c342829ba85eb6d249fa5c.

## Terminal display follow-up

- Added persistent 6–30 sp font controls, reset to 12 sp, keyboard visibility control, live columns/rows, and IME insets. Existing resize callbacks propagate geometry changes to the PTY.
- Restricted JaCoCo execution input to jacoco/testDebugUnitTest.exec to fix an incremental build dependency validation error.
- assembleDebug and pdnCoverage succeeded; APK ZIP, signature, manifest and packaged native programs verified. Visual behavior has not been tested through device automation.
- Updated /sdcard/yyd/PDN/pdn-w1-debug.apk and SHA256SUMS. APK size: 67,138,002 bytes. SHA-256: e92d347f66eda39122b4ec0fcda440d8be59e414acc27fe94972a25263aafb73.
- The requested 3000 ms vibration command completed successfully.

## Startup output and legacy UI follow-up

- Explicit root identity now retains -0 without adding redundant -i 0:0. Added a regression for root, 0 and 0:0; it failed against the previous binary and passed after the fix.
- Terminal sessions now start from the first measured terminal size rather than emitting output into a fixed 80-column emulator before layout. The initial emulator uses 1x1 solely to trigger the first real resize; that resize launches the PTY with measured rows/columns.
- Removed the old workspace entry, old distro screen, OCI install UI and its unused helpers. Existing distribution data and packaged compatibility programs remain.
- Expanded the device PTY test to assert 24x42 geometry and absence of the root warning. Device tests compiled but have not run through ADB.
- Production and correctly instrumented coverage builds each passed 78 PDN tests. PDN frontend line coverage remains 334/337 (99.11%), unchanged from the v0.6.0 baseline. APK assembly, signatures, ZIP, manifest and native program equality checks passed.
- Updated both APKs and SHA256SUMS in /sdcard/yyd/PDN; requested vibration completed. Latest main APK: 67,051,014 bytes, SHA-256 9ad0e180d8be17f1fc613b43276da99c9d136df0ff9479cf9fe5e95aa47591d2.

## Directory diagnostics follow-up

- Rootfs install failures now distinguish creation, entry, opening and install-lock creation, printing the selected path, system error/errno, source variable and writable-directory hint. Lock permission failures no longer claim another install is running.
- Login/exec temporary-directory failures now report PROOT_TMP_DIR, TMPDIR or default rootfs/.pdn-tmp selection, system error/errno and a creation hint for missing directories. Default selection and explicit-directory validation remain unchanged.
- Added regression cases for HOME=/, a file in the rootfs path, unwritable rootfs locks, missing/file/unwritable temp paths, precedence, broken default temp links and recovery. Production and coverage suites both passed 83 tests.
- LLVM line coverage: frontend 347/350 (99.14%), installer 476/482 (98.76%). Android runtime coverage gate also passed.
- Updated standalone pdn, proot-loader, both APKs and SHA256SUMS in /sdcard/yyd/PDN. APK signatures/ZIP/manifest/native-byte equality and copied digests verified; vibration completed.
- Main APK: 67,051,482 bytes; SHA-256 20322ca3e1c7d4b714e99790975824f160c640cb0454df7c1cb102ffb96a586f.
- Updated the detailed manual with Android-shell directory setup. README remains pending user approval. The user verified rish version and Alpine installation; guest login through rish is still pending.
