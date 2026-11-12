package com.swararia.audiolab

import org.junit.Assert.*
import org.junit.Test

class DesktopRemoteProtocolTest {
    private val pin = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
    private val invite = "a".repeat(43)
    @Test fun pairingIsStrictAndSecretsAreNotPrinted() {
        val text = "swararia1|192.168.1.2:8766|$pin|$invite"
        val parsed = DesktopRemotePairing.parse(text)
        assertEquals("192.168.1.2:8766", parsed.endpoint)
        assertFalse(parsed.toString().contains(invite))
        listOf(" $text", "$text\n", text.replace("192.168.1.2", "192.168.01.2"),
            text.replace("192.168.1.2", "8.8.8.8"), text.replace(":8766", ":0"),
            text.replace(":8766", ":08766"), text.replace(pin, "$pin="), "$text|extra").forEach {
            assertThrows(DesktopRemoteFailure::class.java) { DesktopRemotePairing.parse(it) }
        }
    }
    @Test fun strictJsonRejectsDuplicateKeysTrailingDataAndWrongProtocol() {
        assertThrows(DesktopRemoteFailure::class.java) { DesktopRemoteJson.objectValue("{\"x\":1,\"x\":2}".toByteArray()) }
        assertThrows(DesktopRemoteFailure::class.java) { DesktopRemoteJson.objectValue("{} {}".toByteArray()) }
        assertThrows(DesktopRemoteFailure::class.java) { DesktopRemoteJson.capabilities(DesktopRemoteJson.objectValue("{\"protocol\":\"v2\",\"commands\":[\"play\"]}".toByteArray())) }
        assertEquals(setOf("play", "pause", "stop"), DesktopRemoteJson.capabilities(DesktopRemoteJson.objectValue("{\"protocol\":\"v1\",\"commands\":[\"play\",\"pause\",\"stop\"],\"media\":false}".toByteArray())))
    }
}
