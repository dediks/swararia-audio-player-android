package com.swararia.audiolab.foh

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Normalized second-order IIR biquad transfer coefficients (`H(z) = (b0 + b1 z^-1 + b2 z^-2) / (1 + a1 z^-1 + a2 z^-2)`). */
data class BiquadCoeffs(
    val b0: Double = 1.0,
    val b1: Double = 0.0,
    val b2: Double = 0.0,
    val a1: Double = 0.0,
    val a2: Double = 0.0,
) {
    val isIdentity: Boolean
        get() = abs(b0 - 1.0) < 1e-9 &&
            abs(b1) < 1e-9 &&
            abs(b2) < 1e-9 &&
            abs(a1) < 1e-9 &&
            abs(a2) < 1e-9

    companion object {
        val IDENTITY = BiquadCoeffs()
    }
}

/** Named 31-band equalizer preset with per-band gains (dB) and optional preamp trim. */
data class GraphicEqPreset(
    val id: String,
    val label: String,
    val description: String,
    val bandGainsDb: List<Float>,
    val preampDb: Float = 0f,
)

/** Persistent state of the local FOH 31-band ISO graphic equalizer. */
data class GraphicEqState(
    val enabled: Boolean = true,
    val preampDb: Float = 0f,
    val limiterEnabled: Boolean = true,
    val presetId: String = GraphicEqRules.PRESET_FLAT,
    val bandGainsDb: List<Float> = GraphicEqRules.FLAT_BANDS,
) {
    val isNonFlat: Boolean
        get() = abs(preampDb) >= GraphicEqRules.ACTIVE_EPS_DB ||
            bandGainsDb.any { abs(it) >= GraphicEqRules.ACTIVE_EPS_DB }

    val isProcessingAudio: Boolean
        get() = enabled && isNonFlat

    val shortStatusLabel: String
        get() = when {
            !enabled -> "BYPASS"
            !isNonFlat -> "FLAT"
            else -> GraphicEqRules.presetDisplayLabel(presetId)
        }
}

/**
 * Pure ISO 31-band 1/3-octave graphic equalizer rules, Orfanidis Nyquist-prescribed peaking filter
 * synthesis, coupled 31x31 cascade solver, and JSON persistence helpers.
 *
 * Uses double-precision Orfanidis reciprocal biquads (`Q = 3.5`) coupled with a cached 31x31
 * interaction inverse matrix and backtracking quasi-Newton refinement so adjacent band overlap is
 * compensated accurately across all sample rates (`44.1 kHz`, `48 kHz`, `96 kHz`, etc.).
 */
internal object GraphicEqRules {
    const val BAND_COUNT: Int = 31
    const val MIN_GAIN_DB: Float = -12f
    const val MAX_GAIN_DB: Float = 12f
    const val MIN_PREAMP_DB: Float = -12f
    const val MAX_PREAMP_DB: Float = 12f
    const val ACTIVE_EPS_DB: Float = 0.02f
    const val DEFAULT_CASCADE_Q: Double = 3.5
    const val PRESET_FLAT: String = "flat"
    const val PRESET_CUSTOM: String = "custom"

    val CENTER_FREQUENCIES_HZ: DoubleArray = doubleArrayOf(
        20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0,
        200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0,
        2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0, 12500.0, 16000.0, 20000.0,
    )

    val BAND_LABELS: List<String> = listOf(
        "20", "25", "31.5", "40", "50", "63", "80", "100", "125", "160",
        "200", "250", "315", "400", "500", "630", "800", "1k", "1.25k", "1.6k",
        "2k", "2.5k", "3.15k", "4k", "5k", "6.3k", "8k", "10k", "12.5k", "16k", "20k",
    )

    val FLAT_BANDS: List<Float> = List(BAND_COUNT) { 0f }

    val ZONE_RANGES: List<Pair<String, Int>> = listOf(
        "20–100" to 0,
        "125–630" to 8,
        "800–3.15k" to 16,
        "4k–20k" to 23,
    )

    val PRESETS: List<GraphicEqPreset> = listOf(
        GraphicEqPreset(
            id = PRESET_FLAT,
            label = "Flat (0 dB)",
            description = "Netral tanpa pewarnaan sinyal",
            bandGainsDb = FLAT_BANDS,
            preampDb = 0f,
        ),
        GraphicEqPreset(
            id = "warm_bass",
            label = "Sub & Warmth",
            description = "Bobot sub-bass padat tanpa mendengung",
            bandGainsDb = listOf(
                1.5f, 2.5f, 3.5f, 4.0f, 3.5f, 3.0f, 2.0f, 1.0f, 0.0f, -0.5f,
                -1.0f, -1.0f, -0.5f, 0.0f, 0.0f, 0.0f, 0.5f, 0.5f, 0.5f, 0.5f,
                1.0f, 1.0f, 1.0f, 1.0f, 1.5f, 1.5f, 1.0f, 1.0f, 0.5f, 0.0f, 0.0f,
            ),
            preampDb = -1.5f,
        ),
        GraphicEqPreset(
            id = "vocal_mc",
            label = "MC / Vocal Clarity",
            description = "Jernih untuk pidato, MC & pengumuman panggung",
            bandGainsDb = listOf(
                -8.0f, -7.0f, -6.0f, -4.5f, -3.0f, -1.5f, -0.5f, 0.0f, 0.0f, -0.5f,
                -1.5f, -2.5f, -2.0f, -1.0f, 0.0f, 0.5f, 1.0f, 1.5f, 2.0f, 2.5f,
                3.0f, 3.5f, 3.5f, 3.0f, 2.0f, 1.0f, 0.5f, 0.0f, -0.5f, -1.0f, -1.5f,
            ),
            preampDb = 0f,
        ),
        GraphicEqPreset(
            id = "acoustic",
            label = "Akustik / Sholawat",
            description = "Vokal hangat, petikan & perkusi natural",
            bandGainsDb = listOf(
                -1.0f, 0.0f, 0.5f, 1.0f, 1.5f, 1.5f, 1.0f, 0.5f, 0.0f, -0.5f,
                -1.0f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 1.0f, 1.0f, 1.5f, 2.0f,
                2.0f, 2.5f, 2.5f, 2.0f, 2.0f, 2.5f, 3.0f, 2.5f, 2.0f, 1.5f, 1.0f,
            ),
            preampDb = -1.0f,
        ),
        GraphicEqPreset(
            id = "dangdut_live",
            label = "FOH Dangdut Punch",
            description = "Tendangan kick-sub rapat & kendang renyah",
            bandGainsDb = listOf(
                2.0f, 3.0f, 4.0f, 4.5f, 4.5f, 4.0f, 2.5f, 1.0f, -0.5f, -1.5f,
                -2.0f, -2.5f, -2.5f, -2.0f, -1.0f, 0.0f, 0.5f, 1.0f, 1.5f, 2.0f,
                2.5f, 3.0f, 3.0f, 3.5f, 3.5f, 3.0f, 2.5f, 2.0f, 1.5f, 1.0f, 0.5f,
            ),
            preampDb = -2.0f,
        ),
        GraphicEqPreset(
            id = "outdoor_pa",
            label = "Outdoor PA Contour",
            description = "Kompensasi redaman udara terbuka & jarak jauh",
            bandGainsDb = listOf(
                3.0f, 3.5f, 4.0f, 3.5f, 3.0f, 2.0f, 1.0f, 0.5f, 0.0f, -0.5f,
                -1.0f, -1.5f, -1.5f, -1.0f, -0.5f, 0.0f, 0.5f, 0.5f, 1.0f, 1.5f,
                2.0f, 2.5f, 3.0f, 3.0f, 3.5f, 4.0f, 4.0f, 3.5f, 3.0f, 2.5f, 2.0f,
            ),
            preampDb = -2.0f,
        ),
        GraphicEqPreset(
            id = "feedback_safe",
            label = "Redam Feedback",
            description = "Potong frekuensi rawan dengung & storing panggung",
            bandGainsDb = listOf(
                -4.0f, -3.0f, -2.0f, -1.0f, 0.0f, 0.0f, 0.0f, 0.0f, -1.0f, -2.0f,
                -1.0f, -3.5f, -1.5f, 0.0f, -3.0f, -1.0f, 0.0f, -1.5f, 0.0f, 0.0f,
                -3.0f, -1.0f, 0.0f, -3.5f, -1.0f, 0.0f, -2.5f, -1.0f, 0.0f, -1.0f, -2.0f,
            ),
            preampDb = 0f,
        ),
    )

    private val presetsById: Map<String, GraphicEqPreset> = PRESETS.associateBy { it.id }
    private val mapper = ObjectMapper()

    private data class CachedSampleRateSolverData(
        val sampleRateHz: Double,
        val q: Double,
        val activeMask: BooleanArray,
        val phi: DoubleArray,
        val oneMinusPhi: DoubleArray,
        val fourPhiOneMinusPhi: DoubleArray,
        val inverseMatrix: Array<DoubleArray>,
    )

    private val solverCache = ConcurrentHashMap<Long, CachedSampleRateSolverData>()

    fun presetById(id: String?): GraphicEqPreset? =
        id?.trim()?.takeIf { it.isNotEmpty() }?.let { presetsById[it] }

    fun presetDisplayLabel(id: String?): String =
        presetById(id)?.label ?: if (id == PRESET_CUSTOM) "Custom" else "Flat"

    fun clampGainDb(db: Float): Float {
        if (!db.isFinite()) return 0f
        val clamped = db.coerceIn(MIN_GAIN_DB, MAX_GAIN_DB)
        return (clamped * 10f).roundToInt() / 10f
    }

    fun clampPreampDb(db: Float): Float {
        if (!db.isFinite()) return 0f
        val clamped = db.coerceIn(MIN_PREAMP_DB, MAX_PREAMP_DB)
        return (clamped * 10f).roundToInt() / 10f
    }

    fun clampBandGains(gains: List<Float>): List<Float> =
        List(BAND_COUNT) { idx -> clampGainDb(gains.getOrElse(idx) { 0f }) }

    fun formatGainDb(db: Float): String {
        val clamped = clampGainDb(db)
        return when {
            abs(clamped) < 0.05f -> "0.0 dB"
            clamped > 0f -> String.format(Locale.ROOT, "+%.1f dB", clamped)
            else -> String.format(Locale.ROOT, "%.1f dB", clamped)
        }
    }

    fun formatCompactBandDb(db: Float): String {
        val clamped = clampGainDb(db)
        return when {
            abs(clamped) < 0.05f -> "0.0"
            clamped > 0f -> String.format(Locale.ROOT, "+%.1f", clamped)
            else -> String.format(Locale.ROOT, "%.1f", clamped)
        }
    }

    fun withBandGain(state: GraphicEqState, bandIndex: Int, gainDb: Float): GraphicEqState {
        if (bandIndex !in 0 until BAND_COUNT) return state
        val nextBands = state.bandGainsDb.toMutableList().let { list ->
            if (list.size != BAND_COUNT) clampBandGains(list).toMutableList() else list
        }
        nextBands[bandIndex] = clampGainDb(gainDb)
        val matched = findMatchingPresetId(nextBands, state.preampDb)
        return state.copy(
            bandGainsDb = nextBands,
            presetId = matched,
        )
    }

    fun withPreampDb(state: GraphicEqState, preampDb: Float): GraphicEqState {
        val clamped = clampPreampDb(preampDb)
        val matched = findMatchingPresetId(state.bandGainsDb, clamped)
        return state.copy(
            preampDb = clamped,
            presetId = matched,
        )
    }

    fun applyPreset(state: GraphicEqState, preset: GraphicEqPreset): GraphicEqState =
        state.copy(
            enabled = true,
            preampDb = clampPreampDb(preset.preampDb),
            presetId = preset.id,
            bandGainsDb = clampBandGains(preset.bandGainsDb),
        )

    fun resetFlat(state: GraphicEqState): GraphicEqState =
        state.copy(
            preampDb = 0f,
            presetId = PRESET_FLAT,
            bandGainsDb = FLAT_BANDS,
        )

    fun findMatchingPresetId(bandGainsDb: List<Float>, preampDb: Float): String {
        val normalized = clampBandGains(bandGainsDb)
        val normPreamp = clampPreampDb(preampDb)
        for (preset in PRESETS) {
            if (abs(preset.preampDb - normPreamp) < 0.05f &&
                preset.bandGainsDb.indices.all { i -> abs(preset.bandGainsDb[i] - normalized[i]) < 0.05f }
            ) {
                return preset.id
            }
        }
        return PRESET_CUSTOM
    }

    /**
     * Computes Orfanidis (1997) Nyquist-prescribed second-order peaking filter coefficients with
     * exact boost/cut reciprocity (`cut(gain)` equals the algebraic inverse of `boost(|gain|)`).
     */
    fun computeOrfanidisPeaking(
        freqHz: Double,
        sampleRateHz: Double,
        gainDb: Double,
        q: Double = DEFAULT_CASCADE_Q,
    ): BiquadCoeffs {
        if (!gainDb.isFinite() || abs(gainDb) < 1e-4 ||
            !sampleRateHz.isFinite() || sampleRateHz <= 0.0 ||
            freqHz <= 0.0 || freqHz >= sampleRateHz * 0.485
        ) {
            return BiquadCoeffs.IDENTITY
        }

        val isCut = gainDb < 0.0
        val g0 = 1.0
        val g = 10.0.pow(abs(gainDb) / 20.0)
        val a = sqrt(g)
        val r = (sampleRateHz * 0.5) / freqHz
        val r2 = r * r
        val numNyq = (1.0 - r2) * (1.0 - r2) + (r * a / q) * (r * a / q)
        val denNyq = (1.0 - r2) * (1.0 - r2) + (r / (a * q)) * (r / (a * q))
        val g1 = min(g * 0.995, max(g0, sqrt(numNyq / max(1e-24, denNyq))))

        val gb: Double
        val dwScale: Double
        if (g1 < a * 0.98) {
            gb = a
            dwScale = 1.0
        } else {
            gb = g1.pow(0.35) * g.pow(0.65)
            val u2 = (g - (gb * gb) / g) / max(1e-12, (gb * gb - 1.0))
            dwScale = sqrt(max(1e-6, u2))
        }

        val w0 = 2.0 * PI * freqHz / sampleRateHz
        val dw = (w0 / q) * dwScale
        val f = abs(g * g - gb * gb)
        val g00 = abs(g * g - g0 * g0)
        val f00 = abs(gb * gb - g0 * g0)
        val g01 = abs(g * g - g0 * g1)
        val g11 = abs(g * g - g1 * g1)
        val f01 = abs(gb * gb - g0 * g1)
        val f11 = abs(gb * gb - g1 * g1)

        if (f < 1e-15 || g00 < 1e-15 || f11 < 1e-15) {
            return BiquadCoeffs.IDENTITY
        }

        val tanHalfW0 = tan(w0 * 0.5)
        val w2 = sqrt(g11 / g00) * tanHalfW0 * tanHalfW0
        val dwTerm = (1.0 + sqrt(f00 / f11) * w2) * tan(dw * 0.5)

        val c = f11 * dwTerm * dwTerm - 2.0 * w2 * (f01 - sqrt(f00 * f11))
        val d = 2.0 * w2 * (g01 - sqrt(g00 * g11))
        val aCoef = sqrt(max(0.0, (c + d) / f))
        val bCoef = sqrt(max(0.0, (g * g * c + gb * gb * d) / f))

        val denom = 1.0 + w2 + aCoef
        if (denom <= 1e-15) return BiquadCoeffs.IDENTITY

        val b0 = (g1 + g0 * w2 + bCoef) / denom
        val b1 = -2.0 * (g1 - g0 * w2) / denom
        val b2 = (g1 + g0 * w2 - bCoef) / denom
        val a1 = -2.0 * (1.0 - w2) / denom
        val a2 = (1.0 + w2 - aCoef) / denom

        return if (isCut) {
            if (abs(b0) < 1e-15) BiquadCoeffs.IDENTITY
            else BiquadCoeffs(
                b0 = 1.0 / b0,
                b1 = a1 / b0,
                b2 = a2 / b0,
                a1 = b1 / b0,
                a2 = b2 / b0,
            )
        } else {
            BiquadCoeffs(b0 = b0, b1 = b1, b2 = b2, a1 = a1, a2 = a2)
        }
    }

    /**
     * Evaluates magnitude response in decibels of a single biquad using half-angle `phi = sin^2(w/2)`
     * to avoid catastrophic floating-point cancellation at low frequencies and high sample rates.
     */
    fun evaluateSingleDb(
        coeffs: BiquadCoeffs,
        phi: Double,
        oneMinusPhi: Double,
        fourPhiOneMinusPhi: Double,
    ): Double {
        if (coeffs.isIdentity) return 0.0
        val b0 = coeffs.b0
        val b1 = coeffs.b1
        val b2 = coeffs.b2
        val a1 = coeffs.a1
        val a2 = coeffs.a2
        val sb = b0 + b1 + b2
        val db = b0 - b1 + b2
        val sa = 1.0 + a1 + a2
        val da = 1.0 - a1 + a2
        val num = sb * sb * oneMinusPhi + db * db * phi - 4.0 * b0 * b2 * fourPhiOneMinusPhi
        val den = sa * sa * oneMinusPhi + da * da * phi - 4.0 * a2 * fourPhiOneMinusPhi
        return 10.0 * log10(max(1e-30, num / max(1e-30, den)))
    }

    /** Evaluates the combined cascade magnitude response in dB at [freqHz]. */
    fun evaluateCascadeDb(
        coeffs: List<BiquadCoeffs>,
        freqHz: Double,
        sampleRateHz: Double,
        preampDb: Double = 0.0,
    ): Double {
        if (sampleRateHz <= 0.0 || freqHz <= 0.0 || freqHz >= sampleRateHz * 0.5) return preampDb
        val s = sin(PI * freqHz / sampleRateHz)
        val phi = s * s
        val oneMinusPhi = 1.0 - phi
        val fourPhiOneMinusPhi = 4.0 * phi * oneMinusPhi
        var totalDb = preampDb
        for (c in coeffs) {
            if (!c.isIdentity) {
                totalDb += evaluateSingleDb(c, phi, oneMinusPhi, fourPhiOneMinusPhi)
            }
        }
        return totalDb
    }

    private fun getOrBuildSolverData(sampleRateHz: Double, q: Double): CachedSampleRateSolverData {
        val safeRate = sampleRateHz.coerceIn(8_000.0, 384_000.0)
        val cacheKey = (safeRate.roundToInt().toLong() shl 20) xor (q * 1000.0).roundToInt().toLong()
        solverCache[cacheKey]?.let { return it }

        val activeMask = BooleanArray(BAND_COUNT) { idx -> CENTER_FREQUENCIES_HZ[idx] < safeRate * 0.485 }
        val phi = DoubleArray(BAND_COUNT) { idx ->
            val s = sin(PI * CENTER_FREQUENCIES_HZ[idx] / safeRate)
            s * s
        }
        val oneMinusPhi = DoubleArray(BAND_COUNT) { idx -> 1.0 - phi[idx] }
        val fourPhiOneMinusPhi = DoubleArray(BAND_COUNT) { idx -> 4.0 * phi[idx] * oneMinusPhi[idx] }

        val matrix = Array(BAND_COUNT) { DoubleArray(BAND_COUNT) }
        for (j in 0 until BAND_COUNT) {
            if (!activeMask[j]) {
                matrix[j][j] = 1.0
            } else {
                val refCoeffs = computeOrfanidisPeaking(CENTER_FREQUENCIES_HZ[j], safeRate, 3.0, q)
                for (i in 0 until BAND_COUNT) {
                    if (activeMask[i]) {
                        matrix[i][j] = evaluateSingleDb(refCoeffs, phi[i], oneMinusPhi[i], fourPhiOneMinusPhi[i]) / 3.0
                    }
                }
            }
        }
        val inverseMatrix = invertSquareMatrix(matrix)
        val built = CachedSampleRateSolverData(
            sampleRateHz = safeRate,
            q = q,
            activeMask = activeMask,
            phi = phi,
            oneMinusPhi = oneMinusPhi,
            fourPhiOneMinusPhi = fourPhiOneMinusPhi,
            inverseMatrix = inverseMatrix,
        )
        solverCache[cacheKey] = built
        return built
    }

    private fun invertSquareMatrix(input: Array<DoubleArray>): Array<DoubleArray> {
        val n = input.size
        val aug = Array(n) { r ->
            DoubleArray(2 * n) { c ->
                when {
                    c < n -> input[r][c]
                    c - n == r -> 1.0
                    else -> 0.0
                }
            }
        }
        for (col in 0 until n) {
            var pivotRow = col
            var maxVal = abs(aug[col][col])
            for (r in col + 1 until n) {
                val v = abs(aug[r][col])
                if (v > maxVal) {
                    maxVal = v
                    pivotRow = r
                }
            }
            if (pivotRow != col) {
                val tmp = aug[col]
                aug[col] = aug[pivotRow]
                aug[pivotRow] = tmp
            }
            val div = aug[col][col]
            if (abs(div) < 1e-12) continue
            for (c in 0 until 2 * n) {
                aug[col][c] /= div
            }
            for (r in 0 until n) {
                if (r != col) {
                    val factor = aug[r][col]
                    if (factor != 0.0) {
                        for (c in 0 until 2 * n) {
                            aug[r][c] -= factor * aug[col][c]
                        }
                    }
                }
            }
        }
        return Array(n) { r -> DoubleArray(n) { c -> aug[r][n + c] } }
    }

    /**
     * Solves exact 31-band biquad filter coefficients for [targetGainsDb] at [sampleRateHz] so the
     * cascaded magnitude response hits each ISO 1/3-octave center frequency accurately.
     */
    fun solveCascadeCoefficients(
        targetGainsDb: List<Float>,
        sampleRateHz: Double,
        q: Double = DEFAULT_CASCADE_Q,
        maxIterations: Int = 10,
    ): List<BiquadCoeffs> {
        if (!sampleRateHz.isFinite() || sampleRateHz <= 0.0) {
            return List(BAND_COUNT) { BiquadCoeffs.IDENTITY }
        }
        val targets = DoubleArray(BAND_COUNT) { idx -> clampGainDb(targetGainsDb.getOrElse(idx) { 0f }).toDouble() }
        if (targets.all { abs(it) < ACTIVE_EPS_DB }) {
            return List(BAND_COUNT) { BiquadCoeffs.IDENTITY }
        }

        val data = getOrBuildSolverData(sampleRateHz, q)
        val mInv = data.inverseMatrix
        val g = DoubleArray(BAND_COUNT) { i ->
            if (!data.activeMask[i]) 0.0
            else {
                var sum = 0.0
                val row = mInv[i]
                for (j in 0 until BAND_COUNT) sum += row[j] * targets[j]
                sum.coerceIn(-24.0, 24.0)
            }
        }

        val coeffs = Array(BAND_COUNT) { BiquadCoeffs.IDENTITY }
        val err = DoubleArray(BAND_COUNT)

        fun evaluateInto(gVec: DoubleArray, outCoeffs: Array<BiquadCoeffs>, outErr: DoubleArray): Double {
            for (j in 0 until BAND_COUNT) {
                outCoeffs[j] = if (data.activeMask[j]) {
                    computeOrfanidisPeaking(CENTER_FREQUENCIES_HZ[j], data.sampleRateHz, gVec[j], q)
                } else {
                    BiquadCoeffs.IDENTITY
                }
            }
            var sse = 0.0
            for (i in 0 until BAND_COUNT) {
                if (!data.activeMask[i]) {
                    outErr[i] = 0.0
                    continue
                }
                val p = data.phi[i]
                val omp = data.oneMinusPhi[i]
                val fpomp = data.fourPhiOneMinusPhi[i]
                var actual = 0.0
                for (j in 0 until BAND_COUNT) {
                    if (!outCoeffs[j].isIdentity) {
                        actual += evaluateSingleDb(outCoeffs[j], p, omp, fpomp)
                    }
                }
                val e = targets[i] - actual
                outErr[i] = e
                sse += e * e
            }
            return sse
        }

        var currentSse = evaluateInto(g, coeffs, err)
        val cand = DoubleArray(BAND_COUNT)
        val candCoeffs = Array(BAND_COUNT) { BiquadCoeffs.IDENTITY }
        val candErr = DoubleArray(BAND_COUNT)

        for (iter in 0 until maxIterations) {
            var maxAbsErr = 0.0
            for (i in 0 until BAND_COUNT) {
                val a = abs(err[i])
                if (a > maxAbsErr) maxAbsErr = a
            }
            if (maxAbsErr < 0.004) break

            val dg = DoubleArray(BAND_COUNT) { i ->
                if (!data.activeMask[i]) 0.0
                else {
                    var s = 0.0
                    val row = mInv[i]
                    for (j in 0 until BAND_COUNT) s += row[j] * err[j]
                    s
                }
            }

            var alpha = 1.0
            var accepted = false
            for (step in 0 until 5) {
                for (k in 0 until BAND_COUNT) {
                    cand[k] = (g[k] + alpha * dg[k]).coerceIn(-24.0, 24.0)
                }
                val candSse = evaluateInto(cand, candCoeffs, candErr)
                if (candSse < currentSse) {
                    System.arraycopy(cand, 0, g, 0, BAND_COUNT)
                    System.arraycopy(candCoeffs, 0, coeffs, 0, BAND_COUNT)
                    System.arraycopy(candErr, 0, err, 0, BAND_COUNT)
                    currentSse = candSse
                    accepted = true
                    break
                }
                alpha *= 0.5
            }
            if (!accepted) break
        }

        return coeffs.toList()
    }

    /** Samples logarithmic 20 Hz..20 kHz transfer curve in dB for UI plotting. */
    fun sampleResponseCurveDb(
        state: GraphicEqState,
        sampleRateHz: Double = 48_000.0,
        pointCount: Int = 93,
        includePreamp: Boolean = false,
    ): List<Float> {
        val count = pointCount.coerceAtLeast(31)
        val preampOffset = if (includePreamp && state.enabled) state.preampDb.toDouble() else 0.0
        if (!state.enabled || (!state.isNonFlat)) {
            return List(count) { preampOffset.toFloat() }
        }
        val coeffs = solveCascadeCoefficients(state.bandGainsDb, sampleRateHz)
        val minLog = ln(20.0)
        val maxLog = ln(20_000.0)
        return List(count) { idx ->
            val frac = idx.toDouble() / (count - 1).toDouble()
            val freq = kotlin.math.exp(minLog + frac * (maxLog - minLog))
            evaluateCascadeDb(coeffs, freq, sampleRateHz, preampOffset).toFloat()
        }
    }

    fun encodeJson(state: GraphicEqState): String {
        val payload = mapOf(
            "enabled" to state.enabled,
            "preampDb" to clampPreampDb(state.preampDb),
            "limiterEnabled" to state.limiterEnabled,
            "presetId" to state.presetId,
            "bands" to clampBandGains(state.bandGainsDb),
        )
        return mapper.writeValueAsString(payload)
    }

    fun decodeJson(raw: String?): GraphicEqState {
        if (raw.isNullOrBlank()) return GraphicEqState()
        val tree = runCatching { mapper.readTree(raw) }.getOrNull() ?: return GraphicEqState()
        if (!tree.isObject) return GraphicEqState()

        val enabled = tree.path("enabled").asBoolean(true)
        val preampDb = clampPreampDb(tree.path("preampDb").asDouble(0.0).toFloat())
        val limiterEnabled = tree.path("limiterEnabled").asBoolean(true)
        val bandsNode = tree.path("bands")
        val bands = if (bandsNode.isArray) {
            List(BAND_COUNT) { idx ->
                val node = bandsNode.get(idx)
                clampGainDb(node?.asDouble(0.0)?.toFloat() ?: 0f)
            }
        } else {
            FLAT_BANDS
        }
        val rawPreset = tree.path("presetId").asText("").trim()
        val resolvedPreset = if (rawPreset.isNotEmpty() &&
            (rawPreset == PRESET_CUSTOM || presetById(rawPreset) != null)
        ) {
            rawPreset
        } else {
            findMatchingPresetId(bands, preampDb)
        }
        return GraphicEqState(
            enabled = enabled,
            preampDb = preampDb,
            limiterEnabled = limiterEnabled,
            presetId = resolvedPreset,
            bandGainsDb = bands,
        )
    }
}
