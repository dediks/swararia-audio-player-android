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
