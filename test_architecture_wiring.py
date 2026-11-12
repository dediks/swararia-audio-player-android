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
    def test_explicit_deletion_owner_is_retained_and_bound_to_current_connection(self):
        vm=(SRC/'AppViewModel.kt').read_text()
        activity=(SRC/'MainActivity.kt').read_text()
        self.assertIn('val playbackDeletion = PlaybackDeletion()', vm)
        self.assertIn('playbackDeletion.delete(id) { library.delete(id) }', vm)
        self.assertIn('model.playbackDeletion.connect(port)', activity)
        self.assertIn('model.playbackDeletion.disconnect(it)', activity)
        self.assertIn('model.playbackDeletion.reconcile()', activity)
        self.assertIn('player.removeMediaItem(index)', activity)
        delete=activity.split('private fun deleteTrack(',1)[1].split('private fun playTrack(',1)[0]
        self.assertNotIn('controller?.', delete)
        self.assertIn('model.delete(track.id, done)', delete)
        play=activity.split('private fun playTrack(',1)[1].split('private fun withNotificationPermission(',1)[0]
        self.assertIn('check(model.playbackDeletion.canPlay(track.id))', play)
        self.assertIn('tracks.filter { model.playbackDeletion.canPlay(it.id) }', play)
        self.assertNotIn('removeMediaItem',activity.split('LaunchedEffect(tracks, controller)',1)[1].split('SwarariaUi(',1)[0])

    def test_preview_error_keeps_retry_connected_and_invalidates_url(self):
        vm=(SRC/'AppViewModel.kt').read_text()
        ui=(SRC/'SearchPreviewPlayer.kt').read_text()
        root=(SRC/'SwarariaUi.kt').read_text()
        self.assertIn('let(searchRepository::invalidatePreview)', vm)
        self.assertIn('session.previewError()', vm)
        service=(SRC/'PlaybackService.kt').read_text()
        owner=(SRC/'PreviewSession.kt').read_text()
        self.assertIn('override fun onPlayerError', service)
        self.assertIn('previewOwner.previewError(); finishPreview(keepError = true)', service)
        self.assertIn('searchRepository.invalidatePreview(hit.videoId)', owner)
        self.assertIn('state.error?.let', ui)
        self.assertIn('TextButton(onClick = onRetry, enabled = !state.loading)', ui)
        self.assertIn('searchActions.previewSong', root)

    def test_platform_composition_is_separate_from_storage_logic(self):
        code=(SRC/'AppContainer.kt').read_text()
        self.assertIn('audio-library-spike.db',code)
        self.assertIn('AppViewModelFactory',code)
        storage=(SRC/'MediaLibrary.kt').read_text()
        self.assertNotIn('class SwarariaApp',storage)
        self.assertNotIn('@Entity',storage)
        self.assertIn(': AudioLibraryRepository',storage)
if __name__=='__main__':unittest.main(verbosity=2)
