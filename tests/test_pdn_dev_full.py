import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[1]
BINARY = Path(os.environ.get(
    "PROOT_NOLIB_BINARY",
    PROJECT / "build/proot-distro-nolib/arm64/proot-distro-nolib",
)).resolve()


class DevFullTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        ndk = Path(os.environ["NDK_PATH"])
        toolchain, = ndk.glob("toolchains/llvm/prebuilt/*")
        resource, = (toolchain / "lib/clang").iterdir()
        compiler = os.environ.get("CC", "clang")
        flags = ["--target=aarch64-linux-android24", f"--sysroot={toolchain / 'sysroot'}", f"-resource-dir={resource}"]
        if "-fno-termux-rpath" in subprocess.check_output([compiler, "--help"], text=True):
            flags.append("-fno-termux-rpath")
        cls.probe = PROJECT / "build/proot-distro-nolib/tests/dev-full-probe"
        cls.probe.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run([compiler, *flags, str(PROJECT / "tests/pdn_dev_full_probe.c"), "-o", str(cls.probe)], check=True)

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="dev-full-test-", dir=PROJECT / "build")
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name)
        self.tmp = self.base / "tmp"
        self.tmp.mkdir()
        self.work = self.base / "work"
        self.work.mkdir()
        self.rootfs = self.base / "rootfs"
        (self.rootfs / "bin").mkdir(parents=True)
        shutil.copy2(PROJECT / "android/proot-engine/src/main/jniLibs/arm64-v8a/libbusybox.so", self.rootfs / "bin/busybox")
        (self.rootfs / "bin/sh").symlink_to("busybox")
        self.env = {
            "PATH": "/system/bin", "TMPDIR": str(self.tmp),
            "PROOT_TMP_DIR": str(self.tmp), "PROOT_EMULATE_DEV_FULL": "1",
        }
        if "LLVM_PROFILE_FILE" in os.environ:
            self.env["LLVM_PROFILE_FILE"] = os.environ["LLVM_PROFILE_FILE"]

    def invoke(self, arguments, disabled, value="1"):
        env = dict(self.env)
        if value is None:
            env.pop("PROOT_EMULATE_DEV_FULL")
        else:
            env["PROOT_EMULATE_DEV_FULL"] = value
        if disabled:
            env["PROOT_NO_SECCOMP"] = "1"
        result = subprocess.run(
            [str(BINARY), "-0", "-b", "/dev", "-b", "/proc", *arguments],
            env=env, capture_output=True, text=True, timeout=30,
        )
        self.assertFalse(list(self.tmp.glob("pdn-full*")), result.stderr)
        return result

    def check_probe(self, mode):
        for disabled in (True, False):
            with self.subTest(disabled=disabled):
                result = self.invoke(["-w", str(self.work), str(self.probe), mode], disabled)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "passed\n")

    def test_scalar_reads_writes_access_modes_and_bad_buffers(self):
        self.check_probe("scalar")

    @unittest.skipIf(Path("/dev/full").exists(), "vector bounds assert the emulation contract")
    def test_vectors_and_invalid_iovecs(self):
        self.check_probe("vectors")

    @unittest.skipIf(Path("/dev/full").exists(), "native device has no emulation syscall restrictions")
    def test_unsupported_transfers(self):
        self.check_probe("transfers")

    def test_descriptor_transfer_preserves_device(self):
        self.check_probe("descriptor-transfer")

    def test_positioned_io_and_seek(self):
        self.check_probe("positioned")

    def test_device_metadata_and_unsupported_operations(self):
        self.check_probe("metadata")

    def test_duplicate_overwrite_and_descriptor_reuse(self):
        self.check_probe("duplicate")

    def test_fork_exec_inheritance_and_close_on_exec(self):
        self.check_probe("inherit")

    def test_relative_open_symlink_and_zero_remains_normal(self):
        self.check_probe("paths")

    @unittest.skipIf(Path("/dev/full").exists(), "native /dev/full exists")
    def test_only_exact_one_enables_emulation(self):
        for disabled in (True, False):
            for value in (None, "0", "", "true", "01", "2"):
                with self.subTest(disabled=disabled, value=value):
                    result = self.invoke(["-w", str(self.work), str(self.probe), "disabled"], disabled, value)
                    self.assertEqual(result.returncode, 0, result.stderr)
                    self.assertEqual(result.stdout, "passed\n")

    def test_explicit_bind_overrides_emulated_device(self):
        override = self.base / "override"
        for disabled in (True, False):
            with self.subTest(disabled=disabled):
                override.write_bytes(b"")
                result = self.invoke(["-b", f"{override}:/dev/full", str(self.probe), "override"], disabled)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(override.read_bytes(), b"override")
                result = self.invoke(["-b", "/dev/zero:/dev/full", str(self.probe), "zero-override"], disabled)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(result.stdout, "passed\n")

    def test_minimal_guest_busybox_observes_full_device(self):
        command = (
            '/bin/busybox test -c /dev/full || exit 11; '
            '/bin/busybox dd if=/dev/full of=/zero bs=32 count=1 2>/read-error || exit 12; '
            '/bin/busybox cmp /zero /expected || exit 13; '
            'echo payload > /dev/full 2>/write-error; result=$?; '
            '/bin/busybox cat /write-error; test "$result" -ne 0 || exit 14; '
            '/bin/busybox grep -q "No space left on device" /write-error || exit 15'
        )
        (self.rootfs / "expected").write_bytes(bytes(32))
        for disabled in (True, False):
            with self.subTest(disabled=disabled):
                result = self.invoke(["-r", str(self.rootfs), "-w", "/", "/bin/sh", "-c", command], disabled)
                self.assertEqual(result.returncode, 0, result.stderr + result.stdout)
                self.assertIn("No space left on device", result.stdout)
                self.assertEqual((self.rootfs / "zero").read_bytes(), bytes(32))


if __name__ == "__main__":
    unittest.main()
