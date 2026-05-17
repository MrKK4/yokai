package yokai.domain.suggestions

data class TagPattern(
    val canonicalKey: String,
    val displayName: String,
    val exact: Set<String> = emptySet(),
    val prefixes: Set<String> = emptySet(),
    val patterns: List<Regex> = emptyList(),
)
