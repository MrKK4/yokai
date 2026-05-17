## 2024-05-15 - [Sequence Evaluation for Large Data Sets]
**Learning:** For Kotlin collections inside the application, especially inside search and library components containing thousands of elements, using standard chained collection operators (`.map`, `.filter`, `.take`) triggers intermediate list creations resulting in high memory cost.
**Action:** Use `.asSequence()` when operating over large sets so that operations execute lazily one-by-one.
