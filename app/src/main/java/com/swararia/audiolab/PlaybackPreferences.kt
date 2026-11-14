package com.swararia.audiolab

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Persists global playback fade settings inside the same store used by PlaybackService. */
class PlaybackPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(readState(prefs))
    val state: StateFlow<PlaybackSettingsState> = mutableState.asStateFlow()

    fun current(): PlaybackSettingsState = readState(prefs)

    fun setFadeInMs(durationMs: Long) {
        val clamped = FadeRules.clampDurationMs(durationMs)
        prefs.edit().putLong(KEY_FADE_IN_MS, clamped).apply()
        mutableState.update { it.copy(fadeInMs = clamped) }
    }

    fun setFadeOutMs(durationMs: Long) {
        val clamped = FadeRules.clampDurationMs(durationMs)
        prefs.edit().putLong(KEY_FADE_OUT_MS, clamped).apply()
        mutableState.update { it.copy(fadeOutMs = clamped) }
    }

    companion object {
        const val PREFS_NAME = "native_playback"
        const val KEY_FADE_IN_MS = "playback_fade_in_ms"
        const val KEY_FADE_OUT_MS = "playback_fade_out_ms"

        fun readState(prefs: SharedPreferences): PlaybackSettingsState =
            PlaybackSettingsState(
                fadeInMs = FadeRules.clampDurationMs(prefs.getLong(KEY_FADE_IN_MS, 0L)),
                fadeOutMs = FadeRules.clampDurationMs(prefs.getLong(KEY_FADE_OUT_MS, 0L)),
            )
    }
}
