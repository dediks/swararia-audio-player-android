package com.swararia.audiolab

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64

class DesktopRemoteTlsTest {
    private fun cert(value: String) = CertificateFactory.getInstance("X.509").generateCertificate(Base64.getDecoder().decode(value).inputStream()) as X509Certificate
    private val valid = cert("MIIBQzCB6aADAgECAgEBMAoGCCqGSM49BAMCMBYxFDASBgNVBAMMC3Rlc3QgcmVtb3RlMB4XDTIwMDEwMTAwMDAwMFoXDTQwMDEwMTAwMDAwMFowFjEUMBIGA1UEAwwLdGVzdCByZW1vdGUwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAS1ADcYIDo6jhB3MdGDpzfezx4RgG6uTZDv1LKFEdcR7ISYBCxO4DYsJg+TD1+5VItNAAOGVsouYGtio6OnlhkDoygwJjAPBgNVHREECDAGhwTAqAECMBMGA1UdJQQMMAoGCCsGAQUFBwMBMAoGCCqGSM49BAMCA0kAMEYCIQDiHqJO7GedaAWGpqnjWpymlhB2Z31j7fowLPGK+QUEgAIhAKAgG5+tbbI4Uut79HEuInN/kaoTF0A1HRNhXDgFzBVs")
    private val expired = cert("MIIBQjCB6aADAgECAgEBMAoGCCqGSM49BAMCMBYxFDASBgNVBAMMC3Rlc3QgcmVtb3RlMB4XDTIwMDEwMTAwMDAwMFoXDTIxMDEwMTAwMDAwMFowFjEUMBIGA1UEAwwLdGVzdCByZW1vdGUwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAS1ADcYIDo6jhB3MdGDpzfezx4RgG6uTZDv1LKFEdcR7ISYBCxO4DYsJg+TD1+5VItNAAOGVsouYGtio6OnlhkDoygwJjAPBgNVHREECDAGhwTAqAECMBMGA1UdJQQMMAoGCCsGAQUFBwMBMAoGCCqGSM49BAMCA0gAMEUCIQCOo3rhx1Fx53kztkYUvlhFoResWjpxyYzy+IJpQaVqNwIgJ73N1zJF4puQHg+PTO/DvjLXPfxbSg2L2NGifTQ9kUk=")
    @Test fun exactPinValidSelfSignedIpSanIsAccepted() {
        DesktopRemoteTrustManager(MessageDigest.getInstance("SHA-256").digest(valid.encoded), "192.168.1.2").checkServerTrusted(arrayOf(valid), "EC")
    }
    @Test fun wrongPinExpiredCertificateAndWrongIpFailClosed() {
        assertThrows(CertificateException::class.java) { DesktopRemoteTrustManager(ByteArray(32), "192.168.1.2").checkServerTrusted(arrayOf(valid), "EC") }
        assertThrows(CertificateException::class.java) { DesktopRemoteTrustManager(MessageDigest.getInstance("SHA-256").digest(expired.encoded), "192.168.1.2").checkServerTrusted(arrayOf(expired), "EC") }
        assertThrows(CertificateException::class.java) { DesktopRemoteTrustManager(MessageDigest.getInstance("SHA-256").digest(valid.encoded), "192.168.1.3").checkServerTrusted(arrayOf(valid), "EC") }
    }
}
