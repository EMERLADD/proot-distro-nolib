import json
import os
import signal
import stat
import shutil
import subprocess
import tempfile
import unittest

import test_pdn as fixture


def read_events(path):
    data = path.read_bytes()
    if not data:
        raise AssertionError("native event channel is empty")
    if not data.endswith(b"\n"):
        raise AssertionError("native event channel ends with a partial record")
    lines = data.decode("utf-8").splitlines()
    if any(len(line.encode("utf-8")) > 16384 for line in lines):
        raise AssertionError("native event record exceeds 16 KiB")
    return [json.loads(line) for line in lines]


def assert_events(case, path, operation, operation_id, status, outcome):
    events = read_events(path)
    case.assertEqual([event["sequence"] for event in events], list(range(1, len(events) + 1)))
    for event in events:
        case.assertEqual(event["version"], 1)
        case.assertEqual(event["operation"], operation)
        case.assertEqual(event["operation_id"], operation_id)
        case.assertIn(event["type"], ("started", "stage", "progress", "error", "result"))
    case.assertEqual(events[0]["type"], "started")
    results = [event for event in events if event["type"] == "result"]
    case.assertEqual(len(results), 1)
    case.assertEqual(events[-1], results[0])
    case.assertEqual(results[0]["exit_code"], status & 255)
    case.assertEqual(results[0]["outcome"], outcome)
    return events


class EventTests(unittest.TestCase):
    setUp = fixture.PdnTests.setUp
    invoke = fixture.PdnTests.invoke

    def channel(self, operation_id="native-test_1.0"):
        path = self.base / ("events-" + str(len(list(self.base.glob("events-*")))))
        path.write_bytes(b"")
        path.chmod(0o600)
        self.env.update(PDN_EVENT_FILE=str(path), PDN_OPERATION_ID=operation_id)
        return path

    def events(self, path, operation, result, outcome):
        return assert_events(self, path, operation, self.env["PDN_OPERATION_ID"], result.returncode, outcome)

    def test_version_channel_preserves_output_and_private_mode(self):
        plain = self.invoke("--version")
        path = self.channel()
        result = self.invoke("--version")
        self.assertEqual((result.returncode, result.stdout, result.stderr),
                         (plain.returncode, plain.stdout, plain.stderr))
        self.events(path, "version", result, "success")
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_new_channel_and_maximum_operation_id(self):
        path = self.base / "created-events"
        self.env.update(PDN_EVENT_FILE=str(path), PDN_OPERATION_ID="a" * 64)
        result = self.invoke("--version")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.events(path, "version", result, "success")
        self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)

    def test_guest_status_and_streams(self):
        for status in (0, 37, 127, 143):
            with self.subTest(status=status):
                path = self.channel()
                result = self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c",
                                     f'echo guest; echo diagnostic >&2; exit {status}')
                self.assertEqual(result.returncode, status, result.stderr)
                self.assertEqual(result.stdout, "guest\n")
                self.assertEqual(result.stderr, "diagnostic\n")
                events = self.events(path, "exec", result, "success" if status == 0 else "guest_exit")
                self.assertEqual(events[-1]["guest_exit_code"], status)
                self.assertNotIn("guest_signal", events[-1])

    def test_guest_signal_is_distinct_from_exit_143(self):
        baseline = self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c", "kill -TERM $$")
        path = self.channel()
        result = self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c", "kill -TERM $$")
        self.assertEqual(result.returncode, baseline.returncode, result.stderr)
        events = self.events(path, "exec", result, "guest_exit")
        self.assertEqual(events[-1]["guest_signal"], signal.SIGTERM)

    def test_primary_guest_status_preserves_legacy_background_child_exit(self):
        command = "(/bin/busybox sleep 0.2; exit 37) & exit 0"
        baseline = self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c", command)
        path = self.channel()
        result = self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c", command)
        self.assertEqual(result.returncode, baseline.returncode, result.stderr)
        events = self.events(path, "exec", result, "success" if result.returncode == 0 else "guest_exit")
        self.assertEqual(events[-1]["guest_exit_code"], 0)

    def test_startup_failure_missing_rootfs(self):
        path = self.channel()
        result = self.invoke("exec", "missing", "--", "/bin/sh")
        self.assertNotEqual(result.returncode, 0)
        events = self.events(path, "exec", result, "manager_error")
        errors = [event for event in events if event["type"] == "error"]
        self.assertTrue(errors)
        self.assertTrue(any(event.get("code") and event.get("suggestion") for event in errors))
        self.assertNotIn("guest_exit_code", events[-1])

    def test_startup_failure_temp_path(self):
        (self.root / ".pdn-tmp").write_text("blocked")
        path = self.channel()
        result = self.invoke("exec", "ubuntu", "--", "/bin/sh")
        self.assertNotEqual(result.returncode, 0)
        events = self.events(path, "exec", result, "manager_error")
        self.assertTrue(any(event["type"] == "error" for event in events))

    def test_missing_shell_is_startup_failure(self):
        (self.root / "bin/sh").unlink()
        path = self.channel()
        result = self.invoke("login", "ubuntu", input="exit\n")
        self.assertNotEqual(result.returncode, 0)
        self.events(path, "login", result, "manager_error")

    def test_guest_command_not_found_is_guest_exit(self):
        path = self.channel()
        result = self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c", "missing-guest-command")
        self.assertEqual(result.returncode, 127)
        events = self.events(path, "exec", result, "guest_exit")
        self.assertEqual(events[-1]["guest_exit_code"], 127)

    def test_json_stdout_is_raw_and_secrets_are_excluded(self):
        path = self.channel()
        secret = "argv-secret-7d79"
        self.env["TEST_SECRET"] = "env-secret-7481"
        raw = '{"version":1,"type":"result","outcome":"success"}'
        result = self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c", 'printf "%s\\n" "$1"; echo "$2"',
                             "marker", raw, secret)
        self.assertEqual(result.stdout, raw + "\n" + secret + "\n")
        self.events(path, "exec", result, "success")
        data = path.read_text()
        self.assertNotIn(secret, data)
        self.assertNotIn(self.env["TEST_SECRET"], data)
        self.assertNotIn(raw, data)

    def test_guest_protocol_environment_is_removed(self):
        path = self.channel()
        result = self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c",
                             'echo "${PDN_EVENT_FILE-unset}:${PDN_OPERATION_ID-unset}"')
        self.assertEqual(result.stdout, "unset:unset\n")
        self.events(path, "exec", result, "success")

    def test_guest_event_descriptor_is_closed(self):
        path = self.channel()
        result = self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c",
                             'for fd in /proc/self/fd/*; do /bin/busybox readlink "$fd"; done; exit 0')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn(str(path), result.stdout)
        self.events(path, "exec", result, "success")

    def test_operation_ids_correlate_independent_channels(self):
        for operation_id in ("operation-a", "operation-b"):
            path = self.channel(operation_id)
            result = self.invoke("--version")
            self.assertEqual(result.returncode, 0, result.stderr)
            self.events(path, "version", result, "success")

    def test_invalid_ids_fail_before_guest_side_effects(self):
        for operation_id in ("", "a" * 65, "contains space", "bad\nline", "bad/segment", "nonascii-猫"):
            with self.subTest(operation_id=operation_id):
                path = self.channel(operation_id)
                result = self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c", "echo changed > /sentinel")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("operation", result.stderr.lower())
                self.assertFalse((self.root / "sentinel").exists())
                self.assertEqual(path.read_bytes(), b"")

    def test_bad_channels_fail_without_mutation_or_hanging(self):
        target = self.base / "target"
        target.write_bytes(b"")
        target.chmod(0o600)
        link = self.base / "link"
        link.symlink_to(target)
        directory = self.base / "directory"
        directory.mkdir()
        shared = self.base / "shared"
        shared.write_bytes(b"")
        shared.chmod(0o644)
        nonempty = self.base / "nonempty"
        nonempty.write_bytes(b"preserve")
        nonempty.chmod(0o600)
        fifo = self.base / "fifo"
        os.mkfifo(fifo, 0o600)
        for path in (link, directory, shared, nonempty, fifo, self.base / "absent/events"):
            with self.subTest(path=path.name):
                self.env.update(PDN_EVENT_FILE=str(path), PDN_OPERATION_ID="reject-channel")
                result = self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c", "echo changed > /sentinel")
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("event", result.stderr.lower())
                self.assertFalse((self.root / "sentinel").exists())
        self.assertEqual(target.read_bytes(), b"")
        self.assertEqual(nonempty.read_bytes(), b"preserve")

    def test_no_channel_preserves_guest_behavior(self):
        self.env["PDN_OPERATION_ID"] = "ignored invalid id"
        result = self.invoke("exec", "ubuntu", "--", "/bin/sh", "-c", "echo raw; echo error >&2; exit 41")
        self.assertEqual((result.returncode, result.stdout, result.stderr), (41, "raw\n", "error\n"))

    def test_backup_restore_events_and_progress(self):
        (self.root / "root/payload").write_bytes(bytes(range(256)) * 8192)
        archive = self.base / "backup.tar.gz"
        for operation, args in (("backup", ("ubuntu", str(archive))), ("restore", ("restored", str(archive)))):
            path = self.channel()
            result = self.invoke(operation, *args)
            self.assertEqual(result.returncode, 0, result.stderr)
            events = self.events(path, operation, result, "success")
            self.assertTrue(any(event["type"] == "stage" for event in events))
            progress = [event for event in events if event["type"] == "progress"]
            self.assertTrue(progress)
            for event in progress:
                self.assertGreaterEqual(event["current"], 0)
                if "percent" in event:
                    self.assertGreaterEqual(event["percent"], 0)
                    self.assertLessEqual(event["percent"], 100)
        self.assertEqual((self.root.parent / "restored/root/payload").read_bytes(), (self.root / "root/payload").read_bytes())


class EmitterTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.shared = tempfile.TemporaryDirectory(prefix="pdn-events-fixture-", dir=fixture.PROJECT / "build")
        cls.addClassCleanup(cls.shared.cleanup)
        directory = fixture.Path(cls.shared.name)
        source = directory / "harness.c"
        source.write_text(r'''#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>
#include "pdn_events.h"
int main(int argc, char **argv) {
    const char *mode = argc > 1 ? argv[1] : "success";
    int status = pdn_events_begin(!strcmp(mode, "operation-filter") ? "install" : "exec");
    if (status) return status;
    if (!strcmp(mode, "atexit")) return 1;
    if (!strcmp(mode, "escaping")) {
        char message[2001], advice[2001];
        memset(message, 'x', sizeof(message) - 1);
        memset(advice, 'y', sizeof(advice) - 1);
        memcpy(message, "quote\" slash\\ newline\n tab\t control\001 ",
               sizeof("quote\" slash\\ newline\n tab\t control\001 ") - 1);
        memcpy(advice, "建议\n", 7);
        message[2000] = advice[2000] = 0;
        pdn_events_problem("escaped", message, advice);
        status = 2;
    } else if (!strcmp(mode, "utf8")) {
        pdn_events_problem("utf8", "bad:\377:\300\257:\355\240\200:\364\220\200\200:end",
                           "valid:建议 猫 😀; bad:\200");
        status = 2;
    } else if (!strcmp(mode, "boundary")) {
        char message[520];
        memset(message, 'x', 510);
        memcpy(message + 510, "猫end", 7);
        pdn_events_problem("boundary", message, "advice");
        status = 2;
    } else if (!strcmp(mode, "progress")) {
        pdn_events_stage("known");
        pdn_events_progress(-1, 10000);
        for (int i = 0; i <= 10000; i++) pdn_events_progress(i, 10000);
        pdn_events_stage("unknown");
        pdn_events_progress(0, -1);
        pdn_events_progress(1, -1);
        usleep(300000);
        pdn_events_progress(1048576, -1);
        pdn_events_stage("zero-total");
        pdn_events_progress(0, 0);
        pdn_events_stage("overshoot");
        pdn_events_progress(2, 1);
    } else if (!strcmp(mode, "progress-cap")) {
        for (int i = 0; i < 1100; i++) {
            pdn_events_stage("repeated");
            pdn_events_progress(i, -1);
        }
    } else if (!strcmp(mode, "disable-child")) {
        pid_t child = fork();
        if (child == 0) {
            pdn_events_disable();
            pdn_events_stage("child");
            pdn_events_problem("child", "must not escape", "child");
            pdn_events_guest_loaded();
            pdn_events_guest_exit(37, 0);
            pdn_events_finish(37);
            exit(37);
        }
        int child_status;
        if (child < 0 || waitpid(child, &child_status, 0) < 0) return 3;
    } else if (!strcmp(mode, "cancelled")) {
        pdn_events_cancelled(2);
        status = 130;
    } else if (!strcmp(mode, "guest-signal")) {
        pdn_events_guest_loaded();
        pdn_events_guest_loaded();
        pdn_events_guest_exit(-1, 15);
        status = 255;
    } else if (!strcmp(mode, "guest-failure")) {
        pdn_events_guest_exit(31, 0);
        pdn_events_guest_loaded();
        pdn_events_guest_exit(37, 0);
        status = 37;
    } else if (!strcmp(mode, "guest-success")) {
        pdn_events_guest_loaded();
        pdn_events_guest_exit(0, 0);
    } else if (!strcmp(mode, "manager-default")) {
        status = 2;
    } else if (!strcmp(mode, "operation-filter")) {
        pdn_events_guest_loaded();
        pdn_events_guest_exit(37, 0);
    } else if (!strncmp(mode, "error:", 6)) {
        pdn_events_stage(mode + 6);
        pdn_events_error("fixture diagnostic");
        status = 2;
    } else if (!strncmp(mode, "system:", 7)) {
        errno = EINTR;
        pdn_events_system_problem("file_io_failed", "fixture I/O failure", "fixture advice", atoi(mode + 7));
        if (errno != EINTR || !pdn_events_has_error()) return 3;
        status = 2;
    } else if (!strcmp(mode, "preserve-specific") || !strcmp(mode, "preserve-atexit")) {
        pdn_events_problem("archive_unsafe", "unsafe entry", "use a safe archive");
        pdn_events_error("generic wrapper");
        if (!strcmp(mode, "preserve-atexit")) return 1;
        status = 2;
    } else if (!strcmp(mode, "replace-attempt")) {
        pdn_events_problem("connection_failed", "first mirror", "retry");
        pdn_events_problem("http_error", "second mirror", "check source");
        pdn_events_error("all mirrors failed");
        status = 2;
    } else if (!strcmp(mode, "recover-after-error")) {
        pdn_events_problem("connection_failed", "first mirror", "retry");
        pdn_events_clear_error();
        if (pdn_events_has_error()) return 3;
    } else if (!strcmp(mode, "failure-after-recovery")) {
        pdn_events_problem("connection_failed", "first mirror", "retry");
        pdn_events_clear_error();
        pdn_events_stage("configuring");
        pdn_events_error("configuration failed after recovery");
        status = 2;
    } else if (!strcmp(mode, "errno")) {
        errno = ENOTDIR;
        pdn_events_stage("preparing");
        pdn_events_progress(0, -1);
        pdn_events_problem("directory", "fixture diagnostic", "fixture advice");
        if (errno != ENOTDIR) return 3;
    }
    pdn_events_finish(status);
    pdn_events_finish(99);
    pdn_events_stage("after-result");
    return status;
}
''')
        ndk = fixture.Path(os.environ["NDK_PATH"])
        toolchain, = ndk.glob("toolchains/llvm/prebuilt/*")
        resource, = (toolchain / "lib/clang").iterdir()
        compiler = os.environ.get("CC", "clang")
        flags = ["--target=aarch64-linux-android24", f"--sysroot={toolchain / 'sysroot'}",
                 f"-resource-dir={resource}", "-D_GNU_SOURCE"]
        if "-fno-termux-rpath" in subprocess.check_output([compiler, "--help"], text=True):
            flags.append("-fno-termux-rpath")
        if os.environ.get("PDN_COVERAGE"):
            flags += ["-fprofile-instr-generate", "-fcoverage-mapping"]
            runtime = subprocess.check_output([compiler, "-print-resource-dir"], text=True).strip()
            flags += [f"{runtime}/lib/linux/libclang_rt.profile-aarch64-android.a"]
        cls.harness = directory / "harness"
        native = fixture.PROJECT / "src/proot/src/cli"
        subprocess.run([compiler, *flags, str(source), str(native / "pdn_events.c"),
                        f"-I{native}", "-o", str(cls.harness)], check=True)
        if os.environ.get("PDN_COVERAGE"):
            shutil.copy2(cls.harness, fixture.PROJECT / "build/pdn-events-coverage-harness")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pdn-emitter-test-", dir=fixture.PROJECT / "build")
        self.addCleanup(self.temp.cleanup)
        self.base = fixture.Path(self.temp.name)
        self.path = self.base / "events.jsonl"
        self.env = {"PATH": "/system/bin", "PDN_EVENT_FILE": str(self.path), "PDN_OPERATION_ID": "emitter-fixture"}
        if "LLVM_PROFILE_FILE" in os.environ:
            self.env["LLVM_PROFILE_FILE"] = os.environ["LLVM_PROFILE_FILE"]

    def invoke(self, mode="success"):
        return subprocess.run([str(self.harness), mode], env=self.env, capture_output=True, text=True, timeout=5)

    def records(self, mode="success", outcome="success", operation="exec"):
        result = self.invoke(mode)
        events = assert_events(self, self.path, operation, "emitter-fixture", result.returncode, outcome)
        self.assertEqual(result.stdout, "")
        return events

    def test_exactly_one_result_and_ignored_post_finish_calls(self):
        events = self.records()
        self.assertEqual([event["type"] for event in events], ["started", "result"])

    def test_escaped_and_bounded_diagnostic(self):
        events = self.records("escaping", "manager_error")
        error = events[1]
        self.assertIn('quote" slash\\ newline\n tab\t control\x01 ', error["message"])
        self.assertTrue(error["suggestion"].startswith("建议\n"))
        self.assertLessEqual(len(error["message"].encode()), 512)
        self.assertLessEqual(len(error["suggestion"].encode()), 512)
        self.assertEqual(events[-1]["message"], error["message"])

    def test_invalid_utf8_is_replaced(self):
        error = self.records("utf8", "manager_error")[1]
        self.assertIn("\ufffd", error["message"])
        self.assertIn("valid:建议 猫 😀", error["suggestion"])
        self.assertIn("\ufffd", error["suggestion"])
        self.assertTrue(error["message"].endswith(":end"))

    def test_truncation_preserves_utf8_boundary(self):
        error = self.records("boundary", "manager_error")[1]
        self.assertEqual(error["message"], "x" * 510)

    def test_progress_throttles_known_and_unknown_totals(self):
        events = self.records("progress")
        progress = [event for event in events if event["type"] == "progress"]
        known = [event for event in progress if event["stage"] == "known"]
        self.assertGreaterEqual(len(known), 2)
        self.assertLess(len(known), 20)
        self.assertEqual(known[0]["current"], 0)
        self.assertEqual(known[-1]["current"], 10000)
        self.assertEqual(known[-1]["percent"], 100)
        unknown = [event for event in progress if event["stage"] == "unknown"]
        self.assertEqual([event["current"] for event in unknown], [0, 1048576])
        self.assertTrue(all(event["total"] == -1 and "percent" not in event for event in unknown))
        zero = [event for event in progress if event["stage"] == "zero-total"]
        self.assertEqual(len(zero), 1)
        self.assertNotIn("percent", zero[0])

    def test_successful_guest_metadata(self):
        events = self.records("guest-success")
        self.assertEqual(events[-1]["guest_exit_code"], 0)

    def test_manager_failure_without_prior_diagnostic(self):
        events = self.records("manager-default", "manager_error")
        self.assertEqual(events[-1]["code"], "manager_failed")
        self.assertEqual(events[-2]["type"], "error")

    def test_system_errno_mapping_and_errno_preservation(self):
        import errno
        codes = [(errno.EACCES, "file_permission"), (errno.EPERM, "file_permission"),
                 (errno.EROFS, "file_read_only"), (errno.ENOSPC, "storage_full"),
                 (errno.EDQUOT, "storage_full"), (errno.ENOENT, "file_missing"),
                 (errno.ENOMEM, "out_of_memory"), (errno.EIO, "file_io_failed"),
                 (0, "file_io_failed")]
        for value, code in codes:
            with self.subTest(value=value):
                self.path.unlink(missing_ok=True)
                events = self.records(f"system:{value}", "manager_error")
                self.assertEqual(events[-1]["code"], code)
                self.assertTrue(events[-1]["suggestion"])
                if value:
                    self.assertIn(f"errno={value}", events[-1]["message"])

    def test_generic_wrapper_and_atexit_preserve_specific_error(self):
        for mode in ("preserve-specific", "preserve-atexit"):
            with self.subTest(mode=mode):
                self.path.unlink(missing_ok=True)
                events = self.records(mode, "manager_error")
                self.assertEqual(events[-1]["code"], "archive_unsafe")
                self.assertEqual(events[-1]["message"], "unsafe entry")
                self.assertEqual(sum(event["type"] == "error" for event in events), 1)

    def test_explicit_attempt_failure_replaces_previous_attempt(self):
        events = self.records("replace-attempt", "manager_error")
        self.assertEqual(events[-1]["code"], "http_error")
        self.assertEqual(sum(event["type"] == "error" for event in events), 2)

    def test_recovered_operation_has_no_final_error(self):
        events = self.records("recover-after-error")
        for key in ("code", "message", "suggestion"):
            self.assertNotIn(key, events[-1])

    def test_later_failure_does_not_reuse_recovered_diagnostic(self):
        events = self.records("failure-after-recovery", "manager_error")
        self.assertEqual(events[-1]["code"], "configuration_failed")
        self.assertEqual(events[-1]["message"], "configuration failed after recovery")

    def test_invalid_ids_rejected_before_file_creation(self):
        for value in ("", "x" * 65, "bad space", "bad/segment", "bad\nline", "猫"):
            with self.subTest(value=value):
                self.env["PDN_OPERATION_ID"] = value
                result = self.invoke()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("PDN_OPERATION_ID", result.stderr)
                self.assertFalse(self.path.exists())

    def test_unsafe_channel_paths_rejected(self):
        target = self.base / "target"
        target.write_bytes(b"")
        target.chmod(0o600)
        link = self.base / "link"
        link.symlink_to(target)
        directory = self.base / "directory"
        directory.mkdir()
        shared = self.base / "shared"
        shared.write_bytes(b"")
        shared.chmod(0o644)
        nonempty = self.base / "nonempty"
        nonempty.write_bytes(b"preserve")
        nonempty.chmod(0o600)
        fifo = self.base / "fifo"
        os.mkfifo(fifo, 0o600)
        for path in (link, directory, shared, nonempty, fifo, self.base / "missing/events"):
            with self.subTest(path=path.name):
                self.env["PDN_EVENT_FILE"] = str(path)
                result = self.invoke()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("event channel", result.stderr)
        self.assertEqual(nonempty.read_bytes(), b"preserve")
        self.assertEqual(target.read_bytes(), b"")

    def test_progress_has_operation_wide_cap(self):
        events = self.records("progress-cap")
        self.assertEqual(sum(event["type"] == "progress" for event in events), 1024)

    def test_atexit_fallback_reports_manager_failure(self):
        events = self.records("atexit", "manager_error")
        self.assertEqual(events[-1]["exit_code"], 1)
        self.assertTrue(any(event["type"] == "error" for event in events))

    def test_fork_child_disables_channel_and_atexit(self):
        events = self.records("disable-child")
        self.assertEqual([event["type"] for event in events], ["started", "result"])
        self.assertNotIn("guest_exit_code", events[-1])

    def test_cancelled_result(self):
        self.records("cancelled", "cancelled")

    def test_guest_exit_and_signal_observations(self):
        for mode, field, value in (("guest-failure", "guest_exit_code", 37),
                                   ("guest-signal", "guest_signal", 15)):
            self.path.unlink(missing_ok=True)
            events = self.records(mode, "guest_exit")
            self.assertEqual(events[-1][field], value)
            self.assertEqual(sum(event["type"] == "stage" for event in events), 1)

    def test_guest_observations_ignore_install_operation(self):
        events = self.records("operation-filter", operation="install")
        self.assertNotIn("guest_exit_code", events[-1])
        self.assertEqual([event["type"] for event in events], ["started", "result"])

    def test_error_diagnostics_classify_current_stage(self):
        for stage, code in (("downloading", "download_failed"), ("verifying", "verification_failed"),
                            ("extracting", "extraction_failed"), ("configuring", "configuration_failed"),
                            ("backing_up", "archive_failed"), ("restoring", "archive_failed"),
                            ("preparing", "manager_failed")):
            with self.subTest(stage=stage):
                self.path.unlink(missing_ok=True)
                events = self.records("error:" + stage, "manager_error")
                self.assertEqual(events[-1]["code"], code)
                self.assertTrue(events[-1]["suggestion"])

    def test_event_writes_preserve_errno(self):
        self.records("errno")

    def test_disabled_channel_preserves_stdout_and_no_files(self):
        self.env.pop("PDN_EVENT_FILE")
        result = self.invoke("escaping")
        self.assertEqual((result.returncode, result.stdout, result.stderr), (2, "", ""))
        self.assertFalse(self.path.exists())

    def test_default_operation_id(self):
        self.env.pop("PDN_OPERATION_ID")
        result = self.invoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        events = read_events(self.path)
        self.assertRegex(events[0]["operation_id"], r"^pdn-[0-9]+$")
        self.assertEqual(events[-1]["operation_id"], events[0]["operation_id"])

    def test_special_device_channel_rejected(self):
        self.env["PDN_EVENT_FILE"] = "/dev/full"
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("event channel", result.stderr)

    def test_hardlinked_channel_rejected(self):
        self.path.write_bytes(b"")
        self.path.chmod(0o600)
        result = subprocess.run(["/system/bin/ln", str(self.path), str(self.base / "alias")],
                                capture_output=True, text=True)
        if result.returncode != 0:
            self.skipTest("hard links unavailable: " + result.stderr.strip())
        result = self.invoke()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(self.path.read_bytes(), b"")


if __name__ == "__main__":
    unittest.main()
