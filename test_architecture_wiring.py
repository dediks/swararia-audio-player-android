"""Structural boundaries only; behavioral session tests are in SearchSessionTest.kt."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parent;SRC=ROOT/'app/src/main/java/com/swararia/audiolab'
class Boundaries(unittest.TestCase):
    def test_viewmodel_uses_injected_dependencies_not_android_application(self):
        vm=(SRC/'AppViewModel.kt').read_text()
        self.assertNotIn('AndroidViewModel',vm)
        self.assertNotIn('YoutubeSearchRepository(',vm)
        self.assertNotIn('IngestStatus.',vm)
        self.assertIn('AudioLibraryRepository',vm)
        self.assertIn('AudioSearchRepository',vm)
        self.assertIn('SearchSession(',vm)
    def test_activity_resolves_factory_and_uses_central_operation_policy(self):
        code=(SRC/'MainActivity.kt').read_text()
        self.assertIn('AppViewModelFactory(',code)
        self.assertIn('model.canStartOnlineOperation()',code)
        self.assertNotIn('IngestStatus.',code)
    def test_platform_composition_is_separate_from_storage_logic(self):
        code=(SRC/'AppContainer.kt').read_text()
        self.assertIn('audio-library-spike.db',code)
        self.assertIn('AppViewModelFactory',code)
        storage=(SRC/'MediaLibrary.kt').read_text()
        self.assertNotIn('class SwarariaApp',storage)
        self.assertNotIn('@Entity',storage)
        self.assertIn(': AudioLibraryRepository',storage)
if __name__=='__main__':unittest.main(verbosity=2)
