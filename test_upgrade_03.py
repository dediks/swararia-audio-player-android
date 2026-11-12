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

    def test_mode_control_stays_service_backed_with_compact_dropdown(self):
        code=(SRC/'PlaybackControls.kt').read_text()
        for needle in ['DropdownMenu(', 'UiIcons.ChevronDown', 'UiIcons.Loop', 'UiIcons.Fade',
                       'target.isSessionCommandAvailable(command)', 'withTimeout(5000L)',
                       'send(PlaybackCommands.setMode', 'send(PlaybackCommands.fade',
                       'state.fadeSupported && state.playing && !state.busy && !pending']:
            self.assertIn(needle, code)

    def test_playback_polling_is_foreground_scoped(self):
        code=(SRC/'PlaybackControls.kt').read_text()
        self.assertIn('repeatOnLifecycle(Lifecycle.State.STARTED)',code)

    def test_background_preview_is_not_owned_by_composition(self):
        code=(SRC/'SearchPreviewPlayer.kt').read_text()
        self.assertNotIn('ON_STOP',code)
        self.assertNotIn('ExoPlayer',code)
        self.assertNotIn('release()',code)
        self.assertIn('PreviewSession(', (SRC/'PlaybackService.kt').read_text())

    def test_local_replacement_restores_before_changing_single_service_player(self):
        code=(SRC/'PlaybackService.kt').read_text()
        self.assertIn('override fun onPlayerCommandRequest',code)
        self.assertIn('Player.COMMAND_CHANGE_MEDIA_ITEMS, Player.COMMAND_SET_MEDIA_ITEM',code)
        self.assertIn('finishPreview()',code)
        self.assertEqual(1,code.count('ExoPlayer.Builder('))

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
        self.assertTrue(player.is_file(),'Controller-backed Preview presentation is missing')
        code=player.read_text()
        for needle in ['repeatOnLifecycle(Lifecycle.State.STARTED)','OnlinePreviewRules.PREVIEW_MS','player.pause()', 'onDispose { player?.removeListener(listener) }']:
            self.assertIn(needle,code)
if __name__=='__main__':unittest.main(verbosity=2)
