package com.swararia.audiolab.phasechecker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.swararia.audiolab.MainActivity
import com.swararia.audiolab.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

internal data class PhaseTxState(
    val running: Boolean = false,
    val mode: PhaseTestSignalMode = PhaseTestSignalMode.PULSE_FULL_RANGE,
    val gain: Float = PhaseCheckerRules.DEFAULT_GENERATOR_GAIN,
    val pulseTick: Long = 0L,
    val errorMessage: String? = null,
)

/**
 * Foreground mediaPlayback service owning the phase test signal [AudioTrack].
 * Running TX in a dedicated foreground service ensures Mode 2 HP ("HP 1 di Mixer")
 * keeps pulsing steadily even if the phone screen locks.
 */
class PhaseTransmitterService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val txGeneration = AtomicLong(0L)
    private var txJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var audioFocusRequest: android.media.AudioFocusRequest? = null

    @Volatile
    private var currentMode: PhaseTestSignalMode = PhaseTestSignalMode.PULSE_FULL_RANGE

    @Volatile
    private var currentGain: Float = PhaseCheckerRules.DEFAULT_GENERATOR_GAIN

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Pemancar Uji Fasa Speaker", NotificationManager.IMPORTANCE_LOW),
        )
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Swararia:PhaseTransmitter").apply {
            setReferenceCounted(false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTransmission()
                return START_NOT_STICKY
            }

            ACTION_UPDATE -> {
                val nextMode = parseMode(intent.getStringExtra(EXTRA_MODE)) ?: currentMode
                val nextGain = intent.getFloatExtra(EXTRA_GAIN, currentGain).coerceIn(0.15f, 0.90f)
                currentMode = nextMode
                currentGain = nextGain
                mutableState.update { it.copy(mode = nextMode, gain = nextGain) }
                if (txJob?.isActive == true) {
                    getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(nextMode))
                }
                return START_NOT_STICKY
            }

            else -> {
                val mode = parseMode(intent?.getStringExtra(EXTRA_MODE)) ?: PhaseTestSignalMode.PULSE_FULL_RANGE
                val gain = (intent?.getFloatExtra(EXTRA_GAIN, PhaseCheckerRules.DEFAULT_GENERATOR_GAIN)
                    ?: PhaseCheckerRules.DEFAULT_GENERATOR_GAIN).coerceIn(0.15f, 0.90f)
                currentMode = mode
                currentGain = gain
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    buildNotification(mode),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
                startTransmissionLoop()
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopTransmissionResources()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startTransmissionLoop() {
        if (txJob?.isActive == true) {
            mutableState.update { it.copy(running = true, mode = currentMode, gain = currentGain, errorMessage = null) }
            return
        }
        val generation = txGeneration.incrementAndGet()
        runCatching { wakeLock?.acquire(MAX_WAKELOCK_MS) }
        mutableState.update {
            it.copy(
                running = true,
                mode = currentMode,
                gain = currentGain,
                pulseTick = 0L,
                errorMessage = null,
            )
        }
        txJob = serviceScope.launch {
            runAudioTrackLoop(generation)
        }
    }

    private fun stopTransmission() {
        txGeneration.incrementAndGet()
        stopTransmissionResources()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopTransmissionResources() {
        txJob?.cancel()
        txJob = null
        abandonAudioFocus()
        runCatching {
            val wl = wakeLock
            if (wl != null && wl.isHeld) wl.release()
        }
        mutableState.update { it.copy(running = false) }
    }

    private fun requestTxAudioFocus() {
        val am = getSystemService(android.media.AudioManager::class.java) ?: return
        val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change == android.media.AudioManager.AUDIOFOCUS_LOSS ||
                    change == android.media.AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                ) {
                    stopTransmission()
                }
            }, android.os.Handler(android.os.Looper.getMainLooper()))
            .build()
        val granted = runCatching { am.requestAudioFocus(req) }.getOrNull()
        if (granted == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            audioFocusRequest = req
        }
    }

    private fun abandonAudioFocus() {
        val req = audioFocusRequest ?: return
        audioFocusRequest = null
        runCatching { getSystemService(android.media.AudioManager::class.java)?.abandonAudioFocusRequest(req) }
    }

    private fun runAudioTrackLoop(generation: Long) {
        val sampleRate = PhaseCheckerRules.SAMPLE_RATE
        val rawMinBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (rawMinBuf <= 0) {
            mutableState.update {
                it.copy(
                    running = false,
                    errorMessage = "Output audio 48 kHz tidak didukung perangkat (kode $rawMinBuf).",
                )
            }
            if (txGeneration.get() == generation) {
                runCatching {
                    val wl = wakeLock
                    if (wl != null && wl.isHeld) wl.release()
                }
                ServiceCompat.stopForeground(this@PhaseTransmitterService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        val minBuf = rawMinBuf.coerceAtLeast(8192)
        val bufferBytes = maxOf(minBuf * 2, 16_384)
        var track: AudioTrack? = null
        try {
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                mutableState.update {
                    it.copy(running = false, errorMessage = "Audio output perangkat gagal diinisialisasi.")
                }
                return
            }
            requestTxAudioFocus()
            track.play()

            var builtMode = currentMode
            var builtGain = currentGain
            var pcmFrame = PhaseCheckerRules.buildStereoTestFrame(builtMode, builtGain, sampleRate)
            var offset = 0
            val chunkShorts = 4_800 // 50 ms chunks so mode/gain edits respond rapidly

            while (serviceScope.isActive && txJob?.isActive == true) {
                if (currentMode != builtMode || currentGain != builtGain) {
                    builtMode = currentMode
                    builtGain = currentGain
                    pcmFrame = PhaseCheckerRules.buildStereoTestFrame(builtMode, builtGain, sampleRate)
                    offset = 0
                }
                if (offset == 0) {
                    mutableState.update { it.copy(pulseTick = it.pulseTick + 1L) }
                }
                val count = min(chunkShorts, pcmFrame.size - offset)
                val written = track.write(pcmFrame, offset, count, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    mutableState.update {
                        it.copy(errorMessage = "Pemancaran sinyal uji terhenti (kode $written).")
                    }
                    break
                }
                offset += written
                if (offset >= pcmFrame.size) offset = 0
            }
        } catch (t: Throwable) {
            if (txJob?.isActive == true) {
                mutableState.update {
                    it.copy(errorMessage = t.message ?: "Gagal menjalankan pemancar sinyal uji.")
                }
            }
        } finally {
            abandonAudioFocus()
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.stop() }
            runCatching { track?.release() }
            if (txGeneration.get() == generation) {
                runCatching {
                    val wl = wakeLock
                    if (wl != null && wl.isHeld) wl.release()
                }
                mutableState.update { it.copy(running = false) }
                ServiceCompat.stopForeground(this@PhaseTransmitterService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun buildNotification(mode: PhaseTestSignalMode): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            31,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            32,
            Intent(this, PhaseTransmitterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_audio)
            .setContentTitle("SWR Player TX Uji Fasa Aktif")
            .setContentText("${mode.shortLabel} • Level ${(currentGain * 100).toInt()}%")
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_audio, "Hentikan", stopIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    private fun parseMode(raw: String?): PhaseTestSignalMode? =
        raw?.let { name -> PhaseTestSignalMode.entries.firstOrNull { it.name == name } }

    companion object {
        private const val CHANNEL_ID = "phase_transmitter"
        private const val NOTIFICATION_ID = 2108
        private const val MAX_WAKELOCK_MS = 2 * 60 * 60 * 1000L

        private const val ACTION_START = "com.swararia.audiolab.action.PHASE_TX_START"
        private const val ACTION_UPDATE = "com.swararia.audiolab.action.PHASE_TX_UPDATE"
        private const val ACTION_STOP = "com.swararia.audiolab.action.PHASE_TX_STOP"
        private const val EXTRA_MODE = "extra_mode"
        private const val EXTRA_GAIN = "extra_gain"

        private val mutableState = MutableStateFlow(PhaseTxState())
        internal val state: StateFlow<PhaseTxState> = mutableState.asStateFlow()

        internal fun start(context: Context, mode: PhaseTestSignalMode, gain: Float) {
            val intent = Intent(context, PhaseTransmitterService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_GAIN, gain)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        internal fun updateParams(context: Context, mode: PhaseTestSignalMode, gain: Float) {
            if (!mutableState.value.running) {
                mutableState.update { it.copy(mode = mode, gain = gain) }
                return
            }
            val intent = Intent(context, PhaseTransmitterService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_MODE, mode.name)
                putExtra(EXTRA_GAIN, gain)
            }
            context.startService(intent)
        }

        internal fun stop(context: Context) {
            mutableState.update { it.copy(running = false) }
            runCatching {
                context.startService(Intent(context, PhaseTransmitterService::class.java).setAction(ACTION_STOP))
            }
        }

        internal fun clearError() {
            mutableState.update { it.copy(errorMessage = null) }
        }
    }
}
