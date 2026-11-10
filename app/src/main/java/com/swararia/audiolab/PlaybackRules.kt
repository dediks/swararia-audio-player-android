package com.swararia.audiolab

/** Names are the persisted values and the app-only session protocol values. */
enum class PlaybackMode(val label: String) {
    LOOP_TRACK("Loop track"), CONTINUE("Continue"), ONCE("Once");

    val repeatCurrent: Boolean get() = this == LOOP_TRACK
    val pauseAtItemEnd: Boolean get() = this == ONCE

    companion object {
        fun fromStored(value: String?): PlaybackMode =
            entries.firstOrNull { it.name == value } ?: CONTINUE
    }
}

object FadeRules {
    const val DURATION_MS = 3000L
    fun canStart(isPlaying: Boolean, busy: Boolean): Boolean = isPlaying && !busy
    fun volumeAt(original: Float, elapsedMs: Long): Float =
        original * (1f - elapsedMs.coerceIn(0L, DURATION_MS).toFloat() / DURATION_MS)
}
