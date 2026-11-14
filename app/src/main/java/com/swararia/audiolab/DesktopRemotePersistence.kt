package com.swararia.audiolab

import kotlinx.coroutines.*

/** Application-owned ordering boundary, shared by every ViewModel using this credential file.
 * Submission order is reserved synchronously, before dispatch. Waiting callers may cancel but
 * submitted disk operations finish independently. No idle worker, retries, or network work.
 * This protects against coroutine cancellation, NOT process death or failed storage writes.
 */
class DesktopRemotePersistence(
    private val store: DesktopRemoteSessionStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private var tail: Job? = null
    private var owner: Any? = null

    @Synchronized fun claim(): Any = Any().also { owner = it }
    @Synchronized fun release(token: Any) { if (owner === token) owner = null }

    private fun <T> submit(token: Any, operation: () -> T): Deferred<Result<T>> {
        val next = synchronized(this) {
            if (owner !== token) return CompletableDeferred(Result.failure(IllegalStateException("Retired session")))
            val previous = tail
            scope.async(start = CoroutineStart.LAZY) {
                previous?.join()
                runCatching { operation() }
            }.also { tail = it }
        }
        next.invokeOnCompletion {
            synchronized(this) { if (tail === next) tail = null }
        }
        // Never run disk work while holding the synchronous intent-registration monitor.
        next.start()
        return next
    }

    fun load(token: Any) = submit(token) { store.load() }
    fun save(token: Any, session: PersistedDesktopRemoteSession) = submit(token) { store.save(session) }
    fun clear(token: Any) = submit(token) { store.clear() }
}
