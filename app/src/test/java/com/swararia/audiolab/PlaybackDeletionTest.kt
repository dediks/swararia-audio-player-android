package com.swararia.audiolab

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PlaybackDeletionTest {
    private class Queue(var connected: Boolean = true, var editable: Boolean = true) : PlaybackDeletion.Queue {
        val ids = mutableListOf("1", "2", "1")
        var removals = 0
        var ignoreRemoval = false
        var onRemove: () -> Unit = {}
        override fun snapshot(): List<String>? = if (connected) ids.toList() else null
        override fun removeAt(index: Int): Boolean {
            if (!connected || !editable) return false
            removals++
            onRemove()
            if (!ignoreRemoval) ids.removeAt(index)
            return true
        }
    }

    @Test(timeout = 5000) fun delayedDeleteSurvivesActivityReplacementAndDisconnectedCompletion() = runBlocking {
        val owner = PlaybackDeletion()
        val old = Queue()
        owner.connect(old)
        val stored = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { owner.delete(1) { stored.await() } }
        assertFalse(owner.canPlay(1))
        assertTrue(old.ids.none { it == "1" }) // Cannot skip/replay it during storage I/O.
        owner.disconnect(old)
        stored.complete(Unit); job.join()
        assertEquals(setOf("1"), owner.pendingDeletedIds)
        val replacement = Queue(connected = false)
        owner.connect(replacement)
        assertEquals(setOf("1"), owner.pendingDeletedIds)
        replacement.connected = true
        owner.reconcile()
        assertEquals(listOf("2"), replacement.ids)
        assertTrue(owner.pendingDeletedIds.isEmpty())
        assertFalse(owner.canPlay(1)) // Stale library/permission callback cannot reinsert a deleted ID.
        assertTrue(owner.canPlay(2))
    }

    @Test(timeout = 5000) fun replacementDuringStorageIsReconciledAndOldDisconnectCannotDetachIt() = runBlocking {
        val owner = PlaybackDeletion(); val old = Queue(); owner.connect(old)
        val stored = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { owner.delete(1) { stored.await() } }
        val current = Queue(); owner.connect(current); owner.disconnect(old)
        assertEquals(listOf("2"), current.ids)
        // Simulate the service restoring an old queue while the storage request is outstanding.
        current.ids.add("1")
        stored.complete(Unit); job.join()
        assertEquals(listOf("2"), current.ids)
        assertTrue(owner.pendingDeletedIds.isEmpty())
    }

    @Test(timeout = 5000) fun acknowledgementRequiresCheckedAbsenceNotJustSuccessfulRemoveCommand() = runBlocking {
        val owner = PlaybackDeletion(); val queue = Queue(editable = false); owner.connect(queue)
        owner.delete(1) {}
        assertEquals(setOf("1"), owner.pendingDeletedIds)
        queue.editable = true; queue.ignoreRemoval = true; owner.reconcile()
        assertEquals(setOf("1"), owner.pendingDeletedIds)
        queue.ignoreRemoval = false; owner.reconcile()
        assertTrue(owner.pendingDeletedIds.isEmpty())
        assertEquals(listOf("2"), queue.ids)
    }

    @Test(timeout = 5000) fun failedStorageReleasesPlaybackGuardAndDoesNotInventDeletion() = runBlocking {
        val owner = PlaybackDeletion(); val queue = Queue(); owner.connect(queue)
        try { owner.delete(1) { error("storage failure") }; fail("expected failure") }
        catch (_: IllegalStateException) {}
        assertTrue(owner.canPlay(1))
        assertTrue(owner.pendingDeletedIds.isEmpty())
    }

    @Test(timeout = 5000) fun synchronousQueueEventsCannotReenterRemoval() = runBlocking {
        val owner = PlaybackDeletion(); val queue = Queue(); owner.connect(queue)
        queue.onRemove = { owner.reconcile() }
        owner.delete(1) {}
        assertEquals(2, queue.removals)
        assertEquals(listOf("2"), queue.ids)
        assertTrue(owner.pendingDeletedIds.isEmpty())
    }

    @Test fun connectingWithoutExplicitDeletionNeverClearsUnknownQueueItems() {
        val owner = PlaybackDeletion(); val queue = Queue(); owner.connect(queue); owner.reconcile()
        assertEquals(listOf("1", "2", "1"), queue.ids)
        assertEquals(0, queue.removals)
    }
}
