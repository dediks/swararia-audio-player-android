"""Verify real APK bytes and SDK output; never substitutes for phone tests."""
from pathlib import Path
from datetime import datetime, timezone
import hashlib, io, json, os, re, struct, subprocess, zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent
TOOLS = ROOT/'.toolchain/android-sdk/build-tools/36.0.0'
OUT = ROOT/'verification'
OUT.mkdir(exist_ok=True)
ENV = os.environ.copy()
ENV['JAVA_HOME'] = str(ROOT/'.toolchain/jdk')
ENV['PATH'] = str(ROOT/'.toolchain/jdk/bin') + ':' + ENV.get('PATH', '')
ENV['JAVA_TOOL_OPTIONS'] = '-Djava.io.tmpdir=' + str(ROOT/'.cache/tmp')

def run(command):
    result = subprocess.run(command, env=ENV, capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise RuntimeError(str(command[:2]) + '\n' + result.stdout[-3000:] + result.stderr[-3000:])
    return result.stdout

def elf_loads(data):
    if not data.startswith(b'\x7fELF'):
        return None
    endian = '<' if data[5] == 1 else '>'
    machine = struct.unpack_from(endian + 'H', data, 18)[0]
    if data[4] == 2:
        offset = struct.unpack_from(endian+'Q', data, 32)[0]
        size, count = struct.unpack_from(endian+'HH', data, 54)
        fmt = endian+'IIQQQQQQ'
    elif data[4] == 1:
        offset = struct.unpack_from(endian+'I', data, 28)[0]
        size, count = struct.unpack_from(endian+'HH', data, 42)
        fmt = endian+'IIIIIIII'
    else:
        raise ValueError('Unknown ELF class')
    alignments = []
    for number in range(count):
        header = struct.unpack_from(fmt, data, offset + number * size)
        if header[0] == 1:
            alignments.append(header[-1])
    return {'machine': machine, 'pt_load_alignments': alignments}

def audit_native(archive):
    found = []
    def record(name, content):
        elf = elf_loads(content)
        if elf is not None:
            found.append({'path': name, **elf})
    for info in archive.infolist():
        if not info.filename.startswith('lib/') or info.is_dir():
            continue
        content = archive.read(info)
        record(info.filename, content)
        if info.filename.endswith('.zip.so'):
            with zipfile.ZipFile(io.BytesIO(content)) as runtime:
                for entry in runtime.infolist():
                    if not entry.is_dir():
                        record(info.filename + '!' + entry.filename, runtime.read(entry))
    arm64 = [entry for entry in found if entry['machine'] == 183 and entry['pt_load_alignments']]
    incompatible = [entry for entry in arm64 if min(entry['pt_load_alignments']) < 16384]
    return {'elf_count': len(found), 'arm64_elf_count': len(arm64),
            'arm64_16kb_incompatible': incompatible,
            'verdict': 'not-applicable-arm32' if not arm64 else ('4KB-only-ingest-prototype' if incompatible else 'static-alignment-passed'),
            'device_execution_tested': False}

engine = json.loads((ROOT/'engine-lock.json').read_text())
artifacts = []
for apk in sorted((ROOT/'app/build/outputs/apk/debug').glob('*.apk')):
    signed = run([str(TOOLS/'apksigner'), 'verify', '--verbose', '--print-certs', str(apk)])
    aligned = run([str(TOOLS/'zipalign'), '-c', '-P', '16', '-v', '4', str(apk)])
    badging = run([str(TOOLS/'aapt2'), 'dump', 'badging', str(apk)])
    tree = run([str(TOOLS/'aapt2'), 'dump', 'xmltree', str(apk), '--file', 'AndroidManifest.xml'])
    assert "package: name='com.swararia.audiolab.spike'" in badging
    assert re.search(r"(?m)^(?:minSdkVersion|sdkVersion):'29'$", badging)
    assert 'android:extractNativeLibs' in tree
    forbidden = ['MANAGE_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE', 'READ_EXTERNAL_STORAGE', 'READ_MEDIA_AUDIO']
    assert not any('android.permission.' + permission in tree for permission in forbidden)
    with zipfile.ZipFile(apk) as archive:
        assert archive.testzip() is None
        assert len(archive.namelist()) == len(set(archive.namelist())), 'Duplicate ZIP entries'
        assert hashlib.sha256(archive.read('res/raw/ytdlp')).hexdigest() == engine['sha256']
        native = audit_native(archive)
    cert = next(line.split(': ', 1)[1] for line in signed.splitlines() if 'certificate SHA-256 digest:' in line)
    with apk.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    artifacts.append({'file': apk.name, 'bytes': apk.stat().st_size, 'sha256': digest,
                      'signature_verified': True, 'certificate_sha256': cert,
                      'zipalign_verified': True, 'native': native})
    (OUT/(apk.stem+'-manifest.txt')).write_text(tree)
    (OUT/(apk.stem+'-signature.txt')).write_text(signed)

reports = list((ROOT/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
unit = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
for file in reports:
    result = ET.parse(file).getroot()
    for key in unit:
        unit[key] += int(result.attrib.get(key, '0'))
assert unit['tests'] > 0 and unit['failures'] == 0 and unit['errors'] == 0
summary = {'generated_at_utc': datetime.now(timezone.utc).isoformat(),
           'build': 'debug APK; not a public/production release',
           'unit_tests': unit, 'engine': engine, 'artifacts': artifacts,
           'device_execution_tested': False, 'ui_render_tested': False,
           'native_limit': 'Five bundled FFmpeg WebP-related ARM64 libraries are 4KB aligned. Ingest checks the actual device page size and refuses non-4KB pages; import/player/organizer can still be tried. No 16KB compatibility or device success is claimed.',
           'license_limit': 'Upstream declares GPLv3 for wrapper/FFmpeg; exact corresponding native-source provenance and public-distribution compliance are not cleared.'}
(OUT/'verification.json').write_text(json.dumps(summary, indent=2)+'\n')
print(json.dumps(summary, indent=2))
