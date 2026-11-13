package com.swararia.audiolab

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

data class RemoteTlsIdentity(
    val sslContext: SSLContext,
    val fingerprintBytes: ByteArray,
    val fingerprintBase64Url: String,
    val fingerprintHex: String,
    val certificate: X509Certificate,
    val validUntilEpochSec: Long,
) {
    fun formattedHexColon(): String = fingerprintHex.chunked(2).joinToString(":") { it.uppercase(Locale.ROOT) }
}

/**
 * Generates an ephemeral in-memory EC P-256 self-signed X.509 v3 TLS identity without external crypto
 * libraries or disk key files. Matches DesktopRemoteTrustManager (IP SAN + EKU serverAuth + CA=false).
 */
object AndroidRemoteTlsIdentity {
    private val random = SecureRandom()

    fun generate(
        primaryIp: String,
        extraIps: List<String> = emptyList(),
        commonName: String = "Swararia Android Server",
        ttlHours: Long = 12L,
        nowMillis: Long = System.currentTimeMillis(),
    ): RemoteTlsIdentity {
        val uniqueIps = LinkedHashMap<String, ByteArray>()
        for (candidate in listOf(primaryIp) + extraIps) {
            val bytes = parsePrivateOrLoopbackIpv4(candidate)
                ?: throw IllegalArgumentException("IP server harus berupa IPv4 privat atau loopback yang valid.")
            uniqueIps[candidate] = bytes
        }
        require(uniqueIps.isNotEmpty()) { "Minimal satu IPv4 untuk SAN sertifikat." }

        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"), random)
        }.generateKeyPair()

        val notBeforeMillis = nowMillis - 60_000L
        val notAfterMillis = nowMillis + ttlHours * 3_600_000L

        val serial = ByteArray(16).also { buf ->
            random.nextBytes(buf)
            buf[0] = (buf[0].toInt() and 0x7F).toByte()
            if (buf.all { it.toInt() == 0 }) buf[15] = 1
            if (buf[0].toInt() == 0) buf[0] = 1
        }

        val sigAlgId = derSequence(derOid(1, 2, 840, 10045, 4, 3, 2)) // ecdsa-with-SHA256 (RFC 5758: omit NULL)
        val dn = derSequence(
            derSet(
                derSequence(
                    derOid(2, 5, 4, 3), // commonName
                    derUtf8(commonName),
                ),
            ),
        )
        val validity = derSequence(
            derUtcTime(notBeforeMillis),
            derUtcTime(notAfterMillis),
        )
        val basicConstraintsExt = derExtension(
            oid = intArrayOf(2, 5, 29, 19),
            critical = true,
            value = derSequence(), // cA = FALSE
        )
        val keyUsageExt = derExtension(
            oid = intArrayOf(2, 5, 29, 15),
            critical = true,
            value = derBitString(byteArrayOf(0x80.toByte()), unusedBits = 7), // digitalSignature
        )
        val ekuExt = derExtension(
            oid = intArrayOf(2, 5, 29, 37),
            critical = false,
            value = derSequence(derOid(1, 3, 6, 1, 5, 5, 7, 3, 1)), // id-kp-serverAuth
        )
        val sanItems = uniqueIps.values.map { ipBytes ->
            derTaggedPrimitive(0x87, ipBytes) // GeneralName [7] iPAddress
        }.toTypedArray()
        val sanExt = derExtension(
            oid = intArrayOf(2, 5, 29, 17),
            critical = false,
            value = derSequence(*sanItems),
        )
        val extensionsWrapper = derExplicitTag(0xA3, derSequence(basicConstraintsExt, keyUsageExt, ekuExt, sanExt))

        val tbsCertificate = derSequence(
            derExplicitTag(0xA0, derInteger(byteArrayOf(0x02))), // v3 = 2
            derInteger(serial),
            sigAlgId,
            dn, // issuer
            validity,
            dn, // subject
            keyPair.public.encoded, // SubjectPublicKeyInfo DER
            extensionsWrapper,
        )

        val signatureBytes = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private, random)
            update(tbsCertificate)
            sign()
        }

        val fullCertDer = derSequence(
            tbsCertificate,
            sigAlgId,
            derBitString(signatureBytes, unusedBits = 0),
        )

        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(fullCertDer)) as X509Certificate
        cert.verify(cert.publicKey)

        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        val b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
        val hex = digest.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xFF) }

        val ksPassword = CharArray(32)
        val passwordBytes = ByteArray(32).also { random.nextBytes(it) }
        for (i in ksPassword.indices) {
            val code = 33 + ((passwordBytes[i].toInt() and 0xFF) % 90)
            ksPassword[i] = code.toChar()
        }
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, ksPassword)
            setKeyEntry("swararia-remote", keyPair.private, ksPassword, arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, ksPassword)
        }
        ksPassword.fill('\u0000')
        passwordBytes.fill(0)

        val sslContext = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, random)
        }
        return RemoteTlsIdentity(
            sslContext = sslContext,
            fingerprintBytes = digest,
            fingerprintBase64Url = b64,
            fingerprintHex = hex,
            certificate = cert,
            validUntilEpochSec = notAfterMillis / 1000L,
        )
    }

    fun parsePrivateOrLoopbackIpv4(host: String): ByteArray? {
        val parts = host.trim().split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val piece = parts[i]
            if (!Regex("0|[1-9][0-9]{0,2}").matches(piece)) return null
            val v = piece.toIntOrNull() ?: return null
            if (v !in 0..255) return null
            octets[i] = v
        }
        val isLoopback = octets[0] == 127
        val isPrivate = octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
        if (!isLoopback && !isPrivate) return null
        return byteArrayOf(octets[0].toByte(), octets[1].toByte(), octets[2].toByte(), octets[3].toByte())
    }

    private fun derExtension(oid: IntArray, critical: Boolean, value: ByteArray): ByteArray {
        val oidDer = derOid(*oid)
        val valDer = derOctetString(value)
        return if (critical) derSequence(oidDer, byteArrayOf(0x01, 0x01, 0xFF.toByte()), valDer)
        else derSequence(oidDer, valDer)
    }

    private fun derSequence(vararg elements: ByteArray): ByteArray = wrapDer(0x30, concat(*elements))
    private fun derSet(vararg elements: ByteArray): ByteArray = wrapDer(0x31, concat(*elements))
    private fun derOctetString(bytes: ByteArray): ByteArray = wrapDer(0x04, bytes)
    private fun derUtf8(value: String): ByteArray = wrapDer(0x0C, value.toByteArray(Charsets.UTF_8))
    private fun derExplicitTag(tag: Int, inner: ByteArray): ByteArray = wrapDer(tag, inner)
    private fun derTaggedPrimitive(tag: Int, bytes: ByteArray): ByteArray = wrapDer(tag, bytes)

    private fun derInteger(positiveBytes: ByteArray): ByteArray {
        val normalized = if (positiveBytes.isNotEmpty() && (positiveBytes[0].toInt() and 0x80) != 0) {
            byteArrayOf(0x00) + positiveBytes
        } else positiveBytes
        return wrapDer(0x02, normalized)
    }

    private fun derBitString(bytes: ByteArray, unusedBits: Int): ByteArray {
        val out = ByteArray(1 + bytes.size)
        out[0] = unusedBits.toByte()
        System.arraycopy(bytes, 0, out, 1, bytes.size)
        return wrapDer(0x03, out)
    }

    private fun derUtcTime(epochMillis: Long): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return wrapDer(0x17, fmt.format(Date(epochMillis)).toByteArray(Charsets.US_ASCII))
    }

    private fun derOid(vararg arcs: Int): ByteArray {
        require(arcs.size >= 2)
        val buf = ByteArrayOutputStream()
        buf.write(arcs[0] * 40 + arcs[1])
        for (i in 2 until arcs.size) {
            var value = arcs[i]
            val encoded = ByteArray(5)
            var count = 0
            var first = true
            do {
                encoded[count++] = ((value and 0x7F) or (if (first) 0 else 0x80)).toByte()
                first = false
                value = value ushr 7
            } while (value > 0)
            for (j in count - 1 downTo 0) buf.write(encoded[j].toInt())
        }
        return wrapDer(0x06, buf.toByteArray())
    }

    private fun concat(vararg chunks: ByteArray): ByteArray {
        val total = chunks.sumOf { it.size }
        val out = ByteArray(total)
        var offset = 0
        for (c in chunks) {
            System.arraycopy(c, 0, out, offset, c.size)
            offset += c.size
        }
        return out
    }

    private fun wrapDer(tag: Int, content: ByteArray): ByteArray {
        val len = content.size
        val header = ByteArrayOutputStream()
        header.write(tag)
        when {
            len < 128 -> header.write(len)
            len <= 0xFF -> { header.write(0x81); header.write(len) }
            len <= 0xFFFF -> { header.write(0x82); header.write(len ushr 8); header.write(len and 0xFF) }
            else -> {
                header.write(0x83)
                header.write(len ushr 16)
                header.write((len ushr 8) and 0xFF)
                header.write(len and 0xFF)
            }
        }
        val hdr = header.toByteArray()
        val out = ByteArray(hdr.size + content.size)
        System.arraycopy(hdr, 0, out, 0, hdr.size)
        System.arraycopy(content, 0, out, hdr.size, content.size)
        return out
    }
}
