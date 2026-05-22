package yokai.presentation.suggestions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.domain.manga.models.Manga
import yokai.i18n.MR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsExpandedSheet(
    displayName: String,
    results: List<Manga>,
    isLoading: Boolean,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    error: String?,
    onMangaClick: (Manga) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    onLoadMore: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val gridState = rememberLazyGridState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            // Header row — same pattern as SuggestionsFilterSheet
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(MR.strings.close),
                    )
                }
            }

            when {
                isLoading -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(SUGGESTION_GRID_COLUMNS),
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.85f),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 24.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(SKELETON_CARDS) {
                            SuggestionSkeletonCard()
                        }
                    }
                }
                error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = error,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                            TextButton(onClick = onRetry) {
                                Text(text = stringResource(MR.strings.retry))
                            }
                        }
                    }
                }
                results.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(MR.strings.no_results_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(SUGGESTION_GRID_COLUMNS),
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.85f),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = 24.dp,
                        ),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(
                            items = results,
                            key = { manga -> "${manga.source}:${manga.url}" },
                        ) { manga ->
                            SuggestionItem(
                                manga = manga,
                                onClick = { onMangaClick(manga) },
                            )
                        }
                        if (isLoadingMore) {
                            items(SUGGESTION_GRID_COLUMNS) {
                                SuggestionSkeletonCard()
                            }
                        }
                    }

                    // Trigger load-more reactively via snapshotFlow so it fires at most once
                    // per threshold-crossing instead of on every recomposition.
                    LaunchedEffect(gridState, hasMore, isLoadingMore) {
                        if (!hasMore || isLoadingMore) return@LaunchedEffect
                        snapshotFlow {
                            val info = gridState.layoutInfo
                            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                            val total = info.totalItemsCount
                            total > 0 && lastVisible >= total - 4
                        }
                            .distinctUntilChanged()
                            .filter { it }
                            .collect { onLoadMore() }
                    }
                }
            }
        }
    }
}

private const val SUGGESTION_GRID_COLUMNS = 3
private const val SKELETON_CARDS = 9
