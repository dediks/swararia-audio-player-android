package com.swararia.audiolab

import android.content.Context
import android.net.ConnectivityManager
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.IOException
import java.net.URLEncoder
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Metadata only. No FFmpeg initialization or library/MediaStore writes. */
class YoutubeSearchRepository(context: Context, private val ingestBusy: () -> Boolean) : AudioSearchRepository {
    private val appContext = context.applicationContext
    private val jsonMapper = com.fasterxml.jackson.databind.ObjectMapper()
    private val warmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val visitorLock = Any()
    private var cachedVisitorNetwork: Any? = null
    private var cachedVisitorData: String? = null
    private var cachedVisitorExpiresAt = 0L
    private val fastSkipUntilMs = object : LinkedHashMap<String, Long>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 32
    }
    private val previewResolver = PreviewResolver(
        extract = { id -> resolvePreviewJson(id) },
        networkKey = ::activeNetworkKey,
        timing = { cached, ms -> android.util.Log.d("SwarariaTiming", "preview_resolve_ms=$ms cache_hit=$cached") },
    )
    private val channelCache = object : LinkedHashMap<String, String>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 128
    }
    private val qualityCache = object : LinkedHashMap<String, AudioStreamQuality>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AudioStreamQuality>?): Boolean = size > 128
    }

    private fun cacheQuality(videoId: String, bitrateKbps: Int?, audioCodec: String?): AudioStreamQuality? {
        val kbps = bitrateKbps?.takeIf { it > 0 } ?: return null
        val quality = AudioStreamQuality(kbps, audioCodec)
        synchronized(qualityCache) { qualityCache[videoId] = quality }
        return quality
    }

    private fun cachedQuality(videoId: String): AudioStreamQuality? =
        synchronized(qualityCache) { qualityCache[videoId] }

    private fun activeNetworkKey(): Any? =
        runCatching { appContext.getSystemService(ConnectivityManager::class.java)?.activeNetwork?.networkHandle }.getOrNull()

    suspend fun search(query: String, provider: SearchProvider): List<SearchHit> =
        searchPage(query, provider, SearchKind.SONGS).hits

    override suspend fun searchPage(query: String, provider: SearchProvider, kind: SearchKind,
        offset: Int, playlistId: String?): SearchPage =
        executeSearchOrPlaylist(query, provider, kind, offset, playlistId, YoutubeSearchRules.RESULT_LIMIT)

    override suspend fun playlistPage(playlistId: String, offset: Int, limit: Int): SearchPage =
        executeSearchOrPlaylist("", SearchProvider.YOUTUBE_MUSIC, SearchKind.SONGS, offset, playlistId, limit)

    private suspend fun executeSearchOrPlaylist(
        query: String,
        provider: SearchProvider,
        kind: SearchKind,
        offset: Int,
        playlistId: String?,
        limit: Int,
    ): SearchPage = withTimeoutOrNull(60_000L) {
        val base = YoutubeSearchRules.arguments(query, provider, kind, offset, playlistId, limit)
        val plugin = withContext(Dispatchers.IO) { pluginDirectory() }
        val args = base.dropLast(1) + listOf("--no-plugin-dirs", "--plugin-dirs", plugin.absolutePath, base.last())
        val output = executeMetadata(args, "search", 30_000L)
        val raw = withContext(Dispatchers.Default) { YoutubeSearchJson.parse(output, kind, limit) }
        val playlistMeta = if (playlistId != null) {
            withContext(Dispatchers.Default) { runCatching { YoutubeSearchJson.playlistMeta(output) }.getOrNull() }
        } else null
        val permits = Semaphore(6)
        val enriched = coroutineScope {
            raw.map { hit -> async {
                if (hit.channel.isNullOrBlank() && hit.kind == SearchKind.SONGS) {
                    val channel = permits.withPermit {
                        runInterruptible(Dispatchers.IO) { channelFor(hit.videoId) }
                    }
                    hit.copy(channel = channel)
                } else hit
            } }.awaitAll()
        }.toMutableList()
        // A fallback for rare source entries lacking a duration; bounded, not an unbounded N+1 scan.
        for (index in enriched.indices.filter { enriched[it].kind == SearchKind.SONGS && enriched[it].durationSeconds == null }.take(2)) {
            currentCoroutineContext().ensureActive()
            val hit = enriched[index]
            try {
                val info = executeMetadata(listOf("--ignore-config", "--no-cache-dir", "--no-plugin-dirs", "--skip-download",
                    "--no-playlist", "--dump-single-json", "--socket-timeout", "6", "--retries", "0", "--extractor-retries", "0", hit.url), "metadata", 12_000L)
                withContext(Dispatchers.Default) { YoutubeSearchJson.metadata(info, hit.videoId) }?.let { full ->
                    enriched[index] = hit.copy(channel = full.channel ?: hit.channel,
                        durationSeconds = full.durationSeconds ?: hit.durationSeconds,
                        channelIsVerified = full.channelIsVerified || hit.channelIsVerified,
                        thumbnailUrl = full.thumbnailUrl ?: hit.thumbnailUrl,
                        artist = full.artist ?: hit.artist,
                        album = full.album ?: hit.album)
                }
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { /* Incomplete items remain explicitly excluded, not fabricated. */ }
        }
        val complete = enriched.filter(YoutubeSearchRules::complete).map { hit ->
            if (hit.kind == SearchKind.SONGS && hit.bitrateKbps == null) {
                cachedQuality(hit.videoId)?.let { q ->
                    hit.copy(bitrateKbps = q.bitrateKbps, audioCodec = q.audioCodec ?: hit.audioCodec)
                } ?: hit
            } else hit
        }
        if (complete.any { it.kind == SearchKind.SONGS }) warmVisitorSessionAsync()
        val entryCount = withContext(Dispatchers.Default) { YoutubeSearchJson.entryCount(output) }
        SearchPage(complete, entryCount, enriched.size - complete.size, playlistMeta)
    } ?: throw IOException("Pencarian terlalu lama. Coba lagi saat koneksi lebih stabil.")

    override suspend fun preview(hit: SearchHit): PreviewMedia {
        require(hit.kind == SearchKind.SONGS) { "Buka playlist lalu pilih satu lagu untuk preview." }
        return previewResolver.resolve(hit).also { resolved ->
            cacheQuality(resolved.videoId, resolved.bitrateKbps, resolved.audioCodec)
        }
    }

    override suspend fun audioQuality(videoId: String): AudioStreamQuality? {
        if (!Regex("[A-Za-z0-9_-]{11}").matches(videoId)) return null
        cachedQuality(videoId)?.let { return it }
        if (ingestBusy() || isFastSkipped(videoId)) return null
        val raw = try {
            withTimeoutOrNull(5_000L) {
                runInterruptible(Dispatchers.IO) {
                    fetchInnerTubePlayerJson(videoId, connectMs = 3000, readMs = 4000)
                }
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            null
        } ?: return null
        val parsed = runCatching { OnlinePreviewRules.parseAudioQuality(raw, videoId) }.getOrNull() ?: return null
        return cacheQuality(videoId, parsed.bitrateKbps, parsed.audioCodec)
    }
    override fun invalidatePreview(videoId: String) {
        previewResolver.invalidate(videoId)
        synchronized(fastSkipUntilMs) {
            fastSkipUntilMs[videoId] = android.os.SystemClock.elapsedRealtime() + FAST_RETRY_SKIP_MS
        }
    }

    private fun isFastSkipped(videoId: String): Boolean = synchronized(fastSkipUntilMs) {
        val until = fastSkipUntilMs[videoId] ?: return@synchronized false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now >= until) {
            fastSkipUntilMs.remove(videoId)
            false
        } else {
            true
        }
    }

    private fun warmVisitorSessionAsync() {
        val network = activeNetworkKey()
        val now = android.os.SystemClock.elapsedRealtime()
        val fresh = synchronized(visitorLock) {
            cachedVisitorNetwork == network && cachedVisitorData != null && now < cachedVisitorExpiresAt
        }
        if (fresh || ingestBusy()) return
        warmupScope.launch {
            runCatching { ensureVisitorData(network) }
        }
    }

    private suspend fun resolvePreviewJson(id: String): String {
        check(!ingestBusy()) { "Tunggu proses audio selesai terlebih dahulu." }
        if (!isFastSkipped(id)) {
            val fast = try {
                withTimeoutOrNull(7_000L) {
                    runInterruptible(Dispatchers.IO) { resolveFastPreviewJson(id) }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            if (fast != null) return fast
            android.util.Log.d("SwarariaTiming", "preview_fast_fallback=1")
        }
        return executeMetadata(OnlinePreviewRules.arguments(id), "preview", 30_000L)
    }

    private fun fetchInnerTubePlayerJson(id: String, connectMs: Int, readMs: Int): String? {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        if (ingestBusy()) return null
        val network = activeNetworkKey()
        val visitor = ensureVisitorData(network) ?: return null
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val payload = jsonMapper.createObjectNode().apply {
            put("videoId", id)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            set<com.fasterxml.jackson.databind.JsonNode>("context", jsonMapper.createObjectNode().apply {
                set<com.fasterxml.jackson.databind.JsonNode>("client", jsonMapper.createObjectNode().apply {
                    put("clientName", "VISIONOS")
                    put("clientVersion", VISIONOS_CLIENT_VERSION)
                    put("deviceMake", "Apple")
                    put("deviceModel", "RealityDevice17,1")
                    put("userAgent", VISIONOS_UA)
                    put("osName", "visionOS")
                    put("osVersion", "26.5.23O471")
                    put("visitorData", visitor)
                })
            })
        }
        val raw = postJson(
            url = INNERTUBE_PLAYER_URL,
            body = jsonMapper.writeValueAsString(payload),
            headers = mapOf(
                "User-Agent" to VISIONOS_UA,
                "X-YouTube-Client-Name" to "101",
                "X-YouTube-Client-Version" to VISIONOS_CLIENT_VERSION,
                "X-Goog-Visitor-Id" to visitor,
                "Origin" to "https://www.youtube.com",
            ),
            connectMs = connectMs,
            readMs = readMs,
        ) ?: run {
            clearVisitorData(network)
            return null
        }
        val status = runCatching { YoutubeSearchJson.objectNode(raw).path("playabilityStatus").path("status").asText("") }.getOrDefault("")
        if (status == "LOGIN_REQUIRED") {
            clearVisitorData(network)
            return null
        }
        return raw
    }

    private fun resolveFastPreviewJson(id: String): String? {
        val started = System.nanoTime()
        check(!ingestBusy()) { "Tunggu proses audio selesai terlebih dahulu." }
        val raw = fetchInnerTubePlayerJson(id, connectMs = 4000, readMs = 5000) ?: return null
        val media = OnlinePreviewRules.parsePlayerResponse(
            output = raw,
            expectedId = id,
            title = id,
            requestHeaders = mapOf("User-Agent" to VISIONOS_UA),
        ) ?: return null
        cacheQuality(id, media.bitrateKbps, media.audioCodec)
        val elapsedMs = ((System.nanoTime() - started) / 1_000_000L).coerceAtLeast(0L)
        android.util.Log.d("SwarariaTiming", "preview_fast_ms=$elapsedMs")
        return OnlinePreviewRules.toJson(media)
    }

    private fun clearVisitorData(network: Any?) {
        synchronized(visitorLock) {
            if (cachedVisitorNetwork == network) {
                cachedVisitorData = null
                cachedVisitorExpiresAt = 0L
            }
        }
    }

    private fun ensureVisitorData(network: Any?): String? {
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(visitorLock) {
            if (cachedVisitorNetwork == network && cachedVisitorData != null && now < cachedVisitorExpiresAt) {
                return cachedVisitorData
            }
        }
        val body = """{"context":{"client":{"clientName":"WEB","clientVersion":"2.20260708.00.00"}}}"""
        val raw = postJson(
            url = INNERTUBE_VISITOR_URL,
            body = body,
            headers = mapOf("User-Agent" to "Mozilla/5.0"),
            connectMs = 3500,
            readMs = 4000,
        ) ?: return null
        val vd = runCatching {
            YoutubeSearchJson.objectNode(raw).path("responseContext").path("visitorData").takeIf { it.isTextual }?.asText()
        }.getOrNull()?.takeIf { it.isNotBlank() && it.length <= 1024 } ?: return null
        val freshAt = android.os.SystemClock.elapsedRealtime() + VISITOR_TTL_MS
        synchronized(visitorLock) {
            if (activeNetworkKey() == network) {
                cachedVisitorNetwork = network
                cachedVisitorData = vd
                cachedVisitorExpiresAt = freshAt
            }
        }
        return vd
    }

    private fun postJson(
        url: String,
        body: String,
        headers: Map<String, String>,
        connectMs: Int,
        readMs: Int,
    ): String? {
        var connection: HttpsURLConnection? = null
        return try {
            val request = URL(url).openConnection() as HttpsURLConnection
            connection = request
            request.requestMethod = "POST"
            request.instanceFollowRedirects = false
            request.connectTimeout = connectMs
            request.readTimeout = readMs
            request.doOutput = true
            request.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> request.setRequestProperty(k, v) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            request.setFixedLengthStreamingMode(bytes.size)
            request.outputStream.use { it.write(bytes) }
            if (request.responseCode != 200 || request.contentLengthLong > MAX_INNERTUBE_BYTES.toLong()) null
            else request.inputStream.use { ThumbnailTransfer.read(it, MAX_INNERTUBE_BYTES) }.toString(Charsets.UTF_8)
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        const val VISITOR_TTL_MS = 15 * 60 * 1000L
        const val FAST_RETRY_SKIP_MS = 120 * 1000L
        const val MAX_INNERTUBE_BYTES = 1_048_576
        const val VISIONOS_CLIENT_VERSION = "1.02"
        const val VISIONOS_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"
        const val INNERTUBE_VISITOR_URL = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false"
        const val INNERTUBE_PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
    }

    private suspend fun executeMetadata(args: List<String>, prefix: String, timeout: Long): String {
        check(!ingestBusy()) { "Tunggu proses pembaruan mesin selesai terlebih dahulu." }
        val processId = "$prefix-${UUID.randomUUID()}"
        var completionHandle: DisposableHandle? = null
        try {
            return withTimeoutOrNull(timeout) {
                completionHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
                    if (cause != null) runCatching { YoutubeDL.destroyProcessById(processId) }
                }
                runInterruptible(Dispatchers.IO) {
                    val initStarted = System.nanoTime()
                    YoutubeDL.init(appContext)
                    android.util.Log.d("SwarariaTiming", "metadata_engine_init_ms=${((System.nanoTime() - initStarted) / 1_000_000L).coerceAtLeast(0)}")
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    check(!ingestBusy()) { "Tunggu proses pembaruan mesin selesai terlebih dahulu." }
                    val request = YoutubeDLRequest(args.last()).addCommands(args.dropLast(1))
                    val executeStarted = System.nanoTime()
                    try { YoutubeDL.execute(request, processId).out }
                    finally { android.util.Log.d("SwarariaTiming", "metadata_execute_ms=${((System.nanoTime() - executeStarted) / 1_000_000L).coerceAtLeast(0)}") }
                }
            } ?: throw IOException("Sumber merespons terlalu lama. Coba lagi nanti.")
        } catch (cancel: CancellationException) { throw cancel }
          catch (cancel: YoutubeDL.CanceledException) { throw CancellationException("Proses dibatalkan.").apply { initCause(cancel) } }
          catch (interrupted: InterruptedException) { throw CancellationException("Proses dibatalkan.").apply { initCause(interrupted) } }
          catch (failure: com.yausername.youtubedl_android.YoutubeDLException) {
              throw IOException(if (prefix == "preview") "Preview belum tersedia dari sumber ini. Sumber dapat membatasi akses; coba lagu lain atau coba lagi nanti."
                  else "Sumber belum dapat diakses. Periksa koneksi lalu coba lagi.", failure)
          } catch (error: Error) {
              throw IOException("Mesin pencarian tidak kompatibel atau gagal dijalankan di perangkat ini.", error)
          } finally {
              completionHandle?.dispose()
              runCatching { YoutubeDL.destroyProcessById(processId) }
          }
    }

    @Synchronized private fun pluginDirectory(): java.io.File {
        val base = java.io.File(appContext.filesDir, "search-adapter-plugins")
        val file = java.io.File(base, "swararia/yt_dlp_plugins/extractor/swararia_music.py")
        val bytes = appContext.assets.open("search_plugins/yt_dlp_plugins/extractor/swararia_music.py").use { it.readBytes() }
        check(bytes.size in 1..100_000)
        if (!file.isFile || !file.readBytes().contentEquals(bytes)) {
            check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
            val staging = java.io.File(file.parentFile, "swararia_music.tmp")
            staging.writeBytes(bytes)
            check(staging.renameTo(file)) { "Adapter pencarian tidak dapat disiapkan." }
        }
        return base
    }

    private fun channelFor(id: String): String? {
        require(Regex("[A-Za-z0-9_-]{11}").matches(id))
        synchronized(channelCache) { channelCache[id]?.let { return it } }
        val query = URLEncoder.encode("https://www.youtube.com/watch?v=$id", "UTF-8")
        var connection: HttpsURLConnection? = null
        return try {
            val request = URL("https://www.youtube.com/oembed?url=$query&format=json").openConnection() as HttpsURLConnection
            connection = request
            request.instanceFollowRedirects = false
            request.connectTimeout = 3000
            request.readTimeout = 4000
            request.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (request.responseCode != 200 || request.contentLengthLong > 100_000L) null
            else {
                val output = request.inputStream.use { ThumbnailTransfer.read(it, 100_000) }.toString(Charsets.UTF_8)
                YoutubeSearchJson.oembedChannel(output)?.also { synchronized(channelCache) { channelCache[id] = it } }
            }
        } catch (_: IOException) { null }
          catch (_: IllegalArgumentException) { null }
          finally { connection?.disconnect() }
    }
}
