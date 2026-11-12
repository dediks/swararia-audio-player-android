package com.swararia.audiolab

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room

/** Manual composition root. Platform dependencies are built here, never inside a screen/ViewModel. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val ingestState = IngestStatus.state // Adapter to the existing foreground service; process-local, not a durable queue.
    val database: AudioDatabase by lazy {
        Room.databaseBuilder(appContext, AudioDatabase::class.java, "audio-library-spike.db")
            .addMigrations(AudioMigrations.MIGRATION_1_2).build()
    }
    val library: MediaLibrary by lazy { MediaLibrary(appContext, database) }
    val downloadQueue: DownloadQueueRepository by lazy { RoomDownloadQueueRepository(database) }
    val searchRepository: AudioSearchRepository by lazy {
        YoutubeSearchRepository(appContext, { ingestState.value.busy })
    }
    fun previewPlayback(): PreviewPlayback = PreviewRemote(appContext)
    fun desktopRemoteApi(): DesktopRemoteApi = DesktopRemoteHttpsApi()
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
        return AppViewModel(container.library, container.searchRepository, container.ingestState, container.downloadQueue, container.previewPlayback(), container.desktopRemoteApi()) as T
    }
}
