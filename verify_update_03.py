"""Read-only candidate/baseline compatibility checks; writes a public evidence report."""
from pathlib import Path
import hashlib,json,subprocess,zipfile
ROOT=Path(__file__).resolve().parent
OLD=ROOT.parent/'audio-library-android-spike/dist'
def digest(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def main():
    baseline=json.loads((ROOT/'verification/baseline-0.3.0.json').read_text())
    protected={p:digest(ROOT/p)==h for p,h in baseline['protected'].items()}
    assert all(protected.values()),protected
    previous=json.loads((OLD/'manifest-0.2.0.json').read_text())
    current=json.loads((ROOT/'verification/verification.json').read_text())
    artifacts=[]
    for item in current['artifacts']:
        abi='arm64-v8a' if 'arm64-v8a' in item['file'] else 'armeabi-v7a'
        old=next(a for a in previous['artifacts'] if abi in a['file'])
        oldpath=OLD/old['file']; newpath=ROOT/'app/build/outputs/apk/debug'/item['file']
        assert digest(oldpath)==old['sha256'] and digest(newpath)==item['sha256']
        assert old['certificate_sha256']==item['certificate_sha256']
        with zipfile.ZipFile(oldpath) as before,zipfile.ZipFile(newpath) as after:
            names=[n for n in before.namelist() if (n.startswith('lib/') and not n.endswith('/')) or n=='res/raw/ytdlp']
            assert set(names)=={n for n in after.namelist() if (n.startswith('lib/') and not n.endswith('/')) or n=='res/raw/ytdlp'}
            assert names and all(hashlib.sha256(before.read(n)).digest()==hashlib.sha256(after.read(n)).digest() for n in names)
        badging=subprocess.check_output([str(ROOT/'.toolchain/android-sdk/build-tools/36.0.0/aapt2'),'dump','badging',str(newpath)],text=True)
        assert "name='com.swararia.audiolab.spike' versionCode='3' versionName='0.3.0-spike'" in badging
        artifacts.append({'abi':abi,'same_signer':True,'old_apk_unchanged':True,'native_and_engine_payloads_identical':True,'new_apk_sha256':item['sha256'],'old_apk_sha256':old['sha256']})
    report={'core_schema_unchanged':all(protected.values()),'protected':protected,'artifacts':artifacts,'application_id_unchanged':True,'version_code':3,'device_upgrade_tested':False}
    (ROOT/'verification/update-compatibility-0.3.0.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
if __name__=='__main__':main()
