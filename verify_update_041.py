"""Verify a private 0.4.0 -> 0.4.1 update from real source/APK bytes, not a device test."""
from pathlib import Path
import hashlib
import json
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    baseline = json.loads((ROOT/'verification/preview-background-0.4.1-baseline.json').read_text())
    checkpoint = Path(baseline['snapshot'])
    assert digest(checkpoint) == baseline['sha256']
    with zipfile.ZipFile(checkpoint) as archive:
        assert archive.testzip() is None
        protected = [n for n in archive.namelist() if n.startswith(
            ('app/src/main/res/', 'app/src/main/assets/', 'app/schemas/'))]
        protected += ['app/src/main/AndroidManifest.xml', 'build.gradle']
        assert protected and all(archive.read(n) == (ROOT/n).read_bytes() for n in protected)
        before_gradle = archive.read('app/build.gradle').decode()
    after_gradle = (ROOT/'app/build.gradle').read_text()
    assert before_gradle.split('dependencies {', 1)[1] == after_gradle.split('dependencies {', 1)[1]
    schema = json.loads((ROOT/'app/schemas/com.swararia.audiolab.AudioDatabase/2.json').read_text())
    assert schema['database']['version'] == 2
    old = json.loads((ROOT/'dist/manifest-0.4.0.json').read_text())
    current = json.loads((ROOT/'verification/verification.json').read_text())
    assert len(current['artifacts']) == len(old['artifacts']) == 2
    artifacts = []
    seen = set()
    for item in current['artifacts']:
        abi = next(a for a in ['arm64-v8a', 'armeabi-v7a'] if a in item['file'])
        assert abi not in seen
        seen.add(abi)
        before = next(a for a in old['artifacts'] if abi in a['file'])
        oldpath = ROOT/'dist'/before['file']
        newpath = ROOT/'app/build/outputs/apk/debug'/item['file']
        assert digest(oldpath) == before['sha256'] and digest(newpath) == item['sha256']
        assert item['signature_verified'] and item['certificate_sha256'] == before['certificate_sha256']
        with zipfile.ZipFile(oldpath) as oldzip, zipfile.ZipFile(newpath) as newzip:
            names = {n for n in oldzip.namelist() if n == 'res/raw/ytdlp' or (n.startswith('lib/') and not n.endswith('/'))}
            assert names and names == {n for n in newzip.namelist() if n == 'res/raw/ytdlp' or (n.startswith('lib/') and not n.endswith('/'))}
            assert all(oldzip.read(n) == newzip.read(n) for n in names)
        badging = subprocess.check_output([str(ROOT/'.toolchain/android-sdk/build-tools/36.0.0/aapt2'), 'dump', 'badging', str(newpath)], text=True)
        assert "name='com.swararia.audiolab.spike' versionCode='5' versionName='0.4.1-spike'" in badging
        artifacts.append({'abi': abi, 'same_signer': True, 'old_apk_unchanged': True,
                          'native_and_engine_payloads_identical': True,
                          'old_apk_sha256': before['sha256'], 'new_apk_sha256': item['sha256']})
    report = {'version': '0.4.1-spike', 'version_code': 5, 'application_id_unchanged': True,
              'database_schema_unchanged': True, 'database_version': 2, 'new_migration_required': False,
              'protected_source_files_unchanged': len(protected), 'no_new_dependencies': True,
              'manifest_permissions_and_services_unchanged': True, 'artifacts': artifacts,
              'device_upgrade_tested': False}
    (ROOT/'verification/update-compatibility-0.4.1.json').write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
