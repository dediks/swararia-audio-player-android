package com.swararia.audiolab.phasechecker

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.swararia.audiolab.AppContainer
import com.swararia.audiolab.SwarariaApp
import kotlinx.coroutines.flow.StateFlow

/**
 * Screen-level ViewModel for [PhaseCheckerScreen].
 * Keeps phase measurements, locked reference polarity, and engine lifecycle out of Compose UI
 * so configuration changes and Hub navigation do not wipe active test sessions.
 */
internal class PhaseCheckerViewModel(
    private val controller: PhaseCheckerController,
) : ViewModel() {
    val state: StateFlow<PhaseCheckerUiState> = controller.state

    @Volatile
    private var preserveTxOnClear: Boolean = false

    fun isTransmitterActiveAtEntry(): Boolean = controller.isTransmitterRunning()

    fun setPreserveTxInBackground(preserve: Boolean) {
        preserveTxOnClear = preserve
    }

    fun refreshOutputRoute() {
        controller.refreshOutputRoute()
    }

    fun setMode(mode: PhaseTestSignalMode) {
        controller.setMode(mode)
    }

    fun setGeneratorGain(gain: Float) {
        controller.setGeneratorGain(gain)
    }

    fun setSensitivity(sensitivity: PhaseMicSensitivity) {
        controller.setSensitivity(sensitivity)
    }

    fun toggleMicInverted() {
        controller.toggleMicInverted()
    }

    fun toggleReferenceLock() {
        if (state.value.referencePolarity != null) {
            controller.clearReference()
        } else {
            controller.lockCurrentAsReference()
        }
    }

    fun recordCurrentSpeaker(speakerName: String) {
        controller.recordCurrentSpeaker(speakerName)
    }

    fun removeCheckEntry(id: Long) {
        controller.removeCheckEntry(id)
    }

    fun clearChecklist() {
        controller.clearChecklist()
    }

    fun clearError() {
        controller.clearError()
    }

    fun hasMicPermission(): Boolean = controller.hasMicPermission()

    fun startMicAnalysis() {
        controller.startMicAnalysis()
    }

    fun stopMicAnalysis() {
        controller.stopMicAnalysis()
    }

    fun startGenerator() {
        controller.startGenerator()
    }

    fun stopGenerator() {
        controller.stopGenerator()
    }

    suspend fun shareCurrentSignalWavAsync(): Result<Unit> = controller.shareCurrentSignalWavAsync()

    fun onHostStop(preserveTxInBackground: Boolean) {
        preserveTxOnClear = preserveTxInBackground
        controller.stopMicAnalysis()
        if (!preserveTxInBackground) {
            controller.stopGenerator()
        }
    }

    fun onScreenDisposed(preserveTxInBackground: Boolean) {
        preserveTxOnClear = preserveTxInBackground
        controller.stopMicAnalysis()
        if (!preserveTxInBackground) {
            controller.stopGenerator()
        }
    }

    override fun onCleared() {
        controller.release(stopTransmitter = !preserveTxOnClear)
        super.onCleared()
    }
}

internal class PhaseCheckerViewModelFactory(
    private val createController: () -> PhaseCheckerController,
) : ViewModelProvider.Factory {
    constructor(container: AppContainer) : this(container::createPhaseCheckerEngine)

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == PhaseCheckerViewModel::class.java) {
            "Unsupported ViewModel: ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return PhaseCheckerViewModel(createController()) as T
    }

    companion object {
        fun fromContext(context: Context): PhaseCheckerViewModelFactory {
            val app = context.applicationContext
            val container = (app as? SwarariaApp)?.container
            return if (container != null) {
                PhaseCheckerViewModelFactory(container)
            } else {
                PhaseCheckerViewModelFactory { PhaseCheckerEngine(app) }
            }
        }
    }
}
