package com.swararia.audiolab

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.CookieHandler
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.*

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
    private val registerSocket: (Socket) -> Unit = {},
) : SSLSocketFactory() {
    private fun secure(socket: Socket): Socket = (socket as SSLSocket).apply {
        enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        registerSocket(this)
    }
    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
    override fun createSocket(): Socket = secure(delegate.createSocket())
    override fun createSocket(s: Socket, h: String, p: Int, close: Boolean): Socket = secure(delegate.createSocket(s, h, p, close))
    override fun createSocket(h: String, p: Int): Socket = secure(delegate.createSocket(h, p))
    override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int): Socket = secure(delegate.createSocket(h, p, l, lp))
    override fun createSocket(h: InetAddress, p: Int): Socket = secure(delegate.createSocket(h, p))
    override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int): Socket = secure(delegate.createSocket(h, p, l, lp))
}

/** Injected port: state ownership stays testable without Android or a listener. */
interface DesktopRemoteApi {
    suspend fun pair(pairing: DesktopRemotePairing): DesktopRemoteGrant
    suspend fun session(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant): DesktopRemoteApproval
    suspend fun capabilities(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant): Set<String>
    suspend fun state(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant): DesktopRemotePlayback
    suspend fun tracks(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant): DesktopRemoteCatalog =
        DesktopRemoteCatalog(online = true, version = 0, playlist = null, currentTrackId = null, tracks = emptyList())
    suspend fun command(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, action: String, key: String): DesktopRemoteCommand
    suspend fun selectTrack(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, trackId: String, autoPlay: Boolean, key: String): DesktopRemoteCommand =
        command(pairing, grant, "select_track", key)
    suspend fun selectGroup(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, groupId: String, key: String): DesktopRemoteCommand =
        command(pairing, grant, "select_group", key)
    suspend fun commandStatus(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, id: String): DesktopRemoteCommand
}

class DesktopRemoteHttpsApi : DesktopRemoteApi {
    override suspend fun pair(pairing: DesktopRemotePairing) = DesktopRemoteJson.grant(
        request(pairing, pairing.invitation(), "/v1/pair", "{\"label\":\"Swararia Android\"}".toByteArray(), 202))
    override suspend fun session(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant) =
        DesktopRemoteJson.approval(request(pairing, grant.authorization(), "/v1/session"))
    override suspend fun capabilities(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant) =
        DesktopRemoteJson.capabilities(request(pairing, grant.authorization(), "/v1/capabilities"))
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
    private suspend fun request(pairing: DesktopRemotePairing, secret: DesktopRemoteSecret, path: String,
        body: ByteArray? = null, expected: Int = 200, maxBytes: Int = 16_384): com.fasterxml.jackson.databind.JsonNode {
        return try {
            // Socket idle timeouts alone do not bound a peer that keeps sending bytes.
            withTimeout(8_000) { requestWithinDeadline(pairing, secret, path, body, expected, maxBytes) }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive() // Preserve cancellation owned by the caller.
            throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.UNAVAILABLE)
        }
    }
    private suspend fun requestWithinDeadline(pairing: DesktopRemotePairing, secret: DesktopRemoteSecret, path: String,
        body: ByteArray?, expected: Int, maxBytes: Int): com.fasterxml.jackson.databind.JsonNode = coroutineScope {
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
                    val context = SSLContext.getInstance("TLS").apply {
                        init(null, arrayOf(DesktopRemoteTrustManager(pairing.fingerprint(), pairing.endpoint.substringBefore(':'))), null)
                    }
                    val connection = URL("https://${pairing.endpoint}$path").openConnection() as HttpsURLConnection
                    active.set(connection)
                    if (!continuation.isActive || cancelled.get()) {
                        abortTransport()
                        return@launch
                    }
                    connection.apply {
                        sslSocketFactory = DesktopRemoteSocketFactory(context.socketFactory) { socket ->
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
                        readTimeout = 4000
                        requestMethod = if (body == null) "GET" else "POST"
                        setRequestProperty("Authorization", secret.bearer())
                        setRequestProperty("Accept", "application/json")
                        if (body == null && path == "/v1/capabilities") {
                            setRequestProperty("X-Swararia-Capabilities", "extended-v1")
                        }
                        setRequestProperty("Connection", "close")
                        if (body != null) {
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json")
                            setFixedLengthStreamingMode(body.size)
                        }
                    }
                    connection.connect()
                    if (body != null) connection.outputStream.use { it.write(body) }
                    val code = connection.responseCode
                    if (code == 401 || code == 403) throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.AUTH)
                    if (code != expected) throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.PROTOCOL)
                    if (connection.contentType?.substringBefore(';')?.trim() != "application/json" || connection.contentLengthLong > maxBytes) throw DesktopRemoteFailure()
                    val bytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(2048)
                        while (true) {
                            ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (output.size() + n > maxBytes) throw DesktopRemoteFailure()
                            output.write(buffer, 0, n)
                        }
                        output.toByteArray()
                    }
                    val result = DesktopRemoteJson.objectValue(bytes, maxBytes)
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
