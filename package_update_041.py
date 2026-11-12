"""Fail-closed private 0.4.1 packaging. Leaves all previous releases intact."""
from pathlib import Path
import json
import shutil
import zipfile
from verify_update_041 import digest
from verify_upgrade_041 import source_fingerprint

ROOT = Path(__file__).resolve().parent
DIST = ROOT/'dist'


def main():
    def read(name):
        return json.loads((ROOT/'verification'/name).read_text())
    review = read('preview-background-0.4.1-review.json')
    checks = read('checks-0.4.1.json')
    report = read('verification.json')
    compat = read('update-compatibility-0.4.1.json')
    assert review['passed'] and not review['security_concerns'] and not review['logic_errors']
    assert checks['passed'] and not checks['lint'].get('Error', 0) and not checks['lint'].get('Fatal', 0)
    assert review['app_source_fingerprint_sha256'] == checks['app_source_fingerprint_sha256'] == source_fingerprint()
    assert checks['unit_tests'] == report['unit_tests']
    assert compat['database_schema_unchanged'] and compat['no_new_dependencies'] and compat['application_id_unchanged']
    assert len(compat['artifacts']) == len(report['artifacts']) == 2
    assert all(a['same_signer'] and a['old_apk_unchanged'] and a['native_and_engine_payloads_identical'] for a in compat['artifacts'])
    manifest = DIST/'manifest-0.4.1.json'
    archive = DIST/'swararia-audio-lab-0.4.1-spike-source.zip'
    assert not manifest.exists() and not archive.exists()
    artifacts = []
    for item in report['artifacts']:
        abi = 'arm64-v8a' if 'arm64-v8a' in item['file'] else 'armeabi-v7a'
        source = ROOT/'app/build/outputs/apk/debug'/item['file']
        target = DIST/f'Swararia-Audio-Lab-0.4.1-spike-{abi}.apk'
        assert digest(source) == item['sha256'] == checks['artifact_sha256'][item['file']]
        assert next(a for a in compat['artifacts'] if a['abi'] == abi)['new_apk_sha256'] == item['sha256']
        assert not target.exists()
        shutil.copy2(source, target)
        assert digest(target) == item['sha256']
        artifacts.append({**item, 'file': target.name})
    excluded = {'.toolchain', '.cache', '.gradle', '.kotlin', '.checkpoints', 'build', 'dist', '__pycache__', '.git'}
    files = set()
    for base in [ROOT/'app', ROOT/'third-party']:
        files.update(p for p in base.rglob('*') if p.is_file() and not p.is_symlink()
                     and not any(s in excluded for s in p.relative_to(ROOT).parts))
    for path in ROOT.iterdir():
        if path.is_file() and not path.is_symlink() and (path.suffix in {'.py', '.sh', '.gradle', '.properties', '.md', '.json'} or path.name == '.gitignore') and path.name != 'local.properties':
            files.add(path)
    files.update(p for p in (ROOT/'verification').iterdir() if p.is_file() and p.suffix in {'.json', '.py'})
    for name in ['design/ui-preview-v1/assets/Inter-variable.ttf', 'design/ui-preview-v1/assets/OFL.txt', 'design/ui-preview-v2-logo/assets/swararia-logo-512.png']:
        files.add(ROOT/name)
    assert all(p.resolve().is_relative_to(ROOT) and p.suffix not in {'.keystore', '.jks', '.apk', '.pem', '.env'} for p in files)
    assert ROOT/'UPDATE-0.4.1.md' in files
    with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED, compresslevel=9) as zipped:
        for path in sorted(files):
            zipped.write(path, path.relative_to(ROOT).as_posix())
    with zipfile.ZipFile(archive) as zipped:
        assert zipped.testzip() is None and len(zipped.namelist()) == len(set(zipped.namelist())) == len(files)
        for path in files:
            assert zipped.read(path.relative_to(ROOT).as_posix()) == path.read_bytes()
    result = {'version': '0.4.1-spike', 'version_code': 5, 'application_id': 'com.swararia.audiolab.spike',
              'artifacts': artifacts, 'unit_tests': report['unit_tests'], 'lint': checks['lint'],
              'source_package': {'file': archive.name, 'bytes': archive.stat().st_size, 'sha256': digest(archive), 'files': len(files)},
              'independent_review': review, 'update_compatibility': compat, 'device_execution_tested': False,
              'preview_latency_measured_on_device': False, 'push_performed': False,
              'native_limit': report['native_limit'], 'license_limit': report['license_limit']}
    with manifest.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(json.dumps({'manifest': str(manifest), 'artifacts': [{k: a[k] for k in ['file', 'bytes', 'sha256']} for a in artifacts], 'source': result['source_package']}, indent=2))


if __name__ == '__main__':
    main()
