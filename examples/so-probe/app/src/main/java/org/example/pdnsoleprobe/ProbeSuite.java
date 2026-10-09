package org.example.pdnsoleprobe;

import android.content.Context;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ProbeSuite {
    public interface Log { void accept(String line); }
    private final Context context;
    private final ProbeHost host;
    private NativeRuntime runtime;
    private NativeOperations operations;
    private final Log log;

    public ProbeSuite(Context context, Log log) {
        this.context = context.getApplicationContext();
        this.log = log;
        host = new ProbeHost(context);
        runtime = new NativeRuntime(host, new File(context.getFilesDir(), "distributions"),
                new File(context.getFilesDir(), "project with spaces"));
        operations = new NativeOperations(runtime);
    }

    public NativeRuntime getRuntime() { return runtime; }
    public ProbeHost getHost() { return host; }
    public File getRootfs() { return new File(runtime.getRootfsDir(), "alpine"); }

    public Capture run(ProcessBuilder builder) throws Exception {
        Capture capture = new Capture(log);
        capture.result = operations.run(builder, capture);
        capture.validate();
        return capture;
    }

    private interface Check { String run() throws Exception; }
    private void check(JSONArray checks, String name, Check action) throws Exception {
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

    public JSONObject verify() throws Exception {
        runtime = new NativeRuntime(host, new File(context.getFilesDir(), "acceptance/" + java.util.UUID.randomUUID() + "/distributions"), runtime.getProjectDir());
        operations = new NativeOperations(runtime);
        JSONArray checks = new JSONArray();
        check(checks, "initialize", () -> {
            runtime.prepare();
            require(runtime.getExecutable().canExecute() && runtime.getLoader().canExecute(), "native executables");
            require(runtime.getProjectDir().isDirectory() && host.getCacheDir().isDirectory(), "host directories");
            String context = new String(Files.readAllBytes(new File("/proc/self/attr/current").toPath()), StandardCharsets.UTF_8).trim();
            require(context.contains("untrusted_app"), "must run from ordinary Android App: " + context);
            require(!runtime.environment().get("PATH").contains("com.termux"), "runtime PATH independence");
            return "uid=" + Process.myUid() + ", SELinux=" + context.split(":s0")[0];
        });
        check(checks, "version_events", () -> {
            Capture c = run(runtime.version());
            require(c.result.isSuccess() && c.stdout().contains("proot-distro-nolib 0.6.6"), "version: " + c.stderr());
            return "native PDN 0.6.6, correlated started/result callbacks";
        });
        check(checks, "install_alpine", () -> {
            boolean fresh = !getRootfs().exists();
            require(fresh, "acceptance requires a new rootfs");
            File archive = new File(host.getCacheDir(), "alpine.tar.gz");
            try (java.io.InputStream input = context.getAssets().open("alpine-rootfs.archive")) {
                Files.copy(input, archive.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Capture c = run(runtime.install("alpine", null, archive));
            require(c.result.isSuccess(), "install: " + c.stderr() + " " + c.result.getMessage());
            require(Files.exists(new File(getRootfs(), "bin/sh").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS), "Alpine shell missing");
            {
                for (String phase : Arrays.asList("verifying", "extracting", "configuring", "publishing")) {
                    require(c.events.stream().anyMatch(e -> "stage".equals(e.getType()) && phase.equals(e.getStage())), "missing phase " + phase);
                }
                require(c.events.stream().anyMatch(e -> "stage".equals(e.getType())), "installation callbacks");
            }
            return "pinned official offline ARM64 archive; verified, extracted and configured";
        });
        ProbePathChecks.verify(checks, this, runtime, log);
        check(checks, "exec_stdout_stderr_workspace", () -> {
            Capture c = run(runtime.exec("alpine", Arrays.asList("/bin/sh", "-c",
                    "printf 'PDN_EXEC\\n'; id -u; pwd; printf '%s\\n' \"$1\"; printf 'PDN_STDERR\\n' >&2; printf persist > /workspace/probe.txt", "probe", "two words")));
            require(c.result.isSuccess(), "exec: " + c.stderr());
            require(c.stdout().equals("PDN_EXEC\n0\n/workspace\ntwo words\n"), "stdout: " + c.stdout());
            require(c.stderr().equals("PDN_STDERR\n"), "stderr: " + c.stderr());
            require(new String(Files.readAllBytes(new File(runtime.getProjectDir(), "probe.txt").toPath()), StandardCharsets.UTF_8).equals("persist"), "workspace persistence");
            require(c.events.stream().anyMatch(e -> "running".equals(e.getStage())), "guest started event");
            return "fake root, exact argv, independent streams and shared project file";
        });
        check(checks, "guest_nonzero", () -> {
            Capture c = run(runtime.exec("alpine", Arrays.asList("/bin/sh", "-c", "exit 17")));
            require(!c.result.isSuccess() && "guest_exit".equals(c.result.getOutcome()), "guest classification");
            require(c.result.getExitCode() == 17 && Integer.valueOf(17).equals(c.result.getGuestExitCode()), "exit 17 metadata");
            return "guest_exit, process=17, guest=17";
        });
        check(checks, "startup_error_advice", () -> {
            ProcessBuilder b = runtime.exec("alpine", Arrays.asList("/bin/sh", "-c", "echo must-not-run"));
            b.environment().put("PROOT_TMP_DIR", new File(host.getCacheDir(), "missing-temp").getAbsolutePath());
            Capture c = run(b);
            require("manager_error".equals(c.result.getOutcome()), "startup outcome");
            require("directory_missing".equals(c.result.getCode()) && c.result.getSuggestion() != null, "typed advice");
            require(c.stdout().isEmpty() && c.result.getGuestExitCode() == null, "guest must not run");
            return "directory_missing with suggestion; no guest execution";
        });
        check(checks, "pty_interaction_resize_exit", () -> {
            try (ProbeTerminal terminal = new ProbeTerminal(host, runtime, getRootfs(), text -> { })) {
                terminal.send("test -t 0 && test -t 1 && printf '\\120\\104\\116\\137\\124\\124\\131\\137\\117\\113\\n'\n");
                terminal.await("PDN_TTY_OK", 15000);
                terminal.send("printf '\\120\\104\\116\\137\\111\\116\\120\\125\\124\\137\\117\\113\\n'\n");
                terminal.await("PDN_INPUT_OK", 15000);
                terminal.resize(32, 96);
                terminal.send("stty size; printf '\\120\\104\\116\\137\\122\\105\\123\\111\\132\\105\\137\\117\\113\\n'\n");
                String transcript = terminal.await("PDN_RESIZE_OK", 15000);
                require(transcript.contains("32 96"), "TTY dimensions: " + transcript);
                terminal.send("exit\n");
                require(terminal.awaitExit(10000), "terminal did not exit");
            }
            return "real TTY, repeated input/output, stty 32x96, normal exit";
        });
        for (String mode : Arrays.asList("missing", "nonexecutable", "bad_format", "fallback")) {
            check(checks, "initial_shell_" + mode, () -> {
                File root = fixture("initial-" + mode, "/bin/sh");
                File shell = new File(root, "bin/sh");
                if (mode.equals("missing")) Files.delete(shell.toPath());
                else if (mode.equals("nonexecutable")) require(shell.setExecutable(false, false), "chmod shell");
                else Files.write(shell.toPath(), (mode.equals("fallback") ? "exit 0\n" : "\u007fELFbroken").getBytes(StandardCharsets.UTF_8));
                Capture c = run(runtime.exec(root, Arrays.asList("/bin/sh", "-c", "echo unexpected")));
                startup(c, "guest_shell_" + (mode.equals("fallback") ? "bad_format" : mode), mode.equals("missing") ? 2 : mode.equals("nonexecutable") ? 13 : 8);
                return c.result.getOutcome() + ": " + c.result.getCode();
            });
        }
        for (String mode : Arrays.asList("missing", "nonexecutable", "bad_format")) {
            check(checks, "selected_login_shell_" + mode, () -> {
                File root = fixture("login-" + mode, "/bin/selected");
                File shell = new File(root, "bin/selected");
                if (!mode.equals("missing")) {
                    Files.write(shell.toPath(), "\u007fELFbroken".getBytes(StandardCharsets.UTF_8));
                    require(shell.setExecutable(!mode.equals("nonexecutable"), false), "chmod selected shell");
                }
                Capture c = run(runtime.login(root));
                startup(c, "guest_login_shell_" + mode, mode.equals("missing") ? 2 : mode.equals("nonexecutable") ? 13 : 8);
                return c.result.getCode();
            });
        }
        for (String mode : Arrays.asList("missing", "nonexecutable", "bad_format")) {
            check(checks, "loader_" + mode, () -> {
                File loader = new File(host.getCacheDir(), "loader-" + mode);
                if (!mode.equals("missing")) {
                    Files.write(loader.toPath(), "\u007fELFbroken".getBytes(StandardCharsets.UTF_8));
                    require(loader.setExecutable(!mode.equals("nonexecutable"), false), "chmod loader");
                }
                ProcessBuilder builder = runtime.exec("alpine", Arrays.asList("/bin/sh", "-c", "echo unexpected"));
                builder.environment().put("PROOT_LOADER", loader.getAbsolutePath());
                Capture c = run(builder);
                startup(c, "proot_loader_" + (mode.equals("missing") ? "missing" : "nonexecutable"), mode.equals("missing") ? 2 : 13);
                return c.result.getCode() + (mode.equals("bad_format") ? "; app-data execution denied EACCES before format decoding" : "");
            });
        }
        check(checks, "guest_exit_127", () -> {
            Capture c = run(runtime.exec("alpine", Arrays.asList("/bin/sh", "-c", "exit 127")));
            require("guest_exit".equals(c.result.getOutcome()) && Integer.valueOf(127).equals(c.result.getGuestExitCode()), "real guest127");
            return "guest_exit127 with zero errors";
        });
        check(checks, "guest_signal", () -> {
            Capture c = run(runtime.exec("alpine", Arrays.asList("/bin/sh", "-c", "kill -TERM $$")));
            require("guest_exit".equals(c.result.getOutcome()) && Integer.valueOf(15).equals(c.result.getGuestSignal()), "real guest signal: " + c.result.getOutcome());
            return "guest signal15 with zero errors";
        });
        check(checks, "native_pty_invalid_arguments", () -> {
            byte[] bytes = new byte[4];
            require(NativePty.read(-1, bytes, 0, 4) == -1, "invalid descriptor");
            require(NativePty.read(0, null, 0, 1) == -1, "null read array");
            require(NativePty.read(0, bytes, -1, 1) == -1, "negative offset");
            require(NativePty.read(0, bytes, 0, -1) == -1, "negative length");
            require(NativePty.read(0, bytes, 5, 0) == -1, "offset beyond array");
            require(NativePty.read(0, bytes, 3, 2) == -1, "length beyond array");
            require(NativePty.read(0, bytes, 0, 0) == 0, "empty read");
            require(NativePty.write(-1, bytes) == -1 && NativePty.write(0, null) == -1, "invalid write");
            require(NativePty.resize(-1, 24, 80) == -1 && NativePty.resize(0, 0, 80) == -1, "invalid resize");
            require(NativePty.waitPid(0) == -1, "invalid wait PID");
            require(NativePty.spawn(null, new String[0], "/", 24, 80) == null, "null arguments");
            require(NativePty.spawn(new String[0], new String[0], "/", 24, 80) == null, "empty arguments");
            require(NativePty.spawn(new String[] { null }, new String[0], "/", 24, 80) == null, "null argument element");
            require(NativePty.spawn(new String[] { "/system/bin/sh" }, new String[] { null }, "/", 24, 80) == null, "null environment element");
            return "JNI rejects invalid descriptors, array ranges, strings and dimensions safely";
        });
        check(checks, "native_pty_wait_status", () -> {
            verifyNativeChild("exit 0", 0);
            verifyNativeChild("exit 37", 37);
            verifyNativeChild("kill -TERM $$", 128 + OsConstants.SIGTERM);
            return "running, exit0, exit37, signal termination and already-reaped errors without /proc";
        });
        for (String tail : Arrays.asList("malformed", "event_after_result", "truncated", "invalid_utf8")) {
            check(checks, "native_event_tail_" + tail, () -> {
                String script = "printf '{\"version\":1,\"operation_id\":\"%s\",\"sequence\":1,\"type\":\"started\"}\\n' \"$PDN_OPERATION_ID\"; "
                        + "printf '{\"version\":1,\"operation_id\":\"%s\",\"sequence\":2,\"type\":\"result\",\"outcome\":\"success\",\"exit_code\":0}\\n' \"$PDN_OPERATION_ID\"; ";
                if (tail.equals("malformed")) script += "printf '{broken\\n'";
                else if (tail.equals("event_after_result")) script += "printf '{\"version\":1,\"operation_id\":\"%s\",\"sequence\":3,\"type\":\"stage\"}\\n' \"$PDN_OPERATION_ID\"";
                else if (tail.equals("truncated")) script += "printf '{broken'";
                else script += "printf '\\377'";
                ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", "{ " + script + "; } > \"$PDN_EVENT_FILE\"");
                builder.environment().put("TMPDIR", host.getCacheDir().getAbsolutePath());
                boolean rejected = false;
                try { operations.run(builder, new Capture(log)); }
                catch (AssertionError | java.nio.charset.CharacterCodingException expected) { rejected = true; }
                require(rejected, "malformed final event tail was accepted");
                return "consumer rejected " + tail + " after a valid result";
            });
        }
        boolean success = true;
        for (int i = 0; i < checks.length(); i++) success &= checks.getJSONObject(i).getBoolean("passed");
        JSONObject report = new JSONObject().put("package", context.getPackageName()).put("target_sdk", context.getApplicationInfo().targetSdkVersion)
                .put("passed", success).put("checks", checks);
        Files.write(new File(context.getFilesDir(), "acceptance.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
        log.accept(success ? "ALL CHECKS PASSED" : "CHECKS FAILED — see acceptance.json");
        return report;
    }

    private void verifyNativeChild(String command, int expectedStatus) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("/system/bin/sh", "-c", "read gate; " + command);
        builder.directory(host.getCacheDir());
        NativePty.Session child = NativePty.start(builder, 24, 80);
        boolean finished = false;
        try {
            require(child.pid > 0, "native child PID");
            int status = NativePty.waitPid(child.pid);
            finished = status != NativePty.RUNNING;
            require(status == NativePty.RUNNING, "blocked child must remain running: " + status);
            byte[] input = "go\n".getBytes(StandardCharsets.UTF_8);
            require(child.write(input) == input.length, "release native child input gate");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            do {
                status = NativePty.waitPid(child.pid);
                if (status == NativePty.RUNNING) Thread.sleep(10);
            } while (status == NativePty.RUNNING && System.nanoTime() < deadline);
            finished = status != NativePty.RUNNING;
            require(status == expectedStatus, "native child exit: expected " + expectedStatus + ", got " + status);
            require(NativePty.waitPid(child.pid) == -1, "already-reaped child must return an error");
        } finally {
            try {
                if (!finished && child.pid > 0 && NativePty.waitPid(child.pid) == NativePty.RUNNING) {
                    Os.kill(child.pid, OsConstants.SIGKILL);
                    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
                    while (NativePty.waitPid(child.pid) == NativePty.RUNNING && System.nanoTime() < deadline) Thread.sleep(10);
                }
            } finally { child.close(); }
        }
    }

    private File fixture(String name, String selected) throws Exception {
        File root = new File(runtime.getRootfsDir().getParentFile(), "fixtures/" + name);
        for (String dir : Arrays.asList("bin", "lib", "etc", "root", "tmp")) require(new File(root, dir).mkdirs(), "fixture directory " + dir);
        for (String path : Arrays.asList("bin/busybox", "lib/ld-musl-aarch64.so.1")) {
            File source = new File(getRootfs(), path);
            File dest = new File(root, path);
            Files.copy(source.toPath(), dest.toPath());
            require(dest.setExecutable(source.canExecute(), false), "fixture executable");
        }
        Files.copy(new File(root, "bin/busybox").toPath(), new File(root, "bin/sh").toPath());
        require(new File(root, "bin/sh").setExecutable(true, false), "shell executable");
        Files.write(new File(root, "etc/passwd").toPath(), ("root:x:0:0:root:/root:" + selected + "\n").getBytes(StandardCharsets.UTF_8));
        return root;
    }

    private static void startup(Capture c, String code, int errno) {
        require("manager_error".equals(c.result.getOutcome()) && code.equals(c.result.getCode()), "startup classification: " + c.result.getOutcome() + " " + c.result.getCode() + " " + c.stderr());
        require(c.result.getMessage().contains("errno=" + errno + ")") && c.result.getSuggestion() != null, "startup errno/advice");
        require(c.result.getGuestExitCode() == null && c.result.getGuestSignal() == null, "no guest status for startup error");
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    public static final class Capture implements NativeListener {
        private final Log log;
        private final long thread = Thread.currentThread().getId();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        public final List<NativeEvent> events = new ArrayList<>();
        public NativeResult result;
        private int completed;
        Capture(Log log) { this.log = log; }
        @Override public void onStdout(byte[] data) { sameThread(); out.write(data, 0, data.length); }
        @Override public void onStderr(byte[] data) { sameThread(); err.write(data, 0, data.length); }
        @Override public void onEvent(NativeEvent event) {
            sameThread();
            events.add(event);
            if ("stage".equals(event.getType())) log.accept("阶段：" + event.getStage());
            if (event.getPercent() != null) log.accept("进度：" + event.getPercent() + "%");
        }
        @Override public void onComplete(NativeResult value) { sameThread(); completed++; result = value; }
        private void sameThread() { require(thread == Thread.currentThread().getId(), "callback thread changed"); }
        void validate() {
            require(completed == 1 && !events.isEmpty(), "event/result callbacks");
            require("started".equals(events.get(0).getType()) && "result".equals(events.get(events.size() - 1).getType()), "event boundaries");
            require(events.stream().filter(e -> "started".equals(e.getType())).count() == 1, "one started callback");
            require(events.stream().filter(e -> "result".equals(e.getType())).count() == 1, "one result callback");
            require(events.stream().filter(e -> "error".equals(e.getType())).count() == ("manager_error".equals(result.getOutcome()) ? 1 : 0), "unique error callback");
            long sequence = 0;
            for (NativeEvent e : events) require(e.getSequence() == ++sequence && e.getOperationId().equals(result.getOperationId()), "event correlation");
        }
        public String stdout() { return new String(out.toByteArray(), StandardCharsets.UTF_8); }
        public String stderr() { return new String(err.toByteArray(), StandardCharsets.UTF_8); }
    }
}
