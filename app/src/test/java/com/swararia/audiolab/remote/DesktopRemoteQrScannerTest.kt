package com.swararia.audiolab

import com.google.android.gms.common.ConnectionResult
import com.google.mlkit.common.MlKitException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class DesktopRemoteQrScannerTest {
    private val validPairing = "swararia1|192.168.18.47:8767|" +
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32)) + "|" +
        "z".repeat(43)

    @Test
    fun barcodeValueClassificationPreservesExactSecretAndRejectsEmpty() {
        assertTrue(DesktopRemoteQrScanner.classifyBarcodeValue(null) is DesktopRemoteScanResult.Failed)
        assertTrue(DesktopRemoteQrScanner.classifyBarcodeValue("") is DesktopRemoteScanResult.Failed)

        val scanned = DesktopRemoteQrScanner.classifyBarcodeValue(validPairing)
        assertEquals(DesktopRemoteScanResult.Scanned(validPairing), scanned)
    }

    @Test
    fun errorAndPlayServicesClassificationStaysStaticAndSecretFree() {
        assertEquals(
            DesktopRemoteScanResult.Canceled,
            DesktopRemoteQrScanner.classifyScannerErrorCode(MlKitException.CODE_SCANNER_CANCELLED),
        )
        listOf(
            MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD,
            MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED,
            MlKitException.CODE_SCANNER_UNAVAILABLE,
            MlKitException.CODE_SCANNER_TASK_IN_PROGRESS,
            null,
        ).forEach { code ->
            val res = DesktopRemoteQrScanner.classifyScannerErrorCode(code)
            assertTrue(res is DesktopRemoteScanResult.Failed)
            assertFalse((res as DesktopRemoteScanResult.Failed).message.contains("swararia1"))
        }

        assertNull(DesktopRemoteQrScanner.classifyPlayServicesStatus(ConnectionResult.SUCCESS))
        assertNotNull(DesktopRemoteQrScanner.classifyPlayServicesStatus(ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED))
        assertNotNull(DesktopRemoteQrScanner.classifyPlayServicesStatus(ConnectionResult.SERVICE_MISSING))
    }

    @Test
    fun scannedPairingConnectsImmediatelyAndRejectsUnrelatedQr() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        var pairCalls = 0
        val api = object : DesktopRemoteApi {
            override suspend fun pair(pairing: DesktopRemotePairing): DesktopRemoteGrant {
                pairCalls++
                return DesktopRemoteGrant("d".repeat(24), DesktopRemoteSecret("t".repeat(43)))
            }
            override suspend fun session(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant) =
                DesktopRemoteApproval(grant.deviceId, "pending", null, 4e9)
            override suspend fun capabilities(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant) = emptySet<String>()
            override suspend fun state(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant) =
                DesktopRemotePlayback("view_only", true, null, null, null, null, null)
            override suspend fun command(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, action: String, key: String) =
                DesktopRemoteCommand("c".repeat(24), "accepted")
            override suspend fun commandStatus(pairing: DesktopRemotePairing, grant: DesktopRemoteGrant, id: String) =
                DesktopRemoteCommand(id, "applied")
        }
        try {
            val session = DesktopRemoteSession(api, scope, pollMillis = 100_000)
            session.visible(Any())

            val badQr = DesktopRemoteQrScanner.classifyBarcodeValue("https://example.com/not-swararia")
            assertTrue(badQr is DesktopRemoteScanResult.Scanned)
            session.connect((badQr as DesktopRemoteScanResult.Scanned).rawText)
            repeat(4) { yield() }
            assertEquals(0, pairCalls)
            assertEquals("disconnected", session.state.value.phase)
            assertEquals("Kode pairing tidak valid.", session.state.value.message)

            val goodQr = DesktopRemoteQrScanner.classifyBarcodeValue(validPairing)
            session.connect((goodQr as DesktopRemoteScanResult.Scanned).rawText)
            repeat(6) { yield() }
            assertEquals(1, pairCalls)
            assertEquals("pending", session.state.value.phase)
            assertEquals("192.168.18.47:8767", session.state.value.endpoint)
        } finally {
            scope.cancel()
        }
    }
}

