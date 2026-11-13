package com.swararia.audiolab

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallClient
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal sealed interface DesktopRemoteScanResult {
    data class Scanned(val rawText: String) : DesktopRemoteScanResult
    data object Canceled : DesktopRemoteScanResult
    data class Failed(val message: String) : DesktopRemoteScanResult
}

/**
 * Play Services Code Scanner adapter for one-shot QR pairing text.
 * Requires no CAMERA permission and keeps scanned pairing secrets strictly in transient RAM.
 */
internal object DesktopRemoteQrScanner {
    private const val MODULE_WAIT_MS = 25_000L

    private sealed interface ModulePrepOutcome {
        data object Ready : ModulePrepOutcome
        data class Stop(val result: DesktopRemoteScanResult) : ModulePrepOutcome
    }

    internal fun classifyPlayServicesStatus(status: Int): String? = when (status) {
        ConnectionResult.SUCCESS -> null
        ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED ->
            "Google Play Services perlu diperbarui untuk scan QR. Gunakan input manual di bawah."
        else ->
            "Layanan Google Play tidak tersedia di perangkat ini. Gunakan input pairing manual di bawah."
    }

    internal fun classifyBarcodeValue(raw: String?): DesktopRemoteScanResult =
        if (raw.isNullOrEmpty()) DesktopRemoteScanResult.Failed("QR tidak berisi teks pairing.")
        else DesktopRemoteScanResult.Scanned(raw)

    internal fun classifyScannerErrorCode(errorCode: Int?): DesktopRemoteScanResult = when (errorCode) {
        MlKitException.CODE_SCANNER_CANCELLED -> DesktopRemoteScanResult.Canceled
        MlKitException.CODE_SCANNER_GOOGLE_PLAY_SERVICES_VERSION_TOO_OLD ->
            DesktopRemoteScanResult.Failed("Google Play Services terlalu lama untuk scan QR. Perbarui Play Services atau gunakan input manual.")
        MlKitException.CODE_SCANNER_CAMERA_PERMISSION_NOT_GRANTED ->
            DesktopRemoteScanResult.Failed("Izin kamera pada Google Play Services ditolak. Gunakan input pairing manual di bawah.")
        MlKitException.CODE_SCANNER_UNAVAILABLE ->
            DesktopRemoteScanResult.Failed("Modul scanner QR belum siap. Pastikan Play Services/internet aktif saat pertama pakai, atau gunakan input manual.")
        MlKitException.CODE_SCANNER_TASK_IN_PROGRESS ->
            DesktopRemoteScanResult.Failed("Scanner QR sedang dibuka.")
        else ->
            DesktopRemoteScanResult.Failed("Scanner QR gagal dibuka. Gunakan input teks pairing manual di bawah.")
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private suspend fun isModuleAvailable(client: ModuleInstallClient, scanner: GmsBarcodeScanner): Boolean =
        suspendCancellableCoroutine { cont ->
            val settled = AtomicBoolean(false)
            client.areModulesAvailable(scanner)
                .addOnSuccessListener { response ->
                    if (settled.compareAndSet(false, true)) cont.resume(response.areModulesAvailable())
                }
                .addOnFailureListener {
                    if (settled.compareAndSet(false, true)) cont.resume(false)
                }
                .addOnCanceledListener {
                    if (settled.compareAndSet(false, true)) cont.resume(false)
                }
        }

    private suspend fun ensureScannerModule(
        client: ModuleInstallClient,
        scanner: GmsBarcodeScanner,
        onProgress: (String?) -> Unit,
    ): DesktopRemoteScanResult? {
        if (isModuleAvailable(client, scanner)) return null
        onProgress("Menyiapkan modul scanner QR (hanya sekali)…")
        val outcome: ModulePrepOutcome = withTimeoutOrNull(MODULE_WAIT_MS) {
            suspendCancellableCoroutine<ModulePrepOutcome> { cont ->
                val settled = AtomicBoolean(false)
                val listener = object : InstallStatusListener {
                    override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                        val next: ModulePrepOutcome = when (update.installState) {
                            InstallState.STATE_COMPLETED -> ModulePrepOutcome.Ready
                            InstallState.STATE_CANCELED -> ModulePrepOutcome.Stop(DesktopRemoteScanResult.Canceled)
                            InstallState.STATE_FAILED -> ModulePrepOutcome.Stop(classifyScannerErrorCode(MlKitException.CODE_SCANNER_UNAVAILABLE))
                            else -> return
                        }
                        client.unregisterListener(this)
                        if (settled.compareAndSet(false, true)) cont.resume(next)
                    }
                }
                cont.invokeOnCancellation { client.unregisterListener(listener) }
                val request = ModuleInstallRequest.newBuilder()
                    .addApi(scanner)
                    .setListener(listener)
                    .build()
                client.installModules(request)
                    .addOnSuccessListener { response ->
                        if (response.areModulesAlreadyInstalled()) {
                            client.unregisterListener(listener)
                            if (settled.compareAndSet(false, true)) cont.resume(ModulePrepOutcome.Ready)
                        }
                    }
                    .addOnFailureListener {
                        client.unregisterListener(listener)
                        if (settled.compareAndSet(false, true)) {
                            cont.resume(ModulePrepOutcome.Stop(classifyScannerErrorCode(MlKitException.CODE_SCANNER_UNAVAILABLE)))
                        }
                    }
            }
        } ?: return DesktopRemoteScanResult.Failed("Unduhan modul scanner QR melebihi batas waktu. Gunakan input pairing manual di bawah.")
        return when (outcome) {
            ModulePrepOutcome.Ready -> null
            is ModulePrepOutcome.Stop -> outcome.result
        }
    }

    private suspend fun launchScanner(scanner: GmsBarcodeScanner): DesktopRemoteScanResult =
        suspendCancellableCoroutine { cont ->
            val settled = AtomicBoolean(false)
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    if (settled.compareAndSet(false, true)) cont.resume(classifyBarcodeValue(barcode.rawValue))
                }
                .addOnCanceledListener {
                    if (settled.compareAndSet(false, true)) cont.resume(DesktopRemoteScanResult.Canceled)
                }
                .addOnFailureListener { exc ->
                    if (settled.compareAndSet(false, true)) {
                        cont.resume(classifyScannerErrorCode((exc as? MlKitException)?.errorCode))
                    }
                }
        }

    suspend fun scan(context: Context, onProgress: (String?) -> Unit): DesktopRemoteScanResult {
        val host = context.findActivity() ?: context
        val statusMessage = classifyPlayServicesStatus(
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(host)
        )
        if (statusMessage != null) return DesktopRemoteScanResult.Failed(statusMessage)
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        val scanner = GmsBarcodeScanning.getClient(host, options)
        val prepFailure = ensureScannerModule(ModuleInstall.getClient(host), scanner, onProgress)
        if (prepFailure != null) return prepFailure
        onProgress(null)
        return launchScanner(scanner)
    }
}

