package com.swararia.audiolab

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeBrandSkinStore : BrandSkinStore {
    private val mutableState = MutableStateFlow(BrandSkinConfig())
    override val state: StateFlow<BrandSkinConfig> = mutableState.asStateFlow()

    var importCalls = 0
    var resetCalls = 0
    var importGate: CompletableDeferred<Result<BrandSkinConfig>>? = null
    var nextImportResult: Result<BrandSkinConfig> = Result.success(BrandSkinConfig(customLogoKey = "abc123"))

    override fun selectPreset(presetId: String) {
        mutableState.update { it.copy(presetId = presetId, useCustomLogoAccent = false) }
    }

    override fun selectCustomLogoAccent() {
        mutableState.update { it.copy(useCustomLogoAccent = true) }
    }

    override fun setOledTrueBlack(enabled: Boolean) {
        mutableState.update { it.copy(oledTrueBlack = enabled) }
    }

    override fun setRentalName(raw: String) {
        mutableState.update { it.copy(rentalName = raw) }
    }

    override fun setEventTag(raw: String) {
        mutableState.update { it.copy(eventTag = raw) }
    }

    override fun setStageRtaStyle(style: StageRtaStyle) {
        mutableState.update { it.copy(stageRtaStyle = style) }
    }

    override fun setHaloChannelMode(mode: HaloChannelMode) {
        mutableState.update { it.copy(haloChannelMode = mode) }
    }

    override fun setCleanStageMode(clean: Boolean) {
        mutableState.update { it.copy(cleanStageMode = clean) }
    }

    override fun setPrestigeAnimations(enabled: Boolean) {
        mutableState.update { it.copy(prestigeAnimations = enabled) }
    }

    override fun dismissWelcomeBanner() {
        mutableState.update { it.copy(welcomeBannerDismissed = true) }
    }

    override fun resetAllBranding() {
        resetCalls++
        mutableState.update { it.copy(rentalName = "", eventTag = "", customLogoKey = null) }
    }

    override suspend fun importLogoFromUriString(uriString: String): Result<BrandSkinConfig> {
        importCalls++
        val result = importGate?.await() ?: nextImportResult
        result.onSuccess { updated -> mutableState.value = updated }
        return result
    }
}

class BrandSkinViewModelTest {
    @Test
    fun `ui actions delegate brand and stage skin mutations to store`() {
        val store = FakeBrandSkinStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = BrandSkinViewModel(store, scope)
            vm.actions.onSelectPreset("royal_gold")
            vm.actions.onSetOledTrueBlack(true)
            vm.actions.onSetRentalName("SWARARIA PRO")
            vm.actions.onSetEventTag("STAGE A")
            vm.actions.onSetStageRtaStyle(StageRtaStyle.HALO)
            vm.actions.onSetHaloChannelMode(HaloChannelMode.STEREO_SPLIT)
            vm.actions.onSetCleanStageMode(true)
            vm.actions.onSetPrestigeAnimations(false)
            vm.actions.onDismissWelcomeBanner()

            val cfg = vm.state.value
            assertEquals("royal_gold", cfg.presetId)
            assertTrue(cfg.oledTrueBlack)
            assertEquals("SWARARIA PRO", cfg.rentalName)
            assertEquals("STAGE A", cfg.eventTag)
            assertEquals(StageRtaStyle.HALO, cfg.stageRtaStyle)
            assertEquals(HaloChannelMode.STEREO_SPLIT, cfg.haloChannelMode)
            assertTrue(cfg.cleanStageMode)
            assertFalse(cfg.prestigeAnimations)
            assertTrue(cfg.welcomeBannerDismissed)
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 5000)
    fun `importLogoUri ignores duplicate launches while in flight and clears errors on reset`() = runBlocking {
        val store = FakeBrandSkinStore()
        val gate = CompletableDeferred<Result<BrandSkinConfig>>()
        store.importGate = gate
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val vm = BrandSkinViewModel(store, scope)

            vm.actions.onImportLogoUri("content://test/logo1.png")
            assertTrue(vm.studioState.value.importingLogo)
            vm.actions.onImportLogoUri("content://test/logo2.png")
            assertEquals(1, store.importCalls)

            gate.complete(Result.failure(IllegalStateException("File terlalu besar")))
            assertFalse(vm.studioState.value.importingLogo)
            assertEquals("File terlalu besar", vm.studioState.value.importError)

            vm.actions.onResetAllBranding()
            assertEquals(1, store.resetCalls)
            assertNull(vm.studioState.value.importError)
        } finally {
            scope.cancel()
        }
    }
}
