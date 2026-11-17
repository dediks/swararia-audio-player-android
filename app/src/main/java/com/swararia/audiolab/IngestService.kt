package com.swararia.audiolab

import android.app.*
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.swararia.audiolab.ytmusic.YoutubeMusicCookieRules
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class IngestState(
    val busy: Boolean = false,
    val stage: String = "Siap menambah audio",
    val progress: Float? = null,
    val detail: String = "",
    val cancelable: Boolean = false,
    val error: Boolean = false,
    val engineVersion: String = "bawaan APK",
    val batchId: String? = null,
    val engineBusy: Boolean = false,
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
    private val paused = AtomicBoolean(false)
    private val pausedWifi = AtomicBoolean(false)
    private val skipJobId = AtomicLong(-1L)
    private val rescanQueued = AtomicBoolean(false)
    @Volatile private var runner: BatchDownloadRunner? = null
    @Volatile private var activeBatch: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val lastProgressAtMs = AtomicLong(0L)
    private var lastNotification = 0L
    private val queue get() = (application as SwarariaApp).container.downloadQueue
    private val downloadPrefs get() = (application as SwarariaApp).container.downloadPreferences
    private val library get() = (application as SwarariaApp).library
    private val ytMusicAccount get() = (application as SwarariaApp).container.ytMusicAccount

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Impor dan unduh audio", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                requestCancel()
                if (task == null) stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_PAUSE_QUEUE -> {
                requestPauseQueue()
                if (task == null) stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_SKIP_CURRENT -> {
                requestSkipCurrent(intent.getLongExtra(EXTRA_JOB_ID, -1L))
                if (task == null) stopSelf(startId)
                return START_NOT_STICKY
            }
            ACTION_ENSURE_BATCH -> {
                if (task?.isActive == true) {
                    rescanQueued.set(true)
                    return START_NOT_STICKY
                }
            }
        }
        if (task?.isActive == true) return START_NOT_STICKY
        if (intent?.action !in setOf(ACTION_BATCH, ACTION_ENSURE_BATCH, ACTION_DOWNLOAD, ACTION_IMPORT, ACTION_UPDATE)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        canceled.set(false)
        paused.set(false)
        pausedWifi.set(false)
        skipJobId.set(-1L)
        rescanQueued.set(false)
        activeBatch = intent?.getStringExtra(EXTRA_BATCH)
        try {
            if (intent?.action == ACTION_ENSURE_BATCH) {
                IngestStatus.mutable.update {
                    it.copy(busy = true, stage = "Menyiapkan...", progress = null, detail = "", cancelable = true, error = false, batchId = activeBatch)
                }
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification("Menyiapkan...", null), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            acquireLocks()
        } catch (_: Exception) {
            releaseResources(startId)
            IngestStatus.mutable.update { it.copy(busy = false, cancelable = false, error = true, stage = "Tidak dapat memulai layanan", detail = "Coba lagi saat aplikasi terbuka.") }
            return START_NOT_STICKY
        }
        task = scope.launch(start = CoroutineStart.ATOMIC) {
            var exclusive = false
            val startedAtMs = android.os.SystemClock.elapsedRealtime()
            lastProgressAtMs.set(startedAtMs)
            val watchdog = scope.launch {
                while (isActive) {
                    delay(60_000L)
                    val now = android.os.SystemClock.elapsedRealtime()
                    val idleMs = now - lastProgressAtMs.get()
                    val totalMs = now - startedAtMs
                    if (idleMs >= MAX_STALL_MS || totalMs >= MAX_TOTAL_SESSION_MS) {
                        forceStop()
                        break
                    }
                }
            }
            try {
                // Recovery/cleanup must never run concurrently with a live native worker.
                exclusive = workerMutex.tryLock()
                check(exclusive) { "Worker already active" }
                library.recoverDownloadPublications()
                queue.recoverInterrupted()
                cleanAbandonedWorkspaces()
                when (intent?.action) {
                    ACTION_IMPORT -> {
                        val uris = extractImportUris(intent)
                        require(uris.isNotEmpty()) { "File pilihan tidak ditemukan." }
                        if (uris.size == 1) {
                            ensureSufficientStorage()
                            enterProtectedPhase("Menyalin audio pilihan...")
                            val outcome = withContext(NonCancellable) { library.importAudioWithOutcome(uris.single()) }
                            val msg = if (outcome is UploadImportOutcome.Deduplicated) {
                                "Sudah ada di pustaka: ${outcome.track.displayName}"
                            } else {
                                outcome.track.displayName
                            }
                            finished("Impor selesai", msg)
                        } else {
                            var importedCount = 0
                            var duplicateCount = 0
                            var failedCount = 0
                            for ((idx, uri) in uris.withIndex()) {
                                currentCoroutineContext().ensureActive()
                                checkNotCanceled()
                                ensureSufficientStorage()
                                report(
                                    stage = "Mengimpor (${idx + 1}/${uris.size})...",
                                    progress = idx.toFloat() / uris.size.toFloat(),
                                    detail = "$importedCount masuk, $duplicateCount sudah ada",
                                    cancelable = true,
                                )
                                try {
                                    when (library.importAudioWithOutcome(uri)) {
                                        is UploadImportOutcome.Created -> importedCount++
                                        is UploadImportOutcome.Deduplicated -> duplicateCount++
                                    }
                                } catch (cancel: CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    failedCount++
                                }
                            }
                            if (importedCount == 0 && duplicateCount == 0 && failedCount > 0) {
                                throw IngestUserMessageException(IngestFailureMessages.IMPORT_FAILED)
                            }
                            val parts = buildList {
                                add("$importedCount lagu masuk pustaka")
                                if (duplicateCount > 0) add("$duplicateCount sudah ada")
                                if (failedCount > 0) add("$failedCount dilewati")
                            }
                            finished("Impor selesai", parts.joinToString(", "))
                        }
                    }
                    ACTION_UPDATE -> {
                        report("Menyiapkan mesin...", null)
                        checkSupportedPageSize()
                        YoutubeDL.init(applicationContext)
                        currentCoroutineContext().ensureActive()
                        enterProtectedPhase("Mengambil pembaruan yt-dlp dari GitHub resmi...")
                        updateEnginePreferringNightly()
                        updateVersion()
                        finished("Mesin siap", "yt-dlp: ${IngestStatus.state.value.engineVersion}")
                    }
                    ACTION_DOWNLOAD, ACTION_BATCH, ACTION_ENSURE_BATCH -> {
                        var batch: String? = if (intent.action == ACTION_DOWNLOAD) {
                            require(intent.getBooleanExtra(EXTRA_AUTHORIZED, false))
                            val url = LibraryRules.validatedHttpsUrl(intent.getStringExtra(EXTRA_URL).orEmpty())
                            queue.enqueue(listOf(DownloadRequest(url, "Audio")), authorized = true)
                        } else activeBatch ?: queue.nextQueuedBatchId()
                        if (batch.isNullOrBlank()) {
                            finished("Antrean kosong", "Tidak ada item yang menunggu.")
                        } else {
                            do {
                                rescanQueued.set(false)
                                val runningBatch = batch ?: break
                                activeBatch = runningBatch
                                IngestStatus.mutable.update { it.copy(batchId = runningBatch) }
                                val worker = BatchDownloadRunner(
                                    queue = queue,
                                    isPaused = { paused.get() },
                                    nextQueuedOverride = { _ -> queue.nextAnyQueued() },
                                )
                                runner = worker
                                if (canceled.get()) worker.cancel(runningBatch)
                                // Engine preparation belongs to each claimed job: setup failure must not strand QUEUED rows.
                                worker.run(runningBatch) { job, control -> downloadOne(job, control) }
                                if (canceled.get() || paused.get()) break
                                batch = queue.nextQueuedBatchId() ?: if (rescanQueued.getAndSet(false)) queue.nextQueuedBatchId() else null
                            } while (batch != null)
                            when {
                                canceled.get() -> finished("Dibatalkan", "File yang sudah selesai tetap di library.")
                                pausedWifi.get() -> finished("Menunggu Wi-Fi", "Mode Hanya Wi-Fi aktif. Sambungkan Wi-Fi lalu lanjutkan.")
                                paused.get() -> finished("Antrean dijeda", "Antrean menunggu dilanjutkan kembali.")
                                else -> finished("Antrean selesai", "Lihat status setiap audio di Unduhan.")
                            }
                        }
                    }
                }
            } catch (_: CancellationException) {
                finished("Dibatalkan", "File yang sudah selesai tetap di library.")
            } catch (failure: Exception) {
                // Never surface yt-dlp output/exception text: it can contain signed URLs and headers.
                val detail = if (failure is IngestUserMessageException) {
                    IngestFailureMessages.sanitize(failure.userMessage)
                } else {
                    "Periksa sumber, koneksi, dan dukungan perangkat, lalu coba ulang."
                }
                IngestStatus.mutable.update { it.copy(stage = "Belum berhasil", progress = null,
                    detail = detail, error = true, cancelable = false) }
            } finally {
                watchdog.cancel()
                synchronized(phaseLock) { processId?.let { runCatching { YoutubeDL.destroyProcessById(it) } }; processId = null }
                runner = null
                activeBatch = null
                if (exclusive) workerMutex.unlock()
                releaseResources(startId)
                task = null
                // Release reservation last: another start cannot race native/foreground teardown.
                IngestStatus.mutable.update { it.copy(busy = false, cancelable = false, engineBusy = false) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun downloadOne(job: DownloadJob, control: BatchDownloadRunner.Control) {
        var work: File? = null
        var cookieFile: File? = null
        try {
            require(job.authorized)
            val url = DownloadQueueRules.validatedPublicSourceUrl(job.sourceUrl)
            val videoId = url.substringAfter("v=")
            synchronized(phaseLock) { interruptible = true; checkJobControls(job.id) }
            acquireLocks()
            lastProgressAtMs.set(android.os.SystemClock.elapsedRealtime())
            ensureSufficientStorage()
            checkSupportedPageSize()
            control.progress(null, "Menyiapkan mesin")
            report("Menyiapkan Python dan FFmpeg di HP...", null)
            val engineStarted = System.nanoTime()
            YoutubeDL.init(applicationContext)
            FFmpeg.init(applicationContext)
            android.util.Log.d("SwarariaTiming", "download_engine_init_ms=${((System.nanoTime() - engineStarted) / 1_000_000L).coerceAtLeast(0)}")
            currentCoroutineContext().ensureActive()
            checkJobControls(job.id)
            updateVersion()
            val root = File(cacheDir, "ingest").apply { check(mkdirs() || isDirectory) }
            val directory = File(root, UUID.randomUUID().toString()).apply { check(mkdir()) }
            work = directory
            val outputTemplate = File(directory, "%(title).100B [%(id)s].%(ext)s").absolutePath
            val activeCookies = ytMusicAccount.activeNetscapeCookiesForDownload()
            cookieFile = activeCookies?.let { text ->
                File(directory, "ytm-cookies.txt").apply {
                    writeText(text, Charsets.UTF_8)
                    setReadable(false, false); setReadable(true, true)
                    setWritable(false, false); setWritable(true, true)
                }
            }
            val targetUrl = if (cookieFile != null) "https://music.youtube.com/watch?v=$videoId" else url
            var usedCookies = cookieFile != null
            control.progress(null, "Mengunduh audio")
            var budgetExceeded = false
            if (cookieFile != null) {
                try {
                    budgetExceeded = runYtDlpAttempt(
                        jobId = job.id,
                        request = buildDownloadRequest(targetUrl, outputTemplate, cookieFile),
                        directory = directory,
                        control = control,
                    )
                } catch (failure: Exception) {
                    if (canceled.get()) throw CancellationException("Dibatalkan")
                    if (paused.get() || failure is PauseQueueException) throw PauseQueueException()
                    if (skipJobId.get() == job.id || failure is SkipCurrentJobException) throw SkipCurrentJobException()
                    val activeCookieFile = cookieFile
                    val dirExceeded = budgetExceeded ||
                        directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } > MAX_WORK_BYTES
                    if (activeCookieFile == null || dirExceeded) throw failure
                    val updatedText = runCatching { activeCookieFile.takeIf { it.isFile }?.readText() }.getOrNull()
                    val stillValid = updatedText?.let {
                        runCatching { YoutubeMusicCookieRules.sanitizeNetscapeCookies(it) }.getOrNull()
                    }
                    wipeCookieFile(activeCookieFile)
                    cookieFile = null
                    if (stillValid == null) ytMusicAccount.markNeedsReauth()
                    directory.listFiles().orEmpty().forEach { it.deleteRecursively() }
                    usedCookies = false
                    control.progress(null, "Mengulang tanpa akun")
                    budgetExceeded = runPublicWithEngineRecovery(
                        jobId = job.id,
                        url = url,
                        outputTemplate = outputTemplate,
                        directory = directory,
                        control = control,
                    )
                }
            } else {
                budgetExceeded = runPublicWithEngineRecovery(
                    jobId = job.id,
                    url = url,
                    outputTemplate = outputTemplate,
                    directory = directory,
                    control = control,
                )
            }
            if (usedCookies) {
                val activeCookieFile = cookieFile
                if (activeCookieFile != null && activeCookieFile.isFile) {
                    val updated = runCatching { activeCookieFile.readText(Charsets.UTF_8) }.getOrNull()
                    if (updated != null) ytMusicAccount.refreshFromYtDlpOutput(updated)
                }
            }
            wipeCookieFile(cookieFile)
            cookieFile = null
            check(!budgetExceeded && directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } <= MAX_WORK_BYTES)
            currentCoroutineContext().ensureActive()
            val output = directory.listFiles().orEmpty().filter {
                it.isFile && it.length() > 0 && it.extension.lowercase() in MediaLibrary.AUDIO_TYPES &&
                    it.canonicalFile.parentFile == directory.canonicalFile
            }
            require(output.size == 1)
            val downloadedFile = output.single()
            android.util.Log.i(
                "SwarariaIngest",
                "downloaded ext=${downloadedFile.extension.lowercase()} bytes=${downloadedFile.length()} usedCookies=$usedCookies",
            )
            if (usedCookies) {
                inspectHighBitratePremiumLabel(downloadedFile)?.let { label ->
                    ytMusicAccount.confirmPremiumFromDownload(label)
                }
            }
            // A cancellation during postprocess may finish this current item, never the pending items.
            synchronized(phaseLock) {
                if (interruptible) checkJobControls(job.id)
                interruptible = false
            }
            val (downloadedMeta, coverFile) = parseDownloadedMetadata(directory)
            val resolvedThumbnailUrl = downloadedMeta?.thumbnailUrl ?: job.thumbnailUrl
            runCatching {
                queue.updateResolvedMetadata(job.id, downloadedMeta?.title, resolvedThumbnailUrl)
            }
            control.progress(null, "Menyimpan audio")
            report("Menyimpan ke Music/SwarariaAudioLab/...", null, cancelable = false)
            var trackId: Long? = null
            control.commitDurably {
                trackId = library.addDownloadedFile(downloadedFile, job.id, downloadedMeta).id
            }
            // Optional artwork is strictly AFTER the durable audio + job transaction.
            runCatching {
                library.attachArtwork(
                    trackId = requireNotNull(trackId),
                    thumbnailUrl = resolvedThumbnailUrl,
                    localArtworkFile = coverFile,
                )
            }
        } finally {
            skipJobId.compareAndSet(job.id, -1L)
            wipeCookieFile(cookieFile)
            runCatching { work?.deleteRecursively() }
            synchronized(phaseLock) { interruptible = true }
        }
    }

    private suspend fun runPublicWithEngineRecovery(
        jobId: Long,
        url: String,
        outputTemplate: String,
        directory: File,
        control: BatchDownloadRunner.Control,
    ): Boolean {
        try {
            return runYtDlpAttempt(
                jobId = jobId,
                request = buildDownloadRequest(url, outputTemplate, cookieFile = null),
                directory = directory,
                control = control,
            )
        } catch (failure: Exception) {
            if (canceled.get()) throw CancellationException("Dibatalkan")
            if (paused.get() || failure is PauseQueueException) throw PauseQueueException()
            if (skipJobId.get() == jobId || failure is SkipCurrentJobException) throw SkipCurrentJobException()
            val dirExceeded = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } > MAX_WORK_BYTES
            if (dirExceeded || !DownloadQueueRules.shouldAttemptEngineRecovery(failure.message)) throw failure
            checkJobControls(jobId)
            if (tryClaimAutoHealSlot()) {
                control.progress(null, "Memperbarui mesin yt-dlp")
                report("Memperbarui mesin yt-dlp...", null)
                IngestStatus.mutable.update { it.copy(engineBusy = true) }
                try {
                    runCatching {
                        updateEnginePreferringNightly()
                        updateVersion()
                    }
                } finally {
                    IngestStatus.mutable.update { it.copy(engineBusy = false) }
                }
            }
            directory.listFiles().orEmpty().forEach { it.deleteRecursively() }
            checkJobControls(jobId)
            control.progress(null, "Mengulang dengan klien cadangan")
            return runYtDlpAttempt(
                jobId = jobId,
                request = buildDownloadRequest(
                    url = url,
                    outputTemplate = outputTemplate,
                    cookieFile = null,
                    useFallbackClients = true,
                ),
                directory = directory,
                control = control,
            )
        }
    }

    private fun buildDownloadRequest(
        url: String,
        outputTemplate: String,
        cookieFile: File?,
        useFallbackClients: Boolean = false,
    ): YoutubeDLRequest = YoutubeDLRequest(url)
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
        .addOption("--remote-components", DownloadQueueRules.REMOTE_COMPONENTS_EJS)
        .addOption("-f", "bestaudio/best")
        .addOption("--extract-audio")
        .addOption("--audio-format", "best")
        .addOption("--write-thumbnail")
        .addOption("--embed-metadata")
        .addOption("--no-plugin-dirs")
        .addOption("--plugin-dirs", downloadPluginDirectory().absolutePath)
        .addOption("--use-postprocessor", "SwarariaEnrich:when=pre_process")
        .addOption("--use-postprocessor", "SwarariaEmbedCover:when=after_move")
        .addOption("-o", outputTemplate)
        .apply {
            downloadPrefs.state.value.rateLimit.ytDlpArg?.let { limit ->
                addOption("--limit-rate", limit)
            }
            if (cookieFile != null) {
                addOption("--cookies", cookieFile.absolutePath)
                addOption("--extractor-args", DownloadQueueRules.AUTH_YOUTUBE_EXTRACTOR_ARGS)
            } else if (useFallbackClients) {
                addOption("--extractor-args", DownloadQueueRules.FALLBACK_YOUTUBE_EXTRACTOR_ARGS)
            }
        }

    private fun runYtDlpAttempt(
        jobId: Long,
        request: YoutubeDLRequest,
        directory: File,
        control: BatchDownloadRunner.Control,
    ): Boolean {
        val id = UUID.randomUUID().toString()
        synchronized(phaseLock) { checkJobControls(jobId); processId = id }
        var lastPersist = 0L
        var lastStage = ""
        var budgetExceeded = false
        val executeStarted = System.nanoTime()
        var firstProgressRecorded = false
        try {
            val response = YoutubeDL.execute(request, id) { percentage, _, _ ->
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
                    val stopRequested = canceled.get() || paused.get() || skipJobId.get() == jobId
                    if (stopRequested && interruptible) runCatching { YoutubeDL.destroyProcessById(id) }
                }
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastPersist >= 700 || stage != lastStage) {
                    if (!downloadPrefs.canDownloadOnCurrentNetwork()) {
                        pausedWifi.set(true)
                        paused.set(true)
                        synchronized(phaseLock) {
                            if (interruptible) runCatching { YoutubeDL.destroyProcessById(id) }
                        }
                    }
                    // Bounded per-job workspace; postprocessing may temporarily hold input plus output.
                    budgetExceeded = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() } > MAX_WORK_BYTES
                    if (budgetExceeded) runCatching { YoutubeDL.destroyProcessById(id) }
                    val progress = if (postprocess) null else actual?.div(100f)
                    runBlocking { control.progress(progress, stage) }
                    report("$stage...", progress, cancelable = synchronized(phaseLock) { interruptible })
                    lastPersist = now; lastStage = stage
                }
            }
            logSanitizedYtDlpDiagnostics(response.out.orEmpty(), response.err.orEmpty())
            return budgetExceeded
        } catch (failure: Exception) {
            failure.message?.let { logSanitizedYtDlpDiagnostics(it) }
            if (canceled.get()) throw CancellationException("Dibatalkan")
            if (paused.get()) throw PauseQueueException()
            if (skipJobId.get() == jobId) throw SkipCurrentJobException()
            throw failure
        } finally {
            // execute returned/unwound before the runner resolves the RUNNING row.
            runCatching { YoutubeDL.destroyProcessById(id) }
            synchronized(phaseLock) { processId = null }
        }
    }

    private fun wipeCookieFile(file: File?) {
        if (file == null) return
        runCatching {
            if (file.isFile) {
                file.writeBytes(ByteArray(0))
                file.delete()
            }
        }
    }

    private fun logSanitizedYtDlpDiagnostics(vararg streams: String) {
        val urlRegex = Regex("https?://\\S+")
        val sensitiveRegex = Regex("(?i)(cookie\\s*[:=]|token=|pot=|sig=|sapisid|login_info|__secure-|__host-|authorization\\s*[:=])\\S*")
        val allowedPrefixes = listOf("[youtube]", "[info]", "WARNING:", "ERROR:")
        val allowedSignals = listOf(
            "Downloading 1 format(s):",
            "Detected YouTube Premium",
            "player API JSON",
            "initial data",
            "PO Token",
            "n challenge",
            "JavaScript runtime",
            "no longer valid",
            "Skipping client",
            "Requested format",
            "Some web",
            "Sign in to confirm",
        )
        streams.asSequence()
            .flatMap { it.lineSequence() }
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    allowedPrefixes.any { line.startsWith(it) } &&
                    allowedSignals.any { line.contains(it, ignoreCase = true) }
            }
            .map { line ->
                line.replace(urlRegex, "<url>")
                    .replace(sensitiveRegex, "<redacted>")
                    .take(220)
            }
            .distinct()
            .take(10)
            .forEach { android.util.Log.i("SwarariaIngest", it) }
    }

    private fun inspectHighBitratePremiumLabel(file: File): String? {
        val ext = file.extension.lowercase()
        if (ext != "m4a" && ext != "opus") return null
        val reader = MediaMetadataRetriever()
        return try {
            reader.setDataSource(file.absolutePath)
            val reportedBps = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            val durationMs = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val effectiveBps = when {
                reportedBps > 0L -> reportedBps
                durationMs >= 1_000L && file.length() > 0L -> (file.length() * 8_000L) / durationMs
                else -> 0L
            }
            if (effectiveBps < 192_000L) {
                null
            } else {
                val kbps = ((effectiveBps + 500L) / 1_000L).toInt()
                val codec = if (ext == "opus") "Opus" else "AAC M4A"
                "${kbps}k $codec"
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { reader.release() }
        }
    }

    private fun checkNotCanceled() { if (canceled.get()) throw CancellationException("Dibatalkan") }
    private fun checkJobControls(jobId: Long) {
        checkNotCanceled()
        if (paused.get()) throw PauseQueueException()
        if (skipJobId.get() == jobId) throw SkipCurrentJobException()
        if (!downloadPrefs.canDownloadOnCurrentNetwork()) {
            pausedWifi.set(true)
            paused.set(true)
            throw PauseQueueException()
        }
    }
    private fun enterProtectedPhase(stage: String) {
        synchronized(phaseLock) { checkNotCanceled(); interruptible = false }
        report(stage, null, cancelable = false)
    }
    private fun updateEnginePreferringNightly(): YoutubeDL.UpdateStatus? =
        try {
            YoutubeDL.updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.NIGHTLY)
        } catch (_: Exception) {
            YoutubeDL.updateYoutubeDL(applicationContext, YoutubeDL.UpdateChannel.STABLE)
        }

    private fun tryClaimAutoHealSlot(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        while (true) {
            val previous = lastAutoHealElapsedMs.get()
            if (!DownloadQueueRules.canAutoUpdateEngine(previous, now)) return false
            if (lastAutoHealElapsedMs.compareAndSet(previous, now)) return true
        }
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
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                if (batch != null) {
                    if (worker != null) worker.cancel(batch) else queue.cancelBatch(batch)
                }
                queue.cancelAllQueued()
            }
        }
        synchronized(phaseLock) {
            if (interruptible) {
                processId?.let { runCatching { YoutubeDL.destroyProcessById(it) } }
                task?.cancel()
            }
        }
    }
    private fun requestPauseQueue() {
        paused.set(true)
        synchronized(phaseLock) {
            if (interruptible) {
                processId?.let { runCatching { YoutubeDL.destroyProcessById(it) } }
            }
        }
    }
    private fun requestSkipCurrent(targetJobId: Long) {
        if (targetJobId > 0L) skipJobId.set(targetJobId)
        synchronized(phaseLock) {
            if (interruptible) {
                processId?.let { runCatching { YoutubeDL.destroyProcessById(it) } }
            }
        }
    }
    private fun forceStop() {
        requestCancel()
        synchronized(phaseLock) { processId?.let { runCatching { YoutubeDL.destroyProcessById(it) } } }
        task?.cancel()
    }
    private fun checkSupportedPageSize() {
        val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
        if (pageSize != 4096L) {
            throw IngestUserMessageException(IngestFailureMessages.UNSUPPORTED_16KB_PAGE_SIZE)
        }
    }

    private fun ensureSufficientStorage() {
        val minBytes = MIN_FREE_STORAGE_BYTES
        val cacheAvail = runCatching { StatFs(cacheDir.absolutePath).availableBytes }.getOrNull()
        val filesAvail = runCatching { StatFs(filesDir.absolutePath).availableBytes }.getOrNull()
        if ((cacheAvail != null && cacheAvail < minBytes) || (filesAvail != null && filesAvail < minBytes)) {
            throw IngestUserMessageException(IngestFailureMessages.INSUFFICIENT_STORAGE)
        }
    }

    private fun cleanAbandonedWorkspaces() {
        val root = File(cacheDir, "ingest")
        root.listFiles().orEmpty().filter {
            it.isDirectory && runCatching { UUID.fromString(it.name).toString() == it.name && it.canonicalFile.parentFile == root.canonicalFile }.getOrDefault(false)
        }.forEach { it.deleteRecursively() }
        val cutoff = System.currentTimeMillis() - STALE_CACHE_MS
        File(cacheDir, "desktop_remote_downloads").listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.name.endsWith(".part") && file.lastModified() < cutoff) file.delete()
        }
        File(cacheDir, "phase_export").listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        synchronized(phaseLock) {
            if (wakeLock == null) {
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:ingest")
                    .apply { setReferenceCounted(false) }
            }
            runCatching { wakeLock?.acquire(WAKELOCK_SLICE_MS) }
            if (wifiLock == null) {
                val wifi = applicationContext.getSystemService(WifiManager::class.java)
                wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:ingest-wifi")
                    ?.apply { setReferenceCounted(false) }
            }
            runCatching { if (wifiLock?.isHeld == false) wifiLock?.acquire() }
        }
    }

    private fun releaseWakeLock() {
        val (heldWake, heldWifi) = synchronized(phaseLock) {
            val wl = wakeLock.also { wakeLock = null }
            val wfl = wifiLock.also { wifiLock = null }
            wl to wfl
        }
        heldWake?.let { runCatching { if (it.isHeld) it.release() } }
        heldWifi?.let { runCatching { if (it.isHeld) it.release() } }
    }
    private fun releaseResources(startId: Int) {
        releaseWakeLock()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        runCatching { stopSelf(startId) }
    }
    private fun report(stage: String, progress: Float?, detail: String = "", cancelable: Boolean = true) {
        val now = android.os.SystemClock.elapsedRealtime()
        lastProgressAtMs.set(now)
        IngestStatus.mutable.update { it.copy(busy = true, stage = stage, progress = progress,
            detail = detail, cancelable = cancelable, error = false) }
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
            .setContentTitle("SWR Player").setContentText(stage).setContentIntent(open)
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
        if (task == null) IngestStatus.mutable.update { it.copy(busy = false, cancelable = false, engineBusy = false) }
        super.onDestroy()
    }

    @Synchronized
    private fun downloadPluginDirectory(): File {
        val base = File(applicationContext.filesDir, "download-adapter-plugins")
        val file = File(base, "swararia/yt_dlp_plugins/postprocessor/swararia_metadata.py")
        val bytes = applicationContext.assets
            .open("download_plugins/yt_dlp_plugins/postprocessor/swararia_metadata.py")
            .use { it.readBytes() }
        check(bytes.size in 1..512_000)
        if (!file.isFile || !file.readBytes().contentEquals(bytes)) {
            check(file.parentFile?.isDirectory == true || file.parentFile?.mkdirs() == true)
            val staging = File(file.parentFile, "swararia_metadata.tmp")
            staging.writeBytes(bytes)
            check(staging.renameTo(file)) { "Plugin metadata unduhan tidak dapat disiapkan." }
        }
        return base
    }

    private fun parseDownloadedMetadata(directory: File): Pair<DownloadedAudioMetadata?, File?> {
        val metaFile = File(directory, "swararia-meta.json")
        if (!metaFile.isFile || metaFile.length() !in 1L..MAX_META_JSON_BYTES) return null to null
        val json = runCatching {
            val dirCanon = directory.canonicalFile
            check(metaFile.canonicalFile.parentFile == dirCanon)
            JSONObject(metaFile.readText(Charsets.UTF_8))
        }.getOrNull() ?: return null to null

        fun optText(key: String, maxLen: Int): String? {
            if (json.isNull(key)) return null
            val raw = json.opt(key) as? String ?: return null
            return raw.trim().takeIf { it.isNotEmpty() && !it.equals("<unknown>", ignoreCase = true) }?.take(maxLen)
        }

        val durationMs = if (!json.isNull("durationMs")) {
            json.optDouble("durationMs", -1.0).takeIf { it.isFinite() && it > 0.0 }?.toLong()
        } else {
            null
        }
        val thumbnailUrl = optText("thumbnailUrl", 2048)?.let { candidate ->
            runCatching { DownloadQueueRules.durableThumbnail(candidate) }.getOrNull()
        }
        val metadata = DownloadedAudioMetadata(
            title = optText("track", 240) ?: optText("title", 240),
            artist = optText("artist", 160),
            album = optText("album", 160),
            genre = optText("genre", 80),
            durationMs = durationMs,
            thumbnailUrl = thumbnailUrl,
        )
        val coverName = optText("coverFile", 120)
            ?.takeIf { !it.contains('/') && !it.contains('\\') }
        val coverFile = coverName?.let { name ->
            val candidate = File(directory, name)
            runCatching {
                candidate.takeIf {
                    it.isFile &&
                        it.length() in 1L..ArtworkStore.MAX_INPUT_BYTES.toLong() &&
                        it.canonicalFile.parentFile == directory.canonicalFile
                }
            }.getOrNull()
        }
        return metadata to coverFile
    }

    companion object {
        private val workerMutex = Mutex()
        private val lastAutoHealElapsedMs = AtomicLong(0L)
        private const val MAX_WORK_BYTES = 1024L * 1024 * 1024
        private const val MAX_META_JSON_BYTES = 64L * 1024L
        private const val MIN_FREE_STORAGE_BYTES = 150L * 1024L * 1024L
        private const val WAKELOCK_SLICE_MS = 30L * 60L * 1000L
        private const val MAX_STALL_MS = 15L * 60L * 1000L
        private const val MAX_TOTAL_SESSION_MS = 120L * 60L * 1000L
        private const val STALE_CACHE_MS = 2L * 60L * 60L * 1000L
        private const val MAX_IMPORT_BATCH_COUNT = 500
        private const val CHANNEL = "audio_ingest"
        private const val NOTIFICATION_ID = 101
        const val ACTION_DOWNLOAD = "com.swararia.audiolab.INGEST_DOWNLOAD"
        const val ACTION_IMPORT = "com.swararia.audiolab.INGEST_IMPORT"
        const val ACTION_UPDATE = "com.swararia.audiolab.INGEST_ENGINE_UPDATE"
        private const val ACTION_BATCH = "com.swararia.audiolab.INGEST_BATCH"
        private const val ACTION_ENSURE_BATCH = "com.swararia.audiolab.INGEST_ENSURE_BATCH"
        private const val ACTION_CANCEL = "com.swararia.audiolab.INGEST_CANCEL"
        private const val ACTION_PAUSE_QUEUE = "com.swararia.audiolab.INGEST_PAUSE_QUEUE"
        private const val ACTION_SKIP_CURRENT = "com.swararia.audiolab.INGEST_SKIP_CURRENT"
        private const val EXTRA_BATCH = "batchId"
        private const val EXTRA_JOB_ID = "jobId"
        private const val EXTRA_URL = "url"
        private const val EXTRA_AUTHORIZED = "authorized"
        /** Reserve before Android launch, not asynchronously inside the service. */
        private fun start(
            context: Context,
            intent: Intent,
            batchId: String? = null,
            engineBusy: Boolean = false,
        ) {
            val previous = IngestStatus.mutable.value
            check(!previous.busy && IngestStatus.mutable.compareAndSet(previous, previous.copy(
                busy = true, stage = "Menyiapkan...", progress = null, detail = "", cancelable = true, error = false, batchId = batchId, engineBusy = engineBusy))) {
                "Tunggu proses audio yang sedang berjalan."
            }
            try { ContextCompat.startForegroundService(context, intent) }
            catch (failure: Exception) { IngestStatus.mutable.value = previous; throw failure }
        }
        fun startBatch(context: Context, batchId: String) {
            require(batchId.isNotBlank())
            start(context, Intent(context, IngestService::class.java).setAction(ACTION_BATCH).putExtra(EXTRA_BATCH, batchId), batchId)
        }
        fun ensureBatch(context: Context, batchId: String) {
            require(batchId.isNotBlank())
            val previous = IngestStatus.mutable.value
            when {
                !previous.busy -> startBatch(context, batchId)
                previous.batchId != null -> {
                    runCatching {
                        context.startService(
                            Intent(context, IngestService::class.java)
                                .setAction(ACTION_ENSURE_BATCH)
                                .putExtra(EXTRA_BATCH, batchId),
                        )
                    }
                }
            }
        }
        fun download(context: Context, url: String, authorized: Boolean) {
            LibraryRules.validatedHttpsUrl(url)
            require(authorized) { "Konfirmasi hak penggunaan audio terlebih dahulu." }
            start(context, Intent(context, IngestService::class.java).setAction(ACTION_DOWNLOAD)
                .putExtra(EXTRA_URL, url).putExtra(EXTRA_AUTHORIZED, authorized))
        }
        fun importAudio(context: Context, uri: Uri) {
            importAudioBatch(context, listOf(uri))
        }
        fun importAudioBatch(context: Context, uris: List<Uri>) {
            val distinct = uris.distinct().take(MAX_IMPORT_BATCH_COUNT)
            require(distinct.isNotEmpty()) { "Pilih minimal satu file audio." }
            val intent = Intent(context, IngestService::class.java)
                .setAction(ACTION_IMPORT)
                .setData(distinct.first())
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (distinct.size > 1) {
                val clip = ClipData.newUri(context.contentResolver, "audio", distinct.first())
                for (i in 1 until distinct.size) {
                    clip.addItem(ClipData.Item(distinct[i]))
                }
                intent.clipData = clip
            }
            start(context, intent)
        }
        private fun extractImportUris(intent: Intent?): List<Uri> {
            if (intent == null) return emptyList()
            val collected = LinkedHashSet<Uri>()
            val clip = intent.clipData
            if (clip != null) {
                for (i in 0 until clip.itemCount) {
                    clip.getItemAt(i).uri?.let { collected.add(it) }
                }
            }
            intent.data?.let { collected.add(it) }
            return collected.take(MAX_IMPORT_BATCH_COUNT)
        }
        fun update(context: Context) {
            start(context, Intent(context, IngestService::class.java).setAction(ACTION_UPDATE), engineBusy = true)
        }
        fun cancel(context: Context) {
            if (IngestStatus.state.value.busy) context.startService(Intent(context, IngestService::class.java).setAction(ACTION_CANCEL))
        }
        fun pauseQueue(context: Context) {
            if (IngestStatus.state.value.busy) context.startService(Intent(context, IngestService::class.java).setAction(ACTION_PAUSE_QUEUE))
        }
        fun skipCurrent(context: Context, jobId: Long) {
            if (IngestStatus.state.value.busy && jobId > 0L) {
                context.startService(
                    Intent(context, IngestService::class.java)
                        .setAction(ACTION_SKIP_CURRENT)
                        .putExtra(EXTRA_JOB_ID, jobId),
                )
            }
        }
    }
}
