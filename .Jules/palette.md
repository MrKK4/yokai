## 2024-05-24 - Accessibility Content Descriptions in Compose
**Learning:** IconButtons in Compose components often have `contentDescription = null` even when they perform distinct actions, making them invisible to screen readers. For example, the `ExtensionRepoItem` component has `IconButton`s for deleting and adding repos without descriptions.
**Action:** Add proper content descriptions to IconButtons using `dev.icerock.moko.resources.compose.stringResource` and `yokai.i18n.MR.strings.*`.
