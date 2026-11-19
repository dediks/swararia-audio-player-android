package com.swararia.audiolab

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import com.swararia.audiolab.brandskin.BrandSkinManager
import com.swararia.audiolab.foh.FohSafeModeController
import com.swararia.audiolab.foh.FohSoundboardEngine
import com.swararia.audiolab.phasechecker.PhaseCheckerController
import com.swararia.audiolab.phasechecker.PhaseCheckerEngine
import com.swararia.audiolab.remote.AndroidKeystoreDesktopRemoteStore
import com.swararia.audiolab.remote.AndroidRemoteServerController
import com.swararia.audiolab.remote.DesktopRemoteApi
import com.swararia.audiolab.remote.DesktopRemoteHttpsApi
import com.swararia.audiolab.remote.DesktopRemoteLocalPlaylistSummary
import com.swararia.audiolab.remote.DesktopRemoteLocalTrackSummary
import com.swararia.audiolab.remote.DesktopRemoteMediaImportOutcome
import com.swararia.audiolab.remote.DesktopRemoteMediaImporter
import com.swararia.audiolab.remote.DesktopRemoteMediaUploader
import com.swararia.audiolab.remote.DesktopRemoteOpenedUpload
import com.swararia.audiolab.remote.DesktopRemotePersistence
import com.swararia.audiolab.remote.RemoteNsdCoordinator
import com.swararia.audiolab.remote.RemoteWifiPeerController
import com.swararia.audiolab.remote.RemoteWifiTransport
import com.swararia.audiolab.remote.formatDeviceClientLabel
import com.swararia.audiolab.ytmusic.AndroidKeystoreYoutubeMusicStore
import com.swararia.audiolab.ytmusic.YoutubeMusicAccountManager
import com.swararia.audiolab.ytmusic.YoutubeMusicWebTierVerifier
import com.swararia.audiolab.ytmusic.clearAndroidWebViewYoutubeSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/** Manual composition root. Platform dependencies are built here, never inside a screen/ViewModel. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    init {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                File(appContext.cacheDir, "desktop_remote_downloads").listFiles()?.forEach { file ->
                    if (file.isFile && file.name.endsWith(".part")) file.delete()
                }
                File(appContext.cacheDir, "phase_export").listFiles()?.forEach { file ->
                    if (file.isFile) file.delete()
                }
            }
        }
    }
    val ingestState = IngestStatus.state // Adapter to the existing foreground service; process-local, not a durable queue.
    val database: AudioDatabase by lazy {
        Room.databaseBuilder(appContext, AudioDatabase::class.java, "audio-library-spike.db")
            .addMigrations(AudioMigrations.MIGRATION_1_2, AudioMigrations.MIGRATION_2_3, AudioMigrations.MIGRATION_3_4).build()
    }
    val library: MediaLibrary by lazy { MediaLibrary(appContext, database) }
    val downloadQueue: DownloadQueueRepository by lazy { RoomDownloadQueueRepository(database) }
    val downloadPreferences: DownloadPreferences by lazy { DownloadPreferences(appContext) }
    val playbackPreferences: PlaybackPreferences by lazy { PlaybackPreferences(appContext) }
    val safeModeController: FohSafeModeController by lazy { FohSafeModeController(appContext) }
    val soundboardEngine: FohSoundboardEngine by lazy { FohSoundboardEngine(appContext) }
    val brandSkinManager: BrandSkinManager by lazy { BrandSkinManager(appContext) }
    val searchRepository: AudioSearchRepository by lazy {
        YoutubeSearchRepository(appContext, { ingestState.value.engineBusy })
    }
    val ytMusicAccount: YoutubeMusicAccountManager by lazy {
        YoutubeMusicAccountManager(
            store = AndroidKeystoreYoutubeMusicStore(appContext),
            verifier = YoutubeMusicWebTierVerifier(appContext),
            onPlatformCookiesCleared = ::clearAndroidWebViewYoutubeSession,
        ).apply { initialize() }
    }
    val remoteWifiTransport: RemoteWifiTransport by lazy {
        RemoteWifiTransport(appContext)
    }
    val remoteNsdCoordinator: RemoteNsdCoordinator by lazy {
        RemoteNsdCoordinator(appContext)
    }
    val remoteWifiPeerController: RemoteWifiPeerController by lazy {
        RemoteWifiPeerController(appContext, remoteWifiTransport) {
            remoteServerController.refreshInterfacesAsync()
        }
    }
    fun previewPlayback(): PreviewPlayback = PreviewRemote(appContext)
    fun desktopRemoteApi(): DesktopRemoteApi = DesktopRemoteHttpsApi()
        .withConnectionOpener(remoteWifiTransport)
        .withClientLabelProvider { formatDeviceClientLabel(Build.MANUFACTURER, Build.MODEL) }
    private val rememberedDesktopSession by lazy {
        DesktopRemotePersistence(AndroidKeystoreDesktopRemoteStore(appContext))
    }
    fun desktopRemoteStore(): DesktopRemotePersistence = rememberedDesktopSession
    private val desktopRemoteDownloadStagingDir by lazy {
        File(appContext.cacheDir, "desktop_remote_downloads").apply { mkdirs() }
    }
    fun desktopRemoteMediaImporter(): DesktopRemoteMediaImporter = object : DesktopRemoteMediaImporter {
        override suspend fun hasTrack(sha256: String, sizeBytes: Long): Boolean =
            library.findTrackBySha256AndSize(sha256, sizeBytes) != null
        override fun hasEnoughStorage(requiredBytes: Long): Boolean {
            val needed = requiredBytes.coerceAtLeast(0L) + 32L * 1024L * 1024L
            val cacheAvail = runCatching { StatFs(appContext.cacheDir.absolutePath).availableBytes }.getOrNull()
            val filesAvail = runCatching { StatFs(appContext.filesDir.absolutePath).availableBytes }.getOrNull()
            return (cacheAvail == null || cacheAvail >= needed) && (filesAvail == null || filesAvail >= needed)
        }
        override fun createTempDownloadFile(): File {
            desktopRemoteDownloadStagingDir.mkdirs()
            return File(desktopRemoteDownloadStagingDir, "remote-${UUID.randomUUID()}.part")
        }
        override suspend fun importDownloadedAudio(
            stagedFile: File,
            requestedName: String,
            expectedSha256: String,
        ): DesktopRemoteMediaImportOutcome =
            importDownloadedAudio(stagedFile, requestedName, expectedSha256, playlistName = null)

        override suspend fun importDownloadedAudio(
            stagedFile: File,
            requestedName: String,
            expectedSha256: String,
            playlistName: String?,
        ): DesktopRemoteMediaImportOutcome {
            val outcome = library.importValidatedUpload(stagedFile, requestedName, expectedSha256, playlistName = playlistName)
            return if (outcome is UploadImportOutcome.Deduplicated) {
                DesktopRemoteMediaImportOutcome.Deduplicated
            } else {
                DesktopRemoteMediaImportOutcome.Saved
            }
        }
    }

    fun desktopRemoteMediaUploader(): DesktopRemoteMediaUploader = object : DesktopRemoteMediaUploader {
        override val localTracksFlow: Flow<List<DesktopRemoteLocalTrackSummary>> =
            combine(library.tracks, library.playlists) { tracksList, playlistList ->
                val playlistsByTrack = HashMap<Long, MutableList<Long>>()
                for (playlist in playlistList) {
                    for (tid in playlist.trackIds) {
                        playlistsByTrack.getOrPut(tid) { mutableListOf() }.add(playlist.id)
                    }
                }
                tracksList.map { track ->
                    DesktopRemoteLocalTrackSummary(
                        trackId = track.id,
                        displayName = track.displayName,
                        sizeBytes = track.sizeBytes,
                        durationMs = track.durationMs,
                        sha256 = track.sha256,
                        playlistIds = playlistsByTrack[track.id].orEmpty(),
                    )
                }
            }

        override val localPlaylistsFlow: Flow<List<DesktopRemoteLocalPlaylistSummary>> =
            library.playlists.map { list ->
                list.map { DesktopRemoteLocalPlaylistSummary(it.id, it.name, it.trackIds) }
            }

        override suspend fun openUploadStream(trackId: Long): DesktopRemoteOpenedUpload? {
            val media = library.openReadableTrack(trackId) ?: return null
            val sha = media.track.sha256?.takeIf { it.isNotBlank() }
            if (sha == null || media.sizeBytes <= 0L) {
                runCatching { media.inputStream.close() }
                return null
            }
            return DesktopRemoteOpenedUpload(
                filename = media.track.displayName,
                sizeBytes = media.sizeBytes,
                sha256 = sha,
                inputStream = media.inputStream,
            )
        }
    }
    val remoteServerController: AndroidRemoteServerController by lazy {
        AndroidRemoteServerController(appContext, library, remoteNsdCoordinator)
    }
    internal fun createPhaseCheckerEngine(): PhaseCheckerController = PhaseCheckerEngine(appContext)
}

class SwarariaApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
    // Preserve the native ingest adapter API without moving its responsibilities into presentation.
    val database get() = container.database
    val library get() = container.library
}

class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == AppViewModel::class.java) { "Unsupported ViewModel: ${modelClass.name}" }
        @Suppress("UNCHECKED_CAST")
        return AppViewModel(
            container.library,
            container.searchRepository,
            container.ingestState,
            container.downloadQueue,
            container.previewPlayback(),
            container.desktopRemoteApi(),
            container.desktopRemoteStore(),
            container.remoteServerController,
            container.desktopRemoteMediaImporter(),
            container.desktopRemoteMediaUploader(),
            container.ytMusicAccount,
            container.downloadPreferences,
            container.playbackPreferences,
        ) as T
    }
}
