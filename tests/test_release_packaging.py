import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile


spec = importlib.util.spec_from_file_location(
    'package_aar', Path(__file__).resolve().parents[1] / 'scripts/package-aar.py')
packager = importlib.util.module_from_spec(spec)
spec.loader.exec_module(packager)


class ReleasePackagingTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        header = self.root / 'src/proot/src/cli/proot.h'
        header.parent.mkdir(parents=True)
        header.write_text('#define PDN_VERSION "0.6.2"\n')
        self.output = self.root / 'build/packages'
        self.output.mkdir(parents=True)
        for name in ('proot-distro-nolib-v0.6.2-android-arm64.tar.gz',
                     'pdn', 'proot-loader', 'libpdn.so', 'libproot-loader.so'):
            (self.output / name).write_bytes(b'\x7fELF' + name.encode())
        self.aar = self.root / 'engine.aar'
        self.entries = {
            f'jni/arm64-v8a/{name}': (self.output / name).read_bytes()
            for name in ('libpdn.so', 'libproot-loader.so')}
        self.entries.update({'jni/arm64-v8a/libptyjni.so': b'\x7fELFpty',
                             'classes.jar': b'classes', 'AndroidManifest.xml': b'manifest'})

    def write_aar(self):
        with zipfile.ZipFile(self.aar, 'w') as archive:
            for name, data in self.entries.items():
                archive.writestr(name, data)

    def test_valid_package_checksums_cover_exact_assets(self):
        self.write_aar()
        packager.package(self.aar, self.root)
        lines = (self.output / 'SHA256SUMS').read_text().splitlines()
        self.assertEqual(len(lines), 7)
        for line in lines:
            digest, name = line.split('  ')
            self.assertEqual(digest, hashlib.sha256((self.output / name).read_bytes()).hexdigest())
        self.assertEqual((self.output / 'pdn-engine-0.6.2.aar').read_bytes(), self.aar.read_bytes())

    def test_all_aars_exclude_legacy_programs_and_preserve_sdk(self):
        self.entries.update({f'jni/arm64-v8a/{name}': b'\x7fELFlegacy' for name in
                             ('libproot.so', 'libpr-cli.so', 'libbusybox.so', 'libbash.so')})
        self.entries['jni/x86_64/libextra.so'] = b'\x7fELFother'
        self.entries['META-INF/metadata.txt'] = b'metadata'
        self.write_aar()
        packager.package(self.aar, self.root)
        with zipfile.ZipFile(self.output / 'pdn-engine-lite-0.6.2.aar') as lite:
            natives = {name for name in lite.namelist() if name.startswith('jni/')}
            self.assertEqual(natives, packager.PDN_LIBRARIES)
            for name in natives | {'classes.jar', 'AndroidManifest.xml', 'META-INF/metadata.txt'}:
                self.assertEqual(lite.read(name), self.entries[name])
        full = self.output / 'pdn-engine-0.6.2.aar'
        self.assertNotEqual(full.read_bytes(), self.aar.read_bytes())
        self.assertEqual(full.read_bytes(), (self.output / 'pdn-engine-lite-0.6.2.aar').read_bytes())
        with zipfile.ZipFile(full) as archive:
            self.assertEqual(set(archive.namelist()), set(self.entries) -
                             {name for name in self.entries if name.startswith('jni/')} |
                             packager.PDN_LIBRARIES)

    def test_rejects_duplicate_archive_entries(self):
        self.write_aar()
        with zipfile.ZipFile(self.aar, 'a') as archive:
            archive.writestr('classes.jar', b'other')
        with self.assertRaisesRegex(ValueError, 'Duplicate AAR entry'):
            packager.package(self.aar, self.root)

    def test_lightweight_requires_exact_native_membership(self):
        self.entries.pop('jni/arm64-v8a/libptyjni.so')
        self.write_aar()
        with self.assertRaisesRegex(ValueError, 'membership mismatch'):
            packager.lightweight(self.aar, self.root / 'lite.aar')

    def test_rejects_missing_native_release(self):
        (self.output / 'pdn').unlink()
        self.write_aar()
        with self.assertRaisesRegex(ValueError, 'Missing release asset: pdn'):
            packager.package(self.aar, self.root)

    def test_rejects_both_mismatched_native_programs(self):
        for name in ('libpdn.so', 'libproot-loader.so'):
            with self.subTest(name=name):
                original = self.entries[f'jni/arm64-v8a/{name}']
                self.entries[f'jni/arm64-v8a/{name}'] = b'other version'
                self.write_aar()
                with self.assertRaisesRegex(ValueError, 'AAR/native release mismatch'):
                    packager.package(self.aar, self.root)
                self.entries[f'jni/arm64-v8a/{name}'] = original

    def test_rejects_invalid_pty(self):
        self.entries['jni/arm64-v8a/libptyjni.so'] = b'invalid'
        self.write_aar()
        with self.assertRaisesRegex(ValueError, 'Invalid PTY library'):
            packager.package(self.aar, self.root)

    def test_rejects_empty_required_entries(self):
        for name in ('classes.jar', 'AndroidManifest.xml'):
            with self.subTest(name=name):
                original = self.entries[name]
                self.entries[name] = b''
                self.write_aar()
                with self.assertRaisesRegex(ValueError, 'Empty AAR entry'):
                    packager.package(self.aar, self.root)
                self.entries[name] = original

    def test_rejects_corrupt_archive(self):
        self.write_aar()
        data = self.aar.read_bytes().replace(b'classes', b'clAsses', 1)
        self.aar.write_bytes(data)
        with self.assertRaisesRegex(ValueError, 'Corrupt AAR'):
            packager.package(self.aar, self.root)


if __name__ == '__main__':
    unittest.main()
