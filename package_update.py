"""Package verified 0.2.0 candidates without modifying any 0.1.0 release."""
from pathlib import Path
import hashlib, json, shutil, zipfile, sys
ROOT=Path(__file__).resolve().parent
DIST=ROOT/'dist'
VERSION=sys.argv[1] if len(sys.argv)>1 else '0.2.0-spike'
assert VERSION in {'0.2.0-spike','0.3.0-spike'}
NEW=VERSION=='0.3.0-spike'
SHORT='0.3.0' if NEW else '0.2.0'
DIST.mkdir(exist_ok=True)

def digest(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

review=json.loads((ROOT/'verification'/('search-player-review-0.3.0.json' if NEW else 'ui-update-review-result.json')).read_text())
assert review['passed'] and not review['security_concerns'] and not review['logic_errors']
report=json.loads((ROOT/'verification/verification.json').read_text())
compat=json.loads((ROOT/f'verification/update-compatibility-{SHORT}.json').read_text())
checks=json.loads((ROOT/'verification'/('checks-0.3.0.json' if NEW else 'ui-update-checks.json')).read_text())
assert compat['core_schema_unchanged'] and all(a['same_signer'] and a['old_apk_unchanged'] and a['native_and_engine_payloads_identical'] for a in compat['artifacts'])
assert not checks['lint'].get('Error',0) and not checks['lint'].get('Fatal',0)
assert report['unit_tests']['tests']>0 and not report['unit_tests']['failures'] and not report['unit_tests']['errors']
manifest_path=DIST/f'manifest-{SHORT}.json'
assert not manifest_path.exists(),'Refusing to overwrite a prepared release manifest'
artifacts=[]
for artifact in report['artifacts']:
    abi='arm64-v8a' if 'arm64-v8a' in artifact['file'] else 'armeabi-v7a'
    source=ROOT/'app/build/outputs/apk/debug'/artifact['file']
    assert digest(source)==artifact['sha256']
    target=DIST/f'Swararia-Audio-Lab-{VERSION}-{abi}.apk'
    assert not target.exists(),'Refusing to overwrite an existing release APK'
    shutil.copy2(source,target)
    assert digest(target)==artifact['sha256']
    artifacts.append({**artifact,'file':target.name})

excluded={'.toolchain','.cache','.gradle','.kotlin','.checkpoints','build','dist','__pycache__','.git'}
files=set()
for base in [ROOT/'app',ROOT/'third-party',ROOT/'verification']:
    for path in base.rglob('*'):
        rel=path.relative_to(ROOT)
        if path.is_file() and not path.is_symlink() and not any(p in excluded for p in rel.parts):files.add(path)
for path in ROOT.iterdir():
    if path.is_file() and (path.suffix in {'.py','.sh','.gradle','.properties','.md','.json'} or path.name=='.gitignore') and path.name!='local.properties':files.add(path)
for rel in ['design/ui-preview-v1/assets/Inter-variable.ttf','design/ui-preview-v1/assets/OFL.txt','design/ui-preview-v2-logo/assets/swararia-logo-512.png']:
    files.add(ROOT/rel)
assert all(p.resolve().is_relative_to(ROOT) and p.suffix not in {'.keystore','.jks','.apk'} for p in files)
archive=DIST/f'swararia-audio-lab-{VERSION}-source.zip'
with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED,compresslevel=9) as output:
    for path in sorted(files):output.write(path,path.relative_to(ROOT).as_posix())
with zipfile.ZipFile(archive) as check:
    assert check.testzip() is None
    names=check.namelist()
    assert len(names)==len(set(names))==len(files)
    for required in ['app/src/main/res/font/inter_variable.ttf','app/src/main/res/drawable-nodpi/swararia_logo.png','app/src/main/java/com/swararia/audiolab/YoutubeThumbnail.kt','app/src/main/java/com/swararia/audiolab/SwarariaUi.kt','app/src/main/assets/licenses/Inter-OFL.txt']:
        assert required in names,required
manifest={'version':VERSION,'application_id':'com.swararia.audiolab.spike','version_code':3 if NEW else 2,
          'artifacts':artifacts,'unit_tests':report['unit_tests'],'lint':checks['lint'],
          'independent_review':review,'update_compatibility':compat,
          'source_package':{'file':archive.name,'bytes':archive.stat().st_size,'sha256':digest(archive),'files':len(files)},
          'device_execution_tested':False,'ui_runtime_render_tested':False,
          'native_limit':report['native_limit'],'license_limit':report['license_limit'],
          'transfer_status':'Local packaging verified; attachment dispatch pending'}
with manifest_path.open('x') as output:json.dump(manifest,output,indent=2);output.write('\n')
print(json.dumps({'manifest':str(manifest_path),'artifacts':[{k:a[k] for k in ['file','bytes','sha256']} for a in artifacts],'source':manifest['source_package']},indent=2))
