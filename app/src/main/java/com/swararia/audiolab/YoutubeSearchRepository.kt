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
    private val previewResolver = PreviewResolver(
        extract = { id -> executeMetadata(OnlinePreviewRules.arguments(id), "preview", 30_000L) },
        networkKey = { appContext.getSystemService(ConnectivityManager::class.java)?.activeNetwork?.networkHandle },
        timing = { cached, ms -> android.util.Log.d("SwarariaTiming", "preview_resolve_ms=$ms cache_hit=$cached") },
    )
    private val channelCache = object : LinkedHashMap<String, String>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 128
    }

    suspend fun search(query: String, provider: SearchProvider): List<SearchHit> =
        searchPage(query, provider, SearchKind.SONGS).hits

    override suspend fun searchPage(query: String, provider: SearchProvider, kind: SearchKind,
        offset: Int, playlistId: String?): SearchPage = withTimeoutOrNull(60_000L) {
        val base = YoutubeSearchRules.arguments(query, provider, kind, offset, playlistId)
        val plugin = withContext(Dispatchers.IO) { pluginDirectory() }
        val args = base.dropLast(1) + listOf("--no-plugin-dirs", "--plugin-dirs", plugin.absolutePath, base.last())
        val output = executeMetadata(args, "search", 30_000L)
        val raw = YoutubeSearchJson.parse(output, kind)
        val permits = Semaphore(3)
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
                YoutubeSearchJson.metadata(info, hit.videoId)?.let { full ->
                    enriched[index] = hit.copy(channel = full.channel ?: hit.channel,
                        durationSeconds = full.durationSeconds ?: hit.durationSeconds,
                        channelIsVerified = full.channelIsVerified || hit.channelIsVerified,
                        thumbnailUrl = full.thumbnailUrl ?: hit.thumbnailUrl)
                }
            } catch (cancel: CancellationException) { throw cancel }
              catch (_: Exception) { /* Incomplete items remain explicitly excluded, not fabricated. */ }
        }
        val complete = enriched.filter(YoutubeSearchRules::complete)
        SearchPage(complete, YoutubeSearchJson.entryCount(output), enriched.size - complete.size)
    } ?: throw IOException("Pencarian terlalu lama. Coba lagi saat koneksi lebih stabil.")

    override suspend fun preview(hit: SearchHit): PreviewMedia {
        require(hit.kind == SearchKind.SONGS) { "Buka playlist lalu pilih satu lagu untuk preview." }
        return previewResolver.resolve(hit)
    }
    override fun invalidatePreview(videoId: String) = previewResolver.invalidate(videoId)

    private suspend fun executeMetadata(args: List<String>, prefix: String, timeout: Long): String {
        check(!ingestBusy()) { "Tunggu proses audio selesai terlebih dahulu." }
        val processId = "$prefix-${UUID.randomUUID()}"
        try {
            return withTimeoutOrNull(timeout) {
                runInterruptible(Dispatchers.IO) {
                    YoutubeDL.init(appContext)
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    check(!ingestBusy()) { "Tunggu proses audio selesai terlebih dahulu." }
                    val request = YoutubeDLRequest(args.last()).addCommands(args.dropLast(1))
                    YoutubeDL.execute(request, processId).out
                }
            } ?: throw IOException("Sumber merespons terlalu lama. Coba lagi nanti.")
        } catch (cancel: CancellationException) { throw cancel }
          catch (cancel: YoutubeDL.CanceledException) { throw CancellationException("Proses dibatalkan.").apply { initCause(cancel) } }
          catch (interrupted: InterruptedException) { throw CancellationException("Proses dibatalkan.").apply { initCause(interrupted) } }
          catch (failure: com.yausername.youtubedl_android.YoutubeDLException) {
              throw IOException(if (prefix == "preview") "Preview belum tersedia dari sumber ini. Sumber dapat membatasi akses; coba lagu lain atau coba lagi nanti."
                  else "Sumber belum dapat diakses. Periksa koneksi lalu coba lagi.", failure)
          } finally { runCatching { YoutubeDL.destroyProcessById(processId) } }
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
