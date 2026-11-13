package com.swararia.audiolab

import org.junit.Assert.*
import org.junit.Test

class AndroidRemoteServerEngineTest {
    @Test
    fun pairingUriMatchesDesktopRemotePairingAndPlanesAreStrictlyIsolated() {
        var nowSec = 1_700_000_000.0
        val engine = AndroidRemoteServerEngine(clock = { nowSec })
        val tls = AndroidRemoteTlsIdentity.generate("192.168.43.1")
        engine.activate("192.168.43.1", 8443, tls.fingerprintBase64Url, tls.formattedHexColon())

        val nativeInvite = engine.issueNativeInvite()
        val webPin = engine.issueWebPin()
        val snap = engine.uiSnapshot()
        assertNotNull(snap.nativePairingUri)
        val parsed = DesktopRemotePairing.parse(snap.nativePairingUri!!)
        assertEquals("192.168.43.1:8443", parsed.endpoint)

        val nativePair = engine.pairNative(nativeInvite, "FOH Phone")
        val nativeId = nativePair["device_id"] as String
        val nativeToken = nativePair["token"] as String

        val webPair = engine.pairWeb(webPin, "FOH Tablet Browser")

        // Native token cannot authenticate on Web plane, and Web cookie cannot authenticate on Native plane.
        assertThrows(RemoteServerError::class.java) {
            engine.authenticateWeb(nativeToken, requireActive = false)
        }
        assertThrows(RemoteServerError::class.java) {
            engine.authenticateNative(webPair.cookieToken, requireActive = false)
        }

        engine.approveDevice(nativeId, "control", allowTransfer = false)
        engine.approveDevice(webPair.deviceId, "view_only", allowTransfer = false)

        val nativeAuth = engine.authenticateNative(nativeToken, requireControl = true)
        assertEquals("control", nativeAuth.role)

        // View-only cannot request control or CSRF-protected transfer.
        assertThrows(RemoteServerError::class.java) {
            engine.authenticateWeb(
                cookieToken = webPair.cookieToken,
                csrfHeader = webPair.csrfToken,
                requireCsrf = true,
                requireActive = true,
                requireControl = true,
            )
        }

        // Wrong CSRF fails closed even for active Web session.
        assertThrows(RemoteServerError::class.java) {
            engine.authenticateWeb(
                cookieToken = webPair.cookieToken,
                csrfHeader = "wrong-csrf",
                requireCsrf = true,
                requireActive = true,
            )
        }
    }

    @Test
    fun inviteAndPinTtlExpiryAndWebPinLockoutAfterFiveFailures() {
        var nowSec = 1_700_000_000.0
        val engine = AndroidRemoteServerEngine(clock = { nowSec })
        val tls = AndroidRemoteTlsIdentity.generate("127.0.0.1")
        engine.activate("127.0.0.1", 8443, tls.fingerprintBase64Url, tls.formattedHexColon())

        val invite = engine.issueNativeInvite()
        nowSec += AndroidRemoteServerEngine.INVITE_TTL_SEC + 1.0
        val expiredErr = assertThrows(RemoteServerError::class.java) {
            engine.pairNative(invite, "Late Client")
        }
        assertEquals(401, expiredErr.httpStatus)

        val freshPin = engine.issueWebPin()
        repeat(AndroidRemoteServerEngine.WEB_PIN_MAX_FAILURES) {
            assertThrows(RemoteServerError::class.java) {
                engine.pairWeb("00000000", "Brute")
            }
        }
        // Locked out after 5 wrong attempts: even the previously valid PIN must now fail.
        assertThrows(RemoteServerError::class.java) {
            engine.pairWeb(freshPin, "Real Browser")
        }
    }

    @Test
    fun mediaTransferDefaultsOffAndFollowsControlRoleAndPlaybackPausePolicy() {
        var nowSec = 1_700_000_000.0
        val engine = AndroidRemoteServerEngine(clock = { nowSec })
        val tls = AndroidRemoteTlsIdentity.generate("127.0.0.1")
        engine.activate("127.0.0.1", 8443, tls.fingerprintBase64Url, tls.formattedHexColon())

        assertFalse(engine.uiSnapshot().mediaTransferEnabled)
        assertTrue(engine.uiSnapshot().pauseTransferDuringPlayback)

        val pin = engine.issueWebPin()
        val pair = engine.pairWeb(pin, "Stage Laptop")
        // Global toggle is OFF -> even requesting allowTransfer = true yields transferAllowed = false.
        engine.approveDevice(pair.deviceId, "control", allowTransfer = true)
        assertThrows(RemoteServerError::class.java) {
            engine.authenticateWeb(pair.cookieToken, pair.csrfToken, requireCsrf = true, requireTransfer = true)
        }

        // Enable global toggle -> still requires explicit per-device grant or approval with allowTransfer.
        engine.setMediaTransferEnabled(true)
        assertThrows(RemoteServerError::class.java) {
            engine.authenticateWeb(pair.cookieToken, requireTransfer = true)
        }
        engine.setDeviceTransferAllowed(pair.deviceId, true)
        val enabledSnap = engine.authenticateWeb(pair.cookieToken, requireTransfer = true)
        assertTrue(enabledSnap.transferAllowed)

        // Turning global toggle OFF revokes all per-device transfer permissions.
        engine.setMediaTransferEnabled(false)
        assertThrows(RemoteServerError::class.java) {
            engine.authenticateWeb(pair.cookieToken, requireTransfer = true)
        }

        // View-only device can never be granted transfer permission.
        val invite = engine.issueNativeInvite()
        val nativePair = engine.pairNative(invite, "Read Only")
        val viewId = nativePair["device_id"] as String
        engine.setMediaTransferEnabled(true)
        engine.approveDevice(viewId, "view_only", allowTransfer = true)
        val viewErr = assertThrows(RemoteServerError::class.java) {
            engine.setDeviceTransferAllowed(viewId, true)
        }
        assertEquals("view_only", viewErr.code)

        // Playback pause policy blocks transfers only when playing == true && pauseTransferDuringPlayback == true.
        engine.updatePlayback(RemoteServerPlaybackSnapshot(online = true, playing = true, transportState = "PLAYING"))
        assertTrue(engine.isMediaTransferPausedByPlayback())
        engine.setPauseTransferDuringPlayback(false)
        assertFalse(engine.isMediaTransferPausedByPlayback())
    }

    @Test
    fun catalogGroupsAndCommandIdempotencyAndPreviewConflictWorkCleanly() {
        var nowSec = 1_700_000_000.0
        val engine = AndroidRemoteServerEngine(clock = { nowSec })
        val tls = AndroidRemoteTlsIdentity.generate("127.0.0.1")
        engine.activate("127.0.0.1", 8443, tls.fingerprintBase64Url, tls.formattedHexColon())
        engine.updateLibrary(
            tracks = listOf(
                RemoteLibrarySourceTrack(10L, "01 Intro.mp3", 1024L, 12_000L, "Artist", "Album", "a".repeat(64)),
                RemoteLibrarySourceTrack(20L, "02 Finale.wav", 2048L, 24_000L, null, null, "b".repeat(64)),
            ),
            playlists = listOf(
                RemoteLibrarySourcePlaylist(5L, "Sesi Malam", listOf(20L)),
            ),
        )
        engine.updatePlayback(RemoteServerPlaybackSnapshot(online = true, playing = false, transportState = "PAUSED"))

        val invite = engine.issueNativeInvite()
        val pair = engine.pairNative(invite, "Main Remote")
        val deviceId = pair["device_id"] as String
        val token = pair["token"] as String
        engine.approveDevice(deviceId, "control", allowTransfer = false)
        val device = engine.authenticateNative(token)

        var executions = 0
        var lastQueue = emptyList<Long>()
        val groupCmd = engine.executeCommand(
            device = device,
            action = "select_group",
            idempotencyKey = "key_group_1234",
            groupId = "pl_5",
        ) { req ->
            executions++
            RemoteCommandExecutionResult(applied = true)
        }
        assertEquals("applied", groupCmd["status"])
        assertEquals(0, executions) // select_group updates active engine group cleanly

        val tracksPayload = engine.tracksPayload(device)
        assertEquals("pl_5", tracksPayload["active_group_id"])
        assertEquals(1, (tracksPayload["tracks"] as List<*>).size)

        val playCmd = engine.executeCommand(
            device = device,
            action = "select_track",
            idempotencyKey = "key_track_1234",
            trackId = "trk_20",
            autoPlay = true,
        ) { req ->
            executions++
            lastQueue = req.queueNumericIds
            RemoteCommandExecutionResult(
                applied = true,
                updatedPlayback = RemoteServerPlaybackSnapshot(online = true, playing = true, transportState = "PLAYING", currentTrackNumericId = 20L),
            )
        }
        assertEquals(listOf(20L), lastQueue)
        assertEquals(1, executions)

        // Exact replay must reuse cached result without re-running executor.
        val replay = engine.executeCommand(
            device = device,
            action = "select_track",
            idempotencyKey = "key_track_1234",
            trackId = "trk_20",
            autoPlay = true,
        ) {
            executions++
            RemoteCommandExecutionResult(applied = true)
        }
        assertEquals(playCmd["command_id"], replay["command_id"])
        assertEquals(1, executions)

        // Same key with different parameters must return 409 conflict.
        val conflict = assertThrows(RemoteServerError::class.java) {
            engine.executeCommand(
                device = device,
                action = "pause",
                idempotencyKey = "key_track_1234",
            ) { RemoteCommandExecutionResult(applied = true) }
        }
        assertEquals(409, conflict.httpStatus)

        // Local search preview blocks remote commands until preview ends.
        engine.updatePlayback(RemoteServerPlaybackSnapshot(online = true, previewActive = true))
        val previewErr = assertThrows(RemoteServerError::class.java) {
            engine.executeCommand(
                device = device,
                action = "pause",
                idempotencyKey = "key_pause_9999",
            ) { RemoteCommandExecutionResult(applied = true) }
        }
        assertEquals("master_unavailable", previewErr.code)
    }
}

