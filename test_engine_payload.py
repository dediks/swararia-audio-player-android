"""Real host-Python CLI check; not an Android execution test."""
from pathlib import Path
import subprocess,sys,unittest,zipfile
ROOT=Path(__file__).resolve().parent
class BundledEngineCompatibility(unittest.TestCase):
    def test_engine_accepts_the_js_runtime_flag_added_by_the_wrapper(self):
        override=ROOT/'app/src/main/res/raw/ytdlp'
        if override.exists():payload=override.read_bytes()
        else:
            matches=list((ROOT/'.cache/gradle/caches/modules-2/files-2.1/io.github.deniscerri.youtubedl-android/library/0.19.0').glob('*/library-0.19.0.aar'))
            self.assertEqual(1,len(matches))
            with zipfile.ZipFile(matches[0]) as z:payload=z.read('res/raw/ytdlp')
        probe=ROOT/'.cache/tmp/pinned-engine-under-test.pyz';probe.parent.mkdir(parents=True,exist_ok=True);probe.write_bytes(payload)
        result=subprocess.run([sys.executable,str(probe),'--ignore-config','--js-runtimes','quickjs:/nonexistent-test-runtime','--list-extractors'],text=True,capture_output=True,timeout=30)
        self.assertEqual(0,result.returncode, 'Bundled engine rejects the wrapper CLI flag: '+result.stderr)
if __name__=='__main__':unittest.main(verbosity=2)
