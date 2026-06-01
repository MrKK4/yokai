# Yokai AI Agent Instructions

This document provides context and conventions for AI agents working on the Yokai Android project.

## Codebase Context
Yokai is a free and open-source manga reader for Android, using Kotlin, Jetpack Compose, and AndroidX. It implements a multi-module architecture:
- `app/`: Main application module, DI setup, and app-level Android configurations.
- `presentation/`: UI layer, containing Compose components and ViewBindings.
- `domain/`: Domain layer, encompassing business logic and models.
- `data/`: Data layer, handling database (e.g. Room), preferences, and network operations (Retrofit/Chucker).
- `core/`: Shared utilities across the application.
- `source/`: Core API for extensions (e.g., manga sources).
- `i18n/`: Translation module.

## Core Technology Stack
- **Language:** Kotlin
- **UI:** Jetpack Compose & Android ViewBinding (Legacy/transitional).
- **Reactive/Concurrency:** RxJava, RxRelay, Kotlin Coroutines, and Flow.
- **Build System:** Gradle Kotlin DSL (`build.gradle.kts`).
- **Dependencies Management:** Version Catalogs (`gradle/*.versions.toml`).

## Useful Commands
- **Build full debug APK:** `./gradlew assembleDebug`
- **Build dev flavor debug APK:** `./gradlew assembleDevDebug`
- **Test:** `./gradlew test`
- **Lint/Format:** Uses ktlint styling (see `ktlintCodeStyle.xml`).

## Project Conventions
1. **Module Cleanliness:** Never bypass the module architecture separating `presentation`, `domain`, and `data`.
2. **UI Guidelines:** Prefer Jetpack Compose over Android Views for new features unless modifying existing ViewBinding layouts. 
3. **References:**
   - Refer to [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md) for contribution guidelines.
   - Refer to [i18n/README.md](i18n/README.md) before making translation changes.

## Development Pitfalls
- Application ID defaults `eu.kanade.tachiyomi`, ensuring backward compatibility for backups with older forks.
- Ensure any added dependencies are organized into the appropriate `gradle/*.versions.toml` file.
- The `app/build.gradle.kts` uses different application ID suffixes per build type (`.debugYokai`, `.nightlyYokai`).

## Tools & Testing
Use `Chucker` (network inspector) which is available natively in debug builds for networking issues.

## Session Summary — Suggestions Pipeline v2 (Phase 1)

### Changes Made

1. **SourceDiversity.kt** — Removed `productiveSourceCount > 1` guard in Phase 2 fill-in logic so that single-source sections (e.g., AllPornComic for nakadashi) can still receive fill-in from that sole productive source.

2. **SourceTagFilterMatcher.kt** — Removed HentaiHand from `TEXT_TAG_FILTER_INJECTION_DENYLIST`. HentaiHand's `/api/tags?q=` resolver supports name→tagID→filtered comic search, so injecting its `Tags` text field yields real tag results instead of the 0-result text search fallback.

3. **CandidateRetriever.kt** — Added `consecutiveEmptyTextFetches` mechanism (chronic-empty bench): sources whose text search returns 0 usable candidates across N consecutive section fetches get skipped to avoid burning request slots.

### Verified Outcomes (device RZ8R803CSPM)

| Metric | Old | New |
|---|---|---|
| Filter-label aliases written | 1935 | 2549 |
| Sources with filter aliases | 116 | 116 |
| SECTION_THIN (blowjob) | 1/9 | Gone (no SECTION_THIN) |
| SECTION_THIN (big breasts) | 6/9 | Gone (9 shown, 11 kept) |
| SECTION_THIN (nakadashi) | 8/9 → 5/9 after filtering | 5/9 (unchanged — only AllPornComic productive) |
| HentaiHand tag injection | Broken resolver → text search (0 results) | **TEXT_FIELD 'Tags' injection works** |

### HentaiHand Issue — Extension Bug

Every HentaiHand instance successfully injects via TEXT_FIELD 'Tags', but then fails with:
```
SECTION_DROPPED: JsonDecodingException at $.data[0].id: Expected quotation mark '"', but had '1'
```

**Root cause:** `IdDto(val id: String)` in `lib-multisrc/hentaihand/src/.../Dto.kt` (keiyoushi/extensions-source repo) declares `id` as `String`, but the API returns numeric IDs (e.g., `27` instead of `"27"`). All `LookupFilter`-based queries (Tags, Artists, Characters, Categories, Parodies, Groups, Languages) fail with this parse error.

**Fix:** Change `IdDto(val id: String)` → `IdDto(val id: Int)` in the extension source, removing the `.toInt()` call at the consumer site.

### Remaining Items

- [ ] Fix HentaiHand `IdDto.id` type (extension source fix)
- [ ] nHentai.com still has broken resolver (different source type, uses its own `tag:` syntax)
- [ ] Milftoon text search produces 0 results for all queries — may need vocabulary seed
- [ ] AllPornComic is sole productive source for nakadashi; need to investigate more sources
