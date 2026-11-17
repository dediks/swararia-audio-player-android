package com.swararia.audiolab

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Screen-level ViewModel for [FocusedPlayer] (Live Deck).
 * Decouples FOH Safe Mode and low-latency Soundboard engines from Compose and Activity lookups.
 */
internal class LiveDeckViewModel(
    private val safeMode: FohSafeModeStore,
    private val soundboard: FohSoundboardStore,
) : ViewModel() {
    val safeSettings: StateFlow<FohSafeModeSettings> = safeMode.settings
    val soundboardState: StateFlow<SoundboardUiState> = soundboard.state

    private val mutableDndGranted = MutableStateFlow(safeMode.isNotificationPolicyGranted())
    val dndGranted: StateFlow<Boolean> = mutableDndGranted.asStateFlow()

    fun onHostResume() {
        mutableDndGranted.value = safeMode.isNotificationPolicyGranted()
        safeMode.refreshPolicySync()
    }

    fun syncTracks(tracks: List<AudioTrack>) {
        soundboard.syncTracks(tracks)
    }

    fun setAutoDuckListener(listener: ((Boolean) -> Unit)?) {
        soundboard.setAutoDuckListener(listener)
    }

    fun setSafeModeEnabled(enabled: Boolean) {
        safeMode.setEnabled(enabled)
    }

    fun setAutoDnd(enabled: Boolean) {
        safeMode.updateSettings { it.copy(autoDnd = enabled) }
    }

    fun setFocusLock(enabled: Boolean) {
        safeMode.updateSettings { it.copy(focusLock = enabled) }
    }

    fun openNotificationPolicySettings() {
        safeMode.openNotificationPolicySettings()
    }

    fun openSamsungSeparateAppSound() {
        safeMode.openSamsungSeparateAppSound()
    }

    fun selectBank(bankIndex: Int) {
        soundboard.selectBank(bankIndex)
    }

    fun triggerPad(bankIndex: Int, slotIndex: Int) {
        soundboard.triggerPad(bankIndex, slotIndex)
    }

    fun setPadDuckEnabled(enabled: Boolean) {
        soundboard.setPadDuckEnabled(enabled)
    }

    fun stopAllPads() {
        soundboard.stopAll()
    }

    fun fillMomentPresets(onlyEmpty: Boolean = false) {
        soundboard.fillMomentPresets(onlyEmpty = onlyEmpty)
    }

    fun assignSlot(bankIndex: Int, slotIndex: Int, track: AudioTrack) {
        soundboard.assignSlot(bankIndex, slotIndex, track)
    }

    fun assignPreset(bankIndex: Int, slotIndex: Int, preset: SoundPadMomentPreset) {
        soundboard.assignPreset(bankIndex, slotIndex, preset)
    }

    fun updateSlot(bankIndex: Int, slotIndex: Int, label: String, trimDb: Float) {
        soundboard.updateSlot(bankIndex, slotIndex, label, trimDb)
    }

    fun clearSlot(bankIndex: Int, slotIndex: Int) {
        soundboard.clearSlot(bankIndex, slotIndex)
    }

    override fun onCleared() {
        soundboard.setAutoDuckListener(null)
        super.onCleared()
    }
}

internal class LiveDeckViewModelFactory(
    private val createSafeMode: () -> FohSafeModeStore,
    private val createSoundboard: () -> FohSoundboardStore,
) : ViewModelProvider.Factory {
    constructor(container: AppContainer) : this(
        createSafeMode = { container.safeModeController },
        createSoundboard = { container.soundboardEngine },
    )

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == LiveDeckViewModel::class.java) {
            "Unsupported ViewModel: ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return LiveDeckViewModel(createSafeMode(), createSoundboard()) as T
    }

    companion object {
        fun fromContext(context: Context): LiveDeckViewModelFactory {
            val app = context.applicationContext
            val container = (app as? SwarariaApp)?.container
            return if (container != null) {
                LiveDeckViewModelFactory(container)
            } else {
                LiveDeckViewModelFactory(
                    createSafeMode = { FohSafeModeController(app) },
                    createSoundboard = { FohSoundboardEngine(app) },
                )
            }
        }
    }
}
