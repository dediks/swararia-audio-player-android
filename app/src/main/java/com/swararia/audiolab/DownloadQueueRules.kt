package com.swararia.audiolab

/** Pure queue validation; never includes private source URLs in errors. */
object DownloadQueueRules {
    const val REMOTE_COMPONENTS_EJS = "ejs:github"
    const val AUTH_YOUTUBE_EXTRACTOR_ARGS =
        "youtube:player_client=web_music,web_creator,tv_samsung,web;formats=missing_pot"
    const val FALLBACK_YOUTUBE_EXTRACTOR_ARGS =
        "youtube:player_client=tv_embedded,web_creator,mweb,android_vr,web;formats=missing_pot"
    const val AUTO_HEAL_COOLDOWN_MS = 6L * 60L * 60L * 1000L

    private val permanentFailureMarkers = listOf(
        "private video",
        "video unavailable",
        "has been removed",
        "copyright",
        "not available in your country",
        "premieres in",
        "live event will begin",
    )

    private val recoverableEngineMarkers = listOf(
        "http error 403",
        "403: forbidden",
        "signature",
        "nsig",
        "n challenge",
        "js challenge",
        "sabr",
        "po token",
        "missing_pot",
        "requested format is not available",
        "only images are available",
        "unable to extract",
        "player api json",
        "initial data",
        "sign in to confirm",
        "reload the page",
    )

    fun shouldAttemptEngineRecovery(errorText: String?): Boolean {
        val normalized = errorText?.trim()?.takeIf { it.isNotEmpty() }?.lowercase() ?: return false
        if (permanentFailureMarkers.any { it in normalized }) return false
        return recoverableEngineMarkers.any { it in normalized }
    }

    fun canAutoUpdateEngine(lastAttemptElapsedMs: Long, nowElapsedMs: Long): Boolean =
        lastAttemptElapsedMs <= 0L || (nowElapsedMs - lastAttemptElapsedMs) >= AUTO_HEAL_COOLDOWN_MS

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
