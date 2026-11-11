"""0.4 additive-schema and signed-APK checks. No Android/device execution."""
from pathlib import Path
import hashlib, json, re, sqlite3, subprocess, zipfile
ROOT=Path(__file__).resolve().parent
SRC=ROOT/'app/src/main/java/com/swararia/audiolab'
def digest(p):
    with p.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
def create(db,schema):
    for entity in schema['entities']:
        db.execute(entity['createSql'].replace('${TABLE_NAME}',entity['tableName']))
        for index in entity.get('indices',[]):db.execute(index['createSql'].replace('${TABLE_NAME}',entity['tableName']))
def signature(db,table):
    indices=[]
    for index in db.execute(f'PRAGMA index_list("{table}")'):
        indices.append((index[1],index[2],list(db.execute(f'PRAGMA index_info("{index[1]}")'))))
    return {'columns':list(db.execute(f'PRAGMA table_info("{table}")')),'foreign_keys':list(db.execute(f'PRAGMA foreign_key_list("{table}")')),'indices':sorted(indices)}
def main():
    baseline=json.loads((ROOT/'verification/upgrade-0.4-baseline.json').read_text())
    checkpoint=Path(baseline['snapshot'])
    assert digest(checkpoint)==baseline['sha256']
    with zipfile.ZipFile(checkpoint) as archive:
        assert archive.testzip() is None
        protected=[n for n in archive.namelist() if n.startswith(('app/src/main/res/','app/src/main/assets/')) or n=='app/schemas/com.swararia.audiolab.AudioDatabase/1.json']
        assert protected
        assert all(archive.read(n)==(ROOT/n).read_bytes() for n in protected)
    v1=json.loads((ROOT/'app/schemas/com.swararia.audiolab.AudioDatabase/1.json').read_text())['database']
    v2=json.loads((ROOT/'app/schemas/com.swararia.audiolab.AudioDatabase/2.json').read_text())['database']
    assert v1['version']==1 and v2['version']==2
    migrated=sqlite3.connect(':memory:');expected=sqlite3.connect(':memory:')
    create(migrated,v1);create(expected,v2)
    statements=re.findall(r'const val \w+ = "([^"]+)"',(SRC/'AudioMigrations.kt').read_text())
    assert len(statements)>=2, 'Expected an additive migration with artwork and queue tables'
    for sql in statements:migrated.execute(sql)
    assert {e['tableName'] for e in v2['entities']}=={'tracks','rename_history','download_jobs'}
    tables={e['tableName']:signature(migrated,e['tableName'])==signature(expected,e['tableName']) for e in v2['entities']}
    assert all(tables.values()),tables
    subprocess.run(['python','verification/test_data_layer.py'],cwd=ROOT,check=True)
    old=json.loads((ROOT/'dist/manifest-0.3.0.json').read_text())
    current=json.loads((ROOT/'verification/verification.json').read_text())
    assert len(current['artifacts'])==2
    artifacts=[]
    for item in current['artifacts']:
        abi='arm64-v8a' if 'arm64-v8a' in item['file'] else 'armeabi-v7a'
        before=next(a for a in old['artifacts'] if abi in a['file'])
        oldpath=ROOT/'dist'/before['file'];newpath=ROOT/'app/build/outputs/apk/debug'/item['file']
        assert digest(oldpath)==before['sha256'] and digest(newpath)==item['sha256']
        assert item['signature_verified'] and before['certificate_sha256']==item['certificate_sha256']
        with zipfile.ZipFile(oldpath) as a,zipfile.ZipFile(newpath) as b:
            names={n for n in a.namelist() if n=='res/raw/ytdlp' or(n.startswith('lib/') and not n.endswith('/'))}
            assert names=={n for n in b.namelist() if n=='res/raw/ytdlp' or(n.startswith('lib/') and not n.endswith('/'))}
            assert names and all(a.read(n)==b.read(n) for n in names)
        badging=subprocess.check_output([str(ROOT/'.toolchain/android-sdk/build-tools/36.0.0/aapt2'),'dump','badging',str(newpath)],text=True)
        assert "name='com.swararia.audiolab.spike' versionCode='4' versionName='0.4.0-spike'" in badging
        artifacts.append({'abi':abi,'same_signer':True,'old_apk_unchanged':True,'native_and_engine_payloads_identical':True,'old_apk_sha256':before['sha256'],'new_apk_sha256':item['sha256']})
    report={'version_code':4,'application_id_unchanged':True,'protected_source_files_unchanged':len(protected),'schema1_preserved':True,
            'migration':{'from':1,'to':2,'host_sql_preservation_passed':True,'generated_schema_tables_match':tables,'android_migration_runtime_tested':False},
            'artifacts':artifacts,'device_upgrade_tested':False,'no_new_dependencies':True}
    # Compare resolved source dependency declarations to the exact approved checkpoint.
    with zipfile.ZipFile(checkpoint) as archive:
        old_gradle=archive.read('app/build.gradle').decode()
    new_gradle=(ROOT/'app/build.gradle').read_text()
    assert old_gradle.split('dependencies {',1)[1]==new_gradle.split('dependencies {',1)[1]
    (ROOT/'verification/update-compatibility-0.4.0.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
if __name__=='__main__':main()
