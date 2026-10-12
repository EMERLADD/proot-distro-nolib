package id.or.oo.pr.engine;

import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.*;
import static org.junit.Assert.*;

public class PdnTaskInputTest {
    private File directory;
    private PdnOperations operations;
    @Before public void setup() throws Exception {
        directory = Files.createTempDirectory("pdn-input-test").toFile();
        PdnRuntime runtime = new PdnRuntime(new ProotHost() {
            public File getNativeLibDir() { return directory; }
            public File getPrefixDir() { return directory; }
            public File getHomeDir() { return directory; }
            public File getCacheDir() { return directory; }
            public String getPackageName() { return "test.input"; }
        });
        operations = new PdnOperations(runtime);
    }
    @After public void cleanup() throws Exception {
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(directory.toPath())) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
    private ProcessBuilder shell(String command) { return new ProcessBuilder("/bin/sh", "-c", command); }
    private String events() {
        return "printf '%s\\n' '{\"version\":1,\"operation_id\":\"'\"$PDN_OPERATION_ID\"'\",\"sequence\":1,\"operation\":\"exec\",\"type\":\"started\"}' '{\"version\":1,\"operation_id\":\"'\"$PDN_OPERATION_ID\"'\",\"sequence\":2,\"operation\":\"exec\",\"type\":\"result\",\"outcome\":\"success\",\"exit_code\":0}' > \"$PDN_EVENT_FILE\"; ";
    }
    private void code(String code, Throwing action) throws Exception {
        try { action.run(); fail(); } catch (PdnHostException failure) { assertEquals(code, failure.getCode()); }
    }
    private interface Throwing { void run() throws Exception; }
    @Test public void inputBeforeStartupPreservesOrderAndEof() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PdnTask task = operations.startInteractive(shell("cat; " + events()), new PdnListener() {
            public void onStdout(byte[] bytes) { output.write(bytes, 0, bytes.length); }
        }, 0, null, queued::add);
        byte[] bytes = "second\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        task.writeStdin("first\n"); task.writeStdin(bytes); Arrays.fill(bytes, (byte)'X');
        task.closeStdin(); task.closeStdin(); assertFalse(task.isStdinOpen());
        code("host_input_closed", () -> task.writeStdin("late"));
        queued.get(0).run();
        assertTrue(task.await(5, TimeUnit.SECONDS).isSuccess());
        assertEquals("first\nsecond\n", output.toString("UTF-8"));
        assertEquals(0, directory.list().length);
    }
    @Test public void runningTaskWaitsForInputAndDeliversPrompt() throws Exception {
        CountDownLatch prompt = new CountDownLatch(1);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PdnTask task = operations.startInteractive(shell("printf READY; read line; printf '%s' \"$line\"; " + events()), new PdnListener() {
            public void onStdout(byte[] bytes) { output.write(bytes, 0, bytes.length); prompt.countDown(); }
        }, 5000);
        assertTrue(prompt.await(3, TimeUnit.SECONDS)); assertFalse(task.isDone()); assertTrue(task.isStdinOpen());
        task.writeStdin("hello 世界\n"); task.closeStdin();
        assertTrue(task.await(5, TimeUnit.SECONDS).isSuccess());
        assertEquals("READYhello 世界", output.toString("UTF-8"));
        code("host_input_closed", () -> task.writeStdin(new byte[]{1}));
    }
    @Test public void defaultTasksStillReceiveImmediateEof() throws Exception {
        PdnTask task = operations.start(shell("if read line; then exit 3; fi; " + events()), new PdnListener() {});
        assertFalse(task.isStdinOpen());
        code("host_input_disabled", () -> task.writeStdin("x"));
        code("host_input_disabled", task::closeStdin);
        assertTrue(task.await(5, TimeUnit.SECONDS).isSuccess());
    }
    @Test public void boundedQueueRejectsWholeWriteWithoutPartialEnqueue() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        AtomicInteger length = new AtomicInteger();
        PdnTask task = operations.startInteractive(shell("cat; " + events()), new PdnListener() {
            public void onStdout(byte[] bytes) { length.addAndGet(bytes.length); }
        }, 0, null, queued::add);
        code("host_input_backpressure", () -> task.writeStdin(new byte[65537]));
        code("host_input_backpressure", () -> task.writeStdin(String.join("", Collections.nCopies(65537, "a"))));
        for (int i = 0; i < 4; i++) task.writeStdin(new byte[65536]);
        code("host_input_backpressure", () -> task.writeStdin(new byte[]{1}));
        task.closeStdin(); queued.get(0).run();
        assertTrue(task.await(5, TimeUnit.SECONDS).isSuccess()); assertEquals(262144, length.get());
    }
    @Test public void waitingReaderCancellationAndTimeoutCloseInput() throws Exception {
        for (boolean timeout : new boolean[]{false, true}) {
            CountDownLatch prompt = new CountDownLatch(1);
            PdnTask task = operations.startInteractive(shell("printf READY; exec cat"), new PdnListener() {
                public void onStdout(byte[] bytes) { prompt.countDown(); }
            }, timeout ? 500 : 0);
            assertTrue(prompt.await(3, TimeUnit.SECONDS));
            if (!timeout) assertTrue(task.cancel());
            ExecutionException failure = assertThrows(ExecutionException.class, () -> task.await(5, TimeUnit.SECONDS));
            assertEquals(timeout ? "host_operation_timeout" : "host_operation_cancelled", ((PdnHostException)failure.getCause()).getCode());
            assertFalse(task.isStdinOpen()); code("host_input_closed", () -> task.writeStdin("x"));
            task.closeStdin(); assertEquals(0, directory.list().length);
        }
    }
    @Test public void queuedCancellationNeverStartsProcess() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        PdnTask task = operations.startInteractive(shell("exit 0"), new PdnListener() {}, 0, null, queued::add);
        task.writeStdin("queued"); assertTrue(task.cancel()); queued.get(0).run();
        assertThrows(ExecutionException.class, task::await); assertFalse(task.isStdinOpen());
        assertEquals(0, directory.list().length);
    }
    @Test public void brokenInputPipeFailsTaskWithStructuredError() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        PdnTask task = operations.startInteractive(shell("exec 0<&-; printf READY; exec sleep 10"), new PdnListener() {
            public void onStdout(byte[] bytes) { ready.countDown(); }
        });
        try {
            assertTrue(ready.await(3, TimeUnit.SECONDS)); task.writeStdin(new byte[65536]);
            ExecutionException failure = assertThrows(ExecutionException.class, () -> task.await(5, TimeUnit.SECONDS));
            assertEquals("host_input_failed", ((PdnHostException)failure.getCause()).getCode());
        } finally { task.cancel(); }
    }
    @Test public void cancellationReleasesBlockedWriter() throws Exception {
        CountDownLatch ready = new CountDownLatch(1);
        PdnTask task = operations.startInteractive(shell("printf READY; exec sleep 10"), new PdnListener() {
            public void onStdout(byte[] bytes) { ready.countDown(); }
        });
        assertTrue(ready.await(3, TimeUnit.SECONDS));
        for (int i = 0; i < 4; i++) task.writeStdin(new byte[65536]);
        assertTrue(task.cancel());
        assertThrows(ExecutionException.class, () -> task.await(5, TimeUnit.SECONDS));
        assertFalse(task.isStdinOpen()); assertEquals(0, directory.list().length);
    }
    @Test public void callbackExecutorCanSendInputWithoutBlockingWorker() throws Exception {
        List<Runnable> queued = new ArrayList<>();
        ExecutorService callbacks = Executors.newSingleThreadExecutor(r -> new Thread(r, "input-callback"));
        AtomicReference<PdnTask> handle = new AtomicReference<>();
        AtomicBoolean sent = new AtomicBoolean();
        try {
            PdnTask task = operations.startInteractive(shell("printf READY; read line; " + events()), new PdnListener() {
                public void onStdout(byte[] bytes) {
                    assertEquals("input-callback", Thread.currentThread().getName());
                    if (sent.compareAndSet(false, true)) {
                        try { handle.get().writeStdin("answer\n"); handle.get().closeStdin(); }
                        catch (PdnHostException failure) { throw new RuntimeException(failure); }
                    }
                }
            }, 0, callbacks, queued::add);
            handle.set(task); queued.get(0).run();
            assertTrue(task.await(5, TimeUnit.SECONDS).isSuccess()); assertTrue(sent.get());
        } finally { callbacks.shutdownNow(); }
    }
    @Test public void rejectedWorkerClosesQueuedInput() throws Exception {
        PdnTask task = operations.startInteractive(shell("exit 0"), new PdnListener() {}, 0, null,
                command -> { throw new RejectedExecutionException("full"); });
        assertThrows(ExecutionException.class, task::await);
        assertFalse(task.isStdinOpen()); code("host_input_closed", () -> task.writeStdin("data"));
        assertEquals(0, directory.list().length);
    }
}
