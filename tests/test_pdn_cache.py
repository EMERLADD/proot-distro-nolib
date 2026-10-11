import ctypes
import errno
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[1]
BINARY = Path(os.environ.get('PROOT_NOLIB_BINARY', PROJECT / 'build/proot-distro-nolib/arm64/proot-distro-nolib')).resolve()
ALPINE_SHA = '9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773'
ALPINE_URL = 'https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz'
UBUNTU_SHA = 'a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2'


class CacheTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='pdn-cache-', dir=PROJECT / 'build')
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name)
        self.roots = self.base / 'rootfs with spaces'
        self.cache = self.roots / '.pdn-downloads'
        self.env = {'PATH': '/system/bin', 'PDN_ROOTFS_DIR': str(self.roots)}
        if 'LLVM_PROFILE_FILE' in os.environ:
            self.env['LLVM_PROFILE_FILE'] = os.environ['LLVM_PROFILE_FILE']

    def invoke(self, *args, output=None):
        return subprocess.run([str(BINARY), 'cache', *args], env=self.env, cwd=self.base,
                              text=True, stdout=output or subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=10)

    def seed(self, sha=ALPINE_SHA, url=ALPINE_URL, content=b'cached', mtime=1720000000):
        self.cache.mkdir(parents=True, exist_ok=True)
        target = self.cache / f'{sha}-{hashlib.sha256(url.encode()).hexdigest()}.part'
        target.write_bytes(content)
        os.utime(target, (mtime, mtime))
        return target

    def hardlink(self, source, target):
        libc = ctypes.CDLL(None, use_errno=True)
        link = libc.link
        link.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
        link.restype = ctypes.c_int
        if link(os.fsencode(source), os.fsencode(target)) != 0:
            code = ctypes.get_errno()
            if code in (errno.ENOSYS, errno.EPERM, errno.EACCES, errno.EOPNOTSUPP):
                self.skipTest(f'filesystem cannot create hard links: {os.strerror(code)}')
            raise OSError(code, os.strerror(code))

    def listing(self, *args):
        result = self.invoke('list', *args, '--json')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        return json.loads(result.stdout)

    def error(self, code, *args):
        events = self.base / 'events.jsonl'
        self.env['PDN_EVENT_FILE'] = str(events)
        try:
            result = self.invoke(*args)
        finally:
            self.env.pop('PDN_EVENT_FILE')
        self.assertNotEqual(result.returncode, 0, result.stdout)
        records = [json.loads(line) for line in events.read_text().splitlines()]
        errors = [event for event in records if event['type'] == 'error']
        self.assertEqual(len(errors), 1, records)
        self.assertEqual(errors[0]['code'], code, records)
        self.assertEqual(records[-1]['code'], code, records)
        self.assertEqual(records[-1]['outcome'], 'manager_error', records)
        self.assertEqual(records[-1]['exit_code'], result.returncode, records)
        self.assertTrue(errors[0].get('suggestion'), records)
        events.unlink()
        return result, errors[0]

    def test_missing_rootfs_is_empty_without_creating(self):
        data = self.listing()
        self.assertEqual(data, {'version': 1, 'path': str(self.cache), 'total_bytes': 0, 'entries': []})
        self.assertFalse(self.roots.exists())
        result = self.invoke('clear', '--yes')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(self.roots.exists())

    def test_missing_cache_preserves_rootfs_without_lock_creation(self):
        (self.roots / 'ubuntu').mkdir(parents=True)
        (self.roots / 'ubuntu' / 'keep').write_text('keep')
        self.assertEqual(self.listing()['entries'], [])
        result = self.invoke('clear', '--yes')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sorted(p.name for p in self.roots.iterdir()), ['ubuntu'])

    def test_json_metadata_file_size_timestamp_and_text(self):
        partial = self.seed(content=b'x' * 37)
        data = self.listing('ALPINE')
        self.assertEqual(data['path'], str(self.cache))
        self.assertEqual(data['total_bytes'], 37)
        self.assertEqual(data['entries'], [{'id': partial.name, 'distro': 'alpine', 'mirror': 'official',
                                           'bytes': 37, 'total': 4028030, 'updated_at': 1720000000}])
        text = self.invoke('list')
        self.assertEqual(text.returncode, 0, text.stderr)
        self.assertIn(str(self.cache), text.stdout)
        self.assertIn('37 bytes', text.stdout)
        self.assertIn('official', text.stdout)
        self.assertIn('4028030 expected bytes', text.stdout)
        self.assertIn('updated_at=1720000000', text.stdout)
        self.assertEqual(partial.read_bytes(), b'x' * 37)

    def test_preepoch_timestamp_is_zero(self):
        partial = self.seed()
        try:
            os.utime(partial, (-1, -1))
        except (OSError, OverflowError):
            self.skipTest('filesystem does not support preepoch timestamps')
        if partial.stat().st_mtime >= 0:
            self.skipTest('filesystem clamps preepoch timestamps')
        self.assertEqual(self.listing()['entries'][0]['updated_at'], 0)
        self.assertIn('updated_at=0', self.invoke('list').stdout)

    def test_filters_stale_entries_unknown_mirrors_and_stable_sort(self):
        alpine = self.seed(content=b'123')
        custom = self.seed(url='https://unknown.example.invalid/archive', content=b'12345')
        ubuntu = self.seed(sha=UBUNTU_SHA, content=b'12')
        stale = self.seed(sha='a' * 64, content=b'1234')
        data = self.listing()
        self.assertEqual(data['total_bytes'], 14)
        self.assertEqual([entry['id'] for entry in data['entries']], sorted([alpine.name, custom.name, ubuntu.name, stale.name]))
        unknown = next(entry for entry in data['entries'] if entry['id'] == stale.name)
        self.assertEqual((unknown['distro'], unknown['mirror'], unknown['total']), (None, None, None))
        unknown_mirror = next(entry for entry in data['entries'] if entry['id'] == custom.name)
        self.assertEqual((unknown_mirror['distro'], unknown_mirror['mirror']), ('alpine', None))
        filtered = self.listing('alpine')
        self.assertEqual(filtered['total_bytes'], 8)
        self.assertEqual(len(filtered['entries']), 2)

    def test_clear_filtered_preserves_other_entries_and_rootfs(self):
        alpine = self.seed()
        ubuntu = self.seed(sha=UBUNTU_SHA)
        stale = self.seed(sha='a' * 64)
        unrelated = self.cache / 'notes.txt'
        unrelated.write_text('keep')
        (self.roots / 'ubuntu').mkdir()
        (self.roots / 'ubuntu' / 'keep').write_text('guest')
        result = self.invoke('clear', 'AlPiNe', '--yes')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse(alpine.exists())
        self.assertTrue(ubuntu.exists())
        self.assertTrue(stale.exists())
        self.assertEqual(unrelated.read_text(), 'keep')
        self.assertEqual((self.roots / 'ubuntu' / 'keep').read_text(), 'guest')
        result = self.invoke('clear', '--yes')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sorted(p.name for p in self.cache.iterdir()), ['notes.txt'])

    def test_unrelated_entries_are_untouched(self):
        partial = self.seed()
        names = ['notes', 'f' * 64 + '-' + 'F' * 64 + '.part', partial.name + '.extra', '.hidden']
        for name in names:
            (self.cache / name).write_text('keep')
        (self.cache / 'unrelated-dir').mkdir()
        (self.cache / 'unrelated-link').symlink_to('missing')
        self.assertEqual(len(self.listing()['entries']), 1)
        result = self.invoke('clear', '--yes')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(sorted(p.name for p in self.cache.iterdir()), sorted(names + ['unrelated-dir', 'unrelated-link']))

    def test_unknown_distro_and_confirmation(self):
        partial = self.seed()
        self.error('distro_unknown', 'list', 'unsupported', '--json')
        self.error('distro_unknown', 'clear', 'unsupported', '--yes')
        self.error('invalid_argument', 'clear')
        self.assertTrue(partial.exists())

    def test_install_exclusive_lock_blocks_list_and_clear(self):
        self.seed()
        with open(self.roots / '.pdn-install.lock', 'w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            self.error('operation_busy', 'list', '--json')
            self.error('operation_busy', 'clear', '--yes')

    def test_shared_list_lock_permits_list_but_blocks_clear(self):
        self.seed()
        with open(self.roots / '.pdn-install.lock', 'w') as lock:
            fcntl.flock(lock, fcntl.LOCK_SH)
            self.assertEqual(len(self.listing()['entries']), 1)
            self.error('operation_busy', 'clear', '--yes')

    def test_managed_symlink_directory_fifo_and_hardlink_refused_before_deletion(self):
        good = self.seed()
        bad = self.cache / ('0' * 64 + '-' + '0' * 64 + '.part')
        outside = self.base / 'outside'
        outside.write_text('outside')
        for kind in ('symlink', 'directory', 'fifo', 'hardlink'):
            with self.subTest(kind=kind):
                if kind == 'symlink':
                    bad.symlink_to(outside)
                elif kind == 'directory':
                    bad.mkdir()
                elif kind == 'fifo':
                    os.mkfifo(bad)
                else:
                    self.hardlink(outside, bad)
                self.error('cache_unsafe', 'list', '--json')
                self.error('cache_unsafe', 'clear', '--yes')
                self.assertTrue(good.exists())
                self.assertEqual(outside.read_text(), 'outside')
                if kind == 'directory':
                    bad.rmdir()
                else:
                    bad.unlink()

    def test_unselected_unsafe_entry_ignored_by_filter(self):
        good = self.seed()
        bad = self.cache / (UBUNTU_SHA + '-' + '0' * 64 + '.part')
        bad.symlink_to(good)
        self.assertEqual(len(self.listing('alpine')['entries']), 1)
        result = self.invoke('clear', 'alpine', '--yes')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(bad.is_symlink())

    def test_cache_directory_symlink_rejected(self):
        self.roots.mkdir()
        outside = self.base / 'outside'
        outside.mkdir()
        self.cache.symlink_to(outside, target_is_directory=True)
        self.error('cache_unsafe', 'list', '--json')
        self.error('cache_unsafe', 'clear', '--yes')
        self.assertEqual(list(outside.iterdir()), [])

    def test_not_directory_rootfs_and_cache(self):
        self.roots.write_text('keep')
        self.error('directory_not_directory', 'list', '--json')
        self.roots.unlink()
        self.roots.mkdir()
        self.cache.write_text('keep')
        self.error('directory_not_directory', 'list', '--json')
        self.error('directory_not_directory', 'clear', '--yes')
        self.assertEqual(self.cache.read_text(), 'keep')

    def test_lock_directory_fifo_hardlink_rejected(self):
        self.seed()
        lock = self.roots / '.pdn-install.lock'
        outside = self.base / 'outside'
        outside.write_text('keep')
        with self.subTest(kind='hardlink'):
            self.hardlink(outside, lock)
            self.error('cache_unsafe', 'list', '--json')
            lock.unlink()
        os.mkfifo(lock)
        self.error('cache_unsafe', 'list', '--json')
        lock.unlink()
        lock.mkdir()
        result = self.invoke('clear', '--yes')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(outside.read_text(), 'keep')

    def test_symlink_lock_refused_without_touching_target(self):
        partial = self.seed()
        outside = self.base / 'outside'
        outside.write_text('keep')
        (self.roots / '.pdn-install.lock').symlink_to(outside)
        self.error('cache_unsafe', 'list', '--json')
        self.error('cache_unsafe', 'clear', '--yes')
        self.assertEqual(outside.read_text(), 'keep')
        self.assertTrue(partial.exists())

    @unittest.skipIf(os.geteuid() == 0, 'requires unprivileged directory access')
    def test_cache_and_base_permissions(self):
        self.seed()
        self.cache.chmod(0)
        try:
            self.error('directory_permission', 'list', '--json')
        finally:
            self.cache.chmod(0o700)
        self.roots.chmod(0)
        try:
            self.error('directory_permission', 'list', '--json')
        finally:
            self.roots.chmod(0o700)

    def test_home_default_and_no_configured_location(self):
        self.env.pop('PDN_ROOTFS_DIR')
        self.env['HOME'] = str(self.base)
        self.assertEqual(self.listing()['path'], str(self.base / '.local/share/pdn/rootfs/.pdn-downloads'))
        self.assertFalse((self.base / '.local').exists())
        self.env.pop('HOME')
        self.error('directory_missing', 'list', '--json')

    def test_success_event_sequence(self):
        self.seed()
        events = self.base / 'events.jsonl'
        self.env['PDN_EVENT_FILE'] = str(events)
        result = self.invoke('clear', '--yes')
        self.assertEqual(result.returncode, 0, result.stderr)
        records = [json.loads(line) for line in events.read_text().splitlines()]
        self.assertTrue(all(event['operation'] == 'cache' for event in records))
        self.assertEqual(records[-1]['type'], 'result')
        self.assertEqual(records[-1]['outcome'], 'success')

    def test_relative_base_returns_absolute_cache_location(self):
        self.env['PDN_ROOTFS_DIR'] = 'relative rootfs'
        self.assertEqual(self.listing()['path'], str(self.base / 'relative rootfs' / '.pdn-downloads'))
        self.assertFalse((self.base / 'relative rootfs').exists())

    @unittest.skipIf(os.geteuid() == 0, 'requires unprivileged directory access')
    def test_unlink_failure_reports_errno_and_preserves_cache(self):
        partial = self.seed()
        self.cache.chmod(0o500)
        try:
            result, event = self.error('file_permission', 'clear', '--yes')
            self.assertTrue(partial.exists())
            self.assertIn('cleanup may be incomplete', result.stderr)
            self.assertIn('errno=', event['message'])
        finally:
            self.cache.chmod(0o700)

    def test_output_failure_reports_unsuccessful_status(self):
        partial = self.seed()
        with partial.open('r') as output:
            result = self.invoke('list', '--json', output=output)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('cannot write download cache result', result.stderr)
        self.assertEqual(partial.read_bytes(), b'cached')


if __name__ == '__main__':
    unittest.main()
