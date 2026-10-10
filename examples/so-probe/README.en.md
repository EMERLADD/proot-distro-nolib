# PDN verification app packaging .so files directly

[简体中文](README.md) | English · [Back to README](../../README.en.md)

This standalone Java project uses package name `org.example.pdnsoleprobe`, minSdk 28, and targetSdk 35. Gradle includes only `:app`, without an AAR, Kotlin, engine source modules, or dependencies on the original app. `NativeRuntime` uses private directories and an environment supplied by the host, launching the matching ELF files in `nativeLibraryDir` through ProcessBuilder. `NativeOperations` reads JSONL and delivers callbacks.

Installation, command execution, events, PTY, and startup error handling in this standalone app have passed acceptance on Android hardware under a normal Android app identity. See the [test record](../../docs/pdn-error-testing.en.md) for versions, individual results, and coverage.

Additional path checks cover `/usr` mapping, nested binds, links across rootfs boundaries, and missing targets; all fixtures live in separate test directories. This project produces Debug and R8-enabled Release test APKs for integration verification and demonstration. It is not a production app. See the [test record](../../docs/pdn-error-testing.en.md) for individual results.

## Build

Prepare `libpdn.so` and `libproot-loader.so` from the [Release](https://github.com/EMERLADD/proot-distro-nolib/releases) matching the current `PDN_VERSION`, plus the official Alpine 3.24.2 ARM64 archive, then run:

```sh
./prepare.sh /your/Release-directory /your/alpine-minirootfs-3.24.2-aarch64.tar.gz
./gradlew --offline -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" :app:assembleDebug
```

The script reads `PDN_VERSION` from `src/proot/src/cli/proot.h`. Without arguments, it uses the repository's `build/releases/vVERSION` (`VERSION` is the current version) and `build/pdn-sources`. The SDK uses `ANDROID_HOME` or `ANDROID_SDK_ROOT`; set `PDN_NDK_DIR` to select the NDK, which defaults to 26.3.11579264. Termux uses an executable Clang with the NDK sysroot; Linux hosts use NDK Clang by default. Remove `--offline` when fetching Gradle dependencies for the first time.

Native files go in `app/src/main/jniLibs/arm64-v8a/` and the archive goes in assets. These inputs are not tracked in Git. The archive is named `alpine-rootfs.archive` and retains the original gzip bytes, preventing the build tools from automatically decompressing a `.gz` asset. PDN validates the embedded size and SHA256 before installation.

The script also compiles this project's own `libprobepty.so`, which handles PTY, input/output, resize, and child-process reaping. It depends only on Android libc/libdl and has 16 KiB alignment. `libpdn.so` and the loader are packaged unchanged as executable ELF files and run as processes; only the project's PTY adapter is loaded through System.loadLibrary.

Build the R8 acceptance version:

```sh
./gradlew :app:assembleRelease
```

Inside Termux:

```sh
./gradlew --offline -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`; Debug output remains `app/build/outputs/apk/debug/app-debug.apk`. Release enables code obfuscation, optimization, and resource shrinking, using Android's default optimization rules without broad keep rules. The APK remains non-debuggable and is signed with the local Debug test key, not a production signing key. JaCoCo is disabled. Before building, use normal `./prepare.sh` inputs without setting `PDN_PROBE_NATIVE_COVERAGE`; do not pass coverage arguments at runtime. The current app version is 0.1.7 (versionCode 8).

## Run acceptance

The UI supports initialization, installing Alpine, executing commands, opening a terminal, sending successive input, Ctrl-C, resize, and close. The install button uses the official online source; full acceptance uses the embedded official archive, creates a new rootfs on every run, and places error fixtures in separate directories.

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w org.example.pdnsoleprobe/.ProbeInstrumentation
adb shell run-as org.example.pdnsoleprobe cat files/acceptance.json
```

Run the same Android shell commands in rish. If instrumentation does not bring the UI to the foreground, you can try this workaround; it does not guarantee that the Activity startup wait returns:

```sh
am instrument -w org.example.pdnsoleprobe/.ProbeInstrumentation &
probe_test_pid=$!
sleep 2
am start -W -n org.example.pdnsoleprobe/.MainActivity
wait "$probe_test_pid"
run-as org.example.pdnsoleprobe cat files/acceptance.json
```

If `startActivitySync` still blocks, use the offline `-e suiteOnly true` mode below for interface and PTY acceptance, then explicitly automate the GUI buttons with UIAutomator or similar tools for UI acceptance. Offline mode does not establish GUI acceptance. The 0.6.13 round used this approach; see the [test record](../../docs/pdn-error-testing.en.md).

`run-as` only exports the report; acceptance requires the actual operation process to be `untrusted_app`. To export to shared storage, use `run-as ... cat ... | cat > output-file`. The report retains individual results and instrumentation failure reasons; acceptance passes only with `passed=true` and the final `INSTRUMENTATION_CODE: -1`. JSONL and the latest stderr are saved under the private cache/engine directory.

The Release APK cannot use `run-as`. Instrumentation writes the full report to standard output; save that output in this directory:

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am instrument -w org.example.pdnsoleprobe/.ProbeInstrumentation > release-acceptance.txt
```

Check `passed=true`, the individual checks, and the final `INSTRUMENTATION_CODE: -1` in the output report. If the UI does not come to the foreground, try the parallel instrumentation and explicit Activity launch steps above, redirecting instrumentation output to a file and omitting the final `run-as` command. rish can save `am instrument -w` output in the same way.

PTY child-process status comes from `waitpid` and does not depend on `/proc/<pid>` visibility. Running and successful exit with code 0 are represented separately. Automated acceptance covers running, exit codes 0/37, signal termination, and already-reaped states.

See the [test record](../../docs/pdn-error-testing.en.md#apk-integration-acceptance) for individual checks, ways to trigger errors, and JNI boundary checks.

## Coverage acceptance build

```sh
PDN_PROBE_NATIVE_COVERAGE=1 ./prepare.sh
./gradlew --offline -PprobeCoverage=true -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" :app:assembleDebug
```

Add `-e nativeCoverage true` when running instrumentation. It generates `files/coverage.ec` and `files/native-coverage.profraw` on completion. LLVM instruments only this project's PTY JNI; the Release PDN/loader stay unchanged. Normal builds do not enable these collection switches.

## Offline automated acceptance

`prepare.sh` compiles a static ARM64 file-operation probe and corrects Bionic TLS alignment. It requires the Android NDK, a C compiler, and Python 3. The probe exists only in test APK assets, not in the distributed ELF or AAR.

The official Alpine archive inside the APK can run full interface acceptance directly, avoiding the wait for GUI online installation downloads. It still runs in a normal app process and covers the actual inherited seccomp restrictions, three `openat2` SIGSYS events, `ENOSYS` and the caller's `openat` fallback, tar round trips, events, and PTY. This mode does not perform GUI button acceptance.

```sh
adb shell am instrument -e suiteOnly true -w -r org.example.pdnsoleprobe/.ProbeInstrumentation
```
