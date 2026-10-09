package id.or.oo.pr.engine;

import org.junit.Test;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import static org.junit.Assert.*;

public class PdnTerminalSessionJavaTest {
    static class Fake implements PdnTerminalSession.Bridge {
        int next = 40, closes, signals, readResult, writeResult = 1, resizeResult;
        int[] spawnResult;
        final Queue<int[]> states = new ArrayDeque<>();
        public int[] spawn(String cmd, String[] args, String[] env, int rows, int cols, String cwd) {
            return spawnResult == null ? new int[]{next++, next++, 0} : spawnResult;
        }
        public int read(int fd, byte[] data, int offset, int length) { return readResult; }
        public int write(int fd, byte[] data, int offset, int length) { return writeResult; }
        public int resize(int fd, int rows, int cols) { return resizeResult; }
        public int[] poll(int pid) { return states.isEmpty() ? new int[]{1, 0, 0} : states.remove(); }
        public int signal(int pid, int sig) { signals++; return 0; }
        public void close(int fd) { closes++; }
    }
    static PdnTerminalSession session(Fake fake) throws Exception {
        return PdnTerminalSession.start(new ProcessBuilder("/native/engine", "😀"), 24, 80, fake);
    }
    @Test public void independentSessionsReapOnceAndDistinguishStatuses() throws Exception {
        Fake fake = new Fake();
        PdnTerminalSession first = session(fake), second = session(fake);
        assertNotEquals(first.getPid(), second.getPid()); assertNotEquals(first.getMasterFd(), second.getMasterFd());
        fake.states.add(new int[]{0, 0, 0}); fake.states.add(new int[]{1, 0, 0});
        assertNull(first.poll()); assertTrue(first.waitFor().isSuccess());
        fake.states.add(new int[]{2, 11, 0});
        assertEquals(Integer.valueOf(11), second.poll().getSignal()); assertNull(second.poll().getExitCode());
        first.close(); first.close(); second.close(); assertEquals(2, fake.closes); assertEquals(0, fake.signals);
        assertTrue(first.isClosed());
    }
    @Test public void validatesArgumentsBuffersDimensionsAndClosedInput() throws Exception {
        Fake fake = new Fake(); PdnTerminalSession session = session(fake);
        assertThrows(IllegalArgumentException.class, () -> session.resize(0, 80));
        assertThrows(IllegalArgumentException.class, () -> session.resize(24, 65536));
        assertThrows(IndexOutOfBoundsException.class, () -> session.read(new byte[1], 1, 1));
        assertThrows(IndexOutOfBoundsException.class, () -> session.write(new byte[1], -1, 1));
        assertThrows(IllegalArgumentException.class, () -> PdnTerminalSession.start(new ProcessBuilder("bad\u0000"), 24, 80, fake));
        assertThrows(IllegalArgumentException.class, () -> PdnTerminalSession.start(new ProcessBuilder("\ud800"), 24, 80, fake));
        assertThrows(IllegalArgumentException.class, () -> PdnTerminalSession.start(new ProcessBuilder(), 24, 80, fake));
        assertThrows(IllegalArgumentException.class, () -> PdnTerminalSession.start(new ProcessBuilder("x"), 65536, 80, fake));
        session.close();
        assertThrows(IOException.class, () -> session.write(new byte[1]));
        assertThrows(IOException.class, () -> session.read(new byte[1]));
        assertThrows(IOException.class, () -> session.resize(24, 80));
    }
    @Test public void errorsRetainErrnoAndHangupIsEof() throws Exception {
        for (int errno : new int[]{2, 13, 8, 12}) {
            Fake fake = new Fake(); fake.spawnResult = new int[]{-1, -1, errno};
            PdnTerminalException failure = assertThrows(PdnTerminalException.class, () -> session(fake));
            assertEquals(errno, failure.getErrno()); assertEquals("spawn", failure.getCode()); assertNotNull(failure.getSuggestion());
        }
        Fake fake = new Fake(); PdnTerminalSession session = session(fake);
        fake.readResult = -5; assertEquals(-1, session.read(new byte[4]));
        fake.readResult = -9; assertEquals(9, assertThrows(PdnTerminalException.class, () -> session.read(new byte[4])).getErrno());
        fake.writeResult = -32; assertEquals(32, assertThrows(PdnTerminalException.class, () -> session.write(new byte[4])).getErrno());
        fake.resizeResult = -9; assertThrows(PdnTerminalException.class, () -> session.resize(24, 80));
        fake.states.add(new int[]{3, 0, 10}); assertThrows(PdnTerminalException.class, session::poll);
        fake.readResult = 0; fake.writeResult = 1; fake.resizeResult = 0;
        assertEquals(0, session.read(new byte[4])); assertEquals(1, session.write(new byte[4])); session.resize(80, 24);
        session.close();
    }
    @Test public void waitTimeoutAndTerminationReapAndRestoreInterruption() throws Exception {
        Fake fake = new Fake(); PdnTerminalSession session = session(fake);
        fake.states.add(new int[]{0,0,0}); assertNull(session.waitFor(0));
        assertThrows(IllegalArgumentException.class, () -> session.waitFor(-1));
        fake.states.add(new int[]{0,0,0}); fake.states.add(new int[]{1,7,0});
        session.terminate(); assertEquals(1, fake.signals); assertEquals(Integer.valueOf(7), session.poll().getExitCode());
        Thread.currentThread().interrupt(); session.close(); assertTrue(Thread.interrupted());
        assertEquals(1, fake.closes);
    }
    @Test public void forceFallbackAndConcurrentCloseAreSafe() throws Exception {
        Fake fake = new Fake() {
            volatile boolean killed;
            @Override public int[] poll(int pid) { return killed ? new int[]{2,9,0} : new int[]{0,0,0}; }
            @Override public int signal(int pid, int signal) { signals++; if (signal == 9) killed = true; return 0; }
        };
        PdnTerminalSession session = session(fake);
        Thread one = new Thread(() -> { try { session.close(); } catch (IOException failure) { throw new RuntimeException(failure); } });
        Thread two = new Thread(() -> { try { session.close(); } catch (IOException failure) { throw new RuntimeException(failure); } });
        one.start(); two.start(); one.join(3000); two.join(3000);
        assertFalse(one.isAlive()); assertFalse(two.isAlive()); assertEquals(1, fake.closes);
        assertEquals(Integer.valueOf(9), session.poll().getSignal());
    }
    @Test public void signalFailureStillClosesDescriptor() throws Exception {
        Fake fake = new Fake() {
            @Override public int[] poll(int pid) { return new int[]{0,0,0}; }
            @Override public int signal(int pid, int signal) { return -13; }
        };
        PdnTerminalSession session = session(fake);
        assertThrows(PdnTerminalException.class, session::close); assertEquals(1, fake.closes); assertTrue(session.isClosed());
    }
}
