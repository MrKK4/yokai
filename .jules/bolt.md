## 2024-05-18 - Jetpack Compose LazyColumn items() vs forEach
**Learning:** In Jetpack Compose lists, mapping over a collection and calling `item { ... }` inside a `LazyColumn` for each item (i.e. `forEach { item { } }`) prevents Compose from optimally recycling views and keeping track of state properly via keys. This is especially true for longer lists where view recycling is critical.
**Action:** Use `items(items = list, key = { it.key }) { item -> ... }` instead of `.forEach { item { ... } }` in `LazyColumn` for correct and optimal composition rendering.
