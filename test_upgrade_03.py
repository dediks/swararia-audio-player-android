"""0.3 wiring contracts. These inspect source, not Android runtime behavior."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parent
SRC=ROOT/'app/src/main/java/com/swararia/audiolab'
class UpgradeWiring(unittest.TestCase):
    def test_retry_keeps_playlist_scope_and_page(self):
        vm=(SRC/'SearchSession.kt').read_text()
        self.assertIn('fun retrySearch()',vm)
        retry=vm.split('fun retrySearch()',1)[1].split('fun openPlaylist',1)[0]
        for value in ['current.query, current.provider, current.kind, current.sort', 'append = current.loadedThrough > 0',
                      'playlistId = current.playlistId', 'playlistTitle = current.playlistTitle']:
            self.assertIn(value,retry)
        code=(SRC/'AddAudioScreen.kt').read_text()
        self.assertIn('onClick = actions.retry',code)

    def test_playback_polling_is_foreground_scoped(self):
        code=(SRC/'PlaybackControls.kt').read_text()
        self.assertIn('repeatOnLifecycle(Lifecycle.State.STARTED)',code)

    def test_background_event_stops_player_without_waiting_for_ui_state_collection(self):
        code=(SRC/'SearchPreviewPlayer.kt').read_text()
        self.assertIn('activePreviewPlayer?.stop()',code)

    def test_preview_stops_when_main_player_resumes(self):
        code=(SRC/'SearchPreviewPlayer.kt').read_text()
        self.assertIn('override fun onIsPlayingChanged',code)
        self.assertIn('controller?.addListener(mainListener)',code)
        self.assertIn('controller?.removeListener(mainListener)',code)

    def test_playback_controls_are_visible_in_focused_player(self):
        root=(SRC/'SwarariaPlayer.kt').read_text().split('internal fun FocusedPlayer(',1)[1]
        self.assertIn('PlaybackModeBar(controller',root)

    def test_new_search_and_preview_are_connected_to_activity(self):
        activity=(SRC/'MainActivity.kt').read_text()
        root=(SRC/'SwarariaUi.kt').read_text()
        self.assertIn('searchActions = SearchActions(',activity)
        self.assertIn('model.preview.collectAsStateWithLifecycle()',activity)
        self.assertIn('model.canStartOnlineOperation()',activity)
        self.assertIn('AddAudioScreen(',root)
        player=SRC/'SearchPreviewPlayer.kt'
        self.assertTrue(player.is_file(),'Lifecycle-scoped online preview player is missing')
        code=player.read_text()
        for needle in ['Lifecycle.Event.ON_STOP','release()','OnlinePreviewRules.PREVIEW_MS','controller.pause()', 'STATE_ENDED']:
            self.assertIn(needle,code)
if __name__=='__main__':unittest.main(verbosity=2)
