package com.swararia.audiolab

import org.junit.Assert.*
import org.junit.Test
import java.security.cert.CertificateException
import java.security.interfaces.ECPublicKey

class AndroidRemoteTlsIdentityTest {
    @Test
    fun generatedCertificateMatchesDesktopRemoteTrustManagerForPrivateAndLoopbackIps() {
        val identity = AndroidRemoteTlsIdentity.generate(
            primaryIp = "192.168.43.1",
            extraIps = listOf("127.0.0.1"),
            commonName = "Swararia Test Server",
        )
        assertTrue(identity.certificate.publicKey is ECPublicKey)
        assertEquals(-1, identity.certificate.basicConstraints) // cA = false
        assertTrue(identity.certificate.extendedKeyUsage.contains("1.3.6.1.5.5.7.3.1"))
        assertTrue(identity.certificate.keyUsage[0]) // digitalSignature
        assertEquals(32, identity.fingerprintBytes.size)
        assertEquals(43, identity.fingerprintBase64Url.length)
        assertEquals(64, identity.fingerprintHex.length)

        val sanEntries = identity.certificate.subjectAlternativeNames
            ?.filter { (it[0] as? Int) == 7 }
            ?.mapNotNull { it[1] as? String }
            .orEmpty()
        assertEquals(listOf("192.168.43.1", "127.0.0.1"), sanEntries)

        // Exact certificate pin & matching SAN IP must be trusted by DesktopRemoteTrustManager.
        DesktopRemoteTrustManager(identity.fingerprintBytes, "192.168.43.1")
            .checkServerTrusted(arrayOf(identity.certificate), "EC")
        DesktopRemoteTrustManager(identity.fingerprintBytes, "127.0.0.1")
            .checkServerTrusted(arrayOf(identity.certificate), "EC")

        // Mismatched IP or mismatched fingerprint must fail closed.
        assertThrows(CertificateException::class.java) {
            DesktopRemoteTrustManager(identity.fingerprintBytes, "192.168.43.2")
                .checkServerTrusted(arrayOf(identity.certificate), "EC")
        }
        assertThrows(CertificateException::class.java) {
            DesktopRemoteTrustManager(ByteArray(32), "192.168.43.1")
                .checkServerTrusted(arrayOf(identity.certificate), "EC")
        }
    }

    @Test
    fun publicAndMalformedIpsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidRemoteTlsIdentity.generate(primaryIp = "8.8.8.8")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidRemoteTlsIdentity.generate(primaryIp = "192.168.01.1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            AndroidRemoteTlsIdentity.generate(primaryIp = "not-an-ip")
        }
    }
}

