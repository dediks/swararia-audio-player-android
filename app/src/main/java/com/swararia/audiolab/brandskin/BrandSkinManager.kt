package com.swararia.audiolab.brandskin

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * Platform-agnostic contract for reading and mutating persisted rental brand & stage skin settings.
 */
internal interface BrandSkinStore {
    val state: StateFlow<BrandSkinConfig>
    fun selectPreset(presetId: String)
    fun selectCustomLogoAccent()
    fun setOledTrueBlack(enabled: Boolean)
    fun setRentalName(raw: String)
    fun setEventTag(raw: String)
    fun setStageRtaStyle(style: StageRtaStyle)
    fun setHaloChannelMode(mode: HaloChannelMode)
    fun setCleanStageMode(clean: Boolean)
    fun setPrestigeAnimations(enabled: Boolean)
    fun dismissWelcomeBanner()
    fun resetAllBranding()
    suspend fun importLogoFromUriString(uriString: String): Result<BrandSkinConfig>
}

/**
 * Manages persisted rental branding, custom transparent PNG/photo logo storage in app-private files,
 * and dynamic console skin configuration.
 */
class BrandSkinManager(context: Context) : BrandSkinStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(readConfig(prefs))
    override val state: StateFlow<BrandSkinConfig> = mutableState.asStateFlow()

    override fun selectPreset(presetId: String) {
        val preset = BrandSkinRules.resolvePreset(presetId)
        updateConfig { it.copy(presetId = preset.id, useCustomLogoAccent = false) }
    }

    override fun selectCustomLogoAccent() {
        updateConfig { current ->
            if (current.customAccentArgb != null) current.copy(useCustomLogoAccent = true) else current
        }
    }

    override fun setOledTrueBlack(enabled: Boolean) {
        updateConfig { it.copy(oledTrueBlack = enabled) }
    }

    override fun setRentalName(raw: String) {
        val sanitized = BrandSkinRules.sanitizeRentalName(raw)
        updateConfig { it.copy(rentalName = sanitized) }
    }

    override fun setEventTag(raw: String) {
        val sanitized = BrandSkinRules.sanitizeEventTag(raw)
        updateConfig { it.copy(eventTag = sanitized) }
    }

    override fun setStageRtaStyle(style: StageRtaStyle) {
        updateConfig { it.copy(stageRtaStyle = style) }
    }

    override fun setHaloChannelMode(mode: HaloChannelMode) {
        updateConfig { it.copy(haloChannelMode = mode) }
    }

    override fun setCleanStageMode(clean: Boolean) {
        updateConfig { it.copy(cleanStageMode = clean) }
    }

    override fun setPrestigeAnimations(enabled: Boolean) {
        updateConfig { it.copy(prestigeAnimations = enabled) }
    }

    override fun dismissWelcomeBanner() {
        updateConfig { it.copy(welcomeBannerDismissed = true) }
    }

    fun clearCustomLogo() {
        val previousKey = mutableState.value.customLogoKey
        updateConfig {
            it.copy(
                customLogoKey = null,
                customAccentArgb = null,
                useCustomLogoAccent = false,
            )
        }
        if (previousKey != null) {
            deleteLogoFile(previousKey)
        }
    }

    override fun resetAllBranding() {
        val previousKey = mutableState.value.customLogoKey
        updateConfig {
            it.copy(
                rentalName = "",
                eventTag = "",
                customLogoKey = null,
                customAccentArgb = null,
                useCustomLogoAccent = false,
                welcomeBannerDismissed = false,
            )
        }
        if (previousKey != null) {
            deleteLogoFile(previousKey)
        }
    }

    override suspend fun importLogoFromUriString(uriString: String): Result<BrandSkinConfig> =
        importLogoFromUri(Uri.parse(uriString))

    suspend fun importLogoFromUri(uri: Uri): Result<BrandSkinConfig> = withContext(Dispatchers.IO) {
        runCatching {
            val rawBytes = readBoundedBytes(uri, BrandSkinRules.MAX_LOGO_IMPORT_BYTES)
                ?: error("File logo tidak dapat dibaca atau melebihi batas 8 MB.")

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            val rawW = bounds.outWidth
            val rawH = bounds.outHeight
            if (rawW !in 1..4096 || rawH !in 1..4096) {
                error("Dimensi gambar logo tidak valid ($rawW x $rawH).")
            }

            var sample = 1
            while (maxOf(rawW, rawH) > BrandSkinRules.MAX_LOGO_DIMENSION_PX * sample) {
                sample *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var bitmap = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                ?: error("Format gambar logo tidak didukung.")

            val orientation = readExifOrientation(rawBytes)
            val rotated = applyExifOrientation(bitmap, orientation)
            if (rotated !== bitmap) {
                bitmap.recycle()
                bitmap = rotated
            }

            val maxEdge = maxOf(bitmap.width, bitmap.height)
            if (maxEdge > BrandSkinRules.MAX_LOGO_DIMENSION_PX) {
                val scale = BrandSkinRules.MAX_LOGO_DIMENSION_PX.toFloat() / maxEdge.toFloat()
                val targetW = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
                val targetH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }

            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val extractedAccent = BrandSkinRules.extractVibrantAccentArgb(pixels)

            val encoded = ByteArrayOutputStream().use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) { "Gagal memproses logo PNG." }
                out.toByteArray()
            }
            bitmap.recycle()

            val newKey = MessageDigest.getInstance("SHA-256")
                .digest(encoded)
                .joinToString("") { "%02x".format(it) }

            writeLogoFile(newKey, encoded)

            val previousKey = mutableState.value.customLogoKey
            val updated = updateConfig {
                it.copy(
                    customLogoKey = newKey,
                    customAccentArgb = extractedAccent,
                )
            }
            if (previousKey != null && previousKey != newKey) {
                deleteLogoFile(previousKey)
            }
            updated
        }
    }

    private fun readBoundedBytes(uri: Uri, maxBytes: Int): ByteArray? {
        val stream = appContext.contentResolver.openInputStream(uri) ?: return null
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                total += read
                if (total > maxBytes) return null
                out.write(buf, 0, read)
            }
            if (total == 0) null else out.toByteArray()
        }
    }

    private fun readExifOrientation(bytes: ByteArray): Int = runCatching {
        ByteArrayInputStream(bytes).use { stream ->
            ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bitmap
        }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrDefault(bitmap)
    }

    private fun writeLogoFile(key: String, bytes: ByteArray) {
        check(BrandSkinRules.validLogoKey(key))
        synchronized(storageLock) {
            val directory = File(appContext.filesDir, BRAND_DIR)
            check(directory.mkdirs() || directory.isDirectory)
            val destination = File(directory, "$key.img")
            if (destination.isFile) return
            val temporary = File.createTempFile("logo-", ".tmp", directory)
            try {
                temporary.outputStream().use {
                    it.write(bytes)
                    it.fd.sync()
                }
                check(temporary.renameTo(destination))
            } finally {
                temporary.delete()
            }
        }
    }

    private fun deleteLogoFile(key: String) {
        if (!BrandSkinRules.validLogoKey(key)) return
        synchronized(storageLock) {
            runCatching { resolveLogoFile(appContext, key)?.delete() }
        }
    }

    private fun updateConfig(transform: (BrandSkinConfig) -> BrandSkinConfig): BrandSkinConfig {
        var latest = mutableState.value
        mutableState.update { current ->
            transform(current).also { latest = it }
        }
        persistConfig(prefs, latest)
        return latest
    }

    companion object {
        private val storageLock = Any()
        private const val BRAND_DIR = "brand"
        private const val PREFS_NAME = "brand_skin_prefs"
        private const val MAX_STORED_LOGO_BYTES = 4L * 1024 * 1024

        private const val KEY_PRESET_ID = "preset_id"
        private const val KEY_OLED_BLACK = "oled_true_black"
        private const val KEY_RENTAL_NAME = "rental_name"
        private const val KEY_EVENT_TAG = "event_tag"
        private const val KEY_CUSTOM_LOGO_KEY = "custom_logo_key"
        private const val KEY_CUSTOM_ACCENT = "custom_accent_argb"
        private const val KEY_USE_LOGO_ACCENT = "use_custom_logo_accent"
        private const val KEY_RTA_STYLE = "stage_rta_style"
        private const val KEY_HALO_CHANNEL_MODE = "halo_channel_mode"
        private const val KEY_CLEAN_STAGE = "clean_stage_mode"
        private const val KEY_PRESTIGE_ANIM = "prestige_animations"
        private const val KEY_WELCOME_DISMISSED = "welcome_banner_dismissed"

        fun resolveLogoFile(context: Context, key: String?): File? {
            if (!BrandSkinRules.validLogoKey(key)) return null
            return runCatching {
                val directory = File(context.filesDir, BRAND_DIR).canonicalFile
                val file = File(directory, "$key.img")
                file.takeIf {
                    it.canonicalFile.parentFile == directory &&
                        it.isFile &&
                        it.length() in 1..MAX_STORED_LOGO_BYTES
                }
            }.getOrNull()
        }

        private fun readConfig(prefs: SharedPreferences): BrandSkinConfig {
            val preset = BrandSkinRules.resolvePreset(prefs.getString(KEY_PRESET_ID, null))
            val logoKey = prefs.getString(KEY_CUSTOM_LOGO_KEY, null)?.takeIf(BrandSkinRules::validLogoKey)
            val accentRaw = if (prefs.contains(KEY_CUSTOM_ACCENT)) prefs.getLong(KEY_CUSTOM_ACCENT, 0L) else null
            return BrandSkinConfig(
                presetId = preset.id,
                oledTrueBlack = prefs.getBoolean(KEY_OLED_BLACK, false),
                rentalName = BrandSkinRules.sanitizeRentalName(prefs.getString(KEY_RENTAL_NAME, "").orEmpty()),
                eventTag = BrandSkinRules.sanitizeEventTag(prefs.getString(KEY_EVENT_TAG, "").orEmpty()),
                customLogoKey = logoKey,
                customAccentArgb = accentRaw,
                useCustomLogoAccent = prefs.getBoolean(KEY_USE_LOGO_ACCENT, false) && accentRaw != null,
                stageRtaStyle = BrandSkinRules.resolveRtaStyle(prefs.getString(KEY_RTA_STYLE, null)),
                haloChannelMode = BrandSkinRules.resolveHaloChannelMode(prefs.getString(KEY_HALO_CHANNEL_MODE, null)),
                cleanStageMode = prefs.getBoolean(KEY_CLEAN_STAGE, false),
                prestigeAnimations = prefs.getBoolean(KEY_PRESTIGE_ANIM, true),
                welcomeBannerDismissed = prefs.getBoolean(KEY_WELCOME_DISMISSED, false),
            )
        }

        private fun persistConfig(prefs: SharedPreferences, config: BrandSkinConfig) {
            prefs.edit().apply {
                putString(KEY_PRESET_ID, BrandSkinRules.resolvePreset(config.presetId).id)
                putBoolean(KEY_OLED_BLACK, config.oledTrueBlack)
                putString(KEY_RENTAL_NAME, BrandSkinRules.sanitizeRentalName(config.rentalName))
                putString(KEY_EVENT_TAG, BrandSkinRules.sanitizeEventTag(config.eventTag))
                if (BrandSkinRules.validLogoKey(config.customLogoKey)) {
                    putString(KEY_CUSTOM_LOGO_KEY, config.customLogoKey)
                } else {
                    remove(KEY_CUSTOM_LOGO_KEY)
                }
                val accent = config.customAccentArgb
                if (accent != null) {
                    putLong(KEY_CUSTOM_ACCENT, accent)
                } else {
                    remove(KEY_CUSTOM_ACCENT)
                }
                putBoolean(KEY_USE_LOGO_ACCENT, config.useCustomLogoAccent && accent != null)
                putString(KEY_RTA_STYLE, config.stageRtaStyle.id)
                putString(KEY_HALO_CHANNEL_MODE, config.haloChannelMode.id)
                putBoolean(KEY_CLEAN_STAGE, config.cleanStageMode)
                putBoolean(KEY_PRESTIGE_ANIM, config.prestigeAnimations)
                putBoolean(KEY_WELCOME_DISMISSED, config.welcomeBannerDismissed)
            }.apply()
        }
    }
}
