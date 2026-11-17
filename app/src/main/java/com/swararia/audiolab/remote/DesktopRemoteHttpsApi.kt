package com.swararia.audiolab.remote

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.CookieHandler
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory
import javax.net.ssl.*

interface RemoteHttpsConnectionOpener {
    fun open(url: URL, targetHost: String): Pair<HttpsURLConnection, SocketFactory?>
}

/** Trust is scoped to one OOB certificate and endpoint. Default HTTPS hostname verification remains enabled. */
class DesktopRemoteTrustManager(private val pin: ByteArray, private val host: String) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) { throw CertificateException("Not a client authority") }
    override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
        try {
            if (chain == null || chain.size != 1 || pin.size != 32) throw CertificateException()
            val cert = chain[0]
            if (!MessageDigest.isEqual(pin, MessageDigest.getInstance("SHA-256").digest(cert.encoded))) throw CertificateException()
            cert.checkValidity()
            if (cert.issuerX500Principal != cert.subjectX500Principal || cert.basicConstraints != -1 ||
                cert.publicKey.algorithm != "EC" || cert.sigAlgOID != "1.2.840.10045.4.3.2" ||
                cert.extendedKeyUsage?.contains("1.3.6.1.5.5.7.3.1") != true) throw CertificateException()
            cert.verify(cert.publicKey)
            if (cert.subjectAlternativeNames?.any { it[0] == 7 && it[1] == host } != true) throw CertificateException()
        } catch (_: Exception) { throw CertificateException("Desktop certificate rejected") }
    }
}

private class DesktopRemoteSocketFactory(
    private val delegate: SSLSocketFactory,
    private val boundSocketFactory: SocketFactory? = null,
    private val connectTimeoutMs: Int = 4000,
    private val registerSocket: (Socket) -> Unit = {},
) : SSLSocketFactory() {
    private fun secure(socket: Socket): Socket = (socket as SSLSocket).apply {
        enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        registerSocket(this)
    }
    private fun connectBound(address: InetSocketAddress, local: InetSocketAddress? = null): Socket {
        val raw = boundSocketFactory!!.createSocket()
        return try {
            if (local != null) raw.bind(local)
            raw.connect(address, connectTimeoutMs)
            secure(delegate.createSocket(raw, address.hostString, address.port, true))
        } catch (t: Throwable) {
            runCatching { raw.close() }
            throw t
        }
    }
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(): Socket = secure(delegate.createSocket())
    override fun createSocket(s: Socket, h: String, p: Int, close: Boolean): Socket = secure(delegate.createSocket(s, h, p, close))
    override fun createSocket(h: String, p: Int): Socket =
        if (boundSocketFactory != null) connectBound(InetSocketAddress(h, p)) else secure(delegate.createSocket(h, p))
    override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket =
        if (boundSocketFactory != null) connectBound(InetSocketAddress(h, p), InetSocketAddress(l, lp))
        else secure(delegate.createSocket(h, p, l, lp))
    override fun createSocket(h: InetAddress, p: Int): Socket =
        if (boundSocketFactory != null) connectBound(InetSocketAddress(h, p)) else secure(delegate.createSocket(h, p))
    override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket =
        if (boundSocketFactory != null) connectBound(InetSocketAddress(h, p), InetSocketAddress(l, lp))
        else secure(delegate.createSocket(h, p, l, lp))
}

/** Injected port: state ownership stays testable without Android or a listener. */
interface DesktopRemoteApi {
    suspend fun pair(pairing: DesktopRemotePairing): DesktopRemoteGrant
    suspend fun session(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant): DesktopRemoteApproval
    suspend fun capabilities(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant): Set<String>
    suspend fun capabilitiesInfo(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant): DesktopRemoteCapabilities =
        DesktopRemoteCapabilities(commands = capabilities(pairing, grant))
    suspend fun state(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant): DesktopRemotePlayback
    suspend fun tracks(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant): DesktopRemoteCatalog =
        DesktopRemoteCatalog(online = true, version = 0, playlist = null, currentTrackId = null, tracks = emptyList())
    suspend fun headTrackMedia(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, trackId: String): DesktopRemoteMediaHead =
        throw DesktopRemoteFailure()
    suspend fun downloadTrackMedia(
        pairing: DesktopRemotePairing,
        grant: DesktopRemoteGrant,
        trackId: String,
        destinationFile: File,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): DesktopRemoteMediaHead = throw DesktopRemoteFailure()
    suspend fun command(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, action: String, key: String): DesktopRemoteCommand
    suspend fun selectTrack(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, trackId: String, autoPlay: Boolean, key: String): DesktopRemoteCommand =
        command(pairing, grant, "select_track", key)
    suspend fun selectGroup(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, groupId: String, key: String): DesktopRemoteCommand =
        command(pairing, grant, "select_group", key)
    suspend fun commandStatus(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, id: String): DesktopRemoteCommand
}

class DesktopRemoteHttpsApi(
    private val clientLabelProvider: () -> String = { defaultAndroidClientLabel() },
) : DesktopRemoteApi {
    @Volatile
    private var connectionOpener: RemoteHttpsConnectionOpener? = null

    fun withConnectionOpener(opener: RemoteHttpsConnectionOpener): DesktopRemoteHttpsApi = apply {
        connectionOpener = opener
    }
    override suspend fun pair(pairing: DesktopRemotePairing) = DesktopRemoteJson.grant(
        request(pairing, pairing.invitation(), "/v1/pair", DesktopRemoteJson.pairBody(clientLabelProvider()), 202))
    override suspend fun session(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant) =
        DesktopRemoteJson.approval(request(pairing, grant.authorization(), "/v1/session"))
    override suspend fun capabilities(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant) =
        capabilitiesInfo(pairing, grant).commands
    override suspend fun capabilitiesInfo(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant) =
        DesktopRemoteJson.capabilitiesInfo(request(pairing, grant.authorization(), "/v1/capabilities"))
    override suspend fun state(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant) =
        DesktopRemoteJson.playback(request(pairing, grant.authorization(), "/v1/state"))
    override suspend fun tracks(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant) =
        DesktopRemoteJson.tracks(request(pairing, grant.authorization(), "/v1/tracks", maxBytes = 98_304))
    override suspend fun command(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, action: String, key: String) =
        DesktopRemoteJson.command(request(pairing, grant.authorization(), "/v1/commands", DesktopRemoteJson.commandBody(action, key), 202))
    override suspend fun selectTrack(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, trackId: String, autoPlay: Boolean, key: String) =
        DesktopRemoteJson.command(request(pairing, grant.authorization(), "/v1/commands", DesktopRemoteJson.commandBody("select_track", key, trackId, autoPlay), 202))
    override suspend fun selectGroup(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, groupId: String, key: String) =
        DesktopRemoteJson.command(request(pairing, grant.authorization(), "/v1/commands", DesktopRemoteJson.commandBody("select_group", key, groupId = groupId), 202))
    override suspend fun commandStatus(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, id: String): DesktopRemoteCommand {
        if (!id.matches(Regex("[A-Za-z0-9_-]{24}"))) throw DesktopRemoteFailure()
        return DesktopRemoteJson.command(request(pairing, grant.authorization(), "/v1/commands/$id"))
    }

    override suspend fun headTrackMedia(
        pairing: DesktopRemotePairing,
        grant: DesktopRemoteGrant,
        trackId: String,
    ): DesktopRemoteMediaHead {
        if (!DesktopRemoteJson.isValidTrackId(trackId)) throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.INPUT)
        return withPinnedConnection(
            timeoutMs = 10_000,
            pairing = pairing,
            secret = grant.authorization(),
            path = "/v1/tracks/$trackId/media",
            method = "HEAD",
            readTimeoutMs = 6_000,
        ) { connection ->
            connection.connect()
            val code = connection.responseCode
            if (code != 200) throw mapMediaError(code, null)
            parseMediaHeaders(trackId, connection, maxBytes = 150L * 1024L * 1024L)
        }
    }

    override suspend fun downloadTrackMedia(
        pairing: DesktopRemotePairing,
        grant: DesktopRemoteGrant,
        trackId: String,
        destinationFile: File,
        maxBytes: Long,
        onProgress: (Long, Long) -> Unit,
    ): DesktopRemoteMediaHead {
        if (!DesktopRemoteJson.isValidTrackId(trackId) || maxBytes <= 0L) {
            throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.INPUT)
        }
        return try {
            withPinnedConnection(
                timeoutMs = 120_000,
                pairing = pairing,
                secret = grant.authorization(),
                path = "/v1/tracks/$trackId/media",
                method = "GET",
                readTimeoutMs = 15_000,
                configure = { it.setRequestProperty("Accept", "audio/*, application/octet-stream") },
            ) { connection ->
                connection.connect()
                val code = connection.responseCode
                if (code != 200) {
                    val errBytes = runCatching { readBounded(connection.errorStream, 512) }.getOrNull()
                    throw mapMediaError(code, DesktopRemoteJson.safeErrorCode(errBytes))
                }
                val head = parseMediaHeaders(trackId, connection, maxBytes)
                val digest = MessageDigest.getInstance("SHA-256")
                var totalRead = 0L
                var lastReported = 0L
                var lastReportAt = 0L
                connection.inputStream.use { input ->
                    destinationFile.outputStream().use { output ->
                        val buffer = ByteArray(16_384)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            totalRead += n
                            if (totalRead > maxBytes || totalRead > head.sizeBytes) {
                                throw DesktopRemoteFailure(
                                    DesktopRemoteFailure.Kind.PROTOCOL,
                                    DesktopRemoteFailure.Reason.MEDIA_TOO_LARGE,
                                )
                            }
                            digest.update(buffer, 0, n)
                            output.write(buffer, 0, n)
                            val now = System.currentTimeMillis()
                            if (totalRead - lastReported >= 65_536L || now - lastReportAt >= 80L || totalRead == head.sizeBytes) {
                                lastReported = totalRead
                                lastReportAt = now
                                onProgress(totalRead, head.sizeBytes)
                            }
                        }
                        output.flush()
                    }
                }
                if (totalRead != head.sizeBytes) throw DesktopRemoteFailure()
                val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actualSha.equals(head.sha256, ignoreCase = true)) throw DesktopRemoteFailure()
                head
            }
        } catch (failure: Throwable) {
            runCatching { if (destinationFile.exists()) destinationFile.delete() }
            throw failure
        }
    }

    private fun readBounded(stream: java.io.InputStream?, maxBytes: Int): ByteArray {
        if (stream == null) return ByteArray(0)
        return stream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(2048)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (output.size() + n > maxBytes) throw DesktopRemoteFailure()
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        }
    }

    private fun parseMediaHeaders(
        trackId: String,
        connection: HttpsURLConnection,
        maxBytes: Long,
    ): DesktopRemoteMediaHead {
        val mime = connection.contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (!mime.startsWith("audio/")) throw DesktopRemoteFailure()
        val length = connection.contentLengthLong
        if (length <= 0L || length > maxBytes) {
            throw DesktopRemoteFailure(
                DesktopRemoteFailure.Kind.PROTOCOL,
                if (length > maxBytes) DesktopRemoteFailure.Reason.MEDIA_TOO_LARGE else DesktopRemoteFailure.Reason.NONE,
            )
        }
        val sha256 = connection.getHeaderField("X-Swararia-Sha256")?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (!DesktopRemoteJson.isValidSha256Hex(sha256)) throw DesktopRemoteFailure()
        val rawHeaderName = connection.getHeaderField("X-Swararia-Filename")?.trim().orEmpty()
        val decodedName = if (rawHeaderName.isNotEmpty()) {
            runCatching { URLDecoder.decode(rawHeaderName, "UTF-8") }.getOrDefault("")
        } else ""
        val cleanName = decodedName
            .replace('\\', '/')
            .substringAfterLast('/')
            .filter { !it.isISOControl() }
            .trim()
            .takeUnless { it == "." || it == ".." }
            .orEmpty()
        val ext = cleanName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val finalName = if (ext.isNotEmpty() && cleanName.isNotBlank()) {
            cleanName
        } else {
            val fallbackExt = extensionForMime(mime) ?: throw DesktopRemoteFailure(
                DesktopRemoteFailure.Kind.PROTOCOL,
                DesktopRemoteFailure.Reason.MEDIA_UNSUPPORTED,
            )
            "${cleanName.ifBlank { trackId }}.$fallbackExt"
        }
        return DesktopRemoteMediaHead(trackId, finalName, mime, length, sha256)
    }

    private fun extensionForMime(mime: String): String? = when (mime) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/x-m4a" -> "m4a"
        "audio/aac" -> "aac"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/webm" -> "webm"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/flac", "audio/x-flac" -> "flac"
        else -> null
    }

    private fun mapMediaError(code: Int, errCode: String?): DesktopRemoteFailure {
        if (code == 401) return DesktopRemoteFailure(DesktopRemoteFailure.Kind.AUTH)
        if (errCode in setOf("revoked", "expired", "denied", "unauthorized")) {
            return DesktopRemoteFailure(DesktopRemoteFailure.Kind.AUTH)
        }
        val reason = when {
            errCode == "view_only" -> DesktopRemoteFailure.Reason.MEDIA_VIEW_ONLY
            errCode == "media_disabled" -> DesktopRemoteFailure.Reason.MEDIA_DISABLED
            errCode == "media_busy" || code == 429 -> DesktopRemoteFailure.Reason.MEDIA_BUSY
            errCode == "media_too_large" || code == 413 -> DesktopRemoteFailure.Reason.MEDIA_TOO_LARGE
            errCode == "unsupported_media_format" || code == 415 -> DesktopRemoteFailure.Reason.MEDIA_UNSUPPORTED
            errCode in setOf("unknown_track", "media_unavailable", "media_forbidden", "master_unavailable") || code == 404 || code == 409 ->
                DesktopRemoteFailure.Reason.MEDIA_UNAVAILABLE
            code == 403 -> DesktopRemoteFailure.Reason.MEDIA_DISABLED
            else -> DesktopRemoteFailure.Reason.NONE
        }
        val kind = if (code == 503 || code == 429) DesktopRemoteFailure.Kind.UNAVAILABLE else DesktopRemoteFailure.Kind.PROTOCOL
        return DesktopRemoteFailure(kind, reason)
    }

    private suspend fun request(pairing: DesktopRemotePairing, secret: DesktopRemoteSecret, path: String,
        body: ByteArray? = null, expected: Int = 200, maxBytes: Int = 16_384): com.fasterxml.jackson.databind.JsonNode {
        return withPinnedConnection(
            timeoutMs = 8_000,
            pairing = pairing,
            secret = secret,
            path = path,
            method = if (body == null) "GET" else "POST",
            readTimeoutMs = 4_000,
            configure = { connection ->
                connection.setRequestProperty("Accept", "application/json")
                if (body == null && path == "/v1/capabilities") {
                    connection.setRequestProperty("X-Swararia-Capabilities", "extended-v1")
                }
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setFixedLengthStreamingMode(body.size)
                }
            },
        ) { connection ->
            connection.connect()
            if (body != null) connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            if (code == 401 || code == 403) throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.AUTH)
            if (code != expected) throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.PROTOCOL)
            if (connection.contentType?.substringBefore(';')?.trim() != "application/json" || connection.contentLengthLong > maxBytes) {
                throw DesktopRemoteFailure()
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(2048)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > maxBytes) throw DesktopRemoteFailure()
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            DesktopRemoteJson.objectValue(bytes, maxBytes)
        }
    }

    private suspend fun <T> withPinnedConnection(
        timeoutMs: Long,
        pairing: DesktopRemotePairing,
        secret: DesktopRemoteSecret,
        path: String,
        method: String,
        readTimeoutMs: Int,
        configure: (HttpsURLConnection) -> Unit = {},
        block: suspend (HttpsURLConnection) -> T,
    ): T {
        return try {
            // Socket idle timeouts alone do not bound slow peers or stalled transfers.
            withTimeout(timeoutMs) {
                executePinnedConnection(pairing, secret, path, method, readTimeoutMs, configure, block)
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.UNAVAILABLE)
        }
    }

    private suspend fun <T> executePinnedConnection(
        pairing: DesktopRemotePairing,
        secret: DesktopRemoteSecret,
        path: String,
        method: String,
        readTimeoutMs: Int,
        configure: (HttpsURLConnection) -> Unit,
        block: suspend (HttpsURLConnection) -> T,
    ): T = coroutineScope {
        suspendCancellableCoroutine { continuation ->
            val active = AtomicReference<HttpsURLConnection?>()
            val activeSocket = AtomicReference<Socket?>()
            val cancelled = AtomicBoolean(false)
            fun abortTransport() {
                cancelled.set(true)
                activeSocket.getAndSet(null)?.let { runCatching { it.close() } }
                active.getAndSet(null)?.let { runCatching { it.disconnect() } }
            }
            val worker = launch(Dispatchers.IO) {
                try {
                    // Do not participate in a process-global cookie store, or mutate it.
                    if (CookieHandler.getDefault() != null) throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.PROTOCOL)
                    val targetHost = pairing.endpoint.substringBefore(':')
                    val context = SSLContext.getInstance("TLS").apply {
                        init(null, arrayOf(DesktopRemoteTrustManager(pairing.fingerprint(), targetHost)), null)
                    }
                    val targetUrl = URI("https://${pairing.endpoint}$path").toURL()
                    val opened = connectionOpener?.open(targetUrl, targetHost)
                    val connection = opened?.first ?: (targetUrl.openConnection() as HttpsURLConnection)
                    val boundFactory = opened?.second
                    active.set(connection)
                    if (!continuation.isActive || cancelled.get()) {
                        abortTransport()
                        return@launch
                    }
                    connection.apply {
                        sslSocketFactory = DesktopRemoteSocketFactory(context.socketFactory, boundFactory) { socket ->
                            activeSocket.set(socket)
                            if (cancelled.get() || !continuation.isActive) {
                                runCatching { socket.close() }
                                throw IOException("Desktop remote request cancelled")
                            }
                        }
                        // Intentionally never set hostnameVerifier: platform default checks IP SAN.
                        instanceFollowRedirects = false
                        useCaches = false
                        connectTimeout = 4000
                        readTimeout = readTimeoutMs
                        requestMethod = method
                        setRequestProperty("Authorization", secret.bearer())
                        setRequestProperty("Connection", "close")
                        configure(this)
                    }
                    val result = block(connection)
                    if (continuation.isActive) continuation.resumeWith(Result.success(result))
                } catch (cancel: CancellationException) {
                    continuation.cancel(cancel)
                } catch (failure: Exception) {
                    val safe = when (failure) {
                        is DesktopRemoteFailure -> failure
                        is SSLException -> DesktopRemoteFailure(DesktopRemoteFailure.Kind.TRUST)
                        else -> DesktopRemoteFailure(DesktopRemoteFailure.Kind.UNAVAILABLE)
                    }
                    if (continuation.isActive) continuation.resumeWith(Result.failure(safe))
                } finally {
                    activeSocket.set(null)
                    active.getAndSet(null)?.disconnect()
                }
            }
            continuation.invokeOnCancellation { abortTransport(); worker.cancel() }
        }
    }
}

private fun defaultAndroidClientLabel(): String {
    val manufacturer = android.os.Build.MANUFACTURER?.trim().orEmpty()
    val model = android.os.Build.MODEL?.trim().orEmpty()
    val prettyManufacturer = manufacturer
        .lowercase(java.util.Locale.ROOT)
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
    val combined = when {
        model.isBlank() && prettyManufacturer.isBlank() -> "SWR Player Android"
        model.isBlank() -> prettyManufacturer
        prettyManufacturer.isBlank() -> model
        model.startsWith(prettyManufacturer, ignoreCase = true) -> model
        else -> "$prettyManufacturer $model"
    }
    return DesktopRemoteJson.sanitizePairLabel(combined)
}
