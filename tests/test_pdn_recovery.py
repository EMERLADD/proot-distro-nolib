import ctypes
import fcntl
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import unittest
import uuid

from test_pdn import BINARY, PROJECT


class RecoveryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='pdn-recovery-', dir=PROJECT / 'build')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.roots = self.base / 'roots with spaces'
        self.env = {'PATH': '/system/bin', 'PDN_ROOTFS_DIR': str(self.roots)}
        if 'LLVM_PROFILE_FILE' in os.environ:
            self.env['LLVM_PROFILE_FILE'] = os.environ['LLVM_PROFILE_FILE']

    def invoke(self, *args):
        return subprocess.run([str(BINARY), 'recovery', *args], env=self.env,
                              capture_output=True, text=True, timeout=20)

    def listing(self):
        result = self.invoke('list', '--json')
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def installation(self):
        process = subprocess.Popen([str(BINARY), 'install', 'alpine', '--archive', '-'], env=self.env,
                                   stdin=subprocess.PIPE, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
        def finish():
            if process.poll() is None:
                process.kill()
            process.wait(timeout=10)
            process.stdin.close()
            process.stderr.close()
        self.addCleanup(finish)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            for path in self.roots.glob('.pdn-install-*/.pdn-operation'):
                record = dict(line.split('=', 1) for line in path.read_text().splitlines())
                if record['phase'] == 'copying':
                    return process, path.parent, record
            if process.poll() is not None:
                self.fail(process.stderr.read().decode())
            time.sleep(.01)
        self.fail('installation did not persist staging identity')

    def killed(self):
        process, stage, record = self.installation()
        process.send_signal(signal.SIGKILL)
        process.wait(timeout=10)
        return stage, record

    def rewrite(self, stage, record):
        (stage / '.pdn-operation').write_text(''.join(f'{k}={v}\n' for k, v in record.items()))

    def test_actual_sigkill_cleanup_preserves_installed_rootfs_and_cache(self):
        stage, record = self.killed()
        self.assertEqual(len(record['id']), 32)
        self.assertEqual(stage.name, '.pdn-install-' + record['id'])
        self.assertGreater(int(record['pid']), 0)
        self.assertGreater(int(record['start_time']), 0)
        (self.roots / 'alpine').mkdir()
        (self.roots / 'alpine/keep').write_text('published')
        (self.roots / '.pdn-downloads').mkdir()
        (self.roots / '.pdn-downloads/keep').write_text('cache')
        outside = self.base / 'project'
        outside.mkdir()
        (outside / 'keep').write_text('project')
        (stage / 'external').symlink_to(outside)
        self.assertIn('id=' + record['id'], self.invoke('list').stdout)
        entries = self.listing()['entries']
        self.assertEqual(entries[0]['status'], 'interrupted')
        self.assertEqual(entries[0]['reason'], 'process_missing')
        self.assertEqual(self.invoke('clear', record['id'], '--yes').returncode, 0)
        self.assertFalse(stage.exists())
        self.assertEqual((outside / 'keep').read_text(), 'project')
        self.assertEqual((self.roots / 'alpine/keep').read_text(), 'published')
        self.assertEqual((self.roots / '.pdn-downloads/keep').read_text(), 'cache')

    def test_running_installation_blocks_listing_and_cleanup(self):
        process, stage, record = self.installation()
        for args in [('list', '--json'), ('clear', record['id'], '--yes')]:
            result = self.invoke(*args)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('cannot acquire recovery lock', result.stderr)
        self.assertTrue(stage.exists())

    def test_pid_reuse_identity_and_matching_live_process(self):
        stage, record = self.killed()
        own_stat = Path('/proc/self/stat').read_text().rsplit(')', 1)[1].split()
        record['pid'] = str(os.getpid())
        record['start_time'] = own_stat[19]
        self.rewrite(stage, record)
        self.assertIn(self.listing()['entries'][0]['status'], ('active', 'unknown'))
        self.assertNotEqual(self.invoke('clear', record['id'], '--yes').returncode, 0)
        record['start_time'] = str(int(own_stat[19]) + 1)
        self.rewrite(stage, record)
        self.assertEqual(self.listing()['entries'][0]['reason'], 'pid_reused')
        self.assertEqual(self.invoke('clear', '--yes').returncode, 0)
        self.assertFalse(stage.exists())

    def test_nondumpable_live_owner_is_never_cleaned(self):
        stage, record = self.killed()
        stat = Path('/proc/self/stat').read_text().rsplit(')', 1)[1].split()
        record['pid'] = str(os.getpid())
        record['start_time'] = stat[19]
        self.rewrite(stage, record)
        libc = ctypes.CDLL(None, use_errno=True)
        libc.prctl.argtypes = [ctypes.c_int, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong, ctypes.c_ulong]
        libc.prctl.restype = ctypes.c_int
        old = libc.prctl(3, 0, 0, 0, 0)
        if old not in (0, 1) or libc.prctl(4, 0, 0, 0, 0) != 0:
            self.skipTest('cannot change dumpability')
        try:
            self.assertIn(self.listing()['entries'][0]['status'], ('active', 'unknown'))
            result = self.invoke('clear', record['id'], '--yes')
            self.assertNotEqual(result.returncode, 0)
            self.assertTrue(stage.exists())
        finally:
            self.assertEqual(libc.prctl(4, old, 0, 0, 0), 0)

    def test_boot_change_is_classified_without_claiming_real_reboot(self):
        stage, record = self.killed()
        if not record['boot_id']:
            self.skipTest('boot ID unavailable')
        record['boot_id'] = str(uuid.uuid4())
        self.rewrite(stage, record)
        self.assertEqual(self.listing()['entries'][0]['reason'], 'boot_changed')

    def test_missing_boot_and_missing_identity_are_conservative(self):
        stage, record = self.killed()
        record['boot_id'] = ''
        self.rewrite(stage, record)
        self.assertEqual(self.listing()['entries'][0]['status'], 'interrupted')
        record['start_time'] = '0'
        self.rewrite(stage, record)
        self.assertEqual(self.listing()['entries'][0]['status'], 'unknown')
        self.assertNotEqual(self.invoke('clear', record['id'], '--yes').returncode, 0)
        self.assertTrue(stage.exists())

    def test_corrupt_and_legacy_staging_are_retained(self):
        stage, record = self.killed()
        (stage / '.pdn-operation').write_text('version=1\nversion=1\n')
        legacy = self.roots / '.pdn-alpine-ABC123'
        legacy.mkdir()
        (legacy / 'keep').write_text('legacy')
        self.assertEqual({e['status'] for e in self.listing()['entries']}, {'unknown'})
        result = self.invoke('clear', '--yes')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(stage.exists())
        self.assertTrue(legacy.exists())

    def test_stage_and_record_symlinks_are_not_followed(self):
        stage, record = self.killed()
        source = self.base / 'saved-record'
        (stage / '.pdn-operation').rename(source)
        (stage / '.pdn-operation').symlink_to(source)
        self.assertEqual(self.listing()['entries'][0]['status'], 'unknown')
        self.assertNotEqual(self.invoke('clear', record['id'], '--yes').returncode, 0)
        (stage / '.pdn-operation').unlink()
        source.rename(stage / '.pdn-operation')
        moved = self.base / 'moved-stage'
        stage.rename(moved)
        stage.symlink_to(moved, target_is_directory=True)
        self.assertEqual(self.listing()['entries'][0]['status'], 'unknown')
        self.assertNotEqual(self.invoke('clear', record['id'], '--yes').returncode, 0)
        self.assertTrue((moved / '.pdn-operation').exists())

    def test_missing_base_is_empty_and_confirmation_required(self):
        self.assertEqual(self.listing()['entries'], [])
        self.assertFalse(self.roots.exists())
        self.assertNotEqual(self.invoke('clear').returncode, 0)
        self.assertNotEqual(self.invoke('clear', '../escape', '--yes').returncode, 0)
        self.assertNotEqual(self.invoke('list', '--json', '--json').returncode, 0)
        self.assertEqual(self.invoke('clear', '--yes').returncode, 0)
        self.assertFalse(self.roots.exists())

    def test_active_guest_directory_lock_is_retained(self):
        stage, record = self.killed()
        root = stage / 'rootfs'
        root.mkdir()
        (root / 'keep').write_text('live')
        fd = os.open(root, os.O_RDONLY)
        try:
            fcntl.flock(fd, fcntl.LOCK_SH | fcntl.LOCK_NB)
            result = self.invoke('clear', record['id'], '--yes')
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual((root / 'keep').read_text(), 'live')
        finally:
            os.close(fd)
        result = self.invoke('clear', record['id'], '--yes')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(stage.exists())

    def test_unknown_id_and_global_lock(self):
        stage, record = self.killed()
        self.assertNotEqual(self.invoke('clear', 'a' * 32, '--yes').returncode, 0)
        with (self.roots / '.pdn-install.lock').open('r+') as lock:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.assertNotEqual(self.invoke('clear', '--yes').returncode, 0)
        self.assertTrue(stage.exists())

    def test_recovery_events_report_operation_and_real_result(self):
        stage, record = self.killed()
        channel = self.base / 'events.jsonl'
        self.env['PDN_EVENT_FILE'] = str(channel)
        self.env['PDN_OPERATION_ID'] = 'recovery-test'
        result = self.invoke('clear', record['id'], '--yes')
        self.assertEqual(result.returncode, 0, result.stderr)
        events = [json.loads(line) for line in channel.read_text().splitlines()]
        self.assertTrue(all(e['operation'] == 'recovery' for e in events))
        self.assertEqual(events[-1]['outcome'], 'success')
        self.assertEqual(events[-1]['exit_code'], 0)


if __name__ == '__main__':
    unittest.main()
