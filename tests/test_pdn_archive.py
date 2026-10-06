import fcntl
import io
import os
from pathlib import Path
import subprocess
import time
import tarfile
import unittest

import test_pdn as fixture


class ArchiveTests(unittest.TestCase):
    setUp = fixture.PdnTests.setUp
    invoke = fixture.PdnTests.invoke
    good = fixture.PdnTests.good

    def archive(self, name, entries):
        path = self.base / name
        with tarfile.open(path, 'w:gz') as output:
            for entry, data in entries:
                output.addfile(entry, io.BytesIO(data) if data else None)
        return path

    def entry(self, name, data=b'', kind=tarfile.REGTYPE, target=''):
        item = tarfile.TarInfo(name)
        item.type = kind
        item.linkname = target
        item.size = len(data)
        item.mode = 0o755 if kind == tarfile.DIRTYPE else 0o644
        return item, data

    def test_roundtrip_and_restored_login(self):
        payload = bytes(range(256)) * 1000
        (self.root / 'root/data').write_bytes(payload)
        (self.root / 'root/relative').symlink_to('data')
        (self.root / 'root/absolute').symlink_to('/root/data')
        (self.root / 'root/broken').symlink_to('/no/such/file')
        (self.root / 'root/empty').write_text('')
        (self.root / 'root/data').chmod(0o640)
        os.utime(self.root / 'root/data', (1700000000, 1700000000))
        archive = self.base / 'backup with spaces.tar.gz'
        self.good(self.invoke('BaCkUp', 'UBUNTU', str(archive)))
        self.good(self.invoke('ReStOrE', 'Saved', str(archive)))
        restored = self.root.parent / 'Saved'
        self.assertEqual((restored / 'root/data').read_bytes(), payload)
        self.assertEqual((restored / 'root/data').stat().st_mode & 0o777, 0o640)
        self.assertEqual(int((restored / 'root/data').stat().st_mtime), 1700000000)
        self.assertEqual(os.readlink(restored / 'root/absolute'), '/root/data')
        self.assertTrue((restored / 'root/broken').is_symlink())
        self.good(self.invoke('exec', 'saved', '--', '/bin/sh', '-c', 'echo restored; /bin/busybox id -u'), 'restored\n0\n')
        self.assertEqual((self.root / 'root/data').read_bytes(), payload)
        self.assertEqual(list(self.root.parent.glob('.pdn-restore-*')), [])

    def test_guest_hardlinks_survive_rootfs_move(self):
        self.good(self.invoke('exec', 'ubuntu', '--', '/bin/sh', '-c',
                              'echo original > /root/a; /bin/busybox ln /root/a /root/b'))
        archive = self.base / 'linked.tar.gz'
        self.good(self.invoke('backup', 'ubuntu', str(archive)))
        with tarfile.open(archive) as source:
            for entry in source.getmembers():
                if entry.issym():
                    self.assertNotIn(str(self.root), entry.linkname)
        self.good(self.invoke('restore', 'moved', str(archive)))
        self.root.rename(self.root.with_name('original-offline'))
        self.good(self.invoke('exec', 'moved', '--', '/bin/sh', '-c',
                              '/bin/busybox cat /root/a /root/b; echo updated > /root/a; '
                              '/bin/busybox cat /root/b; /bin/busybox ln /root/b /root/c; '
                              '/bin/busybox rm /root/a; /bin/busybox cat /root/b /root/c'),
                  'original\noriginal\nupdated\nupdated\nupdated\n')

    def test_excludes_host_configuration_and_runtime(self):
        (self.root / '.pdn-config').write_text('host paths')
        (self.root / '.pdn-config-old').write_text('host paths')
        (self.root / '.pdn-tmp').mkdir()
        (self.root / '.pdn-tmp/loader').write_text('temporary')
        (self.root / 'dev').mkdir()
        (self.root / 'dev/runtime').write_text('not persistent')
        os.mkfifo(self.root / 'root/fifo')
        outside = self.base / 'outside'
        outside.mkdir()
        (outside / 'secret').write_text('outside')
        (self.root / 'root/external').symlink_to(outside)
        archive = self.base / 'backup.tar.gz'
        self.good(self.invoke('backup', 'ubuntu', str(archive)))
        with tarfile.open(archive) as source:
            names = source.getnames()
            self.assertIn('dev', names)
            for name in ('.pdn-config', '.pdn-config-old', '.pdn-tmp', 'dev/runtime', 'root/fifo', 'root/external/secret'):
                self.assertNotIn(name, names)
            self.assertTrue(source.getmember('root/external').issym())

    def test_existing_files_and_names_preserved(self):
        archive = self.base / 'backup.tar.gz'
        archive.write_text('keep')
        self.assertNotEqual(self.invoke('backup', 'ubuntu', str(archive)).returncode, 0)
        self.assertEqual(archive.read_text(), 'keep')
        for name in ('ubuntu', 'UBUNTU'):
            self.assertNotEqual(self.invoke('restore', name, str(archive)).returncode, 0)
        archive.unlink()
        self.good(self.invoke('backup', 'ubuntu', str(archive)))
        (self.root.parent / 'saved').symlink_to(self.root)
        self.assertNotEqual(self.invoke('restore', 'SAVED', str(archive)).returncode, 0)
        self.assertTrue((self.root / 'bin/busybox').exists())
        link = self.base / 'out-link'
        link.symlink_to(self.base / 'absent')
        self.assertNotEqual(self.invoke('backup', 'ubuntu', str(link)).returncode, 0)
        self.assertTrue(link.is_symlink())

    def test_backup_rejects_inside_root_and_unsafe_root(self):
        for output in (self.root / 'backup.tar.gz', self.root / 'root/backup.tar.gz'):
            self.assertNotEqual(self.invoke('backup', 'ubuntu', str(output)).returncode, 0)
            self.assertFalse(output.exists())
        link = self.base / 'root-link'
        link.symlink_to(self.root)
        self.assertNotEqual(self.invoke('backup', 'ubuntu', str(link / 'backup.tar.gz')).returncode, 0)
        (self.root.parent / 'alias').symlink_to(self.root)
        self.assertNotEqual(self.invoke('backup', 'alias', str(self.base / 'b')).returncode, 0)
        (self.root.parent / 'ubuntu').mkdir()
        self.assertNotEqual(self.invoke('backup', 'Ubuntu', str(self.base / 'b')).returncode, 0)

    def test_archive_locks(self):
        output = self.base / 'b.tar.gz'
        with (self.root.parent / '.pdn-install.lock').open('w') as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            for command in ('backup', 'restore'):
                self.assertNotEqual(self.invoke(command, 'ubuntu', str(output)).returncode, 0)
        fd = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
        try:
            fcntl.flock(fd, fcntl.LOCK_SH)
            self.assertNotEqual(self.invoke('backup', 'ubuntu', str(output)).returncode, 0)
            self.assertFalse(output.exists())
        finally:
            os.close(fd)
        self.good(self.invoke('backup', 'ubuntu', str(output)))

    def test_malicious_archives_leave_no_root(self):
        outside = self.base / 'outside'
        outside.write_text('keep')
        cases = [
            [self.entry('../escaped', b'bad')],
            [self.entry(str(outside), b'bad')],
            [self.entry('dir/../../escaped', b'bad')],
            [self.entry('link', kind=tarfile.SYMTYPE, target=str(self.base)), self.entry('link/outside', b'bad')],
            [self.entry('link', kind=tarfile.LNKTYPE, target='../outside')],
            [self.entry('link', kind=tarfile.LNKTYPE, target='link')],
            [self.entry('device', kind=tarfile.CHRTYPE)],
            [self.entry('fifo', kind=tarfile.FIFOTYPE)],
            [self.entry('not-linux', b'data')],
        ]
        huge = self.entry('huge')[0]
        huge.size = 9 * 1024**3
        for i, entries in enumerate(cases):
            archive = self.archive(f'unsafe-{i}.tar.gz', entries)
            self.assertNotEqual(self.invoke('restore', 'unsafe', str(archive)).returncode, 0)
            self.assertFalse((self.root.parent / 'unsafe').exists())
            self.assertEqual(list(self.root.parent.glob('.pdn-restore-*')), [])
            self.assertEqual(outside.read_text(), 'keep')
        raw = self.base / 'huge.tar'
        raw.write_bytes(huge.tobuf() + bytes(1024))
        self.assertNotEqual(self.invoke('restore', 'unsafe', str(raw)).returncode, 0)

    def test_restore_filters_config_and_converts_hardlinks(self):
        entries = [self.entry('./', kind=tarfile.DIRTYPE), self.entry('./bin/', kind=tarfile.DIRTYPE),
                   self.entry('bin/original', b'data'), self.entry('bin/alias', kind=tarfile.LNKTYPE, target='bin/original'),
                   self.entry('.pdn-config', b'unsafe saved settings'), self.entry('.pdn-tmp/loader', b'ignore')]
        archive = self.archive('custom.tar.gz', entries)
        self.good(self.invoke('restore', 'custom', str(archive)))
        root = self.root.parent / 'custom'
        self.assertTrue((root / 'bin/alias').is_symlink())
        self.assertEqual((root / 'bin/alias').read_text(), 'data')
        self.assertFalse((root / '.pdn-config').exists())
        self.assertFalse((root / '.pdn-tmp').exists())

    def test_invalid_inputs_and_base(self):
        for command in ('backup', 'restore'):
            for args in ((), ('ubuntu',), ('ubuntu', 'a', 'extra'), ('../x', 'a'), ('', 'a'), ('x', '')):
                self.assertNotEqual(self.invoke(command, *args).returncode, 0)
            self.good(self.invoke(command, '--help'))
        for contents in (b'', b'not an archive', bytes(1024)):
            archive = self.base / 'bad'
            archive.write_bytes(contents)
            self.assertNotEqual(self.invoke('restore', 'bad', str(archive)).returncode, 0)
        self.assertNotEqual(self.invoke('restore', 'bad', str(self.base / 'missing')).returncode, 0)
        os.mkfifo(self.base / 'fifo')
        self.assertNotEqual(self.invoke('restore', 'bad', str(self.base / 'fifo')).returncode, 0)
        self.env.pop('PDN_ROOTFS_DIR')
        self.assertNotEqual(self.invoke('backup', 'ubuntu', str(self.base / 'a')).returncode, 0)
        self.env['PDN_ROOTFS_DIR'] = '/'
        self.assertNotEqual(self.invoke('restore', 'x', str(self.base / 'bad')).returncode, 0)

    def test_backup_error_cleans_partial_archive(self):
        denied = self.root / 'root/denied'
        denied.mkdir()
        denied.chmod(0)
        output = self.base / 'b.tar.gz'
        try:
            self.assertNotEqual(self.invoke('backup', 'ubuntu', str(output)).returncode, 0)
            self.assertFalse(output.exists())
            self.assertEqual(list(self.base.glob('.pdn-backup-*')), [])
        finally:
            denied.chmod(0o700)

    def test_cancellation_cleans_backup_and_restore(self):
        with (self.root / 'root/large').open('wb') as stream:
            stream.truncate(256 * 1024 * 1024)
        output = self.base / 'cancel.tar.gz'
        for command in ('backup', 'restore'):
            name = 'ubuntu' if command == 'backup' else 'cancelled'
            process = subprocess.Popen([str(fixture.BINARY), command, name, str(output)],
                                       env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            try:
                parent = self.base if command == 'backup' else self.root.parent
                pattern = '.pdn-backup-*' if command == 'backup' else '.pdn-restore-*'
                deadline = time.monotonic() + 5
                while not list(parent.glob(pattern)) and process.poll() is None and time.monotonic() < deadline:
                    time.sleep(0.001)
                self.assertIsNone(process.poll(), 'operation ended before cancellation')
                process.terminate()
                stdout, stderr = process.communicate(timeout=20)
                self.assertEqual(process.returncode, 143, stdout + stderr)
                self.assertEqual(list(parent.glob(pattern)), [])
            finally:
                if process.poll() is None:
                    process.kill()
                process.communicate(timeout=10)
            if command == 'backup':
                self.assertFalse(output.exists())
                self.good(self.invoke('backup', 'ubuntu', str(output)))
            else:
                self.assertFalse((self.root.parent / 'cancelled').exists())

    def test_truncated_archive_is_not_published(self):
        archive = self.base / 'source.tar.gz'
        self.good(self.invoke('backup', 'ubuntu', str(archive)))
        data = archive.read_bytes()
        archive.write_bytes(data[:len(data) // 2])
        self.assertNotEqual(self.invoke('restore', 'truncated', str(archive)).returncode, 0)
        self.assertFalse((self.root.parent / 'truncated').exists())
        self.assertEqual(list(self.root.parent.glob('.pdn-restore-*')), [])

    def test_restore_creates_new_base(self):
        archive = self.base / 'backup.tar.gz'
        self.good(self.invoke('backup', 'ubuntu', str(archive)))
        self.env['PDN_ROOTFS_DIR'] = str(self.base / 'new/roots')
        self.good(self.invoke('restore', 'portable', str(archive)))
        self.good(self.invoke('exec', 'portable', '--', '/bin/sh', '-c', 'echo moved'), 'moved\n')


if __name__ == '__main__':
    unittest.main()
