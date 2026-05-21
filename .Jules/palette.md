## 2026-05-21 - Missing contentDescription in Compose IconButtons
**Learning:** Found an accessibility issue pattern specific to this app's components: interactive `IconButton`s often use `contentDescription = null` for their inner `Icon`s, making actions invisible to screen readers.
**Action:** When adding or modifying interactive icons in Compose, always ensure they use localized `contentDescription` via `stringResource(MR.strings.<name>)`. Save `contentDescription = null` only for purely decorative icons.
