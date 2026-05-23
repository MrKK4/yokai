# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

```bash
# Full debug build
./gradlew assembleDebug

# Dev flavor (faster iteration)
./gradlew assembleDevDebug

# Run all unit tests
./gradlew test

# Run tests for a single module
./gradlew :app:test
./gradlew :domain:test

# Lint / format check (ktlint)
./gradlew ktlintCheck

# Format (auto-fix)
./gradlew ktlintFormat
```

Application ID is `eu.kanade.tachiyomi` (backward-compatible with Tachiyomi backups). Build type suffixes: `.debugYokai` (debug), `.nightlyYokai` (nightly). Do not change the base application ID.

## Module Structure

```
app/        — Main module: UI controllers, presenters, DI wiring, workers
domain/     — Pure Kotlin: repository interfaces, use cases, domain models
              src/commonMain/kotlin/yokai/domain/*  (new yokai namespace)
              src/commonMain/kotlin/eu/kanade/tachiyomi/domain/*  (legacy)
data/       — Repository impls, SQLDelight schemas, DB migrations
              src/commonMain/sqldelight/tachiyomi/data/*.sq
core/       — Shared utilities (eu.kanade.tachiyomi.util.*)
source/     — Extension API contract (eu.kanade.tachiyomi.source.*)
presentation/ — Shared Compose components (yokai.presentation.*)
i18n/       — Moko resources translations
```

## DI: Two Systems Coexist

**Koin** (primary) — defined in `app/src/main/java/yokai/core/di/`:
- `AppModule.kt` — infrastructure singletons (DB, network, repositories)
- `DomainModule.kt` — domain use cases (factory) and domain services (single)
- `PreferenceModule.kt` — all preference classes

**Injekt** (secondary) — used in Conductor controllers, Workers, utility classes via `Injekt.get<T>()` / `by injectLazy()`.

Rule: new controllers/presenters → Injekt. New domain services → Koin DomainModule. New repository impls → Koin AppModule.

## Controller Architecture

All root-tab screens extend:
```kotlin
class XxxController : BaseCoroutineController<XxxBinding, XxxPresenter>(),
    RootSearchInterface, FloatingSearchInterface
```
`BaseCoroutineController` → `BaseLegacyController` → Conductor `Controller`. Presenter lifecycle is tied to the controller via `BaseCoroutinePresenter<T>` which holds a `presenterScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`.

`SuggestionsController` uses a `sharedPresenter` (companion object lazy singleton) so the presenter survives tab switches. Other tab controllers create a new presenter each time.

## Suggestions V2 Pipeline (Critical Path)

All manga fetching flows through this sequence — never bypass it:

```
InterestProfileBuilder → SectionPlanner → SectionBatcher
    → CandidateRetriever → SuggestionRanker → DB → UI
```

All files live in `app/src/main/java/yokai/domain/suggestions/`. Constants in `SuggestionsConfig.kt`. The pipeline is orchestrated by `SuggestionsPresenter.refreshV2()` (foreground) and `SuggestionsWorker.doV2Work()` (background, 12h periodic). Both paths use `SuggestionsRefreshCoordinator.withLock/tryRun` to serialize concurrent refresh attempts.

A V1 pipeline (via `FeedAggregator`) coexists behind `preferences.suggestionsV2Enabled()`. V2 is the canonical system. The V1 path shares the same presenter and worker entry points gated by the V2 toggle.

`CandidateRetriever` caps parallel source requests at `SuggestionsConfig.MAX_CONCURRENT_SOURCE_REQUESTS` (Semaphore). Never bypass this cap.

## Tag Canonicalization

`TagCanonicalizer` normalizes raw genre strings via a five-tier lookup:
1. DB alias (source-specific or global, `tag_alias` table)
2. `TagRegistry.find(rawKey)` — exact/prefix/regex match against 95+ canonical tags
3. Depluralize against known tags

**Do not add hardcoded aliases to `ensureDefaultAliases()`** — it now seeds from `TagRegistry.defaultAliases`. Add new tag patterns to `TagRegistry.kt` instead.

## Database

SQLDelight `.sq` files in `data/src/commonMain/sqldelight/tachiyomi/data/`. To add a table: create `.sq` → add repository interface in `domain/` → add impl in `data/` → register in Koin → add migration to `Migrator`.

Suggestions-specific tables: `suggestions`, `tag_profile`, `tag_alias`, `tag_variant`, `suggestion_planned_section`, `suggestion_seen_log`, `shown_manga_history`.

## Compose Integration Pattern

Full-Compose screens (settings, about): use `YokaiScaffold` + `YokaiTheme`.

Suggestions tab: Compose-in-Binding hybrid — XML `SwipeRefreshLayout` wraps a `ComposeView`. `ComposeAppBarScrollProxy` bridges Compose scroll state to the XML AppBar. Do **not** replace this with a standalone `YokaiScaffold` screen.

All Compose content must be wrapped in `YokaiTheme { }` — it provides the M3 color scheme auto-generated from the current XML theme.

## Navigation

- Push screen: `router.pushController(XController(...).withFadeTransaction())`
- Switch tab: only `MainActivity.setRoot()` does this — controllers never call it
- Global search: push `GlobalSearchController(query).withFadeTransaction()`
- When navigating away while a Compose sheet is open: call `presenter.suppressExpandSheet()` before pushing, `presenter.restoreExpandSheet()` in `onChangeStarted` when returning

## State Management

`SuggestionsPresenter` holds a single `StateFlow<SuggestionsState>` — extend it, never add parallel StateFlows. `feedGeneration: AtomicLong` is incremented on each refresh and checked before committing any results to prevent stale data from in-flight operations overwriting newer state.

## Dependency Versions

Managed in `gradle/*.versions.toml` (four files: `androidx`, `compose`, `kotlinx`, `libs`). New dependencies go in the appropriate toml, not hardcoded in build scripts.
