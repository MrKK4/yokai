package yokai.presentation.suggestions

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
import androidx.compose.runtime.saveable.rememberSaveable
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
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.suggestions.SuggestionsPresenter
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import yokai.domain.manga.models.cover

@Composable
fun SuggestionsScreen(
    presenter: SuggestionsPresenter,
    contentPadding: PaddingValues,
    onMangaClick: (Manga) -> Unit,
    onCanScrollUpChanged: (Boolean) -> Unit,
    onExpandSection: (String) -> Unit,
) {
    val state by presenter.state.collectAsState()
    val gridState = rememberSaveable(saver = LazyGridState.Saver) {
        LazyGridState(
            firstVisibleItemIndex = presenter.gridFirstVisibleItemIndex,
            firstVisibleItemScrollOffset = presenter.gridFirstVisibleItemScrollOffset,
        )
    }
    val visibleSuggestions = state.selectedReason
        ?.let { reason -> state.suggestions.filterKeys { it == reason } }
        ?: state.suggestions

    ReportScrollState(gridState, onCanScrollUpChanged)
    ReportScrollPosition(
        gridState = gridState,
        onScrollPositionChanged = presenter::saveGridScrollPosition,
    )
    ReportLoadMoreState(
        gridState = gridState,
        enabled = state.selectedReason == null && !state.isLoading && !state.isFetching && !state.hasReachedEnd,
        onLoadMore = presenter::loadNextPage,
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (visibleSuggestions.isEmpty()) {
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
                    columns = GridCells.Adaptive(MANGA_GRID_MIN_WIDTH),
                    modifier = Modifier.fillMaxSize(),
                    state = gridState,
                    contentPadding = contentPadding,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    visibleSuggestions.forEach { (reason, mangaList) ->
                        item(
                            key = "header:$reason",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            val query = remember(reason) { presenter.extractQueryFromReason(reason) }
                            SuggestionHeader(
                                reason = reason,
                                hasExpandButton = query != null,
                                onExpand = query?.let { { onExpandSection(reason) } },
                            )
                        }
                        items(
                            items = mangaList,
                            key = { manga -> "$reason:${manga.source}:${manga.url}" },
                        ) { manga ->
                            SuggestionItem(manga = manga, onClick = { onMangaClick(manga) })
                        }
                        item(
                            key = "space:$reason",
                            span = { GridItemSpan(maxLineSpan) },
                        ) {
                            Box(modifier = Modifier.height(18.dp))
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
                    if (state.selectedReason == null && state.hasReachedEnd && endMessage != null) {
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
                    onTagToggled = presenter::setTagBlacklisted,
                    onDismissRequest = presenter::dismissTagFilterSheet,
                )
            }

            val sheetReason = state.sheetReason
            if (sheetReason != null) {
                SuggestionsExpandedSheet(
                    reason = sheetReason,
                    results = state.sheetResults,
                    isLoading = state.sheetIsLoading,
                    error = state.sheetError,
                    onMangaClick = onMangaClick,
                    onDismiss = presenter::dismissExpandSheet,
                )
            }
        }
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
    onLoadMore: () -> Unit,
) {
    LaunchedEffect(gridState, enabled) {
        if (!enabled) return@LaunchedEffect

        snapshotFlow {
            val layoutInfo = gridState.layoutInfo
            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            layoutInfo.totalItemsCount > 0 && lastVisibleItem >= layoutInfo.totalItemsCount - LOAD_MORE_THRESHOLD
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
    val isRefreshing = isLoading || isFetching
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.padding(bottom = 20.dp))
        }
        Text(
            text = when {
                isRefreshing -> "Refreshing suggestions."
                hasSuggestions -> "No matching suggestions."
                else -> "No suggestions found."
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = when {
                isRefreshing -> "Searching your active sources now."
                hasSuggestions -> "Try another suggestion filter."
                emptyMessage != null -> emptyMessage
                else -> "Read or favorite some manga in your library to build personalized suggestions."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoadingMoreFooter() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
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
    reason: String,
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
            text = reason,
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
                    contentDescription = "Expand $reason",
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
                style = MaterialTheme.typography.titleMedium,
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

private val MANGA_GRID_MIN_WIDTH = 104.dp
private const val LOAD_MORE_THRESHOLD = 3
