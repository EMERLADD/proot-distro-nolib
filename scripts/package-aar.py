import hashlib
from pathlib import Path
import re
import sys
import zipfile


LITE_LIBRARIES = frozenset(f'jni/arm64-v8a/{name}' for name in
                           ('libpdn.so', 'libproot-loader.so', 'libptyjni.so'))


def lightweight(source, target):
    with zipfile.ZipFile(source) as original, zipfile.ZipFile(target, 'w') as output:
        for entry in original.infolist():
            if entry.filename.startswith('jni/') and entry.filename not in LITE_LIBRARIES:
                continue
            output.writestr(entry, original.read(entry.filename))
    with zipfile.ZipFile(target) as archive:
        libraries = {name for name in archive.namelist() if name.startswith('jni/') and not name.endswith('/')}
        if libraries != LITE_LIBRARIES:
            raise ValueError('Lightweight AAR native library membership mismatch')
        if archive.testzip() is not None:
            raise ValueError('Corrupt lightweight AAR')


def package(aar, root):
    version = re.search(r'^#define PDN_VERSION "([0-9.]+)"',
                        (root / 'src/proot/src/cli/proot.h').read_text(), re.M).group(1)
    output = root / 'build/packages'
    names = [f'proot-distro-nolib-v{version}-android-arm64.tar.gz',
             'pdn', 'proot-loader', 'libpdn.so', 'libproot-loader.so']
    for name in names:
        if not (output / name).is_file():
            raise ValueError(f'Missing release asset: {name}')
    with zipfile.ZipFile(aar) as archive:
        if len(archive.namelist()) != len(set(archive.namelist())):
            raise ValueError('Duplicate AAR entry')
        if archive.testzip() is not None:
            raise ValueError('Corrupt AAR')
        for name in ('libpdn.so', 'libproot-loader.so'):
            if archive.read(f'jni/arm64-v8a/{name}') != (output / name).read_bytes():
                raise ValueError(f'AAR/native release mismatch: {name}')
        if not archive.read('jni/arm64-v8a/libptyjni.so').startswith(b'\x7fELF'):
            raise ValueError('Invalid PTY library')
        for name in ('classes.jar', 'AndroidManifest.xml'):
            if not archive.read(name):
                raise ValueError(f'Empty AAR entry: {name}')
    target = output / f'pdn-engine-{version}.aar'
    target.write_bytes(Path(aar).read_bytes())
    names.append(target.name)
    lite = output / f'pdn-engine-lite-{version}.aar'
    lightweight(target, lite)
    names.append(lite.name)
    (output / 'SHA256SUMS').write_text(''.join(
        f'{hashlib.sha256((output / name).read_bytes()).hexdigest()}  {name}\n'
        for name in names))
    print(f'Packaged and verified: {target}')


if __name__ == '__main__':
    package(Path(sys.argv[1]), Path(__file__).resolve().parent.parent)
