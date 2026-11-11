"""Host-only Kotlin source contracts, not Compose/device or compilation tests."""
from pathlib import Path
import re
import unittest
import hashlib
import json


def declarations(code):
    """Slice this UI's top-level declarations, retaining annotations and bodies."""
    pattern = r'(?m)^(?:@[^\n]+\n)*(?:(?:private|internal|public|inline|data) )*(?:fun|class|val) (\w+)'
    matches = list(re.finditer(pattern, code))
    return {m[1]: code[m.start():matches[i + 1].start() if i + 1 < len(matches) else len(code)].strip()
            for i, m in enumerate(matches)}


def declaration_hash(code):
    normalized = re.sub(r'(?m)^(private|internal|public) ', '', code).strip()
    return hashlib.sha256(normalized.encode()).hexdigest()

ROOT = Path(__file__).resolve().parent
SRC = ROOT / 'app/src/main/java/com/swararia/audiolab'


class NativeUiSourceContracts(unittest.TestCase):
    def source(self):
        target = SRC / 'SwarariaUi.kt'
        self.assertTrue(target.is_file(), 'Approved native UI root is missing')
        return target.read_text()

    def test_active_declarations_equal_baseline_and_protected_files_are_byte_identical(self):
        baseline = json.loads((ROOT / 'verification/ui-split-baseline.json').read_text())
        found = {}
        for path in SRC.glob('*.kt'):
            for name, body in declarations(path.read_text()).items():
                if name in baseline['declaration_sha256']:
                    self.assertNotIn(name, found, 'Duplicate active declaration: ' + name)
                    found[name] = declaration_hash(body)
        self.assertEqual(found, baseline['declaration_sha256'])
        for name, digest in baseline['byte_identical_files'].items():
            self.assertEqual(hashlib.sha256((SRC / name).read_bytes()).hexdigest(), digest, name)

    def test_ui_split_has_narrow_entry_points_and_no_dead_screens(self):
        expected = {
            'SwarariaUi.kt': {'SwarariaUi', 'navigationColors'},
            'LibraryScreen.kt': {'LibraryPage', 'searchFieldColors', 'LibraryAudioRow'},
            'SwarariaDialogs.kt': {'RenameAudioDialog', 'RenameHistoryDialog', 'DiagnosticsDialog', 'EngineSettingsDialog'},
            'SwarariaPlayer.kt': {'SessionQueueItem', 'SessionPlayback', 'readSwarariaPlayback',
                'rememberSwarariaPlayback', 'connectedCommand', 'playbackRequestsPause',
                'toggleSwarariaPlayback', 'playbackTitle', 'playbackStatus', 'MiniPlayer', 'FocusedPlayer', 'QueueDialog'},
            'UiComponents.kt': {'BrandLogo', 'TrackArtwork', 'GlyphButton', 'EmptyMessage'},
            'AppUi.kt': {'SwarariaFont', 'SwarariaTypography', 'AudioTheme'},
        }
        internal = {'LibraryPage', 'RenameAudioDialog', 'RenameHistoryDialog', 'EngineSettingsDialog',
                    'SessionQueueItem', 'SessionPlayback', 'rememberSwarariaPlayback',
                    'toggleSwarariaPlayback', 'MiniPlayer', 'FocusedPlayer',
                    'BrandLogo', 'TrackArtwork', 'GlyphButton', 'EmptyMessage'}
        for filename, names in expected.items():
            with self.subTest(file=filename):
                path = SRC / filename
                self.assertTrue(path.is_file(), filename + ' is missing')
                code = path.read_text()
                self.assertTrue(code.startswith('package com.swararia.audiolab\n'))
                actual = declarations(code)
                self.assertEqual(set(actual), names)
                for name, body in actual.items():
                    if name in {'SwarariaUi', 'AudioTheme'}:
                        continue
                    visibility = 'internal' if name in internal else 'private'
                    self.assertRegex(body, r'(?m)^' + visibility + r' (?:inline )?(?:data class|fun|val) ' + name + r'\b')
        for filename in ['PlayerScreen.kt', 'OrganizerScreen.kt']:
            self.assertFalse((SRC / filename).exists(), 'Dead screen must be removed: ' + filename)

    def test_root_preserves_integration_api_and_two_destinations(self):
        code = self.source()
        signature = code.split('fun SwarariaUi(', 1)[1].split('\n) {', 1)[0]
        expected = [
            'tracks: List<AudioTrack>', 'controller: MediaController?', 'ingest: IngestState',
            'search: YoutubeSearchState', 'lastRename: RenameRecord?', 'organizerBusy: Boolean',
            'snackbarHostState: SnackbarHostState', 'searchActions: SearchActions', 'onDownload: (String, Boolean) -> Unit',
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
        code = (SRC / 'LibraryScreen.kt').read_text() + (SRC / 'SwarariaDialogs.kt').read_text()
        self.assertIn('internal fun LibraryPage(', code)
        self.assertIn('tracks.filter', code)
        self.assertIn('compareByDescending<AudioTrack> { it.addedAt }', code)
        self.assertIn('UiPresentation.title(track.displayName)', code)
        self.assertIn('UiPresentation.metadata(track.displayName, track.durationMs)', code)
        self.assertIn('internal fun RenameAudioDialog(', code)
        self.assertIn('track.displayName.substringBeforeLast', code)
        self.assertIn('LibraryRules.renamedDisplayName(track.displayName, title)', code)
        self.assertIn('onRename(track.id, title, onDismiss)', code)
        self.assertIn('internal fun RenameHistoryDialog(', code)
        self.assertIn('record.fromName', code)
        self.assertIn('record.toName', code)
        self.assertIn('onClick = onUndo', code)

    def test_search_and_paste_require_fresh_consent_and_share_exclusion_guards(self):
        code = (SRC / 'AddAudioScreen.kt').read_text()
        required = [
            'fun AddAudioScreen(', 'var consent by remember { mutableStateOf(false) }',
            'url = it; consent = false', 'url = it.trim(); consent = false',
            'mode = 1; consent = false', 'mode = 0; consent = false',
            'if (idle && consent && validUrl != null) { consent = false; onDownload(validUrl, true) }',
            'selected = hit', 'YoutubeThumbnail(hit.thumbnailUrl, hit.title',
            'val idle = !ingest.busy && !search.busy && !actions.preview.loading',
            'YoutubeSearchRules.validatedQuery(query)', 'onCancelSearch',
            'LibraryRules.validatedHttpsUrl(url)', 'ConsentLine(consent, { consent = it }, idle)',
        ]
        for needle in required:
            self.assertIn(needle, code, 'Missing live add/search safeguard: ' + needle)
        consent = code.split('selected?.let { hit -> key(hit.videoId) {', 1)[1].split('\n@Composable', 1)[0]
        for needle in ['var authorized by remember { mutableStateOf(false) }',
                       'if (authorized && idle) { selected = null; onDownload(hit.url, true) }',
                       'enabled = authorized && idle', 'onDismissRequest = { selected = null }']:
            self.assertIn(needle, consent)
        self.assertNotIn('rememberSaveable', consent)
        self.assertNotIn('LaunchedEffect', consent)
        self.assertNotIn('SearchHit(', code)

    def test_playback_observation_is_lifecycle_scoped_and_queue_is_controller_order(self):
        code = (SRC / 'SwarariaPlayer.kt').read_text()
        for needle in [
            'repeatOnLifecycle(Lifecycle.State.STARTED)', 'p.addListener(listener)', 'p.removeListener(listener)',
            'snapshotFlow { state.playing }.collectLatest', 'if (!p.isConnected)',
            'p.getMediaItemAt(index)', 'p.mediaItemCount', 'internal fun toggleSwarariaPlayback(',
            'p.prepare()', 'p.play()', 'p.pause()', 'internal fun FocusedPlayer(',
            'internal fun MiniPlayer(', 'private fun QueueDialog(',
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
        code = (SRC / 'SwarariaPlayer.kt').read_text()
        predicate = code.split('private fun playbackRequestsPause(', 1)
        self.assertTrue(len(predicate) == 2, 'Stopped/ended session needs a distinct resume action')
        predicate = predicate[1].split('\nprivate fun ', 1)[0]
        for condition in ['Player.STATE_IDLE', 'Player.STATE_ENDED', 'p.playerError == null']:
            self.assertTrue(condition in predicate, 'Missing pause exclusion: ' + condition)
        self.assertTrue('if (playbackRequestsPause(p)) p.pause() else' in code)
        self.assertTrue('if (p.currentMediaItemIndex == entry.index) toggleSwarariaPlayback(p)' in code)

    def test_unknown_duration_has_no_filled_seek_indicator_and_ended_is_not_paused(self):
        code = (SRC / 'SwarariaPlayer.kt').read_text()
        self.assertTrue('val displayed = if (playback.durationMs == null) 0f else' in code,
                        'Unknown duration must not appear to be 100% complete')
        self.assertTrue('playback.ended -> "Selesai · Audio lokal"' in code,
                        'Finished playback must not be labeled as paused')


if __name__ == '__main__':
    unittest.main(verbosity=2)
