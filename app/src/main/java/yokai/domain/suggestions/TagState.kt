package yokai.domain.suggestions

enum class TagState {
    PINNED,
    MANAGED,
    BLACKLISTED,
}

fun String.toTagState(): TagState =
    runCatching { TagState.valueOf(this) }.getOrDefault(TagState.MANAGED)
