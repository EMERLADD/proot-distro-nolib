package org.example.pdnprobe;

import android.content.Context;
import android.os.Process;
import id.or.oo.pr.engine.PdnEvent;
import id.or.oo.pr.engine.PdnListener;
import id.or.oo.pr.engine.PdnOperations;
import id.or.oo.pr.engine.PdnResult;
import id.or.oo.pr.engine.PdnRuntime;
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
    private PdnRuntime runtime;
    private PdnOperations operations;
    private final Log log;

    public ProbeSuite(Context context, Log log) {
        this.context = context.getApplicationContext();
        this.log = log;
        host = new ProbeHost(context);
        runtime = new PdnRuntime(host, new File(context.getFilesDir(), "distributions"),
                new File(context.getFilesDir(), "project with spaces"));
        operations = new PdnOperations(runtime);
    }

    public PdnRuntime getRuntime() { return runtime; }
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
        runtime = new PdnRuntime(host, new File(context.getFilesDir(), "acceptance/" + java.util.UUID.randomUUID() + "/distributions"), runtime.getProjectDir());
        operations = new PdnOperations(runtime);
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
            require(c.result.isSuccess() && c.stdout().contains("proot-distro-nolib 0.6.2"), "version: " + c.stderr());
            return "native PDN 0.6.2, correlated started/result callbacks";
        });
        check(checks, "install_alpine", () -> {
            boolean fresh = !getRootfs().exists();
            require(fresh, "acceptance requires a new rootfs");
            Capture c = run(runtime.install("alpine", "official"));
            require(c.result.isSuccess(), "install: " + c.stderr() + " " + c.result.getMessage());
            require(new File(getRootfs(), "bin/sh").exists(), "Alpine shell missing");
            {
                for (String phase : Arrays.asList("downloading", "verifying", "extracting", "configuring", "publishing")) {
                    require(c.events.stream().anyMatch(e -> "stage".equals(e.getType()) && phase.equals(e.getStage())), "missing phase " + phase);
                }
                require(c.events.stream().anyMatch(e -> "progress".equals(e.getType()) && e.getPercent() != null && e.getPercent() == 100), "download progress");
            }
            return "downloaded official ARM64 archive; verified, extracted and configured";
        });
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
        boolean success = true;
        for (int i = 0; i < checks.length(); i++) success &= checks.getJSONObject(i).getBoolean("passed");
        JSONObject report = new JSONObject().put("package", context.getPackageName()).put("target_sdk", context.getApplicationInfo().targetSdkVersion)
                .put("passed", success).put("checks", checks);
        Files.write(new File(context.getFilesDir(), "acceptance.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
        log.accept(success ? "ALL CHECKS PASSED" : "CHECKS FAILED — see acceptance.json");
        return report;
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }

    public static final class Capture implements PdnListener {
        private final Log log;
        private final long thread = Thread.currentThread().getId();
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private final ByteArrayOutputStream err = new ByteArrayOutputStream();
        public final List<PdnEvent> events = new ArrayList<>();
        public PdnResult result;
        private int completed;
        Capture(Log log) { this.log = log; }
        @Override public void onStdout(byte[] data) { sameThread(); out.write(data, 0, data.length); }
        @Override public void onStderr(byte[] data) { sameThread(); err.write(data, 0, data.length); }
        @Override public void onEvent(PdnEvent event) {
            sameThread();
            events.add(event);
            if ("stage".equals(event.getType())) log.accept("阶段：" + event.getStage());
            if (event.getPercent() != null) log.accept("进度：" + event.getPercent() + "%");
        }
        @Override public void onComplete(PdnResult value) { sameThread(); completed++; result = value; }
        private void sameThread() { require(thread == Thread.currentThread().getId(), "callback thread changed"); }
        void validate() {
            require(completed == 1 && !events.isEmpty(), "event/result callbacks");
            require("started".equals(events.get(0).getType()) && "result".equals(events.get(events.size() - 1).getType()), "event boundaries");
            long sequence = 0;
            for (PdnEvent e : events) require(e.getSequence() == ++sequence && e.getOperationId().equals(result.getOperationId()), "event correlation");
        }
        public String stdout() { return new String(out.toByteArray(), StandardCharsets.UTF_8); }
        public String stderr() { return new String(err.toByteArray(), StandardCharsets.UTF_8); }
    }
}
