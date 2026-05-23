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
