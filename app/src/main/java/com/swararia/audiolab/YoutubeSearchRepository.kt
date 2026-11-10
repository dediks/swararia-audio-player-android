package com.swararia.audiolab

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

/** Metadata-only search. This does not initialize FFmpeg, download audio or write library data. */
class YoutubeSearchRepository(context: Context) {
    private val appContext = context.applicationContext

    suspend fun search(query: String, provider: SearchProvider): List<SearchHit> {
        val args = YoutubeSearchRules.arguments(query, provider)
        check(!IngestStatus.state.value.busy) { "Tunggu proses audio selesai sebelum mencari." }
        val processId = "search-${UUID.randomUUID()}"
        try {
            val output = withTimeoutOrNull(30_000L) {
                runInterruptible(Dispatchers.IO) {
                    YoutubeDL.init(appContext)
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    check(!IngestStatus.state.value.busy) { "Tunggu proses audio selesai sebelum mencari." }
                    // Arguments are fixed by our rules and passed as a list, never through a shell.
                    val request = YoutubeDLRequest(args.last()).addCommands(args.dropLast(1))
                    YoutubeDL.execute(request, processId).out
                }
            } ?: throw IOException("Pencarian terlalu lama. Periksa koneksi lalu coba lagi.")
            currentCoroutineContext().ensureActive()
            return withContext(Dispatchers.Default) { YoutubeSearchJson.parse(output) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (cancel: YoutubeDL.CanceledException) {
            throw CancellationException("Pencarian dibatalkan.").apply { initCause(cancel) }
        } catch (interrupted: InterruptedException) {
            throw CancellationException("Pencarian dibatalkan.").apply { initCause(interrupted) }
        } catch (failure: com.yausername.youtubedl_android.YoutubeDLException) {
            throw IOException("${provider.label} belum dapat diakses. Periksa koneksi atau coba lagi nanti.", failure)
        } finally {
            // runInterruptible interrupts the blocking wrapper (which destroys its process).
            // This is a defensive cleanup if cancellation happened during startup.
            runCatching { YoutubeDL.destroyProcessById(processId) }
        }
    }
}
