package org.example.pdnsoleprobe;


import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ProbePathChecks {
    private interface Check { String run() throws Exception; }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void write(File file, String text) throws Exception {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
    private static boolean exists(File file) {
        return Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS);
    }
    private static void check(JSONArray checks, ProbeSuite.Log log, String name, Check action) throws Exception {
        JSONObject entry = new JSONObject().put("name", name);
        try {
            String details = action.run();
            entry.put("passed", true).put("details", details);
            log.accept("PASS " + name + ": " + details);
        } catch (Exception | AssertionError failure) {
            entry.put("passed", false).put("details", failure.toString());
            log.accept("FAIL " + name + ": " + failure);
        }
        checks.put(entry);
    }
    private static File root(File scope, File source) throws Exception {
        File root = new File(scope, "root-a");
        for (String directory : Arrays.asList("bin", "lib", "etc", "root", "tmp", "usr"))
            Files.createDirectories(new File(root, directory).toPath());
        for (String path : Arrays.asList("bin/busybox", "lib/ld-musl-aarch64.so.1")) {
            File destination = new File(root, path);
            Files.copy(new File(source, path).toPath(), destination.toPath());
            require(destination.setExecutable(true, false), "fixture executable " + path);
        }
        Files.copy(new File(root, "bin/busybox").toPath(), new File(root, "bin/sh").toPath());
        require(new File(root, "bin/sh").setExecutable(true, false), "fixture shell executable");
        for (String command : Arrays.asList("cat", "readlink"))
            Files.createSymbolicLink(new File(root, "bin/" + command).toPath(), new File("busybox").toPath());
        write(new File(root, "etc/passwd"), "root:x:0:0:root:/root:/bin/sh\n");
        return root;
    }
    private static void guest(ProbeSuite suite, NativeRuntime runtime, File root, String script, String... bindings) throws Exception {
        List<String> args = new ArrayList<>(Arrays.asList("exec", "--rootfs", root.getAbsolutePath(), "--work-dir", "/"));
        for (String binding : bindings) args.addAll(Arrays.asList("--bind", binding));
        args.addAll(Arrays.asList("--", "/bin/sh", "-c", "set -e; cat /usr/marker >/dev/null; " + script + "; printf 'PDN_PATH_OK\\n'"));
        ProbeSuite.Capture capture = suite.run(runtime.processBuilder(args));
        require(capture.result.isSuccess(), "guest failed: " + capture.stderr() + " " + capture.result.getMessage());
        require(capture.stdout().equals("PDN_PATH_OK\n"), "guest success marker: " + capture.stdout());
    }
    public static void verify(JSONArray checks, ProbeSuite suite, NativeRuntime runtime, ProbeSuite.Log log) throws Exception {
        for (String name : Arrays.asList("guest_usr_mapping", "nested_bind_parent_first", "nested_bind_child_first",
                "bind_component_boundary", "symlink_absolute", "symlink_relative", "cross_rootfs_bound_symlink",
                "cross_rootfs_unbound_symlink", "cross_rootfs_host_absolute_symlink", "dangling_symlink_creation",
                "symlink_loop", "missing_read", "missing_leaf_creation", "missing_parent_creation")) {
            check(checks, log, name, () -> {
                File scope = new File(runtime.getRootfsDir().getParentFile(), "path-fixtures/" + name);
                File root = root(scope, suite.getRootfs());
                File host = new File(scope, "host-view");
                write(new File(host, "usr/marker"), "host");
                write(new File(root, "usr/marker"), "guest");
                String hostBinding = host.getAbsolutePath() + ":/pdn-host-view";
                if (name.equals("guest_usr_mapping")) {
                    guest(suite, runtime, root, "test \"$(cat /usr/marker)\" = guest; test \"$(cat /pdn-host-view/usr/marker)\" = host; printf changed > /usr/marker", hostBinding);
                    require(read(new File(root, "usr/marker")).equals("changed"), "guest /usr write mapping");
                } else if (name.startsWith("nested_bind_") || name.equals("bind_component_boundary")) {
                    File parent = new File(scope, "parent"), child = new File(scope, "child");
                    write(new File(parent, "inner/marker"), "parent-inner");
                    write(new File(parent, "innerish/marker"), "parent-innerish");
                    write(new File(parent, "marker"), "parent");
                    write(new File(child, "marker"), "child");
                    String parentBinding = parent.getAbsolutePath() + ":/pdn-path";
                    String childBinding = child.getAbsolutePath() + ":/pdn-path/inner";
                    boolean reversed = name.equals("nested_bind_child_first");
                    guest(suite, runtime, root,
                            "test \"$(cat /pdn-path/marker)\" = parent; test \"$(cat /pdn-path/inner/marker)\" = child; test \"$(cat /pdn-path/innerish/marker)\" = parent-innerish; printf parent-write > /pdn-path/write; printf child-write > /pdn-path/inner/write; printf sibling-write > /pdn-path/innerish/write",
                            reversed ? childBinding : parentBinding, reversed ? parentBinding : childBinding);
                    require(read(new File(parent, "write")).equals("parent-write"), "parent bind write");
                    require(read(new File(child, "write")).equals("child-write"), "child bind write");
                    require(read(new File(parent, "innerish/write")).equals("sibling-write"), "component boundary write");
                    require(read(new File(parent, "inner/marker")).equals("parent-inner") && !exists(new File(parent, "inner/write")), "overridden parent child unchanged");
                    require(!exists(new File(child, "innerish/write")) && !exists(new File(root, "pdn-path")), "no unintended bind write");
                } else if (name.equals("symlink_absolute") || name.equals("symlink_relative")) {
                    write(new File(root, "usr/target"), "guest-target");
                    File target = new File(name.equals("symlink_absolute") ? "/usr/target" : "target");
                    Files.createSymbolicLink(new File(root, "usr/link").toPath(), target.toPath());
                    guest(suite, runtime, root, "test \"$(cat /usr/link)\" = guest-target; printf link-write > /usr/link", hostBinding);
                    require(read(new File(root, "usr/target")).equals("link-write"), "symlink write mapping");
                    require(Files.readSymbolicLink(new File(root, "usr/link").toPath()).equals(target.toPath()), "symlink target preserved");
                    require(!exists(new File(host, "usr/target")), "no host symlink target");
                } else if (name.startsWith("cross_rootfs_")) {
                    File other = new File(scope, "root-b");
                    write(new File(other, "target"), "other-root");
                    write(new File(other, "usr/marker"), "other-usr");
                    File linkTarget = name.equals("cross_rootfs_host_absolute_symlink") ? new File(other, "target") : new File("/pdn-other/target");
                    Files.createSymbolicLink(new File(root, "usr/cross").toPath(), linkTarget.toPath());
                    if (name.equals("cross_rootfs_bound_symlink")) {
                        guest(suite, runtime, root, "test \"$(cat /usr/cross)\" = other-root; printf cross-write > /usr/cross", other.getAbsolutePath() + ":/pdn-other");
                        require(read(new File(other, "target")).equals("cross-write"), "bound other root write");
                    } else {
                        guest(suite, runtime, root, "if cat /usr/cross >/dev/null 2>&1; then exit 71; fi; test ! -e /usr/cross; test -L /usr/cross");
                        require(read(new File(other, "target")).equals("other-root"), "unbound other root unchanged");
                    }
                    require(read(new File(other, "usr/marker")).equals("other-usr") && !exists(new File(root, "pdn-other")), "other root boundary");
                    require(Files.readSymbolicLink(new File(root, "usr/cross").toPath()).equals(linkTarget.toPath()), "cross-root symlink preserved");
                } else if (name.equals("dangling_symlink_creation")) {
                    Files.createSymbolicLink(new File(root, "usr/dangling").toPath(), new File("/usr/new-target").toPath());
                    guest(suite, runtime, root, "if cat /usr/dangling >/dev/null 2>&1; then exit 75; fi; test -L /usr/dangling; test \"$(readlink /usr/dangling)\" = /usr/new-target; printf dangling-write > /usr/dangling; test \"$(cat /usr/dangling)\" = dangling-write; test \"$(readlink /usr/dangling)\" = /usr/new-target", hostBinding);
                    require(read(new File(root, "usr/new-target")).equals("dangling-write") && !exists(new File(host, "usr/new-target")), "dangling symlink guest target creation");
                    require(Files.readSymbolicLink(new File(root, "usr/dangling").toPath()).toString().equals("/usr/new-target"), "dangling symlink preserved");
                } else if (name.equals("symlink_loop")) {
                    Files.createSymbolicLink(new File(root, "usr/loop-a").toPath(), new File("loop-b").toPath());
                    Files.createSymbolicLink(new File(root, "usr/loop-b").toPath(), new File("loop-a").toPath());
                    guest(suite, runtime, root, "if cat /usr/loop-a >/dev/null 2>&1; then exit 72; fi; test -L /usr/loop-a; test -L /usr/loop-b");
                } else if (name.equals("missing_read")) {
                    guest(suite, runtime, root, "if cat /usr/missing-leaf >/dev/null 2>&1; then exit 73; fi; test ! -e /usr/missing-leaf");
                    require(!exists(new File(root, "usr/missing-leaf")) && !exists(new File(host, "usr/missing-leaf")), "read created no file");
                } else if (name.equals("missing_leaf_creation")) {
                    guest(suite, runtime, root, "test ! -e /usr/new-leaf; printf new-file > /usr/new-leaf; test \"$(cat /usr/new-leaf)\" = new-file", hostBinding);
                    require(read(new File(root, "usr/new-leaf")).equals("new-file") && !exists(new File(host, "usr/new-leaf")), "new file guest mapping");
                } else {
                    guest(suite, runtime, root, "if (printf must-not-write > /usr/missing-parent/leaf) 2>/dev/null; then exit 74; fi; test ! -e /usr/missing-parent", hostBinding);
                    require(!exists(new File(root, "usr/missing-parent")) && !exists(new File(host, "usr/missing-parent")), "missing parent not created");
                }
                require(read(new File(host, "usr/marker")).equals("host"), "host /usr fixture unchanged");
                return name.equals("guest_usr_mapping") ? "guest /usr mapping; explicit host-view sentinel unchanged; not a security isolation claim"
                        : "real guest read/write or expected failure with host filesystem assertions";
            });
        }
    }
}
