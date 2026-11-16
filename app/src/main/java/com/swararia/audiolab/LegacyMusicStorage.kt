package com.swararia.audiolab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Legacy public Music/SwarariaAudioLab/ file storage for Android 8.0–9.0 (API 26–28) where Scoped
 * Storage columns (RELATIVE_PATH, IS_PENDING, VOLUME_EXTERNAL_PRIMARY) do not exist.
 *
 * Keeps downloaded/imported/synced audio inside the public Music folder so songs survive uninstall,
 * while maintaining atomic pending markers inside `.swararia-pending` with `.nomedia`.
 */
internal class LegacyMusicStorage(private val context: Context) {
    fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        val readOk = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val writeOk = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        return readOk && writeOk
    }

    fun requireStoragePermission() {
        require(hasStoragePermission()) {
            "Berikan izin penyimpanan terlebih dahulu agar file dapat disimpan di Music/SwarariaAudioLab."
        }
    }

    @Suppress("DEPRECATION")
    fun publicMusicDir(): File {
        val musicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        return File(musicRoot, "SwarariaAudioLab")
    }

    private fun pendingDir(): File = File(publicMusicDir(), ".swararia-pending")

    private fun ensureDirectories(): Pair<File, File> {
        requireStoragePermission()
        val pub = publicMusicDir()
        if (!pub.exists()) check(pub.mkdirs() || pub.isDirectory) { "Gagal membuat folder Music/SwarariaAudioLab." }
        val pend = pendingDir()
        if (!pend.exists()) check(pend.mkdirs() || pend.isDirectory) { "Gagal membuat folder staging audio." }
        val nomedia = File(pend, ".nomedia")
        if (!nomedia.exists()) runCatching { nomedia.createNewFile() }
        return pub to pend
    }

    private fun resolveInside(dir: File, uriString: String): File? {
        val parsed = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        if (!"file".equals(parsed.scheme, ignoreCase = true)) return null
        val path = parsed.path ?: return null
        val candidate = File(path)
        val dirCanonical = runCatching { dir.canonicalFile }.getOrNull() ?: return null
        val fileCanonical = runCatching { candidate.canonicalFile }.getOrNull() ?: return null
        if (fileCanonical.parentFile != dirCanonical) return null
        return fileCanonical
    }

    fun resolveVisibleFile(uriString: String): File? {
        if (!ManagedCopyRules.validLegacyFileUri(uriString)) return null
        return resolveInside(publicMusicDir(), uriString)
    }

    fun isManagedItemExists(uriString: String): Boolean {
        val file = resolveVisibleFile(uriString) ?: return false
        return file.isFile
    }

    fun actualName(uriString: String): String {
        val file = resolveVisibleFile(uriString)
        if (file != null && file.isFile) return file.name
        error("File tidak ditemukan. Mungkin dipindah atau dihapus di luar aplikasi.")
    }

    fun openReadable(uriString: String): Pair<InputStream, Long>? {
        if (!hasStoragePermission()) return null
        val file = resolveVisibleFile(uriString) ?: return null
        if (!file.isFile) return null
        return FileInputStream(file) to file.length()
    }

    fun deleteVisible(uriString: String) {
        requireStoragePermission()
        val file = resolveVisibleFile(uriString)
            ?: throw IllegalArgumentException("Lokasi audio bukan salinan yang dikelola aplikasi.")
        if (file.isFile) {
            check(file.delete()) { "File belum terhapus; library tetap disimpan. Coba lagi." }
            check(!file.exists()) { "File belum terhapus; library tetap disimpan. Coba lagi." }
            scanPaths(listOf(file.absolutePath))
        }
    }

    fun renameVisible(uriString: String, requestedTitle: String): Pair<String, String> {
        requireStoragePermission()
        val source = resolveVisibleFile(uriString)
            ?: error("File tidak ditemukan. Mungkin dipindah atau dihapus di luar aplikasi.")
        require(source.isFile) { "File tidak ditemukan. Mungkin dipindah atau dihapus di luar aplikasi." }
        val oldName = source.name
        val targetName = LibraryRules.renamedDisplayName(oldName, requestedTitle)
        require(targetName != oldName) { "Nama belum berubah." }
        return renameVisibleExact(uriString, targetName)
    }

    fun renameVisibleExact(uriString: String, targetName: String): Pair<String, String> {
        requireStoragePermission()
        val (pub, _) = ensureDirectories()
        val source = resolveVisibleFile(uriString)
            ?: error("File tidak ditemukan. Mungkin dipindah atau dihapus di luar aplikasi.")
        require(source.isFile) { "File tidak ditemukan. Mungkin dipindah atau dihapus di luar aplikasi." }
        val target = File(pub, targetName)
        require(!target.exists() || target.canonicalFile == source.canonicalFile) {
            "Nama sudah dipakai file lain. Pilih nama berbeda."
        }
        check(source.renameTo(target)) { "HP tidak mengizinkan perubahan nama file." }
        scanPaths(listOf(source.absolutePath, target.absolutePath))
        return target.name to Uri.fromFile(target).toString()
    }

    fun restoreRename(currentUri: String, originalName: String): Pair<String, String>? {
        val (pub, _) = runCatching { ensureDirectories() }.getOrNull() ?: return null
        val current = resolveVisibleFile(currentUri) ?: return null
        if (!current.isFile) return null
        val target = File(pub, originalName)
        if (target.exists() && target.canonicalFile != current.canonicalFile) return null
        if (!current.renameTo(target)) return null
        scanPaths(listOf(current.absolutePath, target.absolutePath))
        return target.name to Uri.fromFile(target).toString()
    }

    fun uniqueAvailableDisplayName(requested: String): String {
        val (pub, _) = ensureDirectories()
        fun isFree(name: String): Boolean = !File(pub, name).exists()
        if (isFree(requested)) return requested
        val ext = requested.substringAfterLast('.', "")
        val base = if (ext.isNotEmpty()) requested.dropLast(ext.length + 1) else requested
        for (suffix in 2..999) {
            val trimmedBase = base.take(120)
            val candidate = LibraryRules.renamedDisplayName(requested, "$trimmedBase ($suffix)")
            if (isFree(candidate)) return candidate
        }
        return LibraryRules.renamedDisplayName(requested, "${base.take(80)}-${System.currentTimeMillis()}")
    }

    suspend fun writeFromStream(
        input: InputStream,
        displayName: String,
        expectedSha256: String? = null,
    ): Triple<File, String, Long> {
        val (pub, pend) = ensureDirectories()
        val tempName = "tmp-${java.util.UUID.randomUUID()}.part"
        val tempFile = File(pend, tempName)
        var copied = 0L
        val actualSha256: String
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileOutputStream(tempFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        copied += read
                    }
                }
                output.flush()
                output.fd.sync()
            }
            require(copied > 0) { "Sumber audio kosong; tidak ada file yang ditambahkan." }
            actualSha256 = shaHex(digest.digest())
            if (expectedSha256 != null) {
                require(actualSha256.equals(expectedSha256, ignoreCase = true)) { "Checksum audio tidak cocok." }
            }
            val finalName = uniqueAvailableDisplayName(displayName)
            val targetFile = File(pub, finalName)
            moveAtomically(tempFile, targetFile)
            scanPaths(listOf(targetFile.absolutePath))
            return Triple(targetFile, actualSha256, copied)
        } catch (t: Throwable) {
            runCatching { tempFile.delete() }
            throw t
        }
    }

    // --- PublicationStore helpers for crash-safe DownloadQueuePublication on API 26-28 ---

    fun createPending(name: String): String {
        require(MARKER_REGEX.matches(name))
        val (_, pend) = ensureDirectories()
        val file = File(pend, name)
        if (!file.exists()) check(file.createNewFile()) { "HP menolak pembuatan file audio." }
        return Uri.fromFile(file).toString()
    }

    fun setPendingTargetName(uriString: String, targetDisplayName: String) {
        val (_, pend) = ensureDirectories()
        val pendingFile = resolveInside(pend, uriString) ?: error("Lokasi pending tidak valid.")
        val safeTarget = uniqueAvailableDisplayName(targetDisplayName)
        File(pend, "${pendingFile.name}.target").writeText(safeTarget, Charsets.UTF_8)
    }

    fun writePendingFromFile(uriString: String, sourceFile: File): Long {
        val (_, pend) = ensureDirectories()
        val pendingFile = resolveInside(pend, uriString) ?: error("Lokasi pending tidak valid.")
        FileOutputStream(pendingFile).use { destination ->
            val copied = sourceFile.inputStream().use { it.copyTo(destination, 64 * 1024) }
            destination.flush()
            destination.fd.sync()
            check(copied == sourceFile.length())
            return copied
        }
    }

    fun locatePending(name: String): String? {
        require(MARKER_REGEX.matches(name))
        val (_, pend) = ensureDirectories()
        val pendingFile = File(pend, name)
        if (pendingFile.isFile) return Uri.fromFile(pendingFile).toString()
        val publishedSidecar = File(pend, "$name.published")
        if (publishedSidecar.isFile) {
            val visibleUri = publishedSidecar.readText(Charsets.UTF_8).trim()
            if (resolveVisibleFile(visibleUri)?.isFile == true) {
                return Uri.fromFile(pendingFile).toString()
            }
        }
        return null
    }

    fun inspect(uriString: String): DownloadQueuePublication.Media? {
        requireStoragePermission()
        val pend = pendingDir()
        val pendingFile = resolveInside(pend, uriString)
        if (pendingFile != null) {
            if (pendingFile.isFile) {
                return DownloadQueuePublication.Media(pending = true, bytes = pendingFile.length())
            }
            val publishedSidecar = File(pend, "${pendingFile.name}.published")
            if (publishedSidecar.isFile) {
                val visibleUri = publishedSidecar.readText(Charsets.UTF_8).trim()
                val visible = resolveVisibleFile(visibleUri)
                if (visible != null && visible.isFile) {
                    return DownloadQueuePublication.Media(pending = false, bytes = visible.length())
                }
            }
            return null
        }
        val visible = resolveVisibleFile(uriString) ?: return null
        return if (visible.isFile) DownloadQueuePublication.Media(pending = false, bytes = visible.length()) else null
    }

    fun discardPending(uriString: String) {
        val pend = pendingDir()
        val pendingFile = resolveInside(pend, uriString) ?: error("Bukan salinan pending aplikasi.")
        if (pendingFile.exists()) check(pendingFile.delete()) { "Gagal menghapus salinan sementara." }
        File(pend, "${pendingFile.name}.target").delete()
        File(pend, "${pendingFile.name}.published").delete()
    }

    fun makeVisible(uriString: String) {
        val (pub, pend) = ensureDirectories()
        val pendingFile = resolveInside(pend, uriString) ?: error("Salinan pending tidak ditemukan.")
        check(pendingFile.isFile) { "Salinan pending tidak ditemukan." }
        val targetSidecar = File(pend, "${pendingFile.name}.target")
        val desiredName = if (targetSidecar.isFile) {
            targetSidecar.readText(Charsets.UTF_8).trim().ifBlank { pendingFile.name }
        } else {
            pendingFile.name
        }
        val finalName = uniqueAvailableDisplayName(desiredName)
        val visibleFile = File(pub, finalName)
        moveAtomically(pendingFile, visibleFile)
        val visibleUri = Uri.fromFile(visibleFile).toString()
        File(pend, "${pendingFile.name}.published").writeText(visibleUri, Charsets.UTF_8)
        targetSidecar.delete()
        scanPaths(listOf(visibleFile.absolutePath))
    }

    fun resolveFinalUriAndClearMarker(uriString: String): String {
        val pend = pendingDir()
        val pendingFile = resolveInside(pend, uriString)
        if (pendingFile != null) {
            val publishedSidecar = File(pend, "${pendingFile.name}.published")
            if (publishedSidecar.isFile) {
                val visibleUri = publishedSidecar.readText(Charsets.UTF_8).trim()
                if (resolveVisibleFile(visibleUri)?.isFile == true) {
                    return visibleUri
                }
            }
        }
        require(ManagedCopyRules.validLegacyFileUri(uriString)) { "URI hasil publikasi tidak valid." }
        return uriString
    }

    fun clearPublishedMarker(uriString: String) {
        val pendingFile = resolveInside(pendingDir(), uriString) ?: return
        File(pendingDir(), "${pendingFile.name}.published").delete()
    }

    private fun moveAtomically(src: File, dst: File) {
        if (src.renameTo(dst)) return
        src.inputStream().use { input ->
            FileOutputStream(dst).use { out ->
                input.copyTo(out, 64 * 1024)
                out.flush()
                out.fd.sync()
            }
        }
        check(dst.isFile && dst.length() == src.length()) { "Gagal memindahkan audio ke folder Music." }
        src.delete()
    }

    private fun scanPaths(paths: List<String>) {
        if (paths.isEmpty()) return
        runCatching {
            MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
        }
    }

    private fun shaHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            append("0123456789abcdef"[v ushr 4])
            append("0123456789abcdef"[v and 0x0F])
        }
    }

    companion object {
        private val MARKER_REGEX = Regex("swararia-[0-9a-f-]{36}\\.[a-z0-9]+")
    }
}
