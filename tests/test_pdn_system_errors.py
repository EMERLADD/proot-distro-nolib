import errno
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

import test_pdn as fixture


class InjectedLockErrors(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = tempfile.TemporaryDirectory(prefix="pdn-system-fixture-", dir=fixture.PROJECT / "build")
        cls.addClassCleanup(cls.shared.cleanup)
        directory = Path(cls.shared.name)
        source = directory / "fault.c"
        source.write_text('''#include <errno.h>
#include <stdlib.h>
int pdn_fixture_flock(int fd, int operation) {
    (void)fd; (void)operation;
    errno = atoi(getenv("PDN_FIXTURE_ERRNO"));
    return -1;
}
int proot_main(int argc, char *const argv[]) {
    (void)argc; (void)argv;
    return 0;
}
''')
        ndk = Path(os.environ["NDK_PATH"])
        toolchain, = ndk.glob("toolchains/llvm/prebuilt/*")
        resource, = (toolchain / "lib/clang").iterdir()
        compiler = os.environ.get("CC", "clang")
        flags = ["--target=aarch64-linux-android24", f"--sysroot={toolchain / 'sysroot'}",
                 f"-resource-dir={resource}", "-D_GNU_SOURCE", "-Dflock=pdn_fixture_flock"]
        if "-fno-termux-rpath" in subprocess.check_output([compiler, "--help"], text=True):
            flags.append("-fno-termux-rpath")
        if os.environ.get("PDN_COVERAGE"):
            flags += ["-fprofile-instr-generate", "-fcoverage-mapping"]
            runtime = subprocess.check_output([compiler, "-print-resource-dir"], text=True).strip()
            flags += [f"{runtime}/lib/linux/libclang_rt.profile-aarch64-android.a"]
        cls.harness = directory / "pdn"
        native = fixture.PROJECT / "src/proot/src/cli"
        subprocess.run([compiler, *flags, str(source),
                        *(str(native / name) for name in ("pdn.c", "pdn_config.c", "pdn_remove.c", "pdn_events.c")),
                        f"-I{fixture.PROJECT / 'src/proot/src'}", "-o", str(cls.harness)], check=True)
        if os.environ.get("PDN_COVERAGE"):
            shutil.copy2(cls.harness, fixture.PROJECT / "build/pdn-system-coverage-harness")

    setUp = fixture.PdnTests.setUp
    invoke_events = fixture.PdnTests.invoke_events

    def invoke(self, *args):
        return fixture.PdnTests.invoke(self, *args, binary=self.harness)

    def test_injected_non_contention_flock_errno_is_retained(self):
        for value in (errno.ENOLCK, errno.EIO, errno.EINTR):
            self.env["PDN_FIXTURE_ERRNO"] = str(value)
            for args in (("exec", "ubuntu", "--", "/bin/sh"),
                         ("config", "ubuntu", "--show"), ("uninstall", "ubuntu", "--yes")):
                with self.subTest(value=value, command=args[0]):
                    result, events = self.invoke_events(*args)
                    self.assertEqual(result.returncode, 2, result.stderr)
                    self.assertEqual(events[-1]["code"], "lock_failed")
                    self.assertIn(f"errno={value}", events[-1]["message"])
                    self.assertTrue(events[-1]["suggestion"])
                    self.assertTrue(self.root.exists())


if __name__ == '__main__':
    unittest.main()
