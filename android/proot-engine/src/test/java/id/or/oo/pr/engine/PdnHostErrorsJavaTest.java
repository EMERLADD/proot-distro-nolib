package id.or.oo.pr.engine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class PdnHostErrorsJavaTest {
    private File directory;
    private PdnRuntime runtime;
    @Before public void setup() throws Exception {
        directory = Files.createTempDirectory("pdn-host-errors").toFile();
        runtime = runtime(directory);
    }
    private PdnRuntime runtime(File cache) {
        return new PdnRuntime(new ProotHost() {
            public File getNativeLibDir() { return directory; }
            public File getPrefixDir() { return directory; }
            public File getHomeDir() { return directory; }
            public File getCacheDir() { return cache; }
            public String getPackageName() { return "test.pdn"; }
        });
    }
    @After public void cleanup() throws Exception {
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(directory.toPath())) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
    private ProcessBuilder command() { return new ProcessBuilder("/bin/sh", "-c", "exit 0"); }
    private PdnHostException failure(PdnOperations operations, PdnListener listener) throws Exception {
        try { operations.run(command(), listener); fail(); }
        catch (IOException caught) {
            assertTrue(caught instanceof PdnHostException);
            PdnHostException actual = (PdnHostException) caught;
            assertFalse(actual.getMessage().isEmpty());
            assertFalse(actual.getSuggestion().isEmpty());
            return actual;
        }
        throw new AssertionError();
    }
    @Test public void regularFileCacheHasStructuredFailure() throws Exception {
        File cache = new File(directory, "cache-file");
        Files.write(cache.toPath(), new byte[]{1});
        PdnHostException actual = failure(new PdnOperations(runtime(cache)), new PdnListener() {});
        assertEquals("host_cache_failed", actual.getCode());
        assertTrue(actual.getCause() instanceof IOException);
        assertEquals(1, directory.list().length);
    }
    @Test public void unwritableEventDirectoryHasStructuredFailure() throws Exception {
        File cache = new File("/proc/self");
        org.junit.Assume.assumeTrue(cache.isDirectory());
        PdnHostException actual = failure(new PdnOperations(runtime(cache)), new PdnListener() {});
        assertEquals("host_event_channel_failed", actual.getCode());
        assertTrue(actual.getCause() instanceof IOException);
    }
    @Test public void missingExecutableRetainsStartCauseAndIOExceptionCompatibility() throws Exception {
        try {
            new PdnOperations(runtime).run(new ProcessBuilder(new File(directory, "missing-elf").getAbsolutePath()), new PdnListener() {});
            fail();
        } catch (IOException actual) {
            assertTrue(actual instanceof PdnHostException);
            assertEquals("host_process_start_failed", ((PdnHostException) actual).getCode());
            assertTrue(actual.getCause() instanceof IOException);
            assertTrue(actual.getCause().getMessage().contains("missing-elf"));
        }
        assertEquals(0, directory.list().length);
    }
    @Test public void processStartSecurityFailureRetainsCause() throws Exception {
        SecurityException denied = new SecurityException("execution denied");
        PdnHostException actual = failure(new PdnOperations(runtime, builder -> { throw denied; }), new PdnListener() {});
        assertEquals("host_process_start_failed", actual.getCode());
        assertSame(denied, actual.getCause());
        assertEquals(0, directory.list().length);
    }
    @Test public void inputCloseAndOutputReaderRetainCauses() throws Exception {
        IOException inputFailure = new IOException("input close");
        FixtureProcess input = new FixtureProcess();
        input.output = new OutputStream() {
            public void write(int value) {}
            public void close() throws IOException { throw inputFailure; }
        };
        PdnHostException actual = failure(new PdnOperations(runtime, builder -> input), new PdnListener() {});
        assertEquals("host_output_failed", actual.getCode());
        assertSame(inputFailure, actual.getCause());
        assertEquals("host_cleanup_failed", ((PdnHostException) actual.getSuppressed()[0]).getCode());
        IOException readerFailure = new IOException("reader read");
        FixtureProcess reader = new FixtureProcess();
        reader.input = new InputStream() {
            public int read() throws IOException { throw readerFailure; }
            public int available() { return 1; }
        };
        actual = failure(new PdnOperations(runtime, builder -> reader), new PdnListener() {});
        assertEquals("host_output_failed", actual.getCode());
        assertSame(readerFailure, actual.getCause());
        assertEquals(0, directory.list().length);
    }
    @Test public void stalledReaderHasBoundedOutputTimeout() throws Exception {
        FixtureProcess process = new FixtureProcess();
        CountDownLatch close = new CountDownLatch(1);
        process.input = new InputStream() {
            public int read() { return -1; }
            public int available() {
                boolean interrupted = false;
                while (true) {
                    try { close.await(); break; }
                    catch (InterruptedException ignored) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
                return 0;
            }
            public void close() { close.countDown(); }
        };
        long start = System.nanoTime();
        PdnHostException actual = failure(new PdnOperations(runtime, builder -> process), new PdnListener() {});
        assertEquals("host_output_failed", actual.getCode());
        assertNull(actual.getCause());
        assertTrue(actual.getMessage().contains("did not finish"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 6000);
        assertEquals(0, directory.list().length);
    }
    @Test public void cleanupStreamFailureIsStructured() throws Exception {
        IOException closeFailure = new IOException("stream close");
        FixtureProcess process = new FixtureProcess();
        process.input = new ByteArrayInputStream(new byte[0]) {
            public void close() throws IOException { throw closeFailure; }
        };
        PdnHostException actual = failure(new PdnOperations(runtime, builder -> process), new PdnListener() {});
        assertEquals("host_cleanup_failed", actual.getCode());
        assertSame(closeFailure, actual.getCause());
    }
    @Test public void processCleanupTimeoutIsSuppressedOnPrimaryCallback() throws Exception {
        RuntimeException callback = new RuntimeException("completion callback");
        FixtureProcess process = new FixtureProcess() {
            public boolean isAlive() { return !destroyed; }
            boolean destroyed;
            public Process destroyForcibly() { destroyed = false; return this; }
            public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
                unit.sleep(timeout);
                return false;
            }
            public InputStream getInputStream() {
                return new ByteArrayInputStream(new byte[]{1}) {
                    public int available() { return 1; }
                };
            }
        };
        try {
            new PdnOperations(runtime, builder -> process).run(command(), new PdnListener() {
                public void onStdout(byte[] bytes) { throw callback; }
            });
            fail();
        } catch (RuntimeException actual) {
            assertSame(callback, actual);
            PdnHostException cleanup = (PdnHostException) actual.getSuppressed()[0];
            assertEquals("host_cleanup_failed", cleanup.getCode());
            assertTrue(cleanup.getCause().getMessage().contains("process did not stop"));
        }
    }
    @Test public void cleanupFileFailureDoesNotMaskCallback() throws Exception {
        RuntimeException callback = new RuntimeException("listener");
        File[] channel = {null};
        PdnOperations operations = new PdnOperations(runtime, builder -> {
            channel[0] = new File(builder.environment().get("PDN_EVENT_FILE"));
            return new FixtureProcess();
        });
        try {
            operations.run(command(), new PdnListener() {
                public void onComplete(PdnResult result) {
                    assertTrue(channel[0].delete());
                    assertTrue(channel[0].mkdir());
                    try { Files.write(new File(channel[0], "child").toPath(), new byte[]{1}); }
                    catch (IOException failure) { throw new AssertionError(failure); }
                    throw callback;
                }
            });
            fail();
        } catch (RuntimeException actual) {
            assertSame(callback, actual);
            assertEquals(1, actual.getSuppressed().length);
            PdnHostException cleanup = (PdnHostException) actual.getSuppressed()[0];
            assertEquals("host_cleanup_failed", cleanup.getCode());
            assertTrue(cleanup.getCause() instanceof java.nio.file.DirectoryNotEmptyException);
        }
    }
    @Test public void callbackCheckedIOExceptionIsNotReclassified() throws Exception {
        IOException callback = new IOException("checked callback");
        for (String target : new String[]{"event", "stdout", "stderr", "complete"}) {
            String script = "printf '%s\\n' '{\"version\":1,\"operation_id\":\"'\"$PDN_OPERATION_ID\"'\",\"sequence\":1,\"operation\":\"exec\",\"type\":\"started\"}' >> \"$PDN_EVENT_FILE\"; printf x; printf y >&2";
            try {
                new PdnOperations(runtime).run(new ProcessBuilder("/bin/sh", "-c", script), new PdnListener() {
                    public void onEvent(PdnEvent event) { if (target.equals("event")) sneaky(callback); }
                    public void onStdout(byte[] bytes) { if (target.equals("stdout")) sneaky(callback); }
                    public void onStderr(byte[] bytes) { if (target.equals("stderr")) sneaky(callback); }
                    public void onComplete(PdnResult result) { if (target.equals("complete")) sneaky(callback); }
                });
                fail(target);
            } catch (IOException actual) { assertSame(callback, actual); }
            assertEquals(0, directory.list().length);
        }
    }
    private static <E extends Throwable> void sneaky(Throwable failure) throws E { throw (E) failure; }
    @Test public void eventReadFailureRetainsClosedFileCause() throws Exception {
        File channel = new File(directory, "events");
        Files.write(channel.toPath(), new byte[0]);
        RandomAccessFile file = new RandomAccessFile(channel, "r");
        file.close();
        Class<?> tail = Class.forName("id.or.oo.pr.engine.PdnOperations$Tail");
        Constructor<?> constructor = tail.getDeclaredConstructor(String.class, RandomAccessFile.class);
        constructor.setAccessible(true);
        Object instance = constructor.newInstance("test", file);
        Method read = tail.getDeclaredMethod("read", PdnListener.class);
        read.setAccessible(true);
        try { read.invoke(instance, new PdnListener() {}); fail(); }
        catch (InvocationTargetException failure) {
            PdnHostException actual = (PdnHostException) failure.getCause();
            assertEquals("host_event_channel_failed", actual.getCode());
            assertTrue(actual.getCause() instanceof IOException);
        }
    }
    private static class FixtureProcess extends Process {
        InputStream input = new ByteArrayInputStream(new byte[0]);
        OutputStream output = new ByteArrayOutputStream();
        public OutputStream getOutputStream() { return output; }
        public InputStream getInputStream() { return input; }
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        public int waitFor() { return 0; }
        public int exitValue() { return 0; }
        public boolean isAlive() { return false; }
        public void destroy() {}
    }
}
