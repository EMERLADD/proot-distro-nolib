import hashlib
from pathlib import Path
import re
import sys
import zipfile


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
    (output / 'SHA256SUMS').write_text(''.join(
        f'{hashlib.sha256((output / name).read_bytes()).hexdigest()}  {name}\n'
        for name in names))
    print(f'Packaged and verified: {target}')


if __name__ == '__main__':
    package(Path(sys.argv[1]), Path(__file__).resolve().parent.parent)
