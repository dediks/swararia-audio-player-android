package com.swararia.audiolab

object ArtworkRules {
    const val MAX_BYTES = 524_288
    const val TRANSFER_BUDGET_MS = 15_000L
    fun transferWithinBudget(startNanos: Long, nowNanos: Long): Boolean =
        nowNanos - startNanos < TRANSFER_BUDGET_MS * 1_000_000L
    fun validKey(key: String?): Boolean = key != null && Regex("[0-9a-f]{64}").matches(key)
    fun validDimensions(width: Int, height: Int): Boolean = width in 1..4096 && height in 1..4096 && width.toLong() * height <= 4_194_304
}

object ManagedCopyRules {
    const val PATH = "Music/SwarariaAudioLab/"
    fun validUri(value: String): Boolean = Regex("content://media/external_primary/audio/media/[1-9][0-9]*").matches(value) &&
        value.substringAfterLast('/').toLongOrNull() != null
}
