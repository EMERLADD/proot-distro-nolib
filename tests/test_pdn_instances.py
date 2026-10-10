import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import time
import unittest


PROJECT = Path(__file__).resolve().parents[1]
BINARY = Path(os.environ.get("PROOT_NOLIB_BINARY", PROJECT / "build/proot-distro-nolib/arm64/proot-distro-nolib")).resolve()
ARCHIVE = PROJECT / "build/pdn-sources/alpine-minirootfs-3.24.2-aarch64.tar.gz"


class InstanceTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="pdn-instance-test-", dir=PROJECT / "build")
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name)
        self.roots = self.base / "roots with spaces"
        self.roots.mkdir()
        self.env = {"PATH": "/system/bin", "PDN_ROOTFS_DIR": str(self.roots)}
        if "LLVM_PROFILE_FILE" in os.environ:
            self.env["LLVM_PROFILE_FILE"] = os.environ["LLVM_PROFILE_FILE"]

    def invoke(self, *args):
        return subprocess.run([str(BINARY), *map(str, args)], env=self.env,
                              capture_output=True, text=True, timeout=60)

    def good(self, result):
        self.assertEqual(result.returncode, 0, result.stderr)
        return result

    def install(self, name="ai-python", archive_first=False):
        if not ARCHIVE.is_file():
            self.skipTest("official pinned Alpine archive is unavailable")
        options = ["--archive", ARCHIVE, "--name", name] if archive_first else ["--name", name, "--archive", ARCHIVE]
        self.good(self.invoke("install", "alpine", *options))
        self.assertTrue((self.roots / name / ".pdn-instance").is_file())
        self.assert_stages_clean()
        return self.roots / name

    def rows(self):
        result = self.good(self.invoke("list", "--json"))
        document = json.loads(result.stdout)
        self.assertEqual(document["version"], 1)
        self.assertEqual(set(document), {"version", "distributions"})
        return document["distributions"]

    def metadata(self, name="ai-python"):
        row, = [row for row in self.rows() if row["name"] == name]
        self.assertEqual(set(row), {"name", "rootfs", "instance"})
        self.assertEqual(row["rootfs"], str(self.roots / name))
        instance = row["instance"]
        self.assertEqual(set(instance), {"version", "id", "name", "distro", "distro_version",
                                         "architecture", "source", "source_url", "sha256", "created_at"})
        self.assertEqual(instance["version"], 1)
        self.assertRegex(instance["id"], r"^[0-9a-f]{32}$")
        self.assertEqual(instance["name"], name)
        self.assertEqual(instance["architecture"], "aarch64")
        self.assertIs(type(instance["created_at"]), int)
        self.assertGreater(instance["created_at"], 0)
        self.assertLessEqual(instance["created_at"], int(time.time()) + 5)
        return instance

    def assert_stages_clean(self):
        self.assertEqual([path.name for path in self.roots.iterdir()
                          if path.name.startswith(".pdn-") and path.is_dir()], [])

    def legacy(self, name="legacy"):
        root = self.roots / name
        (root / "bin").mkdir(parents=True)
        (root / "root").mkdir()
        (root / "tmp").mkdir()
        shutil.copy2(PROJECT / "android/proot-engine/src/main/jniLibs/arm64-v8a/libbusybox.so", root / "bin/busybox")
        (root / "bin/sh").symlink_to("busybox")
        return root

    def test_two_instances_keep_distinct_ids_and_guest_files(self):
        self.install()
        self.install("ai-node", archive_first=True)
        python = self.metadata()
        node = self.metadata("ai-node")
        self.assertNotEqual(python["id"], node["id"])
        for name, expected in (("ai-python", "python"), ("ai-node", "node")):
            self.good(self.invoke("exec", name, "--", "/bin/sh", "-c", f"echo {expected} > /root/owner"))
            result = self.good(self.invoke("login", name.upper(), "--", "/bin/sh", "-c", "cat /root/owner; id -u"))
            self.assertEqual(result.stdout, expected + "\n0\n")
            self.assertEqual((self.roots / name / "root/owner").read_text(), expected + "\n")
        self.assertEqual(python, self.metadata())
        self.assertEqual(node, self.metadata("ai-node"))

    def test_archive_metadata_exact_provenance_and_stable_queries(self):
        self.install()
        instance = self.metadata()
        self.assertEqual(instance["distro"], "alpine")
        self.assertEqual(instance["distro_version"], "3.24.2")
        self.assertEqual(instance["source"], "archive")
        self.assertIsNone(instance["source_url"])
        self.assertEqual(instance["sha256"], hashlib.sha256(ARCHIVE.read_bytes()).hexdigest())
        self.assertEqual(self.rows(), self.rows())
        self.assertIn("ai-python", self.good(self.invoke("list")).stdout)

    def test_canonical_name_when_name_omitted(self):
        if not ARCHIVE.is_file():
            self.skipTest("official pinned Alpine archive is unavailable")
        self.good(self.invoke("install", "alpine", "--archive", ARCHIVE))
        self.assertEqual(self.metadata("alpine")["distro"], "alpine")

    def test_case_insensitive_conflict_preserves_existing_instance(self):
        root = self.install()
        original = (root / ".pdn-instance").read_bytes()
        result = self.invoke("install", "alpine", "--name", "AI-PYTHON", "--archive", ARCHIVE)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((root / ".pdn-instance").read_bytes(), original)
        self.assertFalse((self.roots / "AI-PYTHON").exists())
        self.assert_stages_clean()

    def test_invalid_names_and_duplicate_or_conflicting_options(self):
        names = ("", ".", "..", "a/b", "a b", "a\\b", "a\nb", "café", "a" * 129)
        args = [["--name", name, "--archive", ARCHIVE] for name in names]
        args += [["--name", "one", "--name", "two", "--archive", ARCHIVE],
                 ["--name", "one", "--name", "one", "--archive", ARCHIVE],
                 ["--name", "one", "--archive", ARCHIVE, "--archive", ARCHIVE],
                 ["--name", "one", "--mirror", "tuna", "--mirror", "tuna"],
                 ["--name", "one", "--archive", ARCHIVE, "--mirror", "tuna"],
                 ["--mirror", "tuna", "--archive", ARCHIVE, "--name", "one"], ["--name"]]
        for options in args:
            with self.subTest(options=options):
                self.assertNotEqual(self.invoke("install", "alpine", *options).returncode, 0)
                self.assertEqual([p for p in self.roots.iterdir() if not p.name.startswith(".pdn-")], [])
                self.assert_stages_clean()

    def test_maximum_length_name_is_accepted(self):
        name = "a" * 128
        self.install(name)
        self.assertEqual(self.metadata(name)["name"], name)

    def test_legacy_query_is_null_and_never_writes_metadata(self):
        root = self.legacy()
        before = set(root.iterdir())
        self.assertEqual(self.rows(), [{"name": "legacy", "rootfs": str(root), "instance": None}])
        self.assertEqual(self.rows(), self.rows())
        self.assertEqual(set(root.iterdir()), before)
        self.assertFalse((root / ".pdn-instance").exists())
        self.assertEqual(self.good(self.invoke("exec", "legacy", "--", "/bin/sh", "-c", "echo legacy")).stdout, "legacy\n")

    def test_malformed_metadata_fails_query_without_partial_json(self):
        root = self.install()
        self.legacy("aaa-legacy")
        metadata = root / ".pdn-instance"
        original = metadata.read_bytes()
        payloads = (b"", b"invalid\n", b"\x00" + original, original + original,
                    original[:len(original) // 2], b"x" * 16385)
        for payload in payloads:
            with self.subTest(payload_length=len(payload)):
                metadata.write_bytes(payload)
                result = self.invoke("list", "--json")
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertEqual(result.stdout, "")
        metadata.write_bytes(original)
        self.assertEqual(len(self.rows()), 2)

    def test_symlink_directory_fifo_metadata_rejected_without_host_writes(self):
        root = self.legacy()
        metadata = root / ".pdn-instance"
        outside = self.base / "outside"
        outside.write_bytes(b"keep\n")
        for kind in ("symlink", "directory", "fifo"):
            with self.subTest(kind=kind):
                if kind == "symlink":
                    metadata.symlink_to(outside)
                elif kind == "directory":
                    metadata.mkdir()
                else:
                    os.mkfifo(metadata)
                result = self.invoke("list", "--json")
                self.assertNotEqual(result.returncode, 0, result.stdout)
                self.assertEqual(result.stdout, "")
                self.assertEqual(outside.read_bytes(), b"keep\n")
                metadata.rmdir() if kind == "directory" else metadata.unlink()

    def test_failed_install_leaves_no_metadata_or_stages(self):
        corrupt = self.base / "corrupt.tar.gz"
        corrupt.write_bytes(b"invalid archive")
        for archive in (self.base / "missing.tar.gz", corrupt):
            with self.subTest(archive=archive):
                result = self.invoke("install", "alpine", "--name", "failed", "--archive", archive)
                self.assertNotEqual(result.returncode, 0)
                self.assertFalse((self.roots / "failed").exists())
                self.assertEqual(list(self.roots.rglob(".pdn-instance")), [])
                self.assert_stages_clean()

    def test_restore_regenerates_identity_and_preserves_provenance(self):
        root = self.install()
        original = self.metadata()
        (root / "root/payload").write_text("preserved\n")
        archive = self.base / "backup.tar.gz"
        self.good(self.invoke("backup", "ai-python", archive))
        with tarfile.open(archive) as source:
            self.assertIn(".pdn-instance", source.getnames())
        self.good(self.invoke("restore", "restored", archive))
        restored = self.metadata("restored")
        self.assertNotEqual(restored["id"], original["id"])
        self.assertGreaterEqual(restored["created_at"], original["created_at"])
        self.assertEqual(restored["source"], "restore")
        for key in ("distro", "distro_version", "architecture", "sha256", "source_url"):
            self.assertEqual(restored[key], original[key])
        self.assertEqual((self.roots / "restored/root/payload").read_text(), "preserved\n")
        self.assertEqual(self.metadata(), original)
        self.assert_stages_clean()

    def test_legacy_restore_creates_identity_with_unknown_provenance(self):
        self.legacy()
        archive = self.base / "legacy.tar.gz"
        self.good(self.invoke("backup", "legacy", archive))
        self.good(self.invoke("restore", "restored", archive))
        restored = self.metadata("restored")
        self.assertEqual(restored["source"], "restore")
        for key in ("distro", "distro_version", "sha256", "source_url"):
            self.assertIsNone(restored[key])
        self.assert_stages_clean()


class InstanceModuleTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="pdn-instance-probe-", dir=PROJECT / "build")
        cls.addClassCleanup(cls.directory.cleanup)
        cls.probe = Path(cls.directory.name) / "probe"
        cli = PROJECT / "src/proot/src/cli"
        compiler = os.environ.get("CC", "clang")
        flags = ["-D_GNU_SOURCE", f"-I{cli}"]
        if os.environ.get("PDN_COVERAGE"):
            flags += ["-fprofile-instr-generate", "-fcoverage-mapping"]
        subprocess.run([compiler, *flags, str(PROJECT / "tests/pdn_instance_probe.c"),
                        str(cli / "pdn_instance.c"), str(cli / "pdn_events.c"), "-o", str(cls.probe)], check=True)
        if os.environ.get("PDN_COVERAGE"):
            shutil.copy2(cls.probe, PROJECT / "build/pdn-instance-coverage-harness")

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="pdn-instance-module-", dir=PROJECT / "build")
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.file = self.root / ".pdn-instance"
        self.digest = "a" * 64

    def invoke(self, operation, *args, root=None):
        return subprocess.run([str(self.probe), operation, str(root or self.root), *map(str, args)],
                              capture_output=True, text=True, timeout=10)

    def create(self, **changes):
        values = {"name": "fixture", "distro": "alpine", "version": "3.24.2", "digest": self.digest,
                  "source": "archive", "url": ""}
        values.update(changes)
        return self.invoke("create", *values.values())

    def original(self):
        result = self.create()
        self.assertEqual(result.returncode, 0, result.stderr)
        return self.file.read_text()

    def test_archive_mirror_and_legacy_restore_json(self):
        self.assertEqual(self.invoke("read").stdout, "null")
        self.assertEqual(self.create().returncode, 0)
        before = json.loads(self.invoke("read").stdout)
        self.assertEqual(self.invoke("restore", "new-name").returncode, 0)
        after = json.loads(self.invoke("read").stdout)
        self.assertNotEqual(after["id"], before["id"])
        self.assertEqual(after["name"], "new-name")
        self.assertEqual(after["sha256"], self.digest)
        self.assertEqual(after["source"], "restore")
        self.file.unlink()
        self.assertEqual(self.create(source="mirror", url='https://example.invalid/a?x="quoted"').returncode, 0)
        mirror = json.loads(self.invoke("read").stdout)
        self.assertEqual(mirror["source_url"], 'https://example.invalid/a?x="quoted"')
        self.file.unlink()
        self.assertEqual(self.invoke("restore", "legacy-restored").returncode, 0)
        restored = json.loads(self.invoke("read").stdout)
        for field in ("distro", "distro_version", "sha256", "source_url"):
            self.assertIsNone(restored[field])

    def test_invalid_metadata_fields_and_record_boundaries(self):
        original = self.original()
        fields = dict(line.split("=", 1) for line in original.splitlines())
        substitutions = {"version": ["", "2"], "id": ["short", "z" * 32, "A" * 32],
                         "name": ["", ".hidden", "bad/name", "a" * 129], "distro": ["bad/name"],
                         "distro_version": ["x" * 129, "bad\tversion"], "architecture": ["x86_64"],
                         "source": ["unknown", "x" * 16], "source_url": ["x" * 2049, "bad\rurl"],
                         "sha256": ["short", "Z" * 64],
                         "created_at": ["", "-1", "+1", "1tail", "99999999999999999999999999"]}
        payloads = ["", "x" * 4097, "x" * 4095 + "\n", original[:-1], original + "\x00\n",
                    original + "extra=value\n", original + "version=1\n", "no-equals\n" + original,
                    "version\n=value\n" + original, original.replace("version=1\n", "")]
        for key, replacements in substitutions.items():
            for replacement in replacements:
                altered = dict(fields, **{key: replacement})
                payloads.append("".join(f"{field}={value}\n" for field, value in altered.items()))
        for key in ("distro", "distro_version", "sha256"):
            payloads.append(original.replace(f"{key}={fields[key]}\n", f"{key}=\n"))
        payloads.append(original.replace("source=archive\n", "source=mirror\n"))
        for index, payload in enumerate(payloads):
            with self.subTest(index=index):
                self.file.write_text(payload)
                result = self.invoke("read")
                self.assertEqual(result.returncode, 2, result.stdout)
                self.assertEqual(result.stdout, "")
        self.file.write_text(original)
        self.assertEqual(self.invoke("read").returncode, 0)

    def test_invalid_creation_provenance_does_not_write(self):
        cases = [{"name": "../escape"}, {"name": "a" * 129}, {"name": "bad\nname"},
                 {"distro": "x" * 129}, {"distro": "bad/distro"}, {"version": "x" * 129},
                 {"version": "bad\nversion"}, {"digest": "x" * 65}, {"digest": "invalid"},
                 {"source": "x" * 16}, {"source": "unknown"}, {"url": "x" * 2049},
                 {"url": "bad\nurl"}, {"source": "mirror", "url": ""}, {"distro": ""},
                 {"version": ""}, {"digest": ""}]
        for changes in cases:
            with self.subTest(changes=changes):
                result = self.create(**changes)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertFalse(self.file.exists())

    def test_nonregular_denied_and_invalid_root_descriptors(self):
        outside = self.root / "outside"
        outside.write_text("keep")
        self.file.symlink_to(outside)
        self.assertEqual(self.invoke("read").returncode, 2)
        self.assertEqual(self.create().returncode, 0)
        self.assertEqual(outside.read_text(), "keep")
        self.file.unlink()
        self.file.mkdir()
        self.assertEqual(self.invoke("read").returncode, 2)
        self.assertEqual(self.create().returncode, 2)
        self.file.rmdir()
        os.mkfifo(self.file)
        self.assertEqual(self.invoke("read").returncode, 2)
        self.file.unlink()
        self.file.write_text("invalid\n")
        self.assertEqual(self.invoke("restore", "restored").returncode, 2)
        self.assertEqual(self.file.read_text(), "invalid\n")
        self.assertEqual(self.invoke("read", root=self.root / "absent").returncode, 2)
        self.assertEqual(self.invoke("restore", "restored", root=self.root / "absent").returncode, 2)
        self.assertEqual(self.invoke("create", "name", "alpine", "3.24.2", self.digest, "archive", "",
                                     root=self.root / "absent").returncode, 2)
        self.file.unlink()
        self.root.chmod(0o500)
        try:
            if not os.access(self.root, os.W_OK):
                self.assertEqual(self.create().returncode, 2)
        finally:
            self.root.chmod(0o700)


if __name__ == "__main__":
    unittest.main()
