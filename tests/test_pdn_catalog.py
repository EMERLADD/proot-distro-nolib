import json
import os
import signal
import subprocess
import time
import unittest

import test_pdn as fixture


class CatalogTests(unittest.TestCase):
    setUp = fixture.PdnTests.setUp
    invoke = fixture.PdnTests.invoke
    invoke_events = fixture.PdnTests.invoke_events

    def data(self, key, *args):
        result = self.invoke(*args)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stderr, "")
        data = json.loads(result.stdout)
        self.assertEqual(data["version"], 1)
        self.assertIsInstance(data[key], list)
        return data[key]

    def test_available_metadata(self):
        rows = self.data("distributions", "list", "--available", "--json")
        self.assertEqual([row["name"] for row in rows], ["alpine", "ubuntu", "debian", "arch"])
        self.assertEqual(rows[0]["version"], "3.24.2")
        self.assertEqual(rows[0]["download_size"], 4028030)
        self.assertTrue(all(row["architecture"] == "aarch64" for row in rows))
        self.assertEqual(rows, self.data("distributions", "LS", "--JSON", "--AVAILABLE"))

    def test_installed_metadata_filters_and_order(self):
        for name in ("alpine", ".hidden", "bad name"):
            (self.root.parent / name).mkdir()
        (self.root.parent / "regular").write_text("file")
        rows = self.data("distributions", "list", "--json")
        self.assertEqual([row["name"] for row in rows], ["Ubuntu", "alpine"])
        self.assertEqual(rows[0]["rootfs"], str(self.root))
        self.assertNotIn("version", rows[0])

    def test_missing_parent_is_empty(self):
        self.env["PDN_ROOTFS_DIR"] = str(self.base / "absent")
        self.assertEqual(self.data("distributions", "list", "--json"), [])

    def test_empty_existing_parent_is_empty(self):
        empty = self.base / "empty"
        empty.mkdir()
        self.env["PDN_ROOTFS_DIR"] = str(empty)
        self.assertEqual(self.data("distributions", "list", "--json"), [])

    def test_rootfs_path_json_escaping(self):
        parent = self.base / 'quote" slash\\ newline\n制表\t'
        self.root.parent.rename(parent)
        self.env["PDN_ROOTFS_DIR"] = str(parent)
        rows = self.data("distributions", "list", "--json")
        self.assertEqual(rows, [{"name": "Ubuntu", "rootfs": str(parent / "Ubuntu")}])

    def test_mirrors_metadata(self):
        rows = self.data("mirrors", "mirrors", "alpine", "--json")
        self.assertEqual([row["priority"] for row in rows], list(range(len(rows))))
        self.assertTrue(all(row["distro"] == "alpine" and row["url"].startswith(row["base_url"]) for row in rows))
        self.assertEqual([row["name"] for row in rows if row["official"]], ["official"])
        self.assertEqual(rows, self.data("mirrors", "mirrors", "--json", "ALPINE"))
        debian = self.data("mirrors", "mirrors", "debian", "--json")
        self.assertEqual(len(debian), 1)
        self.assertTrue(debian[0]["official"])
        all_rows = self.data("mirrors", "mirrors", "--json")
        self.assertEqual({row["distro"] for row in all_rows}, {"alpine", "ubuntu", "debian", "arch"})

    def test_data_and_events_remain_separate(self):
        result, events = self.invoke_events("list", "--available", "--json")
        self.assertEqual(result.returncode, 0)
        self.assertEqual(len(json.loads(result.stdout)["distributions"]), 4)
        self.assertEqual(events[0]["type"], "started")
        self.assertEqual(events[-1]["type"], "result")
        self.assertEqual(events[-1]["outcome"], "success")

    def test_invalid_arguments_and_unknown_distro(self):
        for args, code in ((["mirrors", "absent", "--json"], "distro_unknown"),
                           (["mirrors", "--json", "--json"], "invalid_argument"),
                           (["list", "--json", "--json"], "invalid_argument"),
                           (["list", "--json", "--extra"], "invalid_argument")):
            with self.subTest(args=args):
                result, events = self.invoke_events(*args)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(events[-1]["code"], code)

    def test_sigterm_reaps_guest_process_tree(self):
        pid_file = self.root / "root" / "child-pids"
        script = "sleep 120 & child=$!; printf '%s %s' $$ $child > /root/child-pids; wait"
        process = subprocess.Popen([str(fixture.BINARY), "exec", "Ubuntu", "--", "/bin/sh", "-c", script],
                                   env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            deadline = time.monotonic() + 5
            while not pid_file.exists() and process.poll() is None and time.monotonic() < deadline:
                time.sleep(0.02)
            self.assertTrue(pid_file.exists(), "guest command did not start")
            pids = [int(value) for value in pid_file.read_text().split()]
            self.assertEqual(len(pids), 2)
            process.send_signal(signal.SIGTERM)
            process.communicate(timeout=5)
            for pid in pids:
                with self.assertRaises(ProcessLookupError):
                    os.kill(pid, 0)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate(timeout=5)

    def test_human_output_unchanged(self):
        self.assertEqual(self.invoke("list").stdout, "Ubuntu\n")
        self.assertIn("Available ARM64 systems", self.invoke("list", "--available").stdout)
        self.assertIn("alpine ARM64 rootfs mirrors", self.invoke("mirrors", "alpine").stdout)


if __name__ == "__main__":
    unittest.main()
