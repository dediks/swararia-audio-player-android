package com.swararia.audiolab

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale

class RemoteServerError(val code: String, val httpStatus: Int = 403) : Exception(code)

enum class RemoteClientKind { NATIVE, WEB }

data class RemoteDeviceSnapshot(
    val deviceId: String,
    val label: String,
    val status: String,
    val role: String?,
    val kind: RemoteClientKind,
    val transferAllowed: Boolean,
    val expiresAt: Double,
    val csrfToken: String? = null,
)

data class RemoteConnectedDeviceView(
    val deviceId: String,
    val label: String,
    val status: String,
    val role: String?,
    val kind: RemoteClientKind,
    val transferAllowed: Boolean,
    val expiresAt: Double,
)

data class RemoteServerPlaybackSnapshot(
    val online: Boolean = true,
    val playing: Boolean = false,
    val transportState: String = "PAUSED",
    val currentTimeSec: Double? = null,
    val durationSec: Double? = null,
    val currentTrackNumericId: Long? = null,
    val previewActive: Boolean = false,
)

data class RemoteLibrarySourceTrack(
    val id: Long,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val artist: String?,
    val album: String?,
    val sha256: String?,
)

data class RemoteLibrarySourcePlaylist(
    val id: Long,
    val name: String,
    val trackIds: List<Long>,
)

data class RemoteServerCatalogTrack(
    val numericId: Long,
    val id: String,
    val title: String,
    val displayName: String,
    val sizeBytes: Long,
    val durationMs: Long?,
    val artist: String?,
    val album: String?,
    val sha256: String?,
    val playlistIds: List<String>,
)

data class RemoteServerCatalogGroup(
    val numericPlaylistId: Long?,
    val id: String,
    val name: String,
    val count: Int,
    val trackIds: List<String>,
)

data class RemoteEngineUiSnapshot(
    val enabled: Boolean = false,
    val bindIp: String? = null,
    val port: Int? = null,
    val fingerprintHex: String? = null,
    val fingerprintBase64Url: String? = null,
    val nativePairingUri: String? = null,
    val nativeInviteExpiresAt: Double? = null,
    val webUrl: String? = null,
    val webPin: String? = null,
    val webPinExpiresAt: Double? = null,
    val mediaTransferEnabled: Boolean = false,
    val pauseTransferDuringPlayback: Boolean = true,
    val devices: List<RemoteConnectedDeviceView> = emptyList(),
)

data class WebPairResult(
    val deviceId: String,
    val cookieToken: String,
    val csrfToken: String,
    val status: String,
)

data class RemoteCommandExecutionRequest(
    val action: String,
    val trackNumericId: Long? = null,
    val autoPlay: Boolean = false,
    val groupId: String? = null,
    val queueNumericIds: List<Long> = emptyList(),
)

data class RemoteCommandExecutionResult(
    val applied: Boolean,
    val errorCode: String? = null,
    val updatedPlayback: RemoteServerPlaybackSnapshot? = null,
)

/**
 * Android-free server state, authorization, pairing, catalog and FOH command engine.
 * Enforces separate auth planes (v1 Native vs web Browser) and separate explicit permission
 * for media transfers/sync (global toggle default OFF + per-device control-only grant).
 */
class AndroidRemoteServerEngine(
    private val clock: () -> Double = { System.currentTimeMillis() / 1000.0 },
    private val onStateChanged: () -> Unit = {},
) {
    private data class MutableDevice(
        val deviceId: String,
        val label: String,
        var status: String,
        var role: String?,
        val kind: RemoteClientKind,
        var transferAllowed: Boolean,
        var expiresAt: Double,
        val tokenHash: ByteArray,
        var csrfToken: String?,
    )

    private data class CommandRecord(
        val commandId: String,
        val deviceId: String,
        val key: String,
        val action: String,
        val trackId: String?,
        val autoPlay: Boolean,
        val groupId: String?,
        var status: String,
        val createdAt: Double,
        val expiresAt: Double,
    )

    private data class TokenBucket(var tokens: Double, var lastRefill: Double)

    private val lock = Any()
    private val random = SecureRandom()

    private var enabled = false
    private var bindIp: String? = null
    private var port: Int? = null
    private var fingerprintBase64Url: String? = null
    private var fingerprintHex: String? = null

    private var nativeInvite: String? = null
    private var nativeInviteExpiresAt = 0.0
    private var webPin: String? = null
    private var webPinExpiresAt = 0.0
    private var webPinFailures = 0

    private var mediaTransferEnabled = false
    private var pauseTransferDuringPlayback = true

    private val devices = LinkedHashMap<String, MutableDevice>()
    private val commandRecords = LinkedHashMap<String, CommandRecord>()

    private var tracksById = LinkedHashMap<String, RemoteServerCatalogTrack>()
    private var tracksByNumericId = LinkedHashMap<Long, RemoteServerCatalogTrack>()
    private var groupsById = LinkedHashMap<String, RemoteServerCatalogGroup>()
    private var activeGroupId: String = ALL_GROUP_ID
    private var catalogVersion = 0

    private var playback = RemoteServerPlaybackSnapshot(online = false, transportState = "STOPPED")

    private val allBucket = TokenBucket(120.0, clock())
    private val pairBucket = TokenBucket(10.0, clock())

    fun uiSnapshot(): RemoteEngineUiSnapshot = synchronized(lock) {
        pruneAuthStateLocked()
        val ip = bindIp
        val p = port
        val b64 = fingerprintBase64Url
        val inv = nativeInvite
        val uri = if (enabled && ip != null && p != null && b64 != null && inv != null) {
            "swararia1|$ip:$p|$b64|$inv"
        } else null
        val url = if (enabled && ip != null && p != null) "https://$ip:$p/" else null
        RemoteEngineUiSnapshot(
            enabled = enabled,
            bindIp = ip,
            port = p,
            fingerprintHex = fingerprintHex,
            fingerprintBase64Url = b64,
            nativePairingUri = uri,
            nativeInviteExpiresAt = if (inv != null) nativeInviteExpiresAt else null,
            webUrl = url,
            webPin = webPin,
            webPinExpiresAt = if (webPin != null) webPinExpiresAt else null,
            mediaTransferEnabled = mediaTransferEnabled,
            pauseTransferDuringPlayback = pauseTransferDuringPlayback,
            devices = devices.values.map {
                RemoteConnectedDeviceView(
                    deviceId = it.deviceId,
                    label = it.label,
                    status = it.status,
                    role = it.role,
                    kind = it.kind,
                    transferAllowed = it.transferAllowed,
                    expiresAt = it.expiresAt,
                )
            },
        )
    }

    fun activate(bindIp: String, port: Int, fingerprintBase64Url: String, fingerprintHex: String) {
        synchronized(lock) {
            enabled = true
            this.bindIp = bindIp
            this.port = port
            this.fingerprintBase64Url = fingerprintBase64Url
            this.fingerprintHex = fingerprintHex
            nativeInvite = null
            nativeInviteExpiresAt = 0.0
            webPin = null
            webPinExpiresAt = 0.0
            webPinFailures = 0
            devices.clear()
            commandRecords.clear()
            allBucket.tokens = 120.0
            allBucket.lastRefill = clock()
            pairBucket.tokens = 10.0
            pairBucket.lastRefill = clock()
        }
        onStateChanged()
    }

    fun deactivate() {
        synchronized(lock) {
            enabled = false
            bindIp = null
            port = null
            fingerprintBase64Url = null
            fingerprintHex = null
            nativeInvite = null
            nativeInviteExpiresAt = 0.0
            webPin = null
            webPinExpiresAt = 0.0
            webPinFailures = 0
            mediaTransferEnabled = false
            devices.clear()
            commandRecords.clear()
        }
        onStateChanged()
    }

    fun setMediaTransferEnabled(allowed: Boolean) {
        synchronized(lock) {
            mediaTransferEnabled = allowed
            if (!allowed) {
                for (d in devices.values) d.transferAllowed = false
            }
        }
        onStateChanged()
    }

    fun setPauseTransferDuringPlayback(pause: Boolean) {
        synchronized(lock) { pauseTransferDuringPlayback = pause }
        onStateChanged()
    }

    fun isMediaTransferPausedByPlayback(): Boolean = synchronized(lock) {
        pauseTransferDuringPlayback && playback.playing
    }

    fun issueNativeInvite(): String {
        val code = synchronized(lock) {
            requireEnabledLocked()
            val token = randomToken43()
            nativeInvite = token
            nativeInviteExpiresAt = clock() + INVITE_TTL_SEC
            token
        }
        onStateChanged()
        return code
    }

    fun issueWebPin(): String {
        val pin = synchronized(lock) {
            requireEnabledLocked()
            val generated = buildString(8) {
                repeat(8) { append(WEB_PIN_ALPHABET[random.nextInt(WEB_PIN_ALPHABET.length)]) }
            }
            webPin = generated
            webPinExpiresAt = clock() + INVITE_TTL_SEC
            webPinFailures = 0
            generated
        }
        onStateChanged()
        return pin
    }

    fun updateLibrary(
        tracks: List<RemoteLibrarySourceTrack>,
        playlists: List<RemoteLibrarySourcePlaylist>,
        excludedIds: Set<String> = emptySet(),
    ) {
        val changed = synchronized(lock) {
            val playableTracks = tracks.filter { it.id.toString() !in excludedIds }
            val playableIds = playableTracks.map { it.id }.toHashSet()
            val playlistIdsByTrack = HashMap<Long, MutableList<String>>()
            val nextGroups = LinkedHashMap<String, RemoteServerCatalogGroup>()

            val allTrackIds = playableTracks.map { trackIdString(it.id) }
            nextGroups[ALL_GROUP_ID] = RemoteServerCatalogGroup(
                numericPlaylistId = null,
                id = ALL_GROUP_ID,
                name = "Semua Lagu",
                count = allTrackIds.size,
                trackIds = allTrackIds,
            )

            for (pl in playlists.take(MAX_GROUPS - 1)) {
                val gid = groupIdString(pl.id)
                val cleanName = sanitizeTitle(pl.name, 60) ?: "Playlist ${pl.id}"
                val memberIds = pl.trackIds.filter { it in playableIds }.map { trackIdString(it) }
                nextGroups[gid] = RemoteServerCatalogGroup(
                    numericPlaylistId = pl.id,
                    id = gid,
                    name = cleanName,
                    count = memberIds.size,
                    trackIds = memberIds,
                )
                for (tid in pl.trackIds) {
                    if (tid in playableIds) playlistIdsByTrack.getOrPut(tid) { ArrayList() }.add(gid)
                }
            }

            val nextById = LinkedHashMap<String, RemoteServerCatalogTrack>(playableTracks.size)
            val nextByNum = LinkedHashMap<Long, RemoteServerCatalogTrack>(playableTracks.size)
            for (t in playableTracks) {
                val sid = trackIdString(t.id)
                val cleanTitle = sanitizeTitle(UiPresentation.title(t.displayName), 140) ?: "Audio ${t.id}"
                val cleanFile = sanitizeTitle(t.displayName, 160) ?: "audio_${t.id}"
                val item = RemoteServerCatalogTrack(
                    numericId = t.id,
                    id = sid,
                    title = cleanTitle,
                    displayName = cleanFile,
                    sizeBytes = t.sizeBytes,
                    durationMs = t.durationMs,
                    artist = sanitizeTitle(t.artist, 100),
                    album = sanitizeTitle(t.album, 100),
                    sha256 = t.sha256,
                    playlistIds = playlistIdsByTrack[t.id]?.toList().orEmpty(),
                )
                nextById[sid] = item
                nextByNum[t.id] = item
            }

            tracksById = nextById
            tracksByNumericId = nextByNum
            groupsById = nextGroups
            if (activeGroupId !in nextGroups) activeGroupId = ALL_GROUP_ID
            catalogVersion++
            true
        }
        if (changed) onStateChanged()
    }

    fun updatePlayback(snapshot: RemoteServerPlaybackSnapshot) {
        synchronized(lock) { playback = snapshot }
    }

    fun checkRateLimit(category: String) {
        synchronized(lock) {
            val now = clock()
            val (bucket, capacity, refillPerSec) = when (category) {
                "pair" -> Triple(pairBucket, 10.0, 1.0 / 6.0)
                "media" -> Triple(allBucket, 120.0, 20.0)
                else -> Triple(allBucket, 120.0, 20.0)
            }
            bucket.tokens = minOf(capacity, bucket.tokens + (now - bucket.lastRefill).coerceAtLeast(0.0) * refillPerSec)
            bucket.lastRefill = now
            if (bucket.tokens < 1.0) throw RemoteServerError("rate_limited", 429)
            bucket.tokens -= 1.0
        }
    }

    fun pairNative(inviteToken: String, rawLabel: String): Map<String, Any?> {
        val result = synchronized(lock) {
            requireEnabledLocked()
            pruneAuthStateLocked()
            val expected = nativeInvite ?: throw RemoteServerError("invalid_invite", 401)
            if (!INVITE_REGEX.matches(inviteToken) || !constantTimeEquals(inviteToken, expected)) {
                throw RemoteServerError("invalid_invite", 401)
            }
            val label = sanitizeLabel(rawLabel) ?: throw RemoteServerError("invalid_label", 400)
            ensureDeviceSlotLocked()
            nativeInvite = null
            nativeInviteExpiresAt = 0.0

            val deviceId = randomId24()
            val token = randomToken43()
            devices[deviceId] = MutableDevice(
                deviceId = deviceId,
                label = label,
                status = "pending",
                role = null,
                kind = RemoteClientKind.NATIVE,
                transferAllowed = false,
                expiresAt = clock() + PENDING_TTL_SEC,
                tokenHash = sha256(token),
                csrfToken = null,
            )
            mapOf("device_id" to deviceId, "token" to token, "status" to "pending")
        }
        onStateChanged()
        return result
    }

    fun pairWeb(pinInput: String, rawLabel: String): WebPairResult {
        val outcome = synchronized(lock) {
            requireEnabledLocked()
            pruneAuthStateLocked()
            val expected = webPin ?: throw RemoteServerError("invalid_pin", 401)
            val normalizedPin = pinInput.trim().uppercase(Locale.ROOT)
            if (normalizedPin.length != 8 || !constantTimeEquals(normalizedPin, expected)) {
                webPinFailures++
                if (webPinFailures >= WEB_PIN_MAX_FAILURES) {
                    webPin = null
                    webPinExpiresAt = 0.0
                }
                throw RemoteServerError("invalid_pin", 401)
            }
            val label = sanitizeLabel(rawLabel) ?: throw RemoteServerError("invalid_label", 400)
            ensureDeviceSlotLocked()
            webPin = null
            webPinExpiresAt = 0.0
            webPinFailures = 0

            val deviceId = randomId24()
            val cookieToken = randomToken43()
            val csrfToken = randomToken43()
            devices[deviceId] = MutableDevice(
                deviceId = deviceId,
                label = label,
                status = "pending",
                role = null,
                kind = RemoteClientKind.WEB,
                transferAllowed = false,
                expiresAt = clock() + PENDING_TTL_SEC,
                tokenHash = sha256(cookieToken),
                csrfToken = csrfToken,
            )
            WebPairResult(deviceId = deviceId, cookieToken = cookieToken, csrfToken = csrfToken, status = "pending")
        }
        onStateChanged()
        return outcome
    }

    fun authenticateNative(
        bearerToken: String,
        requireActive: Boolean = true,
        requireControl: Boolean = false,
        requireTransfer: Boolean = false,
    ): RemoteDeviceSnapshot = synchronized(lock) {
        authenticateLocked(
            rawToken = bearerToken,
            expectedKind = RemoteClientKind.NATIVE,
            csrfHeader = null,
            requireCsrf = false,
            requireActive = requireActive,
            requireControl = requireControl,
            requireTransfer = requireTransfer,
        )
    }

    fun authenticateWeb(
        cookieToken: String,
        csrfHeader: String? = null,
        requireCsrf: Boolean = false,
        requireActive: Boolean = true,
        requireControl: Boolean = false,
        requireTransfer: Boolean = false,
    ): RemoteDeviceSnapshot = synchronized(lock) {
        authenticateLocked(
            rawToken = cookieToken,
            expectedKind = RemoteClientKind.WEB,
            csrfHeader = csrfHeader,
            requireCsrf = requireCsrf,
            requireActive = requireActive,
            requireControl = requireControl,
            requireTransfer = requireTransfer,
        )
    }

    fun logoutWeb(cookieToken: String, csrfHeader: String?) {
        val revoked = synchronized(lock) {
            val device = findDeviceByTokenLocked(cookieToken, RemoteClientKind.WEB)
                ?: throw RemoteServerError("unauthorized", 401)
            val expectedCsrf = device.csrfToken ?: throw RemoteServerError("csrf_invalid", 403)
            if (csrfHeader == null || !constantTimeEquals(csrfHeader, expectedCsrf)) {
                throw RemoteServerError("csrf_invalid", 403)
            }
            device.status = "revoked"
            device.transferAllowed = false
            device.csrfToken = null
            cancelDeviceCommandsLocked(device.deviceId)
            true
        }
        if (revoked) onStateChanged()
    }

    fun approveDevice(deviceId: String, role: String, allowTransfer: Boolean) {
        synchronized(lock) {
            requireEnabledLocked()
            pruneAuthStateLocked()
            if (role !in setOf("control", "view_only")) throw RemoteServerError("invalid_role", 400)
            val d = devices[deviceId] ?: throw RemoteServerError("unknown_device", 404)
            if (d.status !in setOf("pending", "active") || clock() >= d.expiresAt) {
                throw RemoteServerError("unknown_device", 404)
            }
            if (role == "view_only") cancelDeviceCommandsLocked(deviceId)
            d.status = "active"
            d.role = role
            d.transferAllowed = (role == "control") && mediaTransferEnabled && allowTransfer
            d.expiresAt = clock() + SESSION_TTL_SEC
        }
        onStateChanged()
    }

    fun setDeviceTransferAllowed(deviceId: String, allow: Boolean) {
        synchronized(lock) {
            requireEnabledLocked()
            val d = devices[deviceId] ?: throw RemoteServerError("unknown_device", 404)
            if (d.status != "active" || d.role != "control") throw RemoteServerError("view_only", 403)
            if (allow && !mediaTransferEnabled) throw RemoteServerError("transfer_disabled", 403)
            d.transferAllowed = allow
        }
        onStateChanged()
    }

    fun revokeDevice(deviceId: String, deny: Boolean = false) {
        synchronized(lock) {
            val d = devices[deviceId] ?: return
            d.status = if (deny) "denied" else "revoked"
            d.transferAllowed = false
            d.csrfToken = null
            cancelDeviceCommandsLocked(deviceId)
        }
        onStateChanged()
    }

    fun sessionPayload(device: RemoteDeviceSnapshot, includeCsrfForWeb: Boolean = false): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        map["device_id"] = device.deviceId
        map["status"] = device.status
        map["role"] = device.role
        map["expires_at"] = device.expiresAt
        map["transfer_allowed"] = device.transferAllowed
        if (includeCsrfForWeb && device.csrfToken != null) {
            map["csrf_token"] = device.csrfToken
        }
        return map
    }

    fun capabilitiesPayload(device: RemoteDeviceSnapshot): Map<String, Any?> = synchronized(lock) {
        mapOf(
            "protocol" to "v1",
            "commands" to ALLOWED_ACTIONS,
            "media" to (mediaTransferEnabled && device.transferAllowed && device.role == "control"),
            "command_ttl_seconds" to COMMAND_TTL_SEC.toInt(),
            "idempotency_ttl_seconds" to RECORD_TTL_SEC.toInt(),
        )
    }

    fun statePayload(device: RemoteDeviceSnapshot): Map<String, Any?> = synchronized(lock) {
        val curTrack = playback.currentTrackNumericId?.let { tracksByNumericId[it] }
        val title = curTrack?.title
        val snapshot = linkedMapOf<String, Any?>(
            "is_playing" to playback.playing,
            "transport_state" to playback.transportState,
            "current_time" to playback.currentTimeSec,
            "duration" to playback.durationSec,
            "track_title" to title,
        )
        mapOf(
            "protocol" to "v1",
            "role" to device.role,
            "master_online" to (playback.online && !playback.previewActive),
            "transfer_allowed" to (mediaTransferEnabled && device.transferAllowed && device.role == "control"),
            "paused_for_playback" to pauseTransferDuringPlayback,
            "state" to snapshot,
        )
    }

    fun tracksPayload(device: RemoteDeviceSnapshot, webView: Boolean = false): Map<String, Any?> = synchronized(lock) {
        val online = playback.online && !playback.previewActive
        val groupsList = groupsById.values.take(MAX_GROUPS).map { g ->
            linkedMapOf<String, Any?>(
                "id" to g.id,
                "name" to g.name,
                "count" to g.count,
            )
        }
        val activeGroup = groupsById[activeGroupId] ?: groupsById[ALL_GROUP_ID]
        val groupLabel = activeGroup?.name
        val candidateTracks = if (activeGroup != null && activeGroup.id != ALL_GROUP_ID) {
            activeGroup.trackIds.mapNotNull { tracksById[it] }
        } else {
            tracksById.values.toList()
        }
        val maxItems = if (webView) MAX_MANIFEST_TRACKS else MAX_TRACKS
        val limitedTracks = candidateTracks.take(maxItems)
        val serializedTracks = limitedTracks.map { item ->
            val gid = when {
                activeGroup != null && activeGroup.id != ALL_GROUP_ID -> activeGroup.id
                else -> item.playlistIds.firstOrNull()
            }
            val row = linkedMapOf<String, Any?>(
                "id" to item.id,
                "title" to item.title,
                "group_id" to gid,
            )
            if (webView) {
                row["display_name"] = item.displayName
                row["artist"] = item.artist
                row["duration_ms"] = item.durationMs
                row["size_bytes"] = item.sizeBytes
                row["playlist_ids"] = item.playlistIds
            }
            row
        }
        val currentSid = playback.currentTrackNumericId?.let { tracksByNumericId[it]?.id }
            ?.takeIf { sid -> limitedTracks.any { it.id == sid } }
        val payload = linkedMapOf<String, Any?>(
            "protocol" to "v1",
            "master_online" to online,
            "catalog_version" to catalogVersion,
            "playlist" to groupLabel,
            "active_group_id" to activeGroup?.id,
            "total_tracks" to tracksById.size,
            "groups" to groupsList,
            "current_track_id" to currentSid,
            "tracks" to serializedTracks,
        )
        if (webView) {
            payload["transfer_allowed"] = (mediaTransferEnabled && device.transferAllowed && device.role == "control")
        }
        payload
    }

    fun manifestPayload(): Map<String, Any?> = synchronized(lock) {
        val playlistsPayload = groupsById.values
            .filter { it.numericPlaylistId != null }
            .take(MAX_GROUPS)
            .map { g ->
                mapOf(
                    "id" to g.id,
                    "name" to g.name,
                    "track_ids" to g.trackIds,
                )
            }
        val tracksPayload = tracksById.values.take(MAX_MANIFEST_TRACKS).map { t ->
            mapOf(
                "id" to t.id,
                "title" to t.title,
                "display_name" to t.displayName,
                "size_bytes" to t.sizeBytes,
                "duration_ms" to t.durationMs,
                "artist" to t.artist,
                "album" to t.album,
                "sha256" to t.sha256,
                "playlist_ids" to t.playlistIds,
            )
        }
        mapOf(
            "protocol" to "v1",
            "transfer_enabled" to mediaTransferEnabled,
            "paused_for_playback" to pauseTransferDuringPlayback,
            "playback_active" to playback.playing,
            "max_upload_bytes" to MAX_UPLOAD_BYTES,
            "allowed_extensions" to ALLOWED_AUDIO_EXTENSIONS,
            "playlists" to playlistsPayload,
            "tracks" to tracksPayload,
        )
    }

    fun executeCommand(
        device: RemoteDeviceSnapshot,
        action: String,
        idempotencyKey: String,
        trackId: String? = null,
        autoPlay: Boolean = false,
        groupId: String? = null,
        executor: (RemoteCommandExecutionRequest) -> RemoteCommandExecutionResult,
    ): Map<String, Any?> {
        var cachedOutcome: Map<String, Any?>? = null
        var dispatchPair: Pair<String, RemoteCommandExecutionRequest>? = null
        synchronized(lock) {
            requireEnabledLocked()
            if (action !in ALLOWED_ACTIONS || !KEY_REGEX.matches(idempotencyKey)) {
                throw RemoteServerError("invalid_command", 400)
            }
            when (action) {
                "select_track" -> if (trackId == null || !TRACK_ID_REGEX.matches(trackId) || groupId != null) {
                    throw RemoteServerError("invalid_command", 400)
                }
                "select_group" -> if (groupId == null || !GROUP_ID_REGEX.matches(groupId) || trackId != null || autoPlay) {
                    throw RemoteServerError("invalid_command", 400)
                }
                else -> if (trackId != null || autoPlay || groupId != null) {
                    throw RemoteServerError("invalid_command", 400)
                }
            }
            val normalizedGroupId = if (groupId == "all") ALL_GROUP_ID else groupId

            pruneCommandRecordsLocked()
            for (r in commandRecords.values) {
                if (r.deviceId == device.deviceId && r.key == idempotencyKey) {
                    if (r.action != action || r.trackId != trackId || r.autoPlay != autoPlay || r.groupId != normalizedGroupId) {
                        throw RemoteServerError("idempotency_conflict", 409)
                    }
                    cachedOutcome = commandResultMap(r)
                    break
                }
            }
            if (cachedOutcome == null) {
                if (commandRecords.size >= MAX_RECORDS) throw RemoteServerError("command_limit", 429)
                if (!playback.online || playback.previewActive) throw RemoteServerError("master_unavailable", 409)

                var targetNumericId: Long? = null
                var queueNumericIds: List<Long> = emptyList()
                if (action == "select_track") {
                    val track = tracksById[trackId] ?: throw RemoteServerError("unknown_track", 409)
                    targetNumericId = track.numericId
                    val activeGroup = groupsById[activeGroupId]
                    queueNumericIds = if (activeGroup != null && track.id in activeGroup.trackIds) {
                        activeGroup.trackIds.mapNotNull { tracksById[it]?.numericId }
                    } else {
                        tracksById.values.map { it.numericId }
                    }
                } else if (action == "select_group") {
                    val targetGroup = groupsById[normalizedGroupId] ?: throw RemoteServerError("unknown_group", 409)
                    activeGroupId = targetGroup.id
                    queueNumericIds = targetGroup.trackIds.mapNotNull { tracksById[it]?.numericId }
                } else if (action == "play") {
                    val activeGroup = groupsById[activeGroupId] ?: groupsById[ALL_GROUP_ID]
                    queueNumericIds = activeGroup?.trackIds?.mapNotNull { tracksById[it]?.numericId }.orEmpty()
                }

                val cid = randomId24()
                val now = clock()
                val record = CommandRecord(
                    commandId = cid,
                    deviceId = device.deviceId,
                    key = idempotencyKey,
                    action = action,
                    trackId = trackId,
                    autoPlay = autoPlay,
                    groupId = normalizedGroupId,
                    status = "accepted",
                    createdAt = now,
                    expiresAt = now + COMMAND_TTL_SEC,
                )
                commandRecords[cid] = record
                dispatchPair = cid to RemoteCommandExecutionRequest(
                    action = action,
                    trackNumericId = targetNumericId,
                    autoPlay = autoPlay,
                    groupId = normalizedGroupId,
                    queueNumericIds = queueNumericIds,
                )
            }
        }
        cachedOutcome?.let { return it }
        val validated = checkNotNull(dispatchPair)

        val outcome = if (validated.second.action == "select_group") {
            RemoteCommandExecutionResult(applied = true)
        } else {
            runCatching { executor(validated.second) }.getOrElse {
                RemoteCommandExecutionResult(applied = false, errorCode = "execution_failed")
            }
        }

        synchronized(lock) {
            val rec = commandRecords[validated.first] ?: return mapOf("command_id" to validated.first, "status" to "unknown")
            if (outcome.applied) {
                rec.status = "applied"
                outcome.updatedPlayback?.let { playback = it }
            } else if (rec.status == "accepted" || rec.status == "pending") {
                rec.status = "rejected"
            }
            return commandResultMap(rec)
        }
    }

    fun commandStatus(device: RemoteDeviceSnapshot, commandId: String): Map<String, Any?> = synchronized(lock) {
        pruneCommandRecordsLocked()
        val r = commandRecords[commandId]
        if (r == null || r.deviceId != device.deviceId) {
            mapOf("command_id" to commandId, "status" to "unknown")
        } else {
            commandResultMap(r)
        }
    }

    fun parseTrackNumericId(rawId: String): Long? {
        val trimmed = rawId.trim()
        return when {
            TRACK_ID_REGEX.matches(trimmed) -> trimmed.removePrefix("trk_").toLongOrNull()
            Regex("[1-9][0-9]{0,18}").matches(trimmed) -> trimmed.toLongOrNull()
            else -> null
        }
    }

    private fun commandResultMap(r: CommandRecord): Map<String, Any?> = mapOf(
        "command_id" to r.commandId,
        "action" to r.action,
        "status" to r.status,
        "expires_at" to r.expiresAt,
    )

    private fun requireEnabledLocked() {
        if (!enabled) throw RemoteServerError("disabled", 403)
    }

    private fun ensureDeviceSlotLocked() {
        if (devices.size < MAX_DEVICES) return
        val removableKey = devices.entries.firstOrNull { (_, d) ->
            d.status in setOf("revoked", "denied", "expired")
        }?.key
        if (removableKey != null) {
            devices.remove(removableKey)
            return
        }
        throw RemoteServerError("device_limit", 429)
    }

    private fun pruneAuthStateLocked() {
        val now = clock()
        if (nativeInvite != null && now >= nativeInviteExpiresAt) {
            nativeInvite = null
            nativeInviteExpiresAt = 0.0
        }
        if (webPin != null && now >= webPinExpiresAt) {
            webPin = null
            webPinExpiresAt = 0.0
            webPinFailures = 0
        }
        for (d in devices.values) {
            if (d.status in setOf("pending", "active") && now >= d.expiresAt) {
                d.status = "expired"
                d.transferAllowed = false
                d.csrfToken = null
                cancelDeviceCommandsLocked(d.deviceId)
            }
        }
    }

    private fun pruneCommandRecordsLocked() {
        val now = clock()
        val iter = commandRecords.entries.iterator()
        while (iter.hasNext()) {
            val r = iter.next().value
            if (now >= r.expiresAt && r.status in setOf("accepted", "pending")) {
                r.status = if (r.status == "accepted") "expired" else "unknown"
            }
            if (now >= r.createdAt + RECORD_TTL_SEC) {
                iter.remove()
            }
        }
    }

    private fun cancelDeviceCommandsLocked(deviceId: String) {
        for (r in commandRecords.values) {
            if (r.deviceId == deviceId) {
                if (r.status == "accepted") r.status = "rejected"
                else if (r.status == "pending") r.status = "unknown"
            }
        }
    }

    private fun findDeviceByTokenLocked(rawToken: String, expectedKind: RemoteClientKind): MutableDevice? {
        if (rawToken.length !in 20..128) return null
        val digest = sha256(rawToken)
        for (d in devices.values) {
            if (d.kind == expectedKind && MessageDigest.isEqual(d.tokenHash, digest)) {
                return d
            }
        }
        return null
    }

    private fun authenticateLocked(
        rawToken: String,
        expectedKind: RemoteClientKind,
        csrfHeader: String?,
        requireCsrf: Boolean,
        requireActive: Boolean,
        requireControl: Boolean,
        requireTransfer: Boolean,
    ): RemoteDeviceSnapshot {
        requireEnabledLocked()
        pruneAuthStateLocked()
        val device = findDeviceByTokenLocked(rawToken, expectedKind) ?: throw RemoteServerError("unauthorized", 401)
        if (device.status in setOf("revoked", "denied", "expired")) {
            throw RemoteServerError(device.status, 403)
        }
        if (requireCsrf) {
            val expectedCsrf = device.csrfToken ?: throw RemoteServerError("csrf_invalid", 403)
            if (csrfHeader == null || !constantTimeEquals(csrfHeader, expectedCsrf)) {
                throw RemoteServerError("csrf_invalid", 403)
            }
        }
        if (requireActive && device.status != "active") throw RemoteServerError("pending", 403)
        if (requireControl && device.role != "control") throw RemoteServerError("view_only", 403)
        if (requireTransfer && (!mediaTransferEnabled || !device.transferAllowed || device.role != "control")) {
            throw RemoteServerError("transfer_disabled", 403)
        }
        return RemoteDeviceSnapshot(
            deviceId = device.deviceId,
            label = device.label,
            status = device.status,
            role = device.role,
            kind = device.kind,
            transferAllowed = mediaTransferEnabled && device.transferAllowed && device.role == "control",
            expiresAt = device.expiresAt,
            csrfToken = device.csrfToken,
        )
    }

    private fun randomId24(): String {
        val buf = ByteArray(18)
        random.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    private fun randomToken43(): String {
        val buf = ByteArray(32)
        random.nextBytes(buf)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
    }

    companion object {
        const val ALL_GROUP_ID = "g_all"
        const val INVITE_TTL_SEC = 90.0
        const val PENDING_TTL_SEC = 120.0
        const val SESSION_TTL_SEC = 8.0 * 3600.0
        const val MAX_DEVICES = 32
        const val COMMAND_TTL_SEC = 4.0
        const val RECORD_TTL_SEC = 300.0
        const val MAX_RECORDS = 256
        const val MAX_TRACKS = 250
        const val MAX_MANIFEST_TRACKS = 5000
        const val MAX_GROUPS = 64
        const val WEB_PIN_MAX_FAILURES = 5
        const val MAX_UPLOAD_BYTES = 150L * 1024L * 1024L
        val ALLOWED_AUDIO_EXTENSIONS = listOf("aac", "flac", "m4a", "mp3", "mp4", "ogg", "opus", "wav", "webm")
        private const val WEB_PIN_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

        val ALLOWED_ACTIONS = listOf("play", "pause", "stop", "fade_out", "prev", "next", "select_track", "select_group")
        private val INVITE_REGEX = Regex("[A-Za-z0-9_-]{43}")
        private val TRACK_ID_REGEX = Regex("trk_[1-9][0-9]{0,18}")
        private val GROUP_ID_REGEX = Regex("(?:g_all|all|pl_[1-9][0-9]{0,18})")
        private val KEY_REGEX = Regex("[A-Za-z0-9_-]{8,80}")

        fun trackIdString(id: Long): String = "trk_$id"
        fun groupIdString(playlistId: Long): String = "pl_$playlistId"

        fun sanitizeTitle(raw: String?, maxLen: Int = 140): String? {
            if (raw.isNullOrBlank() || raw.contains("://")) return null
            val tail = raw.replace('\\', '/').substringAfterLast('/')
            val cleaned = buildString(tail.length) {
                for (ch in tail) {
                    if (!ch.isISOControl()) append(ch)
                }
            }.trim().take(maxLen)
            return cleaned.ifEmpty { null }
        }

        fun sanitizeLabel(raw: String?): String? {
            if (raw == null) return null
            val cleaned = buildString(raw.length) {
                for (ch in raw) {
                    if (!ch.isISOControl()) append(ch)
                }
            }.trim()
            return if (cleaned.length in 1..80) cleaned else null
        }

        private fun sha256(value: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

        private fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }
}
