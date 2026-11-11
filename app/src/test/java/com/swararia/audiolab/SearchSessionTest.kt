package com.swararia.audiolab

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SearchSessionTest {
    private fun hit(id: String = "abcdefghijk") = SearchHit(id,"Example","Real channel",120,null,"https://www.youtube.com/watch?v=$id")
    private data class Request(val query:String,val provider:SearchProvider,val kind:SearchKind,val offset:Int,val playlist:String?)
    private class Gateway : AudioSearchRepository {
        val calls=mutableListOf<Request>()
        var page: suspend (Request)->SearchPage = { SearchPage(emptyList(),0,0) }
        var stream: suspend (SearchHit)->PreviewMedia = { error("not configured") }
        override suspend fun searchPage(query:String,provider:SearchProvider,kind:SearchKind,offset:Int,playlistId:String?):SearchPage {
            val request=Request(query,provider,kind,offset,playlistId);calls.add(request);return page(request)
        }
        override suspend fun preview(hit:SearchHit)=stream(hit)
    }
    private class Fixture : AutoCloseable {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        val gateway=Gateway()
        var ingest=false
        val messages=mutableListOf<String>()
        val session=SearchSession(gateway,scope,{ingest},{messages.add(it)})
        override fun close() { session.close();scope.cancel() }
    }
    @Test(timeout = 5000) fun retryPreservesPlaylistWhenItsFirstPageFails() = runBlocking {
        Fixture().use { f ->
            f.gateway.page={ error("offline") }
            f.session.search("music",SearchProvider.YOUTUBE_MUSIC,SearchKind.PLAYLISTS)
            val playlist=hit().copy(videoId="PLabcdefghijkl",title="Collection",kind=SearchKind.PLAYLISTS)
            f.session.openPlaylist(playlist)
            f.session.retrySearch()
            assertEquals(3,f.gateway.calls.size)
            assertEquals(f.gateway.calls[1],f.gateway.calls[2])
            assertEquals(playlist.videoId,f.gateway.calls.last().playlist)
            assertEquals(0,f.gateway.calls.last().offset)
        }
    }
    @Test(timeout = 5000) fun failedNextPageRetriesOffsetEvenIfPreviousResultsWereAllIncomplete() = runBlocking {
        Fixture().use { f ->
            f.gateway.page={ if(it.offset==0) SearchPage(emptyList(),10,10) else error("offline") }
            f.session.search("music",SearchProvider.YOUTUBE)
            assertTrue(f.session.search.value.hasMore)
            f.session.loadMore();f.session.retrySearch()
            assertEquals(listOf(0,10,10),f.gateway.calls.map { it.offset })
            assertEquals(10,f.session.search.value.loadedThrough)
            assertEquals(10,f.session.search.value.incompleteCount)
        }
    }
    @Test(timeout = 5000) fun pagingKeepsResultsDeduplicatedAndStopsAtLimit() = runBlocking {
        Fixture().use { f ->
            f.gateway.page={ SearchPage(listOf(hit()),10,0) }
            f.session.search("music",SearchProvider.YOUTUBE)
            repeat(8) { f.session.loadMore() }
            assertEquals(listOf(0,10,20,30,40),f.gateway.calls.map { it.offset })
            assertEquals(listOf(hit()),f.session.search.value.results)
            assertFalse(f.session.search.value.hasMore)
        }
    }
    @Test(timeout = 5000) fun cancellationBlocksDispatchUntilWorkerCleanupFinishes() = runBlocking {
        Fixture().use { f ->
            val started=CompletableDeferred<Unit>();val cleanup=CompletableDeferred<Unit>()
            f.gateway.page={ try { started.complete(Unit);awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } } }
            f.session.search("first",SearchProvider.YOUTUBE);started.await()
            f.session.cancelSearch();f.session.cancelSearch()
            assertTrue(f.session.search.value.busy)
            assertFalse(f.session.canStartOnlineOperation())
            f.session.search("blocked",SearchProvider.YOUTUBE)
            assertEquals(1,f.gateway.calls.size)
            cleanup.complete(Unit);yield()
            assertFalse(f.session.search.value.busy)
            f.gateway.page={ SearchPage(listOf(hit()),1,0) }
            f.session.search("second",SearchProvider.YOUTUBE)
            assertEquals("second",f.session.search.value.query)
            assertEquals(listOf(hit()),f.session.search.value.results)
        }
    }
    @Test(timeout = 5000) fun cancelledWorkerCannotPublishLateSuccess() = runBlocking {
        Fixture().use { f ->
            val done=CompletableDeferred<Unit>()
            f.gateway.page={ withContext(NonCancellable) { done.await() };SearchPage(listOf(hit()),1,0) }
            f.session.search("first",SearchProvider.YOUTUBE);f.session.cancelSearch()
            done.complete(Unit);yield()
            assertTrue(f.session.search.value.results.isEmpty())
            assertFalse(f.session.search.value.busy)
        }
    }
    @Test(timeout = 5000) fun ingestAndPreviewLookupExcludeOtherOnlineDispatch() = runBlocking {
        Fixture().use { f ->
            f.ingest=true;f.session.search("blocked",SearchProvider.YOUTUBE);assertTrue(f.gateway.calls.isEmpty())
            f.ingest=false
            val ready=CompletableDeferred<PreviewMedia>()
            f.gateway.stream={ ready.await() }
            f.session.preview(hit());assertTrue(f.session.preview.value.loading)
            f.session.search("blocked",SearchProvider.YOUTUBE);assertTrue(f.gateway.calls.isEmpty())
            f.session.cancelPreview();yield();assertFalse(f.session.preview.value.loading)
            f.session.search("allowed",SearchProvider.YOUTUBE);assertEquals(1,f.gateway.calls.size)
        }
    }
    @Test(timeout = 5000) fun previewCancellationKeepsBusyUntilCleanupAndDropsLateMedia() = runBlocking {
        Fixture().use { f ->
            val cleanup=CompletableDeferred<Unit>()
            f.gateway.stream={ withContext(NonCancellable) { cleanup.await() };PreviewMedia(it.videoId,it.title,"https://example.invalid/fixture",emptyMap()) }
            f.session.preview(hit());f.session.cancelPreview();f.session.cancelPreview()
            assertTrue(f.session.preview.value.loading)
            assertFalse(f.session.canStartOnlineOperation())
            cleanup.complete(Unit);yield()
            assertNull(f.session.preview.value.media)
            assertFalse(f.session.preview.value.loading)
        }
    }
    @Test(timeout = 5000) fun previewFailureBecomesRecoverableStateNotUncaughtFailure() = runBlocking {
        Fixture().use { f ->
            f.gateway.stream={ error("unavailable") };f.session.preview(hit())
            assertFalse(f.session.preview.value.loading)
            assertEquals("unavailable",f.session.preview.value.error)
            f.session.cancelPreview();assertEquals(PreviewState(),f.session.preview.value)
        }
    }
    @Test(timeout = 5000) fun invalidQueryDoesNotDispatchAndScopeOwnershipIsPreserved() = runBlocking {
        Fixture().use { f ->
            f.session.search(" ",SearchProvider.YOUTUBE);assertTrue(f.gateway.calls.isEmpty());assertTrue(f.messages.isNotEmpty())
            val sibling=f.scope.launch { awaitCancellation() }
            f.session.close();assertTrue(sibling.isActive)
        }
    }

    @Test(timeout = 5000) fun searchPublishesResultsAndRetainsMusicDefault() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val gateway=Gateway().apply { page={ SearchPage(listOf(hit()),1,0) } }
            val session=SearchSession(gateway,scope,{false},{})
            assertEquals(SearchProvider.YOUTUBE_MUSIC,session.search.value.provider)
            session.search("Example",SearchProvider.YOUTUBE_MUSIC)
            assertEquals(listOf(hit()),session.search.value.results)
            assertFalse(session.search.value.busy)
            assertFalse(session.search.value.hasMore)
            session.close()
        } finally { scope.cancel() }
    }
}
