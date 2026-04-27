package yokai.presentation.suggestions

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.suggestions.SuggestionsPresenter
import kotlinx.coroutines.flow.distinctUntilChanged
import yokai.domain.manga.models.cover

@Composable
fun SuggestionsScreen(
    presenter: SuggestionsPresenter,
    contentPadding: PaddingValues,
    onMangaClick: (Manga) -> Unit,
    onCanScrollUpChanged: (Boolean) -> Unit,
) {
    val state by presenter.state.collectAsState()
    val listState = rememberLazyListState()
    val visibleSuggestions = state.selectedReason
        ?.let { reason -> state.suggestions.filterKeys { it == reason } }
        ?: state.suggestions

    ReportScrollState(listState, onCanScrollUpChanged)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                state = listState,
                contentPadding = contentPadding,
            ) {
                if (visibleSuggestions.isEmpty()) {
                    item {
                        EmptySuggestions(
                            hasSuggestions = state.suggestions.isNotEmpty(),
                            isLoading = state.isLoading,
                            emptyMessage = state.emptyMessage,
                            modifier = Modifier.fillParentMaxSize(),
                        )
                    }
                } else {
                    visibleSuggestions.forEach { (reason, mangaList) ->
                        item(key = "header:$reason") {
                            SuggestionHeader(reason = reason)
                        }
                        items(
                            items = mangaList,
                            key = { manga -> "$reason:${manga.source}:${manga.url}" },
                        ) { manga ->
                            SuggestionItem(manga = manga, onClick = { onMangaClick(manga) })
                        }
                        item(key = "space:$reason") {
                            Box(modifier = Modifier.height(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportScrollState(
    listState: LazyListState,
    onCanScrollUpChanged: (Boolean) -> Unit,
) {
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
            .distinctUntilChanged()
            .collect(onCanScrollUpChanged)
    }
}

@Composable
private fun EmptySuggestions(
    hasSuggestions: Boolean,
    isLoading: Boolean,
    emptyMessage: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = when {
                isLoading -> "Refreshing suggestions."
                hasSuggestions -> "No matching suggestions."
                else -> "No suggestions found."
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            text = when {
                isLoading -> "Searching your active sources now."
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
private fun SuggestionHeader(
    reason: String,
) {
    Text(
        text = reason,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
    )
}

@Composable
private fun SuggestionItem(manga: Manga, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ElevatedCard(
            modifier = Modifier
                .width(72.dp)
                .aspectRatio(2f / 3f),
        ) {
            AsyncImage(
                model = manga.cover(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = manga.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
