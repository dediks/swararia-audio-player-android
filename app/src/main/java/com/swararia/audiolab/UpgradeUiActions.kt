package com.swararia.audiolab

/** State and guarded operations supplied by the owner; presentation never opens storage. */
data class UpgradeUiActions(
    val jobs: List<DownloadJob>,
    val operationBusy: Boolean,
    val onDownloadBatch: (List<SearchHit>, Boolean) -> Unit,
    val onCancelBatch: () -> Unit,
    val onRetryFailed: (String) -> Unit,
    val onDelete: (AudioTrack, () -> Unit) -> Unit,
)

/** Playlist actions supplied by the ViewModel owner; presentation never opens storage. */
data class PlaylistUiActions(
    val onCreatePlaylist: (String, Long?) -> Unit = { _, _ -> },
    val onRenamePlaylist: (Long, String) -> Unit = { _, _ -> },
    val onDeletePlaylist: (Long) -> Unit = {},
    val onAddTrackToPlaylist: (Long, Long) -> Unit = { _, _ -> },
    val onRemoveTrackFromPlaylist: (Long, Long) -> Unit = { _, _ -> },
)
