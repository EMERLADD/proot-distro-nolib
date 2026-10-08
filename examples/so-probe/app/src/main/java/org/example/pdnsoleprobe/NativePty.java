package org.example.pdnsoleprobe;

import java.io.IOException;
import java.util.Map;

final class NativePty {
    static { System.loadLibrary("probepty"); }
    static native int[] spawn(String[] args, String[] environment, String cwd, int rows, int cols);
    static native int read(int fd, byte[] data, int offset, int size);
    static native int write(int fd, byte[] data);
    static native int resize(int fd, int rows, int cols);
    static native int waitPid(int pid);
    static native void close(int fd);
    static native int dumpCoverage(String path);
    static Session start(ProcessBuilder builder, int rows, int cols) throws IOException {
        String[] env = new String[builder.environment().size()]; int i = 0;
        for (Map.Entry<String,String> entry : builder.environment().entrySet()) env[i++] = entry.getKey() + "=" + entry.getValue();
        int[] child = spawn(builder.command().toArray(new String[0]), env, builder.directory().getAbsolutePath(), rows, cols);
        if (child == null) throw new IOException("native PTY spawn failed");
        return new Session(child[0], child[1]);
    }
    static final class Session implements AutoCloseable {
        final int fd, pid;
        Session(int fd, int pid) { this.fd = fd; this.pid = pid; }
        int read(byte[] data, int offset, int size) { return NativePty.read(fd, data, offset, size); }
        int write(byte[] data) { return NativePty.write(fd, data); }
        int resize(int rows, int cols) { return NativePty.resize(fd, rows, cols); }
        public void close() { NativePty.close(fd); }
    }
}
