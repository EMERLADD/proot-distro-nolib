import fcntl
import json
import os
import unittest

import test_pdn as pdn


class PdnConfigTests(unittest.TestCase):
    invoke = pdn.PdnTests.invoke
    good = pdn.PdnTests.good
    invoke_events = pdn.PdnTests.invoke_events
    error_code = pdn.PdnTests.error_code

    def setUp(self):
        pdn.PdnTests.setUp(self)
        (self.root / "etc").mkdir()
        (self.root / "home/alice").mkdir(parents=True)
        (self.root / "etc/passwd").write_text(
            "root:x:0:0:root:/root:/bin/sh\n"
            "alice:x:1234:2345:Alice:/home/alice:/bin/sh\n")

    def test_config_error_classification(self):
        config = self.root / ".pdn-config"
        for contents in ("broken", "PDN1\nu:zz\n", "PDN1\ne:31\n", "PDN1\nu:2d31\n"):
            with self.subTest(contents=contents):
                config.write_text(contents)
                self.error_code("config_invalid", "config", "ubuntu", "--show")
        config.unlink()
        config.symlink_to("etc/passwd")
        for action in ("--show", "--clear", "--env"):
            arguments = (action, "X=y") if action == "--env" else (action,)
            self.error_code("config_unsafe", "config", "ubuntu", *arguments)
        config.unlink()
        os.mkfifo(config)
        self.error_code("config_unsafe", "config", "ubuntu", "--show")
        config.unlink()
        self.error_code("user_invalid", "config", "ubuntu", "--user", "-1")
        self.error_code("user_invalid", "config", "ubuntu", "--user", "missing")
        (self.root / "etc/passwd").write_text("broken passwd row\n")
        self.error_code("user_invalid", "exec", "ubuntu", "--user", "alice", "--", "/bin/sh")

    @unittest.skipIf(os.geteuid() == 0, "requires unprivileged file access")
    def test_config_file_permission_classification(self):
        config = self.root / ".pdn-config"
        config.write_text("PDN1\n")
        config.chmod(0)
        try:
            self.error_code("file_permission", "config", "ubuntu", "--show")
        finally:
            config.chmod(0o600)
        self.root.chmod(0o500)
        try:
            self.error_code("file_permission", "config", "ubuntu", "--env", "X=y")
        finally:
            self.root.chmod(0o755)

    def test_environment_values_are_exact_and_last_wins(self):
        value = " a'b\"c; $(echo BAD)\nX=Y "
        self.good(self.invoke("exec", "ubuntu", "--env", "EXACT=old", "-e", "EXACT=" + value,
                              "-e", "EMPTY=", "--", "/bin/sh", "-c", 'printf "<%s><%s>" "$EXACT" "$EMPTY"'),
                  "<" + value + "><>")
        for value in ("NOVALUE", "=empty", "1BAD=x", "A-B=x", "A B=x", "A\nB=x"):
            self.assertEqual(self.invoke("login", "ubuntu", "--env", value).returncode, 2)

    def test_user_ids_and_passwd_environment(self):
        for user in ("alice", "1234"):
            self.good(self.invoke("exec", "ubuntu", "-u", user, "--", "/bin/sh", "-c",
                                  '/bin/busybox id -u; /bin/busybox id -g; echo "$HOME:$USER:$LOGNAME:$SHELL"'),
                      "1234\n2345\n/home/alice:alice:alice:/bin/sh\n")
        self.good(self.invoke("exec", "ubuntu", "--user", "1234:3456", "--", "/bin/busybox", "id", "-g"), "3456\n")
        self.good(self.invoke("exec", "ubuntu", "--user", "4567:5678", "--", "/bin/sh", "-c",
                              '/bin/busybox id -u; /bin/busybox id -g; echo "$HOME:$USER:$LOGNAME:$SHELL"'),
                  "4567\n5678\n/:4567:4567:/bin/sh\n")
        for user in ("missing", "", "-1", "4294967295", "1234:", ":1234", "1234:1:2", "1234:-1"):
            self.assertEqual(self.invoke("login", "ubuntu", "-u", user).returncode, 2)
        self.good(self.invoke("login", "ubuntu", "-u", "alice", input="pwd\nexit\n"), "/home/alice\n")

    def test_explicit_root_has_no_duplicate_identity_warning(self):
        for user in ("root", "0", "0:0"):
            with self.subTest(user=user):
                result = self.invoke("exec", "ubuntu", "--user", user, "--",
                                     "/bin/sh", "-c", '/bin/busybox id -u; /bin/busybox id -g; echo "$HOME:$USER"')
                self.good(result, "0\n0\n/root:root\n")
                self.assertEqual(result.stderr, "")


    def test_workdir_for_interactive_and_command(self):
        (self.root / "work dir").mkdir()
        self.good(self.invoke("exec", "ubuntu", "-w", "/work dir", "--", "/bin/busybox", "pwd"), "/work dir\n")
        self.good(self.invoke("login", "ubuntu", "--work-dir", "/work dir", input="pwd\nexit\n"), "/work dir\n")
        for path in ("relative", "", "/missing", "/bin/busybox"):
            result = self.invoke("exec", "ubuntu", "-w", path, "--", "/bin/sh", "-c", "echo BAD")
            self.assertNotEqual(result.returncode, 0)
            self.assertNotIn("BAD", result.stdout)
        host = self.base / "work host"
        host.mkdir()
        self.good(self.invoke("exec", "ubuntu", "-b", str(host) + ":/bound", "-w", "/bound",
                              "--", "/bin/busybox", "pwd"), "/bound\n")

    def test_config_replacement_defaults_and_overrides(self):
        source = self.base / "source"
        source.mkdir()
        (source / "value").write_text("bound\n")
        self.good(self.invoke("CoNfIg", "uBuNtU", "-u", "alice", "-w", "/home/alice", "-e", "X=saved",
                              "-b", str(source) + ":/shared"))
        config = self.root / ".pdn-config"
        self.assertEqual(config.stat().st_mode & 0o777, 0o600)
        shown = self.invoke("config", "--rootfs", str(self.root), "--show")
        self.good(shown)
        data = json.loads(shown.stdout)
        self.assertEqual(data["env"], ["X=saved"])
        self.assertEqual(data["user"], "alice")
        self.good(self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c",
                              'pwd; echo "$USER:$X"; /bin/busybox cat /shared/value'), "/home/alice\nalice:saved\nbound\n")
        self.good(self.invoke("exec", "ubuntu", "-u", "0", "-w", "/", "-e", "X=cli", "--", "/bin/sh", "-c",
                              'pwd; echo "$USER:$X"'), "/\nroot:cli\n")
        self.good(self.invoke("exec", "ubuntu", "--no-config", "--", "/bin/sh", "-c",
                              'echo "$USER:${X-unset}"'), "root:unset\n")
        self.good(self.invoke("config", "ubuntu", "-e", "NEW=only"))
        data = json.loads(self.invoke("config", "ubuntu").stdout)
        self.assertIsNone(data["user"])
        self.assertIsNone(data["work-dir"])
        self.assertEqual(data["bind"], [])
        self.assertEqual(data["env"], ["NEW=only"])
        self.good(self.invoke("config", "ubuntu", "--clear"))
        self.assertFalse(config.exists())
        self.good(self.invoke("config", "ubuntu", "--clear"))

    def test_config_exact_unicode_control_values_and_limits(self):
        value = 'TEXT=你好\n\t"\\=last'
        self.good(self.invoke("config", "ubuntu", "-e", value, "-e", "OTHER=one", "-e", "OTHER=two"))
        shown = self.invoke("config", "ubuntu", "--show")
        self.good(shown)
        self.assertEqual(json.loads(shown.stdout)["env"], [value, "OTHER=two"])
        self.good(self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c", 'printf "%s" "$TEXT"'), value[5:])
        original = (self.root / ".pdn-config").read_bytes()
        self.assertEqual(self.invoke("config", "ubuntu", "-e", "BIG=" + "x" * 32768).returncode, 2)
        self.assertEqual(self.invoke("config", "ubuntu", "-e", "A=" + "x" * 17000,
                                     "-e", "B=" + "x" * 17000).returncode, 2)
        self.assertEqual((self.root / ".pdn-config").read_bytes(), original)
        args = [part for i in range(257) for part in ("-e", f"VAR{i}=value")]
        self.assertEqual(self.invoke("config", "ubuntu", *args).returncode, 2)

    def test_passwd_failures_and_numeric_without_database(self):
        passwd = self.root / "etc/passwd"
        for text in ("alice:x:bad:1:x:/home/alice:/bin/sh\n", "alice:x:1:bad:x:/home/alice:/bin/sh\n",
                     "alice:x:1:2:x:relative:/bin/sh\n", "alice:x:1:2:x:/home/alice:relative\n",
                     "alice:x:1:2:x:/home/alice:/bin/sh:extra\n", "alice:x:1\n", "alice\x00:x:1:2:x:/:/bin/sh\n",
                     "alice:x:1:2:x:/:/bin/sh\nalice:x:1:2:x:/:/bin/sh\n"):
            passwd.write_text(text)
            self.assertEqual(self.invoke("login", "ubuntu", "-u", "alice").returncode, 2)
        passwd.unlink()
        self.good(self.invoke("exec", "ubuntu", "-u", "6000", "--", "/bin/sh", "-c",
                              '/bin/busybox id -u; /bin/busybox id -g'), "6000\n6000\n")
        self.assertEqual(self.invoke("login", "ubuntu", "-u", "alice").returncode, 2)
        external = self.base / "external-passwd"
        external.write_text("alice:x:1:2:x:/:/bin/sh\n")
        passwd.symlink_to(external)
        self.assertEqual(self.invoke("login", "ubuntu", "-u", "alice").returncode, 2)
        passwd.unlink()
        os.mkfifo(passwd)
        self.assertEqual(self.invoke("login", "ubuntu", "-u", "6000").returncode, 2)

    def test_environment_cannot_change_wrapper_arguments_or_tracer(self):
        self.good(self.invoke("exec", "ubuntu", "-e", "PROOT_TMP_DIR=/not-present", "-e", "IFS=x",
                              "-e", "HOME=/home/alice", "-e", "USER=override", "-e", "pdn_work=/not-present",
                              "-w", "/root", "--", "/bin/sh", "-c",
                              'pwd; printf "%s:%s:%s" "$HOME" "$USER" "$PROOT_TMP_DIR"'),
                  "/root\n/home/alice:override:/not-present")
        (self.root / "bin/bash").write_text('#!/bin/sh\nprintf "%s" "$SHELL"\n')
        (self.root / "bin/bash").chmod(0o755)
        self.good(self.invoke("login", "ubuntu", "-e", "SHELL=exact"), "exact")

    def test_config_fail_closed_and_rescue(self):
        config = self.root / ".pdn-config"
        for contents in (b"bad", b"PDN1\ne:00\n", b"PDN1\nz:4142\n", b"PDN1\ne:zz\n", b"PDN1\ne:4\n", b"PDN1\ne:413d42", b"PDN1\ne4142\n", b"PDN1\ne:58\n", b"PDN1\nu:61\nu:62\n", b"PDN1\nb:2f613a2f623a63\n", b"x" * 65537):
            config.write_bytes(contents)
            self.assertEqual(self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c", "echo BAD").returncode, 2)
            self.assertEqual(self.invoke("config", "ubuntu", "--show").returncode, 2)
            self.good(self.invoke("exec", "ubuntu", "--no-config", "--", "/bin/sh", "-c", "echo rescue"), "rescue\n")
        self.good(self.invoke("config", "ubuntu", "--clear"))
        outside = self.base / "outside"
        outside.write_text("keep")
        config.symlink_to(outside)
        for command in (("config", "ubuntu", "--show"), ("config", "ubuntu", "--clear"),
                        ("config", "ubuntu", "-e", "X=ok"), ("login", "ubuntu")):
            self.assertEqual(self.invoke(*command).returncode, 2)
        self.assertEqual(outside.read_text(), "keep")
        self.assertTrue(config.is_symlink())

    def test_config_atomic_failure_and_locks(self):
        self.good(self.invoke("config", "ubuntu", "-e", "X=original"))
        original = (self.root / ".pdn-config").read_bytes()
        for args in (("-e", "BAD"), ("-u", "missing-user"), ("-w", "relative"), ("-b", "/not-present"),
                     ("--show", "-e", "X=x"), ("--clear", "-e", "X=x"), ("--no-config",), ("--", "/bin/sh")):
            self.assertEqual(self.invoke("config", "ubuntu", *args).returncode, 2)
            self.assertEqual((self.root / ".pdn-config").read_bytes(), original)
        fd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
        try:
            fcntl.flock(fd, fcntl.LOCK_SH)
            self.assertEqual(self.invoke("config", "ubuntu", "-e", "X=locked").returncode, 2)
            self.assertEqual(self.invoke("config", "ubuntu", "--clear").returncode, 2)
            self.good(self.invoke("config", "ubuntu", "--show"))
        finally:
            os.close(fd)
        self.assertEqual((self.root / ".pdn-config").read_bytes(), original)
        self.good(self.invoke("config", "ubuntu", "-e", "X=next"))
        self.assertEqual(list(self.root.glob(".pdn-config.*")), [])

    def test_config_bind_verification_and_cli_priority(self):
        first, second = self.base / "first", self.base / "second"
        first.mkdir()
        second.mkdir()
        (first / "value").write_text("first\n")
        (second / "value").write_text("second\n")
        self.good(self.invoke("config", "ubuntu", "-b", str(first) + ":/shared"))
        self.good(self.invoke("exec", "ubuntu", "-b", str(second) + ":/shared", "--",
                              "/bin/busybox", "cat", "/shared/value"), "second\n")
        (first / "value").unlink()
        first.rmdir()
        self.assertEqual(self.invoke("login", "ubuntu").returncode, 2)
        self.good(self.invoke("exec", "ubuntu", "--no-config", "--", "/bin/sh", "-c", "echo okay"), "okay\n")


if __name__ == "__main__":
    unittest.main()
