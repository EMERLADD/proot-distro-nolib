package org.example.pdnsoleprobe;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class NativeRuntime {
    private final ProbeHost host;
    private final File rootfs;
    private final File project;
    public NativeRuntime(ProbeHost host, File rootfs, File project) { this.host = host; this.rootfs = rootfs; this.project = project; }
    public File getRootfsDir() { return rootfs; }
    public File getProjectDir() { return project; }
    public File getExecutable() { return new File(host.getNativeLibDir(), "libpdn.so"); }
    public File getLoader() { return new File(host.getNativeLibDir(), "libproot-loader.so"); }
    public Map<String,String> environment() {
        Map<String,String> e = new LinkedHashMap<>();
        e.put("APP_PREFIX", host.getPrefixDir().getAbsolutePath()); e.put("APP_HOME", host.getHomeDir().getAbsolutePath());
        e.put("APP_PACKAGE", host.getPackageName()); e.put("HOME", host.getHomeDir().getAbsolutePath());
        e.put("PATH", host.getPrefixDir() + "/bin:/system/bin:/system/xbin");
        e.put("PDN_ROOTFS_DIR", rootfs.getAbsolutePath()); e.put("PROOT_LOADER", getLoader().getAbsolutePath());
        e.put("PROOT_NO_SECCOMP", "1"); e.put("PROOT_TMP_DIR", host.getCacheDir().getAbsolutePath());
        e.put("TMPDIR", host.getCacheDir().getAbsolutePath()); e.put("TERM", "xterm-256color"); e.put("LANG", "en_US.UTF-8");
        return e;
    }
    public void prepare() throws IOException {
        for (File f : Arrays.asList(getExecutable(), getLoader())) if (!f.canExecute()) throw new IOException("native executable: " + f);
        for (File f : Arrays.asList(host.getPrefixDir(), host.getHomeDir(), host.getCacheDir(), rootfs, project))
            if (!f.isDirectory() && !f.mkdirs()) throw new IOException("directory: " + f);
    }
    public List<String> command(List<String> args) {
        List<String> a = new ArrayList<>(); a.add(getExecutable().getAbsolutePath()); a.addAll(args); return a;
    }
    public ProcessBuilder processBuilder(List<String> args) throws IOException {
        prepare(); ProcessBuilder b = new ProcessBuilder(command(args)); b.directory(host.getHomeDir());
        b.environment().clear(); b.environment().putAll(environment()); return b;
    }
    public List<String> loginArguments(File root) { return Arrays.asList("login", "--rootfs", root.getAbsolutePath(), "--user", "root", "--bind", project + ":/workspace", "--work-dir", "/workspace"); }
    public ProcessBuilder version() throws IOException { return processBuilder(Arrays.asList("version")); }
    public ProcessBuilder install(String name, String mirror) throws IOException { return install(name, mirror, null); }
    public ProcessBuilder install(String name, String mirror, File archive) throws IOException {
        return processBuilder(Arrays.asList("install", name, archive == null ? "--mirror" : "--archive", archive == null ? mirror : archive.getAbsolutePath()));
    }
    public ProcessBuilder exec(String name, List<String> cmd) throws IOException { return exec(new File(rootfs, name), cmd); }
    public ProcessBuilder exec(File root, List<String> cmd) throws IOException {
        List<String> a = new ArrayList<>(loginArguments(root)); a.set(0, "exec"); a.add("--"); a.addAll(cmd); return processBuilder(a);
    }
    public ProcessBuilder login(File root) throws IOException { return processBuilder(loginArguments(root)); }
}
