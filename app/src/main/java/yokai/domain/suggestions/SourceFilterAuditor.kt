package yokai.domain.suggestions

import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.Filter
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

        // ── Pass 1 — warmup (no collection) ───────────────────────────────────────
        // Many extension themes (GalleryAdults, Madara) lazy-fetch their genre list
        // on the first `getFilterList()` call via a background coroutine, so the
        // first synchronous return is just Sort/Status/Category — no genre group.
        // Hitting `getFilterList()` here kicks off that background fetch for every
        // source in parallel. We discard the returned list; the goal is the side
        // effect.
        sources.forEach { source ->
            try {
                gate.withPermit {
                    try {
                        source.getFilterList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Warmup failure is fine — pass 2 will retry and either
                        // capture nothing or pick up whatever loaded async.
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Single-source failure must never abort the whole audit.
            }
        }

        // Give every source's background genre fetch time to commit before the
        // collection pass. 5s is conservative; most sources finish under 2s. We are
        // already on Dispatchers.IO running fire-and-forget, so this delay does not
        // block the caller's `retrieve` call.
        delay(GENRE_WARMUP_DELAY_MS)

        // ── Pass 2 — collect labels ───────────────────────────────────────────────
        val entries = mutableListOf<Triple<String, String, Long>>()
        sources.forEach { source ->
            try {
                gate.withPermit {
                    val filters = try {
                        source.getFilterList()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        return@withPermit
                    }
                    filters.forEach { filter -> filter.collectLabels(source.id, entries) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Single-source failure must never abort the whole audit.
            }
        }

        if (entries.isEmpty()) return

        try {
            tagProfileRepository.recordSourceVocabularyBatch(entries)
            debugLog.add(
                LogType.SECTION_SELECTED,
                "SourceFilterAuditor wrote ${entries.size} filter-label aliases across ${sources.size} sources (after ${GENRE_WARMUP_DELAY_MS}ms genre warmup)",
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
        // Time given to lazy genre-fetch coroutines (GalleryAdults/Madara themes
        // launchIO their `/tags/popular/` or `/genres/` request inside `getFilterList`
        // and populate a mutable field on completion). 5s covers most sources; the
        // ones that miss this window simply contribute zero filter-label aliases —
        // not a regression, just no upgrade.
        private const val GENRE_WARMUP_DELAY_MS = 5_000L
    }
}
