# PDN AAR API

[简体中文](pdn-aar-api.md) | English · [Back to README](../README.en.md)

The AAR packages PDN's native components and provides configuration objects, asynchronous tasks, independent terminals, and structured distribution queries. It requires Android 9 (API 28) or later and currently supports ARM64. The host supplies the Kotlin standard library; Compose, coroutines, and Termux are not required.

## Artifacts and layout

| File | Contents |
| --- | --- |
| `pdn-engine-lite-VERSION.aar` | Java/Kotlin API, `libpdn.so`, `libproot-loader.so`, `libptyjni.so` |
| `pdn-engine-VERSION.aar` | Java/Kotlin API, `libpdn.so`, `libproot-loader.so`, `libptyjni.so` |
| `libpdn.so`, `libproot-loader.so` | PDN and loader ELF executables for direct packaging |
| `pdn`, `proot-loader` | Original ELF executables of the same version |

Import `pdn-engine-VERSION.aar`. The `pdn-engine-lite-VERSION.aar` filename is a compatibility alias: both files contain identical bytes, so choose one. Terminal sessions, input/output, resizing, and multiple sessions remain available; the host App supplies the terminal UI. Legacy `ProotLauncher` classes and methods remain for API compatibility, but calls that depend on the old pr-cli, such as `startSession()`, cannot run using this AAR alone. Use `PdnTerminal`, or supply the legacy native programs yourself. The repository's original App packages its legacy components separately.

Within the AAR, `classes.jar` contains the API, `jni/arm64-v8a/` contains native files, and the Manifest and Android metadata are merged during the App build. PDN and the loader are executables; `libptyjni.so` is an actual JNI shared library. The AAR includes neither an installed Linux distribution nor a terminal screen rendering component.

When importing a local AAR through Gradle, keep `extractNativeLibs=true` and `jniLibs.useLegacyPackaging=true` so Android extracts the executables into `nativeLibraryDir`. See the [App integration guide](android-embedding.en.md) for the complete configuration. The host provides program, data, cache, project paths, and its package name through `ProotHost` and the runtime constructor.

## Configuration

```kotlin
val configuration = PdnConfiguration(
    "root",
    "/workspace",
    listOf(PdnBind(extraDirectory, "/data")),
    mapOf("LANG" to "C.UTF-8")
)
val pdn = PdnRuntime(host, rootfsDirectory, projectDirectory, configuration)
val operations = PdnOperations(pdn)
```

Configuration copies the bind list and environment map and is immutable after construction. The account, guest working directory, additional binds, and guest environment apply to `login()`, `exec()`, and terminals using the same ProcessBuilder. Environment values are passed as separate argv entries, preserving spaces, quotes, and `$` literally.

The project directory is bound to `/workspace` by default; an explicit bind to that guest path replaces it. Passing a complete `PdnConfiguration` for one call replaces the runtime configuration. The legacy `user` argument overrides only the account, retaining the remaining settings. Runtime configuration does not replace a distribution's saved `pdn config` file.

## Asynchronous tasks

Java can call the API directly:

```java
PdnTask task = operations.start(
    pdn.install("alpine"),
    new PdnListener() {
        @Override public void onEvent(PdnEvent event) {
        }
        @Override public void onComplete(PdnResult result) {
        }
        @Override public void onFailure(Exception failure) {
        }
    },
    120_000L,
    context.getMainExecutor()
);
```

`start()` submits work to a background thread and copies the ProcessBuilder command, environment, and working directory. `onStdout` and `onStderr` receive raw bytes. Events and output callbacks are delivered serially, in processing order, to the supplied Executor. Without an Executor, callbacks run on the worker thread. The operating system determines ordering between stdout and stderr.

`task.cancel()` requests cancellation, and `isDone()` checks completion. `await()` returns a `PdnResult` or throws `ExecutionException`; wait only on a background thread. A timeout in `await(timeout, unit)` does not cancel the task. A running timeout supplied to `start()` does cancel it.

Cancellation and running timeouts are reported through `PdnHostException`, using `host_operation_cancelled` and `host_operation_timeout` with a reason and suggestion. Cleanup first attempts SIGTERM so PRoot can stop and reap the guest, then forcibly terminates if needed. `await()` includes process cleanup and completion of the final callback.

The worker pool runs at most four tasks concurrently and queues at most 128. A full queue is reported through the failure callback. A cancelled queued task completes without starting a native process when a worker reaches it. The timeout starts at submission and includes time spent queued.

The Executor must run callbacks it accepts, and listeners must return; a callback already running can delay cancellation completion. Do not wait for the same task on its callback thread. If the Executor rejects a callback, failure notification falls back to the current processing thread. A nonzero exit after a Linux command starts is still delivered through `onComplete(PdnResult)`; Java exceptions alone do not establish guest success.

The original `operations.run(builder, listener)` remains synchronous, with callbacks on the calling thread.

## Independent terminals

```java
PdnTerminalSession session = new PdnTerminal(pdn).start(
    pdn.login("alpine"), 24, 80,
    new PdnTerminalListener() {
        @Override public void onOutput(byte[] data) { }
        @Override public void onEvent(PdnEvent event) { }
        @Override public void onComplete(PdnResult result) { }
        @Override public void onFailure(Exception failure) { }
    }
);
session.resize(32, 96);
session.write("apk add nano\n".getBytes(StandardCharsets.UTF_8));
```

Each session owns its PID and PTY fd. The high-level terminal reads output and JSONL events and reports the final result after cleanup. Listeners run on that terminal's background monitoring thread; the host dispatches UI changes to its main thread. Multiple sessions do not overwrite each other's PID.

`write()` may write only part of the input; retry later if it returns zero. Loop to write an entire input buffer. `resize()` accepts rows and columns in the range 1–65535. PTY combines stdout and stderr into one byte stream; the host handles UTF-8 decoding across chunks and terminal rendering.

`poll()` returns null while running and a cached `PdnTerminalStatus` after exit. `waitFor()` waits for and reaps the child. Exit code and terminating signal are available separately, so a normal exit of zero is distinct from a running process. `waitFor(timeoutMillis)` returns null if the process has not exited before the timeout.

`terminate()` first sends SIGTERM, then SIGKILL if the process has not stopped after 500 ms. `close()` terminates, reaps, and closes the fd and can be called repeatedly. Input, reads, and resize throw after the session closes. A process in an uninterruptible kernel wait can delay reaping; run close operations on a background thread. The host closes tasks and sessions when their Activity, Service, or workspace ends.

PRoot, loader, and guest shell startup failures use the classification, message, and suggestion in `PdnResult`. Host failures in PTY creation, exec, and I/O use `PdnTerminalException`, with the operation, actual errno, and suggestion; failures are not silently converted to null.

To read PTY yourself, use `PdnTerminalSession.start(builder, rows, cols)`. A positive read result is a byte count, -1 is a PTY hangup, and zero means no data currently available or EOF. Use `poll()` to distinguish exit, and drain remaining output afterward. Do not read the same fd concurrently with high-level `PdnTerminal`. New sessions use the complete `ProcessBuilder.environment()`; clearing it really removes inherited variables.

## Installation aliases and independent instances

```kotlin
val operation = pdn.installAs("alpine", "ai-python", mirror = "official")
```

```java
ProcessBuilder operation = pdn.installAs("alpine", "ai-python");
ProcessBuilder offline = pdn.installAs("alpine", "ai-node", null, archiveFile);
```

Like `install()`, this returns a ProcessBuilder for execution through the existing operation API. The third and fourth arguments select a mirror or a pinned-version local archive and are mutually exclusive. Subsequent exec, login, backup, and remove operations use the instance name. Rootfs data is independent between instances; explicitly bound shared project directories remain shared.

Metadata lives in `.pdn-instance` inside the rootfs. Installation publishes only after writing metadata in the staging directory. Restore preserves known source versions and digests and generates a new ID, name, and timestamp. Invalid metadata makes `list --json` return `instance_metadata_invalid` without partial JSON. A metadata write failure returns `instance_metadata_failed` and rolls back the staging directory. The guest can edit metadata, so it must not be treated as a security identity or authorization record.

## Clone and rename

```kotlin
val copy = pdn.clone("ai-python", "ai-python-test")
val rename = pdn.rename("ai-python-test", "workspace-python")
```

```java
ProcessBuilder copy = pdn.clone("ai-python", "ai-python-test");
ProcessBuilder rename = pdn.rename("ai-python-test", "workspace-python");
```

Execute these builders through the existing operation API; they use the same event and result protocol. Clone creates a new ID and timestamp, sets source to `clone`, and preserves known versions and digests. A legacy instance can produce a clone with null provenance fields. Rename retains the ID, timestamp, and source; a legacy rootfs may continue to have no metadata. Name conflicts are case insensitive, and a rename that only changes case is allowed.

Active sessions or an existing management lock return `operation_busy`. Clone follows the portable backup rules and needs space for a temporary archive and the complete target rootfs. Rename moves the directory and migrates its own host absolute links, `.l2s` backing paths, and binds within the rootfs. External project binds stay shared; environment strings and ordinary file contents are not rewritten. Ordinary failures and SIGINT/SIGTERM roll back before publication. Failed rollback reports `rollback_failed` and preserves recovery snapshots; follow the suggestion to inspect them. SIGKILL and power-loss recovery remain future work.

## Distributions and mirrors

```java
PdnCatalog catalog = pdn.catalog();
List<PdnDistributionInfo> available = catalog.available();
List<PdnDistributionInfo> installed = catalog.installed();
List<PdnMirrorInfo> mirrors = catalog.mirrors("debian");
```

Queries are synchronous; run them on a background thread in a GUI. Returned lists and entries are immutable. Available distributions provide a name, pinned version, architecture, and download size in bytes. Installed entries provide the name and rootfs path; `getInstance()` returns optional immutable `PdnInstanceInfo`. This includes the stable ID, instance name, distribution and version, architecture, source, source URL, archive SHA256, and creation time in Unix seconds. Legacy rootfs entries have null instance information and are never modified by queries. Restored or cloned instances with unknown provenance can retain null fields; historical versions are not inferred from the current download catalog.

Mirror entries provide the distribution, mirror name, base URL, full archive URL, priority, and official-source flag. Priority is the built-in fallback order, not a live speed ranking.

Queries use version 1 JSON envelopes from native `list --json`, `list --available --json`, and `mirrors [NAME] --json`. JSONL events remain on a separate channel. Invalid format, UTF-8, types, version, or size throws `host_catalog_protocol`. Native query failures throw `PdnQueryException`; `getResult()` retains the native cause and suggestion.

## Validation and release scope

Builds provide a non-instrumented AAR and matching ELF and `.so` files, retaining the legacy lite download name as a byte-identical alias. Both standard and Termux builds exclude the legacy pr native components from the AAR. Packaging filters them again and verifies the three native files, API metadata, byte consistency, and checksums. Version-specific builds, unit tests, packaging checks, and independent APK acceptance results are recorded in the [test records](pdn-error-testing.en.md).

The AAR has passed acceptance in non-debuggable Release test Apps with R8 minification, optimization, and resource shrinking enabled. Android's default optimization/JNI rules are used; no rule retaining the entire SDK is required. Test APKs use a local Debug signing key. Maven publishing, terminal rendering components, and an automatic background service are not currently provided. See the [AAR verification project](../examples/aar-probe/README.en.md) for building and testing an independent App.
