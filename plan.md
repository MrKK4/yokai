1. **Optimize `UnifiedSearchPresenter` using Sequences**:
   - In `app/src/main/java/eu/kanade/tachiyomi/ui/search/UnifiedSearchPresenter.kt`, update `libraryJob` to use lazy sequence evaluation:
     `allLibraryManga.asSequence().map { it.manga }.filter { ... }.take(10).toList()`
   - Doing so avoids expensive intermediate allocations of entire library lists and short-circuits execution as soon as 10 items are matched.
2. **Complete pre-commit steps to ensure proper testing, verification, review, and reflection are done**:
   - Run linter/format checks.
   - Run tests.
3. **Submit the PR**:
   - Present PR as Bolt with title "⚡ Bolt: [performance improvement]" and necessary description metrics.
