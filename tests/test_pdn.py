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

    def good(self, result, expected=None):
        self.assertEqual(result.returncode, 0, result.stderr)
        if expected is not None:
            self.assertEqual(result.stdout, expected)

    def test_named_login_and_case(self):
        for name in ("ubuntu", "UBUNTU", "Ubuntu"):
            self.good(self.invoke("LoGiN", name, "--", "/bin/sh", "-c",
                                  '/bin/busybox id -u; /bin/busybox uname -r; echo "$HOME:$USER:$LOGNAME:$TMPDIR"'),
                      "0\n6.17.0-pr\n/root:root:root:/tmp\n")
        self.assertTrue((self.root / ".pdn-tmp").is_dir())

    def test_direct_path_and_exact_arguments(self):
        payloads = ["two words", "", "MiXeD", "a'b\"c", "$(echo injected)", "; exit 99", "line\nbreak"]
        result = self.invoke("login", "--ROOTFS", str(self.root), "--", "/bin/sh", "-c",
                             'printf "<%s>\\n" "$@"', "marker", *payloads)
        self.good(result, "".join(f"<{value}>\n" for value in payloads))

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
        self.good(self.invoke("list"), "Ubuntu\n")
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
                 ("login", "--rootfs", str(self.base / "missing")), ("list", "extra")]
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

    def test_frontend_relocation_and_help(self):
        renamed = self.base / "pdn"
        shutil.copy2(BINARY, renamed)
        for args in [(), ("--HELP",), ("-H",), ("HeLp",), ("login", "--help"), ("login", "-h")]:
            result = self.invoke(*args, binary=renamed)
            self.good(result)
            self.assertIn("pdn login", result.stdout)
        self.assertEqual(self.invoke("install", "ubuntu", binary=renamed).returncode, 2)
        for args in [("VeRsIoN",), ("--version",), ("proot", "--version")]:
            result = self.invoke(*args, binary=renamed)
            self.good(result)
            self.assertIn("proot-distro-nolib 0.2.0", result.stdout)
            self.assertIn("Copyright (C) 2015 STMicroelectronics", result.stdout)
        self.good(self.invoke("login", "Ubuntu", "--", "/bin/sh", "-c", "echo relocated", binary=renamed), "relocated\n")

    def test_host_shell_hooks_not_loaded(self):
        hook = self.base / "hook.sh"
        hook.write_text("exit 93\n")
        self.env.update(ENV=str(hook), BASH_ENV=str(hook), PROOT_NO_SECCOMP="1")
        self.good(self.invoke("login", "ubuntu", "--", "/bin/sh", "-c", 'echo "${ENV-unset}:${BASH_ENV-unset}"'), "unset:unset\n")


if __name__ == "__main__":
    unittest.main()
