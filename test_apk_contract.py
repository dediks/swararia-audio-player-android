"""Host-only APK packaging checks. These do NOT run an Android device."""
from pathlib import Path
import subprocess, unittest, zipfile, hashlib, json
ROOT = Path(__file__).resolve().parent
APK = ROOT/'app/build/outputs/apk/debug/app-arm64-v8a-debug.apk'
class AndroidPackageContract(unittest.TestCase):
    def test_android10_ingest_launcher_and_native_engine_are_bundled(self):
        self.assertTrue(APK.is_file(), 'The installable debug APK does not exist yet')
        aapt = ROOT/'.toolchain/android-sdk/build-tools/36.0.0/aapt2'
        manifest = subprocess.check_output([str(aapt),'dump','badging',str(APK)],text=True)
        self.assertIn("package: name='com.swararia.audiolab.spike'",manifest)
        self.assertRegex(manifest, r"(?m)^(?:minSdkVersion|sdkVersion):'29'$")
        self.assertIn("targetSdkVersion:'36'",manifest)
        self.assertIn("launchable-activity: name='com.swararia.audiolab.MainActivity'",manifest)
        with zipfile.ZipFile(APK) as z:
            names=z.namelist()
            engine=json.loads((ROOT/'engine-lock.json').read_text())
            self.assertEqual(engine['sha256'], hashlib.sha256(z.read('res/raw/ytdlp')).hexdigest(), 'The APK must actually include the fixed engine override')
            self.assertTrue(any(n.startswith('classes') and n.endswith('.dex') for n in names))
            native=[n for n in names if n.startswith('lib/arm64-v8a/')]
            self.assertTrue(any('python' in n for n in native), 'Bundled Python runtime is missing')
            self.assertTrue(any('ffmpeg' in n for n in native), 'Bundled FFmpeg runtime is missing')
            self.assertTrue(any('qjs' in n or 'quickjs' in n for n in native), 'Bundled JavaScript runtime is missing')
        tree=subprocess.check_output([str(aapt),'dump','xmltree',str(APK),'--file','AndroidManifest.xml'],text=True)
        self.assertIn('com.swararia.audiolab.IngestService',tree)
        self.assertNotIn('android.permission.MANAGE_EXTERNAL_STORAGE',tree)
        self.assertNotIn('android.permission.WRITE_EXTERNAL_STORAGE',tree)
        self.assertNotIn('android.permission.READ_MEDIA_AUDIO',tree)
    def test_local_playback_service_is_in_the_installable_package(self):
        self.assertTrue(APK.is_file())
        aapt=ROOT/'.toolchain/android-sdk/build-tools/36.0.0/aapt2'
        tree=subprocess.check_output([str(aapt),'dump','xmltree',str(APK),'--file','AndroidManifest.xml'],text=True)
        self.assertTrue('com.swararia.audiolab.PlaybackService' in tree, 'Local playback service is not packaged yet')
        self.assertIn('android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK',tree)
        with zipfile.ZipFile(APK) as z:
            dex=b''.join(z.read(n) for n in z.namelist() if n.startswith('classes') and n.endswith('.dex'))
            self.assertIn(b'Lcom/swararia/audiolab/PlaybackService;',dex)
    def test_live_rename_ui_and_persistent_rename_history_are_in_the_apk(self):
        with zipfile.ZipFile(APK) as z:
            dex=b''.join(z.read(n) for n in z.namelist() if n.startswith('classes') and n.endswith('.dex'))
        self.assertIn(b'Lcom/swararia/audiolab/SwarariaDialogsKt;', dex, 'Live rename UI facade is missing')
        self.assertIn(b'Lcom/swararia/audiolab/RenameRecord;',dex)
    def test_update_contains_native_search_branding_and_no_design_annotations(self):
        aapt=ROOT/'.toolchain/android-sdk/build-tools/36.0.0/aapt2'
        badging=subprocess.check_output([str(aapt),'dump','badging',str(APK)],text=True)
        self.assertIn("versionCode='4'",badging)
        self.assertIn("versionName='0.4.0-spike'",badging)
        with zipfile.ZipFile(APK) as z:
            names=z.namelist()
            dex=b''.join(z.read(n) for n in names if n.startswith('classes') and n.endswith('.dex'))
            for item in [b'Lcom/swararia/audiolab/SearchProvider;',b'Lcom/swararia/audiolab/SearchHit;',b'Lcom/swararia/audiolab/YoutubeSearchState;',b'Lcom/swararia/audiolab/SwarariaUiKt;', b'Lcom/swararia/audiolab/PlaybackMode;', b'Lcom/swararia/audiolab/PlaybackControlsKt;', b'Lcom/swararia/audiolab/SearchPreviewPlayerKt;', b'Lcom/swararia/audiolab/AddAudioScreenKt;']:
                self.assertIn(item,dex)
            plugin='assets/search_plugins/yt_dlp_plugins/extractor/swararia_music.py'
            self.assertEqual(z.read(plugin),(ROOT/'app/src/main/assets/search_plugins/yt_dlp_plugins/extractor/swararia_music.py').read_bytes())
            self.assertTrue(any('swararia_logo' in n for n in names),'Provided logo must be packaged')
            self.assertTrue(any('ic_launcher' in n for n in names),'Adaptive launcher resources must be packaged')
            for annotation in [b'KONSEP UI',b'DATA CONTOH',b'Logo asli',b'Hujan di jendela']:
                self.assertNotIn(annotation,dex,'Design annotations/example tracks must not ship in app code')
if __name__=='__main__':unittest.main(verbosity=2)
