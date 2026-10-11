import hashlib
from pathlib import Path
import signal
import subprocess
import time
import unittest

import test_pdn_install as install


class ResumeTests(unittest.TestCase):
    setUpClass = classmethod(install.InstallTests.setUpClass.__func__)
    setUp = install.InstallTests.setUp
    run_harness = install.InstallTests.run_harness
    assert_clean = install.InstallTests.assert_clean
    event_records = install.InstallTests.event_records
    event_channel = install.InstallTests.event_channel
    assert_error_code = install.InstallTests.assert_error_code

    def cache_path(self, route):
        digest = hashlib.sha256(self.archive.read_bytes()).hexdigest()
        url_digest = hashlib.sha256((self.url + route).encode()).hexdigest()
        return self.roots / '.pdn-downloads' / f'{digest}-{url_digest}.part'

    def test_disconnect_then_resume_actual_https(self):
        partial = self.base / 'archive.part'
        result = self.run_harness('download', self.url + '/resume', partial)
        self.assertNotEqual(result.returncode, 0, result.stdout + result.stderr)
        offset = partial.stat().st_size
        self.assertGreater(offset, 0)
        self.assertLess(offset, self.archive.stat().st_size)
        result = self.run_harness('download', self.url + '/resume', partial)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(partial.read_bytes(), self.archive.read_bytes())
        self.assertIn(('/resume', f'bytes={offset}-'), self.range_requests)

    def test_bad_range_restarts_without_appending_response(self):
        for route in ['/resume-ignore', '/resume-wrong', '/resume-total', '/resume-416', '/resume-encoded', '/resume-missing', '/resume-malformed', '/resume-duplicate']:
            with self.subTest(route=route):
                self.range_requests.clear()
                partial = self.base / 'archive.part'
                offset = 37
                partial.write_bytes(self.archive.read_bytes()[:offset])
                result = self.run_harness('download', self.url + route, partial)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                self.assertEqual(partial.read_bytes(), self.archive.read_bytes())
                self.assertIn((route, f'bytes={offset}-'), self.range_requests)
                self.assertIn((route, None), self.range_requests)

    def test_unsolicited_partial_response_rejected(self):
        partial = self.base / 'archive.part'
        result = self.run_harness('download', self.url + '/resume-unsolicited', partial)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(partial.exists())

    def test_progress_includes_previous_bytes(self):
        import json
        partial = self.base / 'archive.part'
        partial.write_bytes(self.archive.read_bytes()[:37])
        path = self.event_channel()
        result = self.run_harness('download', self.url + '/resume-good', partial)
        self.assertEqual(result.returncode, 0, result.stderr)
        events = self.event_records(path, result, 'success')
        progress = [event for event in events if event['type'] == 'progress']
        self.assertTrue(progress)
        self.assertEqual(progress[0]['current'], 37)
        self.assertEqual(progress[-1]['current'], self.archive.stat().st_size)

    def test_cancelled_install_retains_partial_and_can_resume(self):
        self.env['TEST_URL'] = self.url + '/resume-slow'
        partial = self.cache_path('/resume-slow')
        proc = subprocess.Popen([str(self.harness), 'mirror', 'tuna'], env=self.env,
                                cwd=self.base, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            deadline = time.monotonic() + 5
            while not any(route == '/resume-slow' for route, _ in self.range_requests):
                if time.monotonic() > deadline:
                    self.fail('download request not started')
                time.sleep(.02)
            time.sleep(.15)
            proc.send_signal(signal.SIGTERM)
            stdout, stderr = proc.communicate(timeout=8)
            self.assertEqual(proc.returncode, 143, stdout + stderr)
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.communicate()
        self.assert_clean()
        self.assertTrue(partial.exists())
        offset = partial.stat().st_size
        self.assertGreater(offset, 0)
        result = self.run_harness('mirror', 'tuna')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(('/resume-slow', f'bytes={offset}-'), self.range_requests)

    def test_install_persists_partial_and_next_install_resumes(self):
        self.env['TEST_URL'] = self.url + '/resume'
        result = self.run_harness('mirror', 'tuna')
        self.assertNotEqual(result.returncode, 0)
        self.assert_clean()
        partial = self.cache_path('/resume')
        offset = partial.stat().st_size
        self.assertGreater(offset, 0)
        result = self.run_harness('mirror', 'tuna')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual((self.roots / 'alpine/bin/tool').read_bytes(), b'guest\n')
        self.assertIn(('/resume', f'bytes={offset}-'), self.range_requests)
        self.assertFalse(partial.exists())

    def test_complete_cache_verification_avoids_network(self):
        partial = self.cache_path('/archive')
        partial.parent.mkdir(parents=True)
        partial.write_bytes(self.archive.read_bytes())
        result = self.run_harness('mirror', 'tuna')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(self.requests, [])
        self.assertFalse(partial.exists())

    def test_corrupt_complete_cache_rejected_then_downloaded(self):
        partial = self.cache_path('/archive')
        partial.parent.mkdir(parents=True)
        partial.write_bytes(b'x' * self.archive.stat().st_size)
        result = self.run_harness('mirror', 'tuna')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('/archive', self.requests)
        self.assertEqual((self.roots / 'alpine/bin/tool').read_bytes(), b'guest\n')

    def test_corrupt_partial_cannot_publish_rootfs(self):
        self.env['TEST_URL'] = self.url + '/resume-good'
        partial = self.cache_path('/resume-good')
        partial.parent.mkdir(parents=True)
        partial.write_bytes(b'x' * 37)
        result = self.run_harness('mirror', 'tuna')
        self.assertNotEqual(result.returncode, 0)
        self.assert_clean()
        self.assertFalse(partial.exists())
        result = self.run_harness('mirror', 'tuna')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_partial_from_other_mirror_not_reused(self):
        other = self.cache_path('/resume-good')
        other.parent.mkdir(parents=True)
        other.write_bytes(self.archive.read_bytes()[:37])
        result = self.run_harness('mirror', 'tuna')
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn(('/archive', None), self.range_requests)
        self.assertEqual(other.stat().st_size, 37)

    def test_cache_directory_symlink_does_not_write_outside(self):
        self.roots.mkdir(parents=True)
        outside = self.base / 'outside'
        outside.mkdir()
        (self.roots / '.pdn-downloads').symlink_to(outside, target_is_directory=True)
        result = self.run_harness('mirror', 'tuna')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(list(outside.iterdir()), [])
        self.assert_clean()

    def test_cache_file_symlink_preserves_target(self):
        partial = self.cache_path('/archive')
        partial.parent.mkdir(parents=True)
        outside = self.base / 'outside'
        outside.write_text('keep')
        partial.symlink_to(outside)
        result = self.run_harness('mirror', 'tuna')
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(outside.read_text(), 'keep')
        self.assert_clean()

    def test_direct_download_symlink_and_directory_rejected(self):
        target = self.base / 'target'
        target.write_text('keep')
        link = self.base / 'link'
        link.symlink_to(target)
        for path in [link, self.base]:
            with self.subTest(path=path):
                result = self.run_harness('download', self.url + '/archive', path)
                self.assertNotEqual(result.returncode, 0)
        self.assertEqual(target.read_text(), 'keep')

    def test_resume_through_real_http_connect_proxy(self):
        partial = self.base / 'archive.part'
        partial.write_bytes(self.archive.read_bytes()[:37])
        self.env['https_proxy'] = self.proxy_url
        self.env['no_proxy'] = ''
        path = self.event_channel()
        result = self.run_harness('download', self.url + '/resume-good', partial)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(partial.read_bytes(), self.archive.read_bytes())
        self.assertEqual(self.proxy_connects, 1)
        events = self.event_records(path, result, 'success')
        self.assertEqual(sum(event.get('stage') == 'downloading' and event['type'] == 'stage' for event in events), 1)
        self.assertTrue(all(event['current'] >= 37 for event in events if event['type'] == 'progress'))

    def test_compressed_http_error_preserves_partial(self):
        partial = self.base / 'archive.part'
        prefix = self.archive.read_bytes()[:37]
        partial.write_bytes(prefix)
        path = self.event_channel()
        result = self.run_harness('download', self.url + '/compressed-error', partial)
        self.assert_error_code(path, result, 'http_error')
        self.assertEqual(partial.read_bytes(), prefix)

    def test_local_io_error_takes_precedence_over_invalid_response(self):
        self.env['TEST_CLOSE_ERRNO'] = '28'
        path = self.event_channel()
        partial = self.base / 'archive.part'
        result = self.run_harness('download', self.url + '/resume-unsolicited', partial)
        self.assert_error_code(path, result, 'storage_full')
        self.assertTrue(partial.exists())

    def test_oversize_cache_restarts(self):
        partial = self.base / 'archive.part'
        partial.write_bytes(self.archive.read_bytes() + b'oversize')
        result = self.run_harness('download', self.url + '/archive', partial)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(partial.read_bytes(), self.archive.read_bytes())
        self.assertIn(('/archive', None), self.range_requests)


if __name__ == '__main__':
    unittest.main()
