package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Proactively walks each source's [CatalogueSource.getFilterList] and records every
 * CheckBox / TriState / Select label as a source-specific alias in the tag_alias table.
 *
 * Why this exists:
 *  - Phase B of [CandidateRetriever.fetchSearchSource] looks up `getExactTermForSource`
 *    to pick the raw query string the source expects for a given canonical tag.
 *  - Without seeding, that table only gets populated by `learnVocabulary` AFTER a
 *    successful fetch — a chicken-and-egg loop where the first fetch goes out with
 *    the generic canonical key and any source whose text-search expects a specific
 *    raw string (e.g. "m.i.l.f") gets zero results.
 *
 * Audits are best-effort: any source that throws is skipped silently, and the whole
 * job runs at most once per process (gated by [audited]).
 */
class SourceFilterAuditor(
    private val tagCanonicalizer: TagCanonicalizer,
    private val tagProfileRepository: TagProfileRepository,
    private val debugLog: SuggestionsDebugLog,
) {
    private val audited = AtomicBoolean(false)
    private val seedWritten = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Write hardcoded [SourceVocabularySeed] entries to `tag_alias` for any of the
     * given sources that have a seed defined. Suspends until the DB write completes
     * so the very next `getExactTermForSource` lookup observes the seed. Idempotent
     * — only runs once per process.
     */
    suspend fun seedHardcodedAliasesNow(sources: List<CatalogueSource>) {
        if (!seedWritten.compareAndSet(false, true)) return
        if (sources.isEmpty()) {
            seedWritten.set(false)
            return
        }
        writeHardcodedSeed(sources)
    }

    /**
     * Kick off the async filter-list pass for [sources] (network I/O may be involved
     * for lazy-loading filter lists). Returns immediately. Safe to call from any
     * thread; subsequent calls after the first do nothing.
     */
    fun scheduleAudit(sources: List<CatalogueSource>) {
        if (!audited.compareAndSet(false, true)) return
        if (sources.isEmpty()) {
            audited.set(false)
            return
        }
        scope.launch { runFilterListAudit(sources) }
    }

    private suspend fun writeHardcodedSeed(sources: List<CatalogueSource>) {
        val entries = mutableListOf<Triple<String, String, Long>>()
        sources.forEach { source ->
            val seed = SourceVocabularySeed.seedFor(source.name) ?: return@forEach
            seed.forEach { (canonical, rawQuery) ->
                if (canonical.isNotBlank() && rawQuery.isNotBlank()) {
                    entries += Triple(rawQuery, canonical, source.id)
                }
            }
        }
        if (entries.isEmpty()) return
        try {
            tagProfileRepository.recordSourceVocabularyBatch(entries)
            debugLog.add(
                LogType.SECTION_SELECTED,
                "SourceVocabularySeed wrote ${entries.size} static aliases across ${sources.count { SourceVocabularySeed.seedFor(it.name) != null }} sources",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            debugLog.add(
                LogType.SECTION_DROPPED,
                "SourceVocabularySeed DB write failed: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private suspend fun runFilterListAudit(sources: List<CatalogueSource>) {
        val gate = Semaphore(AUDIT_PARALLELISM)

        // Poll each source's filter list concurrently until its async genre list loads, then
        // collect labels. Replaces the old single fixed 5s warmup, which silently captured
        // nothing whenever a source's genre fetch (GalleryAdults requestTags, Cloudflare, etc.)
        // took longer than the window — the root cause of tag sections returning 0.
        val entries = java.util.Collections.synchronizedList(mutableListOf<Triple<String, String, Long>>())
        coroutineScope {
            sources.forEach { source ->
                launch {
                    try {
                        gate.withPermit {
                            val filters = try {
                                source.awaitLoadedFilterList(
                                    attempts = SuggestionsConfig.GENRE_WARMUP_POLL_ATTEMPTS,
                                    intervalMs = SuggestionsConfig.GENRE_WARMUP_POLL_INTERVAL_MS,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Throwable) {
                                return@withPermit
                            }
                            val out = mutableListOf<Triple<String, String, Long>>()
                            filters.forEach { filter -> filter.collectLabels(source.id, out) }
                            entries.addAll(out)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Single-source failure must never abort the whole audit.
                    }
                }
            }
        }

        if (entries.isEmpty()) {
            // Nothing loaded this run (cold start / every source Cloudflare-gated). Un-latch so a
            // later refresh re-attempts the warmup instead of giving up for the whole process.
            audited.set(false)
            return
        }

        val collected = entries.toList()
        try {
            tagProfileRepository.recordSourceVocabularyBatch(collected)
            debugLog.add(
                LogType.SECTION_SELECTED,
                "SourceFilterAuditor wrote ${collected.size} filter-label aliases across ${sources.size} sources (poll-until-loaded genre warmup)",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            debugLog.add(
                LogType.SECTION_DROPPED,
                "SourceFilterAuditor DB write failed: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private suspend fun Filter<*>.collectLabels(sourceId: Long, out: MutableList<Triple<String, String, Long>>) {
        when (this) {
            is Filter.Group<*> -> state.forEach { item ->
                if (item is Filter<*>) item.collectLabels(sourceId, out)
            }
            is Filter.CheckBox -> recordLabel(name, sourceId, out)
            is Filter.TriState -> recordLabel(name, sourceId, out)
            is Filter.Select<*> -> values.forEach { value ->
                recordLabel(value.toString(), sourceId, out)
            }
            else -> Unit
        }
    }

    private suspend fun recordLabel(
        rawLabel: String,
        sourceId: Long,
        out: MutableList<Triple<String, String, Long>>,
    ) {
        if (rawLabel.isBlank()) return
        val canonical = try {
            tagCanonicalizer.canonicalize(rawLabel, sourceId).canonicalKey
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return
        }
        if (canonical.isBlank()) return
        out += Triple(rawLabel, canonical, sourceId)
    }

    private companion object {
        private const val AUDIT_PARALLELISM = 4
    }
}
