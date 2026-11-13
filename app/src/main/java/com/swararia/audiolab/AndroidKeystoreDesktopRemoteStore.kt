package com.swararia.audiolab

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Hardware/OS Keystore-backed encrypted credential store for Desktop Remote sessions.
 * Stored in noBackupFilesDir so cloud/ADB backups never capture bearer tokens or TLS pins.
 */
class AndroidKeystoreDesktopRemoteStore(context: Context) : DesktopRemoteSessionStore {
    private val appContext = context.applicationContext
    private val lock = Any()
    private val storeFile = File(appContext.noBackupFilesDir, FILE_NAME)
    private val tempFile = File(appContext.noBackupFilesDir, "$FILE_NAME.tmp")

    override fun load(): PersistedDesktopRemoteSession? = synchronized(lock) {
        if (!storeFile.isFile) return null
        val len = storeFile.length()
        if (len !in MIN_RECORD_BYTES..MAX_RECORD_BYTES) {
            clearUnlocked()
            return null
        }
        val raw = runCatching { storeFile.readBytes() }.getOrNull() ?: return null
        if (raw.size < MIN_RECORD_BYTES || raw[0] != FORMAT_VERSION) {
            clearUnlocked()
            return null
        }
        val ivLen = raw[1].toInt() and 0xFF
        if (ivLen !in 12..16 || raw.size <= HEADER_BYTES + ivLen) {
            clearUnlocked()
            return null
        }
        val iv = raw.copyOfRange(HEADER_BYTES, HEADER_BYTES + ivLen)
        val ciphertext = raw.copyOfRange(HEADER_BYTES + ivLen, raw.size)
        var plaintext: ByteArray? = null
        return try {
            val key = existingKey() ?: run {
                clearUnlocked()
                return null
            }
            val cipher = Cipher.getInstance(CIPHER_TRANSFORM).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                updateAAD(AAD_V1)
            }
            plaintext = cipher.doFinal(ciphertext)
            DesktopRemoteJson.decodePersistedSession(plaintext)
        } catch (_: Exception) {
            clearUnlocked()
            null
        } finally {
            plaintext?.fill(0)
        }
    }

    override fun save(session: PersistedDesktopRemoteSession) {
        synchronized(lock) {
            var plaintext: ByteArray? = null
            try {
                appContext.noBackupFilesDir.mkdirs()
                val key = getOrCreateKey()
                plaintext = DesktopRemoteJson.encodePersistedSession(session)
                val cipher = Cipher.getInstance(CIPHER_TRANSFORM).apply {
                    init(Cipher.ENCRYPT_MODE, key)
                    updateAAD(AAD_V1)
                }
                val iv = cipher.iv
                if (iv == null || iv.size !in 12..16) error("Invalid AES-GCM IV")
                val ciphertext = cipher.doFinal(plaintext)
                val framed = ByteArrayOutputStream(HEADER_BYTES + iv.size + ciphertext.size).apply {
                    write(FORMAT_VERSION.toInt())
                    write(iv.size)
                    write(iv)
                    write(ciphertext)
                }.toByteArray()
                tempFile.writeBytes(framed)
                runCatching {
                    Files.move(
                        tempFile.toPath(),
                        storeFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                }.onFailure {
                    Files.move(
                        tempFile.toPath(),
                        storeFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            } catch (_: Exception) {
                clearUnlocked()
            } finally {
                plaintext?.fill(0)
                runCatching { if (tempFile.exists()) tempFile.delete() }
            }
        }
    }

    override fun clear() {
        synchronized(lock) { clearUnlocked() }
    }

    private fun clearUnlocked() {
        runCatching { if (storeFile.exists()) storeFile.delete() }
        runCatching { if (tempFile.exists()) tempFile.delete() }
    }

    private fun loadKeyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun existingKey(): SecretKey? = runCatching {
        loadKeyStore().getKey(KEY_ALIAS, null) as? SecretKey
    }.getOrNull()

    private fun getOrCreateKey(): SecretKey {
        val ks = loadKeyStore()
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        runCatching { if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS) }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "swararia_desktop_remote_session_v1"
        const val FILE_NAME = "desktop-remote-session.v1.enc"
        const val CIPHER_TRANSFORM = "AES/GCM/NoPadding"
        const val FORMAT_VERSION: Byte = 1
        const val HEADER_BYTES = 2
        const val GCM_TAG_BITS = 128
        const val MIN_RECORD_BYTES = 32L
        const val MAX_RECORD_BYTES = 8192L
        val AAD_V1 = byteArrayOf(FORMAT_VERSION)
    }
}

