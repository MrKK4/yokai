package yokai.domain.suggestions

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SuggestionsRefreshCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withLock(block: suspend () -> T): T =
        mutex.withLock { block() }

    suspend fun <T> tryRun(block: suspend () -> T): T? {
        if (!mutex.tryLock()) return null
        return try {
            block()
        } finally {
            mutex.unlock()
        }
    }
}
