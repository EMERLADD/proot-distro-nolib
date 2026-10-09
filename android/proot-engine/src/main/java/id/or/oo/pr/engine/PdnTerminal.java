package id.or.oo.pr.engine;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public final class PdnTerminal {
    private final PdnRuntime runtime;
    private final PdnTerminalSession.Bridge bridge;
    public PdnTerminal(PdnRuntime runtime) { this(runtime, PdnTerminalSession.NATIVE); }
    PdnTerminal(PdnRuntime runtime, PdnTerminalSession.Bridge bridge) {
        this.runtime = Objects.requireNonNull(runtime);
        this.bridge = Objects.requireNonNull(bridge);
    }
    public PdnTerminalSession start(ProcessBuilder original, int rows, int cols, PdnTerminalListener listener) throws IOException {
        Objects.requireNonNull(listener);
        ProcessBuilder builder = copy(original);
        PdnTerminalSession.dimensions(rows, cols);
        File file = null;
        RandomAccessFile events = null;
        PdnTerminalSession session = null;
        try {
            File cache = new File(runtime.environment().get("PROOT_TMP_DIR"));
            if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("Cannot prepare terminal cache");
            file = Files.createTempFile(cache.toPath(), "pdn-terminal-", ".jsonl",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).toFile();
            events = new RandomAccessFile(file, "r");
            String id = UUID.randomUUID().toString();
            builder.environment().put("PDN_EVENT_FILE", file.getAbsolutePath());
            builder.environment().put("PDN_OPERATION_ID", id);
            session = PdnTerminalSession.start(builder, rows, cols, bridge);
            PdnTerminalSession target = session;
            File channel = file;
            RandomAccessFile input = events;
            Thread worker = new Thread(() -> monitor(target, channel, input, id, listener), "pdn-terminal");
            worker.setDaemon(true);
            worker.start();
            return session;
        } catch (Throwable failure) {
            if (session != null) try { session.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            if (events != null) try { events.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            if (file != null) try { Files.deleteIfExists(file.toPath()); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            Exception structured = failure instanceof PdnTerminalException ? (PdnTerminalException) failure : new PdnHostException(
                    "host_terminal_start_failed", "Cannot start terminal", "Check native executable and writable cache paths", failure);
            listener.onFailure(structured);
            if (structured instanceof IOException) throw (IOException) structured;
            throw new IOException(structured);
        }
    }
    private static ProcessBuilder copy(ProcessBuilder original) {
        Objects.requireNonNull(original);
        if (original.redirectInput() != ProcessBuilder.Redirect.PIPE || original.redirectOutput() != ProcessBuilder.Redirect.PIPE
                || original.redirectError() != ProcessBuilder.Redirect.PIPE || original.redirectErrorStream())
            throw new IllegalArgumentException("Terminal streams must use PIPE redirects");
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(original.command()));
        builder.directory(original.directory());
        builder.environment().clear();
        builder.environment().putAll(original.environment());
        return builder;
    }
    private static void monitor(PdnTerminalSession session, File channel, RandomAccessFile input, String id, PdnTerminalListener listener) {
        Exception failure = null;
        PdnResult result = null;
        PdnOperations.Tail tail = new PdnOperations.Tail(id, input);
        PdnListener events = new PdnListener() { @Override public void onEvent(PdnEvent event) { listener.onEvent(event); } };
        try {
            byte[] buffer = new byte[8192];
            PdnTerminalStatus status;
            while (true) {
                tail.read(events);
                int count;
                synchronized (session) { count = session.isClosed() ? -1 : session.read(buffer); }
                if (count > 0) listener.onOutput(Arrays.copyOf(buffer, count));
                status = session.poll();
                if (status != null && count <= 0) {
                    synchronized (session) { count = session.isClosed() ? -1 : session.read(buffer); }
                    if (count <= 0) break;
                    listener.onOutput(Arrays.copyOf(buffer, count));
                }
                if (count <= 0) Thread.sleep(10);
            }
            tail.read(events);
            int exit = status.getExitCode() == null ? 128 + status.getSignal() : status.getExitCode();
            result = new PdnResult(id, exit, tail.finalEvent(exit));
        } catch (Throwable caught) {
            failure = caught instanceof Exception ? (Exception) caught : new PdnHostException("host_terminal_callback_failed",
                    "Terminal callback failed", "Check the terminal listener implementation", caught);
            if (caught instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            try { session.close(); } catch (IOException caught) { failure = combine(failure, caught); }
            try { input.close(); } catch (IOException caught) { failure = combine(failure, caught); }
            try { Files.deleteIfExists(channel.toPath()); } catch (IOException caught) { failure = combine(failure, caught); }
        }
        if (failure == null) listener.onComplete(result);
        else listener.onFailure(failure);
    }
    private static Exception combine(Exception primary, Exception cleanup) {
        if (primary == null) return new PdnHostException("host_terminal_cleanup_failed", "Cannot clean up terminal resources",
                "Check terminal process and cache permissions", cleanup);
        primary.addSuppressed(cleanup);
        return primary;
    }
}
