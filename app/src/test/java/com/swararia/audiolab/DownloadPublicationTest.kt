package com.swararia.audiolab

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Fake physical/DB port, REAL production journal coordinator. Not MediaStore runtime evidence. */
class DownloadPublicationTest {
    private class Store : DownloadQueuePublication.Store {
        var job = DownloadJob(1, "b", "https://www.youtube.com/watch?v=abcdefgh_12", "Audio", null, "RUNNING", null, "", null, null, true, 0)
        var media: DownloadQueuePublication.Media? = null
        var insertions = 0
        var tracks = 0
        var failAt = ""
        fun boundary(name: String) { if (failAt == name) error(name) }
        override suspend fun begin(id: Long, name: String) { job = job.copy(publicationName = name); boundary("begin") }
        override suspend fun create(name: String): String { insertions++; media = DownloadQueuePublication.Media(true, 0); boundary("create"); return "uri" }
        override suspend fun associate(id: Long, uri: String) { job = job.copy(pendingUri = uri); boundary("associate") }
        override suspend fun ready(id: Long, bytes: Long) { job = job.copy(publicationBytes = bytes); boundary("ready") }
        override suspend fun locate(name: String): String? = if (media == null) null else "uri"
        override suspend fun inspect(uri: String) = media
        override suspend fun discardPending(uri: String) { check(media!!.pending); media = null }
        override suspend fun abandon(id: Long) { job = job.copy(publicationName = null, pendingUri = null, publicationBytes = null, status = "FAILED") }
        override suspend fun makeVisible(uri: String) { media = media!!.copy(pending = false); boundary("visible") }
        override suspend fun finish(id: Long, uri: String, bytes: Long): Long {
            boundary("finish") // failing transaction commits neither track nor completion
            if (tracks == 0) tracks++
            job = job.copy(status = "COMPLETED", trackId = 42, publicationName = null, pendingUri = null, publicationBytes = null)
            return 42
        }
    }
    @Test fun staleIndexedZeroDoesNotBlockCompletedPhysicalCopyOrReadyRecovery() = runBlocking {
        // Pure coordinator fixture: indexed metadata is deliberately independent of physical bytes.
        // The Python adapter source contract + real Android API compile cover the adapter wiring;
        // this is NOT ParcelFileDescriptor/MediaProvider execution on a device.
        for (recoverReady in listOf(false, true)) {
            val store = Store()
            val indexedSize = 0L
            val physicalSize = 123L
            val adapter = object : DownloadQueuePublication.Store by store {
                override suspend fun inspect(uri: String) = store.media?.copy(bytes = physicalSize)
                override suspend fun makeVisible(uri: String) {
                    assertEquals(physicalSize, store.job.publicationBytes)
                    assertTrue(store.media!!.pending)
                    assertEquals(0L, indexedSize)
                    store.makeVisible(uri)
                }
            }
            val publisher = DownloadQueuePublication(adapter)
            if (recoverReady) {
                store.job = store.job.copy(publicationName = "unique.opus", pendingUri = "uri", publicationBytes = physicalSize)
                store.media = DownloadQueuePublication.Media(true, indexedSize)
                publisher.recover(store.job)
            } else {
                assertEquals(42L, publisher.publish(1, "unique.opus") {
                    store.media = DownloadQueuePublication.Media(true, indexedSize)
                    physicalSize
                })
            }
            assertEquals("COMPLETED", store.job.status)
            assertEquals(1, store.tracks)
            assertFalse(store.media!!.pending)
            assertEquals(indexedSize, store.media!!.bytes) // No scan/update was needed to finish.
        }
    }

    @Test fun crashesBeforeReadyDiscardOnlyHiddenPartialCopies() = runBlocking {
        for (boundary in listOf("begin", "create", "associate", "copy")) {
            val store = Store().apply { failAt = boundary }
            val publisher = DownloadQueuePublication(store)
            try { publisher.publish(1, "unique.opus") { store.media = DownloadQueuePublication.Media(true, 123); store.boundary("copy"); 123 } } catch (_: Exception) { }
            store.failAt = ""
            publisher.recover(store.job)
            assertNull(store.media)
            assertEquals("FAILED", store.job.status)
            assertEquals(0, store.tracks)
        }
    }
    @Test fun readyAndVisibleCrashesReconcileWithoutRedownloadOrDeletion() = runBlocking {
        for (boundary in listOf("ready", "visible", "finish")) {
            val store = Store().apply { failAt = boundary }
            val publisher = DownloadQueuePublication(store)
            try { publisher.publish(1, "unique.opus") { store.media = DownloadQueuePublication.Media(true, 123); 123 } } catch (_: Exception) { }
            assertEquals("RUNNING", store.job.status)
            store.failAt = ""
            publisher.recover(store.job)
            assertEquals("COMPLETED", store.job.status)
            assertEquals(1, store.tracks)
            assertEquals(1, store.insertions)
            assertEquals(DownloadQueuePublication.Media(false, 123), store.media)
        }
    }
    @Test fun uncertainVisibleOrMissingReadyCopyNeverBecomesRetryable() = runBlocking {
        for (media in listOf(null, DownloadQueuePublication.Media(false, 123))) {
            val store = Store().apply { job = job.copy(publicationName = "unique.opus", pendingUri = "uri", publicationBytes = if (media == null) 123 else null); this.media = media }
            try { DownloadQueuePublication(store).recover(store.job); fail("Uncertainty must block retry") } catch (_: IllegalStateException) { }
            assertEquals("RUNNING", store.job.status)
            assertNotNull(store.job.publicationName)
            assertEquals(media, store.media)
        }
    }
}
