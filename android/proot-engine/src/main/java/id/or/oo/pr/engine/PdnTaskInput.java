package id.or.oo.pr.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

final class PdnTaskInput {
    static final int MAX_WRITE = 64 * 1024;
    private static final int MAX_BUFFER = 256 * 1024;
    private final ArrayDeque<byte[]> queue = new ArrayDeque<>();
    private int buffered;
    private boolean eof, ended;
    private Process process;
    private Thread writer;
    private PdnHostException failure;

    synchronized void write(byte[] bytes) throws PdnHostException {
        if (!isOpen()) throw closed();
        if (bytes.length > MAX_WRITE || bytes.length > MAX_BUFFER - buffered)
            throw new PdnHostException("host_input_backpressure", "Task input buffer is full or write exceeds 64 KiB",
                    "Split input into smaller chunks and retry when the process has consumed buffered input", null);
        if (bytes.length == 0) return;
        queue.add(Arrays.copyOf(bytes, bytes.length));
        buffered += bytes.length;
        notifyAll();
    }
    synchronized boolean isOpen() { return !eof && !ended && failure == null && (process == null || process.isAlive()); }
    synchronized void closeInput() { eof = true; notifyAll(); }
    synchronized void end() {
        ended = true;
        queue.clear();
        buffered = 0;
        notifyAll();
        if (writer != null) writer.interrupt();
    }
    synchronized void check() throws PdnHostException { if (failure != null) throw failure; }
    synchronized void attach(Process process) {
        this.process = process;
        writer = new Thread(() -> send(process.getOutputStream()), "pdn-stdin");
        writer.setDaemon(true);
        writer.start();
    }
    private void send(OutputStream output) {
        try {
            while (true) {
                byte[] bytes;
                synchronized (this) {
                    while (queue.isEmpty() && !eof && !ended) wait();
                    if (ended) return;
                    bytes = queue.poll();
                    if (bytes == null) break;
                }
                output.write(bytes);
                output.flush();
                synchronized (this) { buffered = Math.max(0, buffered - bytes.length); }
            }
            output.close();
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException caught) {
            synchronized (this) {
                if (!ended) failure = new PdnHostException("host_input_failed", "Cannot write or close task input",
                        "Check whether the process still accepts input and start a new task if necessary", caught);
                eof = true;
                queue.clear();
                buffered = 0;
            }
        }
    }
    void join() throws PdnHostException {
        Thread thread;
        synchronized (this) { thread = writer; }
        if (thread == null) return;
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.isAlive() && System.nanoTime() < deadline) {
            try { thread.join(50); }
            catch (InterruptedException caught) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (thread.isAlive()) throw new PdnHostException("host_cleanup_failed", "Task input writer did not stop",
                "Check for blocked input or surviving child processes", null);
    }
    static PdnHostException closed() {
        return new PdnHostException("host_input_closed", "Task input is closed or the process has exited",
                "Start a new interactive task to send more input", null);
    }
}
