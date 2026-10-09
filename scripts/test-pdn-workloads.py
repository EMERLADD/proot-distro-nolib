import argparse
import hashlib
import json
import os
from pathlib import Path
import pty
import select
import signal
import statistics
import struct
import subprocess
import tempfile
import time
import fcntl
import termios


parser = argparse.ArgumentParser()
parser.add_argument('--report', required=True)
parser.add_argument('--route', required=True)
parser.add_argument('--only', nargs='+', choices=['file_write_read_rm', 'apk_query', 'apk_add_existing',
    'nano_save_read_rm', 'curl_https', 'tar_gzip_roundtrip', 'sha256_512mib',
    '3000_file_roundtrip_rm', 'c_project_compile'])
parser.add_argument('--repetitions', type=int)
args = parser.parse_args()
if args.repetitions is not None and args.repetitions < 1:
    parser.error("--repetitions must be positive")
os.environ['PATH'] = '/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
os.environ['TERM'] = 'xterm-256color'
os.environ['LANG'] = 'C.UTF-8'
results = []
workspace = Path(tempfile.mkdtemp(prefix='pdn-workloads-', dir='/tmp'))


def run(command, timeout=90):
    return subprocess.run(command, check=True, capture_output=True, timeout=timeout)


def check(name, category, repetitions, action):
    if args.only and name not in args.only:
        return
    if args.repetitions is not None:
        repetitions = args.repetitions
    durations = []
    entry = {'name': name, 'category': category}
    try:
        for _ in range(repetitions):
            start = time.perf_counter_ns()
            details = action()
            durations.append((time.perf_counter_ns() - start) / 1000000)
        entry.update(passed=True, samples_ms=durations, median_ms=statistics.median(durations), details=details)
    except Exception as failure:
        entry.update(passed=False, samples_ms=durations, error=str(failure))
    results.append(entry)
    print(json.dumps(entry, ensure_ascii=False), flush=True)


def file_lifecycle():
    path = workspace / 'short-file.txt'
    data = 'PDN file roundtrip 中文\n'.encode()
    path.write_bytes(data)
    assert path.read_bytes() == data
    run(['/bin/busybox', 'rm', str(path)])
    assert not path.exists()
    return 'write/read exact UTF-8 bytes; guest rm removed file'


def apk_query():
    run(['/sbin/apk', 'info', '-e', 'curl', 'nano', 'python3', 'gcc', 'musl-dev', 'make'])
    return 'required guest packages installed'


def apk_noop():
    output = run(['/sbin/apk', 'add', 'curl', 'nano']).stdout.decode()
    assert 'OK' in output
    return 'already-installed package operation completed'


def curl_https():
    body = workspace / 'https-body.html'
    process = run(['/usr/bin/curl', '--fail', '--silent', '--show-error', '--location',
                   '--connect-timeout', '10', '--max-time', '30', '-o', str(body),
                   '-w', '%{http_code} %{ssl_verify_result} %{time_total}', 'https://example.com/'], timeout=40)
    status, verification, network_seconds = process.stdout.decode().split()
    assert status == '200' and verification == '0'
    assert b'Example Domain' in body.read_bytes()
    body.unlink()
    return {'http_status': int(status), 'tls_verify_result': int(verification), 'curl_seconds': float(network_seconds)}


def nano_save():
    path = workspace / 'nano-saved.txt'
    path.unlink(missing_ok=True)
    transcript = bytearray()
    expected = b'PDN nano saved through real PTY\n'
    pid, master = pty.fork()
    if pid == 0:
        os.execv('/usr/bin/nano', ['/usr/bin/nano', '-I', str(path)])
    exited = False
    def drain(duration):
        end = time.monotonic() + duration
        while time.monotonic() < end:
            ready, _, _ = select.select([master], [], [], max(0, end - time.monotonic()))
            if ready:
                try:
                    data = os.read(master, 65536)
                except OSError:
                    break
                if not data:
                    break
                transcript.extend(data)
    try:
        fcntl.ioctl(master, termios.TIOCSWINSZ, struct.pack('HHHH', 32, 96, 0, 0))
        deadline = time.monotonic() + 15
        while b'GNU nano' not in transcript and time.monotonic() < deadline:
            drain(0.1)
        assert b'GNU nano' in transcript, 'nano did not present its interface'
        os.write(master, expected)
        drain(0.1)
        os.write(master, b'\x0f')
        drain(0.1)
        os.write(master, b'\r')
        deadline = time.monotonic() + 10
        while not path.exists() and time.monotonic() < deadline:
            drain(0.1)
        assert path.read_bytes() == expected, 'nano saved wrong bytes'
        os.write(master, b'\x18')
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            drain(0.05)
            reaped, status = os.waitpid(pid, os.WNOHANG)
            if reaped:
                exited = True
                assert os.waitstatus_to_exitcode(status) == 0
                break
        assert exited, 'nano did not exit after Ctrl-X'
        run(['/bin/busybox', 'cat', str(path)])
        run(['/bin/busybox', 'rm', str(path)])
        assert not path.exists()
        return 'real PTY; typed text, Ctrl-O/Enter saved, Ctrl-X exited; bytes read back and rm verified'
    finally:
        if not exited:
            try:
                os.kill(pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            os.waitpid(pid, 0)
        os.close(master)


payload = bytes(range(256)) * 262144
payload_hash = hashlib.sha256(payload).hexdigest()
payload_path = workspace / 'payload.bin'
payload_path.write_bytes(payload)
hash_reference = hashlib.sha256()
for _ in range(8):
    hash_reference.update(payload)
hash_expected = hash_reference.hexdigest()


def hash_data():
    digest = hashlib.sha256()
    for _ in range(8):
        digest.update(payload)
    assert digest.hexdigest() == hash_expected
    return 'SHA256 processed 512 MiB; digest matches precomputed expected value'


def archive_roundtrip():
    archive = workspace / 'payload.tar.gz'
    extracted = workspace / 'extracted'
    extracted.mkdir()
    run(['/bin/busybox', 'tar', '-czf', str(archive), '-C', str(workspace), 'payload.bin'])
    run(['/bin/busybox', 'tar', '-xzf', str(archive), '-C', str(extracted)])
    assert hashlib.sha256((extracted / 'payload.bin').read_bytes()).hexdigest() == payload_hash
    archive.unlink()
    run(['/bin/busybox', 'rm', '-rf', str(extracted)])
    return '64 MiB tar/gzip roundtrip, exact SHA256 match'


def many_files():
    directory = workspace / 'many-files'
    directory.mkdir()
    for number in range(3000):
        path = directory / str(number)
        content = str(number).encode() * 20
        path.write_bytes(content)
        assert path.stat().st_size == len(content)
        assert path.read_bytes() == content
    run(['/bin/busybox', 'rm', '-rf', str(directory)])
    assert not directory.exists()
    return '3000 files created, stat/read verified, recursively removed'


compile_directory = workspace / 'compile'
compile_directory.mkdir()
for number in range(24):
    (compile_directory / f'unit{number}.c').write_text(
        f'int unit{number}(int x) {{ for (int i=0; i<1000; i++) x=(x+i)%100003; return x; }}\n')
(compile_directory / 'main.c').write_text('int unit0(int); int main(void) { return unit0(0)==99488 ? 0 : 1; }\n')


def compile_project():
    objects = []
    for source in sorted(compile_directory.glob('*.c')):
        obj = source.with_suffix('.o')
        run(['/usr/bin/cc', '-O2', '-c', str(source), '-o', str(obj)])
        objects.append(str(obj))
    executable = compile_directory / 'compiled-test'
    run(['/usr/bin/cc', *objects, '-o', str(executable)])
    run([str(executable)])
    return '25 C translation units compiled/linked and executable result verified'


check('file_write_read_rm', 'short', 15, file_lifecycle)
check('apk_query', 'short', 10, apk_query)
check('apk_add_existing', 'short', 5, apk_noop)
check('nano_save_read_rm', 'interactive', 3, nano_save)
check('curl_https', 'network', 3, curl_https)
check('tar_gzip_roundtrip', 'medium', 3, archive_roundtrip)
check('sha256_512mib', 'compute', 3, hash_data)
check('3000_file_roundtrip_rm', 'filesystem', 3, many_files)
check('c_project_compile', 'long', 3, compile_project)
report = {'route': args.route, 'alpine': Path('/etc/alpine-release').read_text().strip(),
          'passed': all(entry['passed'] for entry in results), 'checks': results,
          'workspace': str(workspace), 'package_versions': run(['/sbin/apk', 'info', '-v']).stdout.decode().splitlines()}
Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
run(['/bin/busybox', 'rm', '-rf', str(workspace)])
raise SystemExit(0 if report['passed'] else 1)
