package com.swararia.audiolab

import java.util.Locale

enum class LibraryGroupMode(val label: String) {
    ALL("Semua"),
    PLAYLIST("Playlist"),
    ARTIST("Artis"),
    ALBUM("Album"),
    FORMAT("Format"),
}

data class LibraryTrackGroup<T>(
    val key: String,
    val label: String,
    val items: List<T>,
    val totalDurationMs: Long,
)

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
    fun trackSubtitle(displayName: String, artist: String?, durationMs: Long?): String {
        val base = metadata(displayName, durationMs)
        val cleanArtist = artist?.trim()?.takeIf { it.isNotEmpty() }
        return if (cleanArtist != null) "$cleanArtist · $base" else base
    }
    fun groupSummary(count: Int, totalDurationMs: Long): String =
        if (totalDurationMs > 0L) "$count lagu · ${LibraryRules.durationLabel(totalDurationMs)}"
        else "$count lagu"
    fun <T> groupItems(
        items: List<T>,
        keySelector: (T) -> String,
        durationSelector: (T) -> Long?,
        fallbackLabel: String,
    ): List<LibraryTrackGroup<T>> {
        val grouped = LinkedHashMap<String, MutableList<T>>()
        for (item in items) {
            val raw = keySelector(item).trim().ifEmpty { fallbackLabel }
            grouped.getOrPut(raw) { mutableListOf() }.add(item)
        }
        return grouped.entries
            .sortedWith(compareBy<Map.Entry<String, MutableList<T>>> { it.key == fallbackLabel }
                .thenBy { it.key.lowercase(Locale.ROOT) })
            .map { (label, list) ->
                var totalDuration = 0L
                for (entry in list) totalDuration += (durationSelector(entry) ?: 0L).coerceAtLeast(0L)
                LibraryTrackGroup(
                    key = label,
                    label = label,
                    items = list,
                    totalDurationMs = totalDuration,
                )
            }
    }
    fun artworkIndex(id: Long): Int = Math.floorMod(id, 5L).toInt()
}
