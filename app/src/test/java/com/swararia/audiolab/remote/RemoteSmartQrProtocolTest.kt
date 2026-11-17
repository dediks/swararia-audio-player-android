package com.swararia.audiolab.remote

import org.junit.Assert.*
import org.junit.Test

class RemoteSmartQrProtocolTest {
    private val validPin = "A".repeat(43)
    private val validInvite = "B".repeat(43)
    private val validSwararia1 = "swararia1|192.168.43.1:8443|$validPin|$validInvite"

    @Test fun encodesAndParsesSmartJoinQrAndRedactsSecretsInToString() {
        val encoded = RemoteSmartQrCodec.encodeSmartJoinQr(
            ssid = "AndroidShare_4821",
            passphrase = "stage-pass-2026",
            transportKind = RemotePeerTransportKind.LOCAL_HOTSPOT,
            rawPairing = validSwararia1,
        )
        assertTrue(encoded.startsWith("swararia2|"))
        val parsed = RemoteSmartQrCodec.parseQrInput(encoded)
        assertTrue(parsed is ParsedRemoteQr.SmartWifiAndPairing)
        val smart = parsed as ParsedRemoteQr.SmartWifiAndPairing
        assertEquals("AndroidShare_4821", smart.ssid)
        assertEquals("stage-pass-2026", smart.passphrase())
        assertEquals(RemotePeerTransportKind.LOCAL_HOTSPOT, smart.transportKind)
        assertEquals(validSwararia1, smart.rawPairing)
        assertFalse(smart.toString().contains("stage-pass-2026"))
    }

    @Test fun encodesAndParsesStandardWifiQrWithEscapedCharacters() {
        val ssid = "FOH;Stage:Main\\1"
        val pass = "pass,word;\"test\":99"
        val qr = RemoteSmartQrCodec.encodeStandardWifiQr(ssid, pass)
        assertTrue(qr.startsWith("WIFI:T:WPA;"))
        val parsed = RemoteSmartQrCodec.parseQrInput(qr)
        assertTrue(parsed is ParsedRemoteQr.WifiOnly)
        val wifi = parsed as ParsedRemoteQr.WifiOnly
        assertEquals(ssid, wifi.ssid)
        assertEquals(pass, wifi.passphrase())
        assertFalse(wifi.toString().contains(pass))
    }

    @Test fun acceptsDirectSwararia1AndRejectsMalformedSmartOrWifiPayloads() {
        val direct = RemoteSmartQrCodec.parseQrInput(validSwararia1)
        assertEquals(ParsedRemoteQr.DirectPairing(validSwararia1), direct)

        val badInputs = listOf(
            "",
            "swararia2|bad",
            "swararia2|QQ|BBBBBBBB|unknown|$validSwararia1",
            "swararia2|QQ|BBBBBBBB|lh|swararia1|8.8.8.8:8443|$validPin|$validInvite",
            "WIFI:T:WEP;S:Test;P:12345678;;",
            "WIFI:T:WPA;S:Test;P:short;;",
            "WIFI:T:WPA;S:${"a".repeat(33)};P:12345678;;",
        )
        for (bad in badInputs) {
            assertNull("Expected null for $bad", RemoteSmartQrCodec.parseQrInput(bad))
        }
    }
}
