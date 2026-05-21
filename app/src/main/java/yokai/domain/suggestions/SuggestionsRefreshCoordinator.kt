package yokai.domain.suggestions

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

object SuggestionsRefreshCoordinator {
    private val mutex = Mutex()

    /**
     * Acquires the refresh lock with no upper bound. Suitable for background workers that can
     * afford to wait but should never be used from the UI thread chain — a hung refresh would
     * leave queued callers blocked forever. Foreground paths should use [withLockOrSkip].
     */
    suspend fun <T> withLock(block: suspend () -> T): T =
        mutex.withLock { block() }

    /**
     * Foreground variant: waits up to [timeoutMs] for the mutex, then either runs [block] or
     * returns null. Prevents queue pile-up when a prior refresh is stuck inside source fetches
     * (8 sources × 22 s × N sections can monopolise the lock for minutes).
     */
    suspend fun <T> withLockOrSkip(
        timeoutMs: Long = MAX_FOREGROUND_LOCK_WAIT_MS,
        block: suspend () -> T,
    ): T? = withTimeoutOrNull(timeoutMs) { mutex.withLock { block() } }

    suspend fun <T> tryRun(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }

    /** Upper bound on how long the foreground will wait to acquire the refresh lock. */
    const val MAX_FOREGROUND_LOCK_WAIT_MS: Long = 30_000L
}
