package com.swararia.audiolab

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/** Pure PCM stereo-to-mono summing helpers shared by [MonoSumAudioProcessor] and host JVM tests. */
internal object MonoSumMath {
    /** Averages left and right normalized samples with -6 dB worst-case clipping headroom. */
    fun sumSample(left: Float, right: Float): Float {
        val safeL = if (left.isFinite()) left else 0f
        val safeR = if (right.isFinite()) right else 0f
        return ((safeL + safeR) * 0.5f).coerceIn(-1f, 1f)
    }

    /** Sums interleaved little-endian 16-bit stereo samples in place (L' = R' = (L + R) / 2). */
    fun sumStereoPcm16InPlace(buffer: ByteBuffer) {
        val ordered = buffer.order(ByteOrder.LITTLE_ENDIAN)
        var pos = ordered.position()
        val limit = ordered.limit()
        while (pos + 4 <= limit) {
            val left = ordered.getShort(pos).toInt()
            val right = ordered.getShort(pos + 2).toInt()
            val mono = ((left + right) shr 1).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            ordered.putShort(pos, mono)
            ordered.putShort(pos + 2, mono)
            pos += 4
        }
    }

    /** Sums interleaved little-endian 32-bit float stereo samples in place. */
    fun sumStereoFloatInPlace(buffer: ByteBuffer) {
        val ordered = buffer.order(ByteOrder.LITTLE_ENDIAN)
        var pos = ordered.position()
        val limit = ordered.limit()
        while (pos + 8 <= limit) {
            val left = ordered.getFloat(pos)
            val right = ordered.getFloat(pos + 4)
            val mono = sumSample(left, right)
            ordered.putFloat(pos, mono)
            ordered.putFloat(pos + 4, mono)
            pos += 8
        }
    }

    /** Sums interleaved little-endian 24-bit signed stereo samples in place. */
    fun sumStereoPcm24InPlace(buffer: ByteBuffer) {
        var pos = buffer.position()
        val limit = buffer.limit()
        while (pos + 6 <= limit) {
            val left = readInt24Le(buffer, pos)
            val right = readInt24Le(buffer, pos + 3)
            val mono = ((left + right) shr 1).coerceIn(-8_388_608, 8_388_607)
            writeInt24Le(buffer, pos, mono)
            writeInt24Le(buffer, pos + 3, mono)
            pos += 6
        }
    }

    /** Sums interleaved little-endian 32-bit signed stereo samples in place. */
    fun sumStereoPcm32InPlace(buffer: ByteBuffer) {
        val ordered = buffer.order(ByteOrder.LITTLE_ENDIAN)
        var pos = ordered.position()
        val limit = ordered.limit()
        while (pos + 8 <= limit) {
            val left = ordered.getInt(pos).toLong()
            val right = ordered.getInt(pos + 4).toLong()
            val mono = ((left + right) shr 1).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
            ordered.putInt(pos, mono)
            ordered.putInt(pos + 4, mono)
            pos += 8
        }
    }

    /** Sums unsigned 8-bit stereo PCM samples in place. */
    fun sumStereoPcm8InPlace(buffer: ByteBuffer) {
        var pos = buffer.position()
        val limit = buffer.limit()
        while (pos + 2 <= limit) {
            val left = buffer.get(pos).toInt() and 0xFF
            val right = buffer.get(pos + 1).toInt() and 0xFF
            val mono = ((left + right) shr 1).coerceIn(0, 255).toByte()
            buffer.put(pos, mono)
            buffer.put(pos + 1, mono)
            pos += 2
        }
    }

    private fun readInt24Le(buffer: ByteBuffer, offset: Int): Int {
        val b0 = buffer.get(offset).toInt() and 0xFF
        val b1 = buffer.get(offset + 1).toInt() and 0xFF
        val b2 = buffer.get(offset + 2).toInt()
        return b0 or (b1 shl 8) or (b2 shl 16)
    }

    private fun writeInt24Le(buffer: ByteBuffer, offset: Int, value: Int) {
        buffer.put(offset, (value and 0xFF).toByte())
        buffer.put(offset + 1, ((value ushr 8) and 0xFF).toByte())
        buffer.put(offset + 2, ((value ushr 16) and 0xFF).toByte())
    }
}

/** Configuration for a single Soundboard pad slot inside a bank. */
data class SoundPadSlot(
    val bankIndex: Int,
    val slotIndex: Int,
    val trackId: Long? = null,
    val label: String = "",
    val trimDb: Float = 0f,
) {
    val isAssigned: Boolean get() = trackId != null && trackId > 0L
}

/** Pure Soundboard rules, gain math, and JSON serialization. */
internal object SoundboardRules {
    const val BANK_COUNT = 2
    const val SLOTS_PER_BANK = 8
    const val SOUNDPOOL_MAX_DURATION_MS = 5_500L
    const val TRIM_MIN_DB = -18f
    const val TRIM_MAX_DB = 0f
    const val AUTO_DUCK_GAIN = 0.25f

    private val mapper = ObjectMapper()

    fun bankLabel(bankIndex: Int): String = when (bankIndex) {
        0 -> "Event"
        1 -> "Kuis / MC"
        else -> "Bank ${bankIndex + 1}"
    }

    fun clampTrimDb(trimDb: Float): Float =
        if (!trimDb.isFinite()) 0f else trimDb.coerceIn(TRIM_MIN_DB, TRIM_MAX_DB)

    fun trimDbToLinear(trimDb: Float): Float {
        val clamped = clampTrimDb(trimDb)
        return if (abs(clamped) < 0.05f) 1f else 10.0.pow(clamped / 20.0).toFloat().coerceIn(0f, 1f)
    }

    fun formatTrimDb(trimDb: Float): String {
        val clamped = clampTrimDb(trimDb)
        return if (abs(clamped) < 0.05f) "0 dB" else String.format(Locale.ROOT, "%.0f dB", clamped)
    }

    /** Short clips use low-latency SoundPool; longer clips stream via MediaPlayer to avoid 1 MB truncation. */
    fun shouldUseSoundPool(durationMs: Long?): Boolean =
        durationMs != null && durationMs in 1L..SOUNDPOOL_MAX_DURATION_MS

    fun defaultPadLabel(displayName: String): String {
        val clean = UiPresentation.title(displayName).trim()
        return clean.ifBlank { "Pad" }.take(28)
    }

    fun defaultSlots(): List<SoundPadSlot> = buildList(BANK_COUNT * SLOTS_PER_BANK) {
        for (bank in 0 until BANK_COUNT) {
            for (slot in 0 until SLOTS_PER_BANK) {
                add(SoundPadSlot(bankIndex = bank, slotIndex = slot))
            }
        }
    }

    fun encodeSlotsJson(slots: List<SoundPadSlot>): String {
        val assigned = slots.filter { it.isAssigned }.map {
            mapOf(
                "bank" to it.bankIndex,
                "slot" to it.slotIndex,
                "trackId" to it.trackId,
                "label" to it.label.take(40),
                "trimDb" to clampTrimDb(it.trimDb),
            )
        }
        return mapper.writeValueAsString(assigned)
    }

    fun decodeSlotsJson(raw: String?): List<SoundPadSlot> {
        val base = defaultSlots().toMutableList()
        if (raw.isNullOrBlank()) return base
        val tree = runCatching { mapper.readTree(raw) }.getOrNull() ?: return base
        if (!tree.isArray) return base
        for (node in tree) {
            val bank = node.path("bank").asInt(-1)
            val slot = node.path("slot").asInt(-1)
            val trackId = node.path("trackId").asLong(-1L)
            if (bank !in 0 until BANK_COUNT || slot !in 0 until SLOTS_PER_BANK || trackId <= 0L) continue
            val label = node.path("label").asText("").trim().take(40)
            val trimDb = clampTrimDb(node.path("trimDb").asDouble(0.0).toFloat())
            val idx = bank * SLOTS_PER_BANK + slot
            base[idx] = SoundPadSlot(
                bankIndex = bank,
                slotIndex = slot,
                trackId = trackId,
                label = label,
                trimDb = trimDb,
            )
        }
        return base
    }
}

/** Persistent stage-safety policy controlling Auto DND and Audio Focus Lock. */
data class FohSafeModeSettings(
    val enabled: Boolean = false,
    val autoDnd: Boolean = true,
    val focusLock: Boolean = true,
)

/** Pure rules for FOH Safe Mode and OEM device routing helpers. */
internal object FohSafeModeRules {
    fun shouldApplySystemDnd(settings: FohSafeModeSettings, policyAccessGranted: Boolean): Boolean =
        settings.enabled && settings.autoDnd && policyAccessGranted

    fun shouldIgnoreExternalFocusLoss(settings: FohSafeModeSettings): Boolean =
        settings.enabled && settings.focusLock

    fun needsDndPermissionPrompt(settings: FohSafeModeSettings, policyAccessGranted: Boolean): Boolean =
        settings.enabled && settings.autoDnd && !policyAccessGranted

    fun shouldWarnBluetooth(route: AudioOutputRoute): Boolean =
        route.kind == AudioOutputKind.BLUETOOTH

    fun isSamsungManufacturer(manufacturer: String?): Boolean =
        manufacturer.orEmpty().trim().equals("samsung", ignoreCase = true)
}
