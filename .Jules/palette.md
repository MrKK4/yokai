## 2024-05-13 - Add contentDescription to ExtensionRepoItem icon buttons
**Learning:** ExtensionRepoItem has multiple icon buttons and decorative icons that lack accessibility labels (`contentDescription = null`). This makes it hard for screen reader users to understand what the buttons do, particularly the delete and add/check buttons.
**Action:** Always ensure interactive elements like `IconButton` have meaningful `contentDescription` using string resources for accessibility. For purely decorative icons, `null` is fine, but for functional ones, a label is required.
