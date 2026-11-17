package com.swararia.audiolab.ytmusic

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import java.util.Locale

enum class YoutubeMusicAuthSource { NONE, WEBVIEW, COOKIES_TXT }

enum class YoutubeMusicPremiumTier { UNKNOWN, FREE, PREMIUM }

/** Safe, secret-free UI state for the optional YouTube Music account integration. */
data class YoutubeMusicAccountState(
    val connected: Boolean = false,
    val enabledForDownloads: Boolean = true,
    val source: YoutubeMusicAuthSource = YoutubeMusicAuthSource.NONE,
    val tier: YoutubeMusicPremiumTier = YoutubeMusicPremiumTier.UNKNOWN,
    val needsReauth: Boolean = false,
    val updatedAtMillis: Long = 0L,
    val checking: Boolean = false,
    val statusNote: String? = null,
)

data class YoutubeMusicAccountActions(
    val onOpenSignIn: () -> Unit = {},
    val onOpenImportCookies: () -> Unit = {},
    val onCompleteWebViewSignIn: (String?, String?) -> Unit = { _, _ -> },
    val onImportCookiesText: (String) -> Unit = {},
    val onToggleEnabled: (Boolean) -> Unit = {},
    val onVerifyAccount: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
)

data class PersistedYoutubeMusicSession(
    val netscapeCookies: String,
    val source: YoutubeMusicAuthSource,
    val enabledForDownloads: Boolean = true,
    val tier: YoutubeMusicPremiumTier = YoutubeMusicPremiumTier.UNKNOWN,
    val needsReauth: Boolean = false,
    val updatedAtMillis: Long = 0L,
)

interface YoutubeMusicSessionStore {
    fun load(): PersistedYoutubeMusicSession?
    fun save(session: PersistedYoutubeMusicSession)
    fun clear()
}

data class YoutubeCookieEntry(
    val domain: String,
    val includeSubdomains: Boolean,
    val path: String,
    val secure: Boolean,
    val expiresEpoch: Long,
    val name: String,
    val value: String,
) {
    fun toNetscapeLine(): String = listOf(
        domain,
        if (includeSubdomains) "TRUE" else "FALSE",
        path,
        if (secure) "TRUE" else "FALSE",
        expiresEpoch.toString(),
        name,
        value,
    ).joinToString("\t")
}

/** Strict validation and least-privilege filtering for optional YouTube Music cookies. */
object YoutubeMusicCookieRules {
    const val NETSCAPE_HEADER = "# Netscape HTTP Cookie File"
    const val MAX_RAW_IMPORT_BYTES = 256 * 1024
    const val MAX_STORED_COOKIES_BYTES = 48 * 1024
    const val MAX_COOKIE_COUNT = 100

    private val ALLOWED_COOKIE_DOMAINS = setOf(
        ".youtube.com",
        "youtube.com",
        "www.youtube.com",
        "music.youtube.com",
        "m.youtube.com",
    )

    private val AUTH_SID_NAMES = setOf(
        "SAPISID",
        "__Secure-1PAPISID",
        "__Secure-3PAPISID",
    )

    private val ALLOWED_LOGIN_HOSTS = setOf(
        "youtube.com",
        "youtu.be",
        "accounts.google.com",
        "accounts.youtube.com",
        "myaccount.google.com",
        "consent.google.com",
        "consent.youtube.com",
        "policies.google.com",
        "gds.google.com",
        "ogs.google.com",
        "families.google.com",
        "www.google.com",
        "gstatic.com",
        "googleusercontent.com",
        "ggpht.com",
        "ytimg.com",
    )

    private val TOKEN_REGEX = Regex("[!#$%&'*+\\-.^_`|~0-9A-Za-z]+")
    private val EXPIRY_REGEX = Regex("([0-9]+)(?:\\.[0-9]+)?")
    private val REGIONAL_GOOGLE_ACCOUNTS_REGEX = Regex("accounts\\.google\\.(?:com|[a-z]{2}|(?:co|com)\\.[a-z]{2})")

    fun hasRequiredAuthCookies(names: Set<String>): Boolean =
        "LOGIN_INFO" in names && names.any { it in AUTH_SID_NAMES }

    fun hasRequiredAuthCookiesInHeader(cookieHeader: String?): Boolean {
        if (cookieHeader.isNullOrBlank()) return false
        val names = parseHeaderPairs(cookieHeader).mapTo(HashSet()) { it.first }
        return hasRequiredAuthCookies(names)
    }

    fun isAllowedLoginUrl(rawUrl: String): Boolean {
        val uri = runCatching { URI(rawUrl.trim()) }.getOrNull() ?: return false
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return false
        if (uri.rawUserInfo != null) return false
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        if (REGIONAL_GOOGLE_ACCOUNTS_REGEX.matches(host)) return true
        return ALLOWED_LOGIN_HOSTS.any { allowed ->
            host == allowed || host.endsWith(".$allowed")
        }
    }

    fun fromWebViewCookieStrings(wwwYoutubeCookies: String?, musicYoutubeCookies: String?): String {
        val entries = linkedMapOf<String, YoutubeCookieEntry>()
        for ((name, value) in parseHeaderPairs(wwwYoutubeCookies.orEmpty())) {
            val hostOnly = name.startsWith("__Host-")
            val entry = YoutubeCookieEntry(
                domain = if (hostOnly) "www.youtube.com" else ".youtube.com",
                includeSubdomains = !hostOnly,
                path = "/",
                secure = name.startsWith("__Secure-") || hostOnly || name in AUTH_SID_NAMES || name == "LOGIN_INFO",
                expiresEpoch = 0L,
                name = name,
                value = value,
            )
            entries["${entry.domain}\t${entry.name}"] = entry
        }
        for ((name, value) in parseHeaderPairs(musicYoutubeCookies.orEmpty())) {
            val hostOnly = name.startsWith("__Host-")
            val hasShared = entries.containsKey(".youtube.com\t$name") || entries.containsKey("www.youtube.com\t$name")
            if (!hostOnly && hasShared) continue
            val entry = YoutubeCookieEntry(
                domain = if (hostOnly) "music.youtube.com" else ".youtube.com",
                includeSubdomains = !hostOnly,
                path = "/",
                secure = name.startsWith("__Secure-") || hostOnly || name in AUTH_SID_NAMES || name == "LOGIN_INFO",
                expiresEpoch = 0L,
                name = name,
                value = value,
            )
            entries["${entry.domain}\t${entry.name}"] = entry
        }
        val names = entries.values.mapTo(HashSet()) { it.name }
        require(hasRequiredAuthCookies(names)) {
            "Sesi login YouTube Music belum lengkap. Selesaikan login akun Google terlebih dahulu."
        }
        return formatEntries(entries.values.toList())
    }

    fun sanitizeNetscapeCookies(
        rawText: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): String {
        require(rawText.toByteArray(Charsets.UTF_8).size <= MAX_RAW_IMPORT_BYTES) {
            "File cookies terlalu besar (maksimal 256 KB)."
        }
        val trimmedStart = rawText.trimStart()
        require(trimmedStart.isNotEmpty()) { "Isi cookies masih kosong." }
        require(!trimmedStart.startsWith("{") && !trimmedStart.startsWith("[")) {
            "Gunakan format Netscape cookies.txt, bukan JSON."
        }

        val entries = linkedMapOf<String, YoutubeCookieEntry>()
        for (rawLine in rawText.lineSequence()) {
            val cleanLine = rawLine.trimEnd('\r')
            if (cleanLine.isBlank()) continue
            val line = if (cleanLine.startsWith("#HttpOnly_")) {
                cleanLine.removePrefix("#HttpOnly_")
            } else {
                if (cleanLine.startsWith("#")) continue
                cleanLine
            }
            val parts = line.split('\t')
            if (parts.size != 7) continue

            val domain = parts[0].trim().lowercase(Locale.ROOT)
            if (domain !in ALLOWED_COOKIE_DOMAINS) continue

            val path = parts[2].trim()
            if (!path.startsWith("/") || path.any { it.isISOControl() || it.isWhitespace() }) continue

            val expiryMatch = EXPIRY_REGEX.matchEntire(parts[4].trim()) ?: continue
            val expiry = expiryMatch.groupValues[1].toLongOrNull() ?: continue
            if (expiry in 1L..nowEpochSeconds) continue

            val name = parts[5].trim()
            val value = parts[6]
            if (!isValidCookieName(name) || !isValidCookieValue(value)) continue

            val includeSubdomains = parts[1].trim().equals("TRUE", ignoreCase = true) || domain.startsWith(".")
            val secure = parts[3].trim().equals("TRUE", ignoreCase = true)
            val entry = YoutubeCookieEntry(
                domain = domain,
                includeSubdomains = includeSubdomains,
                path = path,
                secure = secure,
                expiresEpoch = expiry,
                name = name,
                value = value,
            )
            entries["${entry.domain}\t${entry.path}\t${entry.name}"] = entry
        }

        val list = entries.values.toList()
        require(list.isNotEmpty()) {
            "Tidak ada cookie YouTube yang valid ditemukan di file ini."
        }
        val names = list.mapTo(HashSet()) { it.name }
        require(hasRequiredAuthCookies(names)) {
            "Cookie YouTube belum masuk (pastikan Anda login di music.youtube.com sebelum ekspor cookies.txt)."
        }
        return formatEntries(list)
    }

    fun countCookies(netscapeCookies: String): Int =
        parseSanitizedEntries(netscapeCookies).size

    fun cookieHeaderForUrl(netscapeCookies: String, httpsUrl: String): String {
        val uri = runCatching { URI(httpsUrl) }.getOrNull() ?: return ""
        if (uri.scheme?.lowercase(Locale.ROOT) != "https") return ""
        val host = uri.host?.lowercase(Locale.ROOT) ?: return ""
        val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
        val matched = linkedMapOf<String, String>()
        for (entry in parseSanitizedEntries(netscapeCookies)) {
            val domainMatches = if (entry.domain.startsWith(".")) {
                val base = entry.domain.removePrefix(".")
                host == base || host.endsWith(".${base}")
            } else {
                host == entry.domain || (entry.includeSubdomains && host.endsWith(".${entry.domain}"))
            }
            if (!domainMatches) continue
            if (!path.startsWith(entry.path)) continue
            matched[entry.name] = entry.value
        }
        return matched.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun parseSanitizedEntries(netscapeCookies: String): List<YoutubeCookieEntry> {
        val out = mutableListOf<YoutubeCookieEntry>()
        for (rawLine in netscapeCookies.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split('\t')
            if (parts.size != 7) continue
            val domain = parts[0]
            val expiry = parts[4].toLongOrNull() ?: continue
            val name = parts[5]
            val value = parts[6]
            if (domain !in ALLOWED_COOKIE_DOMAINS || !isValidCookieName(name) || !isValidCookieValue(value)) continue
            out += YoutubeCookieEntry(
                domain = domain,
                includeSubdomains = parts[1].equals("TRUE", ignoreCase = true),
                path = parts[2],
                secure = parts[3].equals("TRUE", ignoreCase = true),
                expiresEpoch = expiry,
                name = name,
                value = value,
            )
        }
        return out
    }

    private fun parseHeaderPairs(rawHeader: String): List<Pair<String, String>> {
        if (rawHeader.isBlank() || rawHeader.length > MAX_RAW_IMPORT_BYTES) return emptyList()
        val pairs = mutableListOf<Pair<String, String>>()
        for (segment in rawHeader.split(';')) {
            val eq = segment.indexOf('=')
            if (eq <= 0) continue
            val name = segment.substring(0, eq).trim()
            val value = segment.substring(eq + 1).trim()
            if (isValidCookieName(name) && isValidCookieValue(value)) {
                pairs += name to value
            }
        }
        return pairs
    }

    private fun isValidCookieName(name: String): Boolean =
        name.length in 1..128 && TOKEN_REGEX.matches(name)

    private fun isValidCookieValue(value: String): Boolean =
        value.length <= 8192 && value.none { it.isISOControl() || it == '\t' }

    private fun formatEntries(entries: List<YoutubeCookieEntry>): String {
        require(entries.size <= MAX_COOKIE_COUNT) {
            "Jumlah cookie YouTube melebihi batas aman ($MAX_COOKIE_COUNT)."
        }
        val serialized = buildString {
            append(NETSCAPE_HEADER).append('\n')
            for (entry in entries) {
                append(entry.toNetscapeLine()).append('\n')
            }
        }
        require(serialized.toByteArray(Charsets.UTF_8).size <= MAX_STORED_COOKIES_BYTES) {
            "Ukuran cookie YouTube melebihi batas aman (48 KB)."
        }
        return serialized
    }
}

sealed interface YoutubeMusicTierCheckResult {
    data object Premium : YoutubeMusicTierCheckResult
    data object FreeAccount : YoutubeMusicTierCheckResult
    data object Unauthenticated : YoutubeMusicTierCheckResult
    data object Inconclusive : YoutubeMusicTierCheckResult
}

fun interface YoutubeMusicTierVerifier {
    suspend fun check(netscapeCookies: String): YoutubeMusicTierCheckResult
}

/** Coordinates hardware-encrypted YT Music session state shared between Settings and IngestService. */
class YoutubeMusicAccountManager(
    private val store: YoutubeMusicSessionStore,
    private val verifier: YoutubeMusicTierVerifier = YoutubeMusicTierVerifier { YoutubeMusicTierCheckResult.Inconclusive },
    private val onPlatformCookiesCleared: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val stateMutex = Mutex()
    private val mutable = MutableStateFlow(YoutubeMusicAccountState())
    val state: StateFlow<YoutubeMusicAccountState> = mutable.asStateFlow()

    @Volatile private var cachedSession: PersistedYoutubeMusicSession? = null
    @Volatile private var initialized = false
    private var initJob: Job? = null

    fun initialize(): Job = synchronized(this) {
        initJob?.let { return it }
        scope.async {
            stateMutex.withLock {
                if (initialized) return@withLock
                val loaded = runCatching { store.load() }.getOrNull()
                if (loaded != null) {
                    val sanitized = runCatching {
                        YoutubeMusicCookieRules.sanitizeNetscapeCookies(loaded.netscapeCookies, nowMillis() / 1000L)
                    }.getOrNull()
                    if (sanitized != null) {
                        val normalized = loaded.copy(netscapeCookies = sanitized)
                        cachedSession = normalized
                        publishState(normalized)
                    } else {
                        runCatching { store.clear() }
                        cachedSession = null
                        mutable.value = YoutubeMusicAccountState(
                            needsReauth = true,
                            statusNote = "Sesi YouTube Music kadaluarsa; silakan masuk ulang.",
                        )
                    }
                }
                initialized = true
            }
        }.also { initJob = it }
    }

    private suspend fun ensureInitialized() {
        val job = initialize()
        job.join()
    }

    suspend fun connectFromWebView(wwwCookies: String?, musicCookies: String?): YoutubeMusicAccountState {
        val netscape = YoutubeMusicCookieRules.fromWebViewCookieStrings(wwwCookies, musicCookies)
        val saved = saveValidatedSession(netscape, YoutubeMusicAuthSource.WEBVIEW, verifyNow = true)
        runCatching { onPlatformCookiesCleared() }
        return saved
    }

    suspend fun connectFromNetscapeText(rawText: String, source: YoutubeMusicAuthSource = YoutubeMusicAuthSource.COOKIES_TXT): YoutubeMusicAccountState {
        val netscape = YoutubeMusicCookieRules.sanitizeNetscapeCookies(rawText, nowMillis() / 1000L)
        return saveValidatedSession(netscape, source, verifyNow = true)
    }

    private suspend fun saveValidatedSession(
        netscapeCookies: String,
        source: YoutubeMusicAuthSource,
        verifyNow: Boolean,
    ): YoutubeMusicAccountState {
        ensureInitialized()
        val updated = stateMutex.withLock {
            val session = PersistedYoutubeMusicSession(
                netscapeCookies = netscapeCookies,
                source = source,
                enabledForDownloads = cachedSession?.enabledForDownloads ?: true,
                tier = YoutubeMusicPremiumTier.UNKNOWN,
                needsReauth = false,
                updatedAtMillis = nowMillis(),
            )
            store.save(session)
            cachedSession = session
            publishState(session, checking = verifyNow)
        }
        return if (verifyNow) verifyAccount() else updated
    }

    suspend fun setEnabledForDownloads(enabled: Boolean): YoutubeMusicAccountState {
        ensureInitialized()
        return stateMutex.withLock {
            val current = cachedSession ?: return@withLock mutable.value
            val updated = current.copy(enabledForDownloads = enabled)
            store.save(updated)
            cachedSession = updated
            publishState(updated)
        }
    }

    suspend fun verifyAccount(): YoutubeMusicAccountState {
        ensureInitialized()
        val netscape = stateMutex.withLock {
            val current = cachedSession ?: return@withLock null
            mutable.update { it.copy(checking = true, statusNote = "Memeriksa status akun YouTube Music...") }
            current.netscapeCookies
        } ?: return mutable.value

        val result = runCatching { verifier.check(netscape) }.getOrDefault(YoutubeMusicTierCheckResult.Inconclusive)
        return stateMutex.withLock {
            val current = cachedSession
            if (current == null || current.netscapeCookies != netscape) {
                mutable.update { it.copy(checking = false) }
                return@withLock mutable.value
            }
            val updated = when (result) {
                YoutubeMusicTierCheckResult.Premium -> current.copy(tier = YoutubeMusicPremiumTier.PREMIUM, needsReauth = false)
                YoutubeMusicTierCheckResult.FreeAccount -> current.copy(tier = YoutubeMusicPremiumTier.FREE, needsReauth = false)
                YoutubeMusicTierCheckResult.Unauthenticated -> current.copy(needsReauth = true)
                YoutubeMusicTierCheckResult.Inconclusive -> current
            }
            if (updated != current) {
                runCatching { store.save(updated) }
                cachedSession = updated
            }
            publishState(updated, checking = false, overrideNote = when (result) {
                YoutubeMusicTierCheckResult.Premium -> "Akun Premium terdeteksi (siap unduh kualitas tinggi)."
                YoutubeMusicTierCheckResult.FreeAccount -> "Akun standar (non-Premium); kualitas tetap 251 Opus."
                YoutubeMusicTierCheckResult.Unauthenticated -> "Sesi ditolak atau kadaluarsa; silakan masuk ulang."
                YoutubeMusicTierCheckResult.Inconclusive -> "Terhubung (status Premium dicek saat mengunduh)."
            })
        }
    }

    /** Returns sanitized Netscape cookies strictly for IngestService when enabled and healthy. */
    suspend fun activeNetscapeCookiesForDownload(): String? {
        ensureInitialized()
        return stateMutex.withLock {
            val current = cachedSession ?: return@withLock null
            if (!current.enabledForDownloads || current.needsReauth) return@withLock null
            runCatching {
                YoutubeMusicCookieRules.sanitizeNetscapeCookies(current.netscapeCookies, nowMillis() / 1000L)
            }.getOrNull()
        }
    }

    suspend fun refreshFromYtDlpOutput(updatedCookiesText: String) {
        ensureInitialized()
        val sanitized = runCatching {
            YoutubeMusicCookieRules.sanitizeNetscapeCookies(updatedCookiesText, nowMillis() / 1000L)
        }.getOrNull()
        if (sanitized == null) {
            markNeedsReauth()
            return
        }
        stateMutex.withLock {
            val current = cachedSession ?: return@withLock
            if (current.netscapeCookies != sanitized || current.needsReauth) {
                val updated = current.copy(
                    netscapeCookies = sanitized,
                    needsReauth = false,
                    updatedAtMillis = nowMillis(),
                )
                runCatching { store.save(updated) }
                cachedSession = updated
                publishState(updated)
            }
        }
    }

    suspend fun markNeedsReauth() {
        ensureInitialized()
        stateMutex.withLock {
            val current = cachedSession ?: return@withLock
            if (!current.needsReauth) {
                val updated = current.copy(needsReauth = true)
                runCatching { store.save(updated) }
                cachedSession = updated
                publishState(updated, overrideNote = "Sesi kadaluarsa — masuk ulang untuk audio Premium.")
            }
        }
    }

    suspend fun confirmPremiumFromDownload(formatSummary: String = "256k AAC M4A") {
        ensureInitialized()
        stateMutex.withLock {
            val current = cachedSession ?: return@withLock
            if (current.tier != YoutubeMusicPremiumTier.PREMIUM || current.needsReauth) {
                val updated = current.copy(tier = YoutubeMusicPremiumTier.PREMIUM, needsReauth = false)
                runCatching { store.save(updated) }
                cachedSession = updated
                publishState(updated, overrideNote = "Akun Premium terkonfirmasi ($formatSummary).")
            }
        }
    }

    suspend fun disconnect(): YoutubeMusicAccountState {
        ensureInitialized()
        return stateMutex.withLock {
            cachedSession = null
            runCatching { store.clear() }
            runCatching { onPlatformCookiesCleared() }
            YoutubeMusicAccountState(
                connected = false,
                statusNote = "Akun YouTube Music diputuskan.",
            ).also { mutable.value = it }
        }
    }

    private fun publishState(
        session: PersistedYoutubeMusicSession,
        checking: Boolean = false,
        overrideNote: String? = null,
    ): YoutubeMusicAccountState {
        val next = YoutubeMusicAccountState(
            connected = true,
            enabledForDownloads = session.enabledForDownloads,
            source = session.source,
            tier = session.tier,
            needsReauth = session.needsReauth,
            updatedAtMillis = session.updatedAtMillis,
            checking = checking,
            statusNote = overrideNote ?: mutable.value.statusNote,
        )
        mutable.value = next
        return next
    }
}
