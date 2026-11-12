package com.swararia.audiolab

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints

enum class SearchProvider(val label: String) {
    YOUTUBE("YouTube"), YOUTUBE_MUSIC("YouTube Music")
}

enum class SearchKind(val label: String) { SONGS("Lagu"), PLAYLISTS("Playlist") }
enum class SearchSort(val label: String) {
    RELEVANCE("Relevansi"), TITLE("Judul A–Z"), DURATION_ASC("Durasi terpendek"), DURATION_DESC("Durasi terpanjang")
}

data class SearchHit(
    val videoId: String,
    val title: String,
    val channel: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val url: String,
    val kind: SearchKind = SearchKind.SONGS,
    val channelIsVerified: Boolean = false,
    val artist: String? = null,
    val itemCount: Int? = null
)

data class YoutubeSearchState(
    val busy: Boolean = false,
    val query: String = "",
    val provider: SearchProvider = SearchProvider.YOUTUBE_MUSIC,
    val results: List<SearchHit> = emptyList(),
    val error: String? = null,
    val hasSearched: Boolean = false,
    val kind: SearchKind = SearchKind.SONGS,
    val sort: SearchSort = SearchSort.RELEVANCE,
    val loadedThrough: Int = 0,
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val incompleteCount: Int = 0,
    val playlistId: String? = null,
    val playlistTitle: String? = null
)

/** Tickets invalidate older/cancelled calls before any result is published. */
class SearchGeneration {
    private val sequence = java.util.concurrent.atomic.AtomicLong(0)
    fun next(): Long = sequence.incrementAndGet()
    fun isCurrent(ticket: Long): Boolean = sequence.get() == ticket
}

/** Only public, metadata-only requests are constructed here. No shell or account needed. */
object YoutubeSearchRules {
    const val RESULT_LIMIT = 10
    const val MAX_RESULTS = 50
    private val playlistPattern = Regex("(?:PL|RD|OLAK5uy_|UU|FL)[A-Za-z0-9_-]{8,126}")
    fun validatedPlaylistId(value: String): String {
        require(value.length in 12..128 && playlistPattern.matches(value)) { "ID playlist tidak valid." }
        return value
    }
    fun originScore(hit: SearchHit): Int = when {
        hit.channelIsVerified -> 2
        hit.channel?.endsWith(" - Topic", ignoreCase = true) == true -> 1
        else -> 0
    }
    fun originLabel(hit: SearchHit): String? = when (originScore(hit)) {
        2 -> "Kanal terverifikasi"
        1 -> "Indikasi kanal artis"
        else -> null
    }
    fun complete(hit: SearchHit): Boolean = !hit.channel.isNullOrBlank() &&
        (hit.kind == SearchKind.PLAYLISTS || hit.durationSeconds?.let { it > 0 } == true)
    fun merge(old: List<SearchHit>, added: List<SearchHit>): List<SearchHit> =
        (old + added).distinctBy { it.kind.name + ":" + it.videoId }.take(MAX_RESULTS)
    fun sorted(hits: List<SearchHit>, sort: SearchSort): List<SearchHit> {
        val priority = compareByDescending<SearchHit> { originScore(it) }
        return when (sort) {
            SearchSort.RELEVANCE -> hits.sortedWith(priority)
            SearchSort.TITLE -> hits.sortedWith(compareBy<SearchHit> { it.title.lowercase(java.util.Locale.ROOT) }.then(priority))
            SearchSort.DURATION_ASC -> hits.sortedWith(compareBy<SearchHit> { it.durationSeconds == null }
                .thenBy { it.durationSeconds ?: Long.MAX_VALUE }.then(priority))
            SearchSort.DURATION_DESC -> hits.sortedWith(compareBy<SearchHit> { it.durationSeconds == null }
                .thenByDescending { it.durationSeconds ?: Long.MIN_VALUE }.then(priority))
        }
    }
    fun validatedQuery(input: String): String {
        require(input.none { it.isISOControl() }) { "Kata pencarian mengandung karakter yang tidak didukung." }
        val query = input.trim()
        require(query.isNotEmpty() && query.length <= 200) { "Isi kata pencarian, maksimal 200 karakter." }
        return query
    }
    @JvmOverloads fun input(query: String, provider: SearchProvider, kind: SearchKind = SearchKind.SONGS, count: Int = RESULT_LIMIT): String {
        val valid = validatedQuery(query)
        require(count in 1..MAX_RESULTS)
        val encoded = java.net.URLEncoder.encode(valid, "UTF-8")
        return when (provider) {
            SearchProvider.YOUTUBE -> if (kind == SearchKind.SONGS) "ytsearch$count:$valid"
                else "https://www.youtube.com/results?search_query=$encoded&sp=EgIQAw%3D%3D"
            SearchProvider.YOUTUBE_MUSIC -> "https://music.youtube.com/search?q=$encoded#${if (kind == SearchKind.SONGS) "songs" else "community+playlists"}"
        }
    }
    @JvmOverloads fun arguments(query: String, provider: SearchProvider, kind: SearchKind = SearchKind.SONGS,
        offset: Int = 0, playlistId: String? = null): List<String> {
        require(offset in 0 until MAX_RESULTS && offset % RESULT_LIMIT == 0) { "Halaman pencarian tidak valid." }
        val end = minOf(offset + RESULT_LIMIT, MAX_RESULTS)
        val target = if (playlistId == null) input(query, provider, kind, end)
            else "https://www.youtube.com/playlist?list=${validatedPlaylistId(playlistId)}"
        return listOf("--ignore-config", "--no-cache-dir", "--flat-playlist", "--dump-single-json", "--skip-download",
            "--playlist-start", (offset + 1).toString(), "--playlist-end", end.toString(),
            "--socket-timeout", "8", "--retries", "0", "--extractor-retries", "0", target)
    }
}

/** Parses metadata only; result links are rebuilt from IDs, never trusted from JSON. */
object YoutubeSearchJson {
    private val mapper = ObjectMapper(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(100_000).build())
        .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private fun displayText(value: String, maxLength: Int): String {
        val clean = value.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()
        val end = clean.offsetByCodePoints(0, minOf(maxLength, clean.codePointCount(0, clean.length)))
        return clean.substring(0, end)
    }
    private val videoIdPattern = Regex("[A-Za-z0-9_-]{11}")
    fun objectNode(output: String): com.fasterxml.jackson.databind.JsonNode {
        require(output.isNotBlank() && output.length <= 2_000_000) { "Ukuran respons pencarian tidak valid." }
        val root = try { mapper.readTree(output) }
        catch (failure: com.fasterxml.jackson.core.JsonProcessingException) {
            throw IllegalArgumentException("Data hasil pencarian tidak valid.", failure)
        }
        require(root != null && root.isObject) { "Bentuk hasil pencarian tidak dikenali." }
        return root
    }
    fun entryCount(output: String): Int = objectNode(output).path("entries").takeIf { it.isArray }?.size()
        ?: throw IllegalArgumentException("Daftar hasil tidak valid.")
    fun oembedChannel(output: String): String? = objectNode(output).get("author_name")?.takeIf { it.isTextual }
        ?.asText()?.let { displayText(it, 160) }?.takeIf { it.isNotBlank() }
    fun metadata(output: String, expectedId: String): SearchHit? {
        val root = objectNode(output)
        require(root.path("id").asText() == expectedId) { "Metadata lagu tidak cocok." }
        return parse(mapper.createObjectNode().set<com.fasterxml.jackson.databind.JsonNode>("entries", mapper.createArrayNode().add(root)).toString()).singleOrNull()
    }
    @JvmOverloads fun parse(output: String, kind: SearchKind = SearchKind.SONGS, limit: Int = YoutubeSearchRules.RESULT_LIMIT): List<SearchHit> {
        require(limit in 1..YoutubeSearchRules.MAX_RESULTS)
        val root = objectNode(output)
        require(root.path("entries").isArray) { "Bentuk hasil pencarian tidak dikenali." }
        val entries = root.get("entries")
        val hits = linkedMapOf<String, SearchHit>()
        for (item in entries) {
            val id = item.path("id").takeIf { it.isTextual }?.asText() ?: continue
            val validId = if (kind == SearchKind.SONGS) videoIdPattern.matches(id)
                else runCatching { YoutubeSearchRules.validatedPlaylistId(id) }.isSuccess
            if (!validId || id in hits) continue
            if (kind == SearchKind.SONGS && item.path("_type").asText() in setOf("playlist", "multi_video")) continue
            if (item.path("is_live").asBoolean() || item.path("live_status").asText() in setOf("is_live", "is_upcoming")) continue
            if (item.path("availability").asText() in setOf("private", "premium_only", "subscriber_only", "needs_auth")) continue
            val title = item.path("title").takeIf { it.isTextual }?.asText()?.let { displayText(it, 200) }?.takeIf { it.isNotBlank() } ?: continue
            val channel = sequenceOf("channel", "uploader").mapNotNull { key ->
                item.get(key)?.takeIf { it.isTextual }?.asText()?.let { displayText(it, 160) }?.takeIf { it.isNotBlank() }
            }.firstOrNull()
            val thumbnail = item.get("thumbnail")?.takeIf { it.isTextual }?.asText()?.takeIf(ThumbnailPolicy::allowed)
                ?: item.path("thumbnails").takeIf { it.isArray }?.asSequence()?.take(32)?.mapNotNull {
                    it.path("url").takeIf { url -> url.isTextual }?.asText()?.takeIf(ThumbnailPolicy::allowed)
                }?.firstOrNull()
            hits[id] = SearchHit(
                videoId = id, title = title, channel = channel,
                durationSeconds = item.get("duration")?.takeIf { it.isNumber && it.asDouble().isFinite() && it.asDouble() >= 0 && it.asDouble() <= 366.0 * 24 * 60 * 60 }?.asLong(),
                thumbnailUrl = thumbnail,
                url = if (kind == SearchKind.SONGS) "https://www.youtube.com/watch?v=$id" else "https://www.youtube.com/playlist?list=$id",
                kind = kind,
                channelIsVerified = item.path("channel_is_verified").asBoolean(false),
                artist = item.get("artist")?.takeIf { it.isTextual }?.asText()?.let { displayText(it, 160) },
                itemCount = sequenceOf("playlist_count", "n_entries").mapNotNull { key ->
                    item.get(key)?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.asInt()?.takeIf { it in 0..100_000 }
                }.firstOrNull()
            )
            if (hits.size == limit) break
        }
        return hits.values.toList()
    }
}
