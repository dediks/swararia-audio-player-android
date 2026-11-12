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
        fun parse(text: String): DesktopRemotePairing {
            fun invalid(): Nothing = throw DesktopRemoteFailure(DesktopRemoteFailure.Kind.INPUT)
            if (text.length !in 80..256) invalid()
            val parts = text.split('|')
            if (parts.size != 4 || parts[0] != "swararia1") invalid()
            val ep = parts[1].split(':')
            if (ep.size != 2) invalid()
            val octets = ep[0].split('.')
            if (octets.size != 4) invalid()
            val ip = octets.map { piece ->
                if (!piece.matches(Regex("0|[1-9][0-9]{0,2}"))) invalid()
                piece.toInt().also { if (it > 255) invalid() }
            }
            if (!(ip[0] == 10 || ip[0] == 172 && ip[1] in 16..31 || ip[0] == 192 && ip[1] == 168)) invalid()
            if (!ep[1].matches(Regex("[1-9][0-9]{0,4}")) || ep[1].toInt() !in 1..65535) invalid()
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
data class DesktopRemoteApproval(val deviceId: String, val status: String, val role: String?, val expiresAt: Double)
data class DesktopRemotePlayback(val role: String, val online: Boolean, val playing: Boolean?,
    val transport: String?, val time: Double?, val duration: Double?, val title: String?)
data class DesktopRemoteCommand(val id: String, val status: String)

object DesktopRemoteJson {
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    fun objectValue(bytes: ByteArray): JsonNode = checked {
        require(bytes.size in 2..16_384)
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
    fun capabilities(node: JsonNode): Set<String> {
        protocol(node)
        val commands = node.get("commands") ?: throw DesktopRemoteFailure()
        if (!commands.isArray || commands.size() !in 0..3 || commands.any { !it.isTextual || it.textValue() !in setOf("play", "pause", "stop") }) throw DesktopRemoteFailure()
        return commands.map { it.textValue() }.toSet().also { if (it.size != commands.size()) throw DesktopRemoteFailure() }
    }
    fun playback(node: JsonNode): DesktopRemotePlayback {
        protocol(node)
        val state = node.get("state") ?: throw DesktopRemoteFailure()
        if (!state.isObject) throw DesktopRemoteFailure()
        val title = state.get("track_title")?.let { if (it.isNull) null else text(state, "track_title", 160) }
        if (title != null && (title.any { it.isISOControl() } || title.contains("://") || title.contains('/') || title.contains('\\'))) throw DesktopRemoteFailure()
        val transport = state.get("transport_state")?.let {
            if (it.isNull) null else text(state, "transport_state", 32)
        }
        return DesktopRemotePlayback(role(node), bool(node, "master_online")!!, bool(state, "is_playing", true),
            transport, number(state, "current_time", true), number(state, "duration", true), title)
    }
    fun command(node: JsonNode): DesktopRemoteCommand {
        val status = text(node, "status")
        if (status !in setOf("accepted", "pending", "applied", "rejected", "expired", "unknown")) throw DesktopRemoteFailure()
        return DesktopRemoteCommand(id(node, "command_id"), status)
    }
    fun commandBody(action: String, key: String): ByteArray {
        if (action !in setOf("play", "pause", "stop") || !key.matches(Regex("[A-Za-z0-9_-]{8,80}"))) throw DesktopRemoteFailure()
        return mapper.writeValueAsBytes(mapOf("action" to action, "idempotency_key" to key))
    }
}
