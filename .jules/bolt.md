## 2026-05-14 - Kotlin Lazy Evaluation (asSequence) in Unified Search
**Learning:** In Kotlin, chaining operations like `.map { ... }.filter { ... }.take(10)` on large data sources (like an entire manga library) creates immediate intermediate `ArrayList` objects for the full dataset at each step before truncation.
**Action:** Always insert `.asSequence()` before filtering/mapping heavy collections if we only intend to `.take()` or process a subset. Remember to call `.toList()` after to collapse the sequence back into standard list types for UI ingestion.
