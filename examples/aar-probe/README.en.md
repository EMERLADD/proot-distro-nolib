# Standalone PDN AAR verification app

[简体中文](README.md) | English · [Back to README](../../README.en.md)

This is a standalone Gradle project with package name `org.example.pdnprobe`. Its `settings.gradle.kts` includes only `:app`; it does not reference source modules from the original repository, termlib, or the original app's data. The UI and automated acceptance checks use Java and Android platform widgets.

The normal APK depends on the local `pdn-engine.aar` and the Kotlin standard library. The Kotlin classes inside the AAR require the standard library; the Kotlin plugin, coroutines, and Compose are not required. The install button downloads from the official ARM64 source. Full automated acceptance uses the official Alpine 3.24.2 ARM64 archive packaged in assets, extracts it at runtime, and installs it without shipping a pre-extracted rootfs.

## Verified results

Initialization, installation, command execution, events, and terminal integration in the standalone app have passed acceptance on Android hardware. Configuration, queries, asynchronous cancellation/timeouts, and two-terminal operation through the extended interfaces have also been verified. See the [test record](../../docs/pdn-error-testing.en.md) for results and coverage by version.

Additional path checks cover `/usr` mapping, nested binds, links across rootfs boundaries, and missing targets; all fixtures live in separate test directories. This project produces Debug and R8-enabled Release test APKs for integration verification and demonstration. It is not a production app. See the [test record](../../docs/pdn-error-testing.en.md) for individual results.

## Build

Run `prepare.sh` first to place the current PDN AAR at `app/libs/pdn-engine.aar` and prepare the official Alpine archive for acceptance. The script accepts a Release directory and archive path. Importing just the AAR is enough to build, but full acceptance also requires the archive asset. Then run in this directory:

```sh
./prepare.sh
./gradlew :app:assembleDebug
```

For a build inside Termux, specify an executable aapt2:

```sh
./gradlew -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`. The app uses minSdk 28 and targetSdk 35, and currently includes only `arm64-v8a`. The Manifest retains network permission and `extractNativeLibs=true`; programs start from the `nativeLibraryDir` extracted by Android.

Build the R8 acceptance version:

```sh
./gradlew :app:assembleRelease
```

Inside Termux:

```sh
./gradlew -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" :app:assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`. Release enables code obfuscation, optimization, and resource shrinking, using Android's default optimization and JNI rules without additional SDK keep rules. The APK remains non-debuggable and is signed with the local Debug test key, not a production signing key. JaCoCo is disabled; do not pass coverage collection arguments. The current app version is 0.1.9 (versionCode 10).

## UI and acceptance

- Initialize: prepare this app's private directories and call the PDN version API and event listener.
- Install Alpine: install from the official source without overwriting an existing system.
- Execute command: use `exec()` to run the shell command in the input field, reading stdout, stderr, and the final result separately.
- Open terminal: log into Alpine through the AAR's PTY JNI, supporting successive command input, Ctrl-C, 32×96 resize, and close.
- Run all checks: create a new test rootfs directory on every run, validate the embedded official archive, and install Alpine. Verify arguments containing spaces, simulated root, working-directory mounts, project persistence, event order/correlation/a unique final result/callback threads, guest nonzero and signal exits, shell and loader startup error classification and advice, and normal PTY exit.

The terminal is a simple PTY input/output panel. It retains UTF-8 decoding state across chunks; the host can provide full terminal screen rendering separately.

Automated acceptance can run from an authorized shell. The code executes in a normal app process:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am instrument -w org.example.pdnprobe/.ProbeInstrumentation
adb shell run-as org.example.pdnprobe cat files/acceptance.json
```

An existing rish/Shizuku environment can also run:

```sh
rish -c 'am instrument -w org.example.pdnprobe/.ProbeInstrumentation'
rish -c 'run-as org.example.pdnprobe cat files/acceptance.json'
```

If starting instrumentation alone does not bring the UI to the foreground, you can try explicitly opening the Activity from an Android shell. This is a possible workaround and does not guarantee that the Activity startup wait returns:

```sh
am instrument -w org.example.pdnprobe/.ProbeInstrumentation &
probe_test_pid=$!
sleep 2
am start -W -n org.example.pdnprobe/.MainActivity
wait "$probe_test_pid"
run-as org.example.pdnprobe cat files/acceptance.json
```

If `startActivitySync` still blocks, use the offline `-e suiteOnly true` mode below for interface and PTY acceptance, then explicitly automate the GUI buttons with UIAutomator or similar tools for UI acceptance. Offline mode does not establish GUI acceptance. The 0.6.13 round used this approach; see the [test record](../../docs/pdn-error-testing.en.md).

`run-as` is used only to read the report; Linux operations still execute in a normal app process. To export a report to shared storage, use `run-as ... cat ... | cat > output-file`, avoiding direct writes by the app process to a shared file descriptor opened by the host.

The Release APK cannot use `run-as`. Instrumentation writes the full report to standard output; save that output in this directory:

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am instrument -w org.example.pdnprobe/.ProbeInstrumentation > release-acceptance.txt
```

Check `passed=true`, the individual checks, and the final `INSTRUMENTATION_CODE: -1` in the output report. If the UI does not come to the foreground, try the parallel instrumentation and explicit Activity launch steps above, redirecting instrumentation output to a file and omitting the final `run-as` command. rish can save `am instrument -w` output in the same way.

Automated GUI acceptance actually clicks the initialize, install, execute, terminal input/control/resize/close, and run-all-checks buttons. If a manual installation already exists, the install button verifies overwrite rejection; full acceptance still performs a real installation into a fresh directory.

## Coverage acceptance version

```sh
./gradlew -PprobeCoverage=true :app:assembleDebug
```

This optional version adds the JaCoCo test runtime and writes `files/coverage.ec` when acceptance finishes. Normal builds do not include it. Use `passed` and the individual checks in `acceptance.json` to assess the test results. The signature, Manifest, and hashes of the AAR's native files must also pass validation.

See [AAR.sha256](AAR.sha256) for the AAR checksum. The local AAR file is not tracked in Git. After copying this project on its own, place the same AAR in it to build; full acceptance also needs the archive asset.

The archive asset is named `alpine-rootfs.archive` and retains the original gzip bytes; the current build tools automatically decompress and rename assets with a `.gz` suffix. PDN recognizes the compression format from the contents and validates it against the embedded size and SHA256. Acceptance uses `NOFOLLOW_LINKS` to check the `/bin/sh` entry because Alpine's absolute symlink points to the guest's `/bin/busybox`; the Android host cannot use its own `/bin` to decide whether that target exists.

## Offline automated acceptance

`prepare.sh` compiles a static ARM64 file-operation probe and corrects Bionic TLS alignment. It requires the Android NDK, a C compiler, and Python 3. The probe exists only in test APK assets, not in the distributed ELF or AAR.

The official Alpine archive inside the APK can run full interface acceptance directly, avoiding the wait for GUI online installation downloads. It still runs in a normal app process and covers the actual inherited seccomp restrictions, three `openat2` SIGSYS events, `ENOSYS` and the caller's `openat` fallback, tar round trips, events, and PTY. This mode does not perform GUI button acceptance.

```sh
adb shell am instrument -e suiteOnly true -w -r org.example.pdnprobe/.ProbeInstrumentation
```
