package com.swararia.audiolab.foh

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Deterministic stereo 16-bit PCM `.wav` synthesizer for FOH Soundboard moment presets.
 *
 * Uses 24 kHz stereo 16-bit PCM (96 KB/s) so every stinger (1.4s–4.0s) stays compact (< 400 KB)
 * and loads cleanly into Android's `SoundPool` 1 MB decoded buffer cap with sub-20ms trigger latency.
 */
internal object SoundboardWavPresets {
    const val SAMPLE_RATE = 24_000
    private const val PRESET_DIR_NAME = "soundboard_presets_v1"
    private const val TWO_PI = 2.0 * PI

    private class DeterministicRng(initialSeed: Long) {
        private var state: Long = initialSeed xor 0x5DEECE66DL

        fun nextFloat(): Float {
            state = state * 6364136223846793005L + 1442695040888963407L
            val bits = ((state ushr 40) and 0xFFFFFFL).toInt()
            return bits / 16_777_216f
        }

        fun nextSigned(): Float = nextFloat() * 2f - 1f
    }

    private class StereoCanvas(
        val sampleRate: Int,
        durationMs: Long,
    ) {
        val frameCount: Int = ((sampleRate.toLong() * durationMs.coerceAtLeast(250L)) / 1000L).toInt().coerceAtLeast(1)
        val left = FloatArray(frameCount)
        val right = FloatArray(frameCount)

        fun addSample(index: Int, l: Float, r: Float) {
            if (index in 0 until frameCount) {
                left[index] += l
                right[index] += r
            }
        }

        fun addTone(
            startSec: Double,
            durSec: Double,
            freqStart: Double,
            freqEnd: Double = freqStart,
            amp: Float = 0.5f,
            pan: Float = 0f,
            attackSec: Double = 0.008,
            releaseSec: Double = 0.08,
            harmonics: FloatArray = floatArrayOf(1f),
        ) {
            val startIdx = (startSec * sampleRate).toInt().coerceIn(0, frameCount)
            val endIdx = ((startSec + durSec) * sampleRate).toInt().coerceIn(startIdx, frameCount)
            if (endIdx <= startIdx) return
            val totalFrames = (endIdx - startIdx).coerceAtLeast(1)
            val attFrames = (attackSec * sampleRate).toInt().coerceAtLeast(1)
            val relFrames = (releaseSec * sampleRate).toInt().coerceAtLeast(1)
            val gainL = amp * ((1f - pan.coerceIn(-1f, 1f)) * 0.5f + 0.5f).coerceIn(0.15f, 1f)
            val gainR = amp * ((1f + pan.coerceIn(-1f, 1f)) * 0.5f + 0.5f).coerceIn(0.15f, 1f)

            var phase = 0.0
            for (i in startIdx until endIdx) {
                val local = i - startIdx
                val progress = local.toDouble() / totalFrames.toDouble()
                val freq = freqStart + (freqEnd - freqStart) * progress
                phase += TWO_PI * freq / sampleRate
                var sample = 0.0
                for (hIdx in harmonics.indices) {
                    val weight = harmonics[hIdx]
                    if (weight != 0f) {
                        sample += weight * sin(phase * (hIdx + 1))
                    }
                }
                val envAttack = if (local < attFrames) local.toFloat() / attFrames.toFloat() else 1f
                val distEnd = endIdx - 1 - i
                val envRelease = if (distEnd < relFrames) distEnd.toFloat() / relFrames.toFloat() else 1f
                val env = min(envAttack, envRelease)
                val v = sample.toFloat() * env
                left[i] += v * gainL
                right[i] += v * gainR
            }
        }

        fun addChimeStrike(
            startSec: Double,
            durSec: Double,
            baseFreq: Double,
            amp: Float = 0.55f,
            pan: Float = 0f,
            decayRate: Double = 4.2,
        ) {
            val startIdx = (startSec * sampleRate).toInt().coerceIn(0, frameCount)
            val endIdx = ((startSec + durSec) * sampleRate).toInt().coerceIn(startIdx, frameCount)
            if (endIdx <= startIdx) return
            val gainL = amp * (1f - pan.coerceIn(-1f, 1f) * 0.45f)
            val gainR = amp * (1f + pan.coerceIn(-1f, 1f) * 0.45f)
            val partials = doubleArrayOf(1.0, 2.0, 2.756, 3.98)
            val partialAmps = doubleArrayOf(1.0, 0.52, 0.28, 0.16)
            val partialDecays = doubleArrayOf(decayRate, decayRate * 1.4, decayRate * 2.0, decayRate * 2.7)

            for (i in startIdx until endIdx) {
                val t = (i - startIdx).toDouble() / sampleRate.toDouble()
                val att = min(1.0, t / 0.004)
                var sum = 0.0
                for (p in partials.indices) {
                    sum += partialAmps[p] * exp(-t * partialDecays[p]) * sin(TWO_PI * baseFreq * partials[p] * t)
                }
                val s = (sum * att).toFloat()
                left[i] += s * gainL
                right[i] += s * gainR
            }
        }

        fun toWaveBytes(targetPeak: Float = 0.88f): ByteArray {
            var peak = 0f
            for (i in 0 until frameCount) {
                val absL = abs(left[i])
                val absR = abs(right[i])
                if (absL > peak) peak = absL
                if (absR > peak) peak = absR
            }
            val scale = if (peak > 1e-5f) targetPeak / max(peak, 0.35f) else 1f
            val dataBytes = frameCount * 4
            val buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)

            buf.put("RIFF".toByteArray(Charsets.US_ASCII))
            buf.putInt(36 + dataBytes)
            buf.put("WAVE".toByteArray(Charsets.US_ASCII))
            buf.put("fmt ".toByteArray(Charsets.US_ASCII))
            buf.putInt(16)
            buf.putShort(1) // PCM
            buf.putShort(2) // Stereo
            buf.putInt(sampleRate)
            buf.putInt(sampleRate * 4)
            buf.putShort(4)
            buf.putShort(16)
            buf.put("data".toByteArray(Charsets.US_ASCII))
            buf.putInt(dataBytes)

            for (i in 0 until frameCount) {
                val sl = tanh((left[i] * scale).toDouble()).toFloat()
                val sr = tanh((right[i] * scale).toDouble()).toFloat()
                buf.putShort((sl.coerceIn(-1f, 1f) * 32_767f).toInt().toShort())
                buf.putShort((sr.coerceIn(-1f, 1f) * 32_767f).toInt().toShort())
            }
            return buf.array()
        }
    }

    fun ensurePresetWavFile(baseDir: File, presetId: String): File? {
        val preset = SoundboardRules.presetById(presetId) ?: return null
        val dir = File(baseDir, PRESET_DIR_NAME).apply { if (!exists()) mkdirs() }
        val target = File(dir, "swararia-pad-${preset.id}.wav")
        if (target.exists() && target.length() > 44L) return target
        val bytes = buildPresetWavBytes(preset.id)
        val temp = File(dir, "swararia-pad-${preset.id}.wav.tmp")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            temp.delete()
        }
        return target
    }

    fun buildPresetWavBytes(presetId: String, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val preset = SoundboardRules.presetById(presetId)
        val durationMs = preset?.durationMs ?: 2_000L
        val canvas = StereoCanvas(sampleRate = sampleRate, durationMs = durationMs)

        when (presetId) {
            "applause" -> renderApplause(canvas)
            "laughter" -> renderLaughter(canvas)
            "tense_suspense" -> renderTenseSuspense(canvas)
            "drumroll" -> renderDrumroll(canvas)
            "fanfare_opening" -> renderFanfareOpening(canvas)
            "gong_peresmian" -> renderGongPeresmian(canvas)
            "kirab_stinger" -> renderKirabStinger(canvas)
            "award_victory" -> renderAwardVictory(canvas)
            "bell_correct" -> renderBellCorrect(canvas)
            "buzzer_wrong" -> renderBuzzerWrong(canvas)
            "punchline_joke" -> renderPunchlineJoke(canvas)
            "comedy_womp" -> renderComedyWomp(canvas)
            "countdown_tick" -> renderCountdownTick(canvas)
            "siren_doorprize" -> renderSirenDoorprize(canvas)
            "chime_mc" -> renderChimeMc(canvas)
            "airhorn_hype" -> renderAirhornHype(canvas)
            else -> renderChimeMc(canvas)
        }

        return canvas.toWaveBytes()
    }

    private fun renderApplause(c: StereoCanvas) {
        val rng = DeterministicRng(104729L)
        val totalSec = c.frameCount.toDouble() / c.sampleRate.toDouble()

        // Warm crowd room ambience bed
        var lpL = 0f
        var hpL = 0f
        var prevInL = 0f
        var lpR = 0f
        var hpR = 0f
        var prevInR = 0f
        for (i in 0 until c.frameCount) {
            val t = i.toDouble() / c.sampleRate.toDouble()
            val swellIn = min(1.0, t / 0.25)
            val fadeOut = min(1.0, max(0.0, (totalSec - t) / 0.70))
            val crowdEnv = (swellIn * fadeOut).pow(0.85).toFloat()

            val nL = rng.nextSigned()
            val nR = rng.nextSigned()
            hpL = 0.86f * (hpL + nL - prevInL)
            prevInL = nL
            lpL += 0.34f * (hpL - lpL)

            hpR = 0.86f * (hpR + nR - prevInR)
            prevInR = nR
            lpR += 0.34f * (hpR - lpR)

            c.addSample(i, lpL * crowdEnv * 0.26f, lpR * crowdEnv * 0.26f)
        }

        // 220 overlapping individual hand-clap bursts spread across stereo field
        val clapCount = 220
        for (k in 0 until clapCount) {
            val centerSec = 0.04 + (totalSec - 0.35) * rng.nextFloat().toDouble()
            val pan = rng.nextSigned() * 0.88f
            val baseTone = 920.0 + rng.nextFloat() * 1450.0
            val clapAmp = (0.14f + rng.nextFloat() * 0.26f) *
                min(1.0, (totalSec - centerSec) / 0.55).toFloat().coerceIn(0.15f, 1f)
            val clapDurSec = 0.055 + rng.nextFloat() * 0.035
            val startIdx = (centerSec * c.sampleRate).toInt().coerceIn(0, c.frameCount)
            val endIdx = ((centerSec + clapDurSec) * c.sampleRate).toInt().coerceIn(startIdx, c.frameCount)
            val gainL = clapAmp * (1f - pan * 0.48f)
            val gainR = clapAmp * (1f + pan * 0.48f)
            for (i in startIdx until endIdx) {
                val dt = (i - startIdx).toDouble() / c.sampleRate.toDouble()
                val burstEnv = (exp(-dt * 72.0) + 0.32 * exp(-dt * 28.0)).toFloat()
                val res = (0.6f * sin(TWO_PI * baseTone * dt).toFloat() + 0.4f * sin(TWO_PI * (baseTone * 1.62) * dt).toFloat())
                val noise = rng.nextSigned()
                val clapSample = (0.65f * noise + 0.35f * res) * burstEnv
                c.addSample(i, clapSample * gainL, clapSample * gainR)
            }
        }
    }

    private fun renderLaughter(c: StereoCanvas) {
        val rng = DeterministicRng(33019L)
        // 5 distinct cheerful crowd voices laughing with rhythmic "ha-ha-ha" syllables
        val voicePitches = doubleArrayOf(210.0, 265.0, 310.0, 175.0, 345.0)
        val voicePans = floatArrayOf(-0.65f, 0.55f, -0.22f, 0.72f, 0.08f)
        val voiceOffsets = doubleArrayOf(0.02, 0.09, 0.16, 0.05, 0.22)

        for (v in voicePitches.indices) {
            val pitch0 = voicePitches[v]
            val pan = voicePans[v]
            val gainL = 0.28f * (1f - pan * 0.45f)
            val gainR = 0.28f * (1f + pan * 0.45f)
            val syllables = 7 + (v % 3)
            var tCur = voiceOffsets[v]
            for (s in 0 until syllables) {
                val sylRate = 0.165 + rng.nextFloat() * 0.045
                val sylDur = sylRate * 0.72
                val startIdx = (tCur * c.sampleRate).toInt().coerceIn(0, c.frameCount)
                val endIdx = ((tCur + sylDur) * c.sampleRate).toInt().coerceIn(startIdx, c.frameCount)
                val sylPitchStart = pitch0 * (1.08 - s * 0.032 + rng.nextSigned() * 0.03)
                val sylPitchEnd = sylPitchStart * 0.84
                val f1 = 720.0 + rng.nextSigned() * 70.0
                val f2 = 1240.0 + rng.nextSigned() * 110.0
                var glottalPhase = 0.0
                for (i in startIdx until endIdx) {
                    val progress = (i - startIdx).toDouble() / max(1, endIdx - startIdx).toDouble()
                    val dt = (i - startIdx).toDouble() / c.sampleRate.toDouble()
                    val f0 = sylPitchStart + (sylPitchEnd - sylPitchStart) * progress
                    glottalPhase += TWO_PI * f0 / c.sampleRate
                    // Glottal pulse + vowel formants ("HA")
                    val glottal = (sin(glottalPhase) + 0.55 * sin(2.0 * glottalPhase) + 0.28 * sin(3.0 * glottalPhase))
                    val formant = (
                        0.60 * sin(TWO_PI * f1 * dt) * (0.5 + 0.5 * sin(glottalPhase)) +
                            0.35 * sin(TWO_PI * f2 * dt) * (0.5 + 0.5 * sin(glottalPhase))
                        )
                    val breath = rng.nextSigned() * 0.14f
                    val env = (sin(PI * progress).pow(0.65) * (1.0 - s * 0.06)).toFloat().coerceAtLeast(0f)
                    val sample = ((0.45 * glottal + 0.55 * formant).toFloat() + breath) * env
                    c.addSample(i, sample * gainL, sample * gainR)
                }
                tCur += sylRate
            }
        }
    }

    private fun renderTenseSuspense(c: StereoCanvas) {
        val totalSec = c.frameCount.toDouble() / c.sampleRate.toDouble()
        val rng = DeterministicRng(88117L)

        // Dark rising tension drone + tremolo strings feel
        var lfoPhase = 0.0
        for (i in 0 until c.frameCount) {
            val t = i.toDouble() / c.sampleRate.toDouble()
            val progress = t / totalSec
            val env = (min(1.0, t / 0.35) * min(1.0, max(0.0, (totalSec - t) / 0.45))).toFloat()
            val tremRate = 3.5 + progress * 7.5
            lfoPhase += TWO_PI * tremRate / c.sampleRate
            val trem = (0.62 + 0.38 * sin(lfoPhase)).toFloat()

            // Dissonant minor-second / tritone cluster rising gently
            val root = 73.42 * (1.0 + progress * 0.08) // ~D2
            val tritone = root * 1.4142
            val minor2 = root * 2.1189
            val padL = (
                0.55 * sin(TWO_PI * root * t) +
                    0.35 * sin(TWO_PI * tritone * t) +
                    0.22 * sin(TWO_PI * minor2 * t) +
                    0.06 * rng.nextSigned()
                ).toFloat()
            val padR = (
                0.55 * sin(TWO_PI * root * 1.004 * t) +
                    0.35 * sin(TWO_PI * tritone * 0.996 * t) +
                    0.22 * sin(TWO_PI * minor2 * 1.005 * t) +
                    0.06 * rng.nextSigned()
                ).toFloat()
            c.addSample(i, padL * env * trem * 0.34f, padR * env * trem * 0.34f)
        }

        // Accelerating double-thump heartbeat ("lub-dub")
        var beatTime = 0.12
        var interval = 0.72
        while (beatTime < totalSec - 0.35) {
            c.addTone(
                startSec = beatTime,
                durSec = 0.15,
                freqStart = 72.0,
                freqEnd = 42.0,
                amp = 0.75f,
                pan = -0.05f,
                attackSec = 0.006,
                releaseSec = 0.11,
                harmonics = floatArrayOf(1f, 0.35f),
            )
            c.addTone(
                startSec = beatTime + 0.14,
                durSec = 0.13,
                freqStart = 64.0,
                freqEnd = 38.0,
                amp = 0.56f,
                pan = 0.05f,
                attackSec = 0.006,
                releaseSec = 0.09,
                harmonics = floatArrayOf(1f, 0.25f),
            )
            beatTime += interval
            interval = max(0.38, interval * 0.87)
        }
    }

    private fun renderDrumroll(c: StereoCanvas) {
        val rng = DeterministicRng(51923L)
        val totalSec = c.frameCount.toDouble() / c.sampleRate.toDouble()
        val rollEndSec = totalSec - 0.75
        var t = 0.02
        var step = 0.075
        var hitIndex = 0
        while (t < rollEndSec) {
            val intensity = (0.35 + 0.65 * (t / rollEndSec)).toFloat()
            val pan = if (hitIndex % 2 == 0) -0.32f else 0.32f
            addSnareHit(c, t, intensity * 0.55f, pan, rng)
            t += step
            step = max(0.032, step * 0.965)
            hitIndex++
        }
        // Final accent crash + kick impact
        c.addTone(
            startSec = rollEndSec,
            durSec = 0.24,
            freqStart = 115.0,
            freqEnd = 46.0,
            amp = 0.85f,
            attackSec = 0.003,
            releaseSec = 0.18,
            harmonics = floatArrayOf(1f, 0.3f),
        )
        addCymbalCrash(c, rollEndSec, 0.72, 0.68f, rng)
    }

    private fun addSnareHit(c: StereoCanvas, startSec: Double, amp: Float, pan: Float, rng: DeterministicRng) {
        val startIdx = (startSec * c.sampleRate).toInt().coerceIn(0, c.frameCount)
        val endIdx = ((startSec + 0.11) * c.sampleRate).toInt().coerceIn(startIdx, c.frameCount)
        val gainL = amp * (1f - pan * 0.45f)
        val gainR = amp * (1f + pan * 0.45f)
        for (i in startIdx until endIdx) {
            val dt = (i - startIdx).toDouble() / c.sampleRate.toDouble()
            val body = sin(TWO_PI * (190.0 - dt * 450.0) * dt) * exp(-dt * 34.0)
            val wires = rng.nextSigned() * exp(-dt * 26.0)
            val s = (0.45 * body + 0.55 * wires).toFloat()
            c.addSample(i, s * gainL, s * gainR)
        }
    }

    private fun addCymbalCrash(c: StereoCanvas, startSec: Double, durSec: Double, amp: Float, rng: DeterministicRng) {
        val startIdx = (startSec * c.sampleRate).toInt().coerceIn(0, c.frameCount)
        val endIdx = ((startSec + durSec) * c.sampleRate).toInt().coerceIn(startIdx, c.frameCount)
        var prevL = 0f
        var hpL = 0f
        var prevR = 0f
        var hpR = 0f
        for (i in startIdx until endIdx) {
            val dt = (i - startIdx).toDouble() / c.sampleRate.toDouble()
            val env = (min(1.0, dt / 0.005) * exp(-dt * 3.8)).toFloat()
            val nL = rng.nextSigned()
            val nR = rng.nextSigned()
            hpL = 0.84f * (hpL + nL - prevL)
            prevL = nL
            hpR = 0.84f * (hpR + nR - prevR)
            prevR = nR
            val shimmer = (0.18 * sin(TWO_PI * 3_120.0 * dt) + 0.14 * sin(TWO_PI * 4_680.0 * dt)).toFloat()
            c.addSample(i, (hpL + shimmer) * env * amp, (hpR - shimmer * 0.6f) * env * amp)
        }
    }

    private fun renderFanfareOpening(c: StereoCanvas) {
        val brassHarmonics = floatArrayOf(1.0f, 0.72f, 0.48f, 0.30f, 0.16f)
        // Bright brass cadence: C4 -> E4 -> G4 -> C5 full triad
        c.addTone(0.02, 0.32, 261.63, amp = 0.48f, pan = -0.25f, releaseSec = 0.07, harmonics = brassHarmonics)
        c.addTone(0.32, 0.30, 329.63, amp = 0.50f, pan = 0.25f, releaseSec = 0.07, harmonics = brassHarmonics)
        c.addTone(0.62, 0.36, 392.00, amp = 0.54f, pan = -0.15f, releaseSec = 0.08, harmonics = brassHarmonics)
        c.addTone(0.98, 1.72, 523.25, amp = 0.52f, pan = 0.0f, releaseSec = 0.45, harmonics = brassHarmonics)
        c.addTone(0.98, 1.72, 392.00, amp = 0.38f, pan = -0.35f, releaseSec = 0.45, harmonics = brassHarmonics)
        c.addTone(0.98, 1.72, 261.63, amp = 0.36f, pan = 0.35f, releaseSec = 0.45, harmonics = brassHarmonics)
    }

    private fun renderGongPeresmian(c: StereoCanvas) {
        val partials = doubleArrayOf(1.0, 1.48, 2.13, 2.86, 3.64, 4.91)
        val amps = doubleArrayOf(1.0, 0.62, 0.44, 0.30, 0.22, 0.14)
        val base = 92.5 // ~F#2
        for (i in 0 until c.frameCount) {
            val t = i.toDouble() / c.sampleRate.toDouble()
            val attack = min(1.0, t / 0.018)
            val bloom = 1.0 + 0.18 * sin(TWO_PI * 1.4 * t) * exp(-t * 0.7)
            var sumL = 0.0
            var sumR = 0.0
            for (p in partials.indices) {
                val decay = exp(-t * (0.85 + p * 0.38))
                val fL = base * partials[p]
                val fR = fL * (1.0 + (p % 2 * 2 - 1) * 0.0035)
                sumL += amps[p] * decay * sin(TWO_PI * fL * t)
                sumR += amps[p] * decay * sin(TWO_PI * fR * t)
            }
            val env = (attack * bloom * min(1.0, max(0.0, (3.4 - t) / 0.45))).toFloat()
            c.addSample(i, sumL.toFloat() * env * 0.45f, sumR.toFloat() * env * 0.45f)
        }
    }

    private fun renderKirabStinger(c: StereoCanvas) {
        // Ascending Javanese/Pentatonic celebratory harp-chime arpeggio (C5-D5-E5-G5-A5-C6-E6)
        val notes = doubleArrayOf(523.25, 587.33, 659.25, 783.99, 880.00, 1046.50, 1318.51)
        for (idx in notes.indices) {
            val start = 0.04 + idx * 0.19
            val pan = -0.6f + idx * (1.2f / max(1, notes.lastIndex))
            c.addChimeStrike(start, 1.85, notes[idx], amp = 0.44f, pan = pan, decayRate = 2.6)
        }
    }

    private fun renderAwardVictory(c: StereoCanvas) {
        val h = floatArrayOf(1f, 0.6f, 0.35f, 0.18f)
        // Triumphant 4-note major arpeggio + held victory chord
        c.addTone(0.02, 0.22, 392.00, amp = 0.46f, pan = -0.2f, releaseSec = 0.05, harmonics = h)
        c.addTone(0.22, 0.22, 523.25, amp = 0.48f, pan = 0.2f, releaseSec = 0.05, harmonics = h)
        c.addTone(0.42, 0.24, 659.25, amp = 0.52f, pan = -0.15f, releaseSec = 0.05, harmonics = h)
        c.addTone(0.66, 2.05, 783.99, amp = 0.48f, pan = 0f, releaseSec = 0.50, harmonics = h)
        c.addTone(0.66, 2.05, 523.25, amp = 0.38f, pan = -0.4f, releaseSec = 0.50, harmonics = h)
        c.addTone(0.66, 2.05, 329.63, amp = 0.34f, pan = 0.4f, releaseSec = 0.50, harmonics = h)
        c.addChimeStrike(0.66, 1.9, 1567.98, amp = 0.35f, pan = 0.15f, decayRate = 2.8)
    }

    private fun renderBellCorrect(c: StereoCanvas) {
        c.addChimeStrike(0.02, 0.85, 1318.51, amp = 0.55f, pan = -0.20f, decayRate = 4.6) // E6
        c.addChimeStrike(0.20, 1.15, 1975.53, amp = 0.62f, pan = 0.20f, decayRate = 3.5) // B6
    }

    private fun renderBuzzerWrong(c: StereoCanvas) {
        val buzzHarmonics = floatArrayOf(1f, 0.85f, 0.68f, 0.52f, 0.38f, 0.26f)
        // Classic game-show "TE-TOT!" low saw-like buzz
        c.addTone(0.02, 0.30, 138.0, 128.0, amp = 0.48f, pan = -0.15f, attackSec = 0.005, releaseSec = 0.04, harmonics = buzzHarmonics)
        c.addTone(0.02, 0.30, 146.0, 136.0, amp = 0.42f, pan = 0.15f, attackSec = 0.005, releaseSec = 0.04, harmonics = buzzHarmonics)
        c.addTone(0.36, 0.95, 118.0, 102.0, amp = 0.52f, pan = -0.15f, attackSec = 0.005, releaseSec = 0.18, harmonics = buzzHarmonics)
        c.addTone(0.36, 0.95, 125.0, 108.0, amp = 0.46f, pan = 0.15f, attackSec = 0.005, releaseSec = 0.18, harmonics = buzzHarmonics)
    }

    private fun renderPunchlineJoke(c: StereoCanvas) {
        val rng = DeterministicRng(7013L)
        // "Ba" (tom)
        c.addTone(0.03, 0.18, 165.0, 115.0, amp = 0.68f, pan = -0.28f, attackSec = 0.003, releaseSec = 0.11, harmonics = floatArrayOf(1f, 0.4f))
        // "Dum" (snare)
        addSnareHit(c, 0.24, 0.72f, 0.18f, rng)
        // "Tss!" (splash cymbal)
        addCymbalCrash(c, 0.46, 1.05, 0.62f, rng)
    }

    private fun renderComedyWomp(c: StereoCanvas) {
        val h = floatArrayOf(1f, 0.72f, 0.46f, 0.25f)
        // Classic 4-note descending "Wah - Wah - Wah - Waaah..."
        c.addTone(0.03, 0.32, 311.13, 293.66, amp = 0.55f, pan = -0.18f, releaseSec = 0.06, harmonics = h)
        c.addTone(0.38, 0.32, 293.66, 277.18, amp = 0.55f, pan = 0.18f, releaseSec = 0.06, harmonics = h)
        c.addTone(0.73, 0.32, 277.18, 261.63, amp = 0.55f, pan = -0.12f, releaseSec = 0.06, harmonics = h)
        c.addTone(1.08, 1.02, 261.63, 224.00, amp = 0.58f, pan = 0.0f, releaseSec = 0.28, harmonics = h)
    }

    private fun renderCountdownTick(c: StereoCanvas) {
        // 6 steady ticks + 4 faster ticks + end bell
        val times = doubleArrayOf(0.05, 0.60, 1.15, 1.70, 2.25, 2.65, 2.95, 3.20, 3.42)
        for (idx in times.indices) {
            val freq = if (idx < 5) 980.0 else 1240.0
            c.addChimeStrike(times[idx], 0.16, freq, amp = 0.58f, pan = if (idx % 2 == 0) -0.2f else 0.2f, decayRate = 18.0)
        }
        c.addChimeStrike(3.52, 0.45, 1567.98, amp = 0.72f, pan = 0f, decayRate = 4.2)
    }

    private fun renderSirenDoorprize(c: StereoCanvas) {
        val startIdx = (0.02 * c.sampleRate).toInt()
        val endIdx = ((2.72) * c.sampleRate).toInt().coerceAtMost(c.frameCount)
        var phase = 0.0
        for (i in startIdx until endIdx) {
            val t = (i - startIdx).toDouble() / c.sampleRate.toDouble()
            val mod = sin(TWO_PI * 2.1 * t)
            val freq = 780.0 + 340.0 * mod
            phase += TWO_PI * freq / c.sampleRate
            val env = (min(1.0, t / 0.05) * min(1.0, max(0.0, (2.70 - t) / 0.25))).toFloat()
            val sig = (0.7 * sin(phase) + 0.35 * sin(2.0 * phase)).toFloat() * env * 0.48f
            val pan = (0.4 * mod).toFloat()
            c.addSample(i, sig * (1f - pan), sig * (1f + pan))
        }
    }

    private fun renderChimeMc(c: StereoCanvas) {
        val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.50) // C5-E5-G5-C6
        for (idx in notes.indices) {
            c.addChimeStrike(0.04 + idx * 0.24, 1.0, notes[idx], amp = 0.52f, pan = -0.3f + idx * 0.2f, decayRate = 3.6)
        }
    }

    private fun renderAirhornHype(c: StereoCanvas) {
        val h = floatArrayOf(1f, 0.82f, 0.64f, 0.46f, 0.32f, 0.20f)
        // Classic triple hype horn: "Pew! Pew! Peeeeew!"
        val blasts = listOf(
            Triple(0.03, 0.22, 0.04),
            Triple(0.30, 0.22, 0.04),
            Triple(0.58, 1.25, 0.22),
        )
        for ((start, dur, rel) in blasts) {
            c.addTone(start, dur, 466.16, 462.0, amp = 0.44f, pan = -0.2f, attackSec = 0.006, releaseSec = rel, harmonics = h)
            c.addTone(start, dur, 587.33, 583.0, amp = 0.42f, pan = 0.2f, attackSec = 0.006, releaseSec = rel, harmonics = h)
        }
    }
}
