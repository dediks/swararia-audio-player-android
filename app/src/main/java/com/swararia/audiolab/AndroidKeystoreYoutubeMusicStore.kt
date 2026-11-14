package com.swararia.audiolab

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/**
 * Hardware/OS Keystore-backed encrypted store for optional YouTube Music cookies.
 * Stored in noBackupFilesDir so cloud/ADB backups never capture session cookies.
 */
class AndroidKeystoreYoutubeMusicStore(context: Context) : YoutubeMusicSessionStore {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val mapper = ObjectMapper()
    private val storeFile = File(appContext.noBackupFilesDir, FILE_NAME)
    private val tempFile = File(appContext.noBackupFilesDir, "$FILE_NAME.tmp")

    override fun load(): PersistedYoutubeMusicSession? = synchronized(lock) {
        if (!storeFile.isFile) return null
        val len = storeFile.length()
        if (len !in MIN_RECORD_BYTES..MAX_RECORD_BYTES) {
            clearUnlocked()
            return null
        }
        val raw = runCatching { storeFile.readBytes() }.getOrNull() ?: return null
        if (raw.size < MIN_RECORD_BYTES || raw[0] != FORMAT_VERSION) {
            clearUnlocked()
            return null
        }
        val ivLen = raw[1].toInt() and 0xFF
        if (ivLen !in 12..16 || raw.size <= HEADER_BYTES + ivLen) {
            clearUnlocked()
            return null
        }
        val iv = raw.copyOfRange(HEADER_BYTES, HEADER_BYTES + ivLen)
        val ciphertext = raw.copyOfRange(HEADER_BYTES + ivLen, raw.size)
        var plaintext: ByteArray? = null
        return try {
            val key = existingKey() ?: run {
                clearUnlocked()
                return null
            }
            val cipher = Cipher.getInstance(CIPHER_TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD(AAD_V1)
            }
            plaintext = cipher.doFinal(ciphertext)
            decodeSession(plaintext)
        } catch (_: Exception) {
            clearUnlocked()
            null
        } finally {
            plaintext?.fill(0)
        }
    }

    override fun save(session: PersistedYoutubeMusicSession) {
        synchronized(lock) {
            var plaintext: ByteArray? = null
            try {
                appContext.noBackupFilesDir.mkdirs()
                val key = getOrCreateKey()
                plaintext = encodeSession(session)
                val cipher = Cipher.getInstance(CIPHER_TRANSFORM).apply {
                    init(Cipher.ENCRYPT_MODE, key)
                    updateAAD(AAD_V1)
                }
                val iv = cipher.iv
                if (iv == null || iv.size !in 12..16) error("Invalid AES-GCM IV")
                val ciphertext = cipher.doFinal(plaintext)
                val framed = ByteArrayOutputStream(HEADER_BYTES + iv.size + ciphertext.size).apply {
                    write(FORMAT_VERSION.toInt())
                    write(iv.size)
                    write(iv)
                    write(ciphertext)
                }.toByteArray()
                tempFile.writeBytes(framed)
                runCatching {
                    Files.move(
                        tempFile.toPath(),
                        storeFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                }.onFailure {
                    Files.move(
                        tempFile.toPath(),
                        storeFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } catch (_: Exception) {
                clearUnlocked()
            } finally {
                plaintext?.fill(0)
                runCatching { if (tempFile.exists()) tempFile.delete() }
            }
        }
    }

    override fun clear() {
        synchronized(lock) { clearUnlocked() }
    }

    private fun clearUnlocked() {
        runCatching { if (storeFile.exists()) storeFile.delete() }
        runCatching { if (tempFile.exists()) tempFile.delete() }
    }

    private fun encodeSession(session: PersistedYoutubeMusicSession): ByteArray {
        val node = mapper.createObjectNode().apply {
            put("cookies", session.netscapeCookies)
            put("source", session.source.name)
            put("enabledForDownloads", session.enabledForDownloads)
            put("tier", session.tier.name)
            put("needsReauth", session.needsReauth)
            put("updatedAtMillis", session.updatedAtMillis)
        }
        return mapper.writeValueAsBytes(node)
    }

    private fun decodeSession(bytes: ByteArray): PersistedYoutubeMusicSession? {
        val tree = mapper.readTree(bytes) ?: return null
        val cookies = tree.path("cookies").takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() } ?: return null
        val source = runCatching {
            YoutubeMusicAuthSource.valueOf(tree.path("source").asText(YoutubeMusicAuthSource.COOKIES_TXT.name))
        }.getOrDefault(YoutubeMusicAuthSource.COOKIES_TXT)
        val tier = runCatching {
            YoutubeMusicPremiumTier.valueOf(tree.path("tier").asText(YoutubeMusicPremiumTier.UNKNOWN.name))
        }.getOrDefault(YoutubeMusicPremiumTier.UNKNOWN)
        return PersistedYoutubeMusicSession(
            netscapeCookies = cookies,
            source = source,
            enabledForDownloads = tree.path("enabledForDownloads").asBoolean(true),
            tier = tier,
            needsReauth = tree.path("needsReauth").asBoolean(false),
            updatedAtMillis = tree.path("updatedAtMillis").asLong(0L),
        )
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun existingKey(): SecretKey? = runCatching {
        loadKeyStore().getKey(KEY_ALIAS, null) as? SecretKey
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val ks = loadKeyStore()
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        runCatching { if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS) }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "swararia_yt_music_account_v1"
        const val FILE_NAME = "yt-music-account.v1.enc"
        const val CIPHER_TRANSFORM = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val HEADER_BYTES = 2
        const val GCM_TAG_BITS = 128
        const val MIN_RECORD_BYTES = 32L
        const val MAX_RECORD_BYTES = 131_072L
        val AAD_V1 = byteArrayOf(FORMAT_VERSION)
    }
}

/** Verifies session health (and Premium logo badge when rendered) over HTTPS without leaking cookies. */
class YoutubeMusicWebTierVerifier(context: Context) : YoutubeMusicTierVerifier {
    @Suppress("unused")
    private val appContext = context.applicationContext

    private enum class PageStatus { HTML, UNAUTHENTICATED, INCONCLUSIVE }
    private data class FetchedPage(val status: PageStatus, val html: String = "", val httpCode: Int = 0)

    private val desktopChromeUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private val allowedYoutubeHosts = setOf(
        "www.youtube.com",
        "youtube.com",
        "music.youtube.com",
        "m.youtube.com",
    )

    override suspend fun check(netscapeCookies: String): YoutubeMusicTierCheckResult = withContext(Dispatchers.IO) {
        val www = fetchYoutubePage("https://www.youtube.com/", netscapeCookies)
        if (www.status == PageStatus.UNAUTHENTICATED) {
            android.util.Log.i("SwarariaYtMusic", "tier_check www=unauth code=${www.httpCode}")
            return@withContext YoutubeMusicTierCheckResult.Unauthenticated
        }
        val wwwLoggedOut = www.status == PageStatus.HTML && www.html.contains("\"LOGGED_IN\":false")
        if (wwwLoggedOut) {
            android.util.Log.i("SwarariaYtMusic", "tier_check www=logged_out code=${www.httpCode}")
            return@withContext YoutubeMusicTierCheckResult.Unauthenticated
        }
        if (www.status == PageStatus.HTML && hasPremiumMarker(www.html)) {
            android.util.Log.i("SwarariaYtMusic", "tier_check www=premium code=${www.httpCode}")
            return@withContext YoutubeMusicTierCheckResult.Premium
        }

        val wwwLoggedIn = www.status == PageStatus.HTML && www.html.contains("\"LOGGED_IN\":true")
        val music = fetchYoutubePage("https://music.youtube.com/", netscapeCookies)
        if (music.status == PageStatus.UNAUTHENTICATED && !wwwLoggedIn) {
            android.util.Log.i("SwarariaYtMusic", "tier_check music=unauth wwwCode=${www.httpCode} musicCode=${music.httpCode}")
            return@withContext YoutubeMusicTierCheckResult.Unauthenticated
        }
        val musicLoggedOut = music.status == PageStatus.HTML && music.html.contains("\"LOGGED_IN\":false")
        if (musicLoggedOut && !wwwLoggedIn) {
            android.util.Log.i("SwarariaYtMusic", "tier_check music=logged_out wwwCode=${www.httpCode} musicCode=${music.httpCode}")
            return@withContext YoutubeMusicTierCheckResult.Unauthenticated
        }
        if (music.status == PageStatus.HTML && hasPremiumMarker(music.html)) {
            android.util.Log.i("SwarariaYtMusic", "tier_check music=premium wwwCode=${www.httpCode} musicCode=${music.httpCode}")
            return@withContext YoutubeMusicTierCheckResult.Premium
        }

        val musicLoggedIn = music.status == PageStatus.HTML && music.html.contains("\"LOGGED_IN\":true")
        val musicNotSub = music.status == PageStatus.HTML && (
            music.html.contains("\"IS_SUBSCRIBER\":false") ||
                music.html.contains("\\x22IS_SUBSCRIBER\\x22:false")
            )
        val stdLogo = www.status == PageStatus.HTML && www.html.contains("\"iconType\":\"YOUTUBE_LOGO\"")
        val hasTopbar = www.status == PageStatus.HTML && www.html.contains("\"topbarLogoRenderer\"")
        if (wwwLoggedIn && stdLogo && musicLoggedIn && musicNotSub) {
            android.util.Log.i(
                "SwarariaYtMusic",
                "tier_check free_account wwwCode=${www.httpCode} wwwLen=${www.html.length} stdLogo=$stdLogo musicCode=${music.httpCode} musicLen=${music.html.length} musicNotSub=$musicNotSub",
            )
            return@withContext YoutubeMusicTierCheckResult.FreeAccount
        }
        android.util.Log.i(
            "SwarariaYtMusic",
            "tier_check inconclusive wwwCode=${www.httpCode} wwwLen=${www.html.length} wwwLoggedIn=$wwwLoggedIn topbar=$hasTopbar stdLogo=$stdLogo musicCode=${music.httpCode} musicLen=${music.html.length} musicLoggedIn=$musicLoggedIn musicNotSub=$musicNotSub",
        )
        YoutubeMusicTierCheckResult.Inconclusive
    }

    private fun fetchYoutubePage(startUrl: String, netscapeCookies: String): FetchedPage {
        var currentUrl = startUrl
        for (hop in 0..3) {
            val cookieHeader = YoutubeMusicCookieRules.cookieHeaderForUrl(netscapeCookies, currentUrl)
            if (cookieHeader.isBlank()) return FetchedPage(PageStatus.UNAUTHENTICATED)
            var connection: HttpURLConnection? = null
            try {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpsURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 5_000
                    readTimeout = 8_000
                    useCaches = false
                    setRequestProperty("Cookie", cookieHeader)
                    setRequestProperty("User-Agent", desktopChromeUserAgent)
                    setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                }
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location").orEmpty()
                    if (location.isBlank()) return FetchedPage(PageStatus.INCONCLUSIVE, httpCode = code)
                    val nextUrl = URL(url, location)
                    val nextHost = nextUrl.host.orEmpty().lowercase()
                    if (location.contains("ServiceLogin") ||
                        nextHost == "accounts.google.com" ||
                        nextHost.startsWith("accounts.google.")
                    ) {
                        return FetchedPage(PageStatus.UNAUTHENTICATED, httpCode = code)
                    }
                    if (!nextUrl.protocol.equals("https", ignoreCase = true) || nextHost !in allowedYoutubeHosts) {
                        return FetchedPage(PageStatus.INCONCLUSIVE, httpCode = code)
                    }
                    currentUrl = nextUrl.toString()
                    continue
                }
                if (code !in 200..299) return FetchedPage(PageStatus.INCONCLUSIVE, httpCode = code)
                return FetchedPage(PageStatus.HTML, html = readBoundedUtf8(connection), httpCode = code)
            } catch (_: Exception) {
                return FetchedPage(PageStatus.INCONCLUSIVE)
            } finally {
                connection?.disconnect()
            }
        }
        return FetchedPage(PageStatus.INCONCLUSIVE)
    }

    private fun hasPremiumMarker(html: String): Boolean {
        if (html.contains("\"IS_SUBSCRIBER\":true") ||
            html.contains("\\x22IS_SUBSCRIBER\\x22:true") ||
            html.contains("\"iconType\":\"YOUTUBE_PREMIUM_LOGO\"") ||
            html.contains("\\x22iconType\\x22:\\x22YOUTUBE_PREMIUM_LOGO\\x22") ||
            html.contains("\"iconType\":\"MUSIC_PREMIUM_LOGO\"") ||
            html.contains("\\x22iconType\\x22:\\x22MUSIC_PREMIUM_LOGO\\x22")
        ) {
            return true
        }
        val logoIdx = html.lastIndexOf("\"topbarLogoRenderer\"")
        if (logoIdx >= 0) {
            val snippet = html.substring(logoIdx, minOf(html.length, logoIdx + 500))
            if (snippet.contains("Premium", ignoreCase = true)) return true
        }
        return false
    }

    private fun readBoundedUtf8(connection: HttpURLConnection): String {
        val stream = connection.inputStream ?: return ""
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        stream.use { input ->
            while (total < MAX_HTML_BYTES) {
                val read = input.read(buffer, 0, minOf(buffer.size, MAX_HTML_BYTES - total))
                if (read <= 0) break
                out.write(buffer, 0, read)
                total += read
            }
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private companion object {
        const val MAX_HTML_BYTES = 4_000_000
    }
}

fun clearAndroidWebViewYoutubeSession() {
    val action = Runnable {
        runCatching {
            CookieManager.getInstance().apply {
                removeAllCookies(null)
                flush()
            }
        }
        runCatching { WebStorage.getInstance().deleteAllData() }
    }
    if (Looper.myLooper() == Looper.getMainLooper()) action.run()
    else Handler(Looper.getMainLooper()).post(action)
}
