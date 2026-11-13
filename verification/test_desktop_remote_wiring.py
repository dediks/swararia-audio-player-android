"""Host source contracts only; functional TLS/session tests run separately."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/swararia/audiolab'


class DesktopRemoteWiring(unittest.TestCase):
    def test_network_implementation_is_built_only_at_composition_root(self):
        vm = (SRC / 'AppViewModel.kt').read_text()
        root = (SRC / 'AppContainer.kt').read_text()
        self.assertNotIn('DesktopRemoteHttpsApi()', vm,
                         'ViewModel must consume injected reusable port, not construct network implementation')
        self.assertIn('DesktopRemoteHttpsApi()', root)
        self.assertIn('container.desktopRemoteApi()', root)
        self.assertIn('AndroidKeystoreDesktopRemoteStore(', root)
        self.assertIn('container.desktopRemoteStore()', root)
        self.assertIn('private val rememberedDesktopSession by lazy', root)
        self.assertIn('fun desktopRemoteStore(): DesktopRemotePersistence = rememberedDesktopSession', root)
        self.assertIn('persistence = desktopRemoteStore', vm)
        self.assertNotIn('AndroidKeystoreDesktopRemoteStore', vm)
        self.assertIn('desktopRemote.release()', vm)

    def test_remote_feature_never_owns_phone_player_or_durable_credentials(self):
        names = ['DesktopRemoteProtocol', 'DesktopRemoteHttpsApi', 'DesktopRemoteSession', 'DesktopRemoteScreen', 'DesktopRemoteQrScanner']
        source = '\n'.join((SRC / (n + '.kt')).read_text() for n in names)
        for forbidden in ['MediaController', 'ExoPlayer', 'PlaybackService', 'SharedPreferences',
                          'SavedStateHandle', 'rememberSaveable', 'setDefaultSSLSocketFactory',
                          'setDefaultHostnameVerifier', 'setHostnameVerifier']:
            self.assertNotIn(forbidden, source)
        manifest = (ROOT / 'app/src/main/AndroidManifest.xml').read_text()
        self.assertNotIn('android.permission.CAMERA', manifest)
        self.assertIn('com.google.mlkit.vision.DEPENDENCIES', manifest)

    def test_keystore_store_uses_android_keystore_aes_gcm_without_shared_preferences(self):
        store = (SRC / 'AndroidKeystoreDesktopRemoteStore.kt').read_text()
        for required in ['AndroidKeyStore', 'AES/GCM/NoPadding', 'noBackupFilesDir']:
            self.assertIn(required, store)
        for forbidden in ['SharedPreferences', 'MODE_PRIVATE', 'MODE_WORLD_READABLE', 'AES/ECB', 'AES/CBC']:
            self.assertNotIn(forbidden, store)


if __name__ == '__main__':
    unittest.main()
