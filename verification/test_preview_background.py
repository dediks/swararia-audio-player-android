"""Host source contracts for background ownership; not device lifecycle evidence."""
from pathlib import Path
import unittest
ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/com/swararia/audiolab'
class BackgroundPreview(unittest.TestCase):
    def test_service_owns_resolution_and_preparation(self):
        text = (SRC/'PlaybackService.kt').read_text()
        self.assertIn('PreviewSession(', text)
        self.assertIn('setMediaSource', text)
        self.assertIn('setEndPositionMs(OnlinePreviewRules.PREVIEW_MS)', text)
        self.assertIn('Player.REPEAT_MODE_OFF', text)
    def test_ui_is_controller_only(self):
        text = (SRC/'SearchPreviewPlayer.kt').read_text()
        self.assertNotIn('ExoPlayer', text)
        self.assertNotIn('ON_STOP', text)
        self.assertNotIn('player.release()', text)
    def test_viewmodel_dispatch_not_compose_collector(self):
        text = (SRC/'AppContainer.kt').read_text()
        self.assertIn('PreviewRemote(appContext)', text)
        self.assertIn('PreviewPlayback', (SRC/'SearchSession.kt').read_text())
    def test_restore_and_explicit_delete_do_not_resurrect_local_ids(self):
        text = (SRC/'PlaybackService.kt').read_text()
        self.assertIn('savedLocal', text)
        self.assertIn('forgetDeleted', text)
        self.assertIn('player.pause()', text)
        self.assertIn('PreviewProtocol.forget', (SRC/'MainActivity.kt').read_text())
    def test_numeric_timings_distinguish_native_init_execute_and_progress(self):
        repo = (SRC/'YoutubeSearchRepository.kt').read_text()
        for stage in ['metadata_engine_init_ms', 'metadata_execute_ms']:
            self.assertIn(stage, repo)
        service = (SRC/'PlaybackService.kt').read_text()
        for stage in ['playback_player_init_ms', 'preview_dispatch_to_service_ms', 'preview_tap_to_playing_ms', 'preview_prepare_to_ready_ms', 'preview_prepare_to_playing_ms', 'preview_start_ms']:
            self.assertIn(stage, service)
        ingest = (SRC/'IngestService.kt').read_text()
        self.assertIn('download_execute_to_first_progress_ms', ingest)
        self.assertNotIn('download_first_byte_ms', ingest)
    def test_controller_reconnects_and_does_not_lose_stop_during_start_ack(self):
        remote = (SRC/'PreviewRemote.kt').read_text()
        self.assertIn('handler.postDelayed(reconnect, reconnectDelay)', remote)
        self.assertIn('?: lastExtras', remote)
        self.assertIn('requested ?: controller?.sessionExtras', remote)
        self.assertIn('if (requestedGeneration > extras.getLong', remote)
        self.assertIn('if (!closed && future === connecting)', remote)
        activity = (SRC/'MainActivity.kt').read_text()
        self.assertIn('override fun onDisconnected(player: MediaController)', activity)
        self.assertIn('if (!isDestroyed) connectPlayer()', activity)
    def test_resolved_remote_preview_remains_visible_without_signed_media_in_ui(self):
        root = (SRC/'SwarariaUi.kt').read_text()
        active = next(line for line in root.splitlines() if 'val previewActive =' in line)
        # Remote state deliberately has no PreviewMedia. A public hit still owns the player slot.
        self.assertIn('it.hit != null', active)
    def test_failed_initial_connection_retries_and_stale_command_failures_are_ignored(self):
        remote = (SRC/'PreviewRemote.kt').read_text()
        failure = remote.split('}.onFailure {', 1)[1].split('}, ContextCompat', 1)[0]
        self.assertIn('scheduleReconnect()', failure)
        self.assertIn('ticket != commandGeneration', remote)
        self.assertIn('this.controller !== player', remote)
    def test_resolving_notification_has_a_generation_checked_cancel_action(self):
        service = (SRC/'PlaybackService.kt').read_text()
        self.assertIn('.addAction(', service)
        self.assertIn('PendingIntent.getService(', service)
        start = service.split('override fun onStartCommand(', 1)[1].split('override fun onUpdateNotification', 1)[0]
        self.assertIn('ACTION_CANCEL_PREVIEW', start)
        self.assertIn('lease.matches(', start)
    def test_service_uses_canonical_media3_error_constants(self):
        service = (SRC/'PlaybackService.kt').read_text()
        self.assertIn('import androidx.media3.session.SessionError', service)
        self.assertNotIn('SessionResult.RESULT_ERROR_', service)
    def test_secrets_not_session_metadata(self):
        text = (SRC/'PlaybackService.kt').read_text()
        section = text[text.index('private fun stateExtras()'):text.index('private fun publishState()')]
        self.assertNotIn('.url', section)
        self.assertNotIn('.headers', section)
        self.assertNotIn('setUri(media.url)', text)
if __name__ == '__main__': unittest.main()
