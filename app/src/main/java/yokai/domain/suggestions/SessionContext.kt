package yokai.domain.suggestions

class SessionContext(
    private val maxTags: Int = 30,
) {
    private val recentTags = ArrayDeque<String>()

    @Synchronized
    fun onMangaOpened(canonicalTags: List<String>) {
        canonicalTags
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .forEach { recentTags.addLast(it) }
        while (recentTags.size > maxTags) {
            recentTags.removeFirst()
        }
    }

    @Synchronized
    fun getRecentTags(): Set<String> =
        recentTags.toSet()

    @Synchronized
    fun clear() {
        recentTags.clear()
    }
}
