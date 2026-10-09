package id.or.oo.pr.engine;

import org.junit.*;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

public class PdnTaskJavaTest {
    private File directory;
    private PdnRuntime runtime;
    private PdnOperations operations;
    @Before public void setup() throws Exception {
        directory = Files.createTempDirectory("pdn-task-test").toFile();
        runtime = new PdnRuntime(new ProotHost() {
            public File getNativeLibDir() { return directory; }
            public File getPrefixDir() { return directory; }
            public File getHomeDir() { return directory; }
            public File getCacheDir() { return directory; }
            public String getPackageName() { return "test.pdn"; }
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
    private String batchEvents() {
        String[] fields = {
            "\"type\":\"started\"", "\"type\":\"stage\",\"stage\":\"download\"",
            "\"type\":\"progress\",\"current\":1,\"total\":1,\"percent\":100",
            "\"type\":\"result\",\"outcome\":\"success\",\"exit_code\":0"
        };
        StringBuilder command = new StringBuilder();
        for (int index = 0; index < fields.length; index++) {
            command.append("printf '%s\\n' '{\"version\":1,\"operation_id\":\"'\"$PDN_OPERATION_ID\"'\",\"sequence\":")
                .append(index + 1).append(",\"operation\":\"exec\",").append(fields[index])
                .append("}' >> \"$PDN_EVENT_FILE\"; ");
        }
        return command.toString();
    }

    private Throwable failed(PdnTask task) throws Exception {
        return assertThrows(ExecutionException.class, () -> task.await(5, TimeUnit.SECONDS)).getCause();
    }
    @Test public void concurrentTasksCompleteIndependently() throws Exception {
        AtomicInteger complete = new AtomicInteger();
        PdnListener listener = new PdnListener() { public void onComplete(PdnResult result) { complete.incrementAndGet(); } };
        PdnTask a = operations.start(shell(events()), listener);
        PdnTask b = operations.start(shell(events()), listener, 2000);
        assertTrue(a.await(5, TimeUnit.SECONDS).isSuccess());
        assertTrue(b.await().isSuccess());
        assertTrue(a.isDone()); assertFalse(a.isCancelled()); assertFalse(a.cancel());
        assertEquals(2, complete.get()); assertEquals(0, directory.list().length);
    }
    @Test public void callbackExecutorPreservesOrderAndThread() throws Exception {
        ExecutorService callbacks = Executors.newFixedThreadPool(3, runnable -> new Thread(runnable, "test-callback"));
        try {
            List<String> received = new ArrayList<>();
            AtomicInteger active = new AtomicInteger();
            PdnTask task = operations.start(shell(events() + "printf hello; printf error >&2"), new PdnListener() {
                private void check() { assertEquals("test-callback", Thread.currentThread().getName()); assertEquals(1, active.incrementAndGet()); }
                public void onEvent(PdnEvent event) { check(); received.add(event.getType()); active.decrementAndGet(); }
                public void onStdout(byte[] data) { check(); received.add("stdout"); active.decrementAndGet(); }
                public void onStderr(byte[] data) { check(); received.add("stderr"); active.decrementAndGet(); }
                public void onComplete(PdnResult result) { check(); received.add("complete"); active.decrementAndGet(); }
            }, 0, callbacks);
            assertTrue(task.await(5, TimeUnit.SECONDS).isSuccess());
            assertTrue(received.indexOf("started") < received.indexOf("result"));
            assertEquals("complete", received.get(received.size() - 1));
            assertTrue(received.contains("stdout")); assertTrue(received.contains("stderr"));
        } finally { callbacks.shutdownNow(); }
    }
    @Test public void runningCancellationAndTimeoutStopProcessesBeforeAwait() throws Exception {
        for (boolean timeout : new boolean[]{false, true}) {
            AtomicReference<Process> process = new AtomicReference<>();
            CountDownLatch started = new CountDownLatch(1);
            AtomicInteger terminal = new AtomicInteger();
            PdnOperations tracking = new PdnOperations(runtime, builder -> {
                Process value = builder.start(); process.set(value); started.countDown(); return value;
            });
            PdnTask task = tracking.start(shell("exec sleep 30"), new PdnListener() {
                public void onFailure(Exception failure) { assertFalse(process.get().isAlive()); terminal.incrementAndGet(); }
                public void onComplete(PdnResult result) { fail("Cancellation completed normally"); }
            }, timeout ? 100 : 0);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            if (!timeout) { assertTrue(task.cancel()); assertFalse(task.cancel()); }
            PdnHostException failure = (PdnHostException) failed(task);
            assertEquals(timeout ? "host_operation_timeout" : "host_operation_cancelled", failure.getCode());
            assertFalse(process.get().isAlive()); assertTrue(task.isDone()); assertTrue(task.isCancelled());
            assertEquals(1, terminal.get()); assertEquals(0, directory.list().length);
        }
    }
    @Test public void queuedCancellationNeverStartsProcess() throws Exception {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger terminal = new AtomicInteger();
        PdnOperations tracking = new PdnOperations(runtime, builder -> { starts.incrementAndGet(); return builder.start(); });
        PdnTask task = tracking.start(shell(events()), new PdnListener() {
            public void onFailure(Exception failure) { terminal.incrementAndGet(); }
        }, 0, null, queued::set);
        assertFalse(task.isDone()); assertTrue(task.cancel());
        assertFalse(task.isDone()); queued.get().run();
        assertEquals("host_operation_cancelled", ((PdnHostException) failed(task)).getCode());
        assertEquals(0, starts.get()); assertEquals(1, terminal.get());
    }
    @Test public void listenerFailureRetainsCauseAndCleansUp() throws Exception {
        RuntimeException cause = new RuntimeException("listener failed");
        AtomicReference<Process> process = new AtomicReference<>();
        AtomicInteger terminal = new AtomicInteger();
        ExecutorService callbacks = Executors.newFixedThreadPool(2);
        try {
            PdnOperations tracking = new PdnOperations(runtime, builder -> { Process value = builder.start(); process.set(value); return value; });
            PdnTask task = tracking.start(shell("printf output; exec sleep 30"), new PdnListener() {
                public void onStdout(byte[] data) { throw cause; }
                public void onFailure(Exception failure) { assertSame(cause, failure); assertFalse(process.get().isAlive()); terminal.incrementAndGet(); }
            }, 0, callbacks);
            assertSame(cause, failed(task)); assertEquals(1, terminal.get()); assertEquals(0, directory.list().length);
        } finally { callbacks.shutdownNow(); }
    }
    @Test public void rejectedExecutorsAndTerminalExceptionsFinishOnce() throws Exception {
        RejectedExecutionException rejection = new RejectedExecutionException("rejected");
        Executor rejected = runnable -> { throw rejection; };
        AtomicInteger failures = new AtomicInteger();
        PdnTask task = operations.start(shell("exit 0"), new PdnListener() {
            public void onFailure(Exception failure) { assertSame(rejection, failure); failures.incrementAndGet(); }
        }, 0, rejected);
        assertSame(rejection, failed(task)); assertEquals(1, failures.get());
        task = operations.start(shell(events()), new PdnListener() {
            public void onFailure(Exception failure) { failures.incrementAndGet(); }
        }, 0, null, rejected);
        assertSame(rejection, failed(task)); assertEquals(2, failures.get());
        RuntimeException thrown = new RuntimeException("complete failed");
        task = operations.start(shell(events()), new PdnListener() {
            public void onComplete(PdnResult result) { throw thrown; }
            public void onFailure(Exception failure) { fail("A second terminal callback was delivered"); }
        });
        assertSame(thrown, failed(task).getCause());
        task = operations.start(shell("exit 0"), new PdnListener() {
            public void onFailure(Exception failure) { throw thrown; }
        }, 0, rejected);
        assertSame(thrown, failed(task).getSuppressed()[0]);
    }
    @Test public void awaitTimeoutAndInterruptedWaitDoNotCancelTask() throws Exception {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        PdnTask task = operations.start(shell(events()), new PdnListener() {}, 0, null, queued::set);
        assertThrows(TimeoutException.class, () -> task.await(1, TimeUnit.MILLISECONDS));
        Thread.currentThread().interrupt();
        assertThrows(InterruptedException.class, task::await);
        assertFalse(task.isCancelled());
        queued.get().run(); assertTrue(task.await().isSuccess());
        assertThrows(IllegalArgumentException.class, () -> operations.start(shell("true"), new PdnListener() {}, -1));
    }
    @Test public void snapshotsBuilderAndDefaultCallbacksUseWorker() throws Exception {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        Thread caller = Thread.currentThread();
        ProcessBuilder builder = shell(events());
        PdnTask task = operations.start(builder, new PdnListener() {
            public void onComplete(PdnResult result) { assertNotSame(caller, Thread.currentThread()); }
        }, 0, null, queued::set);
        builder.command("/missing");
        Thread worker = new Thread(queued.get()); worker.start();
        assertTrue(task.await(5, TimeUnit.SECONDS).isSuccess()); worker.join();
    }
    @Test public void cancellationSkipsPendingCallbacksAndCleansBeforeExecutorResumes() throws Exception {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        AtomicReference<Process> process = new AtomicReference<>();
        AtomicInteger terminal = new AtomicInteger();
        PdnOperations tracking = new PdnOperations(runtime, builder -> {
            Process value = builder.start(); process.set(value); return value;
        });
        PdnTask task = tracking.start(shell("printf output; exec sleep 30"), new PdnListener() {
            public void onStdout(byte[] data) { fail("A cancelled pending callback ran"); }
            public void onFailure(Exception failure) { terminal.incrementAndGet(); }
        }, 0, callbacks::add);
        Runnable pending = callbacks.poll(2, TimeUnit.SECONDS);
        assertNotNull(pending); assertTrue(task.cancel());
        Runnable completion = callbacks.poll(3, TimeUnit.SECONDS);
        assertNotNull(completion);
        assertFalse(process.get().isAlive());
        assertEquals(0, directory.list().length);
        assertFalse(task.isDone());
        pending.run(); completion.run();
        assertEquals("host_operation_cancelled", ((PdnHostException) failed(task)).getCode());
        assertEquals(1, terminal.get());
    }

    @Test public void queuedTimeoutAndFailureCallbackExceptionsPreservePrimary() throws Exception {
        AtomicReference<Runnable> queued = new AtomicReference<>();
        RuntimeException callbackFailure = new RuntimeException("failure callback");
        PdnTask task = operations.start(shell(events()), new PdnListener() {
            public void onFailure(Exception failure) { throw callbackFailure; }
        }, 10, null, queued::set);
        PdnTask queuedTask = task;
        assertThrows(TimeoutException.class, () -> queuedTask.await(100, TimeUnit.MILLISECONDS));
        assertTrue(task.isCancelled()); queued.get().run();
        PdnHostException failure = (PdnHostException) failed(task);
        assertEquals("host_operation_timeout", failure.getCode());
        assertSame(callbackFailure, failure.getSuppressed()[0]);
        queued.get().run(); assertTrue(task.isDone());
        Error cause = new AssertionError("output callback");
        task = operations.start(shell("printf output; exec sleep 30"), new PdnListener() {
            public void onStdout(byte[] data) { throw cause; }
        });
        assertSame(cause, failed(task).getCause());
    }

    @Test public void interruptedWorkerStopsProcessAndSignalsFailure() throws Exception {
        AtomicReference<Process> process = new AtomicReference<>();
        PdnOperations tracking = new PdnOperations(runtime, builder -> { Process value = builder.start(); process.set(value); return value; });
        PdnTask task = tracking.start(shell("printf output; exec sleep 30"), new PdnListener() {
            public void onStdout(byte[] data) { Thread.currentThread().interrupt(); }
        });
        assertTrue(failed(task) instanceof InterruptedException);
        assertFalse(process.get().isAlive());
    }

    @Test public void cancellationInFirstEventStopsBatchAndOutputCallbacks() throws Exception {
        for (Executor executor : new Executor[]{null, Runnable::run}) {
            AtomicReference<Runnable> queued = new AtomicReference<>();
            AtomicReference<PdnTask> task = new AtomicReference<>();
            AtomicInteger delivered = new AtomicInteger();
            AtomicInteger terminal = new AtomicInteger();
            PdnOperations exited = new PdnOperations(runtime, builder -> {
                Process value = builder.start();
                try { value.waitFor(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
                return value;
            });
            task.set(exited.start(shell(batchEvents() + "printf output; printf error >&2"), new PdnListener() {
                public void onEvent(PdnEvent event) { assertEquals(1, delivered.incrementAndGet()); assertTrue(task.get().cancel()); }
                public void onStdout(byte[] data) { fail("Output delivered after cancellation"); }
                public void onStderr(byte[] data) { fail("Error output delivered after cancellation"); }
                public void onComplete(PdnResult result) { fail("Cancelled task completed"); }
                public void onFailure(Exception failure) { terminal.incrementAndGet(); }
            }, 0, executor, queued::set));
            queued.get().run();
            assertEquals("host_operation_cancelled", ((PdnHostException) failed(task.get())).getCode());
            assertEquals(1, delivered.get()); assertEquals(1, terminal.get());
        }
    }

    @Test public void slowCallbackDoesNotConsumeStreamDrainDeadline() throws Exception {
        ExecutorService callbacks = Executors.newSingleThreadExecutor();
        try {
            PdnOperations exited = new PdnOperations(runtime, builder -> {
                Process value = builder.start();
                try { value.waitFor(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
                return value;
            });
            PdnTask task = exited.start(shell(events()), new PdnListener() {
                public void onEvent(PdnEvent event) {
                    if ("started".equals(event.getType())) {
                        try { Thread.sleep(2100); }
                        catch (InterruptedException failure) { throw new RuntimeException(failure); }
                    }
                }
            }, 0, callbacks);
            assertTrue(task.await(5, TimeUnit.SECONDS).isSuccess());
        } finally { callbacks.shutdownNow(); }
    }

    @Test public void rejectedWorkerDoesNotWaitForPostedFailure() throws Exception {
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        AtomicInteger failures = new AtomicInteger();
        PdnTask task = operations.start(shell(events()), new PdnListener() {
            public void onFailure(Exception failure) { failures.incrementAndGet(); }
        }, 0, callbacks::add, runnable -> { throw new RejectedExecutionException("full"); });
        assertFalse(task.isDone());
        assertEquals(0, failures.get());
        callbacks.remove().run();
        assertTrue(failed(task) instanceof RejectedExecutionException);
        assertEquals(1, failures.get());
    }
}
