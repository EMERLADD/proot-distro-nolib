# PDN AAR example App

[简体中文](README.md) | English · [README](../../README.en.md)

Independent Java Android project, package `org.example.pdnprobe`, minSdk 28, targetSdk 35, ARM64 only. Uses a local PDN AAR and the Kotlin standard library; no original repository modules, Kotlin plugin or Compose are required.

This is an ordinary demonstration and test App, not a production application. Debug and R8 Release use test signing keys. The terminal is a simple PTY input/output panel, not a complete terminal rendering widget.

## Build

Prepare matching Release files. `prepare.sh` prepares the engine and official Alpine archive used by the demonstration and compiles a file-operation probe, requiring an Android NDK, C compiler and Python 3. The script may download files.

```sh
./prepare.sh
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

When building in Termux, select an aapt2 executable that runs on the host:

```sh
./gradlew -Pandroid.aapt2FromMavenOverride="$(command -v aapt2)" :app:assembleDebug
```

Outputs are under `app/build/outputs/apk/debug/` and `app/build/outputs/apk/release/`. Release enables R8 minification, optimization and resource shrinking with default optimization/JNI rules. Use your own package name and signing key for your production App.

## Usage

The UI provides initialization, Alpine installation, command execution, terminal opening, Ctrl-C, resize and close. Installation needs networking and does not overwrite an existing system. Command output and final errors are displayed separately. “Run all checks” creates a separate temporary instance; some checks need networking.

See [App integration](../../docs/android-embedding.en.md) and the [AAR API](../../docs/pdn-aar-api.en.md) for integrating your own App.
