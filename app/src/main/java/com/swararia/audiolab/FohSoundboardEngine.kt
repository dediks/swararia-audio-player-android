package com.swararia.audiolab

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class PadKey(val bankIndex: Int, val slotIndex: Int)

internal data class SoundboardUiState(
    val selectedBank: Int = 0,
    val slots: List<SoundPadSlot> = SoundboardRules.defaultSlots(),
    val activeSlots: Set<PadKey> = emptySet(),
    val loadingSlots: Set<PadKey> = emptySet(),
    val padDuckEnabled: Boolean = true,
)

/**
 * Dual-path low-latency soundboard engine supporting overlapping one-shot stingers.
 *
 * Short stingers (`<= 5.5s`) load into `SoundPool` for sub-20ms hardware-mixed response, while
 * longer bumpers pre-prepare on dedicated `MediaPlayer` instances so SoundPool's 1 MB decoded PCM
 * cap never truncates them.
 */
class FohSoundboardEngine(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(MAX_CONCURRENT_STREAMS)
        .setAudioAttributes(audioAttrs)
        .build()

    private sealed interface LoadedPadBackend {
        data class Pool(val sampleId: Int, val ready: Boolean, val durationMs: Long) : LoadedPadBackend
        data class LongPlayer(val player: MediaPlayer, val ready: Boolean) : LoadedPadBackend
    }

    private data class BoundPad(
        val trackId: Long,
        val uriString: String,
        val trimDb: Float,
        val backend: LoadedPadBackend,
    )

    private val boundPads = HashMap<PadKey, BoundPad>()
    private val poolSampleToKey = HashMap<Int, PadKey>()
    private val activePoolStreams = HashMap<PadKey, Int>()
    private val activeTimers = HashMap<PadKey, Job>()
    private val slotGenerations = HashMap<PadKey, Long>()
    private var tracksById: Map<Long, AudioTrack> = emptyMap()
    private var duckListener: ((Boolean) -> Unit)? = null
    private var lastReportedDuck = false

    private val mutableState = MutableStateFlow(
        SoundboardUiState(
            selectedBank = prefs.getInt(KEY_SELECTED_BANK, 0).coerceIn(0, SoundboardRules.BANK_COUNT - 1),
            slots = SoundboardRules.decodeSlotsJson(prefs.getString(KEY_SLOTS_JSON, null)),
            padDuckEnabled = prefs.getBoolean(KEY_PAD_DUCK_ENABLED, true),
        ),
    )
    internal val state: StateFlow<SoundboardUiState> = mutableState.asStateFlow()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            scope.launch {
                val key = poolSampleToKey[sampleId] ?: return@launch
                val existing = boundPads[key] ?: return@launch
                val backend = existing.backend
                if (backend is LoadedPadBackend.Pool && backend.sampleId == sampleId) {
                    if (status == 0) {
                        boundPads[key] = existing.copy(backend = backend.copy(ready = true))
                        mutableState.update { it.copy(loadingSlots = it.loadingSlots - key) }
                    } else {
                        // Fall back cleanly to MediaPlayer if OEM codec fails in SoundPool.
                        soundPool.unload(sampleId)
                        poolSampleToKey.remove(sampleId)
                        val gen = slotGenerations[key] ?: 0L
                        prepareLongPlayerFallback(key, gen, existing.trackId, existing.uriString, existing.trimDb)
                    }
                }
            }
        }
    }

    fun setAutoDuckListener(listener: ((Boolean) -> Unit)?) {
        duckListener = listener
        reportDuckIfNeeded()
    }

    fun syncTracks(tracks: List<AudioTrack>) {
        tracksById = tracks.associateBy { it.id }
        val currentSlots = mutableState.value.slots
        var changed = false
        val cleaned = currentSlots.map { slot ->
            if (slot.isAssigned && !tracksById.containsKey(slot.trackId)) {
                changed = true
                SoundPadSlot(bankIndex = slot.bankIndex, slotIndex = slot.slotIndex)
            } else slot
        }
        if (changed) {
            persistSlots(cleaned)
        }
        prewarmAssignedSlots(if (changed) cleaned else currentSlots)
    }

    fun selectBank(bankIndex: Int) {
        val clamped = bankIndex.coerceIn(0, SoundboardRules.BANK_COUNT - 1)
        prefs.edit().putInt(KEY_SELECTED_BANK, clamped).apply()
        mutableState.update { it.copy(selectedBank = clamped) }
    }

    fun setPadDuckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PAD_DUCK_ENABLED, enabled).apply()
        mutableState.update { it.copy(padDuckEnabled = enabled) }
        reportDuckIfNeeded()
    }

    fun assignSlot(bankIndex: Int, slotIndex: Int, track: AudioTrack) {
        if (bankIndex !in 0 until SoundboardRules.BANK_COUNT || slotIndex !in 0 until SoundboardRules.SLOTS_PER_BANK) return
        val key = PadKey(bankIndex, slotIndex)
        stopSinglePad(key)
        releaseBoundPad(key)
        val updated = SoundPadSlot(
            bankIndex = bankIndex,
            slotIndex = slotIndex,
            trackId = track.id,
            label = SoundboardRules.defaultPadLabel(track.displayName),
            trimDb = 0f,
        )
        replaceSlot(updated)
        prewarmSlot(updated)
    }

    fun updateSlot(bankIndex: Int, slotIndex: Int, label: String, trimDb: Float) {
        val idx = bankIndex * SoundboardRules.SLOTS_PER_BANK + slotIndex
        val existing = mutableState.value.slots.getOrNull(idx) ?: return
        if (!existing.isAssigned) return
        val next = existing.copy(
            label = label.trim().ifBlank { existing.label }.take(28),
            trimDb = SoundboardRules.clampTrimDb(trimDb),
        )
        replaceSlot(next)
        boundPads[PadKey(bankIndex, slotIndex)]?.let { bound ->
            val updatedBound = bound.copy(trimDb = next.trimDb)
            boundPads[PadKey(bankIndex, slotIndex)] = updatedBound
            val linear = SoundboardRules.trimDbToLinear(next.trimDb)
            when (val backend = updatedBound.backend) {
                is LoadedPadBackend.Pool -> activePoolStreams[PadKey(bankIndex, slotIndex)]?.let {
                    soundPool.setVolume(it, linear, linear)
                }
                is LoadedPadBackend.LongPlayer -> runCatching { backend.player.setVolume(linear, linear) }
            }
        }
    }

    fun clearSlot(bankIndex: Int, slotIndex: Int) {
        val key = PadKey(bankIndex, slotIndex)
        stopSinglePad(key)
        releaseBoundPad(key)
        replaceSlot(SoundPadSlot(bankIndex = bankIndex, slotIndex = slotIndex))
        reportDuckIfNeeded()
    }

    fun triggerPad(bankIndex: Int, slotIndex: Int) {
        val key = PadKey(bankIndex, slotIndex)
        if (key in mutableState.value.activeSlots) {
            stopSinglePad(key)
            reportDuckIfNeeded()
            return
        }
        val idx = bankIndex * SoundboardRules.SLOTS_PER_BANK + slotIndex
        val slot = mutableState.value.slots.getOrNull(idx) ?: return
        val trackId = slot.trackId ?: return
        val track = tracksById[trackId] ?: return
        val bound = boundPads[key]
        val linear = SoundboardRules.trimDbToLinear(slot.trimDb)

        if (bound == null) {
            prewarmSlot(slot, playWhenReady = true)
            return
        }

        when (val backend = bound.backend) {
            is LoadedPadBackend.Pool -> {
                if (!backend.ready) return
                val streamId = soundPool.play(backend.sampleId, linear, linear, 1, 0, 1f)
                if (streamId != 0) {
                    activePoolStreams[key] = streamId
                    markPadActive(key)
                    activeTimers[key]?.cancel()
                    activeTimers[key] = scope.launch {
                        delay(backend.durationMs.coerceIn(250L, SoundboardRules.SOUNDPOOL_MAX_DURATION_MS) + 90L)
                        activePoolStreams.remove(key)
                        activeTimers.remove(key)
                        markPadInactive(key)
                    }
                }
            }
            is LoadedPadBackend.LongPlayer -> {
                if (!backend.ready) {
                    prewarmSlot(slot, playWhenReady = true)
                    return
                }
                runCatching {
                    backend.player.setVolume(linear, linear)
                    backend.player.seekTo(0)
                    backend.player.start()
                    markPadActive(key)
                }.onFailure {
                    releaseBoundPad(key)
                    prewarmSlot(slot, playWhenReady = true)
                }
            }
        }
    }

    fun stopAll() {
        val active = mutableState.value.activeSlots.toList()
        active.forEach { stopSinglePad(it) }
        reportDuckIfNeeded()
    }

    fun release() {
        stopAll()
        boundPads.keys.toList().forEach { releaseBoundPad(it) }
        runCatching { soundPool.release() }
    }

    private fun replaceSlot(slot: SoundPadSlot) {
        val list = mutableState.value.slots.toMutableList()
        val idx = slot.bankIndex * SoundboardRules.SLOTS_PER_BANK + slot.slotIndex
        if (idx !in list.indices) return
        list[idx] = slot
        persistSlots(list)
    }

    private fun persistSlots(list: List<SoundPadSlot>) {
        prefs.edit().putString(KEY_SLOTS_JSON, SoundboardRules.encodeSlotsJson(list)).apply()
        mutableState.update { it.copy(slots = list) }
    }

    private fun prewarmAssignedSlots(slots: List<SoundPadSlot>) {
        val assignedKeys = HashSet<PadKey>()
        for (slot in slots) {
            if (!slot.isAssigned) continue
            val key = PadKey(slot.bankIndex, slot.slotIndex)
            assignedKeys.add(key)
            val track = tracksById[slot.trackId] ?: continue
            val existing = boundPads[key]
            if (existing == null || existing.trackId != track.id || existing.uriString != track.uri) {
                releaseBoundPad(key)
                prewarmSlot(slot)
            }
        }
        boundPads.keys.toList().filterNot { it in assignedKeys }.forEach { releaseBoundPad(it) }
    }

    private fun prewarmSlot(slot: SoundPadSlot, playWhenReady: Boolean = false) {
        val track = slot.trackId?.let { tracksById[it] } ?: return
        val key = PadKey(slot.bankIndex, slot.slotIndex)
        val gen = (slotGenerations[key] ?: 0L) + 1L
        slotGenerations[key] = gen
        mutableState.update { it.copy(loadingSlots = it.loadingSlots + key) }

        if (SoundboardRules.shouldUseSoundPool(track.durationMs) && !playWhenReady) {
            scope.launch {
                val sampleId = withContext(Dispatchers.IO) {
                    runCatching {
                        appContext.contentResolver.openAssetFileDescriptor(Uri.parse(track.uri), "r")?.use { afd ->
                            soundPool.load(afd, 1)
                        } ?: 0
                    }.getOrDefault(0)
                }
                if (slotGenerations[key] != gen) {
                    if (sampleId != 0) runCatching { soundPool.unload(sampleId) }
                    return@launch
                }
                if (sampleId != 0) {
                    poolSampleToKey[sampleId] = key
                    boundPads[key] = BoundPad(
                        trackId = track.id,
                        uriString = track.uri,
                        trimDb = slot.trimDb,
                        backend = LoadedPadBackend.Pool(
                            sampleId = sampleId,
                            ready = false,
                            durationMs = track.durationMs ?: 2_000L,
                        ),
                    )
                } else {
                    prepareLongPlayerFallback(key, gen, track.id, track.uri, slot.trimDb)
                }
            }
        } else {
            prepareLongPlayerFallback(key, gen, track.id, track.uri, slot.trimDb, autoStart = playWhenReady)
        }
    }

    private fun prepareLongPlayerFallback(
        key: PadKey,
        gen: Long,
        trackId: Long,
        uriString: String,
        trimDb: Float,
        autoStart: Boolean = false,
    ) {
        if (slotGenerations[key] != gen) return
        val mp = MediaPlayer()
        runCatching {
            mp.setAudioAttributes(audioAttrs)
            mp.setDataSource(appContext, Uri.parse(uriString))
            val bound = BoundPad(trackId, uriString, trimDb, LoadedPadBackend.LongPlayer(mp, ready = false))
            boundPads[key] = bound
            mp.setOnPreparedListener { readyPlayer ->
                val cur = boundPads[key]
                val backend = cur?.backend
                if (slotGenerations[key] == gen && backend is LoadedPadBackend.LongPlayer && backend.player === readyPlayer) {
                    boundPads[key] = cur.copy(backend = backend.copy(ready = true))
                    mutableState.update { it.copy(loadingSlots = it.loadingSlots - key) }
                    val linear = SoundboardRules.trimDbToLinear(cur.trimDb)
                    readyPlayer.setVolume(linear, linear)
                    if (autoStart) {
                        runCatching {
                            readyPlayer.start()
                            markPadActive(key)
                        }
                    }
                } else {
                    runCatching { readyPlayer.release() }
                }
            }
            mp.setOnCompletionListener {
                markPadInactive(key)
            }
            mp.setOnErrorListener { _, _, _ ->
                mutableState.update { it.copy(loadingSlots = it.loadingSlots - key) }
                markPadInactive(key)
                true
            }
            mp.prepareAsync()
        }.onFailure {
            runCatching { mp.release() }
            boundPads.remove(key)
            mutableState.update { it.copy(loadingSlots = it.loadingSlots - key) }
        }
    }

    private fun stopSinglePad(key: PadKey) {
        activeTimers.remove(key)?.cancel()
        activePoolStreams.remove(key)?.let { streamId ->
            runCatching { soundPool.stop(streamId) }
        }
        val backend = boundPads[key]?.backend
        if (backend is LoadedPadBackend.LongPlayer && backend.ready) {
            runCatching {
                if (backend.player.isPlaying) {
                    backend.player.pause()
                    backend.player.seekTo(0)
                }
            }
        }
        mutableState.update { it.copy(activeSlots = it.activeSlots - key) }
    }

    private fun releaseBoundPad(key: PadKey) {
        val removed = boundPads.remove(key) ?: return
        when (val backend = removed.backend) {
            is LoadedPadBackend.Pool -> {
                poolSampleToKey.remove(backend.sampleId)
                runCatching { soundPool.unload(backend.sampleId) }
            }
            is LoadedPadBackend.LongPlayer -> runCatching { backend.player.release() }
        }
        mutableState.update { it.copy(loadingSlots = it.loadingSlots - key) }
    }

    private fun markPadActive(key: PadKey) {
        mutableState.update { it.copy(activeSlots = it.activeSlots + key) }
        reportDuckIfNeeded()
    }

    private fun markPadInactive(key: PadKey) {
        mutableState.update { it.copy(activeSlots = it.activeSlots - key) }
        reportDuckIfNeeded()
    }

    private fun reportDuckIfNeeded() {
        val cur = mutableState.value
        val shouldDuck = cur.padDuckEnabled && cur.activeSlots.isNotEmpty()
        if (shouldDuck != lastReportedDuck) {
            lastReportedDuck = shouldDuck
            duckListener?.invoke(shouldDuck)
        }
    }

    companion object {
        private const val PREFS_NAME = "foh_soundboard"
        private const val KEY_SELECTED_BANK = "selected_bank"
        private const val KEY_SLOTS_JSON = "pad_slots_json"
        private const val KEY_PAD_DUCK_ENABLED = "pad_duck_enabled"
        private const val MAX_CONCURRENT_STREAMS = 6
    }
}
