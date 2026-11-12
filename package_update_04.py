"""Fail-closed private 0.4 candidate packaging; never overwrites previous releases."""
from pathlib import Path
import hashlib,json,shutil,zipfile
ROOT=Path(__file__).resolve().parent
DIST=ROOT/'dist';DIST.mkdir(exist_ok=True)
def digest(p):
    with p.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def read(name):return json.loads((ROOT/'verification'/name).read_text())
review=read('upgrade-0.4-review.json');checks=read('checks-0.4.0.json')
report=read('verification.json');compat=read('update-compatibility-0.4.0.json')
assert review['passed'] and not review['security_concerns'] and not review['logic_errors']
assert checks['passed'] and not checks['lint'].get('Error',0) and not checks['lint'].get('Fatal',0)
assert report['unit_tests']['tests']>0 and not report['unit_tests']['failures'] and not report['unit_tests']['errors']
assert compat['schema1_preserved'] and all(compat['migration']['generated_schema_tables_match'].values())
assert all(a['same_signer'] and a['old_apk_unchanged'] and a['native_and_engine_payloads_identical'] for a in compat['artifacts'])
manifest=DIST/'manifest-0.4.0.json';assert not manifest.exists()
artifacts=[]
for item in report['artifacts']:
    abi='arm64-v8a' if 'arm64-v8a' in item['file'] else 'armeabi-v7a'
    source=ROOT/'app/build/outputs/apk/debug'/item['file']
    target=DIST/f'Swararia-Audio-Lab-0.4.0-spike-{abi}.apk'
    assert digest(source)==item['sha256'] and not target.exists()
    shutil.copy2(source,target);assert digest(target)==item['sha256']
    artifacts.append({**item,'file':target.name})
excluded={'.toolchain','.cache','.gradle','.kotlin','.checkpoints','build','dist','__pycache__','.git'}
files=set()
for base in [ROOT/'app',ROOT/'third-party']:
    files.update(p for p in base.rglob('*') if p.is_file() and not p.is_symlink() and not any(s in excluded for s in p.relative_to(ROOT).parts))
for p in ROOT.iterdir():
    if p.is_file() and not p.is_symlink() and (p.suffix in {'.py','.sh','.gradle','.properties','.md','.json'} or p.name=='.gitignore') and p.name!='local.properties':files.add(p)
# Ship evidence summaries and executable host checks, not arbitrary raw logs.
for p in (ROOT/'verification').iterdir():
    if p.is_file() and p.suffix in {'.json','.py'}:files.add(p)
for name in ['design/ui-preview-v1/assets/Inter-variable.ttf','design/ui-preview-v1/assets/OFL.txt','design/ui-preview-v2-logo/assets/swararia-logo-512.png']:files.add(ROOT/name)
assert all(p.resolve().is_relative_to(ROOT) and p.suffix not in {'.keystore','.jks','.apk','.pem'} for p in files)
archive=DIST/'swararia-audio-lab-0.4.0-spike-source.zip'
with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for p in sorted(files):z.write(p,p.relative_to(ROOT).as_posix())
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None and len(z.namelist())==len(set(z.namelist()))==len(files)
    assert 'app/schemas/com.swararia.audiolab.AudioDatabase/2.json' in z.namelist()
    assert 'UPDATE-0.4.0.md' in z.namelist()
result={'version':'0.4.0-spike','version_code':4,'application_id':'com.swararia.audiolab.spike',
        'artifacts':artifacts,'unit_tests':report['unit_tests'],'lint':checks['lint'],
        'source_package':{'file':archive.name,'bytes':archive.stat().st_size,'sha256':digest(archive),'files':len(files)},
        'independent_review':review,'update_compatibility':compat,'device_execution_tested':False,'ui_runtime_render_tested':False,
        'push_performed':False,'native_limit':report['native_limit'],'license_limit':report['license_limit']}
with manifest.open('x') as out:json.dump(result,out,indent=2);out.write('\n')
print(json.dumps({'manifest':str(manifest),'artifacts':[{k:a[k] for k in ['file','bytes','sha256']} for a in artifacts],'source':result['source_package']},indent=2))
