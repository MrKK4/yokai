
## 2024-05-18 - Missing contentDescription in standard components
**Learning:** In the extension repo section, important action buttons like 'Delete' and 'Add/Check' were discovered missing `contentDescription` entirely, making them invisible to screen readers since they use icon-only `IconButton` configurations.
**Action:** Always verify `IconButton` implementations in lists and inputs contain appropriate localized Moko string resources for `contentDescription`, especially for destructive actions.
