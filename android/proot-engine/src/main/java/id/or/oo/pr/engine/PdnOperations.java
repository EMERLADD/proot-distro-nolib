package id.or.oo.pr.engine;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.json.JSONException;
import org.json.JSONTokener;

public final class PdnOperations {
    private static final int MAX_LINE = 16384;
    private static final int MAX_RECORDS = 10000;
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private final PdnRuntime runtime;
    public PdnOperations(PdnRuntime runtime) { this.runtime = Objects.requireNonNull(runtime); }

    public PdnResult run(ProcessBuilder original, PdnListener listener) throws IOException, InterruptedException {
        Objects.requireNonNull(original);
        Objects.requireNonNull(listener);
        if (original.redirectErrorStream() || original.redirectInput() != ProcessBuilder.Redirect.PIPE
                || original.redirectOutput() != ProcessBuilder.Redirect.PIPE
                || original.redirectError() != ProcessBuilder.Redirect.PIPE) {
            throw new IllegalArgumentException("Operation streams must use separate PIPE redirects");
        }
        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(original.command()));
        builder.directory(original.directory());
        builder.environment().clear();
        builder.environment().putAll(original.environment());
        File cache = new File(runtime.environment().get("PROOT_TMP_DIR"));
        if (!cache.isDirectory() && !cache.mkdirs()) throw new IOException("Cannot prepare operation cache");
        File file = Files.createTempFile(cache.toPath(), "pdn-events-", ".jsonl",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).toFile();
        String id = UUID.randomUUID().toString();
        builder.environment().put("PDN_EVENT_FILE", file.getAbsolutePath());
        builder.environment().put("PDN_OPERATION_ID", id);
        Process process = null;
        Thread stdout = null;
        Thread stderr = null;
        BlockingQueue<Chunk> queue = new ArrayBlockingQueue<>(32);
        Throwable primaryFailure = null;
        try (RandomAccessFile events = new RandomAccessFile(file, "r")) {
            process = builder.start();
            process.getOutputStream().close();
            stdout = reader(process, process.getInputStream(), false, queue);
            stderr = reader(process, process.getErrorStream(), true, queue);
            Tail tail = new Tail(id, events);
            int completedStreams = 0;
            long drainDeadline = 0;
            while (process.isAlive() || completedStreams < 2 || !queue.isEmpty()) {
                if (!process.isAlive()) {
                    if (drainDeadline == 0) drainDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    if (System.nanoTime() > drainDeadline) throw new IOException("Operation streams did not finish after process exit");
                }
                tail.read(listener);
                Chunk chunk = queue.poll(10, TimeUnit.MILLISECONDS);
                if (chunk != null) {
                    if (chunk.failure != null) throw chunk.failure;
                    if (chunk.bytes == null) completedStreams++;
                    else if (chunk.stderr) listener.onStderr(chunk.bytes);
                    else listener.onStdout(chunk.bytes);
                }
            }
            int status = process.waitFor();
            tail.read(listener);
            PdnResult result = new PdnResult(id, status, tail.finalEvent(status));
            listener.onComplete(result);
            return result;
        } catch (IOException | InterruptedException | RuntimeException | Error failure) {
            primaryFailure = failure;
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw failure;
        } finally {
            IOException cleanupFailure = cleanup(process, stdout, stderr);
            try { Files.deleteIfExists(file.toPath()); }
            catch (IOException failure) {
                if (cleanupFailure == null) cleanupFailure = failure;
                else cleanupFailure.addSuppressed(failure);
            }
            if (cleanupFailure != null) {
                if (primaryFailure == null) throw cleanupFailure;
                primaryFailure.addSuppressed(cleanupFailure);
            }
        }
    }

    private static Thread reader(Process process, InputStream input, boolean stderr, BlockingQueue<Chunk> queue) {
        Thread thread = new Thread(() -> {
            try {
                byte[] buffer = new byte[8192];
                while (true) {
                    int available = input.available();
                    if (available == 0) {
                        if (!process.isAlive()) break;
                        Thread.sleep(5);
                        continue;
                    }
                    int size = input.read(buffer, 0, Math.min(buffer.length, available));
                    if (size == -1) break;
                    queue.put(new Chunk(stderr, Arrays.copyOf(buffer, size), null));
                }
                queue.put(new Chunk(stderr, null, null));
            } catch (IOException failure) {
                try { queue.put(new Chunk(stderr, null, new IOException("Cannot read operation stream", failure))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, stderr ? "pdn-stderr" : "pdn-stdout");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static IOException cleanup(Process process, Thread stdout, Thread stderr) {
        boolean interrupted = Thread.interrupted();
        IOException failure = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        if (process != null) {
            if (process.isAlive()) process.destroyForcibly();
            while (process.isAlive() && System.nanoTime() < deadline) {
                try { process.waitFor(50, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (process.isAlive()) failure = new IOException("Operation process did not stop during cleanup");
        }
        for (Thread thread : new Thread[]{stdout, stderr}) {
            if (thread != null) thread.interrupt();
        }
        Thread closer = null;
        if (process != null) {
            Process target = process;
            closer = new Thread(() -> {
                try { target.getInputStream().close(); } catch (IOException ignored) { }
                try { target.getErrorStream().close(); } catch (IOException ignored) { }
                try { target.getOutputStream().close(); } catch (IOException ignored) { }
            }, "pdn-stream-cleanup");
            closer.setDaemon(true);
            closer.start();
        }
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        for (Thread thread : new Thread[]{stdout, stderr, closer}) {
            if (thread == null) continue;
            while (thread.isAlive() && System.nanoTime() < deadline) {
                try { thread.join(50); }
                catch (InterruptedException ignored) { interrupted = true; }
            }
            if (thread.isAlive()) failure = new IOException("Operation streams did not stop during cleanup");
        }
        if (interrupted) Thread.currentThread().interrupt();
        return failure;
    }

    private static final class Chunk {
        final boolean stderr;
        final byte[] bytes;
        final IOException failure;
        Chunk(boolean stderr, byte[] bytes, IOException failure) {
            this.stderr = stderr;
            this.bytes = bytes;
            this.failure = failure;
        }
    }

    private static final class Tail {
        final String id;
        final RandomAccessFile file;
        final ByteArrayOutputStream line = new ByteArrayOutputStream();
        long sequence;
        String operation;
        PdnEvent result;
        boolean invalid;
        Tail(String id, RandomAccessFile file) { this.id = id; this.file = file; }
        void read(PdnListener listener) throws IOException {
            if (invalid) return;
            if (file.length() < file.getFilePointer() || file.length() > MAX_BYTES) { invalid = true; return; }
            byte[] buffer = new byte[4096];
            long remaining = file.length() - file.getFilePointer();
            while (remaining > 0) {
                int size = file.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (size == -1) { invalid = true; return; }
                remaining -= size;
                for (int index = 0; index < size; index++) {
                    int value = buffer[index] & 255;
                    if (value == '\n') {
                        PdnEvent event;
                        try { event = parse(); }
                        catch (Exception failure) { invalid = true; return; }
                        line.reset();
                        listener.onEvent(event);
                    } else {
                        if (line.size() >= MAX_LINE) { invalid = true; return; }
                        line.write(value);
                    }
                }
            }
        }

        PdnEvent parse() throws CharacterCodingException, JSONException {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(line.toByteArray())).toString();
            JSONTokener parser = new JSONTokener(json);
            JSONObject data = new JSONObject(parser);
            if (parser.nextClean() != 0) fail();
            if (integer(data, "version") != 1 || !id.equals(string(data, "operation_id"))) fail();
            long next = integer(data, "sequence");
            if (next != sequence + 1 || next > MAX_RECORDS || result != null) fail();
            String op = string(data, "operation");
            if (op.isEmpty() || (operation != null && !operation.equals(op))) fail();
            String type = string(data, "type");
            if (!("started".equals(type) || "stage".equals(type) || "progress".equals(type)
                    || "error".equals(type) || "result".equals(type))) fail();
            if ((sequence == 0) != "started".equals(type)) fail();
            for (String key : new String[]{"stage", "code", "message", "suggestion", "outcome"}) {
                if (data.has(key)) string(data, key);
            }
            for (String key : new String[]{"current", "total"}) {
                if (data.has(key) && integer(data, key) < -1) fail();
            }
            for (String key : new String[]{"percent", "exit_code", "guest_exit_code", "guest_signal", "signal"}) {
                if (data.has(key)) {
                    long number = integer(data, key);
                    if (number < 0 || number > ("percent".equals(key) ? 100 : 255)) fail();
                }
            }
            if ("stage".equals(type) && string(data, "stage").isEmpty()) fail();
            if ("progress".equals(type)) {
                if (integer(data, "current") < 0 || integer(data, "total") < -1) fail();
                if (data.has("percent") && integer(data, "total") <= 0) fail();
            }
            if ("error".equals(type)) {
                if (string(data, "code").isEmpty() || string(data, "message").isEmpty()
                        || string(data, "suggestion").isEmpty()) fail();
            }
            if (data.has("signal") && integer(data, "signal") == 0) fail();
            PdnEvent event = new PdnEvent(data);
            if ("result".equals(type)) {
                String outcome = string(data, "outcome");
                long status = integer(data, "exit_code");
                if (status < 0 || status > 255 || !("success".equals(outcome) || "manager_error".equals(outcome)
                        || "guest_exit".equals(outcome) || "cancelled".equals(outcome))) fail();
                if ("success".equals(outcome) && (status != 0 || (event.getGuestExitCode() != null
                        && event.getGuestExitCode() != 0) || event.getGuestSignal() != null)) fail();
                if (("manager_error".equals(outcome) || "cancelled".equals(outcome)) && status == 0) fail();
                if (event.getGuestSignal() != null && event.getGuestSignal() == 0) fail();
                if (event.getGuestExitCode() != null && event.getGuestSignal() != null) fail();
                result = event;
            }
            sequence = next;
            operation = op;
            return event;
        }
        PdnEvent finalEvent(int status) {
            return invalid || line.size() != 0 || result == null || result.getExitCode() != status ? null : result;
        }
        static String string(JSONObject data, String key) {
            Object value = data.opt(key);
            if (!(value instanceof String)) fail();
            return (String) value;
        }
        static long integer(JSONObject data, String key) {
            Object value = data.opt(key);
            if (!(value instanceof Integer || value instanceof Long)) fail();
            return ((Number) value).longValue();
        }
        static void fail() { throw new IllegalArgumentException("Invalid operation event"); }
    }
}
