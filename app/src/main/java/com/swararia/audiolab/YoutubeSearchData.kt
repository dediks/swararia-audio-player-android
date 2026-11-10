package com.swararia.audiolab

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints

enum class SearchProvider(val label: String) {
    YOUTUBE("YouTube"), YOUTUBE_MUSIC("YouTube Music")
}

data class SearchHit(
    val videoId: String,
    val title: String,
    val channel: String?,
    val durationSeconds: Long?,
    val thumbnailUrl: String?,
    val url: String
)

data class YoutubeSearchState(
    val busy: Boolean = false,
    val query: String = "",
    val provider: SearchProvider = SearchProvider.YOUTUBE,
    val results: List<SearchHit> = emptyList(),
    val error: String? = null,
    val hasSearched: Boolean = false
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
    fun validatedQuery(input: String): String {
        require(input.none { it.isISOControl() }) { "Kata pencarian mengandung karakter yang tidak didukung." }
        val query = input.trim()
        require(query.isNotEmpty() && query.length <= 200) { "Isi kata pencarian, maksimal 200 karakter." }
        return query
    }
    fun input(query: String, provider: SearchProvider): String {
        val valid = validatedQuery(query)
        return when (provider) {
            SearchProvider.YOUTUBE -> "ytsearch$RESULT_LIMIT:$valid"
            SearchProvider.YOUTUBE_MUSIC -> "https://music.youtube.com/search?q=${java.net.URLEncoder.encode(valid, "UTF-8")}#songs"
        }
    }
    fun arguments(query: String, provider: SearchProvider): List<String> = listOf(
        "--ignore-config", "--no-cache-dir", "--flat-playlist", "--dump-single-json", "--skip-download",
        "--playlist-end", RESULT_LIMIT.toString(), "--socket-timeout", "8", "--retries", "0", "--extractor-retries", "0",
        input(query, provider)
    )
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
    fun parse(output: String): List<SearchHit> {
        require(output.isNotBlank() && output.length <= 2_000_000) { "Ukuran respons pencarian tidak valid." }
        val root = try { mapper.readTree(output) }
        catch (failure: com.fasterxml.jackson.core.JsonProcessingException) {
            throw IllegalArgumentException("Data hasil pencarian tidak valid.", failure)
        }
        require(root != null && root.isObject && root.path("entries").isArray) { "Bentuk hasil pencarian tidak dikenali." }
        val entries = root.get("entries")
        val hits = linkedMapOf<String, SearchHit>()
        for (item in entries) {
            val id = item.path("id").takeIf { it.isTextual }?.asText() ?: continue
            if (!videoIdPattern.matches(id) || id in hits) continue
            if (item.path("_type").asText() in setOf("playlist", "multi_video")) continue
            if (item.path("is_live").asBoolean() || item.path("live_status").asText() in setOf("is_live", "is_upcoming")) continue
            if (item.path("availability").asText() in setOf("private", "premium_only", "subscriber_only", "needs_auth")) continue
            val title = item.path("title").takeIf { it.isTextual }?.asText()?.let { displayText(it, 200) }?.takeIf { it.isNotBlank() } ?: continue
            val channel = sequenceOf("channel", "artist", "uploader").mapNotNull { key ->
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
                url = "https://www.youtube.com/watch?v=$id"
            )
            if (hits.size == YoutubeSearchRules.RESULT_LIMIT) break
        }
        return hits.values.toList()
    }
}
