# PDN events and AAR

[简体中文](pdn-events.md) | English · [Back to README](../README.en.md)

PDN uses protocol v1. Java/Kotlin callers can obtain stages, progress, errors, and final results without parsing terminal text. Running a ProcessBuilder directly remains supported.

The current SDK's asynchronous tasks, immutable configuration, structured queries, and independent terminals are documented in the [AAR API](pdn-aar-api.en.md). The synchronous `run()` and protocol v1 examples below remain valid. Use `PdnTerminal` for interactive terminals.

## Call flow

```text
Host GUI / Java / Kotlin
  ├─ ProotHost: program, data, cache, and project paths
  ├─ PdnRuntime: install / exec / backup arguments and environment
  └─ PdnOperations.run(builder, listener)
       ├─ starts nativeLibraryDir/libpdn.so as a separate process
       ├─ stdout → onStdout(byte[])
       ├─ stderr → onStderr(byte[])
       └─ private JSONL file → onEvent(PdnEvent)
                                └─ final validation → PdnResult / onComplete
```

Methods such as `PdnRuntime.install()` return a ProcessBuilder that has not started. `PdnOperations.run()` starts it and waits for completion; call it on a worker thread. Listener callbacks run sequentially on that thread. Dispatch Android UI updates to the main thread. `run()` closes stdin and suits installation and one-shot commands; interactive terminals use `PdnTerminal` or the lower-level `PdnTerminalSession`.

```java
PdnRuntime pdn = new PdnRuntime(host);
PdnOperations operations = new PdnOperations(pdn);
PdnResult result = operations.run(pdn.install("alpine"), new PdnListener() {
    @Override public void onEvent(PdnEvent event) {
        if ("progress".equals(event.getType())) {
            Integer percent = event.getPercent();
        }
    }
    @Override public void onStderr(byte[] data) {
    }
});
if (!result.isSuccess()) {
    String reason = result.getMessage();
    String advice = result.getSuggestion();
}
```

Here, `host` implements `ProotHost`, providing Java getters `getNativeLibDir()`, `getPrefixDir()`, `getHomeDir()`, `getCacheDir()`, and `getPackageName()`. Default `PdnRuntime` parameters have Java overloads; use its three-argument constructor for custom rootfs and project directories.

```kotlin
val result = runInterruptible(Dispatchers.IO) {
    PdnOperations(pdn).run(
        pdn.exec("alpine", listOf("/bin/sh", "-c", "apk update && apk add curl")),
        object : PdnListener {
            override fun onEvent(event: PdnEvent) {
            }
        },
    )
}
```

Listeners do not depend on coroutines; this Kotlin example simply chooses them to run work off the main thread. Invalid arguments throw `IllegalArgumentException`. Host failures creating cache files, starting processes, or reading/writing channels throw `IOException`. Thread interruption throws `InterruptedException`. Listener exceptions are rethrown after cleanup. Native PDN failures and Linux command exits are expressed through `PdnResult`.

Do not merge or redirect the standard streams of a builder passed to `run()`; all three must remain independent PIPE streams. The original builder's command, directory, and environment are not modified. `onStdout` and `onStderr` receive raw byte chunks, which need not contain a complete line or UTF-8 character. There is no guaranteed global ordering across the two streams.

## Final results

| outcome | Meaning |
| --- | --- |
| `success` | PDN completed and its actual process exit code is zero |
| `manager_error` | Failure in arguments, directories, download, verification, extraction, or startup |
| `guest_exit` | A Linux program started and a nonzero exit or terminating signal was observed |
| `cancelled` | A native installation, archive, or instance operation caught an interruption and completed cleanup |
| `host_protocol_error` | The Java wrapper found missing, truncated, invalid, or exit-code-inconsistent events |

Use `isSuccess()` rather than checking only `exitCode == 0`. `getExitCode()` is the actual host PDN process exit code. `getGuestExitCode()` and `getGuestSignal()` describe the main guest process. PRoot's process exit status can be affected by children that exit later, so the two exit codes may differ. A signal is not required to be encoded as `128 + signal`. `getSignal()` identifies an interruption signal caught by a native management operation.

An `error` event is a diagnostic, not a final result. For example, one mirror can fail and emit an error, while a second succeeds and produces final `success`. A successful result does not retain the failed mirror's error. Display `code`, `message`, and `suggestion` for a final failure; detailed logs still come from stderr.

Directory errors distinguish missing paths, non-directories, insufficient permissions, and read-only storage. Installation, verification, archive, and configuration failures provide classifications from the actual failing operation. See [error classifications and validation](pdn-error-testing.en.md) for categories and triggers. PRoot, loader, guest shell, and ELF interpreter startup errors are classified as listed below. Internal errors without a specific cause retain a fallback classification and stderr. `PdnOperations.start()` provides background tasks, running timeouts, and cancellation; use `PdnTask` to check or await completion. Cleanup attempts a graceful stop, then forced termination if necessary. The [AAR API](pdn-aar-api.en.md) describes interfaces and callback-thread constraints.

### PRoot and guest startup errors

| code | Meaning |
| --- | --- |
| `guest_shell_missing` / `guest_shell_nonexecutable` / `guest_shell_bad_format` / `guest_shell_failed` | Initial `/bin/sh` is missing, cannot execute, has an unsupported format, or fails for another execution reason |
| `guest_login_shell_missing` / `guest_login_shell_nonexecutable` / `guest_login_shell_bad_format` / `guest_login_shell_failed` | The wrapper started, but the actual interactive login shell cannot start |
| `guest_interpreter_missing` / `guest_interpreter_nonexecutable` / `guest_interpreter_bad_format` / `guest_interpreter_failed` | The shell's ELF interpreter is missing, cannot execute, has a bad format, or fails while reading/loading |
| `proot_loader_missing` / `proot_loader_nonexecutable` / `proot_loader_bad_format` / `proot_loader_failed` | Preparing or executing the external or embedded PRoot loader failed |
| `launch_pipe_failed` / `launch_fork_failed` | Creating the startup diagnostic pipe or process failed |
| `ptrace_failed` | Startup tracing declaration, option setup, or execution resumption failed |
| `guest_exec_failed` | The startup child reported an execution failure without a more specific diagnosis |
| `loader_open_failed` / `loader_mapping_failed` / `loader_close_failed` | Opening, mapping, or closing a file during loader startup failed; the actual error is retained |
| `guest_start_failed` / `guest_login_failed` | The guest or actual login shell exited before the load notification, without a more specific diagnosis |

These return `manager_error`. Read `getCode()`, `getMessage()`, and `getSuggestion()` without changing Java/Kotlin catch clauses. System-call error messages retain the actual errno. A permission failure means execution was denied and suggests checking permissions, mounts, and platform policy logs; a single errno does not identify a particular policy.

Successful startup means the PRoot loader finished mapping and sent a load notification; interactive login also requires loading the actual login shell. Command-not-found errors, exit codes 126/127, terminating signals, and guest dynamic-linker messages about missing shared libraries after that point remain `guest_exit`. Read raw stderr for the detail. This API does not parse dynamic-linker text or guarantee that guest initialization scripts succeed.

The parent writes all events. Startup children report failures through a close-on-exec pipe, preventing duplicate final results from parent and child. Even if the system falls back to treating a bad shell as a text script and it exits zero, an established startup failure is not reported as success.

Java host failures in cache access, event files, process startup, stream I/O, and cleanup throw `PdnHostException`. It extends `IOException`, retains the original `cause`, and exposes `getCode()` and `getSuggestion()`. Existing `catch (IOException)` remains valid. Argument errors, thread interruption, and listener exceptions retain their existing behavior. Cleanup failures are attached through `getSuppressed()` rather than replacing an earlier exception.

```java
try {
    PdnResult result = operations.run(pdn.install("alpine"), listener);
} catch (PdnHostException failure) {
    String code = failure.getCode();
    String advice = failure.getSuggestion();
    Throwable cause = failure.getCause();
}
```

## Native JSONL protocol

The native interface uses optional environment variables `PDN_EVENT_FILE` and `PDN_OPERATION_ID`. Without a channel, the original CLI output remains. The channel must be a private empty regular file owned by the process, or a new file that does not exist yet. Symlinks, special files, nonempty files, multiple hard links, and group/other-accessible files are rejected. Java creates a 0600 file in the host cache directory and deletes it when finished.

Each line is UTF-8 JSON ending with a newline. Common fields:

| Field | Contents |
| --- | --- |
| `version` | Always 1 |
| `operation_id` | 1–64 ASCII letters, digits, `.`, `_`, or `-`; Java generates it automatically |
| `sequence` | Consecutive sequence starting at 1 |
| `operation` | Operation name, such as `install`, `exec`, or `backup` |
| `type` | `started`, `stage`, `progress`, `error`, or `result` |

Example:

```json
{"version":1,"sequence":1,"operation_id":"demo","operation":"install","type":"started"}
{"version":1,"sequence":2,"operation_id":"demo","operation":"install","type":"stage","stage":"downloading"}
{"version":1,"sequence":3,"operation_id":"demo","operation":"install","type":"progress","stage":"downloading","current":1024,"total":2048,"percent":50}
{"version":1,"sequence":4,"operation_id":"demo","operation":"install","type":"result","stage":"publishing","outcome":"success","exit_code":0}
```

Stages include `preparing`, `downloading`, `verifying`, `extracting`, `configuring`, `initializing`, `publishing`, `backing_up`, `restoring`, `cloning`, `renaming`, `starting`, and `running`. Download percentages use the pinned archive size. Backup, restore, and clone report processed bytes with an unknown total of `-1`; they do not invent a percentage. Progress from guest programs such as `apk` remains raw log output and is not parsed by PDN.

Native progress is throttled to at most 1024 progress records per operation. Error strings have length limits, JSON escaping, and invalid UTF-8 handling. Java limits lines to 16 KiB, records to 10,000, and the file to 8 MiB; the standard-stream queue contains 32 × 8192-byte chunks. The event fd is close-on-exec, and protocol environment variables are removed before entering the guest. Events do not contain guest arguments or environment values; callers decide whether to retain raw logs.

Normal completion includes one `started` and one final `result`. Forced termination or channel write failure can leave an incomplete result; Java reports `host_protocol_error` rather than assuming success. If background children retain standard streams after PDN exits, Java drains for at most two more seconds before reporting a host I/O error. Asynchronous cancellation and running timeouts use the same cleanup flow; see [tasks](pdn-aar-api.en.md#asynchronous-tasks). This does not guarantee reaping daemons that detached from tracing, cover uninterruptible kernel waits, or provide persistent recovery after SIGKILL.

## Current AAR contents

`android/proot-engine` is the engine library module. Standard builds produce `proot-engine-debug.aar` or `proot-engine-release.aar`:

```text
proot-engine-*.aar
├─ AndroidManifest.xml
├─ classes.jar
│   └─ id/or/oo/pr/engine/
│      ├─ ProotHost / PdnRuntime / AlpinePackages
│      ├─ PdnOperations / PdnTask / PdnListener / PdnEvent / PdnResult / PdnHostException
│      ├─ PdnConfiguration / PdnBind
│      ├─ PdnCatalog / PdnDistributionInfo / PdnInstanceInfo / PdnMirrorInfo / PdnQueryException
│      ├─ PdnTerminal / PdnTerminalListener / PdnTerminalSession / PdnTerminalStatus / PdnTerminalException
│      └─ ProotLauncher / PtyNative compatibility interfaces
├─ jni/arm64-v8a/
│  ├─ libpdn.so
│  ├─ libproot-loader.so
│  └─ libptyjni.so
└─ build-generated R.txt and metadata
```

`libpdn.so` is a standalone ELF executable containing PDN command management, download/extraction dependencies, and patched PRoot. `libproot-loader.so` is also an ELF executable. The `.so` filenames enable Android native-library packaging and extraction; they do not expose `install()` through JNI. The API launches PDN with ProcessBuilder, wrapping arguments, events, and results.

`libptyjni.so` is the JNI shared library for interactive terminals. The standard AAR contains only the three native files above; `pdn-engine-lite-VERSION.aar` is a byte-identical compatibility filename. Legacy Java/Kotlin classes retain API compatibility, but methods depending on old pr-cli need separately supplied native programs. Use current APIs for PDN operations and terminals. The AAR does not include terminal Compose UI, an example App, Linux rootfs, or installed software; those belong to the host UI or runtime data.

The host still configures the Kotlin standard library, ARM64, minSdk 28, native-library extraction, and Internet permission. Importing a local AAR does not automatically carry Maven dependency declarations. Java projects can use the Java interfaces, but the Kotlin implementation of `PdnRuntime` still needs the Kotlin standard library at runtime. All paths come from the host; see the [Android integration guide](android-embedding.en.md).

## Independent App validation

The generated AAR has been validated in a [new independent Java Android test App](../examples/aar-probe/README.en.md). It references no original project modules and imports only the AAR and Kotlin standard library. Its Alpine interactive terminal successfully ran `apk add nano`. AAR and direct `.so` integration have both passed independent Debug and R8 Release App acceptance covering initialization, distribution installation, command execution, events, and PTY. See the [test records](pdn-error-testing.en.md) for each version's actual artifacts, check counts, and coverage scope; historical results do not establish acceptance for a newer version.
