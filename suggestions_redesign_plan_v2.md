# Suggestions Page Redesign — v2 Revision

> **This document supersedes specific sections of `suggestions_redesign_plan.md`.**  
> Read the original plan first. This revision only describes what changes and why. Where a section is not mentioned here, the original plan stands.

---

## What Changed and Why

The original plan used a quota/slot system (GUARANTEED_SLOTS, ROTATING_SLOTS, cooldowns) to limit sections to ~5–7 tag sections per refresh. This was over-engineered for the actual goal. The real goal is simpler:

> Show all of the user's tags, ranked by score, with the highest-scoring tag at the top. Load them progressively as the user scrolls. Do not cap the number of sections arbitrarily.

A user with 60–70 unique tags should see 60–70 tag sections. A user with 10 tags sees 10. The system does not decide how many is "enough."

---

## Revised Section 9 — Section Planner (Simplified)

### 9.1 What the planner now does

The `SectionPlanner` no longer applies quotas or cooldowns. It does one thing: produce an ordered list of all eligible tags, ranked by affinity score descending.

```
Input:  List<TagProfile> (all non-blacklisted, non-MANAGED-with-zero-score tags)
Output: List<PlannedSection> ordered as follows:

  1. Discovery section (always first, exactly one)
  2. All PINNED tags, ordered by pinned_at ascending (oldest pin first)
  3. All remaining MANAGED tags ordered by affinity descending (highest score first)
```

That's the entire ordering logic. No slots. No guaranteed count. No cooldowns. No rotation. The highest-scoring tag is always section 2 (or section after pinned tags). The lowest-scoring tag is always last.

### 9.2 What is removed from the original plan

Remove entirely:
- `GUARANTEED_SLOTS` constant and concept
- `ROTATING_SLOTS_MIN` / `ROTATING_SLOTS_MAX` constants
- `ROTATING_TAG` section type — all managed tags are just `MANAGED_TAG`
- `GUARANTEED_TAG` section type — the top tag is already first by sort order, no special treatment needed
- `tag_profile.cooldown_until` column — remove from migration
- `tag_profile.pinned_at` → keep this one, it controls pinned ordering
- Cooldown write logic in the presenter

### 9.3 Updated `SectionType` enum

```kotlin
enum class SectionType {
    DISCOVERY,
    PINNED_TAG,
    MANAGED_TAG,   // replaces both GUARANTEED_TAG and ROTATING_TAG
}
```

### 9.4 Section header reason strings (updated)

```
PINNED:      "Pinned: {displayName}"
MANAGED:
  affinity > 5.0  → "Because you love {displayName}"
  affinity > 2.0  → "Because you often read {displayName}"
  else            → "Because you read {displayName}"
DISCOVERY:
  sort = Latest   → "Latest from your sources"
  sort = Popular  → "Popular from your sources"
```

---

## New Section 9A — Progressive Section Loading (replaces infinite scroll on items)

### The core idea

The feed does not load all tag sections at once. It loads them in batches of 5 as the user scrolls. Each batch of 5 sections triggers exactly one round of network calls (one per section in the batch). The next batch is not fetched until the user scrolls close enough to need it.

This is different from infinite scroll on manga items within a section. This is infinite scroll on sections themselves.

### 9A.1 Terminology

- **Batch size:** 5 sections per load (configurable via `SuggestionsConfig.SECTION_BATCH_SIZE = 5`)
- **Trigger threshold:** load the next batch when the user has scrolled to within 2 sections of the last loaded section
- **Total planned sections:** all tags are planned upfront (full ordered list exists in memory), but only the first batch is fetched on initial load

### 9A.2 State additions to `SuggestionsState`

```kotlin
data class SuggestionsState(
    val sections: List<FeedSection> = emptyList(),      // sections fetched so far
    val plannedSections: List<PlannedSection> = emptyList(), // full ordered plan (all tags)
    val nextBatchStartIndex: Int = 0,                   // index into plannedSections for next fetch
    val isFetchingBatch: Boolean = false,               // true while a batch fetch is in flight
    val allSectionsLoaded: Boolean = false,             // true when nextBatchStartIndex >= plannedSections.size
    val sortOrder: SuggestionSortOrder = SuggestionSortOrder.Popular,
    val isInitialLoading: Boolean = false,
    // tag filter / expand sheet state unchanged from original plan
)
```

### 9A.3 Initial load sequence

On hard refresh:
1. Build interest profile (full history scan).
2. Run `SectionPlanner` → produces full `plannedSections` list (all tags, ordered, in memory). This is fast — no network involved.
3. Store `plannedSections` in state.
4. Immediately fetch the first batch: `plannedSections[0..SECTION_BATCH_SIZE-1]` (Discovery + first 4 tag sections, or however many exist).
5. As each section fetch completes, append to `sections` list and render. Sections appear progressively as network calls return, not all at once.
6. Set `nextBatchStartIndex = SECTION_BATCH_SIZE`.

### 9A.4 Subsequent batch loading (scroll trigger)

In `SuggestionsScreen`, use the existing `ReportLoadMoreState` composable pattern (already in the codebase) but trigger on sections, not items:

```
Trigger condition:
  lastVisibleSectionIndex >= sections.size - LOAD_MORE_SECTION_THRESHOLD
  where LOAD_MORE_SECTION_THRESHOLD = 2
```

When triggered:
1. If `isFetchingBatch == true`, do nothing (a fetch is already in progress).
2. If `allSectionsLoaded == true`, do nothing.
3. Otherwise: fetch `plannedSections[nextBatchStartIndex .. nextBatchStartIndex + SECTION_BATCH_SIZE - 1]`.
4. Set `isFetchingBatch = true`.
5. As each section in the batch completes, append to `sections`. Do not wait for the whole batch — append each section as it arrives.
6. When the entire batch is done: set `isFetchingBatch = false`, advance `nextBatchStartIndex`, check if `allSectionsLoaded`.

### 9A.5 Network call control within a batch

Within a single batch of 5 sections, the network calls are **concurrent** (as in the original plan — `coroutineScope { batch.map { async { fetch(section) } }.awaitAll() }`). All 5 sections in a batch fetch simultaneously.

Between batches, there are **no** network calls until scroll triggers the next batch. This is the key control: the user must scroll to within 2 sections of the bottom before the next 5 sections fetch.

This means:
- User opens suggestions → 5 sections start fetching immediately
- User scrolls halfway → nothing new happens
- User scrolls to section 3 of 5 → next batch of 5 starts fetching
- User never scrolls → only the first 5 sections ever make network calls

### 9A.6 Footer UI states

At the bottom of the section list, show one of:
- **Loading indicator** (circular spinner): `isFetchingBatch == true`
- **End message**: `allSectionsLoaded == true` → `"All ${sections.size} interest sections loaded"`
- **Nothing**: still have sections to load but not currently fetching (user hasn't scrolled far enough)

---

## Revised Section 11 — Source Diversity (Updated Constants Only)

The diversity rules are unchanged. Update the constants to be less restrictive since we now show many more sections:

```kotlin
const val MAX_PER_SOURCE_FETCH          = 5   // unchanged
const val MAX_PER_SOURCE_PER_SECTION    = 3   // unchanged
const val MAX_PER_SOURCE_FEED           = 999 // effectively uncapped — with 60+ sections this cap makes no sense
const val MAX_RESULTS_PER_SECTION       = 8   // unchanged
const val MAX_CANDIDATES_PER_SECTION    = 40  // unchanged
```

Remove `MAX_PER_SOURCE_FEED` as a concept. With 60+ sections each capping per-source at 3, the global feed cap is irrelevant and only creates confusing filtering behavior.

---

## Revised Section 12 — Refresh Policy (Batch-Aware)

### 12.1 Hard refresh changes

Hard refresh now means:
1. Rebuild interest profile.
2. Re-run `SectionPlanner` → new `plannedSections` list.
3. Clear all cached section results.
4. Reset `nextBatchStartIndex = 0`.
5. Reset `sections = []`.
6. Fetch first batch.

The seen-item log (`suggestion_seen_log`) is still cleared of entries older than 24 hours. Keep this.

### 12.2 Soft refresh changes

Soft refresh (background job) now means:
1. Re-run `SectionPlanner` with cached profile → may reorder sections if affinity changed.
2. Clear cached results only for sections that have exceeded their TTL.
3. Do NOT reset `nextBatchStartIndex` — keep the user's scroll position context.
4. Re-fetch only the already-loaded sections (sections currently in `sections` list), not the unloaded ones.

### 12.3 Section TTL cache (per section key)

Store `Map<String, Long>` in memory: `sectionLastFetchedAt[sectionKey] = timestamp`. On soft refresh, re-fetch a section only if `now - sectionLastFetchedAt[key] > TTL`. TTL values unchanged from original plan (Discovery = 30 min, tag sections = 90 min).

---

## Revised Appendix A — Updated Constants

```kotlin
object SuggestionsConfig {
    // Interest profile (unchanged)
    const val STM_HALF_LIFE_DAYS = 7.0
    const val LTM_ALPHA = 0.1
    const val VELOCITY_WEIGHT = 0.05
    const val STM_WEIGHT = 0.6
    const val LTM_WEIGHT = 0.4

    // Section planner — SIMPLIFIED
    // No slot quotas. No cooldowns. Just batch size.
    const val SECTION_BATCH_SIZE = 5
    const val LOAD_MORE_SECTION_THRESHOLD = 2  // sections from end of list

    // Retrieval (unchanged)
    const val MAX_CANDIDATES_PER_SECTION = 40
    const val MAX_PER_SOURCE_FETCH = 5

    // Ranking (unchanged except removed MAX_PER_SOURCE_FEED)
    const val MAX_RESULTS_PER_SECTION = 8
    const val MAX_PER_SOURCE_PER_SECTION = 3

    // Refresh (unchanged)
    const val HARD_REFRESH_NOVELTY_QUOTA = 0.70
    const val SOFT_REFRESH_NOVELTY_QUOTA = 0.40
    const val DISCOVERY_CACHE_TTL_MS = 30 * 60 * 1000L
    const val TAG_SECTION_CACHE_TTL_MS = 90 * 60 * 1000L
    const val SEEN_LOG_TTL_MS = 24 * 60 * 60 * 1000L

    // Debug log (unchanged)
    const val DEBUG_LOG_MAX_ENTRIES = 500
}
```

---

## Revised Appendix B — Corrected Alias Map

The original alias map incorrectly collapsed `milf` into `mother`. These are distinct canonical tags. The alias map must reflect real-world source naming variants for the same concept, not editorial decisions about what two concepts "mean."

**Rule for alias map entries:**
- Same concept, different formatting/spelling → same canonical key
- Different concept (even if related) → different canonical key

### Corrected alias format

The alias map groups variants around a canonical key. The canonical key is the simplest/most common form of the tag. Raw variants that should map to it are listed under it.

```
# ── CANONICAL: mother ──────────────────────────────────────────
mother ♀        → mother
mother♀         → mother
mom             → mother
stepmom         → mother
step-mom        → mother
step mom        → mother
stepmother      → mother
step-mother     → mother
step mother     → mother

# ── CANONICAL: milf ────────────────────────────────────────────
milf ♀          → milf
milf♀           → milf
milfs           → milf
milves          → milf
m.i.l.f         → milf
m.i.l.f.        → milf

# ── CANONICAL: romance ─────────────────────────────────────────
romance ♥       → romance
romance♥        → romance
romcom          → romance          # romcom is a subgenre, collapses here
rom-com         → romance
romantic comedy → romance
love story      → romance
love stories    → romance

# ── CANONICAL: action ──────────────────────────────────────────
action ⚔        → action
action⚔         → action

# ── CANONICAL: fantasy ─────────────────────────────────────────
fantasy ✦       → fantasy
fantasy✦        → fantasy
high fantasy    → fantasy
dark fantasy    → fantasy          # debatable — adjust if users disagree

# ── CANONICAL: isekai ──────────────────────────────────────────
isekai          → isekai           # identity entry, prevents stemming accidents
reincarnation   → isekai
transferred to another world → isekai
transported to another world → isekai
summoned to another world    → isekai
tensei          → isekai

# ── CANONICAL: science fiction ─────────────────────────────────
sci fi          → science fiction
sci-fi          → science fiction
scifi           → science fiction
sf              → science fiction  # only if source consistently uses "SF"

# ── CANONICAL: slice of life ───────────────────────────────────
slice-of-life   → slice of life
slice of life ☀ → slice of life
daily life      → slice of life

# ── CANONICAL: school life ─────────────────────────────────────
school-life     → school life
high school     → school life      # debatable — adjust if too broad
academy         → school life      # debatable

# ── CANONICAL: boys love ───────────────────────────────────────
bl              → boys love
yaoi            → boys love
shounen ai      → boys love
shonen ai       → boys love

# ── CANONICAL: girls love ──────────────────────────────────────
gl              → girls love
yuri            → girls love
shoujo ai       → girls love
shojo ai        → girls love

# ── CANONICAL: shonen ──────────────────────────────────────────
shounen         → shonen

# ── CANONICAL: shojo ───────────────────────────────────────────
shoujo          → shojo

# ── CANONICAL: harem ───────────────────────────────────────────
harem ♥         → harem
harem♥          → harem
harem (male protagonist) → harem

# ── CANONICAL: reverse harem ───────────────────────────────────
reverse-harem   → reverse harem
reverse harem ♥ → reverse harem

# ── CANONICAL: comedy ──────────────────────────────────────────
comedy ☺        → comedy
comedy☺         → comedy
humor           → comedy
humour          → comedy

# ── CANONICAL: horror ──────────────────────────────────────────
horror ☠        → horror
horror☠         → horror

# ── CANONICAL: mystery ─────────────────────────────────────────
mystery ♟       → mystery
detective       → mystery          # debatable — adjust if distinct in your library

# ── CANONICAL: supernatural ────────────────────────────────────
super natural   → supernatural
super-natural   → supernatural

# ── CANONICAL: psychological ───────────────────────────────────
psycho          → psychological    # only if source uses "Psycho" as abbreviation
psych           → psychological

# ── CANONICAL: mature ──────────────────────────────────────────
mature ♀        → mature
mature♀         → mature
adult           → mature           # note: if your sources use adult/mature differently, split these

# ── CANONICAL: drama ───────────────────────────────────────────
drama ♟         → drama
drama♟          → drama

# ── CANONICAL: martial arts ────────────────────────────────────
martialarts     → martial arts
martial-arts    → martial arts
kung fu         → martial arts     # debatable

# ── CANONICAL: game ────────────────────────────────────────────
video game      → game
video games     → game
gaming          → game

# ── CANONICAL: villainess ──────────────────────────────────────
villain         → villainess       # many sources tag these the same way
villainess      → villainess       # identity

# ── CANONICAL: office ──────────────────────────────────────────
office romance  → office
office lady     → office
salaryman       → office
```

### How to add new entries

When the debug log shows an `ITEM_FILTERED` reason of `"tag 'X ♀' not matching blacklist entry 'X'"` or when a tag section returns zero results that you expected, that is the signal to add a new alias entry. Add it to the `tag_alias` table directly. No app update is needed. The canonicalizer reads from the table at runtime.

### Entries deliberately NOT in the alias map

These are distinct enough that collapsing them would cause wrong results:

- `romance` ≠ `erotica` (different content level)
- `mother` ≠ `milf` (different canonical tags — the content categories differ even if they overlap)
- `action` ≠ `adventure` (distinct enough in practice)
- `fantasy` ≠ `isekai` (isekai is a subgenre but many non-isekai fantasy works exist)
- `psychological` ≠ `horror` (often co-occur but are separate)
- `mature` ≠ `explicit` / `adult content` (maturity rating vs content type)

---

## Implementation Phase Changes

Only Phase 2 changes significantly. All other phases from the original plan are unchanged.

### Phase 2 (revised) — Section planner + progressive loading

Replace the original Phase 2 scope with:

1. Implement simplified `SectionPlanner.plan()` — sort all non-blacklisted tags by affinity, no quotas.
2. Add `plannedSections: List<PlannedSection>` and `nextBatchStartIndex: Int` to `SuggestionsState`.
3. Implement `loadNextSectionBatch()` in `SuggestionsPresenter`:
   - Takes the next `SECTION_BATCH_SIZE` items from `plannedSections` starting at `nextBatchStartIndex`.
   - Fetches them concurrently.
   - Appends each to `sections` as it completes (not after all complete).
   - Advances `nextBatchStartIndex` when batch is done.
4. Add scroll trigger in `SuggestionsScreen` using `ReportLoadMoreState` pattern (already exists in codebase) but key it off section count, not item count.
5. Add batch loading footer (spinner / end message).
6. Unit tests: planner ordering, batch boundary behaviour, scroll trigger threshold.

---

## Summary of Changes from v1

| Area | v1 Plan | v2 Plan |
|---|---|---|
| Section count | Capped at ~5–7 via quotas | All tags shown, no cap |
| Section ordering | Pinned → Guaranteed → Rotating | Pinned → all MANAGED by score descending |
| Cooldown system | Yes, 24h per rotating tag | Removed entirely |
| GUARANTEED / ROTATING types | Yes | Removed; only PINNED / MANAGED |
| Loading model | All planned sections fetched at once | Batches of 5, fetched on scroll |
| Network calls | All concurrent at refresh | Concurrent within batch, idle between batches |
| `MAX_PER_SOURCE_FEED` | 8 globally | Removed (irrelevant with many sections) |
| Alias map: milf → mother | Yes (wrong) | No; milf → milf, mother → mother (corrected) |
| Alias map: mom → mother | Yes | Yes (kept) |
