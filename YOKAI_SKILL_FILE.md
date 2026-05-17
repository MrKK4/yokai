# Yokai Project — AI Skill File

## Purpose

This file is the **single source of architectural truth** for any AI/LLM working on this project. Load it at session start. It tells you:

1. **What exists** — every reusable component, pattern, and utility
2. **What must be preserved** — non-negotiable structural invariants
3. **What to reuse** — decision trees for common tasks
4. **What traps to avoid** — past AI mistakes encoded as rules

---

## QUICK REFERENCE: Decision Trees

### I need to add a button to the toolbar

1. Does the tab already have a menu XML? → Add item to existing XML
2. Does the button need custom rendering (like V2 badge)? → Use action view class pattern from `SuggestionsVersionActionView.kt`
3. Is it a sort/filter/more action? → Use `MaterialMenuSheet` in `onOptionsItemSelected`
4. Do NOT create a new menu XML unless it's a completely new screen type

### I need to show a bottom sheet

1. Is the screen Compose-based? → Use Compose `ModalBottomSheet` (see `SuggestionsExpandedSheet.kt`)
2. Is the screen View-based? → Use `E2EBottomSheetDialog` subclass or `MaterialMenuSheet`
3. Is it a simple menu of options? → Use `MaterialMenuSheet` (see `SuggestionsController.showSortSheet()`)

### I need to navigate to a new screen

1. Is it a detail screen (manga, settings, etc.)? → `router.pushController(XController(...).withFadeTransaction())`
2. Is it a tab switch? → Only `MainActivity.setRoot()` does this. Do NOT call `setRoot` from a controller.
3. Is it global search? → Push `GlobalSearchController(query).withFadeTransaction()`

### I need to store data

1. Is it structured, queryable data? → SQLDelight `.sq` file + repository interface + impl
2. Is it a simple key-value preference? → Add `fun xyzPreference()` to `PreferencesHelper.kt`
3. Is it temporary/in-memory state? → Add field to existing StateFlow data class

### I need to fetch manga from sources

1. Go through the V2 pipeline: `InterestProfileBuilder → SectionPlanner → SectionBatcher → CandidateRetriever → SuggestionRanker`
2. Do NOT call source APIs directly from UI or presenter
3. Do NOT bypass `CandidateRetriever`'s concurrency cap (8 parallel source requests)

### I need to add a tag/genre

1. Do NOT add hardcoded aliases to `TagCanonicalizer.ensureDefaultAliases()`
2. Add a `TagPattern` entry to the pattern group registry (per `TAG_REGEX_PLAN.md`)
3. Or add a DB alias via `TagProfileRepository.seedAliases()` for source-specific mappings

---

## SECTION 1: Architecture Overview

```
┌─────────────────────────────────────────────────┐
│  UI Layer                                        │
│  Conductor Controllers + Compose Screens         │
│  Package: eu.kanade.tachiyomi.ui.*              │
│          yokai.presentation.*                    │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────┴────────────────────────────┐
│  Domain Layer (Pure Kotlin, no Android)          │
│  Use cases, models, interfaces                   │
│  Package: yokai.domain.*                         │
│          eu.kanade.tachiyomi.domain.*            │
└────────────────────┬────────────────────────────┘
                     │
┌────────────────────┴────────────────────────────┐
│  Data Layer                                      │
│  Repository impls, SQLDelight, network           │
│  Package: yokai.data.*                           │
│          eu.kanade.tachiyomi.data.*              │
└─────────────────────────────────────────────────┘
```

### Module Map

| Module | Purpose | Key Packages |
|--------|---------|-------------|
| `app/` | Main app, DI, UI controllers, workers | `eu.kanade.tachiyomi.ui.*`, `yokai.presentation.*`, `yokai.core.di.*` |
| `domain/` | Business logic, repository interfaces | `yokai.domain.*` |
| `data/` | Database, preferences, SQLDelight schemas | `yokai.data.*` |
| `core/` | Shared utilities | `eu.kanade.tachiyomi.util.*` |
| `source/` | Extension API (manga source contract) | `eu.kanade.tachiyomi.source.*` |
| `i18n/` | Translations (Moko resources) | `yokai.i18n.*` |
| `presentation/` | Shared Compose components | `yokai.presentation.core.*`, `yokai.presentation.component.*` |

### DI Systems

Two DI systems coexist:

**Koin** (primary): Modules defined in `yokai/core/di/`
- `AppModule.kt` — Application, SqlDriver, Database, NetworkHelper, SourceManager, all suggestion repository implementations, DownloadManager, etc.
- `PreferenceModule.kt` — All preference classes (BasePreferences, UiPreferences, PreferencesHelper, etc.)
- `DomainModule.kt` — All use cases (factory scope), repositories (singleton), domain services (SuggestionsDebugLog, SessionContext, TagCanonicalizer)

**Injekt** (secondary, for classes not instantiated by Koin): Used in Conductor controllers, Workers, and utility classes. `Injekt.get<T>()` and `by injectLazy()`.

Rule: New controllers/presenters → use Injekt. New domain services → register in Koin DomainModule. New repository impls → register in Koin AppModule.

---

## SECTION 2: Controller Hierarchy (Non-Negotiable)

### Base Class Chain

```
Controller (Conductor)
  └── BaseController
        ├── BaseLegacyController<VB>          (ViewBinding, shouldHideLegacyAppBar=false)
        │     └── BaseCoroutineController<VB, PS>  (adds presenter lifecycle)
        │           ├── LibraryController
        │           ├── LibraryComposeController
        │           ├── RecentsController
        │           ├── BrowseController
        │           └── SuggestionsController
        └── BaseComposeController             (Full Compose, shouldHideLegacyAppBar=true)
              └── BaseCoroutineComposeController<PS>  (adds presenter lifecycle)
                    └── Settings controllers, Onboarding, etc.
```

### Root Tab Controller Contract

Every bottom-nav tab controller MUST:

```kotlin
class XxxController(bundle: Bundle? = null)
  : BaseCoroutineController<XxxBinding, XxxPresenter>(bundle),
    RootSearchInterface,       // REQUIRED: wires into global search toolbar
    FloatingSearchInterface {  // REQUIRED: controls floating search bar behavior

    init {
        setHasOptionsMenu(true)  // REQUIRED
    }

    // REQUIRED overrides:
    override fun getTitle(): String? { ... }       // tab title
    override fun getSearchTitle(): String? { ... }  // search bar hint
    override fun createBinding(inflater): Binding { ... }
    override fun onViewCreated(view: View) { ... }
    override fun onCreateOptionsMenu(menu, inflater) { ... }
    override fun onOptionsItemSelected(item): Boolean { ... }
}
```

### Interface Contracts (from MainActivity.kt)

| Interface | Purpose | Required For |
|-----------|---------|-------------|
| `RootSearchInterface` | Tab is a root-level screen; wires search toolbar nav icon | All root tabs |
| `FloatingSearchInterface` | Controls floating/collapsing search toolbar | All root tabs |
| `BottomSheetController` | Tab has a bottom sheet toggled by re-tapping nav icon | Library, Recents, Browse |
| `TabbedInterface` | Tab has sub-tabs (e.g., Recents: All/Updates/History) | Only Recents |
| `BottomNavBarInterface` | Controller can veto tab switches | None currently |
| `SearchControllerInterface` | = FloatingSearchInterface + SmallToolbarInterface | GlobalSearchController, UnifiedSearchController |

### SuggestionsController Specifics

- Uses `sharedPresenter` pattern: companion object lazy singleton. Presenter survives tab switches.
- Compose-in-Binding hybrid: XML `SwipeRefreshLayout` wraps `ComposeView`
- `ComposeAppBarScrollProxy`: fake `ScrollingView` bridging Compose scroll → XML AppBar
- Menu: `R.menu.suggestions` (4 items: search, v2 toggle, sort, more)
- Does NOT implement `BottomSheetController` (sheets are Compose `ModalBottomSheet`, not View-based)

---

## SECTION 3: Component Catalog — What Exists, What to Reuse

### 3A. Compose Components (yokai.presentation.*)

| Component | File | What It Is | When to Use |
|-----------|------|-----------|-------------|
| `YokaiScaffold` | `yokai/presentation/Scaffold.kt` | M3 Scaffold with SMALL/LARGE/NONE app bar | Full-Compose screens (settings, about, extension repos). NOT for suggestions (uses Compose-in-Binding) |
| `YokaiTheme` | `yokai/presentation/theme/Theme.kt` | M3 theme wrapper (auto-generates colors from XML) | Wrap ALL Compose content |
| `JayTopAppBar` | `yokai/presentation/core/JayAppBar.kt` | Small top app bar | YokaiScaffold with SMALL type |
| `JayExpandedTopAppBar` | `yokai/presentation/core/JayAppBar.kt` | Large expanding top app bar | YokaiScaffold with LARGE type |
| `SuggestionItem` | `yokai/presentation/suggestions/SuggestionsScreen.kt` | Manga card: 2:3 ElevatedCard, Coil AsyncImage, gradient overlay, title | Any manga grid display |
| `SuggestionsFilterSheet` | `yokai/presentation/suggestions/SuggestionsFilterSheet.kt` | ModalBottomSheet with searchable checkbox list | Tag/content filtering |
| `SuggestionsExpandedSheet` | `yokai/presentation/suggestions/SuggestionsExpandedSheet.kt` | ModalBottomSheet with manga grid | "See all" for any section |
| `LibraryContent` | `yokai/presentation/library/LibraryContent.kt` | Grid with YokaiScaffold (AppBarType.NONE) | Library tab (placeholder, work-in-progress) |
| `ToolTipButton` | `yokai/presentation/component/ToolTipButton.kt` | Icon button with tooltip | Navigation icons, action buttons |

### 3B. View-Based Components

| Component | File | What It Is | When to Use |
|-----------|------|-----------|-------------|
| `MaterialMenuSheet` | `eu.kanade.tachiyomi.ui.base.MaterialMenuSheet` | Bottom sheet dialog with menu items (icon, text, end check) | Sort/filter/option menus in View-based controllers |
| `E2EBottomSheetDialog<VB>` | `eu.kanade.tachiyomi.widget.E2EBottomSheetDialog` | Base for View-based bottom sheets | Complex sheets (filters, tracking, downloads) |
| `SuggestionsVersionActionView` | `eu.kanade.tachiyomi.ui.suggestions.SuggestionsVersionActionView` | Custom toolbar action view (circular badge) | Toggle indicators in toolbar |

### 3C. Menu XML Files

| Menu File | Used By | Items |
|-----------|---------|-------|
| `R.menu.suggestions` | SuggestionsController | `action_search`, `action_toggle_v2`, `action_sort`, `action_more` |
| `R.menu.library` | LibraryController | `action_search`, `action_filter`, `action_more` |
| `R.menu.recents` | RecentsController | `action_search`, `display_options`, `action_more` |
| `R.menu.catalogue_main` | BrowseController | `action_search`, `action_filter`, `action_more` |
| `R.menu.bottom_navigation` | MainActivity | `nav_library`, `nav_recents`, `nav_browse`, `nav_suggestions` |

### 3D. Icon Drawables (Frequently Needed)

Navigation: `ic_arrow_back_24dp`, `ic_close_24dp`, `ic_chevron_right_24dp`, `ic_expand_more_24dp`, `ic_expand_less_24dp`
Actions: `ic_search_24dp`, `ic_more_vert_24dp`, `ic_filter_list_24dp`, `ic_sort_24dp`, `ic_check_24dp`, `ic_delete_24dp`, `ic_edit_24dp`, `ic_share_24dp`, `ic_refresh_24dp`
Tabs: `ic_suggestions_selector` (filled/outline selector), `ic_library_selector`, `ic_recents_selector`, `ic_browse_selector`
Tags: `ic_label_24dp`, `ic_label_outline_24dp`, `ic_bookmark_24dp`, `ic_bookmark_border_24dp`
Reader states: `ic_read_24dp`, `ic_unread_24dp`, `ic_history_24dp`

### 3E. Theme System

The app has 10+ themes (Base, Amoled, FlatLime, MidnightDusk, SapphireDusk, Lavender, Strawberries, Tako, Yotsuba, YinYang, Doki, Monet).

**How to use colors in Compose:**
- `MaterialTheme.colorScheme.primary` — main brand color
- `MaterialTheme.colorScheme.onPrimary` — text on primary
- `MaterialTheme.colorScheme.surface` — card/surface backgrounds
- `MaterialTheme.colorScheme.onSurface` — text on surface
- `MaterialTheme.colorScheme.onSurfaceVariant` — secondary text
- `MaterialTheme.colorScheme.background` — screen background
- `MaterialTheme.colorScheme.onBackground` — text on background

**How to use colors in XML:**
- `?attr.colorPrimary`, `?attr.colorOnPrimary`, `?attr.colorSurface`, `?attr.colorOnSurface`
- `?attr.colorSurfaceVariant`, `?attr.colorOnSurfaceVariant`

**Typography (Compose):**
- Use M3 defaults: `MaterialTheme.typography.titleLarge`, `.titleMedium`, `.bodyMedium`, `.bodyLarge`
- Custom extension: `MaterialTheme.typography.header` = `bodyMedium` + `onSurfaceVariant` color + SemiBold weight

**Spacing constants:** `yokai.presentation.theme.Constants.Size`: none(0), extraExtraTiny(1), extraTiny(2), tiny(4), small(8), smedium(12), medium(16), large(24), extraLarge(32), huge(48), extraHuge(56), navBarSize(68)

### 3F. Extension Functions (Key Utilities)

**From `ControllerExtensions.kt`:**
- `withFadeTransaction()` — standard navigation animation (Fade/CrossFade)
- `withFadeInTransaction()` — root-set animation (FadeIn + OneWayFade)
- `scrollViewWith()` — wires RecyclerView to AppBar scroll behavior
- `setOnQueryTextChangeListener(..., global=true)` — global search push; `global=false` = local filter

**From `ViewExtensions.kt`:**
- `view.snack(message, length)` — show Snackbar (handles AMOLED theme colors)
- `view.doOnApplyWindowInsetsCompat { }` — window inset handling
- `view.isCompose` — check if view is ComposeView

---

## SECTION 4: Suggestions V2 Pipeline (The Sacred Order)

### Data Flow

```
User refresh / worker trigger
  │
  ▼
SuggestionsPresenter.refresh(hardRefresh)
  ├─ Hard: delete all → full rebuild
  └─ Soft: rebuild profile → re-rank existing → fetch stale
  │
  ▼
InterestProfileBuilder.buildProfile(now)
  ├─ Iterate library manga
  ├─ TagCanonicalizer.canonicalize() each genre
  ├─ InteractionClassifier.classify() reading behavior
  ├─ EMA-based affinity scores
  └─ Upsert TagProfiles into tag_profile table
  │
  ▼
SectionPlanner.plan(profiles, sortOrder, now)
  ├─ DISCOVERY section first (always)
  ├─ PINNED_TAG sections (sorted by pinnedAt)
  └─ MANAGED_TAG sections (sorted by affinity desc)
  │
  ▼
SectionBatcher.nextBatch(sections, startIndex, batchSize=2)
  │
  ▼
CandidateRetriever.retrieve(batchSections, pageOffset)
  ├─ Cap: 8 concurrent source requests
  ├─ Cold-start path: shuffled sources, chunked over 2 pages
  ├─ Normal path: per-source fetch + source-rotation backfill
  ├─ Tag sections: 3-phase strategy
  │   1. injectGenreFilter() — programmatic checkbox in source filter
  │   2. getExactTermForSource() — DB vocabulary lookup
  │   3. Raw canonical tag as search query
  └─ learnVocabulary() — records raw genre strings into tag_alias
  │
  ▼
SuggestionRanker.rankWithContext(results, context, seenKeys)
  ├─ Score: tagAffinity×0.50 + freshness×0.20 + sessionBoost×0.15 + velocity + noise×0.05 - sourcePenalty - positionPenalty
  ├─ Filter: in library, globally seen, section-seen, blacklisted tag, title duplicate
  ├─ Dedup by title (keep best scored)
  ├─ Adaptive per-source caps (1src=12, 2src=5, 3src=4, 4+=3)
  └─ Cap to MAX_RESULTS_PER_SECTION (12, or COLD_START_MAX_RESULTS=500)
  │
  ▼
Insert into suggestions table by section key → UI observes Flow
```

### Key Constants (SuggestionsConfig.kt)

| Constant | Value | Purpose |
|----------|-------|---------|
| `MAX_RESULTS_PER_SECTION` | 12 | Target manga per section |
| `MIN_RESULTS_PER_SECTION` | 10 | Relaxed threshold before source-rotation backfill |
| `SECTION_BATCH_SIZE` | 2 | Sections loaded per batch |
| `LOAD_MORE_SECTION_THRESHOLD` | 1 | Trigger next batch when within N sections of visible end |
| `MAX_PER_SOURCE_FETCH` | 12 | Max manga per source per request |
| `MAX_CANDIDATES_PER_SECTION` | 50 | Max candidates to rank per section |
| `COLD_START_HISTORY_THRESHOLD` | 12 | Min history signals before cold-start mode |
| `HARD_REFRESH_NOVELTY_QUOTA` | 0.70 | Fraction must be unseen (hard refresh) |
| `SOFT_REFRESH_NOVELTY_QUOTA` | 0.40 | Fraction must be unseen (soft refresh) |
| `STM_HALF_LIFE_DAYS` | 7 | Short-term memory decay |
| `LTM_ALPHA` | 0.1 | Long-term memory EMA factor |

### Section Structure

1. **DISCOVERY section**: "Latest from your sources" or "Popular from your sources" based on sort order. Always first.
2. **PINNED_TAG sections**: User-pinned tags, sorted by `pinnedAt` timestamp
3. **MANAGED_TAG sections**: Tags ordered by affinity score (how many of user's manga contain this tag)

Each section targets 12 manga. Sources divide equally: 12 sources = 1 each. Failed sources cycle back to fill 12.

### Refresh Mechanism

Each refresh randomizes `pageOffset` (1-7). This shuffles results naturally — page 2 becomes "page 1 data" next time. `feedGeneration` atomic counter cancels stale in-flight work.

---

## SECTION 5: Tag Canonicalization System

### Current State

`TagCanonicalizer.kt` has 175 hardcoded aliases in `ensureDefaultAliases()`. This is being REPLACED by the regex system.

### Target State (TAG_REGEX_PLAN.md)

95 canonical tags in 12 pattern groups. Five-tier match order:

1. **DB alias** (source-specific or global) — exact, fastest
2. **Exact match** from pattern `exact` set — HashSet O(1)
3. **Prefix match** — `rawKey.startsWith(prefix)`
4. **Regex match** — iterate compiled patterns
5. **Depluralize** — strip trailing 's', check if key exists

### Normalization Pipeline

```kotlin
NFKC normalize → strip decorative symbols (♀♂⚥★☆♡♥•·) →
strip parenthesized/bracketed count suffixes →
replace slashes/underscores with spaces →
trim punctuation → collapse whitespace → lowercase
```

### Self-Learning

`CandidateRetriever.learnVocabulary()` records raw genre strings from fetched results into `tag_alias` table. Coexists with regex matching — DB aliases take priority over regex.

### Rule

**Do NOT add hardcoded aliases to `ensureDefaultAliases()`.** Implement pattern groups instead. The flat list is deprecated.

---

## SECTION 6: Navigation Patterns

### Tab Switching

Only `MainActivity.setRoot()` switches tabs. It uses `router.setRoot(X.withFadeInTransaction().tag(navId))`. Controllers do NOT call `setRoot`.

### Screen Pushing

Always: `router.pushController(XController(...).withFadeTransaction())`

### Global Search

```kotlin
private fun performGlobalSearch(query: String) {
    router.pushController(GlobalSearchController(query).withFadeTransaction())
}
```

Wired in `onCreateOptionsMenu` via `setOnQueryTextChangeListener(searchView, true) { ... }`.

### Local Search

`RecentsController` uses `setOnQueryTextChangeListener(searchView, false)` — filters items in-place without pushing a new controller.

### Back Handling

- System back: `MainActivity.onBackPressedCallback` → checks IME, action mode, search → `backPress()` → `router.handleBack()`
- Controllers can override `handleBack(): Boolean` for internal state (dismissing sheets, resetting tabs)
- Compose: `BackHandler(enabled, onBack)` — used for onboarding, webview history

### Sheet Back Coordination (Critical for Suggestions)

When pushing a controller while a Compose sheet is open:

```kotlin
// BEFORE navigating:
presenter.suppressExpandSheet()

// Navigate:
router.pushController(MangaDetailsController(manga, true).withFadeTransaction())

// AFTER returning (in onChangeStarted):
if (type.isEnter) {
    presenter.restoreExpandSheet()
}
```

If navigation fails, restore: `presenter.restoreExpandSheet()`.

---

## SECTION 7: Presenter Pattern

### BaseCoroutinePresenter

```kotlin
open class BaseCoroutinePresenter<T> {
    var presenterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var weakView: WeakReference<T>? = null

    open fun onCreate() {}
    open fun onDestroy() { presenterScope.cancel() }
}
```

Key rules:
- `SupervisorJob` — child failures don't cancel siblings
- `Dispatchers.Default` — background by default
- `WeakReference` — presenter doesn't prevent GC of controller

### SuggestionsPresenter Specifics

- Single `StateFlow<SuggestionsState>` — the ONE source of truth
- `feedGeneration: AtomicLong` — incremented each refresh, checked before committing results (prevents stale data)
- `sortSnapshotCache` — enables instant sort switching without re-fetch
- `sortDebounceJob` — 300ms debounce on sort changes
- `scrollPosition` — `gridFirstVisibleItemIndex`, `gridFirstVisibleItemScrollOffset`
- Refresh serialized via `SuggestionsRefreshCoordinator.withLock()` (Mutex)

### sharedPresenter Pattern

```kotlin
private companion object {
    val sharedPresenter by lazy {
        SuggestionsPresenter(context = Injekt.get<Application>())
    }
}
override val presenter: SuggestionsPresenter = sharedPresenter
```

Used only in `SuggestionsController`. Presenter survives controller destruction/recreation on tab switches.

---

## SECTION 8: Database & Persistence

### SQLDelight Tables

| Table | File | Key Columns |
|-------|------|-------------|
| `suggestions` | `suggestions.sq` | source, url, title, thumbnail_url, reason, relevance_score, display_rank |
| `tag_profile` | `tag_profile.sq` | canonical_tag (PK), display_name, long_term_count, recent_count, velocity, state, pinned_at |
| `tag_alias` | `tag_alias.sq` | raw_key, source_key (PK), canonical_tag, raw_tag, source_id |
| `tag_variant` | `tag_variant.sq` | canonical_tag, raw_tag (PK), seen_count |
| `suggestion_planned_section` | `suggestion_planned_section.sq` | section_key (PK), section_rank, section_type, canonical_tag, display_reason |
| `suggestion_seen_log` | `suggestion_seen_log.sq` | section_key, manga_key (PK), shown_at, refresh_id |
| `shown_manga_history` | `shown_manga_history.sq` | source, url (PK), shown_at |

### Adding New Data

1. **Add SQLDelight table**: Create `.sq` file in `data/src/commonMain/sqldelight/tachiyomi/data/`
2. **Add repository interface**: In `yokai/domain/` package
3. **Add repository impl**: In `yokai/data/` package, constructor takes `DatabaseHandler`
4. **Register in Koin**: Add to `AppModule.kt` (if singleton) or `DomainModule.kt` (if factory)
5. **Migration**: The `Migrator` handles version-based migrations. Add migration logic if schema changes.

### Preferences

All preferences go through `PreferencesHelper.kt`. Naming convention:
- Suggestions: `suggestionsXxxYyy()`
- Library: `libraryXxxYyy()` or `filterXxx()`
- Reader: `readerXxx()`, `xxxReader()`
- Download: `downloadXxx()`

---

## SECTION 9: Background Workers

All extend `CoroutineWorker`:

| Worker | Trigger | Purpose |
|--------|---------|---------|
| `SuggestionsWorker` | Periodic (12h) / manual | V2: builds profile, plans sections, fetches, ranks. 3 retries. |
| `LibraryUpdateJob` | Periodic / manual | Chapter updates, details, tracking |
| `DownloadJob` | On download start | Manages download queue |
| `ExtensionUpdateJob` | Periodic (12h) | Checks for extension updates |
| `AppUpdateJob` | Periodic (2 days) | Checks for app updates |

---

## SECTION 10: Anti-Patterns (AI Traps Encoded as Rules)

### R1: Don't create a parallel controller/presenter/screen
If a feature belongs to an existing tab, extend the existing controller/presenter/screen. Do not create `XxxV2Controller` or `NewXxxScreen`.

### R2: Don't change the controller base class or interface contracts
Every root tab extends `BaseCoroutineController` + `RootSearchInterface` + `FloatingSearchInterface`. This is non-negotiable. The `MainActivity` depends on it.

### R3: Don't add hardcoded tag aliases
Use the regex pattern system from `TAG_REGEX_PLAN.md`. The `ensureDefaultAliases()` list is deprecated.

### R4: Don't create standalone Compose screens with their own YokaiScaffold for suggestions
Suggestions uses Compose-in-Binding hybrid (XML SwipeRefreshLayout + embedded ComposeView). Full `YokaiScaffold` screens are for settings/about/onboarding.

### R5: Don't inline magic numbers
Every constant belongs in `SuggestionsConfig.kt` for suggestions logic, or the relevant config/preferences file for other features.

### R6: Don't bypass the V2 pipeline
All manga fetching goes through: `InterestProfileBuilder → SectionPlanner → SectionBatcher → CandidateRetriever → SuggestionRanker`. No direct source API calls from UI.

### R7: Don't create duplicate menu XML
Extend the existing tab menu XML or add items in code via `onCreateOptionsMenu`.

### R8: Don't add new parallel state flows
`SuggestionsPresenter` has a single `SuggestionsState` StateFlow. Extend it, don't create `XxxState` alongside it.

### R9: Don't forget sheet suppression before navigation
When a Compose sheet is open and you push a new controller, suppress the sheet first, restore on return. See Section 6.

### R10: Don't bypass the concurrency cap
`CandidateRetriever` caps parallel source requests at 8. Respect this.

### R11: Don't change icon assignments without checking cross-tab consistency
Each tab has distinct icons. Suggestions uses `ic_suggestions_selector`. Library uses `ic_library_selector`. Don't repurpose one tab's icon for another.

### R12: Don't add preferences with inconsistent names
Follow the `suggestionsXxxYyy()` naming convention for suggestions prefs. Use existing patterns in `PreferencesHelper.kt`.

### R13: Don't create a new bottom sheet framework
Two systems exist: `MaterialMenuSheet` (View-based, for menus) and Compose `ModalBottomSheet` (for content-heavy sheets). Use one of these.

### R14: Don't wrap Compose content without YokaiTheme
`YokaiTheme { }` is required for all Compose content. It provides the M3 color scheme auto-generated from the current XML theme.

---

## SECTION 11: Key File Index

### Suggestions V2
- `app/src/main/java/eu/kanade/tachiyomi/ui/suggestions/SuggestionsController.kt`
- `app/src/main/java/eu/kanade/tachiyomi/ui/suggestions/SuggestionsPresenter.kt`
- `app/src/main/java/yokai/presentation/suggestions/SuggestionsScreen.kt`
- `app/src/main/java/yokai/presentation/suggestions/SuggestionsExpandedSheet.kt`
- `app/src/main/java/yokai/presentation/suggestions/SuggestionsFilterSheet.kt`
- `app/src/main/java/yokai/domain/suggestions/SuggestionsConfig.kt`
- `app/src/main/java/yokai/domain/suggestions/SectionPlanner.kt`
- `app/src/main/java/yokai/domain/suggestions/SectionBatcher.kt`
- `app/src/main/java/yokai/domain/suggestions/CandidateRetriever.kt`
- `app/src/main/java/yokai/domain/suggestions/SuggestionRanker.kt`
- `app/src/main/java/yokai/domain/suggestions/TagCanonicalizer.kt`
- `app/src/main/java/yokai/domain/suggestions/InterestProfileBuilder.kt`
- `app/src/main/java/yokai/domain/suggestions/InteractionClassifier.kt`
- `app/src/main/java/yokai/domain/suggestions/SessionContext.kt`
- `app/src/main/java/yokai/domain/suggestions/FeedAggregator.kt`
- `app/src/main/java/yokai/domain/suggestions/SuggestionsRefreshCoordinator.kt`
- `app/src/main/java/eu/kanade/tachiyomi/data/suggestions/SuggestionsWorker.kt`

### UI Patterns (Cross-Tab Reference)
- `app/src/main/java/eu/kanade/tachiyomi/ui/main/MainActivity.kt` — interfaces, tab switching, search
- `app/src/main/java/eu/kanade/tachiyomi/ui/library/compose/LibraryComposeController.kt` — Compose-in-Binding reference
- `app/src/main/java/eu/kanade/tachiyomi/ui/source/BrowseController.kt` — global search + menu reference
- `app/src/main/java/yokai/presentation/Scaffold.kt` — YokaiScaffold
- `app/src/main/java/yokai/presentation/theme/Theme.kt` — YokaiTheme

### DI
- `app/src/main/java/yokai/core/di/AppModule.kt`
- `app/src/main/java/yokai/core/di/PreferenceModule.kt`
- `app/src/main/java/yokai/core/di/DomainModule.kt`

### Database
- `data/src/commonMain/sqldelight/tachiyomi/data/suggestions.sq`
- `data/src/commonMain/sqldelight/tachiyomi/data/tag_profile.sq`
- `data/src/commonMain/sqldelight/tachiyomi/data/tag_alias.sq`
- `data/src/commonMain/sqldelight/tachiyomi/data/tag_variant.sq`

### Plans/Docs
- `TAG_REGEX_PLAN.md` — unimplemented regex canonicalization plan
- `SUGGESTIONS_V2_STRUCTURAL_CONTRACT.md` — detailed structural contract (companion doc)

---

## SECTION 12: Session Startup Prompt

Copy this into every new AI session:

```
Project: Yokai (Tachiyomi fork) — Android manga reader
Tech: Kotlin, Jetpack Compose + ViewBinding hybrid, Conductor controllers, Koin + Injekt DI,
SQLDelight, Coroutines/Flow, WorkManager

CRITICAL: Read SUGGESTIONS_V2_STRUCTURAL_CONTRACT.md for the suggestions architecture.
Read the SKILL_FILE (this document) for the complete component catalog and anti-patterns.

Key invariants:
- Controllers: BaseCoroutineController + RootSearchInterface + FloatingSearchInterface
- Pipeline: ProfileBuilder → Planner → Batcher → Retriever → Ranker
- State: Single StateFlow in Presenter. Extend, don't duplicate.
- Tags: Regex system (TAG_REGEX_PLAN.md), NOT hardcoded aliases.
- Config: ALL constants in SuggestionsConfig.kt
- UI: Compose-in-Binding for suggestions. YokaiTheme wrapper required.
- Navigation: router.pushController(X.withFadeTransaction())
- Sheets: Compose ModalBottomSheet for suggestions, MaterialMenuSheet for menus
- Colors: MaterialTheme.colorScheme.* from YokaiTheme (auto-generated from XML theme)

Before writing code:
1. Check what already exists in the SKILL FILE catalog
2. Check if the change affects cross-tab consistency
3. Follow the "Decision Trees" section for common tasks
4. Avoid every item in "Anti-Patterns" section
```
