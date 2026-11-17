package com.swararia.audiolab

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakePhaseCheckerController : PhaseCheckerController {
    private val mutableState = MutableStateFlow(PhaseCheckerUiState())
    override val state: StateFlow<PhaseCheckerUiState> = mutableState.asStateFlow()

    var transmitterRunning = false
    var stopMicCalls = 0
    var stopGeneratorCalls = 0
    var startGeneratorCalls = 0
    var releaseCalls = 0
    var lastReleaseStopTransmitter: Boolean? = null

    fun setReference(polarity: PolarityVerdict?) {
        mutableState.update { it.copy(referencePolarity = polarity) }
    }

    override fun isTransmitterRunning(): Boolean = transmitterRunning
    override fun refreshOutputRoute() = Unit
    override fun setMode(mode: PhaseTestSignalMode) {
        mutableState.update { it.copy(mode = mode) }
    }
    override fun setGeneratorGain(gain: Float) {
        mutableState.update { it.copy(generatorGain = gain) }
    }
    override fun setSensitivity(sensitivity: PhaseMicSensitivity) {
        mutableState.update { it.copy(sensitivity = sensitivity) }
    }
    override fun toggleMicInverted() {
        mutableState.update { it.copy(micInverted = !it.micInverted) }
    }
    override fun lockCurrentAsReference() {
        mutableState.update { it.copy(referencePolarity = PolarityVerdict.POSITIVE) }
    }
    override fun clearReference() {
        mutableState.update { it.copy(referencePolarity = null) }
    }
    override fun recordCurrentSpeaker(speakerName: String) {
        mutableState.update { current ->
            current.copy(
                checkEntries = current.checkEntries + SpeakerPhaseCheckEntry(
                    id = (current.checkEntries.size + 1).toLong(),
                    speakerName = speakerName,
                    verdict = PolarityVerdict.POSITIVE,
                    comparison = ReferenceComparison.NONE,
                    confidence = 0.9f,
                ),
            )
        }
    }
    override fun removeCheckEntry(id: Long) {
        mutableState.update { it.copy(checkEntries = it.checkEntries.filterNot { e -> e.id == id }) }
    }
    override fun clearChecklist() {
        mutableState.update { it.copy(checkEntries = emptyList()) }
    }
    override fun clearError() {
        mutableState.update { it.copy(errorMessage = null) }
    }
    override fun startGenerator() {
        startGeneratorCalls++
        mutableState.update { it.copy(generatorRunning = true) }
    }
    override fun stopGenerator() {
        stopGeneratorCalls++
        mutableState.update { it.copy(generatorRunning = false) }
    }
    override suspend fun shareCurrentSignalWavAsync(): Result<Unit> = Result.success(Unit)
    override fun hasMicPermission(): Boolean = true
    override fun startMicAnalysis() {
        mutableState.update { it.copy(micListening = true) }
    }
    override fun stopMicAnalysis() {
        stopMicCalls++
        mutableState.update { it.copy(micListening = false) }
    }
    override fun release(stopTransmitter: Boolean) {
        releaseCalls++
        lastReleaseStopTransmitter = stopTransmitter
    }
}

class PhaseCheckerViewModelTest {
    @Test
    fun `toggleReferenceLock locks when empty and clears when already locked`() {
        val fake = FakePhaseCheckerController()
        val vm = PhaseCheckerViewModel(fake)

        assertNull(vm.state.value.referencePolarity)
        vm.toggleReferenceLock()
        assertEquals(PolarityVerdict.POSITIVE, vm.state.value.referencePolarity)

        vm.toggleReferenceLock()
        assertNull(vm.state.value.referencePolarity)
    }

    @Test
    fun `screen dispose pauses hardware without releasing checklist or reference`() {
        val fake = FakePhaseCheckerController()
        val vm = PhaseCheckerViewModel(fake)
        vm.toggleReferenceLock()
        vm.recordCurrentSpeaker("FOH Kiri")

        vm.onScreenDisposed(preserveTxInBackground = false)

        assertEquals(1, fake.stopMicCalls)
        assertEquals(1, fake.stopGeneratorCalls)
        assertEquals(0, fake.releaseCalls)
        assertEquals(1, vm.state.value.checkEntries.size)
        assertEquals(PolarityVerdict.POSITIVE, vm.state.value.referencePolarity)
    }

    @Test
    fun `host stop and clear preserve two-phone transmitter when configured for background tx`() {
        val fake = FakePhaseCheckerController().apply { transmitterRunning = true }
        val vm = PhaseCheckerViewModel(fake)
        val store = ViewModelStore()
        store.put("phase", vm)

        assertTrue(vm.isTransmitterActiveAtEntry())
        vm.onHostStop(preserveTxInBackground = true)
        assertEquals(1, fake.stopMicCalls)
        assertEquals(0, fake.stopGeneratorCalls)

        store.clear()
        assertEquals(1, fake.releaseCalls)
        assertEquals(false, fake.lastReleaseStopTransmitter)
    }

    @Test
    fun `clearing ViewModel in single phone mode releases controller and stops transmitter`() {
        val fake = FakePhaseCheckerController()
        val vm = PhaseCheckerViewModel(fake)
        val store = ViewModelStore()
        store.put("phase", vm)

        vm.setPreserveTxInBackground(false)
        store.clear()

        assertEquals(1, fake.releaseCalls)
        assertEquals(true, fake.lastReleaseStopTransmitter)
        assertFalse(vm.isTransmitterActiveAtEntry())
    }
}
