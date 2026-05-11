## 2024-05-19 - Content Descriptions in Jetpack Compose
**Learning:** Found that `IconButton` and interactive `Icon` elements in Jetpack Compose files (like `ExtensionRepoItem.kt`) often have their `contentDescription` set to `null`, which negatively impacts accessibility via screen readers.
**Action:** Always replace `contentDescription = null` with an appropriate localized string resource using Moko Resources `stringResource(MR.strings.[string_name])` for interactive icons to ensure keyboard and screen reader accessibility.
