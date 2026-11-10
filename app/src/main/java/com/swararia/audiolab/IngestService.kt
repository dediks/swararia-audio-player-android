package com.swararia.audiolab

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

data class IngestState(
    val busy: Boolean = false,
    val stage: String = "Siap menambah audio",
    val progress: Float? = null,
    val detail: String = "",
    val cancelable: Boolean = false,
    val error: Boolean = false,
    val engineVersion: String = "bawaan APK"
)

object IngestStatus {
    internal val mutable = MutableStateFlow(IngestState())
    val state = mutable.asStateFlow()
}

/** User-initiated foreground job; it never schedules unattended downloads. */
class IngestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var task: Job? = null
    private var processId: String? = null
    private val canceled = AtomicBoolean(false)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotification = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Impor dan unduh audio", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            if (IngestStatus.state.value.cancelable) {
                canceled.set(true)
                processId?.let { runCatching { YoutubeDL.destroyProcessById(it) } }
                task?.cancel(CancellationException("Dibatalkan oleh pengguna"))
            }
            return START_NOT_STICKY
        }
        if (task?.isActive == true) return START_NOT_STICKY
        canceled.set(false)
        IngestStatus.mutable.value = IngestState(busy = true, stage = "Menyiapkan...", cancelable = true,
            engineVersion = IngestStatus.state.value.engineVersion)
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Menyiapkan...", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ingest").also {
            it.acquire(30 * 60 * 1000L)
        }
        task = scope.launch {
            var work: File? = null
            try {
                when (intent?.action) {
                    ACTION_IMPORT -> {
                        val uri = intent.data ?: error("File pilihan tidak ditemukan.")
                        report("Menyalin audio pilihan...", null)
                        val track = (application as SwarariaApp).library.importAudio(uri)
                        finished("Impor selesai", track.displayName)
                    }
                    ACTION_UPDATE -> {
                        report("Menyiapkan mesin...", null)
                        YoutubeDL.init(applicationContext)
                        currentCoroutineContext().ensureActive()
                        report("Mengambil pembaruan yt-dlp dari GitHub resmi...", null, cancelable = false)
                        YoutubeDL.updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                        currentCoroutineContext().ensureActive()
                        val version = YoutubeDL.versionName(applicationContext) ?: "bawaan APK"
                        IngestStatus.mutable.value = IngestStatus.state.value.copy(engineVersion = version)
                        finished("Mesin siap", "yt-dlp: $version")
                    }
                    ACTION_DOWNLOAD -> {
                        require(intent.getBooleanExtra(EXTRA_AUTHORIZED, false)) { "Konfirmasi hak penggunaan audio terlebih dahulu." }
                        val url = LibraryRules.validatedHttpsUrl(intent.getStringExtra(EXTRA_URL).orEmpty())
                        report("Menyiapkan Python dan FFmpeg di HP...", null)
                        YoutubeDL.init(applicationContext)
                        val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
                        check(pageSize == 4096L) { "Mesin unduh pada APK uji ini belum mendukung perangkat berhalaman memori 16 KB. Impor, player, dan organizer lokal tetap dapat dicoba." }
                        FFmpeg.init(applicationContext)
                        currentCoroutineContext().ensureActive()
                        val version = YoutubeDL.versionName(applicationContext) ?: "bawaan APK"
                        IngestStatus.mutable.value = IngestStatus.state.value.copy(engineVersion = version)
                        work = File(cacheDir, "ingest/" + UUID.randomUUID().toString()).apply { check(mkdirs()) }
                        val request = YoutubeDLRequest(url)
                            .addOption("--no-playlist")
                            .addOption("--ignore-config")
                            .addOption("--playlist-items", "1")
                            .addOption("--restrict-filenames")
                            .addOption("--no-mtime")
                            .addOption("--newline")
                            .addOption("--socket-timeout", 20)
                            .addOption("--retries", 2)
                            .addOption("--fragment-retries", 2)
                            .addOption("-f", "bestaudio/best")
                            .addOption("--extract-audio")
                            .addOption("--audio-format", "best")
                            .addOption("-o", File(work, "%(title).100B [%(id)s].%(ext)s").absolutePath)
                        processId = UUID.randomUUID().toString()
                        report("Mengunduh audio...", null)
                        YoutubeDL.execute(request, processId) { percentage, _, line ->
                            if (canceled.get()) processId?.let { runCatching { YoutubeDL.destroyProcessById(it) } }
                            val actualProgress = percentage.takeIf { it.isFinite() && it >= 0 }?.coerceIn(0f, 100f)
                            val postprocess = actualProgress != null && actualProgress >= 100f
                            report(if (postprocess) "Menyelesaikan audio..." else "Mengunduh audio...",
                                if (postprocess) null else actualProgress?.div(100f), line.takeLast(420), cancelable = !postprocess)
                        }
                        currentCoroutineContext().ensureActive()
                        check(!canceled.get()) { "Unduhan dibatalkan." }
                        val output = work.listFiles().orEmpty().filter {
                            it.isFile && it.length() > 0 && it.extension.lowercase() in MediaLibrary.AUDIO_TYPES &&
                                it.canonicalPath.startsWith(work.canonicalPath + File.separator)
                        }
                        require(output.size == 1) { "Mesin menghasilkan ${output.size} file audio; satu file diperlukan untuk uji ini." }
                        report("Menyimpan ke Music/SwarariaAudioLab/...", null, cancelable = false)
                        val track = (application as SwarariaApp).library.addDownloadedFile(output.single())
                        finished("Unduhan selesai", track.displayName)
                    }
                    else -> error("Perintah impor tidak dikenal.")
                }
            } catch (_: CancellationException) {
                IngestStatus.mutable.value = IngestStatus.state.value.copy(busy = false, stage = "Dibatalkan", progress = null, detail = "", cancelable = false)
            } catch (_: YoutubeDL.CanceledException) {
                IngestStatus.mutable.value = IngestStatus.state.value.copy(busy = false, stage = "Dibatalkan", progress = null, detail = "", cancelable = false)
            } catch (failure: Exception) {
                IngestStatus.mutable.value = IngestStatus.state.value.copy(busy = false, stage = "Belum berhasil", progress = null,
                    detail = (failure.message ?: failure.javaClass.simpleName).takeLast(1400), error = true, cancelable = false)
            } finally {
                processId = null
                work?.deleteRecursively()
                wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
                ServiceCompat.stopForeground(this@IngestService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun report(stage: String, progress: Float?, detail: String = "", cancelable: Boolean = true) {
        IngestStatus.mutable.value = IngestStatus.state.value.copy(busy = true, stage = stage, progress = progress,
            detail = detail, cancelable = cancelable, error = false)
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNotification > 700 && (android.os.Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED)) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(stage, progress))
            lastNotification = now
        }
    }

    private fun finished(stage: String, name: String) {
        IngestStatus.mutable.value = IngestStatus.state.value.copy(busy = false, stage = stage, progress = 1f,
            detail = name, cancelable = false, error = false)
    }

    private fun notification(stage: String, progress: Float?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_audio)
            .setContentTitle("Swararia Audio Lab")
            .setContentText(stage)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        canceled.set(true)
        processId?.let { runCatching { YoutubeDL.destroyProcessById(it) } }
        task?.cancel()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        canceled.set(true)
        processId?.let { runCatching { YoutubeDL.destroyProcessById(it) } }
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "audio_ingest"
        private const val NOTIFICATION_ID = 101
        const val ACTION_DOWNLOAD = "com.swararia.audiolab.INGEST_DOWNLOAD"
        const val ACTION_IMPORT = "com.swararia.audiolab.INGEST_IMPORT"
        const val ACTION_UPDATE = "com.swararia.audiolab.INGEST_ENGINE_UPDATE"
        private const val ACTION_CANCEL = "com.swararia.audiolab.INGEST_CANCEL"
        private const val EXTRA_URL = "url"
        private const val EXTRA_AUTHORIZED = "authorized"
        fun download(context: Context, url: String, authorized: Boolean) {
            LibraryRules.validatedHttpsUrl(url)
            require(authorized) { "Konfirmasi hak penggunaan audio terlebih dahulu." }
            ContextCompat.startForegroundService(context, Intent(context, IngestService::class.java).setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_AUTHORIZED, authorized))
        }
        fun importAudio(context: Context, uri: Uri) {
            ContextCompat.startForegroundService(context, Intent(context, IngestService::class.java).setAction(ACTION_IMPORT)
                .setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        fun update(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, IngestService::class.java).setAction(ACTION_UPDATE))
        }
        fun cancel(context: Context) {
            context.startService(Intent(context, IngestService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
