package id.or.oo.pr.engine;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public final class PdnTerminalSession implements AutoCloseable {
    interface Bridge {
        int[] spawn(String command, String[] args, String[] environment, int rows, int cols, String directory);
        int read(int fd, byte[] data, int offset, int length);
        int write(int fd, byte[] data, int offset, int length);
        int resize(int fd, int rows, int cols);
        int[] poll(int pid);
        int signal(int pid, int signal);
        void close(int fd);
    }
    static final Bridge NATIVE = new Bridge() {
        public int[] spawn(String cmd, String[] args, String[] env, int rows, int cols, String directory) { return PtyNative.spawn(cmd, args, env, rows, cols, directory); }
        public int read(int fd, byte[] data, int offset, int length) { return PtyNative.readSession(fd, data, offset, length); }
        public int write(int fd, byte[] data, int offset, int length) { return PtyNative.writeSession(fd, data, offset, length); }
        public int resize(int fd, int rows, int cols) { return PtyNative.resizeSession(fd, rows, cols); }
        public int[] poll(int pid) { return PtyNative.poll(pid); }
        public int signal(int pid, int signal) { return PtyNative.signal(pid, signal); }
        public void close(int fd) { PtyNative.INSTANCE.close(fd); }
    };
    private final Bridge bridge;
    private final int fd;
    private final int pid;
    private boolean closed;
    private PdnTerminalStatus status;

    private PdnTerminalSession(Bridge bridge, int fd, int pid) { this.bridge = bridge; this.fd = fd; this.pid = pid; }
    public static PdnTerminalSession start(ProcessBuilder builder, int rows, int cols) throws IOException {
        return start(builder, rows, cols, NATIVE);
    }
    static PdnTerminalSession start(ProcessBuilder builder, int rows, int cols, Bridge bridge) throws IOException {
        Objects.requireNonNull(builder); Objects.requireNonNull(bridge); dimensions(rows, cols);
        if (builder.command().isEmpty()) throw new IllegalArgumentException("An executable is required");
        for (String value : builder.command()) text(value);
        String[] environment = new String[builder.environment().size() * 2];
        int index = 0;
        for (Map.Entry<String, String> entry : builder.environment().entrySet()) {
            text(entry.getKey()); text(entry.getValue());
            if (entry.getKey().isEmpty() || entry.getKey().contains("=")) throw new IllegalArgumentException("Invalid environment key");
            environment[index++] = entry.getKey(); environment[index++] = entry.getValue();
        }
        String directory = builder.directory() == null ? null : builder.directory().getAbsolutePath();
        if (directory != null) text(directory);
        int[] values = bridge.spawn(builder.command().get(0), builder.command().toArray(new String[0]), environment, rows, cols, directory);
        if (values[2] != 0 || values[0] < 0 || values[1] <= 0) throw new PdnTerminalException("spawn", values[2]);
        return new PdnTerminalSession(bridge, values[0], values[1]);
    }
    static void text(String value) {
        Objects.requireNonNull(value);
        if (value.indexOf(0) >= 0) throw new IllegalArgumentException("Terminal strings cannot contain NUL");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw new IllegalArgumentException("Invalid Unicode");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("Invalid Unicode");
        }
    }
    static void dimensions(int rows, int cols) {
        if (rows < 1 || cols < 1 || rows > 65535 || cols > 65535) throw new IllegalArgumentException("Terminal dimensions must be 1..65535");
    }
    public int getPid() { return pid; }
    public int getMasterFd() { return fd; }
    public synchronized boolean isClosed() { return closed; }
    private void open() throws IOException { if (closed) throw new PdnTerminalException("closed", 9); }
    private static void bounds(byte[] data, int offset, int length) {
        Objects.requireNonNull(data);
        if (offset < 0 || length < 0 || offset > data.length - length) throw new IndexOutOfBoundsException();
    }
    public synchronized int read(byte[] data, int offset, int length) throws IOException {
        bounds(data, offset, length); open();
        int count = bridge.read(fd, data, offset, length);
        if (count == -5) return -1;
        if (count < 0) throw new PdnTerminalException("read", -count);
        return count;
    }
    public int read(byte[] data) throws IOException { return read(data, 0, data.length); }
    public synchronized int write(byte[] data, int offset, int length) throws IOException {
        bounds(data, offset, length); open();
        int count = bridge.write(fd, data, offset, length);
        if (count < 0) throw new PdnTerminalException("write", -count);
        return count;
    }
    public int write(byte[] data) throws IOException { return write(data, 0, data.length); }
    public synchronized void resize(int rows, int cols) throws IOException {
        dimensions(rows, cols); open();
        int result = bridge.resize(fd, rows, cols);
        if (result < 0) throw new PdnTerminalException("resize", -result);
    }
    public synchronized PdnTerminalStatus poll() throws IOException {
        if (status != null) return status;
        int[] result = bridge.poll(pid);
        if (result[0] == 3) throw new PdnTerminalException("wait", result[2]);
        if (result[0] == 1) status = PdnTerminalStatus.exited(result[1]);
        if (result[0] == 2) status = PdnTerminalStatus.signalled(result[1]);
        return status;
    }
    public PdnTerminalStatus waitFor() throws IOException, InterruptedException {
        PdnTerminalStatus result;
        while ((result = poll()) == null) Thread.sleep(10);
        return result;
    }
    public PdnTerminalStatus waitFor(long timeoutMillis) throws IOException, InterruptedException {
        if (timeoutMillis < 0) throw new IllegalArgumentException("Timeout must be nonnegative");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        PdnTerminalStatus result;
        do { if ((result = poll()) != null) return result; if (System.nanoTime() >= deadline) return null; Thread.sleep(10); } while (true);
    }
    private synchronized void signal(int signal) throws IOException {
        if (poll() != null) return;
        int result = bridge.signal(pid, signal);
        if (result < 0 && result != -3) throw new PdnTerminalException("signal", -result);
    }
    public void terminate() throws IOException, InterruptedException {
        signal(15);
        if (waitFor(500) == null) { signal(9); waitFor(); }
    }
    @Override public void close() throws IOException {
        synchronized (this) { if (closed) return; }
        boolean interrupted = Thread.interrupted();
        IOException failure = null;
        try {
            for (;;) {
                try { terminate(); break; }
                catch (InterruptedException caught) { interrupted = true; }
            }
        } catch (IOException caught) { failure = caught; }
        synchronized (this) {
            if (!closed) { closed = true; bridge.close(fd); }
        }
        if (interrupted) Thread.currentThread().interrupt();
        if (failure != null) throw failure;
    }
}
