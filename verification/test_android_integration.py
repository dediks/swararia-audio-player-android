"""Host source contracts for the combined composition root, not Android compilation.
INTEGRATION_SOURCE_REV permits proving missing combined wiring on either parent.
"""
from pathlib import Path
import os
import re
import subprocess
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = 'app/src/main/java/com/swararia/audiolab/'

def source(path):
    rev = os.environ.get('INTEGRATION_SOURCE_REV')
    if rev:
        return subprocess.check_output(['git', 'show', f'{rev}:{path}'], cwd=ROOT, text=True)
    return (ROOT / path).read_text()

class AndroidIntegration(unittest.TestCase):
    def test_application_root_injects_shared_persistence_and_server(self):
        root = source(SRC + 'AppContainer.kt')
        self.assertIn('DesktopRemotePersistence(AndroidKeystoreDesktopRemoteStore(appContext))', root)
        self.assertRegex(root, r'private val rememberedDesktopSession by lazy')
        self.assertIn('fun desktopRemoteStore(): DesktopRemotePersistence = rememberedDesktopSession', root)
        self.assertEqual(root.count('AndroidKeystoreDesktopRemoteStore('), 1)
        self.assertIn('val remoteServerController: AndroidRemoteServerController by lazy', root)
        for arg in ['container.desktopRemoteStore(),', 'container.remoteServerController,']:
            self.assertIn(arg, root)
        for migration in ['MIGRATION_1_2', 'MIGRATION_2_3', 'MIGRATION_3_4']:
            self.assertIn('AudioMigrations.' + migration, root)

    def test_viewmodel_retains_both_lifecycles_and_deletion_notifications(self):
        vm = source(SRC + 'AppViewModel.kt')
        for required in ['desktopRemoteStore: DesktopRemotePersistence?',
                         'persistence = desktopRemoteStore', 'desktopRemote.release()',
                         'private val remoteServer: AndroidRemoteServerController?',
                         'remoteServer?.attachDeletedTrackChecker',
                         'remoteServer?.notifyLibraryDeletionsChanged()',
                         'val remoteServerActions = RemoteServerUiActions(']:
            self.assertIn(required, vm)
        cleared = re.search(r'override fun onCleared\(\).*?\}', vm, re.S).group()
        self.assertNotIn('forget', cleared)
        self.assertNotIn('stopServer', cleared)

    def test_version_and_retained_test_inventory(self):
        gradle = source('app/build.gradle')
        self.assertRegex(gradle, r'versionCode\s+8\b')
        self.assertRegex(gradle, r"versionName\s+'0\.6\.0-spike'")
        runner = source('verification/run_desktop_remote_session_checks.py')
        self.assertIn("'DesktopRemotePersistence'", runner)
        self.assertIn("'DesktopRemotePersistenceTest'", runner)
        wiring = source('verification/test_desktop_remote_wiring.py')
        self.assertIn('test_android_remote_server_wiring_and_security_boundaries', wiring)
        self.assertIn('test_keystore_store_uses_android_keystore_aes_gcm_without_shared_preferences', wiring)

if __name__ == '__main__':
    unittest.main()
