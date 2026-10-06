import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[1]
BINARY = Path(os.environ.get(
    "PROOT_NOLIB_BINARY",
    PROJECT / "build/proot-distro-nolib/arm64/proot-distro-nolib",
)).resolve()


class ProotNolibTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        ndk = Path(os.environ["NDK_PATH"])
        toolchain, = ndk.glob("toolchains/llvm/prebuilt/*")
        resource, = (toolchain / "lib/clang").iterdir()
        compiler = os.environ.get("CC", "clang")
        flags = ["--target=aarch64-linux-android24", f"--sysroot={toolchain / 'sysroot'}", f"-resource-dir={resource}"]
        if "-fno-termux-rpath" in subprocess.check_output([compiler, "--help"], text=True):
            flags.append("-fno-termux-rpath")
        cls.probe = PROJECT / "build/proot-distro-nolib/tests/probe"
        cls.probe.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([compiler, *flags, str(PROJECT / "tests/proot_nolib_probe.c"), "-o", str(cls.probe)], check=True)

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="nolib-test-", dir=PROJECT / "build")
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name)
        self.rootfs = self.base / "rootfs"
        (self.rootfs / "bin").mkdir(parents=True)
        self.tmp = self.base / "tmp"
        self.tmp.mkdir()
        shutil.copy2(
            PROJECT / "android/proot-engine/src/main/jniLibs/arm64-v8a/libbusybox.so",
            self.rootfs / "bin/busybox",
        )
        (self.rootfs / "bin/sh").symlink_to("busybox")
        self.env = {"PATH": "/system/bin", "PROOT_NO_SECCOMP": "1", "TMPDIR": str(self.tmp)}
        if "LLVM_PROFILE_FILE" in os.environ:
            self.env["LLVM_PROFILE_FILE"] = os.environ["LLVM_PROFILE_FILE"]

    def invoke(self, args, env=None):
        return subprocess.run(
            [str(BINARY), *args], env=self.env if env is None else env,
            capture_output=True, text=True, timeout=20,
        )

    def login(self, command, extra=()):
        return self.invoke([
            "-0", "--kernel-release=6.17.0-pr", "-r", str(self.rootfs),
            "-b", "/dev", "-b", "/proc", "-w", "/", *extra,
            "/bin/sh", "-c", command,
        ])

    def assert_login(self):
        result = self.login("/bin/busybox id -u; /bin/busybox uname -r; echo ok > /result; /bin/busybox cat /result")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "0\n6.17.0-pr\nok\n")
        self.assertEqual((self.rootfs / "result").read_text(), "ok\n")

    def test_elf_has_only_system_dependencies(self):
        dynamic = subprocess.check_output(["readelf", "-d", str(BINARY)], text=True)
        self.assertNotRegex(dynamic, "RPATH|RUNPATH")
        needed = set(re.findall(r"\(NEEDED\).*?\[(.*?)\]", dynamic))
        self.assertEqual(needed, {"libc.so", "libdl.so"})
        for name in (BINARY, BINARY.parent / "proot-loader"):
            self.assertNotRegex(name.read_bytes().lower(), rb"termux|/data/data/|/data/user/|/home/")

    def test_help_and_version_without_host_environment(self):
        for option in ("--help", "--version", "-V", "--about"):
            env = {"PATH": "/system/bin"}
            if "LLVM_PROFILE_FILE" in self.env:
                env["LLVM_PROFILE_FILE"] = self.env["LLVM_PROFILE_FILE"]
            result = self.invoke([option], env=env)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("proot-distro-nolib 0.3.1", result.stdout)
            self.assertIn("Based on PRoot 5.4.0-pr.", result.stdout)
            self.assertIn("Copyright (C) 2015 STMicroelectronics, licensed under GPL v2 or later.", result.stdout)
            if option == "--help":
                self.assertIn("proot-distro-nolib [option] ... [command]", result.stdout)
                self.assertIn("--rootfs", result.stdout)
            else:
                logo, version, _ = result.stdout.split("\n\n", 2)
                self.assertEqual(len(logo.splitlines()), 5)
                self.assertEqual(version, "proot-distro-nolib 0.3.1")

    def test_login_uses_tmpdir(self):
        self.assert_login()

    def test_explicit_temp_overrides_invalid_tmpdir(self):
        self.env["PROOT_TMP_DIR"] = str(self.tmp)
        self.env["TMPDIR"] = str(self.base / "missing")
        self.assert_login()

    def test_empty_explicit_temp_uses_tmpdir(self):
        self.env["PROOT_TMP_DIR"] = ""
        self.assert_login()

    def test_invalid_explicit_temp_is_not_silently_ignored(self):
        self.env["PROOT_TMP_DIR"] = str(self.base / "missing")
        result = self.login("echo unexpected")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn(str(self.base / "missing"), result.stderr)
        self.assertNotIn("unexpected", result.stdout)

    def test_default_temp_has_no_app_path(self):
        self.env.pop("TMPDIR")
        self.env["PROOT_TMP_DIR"] = ""
        result = self.login("echo ok")
        self.assertNotIn("canonicalize /data/data/", result.stderr)
        if not Path("/tmp").is_dir():
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("/tmp", result.stderr)
        else:
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_external_loader(self):
        self.env["PROOT_LOADER"] = str(BINARY.parent / "proot-loader")
        self.assert_login()

    def test_relocated_binary_and_temp_path_with_spaces(self):
        relocated = self.base / "another directory"
        relocated.mkdir()
        binary = relocated / "engine"
        shutil.copy2(BINARY, binary)
        env = dict(self.env, TMPDIR=str(relocated))
        result = subprocess.run(
            [str(binary), "-0", "-r", str(self.rootfs), "-w", "/", "/bin/sh", "-c", "echo relocated"],
            env=env, capture_output=True, text=True, timeout=20,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "relocated\n")

    def test_missing_guest_command(self):
        result = self.invoke(["-r", str(self.rootfs), "-w", "/", "/missing-command"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("/missing-command", result.stderr)
        self.assertNotIn("termux-exec", result.stderr.lower())

    def test_shell_exit_status(self):
        self.assertEqual(self.login("exit 37").returncode, 37)

    def test_empty_tmpdir_uses_default(self):
        self.env["TMPDIR"] = ""
        result = self.login("echo ok")
        if not Path("/tmp").is_dir():
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("canonicalize /tmp", result.stderr)
        else:
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_port_mapping_messages(self):
        for family in ("ipv4", "ipv6"):
            with self.subTest(family=family):
                result = self.invoke(["-p", str(self.probe), family])
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("bound=3023", result.stdout)
                self.assertIn("outside PRoot", result.stdout)
                self.assertNotIn("Termux", result.stdout)

    def test_sigsys_logging_uses_stderr(self):
        result = self.invoke(["-v", "1", str(self.probe), "sigsys"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("SIGSYS handled", result.stdout)
        self.assertIn("SIGSYS: pid=", result.stderr)

    def test_sigsys_logging_is_quiet_by_default(self):
        result = self.invoke([str(self.probe), "sigsys"])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("SIGSYS handled", result.stdout)
        self.assertNotIn("SIGSYS:", result.stderr)


if __name__ == "__main__":
    unittest.main()
