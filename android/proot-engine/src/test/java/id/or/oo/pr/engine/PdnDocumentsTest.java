package id.or.oo.pr.engine;

import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class PdnDocumentsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private PdnRuntime runtime() throws Exception {
        File base = temporary.newFolder();
        for (String name : new String[]{"libpdn.so", "libproot-loader.so"}) {
            File program = new File(base, name);
            Files.write(program.toPath(), new byte[]{1});
            assertTrue(program.setExecutable(true));
        }
        return new PdnRuntime(new ProotHost() {
            public File getNativeLibDir() { return base; }
            public File getPrefixDir() { return base; }
            public File getHomeDir() { return base; }
            public File getCacheDir() { return new File(base, "cache"); }
            public String getPackageName() { return "test.documents"; }
        });
    }
    private PdnResult result(boolean success) throws Exception {
        return new PdnResult("test", success ? 0 : 2, new PdnEvent(new JSONObject()
                .put("outcome", success ? "success" : "manager_error").put("code", "archive_invalid")));
    }
    private void clean(PdnRuntime runtime) {
        File[] entries = new File(runtime.environment().get("PROOT_TMP_DIR")).listFiles();
        assertNotNull(entries);
        assertEquals(0, entries.length);
    }
    @Test public void importRoutesPreserveDataAndCleanBeforeCompletion() throws Exception {
        PdnRuntime runtime = runtime();
        AtomicInteger calls = new AtomicInteger();
        PdnResult success = result(true);
        PdnDocuments documents = new PdnDocuments(runtime, (builder, listener) -> {
            String path = builder.command().get(builder.command().size() - 1);
            assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(new File(path).toPath()));
            calls.incrementAndGet();
            listener.onStdout(new byte[]{4});
            listener.onComplete(success);
            return success;
        });
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger output = new AtomicInteger();
        PdnListener listener = new PdnListener() {
            public void onComplete(PdnResult result) { clean(runtime); completions.incrementAndGet(); }
            public void onStdout(byte[] bytes) { output.incrementAndGet(); }
        };
        assertTrue(documents.install("alpine", new ByteArrayInputStream(new byte[]{1, 2, 3}), listener).isSuccess());
        assertTrue(documents.installAs("alpine", "work", new ByteArrayInputStream(new byte[]{1, 2, 3}), listener).isSuccess());
        assertTrue(documents.restore("restored", new ByteArrayInputStream(new byte[]{1, 2, 3}), listener).isSuccess());
        assertEquals(3, calls.get()); assertEquals(3, completions.get()); assertEquals(3, output.get());
    }
    @Test public void exportWritesCompletedBackupAndKeepsCallerStreamOpen() throws Exception {
        PdnRuntime runtime = runtime();
        PdnResult success = result(true);
        PdnDocuments documents = new PdnDocuments(runtime, (builder, listener) -> {
            Files.write(new File(builder.command().get(3)).toPath(), new byte[]{5, 6});
            return success;
        });
        boolean[] closed = {false};
        ByteArrayOutputStream output = new ByteArrayOutputStream() {
            public void close() { closed[0] = true; }
        };
        assertTrue(documents.backup("alpine", output, new PdnListener() {}).isSuccess());
        assertArrayEquals(new byte[]{5, 6}, output.toByteArray());
        assertFalse(closed[0]); clean(runtime);
    }
    @Test public void failedNativeBackupLeavesDestinationUntouched() throws Exception {
        PdnRuntime runtime = runtime(); PdnResult failure = result(false);
        PdnDocuments documents = new PdnDocuments(runtime, (builder, listener) -> failure);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); output.write(9);
        assertFalse(documents.backup("missing", output, new PdnListener() {}).isSuccess());
        assertArrayEquals(new byte[]{9}, output.toByteArray()); clean(runtime);
    }
    @Test public void importIoFailureIsStructuredAndDoesNotLaunchNative() throws Exception {
        PdnRuntime runtime = runtime();
        PdnDocuments documents = new PdnDocuments(runtime, (builder, listener) -> { fail(); return null; });
        InputStream input = new InputStream() { public int read() throws IOException { throw new IOException("offline provider"); } };
        try { documents.install("alpine", input, new PdnListener() {}); fail(); }
        catch (PdnHostException failure) { assertEquals("host_document_io_failed", failure.getCode()); }
        clean(runtime);
    }
    @Test public void exportFailureHasNoSuccessfulCompletionAndCleans() throws Exception {
        PdnRuntime runtime = runtime(); PdnResult success = result(true);
        PdnDocuments documents = new PdnDocuments(runtime, (builder, listener) -> {
            Files.write(new File(builder.command().get(3)).toPath(), new byte[]{1}); return success;
        });
        OutputStream output = new OutputStream() { public void write(int value) throws IOException { throw new IOException("full"); } };
        try { documents.backup("alpine", output, new PdnListener() { public void onComplete(PdnResult result) { fail(); } }); fail(); }
        catch (PdnHostException failure) { assertEquals("host_document_io_failed", failure.getCode()); }
        clean(runtime);
    }
    @Test public void interruptedTransferStopsBeforeNativeAndCleans() throws Exception {
        PdnRuntime runtime = runtime();
        PdnDocuments documents = new PdnDocuments(runtime, (builder, listener) -> { fail(); return null; });
        Thread.currentThread().interrupt();
        try { documents.restore("restored", new ByteArrayInputStream(new byte[]{1}), new PdnListener() {}); fail(); }
        catch (InterruptedException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
        finally { Thread.interrupted(); }
        clean(runtime);
    }
    @Test public void invalidArgumentsDoNotConsumeInputAndClean() throws Exception {
        PdnRuntime runtime = runtime();
        PdnDocuments documents = new PdnDocuments(runtime, (builder, listener) -> { fail(); return null; });
        InputStream input = new InputStream() { public int read() { fail(); return -1; } };
        try { documents.restore("../bad", input, new PdnListener() {}); fail(); }
        catch (IllegalArgumentException expected) {}
        clean(runtime);
    }
}
