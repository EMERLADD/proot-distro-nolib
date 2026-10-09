package org.example.pdnprobe;

import id.or.oo.pr.engine.PdnRuntime;
import id.or.oo.pr.engine.PdnResult;
import id.or.oo.pr.engine.PdnTerminal;
import id.or.oo.pr.engine.PdnTerminalListener;
import id.or.oo.pr.engine.PdnTerminalSession;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ProbeTerminal implements AutoCloseable {
    public interface Output { void accept(String text); }
    private final PdnTerminalSession session;
    private final StringBuilder transcript = new StringBuilder();
    private final CountDownLatch completed = new CountDownLatch(1);
    private volatile boolean ended;
    private volatile PdnResult result;
    private volatile Exception failure;

    public ProbeTerminal(ProbeHost host, PdnRuntime runtime, File rootfs, Output output) throws IOException {
        session = new PdnTerminal(runtime).start(runtime.login(rootfs), 24, 80, new PdnTerminalListener() {
            private final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE);
            private final ByteBuffer pending = ByteBuffer.allocate(16384);
            private final CharBuffer characters = CharBuffer.allocate(16384);
            @Override public void onOutput(byte[] data) {
                pending.put(data).flip();
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
            @Override public void onComplete(PdnResult value) { result = value; finish(); }
            @Override public void onFailure(Exception value) { failure = value; finish(); }
            private void finish() {
                ended = true;
                completed.countDown();
                synchronized (transcript) { transcript.notifyAll(); }
            }
        });
    }

    public void send(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        int offset = 0;
        while (offset < bytes.length) {
            int count = session.write(bytes, offset, bytes.length - offset);
            offset += count;
            if (count == 0) {
                if (System.nanoTime() >= deadline) throw new IOException("PTY write timed out");
                try { Thread.sleep(5); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("PTY write interrupted", interrupted);
                }
            }
        }
    }

    public void resize(int rows, int cols) throws IOException { session.resize(rows, cols); }

    public String await(String marker, long timeoutMillis) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (transcript) {
            while (transcript.indexOf(marker) < 0) {
                if (ended) throw new IOException("PTY ended before output: " + transcript, failure);
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new IOException("PTY output timed out: " + transcript);
                transcript.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
            }
            return transcript.toString();
        }
    }

    public boolean awaitExit(long timeoutMillis) throws InterruptedException {
        return completed.await(timeoutMillis, TimeUnit.MILLISECONDS) && failure == null && result != null && result.isSuccess();
    }

    @Override public void close() throws IOException { session.close(); }
}
