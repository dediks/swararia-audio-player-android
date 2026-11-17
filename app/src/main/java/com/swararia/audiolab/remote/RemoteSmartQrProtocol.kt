package com.swararia.audiolab.remote

import java.util.Base64

enum class RemotePeerTransportKind(val wire: String) {
    LOCAL_HOTSPOT("lh"),
    WIFI_DIRECT("p2p");

    companion object {
        fun fromWire(value: String): RemotePeerTransportKind? = entries.firstOrNull { it.wire == value }
    }
}

sealed interface ParsedRemoteQr {
    data class DirectPairing(val rawPairing: String) : ParsedRemoteQr
    data class SmartWifiAndPairing(
        val ssid: String,
        private val passphraseValue: String,
        val transportKind: RemotePeerTransportKind,
        val rawPairing: String,
    ) : ParsedRemoteQr {
        fun passphrase(): String = passphraseValue
        override fun toString(): String = "SmartWifiAndPairing(ssid=$ssid, kind=$transportKind, passphrase=[redacted])"
    }
    data class WifiOnly(
        val ssid: String,
        private val passphraseValue: String,
    ) : ParsedRemoteQr {
        fun passphrase(): String = passphraseValue
        override fun toString(): String = "WifiOnly(ssid=$ssid, passphrase=[redacted])"
    }
}

/**
 * Pure Kotlin encoder/decoder for Smart Join QR (`swararia2|...`) and standard Wi-Fi QR (`WIFI:T:WPA;...`).
 * Leaves `DesktopRemotePairing.parse()` (`swararia1|...`) untouched as the underlying trust & auth token.
 */
object RemoteSmartQrCodec {
    private const val SMART_PREFIX = "swararia2"
    private const val MAX_QR_LENGTH = 512

    fun isValidSsid(ssid: String): Boolean {
        if (ssid.isEmpty() || ssid.any { it.isISOControl() }) return false
        return ssid.toByteArray(Charsets.UTF_8).size in 1..32
    }

    fun isValidWpaPassphrase(passphrase: String): Boolean {
        if (passphrase.any { it.isISOControl() }) return false
        return passphrase.length in 8..63 || (passphrase.length == 64 && passphrase.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
    }

    fun encodeSmartJoinQr(
        ssid: String,
        passphrase: String,
        transportKind: RemotePeerTransportKind,
        rawPairing: String,
    ): String {
        require(isValidSsid(ssid)) { "SSID Wi-Fi tidak valid." }
        require(isValidWpaPassphrase(passphrase)) { "Passphrase Wi-Fi tidak valid." }
        DesktopRemotePairing.parse(rawPairing)
        val enc = Base64.getUrlEncoder().withoutPadding()
        val ssidB64 = enc.encodeToString(ssid.toByteArray(Charsets.UTF_8))
        val passB64 = enc.encodeToString(passphrase.toByteArray(Charsets.UTF_8))
        return "$SMART_PREFIX|$ssidB64|$passB64|${transportKind.wire}|$rawPairing"
    }

    fun encodeStandardWifiQr(ssid: String, passphrase: String): String {
        require(isValidSsid(ssid)) { "SSID Wi-Fi tidak valid." }
        require(isValidWpaPassphrase(passphrase)) { "Passphrase Wi-Fi tidak valid." }
        return "WIFI:T:WPA;S:${escapeWifiQrField(ssid)};P:${escapeWifiQrField(passphrase)};;"
    }

    fun parseQrInput(rawText: String): ParsedRemoteQr? {
        if (rawText.length !in 12..MAX_QR_LENGTH) return null
        if (rawText.startsWith("swararia1|")) {
            return runCatching {
                DesktopRemotePairing.parse(rawText)
                ParsedRemoteQr.DirectPairing(rawText)
            }.getOrNull()
        }
        if (rawText.startsWith("$SMART_PREFIX|")) {
            return parseSmartJoin(rawText)
        }
        if (rawText.startsWith("WIFI:", ignoreCase = false)) {
            return parseStandardWifiQr(rawText)
        }
        return null
    }

    private fun parseSmartJoin(rawText: String): ParsedRemoteQr.SmartWifiAndPairing? {
        // swararia2|<ssid_b64>|<pass_b64>|<kind>|swararia1|<endpoint>|<pin>|<invite>
        val parts = rawText.split('|', limit = 5)
        if (parts.size != 5 || parts[0] != SMART_PREFIX) return null
        val b64Regex = Regex("[A-Za-z0-9_-]{1,128}")
        if (!b64Regex.matches(parts[1]) || !b64Regex.matches(parts[2])) return null
        val kind = RemotePeerTransportKind.fromWire(parts[3]) ?: return null
        val inner = parts[4]
        val validInner = runCatching { DesktopRemotePairing.parse(inner) }.isSuccess
        if (!validInner) return null
        val dec = Base64.getUrlDecoder()
        val ssid = runCatching { String(dec.decode(parts[1]), Charsets.UTF_8) }.getOrNull() ?: return null
        val pass = runCatching { String(dec.decode(parts[2]), Charsets.UTF_8) }.getOrNull() ?: return null
        if (!isValidSsid(ssid) || !isValidWpaPassphrase(pass)) return null
        return ParsedRemoteQr.SmartWifiAndPairing(
            ssid = ssid,
            passphraseValue = pass,
            transportKind = kind,
            rawPairing = inner,
        )
    }

    private fun parseStandardWifiQr(rawText: String): ParsedRemoteQr.WifiOnly? {
        if (!rawText.startsWith("WIFI:") || !rawText.endsWith(";;")) return null
        val body = rawText.removePrefix("WIFI:")
        val fields = HashMap<String, String>()
        val token = StringBuilder()
        var escaped = false
        for (ch in body) {
            if (escaped) {
                token.append(ch)
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == ';') {
                val entry = token.toString()
                token.clear()
                if (entry.isNotEmpty()) {
                    val sep = entry.indexOf(':')
                    if (sep <= 0) return null
                    fields[entry.substring(0, sep)] = entry.substring(sep + 1)
                }
            } else {
                token.append(ch)
            }
        }
        if (escaped || token.isNotEmpty()) return null
        val auth = fields["T"] ?: return null
        if (auth != "WPA" && auth != "WPA2" && auth != "SAE") return null
        val ssid = fields["S"] ?: return null
        val pass = fields["P"] ?: return null
        if (!isValidSsid(ssid) || !isValidWpaPassphrase(pass)) return null
        return ParsedRemoteQr.WifiOnly(ssid = ssid, passphraseValue = pass)
    }

    private fun escapeWifiQrField(value: String): String = buildString(value.length) {
        for (ch in value) {
            if (ch == '\\' || ch == ';' || ch == ',' || ch == ':' || ch == '"') {
                append('\\')
            }
            append(ch)
        }
    }
}
