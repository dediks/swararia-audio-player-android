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
    private val LEGACY_EXTENSIONS = setOf("mp3", "m4a", "mp4", "opus", "ogg", "webm", "wav", "flac", "aac")

    fun validUri(value: String): Boolean = Regex("content://media/external_primary/audio/media/[1-9][0-9]*").matches(value) &&
        value.substringAfterLast('/').toLongOrNull() != null

    fun validLegacyFileUri(value: String): Boolean {
        if (!value.startsWith("file:///") || value.length > 1024) return false
        if (value.any { it.code < 0x20 || it.code == 0x7F } || value.contains('?') || value.contains('#') || value.contains('\\')) {
            return false
        }
        val lower = value.lowercase()
        if (lower.contains("%00") || lower.contains("%2f") || lower.contains("%5c") || lower.contains("%2e")) {
            return false
        }
        val marker = "/Music/SwarariaAudioLab/"
        val idx = value.indexOf(marker)
        if (idx <= "file://".length) return false
        val filePart = value.substring(idx + marker.length)
        if (filePart.isEmpty() || filePart.contains('/') || filePart == "." || filePart == "..") return false
        val ext = filePart.substringAfterLast('.', "").lowercase()
        return ext in LEGACY_EXTENSIONS
    }

    fun isManagedUri(value: String, sdkInt: Int): Boolean =
        validUri(value) || (sdkInt < 29 && validLegacyFileUri(value))
}
