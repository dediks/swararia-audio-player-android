package com.swararia.audiolab

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PreviewSessionTest {
    private fun hit(id: String = "abcdefghijk") = SearchHit(id, "Title", null, 200, null, "https://www.youtube.com/watch?v=$id")
    @Test fun resolutionDispatchesWithoutUiSubscribersAndStaleCompletionCannotStart() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val repository = object : AudioSearchRepository {
            override suspend fun searchPage(query: String, provider: SearchProvider, kind: SearchKind, offset: Int, playlistId: String?) = SearchPage(emptyList(), 0, 0)
            override suspend fun preview(hit: SearchHit): PreviewMedia {
                withContext(NonCancellable) { pending.await() }
                return PreviewMedia(hit.videoId, hit.title, "https://rr.googlevideo.com/videoplayback?expire=9999999999", emptyMap())
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = PreviewSession(repository, scope, {}, onResolved = { started.add(it.videoId) })
        owner.preview(hit()); owner.cancelPreview(); pending.complete(Unit); yield()
        assertTrue(started.isEmpty())
        owner.preview(hit("bcdefghijkl")); yield()
        assertEquals(listOf("bcdefghijkl"), started)
        owner.close(); scope.cancel()
    }
    @Test fun resolutionFailureIsSanitizedInvalidatesCacheAndKeepsRetryIdentity() = runBlocking {
        var attempts = 0
        val invalidated = mutableListOf<String>()
        val started = mutableListOf<String>()
        val repository = object : AudioSearchRepository {
            override suspend fun searchPage(query: String, provider: SearchProvider, kind: SearchKind, offset: Int, playlistId: String?) = SearchPage(emptyList(), 0, 0)
            override suspend fun preview(hit: SearchHit): PreviewMedia {
                if (++attempts == 1) error("native diagnostic must not escape")
                return PreviewMedia(hit.videoId, hit.title, "https://rr.googlevideo.com/videoplayback", emptyMap())
            }
            override fun invalidatePreview(videoId: String) { invalidated.add(videoId) }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = PreviewSession(repository, scope, {}, onResolved = { started.add(it.videoId) })
        owner.preview(hit()); yield()
        assertEquals(hit(), owner.preview.value.hit)
        assertFalse(owner.preview.value.loading)
        assertNull(owner.preview.value.media)
        assertEquals("Preview tidak tersedia dari sumber ini.", owner.preview.value.error)
        assertEquals(listOf(hit().videoId), invalidated)
        owner.preview(hit()); yield()
        assertEquals(listOf(hit().videoId), started)
        assertNull(owner.preview.value.error)
        owner.close(); scope.cancel()
    }
    @Test fun closingOwnerRejectsLateNonCancellableResolution() = runBlocking {
        val pending = CompletableDeferred<Unit>()
        var starts = 0
        val repository = object : AudioSearchRepository {
            override suspend fun searchPage(query: String, provider: SearchProvider, kind: SearchKind, offset: Int, playlistId: String?) = SearchPage(emptyList(), 0, 0)
            override suspend fun preview(hit: SearchHit): PreviewMedia {
                withContext(NonCancellable) { pending.await() }
                return PreviewMedia(hit.videoId, hit.title, "https://rr.googlevideo.com/videoplayback", emptyMap())
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val owner = PreviewSession(repository, scope, {}, onResolved = { starts++ })
        owner.preview(hit()); owner.close(); pending.complete(Unit); yield()
        assertEquals(0, starts)
        scope.cancel()
    }
    @Test fun localContextDeletionPreservesSurvivingIdentityAndNeverRestoresRemovedId() {
        val original = PreviewLocalQueue(listOf("1", "2", "3"), "2", 1, 4200L) { it }
        val trimmed = original.forgetDeleted("1")
        assertEquals(listOf("2", "3"), trimmed.items)
        assertEquals(0, trimmed.restoreIndex)
        assertEquals(4200L, trimmed.restorePosition)
        val currentDeleted = trimmed.forgetDeleted("2")
        assertEquals(listOf("3"), currentDeleted.items)
        assertEquals(0L, currentDeleted.restorePosition)
        assertTrue(currentDeleted.forgetDeleted("3").items.isEmpty())
    }
    @Test fun leaseRejectsStaleStopsAndReplayAndValidatesSignedSource() {
        val lease = PreviewLease()
        assertFalse(lease.begin("bad", 1))
        val token = "11111111-1111-1111-1111-111111111111"
        assertTrue(lease.begin(token, 10))
        assertFalse(lease.begin(token, 10))
        assertFalse(lease.matches(token, 9))
        assertTrue(lease.matches(token, 10))
        assertFalse(lease.matches("22222222-2222-2222-2222-222222222222", 10))
        assertTrue(lease.begin(token, 11))
        assertFalse(lease.matches(token, 10))
        val good = PreviewMedia("abcdefghijk", "Title", "https://rr.googlevideo.com/videoplayback?sig=secret", mapOf("User-Agent" to "test"))
        assertTrue(runCatching { PreviewLease.validate(good) }.isSuccess)
        for (bad in listOf(good.copy(url="https://example.com/videoplayback"), good.copy(headers=mapOf("Authorization" to "secret")), good.copy(headers=mapOf("User-Agent" to "bad\r\nvalue")))) {
            assertTrue(runCatching { PreviewLease.validate(bad) }.isFailure)
        }
    }
}
