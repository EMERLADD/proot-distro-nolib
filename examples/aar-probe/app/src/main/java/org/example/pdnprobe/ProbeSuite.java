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
    private final String resumeProxy;
    private final boolean offlineOnly;

    public ProbeSuite(Context context, Log log) {
        this(context, log, null);
    }

    public ProbeSuite(Context context, Log log, String resumeProxy) {
        this(context, log, resumeProxy, false);
    }

    public ProbeSuite(Context context, Log log, String resumeProxy, boolean offlineOnly) {
        this.offlineOnly = offlineOnly;
        this.resumeProxy = resumeProxy;
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

    private static byte[] readStream(java.io.InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int size;
        while ((size = input.read(buffer)) >= 0) if (size > 0) output.write(buffer, 0, size);
        return output.toByteArray();
    }

    private JSONObject instanceMetadata(Capture capture, String name) throws Exception {
        require(capture.result.isSuccess(), "instance listing: " + capture.stderr());
        JSONArray rows = new JSONObject(capture.stdout()).getJSONArray("distributions");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (name.equals(row.getString("name"))) return row.getJSONObject("instance");
        }
        throw new AssertionError("Missing instance: " + name);
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
            require(c.result.isSuccess() && c.stdout().contains("proot-distro-nolib 0.6.19"), "version: " + c.stderr());
            return "native PDN 0.6.19, correlated started/result callbacks";
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
        check(checks, "execution_policy", () -> {
            File tools = new File(getRootfs(), "tools");
            require(tools.mkdirs(), "policy fixture tools");
            File target = new File(tools, "busybox");
            Files.copy(new File(getRootfs(), "bin/busybox").toPath(), target.toPath());
            require(target.setExecutable(true), "policy fixture executable");
            try {
              for (String cwd : Arrays.asList("/workspace", "/tmp")) {
            id.or.oo.pr.engine.PdnConfiguration configuration = new id.or.oo.pr.engine.PdnConfiguration(
                "root", cwd, java.util.Collections.emptyList(), java.util.Collections.emptyMap(),
                new id.or.oo.pr.engine.PdnExecPolicy(java.util.Collections.singletonList(
                    id.or.oo.pr.engine.PdnExecRule.onlyFrom("/tools/busybox", "/workspace"))));
            ProcessBuilder builder = runtime.exec("alpine", Arrays.asList("/bin/sh", "-c", "exec /tools/busybox echo POLICY_OK"), configuration);
                Capture c = run(builder);
                if (cwd.equals("/workspace")) {
                    require(c.result.isSuccess() && c.stdout().equals("POLICY_OK\n"), "allowed directory: " + c.stderr());
                } else {
                    require(c.result.getExitCode() == 126 && !c.stdout().contains("POLICY_OK"), "disallowed directory: " + c.stderr());
            require(c.events.stream().anyMatch(e -> "execution_denied".equals(e.getType())
                && "working_directory_denied".equals(e.getReason()) && "/tmp".equals(e.getWorkingDirectory())), "typed denial callback");
                }
              }
            } finally { target.delete(); tools.delete(); }
            return "native child exec allowed in /workspace, refused in /tmp with policy event";
        });
        check(checks, "download_complete_cache", () -> verifyDownloadCache(true));
        if (!offlineOnly) check(checks, "download_https_resume", () -> verifyDownloadCache(false));
        check(checks, "cache_management", () -> verifyCacheManagement());
        check(checks, "interrupted_install_recovery", () -> verifyRecovery());
        check(checks, "interactive_task_stdin", () -> verifyTaskInput());
        check(checks, "archive_stdio", () -> {
            java.lang.Process exporter = runtime.processBuilder(Arrays.asList("backup", "alpine", "-")).start();
            byte[] archive;
            try {
                exporter.getOutputStream().close();
                archive = readStream(exporter.getInputStream());
                String errors = new String(readStream(exporter.getErrorStream()), StandardCharsets.UTF_8);
                require(exporter.waitFor() == 0, "stdout backup: " + errors);
            } finally { exporter.destroyForcibly(); }
            require(archive.length > 100 && archive[0] == (byte)0x1f && archive[1] == (byte)0x8b, "binary stdout contamination");
            java.lang.Process importer = runtime.processBuilder(Arrays.asList("restore", "stdio-restored", "-")).start();
            try {
                try (java.io.OutputStream input = importer.getOutputStream()) { input.write(archive); }
                String output = new String(readStream(importer.getInputStream()), StandardCharsets.UTF_8);
                String errors = new String(readStream(importer.getErrorStream()), StandardCharsets.UTF_8);
                require(importer.waitFor() == 0, "stdin restore: " + output + errors);
                require(run(runtime.exec("stdio-restored", Arrays.asList("/bin/sh", "-c", "test -x /bin/sh"))).result.isSuccess(), "stdio restored guest");
                return "binary gzip stdout and pipe stdin roundtrip with working guest";
            } finally { importer.destroyForcibly(); run(runtime.remove("stdio-restored")); }
        });

        check(checks, "document_import_export", () -> {
            id.or.oo.pr.engine.PdnDocuments documents = runtime.documents();
            File source = new File(context.getCacheDir(), "document-archive.tar.gz");
            File backup = new File(context.getCacheDir(), "document-backup.tar.gz");
            File fdBackup = new File(context.getCacheDir(), "document-fd.tar.gz");
            Files.copy(new File(host.getCacheDir(), "alpine.tar.gz").toPath(), source.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            android.net.Uri inputUri = android.net.Uri.parse("content://" + context.getPackageName() + ".documents/archive");
            android.net.Uri outputUri = android.net.Uri.parse("content://" + context.getPackageName() + ".documents/backup");
            try {
                require(documents.installAs("alpine", "document-in", context.getContentResolver(), inputUri, new Capture(log)).isSuccess(), "URI install");
                Files.write(backup.toPath(), new byte[]{7, 8, 9});
                require(!documents.backup("document-missing", context.getContentResolver(), outputUri, new Capture(log)).isSuccess(), "missing source backup");
                require(Arrays.equals(Files.readAllBytes(backup.toPath()), new byte[]{7, 8, 9}), "failed native backup truncated URI destination");
                require(documents.backup("document-in", context.getContentResolver(), outputUri, new Capture(log)).isSuccess(), "URI backup");
                try (android.os.ParcelFileDescriptor fd = android.os.ParcelFileDescriptor.open(backup, android.os.ParcelFileDescriptor.MODE_READ_ONLY)) {
                    require(documents.restore("document-fd", fd, new Capture(log)).isSuccess(), "FD restore");
                    android.system.Os.fstat(fd.getFileDescriptor());
                }
                try (java.io.InputStream input = Files.newInputStream(backup.toPath())) {
                    require(documents.restore("document-stream", input, new Capture(log)).isSuccess(), "stream restore");
                }
                try (android.os.ParcelFileDescriptor fd = android.os.ParcelFileDescriptor.open(fdBackup,
                        android.os.ParcelFileDescriptor.MODE_CREATE | android.os.ParcelFileDescriptor.MODE_TRUNCATE | android.os.ParcelFileDescriptor.MODE_WRITE_ONLY)) {
                    require(documents.backup("document-stream", fd, new Capture(log)).isSuccess(), "FD backup");
                    android.system.Os.fstat(fd.getFileDescriptor());
                }
                ByteArrayOutputStream exported = new ByteArrayOutputStream();
                require(documents.backup("document-fd", exported, new Capture(log)).isSuccess(), "stream backup");
                require(exported.size() > 100 && exported.toByteArray()[0] == (byte)0x1f, "gzip export");
                require(!documents.restore("document-bad", new java.io.ByteArrayInputStream(new byte[]{1, 2}), new Capture(log)).isSuccess(), "corrupt input refusal");
                try {
                    documents.restore("document-missing", context.getContentResolver(), android.net.Uri.parse("content://" + context.getPackageName() + ".documents/missing"), new Capture(log));
                    throw new IllegalStateException("missing provider file accepted");
                } catch (id.or.oo.pr.engine.PdnHostException expected) {
                    require("host_document_io_failed".equals(expected.getCode()), "provider failure classification");
                }
                require(run(runtime.exec("document-fd", Arrays.asList("/bin/sh", "-c", "test -x /bin/sh"))).result.isSuccess(), "restored guest command");
                File[] leftovers = host.getCacheDir().listFiles((dir, name) -> name.startsWith("pdn-document-"));
                require(leftovers != null && leftovers.length == 0, "document staging leak");
                return "content URI, duplicated descriptors and streams; restored guest, invalid input and cleanup";
            } finally {
                for (String name : Arrays.asList("document-in", "document-fd", "document-stream", "document-bad", "document-missing")) run(runtime.remove(name));
                Files.deleteIfExists(source.toPath()); Files.deleteIfExists(backup.toPath()); Files.deleteIfExists(fdBackup.toPath());
            }
        });

        check(checks, "named_instances", () -> {
            File archive = new File(host.getCacheDir(), "alpine.tar.gz");
            File backup = new File(host.getCacheDir(), "instance-" + java.util.UUID.randomUUID() + ".tar.gz");
            try {
                Capture a = run(runtime.installAs("alpine", "ai-python", null, archive));
                Capture b = run(runtime.installAs("alpine", "ai-node", null, archive));
                require(a.result.isSuccess() && b.result.isSuccess(), "alias installs: " + a.stderr() + b.stderr());
                Capture listing = run(runtime.processBuilder(Arrays.asList("list", "--json")));
                require(listing.result.isSuccess(), "instance listing: " + listing.stderr());
                JSONArray rows = new JSONObject(listing.stdout()).getJSONArray("distributions");
                JSONObject first = null, second = null;
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    if ("ai-python".equals(row.getString("name"))) first = row.getJSONObject("instance");
                    if ("ai-node".equals(row.getString("name"))) second = row.getJSONObject("instance");
                }
                require(first != null && second != null, "instance metadata missing");
                require(!first.getString("id").equals(second.getString("id")), "instance IDs must differ");
                require("alpine".equals(first.getString("distro")) && "archive".equals(first.getString("source")), "instance provenance");
                id.or.oo.pr.engine.PdnInstanceInfo typedInfo = runtime.catalog().installed().stream().filter(row -> "ai-python".equals(row.getName())).findFirst().get().getInstance();
                require(typedInfo != null && first.getString("id").equals(typedInfo.getId()) && "alpine".equals(typedInfo.getDistro()), "typed SDK instance query");
                Capture conflict = run(runtime.installAs("alpine", "AI-PYTHON", null, archive));
                require(!conflict.result.isSuccess() && "rootfs_exists".equals(conflict.result.getCode()), "case-insensitive alias collision");
                Capture write = run(runtime.exec("ai-python", Arrays.asList("/bin/sh", "-c", "printf INSTANCE_A > /tmp/pdn-instance-proof")));
                Capture separate = run(runtime.exec("ai-node", Arrays.asList("/bin/sh", "-c", "test ! -e /tmp/pdn-instance-proof")));
                require(write.result.isSuccess() && separate.result.isSuccess(), "isolated rootfs contents");
                Capture saved = run(runtime.processBuilder(Arrays.asList("backup", "ai-python", backup.getAbsolutePath())));
                Capture restored = run(runtime.processBuilder(Arrays.asList("restore", "ai-restored", backup.getAbsolutePath())));
                require(saved.result.isSuccess() && restored.result.isSuccess(), "instance restore: " + saved.stderr() + restored.stderr());
                Capture after = run(runtime.processBuilder(Arrays.asList("list", "--json")));
                require(after.result.isSuccess(), "restored listing");
                JSONArray restoredRows = new JSONObject(after.stdout()).getJSONArray("distributions");
                boolean found = false;
                for (int i = 0; i < restoredRows.length(); i++) {
                    JSONObject row = restoredRows.getJSONObject(i);
                    JSONObject info = row.getJSONObject("instance");
                    if ("ai-python".equals(row.getString("name"))) require(first.getString("id").equals(info.getString("id")), "stable instance ID");
                    if ("ai-restored".equals(row.getString("name"))) {
                        found = true;
                        require(!first.getString("id").equals(info.getString("id")), "restore must generate a new ID");
                        require("restore".equals(info.getString("source")) && first.getString("sha256").equals(info.getString("sha256")), "restore provenance");
                    }
                }
                require(found, "restored instance missing");
                Capture read = run(runtime.exec("ai-restored", Arrays.asList("/bin/sh", "-c", "cat /tmp/pdn-instance-proof")));
                require(read.result.isSuccess() && "INSTANCE_A".equals(read.stdout()), "restored instance contents");
                return "same distro aliases, stable distinct IDs, structured provenance, conflict refusal, isolated files and fresh restore identity";
            } finally {
                for (String name : Arrays.asList("ai-restored", "ai-node", "ai-python")) run(runtime.remove(name));
                Files.deleteIfExists(backup.toPath());
            }
        });
        check(checks, "clone_rename_instances", () -> {
            File source = new File(runtime.getRootfsDir(), "op-source");
            File copied = new File(runtime.getRootfsDir(), "op-copy");
            File renamed = new File(runtime.getRootfsDir(), "op-renamed");
            try {
                Capture installed = run(runtime.installAs("alpine", "op-source", null, new File(host.getCacheDir(), "alpine.tar.gz")));
                require(installed.result.isSuccess(), "operation source install: " + installed.stderr());
                File payload = new File(source, "root/value");
                Files.write(payload.toPath(), "ORIGINAL".getBytes(StandardCharsets.UTF_8));
                Files.write(new File(source, "root/.l2s.value").toPath(), "BACKING".getBytes(StandardCharsets.UTF_8));
                Files.createSymbolicLink(new File(source, "root/owned").toPath(), payload.toPath());
                Files.createSymbolicLink(new File(source, "root/backed").toPath(), new File(source, "root/.l2s.value").toPath());
                Capture configured = run(runtime.processBuilder(Arrays.asList("config", "op-source", "--bind", new File(source, "root") + ":/owned", "--env", "OP_PROBE=preserved")));
                require(configured.result.isSuccess(), "operation configuration: " + configured.stderr());
                JSONObject original = instanceMetadata(run(runtime.processBuilder(Arrays.asList("list", "--json"))), "op-source");
                Capture clone = run(runtime.clone("op-source", "op-copy"));
                require(clone.result.isSuccess(), "clone: " + clone.stderr());
                JSONObject copy = instanceMetadata(run(runtime.processBuilder(Arrays.asList("list", "--json"))), "op-copy");
                require(!original.getString("id").equals(copy.getString("id")) && "clone".equals(copy.getString("source")), "fresh clone identity");
                require(original.getString("sha256").equals(copy.getString("sha256")), "clone provenance");
                Capture cloneRead = run(runtime.exec("op-copy", Arrays.asList("/bin/sh", "-c", "cat /owned/value /root/owned /root/backed; printf '%s' \"$OP_PROBE\"")));
                require(cloneRead.result.isSuccess() && "ORIGINALORIGINALBACKINGpreserved".equals(cloneRead.stdout()), "clone bind, links and environment: " + cloneRead.stdout() + cloneRead.stderr());
                Files.write(new File(copied, "root/value").toPath(), "COPIED".getBytes(StandardCharsets.UTF_8));
                require("ORIGINAL".equals(new String(Files.readAllBytes(payload.toPath()), StandardCharsets.UTF_8)), "clone must be independent");
                Files.createSymbolicLink(new File(copied, "root/rename-host").toPath(), new File(copied, "root/value").toPath());
                Capture moved = run(runtime.rename("op-copy", "op-renamed"));
                require(moved.result.isSuccess() && !copied.exists() && renamed.isDirectory(), "rename: " + moved.stderr());
                JSONObject after = instanceMetadata(run(runtime.processBuilder(Arrays.asList("list", "--json"))), "op-renamed");
                require(copy.getString("id").equals(after.getString("id")) && copy.getLong("created_at") == after.getLong("created_at") && "clone".equals(after.getString("source")), "rename preserves identity and provenance");
                require(Files.readSymbolicLink(new File(renamed, "root/rename-host").toPath()).equals(new File(renamed, "root/value").getCanonicalFile().toPath()), "rename host-prefix symlink");
                Capture movedRead = run(runtime.exec("op-renamed", Arrays.asList("/bin/sh", "-c", "cat /owned/value /root/backed /root/rename-host; printf '%s' \"$OP_PROBE\"")));
                require(movedRead.result.isSuccess() && "COPIEDBACKINGCOPIEDpreserved".equals(movedRead.stdout()), "renamed guest configuration: " + movedRead.stdout() + movedRead.stderr());
                Capture conflict = run(runtime.clone("op-source", "OP-RENAMED"));
                require(!conflict.result.isSuccess() && "rootfs_exists".equals(conflict.result.getCode()), "clone collision refusal");
                return "clone independence and fresh identity; directory rename preserves ID/time, config, guest links and host backing paths";
            } finally {
                for (String name : Arrays.asList("op-renamed", "op-copy", "op-source")) run(runtime.processBuilder(Arrays.asList("remove", name, "--yes")));
            }
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
        check(checks, "openat2_inherited_app_filter", () -> {
            String status = new String(Files.readAllBytes(new File("/proc/self/status").toPath()), StandardCharsets.UTF_8);
            require(java.util.regex.Pattern.compile("(?m)^Seccomp:\\s*2\\s*$").matcher(status).find(), "inherited App seccomp filter missing");
            File probe = new File(runtime.getProjectDir(), "openat2-probe");
            File temporary = null;
            try {
                try (java.io.InputStream input = context.getAssets().open("openat2-probe")) {
                    Files.copy(input, probe.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                require(probe.setExecutable(true, false), "probe executable");
                temporary = Files.createTempDirectory(runtime.getProjectDir().toPath(), "openat2-regression-").toFile();
                ProcessBuilder builder = runtime.exec("alpine", Arrays.asList("/bin/sh", "-c",
                        "TMPDIR=/workspace/" + temporary.getName() + " exec /workspace/openat2-probe openat2"));
                builder.environment().put("PROOT_VERBOSE", "3");
                Capture c = run(builder);
                require(c.result.isSuccess(), "openat2: " + c.stderr() + " " + c.result.getMessage());
                require(c.stdout().equals("openat2 directory, create and invalid-pointer returned ENOSYS; openat fallback passed; payload=inherited openat2 fallback payload\n"), "openat2 output: " + c.stdout());
                int trapped = c.stderr().split(java.util.regex.Pattern.quote("seccomp SIGSYS: openat2("), -1).length - 1;
                require(trapped == 3, "expected three inherited openat2 SIGSYS traps, got " + trapped + ": " + c.stderr());
                String[] remaining = temporary.list();
                require(remaining != null && remaining.length == 0, "probe filesystem cleanup");
                return "App seccomp mode=2; three real SIGSYS traps; ENOSYS without creation; openat directory/create/write/read fallback";
            } finally {
                try { deleteTree(temporary); }
                finally { Files.deleteIfExists(probe.toPath()); }
            }
        });
        check(checks, "optional_dev_full", () -> {
            ProcessBuilder builder = runtime.exec("alpine", Arrays.asList("/bin/sh", "-c",
                    "test -c /dev/full || exit 21; "
                    + "test \"$(/bin/busybox stat -c '%t:%T' /dev/full)\" = '1:7' || exit 22; "
                    + "if printf x > /dev/full; then exit 23; fi; "
                    + "if /bin/busybox dd if=/dev/zero of=/dev/full bs=1 count=1; then exit 25; fi; "
                    + "exec 3<> /dev/full; if printf x >&3; then exit 24; fi; exec 3>&-; "
                    + "/bin/busybox dd if=/dev/full bs=4 count=1 2>/dev/null | /bin/busybox od -An -t x1; "
                    + "printf PDN_FULL_OK"));
            builder.environment().put("PROOT_EMULATE_DEV_FULL", "1");
            Capture c = run(builder);
            require(c.result.isSuccess(), "dev/full: " + c.stderr() + " " + c.result.getMessage());
            require(c.stdout().contains("00 00 00 00") && c.stdout().endsWith("PDN_FULL_OK"), "zero read: " + c.stdout());
            require(c.stderr().contains("No space left on device"), "write must report ENOSPC: " + c.stderr());
            return "opt-in guest character device; ENOSPC through redirection and duplicated descriptor; zero read";
        });
        check(checks, "guest_tar_directory_roundtrip", () -> {
            File temporary = Files.createTempDirectory(runtime.getProjectDir().toPath(), "tar-regression-").toFile();
            try {
                String directory = "/workspace/" + temporary.getName();
                Capture c = run(runtime.exec("alpine", Arrays.asList("/bin/sh", "-c",
                        "set -e; cd " + directory + "; mkdir -p source/bin extracted; "
                        + "printf 'PDN_TAR_PAYLOAD\\n' > source/bin/payload; "
                        + "/bin/busybox tar -cf archive.tar -C source .; "
                        + "/bin/busybox tar -xf archive.tar -C extracted; "
                        + "/bin/busybox cmp source/bin/payload extracted/bin/payload; "
                        + "cat extracted/bin/payload; rm -rf source extracted archive.tar")));
                require(c.result.isSuccess(), "tar directory roundtrip: " + c.stderr());
                require(c.stdout().equals("PDN_TAR_PAYLOAD\n"), "tar payload: " + c.stdout());
                String[] remaining = temporary.list();
                require(remaining != null && remaining.length == 0, "tar filesystem cleanup");
                return "guest directory creation, file write, tar pack/unpack and exact readback";
            } finally { deleteTree(temporary); }
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
        ProbeSdkChecks.verify(checks, host, runtime, operations, log);
        boolean success = true;
        for (int i = 0; i < checks.length(); i++) success &= checks.getJSONObject(i).getBoolean("passed");
        JSONObject report = new JSONObject().put("package", context.getPackageName()).put("target_sdk", context.getApplicationInfo().targetSdkVersion)
                .put("passed", success).put("checks", checks).put("network_checks_skipped", offlineOnly);
        Files.write(new File(context.getFilesDir(), "acceptance.json").toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
        log.accept(success ? "ALL CHECKS PASSED" : "CHECKS FAILED — see acceptance.json");
        return report;
    }

    private String verifyTaskInput() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        java.util.concurrent.CountDownLatch prompt = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();
        id.or.oo.pr.engine.PdnTask task = operations.startInteractive(runtime.exec("alpine", Arrays.asList("/bin/sh", "-c",
                "printf READY; IFS= read -r line; printf 'INPUT:%s\\n' \"$line\"; cat")), new PdnListener() {
            public void onStdout(byte[] bytes) { output.write(bytes, 0, bytes.length); prompt.countDown(); }
            public void onComplete(PdnResult result) { completed.incrementAndGet(); }
        }, 15000);
        try {
            require(prompt.await(10, java.util.concurrent.TimeUnit.SECONDS) && task.isStdinOpen() && !task.isDone(), "interactive input readiness");
            task.writeStdin("hello 世界\n"); task.writeStdin("binary bytes\n".getBytes(StandardCharsets.UTF_8));
            task.closeStdin(); task.closeStdin();
            require(task.await(15, java.util.concurrent.TimeUnit.SECONDS).isSuccess() && completed.get() == 1, "interactive completion");
            require(output.toString("UTF-8").contains("INPUT:hello 世界") && output.toString("UTF-8").contains("binary bytes"), "stdin data: " + output.toString("UTF-8"));
            try { task.writeStdin("late"); throw new IllegalStateException("post-exit input accepted"); }
            catch (id.or.oo.pr.engine.PdnHostException expected) { require("host_input_closed".equals(expected.getCode()), "closed input classification"); }
        } finally { task.cancel(); }
        id.or.oo.pr.engine.PdnTask ordinary = operations.start(runtime.exec("alpine", Arrays.asList("/bin/sh", "-c", "if read line; then exit 23; fi")), new PdnListener() {});
        require(ordinary.await(15, java.util.concurrent.TimeUnit.SECONDS).isSuccess() && !ordinary.isStdinOpen(), "default EOF preserved");
        try { ordinary.writeStdin("x"); throw new IllegalStateException("default input accepted"); }
        catch (id.or.oo.pr.engine.PdnHostException expected) { require("host_input_disabled".equals(expected.getCode()), "disabled input classification"); }
        for (boolean timeout : new boolean[]{false, true}) {
            java.util.concurrent.CountDownLatch waiting = new java.util.concurrent.CountDownLatch(1);
            id.or.oo.pr.engine.PdnTask stopped = operations.startInteractive(runtime.exec("alpine", Arrays.asList("/bin/sh", "-c", "printf READY; cat")), new PdnListener() {
                public void onStdout(byte[] bytes) { waiting.countDown(); }
            }, timeout ? 2500 : 0);
            try {
                require(waiting.await(10, java.util.concurrent.TimeUnit.SECONDS), "waiting input prompt");
                if (!timeout) require(stopped.cancel(), "input cancellation");
                try { stopped.await(15, java.util.concurrent.TimeUnit.SECONDS); throw new IllegalStateException("stopped input succeeded"); }
                catch (java.util.concurrent.ExecutionException expected) {
                    require(expected.getCause() instanceof id.or.oo.pr.engine.PdnHostException
                            && (timeout ? "host_operation_timeout" : "host_operation_cancelled").equals(((id.or.oo.pr.engine.PdnHostException)expected.getCause()).getCode()), "cancel/timeout classification: " + expected);
                }
                require(!stopped.isStdinOpen(), "cancelled input remains open");
            } finally { stopped.cancel(); }
        }
        return "ordinary EOF preserved; interactive prompt, UTF-8/binary input, ordered EOF, exit refusal and cancel/timeout cleanup";
    }

    private String verifyRecovery() throws Exception {
        File roots = runtime.getRootfsDir();
        java.lang.Process installer = runtime.processBuilder(Arrays.asList("install", "alpine", "--name", "recovery-target", "--archive", "-")).start();
        File stage = null;
        String original = null;
        File legacy = new File(roots, ".pdn-alpine-QAZ123");
        File outside = new File(host.getCacheDir(), "recovery-outside-" + java.util.UUID.randomUUID());
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline && stage == null) {
                File[] candidates = roots.listFiles((dir, name) -> name.matches("\\.pdn-install-[0-9a-f]{32}"));
                if (candidates != null) for (File candidate : candidates) {
                    File record = new File(candidate, ".pdn-operation");
                    if (!record.isFile()) continue;
                    String value = new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8);
                    if (value.contains("target=recovery-target\n") && value.contains("phase=copying\n")) { stage = candidate; original = value; break; }
                }
                if (!installer.isAlive()) throw new IllegalStateException("installation exited before identity capture");
                Thread.sleep(10);
            }
            require(stage != null, "installation identity timeout");
            Capture busy = run(runtime.processBuilder(Arrays.asList("recovery", "list", "--json")));
            require(!busy.result.isSuccess() && "operation_busy".equals(busy.result.getCode()), "live installation lock");
            long nativePid = -1;
            for (String line : original.split("\n")) if (line.startsWith("pid=")) nativePid = Long.parseLong(line.substring(4));
            require(nativePid > 0 && installer.isAlive(), "native owner before SIGKILL");
            android.system.Os.kill((int)nativePid, android.system.OsConstants.SIGKILL);
            require(installer.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "SIGKILL wait");
            require(installer.exitValue() == 137, "SIGKILL exit code: " + installer.exitValue());
            Capture listed = run(runtime.processBuilder(Arrays.asList("recovery", "list", "--json")));
            require(listed.result.isSuccess(), "recovery listing: " + listed.stderr());
            JSONArray entries = new JSONObject(listed.stdout()).getJSONArray("entries");
            require(entries.length() == 1, "isolated interrupted entry: " + listed.stdout());
            JSONObject row = entries.getJSONObject(0);
            String id = row.getString("id");
            require("interrupted".equals(row.getString("status")) && "process_missing".equals(row.getString("reason")), "dead process classification: " + row);
            require(row.getLong("pid") > 0 && row.getLong("start_time") > 0 && stage.getName().equals(".pdn-install-" + id), "UUID/PID/start identity");
            id.or.oo.pr.engine.PdnRecoveryInfo info = runtime.catalog().recovery();
            require(info.getEntries().size() == 1 && info.getEntries().get(0).isInterrupted() && id.equals(info.getEntries().get(0).getId()), "typed recovery parity");
            try { info.getEntries().clear(); throw new IllegalStateException("mutable recovery snapshot"); }
            catch (UnsupportedOperationException expected) {}
            require(legacy.mkdir() && outside.mkdir(), "recovery fixtures");
            Files.write(new File(outside, "keep").toPath(), new byte[]{1, 2, 3});
            Files.createSymbolicLink(new File(stage, "external").toPath(), outside.toPath());
            File stagedRoot = new File(stage, "rootfs");
            require(stagedRoot.mkdir(), "staged rootfs");
            Files.write(new File(stagedRoot, "keep").toPath(), new byte[]{4});
            String stat = new String(Files.readAllBytes(new File("/proc/self/stat").toPath()), StandardCharsets.UTF_8);
            long ownStart = Long.parseLong(stat.substring(stat.lastIndexOf(')') + 1).trim().split(" +")[19]);
            String own = original.replace("pid=" + row.getLong("pid") + "\n", "pid=" + android.os.Process.myPid() + "\n")
                    .replace("start_time=" + row.getLong("start_time") + "\n", "start_time=" + ownStart + "\n");
            Files.write(new File(stage, ".pdn-operation").toPath(), own.getBytes(StandardCharsets.UTF_8));
            Capture ownListing = run(runtime.processBuilder(Arrays.asList("recovery", "list", "--json")));
            Capture active = run(runtime.processBuilder(Arrays.asList("recovery", "clear", id, "--yes")));
            require(!active.result.isSuccess() && "recovery_unsafe".equals(active.result.getCode()) && stage.isDirectory(), "live identity refusal: code=" + active.result.getCode() + " exists=" + stage.isDirectory() + " rows=" + ownListing.stdout() + " ownStart=" + ownStart + " ownPid=" + android.os.Process.myPid());
            String reused = own.replace("start_time=" + ownStart + "\n", "start_time=" + (ownStart + 1) + "\n");
            Files.write(new File(stage, ".pdn-operation").toPath(), reused.getBytes(StandardCharsets.UTF_8));
            Capture changed = run(runtime.processBuilder(Arrays.asList("recovery", "list", "--json")));
            JSONArray rows = new JSONObject(changed.stdout()).getJSONArray("entries");
            boolean matched = false;
            for (int i = 0; i < rows.length(); i++) if (id.equals(rows.getJSONObject(i).optString("id"))) {
                JSONObject identity = rows.getJSONObject(i);
                boolean hidden = "unknown".equals(identity.getString("status")) && "process_unavailable".equals(identity.getString("reason"));
                matched = "interrupted".equals(identity.getString("status")) && "pid_reused".equals(identity.getString("reason")) || hidden;
                if (hidden) {
                    Capture retained = run(runtime.processBuilder(Arrays.asList("recovery", "clear", id, "--yes")));
                    require(!retained.result.isSuccess() && "recovery_unsafe".equals(retained.result.getCode()) && stage.exists(), "hidden owner retention");
                }
            }
            require(matched, "PID reuse detection or conservative hidden process: " + changed.stdout());
            Files.write(new File(stage, ".pdn-operation").toPath(), original.getBytes(StandardCharsets.UTF_8));
            Capture cleared = run(runtime.processBuilder(Arrays.asList("recovery", "clear", "--yes")));
            require(cleared.result.isSuccess() && !stage.exists() && legacy.exists(), "cleanup and unknown retention: " + cleared.stderr());
            require(Arrays.equals(Files.readAllBytes(new File(outside, "keep").toPath()), new byte[]{1, 2, 3}) && getRootfs().isDirectory(), "project and installed rootfs preservation");
            return "actual SIGKILL, install lock, UUID/PID/start identity, controlled PID reuse/hidden process, safe cleanup and unknown retention";
        } finally {
            installer.destroyForcibly(); installer.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            installer.getOutputStream().close(); installer.getInputStream().close(); installer.getErrorStream().close();
            if (stage != null && stage.isDirectory() && original != null) {
                Files.write(new File(stage, ".pdn-operation").toPath(), original.getBytes(StandardCharsets.UTF_8));
                run(runtime.processBuilder(Arrays.asList("recovery", "clear", stage.getName().substring(13), "--yes")));
            }
            Files.deleteIfExists(legacy.toPath());
            Files.deleteIfExists(new File(outside, "keep").toPath()); Files.deleteIfExists(outside.toPath());
        }
    }

    private String verifyCacheManagement() throws Exception {
        File cache = new File(runtime.getRootfsDir(), ".pdn-downloads");
        Files.createDirectories(cache.toPath());
        Capture mirrors = run(runtime.processBuilder(Arrays.asList("mirrors", "alpine", "--json")));
        require(mirrors.result.isSuccess(), "cache mirror metadata: " + mirrors.stderr());
        JSONArray rows = new JSONObject(mirrors.stdout()).getJSONArray("mirrors");
        String url = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if ("official".equals(row.getString("name"))) url = row.getString("url");
        }
        require(url != null, "official mirror missing");
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        File archive = new File(host.getCacheDir(), "alpine.tar.gz");
        String archiveHash = hex(digest.digest(Files.readAllBytes(archive.toPath())));
        String urlHash = hex(digest.digest(url.getBytes(StandardCharsets.UTF_8)));
        File known = new File(cache, archiveHash + "-" + urlHash + ".part");
        String unknownHash = hex(digest.digest("unknown-cache-fixture".getBytes(StandardCharsets.UTF_8)));
        File orphan = new File(cache, unknownHash + "-" + unknownHash + ".part");
        File foreign = new File(cache, "unrelated.txt");
        File target = new File(runtime.getRootfsDir().getParentFile(), "cache-symlink-target");
        java.lang.Process holder = null;
        try {
            Files.write(known.toPath(), "known".getBytes(StandardCharsets.UTF_8));
            Files.write(orphan.toPath(), "orphan".getBytes(StandardCharsets.UTF_8));
            Files.write(foreign.toPath(), "foreign".getBytes(StandardCharsets.UTF_8));
            Capture listed = run(runtime.cache());
            require(listed.result.isSuccess(), "cache list: " + listed.stderr());
            JSONObject all = new JSONObject(listed.stdout());
            require(all.getInt("version") == 1 && cache.getAbsolutePath().equals(all.getString("path"))
                    && all.getLong("total_bytes") == 11 && all.getJSONArray("entries").length() == 2, "cache totals/schema");
            boolean unknown = false;
            JSONArray allEntries = all.getJSONArray("entries");
            for (int i = 0; i < allEntries.length(); i++) {
                JSONObject row = allEntries.getJSONObject(i);
                if (orphan.getName().equals(row.getString("id"))) {
                    unknown = row.isNull("distro") && row.isNull("mirror") && row.isNull("total") && row.getLong("bytes") == 6;
                }
            }
            require(unknown, "unknown cached archive metadata");
            Capture selected = run(runtime.cache("alpine"));
            require(selected.result.isSuccess(), "filtered cache list: " + selected.stderr());
            JSONObject filtered = new JSONObject(selected.stdout());
            require(filtered.getLong("total_bytes") == 5 && filtered.getJSONArray("entries").length() == 1, "filtered cache size");
            JSONObject entry = filtered.getJSONArray("entries").getJSONObject(0);
            require(known.getName().equals(entry.getString("id")) && "alpine".equals(entry.getString("distro"))
                    && "official".equals(entry.getString("mirror")) && entry.getLong("bytes") == 5
                    && entry.getLong("total") == archive.length() && entry.getLong("updated_at") > 0, "cache provenance");
            id.or.oo.pr.engine.PdnDownloadCacheInfo typed = runtime.catalog().cache("alpine");
            require(typed.getPath().equals(filtered.getString("path")) && typed.getTotalBytes() == 5 && typed.getEntries().size() == 1, "typed cache totals");
            id.or.oo.pr.engine.PdnDownloadCacheEntry item = typed.getEntries().get(0);
            require(item.getId().equals(entry.getString("id")) && "alpine".equals(item.getDistro()) && "official".equals(item.getMirror())
                    && item.getBytes() == 5 && Long.valueOf(archive.length()).equals(item.getTotal()) && item.getUpdatedAt() == entry.getLong("updated_at"), "typed cache metadata");
            boolean immutable = false;
            try { typed.getEntries().clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
            require(immutable, "typed cache entries must be immutable");
            Capture unconfirmed = run(runtime.processBuilder(Arrays.asList("cache", "clear", "alpine")));
            require(!unconfirmed.result.isSuccess() && known.isFile() && orphan.isFile() && foreign.isFile(), "clear requires confirmation");
            ProcessBuilder lockBuilder = runtime.processBuilder(Arrays.asList("exec", "alpine", "--bind",
                    runtime.getRootfsDir().getAbsolutePath() + ":/cache-test", "--", "/bin/sh", "-c",
                    "exec 9>/cache-test/.pdn-install.lock; /bin/busybox flock -x 9 || exit; printf 'READY\n'; read gate"));
            holder = lockBuilder.start();
            ByteArrayOutputStream readiness = new ByteArrayOutputStream();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && !readiness.toString("UTF-8").contains("\n")) {
                if (holder.getInputStream().available() > 0) readiness.write(holder.getInputStream().read());
                else if (!holder.isAlive()) break;
                else Thread.sleep(10);
            }
            require("READY\n".equals(readiness.toString("UTF-8")), "native flock readiness: " + readiness);
            Capture busy = run(runtime.clearCache("alpine"));
            require(!busy.result.isSuccess() && "operation_busy".equals(busy.result.getCode())
                    && known.isFile() && orphan.isFile() && foreign.isFile(), "locked cache clear refusal: " + busy.stderr());
            holder.getOutputStream().close();
            require(holder.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), "flock release timeout");
            holder.getInputStream().close();
            holder.getErrorStream().close();
            holder = null;
            Capture cleared = run(runtime.clearCache("alpine"));
            require(cleared.result.isSuccess() && !known.exists() && orphan.isFile() && foreign.isFile(), "filtered clear isolation");
            Capture global = run(runtime.clearCache());
            require(global.result.isSuccess() && !orphan.exists() && foreign.isFile() && getRootfs().isDirectory(), "global clear preserves unrelated files and installed rootfs");
            Files.write(target.toPath(), "SAFE".getBytes(StandardCharsets.UTF_8));
            Files.createSymbolicLink(known.toPath(), target.toPath());
            Capture unsafe = run(runtime.clearCache());
            require(!unsafe.result.isSuccess() && "cache_unsafe".equals(unsafe.result.getCode())
                    && Files.isSymbolicLink(known.toPath()) && "SAFE".equals(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8)), "symlink cache refusal: " + unsafe.stderr());
            return "local cache metadata, filtering, confirmation, native flock contention, isolated clear, symlink refusal and correlated events";
        } finally {
            try {
                if (holder != null) {
                    try {
                        holder.getOutputStream().close();
                        if (!holder.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            holder.destroyForcibly();
                            require(holder.waitFor(5, java.util.concurrent.TimeUnit.SECONDS), "flock process cleanup timeout");
                        }
                    } finally {
                        holder.getInputStream().close();
                        holder.getErrorStream().close();
                    }
                }
            } finally {
                for (File file : Arrays.asList(known, orphan, foreign, target)) Files.deleteIfExists(file.toPath());
            }
        }
    }

    private String verifyDownloadCache(boolean complete) throws Exception {
        File archive = new File(host.getCacheDir(), "alpine.tar.gz");
        require(archive.isFile() && archive.length() > 1, "packaged Alpine archive missing");
        Capture mirrors = run(runtime.processBuilder(Arrays.asList("mirrors", "alpine", "--json")));
        require(mirrors.result.isSuccess(), "mirror query: " + mirrors.stderr());
        JSONArray rows = new JSONObject(mirrors.stdout()).getJSONArray("mirrors");
        String url = null;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if ("alpine".equals(row.getString("distro")) && "official".equals(row.getString("name"))) {
                require(row.getBoolean("official"), "official mirror provenance");
                url = row.getString("url");
            }
        }
        require(url != null && url.startsWith("https://"), "official HTTPS archive URL missing");
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32768];
        try (java.io.InputStream input = Files.newInputStream(archive.toPath())) {
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        String archiveHash = hex(digest.digest());
        String urlHash = hex(digest.digest(url.getBytes(StandardCharsets.UTF_8)));
        File cache = new File(runtime.getRootfsDir(), ".pdn-downloads");
        Files.createDirectories(cache.toPath());
        File partial = new File(cache, archiveHash + "-" + urlHash + ".part");
        long seeded = complete ? archive.length() : archive.length() / 2;
        String name = complete ? "complete-cache-probe" : "resume-probe";
        try {
            try (java.io.InputStream input = Files.newInputStream(archive.toPath());
                    java.io.OutputStream output = Files.newOutputStream(partial.toPath())) {
                long remaining = seeded;
                while (remaining > 0) {
                    int count = input.read(buffer, 0, (int)Math.min(buffer.length, remaining));
                    require(count > 0, "archive ended while seeding cache");
                    output.write(buffer, 0, count);
                    remaining -= count;
                }
            }
            require(partial.length() == seeded, "seeded cache size");
            ProcessBuilder builder = runtime.installAs("alpine", name, "official", null);
            if (!complete && resumeProxy != null && !resumeProxy.isEmpty()) {
                builder.environment().put("https_proxy", resumeProxy);
                builder.environment().put("no_proxy", "");
            }
            if (complete) builder.environment().put("PDN_CA_BUNDLE", new File(cache, "missing-ca.pem").getAbsolutePath());
            Capture installed = run(builder);
            require(installed.result.isSuccess(), "cached install: " + installed.stderr() + " " + installed.result.getCode() + " " + installed.result.getMessage());
            boolean downloading = false;
            int downloadingStages = 0;
            Long firstCurrent = null, firstTotal = null;
            for (PdnEvent event : installed.events) {
                if ("stage".equals(event.getType()) && "downloading".equals(event.getStage())) {
                    downloading = true;
                    downloadingStages++;
                }
                if (!complete && downloading && "progress".equals(event.getType()))
                    require(event.getCurrent() != null && event.getCurrent() >= seeded, "resume must not restart from zero");
                if (downloading && "progress".equals(event.getType()) && firstCurrent == null) {
                    firstCurrent = event.getCurrent();
                    firstTotal = event.getTotal();
                }
            }
            if (complete) require(!downloading, "verified complete cache must avoid HTTPS transfer");
            else require(downloadingStages == 1 && Long.valueOf(seeded).equals(firstCurrent) && Long.valueOf(archive.length()).equals(firstTotal),
                    "resume progress must start at cached bytes: " + firstCurrent + "/" + firstTotal + ", expected " + seeded + "/" + archive.length());
            require(!partial.exists(), "completed cache must be consumed");
            JSONObject metadata = instanceMetadata(run(runtime.processBuilder(Arrays.asList("list", "--json"))), name);
            require("mirror".equals(metadata.getString("source")) && url.equals(metadata.getString("source_url"))
                    && archiveHash.equals(metadata.getString("sha256")), "official cached archive provenance");
            Capture executed = run(runtime.exec(name, Arrays.asList("/bin/sh", "-c", "printf PDN_CACHE_EXEC_OK")));
            require(executed.result.isSuccess() && "PDN_CACHE_EXEC_OK".equals(executed.stdout()), "cached guest execution: " + executed.stderr());
            Capture removed = run(runtime.processBuilder(Arrays.asList("remove", name, "--yes")));
            require(removed.result.isSuccess() && !new File(runtime.getRootfsDir(), name).exists(), "cached instance removal: " + removed.stderr());
            return complete ? "complete pinned cache verified and consumed without HTTPS; official provenance, guest execution and removal"
                    : "official HTTPS resumed from " + seeded + " cached bytes; verified archive consumed, guest execution and removal";
        } finally {
            try {
                if (new File(runtime.getRootfsDir(), name).exists()) {
                    Capture removed = run(runtime.processBuilder(Arrays.asList("remove", name, "--yes")));
                    require(removed.result.isSuccess(), "cached instance cleanup: " + removed.stderr());
                }
            } finally { Files.deleteIfExists(partial.toPath()); }
        }
    }

    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = alphabet[(bytes[i] & 255) >>> 4];
            result[i * 2 + 1] = alphabet[bytes[i] & 15];
        }
        return new String(result);
    }

    private static void deleteTree(File directory) throws java.io.IOException {
        if (directory == null || !Files.exists(directory.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(directory.toPath(), new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            @Override public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws java.io.IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(java.nio.file.Path path, java.io.IOException failure) throws java.io.IOException {
                if (failure != null) throw failure;
                Files.delete(path);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
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
            require(events.stream().filter(e -> "started".equals(e.getType())).count() == 1, "one started callback");
            require(events.stream().filter(e -> "result".equals(e.getType())).count() == 1, "one result callback");
            require(events.stream().filter(e -> "error".equals(e.getType())).count() == ("manager_error".equals(result.getOutcome()) ? 1 : 0), "unique error callback");
            long sequence = 0;
            for (PdnEvent e : events) require(e.getSequence() == ++sequence && e.getOperationId().equals(result.getOperationId()), "event correlation");
        }
        public String stdout() { return new String(out.toByteArray(), StandardCharsets.UTF_8); }
        public String stderr() { return new String(err.toByteArray(), StandardCharsets.UTF_8); }
    }
}
