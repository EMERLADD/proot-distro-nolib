package org.example.pdnsoleprobe;

import android.system.Os;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

final class NativeOperations {
    NativeOperations(NativeRuntime runtime) { }
    NativeResult run(ProcessBuilder builder, NativeListener listener) throws Exception {
        File events = File.createTempFile("events-", ".jsonl", new File(builder.environment().get("TMPDIR")));
        Os.chmod(events.getAbsolutePath(), 0600);
        String id = UUID.randomUUID().toString();
        builder.environment().put("PDN_EVENT_FILE", events.getAbsolutePath());
        builder.environment().put("PDN_OPERATION_ID", id);
        java.lang.Process process = builder.start();
        process.getOutputStream().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        List<Exception> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread stdout = drain(process.getInputStream(), out, failures), stderr = drain(process.getErrorStream(), err, failures);
        NativeEvent result = null;
        int delivered = 0;
        boolean exitedSeen = false;
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
        try {
            while (true) {
                if (events.length() > 4194304) throw new AssertionError("native event log exceeds acceptance limit");
                List<String> lines = Files.readAllLines(events.toPath(), StandardCharsets.UTF_8);
                for (; delivered < lines.size(); delivered++) {
                    String line = lines.get(delivered);
                    JSONObject data;
                    try { data = new JSONObject(line); } catch (Exception partial) { break; }
                    if (result != null) throw new AssertionError("event after native result: " + line);
                    NativeEvent e = new NativeEvent(data);
                    if (data.optInt("version") != 1 || !id.equals(e.getOperationId()) || e.getSequence() != delivered + 1) throw new AssertionError("event protocol: " + line);
                    listener.onEvent(e);
                    if ("result".equals(e.getType())) result = e;
                }
                if (!process.isAlive()) {
                    if (!exitedSeen) { exitedSeen = true; continue; }
                    if (result == null) throw new AssertionError("missing native result: " + lines);
                    if (delivered != lines.size()) throw new AssertionError("invalid final native event: " + lines.get(delivered));
                    byte[] finalBytes = Files.readAllBytes(events.toPath());
                    if (finalBytes.length == 0 || finalBytes[finalBytes.length - 1] != '\n') throw new AssertionError("truncated native event tail");
                    break;
                }
                if (System.nanoTime() > deadline) throw new AssertionError("native operation timed out; events=" + lines);
                Thread.sleep(10);
            }
            int status = process.waitFor(); stdout.join(5000); stderr.join(5000);
            if (stdout.isAlive() || stderr.isAlive()) throw new AssertionError("native streams did not close");
            if (!failures.isEmpty()) throw failures.get(0);
            listener.onStdout(out.toByteArray()); listener.onStderr(err.toByteArray());
            if (result.data.optInt("exit_code", -1) != status) throw new AssertionError("event exit mismatch");
            NativeResult value = new NativeResult(result, status); listener.onComplete(value); return value;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close(); process.getErrorStream().close();
            stdout.join(1000); stderr.join(1000);
            Files.write(new File(events.getParentFile(), "last-stderr.txt").toPath(), err.toByteArray());
        }
    }
    private Thread drain(InputStream in, ByteArrayOutputStream out, List<Exception> failures) {
        Thread t = new Thread(() -> { try (InputStream stream = in) { byte[] bytes = new byte[8192]; int n; while ((n = stream.read(bytes)) >= 0) out.write(bytes, 0, n); } catch (Exception e) { failures.add(e); } });
        t.start(); return t;
    }
}
