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

    def test_android_remote_server_wiring_and_security_boundaries(self):
        container = (SRC / 'AppContainer.kt').read_text()
        self.assertIn('remoteServerController: AndroidRemoteServerController', container)
        self.assertIn('container.remoteServerController', container)

        swararia_ui = (SRC / 'SwarariaUi.kt').read_text()
        self.assertIn('3 -> RemoteTabScreen(', swararia_ui)

        remote_screen = (SRC / 'RemoteServerScreen.kt').read_text()
        for required in ['DesktopRemoteScreen(', 'RemoteServerUiState', 'RemoteServerUiActions', 'QRCodeWriter']:
            self.assertIn(required, remote_screen)
        for forbidden in ['PlaybackService', 'MediaController', 'ExoPlayer', 'AudioDatabase', 'MediaLibrary',
                          'AndroidRemoteTlsIdentity', 'SSLServerSocket', 'SharedPreferences']:
            self.assertNotIn(forbidden, remote_screen)

        server_sources = '\n'.join((SRC / (n + '.kt')).read_text() for n in [
            'AndroidRemoteTlsIdentity', 'AndroidRemoteServerEngine',
            'AndroidRemoteHttpListener', 'AndroidRemoteServerController',
        ])
        for forbidden in ['setDefaultSSLSocketFactory', 'setDefaultHostnameVerifier', 'SharedPreferences', 'http://']:
            self.assertNotIn(forbidden, server_sources)

        assets_dir = ROOT / 'app/src/main/assets/remote_server'
        for name in ['index.html', 'remote.css', 'remote.js']:
            self.assertTrue((assets_dir / name).is_file(), f'Missing remote_server asset: {name}')
        web_bundle = '\n'.join((assets_dir / n).read_text() for n in ['index.html', 'remote.js'])
        for forbidden in ['innerHTML', 'outerHTML', 'eval(', 'localStorage', 'sessionStorage']:
            self.assertNotIn(forbidden, web_bundle)


if __name__ == '__main__':
    unittest.main()
