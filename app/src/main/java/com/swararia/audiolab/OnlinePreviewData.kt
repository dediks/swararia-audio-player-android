package com.swararia.audiolab

import java.net.URI

data class PreviewMedia(val videoId: String, val title: String, val url: String, val headers: Map<String, String>)
data class PreviewState(val loading: Boolean = false, val hit: SearchHit? = null,
    val media: PreviewMedia? = null, val error: String? = null,
    val canceling: Boolean = false, val requestStartedNanos: Long = 0L)

/** A preview resolves a stream, never downloads/publishes a library file. */
object OnlinePreviewRules {
    const val PREVIEW_MS = 120_000L
    fun arguments(videoId: String): List<String> {
        require(Regex("[A-Za-z0-9_-]{11}").matches(videoId)) { "ID audio tidak valid." }
        return listOf("--ignore-config", "--no-cache-dir", "--no-plugin-dirs", "--skip-download", "--no-playlist",
            "--dump-single-json", "--socket-timeout", "8", "--retries", "0", "--extractor-retries", "0",
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
    fun parse(output: String, expectedId: String, title: String): PreviewMedia {
        val root = YoutubeSearchJson.objectNode(output)
        require(root.path("id").asText() == expectedId) { "Audio preview tidak cocok dengan pilihan." }
        val url = root.path("url").takeIf { it.isTextual }?.asText() ?: error("Stream preview belum tersedia.")
        require(url.length <= 40_000 && url.none { it.isISOControl() }) { "Alamat preview tidak valid." }
        streamUri(url)
        val headers = linkedMapOf<String, String>()
        val input = root.path("http_headers")
        for (name in listOf("User-Agent", "Referer", "Origin", "Accept-Language")) {
            val value = input.get(name)?.takeIf { it.isTextual }?.asText() ?: continue
            if (value.length > 2048 || value.any { it.isISOControl() }) continue
            if (name in listOf("Referer", "Origin")) {
                val source = runCatching { URI(value) }.getOrNull()
                if (source?.scheme != "https" || source.userInfo != null ||
                    source.host !in setOf("www.youtube.com", "youtube.com", "music.youtube.com")) continue
            }
            headers[name] = value
        }
        return PreviewMedia(expectedId, title.take(200), url, headers)
    }
}
