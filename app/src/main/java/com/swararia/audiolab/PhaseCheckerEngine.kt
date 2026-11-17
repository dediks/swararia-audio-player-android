package com.swararia.audiolab

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal data class PhaseCheckerUiState(
    val mode: PhaseTestSignalMode = PhaseTestSignalMode.PULSE_FULL_RANGE,
    val generatorRunning: Boolean = false,
    val generatorGain: Float = PhaseCheckerRules.DEFAULT_GENERATOR_GAIN,
    val pulseTick: Long = 0L,
    val micListening: Boolean = false,
    val micInverted: Boolean = false,
    val micSourceLabel: String = "",
    val sensitivity: PhaseMicSensitivity = PhaseMicSensitivity.NORMAL,
    val referencePolarity: PolarityVerdict? = null,
    val heldRawVerdict: PolarityVerdict = PolarityVerdict.LOW_SIGNAL,
    val heldEffectiveVerdict: PolarityVerdict = PolarityVerdict.LOW_SIGNAL,
    val confidence: Float = 0f,
    val asymmetry: Float = 0f,
    val peakDbfs: Float = -96f,
    val snrRatio: Float = 0f,
    val waveformPreview: List<Float> = List(PhaseCheckerRules.PREVIEW_POINT_COUNT) { 0f },
    val pulseCount: Int = 0,
    val stackedPulses: Int = 0,
    val clipped: Boolean = false,
    val checkEntries: List<SpeakerPhaseCheckEntry> = emptyList(),
    val outputRoute: AudioOutputRoute = AudioOutputRoute(
        kind = AudioOutputKind.SPEAKER,
        label = "Output: Phone Speaker",
        detail = "Memeriksa rute output audio...",
    ),
    val errorMessage: String? = null,
)

/**
 * Coordinates RX acoustic microphone analysis ([AudioRecord]), A/B speaker checklist records,
 * and delegates TX signal transmission to [PhaseTransmitterService].
 */
internal class PhaseCheckerEngine(context: Context) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var micJob: Job? = null
    private var lastValidPulseElapsedMs = 0L
    private var nextRecordId = 1L

    private val rxState = MutableStateFlow(
        PhaseCheckerUiState(outputRoute = resolveCurrentOutputRoute()),
    )

    val state: StateFlow<PhaseCheckerUiState> = combine(
        rxState,
        PhaseTransmitterService.state,
    ) { rx, tx ->
        rx.copy(
            mode = tx.mode,
            generatorRunning = tx.running,
            generatorGain = tx.gain,
            pulseTick = tx.pulseTick,
            errorMessage = rx.errorMessage ?: tx.errorMessage,
        )
    }.stateIn(scope, SharingStarted.Eagerly, rxState.value)

    @Volatile
    private var invertMicPolarity: Boolean = false

    @Volatile
    private var activeSensitivity: PhaseMicSensitivity = PhaseMicSensitivity.NORMAL

    @Volatile
    private var activeMode: PhaseTestSignalMode = PhaseTransmitterService.state.value.mode

    @Volatile
    private var stackResetRequested: Boolean = false

    fun refreshOutputRoute() {
        rxState.update { it.copy(outputRoute = resolveCurrentOutputRoute()) }
    }

    fun setMode(mode: PhaseTestSignalMode) {
        if (activeMode != mode) {
            activeMode = mode
            stackResetRequested = true
        }
        PhaseTransmitterService.updateParams(appContext, mode, state.value.generatorGain)
        rxState.update { it.copy(errorMessage = null) }
    }

    fun setGeneratorGain(gain: Float) {
        val clamped = gain.coerceIn(0.15f, 0.90f)
        PhaseTransmitterService.updateParams(appContext, state.value.mode, clamped)
    }

    fun setSensitivity(sensitivity: PhaseMicSensitivity) {
        activeSensitivity = sensitivity
        rxState.update { it.copy(sensitivity = sensitivity, errorMessage = null) }
    }

    fun toggleMicInverted() {
        val next = !invertMicPolarity
        invertMicPolarity = next
        rxState.update { current ->
            val flipped = PhaseCheckerRules.toggleHoldInversion(
                heldRawVerdict = current.heldRawVerdict,
                currentAsymmetry = current.asymmetry,
                currentWaveform = current.waveformPreview,
                nextMicInverted = next,
            )
            current.copy(
                micInverted = flipped.micInverted,
                heldEffectiveVerdict = flipped.heldEffectiveVerdict,
                asymmetry = flipped.asymmetry,
                waveformPreview = flipped.waveformPreview,
            )
        }
    }

    fun lockCurrentAsReference() {
        rxState.update { current ->
            val v = current.heldEffectiveVerdict
            if (v == PolarityVerdict.POSITIVE || v == PolarityVerdict.NEGATIVE) {
                current.copy(referencePolarity = v, errorMessage = null)
            } else {
                current.copy(errorMessage = "Belum ada pembacaan fasa (+/-) yang stabil untuk dikunci.")
            }
        }
    }

    fun clearReference() {
        rxState.update { it.copy(referencePolarity = null, errorMessage = null) }
    }

    fun recordCurrentSpeaker(speakerName: String) {
        rxState.update { current ->
            val v = current.heldEffectiveVerdict
            if (v != PolarityVerdict.POSITIVE && v != PolarityVerdict.NEGATIVE) {
                return@update current.copy(errorMessage = "Tunggu sinyal fasa (+/-) stabil sebelum menyimpan hasil.")
            }
            val comparison = PhaseCheckerRules.compareToReference(v, current.referencePolarity)
            val entry = SpeakerPhaseCheckEntry(
                id = nextRecordId++,
                speakerName = speakerName,
                verdict = v,
                comparison = comparison,
                confidence = current.confidence,
            )
            // Replace previous measurement for the same speaker slot so list stays concise.
            val filtered = current.checkEntries.filterNot { it.speakerName == speakerName }
            current.copy(
                checkEntries = listOf(entry) + filtered,
                errorMessage = null,
            )
        }
    }

    fun removeCheckEntry(id: Long) {
        rxState.update { current ->
            current.copy(checkEntries = current.checkEntries.filterNot { it.id == id })
        }
    }

    fun clearChecklist() {
        rxState.update { it.copy(checkEntries = emptyList()) }
    }

    fun clearError() {
        rxState.update { it.copy(errorMessage = null) }
        PhaseTransmitterService.clearError()
    }

    fun startGenerator() {
        refreshOutputRoute()
        PhaseTransmitterService.start(appContext, state.value.mode, state.value.generatorGain)
    }

    fun stopGenerator() {
        PhaseTransmitterService.stop(appContext)
    }

    fun shareCurrentSignalWav(): Result<Unit> =
        PhaseWavExporter.sharePhaseTestWav(
            context = appContext,
            mode = state.value.mode,
            gain = state.value.generatorGain,
        )

    suspend fun shareCurrentSignalWavAsync(): Result<Unit> =
        PhaseWavExporter.sharePhaseTestWavAsync(
            context = appContext,
            mode = state.value.mode,
            gain = state.value.generatorGain,
        )

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun startMicAnalysis() {
        if (micJob?.isActive == true) return
        if (!hasMicPermission()) {
            rxState.update {
                it.copy(
                    micListening = false,
                    errorMessage = "Izin mikrofon diperlukan untuk membaca fasa akustik speaker.",
                )
            }
            return
        }
        lastValidPulseElapsedMs = 0L
        stackResetRequested = true
        rxState.update {
            it.copy(
                micListening = true,
                heldRawVerdict = PolarityVerdict.LOW_SIGNAL,
                heldEffectiveVerdict = PolarityVerdict.LOW_SIGNAL,
                confidence = 0f,
                pulseCount = 0,
                stackedPulses = 0,
                clipped = false,
                errorMessage = null,
            )
        }
        micJob = scope.launch(Dispatchers.IO) {
            runMicLoop()
        }
    }

    fun stopMicAnalysis() {
        micJob?.cancel()
        micJob = null
        rxState.update {
            it.copy(
                micListening = false,
                micSourceLabel = "",
            )
        }
    }

    fun release(stopTransmitter: Boolean) {
        if (stopTransmitter) stopGenerator()
        stopMicAnalysis()
        scope.cancel()
    }

    private fun resolveCurrentOutputRoute(): AudioOutputRoute =
        audioManager.activeFohOutputRoute()

    @SuppressLint("MissingPermission")
    private fun runMicLoop() {
        val sampleRate = PhaseCheckerRules.SAMPLE_RATE
        val rawMinBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (rawMinBuf <= 0) {
            rxState.update {
                it.copy(
                    micListening = false,
                    micSourceLabel = "",
                    errorMessage = "Format mikrofon 48 kHz tidak didukung perangkat (kode $rawMinBuf).",
                )
            }
            return
        }
        val minBuf = rawMinBuf.coerceAtLeast(8192)
        val openResult = openMicRecord(sampleRate, maxOf(minBuf * 2, sampleRate / 2 * 2))
        if (openResult == null) {
            rxState.update {
                it.copy(
                    micListening = false,
                    micSourceLabel = "",
                    errorMessage = "Mikrofon perangkat tidak tersedia atau sedang dipakai aplikasi lain.",
                )
            }
            return
        }
        val (record, sourceLabel) = openResult
        rxState.update { it.copy(micSourceLabel = sourceLabel) }

        val chunkSamples = sampleRate / 10 // 100 ms
        val ringSamples = (sampleRate * 42) / 100 // 420 ms window (< 500 ms pulse period)
        val readChunk = ShortArray(chunkSamples)
        val ring = ShortArray(ringSamples)
        val snapshot = ShortArray(ringSamples)
        val normSnapshot = FloatArray(ringSamples)
        val pulseStacker = PhasePulseStacker()
        var ringWritePos = 0
        var totalCaptured = 0L
        var lastCountedGlobalSample = -sampleRate.toLong()
        var lastStackedGlobalSample = -sampleRate.toLong()
        var prevMode = activeMode

        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rxState.update {
                    it.copy(
                        micListening = false,
                        micSourceLabel = "",
                        errorMessage = "Mikrofon gagal mulai merekam. Tutup aplikasi lain yang memakai mikrofon.",
                    )
                }
                return
            }
            while (scope.isActive && micJob?.isActive == true) {
                val read = record.read(readChunk, 0, readChunk.size, AudioRecord.READ_BLOCKING)
                if (read < 0) {
                    rxState.update { it.copy(errorMessage = "Pembacaan mikrofon gagal (kode $read).") }
                    break
                }
                if (read == 0) continue

                for (i in 0 until read) {
                    ring[ringWritePos] = readChunk[i]
                    ringWritePos++
                    if (ringWritePos >= ringSamples) ringWritePos = 0
                }
                totalCaptured += read
                if (totalCaptured < ringSamples) continue

                val currentMode = state.value.mode
                if (stackResetRequested || currentMode != prevMode) {
                    stackResetRequested = false
                    prevMode = currentMode
                    pulseStacker.reset()
                    lastStackedGlobalSample = -sampleRate.toLong()
                }

                val tailCount = ringSamples - ringWritePos
                System.arraycopy(ring, ringWritePos, snapshot, 0, tailCount)
                System.arraycopy(ring, 0, snapshot, tailCount, ringWritePos)

                var sum = 0.0
                for (i in 0 until ringSamples) sum += snapshot[i]
                val mean = ((sum / ringSamples) / 32768.0).toFloat()
                for (i in 0 until ringSamples) {
                    normSnapshot[i] = snapshot[i] / 32768.0f - mean
                }

                val analysis = PhaseCheckerRules.analyzeFloatWindow(
                    samples = normSnapshot,
                    sampleRate = sampleRate,
                    micInverted = invertMicPolarity,
                    sensitivity = activeSensitivity,
                    mode = currentMode,
                    skipMeanSubtraction = true,
                )
                val nowMs = SystemClock.elapsedRealtime()
                val validPulse = analysis.effectiveVerdict == PolarityVerdict.POSITIVE ||
                    analysis.effectiveVerdict == PolarityVerdict.NEGATIVE

                if (validPulse && analysis.peakSampleIndex >= 0) {
                    val globalPeakSample = totalCaptured - (ringSamples - analysis.peakSampleIndex)
                    val isNewPulse = (globalPeakSample - lastCountedGlobalSample) >= (sampleRate / 5)
                    if (isNewPulse) {
                        lastCountedGlobalSample = globalPeakSample
                    }
                    lastValidPulseElapsedMs = nowMs

                    val (preMargin, postMargin) = pulseStacker.windowMargins(sampleRate, currentMode)
                    val anchorIdx = analysis.anchorSampleIndex
                    val canStackHere = anchorIdx in preMargin..(ringSamples - postMargin)
                    val globalAnchorSample = totalCaptured - (ringSamples - anchorIdx)
                    val isNewStackCandidate = canStackHere &&
                        (globalAnchorSample - lastStackedGlobalSample) >= (sampleRate / 4)

                    if (isNewStackCandidate) {
                        lastStackedGlobalSample = globalAnchorSample
                        val stacked = pulseStacker.ingestAndAnalyze(
                            normSamples = normSnapshot,
                            singleAnalysis = analysis,
                            sampleRate = sampleRate,
                            micInverted = invertMicPolarity,
                            sensitivity = activeSensitivity,
                            mode = currentMode,
                        )
                        val display = stacked.analysis
                        rxState.update { current ->
                            current.copy(
                                heldRawVerdict = display.rawVerdict,
                                heldEffectiveVerdict = display.effectiveVerdict,
                                confidence = display.confidence,
                                asymmetry = display.asymmetry,
                                peakDbfs = analysis.peakDbfs,
                                snrRatio = display.snrRatio,
                                waveformPreview = display.waveformPreview,
                                pulseCount = if (isNewPulse) current.pulseCount + 1 else current.pulseCount,
                                stackedPulses = stacked.stackedCount,
                                clipped = display.clipped,
                            )
                        }
                    } else {
                        val currentState = rxState.value
                        val flippedFromHeld = currentState.stackedPulses > 0 &&
                            analysis.rawVerdict != currentState.heldRawVerdict
                        if (flippedFromHeld) {
                            pulseStacker.reset()
                            lastStackedGlobalSample = -sampleRate.toLong()
                        }
                        rxState.update { current ->
                            if (current.stackedPulses > 0 && !flippedFromHeld) {
                                current.copy(
                                    peakDbfs = analysis.peakDbfs,
                                    snrRatio = maxOf(current.snrRatio, analysis.snrRatio),
                                    pulseCount = if (isNewPulse) current.pulseCount + 1 else current.pulseCount,
                                    clipped = current.clipped || analysis.clipped,
                                )
                            } else {
                                current.copy(
                                    heldRawVerdict = analysis.rawVerdict,
                                    heldEffectiveVerdict = analysis.effectiveVerdict,
                                    confidence = analysis.confidence,
                                    asymmetry = analysis.asymmetry,
                                    peakDbfs = analysis.peakDbfs,
                                    snrRatio = analysis.snrRatio,
                                    waveformPreview = analysis.waveformPreview,
                                    pulseCount = if (isNewPulse) current.pulseCount + 1 else current.pulseCount,
                                    stackedPulses = 0,
                                    clipped = analysis.clipped,
                                )
                            }
                        }
                    }
                } else {
                    val expired = lastValidPulseElapsedMs == 0L ||
                        (nowMs - lastValidPulseElapsedMs) > PhaseCheckerRules.HOLD_DURATION_MS
                    if (expired && pulseStacker.stackedCount > 0) {
                        pulseStacker.reset()
                    }
                    rxState.update { current ->
                        if (expired) {
                            current.copy(
                                heldRawVerdict = analysis.rawVerdict,
                                heldEffectiveVerdict = analysis.effectiveVerdict,
                                confidence = analysis.confidence,
                                asymmetry = analysis.asymmetry,
                                peakDbfs = analysis.peakDbfs,
                                snrRatio = analysis.snrRatio,
                                waveformPreview = analysis.waveformPreview,
                                stackedPulses = 0,
                                clipped = analysis.clipped,
                            )
                        } else {
                            current.copy(
                                peakDbfs = analysis.peakDbfs,
                                snrRatio = analysis.snrRatio,
                                clipped = analysis.clipped,
                            )
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            rxState.update {
                it.copy(
                    micListening = false,
                    errorMessage = "Izin mikrofon ditolak oleh sistem.",
                )
            }
        } catch (t: Throwable) {
            if (micJob?.isActive == true) {
                rxState.update {
                    it.copy(
                        micListening = false,
                        errorMessage = t.message ?: "Analisa mikrofon terhenti.",
                    )
                }
            }
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            rxState.update { it.copy(micListening = false, micSourceLabel = "") }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openMicRecord(sampleRate: Int, bufferBytes: Int): Pair<AudioRecord, String>? {
        val supportsRawUnprocessed = audioManager.getProperty(
            AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED,
        ) == "true"
        val unprocessedLabel = if (supportsRawUnprocessed) {
            "Mic Raw (Unprocessed)"
        } else {
            "Mic Unprocessed (Non-raw HAL)"
        }
        val candidates = listOf(
            MediaRecorder.AudioSource.UNPROCESSED to unprocessedLabel,
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "Mic Voice Recognition (Non-raw DSP)",
            MediaRecorder.AudioSource.MIC to "Mic Standard (Non-raw DSP)",
        )
        for ((source, label) in candidates) {
            val record = runCatching {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) {
                return record to label
            }
            runCatching { record.release() }
        }
        return null
    }
}

internal fun AudioManager.activeFohOutputRoute(): AudioOutputRoute {
    val devices = getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .map { it.type to it.productName?.toString() }
    return LocalFohRules.classifyOutputDevices(devices)
}
