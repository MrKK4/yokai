package yokai.presentation.suggestions

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.suggestions.SuggestionsPresenter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import yokai.domain.manga.models.cover
import yokai.domain.suggestions.SectionBatcher
import yokai.i18n.MR

@Composable
fun SuggestionsScreen(
    presenter: SuggestionsPresenter,
    contentPadding: PaddingValues,
    onMangaClick: (Manga) -> Unit,
    onCanScrollUpChanged: (Boolean) -> Unit,
    onVisibleSectionChanged: (String?) -> Unit,
    onExpandSection: (String) -> Unit,
) {
    val state by presenter.state.collectAsState()
    val gridState = remember(presenter) {
        LazyGridState(
            firstVisibleItemIndex = presenter.gridFirstVisibleItemIndex,
            firstVisibleItemScrollOffset = presenter.gridFirstVisibleItemScrollOffset,
        )
    }
    // On composition start (or when presenter changes due to view recreation after low-memory
    // destroy), scroll to the last saved position if the user was scrolled down.
    LaunchedEffect(presenter) {
        val targetIndex = presenter.gridFirstVisibleItemIndex
        val targetOffset = presenter.gridFirstVisibleItemScrollOffset
        if (targetIndex > 0) {
            gridState.scrollToItem(targetIndex, targetOffset)
        }
    }
    val visibleSuggestions = state.selectedSectionKey
        ?.let { key -> state.suggestions.filterKeys { it == key } }
        ?: state.suggestions

    // Sections visible in the current filter context, preserving planned order.
    val effectivePlannedSections = remember(state.plannedSections, state.selectedSectionKey) {
        if (state.selectedSectionKey != null) {
            state.plannedSections.filter { it.sectionKey == state.selectedSectionKey }
        } else {
            state.plannedSections
        }
    }
    val displayedPlannedSections = remember(
        effectivePlannedSections,
        state.nextBatchStartIndex,
        state.isFetchingBatch,
        state.allSectionsLoaded,
        state.selectedSectionKey,
    ) {
        if (state.selectedSectionKey != null) {
            effectivePlannedSections
        } else {
            val loadingSectionCount = if (state.isFetchingBatch && !state.allSectionsLoaded) 1 else 0
            val visibleCount = (state.nextBatchStartIndex + loadingSectionCount)
                .coerceIn(0, effectivePlannedSections.size)
            effectivePlannedSections.take(visibleCount)
        }
    }
    val hasVisibleSuggestions = visibleSuggestions.values.any { it.isNotEmpty() }
    val hasLoadingPlannedSection = displayedPlannedSections.isNotEmpty() && state.isFetchingBatch
    val showLoadingSkeleton = shouldShowLoadingSkeleton(
        hasVisibleSuggestions = hasVisibleSuggestions,
        isLoading = state.isLoading,
        isFetching = state.isFetching,
        hasLoadingPlannedSection = hasLoadingPlannedSection,
    )
    // V2 progressive layout: render only fetched sections plus the currently fetching one.
    val usePlannedLayout = displayedPlannedSections.isNotEmpty()
    val showGrid = shouldShowSuggestionsGrid(
        hasVisibleSuggestions = hasVisibleSuggestions,
        hasLoadingPlannedSection = hasLoadingPlannedSection,
        showLoadingSkeleton = showLoadingSkeleton,
    )

    val sectionStartIndexes = remember(displayedPlannedSections, visibleSuggestions, usePlannedLayout) {
        if (usePlannedLayout) {
            val indexes = mutableListOf<Int>()
            var idx = 0
            displayedPlannedSections.forEachIndexed { index, section ->
                val mangaList = visibleSuggestions[section.sectionKey]
                val isLoadingSection = index >= state.nextBatchStartIndex && state.isFetchingBatch
                if (!isLoadingSection && mangaList.isNullOrEmpty()) return@forEachIndexed
                indexes += idx
                val count = mangaList?.size ?: SKELETON_CARDS_PER_SECTION
                idx += 1 + count + 1 // header + items/skeleton + spacer
            }
            indexes
        } else {
            visibleSuggestions.values.toSectionStartIndexes()
        }
    }
    val sectionKeysForIndexes = remember(
        displayedPlannedSections,
        visibleSuggestions,
        usePlannedLayout,
        state.nextBatchStartIndex,
        state.isFetchingBatch,
    ) {
        if (usePlannedLayout) {
            buildList {
                displayedPlannedSections.forEachIndexed { index, section ->
                    val mangaList = visibleSuggestions[section.sectionKey]
                    val isLoadingSection = index >= state.nextBatchStartIndex && state.isFetchingBatch
                    if (!isLoadingSection && mangaList.isNullOrEmpty()) return@forEachIndexed
                    add(section.sectionKey)
                }
            }
        } else {
            visibleSuggestions.keys.toList()
        }
    }

    ReportScrollState(gridState, onCanScrollUpChanged)
    ReportScrollPosition(
        gridState = gridState,
        onScrollPositionChanged = presenter::saveGridScrollPosition,
    )
    ReportVisibleSectionState(
        gridState = gridState,
        sectionStartIndexes = sectionStartIndexes,
        sectionKeys = sectionKeysForIndexes,
        onVisibleSectionChanged = onVisibleSectionChanged,
    )
    ReportLoadMoreState(
        gridState = gridState,
        enabled = state.selectedSectionKey == null && !state.isLoading && !state.isFetching && !state.hasReachedEnd,
        sectionStartIndexes = sectionStartIndexes,
        loadedSectionCount = visibleSuggestions.size,
        isFetchingBatch = state.isFetchingBatch,
        allSectionsLoaded = state.allSectionsLoaded,
        useSectionThreshold = false,
        onLoadMore = presenter::loadNextPage,
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (!showGrid) {
                EmptySuggestions(
                    hasSuggestions = state.suggestions.isNotEmpty(),
                    isLoading = state.isLoading,
                    isFetching = state.isFetching,
                    emptyMessage = state.emptyMessage,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .padding(horizontal = 8.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(SUGGESTION_GRID_COLUMNS),
                    modifier = Modifier.fillMaxSize(),
                    state = gridState,
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (showLoadingSkeleton && !usePlannedLayout) {
                        item(
                            key = "loading-header",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            SuggestionHeader(
                                displayName = loadingSectionTitle(state.sortOrder),
                                hasExpandButton = false,
                                onExpand = null,
                            )
                        }
                        repeat(SKELETON_CARDS_PER_SECTION) { index ->
                            item(key = "loading-skeleton:$index") {
                                SuggestionSkeletonCard()
                            }
                        }
                    }
                    if (usePlannedLayout) {
                        // Progressive layout: show skeleton for unloaded sections,
                        // replace with real items as each section's data arrives.
                        displayedPlannedSections.forEachIndexed { index, section ->
                            val sectionKey = section.sectionKey
                            val mangaList = visibleSuggestions[sectionKey]
                            val isLoadingSection = index >= state.nextBatchStartIndex && state.isFetchingBatch
                            if (!isLoadingSection && mangaList.isNullOrEmpty()) return@forEachIndexed
                            item(
                                key = "header:$sectionKey",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                val query = remember(sectionKey) { presenter.extractQueryFromSection(sectionKey) }
                                SuggestionHeader(
                                    displayName = state.sectionDisplayNames[sectionKey] ?: section.displayReason,
                                    hasExpandButton = query != null,
                                    onExpand = query?.let { { onExpandSection(sectionKey) } },
                                )
                            }
                            if (!mangaList.isNullOrEmpty()) {
                                items(
                                    items = mangaList,
                                    key = { manga -> "$sectionKey:${manga.source}:${manga.url}" },
                                ) { manga ->
                                    SuggestionItem(manga = manga, onClick = { onMangaClick(manga) })
                                }
                            } else if (isLoadingSection) {
                                repeat(SKELETON_CARDS_PER_SECTION) { index ->
                                    item(key = "skeleton:$sectionKey:$index") {
                                        SuggestionSkeletonCard()
                                    }
                                }
                            }
                            item(
                                key = "space:$sectionKey",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                Box(modifier = Modifier.height(18.dp))
                            }
                        }
                    } else {
                        // V1 / legacy: iterate loaded sections directly.
                        visibleSuggestions.forEach { (sectionKey, mangaList) ->
                            item(
                                key = "header:$sectionKey",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                val query = remember(sectionKey) { presenter.extractQueryFromSection(sectionKey) }
                                SuggestionHeader(
                                    displayName = state.sectionDisplayNames[sectionKey] ?: sectionKey,
                                    hasExpandButton = query != null,
                                    onExpand = query?.let { { onExpandSection(sectionKey) } },
                                )
                            }
                            items(
                                items = mangaList,
                                key = { manga -> "$sectionKey:${manga.source}:${manga.url}" },
                            ) { manga ->
                                SuggestionItem(manga = manga, onClick = { onMangaClick(manga) })
                            }
                            item(
                                key = "space:$sectionKey",
                                span = { GridItemSpan(maxLineSpan) },
                            ) {
                                Box(modifier = Modifier.height(18.dp))
                            }
                        }
                    }
                    if (state.isFetching && !state.isLoading) {
                        item(
                            key = "loading-more",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            LoadingMoreFooter()
                        }
                    }
                    val endMessage = state.endMessage
                    if (state.selectedSectionKey == null && state.hasReachedEnd && endMessage != null) {
                        item(
                            key = "end-of-feed",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            EndOfFeedFooter(message = endMessage)
                        }
                    }
                }
            }

            if (state.isTagFilterSheetVisible) {
                SuggestionsFilterSheet(
                    availableTags = state.availableTags,
                    blacklistedTags = state.blacklistedTags,
                    pinnedTags = state.pinnedTags,
                    pinEnabled = state.pinFilterEnabled,
                    onApply = presenter::applyTagFilters,
                    onDismissRequest = presenter::dismissTagFilterSheet,
                )
            }

            val sheetSectionKey = state.sheetSectionKey
            if (sheetSectionKey != null && !state.sheetSuppressed) {
                SuggestionsExpandedSheet(
                    displayName = state.sectionDisplayNames[sheetSectionKey] ?: sheetSectionKey,
                    results = state.sheetResults,
                    isLoading = state.sheetIsLoading,
                    isLoadingMore = state.sheetIsLoadingMore,
                    hasMore = state.sheetHasMore,
                    error = state.sheetError,
                    onMangaClick = onMangaClick,
                    onRetry = { presenter.expandSection(sheetSectionKey) },
                    onDismiss = presenter::dismissExpandSheet,
                    onLoadMore = presenter::loadMoreExpandedSection,
                )
            }
        }
    }
}

@Composable
private fun ReportVisibleSectionState(
    gridState: LazyGridState,
    sectionStartIndexes: List<Int>,
    sectionKeys: List<String>,
    onVisibleSectionChanged: (String?) -> Unit,
) {
    LaunchedEffect(gridState, sectionStartIndexes, sectionKeys) {
        snapshotFlow {
            val firstVisibleItem = gridState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
                ?: gridState.firstVisibleItemIndex
            val sectionIndex = sectionStartIndexes.indexOfLast { it <= firstVisibleItem }
            sectionKeys.getOrNull(sectionIndex)
        }
            .distinctUntilChanged()
            .collect(onVisibleSectionChanged)
    }
}

@Composable
private fun ReportScrollState(
    gridState: LazyGridState,
    onCanScrollUpChanged: (Boolean) -> Unit,
) {
    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.canScrollBackward
        }
            .distinctUntilChanged()
            .collect(onCanScrollUpChanged)
    }
}

@Composable
private fun ReportScrollPosition(
    gridState: LazyGridState,
    onScrollPositionChanged: (index: Int, scrollOffset: Int) -> Unit,
) {
    // Fires on every scroll change (async)
    LaunchedEffect(gridState) {
        snapshotFlow {
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, scrollOffset) ->
                onScrollPositionChanged(index, scrollOffset)
            }
    }
    // Fires synchronously on composition dispose (tab switch / navigation).
    // Guarantees the very last position is saved even if the flow hadn't emitted yet.
    DisposableEffect(gridState) {
        onDispose {
            onScrollPositionChanged(
                gridState.firstVisibleItemIndex,
                gridState.firstVisibleItemScrollOffset,
            )
        }
    }
}

@Composable
private fun ReportLoadMoreState(
    gridState: LazyGridState,
    enabled: Boolean,
    sectionStartIndexes: List<Int>,
    loadedSectionCount: Int,
    isFetchingBatch: Boolean,
    allSectionsLoaded: Boolean,
    useSectionThreshold: Boolean,
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(
        gridState,
        enabled,
        sectionStartIndexes,
        loadedSectionCount,
        isFetchingBatch,
        allSectionsLoaded,
        useSectionThreshold,
    ) {
        if (!enabled) return@LaunchedEffect

        snapshotFlow {
            val layoutInfo = gridState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (useSectionThreshold) {
                val lastVisibleSectionIndex = sectionStartIndexes.indexOfLast { it <= lastVisibleItem }
                SectionBatcher.shouldLoadMore(
                    lastVisibleSectionIndex = lastVisibleSectionIndex,
                    loadedSectionCount = loadedSectionCount,
                    isFetchingBatch = isFetchingBatch,
                    allSectionsLoaded = allSectionsLoaded,
                )
            } else {
                layoutInfo.totalItemsCount > 0 && lastVisibleItem >= layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
            }
        }
            .distinctUntilChanged()
            .filter { it }
            .collect { onLoadMore() }
    }
}

@Composable
private fun EmptySuggestions(
    hasSuggestions: Boolean,
    isLoading: Boolean,
    isFetching: Boolean,
    emptyMessage: String?,
    modifier: Modifier = Modifier,
) {
    // emptyMessage is a terminal state (error / no matches / waiting for sources). It must
    // win over an overlapping background refresh so the user isn't shown an endless spinner
    // while the worker happens to be running.
    val isRefreshing = (isLoading || isFetching) && emptyMessage == null
    val title = when {
        emptyMessage != null -> emptyMessage
        isRefreshing -> stringResource(MR.strings.suggestions_refreshing)
        hasSuggestions -> stringResource(MR.strings.suggestions_no_matching)
        else -> stringResource(MR.strings.suggestions_none_found)
    }
    val subtitle = when {
        emptyMessage != null -> null
        isRefreshing -> stringResource(MR.strings.suggestions_searching_sources)
        hasSuggestions -> stringResource(MR.strings.suggestions_try_another_filter)
        else -> stringResource(MR.strings.suggestions_read_to_build)
    }
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.padding(bottom = 20.dp))
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun LoadingMoreFooter() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        repeat(SUGGESTION_GRID_COLUMNS) {
            Box(modifier = Modifier.weight(1f)) {
                SuggestionSkeletonCard()
            }
        }
    }
}

@Composable
private fun EndOfFeedFooter(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
    )
}

@Composable
private fun SuggestionHeader(
    displayName: String,
    hasExpandButton: Boolean,
    onExpand: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = displayName,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasExpandButton) {
            IconButton(onClick = { onExpand?.invoke() }) {
                Icon(
                    imageVector = Icons.Outlined.ExpandMore,
                    contentDescription = stringResource(MR.strings.suggestions_expand_content_description, displayName),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
internal fun SuggestionItem(manga: Manga, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .aspectRatio(2f / 3f),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = manga.cover(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.72f),
                            ),
                            startY = 160f,
                        ),
                    ),
            )
            Text(
                text = manga.title,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
            )
        }
    }
}

@Composable
private fun SuggestionSkeletonCard() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "skeleton_alpha",
    )
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)),
        )
    }
}

private const val SUGGESTION_GRID_COLUMNS = 3
private const val LOAD_MORE_THRESHOLD = 3
private const val SKELETON_CARDS_PER_SECTION = 9

internal fun shouldShowSuggestionsGrid(
    hasVisibleSuggestions: Boolean,
    hasLoadingPlannedSection: Boolean,
    showLoadingSkeleton: Boolean = false,
): Boolean =
    hasVisibleSuggestions || hasLoadingPlannedSection || showLoadingSkeleton

internal fun shouldShowLoadingSkeleton(
    hasVisibleSuggestions: Boolean,
    isLoading: Boolean,
    isFetching: Boolean,
    hasLoadingPlannedSection: Boolean,
): Boolean =
    !hasVisibleSuggestions && (isLoading || isFetching || hasLoadingPlannedSection)

private fun loadingSectionTitle(sortOrder: yokai.domain.suggestions.SuggestionSortOrder): String =
    when (sortOrder) {
        yokai.domain.suggestions.SuggestionSortOrder.Latest -> "Latest from your sources"
        yokai.domain.suggestions.SuggestionSortOrder.Popular -> "Popular from your sources"
    }

private fun Collection<List<Manga>>.toSectionStartIndexes(): List<Int> {
    val indexes = mutableListOf<Int>()
    var itemIndex = 0
    forEach { mangaList ->
        indexes += itemIndex
        itemIndex += 1 + mangaList.size + 1
    }
    return indexes
}
