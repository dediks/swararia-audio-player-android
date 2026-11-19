package com.swararia.audiolab.remote

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

        val capsWithMedia = DesktopRemoteJson.capabilitiesInfo(
            DesktopRemoteJson.objectValue("{\"protocol\":\"v1\",\"commands\":[\"play\",\"pause\",\"stop\"],\"media\":true,\"media_max_bytes\":157286400}".toByteArray()),
        )
        assertTrue(capsWithMedia.media)
        assertEquals(157286400L, capsWithMedia.mediaMaxBytes)

        assertThrows(DesktopRemoteFailure::class.java) {
            DesktopRemoteJson.capabilitiesInfo(DesktopRemoteJson.objectValue("{\"protocol\":\"v1\",\"commands\":[\"play\"],\"media\":\"yes\"}".toByteArray()))
        }
        assertThrows(DesktopRemoteFailure::class.java) {
            DesktopRemoteJson.capabilitiesInfo(DesktopRemoteJson.objectValue("{\"protocol\":\"v1\",\"commands\":[\"play\"],\"media_max_bytes\":-1}".toByteArray()))
        }
        assertEquals("media_disabled", DesktopRemoteJson.safeErrorCode("{\"code\":\"media_disabled\"}".toByteArray()))
        assertNull(DesktopRemoteJson.safeErrorCode("{\"code\":\"bad spaces!\"}".toByteArray()))
        assertTrue(DesktopRemoteJson.isValidSha256Hex("a".repeat(64)))
        assertFalse(DesktopRemoteJson.isValidSha256Hex("z".repeat(64)))
    }

    @Test
    fun sanitizePairLabelAndPairBodyHandleControlCharsLengthAndBlank() {
        assertEquals("SWR Player Android", DesktopRemoteJson.sanitizePairLabel(" \t\r\n "))
        assertEquals("Samsung SM-M156B", DesktopRemoteJson.sanitizePairLabel("  Samsung\u0007 SM-M156B\n "))
        assertEquals(80, DesktopRemoteJson.sanitizePairLabel("A".repeat(120)).length)

        val body = DesktopRemoteJson.pairBody("FOH \"Main\"\\Lab\u0001\n")
        val json = DesktopRemoteJson.objectValue(body)
        assertEquals("FOH \"Main\"\\Lab", json.get("label")?.asText())
    }

    @Test
    fun formatDeviceClientLabelNormalizesManufacturerAndModel() {
        assertEquals("SWR Player Android", formatDeviceClientLabel(null, "  "))
        assertEquals("Samsung", formatDeviceClientLabel("samsung", ""))
        assertEquals("Pixel 8", formatDeviceClientLabel(null, "Pixel 8"))
        assertEquals("Samsung SM-M156B", formatDeviceClientLabel("samsung", "Samsung SM-M156B"))
        assertEquals("Samsung SM-M156B", formatDeviceClientLabel("samsung", "SM-M156B"))
        assertEquals("Xiaomi 2312DRA50G", formatDeviceClientLabel("XIAOMI", "2312DRA50G"))
    }

    @Test
    fun capabilitiesSyncFoldersManifestAndUploadOutcomeValidateStrictly() {
        val caps = DesktopRemoteJson.capabilitiesInfo(
            DesktopRemoteJson.objectValue(
                "{\"protocol\":\"v1\",\"commands\":[\"play\"],\"media\":true,\"sync\":true,\"upload\":true,\"media_max_bytes\":157286400}".toByteArray(),
            ),
        )
        assertTrue(caps.sync)
        assertTrue(caps.upload)

        val foldersRes = DesktopRemoteJson.syncFolders(
            DesktopRemoteJson.objectValue(
                """{"protocol":"v1","folders":[{"folder_id":"fld_1234567890abcdef","rel_folder":"Show/Malam","name":"Show/Malam","track_count":2,"total_bytes":4096,"excluded_wav_flac_count":1,"oversized_count":0}],"excluded_wav_flac_count":1,"oversized_count":0,"warnings":["1 file .wav/.flac dilewati."]}""".toByteArray(),
            ),
        )
        assertEquals(1, foldersRes.folders.size)
        assertEquals("fld_1234567890abcdef", foldersRes.folders.first().folderId)
        assertEquals(1, foldersRes.excludedWavFlacCount)

        val validSha = "a".repeat(64)
        val manifest = DesktopRemoteJson.syncManifest(
            DesktopRemoteJson.objectValue(
                """{"protocol":"v1","folder_id":"fld_1234567890abcdef","rel_folder":"Show/Malam","folder_name":"Show/Malam","total":1,"offset":0,"next_offset":null,"excluded_wav_flac_count":1,"oversized_count":0,"warnings":[],"files":[{"file_id":"fil_0123456789abcdef0123","name":"Intro.mp3","rel_path":"Show/Malam/Intro.mp3","ext":".mp3","size_bytes":1024,"sha256":"$validSha"}]}""".toByteArray(),
            ),
        )
        assertEquals(1, manifest.files.size)
        assertEquals("mp3", manifest.files.first().ext)

        // Reject traversal in rel_path
        assertThrows(DesktopRemoteFailure::class.java) {
            DesktopRemoteJson.syncManifest(
                DesktopRemoteJson.objectValue(
                    """{"protocol":"v1","folder_id":"fld_1234567890abcdef","rel_folder":"Show","folder_name":"Show","total":1,"offset":0,"next_offset":null,"files":[{"file_id":"fil_0123456789abcdef0123","name":"Intro.mp3","rel_path":"../Intro.mp3","ext":".mp3","size_bytes":1024,"sha256":"$validSha"}]}""".toByteArray(),
                ),
            )
        }

        val upload = DesktopRemoteJson.uploadOutcome(
            DesktopRemoteJson.objectValue(
                """{"status":"saved","deduplicated":false,"filename":"Track (2).mp3","rel_path":"Set1/Track (2).mp3","sha256":"$validSha","subfolder":"Set1","renamed":true}""".toByteArray(),
            ),
        )
        assertFalse(upload.deduplicated)
        assertTrue(upload.renamed)
        assertEquals("Set1", upload.subfolder)
    }
}
