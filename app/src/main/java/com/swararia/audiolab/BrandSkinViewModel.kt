package com.swararia.audiolab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BrandSkinStudioUiState(
    val importingLogo: Boolean = false,
    val importError: String? = null,
)

data class BrandSkinUiActions(
    val onSelectPreset: (String) -> Unit = {},
    val onSelectLogoAccent: () -> Unit = {},
    val onSetOledTrueBlack: (Boolean) -> Unit = {},
    val onSetRentalName: (String) -> Unit = {},
    val onSetEventTag: (String) -> Unit = {},
    val onSetStageRtaStyle: (StageRtaStyle) -> Unit = {},
    val onSetHaloChannelMode: (HaloChannelMode) -> Unit = {},
    val onSetCleanStageMode: (Boolean) -> Unit = {},
    val onSetPrestigeAnimations: (Boolean) -> Unit = {},
    val onDismissWelcomeBanner: () -> Unit = {},
    val onResetAllBranding: () -> Unit = {},
    val onImportLogoUri: (String) -> Unit = {},
    val onClearImportError: () -> Unit = {},
)

/**
 * Coordinates rental branding/skin preferences and asynchronous logo image imports
 * independently of individual Compose screens.
 */
internal class BrandSkinViewModel(
    private val store: BrandSkinStore,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    val state: StateFlow<BrandSkinConfig> = store.state

    private val activeScope: CoroutineScope
        get() = scopeOverride ?: viewModelScope

    private val mutableStudioState = MutableStateFlow(BrandSkinStudioUiState())
    val studioState: StateFlow<BrandSkinStudioUiState> = mutableStudioState.asStateFlow()

    val actions = BrandSkinUiActions(
        onSelectPreset = store::selectPreset,
        onSelectLogoAccent = store::selectCustomLogoAccent,
        onSetOledTrueBlack = store::setOledTrueBlack,
        onSetRentalName = store::setRentalName,
        onSetEventTag = store::setEventTag,
        onSetStageRtaStyle = store::setStageRtaStyle,
        onSetHaloChannelMode = store::setHaloChannelMode,
        onSetCleanStageMode = store::setCleanStageMode,
        onSetPrestigeAnimations = store::setPrestigeAnimations,
        onDismissWelcomeBanner = store::dismissWelcomeBanner,
        onResetAllBranding = ::resetAllBranding,
        onImportLogoUri = ::importLogoUri,
        onClearImportError = ::clearImportError,
    )

    fun importLogoUri(uriString: String) {
        if (mutableStudioState.value.importingLogo) return
        mutableStudioState.update { it.copy(importingLogo = true, importError = null) }
        activeScope.launch {
            try {
                store.importLogoFromUriString(uriString)
                    .onFailure { err ->
                        mutableStudioState.update {
                            it.copy(importError = err.message ?: "Logo gagal diproses.")
                        }
                    }
            } catch (cancel: CancellationException) {
                throw cancel
            } finally {
                mutableStudioState.update { it.copy(importingLogo = false) }
            }
        }
    }

    fun resetAllBranding() {
        mutableStudioState.update { it.copy(importError = null) }
        store.resetAllBranding()
    }

    fun clearImportError() {
        mutableStudioState.update { it.copy(importError = null) }
    }

    override fun onCleared() {
        scopeOverride?.cancel()
        super.onCleared()
    }
}

internal class BrandSkinViewModelFactory(
    private val container: AppContainer,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == BrandSkinViewModel::class.java) {
            "Unsupported ViewModel: ${modelClass.name}"
        }
        @Suppress("UNCHECKED_CAST")
        return BrandSkinViewModel(container.brandSkinManager) as T
    }
}
