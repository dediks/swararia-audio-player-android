package com.swararia.audiolab

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

data class RemoteDownloadMedia(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val inputStream: InputStream,
)

data class RemoteUploadImportResult(
    val deduplicated: Boolean,
    val trackId: String,
    val displayName: String,
    val title: String,
    val sizeBytes: Long,
    val sha256: String,
    val linkedPlaylistId: String?,
)

interface RemoteServerMediaPort {
    fun createTempUploadFile(): File
    fun openMediaForDownload(trackNumericId: Long): RemoteDownloadMedia?
    fun importValidatedUpload(
        stagedFile: File,
        requestedName: String,
        expectedSha256: String,
        playlistName: String?,
    ): RemoteUploadImportResult
    fun isTrackDeletionBlocked(trackNumericId: Long): Boolean = false
    fun onTransferStateChanged(activeTransfers: Int, message: String? = null) {}
}

fun interface RemoteWebAssetLoader {
    fun load(assetPath: String): Pair<String, ByteArray>?
}

/**
 * Hardened HTTP/1.1 + TLSv1.2/v1.3 server listener on an isolated private/loopback IPv4 socket.
 * Reserves 14 of 16 connection slots exclusively for low-latency FOH playback commands and polls
 * by capping concurrent heavy media transfers at Semaphore(2).
 */
class AndroidRemoteHttpListener(
    private val bindIp: String,
    requestedPort: Int,
    private val tlsIdentity: RemoteTlsIdentity,
    private val engine: AndroidRemoteServerEngine,
    private val commandExecutor: (RemoteCommandExecutionRequest) -> RemoteCommandExecutionResult,
    private val mediaPort: RemoteServerMediaPort? = null,
    private val webAssets: RemoteWebAssetLoader = RemoteWebAssetLoader { null },
    private val onBeforeStateRead: () -> Unit = {},
) {
    private val mapper = ObjectMapper()
        .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)

    private val ipBytes = AndroidRemoteTlsIdentity.parsePrivateOrLoopbackIpv4(bindIp)
        ?: throw IllegalArgumentException("Bind host harus IPv4 privat atau loopback.")

    private val serverSocket: SSLServerSocket
    val actualPort: Int
    private val expectedHostHeader: String
    private val expectedOriginHeader: String

    private val connectionSlots = Semaphore(TOTAL_SLOTS, true)
    private val mediaSlots = Semaphore(MEDIA_SLOTS, true)
    private val activeMediaTransfers = AtomicInteger(0)

    private val guard = Any()
    private val sockets = HashSet<Socket>()
    private val workers = HashSet<Thread>()
    @Volatile
    private var closing = false
    private val acceptThread: Thread
    private val watchdogExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "SwarariaRemoteWatchdog").apply { isDaemon = true }
    }

    init {
        val inet = InetAddress.getByAddress(bindIp, ipBytes)
        val created = tlsIdentity.sslContext.serverSocketFactory.createServerSocket(requestedPort, TOTAL_SLOTS, inet) as SSLServerSocket
        created.reuseAddress = true
        val tlsProtocols = created.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
        if (tlsProtocols.isNotEmpty()) created.enabledProtocols = tlsProtocols.toTypedArray()
        serverSocket = created
        actualPort = created.localPort
        expectedHostHeader = "$bindIp:$actualPort"
        expectedOriginHeader = "https://$bindIp:$actualPort"

        acceptThread = Thread({ acceptLoop() }, "SwarariaRemoteAccept-$actualPort").apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        val snapshotSockets: List<Socket>
        val snapshotWorkers: List<Thread>
        synchronized(guard) {
            if (closing) return
            closing = true
            snapshotSockets = sockets.toList()
            snapshotWorkers = workers.toList()
        }
        runCatching { serverSocket.close() }
        for (sock in snapshotSockets) {
            runCatching { sock.shutdownInput() }
            runCatching { sock.close() }
        }
        runCatching { acceptThread.join(1500) }
        for (worker in snapshotWorkers) {
            runCatching { worker.join(1500) }
        }
        watchdogExecutor.shutdownNow()
    }

    private fun acceptLoop() {
        while (!closing) {
            val rawSocket = try {
                serverSocket.accept() as SSLSocket
            } catch (_: Exception) {
                break
            }
            if (!connectionSlots.tryAcquire()) {
                runCatching { rawSocket.close() }
                continue
            }
            val worker = Thread({ handleClient(rawSocket) }, "SwarariaRemoteWorker").apply { isDaemon = true }
            val rejected = synchronized(guard) {
                if (closing) {
                    true
                } else {
                    sockets.add(rawSocket)
                    workers.add(worker)
                    false
                }
            }
            if (rejected) {
                runCatching { rawSocket.close() }
                connectionSlots.release()
                continue
            }
            worker.start()
        }
    }

    private fun handleClient(socket: SSLSocket) {
        var timer: ScheduledFuture<*>? = null
        fun armDeadline(millis: Long) {
            timer?.cancel(false)
            timer = watchdogExecutor.schedule({
                runCatching { socket.close() }
            }, millis, TimeUnit.MILLISECONDS)
        }
        try {
            armDeadline(CONTROL_CONNECTION_TTL_MS)
            socket.soTimeout = CONTROL_SOCKET_TIMEOUT_MS
            val protocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }
            if (protocols.isNotEmpty()) socket.enabledProtocols = protocols.toTypedArray()
            socket.startHandshake()

            val input = BufferedInputStream(socket.inputStream, 16 * 1024)
            val output = socket.outputStream
            try {
                val request = parseHttpRequest(input)
                if (request == null) {
                    writeJsonError(output, 400, "invalid_request")
                    return
                }
                dispatchRequest(request, input, output, socket, ::armDeadline)
            } catch (remote: RemoteServerError) {
                writeJsonError(output, remote.httpStatus, remote.code)
            } catch (_: Exception) {
                runCatching { writeJsonError(output, 400, "invalid_request") }
            }
        } catch (_: Exception) {
            // Never log raw request payloads, credentials, cookies or TLS exception details.
        } finally {
            timer?.cancel(false)
            runCatching { socket.close() }
            synchronized(guard) {
                sockets.remove(socket)
                workers.remove(Thread.currentThread())
            }
            connectionSlots.release()
        }
    }

    private data class HttpRequestHead(
        val method: String,
        val path: String,
        val headers: Map<String, List<String>>,
    ) {
        fun singleHeader(name: String): String? {
            val values = headers[name.lowercase(Locale.ROOT)] ?: return null
            return if (values.size == 1) values[0] else null
        }

        fun hasHeader(name: String): Boolean = !headers[name.lowercase(Locale.ROOT)].isNullOrEmpty()
    }

    private fun parseHttpRequest(input: BufferedInputStream): HttpRequestHead? {
        val requestLine = readAsciiLine(input, MAX_REQUEST_LINE_BYTES) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3) return null
        val method = parts[0]
        val target = parts[1]
        val version = parts[2]
        if (version != "HTTP/1.1" && version != "HTTP/1.0") return null
        if (!target.startsWith('/') || target.startsWith("//") || target.contains('?') ||
            target.contains('#') || target.contains('\\') || target.contains("..")
        ) {
            throw RemoteServerError("unknown_route", 404)
        }
        if (target.any { it.code < 0x21 || it.code > 0x7E }) {
            throw RemoteServerError("unknown_route", 404)
        }

        val headers = LinkedHashMap<String, MutableList<String>>()
        var totalHeaderBytes = 0
        var headerCount = 0
        while (true) {
            val line = readAsciiLine(input, MAX_HEADER_LINE_BYTES) ?: return null
            if (line.isEmpty()) break
            totalHeaderBytes += line.length + 2
            headerCount++
            if (headerCount > MAX_HEADER_COUNT || totalHeaderBytes > MAX_TOTAL_HEADER_BYTES) {
                throw RemoteServerError("header_too_large", 431)
            }
            if (line[0] == ' ' || line[0] == '\t') {
                throw RemoteServerError("invalid_request", 400)
            }
            val colon = line.indexOf(':')
            if (colon <= 0) throw RemoteServerError("invalid_request", 400)
            val rawName = line.substring(0, colon)
            if (!HEADER_NAME_REGEX.matches(rawName)) throw RemoteServerError("invalid_request", 400)
            val lowerName = rawName.lowercase(Locale.ROOT)
            val value = line.substring(colon + 1).trim()
            if (value.any { it.isISOControl() }) throw RemoteServerError("invalid_request", 400)
            headers.getOrPut(lowerName) { ArrayList(2) }.add(value)
        }

        if (headers.containsKey("transfer-encoding")) {
            throw RemoteServerError("invalid_length", 400)
        }
        val contentLengths = headers["content-length"]
        if (contentLengths != null && (contentLengths.size != 1 || !CONTENT_LENGTH_REGEX.matches(contentLengths[0]))) {
            throw RemoteServerError("invalid_length", 400)
        }
        val hosts = headers["host"]
        if (version == "HTTP/1.1") {
            if (hosts == null || hosts.size != 1 || !hosts[0].equals(expectedHostHeader, ignoreCase = true)) {
                throw RemoteServerError("invalid_host", 403)
            }
        } else if (hosts != null && (hosts.size != 1 || !hosts[0].equals(expectedHostHeader, ignoreCase = true))) {
            throw RemoteServerError("invalid_host", 403)
        }

        return HttpRequestHead(method = method, path = target, headers = headers)
    }

    private fun readAsciiLine(input: BufferedInputStream, maxLen: Int): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else throw RemoteServerError("invalid_request", 400)
            if (b == '\n'.code) throw RemoteServerError("invalid_request", 400)
            if (b == '\r'.code) {
                val next = input.read()
                if (next != '\n'.code) throw RemoteServerError("invalid_request", 400)
                return String(buf.toByteArray(), Charsets.US_ASCII)
            }
            if (b == 0 || b > 0x7E) throw RemoteServerError("invalid_request", 400)
            if (buf.size() >= maxLen) throw RemoteServerError("header_too_large", 431)
            buf.write(b)
        }
    }

    private fun dispatchRequest(
        req: HttpRequestHead,
        input: InputStream,
        output: OutputStream,
        socket: SSLSocket,
        armDeadline: (Long) -> Unit,
    ) {
        if (req.method != "GET" && req.method != "POST") {
            throw RemoteServerError("method_not_allowed", 405)
        }
        engine.checkRateLimit("all")

        val path = req.path
        if (path.startsWith("/v1/")) {
            dispatchNative(req, input, output, socket, armDeadline)
            return
        }
        if (path == "/" || path.startsWith("/web/")) {
            dispatchWeb(req, input, output, socket, armDeadline)
            return
        }
        throw RemoteServerError("unknown_route", 404)
    }

    private fun dispatchNative(
        req: HttpRequestHead,
        input: InputStream,
        output: OutputStream,
        socket: SSLSocket,
        armDeadline: (Long) -> Unit,
    ) {
        // Strict plane separation: browsers must use "/" and "/web/*" with HttpOnly cookie + CSRF.
        if (req.hasHeader("origin") || req.hasHeader("referer") || req.hasHeader("sec-fetch-site")) {
            throw RemoteServerError("native_clients_only", 403)
        }

        val path = req.path
        val cmdStatusId = CMD_STATUS_V1_REGEX.matchEntire(path)?.groupValues?.get(1)
        val mediaDownloadId = MEDIA_DOWNLOAD_V1_REGEX.matchEntire(path)?.groupValues?.get(1)

        val knownPost = path == "/v1/pair" || path == "/v1/commands" || path == "/v1/library/upload"
        val knownGet = path == "/v1/session" || path == "/v1/capabilities" || path == "/v1/state" ||
            path == "/v1/tracks" || path == "/v1/library/manifest" || cmdStatusId != null || mediaDownloadId != null

        if (req.method == "POST") {
            if (!knownPost) {
                throw RemoteServerError(if (knownGet) "method_not_allowed" else "unknown_route", if (knownGet) 405 else 404)
            }
            if (path == "/v1/library/upload") {
                val device = engine.authenticateNative(extractBearerToken(req), requireActive = true, requireControl = true, requireTransfer = true)
                handleMediaUpload(req, input, output, socket, armDeadline, device.deviceId)
                return
            }
            val payload = readJsonObjectBody(req, input)
            if (path == "/v1/pair") {
                engine.checkRateLimit("pair")
                val token = extractBearerToken(req)
                if (setOf("label") != fieldNames(payload)) throw RemoteServerError("invalid_fields", 400)
                val label = requireStringField(payload, "label")
                writeJson(output, 202, engine.pairNative(token, label))
                return
            }
            val device = engine.authenticateNative(extractBearerToken(req), requireActive = true, requireControl = true)
            writeJson(output, 202, dispatchControlCommand(device, payload))
            return
        }

        // GET /v1/*
        if (!knownGet) {
            throw RemoteServerError(if (knownPost) "method_not_allowed" else "unknown_route", if (knownPost) 405 else 404)
        }
        assertZeroContentLength(req)
        val bearer = extractBearerToken(req)

        when {
            path == "/v1/session" -> {
                val d = engine.authenticateNative(bearer, requireActive = false)
                writeJson(output, 200, engine.sessionPayload(d, includeCsrfForWeb = false))
            }
            path == "/v1/capabilities" -> {
                val d = engine.authenticateNative(bearer, requireActive = true)
                writeJson(output, 200, engine.capabilitiesPayload(d))
            }
            path == "/v1/state" -> {
                val d = engine.authenticateNative(bearer, requireActive = true)
                onBeforeStateRead()
                writeJson(output, 200, engine.statePayload(d))
            }
            path == "/v1/tracks" -> {
                val d = engine.authenticateNative(bearer, requireActive = true)
                onBeforeStateRead()
                writeJson(output, 200, engine.tracksPayload(d, webView = false))
            }
            cmdStatusId != null -> {
                val d = engine.authenticateNative(bearer, requireActive = true)
                writeJson(output, 200, engine.commandStatus(d, cmdStatusId))
            }
            path == "/v1/library/manifest" -> {
                engine.authenticateNative(bearer, requireActive = true, requireControl = true, requireTransfer = true)
                writeJson(output, 200, engine.manifestPayload())
            }
            mediaDownloadId != null -> {
                engine.authenticateNative(bearer, requireActive = true, requireControl = true, requireTransfer = true)
                handleMediaDownload(output, socket, armDeadline, mediaDownloadId)
            }
        }
    }

    private fun dispatchWeb(
        req: HttpRequestHead,
        input: InputStream,
        output: OutputStream,
        socket: SSLSocket,
        armDeadline: (Long) -> Unit,
    ) {
        val path = req.path
        if (req.method == "GET") {
            assertZeroContentLength(req)
            when {
                path == "/" -> {
                    val (mime, bytes) = webAssets.load("remote_server/index.html")
                        ?: throw RemoteServerError("unavailable", 503)
                    writeBytesResponse(output, 200, mime, bytes)
                    return
                }
                path == "/web/remote.css" -> {
                    val (mime, bytes) = webAssets.load("remote_server/remote.css")
                        ?: throw RemoteServerError("unavailable", 503)
                    writeBytesResponse(output, 200, mime, bytes)
                    return
                }
                path == "/web/remote.js" -> {
                    val (mime, bytes) = webAssets.load("remote_server/remote.js")
                        ?: throw RemoteServerError("unavailable", 503)
                    writeBytesResponse(output, 200, mime, bytes)
                    return
                }
            }

            val cmdId = CMD_STATUS_WEB_REGEX.matchEntire(path)?.groupValues?.get(1)
            val mediaId = MEDIA_DOWNLOAD_WEB_REGEX.matchEntire(path)?.groupValues?.get(1)
            val knownGetApi = path == "/web/session" || path == "/web/state" || path == "/web/tracks" ||
                path == "/web/library/manifest" || cmdId != null || mediaId != null
            if (!knownGetApi) {
                val isPostOnly = path in setOf("/web/pair", "/web/logout", "/web/commands", "/web/library/upload")
                throw RemoteServerError(if (isPostOnly) "method_not_allowed" else "unknown_route", if (isPostOnly) 405 else 404)
            }

            val cookieToken = extractSessionCookie(req) ?: throw RemoteServerError("unauthorized", 401)
            when {
                path == "/web/session" -> {
                    val d = engine.authenticateWeb(cookieToken, requireActive = false)
                    writeJson(output, 200, engine.sessionPayload(d, includeCsrfForWeb = true))
                }
                path == "/web/state" -> {
                    val d = engine.authenticateWeb(cookieToken, requireActive = true)
                    onBeforeStateRead()
                    writeJson(output, 200, engine.statePayload(d))
                }
                path == "/web/tracks" -> {
                    val d = engine.authenticateWeb(cookieToken, requireActive = true)
                    onBeforeStateRead()
                    writeJson(output, 200, engine.tracksPayload(d, webView = true))
                }
                cmdId != null -> {
                    val d = engine.authenticateWeb(cookieToken, requireActive = true)
                    writeJson(output, 200, engine.commandStatus(d, cmdId))
                }
                path == "/web/library/manifest" -> {
                    engine.authenticateWeb(cookieToken, requireActive = true, requireControl = true, requireTransfer = true)
                    writeJson(output, 200, engine.manifestPayload())
                }
                mediaId != null -> {
                    engine.authenticateWeb(cookieToken, requireActive = true, requireControl = true, requireTransfer = true)
                    handleMediaDownload(output, socket, armDeadline, mediaId)
                }
            }
            return
        }

        // POST /web/* — enforce same-origin + Sec-Fetch-Site + CSRF token (except pre-session /web/pair).
        verifyBrowserSameOriginPost(req)

        if (path == "/web/pair") {
            engine.checkRateLimit("pair")
            val payload = readJsonObjectBody(req, input)
            if (fieldNames(payload) != setOf("label", "pin")) throw RemoteServerError("invalid_fields", 400)
            val label = requireStringField(payload, "label")
            val pin = requireStringField(payload, "pin")
            val paired = engine.pairWeb(pin, label)
            val setCookie = buildSessionCookie(paired.cookieToken, maxAgeSec = null)
            writeJson(
                output = output,
                status = 202,
                payload = mapOf(
                    "device_id" to paired.deviceId,
                    "status" to paired.status,
                    "csrf_token" to paired.csrfToken,
                ),
                extraHeaders = listOf("Set-Cookie" to setCookie),
            )
            return
        }

        val cookieToken = extractSessionCookie(req) ?: throw RemoteServerError("unauthorized", 401)
        val csrfHeader = req.singleHeader("x-swararia-csrf")

        when (path) {
            "/web/logout" -> {
                assertZeroOrEmptyJsonBody(req, input)
                engine.logoutWeb(cookieToken, csrfHeader)
                val clearCookie = buildSessionCookie("", maxAgeSec = 0)
                writeJson(
                    output = output,
                    status = 200,
                    payload = mapOf("ok" to true),
                    extraHeaders = listOf("Set-Cookie" to clearCookie),
                )
            }
            "/web/commands" -> {
                val device = engine.authenticateWeb(
                    cookieToken = cookieToken,
                    csrfHeader = csrfHeader,
                    requireCsrf = true,
                    requireActive = true,
                    requireControl = true,
                )
                val payload = readJsonObjectBody(req, input)
                writeJson(output, 202, dispatchControlCommand(device, payload))
            }
            "/web/library/upload" -> {
                val device = engine.authenticateWeb(
                    cookieToken = cookieToken,
                    csrfHeader = csrfHeader,
                    requireCsrf = true,
                    requireActive = true,
                    requireControl = true,
                    requireTransfer = true,
                )
                handleMediaUpload(req, input, output, socket, armDeadline, device.deviceId)
            }
            else -> {
                val isGetOnly = path == "/" || path.startsWith("/web/remote.") || path == "/web/session" ||
                    path == "/web/state" || path == "/web/tracks" || path == "/web/library/manifest" ||
                    CMD_STATUS_WEB_REGEX.matches(path) || MEDIA_DOWNLOAD_WEB_REGEX.matches(path)
                throw RemoteServerError(if (isGetOnly) "method_not_allowed" else "unknown_route", if (isGetOnly) 405 else 404)
            }
        }
    }

    private fun verifyBrowserSameOriginPost(req: HttpRequestHead) {
        val secFetch = req.singleHeader("sec-fetch-site")?.lowercase(Locale.ROOT)
        if (secFetch != null && secFetch != "same-origin" && secFetch != "none") {
            throw RemoteServerError("cross_origin_rejected", 403)
        }
        val origin = req.singleHeader("origin")
        if (origin != null) {
            if (!origin.equals(expectedOriginHeader, ignoreCase = true)) {
                throw RemoteServerError("cross_origin_rejected", 403)
            }
            return
        }
        val referer = req.singleHeader("referer")
        if (referer != null && referer.startsWith("$expectedOriginHeader/", ignoreCase = true)) {
            return
        }
        throw RemoteServerError("cross_origin_rejected", 403)
    }

    private fun handleMediaDownload(
        output: OutputStream,
        socket: SSLSocket,
        armDeadline: (Long) -> Unit,
        rawTrackId: String,
    ) {
        val port = mediaPort ?: throw RemoteServerError("transfer_unavailable", 503)
        if (engine.isMediaTransferPausedByPlayback()) {
            throw RemoteServerError("transfer_paused_during_playback", 409)
        }
        val trackNumId = engine.parseTrackNumericId(rawTrackId) ?: throw RemoteServerError("unknown_track", 404)
        if (port.isTrackDeletionBlocked(trackNumId)) {
            throw RemoteServerError("track_deleting", 409)
        }
        if (!mediaSlots.tryAcquire()) {
            throw RemoteServerError("transfer_busy", 429)
        }
        val activeNow = activeMediaTransfers.incrementAndGet()
        port.onTransferStateChanged(activeNow, "Mengirim audio ke klien...")
        try {
            val media = port.openMediaForDownload(trackNumId) ?: throw RemoteServerError("unknown_track", 404)
            media.inputStream.use { stream ->
                armDeadline(MEDIA_CONNECTION_TTL_MS)
                socket.soTimeout = MEDIA_SOCKET_TIMEOUT_MS
                val disposition = buildAttachmentDisposition(media.displayName)
                val header = buildString {
                    append("HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ").append(media.mimeType).append("\r\n")
                    append("Content-Length: ").append(media.sizeBytes).append("\r\n")
                    append("Content-Disposition: ").append(disposition).append("\r\n")
                    appendSecurityHeaders()
                    append("Connection: close\r\n\r\n")
                }.toByteArray(Charsets.US_ASCII)
                output.write(header)

                val buf = ByteArray(MEDIA_BUFFER_BYTES)
                while (true) {
                    val read = stream.read(buf)
                    if (read < 0) break
                    if (read > 0) output.write(buf, 0, read)
                }
                output.flush()
            }
        } finally {
            val remaining = activeMediaTransfers.decrementAndGet().coerceAtLeast(0)
            mediaSlots.release()
            port.onTransferStateChanged(remaining, null)
        }
    }

    private fun handleMediaUpload(
        req: HttpRequestHead,
        input: InputStream,
        output: OutputStream,
        socket: SSLSocket,
        armDeadline: (Long) -> Unit,
        @Suppress("UNUSED_PARAMETER") deviceId: String,
    ) {
        val port = mediaPort ?: throw RemoteServerError("transfer_unavailable", 503)
        if (engine.isMediaTransferPausedByPlayback()) {
            throw RemoteServerError("transfer_paused_during_playback", 409)
        }
        val lenHeader = req.singleHeader("content-length") ?: throw RemoteServerError("invalid_length", 400)
        val contentLength = lenHeader.toLongOrNull() ?: throw RemoteServerError("invalid_length", 400)
        if (contentLength <= 0L) throw RemoteServerError("invalid_length", 400)
        if (contentLength > AndroidRemoteServerEngine.MAX_UPLOAD_BYTES) throw RemoteServerError("body_too_large", 413)

        val contentType = req.singleHeader("content-type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (contentType != "application/octet-stream") throw RemoteServerError("binary_required", 415)

        val rawNameHeader = req.singleHeader("x-swararia-filename") ?: throw RemoteServerError("missing_filename", 400)
        val decodedName = decodePercentUtf8(rawNameHeader) ?: throw RemoteServerError("invalid_filename", 400)
        val cleanFilename = decodedName.replace('\\', '/').substringAfterLast('/').trim()
        val ext = cleanFilename.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext !in AndroidRemoteServerEngine.ALLOWED_AUDIO_EXTENSIONS) {
            throw RemoteServerError("unsupported_audio_format", 415)
        }
        val validatedName = try {
            LibraryRules.renamedDisplayName("audio.$ext", cleanFilename)
        } catch (_: IllegalArgumentException) {
            throw RemoteServerError("invalid_filename", 400)
        }

        val shaHeader = req.singleHeader("x-swararia-sha256")?.trim()?.lowercase(Locale.ROOT)
            ?: throw RemoteServerError("missing_sha256", 400)
        if (!SHA256_HEX_REGEX.matches(shaHeader)) throw RemoteServerError("invalid_sha256", 400)

        val rawPlaylistHeader = req.singleHeader("x-swararia-playlist")
        val playlistName = if (rawPlaylistHeader != null) {
            val decodedPl = decodePercentUtf8(rawPlaylistHeader) ?: throw RemoteServerError("invalid_playlist", 400)
            if (decodedPl.isBlank()) null else try {
                LibraryRules.validatedPlaylistName(decodedPl)
            } catch (_: IllegalArgumentException) {
                throw RemoteServerError("invalid_playlist", 400)
            }
        } else null

        if (!mediaSlots.tryAcquire()) {
            throw RemoteServerError("transfer_busy", 429)
        }
        val activeNow = activeMediaTransfers.incrementAndGet()
        port.onTransferStateChanged(activeNow, "Menerima $validatedName...")
        var stagedFile: File? = null
        try {
            armDeadline(MEDIA_CONNECTION_TTL_MS)
            socket.soTimeout = MEDIA_SOCKET_TIMEOUT_MS
            val tempFile = port.createTempUploadFile()
            stagedFile = tempFile
            val digest = MessageDigest.getInstance("SHA-256")
            var remaining = contentLength
            val buf = ByteArray(MEDIA_BUFFER_BYTES)
            FileOutputStream(tempFile).use { fos ->
                while (remaining > 0L) {
                    val toRead = minOf(buf.size.toLong(), remaining).toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n < 0) throw RemoteServerError("incomplete_upload", 400)
                    if (n > 0) {
                        fos.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        remaining -= n
                    }
                }
                fos.flush()
            }
            val actualSha = digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
            if (!MessageDigest.isEqual(actualSha.toByteArray(Charsets.US_ASCII), shaHeader.toByteArray(Charsets.US_ASCII))) {
                throw RemoteServerError("sha256_mismatch", 422)
            }

            val outcome = try {
                port.importValidatedUpload(
                    stagedFile = tempFile,
                    requestedName = validatedName,
                    expectedSha256 = shaHeader,
                    playlistName = playlistName,
                )
            } catch (_: IllegalArgumentException) {
                throw RemoteServerError("invalid_audio_content", 422)
            } catch (_: IllegalStateException) {
                throw RemoteServerError("invalid_audio_content", 422)
            }

            writeJson(
                output = output,
                status = if (outcome.deduplicated) 200 else 201,
                payload = mapOf(
                    "status" to if (outcome.deduplicated) "deduplicated" else "created",
                    "deduplicated" to outcome.deduplicated,
                    "track_id" to outcome.trackId,
                    "display_name" to outcome.displayName,
                    "title" to outcome.title,
                    "size_bytes" to outcome.sizeBytes,
                    "sha256" to outcome.sha256,
                    "playlist_id" to outcome.linkedPlaylistId,
                ),
            )
        } finally {
            runCatching { stagedFile?.delete() }
            val activeLeft = activeMediaTransfers.decrementAndGet().coerceAtLeast(0)
            mediaSlots.release()
            port.onTransferStateChanged(activeLeft, null)
        }
    }

    private fun dispatchControlCommand(device: RemoteDeviceSnapshot, payload: JsonNode): Map<String, Any?> {
        val action = requireStringField(payload, "action")
        val fields = fieldNames(payload)
        return when (action) {
            "select_track" -> {
                if (fields != setOf("action", "idempotency_key", "track_id") &&
                    fields != setOf("action", "idempotency_key", "track_id", "auto_play")
                ) {
                    throw RemoteServerError("invalid_fields", 400)
                }
                val key = requireStringField(payload, "idempotency_key")
                val trackId = requireStringField(payload, "track_id")
                val autoPlayNode = payload.get("auto_play")
                val autoPlay = when {
                    autoPlayNode == null -> false
                    autoPlayNode.isBoolean -> autoPlayNode.booleanValue()
                    else -> throw RemoteServerError("invalid_command", 400)
                }
                engine.executeCommand(
                    device = device,
                    action = action,
                    idempotencyKey = key,
                    trackId = trackId,
                    autoPlay = autoPlay,
                    executor = commandExecutor,
                )
            }
            "select_group" -> {
                if (fields != setOf("action", "idempotency_key", "group_id")) {
                    throw RemoteServerError("invalid_fields", 400)
                }
                val key = requireStringField(payload, "idempotency_key")
                val groupId = requireStringField(payload, "group_id")
                engine.executeCommand(
                    device = device,
                    action = action,
                    idempotencyKey = key,
                    groupId = groupId,
                    executor = commandExecutor,
                )
            }
            else -> {
                if (fields != setOf("action", "idempotency_key")) {
                    throw RemoteServerError("invalid_fields", 400)
                }
                val key = requireStringField(payload, "idempotency_key")
                engine.executeCommand(
                    device = device,
                    action = action,
                    idempotencyKey = key,
                    executor = commandExecutor,
                )
            }
        }
    }

    private fun extractBearerToken(req: HttpRequestHead): String {
        val auth = req.singleHeader("authorization") ?: throw RemoteServerError("unauthorized", 401)
        if (!auth.startsWith("Bearer ")) throw RemoteServerError("unauthorized", 401)
        val token = auth.substring(7)
        if (token.length !in 20..128) throw RemoteServerError("unauthorized", 401)
        return token
    }

    private fun extractSessionCookie(req: HttpRequestHead): String? {
        val cookieHeaders = req.headers["cookie"] ?: return null
        var matched: String? = null
        for (headerValue in cookieHeaders) {
            for (part in headerValue.split(';')) {
                val eq = part.indexOf('=')
                if (eq <= 0) continue
                val name = part.substring(0, eq).trim()
                val value = part.substring(eq + 1).trim()
                if (name == SESSION_COOKIE_NAME) {
                    if (matched != null) return null // Ambiguous duplicate session cookies fail closed.
                    matched = value
                }
            }
        }
        return matched?.takeIf { it.length in 20..128 }
    }

    private fun buildSessionCookie(value: String, maxAgeSec: Int?): String = buildString {
        append(SESSION_COOKIE_NAME).append('=').append(value)
        append("; Path=/; HttpOnly; Secure; SameSite=Strict")
        if (maxAgeSec != null) append("; Max-Age=").append(maxAgeSec)
    }

    private fun assertZeroContentLength(req: HttpRequestHead) {
        val len = req.singleHeader("content-length")
        if (len != null && len != "0") throw RemoteServerError("invalid_length", 400)
    }

    private fun assertZeroOrEmptyJsonBody(req: HttpRequestHead, input: InputStream) {
        val lenStr = req.singleHeader("content-length") ?: "0"
        val len = lenStr.toIntOrNull() ?: throw RemoteServerError("invalid_length", 400)
        if (len == 0) return
        if (len > MAX_JSON_BYTES) throw RemoteServerError("body_too_large", 413)
        readJsonObjectBody(req, input)
    }

    private fun readJsonObjectBody(req: HttpRequestHead, input: InputStream): JsonNode {
        val lenHeader = req.singleHeader("content-length") ?: throw RemoteServerError("invalid_length", 400)
        val len = lenHeader.toIntOrNull() ?: throw RemoteServerError("invalid_length", 400)
        if (len > MAX_JSON_BYTES) throw RemoteServerError("body_too_large", 413)
        if (len < 2) throw RemoteServerError("invalid_json", 400)
        val contentType = req.singleHeader("content-type")?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
        if (contentType != "application/json") throw RemoteServerError("json_required", 415)

        val buf = ByteArray(len)
        var offset = 0
        while (offset < len) {
            val n = input.read(buf, offset, len - offset)
            if (n < 0) throw RemoteServerError("invalid_json", 400)
            offset += n
        }
        return try {
            mapper.factory.createParser(buf).use { parser ->
                val node = mapper.readTree<JsonNode>(parser)
                if (node == null || !node.isObject || parser.nextToken() != null) {
                    throw RemoteServerError("invalid_json", 400)
                }
                node
            }
        } catch (err: RemoteServerError) {
            throw err
        } catch (_: Exception) {
            throw RemoteServerError("invalid_json", 400)
        }
    }

    private fun fieldNames(node: JsonNode): Set<String> {
        val out = LinkedHashSet<String>()
        val it = node.fieldNames()
        while (it.hasNext()) out.add(it.next())
        return out
    }

    private fun requireStringField(node: JsonNode, field: String): String {
        val child = node.get(field) ?: throw RemoteServerError("invalid_fields", 400)
        if (!child.isTextual) throw RemoteServerError("invalid_fields", 400)
        return child.textValue()
    }

    private fun writeJsonError(output: OutputStream, status: Int, code: String) {
        val extra = if (status == 429) listOf("Retry-After" to "2") else emptyList()
        writeJson(output, status, mapOf("code" to code), extra)
    }

    private fun writeJson(
        output: OutputStream,
        status: Int,
        payload: Map<String, Any?>,
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ) {
        val body = mapper.writeValueAsBytes(payload)
        writeBytesResponse(output, status, "application/json; charset=utf-8", body, extraHeaders)
    }

    private fun writeBytesResponse(
        output: OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        extraHeaders: List<Pair<String, String>> = emptyList(),
    ) {
        val header = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            appendSecurityHeaders()
            for ((k, v) in extraHeaders) {
                val safeVal = v.replace("\r", "").replace("\n", "")
                append(k).append(": ").append(safeVal).append("\r\n")
            }
            append("Connection: close\r\n\r\n")
        }.toByteArray(Charsets.US_ASCII)
        output.write(header)
        output.write(body)
        output.flush()
    }

    private fun StringBuilder.appendSecurityHeaders() {
        append("Cache-Control: no-store\r\n")
        append("X-Content-Type-Options: nosniff\r\n")
        append("X-Frame-Options: DENY\r\n")
        append("Referrer-Policy: no-referrer\r\n")
        append("Permissions-Policy: camera=(), microphone=(), geolocation=()\r\n")
        append("Content-Security-Policy: ").append(STRICT_CSP).append("\r\n")
    }

    private fun reasonPhrase(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        202 -> "Accepted"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        422 -> "Unprocessable Content"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    private fun decodePercentUtf8(raw: String): String? {
        if (raw.length > 512) return null
        val out = ByteArrayOutputStream(raw.length)
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '%') {
                if (i + 2 >= raw.length) return null
                val hi = Character.digit(raw[i + 1], 16)
                val lo = Character.digit(raw[i + 2], 16)
                if (hi < 0 || lo < 0) return null
                val value = (hi shl 4) or lo
                if (value == 0) return null
                out.write(value)
                i += 3
            } else {
                if (ch.isISOControl() || ch.code > 0x7E) return null
                out.write(ch.code)
                i++
            }
        }
        return runCatching { String(out.toByteArray(), Charsets.UTF_8) }.getOrNull()
    }

    private fun buildAttachmentDisposition(displayName: String): String {
        val clean = displayName.replace('\\', '_').replace('/', '_').filter { !it.isISOControl() }.ifBlank { "audio" }
        val asciiFallback = buildString {
            for (ch in clean.take(120)) {
                val safe = ch.code in 0x20..0x7E && ch != '"' && ch != ';'
                append(if (safe) ch else '_')
            }
        }.ifBlank { "audio" }
        val utf8Bytes = clean.toByteArray(Charsets.UTF_8)
        val rfc5987 = buildString {
            for (b in utf8Bytes) {
                val c = b.toInt() and 0xFF
                val unreserved = c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code ||
                    c in '0'.code..'9'.code || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code
                if (unreserved) append(c.toChar()) else append("%%%02X".format(Locale.ROOT, c))
            }
        }
        return "attachment; filename=\"$asciiFallback\"; filename*=UTF-8''$rfc5987"
    }

    companion object {
        const val TOTAL_SLOTS = 16
        const val MEDIA_SLOTS = 2
        const val MAX_JSON_BYTES = 4096
        private const val MEDIA_BUFFER_BYTES = 64 * 1024
        private const val CONTROL_CONNECTION_TTL_MS = 6_000L
        private const val MEDIA_CONNECTION_TTL_MS = 300_000L
        private const val CONTROL_SOCKET_TIMEOUT_MS = 3_500
        private const val MEDIA_SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_REQUEST_LINE_BYTES = 2048
        private const val MAX_HEADER_LINE_BYTES = 2048
        private const val MAX_HEADER_COUNT = 64
        private const val MAX_TOTAL_HEADER_BYTES = 8192
        const val SESSION_COOKIE_NAME = "__Host-swararia_session"

        private const val STRICT_CSP =
            "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
                "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; " +
                "form-action 'self'; frame-ancestors 'none'"

        private val HEADER_NAME_REGEX = Regex("[A-Za-z0-9-]+")
        private val CONTENT_LENGTH_REGEX = Regex("0|[1-9][0-9]{0,9}")
        private val SHA256_HEX_REGEX = Regex("[0-9a-f]{64}")
        private val CMD_STATUS_V1_REGEX = Regex("/v1/commands/([A-Za-z0-9_-]{24})")
        private val MEDIA_DOWNLOAD_V1_REGEX = Regex("/v1/library/media/((?:trk_)?[1-9][0-9]{0,18})")
        private val CMD_STATUS_WEB_REGEX = Regex("/web/commands/([A-Za-z0-9_-]{24})")
        private val MEDIA_DOWNLOAD_WEB_REGEX = Regex("/web/media/((?:trk_)?[1-9][0-9]{0,18})")
    }
}
