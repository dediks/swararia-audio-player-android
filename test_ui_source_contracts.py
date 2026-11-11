"""Approved UX v3 source contracts. Host-only: not Compose rendering or compilation.

The previous declaration-hash snapshot protected a body-preserving split, not this
explicitly approved redesign. Guard behavioral/render boundaries instead.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parent
SRC = ROOT / 'app/src/main/java/com/swararia/audiolab'

def source(name):
    path = SRC / name
    return path.read_text() if path.exists() else ''

class NativeUiSourceContracts(unittest.TestCase):
    def test_root_has_upgrade_contract_and_three_saveable_destinations(self):
        code = source('SwarariaUi.kt')
        for text in ['upgrade: UpgradeUiActions', 'rememberSaveable', 'BackHandler(',
                     'Text("Library")', 'Text("Search")', 'Text("Downloads")', 'DownloadsScreen(',
                     'WindowInsets.safeDrawing', '.imePadding()']:
            self.assertIn(text, code)
        actions = source('UpgradeUiActions.kt')
        for text in ['jobs: List<DownloadJob>', 'operationBusy: Boolean',
                     'onDownloadBatch: (List<SearchHit>, Boolean) -> Unit', 'onCancelBatch: () -> Unit',
                     'onRetryFailed: (String) -> Unit', 'onDelete: (AudioTrack, () -> Unit) -> Unit']:
            self.assertIn(text, actions)

    def test_exactly_one_root_playback_surface_and_no_search_top_player(self):
        code = source('SwarariaUi.kt')
        self.assertIn('if (previewActive)', code)
        self.assertIn('else if (playback.mediaId != null)', code)
        self.assertIn('SearchPreviewPlayer(', code)
        self.assertNotIn('SearchPreviewPlayer(', source('AddAudioScreen.kt'))
        self.assertIn('searchActions.stopPreview()', code)

    def test_search_selection_is_loaded_only_and_consent_is_fresh(self):
        code = source('AddAudioScreen.kt')
        for text in ['combinedClickable(', 'onLongClick =', 'selectionMode', 'selectedIds',
                     'loadedSongs', 'Text("Clear")', 'Text("Select all")', 'Download (',
                     'DownloadConsentDialog(', 'onDownloadBatch(listOf(hit), true)',
                     'SearchChoice(', 'trailingIcon =', 'KeyboardActions(onSearch',
                     'BackHandler(', 'actions.leavePlaylist()', 'YoutubeThumbnail(hit.thumbnailUrl, hit.title']:
            self.assertIn(text, code)
        self.assertNotIn('Card(', code)
        link_dialog = source('SwarariaDialogs.kt').split('internal fun DownloadLinkDialog(',1)[-1].split('\n@Composable',1)[0]
        self.assertIn('DownloadQueueRules.validatedPublicSourceUrl(url)',link_dialog)
        self.assertIn('YouTube / YT Music',link_dialog)
        self.assertIn('authorized = false',link_dialog)
        dialog = source('SwarariaDialogs.kt').split('internal fun DownloadConsentDialog(', 1)[-1].split('\n@Composable', 1)[0]
        self.assertIn('var authorized by remember { mutableStateOf(false) }', dialog)
        self.assertIn('enabled = authorized && !busy', dialog)
        self.assertNotIn('rememberSaveable', dialog)

    def test_downloads_are_job_derived_and_offer_only_supported_actions(self):
        code = source('DownloadsScreen.kt')
        for text in ['upgrade.jobs', '"COMPLETED"', '"RUNNING"', '"QUEUED"', '"FAILED"',
                     '"CANCELED"', 'job.progress', 'job.stage', 'job.error', 'job.thumbnailUrl',
                     'onCancelBatch', 'onRetryFailed', 'Cancel batch', 'Retry failed']:
            self.assertIn(text, code)
        self.assertNotIn('Pause', code)
        self.assertNotIn('Resume', code)

    def test_artwork_decode_is_bounded_async_and_file_only(self):
        code = source('UiComponents.kt')
        for text in ['artworkKey: String?', 'ArtworkStore.resolve(context, artworkKey)',
                     'Dispatchers.IO', 'inJustDecodeBounds = true', 'inSampleSize',
                     '512', '4 * 1024 * 1024', 'produceState', 'cover_0']:
            self.assertIn(text, code)
        for name in ['LibraryScreen.kt', 'SwarariaPlayer.kt']:
            self.assertIn('artworkKey', source(name))

    def test_delete_confirmation_identifies_real_file_and_permanence(self):
        code = source('SwarariaDialogs.kt')
        for text in ['internal fun DeleteAudioDialog(', 'track.displayName', 'Delete audio?',
                     'permanen', 'salinan', 'asli', 'onDelete(track, onDismiss)']:
            self.assertIn(text, code)
        row = source('LibraryScreen.kt')
        self.assertIn('Text("Rename")', row)
        self.assertIn('Text("Delete", color = MaterialTheme.colorScheme.error)', row)

    def test_preview_is_actual_state_seekable_and_lifecycle_authoritative(self):
        code = source('SearchPreviewPlayer.kt')
        for text in ['OnlinePreviewRules.PREVIEW_MS', 'Lifecycle.Event.ON_STOP',
                     'activePreviewPlayer?.stop()', 'release()', 'controller.pause()',
                     'Player.STATE_BUFFERING', 'player.currentPosition', 'Slider(',
                     'player.seekTo(', 'player.pause()', 'player.play()',
                     'remember(context)', 'setMediaItem', 'STATE_ENDED']:
            self.assertIn(text, code)
        self.assertNotIn('30 detik', code)
        self.assertNotIn('controller.play()', code)

    def test_playback_guards_queue_and_lifecycle_survive(self):
        code = source('SwarariaPlayer.kt')
        for text in ['repeatOnLifecycle(Lifecycle.State.STARTED)', 'p.removeListener(listener)',
                     'snapshotFlow { state.playing }.collectLatest', 'p.getMediaItemAt(index)',
                     'p.currentMediaItem?.mediaId == playback.mediaId',
                     'p.getMediaItemAt(entry.index).mediaId == entry.mediaId',
                     'Player.STATE_IDLE', 'Player.STATE_ENDED', 'p.playerError == null',
                     'PlaybackModeBar(controller']:
            self.assertIn(text, code)
        self.assertNotIn('setMediaItems', code)

    def test_no_sample_catalog_or_native_extraction_in_presentation(self):
        names = ['SwarariaUi.kt', 'AddAudioScreen.kt', 'LibraryScreen.kt', 'SwarariaPlayer.kt',
                 'UiComponents.kt', 'DownloadsScreen.kt']
        code = '\n'.join(source(name) for name in names)
        for text in ['SearchHit(', 'AudioTrack(', 'DownloadJob(', 'Cinta dan Airmata',
                     'DATA CONTOH', 'MOCKUP', 'MediaMetadataRetriever', 'YoutubeDL', 'AudioDatabase']:
            self.assertNotIn(text, code)

if __name__ == '__main__':
    unittest.main(verbosity=2)
