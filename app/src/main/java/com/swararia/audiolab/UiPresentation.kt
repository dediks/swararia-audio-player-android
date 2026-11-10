package com.swararia.audiolab

import java.util.Locale

/** Display-only formatting: never changes a file, URI, stored name or database row. */
object UiPresentation {
    private val audioExtensions = setOf("mp3", "m4a", "mp4", "opus", "ogg", "webm", "wav", "flac", "aac")
    private val generatedIdSuffix = Regex("[ _]+\\[[A-Za-z0-9_-]{11}\\]$")
    private val separators = Regex("[_\\s]+")
    fun title(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val stem = if (dot > 0 && extension in audioExtensions) displayName.substring(0, dot) else displayName
        return stem.replace(generatedIdSuffix, "").replace(separators, " ").trim().ifBlank { displayName.ifBlank { "Audio" } }
    }
    fun metadata(displayName: String, durationMs: Long?): String {
        val type = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val label = if (type in audioExtensions) type.uppercase(Locale.ROOT) else "Audio"
        return "$label · ${LibraryRules.durationLabel(durationMs)}"
    }
    fun artworkIndex(id: Long): Int = Math.floorMod(id, 5L).toInt()
}
