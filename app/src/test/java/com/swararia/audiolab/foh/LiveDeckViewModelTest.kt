package com.swararia.audiolab.foh

import androidx.lifecycle.ViewModelStore
import com.swararia.audiolab.AudioTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeFohSafeModeStore : FohSafeModeStore {
    private val mutableSettings = MutableStateFlow(FohSafeModeSettings())
    override val settings: StateFlow<FohSafeModeSettings> = mutableSettings.asStateFlow()

    var policyGranted = false
    var refreshSyncCalls = 0
    var openDndCalls = 0
    var openSamsungCalls = 0

    override fun isNotificationPolicyGranted(): Boolean = policyGranted

    override fun updateSettings(transform: (FohSafeModeSettings) -> FohSafeModeSettings): FohSafeModeSettings {
        val next = transform(mutableSettings.value)
        mutableSettings.value = next
        return next
    }

    override fun setEnabled(enabled: Boolean): FohSafeModeSettings =
        updateSettings { it.copy(enabled = enabled) }

    override fun refreshPolicySync() {
        refreshSyncCalls++
    }

    override fun openNotificationPolicySettings() {
        openDndCalls++
    }

    override fun openSamsungSeparateAppSound() {
        openSamsungCalls++
    }
}

private class FakeFohSoundboardStore : FohSoundboardStore {
    private val mutableState = MutableStateFlow(SoundboardUiState())
    override val state: StateFlow<SoundboardUiState> = mutableState.asStateFlow()

    var listener: ((Boolean) -> Unit)? = null
    var syncTracksCalls = 0
    var stopAllCalls = 0
    var fillOnlyEmptyLast: Boolean? = null
    var lastTriggered: Pair<Int, Int>? = null
    var lastAssignedTrack: Triple<Int, Int, Long>? = null
    var lastAssignedPreset: Triple<Int, Int, String>? = null
    var lastUpdatedSlot: Triple<Int, Int, String>? = null
    var lastClearedSlot: Pair<Int, Int>? = null

    override fun setAutoDuckListener(listener: ((Boolean) -> Unit)?) {
        this.listener = listener
    }

    override fun syncTracks(tracks: List<AudioTrack>) {
        syncTracksCalls++
    }

    override fun selectBank(bankIndex: Int) {
        mutableState.update { it.copy(selectedBank = bankIndex) }
    }

    override fun setPadDuckEnabled(enabled: Boolean) {
        mutableState.update { it.copy(padDuckEnabled = enabled) }
    }

    override fun assignSlot(bankIndex: Int, slotIndex: Int, track: AudioTrack) {
        lastAssignedTrack = Triple(bankIndex, slotIndex, track.id)
    }

    override fun assignPreset(bankIndex: Int, slotIndex: Int, preset: SoundPadMomentPreset) {
        lastAssignedPreset = Triple(bankIndex, slotIndex, preset.id)
    }

    override fun fillMomentPresets(onlyEmpty: Boolean) {
        fillOnlyEmptyLast = onlyEmpty
    }

    override fun updateSlot(bankIndex: Int, slotIndex: Int, label: String, trimDb: Float) {
        lastUpdatedSlot = Triple(bankIndex, slotIndex, label)
    }

    override fun clearSlot(bankIndex: Int, slotIndex: Int) {
        lastClearedSlot = bankIndex to slotIndex
    }

    override fun triggerPad(bankIndex: Int, slotIndex: Int) {
        lastTriggered = bankIndex to slotIndex
    }

    override fun stopAll() {
        stopAllCalls++
    }
}

class LiveDeckViewModelTest {
    @Test
    fun `onHostResume refreshes DND permission state and syncs policy`() {
        val safeMode = FakeFohSafeModeStore()
        val soundboard = FakeFohSoundboardStore()
        val vm = LiveDeckViewModel(safeMode, soundboard)

        assertFalse(vm.dndGranted.value)
        safeMode.policyGranted = true
        vm.onHostResume()

        assertTrue(vm.dndGranted.value)
        assertEquals(1, safeMode.refreshSyncCalls)
    }

    @Test
    fun `safe mode toggles and OS settings actions delegate to store`() {
        val safeMode = FakeFohSafeModeStore()
        val soundboard = FakeFohSoundboardStore()
        val vm = LiveDeckViewModel(safeMode, soundboard)

        vm.setSafeModeEnabled(true)
        vm.setAutoDnd(false)
        vm.setFocusLock(false)
        vm.openNotificationPolicySettings()
        vm.openSamsungSeparateAppSound()

        assertEquals(FohSafeModeSettings(enabled = true, autoDnd = false, focusLock = false), vm.safeSettings.value)
        assertEquals(1, safeMode.openDndCalls)
        assertEquals(1, safeMode.openSamsungCalls)
    }

    @Test
    fun `soundboard actions delegate cleanly and onCleared detaches autoDuckListener`() {
        val safeMode = FakeFohSafeModeStore()
        val soundboard = FakeFohSoundboardStore()
        val vm = LiveDeckViewModel(safeMode, soundboard)
        val store = ViewModelStore()
        store.put("liveDeck", vm)

        val track = AudioTrack(id = 42L, uri = "content://track/42", displayName = "Intro.mp3", sizeBytes = 1000L, durationMs = 5000L)
        val preset = SoundboardRules.MOMENT_PRESETS.first()
        var duckedValue: Boolean? = null

        vm.setAutoDuckListener { duckedValue = it }
        soundboard.listener?.invoke(true)
        assertEquals(true, duckedValue)

        vm.syncTracks(listOf(track))
        vm.selectBank(1)
        vm.setPadDuckEnabled(false)
        vm.triggerPad(1, 2)
        vm.assignSlot(1, 2, track)
        vm.assignPreset(0, 1, preset)
        vm.updateSlot(1, 2, "New Label", -3f)
        vm.clearSlot(0, 1)
        vm.fillMomentPresets(onlyEmpty = true)
        vm.stopAllPads()

        assertEquals(1, soundboard.syncTracksCalls)
        assertEquals(1, vm.soundboardState.value.selectedBank)
        assertFalse(vm.soundboardState.value.padDuckEnabled)
        assertEquals(1 to 2, soundboard.lastTriggered)
        assertEquals(Triple(1, 2, 42L), soundboard.lastAssignedTrack)
        assertEquals(Triple(0, 1, preset.id), soundboard.lastAssignedPreset)
        assertEquals(Triple(1, 2, "New Label"), soundboard.lastUpdatedSlot)
        assertEquals(0 to 1, soundboard.lastClearedSlot)
        assertEquals(true, soundboard.fillOnlyEmptyLast)
        assertEquals(1, soundboard.stopAllCalls)

        store.clear()
        assertNull(soundboard.listener)
    }
}
