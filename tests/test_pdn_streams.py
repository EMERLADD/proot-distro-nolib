import io
import os
import subprocess
import tarfile
import unittest

from test_pdn import BINARY, PROJECT
import test_pdn as fixture


class StreamTests(unittest.TestCase):
    setUp = fixture.PdnTests.setUp

    def test_backup_stdout_and_restore_stdin_roundtrip(self):
        (self.root / 'root/message').write_text('stream roundtrip\n')
        result = subprocess.run([str(BINARY), 'backup', 'ubuntu', '-'], env=self.env,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(result.stdout.startswith(b'\x1f\x8b'))
        with tarfile.open(fileobj=io.BytesIO(result.stdout), mode='r:gz') as archive:
            self.assertEqual(archive.extractfile('root/message').read(), b'stream roundtrip\n')
        restored = subprocess.run([str(BINARY), 'restore', 'from-stream', '-'], input=result.stdout,
                                  env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        self.assertEqual(restored.returncode, 0, restored.stderr)
        self.assertEqual((self.root.parent / 'from-stream/root/message').read_text(), 'stream roundtrip\n')

    def test_broken_output_pipe_reports_failure(self):
        readfd, writefd = os.pipe()
        os.close(readfd)
        try:
            result = subprocess.run([str(BINARY), 'backup', 'ubuntu', '-'], env=self.env,
                                    stdout=writefd, stderr=subprocess.PIPE, timeout=20)
        finally:
            os.close(writefd)
        self.assertGreater(result.returncode, 0, result.stderr)
        self.assertTrue((self.root / 'bin/sh').is_symlink())

    def test_truncated_restore_rolls_back(self):
        result = subprocess.run([str(BINARY), 'restore', 'broken', '-'], input=b'not a tar archive',
                                env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root.parent / 'broken').exists())
        self.assertEqual(list(self.root.parent.glob('.pdn-restore-*')), [])

    def test_install_stdin_validates_pinned_archive(self):
        archive = PROJECT / 'examples/aar-probe/app/src/main/assets/alpine-rootfs.archive'
        if not archive.is_file():
            self.skipTest('offline Alpine fixture unavailable')
        result = subprocess.run([str(BINARY), 'install', 'alpine', '--name', 'stream-alpine', '--archive', '-'],
                                input=archive.read_bytes(), env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue(os.path.lexists(self.root.parent / 'stream-alpine/bin/sh'))

    def test_install_rejects_corrupt_stdin_and_cleans_staging(self):
        result = subprocess.run([str(BINARY), 'install', 'alpine', '--archive', '-'], input=b'bad archive',
                                env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=20)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse((self.root.parent / 'alpine').exists())
        self.assertEqual(list(self.root.parent.glob('.pdn-install-*')), [])


if __name__ == '__main__':
    unittest.main()
