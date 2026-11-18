package com.swararia.audiolab

import java.util.Locale

enum class LibrarySectionTab(val label: String) {
    ALL_TRACKS("All Tracks"),
    PLAYLISTS("Playlists"),
}

enum class LibraryGroupMode(val label: String) {
    ALL("Semua"),
    ARTIST("Artis"),
    ALBUM("Album"),
    FORMAT("Format"),
}

enum class LibrarySongViewType(val label: String, val subtitle: String) {
    LIST("List", "Daftar lengkap dengan cover & kualitas"),
    COMPACT("Compact", "Baris padat lebih banyak lagu"),
    GRID("Grid", "Kartu cover multi-kolom"),
}

enum class DownloadFilterTab(val label: String) {
    ALL("Semua"),
    ACTIVE("Aktif"),
    COMPLETED("Selesai"),
    FAILED("Gagal"),
}

enum class DownloadQueueTab(val label: String) {
    ACTIVE("Antrean"),
    FAILED("Gagal"),
    COMPLETED("Selesai"),
}

data class DownloadQueueSummary(
    val total: Int,
    val running: Int,
    val queued: Int,
    val completed: Int,
    val failed: Int,
    val canceled: Int,
) {
    val active: Int get() = running + queued
    val finished: Int get() = completed + canceled
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
    private val extractedVideoId = Regex("[ _]+\\[([A-Za-z0-9_-]{11})\\]$")
    private val separators = Regex("[_\\s]+")
    private fun stemWithoutAudioExtension(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (dot > 0 && extension in audioExtensions) displayName.substring(0, dot) else displayName
    }
    fun sourceVideoId(displayName: String): String? =
        extractedVideoId.find(stemWithoutAudioExtension(displayName))?.groupValues?.getOrNull(1)
    fun title(displayName: String): String {
        val stem = stemWithoutAudioExtension(displayName)
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
    fun effectiveBitrateKbps(sizeBytes: Long, durationMs: Long?): Int? {
        if (sizeBytes <= 0L || durationMs == null || durationMs < 1_000L) return null
        val kbps = (sizeBytes * 8L + durationMs / 2L) / durationMs
        return kbps.takeIf { it > 0L }?.coerceAtMost(20_000L)?.toInt()
    }
    fun fileSizeLabel(sizeBytes: Long): String {
        if (sizeBytes <= 0L) return ""
        return if (sizeBytes < 1024L * 1024L) {
            "${maxOf(1L, (sizeBytes + 512L) / 1024L)} KB"
        } else {
            String.format(Locale.US, "%.1f MB", sizeBytes / (1024.0 * 1024.0))
        }
    }
    fun qualitySummary(displayName: String, sizeBytes: Long, durationMs: Long?): String {
        val type = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val label = if (type in audioExtensions) type.uppercase(Locale.ROOT) else "Audio"
        val parts = buildList {
            add(label)
            effectiveBitrateKbps(sizeBytes, durationMs)?.let { add("$it kbps") }
            fileSizeLabel(sizeBytes).takeIf { it.isNotEmpty() }?.let { add(it) }
            add(LibraryRules.durationLabel(durationMs))
        }
        return parts.joinToString(" · ")
    }
    fun trackSubtitleWithQuality(
        displayName: String,
        artist: String?,
        sizeBytes: Long,
        durationMs: Long?,
    ): String {
        val base = qualitySummary(displayName, sizeBytes, durationMs)
        val cleanArtist = artist?.trim()?.takeIf { it.isNotEmpty() }
        return if (cleanArtist != null) "$cleanArtist · $base" else base
    }
    fun highBitrateBadge(displayName: String, sizeBytes: Long, durationMs: Long?): String? {
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext == "flac" || ext == "wav") return "LOSSLESS"
        val kbps = effectiveBitrateKbps(sizeBytes, durationMs) ?: return null
        return if (kbps >= 192 && ext in audioExtensions) "HQ ${kbps}k" else null
    }
    fun searchQualityLabel(bitrateKbps: Int?, audioCodec: String?): String? {
        val kbps = bitrateKbps?.takeIf { it > 0 } ?: return null
        val codec = audioCodec?.trim()?.takeIf { it.isNotEmpty() }
        return if (codec != null) "$codec · $kbps kbps" else "$kbps kbps"
    }
    fun compactTrackSpec(displayName: String, sizeBytes: Long, durationMs: Long?): String {
        val ext = LibraryRules.audioFormatLabel(displayName)
        val kbps = effectiveBitrateKbps(sizeBytes, durationMs)
        val dur = LibraryRules.durationLabel(durationMs)
        return listOfNotNull(
            ext,
            kbps?.let { "${it}k" },
            dur,
        ).joinToString(" · ")
    }
    fun codecDisplayLabel(displayName: String, mimeType: String?): String {
        val container = LibraryRules.audioFormatLabel(displayName)
        val mime = mimeType?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val codecName = when {
            mime == "audio/opus" -> "Opus"
            mime == "audio/vorbis" -> "Vorbis"
            mime == "audio/ogg" -> "Ogg"
            mime == "audio/mp4a-latm" || mime == "audio/aac" -> "AAC"
            mime == "audio/flac" -> "FLAC (Lossless)"
            mime == "audio/mpeg" -> "MP3"
            mime == "audio/raw" -> "PCM (Uncompressed)"
            mime.isNotEmpty() -> mime.substringAfter("audio/").uppercase(Locale.ROOT)
            else -> container
        }
        return if (mime.isNotEmpty() && !codecName.contains(container, ignoreCase = true)) {
            "$codecName ($container)"
        } else {
            codecName
        }
    }
    fun sampleRateLabel(sampleRateHz: Int?): String {
        if (sampleRateHz == null || sampleRateHz <= 0) return "—"
        val formattedHz = java.text.NumberFormat.getIntegerInstance(Locale.US).format(sampleRateHz)
        val khz = if (sampleRateHz % 1000 == 0) {
            "${sampleRateHz / 1000} kHz"
        } else {
            String.format(Locale.US, "%.1f kHz", sampleRateHz / 1000.0)
        }
        return "$khz ($formattedHz Hz)"
    }
    fun channelLayoutLabel(channelCount: Int?): String = when {
        channelCount == null || channelCount <= 0 -> "—"
        channelCount == 1 -> "Mono (1 ch)"
        channelCount == 2 -> "Stereo (2 ch)"
        else -> "$channelCount channel"
    }
    fun bitrateDetailLabel(
        sizeBytes: Long,
        durationMs: Long?,
        extractedBitrateBps: Long?,
    ): String {
        val streamKbps = extractedBitrateBps?.takeIf { it > 0L }?.let { ((it + 500L) / 1_000L).toInt() }
        val effectiveKbps = effectiveBitrateKbps(sizeBytes, durationMs)
        return when {
            streamKbps != null && effectiveKbps != null && kotlin.math.abs(streamKbps - effectiveKbps) >= 8 ->
                "$streamKbps kbps (stream) · ~$effectiveKbps kbps (rata-rata)"
            streamKbps != null -> "$streamKbps kbps"
            effectiveKbps != null -> "~$effectiveKbps kbps (rata-rata file)"
            else -> "—"
        }
    }
    fun exactFileSizeLabel(sizeBytes: Long): String {
        if (sizeBytes <= 0L) return "—"
        val human = fileSizeLabel(sizeBytes)
        val formattedBytes = java.text.NumberFormat.getIntegerInstance(Locale.US).format(sizeBytes)
        return "$human ($formattedBytes byte)"
    }
    fun addedDateTimeLabel(addedAtMs: Long): String {
        if (addedAtMs <= 0L) return "—"
        val fmt = java.text.SimpleDateFormat("d MMM yyyy · HH:mm", Locale.forLanguageTag("id-ID"))
        return fmt.format(java.util.Date(addedAtMs))
    }
    fun groupSummary(count: Int, totalDurationMs: Long): String =
        if (totalDurationMs > 0L) "$count lagu · ${LibraryRules.durationLabel(totalDurationMs)}"
        else "$count lagu"
    fun playlistStatsSummary(trackCount: Int, totalDurationMs: Long): String =
        if (totalDurationMs > 0L) "$trackCount tracks · Total ${LibraryRules.durationLabel(totalDurationMs)}"
        else "$trackCount tracks"
    fun playlistPreviewSummary(
        trackIds: List<Long>,
        resolveDisplayName: (Long) -> String?,
        maxItems: Int = 3,
    ): String {
        val resolvedTitles = trackIds.mapNotNull { id -> resolveDisplayName(id)?.let(::title) }
        if (resolvedTitles.isEmpty()) return "Playlist kosong · tap + untuk tambah track"
        val limit = maxItems.coerceAtLeast(1)
        val shown = resolvedTitles.take(limit).mapIndexed { index, label ->
            String.format(Locale.ROOT, "%02d %s", index + 1, label)
        }
        val extra = resolvedTitles.size - shown.size
        return if (extra > 0) "${shown.joinToString(" · ")} · +$extra" else shown.joinToString(" · ")
    }
    fun playlistMembershipBadge(names: List<String>): String? {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return null
        return if (clean.size == 1) clean.first() else "${clean.first()} +${clean.size - 1}"
    }
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
    fun parseSongViewType(raw: String?): LibrarySongViewType =
        LibrarySongViewType.entries.firstOrNull { it.name == raw } ?: LibrarySongViewType.LIST

    fun nextSongViewType(current: LibrarySongViewType): LibrarySongViewType {
        val entries = LibrarySongViewType.entries
        return entries[(current.ordinal + 1) % entries.size]
    }

    fun songGridColumnCount(screenWidthDp: Int): Int = when {
        screenWidthDp >= 840 -> 4
        screenWidthDp >= 580 -> 3
        else -> 2
    }

    fun artworkIndex(id: Long): Int = Math.floorMod(id, 5L).toInt()

    /** Maps low-level search/network exception messages into plain, non-technical Indonesian guidance. */
    fun friendlySearchError(raw: String?): String {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return "Pencarian belum berhasil. Periksa internet lalu coba lagi."
        val lower = text.lowercase(Locale.ROOT)
        return when {
            "timed out" in lower || "timeout" in lower || "sockettimeout" in lower ->
                "Koneksi terlalu lambat. Periksa Wi-Fi atau paket data lalu tekan Coba lagi."
            "unknownhost" in lower || "unable to resolve host" in lower || "connect" in lower ||
                "network" in lower || "no address" in lower || "eof" in lower ->
                "Tidak tersambung ke internet. Periksa koneksi lalu tekan Coba lagi."
            "429" in lower || "too many requests" in lower ->
                "Server sedang padat. Tunggu beberapa detik lalu coba lagi."
            "ssl" in lower || "cert" in lower ->
                "Koneksi aman belum berhasil dibuat. Periksa tanggal HP dan jaringan Anda."
            else -> text
        }
    }

    fun summarizeDownloadStatuses(statuses: Iterable<String>): DownloadQueueSummary {
        var running = 0
        var queued = 0
        var completed = 0
        var failed = 0
        var canceled = 0
        var total = 0
        for (status in statuses) {
            total++
            when (status) {
                "RUNNING" -> running++
                "QUEUED" -> queued++
                "COMPLETED" -> completed++
                "FAILED" -> failed++
                "CANCELED" -> canceled++
            }
        }
        return DownloadQueueSummary(total, running, queued, completed, failed, canceled)
    }

    fun <T> filterDownloads(
        items: List<T>,
        filter: DownloadFilterTab,
        statusSelector: (T) -> String,
    ): List<T> = when (filter) {
        DownloadFilterTab.ALL -> items
        DownloadFilterTab.ACTIVE -> items.filter {
            val status = statusSelector(it)
            status == "RUNNING" || status == "QUEUED"
        }
        DownloadFilterTab.COMPLETED -> items.filter { statusSelector(it) == "COMPLETED" }
        DownloadFilterTab.FAILED -> items.filter { statusSelector(it) == "FAILED" }
    }

    fun <T> filterQueueTab(
        items: List<T>,
        tab: DownloadQueueTab,
        statusSelector: (T) -> String,
    ): List<T> = when (tab) {
        DownloadQueueTab.ACTIVE -> items.filter {
            val status = statusSelector(it)
            status == "RUNNING" || status == "QUEUED"
        }
        DownloadQueueTab.FAILED -> items.filter { statusSelector(it) == "FAILED" }
        DownloadQueueTab.COMPLETED -> items.filter {
            val status = statusSelector(it)
            status == "COMPLETED" || status == "CANCELED"
        }
    }

    /** Records a valid non-URL query to the front of recent history without case-insensitive duplicates. */
    fun recordRecentQuery(existing: List<String>, rawQuery: String, limit: Int = 5): List<String> {
        val clean = rawQuery.trim()
        if (clean.isEmpty() || clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
            return existing
        }
        val cap = limit.coerceAtLeast(1)
        return buildList {
            add(clean)
            for (item in existing) {
                val trimmed = item.trim()
                if (trimmed.isNotEmpty() && !trimmed.equals(clean, ignoreCase = true)) add(trimmed)
                if (size >= cap) break
            }
        }
    }

    /** Formats a batch section header cleanly without depending on DB entities. */
    fun formatBatchGroupLabel(createdAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (createdAtMs <= 0L) return "Riwayat Unduhan"
        val tz = java.util.TimeZone.getDefault()
        val calNow = java.util.Calendar.getInstance(tz).apply { timeInMillis = nowMs }
        val calItem = java.util.Calendar.getInstance(tz).apply { timeInMillis = createdAtMs }
        val timeFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.forLanguageTag("id-ID")).apply {
            timeZone = tz
        }
        val timePart = timeFmt.format(java.util.Date(createdAtMs))
        val dayDiff = calNow.get(java.util.Calendar.DAY_OF_YEAR) - calItem.get(java.util.Calendar.DAY_OF_YEAR)
        val sameYear = calNow.get(java.util.Calendar.YEAR) == calItem.get(java.util.Calendar.YEAR)
        return when {
            sameYear && dayDiff == 0 -> "Batch Hari Ini · $timePart"
            sameYear && dayDiff == 1 -> "Batch Kemarin · $timePart"
            else -> {
                val dateFmt = java.text.SimpleDateFormat("d MMM", java.util.Locale.forLanguageTag("id-ID")).apply {
                    timeZone = tz
                }
                "Batch ${dateFmt.format(java.util.Date(createdAtMs))} · $timePart"
            }
        }
    }
}
