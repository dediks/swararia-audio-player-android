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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
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
    val engineVersion: String = "bawaan APK",
    val batchId: String? = null
)

object IngestStatus {
    internal val mutable = MutableStateFlow(IngestState())
    val state = mutable.asStateFlow()
}

/** Explicit user starts only: no sticky restart, background scheduler, or transfer resume. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class IngestService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var task: Job? = null
    private val phaseLock = Any()
    private var processId: String? = null
    private var interruptible = true
    private val canceled = AtomicBoolean(false)
    @Volatile private var runner: BatchDownloadRunner? = null
    @Volatile private var activeBatch: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotification = 0L
    private val queue get() = (application as SwarariaApp).container.downloadQueue
    private val library get() = (application as SwarariaApp).library

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Impor dan unduh audio", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            requestCancel()
            if (task == null) stopSelf(startId)
            return START_NOT_STICKY
        }
        if (task?.isActive == true) return START_NOT_STICKY
        if (intent?.action !in setOf(ACTION_BATCH, ACTION_DOWNLOAD, ACTION_IMPORT, ACTION_UPDATE)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        canceled.set(false)
        activeBatch = intent?.getStringExtra(EXTRA_BATCH)
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Menyiapkan...", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ingest").also {
                it.acquire(30 * 60 * 1000L)
            }
        } catch (_: Exception) {
            releaseResources(startId)
            IngestStatus.mutable.update { it.copy(busy = false, cancelable = false, error = true, stage = "Tidak dapat memulai layanan", detail = "Coba lagi saat aplikasi terbuka.") }
            return START_NOT_STICKY
        }
        task = scope.launch(start = CoroutineStart.ATOMIC) {
            var exclusive = false
            val watchdog = scope.launch { delay(29 * 60 * 1000L); forceStop() }
            try {
                // Recovery/cleanup must never run concurrently with a live native worker.
                exclusive = workerMutex.tryLock()
                check(exclusive) { "Worker already active" }
                library.recoverDownloadPublications()
                queue.recoverInterrupted()
                cleanAbandonedWorkspaces()
                when (intent?.action) {
                    ACTION_IMPORT -> {
                        val uri = intent.data ?: error("File pilihan tidak ditemukan.")
                        enterProtectedPhase("Menyalin audio pilihan...")
                        val track = withContext(NonCancellable) { library.importAudio(uri) }
                        finished("Impor selesai", track.displayName)
                    }
                    ACTION_UPDATE -> {
                        report("Menyiapkan mesin...", null)
                        YoutubeDL.init(applicationContext)
                        currentCoroutineContext().ensureActive()
                        enterProtectedPhase("Mengambil pembaruan yt-dlp dari GitHub resmi...")
                        YoutubeDL.updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
                        updateVersion()
                        finished("Mesin siap", "yt-dlp: ${IngestStatus.state.value.engineVersion}")
                    }
                    ACTION_DOWNLOAD, ACTION_BATCH -> {
                        val batch = if (intent.action == ACTION_DOWNLOAD) {
                            require(intent.getBooleanExtra(EXTRA_AUTHORIZED, false))
                            val url = LibraryRules.validatedHttpsUrl(intent.getStringExtra(EXTRA_URL).orEmpty())
                            queue.enqueue(listOf(DownloadRequest(url, "Audio")), authorized = true)
                        } else requireNotNull(activeBatch).also { require(it.isNotBlank()) }
                        activeBatch = batch
                        IngestStatus.mutable.update { it.copy(batchId = batch) }
                        val worker = BatchDownloadRunner(queue)
                        runner = worker
                        if (canceled.get()) worker.cancel(batch)
                        // Engine preparation belongs to each claimed job: setup failure must not strand QUEUED rows.
                        worker.run(batch) { job, control -> downloadOne(job, control) }
                        if (canceled.get()) finished("Dibatalkan", "File yang sudah selesai tetap di library.")
                        else finished("Antrean selesai", "Lihat status setiap audio di Unduhan.")
                    }
                }
            } catch (_: CancellationException) {
                finished("Dibatalkan", "File yang sudah selesai tetap di library.")
            } catch (_: Exception) {
                // Never surface yt-dlp output/exception text: it can contain signed URLs and headers.
                IngestStatus.mutable.update { it.copy(stage = "Belum berhasil", progress = null,
                    detail = "Periksa sumber, koneksi, dan dukungan perangkat, lalu coba ulang.", error = true, cancelable = false) }
            } finally {
                watchdog.cancel()
                synchronized(phaseLock) { processId?.let { runCatching { YoutubeDL.destroyProcessById(it) } }; processId = null }
                runner = null
                activeBatch = null
                if (exclusive) workerMutex.unlock()
                releaseResources(startId)
                task = null
                // Release reservation last: another start cannot race native/foreground teardown.
                IngestStatus.mutable.update { it.copy(busy = false, cancelable = false) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun downloadOne(job: DownloadJob, control: BatchDownloadRunner.Control) {
        var work: File? = null
        try {
            require(job.authorized)
            val url = DownloadQueueRules.validatedPublicSourceUrl(job.sourceUrl)
            synchronized(phaseLock) { interruptible = true; checkNotCanceled() }
            control.progress(null, "Menyiapkan mesin")
            report("Menyiapkan Python dan FFmpeg di HP...", null)
            val engineStarted = System.nanoTime()
            YoutubeDL.init(applicationContext)
            val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            check(pageSize == 4096L) { "Perangkat membutuhkan mesin yang mendukung halaman memori 16 KB." }
            FFmpeg.init(applicationContext)
            android.util.Log.d("SwarariaTiming", "download_engine_init_ms=${((System.nanoTime() - engineStarted) / 1_000_000L).coerceAtLeast(0)}")
            currentCoroutineContext().ensureActive()
            checkNotCanceled()
            updateVersion()
            val root = File(cacheDir, "ingest").apply { check(mkdirs() || isDirectory) }
            val directory = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
            work = directory
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
                .addOption("--max-filesize", "512M")
                .addOption("-f", "bestaudio/best")
                .addOption("--extract-audio")
                .addOption("--audio-format", "best")
                .addOption("-o", File(directory, "%(title).100B [%(id)s].%(ext)s").absolutePath)
            val id = UUID.randomUUID().toString()
            synchronized(phaseLock) { checkNotCanceled(); processId = id }
            var lastPersist = 0L
            var lastStage = ""
            var budgetExceeded = false
            control.progress(null, "Mengunduh audio")
            val executeStarted = System.nanoTime()
            var firstProgressRecorded = false
            try {
                YoutubeDL.execute(request, id) { percentage, _, _ ->
                    // Native line deliberately discarded, not logged, shown, or persisted.
                    val actual = percentage.takeIf { it.isFinite() && it >= 0 }?.coerceIn(0f, 100f)
                    if (!firstProgressRecorded && actual != null) {
                        firstProgressRecorded = true
                        // First valid yt-dlp progress callback, NOT a network-first-byte measurement.
                        android.util.Log.d("SwarariaTiming", "download_execute_to_first_progress_ms=${((System.nanoTime() - executeStarted) / 1_000_000L).coerceAtLeast(0)}")
                    }
                    val postprocess = actual != null && actual >= 100f
                    val stage = if (postprocess) "Menyelesaikan audio" else "Mengunduh audio"
                    synchronized(phaseLock) {
                        if (postprocess) interruptible = false // sticky until this job exits
                        if (canceled.get() && interruptible) runCatching { YoutubeDL.destroyProcessById(id) }
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastPersist >= 700 || stage != lastStage) {
                        // Bounded per-job workspace; postprocessing may temporarily hold input plus output.
                        budgetExceeded = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } > MAX_WORK_BYTES
                        if (budgetExceeded) runCatching { YoutubeDL.destroyProcessById(id) }
                        val progress = if (postprocess) null else actual?.div(100f)
                        runBlocking { control.progress(progress, stage) }
                        report("$stage...", progress, cancelable = synchronized(phaseLock) { interruptible })
                        lastPersist = now; lastStage = stage
                    }
                }
            } catch (failure: Exception) {
                if (canceled.get()) throw CancellationException("Dibatalkan")
                throw failure
            } finally {
                // execute returned/unwound before the runner resolves the RUNNING row.
                runCatching { YoutubeDL.destroyProcessById(id) }
                synchronized(phaseLock) { processId = null }
            }
            check(!budgetExceeded && directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= MAX_WORK_BYTES)
            currentCoroutineContext().ensureActive()
            val output = directory.listFiles().orEmpty().filter {
                it.isFile && it.length() > 0 && it.extension.lowercase() in MediaLibrary.AUDIO_TYPES &&
                    it.canonicalFile.parentFile == directory.canonicalFile
            }
            require(output.size == 1)
            // A cancellation during postprocess may finish this current item, never the pending items.
            synchronized(phaseLock) {
                if (interruptible) checkNotCanceled()
                interruptible = false
            }
            control.progress(null, "Menyimpan audio")
            report("Menyimpan ke Music/SwarariaAudioLab/...", null, cancelable = false)
            var trackId: Long? = null
            control.commitDurably {
                trackId = library.addDownloadedFile(output.single(), job.id).id
            }
            // Optional artwork is strictly AFTER the durable audio + job transaction.
            runCatching { library.attachArtwork(requireNotNull(trackId), job.thumbnailUrl) }
        } finally {
            runCatching { work?.deleteRecursively() }
            synchronized(phaseLock) { interruptible = true }
        }
    }

    private fun checkNotCanceled() { if (canceled.get()) throw CancellationException("Dibatalkan") }
    private fun enterProtectedPhase(stage: String) {
        synchronized(phaseLock) { checkNotCanceled(); interruptible = false }
        report(stage, null, cancelable = false)
    }
    private fun updateVersion() {
        val version = YoutubeDL.versionName(applicationContext) ?: "bawaan APK"
        IngestStatus.mutable.update { it.copy(engineVersion = version) }
    }
    private fun requestCancel() {
        canceled.set(true)
        val batch = activeBatch
        val worker = runner
        // Dispatch persistence independently: don't wait for blocking native execute/postprocessing.
        if (batch != null) scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { if (worker != null) worker.cancel(batch) else queue.cancelBatch(batch) }
        }
        synchronized(phaseLock) {
            if (interruptible) {
                processId?.let { runCatching { YoutubeDL.destroyProcessById(it) } }
                task?.cancel()
            }
        }
    }
    private fun forceStop() {
        requestCancel()
        synchronized(phaseLock) { processId?.let { runCatching { YoutubeDL.destroyProcessById(it) } } }
        task?.cancel()
    }
    private fun cleanAbandonedWorkspaces() {
        val root = File(cacheDir, "ingest")
        root.listFiles().orEmpty().filter {
            it.isDirectory && runCatching { UUID.fromString(it.name).toString() == it.name && it.canonicalFile.parentFile == root.canonicalFile }.getOrDefault(false)
        }.forEach { it.deleteRecursively() }
    }
    private fun releaseWakeLock() {
        val held = synchronized(phaseLock) { wakeLock.also { wakeLock = null } }
        held?.let { runCatching { if (it.isHeld) it.release() } }
    }
    private fun releaseResources(startId: Int) {
        releaseWakeLock()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf(startId) }
    }
    private fun report(stage: String, progress: Float?, detail: String = "", cancelable: Boolean = true) {
        IngestStatus.mutable.update { it.copy(busy = true, stage = stage, progress = progress,
            detail = detail, cancelable = cancelable, error = false) }
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNotification > 700) {
            runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(stage, progress)) }
            lastNotification = now
        }
    }
    private fun finished(stage: String, name: String) {
        IngestStatus.mutable.update { it.copy(stage = stage, progress = if (canceled.get()) null else 1f,
            detail = name, cancelable = false, error = false) }
    }
    private fun notification(stage: String, progress: Float?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_audio)
            .setContentTitle("Swararia Audio Lab").setContentText(stage).setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        forceStop()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        forceStop()
        scope.cancel()
        releaseWakeLock()
        if (task == null) IngestStatus.mutable.update { it.copy(busy = false, cancelable = false) }
        super.onDestroy()
    }

    companion object {
        private val workerMutex = Mutex()
        private const val MAX_WORK_BYTES = 1024L * 1024 * 1024
        private const val CHANNEL = "audio_ingest"
        private const val NOTIFICATION_ID = 101
        const val ACTION_DOWNLOAD = "com.swararia.audiolab.INGEST_DOWNLOAD"
        const val ACTION_IMPORT = "com.swararia.audiolab.INGEST_IMPORT"
        const val ACTION_UPDATE = "com.swararia.audiolab.INGEST_ENGINE_UPDATE"
        private const val ACTION_BATCH = "com.swararia.audiolab.INGEST_BATCH"
        private const val ACTION_CANCEL = "com.swararia.audiolab.INGEST_CANCEL"
        private const val EXTRA_BATCH = "batchId"
        private const val EXTRA_URL = "url"
        private const val EXTRA_AUTHORIZED = "authorized"
        /** Reserve before Android launch, not asynchronously inside the service. */
        private fun start(context: Context, intent: Intent, batchId: String? = null) {
            val previous = IngestStatus.mutable.value
            check(!previous.busy && IngestStatus.mutable.compareAndSet(previous, previous.copy(
                busy = true, stage = "Menyiapkan...", progress = null, detail = "", cancelable = true, error = false, batchId = batchId))) {
                "Tunggu proses audio yang sedang berjalan."
            }
            try { ContextCompat.startForegroundService(context, intent) }
            catch (failure: Exception) { IngestStatus.mutable.value = previous; throw failure }
        }
        fun startBatch(context: Context, batchId: String) {
            require(batchId.isNotBlank())
            start(context, Intent(context, IngestService::class.java).setAction(ACTION_BATCH).putExtra(EXTRA_BATCH, batchId), batchId)
        }
        fun download(context: Context, url: String, authorized: Boolean) {
            LibraryRules.validatedHttpsUrl(url)
            require(authorized) { "Konfirmasi hak penggunaan audio terlebih dahulu." }
            start(context, Intent(context, IngestService::class.java).setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_AUTHORIZED, authorized))
        }
        fun importAudio(context: Context, uri: Uri) {
            start(context, Intent(context, IngestService::class.java).setAction(ACTION_IMPORT)
                .setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        fun update(context: Context) { start(context, Intent(context, IngestService::class.java).setAction(ACTION_UPDATE)) }
        fun cancel(context: Context) {
            if (IngestStatus.state.value.busy) context.startService(Intent(context, IngestService::class.java).setAction(ACTION_CANCEL))
        }
    }
}
