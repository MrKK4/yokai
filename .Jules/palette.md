## 2024-05-24 - Accessibility labels for IconButtons
**Learning:** When using Moko Resources for Compose string resources (`dev.icerock.moko.resources.compose.stringResource`), there can be naming conflicts with AndroidX's `androidx.compose.ui.res.stringResource`.
**Action:** Use import aliasing (e.g., `import dev.icerock.moko.resources.compose.stringResource as mokoStringResource`) to prevent naming conflicts when both AndroidX and Moko Resources are used in the same file.
