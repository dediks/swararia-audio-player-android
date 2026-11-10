from pathlib import Path
import hashlib,json,zipfile
ROOT=Path(__file__).resolve().parent
DIST=ROOT/'dist';DIST.mkdir(exist_ok=True)
EXCLUDED={'.toolchain','.cache','.gradle','.kotlin','.checkpoints','build','dist','__pycache__'}
allowed={'.kt','.xml','.gradle','.properties','.py','.sh','.md','.json','.txt','.jar'}
files=[]
for p in ROOT.rglob('*'):
    if not p.is_file() or p.is_symlink() or not p.resolve().is_relative_to(ROOT):continue
    rel=p.relative_to(ROOT)
    if any(part in EXCLUDED for part in rel.parts):continue
    if p.suffix not in allowed and rel.as_posix()!='app/src/main/res/raw/ytdlp' and p.name!='.gitignore':continue
    files.append(p)
files=sorted(files)
archive=DIST/'swararia-audio-lab-0.1.0-spike-source.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
    for p in files:z.write(p,p.relative_to(ROOT))
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    assert len(z.namelist())==len(files)==len(set(z.namelist()))
    assert all(not any(part in EXCLUDED for part in Path(n).parts) and not n.endswith(('.keystore','.jks','.apk')) for n in z.namelist())
    assert all(n in z.namelist() for n in ['README.md','DEVICE-TEST.md','app/build.gradle','app/src/main/AndroidManifest.xml','app/src/main/res/raw/ytdlp','engine-lock.json','app/src/main/java/com/swararia/audiolab/OrganizerScreen.kt'])
with archive.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
print(json.dumps({'path':str(archive),'files':len(files),'bytes':archive.stat().st_size,'sha256':digest},indent=2))
