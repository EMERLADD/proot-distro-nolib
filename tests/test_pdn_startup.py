import errno
import unittest
import os
import struct
import subprocess
from pathlib import Path
import shutil
import tempfile

import test_pdn as fixture
import test_pdn_events as protocol


class StartupTests(unittest.TestCase):
    setUp = fixture.PdnTests.setUp
    invoke = fixture.PdnTests.invoke
    channel = protocol.EventTests.channel
    events = protocol.EventTests.events

    def failure(self, component, value):
        path = self.channel()
        result = self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c", "exit 0")
        self.assertNotEqual(result.returncode, 0, result.stderr)
        events = self.events(path, "exec", result, "manager_error")
        self.assertEqual(events[-1]["code"], component)
        self.assertIn(f"errno={value}", events[-1]["message"])
        self.assertTrue(events[-1]["suggestion"])
        self.assertEqual(len([e for e in events if e["type"] == "error"]), 1)

    def test_initial_shell_missing(self):
        (self.root / "bin/sh").unlink()
        self.failure("guest_shell_missing", errno.ENOENT)

    def test_initial_shell_nonexecutable(self):
        (self.root / "bin/busybox").chmod(0o600)
        self.failure("guest_shell_nonexecutable", errno.EACCES)

    def test_initial_shell_bad_format(self):
        (self.root / "bin/sh").unlink()
        (self.root / "bin/sh").write_bytes(b"not an ELF or script\n")
        (self.root / "bin/sh").chmod(0o700)
        self.failure("guest_shell_bad_format", errno.ENOEXEC)

    def test_initial_shell_bad_format_exit_zero_fallback(self):
        (self.root / "bin/sh").unlink()
        (self.root / "bin/sh").write_bytes(b"exit 0\n")
        (self.root / "bin/sh").chmod(0o700)
        self.failure("guest_shell_bad_format", errno.ENOEXEC)

    def test_external_loader_missing(self):
        self.env["PROOT_LOADER"] = str(self.base / "absent-loader")
        self.failure("proot_loader_missing", errno.ENOENT)

    def test_external_loader_nonexecutable(self):
        path = self.base / "loader"
        path.write_bytes(b"broken")
        path.chmod(0o600)
        self.env["PROOT_LOADER"] = str(path)
        self.failure("proot_loader_nonexecutable", errno.EACCES)

    def test_external_loader_bad_format(self):
        path = self.base / "loader"
        path.write_bytes(b"broken")
        path.chmod(0o700)
        self.env["PROOT_LOADER"] = str(path)
        self.failure("proot_loader_bad_format", errno.ENOEXEC)

    def elf_shell(self):
        data = bytearray((self.root / "bin/busybox").read_bytes())
        offset = len(data)
        interpreter = b"/lib/pdn-test-interpreter\0"
        phoff = struct.unpack_from("<Q", data, 32)[0]
        phsize, count = struct.unpack_from("<HH", data, 54)
        for index in range(count):
            position = phoff + index * phsize
            if struct.unpack_from("<I", data, position)[0] == 4:
                struct.pack_into("<IIQQQQQQ", data, position, 3, 4, offset, 0, 0,
                                 len(interpreter), len(interpreter), 1)
                break
        else:
            raise AssertionError("BusyBox ELF fixture has no replaceable NOTE header")
        data.extend(interpreter)
        (self.root / "bin/sh").unlink()
        (self.root / "bin/sh").write_bytes(data)
        (self.root / "bin/sh").chmod(0o700)

    def test_guest_elf_interpreter_missing(self):
        self.elf_shell()
        self.failure("guest_interpreter_missing", errno.ENOENT)

    def test_guest_elf_interpreter_bad_format(self):
        self.elf_shell()
        (self.root / "lib").mkdir()
        interpreter = self.root / "lib/pdn-test-interpreter"
        interpreter.write_bytes(b"broken")
        interpreter.chmod(0o700)
        self.failure("guest_interpreter_bad_format", errno.ENOEXEC)

    def test_guest_elf_interpreter_nonexecutable(self):
        self.elf_shell()
        (self.root / "lib").mkdir()
        interpreter = self.root / "lib/pdn-test-interpreter"
        interpreter.write_bytes(b"broken")
        interpreter.chmod(0o600)
        self.failure("guest_interpreter_nonexecutable", errno.EACCES)

    def test_guest_elf_interpreter_truncated_metadata(self):
        self.elf_shell()
        shell = self.root / "bin/sh"
        data = bytearray(shell.read_bytes())
        phoff = struct.unpack_from("<Q", data, 32)[0]
        phsize, count = struct.unpack_from("<HH", data, 54)
        for index in range(count):
            position = phoff + index * phsize
            if struct.unpack_from("<I", data, position)[0] == 3:
                struct.pack_into("<Q", data, position + 32, 1000)
                break
        shell.write_bytes(data)
        self.failure("guest_interpreter_bad_format", errno.ENOEXEC)

    def runtime_loader(self, instructions):
        source = self.base / "loader.S"
        source.write_text('.global _start\n.text\n_start:\n' + instructions +
                          '\nmov x0, #0\nmov x8, #93\nsvc #0\n.data\npath: .asciz "/"\n')
        ndk = Path(os.environ["NDK_PATH"])
        toolchain, = ndk.glob("toolchains/llvm/prebuilt/*")
        resource, = (toolchain / "lib/clang").iterdir()
        compiler = os.environ.get("CC", "clang")
        flags = ["--target=aarch64-linux-android24", f"--sysroot={toolchain / 'sysroot'}",
                 f"-resource-dir={resource}", "-nostdlib", "-static"]
        if "-fno-termux-rpath" in subprocess.check_output([compiler, "--help"], text=True):
            flags.append("-fno-termux-rpath")
        loader = self.base / "runtime-loader"
        subprocess.run([compiler, *flags, str(source), "-o", str(loader)], check=True)
        self.env["PROOT_LOADER"] = str(loader)

    def test_valid_loader_exits_without_starting_guest(self):
        self.runtime_loader("")
        path = self.channel()
        result = self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c", "exit 0")
        self.assertNotEqual(result.returncode, 0)
        events = self.events(path, "exec", result, "manager_error")
        self.assertEqual(events[-1]["code"], "guest_start_failed")
        self.assertIn("exit=0", events[-1]["message"])
        self.assertNotIn("errno=", events[-1]["message"])

    def test_injected_loader_open_syscall_failure(self):
        self.runtime_loader("mov x0, #-100\nadr x1, path\nmov x2, #1\nmov x3, #0\nmov x8, #56\nsvc #0")
        self.failure("loader_open_failed", errno.EISDIR)

    def test_injected_loader_mapping_syscall_failure(self):
        self.runtime_loader("mov x0, #0\nmov x1, #4096\nmov x2, #1\nmov x3, #2\nmov x4, #-1\nmov x5, #0\nmov x8, #222\nsvc #0")
        self.failure("loader_mapping_failed", errno.EBADF)

    def test_injected_loader_close_syscall_failure(self):
        self.runtime_loader("mov x0, #-1\nmov x8, #57\nsvc #0")
        self.failure("loader_close_failed", errno.EBADF)

    def test_selected_login_shell_failures(self):
        (self.root / "etc").mkdir()
        for mode in (None, 0o600, 0o700, "fallback"):
            shell = self.root / "bin/selected"
            shell.unlink(missing_ok=True)
            if mode is not None:
                shell.write_bytes(b"exit 0\n" if mode == "fallback" else b"broken")
                shell.chmod(0o700 if mode == "fallback" else mode)
            (self.root / "etc/passwd").write_text("alice:x:1000:1000:Alice:/root:/bin/selected\n")
            path = self.channel()
            result = self.invoke("login", "ubuntu", "-u", "alice", input="exit\n")
            events = self.events(path, "login", result, "manager_error")
            suffix, value = ("missing", errno.ENOENT) if mode is None else \
                ("nonexecutable", errno.EACCES) if mode == 0o600 else ("bad_format", errno.ENOEXEC)
            self.assertEqual(events[-1]["code"], "guest_login_shell_" + suffix)
            self.assertIn(f"errno={value}", events[-1]["message"])

    def test_dynamic_login_benign_library_search_failure(self):
        alpine = Path(os.environ.get("PDN_ALPINE_FIXTURE", fixture.PROJECT / "build/pdn-alpine-live/alpine"))
        binary = alpine / "bin/busybox"
        linker = alpine / "lib/ld-musl-aarch64.so.1"
        library = alpine / "usr/lib/libz.so.1"
        if not all(path.exists() for path in (binary, linker, library)):
            self.skipTest("dynamic Alpine fixture is unavailable")
        data = binary.read_bytes()
        dependency = b"libc.musl-aarch64.so.1\0"
        replacement = b"libz.so.1\0"
        self.assertIn(dependency, data)
        data = data.replace(dependency, replacement + b"\0" * (len(dependency) - len(replacement)), 1)
        (self.root / "bin/busybox").write_bytes(data)
        (self.root / "lib").mkdir()
        (self.root / "usr/lib").mkdir(parents=True)
        shutil.copy2(linker, self.root / "lib/ld-musl-aarch64.so.1")
        shutil.copy2(library, self.root / "usr/lib/libz.so.1")
        path = self.channel()
        result = self.invoke("login", "ubuntu", input="exit 0\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        events = self.events(path, "login", result, "success")
        self.assertFalse(any(event["type"] == "error" for event in events))

    def test_successful_login_guest_exit_and_later_exec_failure(self):
        for command, value in (("exit 126\n", 126), ("missing-command\nexit 127\n", 127)):
            path = self.channel()
            result = self.invoke("login", "ubuntu", input=command)
            events = self.events(path, "login", result, "guest_exit")
            self.assertEqual(events[-1]["guest_exit_code"], value)
            self.assertNotIn("code", events[-1])


class InjectedStartupTests(StartupTests):
    @classmethod
    def setUpClass(cls):
        cls.shared = tempfile.TemporaryDirectory(prefix="pdn-startup-fixture-", dir=fixture.PROJECT / "build")
        cls.addClassCleanup(cls.shared.cleanup)
        directory = Path(cls.shared.name)
        source = directory / "fault.c"
        source.write_text(r'''#include <errno.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/ptrace.h>
#include <sys/stat.h>
#include <stdio.h>
static int failed(const char *component) {
    const char *selected = getenv("PDN_FIXTURE_COMPONENT");
    if (!selected || strcmp(selected, component)) return 0;
    errno = atoi(getenv("PDN_FIXTURE_ERRNO"));
    return 1;
}
pid_t __real_fork(void);
pid_t __wrap_fork(void) { return failed("fork") ? -1 : __real_fork(); }
int __real_pipe2(int *, int);
int __wrap_pipe2(int *fd, int flags) { return failed("pipe") ? -1 : __real_pipe2(fd, flags); }
long __real_ptrace(int, pid_t, void *, void *);
long __wrap_ptrace(int request, pid_t pid, void *addr, void *data) {
    if ((request == PTRACE_TRACEME && failed("ptrace")) ||
        (request == PTRACE_SETOPTIONS && failed("ptrace_options")) ||
        (request == PTRACE_SYSCALL && failed("ptrace_resume"))) return -1;
    return __real_ptrace(request, pid, addr, data);
}
int __real_mkstemp(char *);
int __wrap_mkstemp(char *path) {
    if (strstr(path, "/prooted-") && failed("loader_temp")) return -1;
    return __real_mkstemp(path);
}
int __real_fchmod(int, mode_t);
int __wrap_fchmod(int fd, mode_t mode) {
    char descriptor[64], path[4096];
    snprintf(descriptor, sizeof(descriptor), "/proc/self/fd/%d", fd);
    ssize_t length = readlink(descriptor, path, sizeof(path) - 1);
    if (length >= 0) {
        path[length] = 0;
        if (strstr(path, "/prooted-") && failed("loader_chmod")) return -1;
    }
    return __real_fchmod(fd, mode);
}
ssize_t __real_write(int, const void *, size_t);
ssize_t __wrap_write(int fd, const void *buffer, size_t size) {
    if (size >= 4 && !memcmp(buffer, "\177ELF", 4) && failed("loader_write")) return -1;
    return __real_write(fd, buffer, size);
}
ssize_t __real_pread(int, void *, size_t, off_t);
ssize_t __wrap_pread(int fd, void *buffer, size_t size, off_t offset) {
    const char *selected_offset = getenv("PDN_FIXTURE_OFFSET");
    if (selected_offset && offset == (off_t)atoll(selected_offset) && failed("interp_pread")) return -1;
    return __real_pread(fd, buffer, size, offset);
}
#ifdef PDN_FIXTURE_COVERAGE
int __llvm_profile_write_file(void);
void __real__exit(int) __attribute__((noreturn));
void __wrap__exit(int status) {
    __llvm_profile_write_file();
    __real__exit(status);
}
#endif
''')
        ndk = Path(os.environ["NDK_PATH"])
        toolchain, = ndk.glob("toolchains/llvm/prebuilt/*")
        resource, = (toolchain / "lib/clang").iterdir()
        compiler = os.environ.get("CC", "clang")
        flags = ["--target=aarch64-linux-android24", f"--sysroot={toolchain / 'sysroot'}",
                 f"-resource-dir={resource}", "-D_GNU_SOURCE"]
        if "-fno-termux-rpath" in subprocess.check_output([compiler, "--help"], text=True):
            flags.append("-fno-termux-rpath")
        if os.environ.get("PDN_COVERAGE"):
            flags.append("-DPDN_FIXTURE_COVERAGE")
        obj = directory / "fault.o"
        subprocess.run([compiler, *flags, "-c", str(source), "-o", str(obj)], check=True)
        env = os.environ.copy()
        env["OUT_DIR"] = str(directory)
        env["EXTRA_LDFLAGS"] = env.get("EXTRA_LDFLAGS", "") + \
            f" {obj} -Wl,--wrap=fork,--wrap=pipe2,--wrap=ptrace,--wrap=mkstemp,--wrap=fchmod,--wrap=write,--wrap=pread"
        if os.environ.get("PDN_COVERAGE"):
            env["EXTRA_LDFLAGS"] += " -Wl,--wrap=_exit"
        subprocess.run(["sh", "scripts/build-proot-nolib.sh"], cwd=fixture.PROJECT, env=env,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, check=True)
        cls.harness = directory / "proot-distro-nolib"
        if os.environ.get("PDN_COVERAGE"):
            shutil.copy2(directory / "proot-distro-nolib.debug", fixture.PROJECT / "build/pdn-startup-coverage-harness")

    def invoke(self, *args, input=None):
        return fixture.PdnTests.invoke(self, *args, binary=self.harness, input=input)

    def test_injected_launch_failures(self):
        for component, code, value in (("pipe", "launch_pipe_failed", errno.EMFILE),
                                       ("fork", "launch_fork_failed", errno.EAGAIN),
                                       ("ptrace", "ptrace_failed", errno.EPERM),
                                       ("ptrace_options", "ptrace_failed", errno.EIO),
                                       ("ptrace_resume", "ptrace_failed", errno.EPERM),
                                       ("fork", "launch_fork_failed", errno.EIO)):
            self.env.update(PDN_FIXTURE_COMPONENT=component, PDN_FIXTURE_ERRNO=str(value))
            self.failure(code, value)

    def test_injected_bundled_loader_temp_failure(self):
        self.env.update(PDN_FIXTURE_COMPONENT="loader_temp", PDN_FIXTURE_ERRNO=str(errno.ENOSPC))
        self.failure("proot_loader_failed", errno.ENOSPC)

    def test_injected_bundled_loader_permission_failure(self):
        self.env.update(PDN_FIXTURE_COMPONENT="loader_chmod", PDN_FIXTURE_ERRNO=str(errno.EACCES))
        self.failure("proot_loader_nonexecutable", errno.EACCES)

    def test_injected_bundled_loader_write_failure(self):
        self.env.update(PDN_FIXTURE_COMPONENT="loader_write", PDN_FIXTURE_ERRNO=str(errno.ENOSPC))
        self.failure("proot_loader_failed", errno.ENOSPC)

    def test_injected_interpreter_pread_failure(self):
        self.elf_shell()
        self.env.update(PDN_FIXTURE_COMPONENT="interp_pread", PDN_FIXTURE_ERRNO=str(errno.EIO),
                        PDN_FIXTURE_OFFSET=str((self.root / "bin/busybox").stat().st_size))
        self.failure("guest_interpreter_failed", errno.EIO)


if __name__ == '__main__':
    unittest.main()
