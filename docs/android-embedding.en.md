# Integrating PDN into an Android App

[简体中文](android-embedding.md) | English · [Back to README](../README.en.md)

This guide applies to the current ARM64 Android artifacts. PDN uses Android's libc/libdl at runtime. Downloading, TLS and extraction dependencies are statically linked; the host does not need Termux installed. For standalone usage and rootfs sources, see the [CLI guide](proot-distro-nolib.md) and [rootfs sources](pdn-rootfs-sources.md).

## Choosing Release files

| File | Purpose |
| --- | --- |
| `pdn` | Android shells, MT Manager and other hosts that allow executables to run directly |
| `libpdn.so` | The same program with an APK native-library filename, launched by an App as a process |
| `proot-loader` | The accompanying loader with its ordinary filename |
| `libproot-loader.so` | The accompanying loader with an APK native-library filename |
| Complete `.tar.gz` | Programs, jniLibs layout, guides, corresponding source and license materials |
| `SHA256SUMS` | Checksums for individual downloads and the complete package |

`libpdn.so` has the same bytes as `pdn`. It is still an executable, rather than a shared library exporting JNI functions. Launch it through `ProcessBuilder` or a PTY's `execve`; do not use `System.loadLibrary("pdn")`.

PDN and its loader should come from the same build. GitHub Releases also provide a standalone AAR; see the [AAR API](pdn-aar-api.en.md) for complete usage. Methods such as `install()` and `exec()` belong to the Kotlin wrapper, which assembles paths, environment variables and argument arrays. PDN itself does not expose a C/JNI method API.

## Integrating through the AAR

Download `pdn-engine-VERSION.aar` from the same Release and place it in your App's `libs/` directory. Since 0.6.6, the AAR contains Java/Kotlin APIs and three native files: PDN, its accompanying loader and PTY JNI. All terminal-session APIs remain available; the host App supplies the terminal UI. The standard and lite artifacts have identical contents; import either one.

```kotlin
dependencies {
    implementation(files("libs/pdn-engine-0.6.13.aar"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
}
```

The example standard-library version matches the Kotlin version used by the repository's AAR. Existing Kotlin projects should align the version with their host dependencies. A pure Java App can also call the AAR and does not need the Kotlin Android plugin just to use its API.

The SDK, native-library extraction and network-permission configuration below also applies to AAR integration. Implement `ProotHost`, create operations through `PdnRuntime`, and receive asynchronous events and results through `PdnOperations.start()`. Synchronous `run()` must execute on a background thread. Use `PdnTerminal` for interactive terminals; the host manages the UI and lifecycle.

See the [AAR API](pdn-aar-api.en.md) for directory contracts, Java/Kotlin examples, configuration, cancellation, timeouts and terminal closure. A complete standalone project is available in the [AAR example](../examples/aar-probe/README.en.md). The following steps for placing two ELF files directly apply to hosts managing their own processes. After importing the AAR, do not add duplicate copies of the native files it already contains.

## Packaging into an APK

Place the two files in your App module:

```text
app/src/main/jniLibs/arm64-v8a/
├── libpdn.so
└── libproot-loader.so
```

Configure ARM64 and native-library extraction in `build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        minSdk = 28
        targetSdk = 35
        ndk { abiFilters += "arm64-v8a" }
    }
    packaging {
        jniLibs { useLegacyPackaging = true }
    }
}
```

This uses the SDK configuration of the repository's example App. Standalone PDN targets API 24; this App/JNI example requires API 28 or newer. Other host code may require a higher SDK.

Declare network permission under the Manifest's `manifest` element:

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

After installation, obtain program paths from `context.applicationInfo.nativeLibraryDir`. Store data and project files in `context.filesDir`, and temporary files in `context.cacheDir`. Do not extract the rootfs onto `/sdcard`; shared directories can be mounted into Linux through `--bind`. This approach depends on Android extracting native libraries. Check that the finished APK has `extractNativeLibs=true`.

## Kotlin path and environment wrapper

The repository provides:

- [ProotHost](../android/proot-engine/src/main/java/id/or/oo/pr/engine/ProotHost.kt): the host directory contract.
- [PdnRuntime](../android/proot-engine/src/main/java/id/or/oo/pr/engine/PdnRuntime.kt): directory preparation, argv/environment creation and ProcessBuilder construction.
- [AlpinePackages](../android/proot-engine/src/main/java/id/or/oo/pr/engine/AlpinePackages.kt): package installation, index updates and package queries through exec.

Inside this repository, use the `:proot-engine` module. Other projects should normally import the released AAR. If importing source instead, include all Java/Kotlin APIs you use and their dependencies, and preserve license materials. Using only `PdnRuntime`'s process interface does not require PTY JNI or a terminal UI.

Alternatively, build `:proot-engine:assembleDebug` and place `android/proot-engine/build/outputs/aar/proot-engine-debug.aar` in the host's `app/libs/` directory:

```kotlin
dependencies {
    implementation(files("libs/proot-engine-debug.aar"))
}
```

The host must supply the Kotlin standard library and keep the ARM64, SDK, native-library extraction and network-permission settings above. Java projects can use the Java listener API; runtime dependencies required by the Kotlin implementation must still be retained. The AAR already contains native programs, so do not add duplicate `.so` files with the same names. The engine AAR does not contain a terminal UI module.

```kotlin
import android.content.Context
import id.or.oo.pr.engine.AlpinePackages
import id.or.oo.pr.engine.PdnRuntime
import id.or.oo.pr.engine.ProotHost
import java.io.File

class AndroidPdnHost(context: Context) : ProotHost {
    private val app = context.applicationContext
    override val nativeLibDir = File(app.applicationInfo.nativeLibraryDir)
    override val prefixDir = File(app.filesDir, "usr")
    override val homeDir = File(app.filesDir, "home")
    override val cacheDir = File(app.cacheDir, "pdn")
    override val packageName = app.packageName
}

fun createRuntime(context: Context): PdnRuntime = PdnRuntime(
    host = AndroidPdnHost(context),
    rootfsDir = File(context.filesDir, "linux"),
    projectDir = File(context.filesDir, "projects/current"),
)
```

`prepare()` checks that the program and loader exist and are executable, then creates the host data, cache, rootfs parent and project directories. The default rootfs parent is `prefixDir/var/lib/pdn/rootfs`, and the default project directory is `homeDir/workspace`. Callers can override them explicitly as above.

Main environment variables:

| Variable | Value |
| --- | --- |
| `PDN_ROOTFS_DIR` | Distribution parent directory selected by the host |
| `PROOT_LOADER` | `nativeLibraryDir/libproot-loader.so` |
| `PROOT_TMP_DIR`, `TMPDIR` | Host cache directory; it must exist before startup |
| `HOME`, `APP_HOME` | Host home directory |
| `APP_PREFIX` | Host program-data prefix, unrelated to Termux's `$PREFIX` |
| `APP_PACKAGE` | Current host package name |
| `PROOT_NO_SECCOMP` | The current wrapper sets this to `1`, retaining the existing Android adaptation |

The PDN core selects data locations through `PDN_ROOTFS_DIR` and explicit arguments. `APP_*` variables are part of the existing host-wrapper contract. On entering the guest, PDN sets the guest's HOME, PATH, USER and related environment variables.

## Event listeners and the Java API

`PdnOperations.start(builder, listener, timeoutMillis, executor)` returns a `PdnTask`. It provides stages, progress, classified errors and the final result, with separate stdout/stderr callbacks. A supplied Executor can deliver callbacks to the UI thread; without one, callbacks run on a worker thread. `PdnTask.cancel()` requests cancellation, and a runtime timeout cancels the task. `await()` includes cleanup and completion of the final callback and should only be called on a background thread.

The existing `PdnOperations.run(builder, listener)` remains synchronous, with callbacks on its calling thread. Noninteractive APIs launch processes and do not require PTY JNI or coroutines; terminal APIs use the PTY JNI included in the AAR. Prefer this event API for GUI integration. See the [event protocol, Java/Kotlin examples and AAR structure](pdn-events.en.md).

## Installation and execution APIs

`PdnRuntime` provides `install(name)`, `installAs(distro, instanceName)`, `clone(source, target)`, `rename(source, target)`, `remove(name)`, `login(name, user)` and `exec(name, command, user)`. Each returns a `ProcessBuilder` that has not yet started. Calling `.start()` launches the process; output merging and redirection can be configured beforehand. `remove()` uses `--yes`, so the App must confirm deletion first. `login` and `exec` also accept a complete rootfs `File`. By default, they mount the project directory at `/workspace`, use it as the working directory, and run as guest `root`.

Use separate arguments rather than joining input into a shell command. Wait for processes and read output on a worker thread. This basic example merges output and invokes callbacks on an IO thread:

```kotlin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun runPdn(
    builder: ProcessBuilder,
    onLine: (String) -> Unit,
): Int = withContext(Dispatchers.IO) {
    val process = builder.redirectErrorStream(true)
        .start()
    try {
        process.outputStream.close()
        process.inputStream.bufferedReader().use { reader ->
            reader.forEachLine(onLine)
        }
        process.waitFor()
    } finally {
        if (process.isAlive) process.destroyForcibly()
    }
}
```

Call it from a coroutine or another host task:

```kotlin
val pdn = createRuntime(context)
val installStatus = runPdn(pdn.install("alpine"), onLine)
check(installStatus == 0) { "Installation failed: $installStatus" }

val status = runPdn(pdn.exec("alpine", listOf(
    "/bin/printf", "%s", "two words",
)), onLine)

val shellStatus = runPdn(pdn.exec("alpine", listOf(
    "/bin/sh", "-c", "pwd; ls -la /workspace",
)), onLine)
```

Other common methods:

```kotlin
pdn.version()
pdn.list()
pdn.list(available = true)
pdn.mirrors("alpine")
pdn.install("alpine", mirror = "official")
pdn.installAs("alpine", "ai-python")
pdn.clone("ai-python", "ai-python-test")
pdn.rename("ai-python-test", "workspace-python")
pdn.install("alpine", archive = File(context.filesDir, "alpine.tar.gz"))
pdn.backup("alpine", File(context.filesDir, "backup.tar.gz"))
pdn.restore("alpine-copy", File(context.filesDir, "backup.tar.gz"))
pdn.config("alpine")
pdn.saveConfig("alpine", listOf("--user", "1000:1000", "--work-dir", "/home"))
pdn.clearConfig("alpine")
```

These methods also return a `ProcessBuilder`; they do not start or wait automatically. `config()` queries JSON. `saveConfig()` passes its option list directly to the core, replacing all saved options, while `clearConfig()` removes them. The login and execution wrappers explicitly select the default root identity and `/workspace`, taking precedence over saved configuration. To use the saved identity, working directory or other startup arguments, call `processBuilder()`.

`status` retains the guest command's exit code. The basic example above reads raw text output. When using `PdnOperations`, the wrapper enables and parses the unified JSONL event protocol and delivers stdout/stderr, classified errors and the final result separately. `config --show` and `list --json` return query JSON, distinct from the event stream. The basic example merges stdout/stderr; hosts managing processes directly must consume both streams concurrently if reading them separately, to avoid blocking.

For interactive login, build the process with `pdn.login("alpine")`. To use the PTY interface below, pass `pdn.login(File(pdn.rootfsDir, "alpine")).command()` to the same host's `launcher.startCustomSession(arguments, rows, columns)`. Using the complete path avoids a mismatch between the host's default rootfs parent and a custom directory. `processBuilder(arguments)` remains a general entry point for advanced mounts, environment variables and other arguments.

Ask for deletion confirmation in the App UI before calling `pdn.remove("alpine")`. The host manages background Services, UI and lifecycle. The raw `ProcessBuilder` example above does not implement full task management or guarantee that coroutine cancellation immediately interrupts blocking reads. Use `PdnOperations.start()` / `PdnTask` for cancellation, runtime timeouts and process cleanup. For interactive terminals, use `PdnTerminal` / `PdnTerminalSession` and explicitly terminate and close sessions when their lifecycle ends.

## Graphical Alpine package installation example

The example App provides an “Alpine packages” panel below the Alpine card. Install Alpine through the UI, enter package names such as `git curl` or select common packages, then press “Install packages”. The panel automatically updates the index before installation. “Update index” and “Installed packages” can also be selected separately. It disables duplicate operations and displays logs while running, and reports success only when the exit code is zero. No terminal or command entry is required.

Installing `curl` through this UI has been tested, followed by a successful `curl -v https://example.com/` inside Alpine. This verifies the GUI installation flow and HTTPS access by installed Linux programs. The curl access test ran inside Alpine.

The panel calls `PdnRuntime.exec()` through `AlpinePackages`:

```kotlin
val packages = AlpinePackages(pdn, File(pdn.rootfsDir, "alpine"))
val status = runPdn(packages.install("git curl"), onLine)
val updateStatus = runPdn(packages.update(), onLine)
val listStatus = runPdn(packages.installed(), onLine)
```

Installation runs `/bin/sh -c` in the guest, first executing `apk update`, then `apk add` if the update succeeds. Package names are passed as separate positional arguments rather than inserted into a shell expression. Input accepts only package names and rejects commands, options and paths. Names may contain letters, digits, `+`, `_`, `.` and `-`; whitespace separates multiple packages. Version constraints and installation of local `.apk` files require the general interface.

## Interactive terminals and PTY

Interactive login requires a PTY and a terminal emulator. The repository already provides [ProotLauncher](../android/proot-engine/src/main/java/id/or/oo/pr/engine/ProotLauncher.kt), [PTY JNI](../android/proot-engine/src/main/cpp/ptyjni.c) and [TerminalActivity](../android/app/src/main/java/id/or/oo/pr/TerminalActivity.kt). `libptyjni.so` is the actual JNI shared library loaded through `System.loadLibrary("ptyjni")`. It is not required for the standalone PDN Release; another terminal library may supply its own PTY.

```kotlin
val host = AndroidPdnHost(context)
val launcher = ProotLauncher(host)
val session = launcher.startPdnSession(rootfs, rows = rows, cols = columns)
    ?: error("Cannot start terminal")
session.write("pwd\n".toByteArray())
session.resize(newRows, newColumns)
```

`host` is the host object shown above, and `rootfs` is the complete path to an installed rootfs. Using the repository's PTY implementation also requires building and packaging `libptyjni.so`. Measure rows and columns from the actual layout before starting the session, and call `resize()` when the screen or font size changes. Continuously read bytes through `Session.read()` on a worker thread and send them to the terminal emulator; send keyboard input through `write()`.

The `ProotLauncher` example remains for compatibility. New integrations should use `PdnTerminal` and `PdnTerminalSession`, which support independent PIDs/fds, multiple sessions, exit status and explicit termination/closure. See the [AAR API](pdn-aar-api.en.md) for configuration, project paths, chunked input and lifecycle requirements. Legacy pr-cli calls such as `startSession()` require the host to supply the old native components; the PDN AAR no longer includes them.

## Building and packaging

Build the standalone program and Release files:

```sh
make NDK_PATH=/path/to/NDK
make package NDK_PATH=/path/to/NDK
```

Build output is in `build/proot-distro-nolib/arm64/`, including `jniLibs/arm64-v8a/`. Release output is in `build/packages/`, containing the standalone programs, two `.so` files, complete package and checksums. The publication script requires project changes to be committed so that bundled source corresponds to the built version. The GitHub workflow uploads these files as Actions artifacts and also creates a draft prerelease when its version conditions are met. Before public release, complete all four Android acceptance paths: AAR and direct `.so`, each in Debug and R8 Release. Verify artifact bytes, signatures, Manifest settings, events and PTY. Public Releases also include the complete package and `SHA256SUMS`; see [building and releasing](pdn-build-and-release.en.md).

Build the repository's example APK on Android/Termux:

```sh
sh scripts/build-android-termux.sh assembleDebug --no-daemon
```

The script uses the NDK sysroot, a Clang executable that runs on Android, compatible Gradle and native aapt2. It packages the existing PDN/loader and verifies copied digests. Build standalone PDN first to update the engine. APK output is `android/app/build/outputs/apk/debug/app-debug.apk`.

## Verification scope

Both AAR and direct `.so` integration have passed independent APK tests under ordinary Android App identities, covering installation, command execution, events and terminals. See [test records](pdn-error-testing.en.md) for historical versions, artifact correspondence, coverage and unverified areas. For Android shell debugging, see the [guide](pdn-shizuku-android-shell.en.md).
