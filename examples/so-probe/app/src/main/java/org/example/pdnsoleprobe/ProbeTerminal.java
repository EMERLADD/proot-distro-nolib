package org.example.pdnsoleprobe;

import android.system.Os;
import android.system.OsConstants;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class ProbeTerminal implements AutoCloseable {
    public interface Output { void accept(String text); }
    private final NativePty.Session session;
    private final int pid;
    private final Thread reader;
    private final StringBuilder transcript = new StringBuilder();
    private volatile boolean ended;
    private final Object childLock = new Object();
    private final Thread watcher;
    private volatile boolean childExited;
    private int childStatus;
    private Exception waitFailure;

    public ProbeTerminal(ProbeHost host, NativeRuntime runtime, File rootfs, Output output) throws IOException {
        session = NativePty.start(runtime.processBuilder(runtime.loginArguments(rootfs)), 24, 80);
        if (session == null) throw new IOException("SO PTY could not start");
        pid = session.pid;
        if (pid <= 0) { session.close(); throw new IOException("Invalid PTY child PID"); }
        watcher = new Thread(() -> {
            while (!childExited) {
                synchronized (childLock) {
                    int status = NativePty.waitPid(pid);
                    if (status != NativePty.RUNNING) {
                        childStatus = status;
                        if (status == -1) waitFailure = new IOException("Cannot reap PTY child");
                        childExited = true;
                    }
                }
                if (!childExited) {
                    try { Thread.sleep(10); } catch (InterruptedException interrupted) { return; }
                }
            }
        }, "probe-pty-waiter");
        watcher.setDaemon(true);
        watcher.start();
        reader = new Thread(() -> {
            byte[] bytes = new byte[4096];
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE);
            ByteBuffer pending = ByteBuffer.allocate(8192);
            CharBuffer characters = CharBuffer.allocate(8192);
            while (!ended) {
                int count = session.read(bytes, 0, bytes.length);
                if (count < 0) break;
                if (count == 0) {
                    if (childExited) break;
                    try { Thread.sleep(5); } catch (InterruptedException interrupted) { break; }
                    continue;
                }
                pending.put(bytes, 0, count).flip();
                decoder.decode(pending, characters, false);
                pending.compact();
                characters.flip();
                String text = characters.toString();
                characters.clear();
                synchronized (transcript) {
                    transcript.append(text);
                    if (transcript.length() > 262144) transcript.delete(0, transcript.length() - 262144);
                    transcript.notifyAll();
                }
                output.accept(text);
            }
            ended = true;
            synchronized (transcript) { transcript.notifyAll(); }
        }, "probe-pty-reader");
        reader.setDaemon(true);
        reader.start();
    }

    public void send(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int offset = 0;
        while (offset < bytes.length) {
            byte[] rest = java.util.Arrays.copyOfRange(bytes, offset, bytes.length);
            int count = session.write(rest);
            if (count <= 0) throw new IOException("PTY write failed");
            offset += count;
        }
    }

    public void resize(int rows, int cols) throws IOException {
        if (rows < 1 || cols < 1 || session.resize(rows, cols) != 0) throw new IOException("PTY resize failed");
    }

    public String await(String marker, long timeoutMillis) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (transcript) {
            while (transcript.indexOf(marker) < 0) {
                if (ended) throw new IOException("PTY ended before output: " + transcript);
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new IOException("PTY output timed out: " + transcript);
                transcript.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
            }
            return transcript.toString();
        }
    }

    public boolean awaitExit(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        watcher.join(timeoutMillis);
        long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (left > 0) reader.join(left);
        synchronized (childLock) {
            return ended && childExited && waitFailure == null &&
                    childStatus == 0;
        }
    }

    private void signal(int signal) {
        synchronized (childLock) {
            if (!childExited && pid > 0) {
                try { Os.kill(pid, signal); } catch (Exception ignored) { }
            }
        }
    }
    @Override public void close() {
        signal(OsConstants.SIGTERM);
        try {
            watcher.join(1000);
            if (!childExited) { signal(OsConstants.SIGKILL); watcher.join(1000); }
        } catch (InterruptedException interrupted) {
            signal(OsConstants.SIGKILL);
            Thread.currentThread().interrupt();
        }
        ended = true;
        session.close();
    }
}
