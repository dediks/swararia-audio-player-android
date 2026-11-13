package com.swararia.audiolab

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

class AndroidRemoteHttpLoopbackTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private data class HttpResponse(
        val status: Int,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers[name.lowercase(Locale.ROOT)]?.firstOrNull()
    }

    private fun httpsClient(tls: RemoteTlsIdentity): SSLContext {
        val tm = DesktopRemoteTrustManager(tls.fingerprintBytes, "127.0.0.1")
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
    }

    private fun openTlsSocket(ssl: SSLContext, port: Int): SSLSocket =
        (ssl.socketFactory.createSocket("127.0.0.1", port) as SSLSocket).apply {
            soTimeout = 5000
            startHandshake()
        }

    private fun sendHttp(
        ssl: SSLContext,
        port: Int,
        method: String,
        path: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray = ByteArray(0),
    ): HttpResponse {
        openTlsSocket(ssl, port).use { socket ->
            val rawReq = buildString {
                append("$method $path HTTP/1.1\r\n")
                append("Host: 127.0.0.1:$port\r\n")
                for ((k, v) in headers) append("$k: $v\r\n")
                if (body.isNotEmpty() || method == "POST") {
                    append("Content-Length: ${body.size}\r\n")
                }
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
            socket.outputStream.write(rawReq)
            if (body.isNotEmpty()) socket.outputStream.write(body)
            socket.outputStream.flush()
            return readHttpResponse(BufferedInputStream(socket.inputStream))
        }
    }

    private fun readHttpResponse(input: BufferedInputStream): HttpResponse {
        val headerBuf = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b < 0) break
            headerBuf.write(b)
            state = when {
                state == 0 && b == '\r'.code -> 1
                state == 1 && b == '\n'.code -> 2
                state == 2 && b == '\r'.code -> 3
                state == 3 && b == '\n'.code -> 4
                b == '\r'.code -> 1
                else -> 0
            }
            if (state == 4) break
        }
        val rawHeaders = headerBuf.toString("ISO-8859-1").split("\r\n")
        val status = rawHeaders.first().split(' ')[1].toInt()
        val parsedHeaders = LinkedHashMap<String, MutableList<String>>()
        for (line in rawHeaders.drop(1)) {
            if (line.isBlank()) continue
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val k = line.substring(0, idx).trim().lowercase(Locale.ROOT)
            val v = line.substring(idx + 1).trim()
            parsedHeaders.getOrPut(k) { ArrayList() }.add(v)
        }
        val body = input.readBytes()
        return HttpResponse(status, parsedHeaders, body)
    }

    @Test
    fun nativePlaneMatchesDesktopJsonSchemaAndRejectsBrowserHeaders() {
        val tls = AndroidRemoteTlsIdentity.generate("127.0.0.1")
        val engine = AndroidRemoteServerEngine()
        val sampleBytes = "ID3-SAMPLE-AUDIO-PAYLOAD".toByteArray()
        val sampleSha = sha256Hex(sampleBytes)

        engine.updateLibrary(
            tracks = listOf(RemoteLibrarySourceTrack(11L, "Stage Intro.mp3", sampleBytes.size.toLong(), 9500L, "Swararia", "Live", sampleSha)),
            playlists = listOf(RemoteLibrarySourcePlaylist(2L, "Upacara", listOf(11L))),
        )
        engine.updatePlayback(RemoteServerPlaybackSnapshot(online = true, playing = true, transportState = "PLAYING", currentTimeSec = 2.5, durationSec = 9.5, currentTrackNumericId = 11L))

        val executedActions = ArrayList<String>()
        val listener = AndroidRemoteHttpListener(
            bindIp = "127.0.0.1",
            requestedPort = 0,
            tlsIdentity = tls,
            engine = engine,
            commandExecutor = { req ->
                executedActions.add(req.action)
                RemoteCommandExecutionResult(applied = true)
            },
        )
        try {
            val port = listener.actualPort
            engine.activate("127.0.0.1", port, tls.fingerprintBase64Url, tls.formattedHexColon())
            val invite = engine.issueNativeInvite()
            val ssl = httpsClient(tls)

            // Browser Origin/Referer/Sec-Fetch-Site must be blocked on /v1/* even with valid token.
            val originBlocked = sendHttp(
                ssl, port, "POST", "/v1/pair",
                headers = listOf(
                    "Authorization" to "Bearer $invite",
                    "Content-Type" to "application/json",
                    "Origin" to "https://evil.example",
                ),
                body = """{"label":"Malicious"}""".toByteArray(),
            )
            assertEquals(403, originBlocked.status)

            // Valid native pair parsed by real DesktopRemoteJson!
            val pairResp = sendHttp(
                ssl, port, "POST", "/v1/pair",
                headers = listOf("Authorization" to "Bearer $invite", "Content-Type" to "application/json"),
                body = """{"label":"FOH Pixel"}""".toByteArray(),
            )
            assertEquals(202, pairResp.status)
            val grant = DesktopRemoteJson.grant(DesktopRemoteJson.objectValue(pairResp.body))
            engine.approveDevice(grant.deviceId, "control", allowTransfer = false)

            val sessionResp = sendHttp(ssl, port, "GET", "/v1/session", headers = listOf("Authorization" to grant.authorization().bearer()))
            assertEquals(200, sessionResp.status)
            val approval = DesktopRemoteJson.approval(DesktopRemoteJson.objectValue(sessionResp.body))
            assertEquals("active", approval.status)
            assertEquals("control", approval.role)

            val capResp = sendHttp(ssl, port, "GET", "/v1/capabilities", headers = listOf("Authorization" to grant.authorization().bearer()))
            assertEquals(200, capResp.status)
            val caps = DesktopRemoteJson.capabilities(DesktopRemoteJson.objectValue(capResp.body))
            assertTrue("fade_out" in caps && "select_group" in caps)

            val stateResp = sendHttp(ssl, port, "GET", "/v1/state", headers = listOf("Authorization" to grant.authorization().bearer()))
            val playback = DesktopRemoteJson.playback(DesktopRemoteJson.objectValue(stateResp.body))
            assertEquals(true, playback.playing)
            assertEquals("Stage Intro", playback.title)

            val tracksResp = sendHttp(ssl, port, "GET", "/v1/tracks", headers = listOf("Authorization" to grant.authorization().bearer()))
            val catalog = DesktopRemoteJson.tracks(DesktopRemoteJson.objectValue(tracksResp.body))
            assertEquals("trk_11", catalog.currentTrackId)
            assertEquals(2, catalog.groups.size)

            val cmdResp = sendHttp(
                ssl, port, "POST", "/v1/commands",
                headers = listOf("Authorization" to grant.authorization().bearer(), "Content-Type" to "application/json"),
                body = """{"action":"pause","idempotency_key":"cmd_key_loopback_1"}""".toByteArray(),
            )
            assertEquals(202, cmdResp.status)
            val cmd = DesktopRemoteJson.command(DesktopRemoteJson.objectValue(cmdResp.body))
            assertEquals("applied", cmd.status)
            assertEquals(listOf("pause"), executedActions)
        } finally {
            listener.close()
        }
    }

    @Test
    fun webPlaneSecurityUploadDownloadDeduplicationAndMediaSemaphoreLimit() {
        val tls = AndroidRemoteTlsIdentity.generate("127.0.0.1")
        val engine = AndroidRemoteServerEngine()
        val storedAudio = "RIFF-TEST-WAV-AUDIO".toByteArray()
        val storedSha = sha256Hex(storedAudio)
        val deletedTrack = AtomicBoolean(false)

        // Latches for testing Semaphore(2) backpressure with 2 active downloads.
        val firstTwoStarted = CountDownLatch(2)
        val releaseDownloads = CountDownLatch(1)
        val uploadedNames = ArrayList<String>()

        engine.updateLibrary(
            tracks = listOf(RemoteLibrarySourceTrack(77L, "Stage Bumper.wav", storedAudio.size.toLong(), 4000L, null, null, storedSha)),
            playlists = emptyList(),
        )
        engine.updatePlayback(RemoteServerPlaybackSnapshot(online = true, playing = false, transportState = "PAUSED"))

        val listener = AndroidRemoteHttpListener(
            bindIp = "127.0.0.1",
            requestedPort = 0,
            tlsIdentity = tls,
            engine = engine,
            commandExecutor = { RemoteCommandExecutionResult(applied = true) },
            mediaPort = object : RemoteServerMediaPort {
                override fun createTempUploadFile(): File = tempFolder.newFile("upload-${System.nanoTime()}.part")
                override fun isTrackDeletionBlocked(trackNumericId: Long): Boolean = deletedTrack.get()
                override fun openMediaForDownload(trackNumericId: Long): RemoteDownloadMedia? {
                    if (trackNumericId != 77L) return null
                    if (deletedTrack.get()) return null
                    val slowStream = object : InputStream() {
                        private val delegate = ByteArrayInputStream(storedAudio)
                        private var held = false
                        override fun read(): Int {
                            if (!held) {
                                held = true
                                firstTwoStarted.countDown()
                                releaseDownloads.await(5, TimeUnit.SECONDS)
                            }
                            return delegate.read()
                        }
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (!held) {
                                held = true
                                firstTwoStarted.countDown()
                                releaseDownloads.await(5, TimeUnit.SECONDS)
                            }
                            return delegate.read(b, off, len)
                        }
                    }
                    return RemoteDownloadMedia("Stage Bumper.wav", "audio/wav", storedAudio.size.toLong(), slowStream)
                }

                override fun importValidatedUpload(
                    stagedFile: File,
                    requestedName: String,
                    expectedSha256: String,
                    playlistName: String?,
                ): RemoteUploadImportResult {
                    val existingDup = expectedSha256 == storedSha
                    uploadedNames.add(requestedName)
                    return RemoteUploadImportResult(
                        deduplicated = existingDup,
                        trackId = if (existingDup) "trk_77" else "trk_88",
                        displayName = requestedName,
                        title = UiPresentation.title(requestedName),
                        sizeBytes = stagedFile.length(),
                        sha256 = expectedSha256,
                        linkedPlaylistId = if (playlistName != null) "pl_9" else null,
                    )
                }
            },
            webAssets = { path ->
                when (path) {
                    "remote_server/index.html" -> "text/html; charset=utf-8" to "<!doctype html><html><body>OK</body></html>".toByteArray()
                    else -> null
                }
            },
        )

        try {
            val port = listener.actualPort
            engine.activate("127.0.0.1", port, tls.fingerprintBase64Url, tls.formattedHexColon())
            val ssl = httpsClient(tls)

            // 1) Verify strict CSP + anti-framing headers on "/".
            val home = sendHttp(ssl, port, "GET", "/")
            assertEquals(200, home.status)
            assertEquals("DENY", home.header("x-frame-options"))
            assertEquals("nosniff", home.header("x-content-type-options"))
            assertTrue(home.header("content-security-policy")!!.contains("frame-ancestors 'none'"))

            // 2) Cross-origin POST /web/pair must fail closed (403).
            val pin = engine.issueWebPin()
            val csrfOriginFail = sendHttp(
                ssl, port, "POST", "/web/pair",
                headers = listOf(
                    "Origin" to "https://attacker.local",
                    "Sec-Fetch-Site" to "cross-site",
                    "Content-Type" to "application/json",
                ),
                body = """{"label":"Attacker","pin":"$pin"}""".toByteArray(),
            )
            assertEquals(403, csrfOriginFail.status)

            // 3) Valid same-origin Web pair sets __Host-swararia_session HttpOnly; Secure; SameSite=Strict cookie.
            val origin = "https://127.0.0.1:$port"
            val pairResp = sendHttp(
                ssl, port, "POST", "/web/pair",
                headers = listOf(
                    "Origin" to origin,
                    "Sec-Fetch-Site" to "same-origin",
                    "Content-Type" to "application/json",
                ),
                body = """{"label":"Safari iPhone","pin":"$pin"}""".toByteArray(),
            )
            assertEquals(202, pairResp.status)
            val setCookie = pairResp.header("set-cookie")!!
            assertTrue(setCookie.startsWith("__Host-swararia_session="))
            assertTrue(setCookie.contains("HttpOnly") && setCookie.contains("Secure") && setCookie.contains("SameSite=Strict"))
            val cookiePair = setCookie.substringBefore(';')
            val pairNode = DesktopRemoteJson.objectValue(pairResp.body)
            val deviceId = pairNode.get("device_id").textValue()
            val csrf = pairNode.get("csrf_token").textValue()

            // Approve as control with transfer enabled.
            engine.setMediaTransferEnabled(true)
            engine.approveDevice(deviceId, "control", allowTransfer = true)

            // 4) Upload without matching SHA-256 must fail with 422 before hitting MediaLibrary.
            val badUpload = sendHttp(
                ssl, port, "POST", "/web/library/upload",
                headers = listOf(
                    "Origin" to origin,
                    "Sec-Fetch-Site" to "same-origin",
                    "Cookie" to cookiePair,
                    "X-Swararia-Csrf" to csrf,
                    "Content-Type" to "application/octet-stream",
                    "X-Swararia-Filename" to "New%20Track.mp3",
                    "X-Swararia-Sha256" to "0".repeat(64),
                ),
                body = "fake-bytes".toByteArray(),
            )
            assertEquals(422, badUpload.status)
            assertTrue(uploadedNames.isEmpty())

            // 5) Upload duplicate content (same SHA-256 as trk_77) returns 200 deduplicated = true + playlist link.
            val dupUpload = sendHttp(
                ssl, port, "POST", "/web/library/upload",
                headers = listOf(
                    "Origin" to origin,
                    "Sec-Fetch-Site" to "same-origin",
                    "Cookie" to cookiePair,
                    "X-Swararia-Csrf" to csrf,
                    "Content-Type" to "application/octet-stream",
                    "X-Swararia-Filename" to "Duplicate%20Bumper.wav",
                    "X-Swararia-Sha256" to storedSha,
                    "X-Swararia-Playlist" to "Sesi%20Baru",
                ),
                body = storedAudio,
            )
            assertEquals(200, dupUpload.status)
            assertTrue(DesktopRemoteJson.objectValue(dupUpload.body).get("deduplicated").booleanValue())

            // 6) Semaphore(2) media transfer cap: 2 concurrent downloads hold media slots -> 3rd gets 429,
            // while /web/state control poll STILL completes immediately with 200!
            val dlResponses = arrayOfNulls<HttpResponse>(2)
            val t1 = Thread {
                dlResponses[0] = sendHttp(ssl, port, "GET", "/web/media/77", headers = listOf("Cookie" to cookiePair))
            }
            val t2 = Thread {
                dlResponses[1] = sendHttp(ssl, port, "GET", "/web/media/trk_77", headers = listOf("Cookie" to cookiePair))
            }
            t1.start()
            t2.start()
            assertTrue("Expected two downloads to hold mediaSemaphore", firstTwoStarted.await(4, TimeUnit.SECONDS))

            val throttled = sendHttp(ssl, port, "GET", "/web/media/77", headers = listOf("Cookie" to cookiePair))
            assertEquals(429, throttled.status)
            assertEquals("2", throttled.header("retry-after"))

            // Control state polling remains unblocked!
            val stateWhileDownloading = sendHttp(ssl, port, "GET", "/web/state", headers = listOf("Cookie" to cookiePair))
            assertEquals(200, stateWhileDownloading.status)

            releaseDownloads.countDown()
            t1.join(4000)
            t2.join(4000)
            assertEquals(200, dlResponses[0]?.status)
            assertArrayEquals(storedAudio, dlResponses[0]?.body)
            assertEquals(200, dlResponses[1]?.status)

            // 7) Deletion check blocks downloading deleted/deleting track.
            deletedTrack.set(true)
            val deletingBlocked = sendHttp(ssl, port, "GET", "/web/media/77", headers = listOf("Cookie" to cookiePair))
            assertEquals(409, deletingBlocked.status)
        } finally {
            listener.close()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }
}
