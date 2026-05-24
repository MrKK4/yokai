package yokai.domain.suggestions

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuggestionRefreshSessionTrackerTest {

    @Test
    fun `newer refresh session makes older session stale`() {
        val tracker = SuggestionRefreshSessionTracker()
        val v1 = tracker.start(
            mode = SuggestionResultMode.V1,
            sortOrder = SuggestionSortOrder.Popular,
            hardRefresh = true,
            targetSectionKey = null,
            pageOffset = 1,
            reason = SuggestionRefreshReason.Toggle,
        )
        val v2 = tracker.start(
            mode = SuggestionResultMode.V2,
            sortOrder = SuggestionSortOrder.Popular,
            hardRefresh = true,
            targetSectionKey = null,
            pageOffset = 1,
            reason = SuggestionRefreshReason.Toggle,
        )

        assertFalse(tracker.isCurrent(v1))
        assertTrue(tracker.isCurrent(v2))
    }

    @Test
    fun `paused session is only kept when it is still current`() {
        val tracker = SuggestionRefreshSessionTracker()
        val session = tracker.start(
            mode = SuggestionResultMode.V2,
            sortOrder = SuggestionSortOrder.Latest,
            hardRefresh = false,
            targetSectionKey = "tag:action",
            pageOffset = 3,
            reason = SuggestionRefreshReason.Manual,
        )

        assertTrue(tracker.pauseIfCurrent(session))
        assertSame(session, tracker.paused())

        tracker.start(
            mode = SuggestionResultMode.V1,
            sortOrder = SuggestionSortOrder.Latest,
            hardRefresh = true,
            targetSectionKey = null,
            pageOffset = 1,
            reason = SuggestionRefreshReason.Toggle,
        )

        assertFalse(tracker.pauseIfCurrent(session))
    }

    @Test
    fun `in-flight V1 session is stale once user toggles to V2`() {
        // Regression for the "V1 results showing under V2" report: when a user clicks the
        // V2 toggle while a V1 refresh is still mid-fetch, the V1 session must observe that
        // it is no longer current so it skips its DB write. The session check is what
        // replaces the foreground refresh mutex; if this assertion ever flips, the V1 batch
        // can land in the DB after the user has switched modes.
        val tracker = SuggestionRefreshSessionTracker()
        val v1Session = tracker.start(
            mode = SuggestionResultMode.V1,
            sortOrder = SuggestionSortOrder.Popular,
            hardRefresh = false,
            targetSectionKey = null,
            pageOffset = 1,
            reason = SuggestionRefreshReason.Manual,
        )

        tracker.start(
            mode = SuggestionResultMode.V2,
            sortOrder = SuggestionSortOrder.Popular,
            hardRefresh = true,
            targetSectionKey = null,
            pageOffset = 1,
            reason = SuggestionRefreshReason.Toggle,
        )

        assertFalse(tracker.isCurrent(v1Session))
    }

    @Test
    fun `same-id v1 session is not current under v2 active mode`() {
        // Defensive check: even if a session id collision somehow occurred across modes,
        // the tracker must distinguish by mode so a V1 commit cannot impersonate the V2
        // session that holds the active slot.
        val tracker = SuggestionRefreshSessionTracker()
        val active = tracker.start(
            mode = SuggestionResultMode.V2,
            sortOrder = SuggestionSortOrder.Popular,
            hardRefresh = true,
            targetSectionKey = null,
            pageOffset = 1,
            reason = SuggestionRefreshReason.Toggle,
        )
        val masquerade = active.copy(mode = SuggestionResultMode.V1)

        assertFalse(tracker.isCurrent(masquerade))
        assertTrue(tracker.isCurrent(active))
    }
}
