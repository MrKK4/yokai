## 2024-03-01 - Missing ARIA labels in Jetpack Compose
**Learning:** Found that some `IconButton` elements in Jetpack Compose lack descriptive `contentDescription` properties, potentially reducing accessibility for screen reader users. Specifically, `ExtensionRepoItem` uses `Icons.Filled.Delete`, `Icons.Filled.Add`, and `Icons.Filled.Check` with `contentDescription = null`.
**Action:** Always provide meaningful `contentDescription` using string resources for interactive elements unless they are purely decorative.
