package id.or.oo.pr.engine;

import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;
import static org.junit.Assert.*;

public class PdnOperationsJavaTest {
    private File directory;
    private PdnOperations operations;
    @Before public void setup() throws Exception {
        directory = Files.createTempDirectory("pdn-java-test").toFile();
        ProotHost host = new ProotHost() {
            public File getNativeLibDir() { return directory; }
            public File getPrefixDir() { return directory; }
            public File getHomeDir() { return directory; }
            public File getCacheDir() { return directory; }
            public String getPackageName() { return "test.pdn"; }
        };
        PdnRuntime runtime = new PdnRuntime(host);
        assertTrue(runtime.loginArguments(directory).contains("root"));
        operations = new PdnOperations(runtime);
    }
    @After public void cleanup() throws Exception {
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(directory.toPath())) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
    private String record(int sequence, String type, String fields) {
        return "printf '%s\\n' '{\"version\":1,\"operation_id\":\"'\"$PDN_OPERATION_ID\"'\",\"sequence\":" + sequence + ",\"operation\":\"exec\",\"type\":\"" + type + "\"" + fields + "}' >> \"$PDN_EVENT_FILE\"; ";
    }
    private String started() { return record(1, "started", ""); }
    private String result(int status, String outcome) {
        return record(2, "result", ",\"exit_code\":" + status + ",\"outcome\":\"" + outcome + "\"");
    }
    private ProcessBuilder shell(String script) { return new ProcessBuilder("/bin/sh", "-c", script); }
    @Test public void javaCallbacksPreserveBytesAndCallerThread() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        List<PdnEvent> events = new ArrayList<>();
        Thread caller = Thread.currentThread();
        int[] complete = {0};
        ProcessBuilder builder = shell(started() + "printf '\\000\\377x'; printf 'error' >&2; " + result(0, "success"));
        PdnResult result = operations.run(builder, new PdnListener() {
            public void onEvent(PdnEvent event) { assertSame(caller, Thread.currentThread()); events.add(event); }
            public void onStdout(byte[] data) { out.write(data, 0, data.length); }
            public void onStderr(byte[] data) { err.write(data, 0, data.length); }
            public void onComplete(PdnResult value) { complete[0]++; }
        });
        assertTrue(result.isSuccess());
        assertEquals(0, result.getExitCode());
        assertArrayEquals(new byte[]{0, (byte)255, 'x'}, out.toByteArray());
        assertEquals("error", err.toString("UTF-8"));
        assertEquals(2, events.size());
        assertEquals(1, complete[0]);
        assertFalse(builder.environment().containsKey("PDN_EVENT_FILE"));
        assertEquals(0, directory.list().length);
    }
    @Test public void actualFailuresAndMissingEventsRemainVisible() throws Exception {
        PdnResult guest = operations.run(shell(started() + result(7, "guest_exit") + "exit 7"), new PdnListener() {});
        assertEquals("guest_exit", guest.getOutcome());
        assertEquals(7, guest.getExitCode());
        assertFalse(guest.isSuccess());
        PdnResult manager = operations.run(shell(started() + result(1, "manager_error") + "exit 1"), new PdnListener() {});
        assertEquals("manager_error", manager.getOutcome());
        PdnResult missing = operations.run(shell("exit 9"), new PdnListener() {});
        assertEquals("host_protocol_error", missing.getOutcome());
        assertEquals(9, missing.getExitCode());
        assertEquals("event_protocol", missing.getCode());
    }
    @Test public void malformedTruncatedAndInconsistentEventsAreRejected() throws Exception {
        String[] invalid = {
            "printf 'bad\\n' > \"$PDN_EVENT_FILE\"",
            started() + "printf '{}' >> \"$PDN_EVENT_FILE\"",
            started() + result(1, "success"),
            started() + result(2, "guest_exit"),
            "PDN_OPERATION_ID=wrong; " + started() + result(0, "success"),
            started() + record(1, "result", ",\"exit_code\":0,\"outcome\":\"success\""),
            started() + result(0, "success") + record(3, "stage", ",\"stage\":\"late\"")
        };
        for (String script : invalid) {
            PdnResult result = operations.run(shell(script), new PdnListener() {});
            assertEquals(script, "host_protocol_error", result.getOutcome());
            assertEquals(0, result.getExitCode());
        }
    }
    @Test public void bothLargeStreamsDrainThroughBoundedQueue() throws Exception {
        long[] counts = {0, 0};
        PdnResult result = operations.run(shell(started() + "head -c 1048576 /dev/zero & head -c 1048576 /dev/zero >&2 & wait; " + result(0, "success")), new PdnListener() {
            public void onStdout(byte[] data) { counts[0] += data.length; }
            public void onStderr(byte[] data) { counts[1] += data.length; }
        });
        assertTrue(result.isSuccess());
        assertEquals(1048576, counts[0]);
        assertEquals(1048576, counts[1]);
    }
    @Test public void diagnosticThenSuccessfulFallbackIsValid() throws Exception {
        List<PdnEvent> events = new ArrayList<>();
        String script = started() + record(2, "error", ",\"code\":\"DOWNLOAD\",\"message\":\"retry\",\"suggestion\":\"mirror\"")
            + record(3, "progress", ",\"stage\":\"download\",\"current\":5,\"total\":10,\"percent\":50")
            + record(4, "result", ",\"exit_code\":0,\"outcome\":\"success\"");
        assertTrue(operations.run(shell(script), new PdnListener() {
            public void onEvent(PdnEvent event) { events.add(event); }
        }).isSuccess());
        assertEquals("DOWNLOAD", events.get(1).getCode());
        assertEquals("retry", events.get(1).getMessage());
        assertEquals("mirror", events.get(1).getSuggestion());
        assertEquals(Integer.valueOf(50), events.get(2).getPercent());
        assertEquals("download", events.get(2).getStage());
        assertEquals(Long.valueOf(5), events.get(2).getCurrent());
        assertEquals(Long.valueOf(10), events.get(2).getTotal());
        assertEquals(1, events.get(0).getVersion());
        assertEquals("exec", events.get(0).getOperation());
        assertEquals("started", events.get(0).getType());
        assertEquals(1, events.get(0).getSequence());
        assertNotNull(events.get(0).getOperationId());
        assertNull(events.get(0).getCurrent());
        assertNull(events.get(0).getTotal());
        assertNull(events.get(0).getPercent());
    }
    @Test public void callbackFailureCleansUpAndPropagates() throws Exception {
        RuntimeException failure = new RuntimeException("listener failed");
        long start = System.nanoTime();
        try {
            operations.run(shell(started() + "while :; do printf x; done"), new PdnListener() {
                public void onStdout(byte[] data) { throw failure; }
            });
            fail();
        } catch (RuntimeException actual) { assertSame(failure, actual); }
        assertTrue((System.nanoTime() - start) / 1000000 < 5000);
        assertEquals(0, directory.list().length);
    }
    @Test public void interruptionRestoresFlagAndDeletesFile() throws Exception {
        Thread.currentThread().interrupt();
        try {
            operations.run(shell("while :; do printf x; done"), new PdnListener() {});
            fail();
        } catch (InterruptedException expected) {
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals(0, directory.list().length);
    }
    @Test public void eventAndCompletionCallbackFailuresPropagate() throws Exception {
        RuntimeException failure = new RuntimeException("callback");
        for (boolean onComplete : new boolean[]{false, true}) {
            try {
                operations.run(shell(started() + result(0, "success")), new PdnListener() {
                    public void onEvent(PdnEvent event) { if (!onComplete) throw failure; }
                    public void onComplete(PdnResult result) { if (onComplete) throw failure; }
                });
                fail();
            } catch (RuntimeException actual) { assertSame(failure, actual); }
            assertEquals(0, directory.list().length);
        }
    }
    @Test public void guestMetadataAndHostErrorGettersAreJavaUsable() throws Exception {
        String script = started() + record(2, "result", ",\"exit_code\":143,\"outcome\":\"guest_exit\",\"guest_signal\":15,\"code\":\"guest_signal\",\"message\":\"signal\",\"suggestion\":\"retry\"") + "exit 143";
        PdnResult result = operations.run(shell(script), new PdnListener() {});
        assertEquals(Integer.valueOf(15), result.getGuestSignal());
        assertNull(result.getGuestExitCode());
        assertEquals("guest_signal", result.getCode());
        assertEquals("signal", result.getMessage());
        assertEquals("retry", result.getSuggestion());
        assertNotNull(result.getOperationId());
        PdnResult missing = operations.run(shell("exit 0"), new PdnListener() {});
        assertFalse(missing.isSuccess());
        assertNotNull(missing.getMessage());
        assertNotNull(missing.getSuggestion());
        assertNull(missing.getGuestSignal());
    }
    @Test public void oversizedAndInvalidUtf8RecordsAreRejected() throws Exception {
        for (String script : new String[]{"head -c 17000 /dev/zero > \"$PDN_EVENT_FILE\"", "printf '\\377\\n' > \"$PDN_EVENT_FILE\""}) {
            assertEquals("host_protocol_error", operations.run(shell(script), new PdnListener() {}).getOutcome());
        }
    }
    @Test public void startFailureDeletesPrivateFile() throws Exception {
        try { operations.run(new ProcessBuilder("/nonexistent/pdn-test"), new PdnListener() {}); fail(); }
        catch (java.io.IOException expected) { }
        assertEquals(0, directory.list().length);
    }
    @Test public void invalidSchemaFieldsAndGuestMetadataAreRejected() throws Exception {
        String[] fields = {
            ",\"outcome\":\"unknown\",\"exit_code\":0",
            ",\"outcome\":\"success\",\"exit_code\":256",
            ",\"outcome\":\"success\",\"exit_code\":0,\"guest_exit_code\":1",
            ",\"outcome\":\"success\",\"exit_code\":0,\"guest_signal\":0",
            ",\"outcome\":\"success\",\"exit_code\":0,\"guest_exit_code\":0,\"guest_signal\":15",
            ",\"outcome\":\"success\",\"exit_code\":0,\"percent\":101",
            ",\"outcome\":\"success\",\"exit_code\":0,\"total\":-2",
            ",\"outcome\":\"success\",\"exit_code\":0,\"message\":4",
            ",\"outcome\":\"success\",\"exit_code\":0.0"
        };
        for (String field : fields) {
            assertEquals("host_protocol_error", operations.run(shell(started() + record(2, "result", field)), new PdnListener() {}).getOutcome());
        }
        for (String script : new String[]{record(1, "unknown", ""), record(1, "stage", ""), started() + record(2, "started", "")}) {
            assertEquals("host_protocol_error", operations.run(shell(script), new PdnListener() {}).getOutcome());
        }
    }
    @Test public void boundedChannelRejectsOversizedTotalFile() throws Exception {
        assertEquals("host_protocol_error", operations.run(shell("head -c 8388609 /dev/zero > \"$PDN_EVENT_FILE\""), new PdnListener() {}).getOutcome());
    }
    @Test public void partialUtf8LinesAreDeliveredWhenCompleted() throws Exception {
        List<PdnEvent> events = new ArrayList<>();
        String script = started() + "{ printf '%s' '{\"version\":1,\"operation_id\":\"'\"$PDN_OPERATION_ID\"'\",\"sequence\":2,\"operation\":\"exec\",\"type\":\"stage\",\"stage\":\"'; printf '\\344'; sleep 0.03; printf '\\270\\255'; printf '%s\\n' '\"}'; } >> \"$PDN_EVENT_FILE\"; "
            + record(3, "result", ",\"exit_code\":0,\"outcome\":\"success\",\"guest_exit_code\":0");
        PdnResult result = operations.run(shell(script), new PdnListener() {
            public void onEvent(PdnEvent event) { events.add(event); }
        });
        assertTrue(result.isSuccess());
        assertEquals("中", events.get(1).getStage());
        assertEquals(Integer.valueOf(0), result.getGuestExitCode());
        assertEquals(Integer.valueOf(0), events.get(2).getGuestExitCode());
    }
    @Test public void callerEnvironmentAndDirectoryArePreserved() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ProcessBuilder builder = shell(started() + "printf '%s' \"$PDN_TEST_VALUE\"; [ \"$PWD\" = \"$PDN_TEST_DIRECTORY\" ] || exit 12; [ \"$(stat -c %a \"$PDN_EVENT_FILE\")\" = 600 ] || exit 13; " + result(0, "success"));
        builder.directory(directory);
        builder.environment().put("PDN_TEST_VALUE", "caller value");
        builder.environment().put("PDN_TEST_DIRECTORY", directory.getAbsolutePath());
        builder.environment().put("PDN_OPERATION_ID", "caller-operation");
        assertTrue(operations.run(builder, new PdnListener() {
            public void onStdout(byte[] bytes) { output.write(bytes, 0, bytes.length); }
        }).isSuccess());
        assertEquals("caller value", output.toString("UTF-8"));
        assertEquals("caller-operation", builder.environment().get("PDN_OPERATION_ID"));
        assertEquals(directory, builder.directory());
    }
    @Test public void primaryGuestStatusCanDifferFromProcessStatus() throws Exception {
        PdnResult result = operations.run(shell(started() + record(2, "result", ",\"exit_code\":37,\"outcome\":\"guest_exit\",\"guest_exit_code\":0") + "exit 37"), new PdnListener() {});
        assertEquals("guest_exit", result.getOutcome());
        assertEquals(37, result.getExitCode());
        assertEquals(Integer.valueOf(0), result.getGuestExitCode());
        assertFalse(result.isSuccess());
    }
    @Test public void inheritedDescriptorsDoNotBlockReturnOrCallbackCleanup() throws Exception {
        long start = System.nanoTime();
        assertTrue(operations.run(shell(started() + result(0, "success") + "sleep 2 & exit 0"), new PdnListener() {}).isSuccess());
        assertTrue((System.nanoTime() - start) / 1000000 < 1500);
        RuntimeException failure = new RuntimeException("callback");
        start = System.nanoTime();
        try {
            operations.run(shell(started() + "sleep 2 & while :; do printf x; done"), new PdnListener() {
                public void onStdout(byte[] data) { throw failure; }
            });
            fail();
        } catch (RuntimeException actual) { assertSame(failure, actual); }
        assertTrue((System.nanoTime() - start) / 1000000 < 1500);
    }
    @Test public void requiredFieldsAndCancellationSignalAreValidated() throws Exception {
        for (String record : new String[]{record(2, "stage", ""), record(2, "progress", ",\"current\":-1,\"total\":-1"), record(2, "progress", ",\"current\":0,\"total\":-1,\"percent\":0"), record(2, "error", ",\"code\":\"error\"")}) {
            assertEquals("host_protocol_error", operations.run(shell(started() + record), new PdnListener() {}).getOutcome());
        }
        PdnResult cancelled = operations.run(shell(started() + record(2, "result", ",\"outcome\":\"cancelled\",\"exit_code\":130,\"signal\":2") + "exit 130"), new PdnListener() {});
        assertEquals("cancelled", cancelled.getOutcome());
        assertEquals(Integer.valueOf(2), cancelled.getSignal());
    }
    @Test public void nativeProducerContractWhenFixtureAvailable() throws Exception {
        String executable = System.getenv("PDN_NATIVE_FIXTURE");
        org.junit.Assume.assumeTrue(executable != null && new File(executable).canExecute());
        List<PdnEvent> events = new ArrayList<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PdnResult result = operations.run(new ProcessBuilder(executable, "version"), new PdnListener() {
            public void onEvent(PdnEvent event) { events.add(event); }
            public void onStdout(byte[] bytes) { output.write(bytes, 0, bytes.length); }
        });
        assertTrue(result.getOutcome(), result.isSuccess());
        assertEquals("version", events.get(0).getOperation());
        assertEquals("started", events.get(0).getType());
        assertEquals("result", events.get(events.size() - 1).getType());
        assertTrue(output.size() > 0);
        ProcessBuilder failure = new ProcessBuilder(executable, "exec", "--rootfs", new File(directory, "missing-root").getAbsolutePath(), "--", "/bin/true");
        PdnResult failed = operations.run(failure, new PdnListener() {});
        assertEquals("manager_error", failed.getOutcome());
        assertTrue(failed.getExitCode() != 0);
        assertNotNull(failed.getCode());
    }
    @Test public void trailingDataAfterJsonIsRejected() throws Exception {
        String script = started() + "printf '%s\\n' '{\"version\":1,\"operation_id\":\"'\"$PDN_OPERATION_ID\"'\",\"sequence\":2,\"operation\":\"exec\",\"type\":\"result\",\"outcome\":\"success\",\"exit_code\":0} trailing' >> \"$PDN_EVENT_FILE\"";
        assertEquals("host_protocol_error", operations.run(shell(script), new PdnListener() {}).getOutcome());
    }
    @Test public void nativeGuestOutcomesWhenFixturesAvailable() throws Exception {
        String executable = System.getenv("PDN_NATIVE_FIXTURE");
        String busybox = System.getenv("PDN_GUEST_FIXTURE");
        org.junit.Assume.assumeTrue(executable != null && busybox != null
                && new File(executable).canExecute() && new File(busybox).canExecute());
        File root = new File(directory, "guest-root");
        assertTrue(new File(root, "bin").mkdirs());
        assertTrue(new File(root, "tmp").mkdirs());
        assertTrue(new File(root, "root").mkdirs());
        File guestBinary = new File(root, "bin/busybox");
        Files.copy(new File(busybox).toPath(), guestBinary.toPath());
        assertTrue(guestBinary.setExecutable(true));
        Files.createSymbolicLink(new File(root, "bin/sh").toPath(), java.nio.file.Paths.get("busybox"));
        for (int status : new int[]{0, 37}) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            List<String> records = new ArrayList<>();
            ProcessBuilder guest = nativeGuest(executable, root, "printf guest-bytes; exit " + status);
            if (status == 37) {
                guest.environment().put("PROOT_LOADER", new File(new File(executable).getParentFile(), "proot-loader").getAbsolutePath());
                guest.environment().put("PROOT_NO_SECCOMP", "1");
            }
            PdnResult result = operations.run(guest, new PdnListener() {
                public void onStdout(byte[] bytes) { output.write(bytes, 0, bytes.length); }
                public void onStderr(byte[] bytes) { errors.write(bytes, 0, bytes.length); }
                public void onEvent(PdnEvent event) { records.add(event.getType() + ":" + event.getOutcome() + ":" + event.getExitCode()); }
            });
            assertEquals(records + " stderr=" + errors.toString("UTF-8") + " actual=" + result.getExitCode(), status == 0 ? "success" : "guest_exit", result.getOutcome());
            assertEquals(status, result.getExitCode());
            assertEquals(Integer.valueOf(status), result.getGuestExitCode());
            assertEquals("guest-bytes", output.toString("UTF-8"));
        }
        PdnResult signal = operations.run(nativeGuest(executable, root, "kill -TERM $$"), new PdnListener() {});
        assertEquals("guest_exit", signal.getOutcome());
        assertEquals(255, signal.getExitCode());
        assertEquals(Integer.valueOf(15), signal.getGuestSignal());
        assertNull(signal.getGuestExitCode());
    }
    @Test public void nativeStartupErrorsReachJavaWhenFixtureAvailable() throws Exception {
        String executable = System.getenv("PDN_NATIVE_FIXTURE");
        org.junit.Assume.assumeTrue(executable != null && new File(executable).canExecute());
        File root = new File(directory, "startup-root");
        assertTrue(new File(root, "bin").mkdirs());
        assertTrue(new File(root, "tmp").mkdirs());
        assertTrue(new File(root, "root").mkdirs());
        List<PdnEvent> events = new ArrayList<>();
        PdnResult failure = operations.run(nativeGuest(executable, root, "exit 0"), new PdnListener() {
            public void onEvent(PdnEvent event) { events.add(event); }
        });
        assertEquals("manager_error", failure.getOutcome());
        assertEquals("guest_shell_missing", failure.getCode());
        assertTrue(failure.getExitCode() != 0);
        assertNotNull(failure.getSuggestion());
        assertTrue(failure.getMessage().contains("errno="));
        assertNull(failure.getGuestExitCode());
        assertNull(failure.getGuestSignal());
        assertEquals("result", events.get(events.size() - 1).getType());
        assertEquals(1L, events.stream().filter(event -> "result".equals(event.getType())).count());
        assertEquals(1L, events.stream().filter(event -> "error".equals(event.getType())).count());
    }
    private ProcessBuilder nativeGuest(String executable, File root, String command) {
        ProcessBuilder builder = new ProcessBuilder(executable, "exec", "--rootfs", root.getAbsolutePath(), "--", "/bin/sh", "-c", command);
        builder.environment().clear();
        builder.environment().put("PATH", "/system/bin");
        builder.environment().put("PROOT_TMP_DIR", directory.getAbsolutePath());
        return builder;
    }
    @Test public void mergedOrRedirectedStreamsAreRejected() throws Exception {
        try { operations.run(shell("exit 0").redirectErrorStream(true), new PdnListener() {}); fail(); }
        catch (IllegalArgumentException expected) { }
        try { operations.run(shell("exit 0").redirectOutput(new File(directory, "log")), new PdnListener() {}); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}
