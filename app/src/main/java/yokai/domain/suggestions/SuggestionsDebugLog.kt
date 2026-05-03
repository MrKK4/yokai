package yokai.domain.suggestions

import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SuggestionsLogEntry(
    val timestamp: Long,
    val type: LogType,
    val message: String,
)

enum class LogType {
    SECTION_SELECTED,
    SECTION_DROPPED,
    SECTION_THIN,
    ITEM_FILTERED,
    SORT_FALLBACK,
    PROFILE_UPDATE,
    REFRESH_MODE,
    SOURCE_CAP_HIT,
}

class SuggestionsDebugLog(
    private val maxEntries: Int = SuggestionsConfig.DEBUG_LOG_MAX_ENTRIES,
) {
    private val entries = ArrayDeque<SuggestionsLogEntry>(maxEntries)
    private val _entriesFlow = MutableStateFlow<List<SuggestionsLogEntry>>(emptyList())

    val entriesFlow: StateFlow<List<SuggestionsLogEntry>> = _entriesFlow.asStateFlow()

    @Synchronized
    fun add(type: LogType, message: String, timestamp: Long = System.currentTimeMillis()) {
        if (entries.size >= maxEntries) {
            entries.removeFirst()
        }
        entries.addLast(SuggestionsLogEntry(timestamp, type, message))
        _entriesFlow.value = entries.toList()
    }

    @Synchronized
    fun snapshot(): List<SuggestionsLogEntry> =
        entries.toList()

    @Synchronized
    fun clear() {
        entries.clear()
        _entriesFlow.value = emptyList()
    }
}
