package id.or.oo.pr.engine;

import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.HashMap;
import static org.junit.Assert.*;

public class PdnTerminalJavaTest {
    private static PdnRuntime runtime(File dir) {
        return new PdnRuntime(new ProotHost() {
            public File getNativeLibDir() { return dir; }
            public File getPrefixDir() { return dir; }
            public File getHomeDir() { return dir; }
            public File getCacheDir() { return dir; }
            public String getPackageName() { return "test.pdn"; }
        });
    }
    @Test public void forwardsPrivateProtocolOutputAndExactlyOneCompletion() throws Exception {
        for (String mode : new String[]{"success", "missing", "malformed", "signal", "exit-race"}) {
            File dir = Files.createTempDirectory("pdn-terminal-test").toFile();
            AtomicInteger outputs = new AtomicInteger(), events = new AtomicInteger(), completions = new AtomicInteger(), failures = new AtomicInteger();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<PdnResult> result = new AtomicReference<>();
            PdnTerminalSessionJavaTest.Fake fake = new PdnTerminalSessionJavaTest.Fake() {
                boolean output;
                boolean emptyRead;
                @Override public int[] spawn(String cmd, String[] args, String[] environment, int rows, int cols, String cwd) {
                    Map<String,String> env = new HashMap<>();
                    for (int i = 0; i < environment.length; i += 2) env.put(environment[i], environment[i + 1]);
                    String id = env.get("PDN_OPERATION_ID");
                    String prefix = "{\"version\":1,\"operation_id\":\"" + id + "\",\"operation\":\"login\",\"sequence\":";
                    String content = mode.equals("missing") ? "" : mode.equals("malformed") ? "bad\n" :
                        prefix + "1,\"type\":\"started\"}\n" + prefix + "2,\"type\":\"result\",\"outcome\":\"" + (mode.equals("signal") ? "guest_exit" : "success") + "\",\"exit_code\":" + (mode.equals("signal") ? 143 : 0) + (mode.equals("signal") ? ",\"guest_signal\":15" : "") + "}\n";
                    try { Files.write(new File(env.get("PDN_EVENT_FILE")).toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                    catch (Exception failure) { throw new RuntimeException(failure); }
                    if (mode.equals("signal")) states.add(new int[]{2,15,0});
                    return super.spawn(cmd,args,environment,rows,cols,cwd);
                }
                @Override public int read(int fd, byte[] data, int offset, int length) {
                    if (mode.equals("exit-race") && !emptyRead) { emptyRead = true; return 0; }
                    if (output) return 0; output = true; data[offset] = 'x'; return 1;
                }
            };
            ProcessBuilder builder = new ProcessBuilder("/native/engine");
            PdnTerminalSession session = new PdnTerminal(runtime(dir), fake).start(builder, 24, 80, new PdnTerminalListener() {
                public void onEvent(PdnEvent event) { events.incrementAndGet(); }
                public void onOutput(byte[] data) { assertArrayEquals(new byte[]{'x'}, data); outputs.incrementAndGet(); }
                public void onComplete(PdnResult value) { result.set(value); completions.incrementAndGet(); done.countDown(); }
                public void onFailure(Exception failure) { failures.incrementAndGet(); done.countDown(); }
            });
            assertTrue(done.await(3, TimeUnit.SECONDS));
            assertEquals(1, outputs.get()); assertEquals(1, completions.get()); assertEquals(0, failures.get());
            assertEquals(mode.equals("missing") || mode.equals("malformed") ? "host_protocol_error" : mode.equals("signal") ? "guest_exit" : "success", result.get().getOutcome());
            if (mode.equals("signal")) assertEquals(Integer.valueOf(15), result.get().getGuestSignal());
            assertTrue(session.isClosed()); assertFalse(builder.environment().containsKey("PDN_EVENT_FILE"));
            assertEquals(0, dir.list().length); assertTrue(dir.delete());
        }
    }
    @Test public void spawnFailureHasOneFailureAndCleansChannel() throws Exception {
        File dir = Files.createTempDirectory("pdn-terminal-failure").toFile();
        PdnTerminalSessionJavaTest.Fake fake = new PdnTerminalSessionJavaTest.Fake(); fake.spawnResult = new int[]{-1,-1,13};
        AtomicInteger failures = new AtomicInteger();
        assertThrows(PdnTerminalException.class, () -> new PdnTerminal(runtime(dir), fake).start(new ProcessBuilder("missing"),24,80,
            new PdnTerminalListener() { public void onFailure(Exception failure) { failures.incrementAndGet(); } }));
        assertEquals(1, failures.get()); assertEquals(0, dir.list().length); assertTrue(dir.delete());
    }
    @Test public void callbackErrorStopsAndReapsSessionBeforeFailure() throws Exception {
        File dir = Files.createTempDirectory("pdn-terminal-callback").toFile();
        PdnTerminalSessionJavaTest.Fake fake = new PdnTerminalSessionJavaTest.Fake() {
            @Override public int read(int fd, byte[] data, int offset, int length) { return 1; }
        };
        CountDownLatch done = new CountDownLatch(1); AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicInteger completed = new AtomicInteger();
        PdnTerminalSession session = new PdnTerminal(runtime(dir), fake).start(new ProcessBuilder("engine"),24,80,new PdnTerminalListener() {
            public void onOutput(byte[] data) { throw new AssertionError("listener error"); }
            public void onComplete(PdnResult result) { completed.incrementAndGet(); }
            public void onFailure(Exception caught) { failure.set(caught); done.countDown(); }
        });
        assertTrue(done.await(3,TimeUnit.SECONDS)); assertEquals(0,completed.get()); assertTrue(session.isClosed());
        assertEquals("host_terminal_callback_failed", ((PdnHostException)failure.get()).getCode());
        assertEquals(1,fake.closes); assertEquals(0,dir.list().length); assertTrue(dir.delete());
    }
    @Test public void invalidStreamsAndCacheFailureRemainStructured() throws Exception {
        File dir = Files.createTempDirectory("pdn-terminal-cache").toFile();
        PdnTerminal terminal = new PdnTerminal(runtime(dir),new PdnTerminalSessionJavaTest.Fake());
        assertThrows(IllegalArgumentException.class, () -> terminal.start(new ProcessBuilder("engine").redirectErrorStream(true),24,80,new PdnTerminalListener() {}));
        assertTrue(dir.delete()); Files.write(dir.toPath(),new byte[]{1});
        AtomicInteger failures = new AtomicInteger();
        PdnHostException failure = assertThrows(PdnHostException.class, () -> terminal.start(new ProcessBuilder("engine"),24,80,new PdnTerminalListener() {
            public void onFailure(Exception caught) { failures.incrementAndGet(); }
        }));
        assertEquals("host_terminal_start_failed",failure.getCode()); assertEquals(1,failures.get()); assertTrue(dir.delete());
    }
}
