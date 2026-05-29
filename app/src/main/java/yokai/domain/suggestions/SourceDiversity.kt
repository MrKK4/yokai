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
        val productiveSourceCount = sourceBuckets.size
        fun consumeRoundRobin(limitPerSource: Int?) {
            val countsBySource = mutableMapOf<Long, Int>()
            var addedInPass = false
            while (selected.size < maxResults) {
                addedInPass = false
                for (bucket in sourceBuckets) {
                    if (selected.size >= maxResults) break
                    val next = bucket.firstOrNull() ?: continue
                    val nextSource = sourceId(next)
                    val currentCount = countsBySource.getOrDefault(nextSource, 0)
                    if (limitPerSource != null && currentCount >= limitPerSource) continue
                    selected += bucket.removeAt(0)
                    countsBySource[nextSource] = currentCount + 1
                    addedInPass = true
                }
                if (!addedInPass) break
            }
        }

        consumeRoundRobin(maxPerSource)
        if (selected.size < maxResults && maxPerSource != null && productiveSourceCount > 1) {
            consumeRoundRobin(limitPerSource = null)
        }
        return selected
    }
}
