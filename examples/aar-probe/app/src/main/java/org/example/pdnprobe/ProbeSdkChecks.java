package org.example.pdnprobe;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import id.or.oo.pr.engine.*;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ProbeSdkChecks {
    private interface Check { String run() throws Exception; }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void check(JSONArray checks, ProbeSuite.Log log, String name, Check action) throws Exception {
        JSONObject entry = new JSONObject().put("name", name);
        try {
            String details = action.run();
            entry.put("passed", true).put("details", details);
            log.accept("PASS " + name + ": " + details);
        } catch (Exception | AssertionError failure) {
            entry.put("passed", false).put("details", failure.toString());
            log.accept("FAIL " + name + ": " + failure);
        }
        checks.put(entry);
    }

    public static void verify(JSONArray checks, ProbeHost host, PdnRuntime runtime, PdnOperations operations, ProbeSuite.Log log) throws Exception {
        check(checks, log, "typed_catalog", () -> {
            PdnCatalog catalog = runtime.catalog();
            List<PdnDistributionInfo> available = catalog.available();
            require(available.stream().anyMatch(d -> "debian".equals(d.getName()) && d.getVersion() != null && d.getArchitecture() != null && d.getDownloadSize() > 0), "available Debian metadata");
            PdnDistributionInfo installed = catalog.installed().stream().filter(d -> "alpine".equals(d.getName())).findFirst().orElseThrow(() -> new AssertionError("installed Alpine missing"));
            require(new File(installed.getRootfs()).getCanonicalFile().equals(new File(runtime.getRootfsDir(), "alpine").getCanonicalFile()), "installed rootfs path");
            require(installed.getVersion() == null && installed.getArchitecture() == null && installed.getDownloadSize() == null, "installed metadata must not invent available values");
            List<PdnMirrorInfo> debian = catalog.mirrors("debian");
            require(debian.size() == 1 && debian.get(0).isOfficial() && "debian".equals(debian.get(0).getDistro()), "single official Debian mirror");
            require(debian.get(0).getUrl().startsWith("https://") && debian.get(0).getBaseUrl().startsWith("https://") && debian.get(0).getPriority() >= 0, "mirror fields");
            require(catalog.mirrors().stream().anyMatch(m -> "alpine".equals(m.getDistro())), "all mirrors");
            return "typed available/installed metadata and official Debian mirror";
        });
        check(checks, log, "configured_bind_workdir_literal_environment", () -> {
            File directory = new File(runtime.getProjectDir(), "configured bind space");
            require(directory.isDirectory() || directory.mkdirs(), "bind directory");
            String literal = "two words 'quoted' $HOME $(false) ; 雪 😀";
            PdnConfiguration config = new PdnConfiguration("root", "/probe-bind", Arrays.asList(new PdnBind(directory, "/probe-bind")), Collections.singletonMap("PROBE_LITERAL", literal));
            Async capture = new Async();
            PdnResult result = operations.start(runtime.exec("alpine", Arrays.asList("/bin/sh", "-c", "pwd; printf '%s\\n' \"$PROBE_LITERAL\"; printf bound > bound.txt"), config), capture, 15000).await(20, TimeUnit.SECONDS);
            capture.validate(result);
            require(capture.text().equals("/probe-bind\n" + literal + "\n"), "exact configured output: " + capture.text());
            require(new String(Files.readAllBytes(new File(directory, "bound.txt").toPath()), StandardCharsets.UTF_8).equals("bound"), "custom bind persistence");
            PdnRuntime configured = new PdnRuntime(host, runtime.getRootfsDir(), runtime.getProjectDir(), config);
            require(configured.getConfiguration().getWorkDir().equals("/probe-bind"), "runtime configuration constructor");
            return "custom workdir, bind with spaces and literal Unicode environment";
        });
        check(checks, log, "async_concurrent_ordered_callbacks", () -> {
            ExecutorService callbacks = Executors.newSingleThreadExecutor(r -> new Thread(r, "probe-callbacks"));
            Async first = new Async(), second = new Async();
            PdnTask a = null, b = null;
            try {
                a = operations.start(runtime.exec("alpine", Arrays.asList("/bin/sh", "-c", "sleep 1; printf first")), first, 15000, callbacks);
                b = operations.start(runtime.exec("alpine", Arrays.asList("/bin/sh", "-c", "printf second")), second, 15000, callbacks);
                PdnResult rb = b.await(20, TimeUnit.SECONDS), ra = a.await(20, TimeUnit.SECONDS);
                first.validate(ra); second.validate(rb);
                require(first.text().equals("first") && second.text().equals("second"), "independent output");
                require(!ra.getOperationId().equals(rb.getOperationId()) && first.threadName.equals("probe-callbacks") && second.threadName.equals("probe-callbacks"), "executor and operation isolation");
                require(a.isDone() && b.isDone() && !a.isCancelled() && !a.cancel(), "completed task state");
                return "two tasks, one dedicated callback executor, ordered correlated events, one completion each";
            } finally { if (a != null) a.cancel(); if (b != null) b.cancel(); callbacks.shutdownNow(); }
        });
        for (boolean timeout : Arrays.asList(false, true)) {
            check(checks, log, timeout ? "async_timeout_process_tree" : "async_cancel_process_tree", () -> {
                Async capture = new Async();
                PdnTask task = operations.start(runtime.exec("alpine", Arrays.asList("/bin/sh", "-c", "sleep 120 & child=$!; printf 'PIDS %s %s\\n' \"$$\" \"$child\"; wait")), capture, timeout ? 5000 : 20000);
                try {
                    require(capture.running.await(12, TimeUnit.SECONDS), "guest parent and child output");
                    String[] words = capture.text().trim().split("\\s+");
                    require(words.length == 3 && words[0].equals("PIDS"), "PID output: " + capture.text());
                    int parent = Integer.parseInt(words[1]), child = Integer.parseInt(words[2]);
                    Os.kill(parent, 0); Os.kill(child, 0);
                    if (!timeout) require(task.cancel(), "running cancellation accepted");
                    try { task.await(20, TimeUnit.SECONDS); throw new AssertionError("expected task failure"); }
                    catch (ExecutionException failure) {
                        require(failure.getCause() instanceof PdnHostException, "typed host failure");
                        require(((PdnHostException)failure.getCause()).getCode().equals(timeout ? "host_operation_timeout" : "host_operation_cancelled"), "cancellation/timeout code");
                    }
                    require(task.isDone() && task.isCancelled() && capture.terminals.get() == 1 && capture.failure != null, "one terminal failure callback");
                    gone(parent); gone(child);
                    return "typed failure; guest parent and forked child ESRCH after await";
                } finally { task.cancel(); }
            });
        }
        check(checks, log, "terminal_two_sessions", () -> {
            try (Terminal a = terminal(runtime, runtime.login("alpine")); Terminal b = terminal(runtime, runtime.login("alpine"))) {
                require(a.session.getPid() != b.session.getPid() && a.session.getMasterFd() != b.session.getMasterFd(), "independent PID and fd");
                a.write("test -t 0 && test -t 1 && printf '\\101\\137\\124\\124\\131\\n'\n");
                b.write("test -t 0 && test -t 1 && printf '\\102\\137\\124\\124\\131\\n'\n");
                a.await("A_TTY"); b.await("B_TTY");
                a.session.resize(31, 91); b.session.resize(37, 103);
                a.write("stty size; printf '\\101\\137\\123\\111\\132\\105\\n'\n");
                b.write("stty size; printf '\\102\\137\\123\\111\\132\\105\\n'\n");
                require(a.await("A_SIZE").contains("31 91") && b.await("B_SIZE").contains("37 103"), "independent terminal sizes");
                a.write("exit\n"); b.write("exit 23\n");
                PdnResult ra = a.awaitResult(), rb = b.awaitResult();
                require(ra.isSuccess() && a.session.poll().isSuccess(), "normal zero status");
                require("guest_exit".equals(rb.getOutcome()) && Integer.valueOf(23).equals(rb.getGuestExitCode()) && Integer.valueOf(23).equals(b.session.poll().getExitCode()), "nonzero exit status");
                a.session.close(); a.session.close();
                try { a.session.read(new byte[1]); throw new AssertionError("closed read accepted"); } catch (PdnTerminalException expected) { require(expected.getErrno() == 9, "closed EBADF"); }
                return "two simultaneous TTYs, independent output/resize, zero/nonzero status and idempotent close";
            }
        });
        check(checks, log, "terminal_guest_signal", () -> {
            try (Terminal terminal = terminal(runtime, runtime.exec("alpine", Arrays.asList("/bin/sh", "-c", "kill -TERM $$")))) {
                PdnResult result = terminal.awaitResult();
                require("guest_exit".equals(result.getOutcome()) && Integer.valueOf(15).equals(result.getGuestSignal()), "terminal guest signal classification");
                return "guest SIGTERM reported through high level PdnResult";
            }
        });
        check(checks, log, "launcher_configuration_session", () -> {
            PdnConfiguration configuration = new PdnConfiguration("root", "/workspace", Collections.emptyList(), Collections.singletonMap("LAUNCHER_LITERAL", "雪 😀 two words"));
            try (PdnTerminalSession session = new ProotLauncher(host).startPdnSession(new File(runtime.getRootfsDir(), "alpine"), configuration, 24, 80)) {
                Terminal writer = new Terminal(); writer.session = session;
                writer.write("printf '%s\\n' \"$LAUNCHER_LITERAL\"; exit\n");
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] bytes = new byte[4096];
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
                while (true) {
                    require(System.nanoTime() < deadline, "launcher session timeout");
                    int count = session.read(bytes);
                    if (count > 0) output.write(bytes, 0, count);
                    if (session.poll() != null && count <= 0) break;
                    if (count <= 0) Thread.sleep(10);
                }
                require(new String(output.toByteArray(), StandardCharsets.UTF_8).contains("雪 😀 two words") && session.poll().isSuccess(), "launcher configured environment and zero exit");
                return "ProotLauncher configured overload executes literal Unicode environment";
            }
        });
        check(checks, log, "terminal_exact_processbuilder_environment", () -> {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/env");
            builder.environment().clear();
            builder.environment().put("PDN_EXACT", "中文😀");
            try (PdnTerminalSession session = PdnTerminalSession.start(builder, 24, 80)) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] bytes = new byte[4096];
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (true) {
                    require(System.nanoTime() < deadline, "exact environment terminal timeout");
                    int count = session.read(bytes);
                    if (count > 0) output.write(bytes, 0, count);
                    if (session.poll() != null && count <= 0) break;
                    if (count <= 0) Thread.sleep(10);
                }
                String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
                require(text.equals("PDN_EXACT=中文😀\r\n") && session.poll().isSuccess(), "exact environment output: " + text);
                return "environment.clear preserves one literal Unicode variable without inherited host entries";
            }
        });
        check(checks, log, "terminal_native_signal_status", () -> {
            ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", "kill -TERM $$");
            try (PdnTerminalSession session = PdnTerminalSession.start(builder, 24, 80)) {
                PdnTerminalStatus status = session.waitFor(10000);
                require(status != null && Integer.valueOf(15).equals(status.getSignal()) && status.getExitCode() == null && !status.isSuccess(), "native signal status");
                return "native wait status preserves SIGTERM independently of PDN guest metadata";
            }
        });
        for (String code : Arrays.asList("guest_shell_missing", "proot_loader_missing")) {
            check(checks, log, "terminal_" + code, () -> {
                ProcessBuilder plain;
                if (code.equals("guest_shell_missing")) {
                    File root = new File(runtime.getRootfsDir().getParentFile(), "fixtures/initial-missing");
                    plain = runtime.exec(root, Arrays.asList("/bin/sh", "-c", "echo unexpected"));
                } else {
                    plain = runtime.exec("alpine", Arrays.asList("/bin/sh", "-c", "echo unexpected"));
                    plain.environment().put("PROOT_LOADER", new File(runtime.getProjectDir(), "absent-loader").getAbsolutePath());
                }
                ProbeSuite.Capture capture = new ProbeSuite.Capture(log);
                PdnResult baseline = operations.run(plain, capture);
                capture.result = baseline; capture.validate();
                try (Terminal terminal = terminal(runtime, plain)) {
                    PdnResult result = terminal.awaitResult();
                    require("manager_error".equals(result.getOutcome()) && code.equals(result.getCode()), "terminal startup code " + result.getCode());
                    require(baseline.getCode().equals(result.getCode()) && baseline.getExitCode() == result.getExitCode() && result.getSuggestion() != null && result.getGuestExitCode() == null, "terminal/nonterminal error agreement");
                    return "high level " + code + " agrees with native nonterminal query";
                }
            });
        }
        check(checks, log, "terminal_callback_failure_cleanup", () -> {
            CountDownLatch failed = new CountDownLatch(1);
            AtomicInteger count = new AtomicInteger();
            PdnTerminalSession session = new PdnTerminal(runtime).start(runtime.exec("alpine", Arrays.asList("/bin/sh", "-c", "printf callback; sleep 120")), 24, 80, new PdnTerminalListener() {
                @Override public void onOutput(byte[] bytes) { throw new AssertionError("probe callback failure"); }
                @Override public void onFailure(Exception failure) { count.incrementAndGet(); failed.countDown(); }
                @Override public void onComplete(PdnResult result) { count.addAndGet(100); failed.countDown(); }
            });
            try {
                require(failed.await(15, TimeUnit.SECONDS), "failure callback timeout");
                require(count.get() == 1 && session.isClosed(), "callback failure closes terminal and reports once");
                gone(session.getPid());
                return "callback Error closes/reaps session before one failure callback";
            } finally { session.close(); }
        });
        check(checks, log, "jni_errno_and_boundaries", () -> nativeChecks());
    }

    private static void gone(int pid) throws Exception {
        try { Os.kill(pid, 0); throw new AssertionError("process remains after cleanup: " + pid); }
        catch (ErrnoException failure) { require(failure.errno == OsConstants.ESRCH, "expected ESRCH: " + failure); }
    }

    private static final class Async implements PdnListener {
        final CountDownLatch running = new CountDownLatch(1);
        final AtomicInteger terminals = new AtomicInteger();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<PdnEvent> events = new java.util.ArrayList<>();
        volatile Exception failure;
        volatile String threadName;
        @Override public synchronized void onStdout(byte[] bytes) { out.write(bytes, 0, bytes.length); if (text().contains("PIDS ") && text().contains("\n")) running.countDown(); }
        @Override public synchronized void onEvent(PdnEvent event) { threadName = Thread.currentThread().getName(); events.add(event); }
        @Override public void onComplete(PdnResult result) { terminals.incrementAndGet(); }
        @Override public void onFailure(Exception error) { failure = error; terminals.incrementAndGet(); }
        synchronized String text() { return new String(out.toByteArray(), StandardCharsets.UTF_8); }
        synchronized void validate(PdnResult result) {
            require(result.isSuccess() && failure == null && terminals.get() == 1, "one successful completion");
            require(!events.isEmpty() && events.get(0).getType().equals("started") && events.get(events.size()-1).getType().equals("result"), "event boundaries");
            long sequence = 0;
            for (PdnEvent event : events) require(event.getSequence() == ++sequence && event.getOperationId().equals(result.getOperationId()), "ordered correlated event");
        }
    }

    private static Terminal terminal(PdnRuntime runtime, ProcessBuilder builder) throws Exception {
        Terminal terminal = new Terminal();
        terminal.session = new PdnTerminal(runtime).start(builder, 24, 80, terminal);
        return terminal;
    }
    private static final class Terminal implements PdnTerminalListener, AutoCloseable {
        PdnTerminalSession session;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicInteger terminals = new AtomicInteger();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final List<PdnEvent> events = new java.util.ArrayList<>();
        volatile PdnResult result;
        volatile Exception failure;
        @Override public synchronized void onOutput(byte[] bytes) { output.write(bytes, 0, bytes.length); notifyAll(); }
        @Override public synchronized void onEvent(PdnEvent event) { events.add(event); }
        @Override public void onComplete(PdnResult value) { result = value; terminals.incrementAndGet(); done.countDown(); }
        @Override public void onFailure(Exception value) { failure = value; terminals.incrementAndGet(); done.countDown(); }
        synchronized String text() { return new String(output.toByteArray(), StandardCharsets.UTF_8); }
        synchronized String await(String marker) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!text().contains(marker)) {
                require(System.nanoTime() < deadline && done.getCount() != 0, "terminal marker " + marker + ": " + text());
                wait(20);
            }
            return text();
        }
        void write(String text) throws Exception {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int offset = 0;
            while (offset < bytes.length) {
                require(System.nanoTime() < deadline, "terminal write timeout");
                int count = session.write(bytes, offset, bytes.length - offset);
                offset += count;
                if (count == 0) Thread.sleep(5);
            }
        }
        PdnResult awaitResult() throws Exception {
            require(done.await(15, TimeUnit.SECONDS) && failure == null && result != null, "terminal completion: " + failure);
            require(terminals.get() == 1 && session.isClosed(), "one terminal callback after close");
            synchronized (this) {
                require(!events.isEmpty() && "started".equals(events.get(0).getType()) && "result".equals(events.get(events.size()-1).getType()), "terminal event boundaries");
                long sequence = 0;
                for (PdnEvent event : events) require(event.getSequence() == ++sequence && result.getOperationId().equals(event.getOperationId()), "terminal event correlation");
            }
            return result;
        }
        @Override public void close() throws Exception { session.close(); }
    }

    private static String nativeChecks() throws Exception {
        byte[] data = new byte[4];
        require(PtyNative.readSession(-1, data, 0, 4) == -9 && PtyNative.writeSession(-1, data, 0, 4) == -9, "raw read/write EBADF");
        require(PtyNative.resizeSession(-1, 24, 80) == -9, "raw resize EBADF");
        require(PtyNative.signal(-1, 15) < 0 && PtyNative.poll(-1)[0] == 3, "raw invalid PID");
        int[] invalid = PtyNative.spawn("/system/bin/sh", new String[]{"/system/bin/sh"}, new String[0], 0, 80, null);
        require(invalid[0] < 0 && invalid[2] == 22, "raw spawn dimension EINVAL");
        int[] missing = PtyNative.spawn("/not-a-real-executable", new String[]{"/not-a-real-executable"}, new String[0], 24, 80, null);
        require(missing[0] < 0 && missing[2] == 2, "raw spawn ENOENT");
        for (String command : Arrays.asList("bad\u0000command", "bad\ud800command")) {
            int[] malformed = PtyNative.spawn(command, new String[]{command}, new String[0], 24, 80, null);
            require(malformed[0] < 0 && malformed[2] == 22, "raw malformed Unicode/NUL EINVAL");
        }
        for (String[] environment : Arrays.asList(new String[]{"ODD"}, new String[]{"", "value"}, new String[]{"BAD=KEY", "value"})) {
            int[] malformed = PtyNative.spawn("/system/bin/sh", new String[]{"/system/bin/sh"}, environment, 24, 80, null);
            require(malformed[0] < 0 && malformed[2] == 22, "raw invalid environment EINVAL");
        }
        int[] directory = PtyNative.spawn("/system/bin/sh", new String[]{"/system/bin/sh"}, new String[0], 24, 80, "/not-a-real-working-directory");
        require(directory[0] < 0 && directory[2] == 2, "raw chdir ENOENT");
        require(PtyNative.readSession(-1, data, -1, 1) == -22 && PtyNative.writeSession(-1, data, 0, 5) == -22, "raw JNI bounds EINVAL");
        java.lang.reflect.Method nativeRead = PtyNative.class.getDeclaredMethod("nativeRead", int.class, byte[].class, int.class, int.class);
        nativeRead.setAccessible(true);
        require(((Number)nativeRead.invoke(PtyNative.INSTANCE, -1, null, 0, 1)).intValue() == -22, "raw JNI null buffer EINVAL");
        int legacyFd = PtyNative.INSTANCE.forkPty("/system/bin/sh", null, null, 24, 80);
        require(legacyFd >= 0 && PtyNative.INSTANCE.getPid() > 0, "legacy nullable argv/environment spawn");
        int legacyPid = PtyNative.INSTANCE.getPid();
        ProotLauncher.Session legacy = new ProotLauncher.Session(legacyFd);
        try {
            require(legacy.resize(26, 86) == 0, "legacy Session resize");
            byte[] command = "printf '\\114\\105\\107\\101\\103\\131\\137\\117\\113\\n'\n".getBytes(StandardCharsets.UTF_8);
            require(legacy.write(command) == command.length, "legacy Session write");
            ByteArrayOutputStream legacyOutput = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (!new String(legacyOutput.toByteArray(), StandardCharsets.UTF_8).contains("LEGACY_OK")) {
                require(System.nanoTime() < deadline, "legacy Session read timeout");
                int count = legacy.read(buffer, 0, buffer.length);
                if (count > 0) legacyOutput.write(buffer, 0, count);
                if (count == 0) Thread.sleep(10);
                require(count >= 0, "legacy Session unexpected EOF");
            }
        } finally {
            legacy.close();
            PtyNative.signal(legacyPid, 15);
            long legacyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (PtyNative.poll(legacyPid)[0] == 0) {
                if (System.nanoTime() >= legacyDeadline) PtyNative.signal(legacyPid, 9);
                require(System.nanoTime() < legacyDeadline + TimeUnit.SECONDS.toNanos(2), "legacy child reap timeout");
                Thread.sleep(10);
            }
        }
        legacy.close();
        require(legacy.read(data, 0, 4) == -1 && legacy.write(data) == -1 && legacy.resize(24, 80) == -1, "legacy Session closed operations");
        require(PtyNative.INSTANCE.waitPid(legacyPid) == -1, "legacy reaped child wait");
        try (PdnTerminalSession session = PdnTerminalSession.start(new ProcessBuilder("/system/bin/sh", "-c", "sleep 120"), 24, 80)) {
            try { session.read(data, -1, 1); throw new AssertionError("read bounds"); } catch (IndexOutOfBoundsException expected) { }
            try { session.write(data, 0, 5); throw new AssertionError("write bounds"); } catch (IndexOutOfBoundsException expected) { }
            try { session.resize(0, 80); throw new AssertionError("resize dimensions"); } catch (IllegalArgumentException expected) { }
            require(session.read(data, 0, 0) == 0 && session.write(data, 0, 0) == 0, "empty read/write");
            session.terminate();
            require(session.poll() != null && session.waitFor(0) != null, "terminate/wait cache");
        }
        require(PtyNative.INSTANCE.read(-1, data, 0, 4) == -1 && PtyNative.INSTANCE.write(-1, data, 0, 4) == -1 && PtyNative.INSTANCE.resize(-1, 24, 80) == -1, "legacy errno compatibility");
        PtyNative.INSTANCE.close(-1);
        return "raw JNI errno paths, legacy adapters, session bounds and native cleanup";
    }
}
