package yokai.domain.suggestions

internal object SourceDiversity {
    fun <T> roundRobinBySource(
        items: Collection<T>,
        maxResults: Int,
        maxPerSource: Int? = null,
        sourceId: (T) -> Long,
        sourceIndex: (T) -> Int,
        score: (T) -> Double,
    ): List<T> {
        if (maxResults <= 0) return emptyList()

        val sourceBuckets = items
            .groupBy(sourceId)
            .values
            .map { sourceItems ->
                sourceItems
                    .sortedByDescending(score)
                    .let { items -> maxPerSource?.let(items::take) ?: items }
                    .toMutableList()
            }
            .sortedWith(
                compareBy<MutableList<T>>(
                    { -(it.firstOrNull()?.let(score) ?: Double.NEGATIVE_INFINITY) },
                    { it.firstOrNull()?.let(sourceIndex) ?: Int.MAX_VALUE },
                    { it.firstOrNull()?.let(sourceId) ?: Long.MAX_VALUE },
                ),
            )

        val selected = mutableListOf<T>()
        while (selected.size < maxResults) {
            var addedInPass = false
            for (bucket in sourceBuckets) {
                if (selected.size >= maxResults) break
                if (bucket.isNotEmpty()) {
                    selected += bucket.removeAt(0)
                    addedInPass = true
                }
            }
            if (!addedInPass) break
        }
        return selected
    }
}
