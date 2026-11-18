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
        var quality: suspend (String)->AudioStreamQuality? = { null }
        override suspend fun searchPage(query:String,provider:SearchProvider,kind:SearchKind,offset:Int,playlistId:String?):SearchPage {
            val request=Request(query,provider,kind,offset,playlistId);calls.add(request);return page(request)
        }
        override suspend fun preview(hit:SearchHit)=stream(hit)
        override suspend fun audioQuality(videoId: String)=quality(videoId)
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
            f.gateway.stream={ withContext(NonCancellable) { cleanup.await() };PreviewMedia(it.videoId,it.title,"https://rr.googlevideo.com/videoplayback?fixture=fixture",emptyMap()) }
            f.session.preview(hit());f.session.cancelPreview();f.session.cancelPreview()
            assertTrue(f.session.preview.value.loading)
            assertTrue(f.session.preview.value.canceling)
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
            assertEquals("Preview tidak tersedia dari sumber ini.",f.session.preview.value.error)
            f.session.cancelPreview();assertEquals(PreviewState(),f.session.preview.value)
        }
    }
    @Test(timeout = 5000) fun playerFailureRetainsHitAndRetryResolvesItAgain() = runBlocking {
        Fixture().use { f ->
            val calls=mutableListOf<SearchHit>()
            f.gateway.stream={ calls.add(it);PreviewMedia(it.videoId,it.title,"https://rr.googlevideo.com/videoplayback?fixture=${calls.size}",emptyMap()) }
            f.session.preview(hit())
            val old=f.session.preview.value.media
            f.session.previewError()
            assertEquals(hit(),f.session.preview.value.hit)
            assertNotNull(f.session.preview.value.error)
            assertNull(f.session.preview.value.media)
            assertFalse(f.session.preview.value.loading)
            f.session.preview(f.session.preview.value.hit!!)
            assertEquals(listOf(hit(),hit()),calls)
            assertNotEquals(old,f.session.preview.value.media)
            assertNull(f.session.preview.value.error)
        }
    }

    @Test(timeout = 5000) fun playerFailureWaitsForCleanupAndRejectsLateResolvedMedia() = runBlocking {
        Fixture().use { f ->
            val cleanup=CompletableDeferred<Unit>()
            val first=hit()
            f.gateway.stream={ withContext(NonCancellable) { cleanup.await() };PreviewMedia(it.videoId,it.title,"https://rr.googlevideo.com/videoplayback?fixture=late",emptyMap()) }
            f.session.preview(first);f.session.previewError()
            assertEquals(first,f.session.preview.value.hit)
            assertNotNull(f.session.preview.value.error)
            assertTrue(f.session.preview.value.canceling)
            assertFalse(f.session.canStartOnlineOperation())
            f.session.preview(hit("bcdefghijkl"))
            assertEquals(first,f.session.preview.value.hit)
            cleanup.complete(Unit);yield()
            assertEquals(first,f.session.preview.value.hit)
            assertNotNull(f.session.preview.value.error)
            assertNull(f.session.preview.value.media)
            assertTrue(f.session.canStartOnlineOperation())
            val next=hit("bcdefghijkl")
            f.gateway.stream={ PreviewMedia(it.videoId,it.title,"https://rr.googlevideo.com/videoplayback?fixture=new",emptyMap()) }
            f.session.preview(next);yield()
            assertEquals(next,f.session.preview.value.hit)
            assertEquals(next.videoId,f.session.preview.value.media?.videoId)
            assertNull(f.session.preview.value.error)
        }
    }

    @Test(timeout = 5000) fun cancelAfterPlayerFailureCannotRestoreFailureOverNewRequest() = runBlocking {
        Fixture().use { f ->
            val cleanup=CompletableDeferred<Unit>()
            f.gateway.stream={ withContext(NonCancellable) { cleanup.await() };error("late failure") }
            f.session.preview(hit());f.session.previewError();f.session.cancelPreview()
            cleanup.complete(Unit);yield()
            assertEquals(PreviewState(),f.session.preview.value)
            f.gateway.stream={ PreviewMedia(it.videoId,it.title,"https://rr.googlevideo.com/videoplayback?fixture=new",emptyMap()) }
            val next=hit("bcdefghijkl");f.session.preview(next);yield()
            assertEquals(next,f.session.preview.value.hit)
            assertNull(f.session.preview.value.error)
        }
    }

    @Test(timeout = 5000) fun invalidQueryDoesNotDispatchAndScopeOwnershipIsPreserved() = runBlocking {
        Fixture().use { f ->
            f.session.search(" ",SearchProvider.YOUTUBE);assertTrue(f.gateway.calls.isEmpty());assertTrue(f.messages.isNotEmpty())
            val sibling=f.scope.launch { awaitCancellation() }
            f.session.close();assertTrue(sibling.isActive)
        }
    }

    @Test(timeout = 5000) fun leavingLoadingPlaylistWaitsForNativeCleanupBeforeRestoringParent() = runBlocking {
        Fixture().use { f ->
            val playlist=hit().copy(videoId="PLabcdefghijkl",kind=SearchKind.PLAYLISTS)
            val cleanup=CompletableDeferred<Unit>()
            f.gateway.page={ if(it.playlist==null) SearchPage(listOf(playlist),1,0) else {
                withContext(NonCancellable) { cleanup.await() };SearchPage(listOf(hit()),1,0)
            } }
            f.session.search("music",SearchProvider.YOUTUBE_MUSIC,SearchKind.PLAYLISTS,SearchSort.TITLE)
            val parent=f.session.search.value
            f.session.openPlaylist(playlist);f.session.leavePlaylist()
            assertTrue(f.session.search.value.busy)
            assertFalse(f.session.canStartOnlineOperation())
            cleanup.complete(Unit);yield()
            assertEquals(parent,f.session.search.value)
            assertTrue(f.session.canStartOnlineOperation())
            assertEquals(2,f.gateway.calls.size)
        }
    }

    @Test(timeout = 5000) fun leavingPlaylistRestoresLoadedParentAndSortWithoutAnotherRequest() = runBlocking {
        Fixture().use { f ->
            val playlist=hit().copy(videoId="PLabcdefghijkl",title="Collection",kind=SearchKind.PLAYLISTS)
            f.gateway.page={ SearchPage(if(it.playlist==null)listOf(playlist) else listOf(hit()),1,0) }
            f.session.search("music",SearchProvider.YOUTUBE_MUSIC,SearchKind.PLAYLISTS,SearchSort.TITLE)
            val parent=f.session.search.value
            f.session.openPlaylist(playlist);f.session.leavePlaylist()
            assertEquals(parent,f.session.search.value)
            assertEquals(2,f.gateway.calls.size)
        }
    }

    @Test(timeout = 5000) fun switchSearchCachesTabsAndSupersedesInFlightSearchAfterCleanup() = runBlocking {
        Fixture().use { f ->
            val song = hit("aaaaaaaaaaa")
            val playlist = hit("PLabcdefghijkl").copy(kind = SearchKind.PLAYLISTS, title = "Mix")
            val cleanup = CompletableDeferred<Unit>()
            var slowFirst = true
            f.gateway.page = { req ->
                if (slowFirst && req.kind == SearchKind.SONGS) {
                    try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
                } else if (req.kind == SearchKind.PLAYLISTS) {
                    SearchPage(listOf(playlist), 1, 0)
                } else {
                    SearchPage(listOf(song), 1, 0)
                }
            }
            f.session.search("jazz", SearchProvider.YOUTUBE_MUSIC, SearchKind.SONGS)
            assertTrue(f.session.search.value.busy)

            // Switch immediately to Playlist while Lagu search is still pending cleanup.
            f.session.switchSearch("jazz", SearchProvider.YOUTUBE_MUSIC, SearchKind.PLAYLISTS)
            assertEquals(SearchKind.PLAYLISTS, f.session.search.value.kind)
            assertTrue(f.session.search.value.busy)
            assertEquals(1, f.gateway.calls.size) // Playlist waits for Lagu cleanup before native dispatch.

            cleanup.complete(Unit); yield()
            assertFalse(f.session.search.value.busy)
            assertEquals(listOf(playlist), f.session.search.value.results)
            assertEquals(2, f.gateway.calls.size)

            // Load Lagu now, then switch back to Playlist -> Playlist must restore from RAM cache (0 calls).
            slowFirst = false
            f.session.switchSearch("jazz", SearchProvider.YOUTUBE_MUSIC, SearchKind.SONGS)
            assertEquals(listOf(song), f.session.search.value.results)
            assertEquals(3, f.gateway.calls.size)

            f.session.switchSearch("jazz", SearchProvider.YOUTUBE_MUSIC, SearchKind.PLAYLISTS)
            assertEquals(listOf(playlist), f.session.search.value.results)
            assertEquals(3, f.gateway.calls.size)

            // Ingest exclusion still guards switchSearch.
            f.ingest = true
            f.session.switchSearch("jazz", SearchProvider.YOUTUBE, SearchKind.SONGS)
            assertEquals(3, f.gateway.calls.size)
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

    @Test(timeout = 5000) fun searchErrorIsContainedAsRecoverableStateWithoutBreakingNextSearch() = runBlocking {
        Fixture().use { f ->
            var failWithError = true
            f.gateway.page = {
                if (failWithError) throw UnsatisfiedLinkError("dlopen failed")
                SearchPage(listOf(hit("bbbbbbbbbbb")), 1, 0)
            }
            f.session.search("lagu pertama", SearchProvider.YOUTUBE_MUSIC)
            assertFalse(f.session.search.value.busy)
            assertFalse(f.session.search.value.loadingMore)
            assertEquals(
                "Mesin pencarian tidak kompatibel atau gagal dijalankan di perangkat ini.",
                f.session.search.value.error,
            )
            assertTrue(f.session.canStartOnlineOperation())

            failWithError = false
            f.session.search("lagu kedua", SearchProvider.YOUTUBE_MUSIC)
            assertNull(f.session.search.value.error)
            assertEquals(listOf(hit("bbbbbbbbbbb")), f.session.search.value.results)
        }
    }

    @Test(timeout = 5000) fun previewErrorThrowableIsContainedAsRecoverableState() = runBlocking {
        Fixture().use { f ->
            f.gateway.stream = { throw LinkageError("missing symbol") }
            f.session.preview(hit())
            assertFalse(f.session.preview.value.loading)
            assertEquals("Preview tidak tersedia dari sumber ini.", f.session.preview.value.error)
            assertTrue(f.session.canStartOnlineOperation())

            f.session.cancelPreview()
            assertEquals(PreviewState(), f.session.preview.value)
        }
    }

    @Test(timeout = 5000) fun searchCancellationExceptionClearsBusyWithoutPublishingError() = runBlocking {
        Fixture().use { f ->
            val started = CompletableDeferred<Unit>()
            f.gateway.page = {
                started.complete(Unit)
                awaitCancellation()
            }
            f.session.search("dibatalkan", SearchProvider.YOUTUBE_MUSIC)
            started.await()
            f.session.cancelSearch()
            yield()
            assertFalse(f.session.search.value.busy)
            assertNull(f.session.search.value.error)
            assertTrue(f.session.canStartOnlineOperation())
        }
    }

    @Test(timeout = 5000) fun loadMoreDispatchesWhileDownloadRunsUnlessEngineLocked() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val gateway = Gateway()
        var engineUpdating = false
        val messages = mutableListOf<String>()
        val session = SearchSession(gateway, scope, { engineUpdating }, { messages.add(it) })
        try {
            var pageCalls = 0
            gateway.page = { req ->
                pageCalls++
                val id = String.format("%011d", pageCalls)
                SearchPage(listOf(hit(id)), YoutubeSearchRules.RESULT_LIMIT, 0)
            }
            session.search("lagu live", SearchProvider.YOUTUBE_MUSIC)
            assertEquals(10, session.search.value.loadedThrough)
            assertTrue(session.search.value.hasMore)

            // Active download (engineUpdating stays false) must not block loadMore().
            engineUpdating = false
            session.loadMore()
            assertEquals(listOf(0, 10), gateway.calls.map { it.offset })
            assertEquals(20, session.search.value.loadedThrough)
            assertEquals(2, session.search.value.results.size)

            // Engine binary update still guards search dispatch cleanly.
            engineUpdating = true
            session.loadMore()
            assertEquals(listOf(0, 10), gateway.calls.map { it.offset })
            assertFalse(session.search.value.busy)
            assertFalse(session.search.value.loadingMore)
        } finally {
            session.close()
            scope.cancel()
        }
    }

    @Test(timeout = 5000) fun backgroundQualityProbeEnrichesSongsWithoutBlockingAndRespectsCancellation() = runBlocking {
        Fixture().use { f ->
            val firstHit = hit("aaaaaaaaaaa")
            val playlistHit = hit("PLabcdefghij12").copy(kind = SearchKind.PLAYLISTS)
            val gate = CompletableDeferred<Unit>()
            f.gateway.page = { SearchPage(listOf(firstHit, playlistHit), 2, 0) }
            f.gateway.quality = { id ->
                gate.await()
                if (id == "aaaaaaaaaaa") AudioStreamQuality(134, "OPUS") else AudioStreamQuality(128, "AAC")
            }

            f.session.search("lagu uji", SearchProvider.YOUTUBE_MUSIC)
            assertFalse(f.session.search.value.busy)
            assertTrue(f.session.canStartOnlineOperation())
            assertNull(f.session.search.value.results.first().bitrateKbps)

            gate.complete(Unit)
            yield()
            val updated = f.session.search.value.results
            assertEquals(134, updated[0].bitrateKbps)
            assertEquals("OPUS", updated[0].audioCodec)
            assertNull(updated[1].bitrateKbps)

            // Switching tabs/queries cancels stale background quality probes.
            val staleGate = CompletableDeferred<Unit>()
            f.gateway.page = { req ->
                if (req.query == "pertama") SearchPage(listOf(hit("ccccccccccc")), 1, 0)
                else SearchPage(listOf(hit("ddddddddddd").copy(bitrateKbps = 256, audioCodec = "AAC")), 1, 0)
            }
            f.gateway.quality = { id ->
                if (id == "ccccccccccc") staleGate.await()
                AudioStreamQuality(64, "OPUS")
            }
            f.session.search("pertama", SearchProvider.YOUTUBE)
            f.session.search("kedua", SearchProvider.YOUTUBE)
            staleGate.complete(Unit)
            yield()
            assertEquals("kedua", f.session.search.value.query)
            assertEquals(256, f.session.search.value.results.single().bitrateKbps)
        }
    }

    @Test(timeout = 5000) fun openingPlaylistFromDirectUrlWorksWithoutPriorQueryAndAppliesMetadata() = runBlocking {
        Fixture().use { f ->
            f.gateway.page = {
                SearchPage(
                    hits = listOf(hit("iprFNpkvChk"), hit("rf33vhI_E98")),
                    rawCount = 2,
                    incompleteCount = 0,
                    playlistMeta = PlaylistHeaderMeta(
                        title = "Sholawat Pop Terpopuler",
                        channel = "Jatim Events",
                        thumbnailUrl = "https://i.ytimg.com/pl_c/PLZWOZMU6bkmA/generated_thumbnail.jpg",
                        totalCount = 25,
                    ),
                )
            }
            f.session.openPlaylistUrl("PLZWOZMU6bkmA")
            val state = f.session.search.value
            assertEquals("PLZWOZMU6bkmA", state.playlistId)
            assertEquals("Sholawat Pop Terpopuler", state.playlistTitle)
            assertEquals("Jatim Events", state.playlistChannel)
            assertEquals(25, state.playlistTotalCount)
            assertEquals(2, state.results.size)

            f.session.leavePlaylist()
            assertNull(f.session.search.value.playlistId)
            assertFalse(f.session.search.value.busy)
            assertNull(f.session.search.value.error)
        }
    }
}
