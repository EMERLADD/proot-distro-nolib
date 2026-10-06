import fcntl
import hashlib
import http.server
import io
import os
from pathlib import Path
import signal
import ssl
import subprocess
import tarfile
import tempfile
import threading
import time
import unittest

PROJECT = Path(__file__).resolve().parents[1]
BINARY = Path(os.environ.get("PROOT_NOLIB_BINARY", PROJECT / "build/proot-distro-nolib/arm64/proot-distro-nolib")).resolve()
DEPS = PROJECT / "build/proot-distro-nolib/deps/install"


def make_archive(path, entries):
    with tarfile.open(path, "w:gz") as archive:
        for name, kind, data in entries:
            item = tarfile.TarInfo(name)
            item.mode = 0o755
            if kind == "file":
                item.size = len(data)
                archive.addfile(item, io.BytesIO(data))
            else:
                item.type = {"dir": tarfile.DIRTYPE, "link": tarfile.SYMTYPE,
                             "hardlink": tarfile.LNKTYPE, "fifo": tarfile.FIFOTYPE}[kind]
                item.linkname = data
                archive.addfile(item)


class InstallTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = tempfile.TemporaryDirectory(prefix="pdn-install-fixtures-", dir=PROJECT / "build")
        cls.addClassCleanup(cls.shared.cleanup)
        cls.fixtures = Path(cls.shared.name)
        cls.archive = cls.fixtures / "alpine.tar.gz"
        make_archive(cls.archive, [("bin", "dir", ""), ("bin/tool", "file", b"guest\n"),
                                  ("bin/sh", "link", "tool"), ("etc", "dir", ""), ("etc/apk", "dir", "")])
        digest = hashlib.sha256(cls.archive.read_bytes()).hexdigest()
        source = cls.fixtures / "harness.c"
        source.write_text(f'''#define ALPINE_SIZE {cls.archive.stat().st_size}
#define ALPINE_SHA256 "{digest}"
#define ALPINE_MIRRORS {{"tuna", "https://mirrors.tuna.tsinghua.edu.cn/alpine", getenv("TEST_URL")}}, {{"ustc", "https://mirrors.ustc.edu.cn/alpine", getenv("TEST_URL_2")}}
#include "{PROJECT / 'src/proot/src/cli/pdn_install.c'}"
char *pdn_rootfs_base(void) {{
    const char *base = getenv("PDN_ROOTFS_DIR");
    return base ? strdup(base) : NULL;
}}
int main(int argc, char **argv) {{
    if (argc == 2 && !strcmp(argv[1], "mirrors")) return pdn_mirrors();
    if (argc == 3 && !strcmp(argv[1], "extract")) return extract(argv[2]) != 0;
    if (argc == 3 && !strcmp(argv[1], "verify")) return verify(argv[2]) != 0;
    if (argc == 4 && !strcmp(argv[1], "download")) return download(argv[2], argv[3]) != 0;
    if (argc == 2 && !strcmp(argv[1], "configure")) return configure("https://mirrors.tuna.tsinghua.edu.cn/alpine") != 0;
    if (argc == 3 && !strcmp(argv[1], "mirror")) return pdn_install(NULL, argv[2]);
    return pdn_install(argc == 2 ? argv[1] : NULL, NULL);
}}
''')
        ndk = Path(os.environ["NDK_PATH"])
        toolchain, = ndk.glob("toolchains/llvm/prebuilt/*")
        resource, = (toolchain / "lib/clang").iterdir()
        cls.harness = cls.fixtures / "harness"
        compiler = os.environ.get("CC", "clang")
        flags = ["--target=aarch64-linux-android24", f"--sysroot={toolchain / 'sysroot'}", f"-resource-dir={resource}", "-D_GNU_SOURCE"]
        if "-fno-termux-rpath" in subprocess.check_output([compiler, "--help"], text=True):
            flags.append("-fno-termux-rpath")
        if os.environ.get("PDN_COVERAGE"):
            flags += ["-fprofile-instr-generate", "-fcoverage-mapping"]
            runtime = subprocess.check_output([compiler, "-print-resource-dir"], text=True).strip()
            flags += [f"{runtime}/lib/linux/libclang_rt.profile-aarch64-android.a"]
        subprocess.run([compiler, *flags, str(source), f"-I{DEPS / 'include'}", f"-L{DEPS / 'lib'}",
                        "-lcurl", "-larchive", "-lmbedtls", "-lmbedx509", "-lmbedcrypto", "-lz",
                        "-o", str(cls.harness)], check=True)
        if os.environ.get("PDN_COVERAGE"):
            import shutil
            shutil.copy2(cls.harness, PROJECT / "build/pdn-install-coverage-harness")
        cls.cert = cls.fixtures / "cert.pem"
        key = cls.fixtures / "key.pem"
        certgen = cls.fixtures / "certgen"
        subprocess.run([compiler, *flags, str(PROJECT / "tests/pdn_test_cert.c"),
                        f"-I{DEPS / 'include'}", f"-L{DEPS / 'lib'}", "-lmbedtls", "-lmbedx509",
                        "-lmbedcrypto", "-o", str(certgen)], check=True)
        subprocess.run([str(certgen), str(key), str(cls.cert)], check=True)
        payload = cls.archive.read_bytes()
        cls.requests = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                cls.requests.append(self.path)
                if self.path == "/slow":
                    self.send_response(200)
                    self.send_header("Content-Length", str(len(payload)))
                    self.end_headers()
                    time.sleep(3)
                    return
                if self.path == "/redirect":
                    self.send_response(302)
                    self.send_header("Location", "/archive")
                    self.end_headers()
                    return
                if self.path == "/missing":
                    self.send_error(404)
                    return
                data = payload + b"too long" if self.path == "/large" else payload
                if self.path == "/corrupt":
                    data = payload[:-1] + bytes([payload[-1] ^ 1])
                if self.path == "/short":
                    data = payload[:-17]
                self.send_response(200)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

        cls.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cls.cert, key)
        cls.server.socket = context.wrap_socket(cls.server.socket, server_side=True)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.addClassCleanup(cls.server.server_close)
        cls.addClassCleanup(cls.server.shutdown)
        cls.url = f"https://localhost:{cls.server.server_port}"

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pdn-install-test-", dir=PROJECT / "build")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.roots = self.base / "new roots" / "linux"
        self.requests.clear()
        self.env = {"PATH": "/system/bin", "PDN_ROOTFS_DIR": str(self.roots),
                    "PDN_CA_BUNDLE": str(self.cert), "TEST_URL": self.url + "/archive", "TEST_URL_2": self.url + "/missing"}
        if "LLVM_PROFILE_FILE" in os.environ:
            self.env["LLVM_PROFILE_FILE"] = os.environ["LLVM_PROFILE_FILE"]

    def run_harness(self, *args, cwd=None):
        return subprocess.run([str(self.harness), *map(str, args)], env=self.env,
                              cwd=cwd or self.base, capture_output=True, text=True, timeout=15)

    def assert_clean(self):
        self.assertFalse((self.roots / "alpine").exists())
        self.assertFalse(list(self.roots.glob(".pdn-alpine-*")))

    def test_local_install_and_preservation(self):
        result = self.run_harness(self.archive)
        self.assertEqual(result.returncode, 0, result.stderr)
        root = self.roots / "alpine"
        self.assertEqual((root / "bin/tool").read_bytes(), b"guest\n")
        self.assertEqual(os.readlink(root / "bin/sh"), "tool")
        self.assertEqual((root / "bin/tool").stat().st_mode & 0o777, 0o755)
        self.assertIn("tuna.tsinghua.edu.cn/alpine/v3.24", (root / "etc/apk/repositories").read_text())
        self.assertIn("nameserver", (root / "etc/resolv.conf").read_text())
        self.assertFalse(list(self.roots.glob(".pdn-alpine-*")))
        (root / "keep").write_text("existing")
        self.assertNotEqual(self.run_harness(self.archive).returncode, 0)
        self.assertEqual((root / "keep").read_text(), "existing")

    def test_https_install(self):
        result = self.run_harness()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.roots / "alpine/bin/tool").exists())

    def test_mirror_fallback(self):
        self.env["TEST_URL_2"] = self.url + "/archive"
        for endpoint in ("/missing", "/corrupt", "/short", "/large"):
            with self.subTest(endpoint=endpoint):
                self.env["TEST_URL"] = self.url + endpoint
                self.env["PDN_ROOTFS_DIR"] = str(self.base / endpoint[1:])
                result = self.run_harness()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("Mirror tuna failed", result.stderr)
                root = Path(self.env["PDN_ROOTFS_DIR"]) / "alpine"
                self.assertIn("mirrors.ustc.edu.cn", (root / "etc/apk/repositories").read_text())
                self.assertEqual((root / "bin/tool").read_bytes(), b"guest\n")

    def test_manual_mirror(self):
        self.env["TEST_URL_2"] = self.url + "/archive"
        result = self.run_harness("mirror", "USTC")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("Trying mirror: tuna", result.stdout)
        self.assertEqual(self.requests, ["/archive"])
        self.env["PDN_ROOTFS_DIR"] = str(self.base / "failed")
        self.env["TEST_URL_2"] = self.url + "/missing"
        result = self.run_harness("mirror", "ustc")
        self.assertNotEqual(result.returncode, 0)
        self.assertNotIn("Trying mirror: tuna", result.stdout)
        self.assertNotEqual(self.run_harness("mirror", "unknown").returncode, 0)

    def test_mirror_listing_and_options(self):
        listing = self.run_harness("mirrors")
        self.assertEqual(listing.returncode, 0, listing.stderr)
        self.assertIn("tuna", listing.stdout)
        self.assertIn("ustc", listing.stdout)
        result = subprocess.run([str(BINARY), "MiRrOrS"], env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        for name in ("tuna", "ustc", "nju", "official", "dotsrc"):
            self.assertIn(name, result.stdout)
        for args in [("mirrors", "extra"), ("install", "alpine", "--mirror"),
                     ("install", "alpine", "--mirror", "unknown"),
                     ("install", "alpine", "--archive", "/missing", "--mirror", "tuna")]:
            result = subprocess.run([str(BINARY), *args], env=self.env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)

    def test_download_validation(self):
        output = self.base / "download"
        for endpoint in ("/archive", "/redirect"):
            result = self.run_harness("download", self.url + endpoint, output)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(output.read_bytes(), self.archive.read_bytes())
        for endpoint in ("/missing", "/large"):
            self.assertNotEqual(self.run_harness("download", self.url + endpoint, output).returncode, 0)
        self.assertNotEqual(self.run_harness("download", "http://localhost/", output).returncode, 0)
        self.assertNotEqual(self.run_harness("download", self.url + "/archive", self.base / "absent/file").returncode, 0)
        self.assertNotEqual(self.run_harness("download", self.url.replace("localhost", "127.0.0.1") + "/archive", output).returncode, 0)
        self.env.pop("PDN_CA_BUNDLE")
        self.assertNotEqual(self.run_harness("download", self.url + "/archive", output).returncode, 0)

    def test_invalid_archives_and_cleanup(self):
        wrong = self.base / "wrong.tar.gz"
        wrong.write_bytes(b"bad")
        self.assertNotEqual(self.run_harness(wrong).returncode, 0)
        self.assert_clean()
        wrong.write_bytes(b"x" * self.archive.stat().st_size)
        result = self.run_harness(wrong)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("SHA256 mismatch", result.stderr)
        self.assert_clean()
        self.env["TEST_URL"] = self.url + "/missing"
        self.assertNotEqual(self.run_harness().returncode, 0)
        self.assert_clean()

    def test_install_lock_and_case_collision(self):
        self.roots.mkdir(parents=True)
        with (self.roots / ".pdn-install.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            result = self.run_harness(self.archive)
            self.assertIn("lock", result.stderr)
            self.assert_clean()
        (self.roots / "AlPiNe").mkdir()
        self.assertIn("already exists", self.run_harness(self.archive).stderr)

    def test_input_errors(self):
        self.env.pop("PDN_ROOTFS_DIR")
        self.assertNotEqual(self.run_harness(self.archive).returncode, 0)
        self.env["PDN_ROOTFS_DIR"] = str(self.roots)
        self.assertNotEqual(self.run_harness(self.base / "missing").returncode, 0)
        bad = self.base / "file"
        bad.write_text("x")
        self.env["PDN_ROOTFS_DIR"] = str(bad / "child")
        self.assertNotEqual(self.run_harness(self.archive).returncode, 0)

    def test_interrupt_cleanup(self):
        self.env["TEST_URL"] = self.url + "/slow"
        proc = subprocess.Popen([str(self.harness)], env=self.env, cwd=self.base,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.addCleanup(lambda: proc.kill() if proc.poll() is None else None)
        self.assertIn("Installing", proc.stdout.readline())
        time.sleep(0.15)
        proc.send_signal(signal.SIGINT)
        proc.communicate(timeout=10)
        self.assertEqual(proc.returncode, 130)
        self.assertNotIn("/missing", self.requests)
        self.assert_clean()

    def test_extraction_boundaries(self):
        attacks = [[("../escape", "file", b"bad")], [("/absolute", "file", b"bad")],
                   [("link", "link", str(self.base)), ("link/escape", "file", b"bad")],
                   [("hard", "hardlink", "../outside")], [("pipe", "fifo", "")]]
        for index, entries in enumerate(attacks):
            archive = self.base / f"attack{index}.tar.gz"
            target = self.base / f"extract{index}"
            target.mkdir()
            make_archive(archive, entries)
            self.assertNotEqual(self.run_harness("extract", archive, cwd=target).returncode, 0)
            self.assertFalse((self.base / "escape").exists())
        bad = self.base / "corrupt"
        bad.write_text("not an archive")
        self.assertNotEqual(self.run_harness("extract", bad).returncode, 0)

    def test_config_rejects_symlinks(self):
        self.assertNotEqual(self.run_harness("configure").returncode, 0)
        (self.base / "etc").mkdir()
        self.assertNotEqual(self.run_harness("configure").returncode, 0)
        (self.base / "etc/apk").mkdir()
        outside = self.base / "outside"
        outside.write_text("keep")
        (self.base / "etc/apk/repositories").symlink_to(outside)
        self.assertNotEqual(self.run_harness("configure").returncode, 0)
        self.assertEqual(outside.read_text(), "keep")

    def test_release_cli_arguments(self):
        for args in [("install",), ("install", "ubuntu"), ("install", "alpine", "extra"),
                     ("install", "alpine", "--archive"), ("INSTALL", "ALPINE", "--ARCHIVE", "/missing")]:
            result = subprocess.run([str(BINARY), *args], env=self.env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
        result = subprocess.run([str(BINARY), "install", "--help"], env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn("install alpine", result.stdout)


if __name__ == "__main__":
    unittest.main()
