"""Verify behavior-preserving refactor boundaries against its scoped recovery snapshot."""
from pathlib import Path
import hashlib,json,zipfile,subprocess,sys
ROOT=Path(__file__).resolve().parent
SNAPSHOT=ROOT/'.checkpoints/before-architecture-refactor.zip'
BASE='e96b931e2214006dfb956a004f662b895dc77340'
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def main():
    baseline=json.loads((ROOT/'verification/architecture-baseline.json').read_text())
    assert sha(SNAPSHOT)==baseline['snapshot_sha256']
    preserved=[]
    with zipfile.ZipFile(SNAPSHOT) as before:
        for name in before.namelist():
            parts=Path(name).parts
            protected=(name.startswith(('app/src/main/res/','app/src/main/assets/','app/schemas/','design/ux-revision-v3/')) or
                Path(name).name in {'build.gradle','settings.gradle','gradle.properties','engine-lock.json','AndroidManifest.xml','IngestService.kt','PlaybackService.kt','SearchPreviewPlayer.kt','PlaybackControls.kt','AddAudioScreen.kt'})
            if protected:
                assert (ROOT/name).read_bytes()==before.read(name),name
                preserved.append(name)
        old=before.read('app/src/main/java/com/swararia/audiolab/MediaLibrary.kt').decode()
        current=(ROOT/'app/src/main/java/com/swararia/audiolab/MediaLibrary.kt').read_text()
        old_body=old.split('class MediaLibrary',1)[1].split('{',1)[1]
        new_body=current.split('class MediaLibrary',1)[1].split('{',1)[1].replace('override ','')
        assert old_body==new_body,'Storage method bodies changed'
        entities=old[old.index('@Entity'):old.index('class SwarariaApp')].strip()
        assert (ROOT/'app/src/main/java/com/swararia/audiolab/AudioDatabase.kt').read_text().split('@Entity',1)[1].strip()==entities.split('@Entity',1)[1].strip()
    old_manifest=json.loads((ROOT/'dist/manifest-0.3.0.json').read_text())
    for artifact in old_manifest['artifacts']:
        assert sha(ROOT/'dist'/artifact['file'])==artifact['sha256'],'Previous APK changed'
    report={'base_commit':BASE,'preserved_files':len(preserved),'storage_bodies_unchanged':True,'room_declarations_unchanged':True,'previous_apks_unchanged':True,'mockup_preserved':True,'device_tested':False}
    if '--apk' in sys.argv:
        current=json.loads((ROOT/'verification/verification.json').read_text())
        report['candidate_apks']=[]
        for item in current['artifacts']:
            abi='arm64-v8a' if 'arm64-v8a' in item['file'] else 'armeabi-v7a'
            prev=next(a for a in old_manifest['artifacts'] if abi in a['file'])
            candidate=ROOT/'app/build/outputs/apk/debug'/item['file'];assert sha(candidate)==item['sha256']
            assert item['certificate_sha256']==prev['certificate_sha256']
            with zipfile.ZipFile(ROOT/'dist'/prev['file']) as a,zipfile.ZipFile(candidate) as b:
                names=lambda z:{n for n in z.namelist() if (n.startswith('lib/') and not n.endswith('/')) or n=='res/raw/ytdlp'}
                assert names(a)==names(b)
                assert all(a.read(n)==b.read(n) for n in names(a))
                dex=b''.join(b.read(n) for n in b.namelist() if n.startswith('classes') and n.endswith('.dex'))
                for required in [b'SearchSession;',b'AppViewModelFactory;',b'UiComponentsKt;',b'LibraryScreenKt;']:assert required in dex,required
                assert b'OrganizerScreenKt;' not in dex
            report['candidate_apks'].append({'abi':abi,'sha256':item['sha256'],'signer_unchanged':True,'native_and_engine_unchanged':True})
    path=ROOT/'verification/architecture-preservation.json'
    # This script owns its derived report, not any source or installer.
    path.write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if __name__=='__main__':main()
