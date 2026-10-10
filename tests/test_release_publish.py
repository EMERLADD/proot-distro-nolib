import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


PROJECT = Path(__file__).resolve().parents[1]


class ReleasePublishTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=PROJECT / 'build', prefix='release-publish-')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        for path in ('scripts', 'src/proot/src/cli', 'build/packages', 'bin'):
            (self.root / path).mkdir(parents=True)
        shutil.copy2(PROJECT / 'scripts/publish-release.sh', self.root / 'scripts/publish-release.sh')
        (self.root / 'src/proot/src/cli/proot.h').write_text('#define PDN_VERSION "0.6.11"\n')
        (self.root / 'CHANGELOG.md').write_text('## v0.6.11 — test\n\nVerified release.\n\n## v0.6.10 — previous\n')
        names = ('proot-distro-nolib-v0.6.11-android-arm64.tar.gz', 'pdn-engine-0.6.11.aar', 'pdn-engine-lite-0.6.11.aar', 'pdn', 'proot-loader', 'libpdn.so', 'libproot-loader.so')
        for name in names:
            (self.root / 'build/packages' / name).write_text('fixture\n')
        result = subprocess.run(['sha256sum', *names], cwd=self.root / 'build/packages', capture_output=True, check=True)
        (self.root / 'build/packages/SHA256SUMS').write_bytes(result.stdout)
        gh = self.root / 'bin/gh'
        gh.write_text('#!/usr/bin/env python3\nimport json, os, sys\nfrom pathlib import Path\nif sys.argv[1:3] == ["release", "view"]:\n    sys.exit(0 if os.environ.get("EXISTING_RELEASE") == "1" else 1)\nPath(os.environ["CAPTURE_ARGS"]).write_text(json.dumps(sys.argv[1:]))\n')
        gh.chmod(0o755)
        self.env = dict(os.environ, PATH=str(self.root / 'bin') + os.pathsep + os.environ['PATH'], RELEASE_COMMIT='test-commit', RELEASE_REF='refs/heads/main', GITHUB_REPOSITORY='example/project', CAPTURE_ARGS=str(self.root / 'args.json'))

    def run_publish(self):
        return subprocess.run(['sh', str(self.root / 'scripts/publish-release.sh')], cwd=self.root, env=self.env, capture_output=True, text=True)

    def test_creates_draft_with_verified_assets(self):
        result = self.run_publish()
        self.assertEqual(result.returncode, 0, result.stderr)
        args = json.loads((self.root / 'args.json').read_text())
        self.assertEqual(args[:3], ['release', 'create', 'v0.6.11'])
        self.assertIn('--draft', args)
        self.assertIn('--prerelease', args)
        self.assertEqual(args[args.index('--target') + 1], 'test-commit')
        self.assertEqual(args[-1], 'build/packages/SHA256SUMS')

    def test_existing_release_is_not_overwritten(self):
        self.env['EXISTING_RELEASE'] = '1'
        self.assertEqual(self.run_publish().returncode, 0)
        self.assertFalse((self.root / 'args.json').exists())

    def test_missing_asset_blocks_creation(self):
        (self.root / 'build/packages/pdn').unlink()
        self.assertNotEqual(self.run_publish().returncode, 0)
        self.assertFalse((self.root / 'args.json').exists())

    def test_bad_checksum_blocks_creation(self):
        (self.root / 'build/packages/pdn').write_text('changed\n')
        self.assertNotEqual(self.run_publish().returncode, 0)
        self.assertFalse((self.root / 'args.json').exists())

    def test_mismatched_tag_blocks_creation(self):
        self.env['RELEASE_REF'] = 'refs/tags/v0.6.10'
        self.assertNotEqual(self.run_publish().returncode, 0)
        self.assertFalse((self.root / 'args.json').exists())
