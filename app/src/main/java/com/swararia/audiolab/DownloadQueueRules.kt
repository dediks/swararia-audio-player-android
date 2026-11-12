package com.swararia.audiolab

/** Pure queue validation; never includes private source URLs in errors. */
object DownloadQueueRules {
    fun validate(urls: List<String>, authorized: Boolean): List<String> {
        require(authorized) { "Konfirmasi hak unduh terlebih dahulu." }
        require(urls.size in 1..50) { "Pilih 1–50 audio per batch." }
        return urls.map(::validatedPublicSourceUrl).distinct()
    }
    /** Durable requests contain public page identifiers only, never extractor/stream credentials. */
    fun validatedPublicSourceUrl(value: String): String {
        require(value.length <= 8192 && value.none { it.isISOControl() }) { "URL tidak valid." }
        val uri = runCatching { java.net.URI(value.trim()) }.getOrElse { throw IllegalArgumentException("URL tidak valid.") }
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null) { "Gunakan tautan video YouTube publik." }
        require(uri.host in setOf("youtu.be", "youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com")) { "Gunakan tautan video YouTube publik." }
        val pairs = uri.rawQuery?.split('&')?.map { it.split('=', limit = 2) }.orEmpty()
        // Strict allowlist rejects signed/auth-bearing and unknown parameters, even if a video ID is valid.
        require(pairs.all { it.size == 2 && it[0] in setOf("v", "si", "feature", "t", "start") && it[1].matches(Regex("[A-Za-z0-9_.-]{1,128}")) } && pairs.map { it[0] }.distinct().size == pairs.size) { "Parameter tautan tidak didukung." }
        val id = if (uri.host == "youtu.be") {
            require(pairs.none { it[0] == "v" }); uri.rawPath.removePrefix("/")
        } else if (uri.rawPath == "/watch") pairs.singleOrNull { it[0] == "v" }?.get(1).orEmpty()
        else {
            require(pairs.none { it[0] == "v" })
            Regex("/(?:shorts|live|embed)/([A-Za-z0-9_-]{11})").matchEntire(uri.rawPath)?.groupValues?.get(1).orEmpty()
        }
        require(id.matches(Regex("[A-Za-z0-9_-]{11}"))) { "ID video tidak valid." }
        return "https://www.youtube.com/watch?v=$id"
    }
    fun durableThumbnail(value: String?): String? = value?.takeIf {
        ThumbnailPolicy.allowed(it) && runCatching { val uri = java.net.URI(it); uri.rawQuery == null && uri.rawFragment == null }.getOrDefault(false)
    }
    fun validProgress(value: Float?): Float? {
        require(value == null || (value.isFinite() && value in 0f..1f)) { "Progres tidak valid." }
        return value
    }
    fun batchCancelable(status: String) = status == "QUEUED"
    fun canTransition(from: String, to: String): Boolean = when (from) {
        "QUEUED" -> to in setOf("RUNNING", "CANCELED")
        "RUNNING" -> to in setOf("COMPLETED", "FAILED", "CANCELED")
        "FAILED" -> to == "QUEUED"
        else -> false
    }
}
