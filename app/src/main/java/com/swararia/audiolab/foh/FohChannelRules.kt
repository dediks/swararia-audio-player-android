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

/** Built-in synthesized WAV preset available on FOH soundboard pads. */
data class SoundPadMomentPreset(
    val id: String,
    val bankIndex: Int,
    val slotIndex: Int,
    val label: String,
    val subtitle: String,
    val durationMs: Long,
    val defaultTrimDb: Float = 0f,
)

/** Configuration for a single Soundboard pad slot inside a bank. */
data class SoundPadSlot(
    val bankIndex: Int,
    val slotIndex: Int,
    val trackId: Long? = null,
    val label: String = "",
    val trimDb: Float = 0f,
    val presetId: String? = null,
) {
    val isAssigned: Boolean get() = (trackId != null && trackId > 0L) || !presetId.isNullOrBlank()
}

/** Pure Soundboard rules, gain math, and JSON serialization. */
internal object SoundboardRules {
    const val BANK_COUNT = 2
    const val SLOTS_PER_BANK = 8
    const val SOUNDPOOL_MAX_DURATION_MS = 5_500L
    const val TRIM_MIN_DB = -18f
    const val TRIM_MAX_DB = 0f
    const val AUTO_DUCK_GAIN = 0.25f

    val MOMENT_PRESETS: List<SoundPadMomentPreset> = listOf(
        // Bank 0: Event
        SoundPadMomentPreset(
            id = "applause",
            bankIndex = 0,
            slotIndex = 0,
            label = "Tepuk Tangan",
            subtitle = "Tepuk tangan meriah penonton",
            durationMs = 3_200L,
        ),
        SoundPadMomentPreset(
            id = "laughter",
            bankIndex = 0,
            slotIndex = 1,
            label = "Tertawa Ramai",
            subtitle = "Efek tawa penonton & suasana cair",
            durationMs = 2_600L,
        ),
        SoundPadMomentPreset(
            id = "tense_suspense",
            bankIndex = 0,
            slotIndex = 2,
            label = "Momen Tegang",
            subtitle = "Detak jantung & atmosfer deg-degan",
            durationMs = 3_800L,
        ),
        SoundPadMomentPreset(
            id = "drumroll",
            bankIndex = 0,
            slotIndex = 3,
            label = "Drumroll Tegangan",
            subtitle = "Roll snare pengumuman + simbal",
            durationMs = 3_000L,
        ),
        SoundPadMomentPreset(
            id = "fanfare_opening",
            bankIndex = 0,
            slotIndex = 4,
            label = "Fanfare Pembuka",
            subtitle = "Brass megah pembuka & sambutan VIP",
            durationMs = 2_800L,
        ),
        SoundPadMomentPreset(
            id = "gong_peresmian",
            bankIndex = 0,
            slotIndex = 5,
            label = "Gong Peresmian",
            subtitle = "Dentuman gong seremonial",
            durationMs = 3_400L,
        ),
        SoundPadMomentPreset(
            id = "kirab_stinger",
            bankIndex = 0,
            slotIndex = 6,
            label = "Stinger Kirab",
            subtitle = "Chime anggun pengiring masuk",
            durationMs = 3_200L,
        ),
        SoundPadMomentPreset(
            id = "award_victory",
            bankIndex = 0,
            slotIndex = 7,
            label = "Momen Juara",
            subtitle = "Kemenangan & penyerahan hadiah",
            durationMs = 2_800L,
        ),
        // Bank 1: Kuis / MC
        SoundPadMomentPreset(
            id = "bell_correct",
            bankIndex = 1,
            slotIndex = 0,
            label = "Jawaban Benar",
            subtitle = "Denting bel ganda (Ding-Ding!)",
            durationMs = 1_400L,
        ),
        SoundPadMomentPreset(
            id = "buzzer_wrong",
            bankIndex = 1,
            slotIndex = 1,
            label = "Buzzer Salah",
            subtitle = "Tetot / Zonk jawaban salah",
            durationMs = 1_500L,
        ),
        SoundPadMomentPreset(
            id = "punchline_joke",
            bankIndex = 1,
            slotIndex = 2,
            label = "Ba-Dum-Tss (Lucu)",
            subtitle = "Rimshot punchline candaan MC",
            durationMs = 1_600L,
        ),
        SoundPadMomentPreset(
            id = "comedy_womp",
            bankIndex = 1,
            slotIndex = 3,
            label = "Gagal Lucu (Womp)",
            subtitle = "Nada turun momen zonk santai",
            durationMs = 2_200L,
        ),
        SoundPadMomentPreset(
            id = "countdown_tick",
            bankIndex = 1,
            slotIndex = 4,
            label = "Hitung Mundur",
            subtitle = "Detak waktu cepat + bel akhir",
            durationMs = 4_000L,
        ),
        SoundPadMomentPreset(
            id = "siren_doorprize",
            bankIndex = 1,
            slotIndex = 5,
            label = "Sirine Doorprize",
            subtitle = "Sirine meriah undian hadiah",
            durationMs = 2_800L,
        ),
        SoundPadMomentPreset(
            id = "chime_mc",
            bankIndex = 1,
            slotIndex = 6,
            label = "Chime Panggilan MC",
            subtitle = "Nada perhatian transisi acara",
            durationMs = 1_800L,
        ),
        SoundPadMomentPreset(
            id = "airhorn_hype",
            bankIndex = 1,
            slotIndex = 7,
            label = "Terompet Meriah",
            subtitle = "Party horn pemecah suasana",
            durationMs = 2_000L,
        ),
    )

    private val presetsById: Map<String, SoundPadMomentPreset> = MOMENT_PRESETS.associateBy { it.id }
    private val presetsByPosition: Map<Pair<Int, Int>, SoundPadMomentPreset> =
        MOMENT_PRESETS.associateBy { it.bankIndex to it.slotIndex }

    private val mapper = ObjectMapper()

    fun presetById(id: String?): SoundPadMomentPreset? =
        id?.trim()?.takeIf { it.isNotEmpty() }?.let { presetsById[it] }

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

    fun defaultPresetSlots(): List<SoundPadSlot> = buildList(BANK_COUNT * SLOTS_PER_BANK) {
        for (bank in 0 until BANK_COUNT) {
            for (slot in 0 until SLOTS_PER_BANK) {
                val preset = presetsByPosition[bank to slot]
                if (preset != null) {
                    add(
                        SoundPadSlot(
                            bankIndex = bank,
                            slotIndex = slot,
                            trackId = null,
                            label = preset.label,
                            trimDb = preset.defaultTrimDb,
                            presetId = preset.id,
                        ),
                    )
                } else {
                    add(SoundPadSlot(bankIndex = bank, slotIndex = slot))
                }
            }
        }
    }

    fun mergeEmptyWithDefaultPresets(current: List<SoundPadSlot>): List<SoundPadSlot> {
        val byPos = current.associateBy { it.bankIndex to it.slotIndex }
        return buildList(BANK_COUNT * SLOTS_PER_BANK) {
            for (bank in 0 until BANK_COUNT) {
                for (slot in 0 until SLOTS_PER_BANK) {
                    val existing = byPos[bank to slot]
                    if (existing != null && existing.isAssigned) {
                        add(existing)
                    } else {
                        val preset = presetsByPosition[bank to slot]
                        if (preset != null) {
                            add(
                                SoundPadSlot(
                                    bankIndex = bank,
                                    slotIndex = slot,
                                    trackId = null,
                                    label = preset.label,
                                    trimDb = preset.defaultTrimDb,
                                    presetId = preset.id,
                                ),
                            )
                        } else {
                            add(SoundPadSlot(bankIndex = bank, slotIndex = slot))
                        }
                    }
                }
            }
        }
    }

    fun isSlotPlayable(slot: SoundPadSlot, availableTrackIds: Set<Long>): Boolean =
        presetById(slot.presetId) != null || (slot.trackId != null && slot.trackId in availableTrackIds)

    fun encodeSlotsJson(slots: List<SoundPadSlot>): String {
        val assigned = slots.filter { it.isAssigned }.map { slot ->
            buildMap<String, Any?> {
                put("bank", slot.bankIndex)
                put("slot", slot.slotIndex)
                put("trackId", slot.trackId)
                put("label", slot.label.take(40))
                put("trimDb", clampTrimDb(slot.trimDb))
                val preset = slot.presetId?.trim()?.takeIf { it.isNotEmpty() }
                if (preset != null) {
                    put("presetId", preset)
                }
            }
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
            val rawTrackId = node.path("trackId").asLong(-1L)
            val rawPresetId = node.path("presetId").asText("").trim()
            val validPreset = presetById(rawPresetId)
            val hasTrack = rawTrackId > 0L
            if (bank !in 0 until BANK_COUNT || slot !in 0 until SLOTS_PER_BANK || (!hasTrack && validPreset == null)) {
                continue
            }
            val label = node.path("label").asText("").trim().take(40)
            val trimDb = clampTrimDb(node.path("trimDb").asDouble(0.0).toFloat())
            val idx = bank * SLOTS_PER_BANK + slot
            base[idx] = SoundPadSlot(
                bankIndex = bank,
                slotIndex = slot,
                trackId = if (hasTrack) rawTrackId else null,
                label = label.ifBlank { validPreset?.label.orEmpty() },
                trimDb = trimDb,
                presetId = if (hasTrack) null else validPreset?.id,
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
