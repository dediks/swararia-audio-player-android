package com.swararia.audiolab

import java.net.URI

data class AudioStreamQuality(val bitrateKbps: Int, val audioCodec: String? = null)

data class PreviewMedia(
    val videoId: String,
    val title: String,
    val url: String,
    val headers: Map<String, String>,
    val bitrateKbps: Int? = null,
    val audioCodec: String? = null,
)
data class PreviewState(
    val loading: Boolean = false,
    val hit: SearchHit? = null,
    val media: PreviewMedia? = null,
    val error: String? = null,
    val canceling: Boolean = false,
    val requestStartedNanos: Long = 0L,
    val phase: String = "",
    val bitrateKbps: Int? = null,
    val audioCodec: String? = null,
)

/** A preview resolves a stream, never downloads/publishes a library file. */
object OnlinePreviewRules {
    const val PREVIEW_MS = 120_000L
    private val safeHeaderNames = listOf("User-Agent", "Referer", "Origin", "Accept-Language")
    private val jsonMapper = com.fasterxml.jackson.databind.ObjectMapper()
    private val allowedOriginHosts = setOf("www.youtube.com", "youtube.com", "music.youtube.com")

    private fun parseKbps(bps: Long): Int? =
        if (bps in 1_000L..10_000_000L) ((bps + 500L) / 1_000L).toInt().coerceAtLeast(1) else null

    internal fun codecLabel(mimeOrExt: String?, codecHint: String?, itag: Int = 0): String? {
        val hint = codecHint?.trim()?.lowercase().orEmpty()
        val raw = mimeOrExt?.trim()?.lowercase().orEmpty()
        return when {
            "opus" in hint || "opus" in raw || itag in setOf(249, 250, 251) -> "OPUS"
            "mp4a" in hint || "aac" in hint || "mp4a" in raw || itag in setOf(139, 140, 141) -> "AAC"
            "vorbis" in hint || "vorbis" in raw -> "VORBIS"
            "flac" in hint || "flac" in raw -> "FLAC"
            "mp3" in hint || "mpeg" in raw || raw == "mp3" -> "MP3"
            hint.isNotBlank() && hint != "none" -> hint.substringBefore('.').uppercase().take(8)
            "audio/webm" in raw || raw == "webm" -> "WEBM"
            "audio/mp4" in raw || raw == "m4a" -> "M4A"
            else -> null
        }
    }

    fun arguments(videoId: String): List<String> {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId)) { "ID audio tidak valid." }
        return listOf("--ignore-config", "--no-cache-dir", "--no-plugin-dirs", "--skip-download", "--no-playlist",
            "--dump-single-json", "--no-check-formats",
            "--extractor-args", "youtube:skip=hls,dash,translated_subs",
            "--socket-timeout", "8", "--retries", "0", "--extractor-retries", "0",
            "-f", "bestaudio[protocol=https]/bestaudio", "https://www.youtube.com/watch?v=$videoId")
    }
    internal fun streamUri(url: String): URI {
        require(url.length <= 40_000 && url.none { it.isISOControl() }) { "Alamat preview tidak valid." }
        val uri = runCatching { URI(url) }.getOrNull()
        require(uri != null && uri.scheme == "https" && uri.userInfo == null && uri.fragment == null &&
            uri.host?.lowercase()?.endsWith(".googlevideo.com") == true && (uri.port == -1 || uri.port == 443) &&
            uri.path == "/videoplayback") { "Sumber preview tidak diizinkan." }
        return uri
    }
    internal fun filterHeaders(entries: Iterable<Pair<String, String>>): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        for ((name, value) in entries) {
            if (name !in safeHeaderNames) continue
            if (value.length > 2048 || value.any { it.isISOControl() }) continue
            if (name == "Referer" || name == "Origin") {
                val source = runCatching { URI(value) }.getOrNull()
                if (source?.scheme != "https" || source.userInfo != null || source.host !in allowedOriginHosts) continue
            }
            headers[name] = value
        }
        return headers
    }
    fun parse(output: String, expectedId: String, title: String): PreviewMedia {
        val root = YoutubeSearchJson.objectNode(output)
        require(root.path("id").asText() == expectedId) { "Audio preview tidak cocok dengan pilihan." }
        val url = root.path("url").takeIf { it.isTextual }?.asText() ?: error("Stream preview belum tersedia.")
        streamUri(url)
        val input = root.path("http_headers")
        val headers = filterHeaders(safeHeaderNames.mapNotNull { name ->
            input.get(name)?.takeIf { it.isTextual }?.asText()?.let { name to it }
        })
        val bitrateKbps = root.path("bitrateKbps").takeIf { it.isIntegralNumber }?.asInt()?.takeIf { it in 1..10_000 }
            ?: root.path("abr").takeIf { it.isNumber && it.asDouble().isFinite() && it.asDouble() > 0.0 && it.asDouble() <= 10_000.0 }
                ?.let { kotlin.math.round(it.asDouble()).toInt().coerceAtLeast(1) }
        val audioCodec = root.path("audioCodec").takeIf { it.isTextual }?.asText()?.trim()?.takeIf { it.isNotEmpty() }?.take(12)
            ?: codecLabel(
                mimeOrExt = root.path("ext").takeIf { it.isTextual }?.asText(),
                codecHint = root.path("acodec").takeIf { it.isTextual }?.asText(),
                itag = root.path("format_id").takeIf { it.isTextual }?.asText()?.toIntOrNull() ?: 0,
            )
        return PreviewMedia(expectedId, title.take(200), url, headers, bitrateKbps, audioCodec)
    }

    private data class RankedAudioFormat(
        val url: String?,
        val quality: AudioStreamQuality?,
    )

    private fun selectAudioFormat(
        output: String,
        expectedId: String,
        requireDirectUrl: Boolean,
        preferFastCap: Boolean,
    ): RankedAudioFormat? {
        val root = YoutubeSearchJson.objectNode(output)
        if (root.path("playabilityStatus").path("status").asText() != "OK") return null
        val details = root.path("videoDetails")
        if (details.path("isLiveContent").asBoolean(false)) return null
        val reportedId = details.path("videoId").takeIf { it.isTextual }?.asText()
        if (!reportedId.isNullOrEmpty() && reportedId != expectedId) return null
        val formats = root.path("streamingData").path("adaptiveFormats")
        if (!formats.isArray) return null
        var best: RankedAudioFormat? = null
        var bestScore = Long.MIN_VALUE
        for (format in formats) {
            if (format.path("drmFamilies").let { it.isArray && it.size() > 0 }) continue
            val hasCipher = format.path("signatureCipher").asText("").isNotBlank() || format.path("cipher").asText("").isNotBlank()
            if (requireDirectUrl && hasCipher) continue
            val mime = format.path("mimeType").asText("")
            if (!mime.startsWith("audio/")) continue
            val candidateUrl = format.path("url").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }
            if (requireDirectUrl && candidateUrl == null) continue
            if (candidateUrl != null && runCatching { streamUri(candidateUrl) }.isFailure) continue
            val itag = format.path("itag").asInt(0)
            val bitrate = format.path("bitrate").asLong(0L).coerceIn(0L, 10_000_000L)
            val avgBitrate = format.path("averageBitrate").asLong(0L).coerceIn(0L, 10_000_000L)
            val qualityBps = if (avgBitrate > 0L) avgBitrate else bitrate
            val kbps = parseKbps(qualityBps)
            if (!requireDirectUrl && kbps == null) continue
            val track = format.path("audioTrack")
            val isDefaultTrack = if (track.isMissingNode || track.path("audioIsDefault").asBoolean(true)) 1L else 0L
            val inFastCap = if (preferFastCap && bitrate in 48_000L..192_000L) 1L else 0L
            val itagRank = when (itag) {
                251 -> 5L
                140 -> 4L
                250 -> 3L
                249 -> 2L
                139 -> 1L
                else -> 0L
            }
            val score = if (preferFastCap) {
                (isDefaultTrack shl 40) or (inFastCap shl 36) or (itagRank shl 32) or bitrate
            } else {
                (isDefaultTrack shl 44) or (qualityBps shl 4) or itagRank
            }
            if (score > bestScore) {
                bestScore = score
                val codecHint = mime.substringAfter("codecs=", "").trim('"', ' ')
                val codec = codecLabel(mime, codecHint, itag)
                val quality = kbps?.let { AudioStreamQuality(it, codec) }
                best = RankedAudioFormat(candidateUrl, quality)
            }
        }
        return best
    }

    /** Parses direct InnerTube player JSON into a bounded preview candidate, or null to fall back. */
    fun parsePlayerResponse(
        output: String,
        expectedId: String,
        title: String,
        requestHeaders: Map<String, String> = emptyMap(),
    ): PreviewMedia? {
        val chosen = selectAudioFormat(
            output = output,
            expectedId = expectedId,
            requireDirectUrl = true,
            preferFastCap = true,
        ) ?: return null
        val url = chosen.url ?: return null
        val headers = filterHeaders(requestHeaders.entries.map { it.key to it.value })
        return PreviewMedia(
            videoId = expectedId,
            title = title.take(200),
            url = url,
            headers = headers,
            bitrateKbps = chosen.quality?.bitrateKbps,
            audioCodec = chosen.quality?.audioCodec,
        )
    }

    /** Extracts peak safe audio bitrate/codec from InnerTube player JSON (works even on ciphered formats). */
    fun parseAudioQuality(output: String, expectedId: String): AudioStreamQuality? =
        selectAudioFormat(
            output = output,
            expectedId = expectedId,
            requireDirectUrl = false,
            preferFastCap = false,
        )?.quality

    internal fun toJson(media: PreviewMedia): String {
        val node = jsonMapper.createObjectNode()
        node.put("id", media.videoId)
        node.put("url", media.url)
        media.bitrateKbps?.let { node.put("bitrateKbps", it) }
        media.audioCodec?.let { node.put("audioCodec", it) }
        val headersNode = jsonMapper.createObjectNode()
        media.headers.forEach { (k, v) -> headersNode.put(k, v) }
        node.set<com.fasterxml.jackson.databind.JsonNode>("http_headers", headersNode)
        return jsonMapper.writeValueAsString(node)
    }
}
