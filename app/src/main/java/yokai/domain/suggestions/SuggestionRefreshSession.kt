package yokai.domain.suggestions

import java.util.concurrent.atomic.AtomicLong

enum class SuggestionResultMode(val resultVersion: Int) {
    V1(SuggestionsConfig.RESULT_VERSION_V1),
    V2(SuggestionsConfig.RESULT_VERSION_V2),
}

enum class SuggestionRefreshReason {
    Startup,
    Manual,
    Toggle,
    SourceChange,
    Worker,
    Pagination,
}

data class SuggestionRefreshSession(
    val sessionId: Long,
    val mode: SuggestionResultMode,
    val sortOrder: SuggestionSortOrder,
    val hardRefresh: Boolean,
    val targetSectionKey: String?,
    val pageOffset: Int,
    val reason: SuggestionRefreshReason,
)

class SuggestionRefreshSessionTracker {
    private val nextId = AtomicLong(0L)

    @Volatile
    private var activeSession: SuggestionRefreshSession? = null

    @Volatile
    private var pausedSession: SuggestionRefreshSession? = null

    fun start(
        mode: SuggestionResultMode,
        sortOrder: SuggestionSortOrder,
        hardRefresh: Boolean,
        targetSectionKey: String?,
        pageOffset: Int,
        reason: SuggestionRefreshReason,
    ): SuggestionRefreshSession =
        SuggestionRefreshSession(
            sessionId = nextId.incrementAndGet(),
            mode = mode,
            sortOrder = sortOrder,
            hardRefresh = hardRefresh,
            targetSectionKey = targetSectionKey,
            pageOffset = pageOffset,
            reason = reason,
        ).also { session ->
            activeSession = session
            pausedSession = null
        }

    fun isCurrent(session: SuggestionRefreshSession): Boolean =
        activeSession?.let { current ->
            current.sessionId == session.sessionId && current.mode == session.mode
        } == true

    /**
     * Returns the currently-active session if one exists. Used by callers that
     * need to pause/resume the in-flight refresh without holding a reference to
     * the session themselves (e.g. scroll-triggered batch loads that hit a
     * transient network failure mid-fetch).
     */
    fun activeSessionOrNull(): SuggestionRefreshSession? = activeSession

    fun pauseIfCurrent(session: SuggestionRefreshSession): Boolean =
        if (isCurrent(session)) {
            pausedSession = session
            true
        } else {
            false
        }

    fun paused(): SuggestionRefreshSession? = pausedSession

    fun clearPaused(session: SuggestionRefreshSession) {
        if (pausedSession?.sessionId == session.sessionId) {
            pausedSession = null
        }
    }

    fun finishIfCurrent(session: SuggestionRefreshSession) {
        if (isCurrent(session)) {
            activeSession = null
        }
        clearPaused(session)
    }

    fun cancelCurrent() {
        activeSession = null
        pausedSession = null
    }
}
