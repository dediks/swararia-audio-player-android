package com.swararia.audiolab

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import java.io.File
import java.util.UUID

/** Manual composition root. Platform dependencies are built here, never inside a screen/ViewModel. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val ingestState = IngestStatus.state // Adapter to the existing foreground service; process-local, not a durable queue.
    val database: AudioDatabase by lazy {
        Room.databaseBuilder(appContext, AudioDatabase::class.java, "audio-library-spike.db")
            .addMigrations(AudioMigrations.MIGRATION_1_2, AudioMigrations.MIGRATION_2_3, AudioMigrations.MIGRATION_3_4).build()
    }
    val library: MediaLibrary by lazy { MediaLibrary(appContext, database) }
    val downloadQueue: DownloadQueueRepository by lazy { RoomDownloadQueueRepository(database) }
    val searchRepository: AudioSearchRepository by lazy {
        YoutubeSearchRepository(appContext, { ingestState.value.busy })
    }
    val ytMusicAccount: YoutubeMusicAccountManager by lazy {
        YoutubeMusicAccountManager(
            store = AndroidKeystoreYoutubeMusicStore(appContext),
            verifier = YoutubeMusicWebTierVerifier(appContext),
            onPlatformCookiesCleared = ::clearAndroidWebViewYoutubeSession,
        ).apply { initialize() }
    }
    fun previewPlayback(): PreviewPlayback = PreviewRemote(appContext)
    fun desktopRemoteApi(): DesktopRemoteApi = DesktopRemoteHttpsApi()
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
        override fun createTempDownloadFile(): File {
            desktopRemoteDownloadStagingDir.mkdirs()
            return File(desktopRemoteDownloadStagingDir, "remote-${UUID.randomUUID()}.part")
        }
        override suspend fun importDownloadedAudio(
            stagedFile: File,
            requestedName: String,
            expectedSha256: String,
        ): DesktopRemoteMediaImportOutcome {
            val outcome = library.importValidatedUpload(stagedFile, requestedName, expectedSha256, playlistName = null)
            return if (outcome is UploadImportOutcome.Deduplicated) {
                DesktopRemoteMediaImportOutcome.Deduplicated
            } else {
                DesktopRemoteMediaImportOutcome.Saved
            }
        }
    }
    val remoteServerController: AndroidRemoteServerController by lazy {
        AndroidRemoteServerController(appContext, library)
    }
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
            container.ytMusicAccount,
        ) as T
    }
}
