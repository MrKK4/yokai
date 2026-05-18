## 2024-05-24 - [Kotlin sequence optimizations]
**Learning:** Found an anti-pattern in Kotlin processing large arrays locally. Large collection operations without using `.asSequence()` leads to performance costs. Using lazy sequence via `.asSequence()` significantly speeds up chained map/filter pipelines by avoiding allocating massive temporary intermediate objects.
**Action:** Actively scan the codebase for large loops or array collections with chained mapping or filtering, and use `.asSequence()` before the chained operations to improve lazy evaluation.
