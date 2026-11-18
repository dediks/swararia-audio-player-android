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
    private val youtubeHosts = setOf("youtu.be", "youtube.com", "www.youtube.com", "music.youtube.com", "m.youtube.com")

    private fun parsePublicYoutubeUri(
        value: String,
        allowedQueryKeys: Set<String>,
        invalidHostMessage: String,
    ): Pair<java.net.URI, List<List<String>>> {
        require(value.length <= 8192 && value.none { it.isISOControl() }) { "URL tidak valid." }
        val uri = runCatching { java.net.URI(value.trim()) }.getOrElse { throw IllegalArgumentException("URL tidak valid.") }
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null) { invalidHostMessage }
        require(uri.host in youtubeHosts) { invalidHostMessage }
        val pairs = uri.rawQuery?.split('&')?.map { it.split('=', limit = 2) }.orEmpty()
        // Strict allowlist rejects signed/auth-bearing and unknown parameters, even if the ID is valid.
        require(
            pairs.all { it.size == 2 && it[0] in allowedQueryKeys && it[1].matches(Regex("[A-Za-z0-9_.-]{1,128}")) } &&
                pairs.map { it[0] }.distinct().size == pairs.size
        ) { "Parameter tautan tidak didukung." }
        return uri to pairs
    }

    /** Durable requests contain public page identifiers only, never extractor/stream credentials. */
    fun validatedPublicSourceUrl(value: String): String {
        val (uri, pairs) = parsePublicYoutubeUri(
            value = value,
            allowedQueryKeys = setOf("v", "si", "feature", "t", "start"),
            invalidHostMessage = "Gunakan tautan video YouTube publik.",
        )
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

    /** Extracts a validated public YouTube / YT Music playlist ID from `/playlist?list=...` or `/watch?v=...&list=...`. */
    fun validatedPublicPlaylistId(value: String): String {
        val (uri, pairs) = parsePublicYoutubeUri(
            value = value,
            allowedQueryKeys = setOf("list", "v", "index", "si", "feature", "pp", "t", "start"),
            invalidHostMessage = "Gunakan tautan playlist YouTube publik.",
        )
        require(uri.host != "youtu.be") { "Gunakan tautan playlist YouTube publik." }
        when (uri.rawPath) {
            "/playlist" -> require(pairs.none { it[0] == "v" }) { "Parameter tautan tidak didukung." }
            "/watch" -> {
                val vid = pairs.singleOrNull { it[0] == "v" }?.get(1).orEmpty()
                require(vid.matches(Regex("[A-Za-z0-9_-]{11}"))) { "ID video tidak valid." }
            }
            else -> throw IllegalArgumentException("Gunakan tautan playlist YouTube publik.")
        }
        val listId = pairs.singleOrNull { it[0] == "list" }?.get(1).orEmpty()
        return YoutubeSearchRules.validatedPlaylistId(listId)
    }

    fun validatedPublicPlaylistUrl(value: String): String =
        "https://www.youtube.com/playlist?list=${validatedPublicPlaylistId(value)}"
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
