package com.swararia.audiolab

/** Retained, UI-thread owner of explicit deletions; never infer deletion from an unloaded library.
 * Queue ports belong to an Activity connection; storage completion belongs to this owner instead.
 */
class PlaybackDeletion {
    interface Queue {
        /** Null means disconnected/unreadable, not an empty queue. */
        fun snapshot(): List<String>?
        fun removeAt(index: Int): Boolean
    }
    private var queue: Queue? = null
    private var reconciling = false
    private val deleting = mutableSetOf<String>()
    private val deleted = mutableSetOf<String>()
    private val pending = mutableSetOf<String>()
    val pendingDeletedIds: Set<String> get() = pending.toSet()

    fun canPlay(id: Long): Boolean = id.toString() !in deleting && id.toString() !in deleted

    fun connect(current: Queue) { queue = current; reconcile() }
    fun disconnect(previous: Queue) { if (queue === previous) queue = null }

    suspend fun delete(id: Long, storage: suspend () -> Unit) {
        val key = id.toString()
        check(deleting.add(key)) { "Delete sedang berjalan." }
        // Remove queued copies now, so transport/skip cannot replay a file being deleted.
        reconcile()
        try {
            storage()
            deleted.add(key)
            pending.add(key)
        } finally {
            deleting.remove(key)
        }
        reconcile()
    }

    fun reconcile() {
        val current = queue ?: return
        val blocked = deleting + pending
        if (blocked.isEmpty()) return
        if (reconciling) return
        reconciling = true
        // Command dispatch can fail/disconnect; acknowledge only a subsequent checked absence.
        try {
            val before = current.snapshot() ?: return
            for (index in before.indices.reversed()) {
                if (before[index] in blocked && !current.removeAt(index)) return
            }
            val after = current.snapshot() ?: return
            if (queue === current) pending.removeAll { it !in after }
        } catch (_: Exception) {
            // Retain explicit pending IDs for the next connection/queue event.
        } finally { reconciling = false }
    }
}
