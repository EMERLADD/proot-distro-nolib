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

from test_pdn_events import assert_events

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
                                  ("bin/sh", "link", "tool"), ("etc", "dir", ""), ("etc/apk", "dir", ""),
                                  ("etc/apt", "dir", ""), ("etc/apt/apt.conf.d", "dir", ""),
                                  ("etc/apt/apt.conf.d/docker-clean", "file", b"container cache hooks\n")])
        digest = hashlib.sha256(cls.archive.read_bytes()).hexdigest()
        source = cls.fixtures / "harness.c"
        source.write_text(f'''#define ALPINE_SIZE {cls.archive.stat().st_size}
#define ALPINE_SHA256 "{digest}"
#define ALPINE_MIRRORS {{"tuna", "https://mirrors.tuna.tsinghua.edu.cn/alpine", getenv("TEST_URL")}}, {{"ustc", "https://mirrors.ustc.edu.cn/alpine", getenv("TEST_URL_2")}}
#define UBUNTU_SIZE ALPINE_SIZE
#define UBUNTU_SHA256 ALPINE_SHA256
#define UBUNTU_MIRRORS ALPINE_MIRRORS
#define DEBIAN_SIZE ALPINE_SIZE
#define DEBIAN_SHA256 ALPINE_SHA256
#define DEBIAN_MIRRORS ALPINE_MIRRORS
#define ARCH_SIZE ALPINE_SIZE
#define ARCH_SHA256 ALPINE_SHA256
#define ARCH_MIRRORS ALPINE_MIRRORS
#include "{PROJECT / 'src/proot/src/cli/pdn_events.h'}"
#include <curl/curl.h>
#include <stdlib.h>
#include <stdio.h>
#include <errno.h>
#include <fcntl.h>
#include <stdarg.h>
#include <string.h>
#include <mbedtls/md.h>
#include <archive.h>
#include <sys/file.h>
static int test_flock(int fd, int operation) {{
    const char *fault = getenv("TEST_FLOCK_ERRNO");
    if (fault) {{ errno = atoi(fault); return -1; }}
    return flock(fd, operation);
}}
static CURLcode test_curl_perform(CURL *curl) {{
    const char *fault = getenv("TEST_CURL_CODE");
    return fault ? (CURLcode)atoi(fault) : curl_easy_perform(curl);
}}
static size_t test_fwrite(const void *data, size_t size, size_t count, FILE *file) {{
    const char *fault = getenv("TEST_WRITE_ERRNO");
    if (fault) {{ errno = atoi(fault); return 0; }}
    return fwrite(data, size, count, file);
}}
static int test_fclose(FILE *file) {{
    int result = fclose(file);
    const char *fault = getenv("TEST_CLOSE_ERRNO");
    if (!result && fault) {{ errno = atoi(fault); return EOF; }}
    return result;
}}
static int test_openat(int parent, const char *name, int flags, ...) {{
    mode_t mode = 0;
    if (flags & O_CREAT) {{ va_list args; va_start(args, flags); mode = va_arg(args, int); va_end(args); }}
    if (getenv("TEST_CONFIG_FAIL") && !strcmp(name, "repositories")) {{ errno = EIO; return -1; }}
    return openat(parent, name, flags, mode);
}}
#define curl_easy_perform test_curl_perform
#define fwrite test_fwrite
#define fclose test_fclose
#define openat test_openat
#define flock test_flock
#define mbedtls_md_file(info, path, digest) (getenv("TEST_HASH_FAIL") ? -1 : mbedtls_md_file(info, path, digest))
#define rename(source, destination) (getenv("TEST_PUBLISH_FAIL") ? (errno = EIO, -1) : rename(source, destination))
#define archive_write_data_block(out, data, size, offset) (getenv("TEST_EXTRACT_FAIL") ? (archive_set_error(out, EIO, "injected extraction write failure"), ARCHIVE_FATAL) : archive_write_data_block(out, data, size, offset))
#include "{PROJECT / 'src/proot/src/cli/pdn_install.c'}"
int pdn_login(int argc, char *const argv[]) {{
    if (getenv("TEST_INIT_SLOW")) sleep(5);
#ifdef PDN_TEST_COVERAGE
    extern int __llvm_profile_write_file(void);
    __llvm_profile_write_file();
#endif
    return getenv("TEST_INIT_FAIL") ? 1 : 0;
}}
char *pdn_rootfs_base(void) {{
    const char *base = getenv("PDN_ROOTFS_DIR");
    return base ? strdup(base) : NULL;
}}
static int harness_main(int argc, char **argv) {{
    if (argc == 3 && !strcmp(argv[1], "root-error")) return rootfs_error("create", "/fixture/rootfs", atoi(argv[2])) != 0;
    const char *name = getenv("TEST_DISTRO");
    struct distro distro;
    if (!name) name = "alpine";
    if (find_distro(name, &distro) < 0) return pdn_install(name, NULL, NULL);
    if (argc == 2 && !strcmp(argv[1], "mirrors")) return pdn_mirrors(NULL);
    if (argc == 3 && !strcmp(argv[1], "mirrors")) return pdn_mirrors(argv[2]);
    if (argc == 2 && !strcmp(argv[1], "available")) return pdn_available();
    if (argc == 3 && !strcmp(argv[1], "extract-small")) {{ distro.extracted_limit = 1; return extract(&distro, argv[2]) != 0; }}
    if (argc == 3 && !strcmp(argv[1], "extract")) return extract(&distro, argv[2]) != 0;
    if (argc == 3 && !strcmp(argv[1], "verify")) return verify(&distro, argv[2]) != 0;
    if (argc == 4 && !strcmp(argv[1], "download")) {{ if (getenv("TEST_TIMEOUT")) distro.timeout = 1; return download(&distro, argv[2], argv[3]) != 0; }}
    if (argc == 2 && !strcmp(argv[1], "configure")) return configure(&distro, &distro.mirrors[0]) != 0;
    if (argc == 3 && !strcmp(argv[1], "mirror")) return pdn_install(name, NULL, argv[2]);
    return pdn_install(name, argc == 2 ? argv[1] : NULL, NULL);
}}
int main(int argc, char **argv) {{
    int result;
    if (pdn_events_begin("install") != 0) return 2;
    result = harness_main(argc, argv);
    pdn_events_finish(result);
    return result;
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
            flags += ["-fprofile-instr-generate", "-fcoverage-mapping", "-DPDN_TEST_COVERAGE"]
            runtime = subprocess.check_output([compiler, "-print-resource-dir"], text=True).strip()
            flags += [f"{runtime}/lib/linux/libclang_rt.profile-aarch64-android.a"]
        subprocess.run([compiler, *flags, str(source), str(PROJECT / "src/proot/src/cli/pdn_events.c"), f"-I{DEPS / 'include'}", f"-L{DEPS / 'lib'}",
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

    def event_channel(self):
        path = self.base / "events.jsonl"
        path.write_bytes(b"")
        path.chmod(0o600)
        self.env.update(PDN_EVENT_FILE=str(path), PDN_OPERATION_ID="install-fixture_1")
        return path

    def event_records(self, path, result, outcome):
        return assert_events(self, path, "install", "install-fixture_1", result.returncode, outcome)

    def assert_error_code(self, path, result, code):
        self.assertNotEqual(result.returncode, 0, result.stderr)
        events = self.event_records(path, result, "manager_error")
        self.assertEqual(events[-1]["code"], code, events)
        return events

    def test_download_actual_https_error_categories(self):
        import socket
        with socket.socket() as endpoint:
            endpoint.bind(("127.0.0.1", 0))
            closed_port = endpoint.getsockname()[1]
        cases = [(self.url + "/missing", "http_error"),
                 (f"https://127.0.0.1:{closed_port}/", "connection_failed"),
                 (self.url.replace("localhost", "127.0.0.1") + "/archive", "tls_failed"),
                 (self.url + "/large", "archive_size_mismatch")]
        for url, code in cases:
            with self.subTest(code=code):
                path = self.event_channel()
                self.assert_error_code(path, self.run_harness("download", url, self.base / "download"), code)
        self.env["TEST_TIMEOUT"] = "1"
        path = self.event_channel()
        self.assert_error_code(path, self.run_harness("download", self.url + "/slow", self.base / "download"), "download_timeout")

    def test_download_injected_curl_categories(self):
        for injected, code in ((6, "resolution_failed"), (56, "download_failed")):
            with self.subTest(injected_curl_code=injected):
                self.env["TEST_CURL_CODE"] = str(injected)
                path = self.event_channel()
                self.assert_error_code(path, self.run_harness("download", self.url + "/archive", self.base / "download"), code)
                self.assertEqual(self.requests, [])

    def test_injected_io_hash_extract_publish_categories(self):
        for injected, code in ((13, "file_permission"), (30, "file_read_only"), (28, "storage_full"), (5, "file_io_failed"), (0, "file_io_failed")):
            with self.subTest(injected_write_errno=injected):
                self.env["TEST_WRITE_ERRNO"] = str(injected)
                path = self.event_channel()
                self.assert_error_code(path, self.run_harness("download", self.url + "/archive", self.base / "download"), code)
        self.env.pop("TEST_WRITE_ERRNO")
        for injected, code in ((28, "storage_full"), (5, "file_io_failed"), (0, "file_io_failed")):
            with self.subTest(injected_close_errno=injected):
                self.env["TEST_CLOSE_ERRNO"] = str(injected)
                path = self.event_channel()
                self.assert_error_code(path, self.run_harness("download", self.url + "/archive", self.base / "download"), code)
        self.env.pop("TEST_CLOSE_ERRNO")
        for fault, code in (("TEST_HASH_FAIL", "archive_hash_failed"),
                            ("TEST_EXTRACT_FAIL", "extraction_failed"),
                            ("TEST_PUBLISH_FAIL", "publish_failed")):
            with self.subTest(injected_fixture=fault):
                self.env[fault] = "1"
                path = self.event_channel()
                self.assert_error_code(path, self.run_harness(self.archive), code)
                self.assert_clean()
                self.env.pop(fault)

    def test_recovered_mirror_then_configuration_failure(self):
        self.env.update(TEST_URL=self.url + "/missing", TEST_URL_2=self.url + "/archive", TEST_CONFIG_FAIL="1")
        path = self.event_channel()
        events = self.assert_error_code(path, self.run_harness(), "rootfs_configuration_failed")
        self.assertTrue(any(event.get("code") == "http_error" for event in events))
        self.assertIn("/archive", self.requests)
        self.assert_clean()

    def test_local_archive_verification_categories(self):
        short = self.base / "short.tar.gz"
        short.write_bytes(self.archive.read_bytes()[:-1])
        corrupt = self.base / "corrupt.tar.gz"
        data = self.archive.read_bytes()
        corrupt.write_bytes(data[:-1] + bytes([data[-1] ^ 1]))
        for archive, code in ((self.base / "missing", "file_missing"), (short, "archive_size_mismatch"),
                              (corrupt, "archive_checksum_mismatch")):
            with self.subTest(code=code):
                path = self.event_channel()
                self.assert_error_code(path, self.run_harness(archive), code)
                self.assert_clean()

    def test_install_name_mirror_collision_and_lock_categories(self):
        self.env["TEST_DISTRO"] = "unknown"
        path = self.event_channel()
        self.assert_error_code(path, self.run_harness(), "distro_unknown")
        self.env.pop("TEST_DISTRO")
        path = self.event_channel()
        self.assert_error_code(path, self.run_harness("mirror", "unknown"), "mirror_invalid")
        self.roots.mkdir(parents=True)
        with (self.roots / ".pdn-install.lock").open("w") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            path = self.event_channel()
            self.assert_error_code(path, self.run_harness(self.archive), "operation_busy")
        (self.roots / "ALPINE").mkdir()
        path = self.event_channel()
        self.assert_error_code(path, self.run_harness(self.archive), "rootfs_exists")
        (self.roots / "ALPINE").rmdir()
        (self.roots / ".pdn-install.lock").unlink()
        (self.roots / ".pdn-install.lock").symlink_to(self.base / "missing")
        path = self.event_channel()
        self.assert_error_code(path, self.run_harness(self.archive), "lock_failed")

    def test_injected_non_contention_install_lock_errno(self):
        import errno
        for value in (errno.ENOLCK, errno.EIO, errno.EINTR):
            with self.subTest(injected_errno=value):
                self.env["TEST_FLOCK_ERRNO"] = str(value)
                path = self.event_channel()
                events = self.assert_error_code(path, self.run_harness(self.archive), "lock_failed")
                self.assertIn(f"errno={value}", events[-1]["message"])
                self.assert_clean()

    def test_injected_rootfs_system_errno_categories(self):
        import errno
        for value, code in ((errno.ENOSPC, "storage_full"), (errno.EDQUOT, "storage_full"),
                            (errno.ENOMEM, "out_of_memory"), (errno.EROFS, "directory_read_only")):
            with self.subTest(injected_errno=value):
                path = self.event_channel()
                self.assert_error_code(path, self.run_harness("root-error", str(value)), code)

    def test_extract_archive_error_categories(self):
        cases = [("unsafe", [("../outside", "file", b"x")], "archive_unsafe"),
                 ("unsupported", [("fifo", "fifo", "")], "archive_unsupported"),
                 ("limit", [("file", "file", b"xx")], "archive_limit_exceeded")]
        for name, entries, code in cases:
            with self.subTest(code=code):
                archive = self.base / (name + ".tar.gz")
                make_archive(archive, entries)
                target = self.base / name
                target.mkdir()
                path = self.event_channel()
                self.assert_error_code(path, self.run_harness("extract-small" if name == "limit" else "extract", archive, cwd=target), code)
        corrupt = self.base / "invalid.tar.gz"
        corrupt.write_bytes(b"not an archive")
        path = self.event_channel()
        self.assert_error_code(path, self.run_harness("extract", corrupt), "archive_corrupt")

    def test_actual_full_device_download(self):
        if not Path("/dev/full").exists():
            self.skipTest("/dev/full unavailable; no storage-full syscall exercised")
        path = self.event_channel()
        self.assert_error_code(path, self.run_harness("download", self.url + "/archive", "/dev/full"), "storage_full")

    def test_configuration_and_keyring_categories(self):
        path = self.event_channel()
        self.assert_error_code(path, self.run_harness("configure"), "file_missing")
        self.env.update(TEST_DISTRO="arch", TEST_INIT_FAIL="1")
        path = self.event_channel()
        self.assert_error_code(path, self.run_harness(self.archive), "keyring_initialization_failed")
        self.assertFalse((self.roots / "arch").exists())

    def test_local_install_events_phases(self):
        path = self.event_channel()
        result = self.run_harness(self.archive)
        self.assertEqual(result.returncode, 0, result.stderr)
        events = self.event_records(path, result, "success")
        stages = [event["stage"] for event in events if event["type"] == "stage"]
        required = ["verifying", "extracting", "configuring", "publishing"]
        self.assertEqual([stage for stage in stages if stage in required], required)
        self.assertTrue((self.roots / "alpine/bin/tool").exists())
        self.assertFalse(list(self.roots.glob(".pdn-alpine-*")))

    def test_mirror_fallback_events_final_result_authoritative(self):
        path = self.event_channel()
        self.env["TEST_URL"] = self.url + "/missing"
        self.env["TEST_URL_2"] = self.url + "/archive"
        result = self.run_harness()
        self.assertEqual(result.returncode, 0, result.stderr)
        events = self.event_records(path, result, "success")
        self.assertTrue(any(event["type"] == "error" for event in events))
        self.assertIn("/missing", self.requests)
        self.assertIn("/archive", self.requests)
        self.assertTrue((self.roots / "alpine/bin/tool").exists())

    def test_https_download_events_are_bounded(self):
        path = self.event_channel()
        result = self.run_harness()
        self.assertEqual(result.returncode, 0, result.stderr)
        events = self.event_records(path, result, "success")
        progress = [event for event in events if event["type"] == "progress"]
        self.assertTrue(progress)
        self.assertLessEqual(len(progress), 30)
        for event in progress:
            self.assertGreaterEqual(event["current"], 0)
            if "percent" in event:
                self.assertGreaterEqual(event["percent"], 0)
                self.assertLessEqual(event["percent"], 100)
            if event.get("total", -1) >= 0:
                self.assertLessEqual(event["current"], event["total"])

    def test_interrupted_install_events_after_cleanup(self):
        path = self.event_channel()
        self.env["TEST_URL"] = self.url + "/slow"
        proc = subprocess.Popen([str(self.harness)], env=self.env, cwd=self.base,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.addCleanup(lambda: proc.kill() if proc.poll() is None else None)
        self.assertIn("Installing", proc.stdout.readline())
        time.sleep(0.15)
        proc.send_signal(signal.SIGINT)
        proc.communicate(timeout=10)
        self.assertEqual(proc.returncode, 130)
        events = self.event_records(path, proc, "cancelled")
        self.assertEqual(events[-1]["exit_code"], 130)
        self.assert_clean()
        self.assertNotIn("/missing", self.requests)

    def test_invalid_channel_prevents_install_side_effects(self):
        path = self.base / "shared-events"
        path.write_bytes(b"")
        path.chmod(0o644)
        self.env.update(PDN_EVENT_FILE=str(path), PDN_OPERATION_ID="install-fixture_1")
        result = self.run_harness()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("event", result.stderr.lower())
        self.assertFalse(self.roots.exists())
        self.assertEqual(self.requests, [])
        self.assertEqual(path.read_bytes(), b"")

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

    def test_all_distros_include_official_source(self):
        domains = {
            "alpine": "https://dl-cdn.alpinelinux.org/alpine",
            "ubuntu": "https://cdimage.ubuntu.com",
            "debian": "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts",
            "arch": "https://fl.us.mirror.archlinuxarm.org",
        }
        for name, domain in domains.items():
            with self.subTest(name=name):
                result = subprocess.run([str(BINARY), "mirrors", name], env=self.env,
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                official = [line.split() for line in result.stdout.splitlines()
                            if line.split() and line.split()[0] == "official"]
                self.assertEqual(official, [["official", domain]])

    def test_debian_has_one_rootfs_source(self):
        result = subprocess.run([str(BINARY), "mirrors", "debian"], env=self.env,
                                capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        names = [line.split()[0] for line in result.stdout.splitlines()[1:] if line.strip()]
        self.assertEqual(names, ["official"])
        result = subprocess.run([str(BINARY), "install", "debian", "--mirror", "github"],
                                env=self.env, capture_output=True, text=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unknown mirror", result.stderr)
        self.assertFalse(self.roots.exists())

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
        wrong.write_bytes(b"x" * (self.archive.stat().st_size + 1))
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

    def test_rootfs_errors_show_path_source_and_reason(self):
        bad = self.base / "not a directory"
        bad.write_text("keep")
        for target, action in ((bad / "child", "create"), (bad, "enter")):
            with self.subTest(target=target):
                self.env["PDN_ROOTFS_DIR"] = str(target)
                result = self.run_harness(self.archive)
                self.assertNotEqual(result.returncode, 0)
                for text in (str(target), "Not a directory", "PDN_ROOTFS_DIR", action):
                    self.assertIn(text, result.stderr)
                self.assertNotIn("Installing", result.stdout)
        self.assertEqual(bad.read_text(), "keep")

    def test_home_root_error_explains_default_location(self):
        if os.access("/", os.W_OK) or os.access("/.local/share/pdn/rootfs", os.W_OK):
            self.skipTest("default root directory is writable")
        env = dict(self.env, HOME="/")
        env.pop("PDN_ROOTFS_DIR")
        result = subprocess.run([str(BINARY), "install", "alpine"], env=env,
                                capture_output=True, text=True, timeout=10)
        self.assertNotEqual(result.returncode, 0)
        for text in ("/.local/share/pdn/rootfs", "$HOME", "PDN_ROOTFS_DIR", "writable"):
            self.assertIn(text, result.stderr)
        self.assertRegex(result.stderr, "Permission denied|Read-only file system")
        self.assertNotIn("Installing", result.stdout)

    def test_unwritable_rootfs_reports_permission_not_contention(self):
        self.roots.mkdir(parents=True, mode=0o500)
        if os.access(self.roots, os.W_OK):
            self.skipTest("rootfs directory is writable")
        before = len(self.requests)
        try:
            result = self.run_harness()
            self.assertNotEqual(result.returncode, 0)
            for text in (str(self.roots), "Permission denied", "install lock", "PDN_ROOTFS_DIR"):
                self.assertIn(text, result.stderr)
            self.assertNotIn("another install", result.stderr)
            self.assertNotIn("Installing", result.stdout)
            self.assertEqual(len(self.requests), before)
        finally:
            self.roots.chmod(0o700)

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
        for args in [("install",), ("install", "unknown"), ("install", "alpine", "extra"),
                     ("install", "alpine", "--archive"), ("INSTALL", "ALPINE", "--ARCHIVE", "/missing")]:
            result = subprocess.run([str(BINARY), *args], env=self.env, capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
        result = subprocess.run([str(BINARY), "install", "--help"], env=self.env, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0)
        self.assertIn("install NAME", result.stdout)

    def test_all_distro_profiles(self):
        for name in ("UBUNTU", "Debian", "ARCH"):
            with self.subTest(name=name):
                self.env["TEST_DISTRO"] = name
                result = self.run_harness(self.archive)
                self.assertEqual(result.returncode, 0, result.stderr)
                root = self.roots / name.lower()
                self.assertEqual((root / "bin/tool").read_bytes(), b"guest\n")
                self.assertIn("nameserver", (root / "etc/resolv.conf").read_text())
                if name == "ARCH":
                    config = (root / "etc/pacman.conf").read_text()
                    self.assertIn("SigLevel = Required", config)
                    self.assertIn("DisableSandboxSyscalls", config)
                    self.assertIn("$arch/$repo", (root / "etc/pacman.d/mirrorlist").read_text())
                    self.assertIn("mirrors.ustc.edu.cn", (root / "etc/pacman.d/mirrorlist").read_text())
                else:
                    config = (root / f"etc/apt/sources.list.d/{name.lower()}.sources").read_text()
                    self.assertIn("Signed-By:", config)
                    self.assertIn("noble-security" if name == "UBUNTU" else "trixie-security", config)
                    self.assertIn("Acquire::https::CaInfo", (root / "etc/apt/apt.conf.d/99pdn").read_text())
                    self.assertIn(self.cert.read_text().strip(), (root / "etc/ssl/certs/ca-certificates.crt").read_text())
                    self.assertEqual((root / "etc/apt/apt.conf.d/docker-clean").exists(), name == "UBUNTU")
                (root / "keep").write_text("keep")
                self.assertNotEqual(self.run_harness(self.archive).returncode, 0)
                self.assertEqual((root / "keep").read_text(), "keep")
        self.assertFalse(list(self.roots.glob(".pdn-*-*")))

    def test_distro_catalogue_and_mirrors(self):
        result = self.run_harness("available")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("debian", result.stdout)
        self.assertEqual(self.run_harness("mirrors", "UBUNTU").returncode, 0)
        self.assertNotEqual(self.run_harness("mirrors", "unknown").returncode, 0)
        for command in ("list", "LS"):
            result = subprocess.run([str(BINARY), command, "--AVAILABLE"], env=self.env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            for name in ("alpine", "ubuntu", "debian", "arch"):
                self.assertIn(name, result.stdout)
        for name, expected in (("Ubuntu", "ubuntu-cdimage"), ("DEBIAN", "docker-debian-artifacts"), ("arch", "archlinuxarm")):
            result = subprocess.run([str(BINARY), "mirrors", name], env=self.env, capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn(expected, result.stdout)
            self.assertNotIn("alpine/v3.24", result.stdout)
        self.env["TEST_DISTRO"] = "unknown"
        self.assertNotEqual(self.run_harness().returncode, 0)
        self.assertFalse(self.roots.exists())

    def test_all_profiles_mirror_fallback_and_collision(self):
        for name in ("ubuntu", "debian", "arch"):
            with self.subTest(name=name):
                self.env.update(TEST_DISTRO=name, TEST_URL=self.url + "/corrupt", TEST_URL_2=self.url + "/archive")
                result = self.run_harness()
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("Trying mirror: ustc", result.stdout)
                root = self.roots / name
                root.rename(root.with_name(name.upper()))
                self.assertIn("already exists", self.run_harness().stderr)

    def test_hardlink_aliases_and_limits(self):
        archive = self.base / "links.tar.gz"
        make_archive(archive, [("./usr", "dir", ""), ("./usr/bin", "dir", ""),
                               ("./usr/bin/tool", "file", b"linked"),
                               ("./usr/bin/alias", "hardlink", "./usr/bin/tool"),
                               ("./other", "hardlink", "./usr/bin/tool")])
        target = self.base / "extracted"
        target.mkdir()
        result = self.run_harness("extract", archive, cwd=target)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((target / "usr/bin/alias").is_symlink())
        self.assertEqual((target / "usr/bin/alias").read_bytes(), b"linked")
        self.assertEqual((target / "other").read_bytes(), b"linked")
        (target / "usr/bin/tool").write_bytes(b"changed")
        self.assertEqual((target / "other").read_bytes(), b"changed")
        small = self.base / "small"
        small.mkdir()
        self.assertNotEqual(self.run_harness("extract-small", archive, cwd=small).returncode, 0)
        for index, link in enumerate(("/host", "../host", "a/../host", "a//host", "a/./host", "same")):
            bad = self.base / f"link-{index}.tar.gz"
            make_archive(bad, [("same", "hardlink", link)])
            self.assertNotEqual(self.run_harness("extract", bad, cwd=small).returncode, 0)

    def test_owner_directory_permissions(self):
        archive = self.base / "private.tar.gz"
        with tarfile.open(archive, "w:gz") as output:
            item = tarfile.TarInfo("private")
            item.type = tarfile.DIRTYPE
            item.mode = 0
            output.addfile(item)
        result = self.run_harness("extract", archive)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.base / "private").stat().st_mode & 0o700, 0o700)

    def test_arch_initialization_failure_and_interrupt(self):
        self.env.update(TEST_DISTRO="arch", TEST_INIT_FAIL="1")
        result = self.run_harness(self.archive)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("keyring initialization failed", result.stderr)
        self.assertFalse((self.roots / "arch").exists())
        self.assertFalse(list(self.roots.glob(".pdn-arch-*")))
        self.env.pop("TEST_INIT_FAIL")
        self.env["TEST_INIT_SLOW"] = "1"
        process = subprocess.Popen([str(self.harness), str(self.archive)], env=self.env, cwd=self.base,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            for line in process.stdout:
                if "Initializing" in line:
                    time.sleep(0.1)
                    process.send_signal(signal.SIGINT)
                    break
            process.communicate(timeout=10)
            self.assertEqual(process.returncode, 130)
        finally:
            if process.poll() is None:
                process.kill()
            process.communicate(timeout=10)
        self.assertFalse((self.roots / "arch").exists())
        self.assertFalse(list(self.roots.glob(".pdn-arch-*")))

    def test_config_dns_and_certificate_paths(self):
        self.env["TEST_DISTRO"] = "debian"
        (self.base / "etc").mkdir()
        outside = self.base / "outside"
        outside.write_text("do not overwrite")
        (self.base / "etc/resolv.conf").symlink_to(outside)
        result = self.run_harness("configure")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(outside.read_text(), "do not overwrite")
        self.assertFalse((self.base / "etc/resolv.conf").is_symlink())
        self.assertEqual(self.run_harness("configure").returncode, 0)
        ca = self.base / "etc/ssl/certs/ca-certificates.crt"
        ca.unlink()
        ca.symlink_to(outside)
        self.assertNotEqual(self.run_harness("configure").returncode, 0)
        self.assertEqual(outside.read_text(), "do not overwrite")
        ca.unlink()
        self.env["PDN_CA_BUNDLE"] = str(self.base / "absent.pem")
        self.assertNotEqual(self.run_harness("configure").returncode, 0)

    def test_system_certificates_and_bundle_limit(self):
        self.env["TEST_DISTRO"] = "ubuntu"
        self.env.pop("PDN_CA_BUNDLE")
        (self.base / "etc").mkdir()
        result = self.run_harness("configure")
        self.assertEqual(result.returncode, 0, result.stderr)
        ca = self.base / "etc/ssl/certs/ca-certificates.crt"
        self.assertIn("BEGIN CERTIFICATE", ca.read_text())
        ca.unlink()
        oversized = self.base / "oversized.pem"
        oversized.write_bytes(b"x" * (8 * 1024 * 1024 + 1))
        self.env["PDN_CA_BUNDLE"] = str(oversized)
        self.assertNotEqual(self.run_harness("configure").returncode, 0)


if __name__ == "__main__":
    unittest.main()
