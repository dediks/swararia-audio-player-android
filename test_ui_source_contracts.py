"""Host-only Kotlin source contracts, not Compose/device or compilation tests."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parent
SRC = ROOT / 'app/src/main/java/com/swararia/audiolab'


class NativeUiSourceContracts(unittest.TestCase):
    def source(self):
        target = SRC / 'SwarariaUi.kt'
        self.assertTrue(target.is_file(), 'Approved native UI root is missing')
        return target.read_text()

    def test_root_preserves_integration_api_and_two_destinations(self):
        code = self.source()
        signature = code.split('fun SwarariaUi(', 1)[1].split('\n) {', 1)[0]
        expected = [
            'tracks: List<AudioTrack>', 'controller: MediaController?', 'ingest: IngestState',
            'search: YoutubeSearchState', 'lastRename: RenameRecord?', 'organizerBusy: Boolean',
            'snackbarHostState: SnackbarHostState', 'onDownload: (String, Boolean) -> Unit',
            'onImport: () -> Unit', 'onUpdate: () -> Unit', 'onCancel: () -> Unit',
            'onPlay: (AudioTrack) -> Unit', 'onRename: (Long, String, () -> Unit) -> Unit',
            'onUndo: () -> Unit', 'onSearch: (String, SearchProvider) -> Unit',
            'onCancelSearch: () -> Unit',
        ]
        self.assertEqual([line.strip().rstrip(',') for line in signature.splitlines() if line.strip()], expected)
        self.assertIn('NavigationBarItem(', code)
        self.assertIn('Text("Pustaka")', code)
        self.assertIn('Text("Tambah audio")', code)
        self.assertIn('BackHandler(', code)
        self.assertIn('WindowInsets.safeDrawing', code)
        self.assertIn('.imePadding()', code)
        for annotation in ['KONSEP UI', 'DATA CONTOH', 'Logo asli', 'Hujan di jendela']:
            self.assertNotIn(annotation, code)

    def test_library_uses_real_metadata_and_validated_actual_filename_for_rename(self):
        code = self.source()
        self.assertIn('private fun LibraryPage(', code)
        self.assertIn('tracks.filter', code)
        self.assertIn('compareByDescending<AudioTrack> { it.addedAt }', code)
        self.assertIn('UiPresentation.title(track.displayName)', code)
        self.assertIn('UiPresentation.metadata(track.displayName, track.durationMs)', code)
        self.assertIn('private fun RenameAudioDialog(', code)
        self.assertIn('track.displayName.substringBeforeLast', code)
        self.assertIn('LibraryRules.renamedDisplayName(track.displayName, title)', code)
        self.assertIn('onRename(track.id, title, onDismiss)', code)
        self.assertIn('private fun RenameHistoryDialog(', code)
        self.assertIn('record.fromName', code)
        self.assertIn('record.toName', code)
        self.assertIn('onClick = onUndo', code)

    def test_search_and_paste_require_fresh_consent_and_share_exclusion_guards(self):
        code = self.source()
        required = [
            'private fun AddAudioPage(', 'private fun SearchDownloadDialog(',
            'var authorized by remember { mutableStateOf(false) }',
            'url = it; authorized = false', 'onDownload(hit.url, true)',
            'selectedHit = hit', 'YoutubeThumbnail(hit.thumbnailUrl, hit.title',
            'val canStart = !ingest.busy && !search.busy',
            'YoutubeSearchRules.validatedQuery(query)', 'onCancelSearch',
            'hit.channel ?: provider.label', 'Durasi belum tersedia',
            'LibraryRules.validatedHttpsUrl(url)', 'Text("Tautan")', 'Text("Cari")',
        ]
        for needle in required:
            self.assertTrue(needle in code, 'Missing add/search safeguard: ' + needle)
        consent = code.split('private fun SearchDownloadDialog(', 1)[1].split('\n@Composable', 1)[0]
        self.assertTrue('enabled = canStart && authorized' in consent)
        self.assertNotIn('LaunchedEffect', consent)
        self.assertNotIn('SearchHit(', code)

    def test_playback_observation_is_lifecycle_scoped_and_queue_is_controller_order(self):
        code = self.source()
        for needle in [
            'repeatOnLifecycle(Lifecycle.State.STARTED)', 'p.addListener(listener)', 'p.removeListener(listener)',
            'snapshotFlow { state.playing }.collectLatest', 'if (!p.isConnected)',
            'p.getMediaItemAt(index)', 'p.mediaItemCount', 'private fun toggleSwarariaPlayback(',
            'p.prepare()', 'p.play()', 'p.pause()', 'private fun FocusedPlayer(',
            'private fun MiniPlayer(', 'private fun QueueDialog(',
            'remember(playback.mediaId, playback.currentIndex)', 'playback.queue.size',
            'Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM', 'p.currentMediaItem?.mediaId == playback.mediaId',
        ]:
            self.assertTrue(needle in code, 'Missing playback safeguard: ' + needle)
        self.assertNotIn('setMediaItems', code)
        self.assertNotIn('onPlay(current)', code)

    def test_theme_uses_approved_palette_and_local_variable_inter(self):
        code = (SRC / 'AppUi.kt').read_text()
        for color in ['0xFF101715', '0xFF19231F', '0xFF8CE3C7', '0xFFF1F5EF', '0xFFA9B6AD']:
            self.assertTrue(color in code, 'Missing approved token: ' + color)
        self.assertTrue('R.font.inter_variable' in code)
        self.assertTrue('FontVariation.Settings' in code)
        self.assertTrue('typography =' in code)

    def test_stopped_and_ended_playback_resume_without_extra_pause_or_queue_reset(self):
        code = self.source()
        predicate = code.split('private fun playbackRequestsPause(', 1)
        self.assertTrue(len(predicate) == 2, 'Stopped/ended session needs a distinct resume action')
        predicate = predicate[1].split('\nprivate fun ', 1)[0]
        for condition in ['Player.STATE_IDLE', 'Player.STATE_ENDED', 'p.playerError == null']:
            self.assertTrue(condition in predicate, 'Missing pause exclusion: ' + condition)
        self.assertTrue('if (playbackRequestsPause(p)) p.pause() else' in code)
        self.assertTrue('if (p.currentMediaItemIndex == entry.index) toggleSwarariaPlayback(p)' in code)

    def test_unknown_duration_has_no_filled_seek_indicator_and_ended_is_not_paused(self):
        code = self.source()
        self.assertTrue('val displayed = if (playback.durationMs == null) 0f else' in code,
                        'Unknown duration must not appear to be 100% complete')
        self.assertTrue('playback.ended -> "Selesai · Audio lokal"' in code,
                        'Finished playback must not be labeled as paused')


if __name__ == '__main__':
    unittest.main(verbosity=2)
