import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[1]
BINARY = Path(os.environ.get("PROOT_NOLIB_BINARY", PROJECT / "build/proot-distro-nolib/arm64/proot-distro-nolib")).resolve()


class PdnTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="pdn-test-", dir=PROJECT / "build")
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name)
        self.root = self.base / "roots with spaces" / "Ubuntu"
        (self.root / "bin").mkdir(parents=True)
        (self.root / "tmp").mkdir()
        (self.root / "root").mkdir()
        shutil.copy2(PROJECT / "android/proot-engine/src/main/jniLibs/arm64-v8a/libbusybox.so", self.root / "bin/busybox")
        (self.root / "bin/sh").symlink_to("busybox")
        self.env = {"PATH": "/system/bin", "PDN_ROOTFS_DIR": str(self.root.parent)}
        if "LLVM_PROFILE_FILE" in os.environ:
            self.env["LLVM_PROFILE_FILE"] = os.environ["LLVM_PROFILE_FILE"]

    def invoke(self, *args, binary=None, input=None):
        return subprocess.run([str(binary or BINARY), *args], env=self.env,
                              input=input, text=True, capture_output=True, timeout=20)

    def invoke_events(self, *args):
        event_file = self.base / "events.jsonl"
        event_file.unlink(missing_ok=True)
        self.env["PDN_EVENT_FILE"] = str(event_file)
        try:
            result = self.invoke(*args)
        finally:
            self.env.pop("PDN_EVENT_FILE", None)
        events = [json.loads(line) for line in event_file.read_text().splitlines()]
        return result, events

    def error_code(self, code, *args):
        result, events = self.invoke_events(*args)
        self.assertEqual(result.returncode, 2, result.stderr)
        errors = [event for event in events if event["type"] == "error"]
        self.assertEqual(len(errors), 1, events)
        self.assertEqual(errors[0]["code"], code, events)
        self.assertEqual(events[-1]["code"], code, events)
        self.assertEqual(events[-1]["outcome"], "manager_error")
        self.assertEqual(events[-1]["exit_code"], 2)
        return result

    def good(self, result, expected=None):
        self.assertEqual(result.returncode, 0, result.stderr)
        if expected is not None:
            self.assertEqual(result.stdout, expected)

    def test_frontend_error_classification(self):
        missing = self.base / "missing"
        regular = self.base / "regular"
        regular.write_text("file")
        fifo = self.base / "fifo"
        os.mkfifo(fifo)
        for command in ("exec", "config"):
            trailing = ("--", "/bin/sh") if command == "exec" else ("--show",)
            with self.subTest(command=command):
                self.error_code("rootfs_missing", command, "--rootfs", str(missing), *trailing)
                self.error_code("directory_not_directory", command, "--rootfs", str(regular), *trailing)
        self.error_code("bind_source_missing", "exec", "ubuntu", "-b", str(missing), "--", "/bin/sh")
        self.error_code("bind_source_unavailable", "exec", "ubuntu", "-b", str(fifo), "--", "/bin/sh")
        self.error_code("invalid_argument", "exec", "ubuntu", "-b", ":/guest", "--", "/bin/sh")
        (self.root.parent / "UBUNTU").mkdir()
        self.error_code("name_ambiguous", "config", "ubuntu", "--show")
        self.error_code("name_ambiguous", "uninstall", "ubuntu", "--yes")
        self.error_code("rootfs_missing", "uninstall", "absent", "--yes")
        self.env["PDN_ROOTFS_DIR"] = str(regular)
        self.error_code("directory_not_directory", "list")
        self.error_code("directory_not_directory", "uninstall", "ubuntu", "--yes")
        self.env["PDN_ROOTFS_DIR"] = str(missing)
        self.error_code("directory_missing", "config", "ubuntu", "--show")
        self.error_code("directory_missing", "uninstall", "ubuntu", "--yes")

    def test_temporary_directory_error_classification(self):
        missing = self.base / "missing"
        regular = self.base / "regular"
        regular.write_text("file")
        for path, code in ((missing, "directory_missing"), (regular, "directory_not_directory")):
            with self.subTest(path=path):
                self.env["PROOT_TMP_DIR"] = str(path)
                self.error_code(code, "exec", "ubuntu", "--", "/bin/sh")

    def test_busy_operations_report_lock_contention(self):
        rootfd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
        try:
            fcntl.flock(rootfd, fcntl.LOCK_EX)
            self.error_code("operation_busy", "config", "ubuntu", "--show")
            self.error_code("operation_busy", "exec", "ubuntu", "--", "/bin/sh")
            self.error_code("operation_busy", "uninstall", "ubuntu", "--yes")
        finally:
            os.close(rootfd)
        lockfd = os.open(self.root.parent / ".pdn-install.lock", os.O_RDWR | os.O_CREAT, 0o600)
        try:
            fcntl.flock(lockfd, fcntl.LOCK_EX)
            self.error_code("operation_busy", "uninstall", "ubuntu", "--yes")
        finally:
            os.close(lockfd)

    @unittest.skipIf(os.geteuid() == 0, "requires unprivileged directory access")
    def test_directory_permission_classification(self):
        self.root.chmod(0)
        try:
            self.error_code("directory_permission", "config", "ubuntu", "--show")
        finally:
            self.root.chmod(0o755)
        temporary = self.base / "unwritable"
        temporary.mkdir(mode=0o500)
        self.env["PROOT_TMP_DIR"] = str(temporary)
        self.error_code("directory_permission", "exec", "ubuntu", "--", "/bin/sh")

    def test_named_login_and_case(self):
        for name in ("ubuntu", "UBUNTU", "Ubuntu"):
            self.good(self.invoke("LoGiN", name, "--", "/bin/sh", "-c",
                                  '/bin/busybox id -u; /bin/busybox uname -r; echo "$HOME:$USER:$LOGNAME:$TMPDIR"'),
                      "0\n6.17.0-pr\n/root:root:root:/tmp\n")
        self.assertTrue((self.root / ".pdn-tmp").is_dir())

    def test_login_has_no_host_supplementary_groups(self):
        result = self.invoke("login", "ubuntu", "--", "/bin/busybox", "id", "-G")
        self.good(result, "0\n")

    def test_direct_path_and_exact_arguments(self):
        payloads = ["two words", "", "MiXeD", "a'b\"c", "$(echo injected)", "; exit 99", "line\nbreak"]
        result = self.invoke("login", "--ROOTFS", str(self.root), "--", "/bin/sh", "-c",
                             'printf "<%s>\\n" "$@"', "marker", *payloads)
        self.good(result, "".join(f"<{value}>\n" for value in payloads))

    def test_exec_exact_arguments_and_case(self):
        payloads = ["two words", "", "MiXeD", "a'b\"c", "$(echo injected)",
                    "; exit 99", "line\nbreak", "--bind", "-B", "--rootfs", "--"]
        for selector in (("uBuNtU",), ("--ROOTFS", str(self.root))):
            result = self.invoke("ExEc", *selector, "--", "/bin/sh", "-c",
                                 'printf "<%s>\\n" "$@"', "marker", *payloads)
            self.good(result, "".join(f"<{value}>\n" for value in payloads))

    def test_exec_requires_command_and_preserves_io_status(self):
        for selector in (("ubuntu",), ("--rootfs", str(self.root))):
            for trailing in ((), ("--",), ("/bin/sh",), ("-b", str(self.base))):
                result = self.invoke("exec", *selector, *trailing, input="echo interactive\n")
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertEqual(result.stdout, "")
        result = self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c",
                             'read value; echo "out:$value"; echo error >&2; exit 37', input="from stdin\n")
        self.assertEqual(result.returncode, 37, result.stderr)
        self.assertEqual(result.stdout, "out:from stdin\n")
        self.assertEqual(result.stderr, "error\n")
        self.assertEqual(self.invoke("exec", "ubuntu", "--", "does-not-exist").returncode, 127)

    def test_repeated_directory_and_file_binds(self):
        first, second = self.base / "Host One", self.base / "Host Two"
        first.mkdir()
        second.mkdir()
        source = self.base / "Mixed File"
        source.write_text("exact case\n")
        for command, selector in (("login", ("ubuntu",)), ("exec", ("--rootfs", str(self.root)))):
            result = self.invoke(command, *selector, "--BiNd", f"{first}:/Guest One",
                                 "-B", f"{second}:/Guest Two", "-b", f"{source}:/Guest File", "--",
                                 "/bin/sh", "-c", 'echo one > "/Guest One/result"; '
                                 'echo two > "/Guest Two/result"; /bin/busybox cat "/Guest File"')
            self.good(result, "exact case\n")
            self.assertEqual((first / "result").read_text(), "one\n")
            self.assertEqual((second / "result").read_text(), "two\n")
        self.good(self.invoke("exec", "ubuntu", "-b", f"{source}:/Guest File", "--",
                              "/bin/sh", "-c", 'echo changed > "/Guest File"'))
        self.assertEqual(source.read_text(), "changed\n")
        self.good(self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c",
                              'test ! -e "/Guest One/result" && test ! -s "/Guest File"'))

    def test_interactive_login_bind(self):
        source = self.base / "interactive"
        source.mkdir()
        (source / "value").write_text("interactive bind\n")
        self.good(self.invoke("login", "ubuntu", "-b", f"{source}:/shared",
                              input="/bin/busybox cat /shared/value\nexit\n"), "interactive bind\n")

    def test_bind_relative_host_and_default_destination(self):
        source = self.base / "relative source"
        source.mkdir()
        (source / "value").write_text("canonical\n")
        link = self.base / "source link"
        link.symlink_to(source, target_is_directory=True)
        relative = os.path.relpath(link)
        self.good(self.invoke("exec", "ubuntu", "-b", relative, "--", "/bin/busybox", "cat",
                              str(source / "value")), "canonical\n")

    def test_bind_overrides_default_and_prior_bind(self):
        first, second = self.base / "first", self.base / "second"
        first.mkdir()
        second.mkdir()
        (first / "marker").write_text("first\n")
        (second / "marker").write_text("second\n")
        self.good(self.invoke("exec", "ubuntu", "-b", f"{first}:/sys", "-b", f"{second}:/sys", "--",
                              "/bin/busybox", "cat", "/sys/marker"), "second\n")

    def test_bind_invalid_specs_and_options(self):
        fifo = self.base / "fifo"
        os.mkfifo(fifo)
        missing = self.base / "missing"
        cases = [("--bind",), ("-b", "--"), ("-b", "--bind", str(self.base)),
                 ("-b", ""), ("-b", ":/guest"), ("-b", f"{self.base}:"),
                 ("-b", f"{self.base}:relative"), ("-b", f"{self.base}:/guest:extra"),
                 ("-b", f"{self.base}:/guest!"), ("-b", f"{self.base}!:/guest"),
                 ("-b", f"{missing}:/guest"), ("-b", str(fifo)),
                 ("--unknown",), ("--bind=" + str(self.base),),
                 ("-b", str(self.base), "--rootfs", str(self.root)),
                 ("-b", str(self.base), "--bind", f"{missing}:/guest")]
        for command in ("login", "exec"):
            for options in cases:
                with self.subTest(command=command, options=options):
                    result = self.invoke(command, "ubuntu", *options)
                    self.assertEqual(result.returncode, 2, result.stderr)
                    self.assertIn("pdn:", result.stderr)
        for name in ("colon:host", "suffix!"):
            source = self.base / name
            source.mkdir()
            link = self.base / "link"
            link.symlink_to(source, target_is_directory=True)
            self.assertEqual(self.invoke("exec", "ubuntu", "-b", f"{link}:/guest",
                                         "--", "/bin/sh").returncode, 2)
            link.unlink()

    def test_exec_environment_and_lock(self):
        self.env.update(ENV="/missing-hook", BASH_ENV="/missing-hook")
        self.good(self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c",
                              'echo "$HOME:$USER:$LOGNAME:$TMPDIR:${ENV-unset}:${BASH_ENV-unset}"'),
                  "/root:root:root:/tmp:unset:unset\n")
        rootfd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
        try:
            fcntl.flock(rootfd, fcntl.LOCK_EX)
            self.assertEqual(self.invoke("exec", "ubuntu", "--", "/bin/sh").returncode, 2)
        finally:
            os.close(rootfd)

    def test_interactive_shell_fallback_and_stdin(self):
        result = self.invoke("login", "ubuntu", input='echo "$SHELL"; pwd; exit 23\n')
        self.assertEqual(result.returncode, 23, result.stderr)
        self.assertEqual(result.stdout, "/bin/sh\n/root\n")

    def test_bash_selection(self):
        (self.root / "bin/bash").write_text('#!/bin/sh\necho "chosen:$SHELL:$1"\n')
        (self.root / "bin/bash").chmod(0o755)
        self.good(self.invoke("login", "ubuntu", input="exit\n"), "chosen:/bin/bash:-l\n")

    def test_cwd_fallback_and_exit_status(self):
        (self.root / "root").rmdir()
        self.good(self.invoke("login", "ubuntu", input="pwd\nexit\n"), "/\n")
        self.assertEqual(self.invoke("login", "ubuntu", "--", "/bin/sh", "-c", "exit 37").returncode, 37)
        self.assertEqual(self.invoke("login", "ubuntu", "--", "does-not-exist").returncode, 127)

    def test_guest_files_and_external_loader(self):
        self.env["PROOT_LOADER"] = str(BINARY.parent / "proot-loader")
        self.good(self.invoke("login", "ubuntu", "--", "/bin/sh", "-c", 'echo persisted > /sample; /bin/busybox cat /sample'), "persisted\n")
        self.assertEqual((self.root / "sample").read_text(), "persisted\n")

    def test_hardlink_compatibility(self):
        self.good(self.invoke("login", "ubuntu", "--", "/bin/sh", "-c",
                              'echo original > /first; /bin/busybox ln /first /second; '
                              'echo changed > /second; /bin/busybox cat /first'), "changed\n")

    def test_default_home_lookup(self):
        self.env.pop("PDN_ROOTFS_DIR")
        self.env["HOME"] = str(self.base)
        default = self.base / ".local/share/pdn/rootfs"
        default.mkdir(parents=True)
        (default / "debian").symlink_to(self.root, target_is_directory=True)
        self.good(self.invoke("login", "DEBIAN", "--", "/bin/sh", "-c", "echo default"), "default\n")
        self.good(self.invoke("LIST"), "debian\n")

    def test_list_and_ambiguity(self):
        (self.root.parent / "file").write_text("ignore")
        (self.root.parent / ".hidden").mkdir()
        for command in ("list", "ls", "LS", "Ls"):
            self.good(self.invoke(command), "Ubuntu\n")
        (self.root.parent / "ubuntu").mkdir()
        result = self.invoke("login", "ubuntu")
        self.assertEqual(result.returncode, 2)
        self.assertIn("ambiguous", result.stderr)
        self.good(self.invoke("login", "--rootfs", str(self.root), "--", "/bin/sh", "-c", "echo exact"), "exact\n")

    def test_invalid_inputs(self):
        cases = [("login",), ("login", "--rootfs"), ("login", "--rootfs", ""),
                 ("login", "../Ubuntu"), ("login", ""), ("login", "no/such"),
                 ("login", "ubuntu", "echo"), ("login", "ubuntu", "--"),
                 ("login", "missing"), ("login", "--rootfs", "/"),
                 ("login", "--rootfs", str(self.base / "missing")), ("list", "extra"), ("ls", "extra")]
        (self.base / "file").write_text("not a directory")
        cases.append(("login", "--rootfs", str(self.base / "file")))
        for args in cases:
            with self.subTest(args=args):
                self.assertEqual(self.invoke(*args).returncode, 2)

    def test_base_errors(self):
        self.env.pop("PDN_ROOTFS_DIR")
        self.assertEqual(self.invoke("login", "ubuntu").returncode, 2)
        self.assertEqual(self.invoke("list").returncode, 2)
        self.env["PDN_ROOTFS_DIR"] = str(self.base / "missing")
        self.assertEqual(self.invoke("login", "ubuntu").returncode, 2)
        self.good(self.invoke("list"), "No local rootfs found.\n")
        (self.base / "file").write_text("x")
        self.env["PDN_ROOTFS_DIR"] = str(self.base / "file")
        self.assertEqual(self.invoke("list").returncode, 2)

    def test_temp_precedence_and_failures(self):
        temp = self.base / "chosen temp"
        temp.mkdir()
        self.env.update(TMPDIR=str(self.base / "missing"), PROOT_TMP_DIR=str(temp))
        self.good(self.invoke("login", "ubuntu", "--", "/bin/sh", "-c", "echo ok"), "ok\n")
        self.assertFalse((self.root / ".pdn-tmp").exists())
        self.env["PROOT_TMP_DIR"] = ""
        self.assertEqual(self.invoke("login", "ubuntu").returncode, 2)

        self.env["TMPDIR"] = str(temp)
        self.good(self.invoke("login", "ubuntu", "--", "/bin/sh", "-c", "echo tmp"), "tmp\n")
        self.env["TMPDIR"] = ""
        (self.root / ".pdn-tmp").write_text("not directory")
        self.assertEqual(self.invoke("login", "ubuntu").returncode, 2)

    def test_temp_errors_explain_missing_file_and_permissions(self):
        temp = self.base / "missing temp"
        self.env["PROOT_TMP_DIR"] = str(temp)
        result = self.invoke("login", "ubuntu")
        self.assertEqual(result.returncode, 2)
        for text in (str(temp), "PROOT_TMP_DIR", "No such file or directory", "mkdir -p"):
            self.assertIn(text, result.stderr)
        temp.write_text("keep")
        result = self.invoke("login", "ubuntu")
        self.assertIn("Not a directory", result.stderr)
        self.assertEqual(temp.read_text(), "keep")
        temp.unlink()
        temp.mkdir(mode=0o500)
        if not os.access(temp, os.W_OK):
            result = self.invoke("login", "ubuntu")
            self.assertIn("Permission denied", result.stderr)
            self.assertIn("writable", result.stderr)
        temp.chmod(0o700)
        self.good(self.invoke("exec", "ubuntu", "--", "/bin/busybox", "echo", "ok"), "ok\n")
        self.env.pop("PROOT_TMP_DIR")
        self.env["TMPDIR"] = str(self.base / "missing fallback")
        self.assertIn("selected by TMPDIR", self.invoke("login", "ubuntu").stderr)

    def test_default_temp_creation_and_broken_link_errors(self):
        temp = self.root / ".pdn-tmp"
        self.root.chmod(0o500)
        try:
            if not os.access(self.root, os.W_OK):
                result = self.invoke("login", "ubuntu")
                for text in (str(temp), "rootfs/.pdn-tmp", "Permission denied"):
                    self.assertIn(text, result.stderr)
        finally:
            self.root.chmod(0o700)
        temp.symlink_to("missing temp target")
        result = self.invoke("login", "ubuntu")
        self.assertEqual(result.returncode, 2)
        for text in (str(temp), "rootfs/.pdn-tmp", "No such file or directory", "mkdir -p"):
            self.assertIn(text, result.stderr)
        temp.unlink()
        self.good(self.invoke("exec", "ubuntu", "--", "/bin/busybox", "echo", "recovered"), "recovered\n")

    def test_frontend_relocation_and_help(self):
        renamed = self.base / "pdn"
        shutil.copy2(BINARY, renamed)
        for args in [(), ("--HELP",), ("-H",), ("HeLp",), ("login", "--help"), ("login", "-h"), ("exec", "--help"), ("exec", "-H")]:
            result = self.invoke(*args, binary=renamed)
            self.good(result)
            self.assertIn("pdn login", result.stdout)
        self.assertEqual(self.invoke("unsupported", binary=renamed).returncode, 2)
        for args in [("VeRsIoN",), ("--version",), ("proot", "--version")]:
            result = self.invoke(*args, binary=renamed)
            self.good(result)
            self.assertIn("proot-distro-nolib 0.6.3", result.stdout)
            self.assertIn("Copyright (C) 2015 STMicroelectronics", result.stdout)
        self.good(self.invoke("login", "Ubuntu", "--", "/bin/sh", "-c", "echo relocated", binary=renamed), "relocated\n")

    def test_host_shell_hooks_not_loaded(self):
        hook = self.base / "hook.sh"
        hook.write_text("exit 93\n")
        self.env.update(ENV=str(hook), BASH_ENV=str(hook), PROOT_NO_SECCOMP="1")
        self.good(self.invoke("login", "ubuntu", "--", "/bin/sh", "-c", 'echo "${ENV-unset}:${BASH_ENV-unset}"'), "unset:unset\n")

    def test_uninstall_confirmation_and_alias(self):
        (self.root / "root/important").write_text("user data")
        for answer in ("", "\n", "n\n", "sure\n", "yes please\n"):
            result = self.invoke("uninstall", "UBUNTU", input=answer)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(str(self.root), result.stderr)
            self.assertIn("Cancelled", result.stderr)
            self.assertEqual((self.root / "root/important").read_text(), "user data")
        result = self.invoke("ReMoVe", "uBuNtU", input="YeS\n")
        self.good(result)
        self.assertIn("Uninstalled", result.stdout)
        self.assertFalse(self.root.exists())
        self.good(self.invoke("ls"), "")

    def test_uninstall_yes_and_default_home(self):
        for args, answer in ((("--YES",), None), (("-Y",), None), ((), "y\n")):
            target = self.root.parent / "debian"
            target.mkdir()
            self.good(self.invoke("UNINSTALL", "DEBIAN", *args, input=answer))
            self.assertFalse(target.exists())
        self.env.pop("PDN_ROOTFS_DIR")
        self.env["HOME"] = str(self.base)
        default = self.base / ".local/share/pdn/rootfs/Alpine"
        default.mkdir(parents=True)
        self.good(self.invoke("remove", "alpine", "--yes"))
        self.assertFalse(default.exists())
        self.assertTrue(self.root.exists())

    def test_uninstall_preserves_external_links_and_other_roots(self):
        outside = self.base / "outside"
        outside.mkdir()
        marker = outside / "keep"
        marker.write_text("keep me")
        (self.root / "external").symlink_to(outside, target_is_directory=True)
        (self.root / "host").symlink_to("/")
        (self.root / "loop").symlink_to(".")
        (self.root / "dangling").symlink_to("missing")
        (self.root / "external-file").symlink_to(marker)
        os.mkfifo(self.root / "fifo")
        readonly = self.root / "read only"
        readonly.mkdir()
        (readonly / "file").write_text("remove")
        readonly.chmod(0o555)
        sibling = self.root.parent / "alpine"
        sibling.mkdir()
        (sibling / "keep").write_text("other distro")
        self.good(self.invoke("uninstall", "ubuntu", "-y"))
        self.assertFalse(self.root.exists())
        self.assertEqual(marker.read_text(), "keep me")
        self.assertEqual((sibling / "keep").read_text(), "other distro")

    def test_uninstall_rejects_unsafe_targets(self):
        for name in ("", ".", "..", "../Ubuntu", "/", str(self.root), "a/b", ".hidden"):
            self.assertEqual(self.invoke("uninstall", name, "--yes").returncode, 2)
        for args in ((), ("ubuntu", "--force"), ("--rootfs", str(self.root)),
                     ("ubuntu", "--yes", "extra"), ("missing",)):
            self.assertEqual(self.invoke("remove", *args).returncode, 2)
        (self.root.parent / "file").write_text("keep")
        (self.root.parent / "linked").symlink_to(self.root, target_is_directory=True)
        (self.root.parent / "broken").symlink_to("missing")
        for name in ("file", "linked", "broken"):
            self.assertEqual(self.invoke("remove", name, "--yes").returncode, 2)
        duplicate = self.root.parent / "ubuntu"
        duplicate.mkdir()
        self.assertIn("ambiguous", self.invoke("remove", "ubuntu", "--yes").stderr)
        self.assertTrue(duplicate.exists())
        self.assertTrue((self.root / "bin/busybox").exists())

    def test_uninstall_base_errors_and_help(self):
        for base in (None, str(self.base / "missing"), str(self.root / "bin/busybox"), "/"):
            if base is None:
                self.env.pop("PDN_ROOTFS_DIR", None)
            else:
                self.env["PDN_ROOTFS_DIR"] = base
            self.assertEqual(self.invoke("uninstall", "ubuntu", "--yes").returncode, 2)
        for command in ("uninstall", "remove"):
            for option in ("--help", "-H"):
                result = self.invoke(command, option)
                self.good(result)
                self.assertIn("pdn uninstall", result.stdout)

    def test_uninstall_locks(self):
        with (self.root.parent / ".pdn-install.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            result = self.invoke("uninstall", "ubuntu", "--yes")
            self.assertEqual(result.returncode, 2)
            self.assertIn("lock", result.stderr)
        rootfd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
        try:
            fcntl.flock(rootfd, fcntl.LOCK_EX)
            self.assertEqual(self.invoke("uninstall", "ubuntu", "--yes").returncode, 2)
            self.assertEqual(self.invoke("login", "ubuntu", "--", "/bin/sh", "-c", "exit").returncode, 2)
        finally:
            os.close(rootfd)
        self.good(self.invoke("uninstall", "ubuntu", "--yes"))

    def test_uninstall_blocks_live_session(self):
        self.assert_uninstall_blocks_live_session("login")

    def test_uninstall_blocks_live_exec(self):
        self.assert_uninstall_blocks_live_session("exec")

    def assert_uninstall_blocks_live_session(self, command):
        session = subprocess.Popen([str(BINARY), command, "ubuntu", "--", "/bin/sh", "-c", "echo ready; read answer"],
                                   env=self.env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True)
        try:
            self.assertEqual(session.stdout.readline(), "ready\n")
            result = self.invoke("uninstall", "ubuntu", "--yes")
            self.assertEqual(result.returncode, 2)
            self.assertIn("in use", result.stderr)
            self.assertTrue(self.root.exists())
            session.communicate("done\n", timeout=10)
        finally:
            if session.poll() is None:
                session.kill()
            session.communicate(timeout=10)
        self.good(self.invoke("uninstall", "ubuntu", "--yes"))

    def test_uninstall_rechecks_target_after_confirmation(self):
        process = subprocess.Popen([str(BINARY), "uninstall", "ubuntu"], env=self.env,
                                   stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertIn(str(self.root), process.stderr.readline())
            self.assertEqual(self.invoke("login", "ubuntu", "--", "/bin/sh", "-c", "exit").returncode, 2)
            saved = self.root.with_name("saved")
            self.root.rename(saved)
            self.root.symlink_to(saved, target_is_directory=True)
            stdout, stderr = process.communicate("yes\n", timeout=10)
            self.assertEqual(process.returncode, 2, stdout + stderr)
            self.assertIn("changed", stderr)
            self.assertTrue((saved / "bin/busybox").exists())
            self.assertTrue(self.root.is_symlink())
        finally:
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=10)

    def test_uninstall_permission_error(self):
        denied = self.root / "denied"
        denied.mkdir()
        (denied / "keep").write_text("inaccessible")
        denied.chmod(0)
        try:
            result = self.invoke("uninstall", "ubuntu", "--yes")
            self.assertEqual(result.returncode, 2)
            self.assertIn("incomplete", result.stderr)
            self.assertTrue(denied.exists())
        finally:
            denied.chmod(0o700)
        self.good(self.invoke("uninstall", "ubuntu", "--yes"))


if __name__ == "__main__":
    unittest.main()
