package yokai.domain.suggestions

enum class TagState {
    MANAGED,
    BLACKLISTED,
}

fun String.toTagState(): TagState =
    runCatching { TagState.valueOf(this) }.getOrDefault(TagState.MANAGED)
