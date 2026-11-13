package com.swararia.audiolab

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64

/** Static errors only: never expose input, server bodies or exception messages. */
class DesktopRemoteFailure(val kind: Kind = Kind.PROTOCOL) : Exception("Desktop remote request failed") {
    enum class Kind { INPUT, PROTOCOL, TRUST, AUTH, UNAVAILABLE }
}

class DesktopRemoteSecret(private val value: String) {
    fun bearer(): String = "Bearer $value"
    internal fun rawValue(): String = value
    override fun toString() = "[redacted]"
}

/** Pairing is QR TEXT, not a URL; no normalization of secret-bearing input. */
class DesktopRemotePairing private constructor(val endpoint: String, private val digest: ByteArray,
    private var invite: DesktopRemoteSecret?) {
    fun fingerprint(): ByteArray = digest.copyOf()
    fun invitation(): DesktopRemoteSecret = invite ?: throw DesktopRemoteFailure()
    fun forgetInvitation() { invite = null }
    override fun toString() = "DesktopRemotePairing($endpoint)"
    companion object {
        private fun invalid(): Nothing = throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.INPUT)

        private fun validatePrivateEndpoint(endpoint: String) {
            val ep = endpoint.split(':')
            if (ep.size != 2) invalid()
            val octets = ep[0].split('.')
            if (octets.size != 4) invalid()
            val ip = octets.map { piece ->
                if (!piece.matches(Regex("0|[1-9][0-9]{0,2}"))) invalid()
                piece.toInt().also { if (it > 255) invalid() }
            }
            if (!(ip[0] == 10 || ip[0] == 172 && ip[1] in 16..31 || ip[0] == 192 && ip[1] == 168)) invalid()
            if (!ep[1].matches(Regex("[1-9][0-9]{0,4}")) || ep[1].toInt() !in 1..65535) invalid()
        }

        fun fromSaved(endpoint: String, pinBytes: ByteArray): DesktopRemotePairing {
            validatePrivateEndpoint(endpoint)
            if (pinBytes.size != 32) invalid()
            return DesktopRemotePairing(endpoint, pinBytes.copyOf(), null)
        }

        fun parse(text: String): DesktopRemotePairing {
            if (text.length !in 80..256) invalid()
            val parts = text.split('|')
            if (parts.size != 4 || parts[0] != "swararia1") invalid()
            validatePrivateEndpoint(parts[1])
            if (!parts[2].matches(Regex("[A-Za-z0-9_-]{43}")) || !parts[3].matches(Regex("[A-Za-z0-9_-]{43}"))) invalid()
            val pin = try { Base64.getUrlDecoder().decode(parts[2]) } catch (_: IllegalArgumentException) { invalid() }
            if (pin.size != 32 || Base64.getUrlEncoder().withoutPadding().encodeToString(pin) != parts[2]) invalid()
            return DesktopRemotePairing(parts[1], pin, DesktopRemoteSecret(parts[3]))
        }
    }
}

class DesktopRemoteGrant(val deviceId: String, private val credential: DesktopRemoteSecret) {
    fun authorization() = credential
    override fun toString() = "DesktopRemoteGrant([redacted])"
}

/** Approved grant snapshot kept encrypted at rest for auto-reconnect across app restarts. */
data class PersistedDesktopRemoteSession(
    val endpoint: String,
    private val pinBytes: ByteArray,
    val deviceId: String,
    private val credential: DesktopRemoteSecret,
    val expiresAt: Double,
) {
    fun fingerprint(): ByteArray = pinBytes.copyOf()
    fun secret(): DesktopRemoteSecret = credential
    override fun equals(other: Any?): Boolean = other is PersistedDesktopRemoteSession &&
        endpoint == other.endpoint && pinBytes.contentEquals(other.pinBytes) &&
        deviceId == other.deviceId && credential.rawValue() == other.credential.rawValue() &&
        expiresAt == other.expiresAt
    override fun hashCode(): Int {
        var result = endpoint.hashCode()
        result = 31 * result + pinBytes.contentHashCode()
        result = 31 * result + deviceId.hashCode()
        result = 31 * result + credential.rawValue().hashCode()
        result = 31 * result + expiresAt.hashCode()
        return result
    }
    override fun toString() = "PersistedDesktopRemoteSession($endpoint)"
}

interface DesktopRemoteSessionStore {
    fun load(): PersistedDesktopRemoteSession? = null
    fun save(session: PersistedDesktopRemoteSession) {}
    fun clear() {}
}
data class DesktopRemoteApproval(val deviceId: String, val status: String, val role: String?, val expiresAt: Double)
data class DesktopRemotePlayback(val role: String, val online: Boolean, val playing: Boolean?,
    val transport: String?, val time: Double?, val duration: Double?, val title: String?)
data class DesktopRemoteCommand(val id: String, val status: String)
data class DesktopRemoteTrackGroup(val id: String, val name: String, val count: Int)
data class DesktopRemoteTrack(val id: String, val title: String, val groupId: String? = null)
data class DesktopRemoteCatalog(
    val online: Boolean,
    val version: Int,
    val playlist: String?,
    val currentTrackId: String?,
    val tracks: List<DesktopRemoteTrack>,
    val totalTracks: Int = tracks.size,
    val activeGroupId: String? = null,
    val groups: List<DesktopRemoteTrackGroup> = emptyList(),
)

object DesktopRemoteJson {
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    private val trackIdRegex = Regex("[A-Za-z0-9_-]{3,32}")
    private val groupIdRegex = Regex("[A-Za-z0-9_-]{2,32}")
    private val allowedCommands = setOf("play", "pause", "stop", "fade_out", "prev", "next", "select_track", "select_group")
    fun objectValue(bytes: ByteArray, maxBytes: Int = 16_384): JsonNode = checked {
        require(bytes.size in 2..maxBytes)
        mapper.factory.createParser(bytes).use { parser ->
            val node = mapper.readTree<JsonNode>(parser)
            require(node != null && node.isObject && parser.nextToken() == null)
            node
        }
    }
    private fun <T> checked(block: () -> T): T = try { block() }
        catch (_: Exception) { throw DesktopRemoteFailure() }
    private fun text(node: JsonNode, name: String, max: Int = 80): String {
        val value = node.get(name) ?: throw DesktopRemoteFailure()
        if (!value.isTextual || value.textValue().length !in 1..max) throw DesktopRemoteFailure()
        return value.textValue()
    }
    private fun id(node: JsonNode, name: String): String = text(node, name, 24).also {
        if (!it.matches(Regex("[A-Za-z0-9_-]{24}"))) throw DesktopRemoteFailure()
    }
    private fun role(node: JsonNode): String = text(node, "role").also {
        if (it !in setOf("control", "view_only")) throw DesktopRemoteFailure()
    }
    private fun number(node: JsonNode, name: String, nullable: Boolean = false): Double? {
        val v = node.get(name) ?: throw DesktopRemoteFailure()
        if (nullable && v.isNull) return null
        if (!v.isNumber || !v.doubleValue().isFinite() || v.doubleValue() < 0) throw DesktopRemoteFailure()
        return v.doubleValue()
    }
    private fun bool(node: JsonNode, name: String, nullable: Boolean = false): Boolean? {
        val v = node.get(name) ?: throw DesktopRemoteFailure()
        if (nullable && v.isNull) return null
        if (!v.isBoolean) throw DesktopRemoteFailure()
        return v.booleanValue()
    }
    private fun protocol(node: JsonNode) { if (text(node, "protocol") != "v1") throw DesktopRemoteFailure() }
    fun grant(node: JsonNode): DesktopRemoteGrant {
        if (text(node, "status") != "pending") throw DesktopRemoteFailure()
        val token = text(node, "token", 128)
        if (!token.matches(Regex("[A-Za-z0-9_-]{43}"))) throw DesktopRemoteFailure()
        return DesktopRemoteGrant(id(node, "device_id"), DesktopRemoteSecret(token))
    }
    fun approval(node: JsonNode): DesktopRemoteApproval {
        val status = text(node, "status")
        val role = if (status == "pending") {
            if (node.get("role")?.isNull != true) throw DesktopRemoteFailure()
            null
        } else if (status == "active") role(node) else throw DesktopRemoteFailure()
        return DesktopRemoteApproval(id(node, "device_id"), status, role, number(node, "expires_at")!!)
    }
    private fun safeTitle(node: JsonNode, name: String, max: Int = 160): String? {
        val value = node.get(name) ?: return null
        if (value.isNull) return null
        val textValue = text(node, name, max)
        if (textValue.any { it.isISOControl() } || textValue.contains("://") || textValue.contains('/') || textValue.contains('\\')) {
            throw DesktopRemoteFailure()
        }
        return textValue
    }
    private fun idToken(node: JsonNode, name: String, pattern: Regex, nullable: Boolean = false): String? {
        val value = node.get(name) ?: if (nullable) return null else throw DesktopRemoteFailure()
        if (nullable && value.isNull) return null
        if (!value.isTextual || !value.textValue().matches(pattern)) throw DesktopRemoteFailure()
        return value.textValue()
    }
    fun capabilities(node: JsonNode): Set<String> {
        protocol(node)
        val commands = node.get("commands") ?: throw DesktopRemoteFailure()
        if (!commands.isArray || commands.size() !in 0..allowedCommands.size || commands.any { !it.isTextual || it.textValue() !in allowedCommands }) throw DesktopRemoteFailure()
        return commands.map { it.textValue() }.toSet().also { if (it.size != commands.size()) throw DesktopRemoteFailure() }
    }
    fun playback(node: JsonNode): DesktopRemotePlayback {
        protocol(node)
        val state = node.get("state") ?: throw DesktopRemoteFailure()
        if (!state.isObject) throw DesktopRemoteFailure()
        val title = safeTitle(state, "track_title", 160)
        val transport = state.get("transport_state")?.let {
            if (it.isNull) null else text(state, "transport_state", 32)
        }
        return DesktopRemotePlayback(role(node), bool(node, "master_online")!!, bool(state, "is_playing", true),
            transport, number(state, "current_time", true), number(state, "duration", true), title)
    }
    fun tracks(node: JsonNode): DesktopRemoteCatalog {
        protocol(node)
        val online = bool(node, "master_online")!!
        val verNode = node.get("catalog_version") ?: throw DesktopRemoteFailure()
        if (!verNode.isIntegralNumber || verNode.intValue() < 0) throw DesktopRemoteFailure()
        val playlist = safeTitle(node, "playlist", 80)
        val activeGroupId = idToken(node, "active_group_id", groupIdRegex, nullable = true)
        val currentId = idToken(node, "current_track_id", trackIdRegex, nullable = true)
        val groupArr = node.get("groups")
        val seenGroups = HashSet<String>()
        val groups = ArrayList<DesktopRemoteTrackGroup>()
        if (groupArr != null && !groupArr.isNull) {
            if (!groupArr.isArray || groupArr.size() > 64) throw DesktopRemoteFailure()
            for (entry in groupArr) {
                if (!entry.isObject) throw DesktopRemoteFailure()
                val gid = idToken(entry, "id", groupIdRegex, nullable = false)!!
                if (!seenGroups.add(gid)) throw DesktopRemoteFailure()
                val name = safeTitle(entry, "name", 80) ?: throw DesktopRemoteFailure()
                val countNode = entry.get("count")
                val count = if (countNode == null || countNode.isNull) 0 else {
                    if (!countNode.isIntegralNumber || countNode.intValue() < 0) throw DesktopRemoteFailure()
                    countNode.intValue()
                }
                groups.add(DesktopRemoteTrackGroup(gid, name, count))
            }
        }
        val arr = node.get("tracks") ?: throw DesktopRemoteFailure()
        if (!arr.isArray || arr.size() > 250) throw DesktopRemoteFailure()
        val seen = HashSet<String>(arr.size())
        val items = ArrayList<DesktopRemoteTrack>(arr.size())
        for (entry in arr) {
            if (!entry.isObject) throw DesktopRemoteFailure()
            val tid = idToken(entry, "id", trackIdRegex, nullable = false)!!
            if (!seen.add(tid)) throw DesktopRemoteFailure()
            val title = safeTitle(entry, "title", 140) ?: throw DesktopRemoteFailure()
            val gid = idToken(entry, "group_id", groupIdRegex, nullable = true)?.takeIf { seenGroups.isEmpty() || it in seenGroups }
            items.add(DesktopRemoteTrack(tid, title, gid))
        }
        val totalNode = node.get("total_tracks")
        val totalTracks = if (totalNode != null && !totalNode.isNull) {
            if (!totalNode.isIntegralNumber || totalNode.intValue() < items.size) throw DesktopRemoteFailure()
            totalNode.intValue()
        } else items.size
        return DesktopRemoteCatalog(online, verNode.intValue(), playlist, currentId, items, totalTracks, activeGroupId, groups)
    }
    fun command(node: JsonNode): DesktopRemoteCommand {
        val status = text(node, "status")
        if (status !in setOf("accepted", "pending", "applied", "rejected", "expired", "unknown")) throw DesktopRemoteFailure()
        return DesktopRemoteCommand(id(node, "command_id"), status)
    }
    fun commandBody(action: String, key: String, trackId: String? = null, autoPlay: Boolean = false, groupId: String? = null): ByteArray {
        if (action !in allowedCommands || !key.matches(Regex("[A-Za-z0-9_-]{8,80}"))) throw DesktopRemoteFailure()
        if (action == "select_track") {
            if (groupId != null || trackId == null || !trackId.matches(trackIdRegex)) throw DesktopRemoteFailure()
            return mapper.writeValueAsBytes(mapOf(
                "action" to action,
                "idempotency_key" to key,
                "track_id" to trackId,
                "auto_play" to autoPlay,
            ))
        }
        if (action == "select_group") {
            if (trackId != null || autoPlay || groupId == null || !groupId.matches(groupIdRegex)) throw DesktopRemoteFailure()
            return mapper.writeValueAsBytes(mapOf(
                "action" to action,
                "idempotency_key" to key,
                "group_id" to groupId,
            ))
        }
        if (trackId != null || autoPlay || groupId != null) throw DesktopRemoteFailure()
        return mapper.writeValueAsBytes(mapOf("action" to action, "idempotency_key" to key))
    }

    fun encodePersistedSession(session: PersistedDesktopRemoteSession): ByteArray {
        val pin = Base64.getUrlEncoder().withoutPadding().encodeToString(session.fingerprint())
        return mapper.writeValueAsBytes(mapOf(
            "endpoint" to session.endpoint,
            "pin" to pin,
            "device_id" to session.deviceId,
            "token" to session.secret().rawValue(),
            "expires_at" to session.expiresAt,
        ))
    }

    fun decodePersistedSession(bytes: ByteArray): PersistedDesktopRemoteSession {
        val node = objectValue(bytes, maxBytes = 4_096)
        val endpoint = text(node, "endpoint", 32)
        val pinEncoded = text(node, "pin", 64)
        if (!pinEncoded.matches(Regex("[A-Za-z0-9_-]{43}"))) throw DesktopRemoteFailure()
        val pinBytes = try { Base64.getUrlDecoder().decode(pinEncoded) } catch (_: IllegalArgumentException) { throw DesktopRemoteFailure() }
        val validated = DesktopRemotePairing.fromSaved(endpoint, pinBytes)
        val deviceId = id(node, "device_id")
        val token = text(node, "token", 128)
        if (!token.matches(Regex("[A-Za-z0-9_-]{20,128}"))) throw DesktopRemoteFailure()
        val expiresAt = number(node, "expires_at") ?: throw DesktopRemoteFailure()
        if (expiresAt <= 0.0) throw DesktopRemoteFailure()
        return PersistedDesktopRemoteSession(
            endpoint = validated.endpoint,
            pinBytes = validated.fingerprint(),
            deviceId = deviceId,
            credential = DesktopRemoteSecret(token),
            expiresAt = expiresAt,
        )
    }
}
