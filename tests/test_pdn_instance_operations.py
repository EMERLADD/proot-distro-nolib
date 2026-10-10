import fcntl
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import unittest

import test_pdn_instances as fixtures


class InstanceOperationTests(unittest.TestCase):
    setUp = fixtures.InstanceTests.setUp
    invoke = fixtures.InstanceTests.invoke
    good = fixtures.InstanceTests.good
    install = fixtures.InstanceTests.install
    rows = fixtures.InstanceTests.rows
    metadata = fixtures.InstanceTests.metadata
    legacy = fixtures.InstanceTests.legacy
    assert_stages_clean = fixtures.InstanceTests.assert_stages_clean

    def reject(self, operation, source, target):
        result = self.invoke(operation, source, target)
        self.assertNotEqual(result.returncode, 0, result.stdout)
        self.assertFalse((self.roots / target).exists())
        self.assert_stages_clean()
        return result

    def test_clone_is_independent_and_retains_install_provenance(self):
        root = self.install()
        (root / "root/payload").write_text("original\n")
        before = self.metadata()
        self.good(self.invoke("clone", "AI-PYTHON", "ai-copy"))
        after = self.metadata("ai-copy")
        self.assertNotEqual(after["id"], before["id"])
        self.assertEqual(after["source"], "clone")
        self.assertGreaterEqual(after["created_at"], before["created_at"])
        for key in ("distro", "distro_version", "architecture", "source_url", "sha256"):
            self.assertEqual(after[key], before[key])
        self.assertEqual(self.metadata(), before)
        result = self.good(self.invoke("exec", "ai-copy", "--", "/bin/sh", "-c",
                                      "cat /root/payload; echo changed > /root/payload"))
        self.assertEqual(result.stdout, "original\n")
        self.assertEqual((root / "root/payload").read_text(), "original\n")
        self.assertEqual((self.roots / "ai-copy/root/payload").read_text(), "changed\n")
        self.assert_stages_clean()

    def test_rename_retains_identity_and_guest_execution(self):
        root = self.install()
        (root / "root/payload").write_text("renamed\n")
        before = self.metadata()
        self.good(self.invoke("rename", "AI-PYTHON", "workspace-python"))
        self.assertFalse(root.exists())
        before["name"] = "workspace-python"
        self.assertEqual(self.metadata("workspace-python"), before)
        self.assertEqual(self.good(self.invoke("exec", "workspace-python", "--", "/bin/sh",
                                              "-c", "cat /root/payload")).stdout, "renamed\n")
        self.assert_stages_clean()

    def test_legacy_clone_creates_identity_without_modifying_source(self):
        root = self.legacy()
        self.good(self.invoke("clone", "legacy", "copied"))
        metadata = self.metadata("copied")
        self.assertEqual(metadata["source"], "clone")
        for key in ("distro", "distro_version", "sha256", "source_url"):
            self.assertIsNone(metadata[key])
        self.assertFalse((root / ".pdn-instance").exists())
        self.assertEqual(self.good(self.invoke("exec", "copied", "--", "/bin/sh", "-c",
                                              "echo copied")).stdout, "copied\n")

    def test_legacy_rename_preserves_absent_metadata(self):
        root = self.legacy()
        self.good(self.invoke("rename", "legacy", "renamed"))
        self.assertFalse(root.exists())
        self.assertFalse((self.roots / "renamed/.pdn-instance").exists())
        self.assertIsNone(self.rows()[0]["instance"])
        self.good(self.invoke("rename", "renamed", "final"))
        self.assertFalse((self.roots / "final/.pdn-instance").exists())
        self.assertIsNone(self.rows()[0]["instance"])

    def test_symlinks_migrate_host_prefix_without_following_external_targets(self):
        for operation in ("clone", "rename"):
            with self.subTest(operation=operation):
                name = "links-" + operation
                root = self.install(name)
                (root / "root/payload").write_text("kept\n")
                (root / "root/.l2s.payload").write_text("link2symlink\n")
                outside = self.base / ("outside-" + operation)
                outside.write_text("external\n")
                targets = {"guest": "/root/payload", "relative": "payload",
                           "root-exact": str(root),
                           "host": str(root / "root/payload"),
                           "l2s": str(root / "root/.l2s.payload"),
                           "external": str(outside),
                           "similar-prefix": str(root) + "-other/root/payload"}
                for leaf, target in targets.items():
                    (root / "root" / leaf).symlink_to(target)
                self.good(self.invoke(operation, name, name + "-new"))
                new = self.roots / (name + "-new")
                for leaf, target in targets.items():
                    expected = str(new) + target[len(str(root)):] if leaf in ("host", "l2s", "root-exact") else target
                    if operation == "clone" and leaf == "host":
                        expected = "/root/payload"
                    if operation == "clone" and leaf == "root-exact":
                        expected = "/"
                    self.assertEqual(os.readlink(new / "root" / leaf), expected)
                self.assertEqual(self.good(self.invoke("exec", name + "-new", "--", "/bin/sh",
                                                      "-c", "cat /root/host")).stdout, "kept\n")
                self.assertEqual((new / "root/l2s").read_text(), "link2symlink\n")
                self.assertEqual(outside.read_text(), "external\n")
                if operation == "clone":
                    self.assertEqual(os.readlink(root / "root/host"), targets["host"])
                self.assert_stages_clean()

    def test_saved_configuration_is_preserved(self):
        root = self.install()
        self.good(self.invoke("config", "ai-python", "--env", "INSTANCE_CONFIG=kept"))
        contents = (root / ".pdn-config").read_bytes()
        self.good(self.invoke("clone", "ai-python", "copy"))
        self.assertEqual((self.roots / "copy/.pdn-config").read_bytes(), contents)
        self.good(self.invoke("rename", "copy", "renamed"))
        self.assertEqual((self.roots / "renamed/.pdn-config").read_bytes(), contents)
        self.assertEqual(self.good(self.invoke("exec", "renamed", "--", "/bin/sh", "-c",
                                              'printf "%s\\n" "$INSTANCE_CONFIG"')).stdout, "kept\n")

    def test_configuration_symlink_is_rejected_without_dereferencing(self):
        root = self.install()
        outside = self.base / "external-config"
        outside.write_text("untouched\n")
        (root / ".pdn-config").symlink_to(outside)
        for operation in ("clone", "rename"):
            self.reject(operation, "ai-python", "copy")
            self.assertEqual(os.readlink(root / ".pdn-config"), str(outside))
        self.assertEqual(outside.read_text(), "untouched\n")

    def test_configuration_binds_migrate_only_owned_root_prefix(self):
        for operation in ("clone", "rename"):
            with self.subTest(operation=operation):
                name = "config-" + operation
                root = self.install(name)
                (root / "root/payload").write_text("internal\n")
                outside = self.base / ("bind-" + operation)
                outside.mkdir()
                (outside / "payload").write_text("external\n")
                self.good(self.invoke("config", name, "--user", "0", "--work-dir", "/root",
                                      "--env", "INSTANCE_CONFIG=kept", "--bind", str(root / "root") + ":/owned",
                                      "--bind", str(outside) + ":/external", "--bind", str(root) + ":/whole"))
                original = json.loads(self.good(self.invoke("config", name, "--show")).stdout)
                self.good(self.invoke(operation, name, name + "-new"))
                target = self.roots / (name + "-new")
                migrated = json.loads(self.good(self.invoke("config", name + "-new", "--show")).stdout)
                self.assertEqual(migrated["bind"], [str(target / "root") + ":/owned", str(outside) + ":/external",
                                                       str(target) + ":/whole"])
                for key in ("user", "work-dir", "env"):
                    self.assertEqual(migrated[key], original[key])
                self.assertEqual(self.good(self.invoke("exec", name + "-new", "--", "/bin/sh", "-c",
                                                      'pwd; printf "%s\\n" "$INSTANCE_CONFIG"; cat /owned/payload /external/payload')).stdout,
                                 "/root\nkept\ninternal\nexternal\n")
                if operation == "clone":
                    self.assertEqual(json.loads(self.good(self.invoke("config", name, "--show")).stdout), original)

    def test_symlink_base_alias_migrates_owned_links_and_bind_paths(self):
        alias = self.base / "rootfs-alias"
        alias.symlink_to(self.roots)
        self.env["PDN_ROOTFS_DIR"] = str(alias)
        for operation in ("clone", "rename"):
            with self.subTest(operation=operation):
                name = "alias-" + operation
                root = self.install(name)
                alias_root = alias / name
                shortcut = self.base / ("root-shortcut-" + operation)
                shortcut.symlink_to(root)
                target_name = name + "-new"
                target = self.roots / target_name
                (root / "root/backing").write_text("alias payload\n")
                (root / "root/.l2s.backing").write_text("l2s payload\n")
                sibling = self.roots / (name + "-sibling")
                sibling.mkdir()
                (sibling / "backing").write_text("sibling\n")
                outside = self.base / ("outside-alias-" + operation)
                outside.symlink_to(sibling)
                targets = {"owned": str(alias_root / "root/backing"),
                           "l2s": str(alias_root / "root/.l2s.backing"),
                           "broken-owned": str(alias_root / "missing/leaf"),
                           "root-exact": str(alias_root),
                           "shortcut-owned": str(shortcut / "root/backing"),
                           "shortcut-l2s": str(shortcut / "root/.l2s.backing"),
                           "shortcut-broken": str(shortcut / "missing/leaf"),
                           "shortcut-exact": str(shortcut),
                           "sibling": str(alias / sibling.name / "backing"),
                           "external-alias": str(outside / "backing")}
                for leaf, value in targets.items():
                    (root / "root" / leaf).symlink_to(value)
                self.good(self.invoke("config", name, "--bind", str(alias_root / "root") + ":/owned",
                                      "--bind", str(outside) + ":/external", "--bind", str(shortcut / "root") + ":/shortcut"))
                original_configuration = json.loads(self.good(self.invoke("config", name, "--show")).stdout)
                self.assertEqual(original_configuration["bind"][1], str(sibling) + ":/external")
                self.good(self.invoke(operation, name, target_name))
                for leaf, value in targets.items():
                    if leaf in ("owned", "broken-owned", "root-exact", "shortcut-owned", "shortcut-broken", "shortcut-exact"):
                        prefix = shortcut if leaf.startswith("shortcut-") else alias_root
                        suffix = value[len(str(prefix)):]
                        expected = suffix or "/" if operation == "clone" else str(target) + suffix
                    elif leaf in ("l2s", "shortcut-l2s"):
                        expected = str(target / "root/.l2s.backing")
                    else:
                        expected = value
                    self.assertEqual(os.readlink(target / "root" / leaf), expected)
                configuration = json.loads(self.good(self.invoke("config", target_name, "--show")).stdout)
                self.assertEqual(configuration["bind"], [str(target / "root") + ":/owned", original_configuration["bind"][1],
                                                          str(target / "root") + ":/shortcut"])
                result = self.good(self.invoke("exec", target_name, "--", "/bin/sh", "-c",
                                               "cat /root/owned /root/l2s /owned/backing /external/backing; echo changed > /root/backing"))
                self.assertEqual(result.stdout, "alias payload\nl2s payload\nalias payload\nsibling\n")
                if operation == "clone":
                    self.assertEqual((root / "root/backing").read_text(), "alias payload\n")
                    self.assertEqual(os.readlink(root / "root/owned"), targets["owned"])
                self.assertEqual((sibling / "backing").read_text(), "sibling\n")
                self.assert_stages_clean()

    def test_corrupt_saved_configuration_refuses_without_changes(self):
        root = self.install()
        metadata = (root / ".pdn-instance").read_bytes()
        configuration = root / ".pdn-config"
        configuration.write_bytes(b"invalid configuration\n")
        for operation in ("clone", "rename"):
            self.reject(operation, "ai-python", "copy")
            self.assertEqual(configuration.read_bytes(), b"invalid configuration\n")
            self.assertEqual((root / ".pdn-instance").read_bytes(), metadata)

    def test_clone_rejects_external_l2s_backing_even_through_base_alias(self):
        root = self.install()
        other = self.legacy("other-root")
        backing = other / "root/.l2s.external"
        backing.write_text("external backing\n")
        alias = self.base / "external-rootfs-alias"
        alias.symlink_to(self.roots)
        target = str(alias / "other-root/root/.l2s.external")
        (root / "root/external-hardlink").symlink_to(target)
        metadata = (root / ".pdn-instance").read_bytes()
        self.reject("clone", "ai-python", "rejected-copy")
        self.assertEqual(backing.read_text(), "external backing\n")
        self.assertEqual(os.readlink(root / "root/external-hardlink"), target)
        self.assertEqual((root / ".pdn-instance").read_bytes(), metadata)

    def test_busy_source_and_global_lock_refuse_without_changes(self):
        root = self.install()
        original = (root / ".pdn-instance").read_bytes()
        for lock_path in (root, self.roots / ".pdn-install.lock"):
            descriptor = os.open(lock_path, os.O_RDONLY) if lock_path == root else os.open(lock_path, os.O_RDWR | os.O_CREAT, 0o600)
            try:
                fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
                for operation in ("clone", "rename"):
                    self.reject(operation, "ai-python", "busy-target")
                    self.assertEqual((root / ".pdn-instance").read_bytes(), original)
            finally:
                os.close(descriptor)

    def test_destination_conflicts_preserve_both_roots(self):
        root = self.install()
        other = self.install("existing")
        originals = [(path / ".pdn-instance").read_bytes() for path in (root, other)]
        for operation in ("clone", "rename"):
            result = self.invoke(operation, "ai-python", "EXISTING")
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((self.roots / "EXISTING").exists())
            self.assertEqual([(path / ".pdn-instance").read_bytes() for path in (root, other)], originals)
            self.assert_stages_clean()

    def test_missing_ambiguous_and_symlink_sources_are_rejected(self):
        root = self.legacy("duplicate")
        self.legacy("DUPLICATE")
        (self.roots / "linked").symlink_to(root)
        for source in ("missing", "duplicate", "linked"):
            for operation in ("clone", "rename"):
                self.reject(operation, source, "target")
        self.assertTrue(root.is_dir())
        self.assertTrue((self.roots / "DUPLICATE").is_dir())
        self.assertTrue((self.roots / "linked").is_symlink())

    def test_invalid_names_arguments_and_same_name_leave_source_unchanged(self):
        root = self.install()
        original = (root / ".pdn-instance").read_bytes()
        invalid = ("", ".", "..", "a/b", "a b", "a\\b", "-option", "café", "a" * 129)
        for operation in ("clone", "rename"):
            for target in invalid:
                with self.subTest(operation=operation, target=target):
                    self.assertNotEqual(self.invoke(operation, "ai-python", target).returncode, 0)
            for args in ((), ("ai-python",), ("ai-python", "target", "extra"), ("ai-python", "ai-python")):
                self.assertNotEqual(self.invoke(operation, *args).returncode, 0)
            self.assertEqual((root / ".pdn-instance").read_bytes(), original)
            self.assertFalse((self.roots / "target").exists())
            self.assert_stages_clean()

    def test_case_only_rename_and_maximum_length_destination(self):
        root = self.install()
        metadata = self.metadata()
        self.good(self.invoke("rename", "ai-python", "AI-PYTHON"))
        self.assertFalse(root.exists())
        metadata["name"] = "AI-PYTHON"
        self.assertEqual(self.metadata("AI-PYTHON"), metadata)
        name = "a" * 128
        self.good(self.invoke("clone", "AI-PYTHON", name))
        self.assertEqual(self.metadata(name)["name"], name)

    def test_corrupt_or_unsafe_metadata_refuses_without_side_effects(self):
        root = self.install()
        metadata = root / ".pdn-instance"
        original = metadata.read_bytes()
        outside = self.base / "outside-metadata"
        outside.write_bytes(original)
        for kind in ("corrupt", "symlink", "directory", "fifo"):
            with self.subTest(kind=kind):
                metadata.unlink()
                if kind == "corrupt":
                    metadata.write_text("invalid\n")
                elif kind == "symlink":
                    metadata.symlink_to(outside)
                elif kind == "directory":
                    metadata.mkdir()
                else:
                    os.mkfifo(metadata)
                for operation in ("clone", "rename"):
                    self.reject(operation, "ai-python", "bad-target")
                    self.assertTrue(root.is_dir())
                    self.assertEqual(outside.read_bytes(), original)
                metadata.rmdir() if kind == "directory" else metadata.unlink()
                metadata.write_bytes(original)


class InstanceOperationRollbackTests(unittest.TestCase):
    setUp = fixtures.InstanceTests.setUp
    invoke = fixtures.InstanceTests.invoke
    good = fixtures.InstanceTests.good
    install = fixtures.InstanceTests.install
    assert_stages_clean = fixtures.InstanceTests.assert_stages_clean
    @classmethod
    def setUpClass(cls):
        if "NDK_PATH" not in os.environ:
            raise unittest.SkipTest("NDK_PATH is required for the native rollback harness")
        cls.fixture = tempfile.TemporaryDirectory(prefix="pdn-operation-harness-", dir=fixtures.PROJECT / "build")
        cls.addClassCleanup(cls.fixture.cleanup)
        cls.harness = Path(cls.fixture.name) / "harness"
        source = Path(cls.fixture.name) / "harness.c"
        source.write_text(r'''
#include <errno.h>
#include <signal.h>
#include <stdarg.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include "pdn_events.h"
int pdn_clone(const char *, const char *);
int pdn_rename(const char *, const char *);
char *pdn_rootfs_base(void) { return strdup(getenv("PDN_ROOTFS_DIR")); }
long __real_syscall(long, ...);
long __wrap_syscall(long number, ...) {
    if (number != SYS_renameat2) { errno = ENOSYS; return -1; }
    va_list args;
    va_start(args, number);
    int oldfd = va_arg(args, int);
    const char *oldpath = va_arg(args, const char *);
    int newfd = va_arg(args, int);
    const char *newpath = va_arg(args, const char *);
    unsigned flags = va_arg(args, unsigned);
    va_end(args);
    if (!strcmp(newpath, "failed-target")) {
        if (getenv("PDN_TEST_CANCEL")) raise(SIGTERM);
        errno = EIO;
        return -1;
    }
    return __real_syscall(number, oldfd, oldpath, newfd, newpath, flags);
}
int main(int argc, char **argv) {
    if (argc != 4) return 2;
    pdn_events_begin(argv[1]);
    int result = !strcmp(argv[1], "clone") ? pdn_clone(argv[2], argv[3]) : pdn_rename(argv[2], argv[3]);
    pdn_events_finish(result);
    return result;
}
''')
        toolchain, = Path(os.environ["NDK_PATH"]).glob("toolchains/llvm/prebuilt/*")
        resource, = (toolchain / "lib/clang").iterdir()
        compiler = os.environ.get("CC", "clang")
        cli = fixtures.PROJECT / "src/proot/src/cli"
        deps = fixtures.PROJECT / "build/proot-distro-nolib/deps/install"
        flags = ["--target=aarch64-linux-android24", f"--sysroot={toolchain / 'sysroot'}",
                 f"-resource-dir={resource}", "-D_GNU_SOURCE", f"-I{cli}",
                 f"-I{fixtures.PROJECT / 'src/proot/src'}", f"-I{deps / 'include'}", f"-L{deps / 'lib'}"]
        if "-fno-termux-rpath" in subprocess.check_output([compiler, "--help"], text=True):
            flags.append("-fno-termux-rpath")
        if os.environ.get("PDN_COVERAGE"):
            flags += ["-fprofile-instr-generate", "-fcoverage-mapping"]
            runtime = subprocess.check_output([compiler, "-print-resource-dir"], text=True).strip()
            flags += [f"{runtime}/lib/linux/libclang_rt.profile-aarch64-android.a"]
        subprocess.run([compiler, *flags, str(source), *[str(cli / name) for name in
                       ("pdn_backup.c", "pdn_config.c", "pdn_instance.c", "pdn_events.c")],
                        "-Wl,--wrap=syscall", "-larchive", "-lz", "-o", str(cls.harness)], check=True)
        if os.environ.get("PDN_COVERAGE"):
            shutil.copy2(cls.harness, fixtures.PROJECT / "build/pdn-instance-operations-coverage-harness")

    def rollback_case(self, operation, cancelled):
        root = self.install()
        (root / "root/backing").write_text("preserved\n")
        for name in ("host-link", ".l2s-link"):
            (root / "root" / name).symlink_to(root / "root/backing")
        self.good(self.invoke("config", "ai-python", "--env", "ROLLBACK=kept", "--bind",
                              str(root / "root") + ":/owned"))
        metadata = root / ".pdn-instance"
        metadata.write_bytes(b"\n".join(reversed(metadata.read_bytes().splitlines())) + b"\n")
        configuration = root / ".pdn-config"
        lines = configuration.read_bytes().splitlines()
        configuration.write_bytes(lines[0] + b"\n" + b"\n".join(reversed(lines[1:])) + b"\n")
        for path in (metadata, configuration):
            path.chmod(0o640)
        original = {name: (root / name).read_bytes() for name in (".pdn-config", ".pdn-instance")}
        modes = {name: (root / name).stat().st_mode & 0o777 for name in original}
        environment = dict(self.env)
        event_file = self.base / "rollback-events.jsonl"
        environment["PDN_EVENT_FILE"] = str(event_file)
        if cancelled:
            environment["PDN_TEST_CANCEL"] = "1"
        result = subprocess.run([str(self.harness), operation, "ai-python", "failed-target"],
                                env=environment, capture_output=True, text=True, timeout=60)
        self.assertEqual(result.returncode, 128 + signal.SIGTERM if cancelled else 2, result.stderr)
        self.assertFalse((self.roots / "failed-target").exists())
        self.assertEqual((root / "root/backing").read_text(), "preserved\n")
        for name in ("host-link", ".l2s-link"):
            self.assertEqual(os.readlink(root / "root" / name), str(root / "root/backing"))
        for name, contents in original.items():
            self.assertEqual((root / name).read_bytes(), contents)
            self.assertEqual((root / name).stat().st_mode & 0o777, modes[name])
        events = [json.loads(line) for line in event_file.read_text().splitlines()]
        self.assertTrue(events)
        self.assertEqual(events[-1]["type"], "result")
        self.assertEqual(events[-1]["exit_code"], result.returncode)
        self.assertEqual(events[-1]["outcome"], "cancelled" if cancelled else "manager_error")
        if cancelled:
            self.assertEqual(events[-1]["signal"], signal.SIGTERM)
        else:
            self.assertTrue(events[-1]["suggestion"])
        self.assertFalse(list(root.rglob(".pdn-link.*")))
        self.assertFalse(list(root.rglob(".pdn-config.*")))
        self.assertFalse(list(root.rglob(".pdn-rollback.*")))
        self.assert_stages_clean()

    def test_clone_publication_failure_removes_stage(self):
        self.rollback_case("clone", False)

    def test_rename_publication_failure_restores_links_config_metadata(self):
        self.rollback_case("rename", False)

    def test_clone_cancel_at_publication_removes_stage(self):
        self.rollback_case("clone", True)

    def test_rename_cancel_at_publication_restores_links_config_metadata(self):
        self.rollback_case("rename", True)


if __name__ == "__main__":
    unittest.main()
