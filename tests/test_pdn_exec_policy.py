import os
import shutil
import test_pdn as pdn


import unittest
class PdnExecPolicyTests(unittest.TestCase):
    setUp = pdn.PdnTests.setUp
    invoke = pdn.PdnTests.invoke
    invoke_events = pdn.PdnTests.invoke_events
    good = pdn.PdnTests.good
    error_code = pdn.PdnTests.error_code

    def program(self):
        (self.root / "tools").mkdir()
        shutil.copy2(self.root / "bin/busybox", self.root / "tools/busybox")
        (self.root / "allowed").mkdir()
        (self.root / "other").mkdir()

    def test_directory_rule_and_child_exec(self):
        self.program()
        for seccomp in (None, "1"):
            if seccomp is None: self.env.pop("PROOT_NO_SECCOMP", None)
            else: self.env["PROOT_NO_SECCOMP"] = seccomp
            for cwd, expected in (("/allowed", 0), ("/other", 126)):
                result, events = self.invoke_events("exec", "ubuntu", "--exec-cwd", "/tools/busybox", "/allowed",
                    "-w", cwd, "--", "/bin/sh", "-c", "exec /tools/busybox echo ran")
                self.assertEqual(result.returncode, expected, result.stderr)
                denials = [e for e in events if e["type"] == "execution_denied"]
                self.assertEqual(len(denials), int(expected != 0), events)
                if expected:
                    self.assertNotIn("ran", result.stdout)
                    self.assertEqual(denials[0]["code"], "policy_exec_denied")
                    self.assertEqual(denials[0]["cwd"], cwd)
                    self.assertEqual(events[-1]["denied_executions"], 1)
                else: self.assertEqual(result.stdout, "ran\n")

    def test_relative_symlink_and_dotdot_cannot_skip_rule(self):
        self.program()
        (self.root / "bin/alias").symlink_to("../tools/busybox")
        for program in ("/bin/alias", "../tools/busybox", "/bin/../tools/busybox"):
            result = self.invoke("exec", "ubuntu", "--deny-exec", "/tools/busybox", "-w", "/other",
                "--", "/bin/sh", "-c", 'exec "$1" echo BAD', "test", program)
            self.assertEqual(result.returncode, 126, result.stderr)
            self.assertNotIn("BAD", result.stdout)

    def test_multiple_directories_deny_precedence_and_success_after_refusal(self):
        self.program()
        arguments = ("exec", "ubuntu", "--exec-cwd", "/tools/busybox", "/allowed",
                     "--exec-cwd", "/tools/busybox", "/other", "-w", "/other")
        self.good(self.invoke(*arguments, "--", "/tools/busybox", "echo", "ok"), "ok\n")
        self.assertEqual(self.invoke(*arguments, "--deny-exec", "/tools/busybox", "--",
                                    "/tools/busybox", "echo", "BAD").returncode, 126)
        result, events = self.invoke_events("exec", "ubuntu", "--deny-exec", "/tools/busybox", "--",
            "/bin/sh", "-c", "/tools/busybox echo BAD; echo survived")
        self.good(result, "survived\n")
        self.assertEqual(events[-1]["outcome"], "success")
        self.assertEqual(events[-1]["denied_executions"], 1)

    def test_shebang_and_interpreter_and_interactive(self):
        self.program()
        script = self.root / "bin/script"
        script.write_text("#!/tools/busybox sh\necho BAD\n")
        script.chmod(0o755)
        self.assertNotEqual(self.invoke("exec", "ubuntu", "--deny-exec", "/tools/busybox", "--",
                                       "/bin/script").returncode, 0)
        middle = self.root / "bin/middle"
        middle.write_text("#!/bin/sh\necho BAD\n")
        middle.chmod(0o755)
        script.write_text("#!/bin/middle\necho BAD\n")
        self.assertNotEqual(self.invoke("exec", "ubuntu", "--deny-exec", "/bin/middle", "--",
                                       "/bin/script").returncode, 0)
        script.write_text("#!/bin/sh\necho BAD\n")
        self.assertNotEqual(self.invoke("exec", "ubuntu", "--deny-exec", "/bin/script", "--",
                                       "/bin/script").returncode, 0)
        result = self.invoke("login", "ubuntu", "--deny-exec", "/tools/busybox",
                             input="/tools/busybox echo BAD\necho survived\nexit\n")
        self.good(result, "survived\n")

    def test_bind_and_symlink_directory_match_is_exact(self):
        self.program()
        (self.root / "allowed/sub").mkdir()
        (self.root / "allowed-alias").symlink_to("allowed")
        for cwd, expected in (("/allowed-alias", 0), ("/allowed/sub", 126)):
            result = self.invoke("exec", "ubuntu", "--exec-cwd", "/tools/busybox", "/allowed", "-w", cwd,
                                 "--", "/tools/busybox", "echo", "ran")
            self.assertEqual(result.returncode, expected, result.stderr)
        bound = self.base / "bound"
        bound.mkdir()
        self.good(self.invoke("exec", "ubuntu", "-b", str(bound) + ":/workspace",
            "--exec-cwd", "/tools/busybox", "/workspace", "-w", "/workspace", "--",
            "/tools/busybox", "echo", "ok"), "ok\n")

    def test_denied_startup_shell_has_specific_error(self):
        result, events = self.invoke_events("exec", "ubuntu", "--deny-exec", "/bin/sh", "--", "/bin/sh")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(events[-1]["code"], "policy_exec_denied")
        self.assertEqual(events[-1]["outcome"], "manager_error")

    def test_directory_is_checked_after_chdir_and_in_forked_children(self):
        self.program()
        result, events = self.invoke_events("exec", "ubuntu", "--exec-cwd", "/tools/busybox", "/allowed",
            "-w", "/allowed", "--", "/bin/sh", "-c",
            "(cd /other; /tools/busybox echo BAD); /tools/busybox echo OK")
        self.good(result, "OK\n")
        self.assertEqual(events[-1]["denied_executions"], 1)

    def test_policy_is_optional_and_invalid_rules_fail(self):
        self.program()
        self.good(self.invoke("exec", "ubuntu", "--", "/tools/busybox", "echo", "ok"), "ok\n")
        for path in ("target", "/bin/../target", "/bin//target", "/tools/busybox/", "/" + "a" * 512):
            self.error_code("invalid_argument", "exec", "ubuntu", "--deny-exec", path, "--", "/bin/sh")
        self.error_code("invalid_argument", "exec", "ubuntu", "--exec-cwd", "/tools/busybox")
        self.error_code("invalid_argument", "config", "ubuntu", "--deny-exec", "/tools/busybox")
