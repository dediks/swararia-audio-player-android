package com.swararia.audiolab

/**
 * Curated FOH dark console skin palettes represented with ARGB Long tokens so pure JVM tests stay
 * free of Android framework dependencies.
 */
enum class FohSkinPreset(
    val id: String,
    val label: String,
    val accentArgb: Long,
    val accentMidArgb: Long,
    val onAccentArgb: Long,
    val safeBgArgb: Long,
    val safeBorderArgb: Long,
    val bgAppArgb: Long,
    val bgDeepArgb: Long,
    val bgCardArgb: Long,
    val bgDeckArgb: Long,
    val borderSubtleArgb: Long,
) {
    SWARARIA_EMERALD(
        id = "swararia_emerald",
        label = "Mint",
        accentArgb = 0xFF8CE3C7L,
        accentMidArgb = 0xFF3BD4A4L,
        onAccentArgb = 0xFF12392CL,
        safeBgArgb = 0xFF132B21L,
        safeBorderArgb = 0xFF285944L,
        bgAppArgb = 0xFF101715L,
        bgDeepArgb = 0xFF131C18L,
        bgCardArgb = 0xFF19231FL,
        bgDeckArgb = 0xFF111916L,
        borderSubtleArgb = 0xFF2B3D33L,
    ),
    ROYAL_GOLD(
        id = "royal_gold",
        label = "Gold",
        accentArgb = 0xFFF4CE6AL,
        accentMidArgb = 0xFFD4A12AL,
        onAccentArgb = 0xFF2D2105L,
        safeBgArgb = 0xFF292110L,
        safeBorderArgb = 0xFF6B5420L,
        bgAppArgb = 0xFF120F09L,
        bgDeepArgb = 0xFF0A0805L,
        bgCardArgb = 0xFF1B160DL,
        bgDeckArgb = 0xFF14100AL,
        borderSubtleArgb = 0xFF3E331BL,
    ),
    COBALT_CONSOLE(
        id = "cobalt_console",
        label = "Cobalt",
        accentArgb = 0xFF5CE1E6L,
        accentMidArgb = 0xFF1BA7C9L,
        onAccentArgb = 0xFF062730L,
        safeBgArgb = 0xFF0F2633L,
        safeBorderArgb = 0xFF255973L,
        bgAppArgb = 0xFF0A1118L,
        bgDeepArgb = 0xFF060B10L,
        bgCardArgb = 0xFF111C28L,
        bgDeckArgb = 0xFF0C141DL,
        borderSubtleArgb = 0xFF20374BL,
    ),
    AMBER_PRO(
        id = "amber_pro",
        label = "Amber",
        accentArgb = 0xFFFFB020L,
        accentMidArgb = 0xFFDF7D08L,
        onAccentArgb = 0xFF311B00L,
        safeBgArgb = 0xFF2C1E0BL,
        safeBorderArgb = 0xFF714C19L,
        bgAppArgb = 0xFF120E08L,
        bgDeepArgb = 0xFF090704L,
        bgCardArgb = 0xFF1E170EL,
        bgDeckArgb = 0xFF140F09L,
        borderSubtleArgb = 0xFF423219L,
    ),
    CRIMSON_CLUB(
        id = "crimson_club",
        label = "Crimson",
        accentArgb = 0xFFFF6384L,
        accentMidArgb = 0xFFD62C58L,
        onAccentArgb = 0xFF380614L,
        safeBgArgb = 0xFF2F121CL,
        safeBorderArgb = 0xFF722940L,
        bgAppArgb = 0xFF140B0EL,
        bgDeepArgb = 0xFF090406L,
        bgCardArgb = 0xFF211117L,
        bgDeckArgb = 0xFF160B0FL,
        borderSubtleArgb = 0xFF421F2BL,
    ),
}

enum class StageRtaStyle(val id: String, val label: String) {
    LED("led", "LED"),
    CURVE("curve", "Curve"),
    HALO("halo", "Halo"),
}

enum class HaloChannelMode(val id: String, val label: String) {
    MIRRORED("mirrored", "Mirror"),
    STEREO_SPLIT("stereo_split", "Stereo L/R"),
}

data class BrandSkinConfig(
    val presetId: String = FohSkinPreset.SWARARIA_EMERALD.id,
    val oledTrueBlack: Boolean = false,
    val rentalName: String = "",
    val eventTag: String = "",
    val customLogoKey: String? = null,
    val customAccentArgb: Long? = null,
    val useCustomLogoAccent: Boolean = false,
    val stageRtaStyle: StageRtaStyle = StageRtaStyle.HALO,
    val haloChannelMode: HaloChannelMode = HaloChannelMode.MIRRORED,
    val cleanStageMode: Boolean = false,
    val prestigeAnimations: Boolean = true,
    val welcomeBannerDismissed: Boolean = false,
)

/** Pure rules for rental branding, logo validation, theme token derivation, and Stage RTA modes. */
object BrandSkinRules {
    const val MAX_RENTAL_NAME_LENGTH = 36
    const val MAX_EVENT_TAG_LENGTH = 44
    const val MAX_LOGO_IMPORT_BYTES = 8 * 1024 * 1024
    const val MAX_LOGO_DIMENSION_PX = 512

    fun sanitizeRentalName(raw: String): String =
        normalizeLabel(raw, MAX_RENTAL_NAME_LENGTH)

    fun sanitizeEventTag(raw: String): String =
        normalizeLabel(raw, MAX_EVENT_TAG_LENGTH)

    private fun normalizeLabel(raw: String, maxLen: Int): String {
        val cleaned = raw
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
        return if (cleaned.length <= maxLen) cleaned else cleaned.substring(0, maxLen).trimEnd()
    }

    fun validLogoKey(key: String?): Boolean =
        key != null &&
            key.length == 64 &&
            key.all { it in '0'..'9' || it in 'a'..'f' }

    fun resolvePreset(id: String?): FohSkinPreset =
        FohSkinPreset.entries.firstOrNull { it.id == id } ?: FohSkinPreset.SWARARIA_EMERALD

    fun resolveRtaStyle(id: String?): StageRtaStyle =
        StageRtaStyle.entries.firstOrNull { it.id == id } ?: StageRtaStyle.HALO

    fun resolveHaloChannelMode(id: String?): HaloChannelMode =
        HaloChannelMode.entries.firstOrNull { it.id == id } ?: HaloChannelMode.MIRRORED

    fun effectiveRentalTitle(config: BrandSkinConfig): String =
        sanitizeRentalName(config.rentalName).ifBlank { "SWR Player" }

    fun effectiveEventSubtitle(config: BrandSkinConfig): String {
        val tag = sanitizeEventTag(config.eventTag)
        if (tag.isNotBlank()) return tag
        return "Pro Audio Player"
    }

    fun hasCustomBrand(config: BrandSkinConfig): Boolean =
        validLogoKey(config.customLogoKey) ||
            sanitizeRentalName(config.rentalName).isNotBlank() ||
            sanitizeEventTag(config.eventTag).isNotBlank()

    fun shouldShowWelcomeBrandCard(config: BrandSkinConfig): Boolean =
        !config.welcomeBannerDismissed && !hasCustomBrand(config)

    fun activeAccentArgb(config: BrandSkinConfig): Long {
        if (config.useCustomLogoAccent) {
            val extracted = config.customAccentArgb
            if (extracted != null) return (extracted or 0xFF000000L)
        }
        return resolvePreset(config.presetId).accentArgb
    }

    fun activeAccentMidArgb(config: BrandSkinConfig): Long {
        if (config.useCustomLogoAccent && config.customAccentArgb != null) {
            return scaleRgb(activeAccentArgb(config), 0.78f)
        }
        return resolvePreset(config.presetId).accentMidArgb
    }

    fun derivedSafeBgArgb(accentArgb: Long): Long = blendDarkSurface(accentArgb, 0xFF0E1311L, 0.14f)

    fun derivedSafeBorderArgb(accentArgb: Long): Long = blendDarkSurface(accentArgb, 0xFF1D2421L, 0.36f)

    /**
     * Picks a bright, legible UI accent color from opaque chromatic pixels in an uploaded logo,
     * ensuring enough luminance so icons and meter text remain crisp on dark FOH backgrounds.
     */
    fun extractVibrantAccentArgb(argbPixels: IntArray): Long? {
        if (argbPixels.isEmpty()) return null
        val step = (argbPixels.size / 1024).coerceAtLeast(1)
        val bucketCounts = IntArray(36)
        val bucketRed = LongArray(36)
        val bucketGreen = LongArray(36)
        val bucketBlue = LongArray(36)

        var idx = 0
        while (idx < argbPixels.size) {
            val px = argbPixels[idx]
            idx += step
            val a = (px ushr 24) and 0xFF
            if (a < 140) continue
            val r = (px ushr 16) and 0xFF
            val g = (px ushr 8) and 0xFF
            val b = px and 0xFF
            val maxC = maxOf(r, g, b)
            val minC = minOf(r, g, b)
            val delta = maxC - minC
            if (maxC < 50 || delta < 28) continue // Skip near-black, grey, or white logos.
            val sat = delta.toFloat() / maxC.toFloat()
            if (sat < 0.22f) continue

            val hueDeg = when (maxC) {
                r -> 60f * (((g - b).toFloat() / delta) % 6f)
                g -> 60f * (((b - r).toFloat() / delta) + 2f)
                else -> 60f * (((r - g).toFloat() / delta) + 4f)
            }.let { if (it < 0f) it + 360f else it }
            val bucket = ((hueDeg / 10f).toInt()).coerceIn(0, 35)
            bucketCounts[bucket]++
            bucketRed[bucket] += r.toLong()
            bucketGreen[bucket] += g.toLong()
            bucketBlue[bucket] += b.toLong()
        }

        var bestBucket = -1
        var bestCount = 0
        for (b in 0 until 36) {
            if (bucketCounts[b] > bestCount) {
                bestCount = bucketCounts[b]
                bestBucket = b
            }
        }
        if (bestBucket < 0 || bestCount <= 0) return null

        var r = (bucketRed[bestBucket] / bestCount).toInt().coerceIn(0, 255)
        var g = (bucketGreen[bestBucket] / bestCount).toInt().coerceIn(0, 255)
        var b = (bucketBlue[bestBucket] / bestCount).toInt().coerceIn(0, 255)

        // Lift overly dark chromatic shades so dark UI accents keep crisp daylight/stage legibility.
        val maxChannel = maxOf(r, g, b)
        if (maxChannel in 1..209) {
            val boost = 216f / maxChannel.toFloat()
            r = (r * boost).toInt().coerceIn(0, 255)
            g = (g * boost).toInt().coerceIn(0, 255)
            b = (b * boost).toInt().coerceIn(0, 255)
        }
        if (maxOf(r, g, b) == minOf(r, g, b)) return null
        return 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    private fun scaleRgb(argb: Long, factor: Float): Long {
        val r = (((argb ushr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((argb ushr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * factor).toInt().coerceIn(0, 255)
        return 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }

    private fun blendDarkSurface(fgArgb: Long, bgArgb: Long, alpha: Float): Long {
        val inv = 1f - alpha
        val r = ((((fgArgb ushr 16) and 0xFF) * alpha) + (((bgArgb ushr 16) and 0xFF) * inv)).toInt().coerceIn(0, 255)
        val g = ((((fgArgb ushr 8) and 0xFF) * alpha) + (((bgArgb ushr 8) and 0xFF) * inv)).toInt().coerceIn(0, 255)
        val b = (((fgArgb and 0xFF) * alpha) + ((bgArgb and 0xFF) * inv)).toInt().coerceIn(0, 255)
        return 0xFF000000L or (r.toLong() shl 16) or (g.toLong() shl 8) or b.toLong()
    }
}
