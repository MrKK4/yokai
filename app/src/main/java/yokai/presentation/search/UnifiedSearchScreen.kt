package yokai.presentation.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.search.UnifiedSearchPresenter

@Composable
fun UnifiedSearchScreen(presenter: UnifiedSearchPresenter) {
    val state by presenter.state.collectAsState()

    if (state.isLoading) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp)
        ) {
            if (state.libraryResults.isNotEmpty()) {
                item {
                    Text(
                        text = "In Library",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                // ⚡ Bolt Optimization: Added stable keys to prevent unnecessary recompositions.
                // 📊 Impact: O(1) item updates instead of O(N) list recompositions on state change.
                items(
                    items = state.libraryResults,
                    key = { manga -> manga.id ?: manga.url }
                ) { manga ->
                    Text(text = manga.title, modifier = Modifier.padding(bottom = 4.dp))
                }
            }

            if (state.historyResults.isNotEmpty()) {
                item {
                    Text(
                        text = "In History",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                // ⚡ Bolt Optimization: Added stable keys to prevent unnecessary recompositions.
                // 📊 Impact: O(1) item updates instead of O(N) list recompositions on state change.
                items(
                    items = state.historyResults,
                    key = { manga -> manga.id ?: manga.url }
                ) { manga ->
                    Text(text = manga.title, modifier = Modifier.padding(bottom = 4.dp))
                }
            }

            if (state.sourceResults.isNotEmpty()) {
                item {
                    Text(
                        text = "From Sources",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
                // ⚡ Bolt Optimization: Added stable keys to prevent unnecessary recompositions.
                // 📊 Impact: Prevents whole-list recomposition when new sources stream in.
                items(
                    items = state.sourceResults.entries.toList(),
                    key = { (source, _) -> source.id }
                ) { (source, mangaList) ->
                    Column(modifier = Modifier.padding(bottom = 16.dp)) {
                        Text(
                            text = source.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // ⚡ Bolt Optimization: Added stable keys to prevent unnecessary recompositions.
                            // 📊 Impact: Prevents re-rendering row items during horizontal scroll or updates.
                            items(
                                items = mangaList,
                                key = { manga -> manga.id ?: manga.url }
                            ) { manga ->
                                Text(text = manga.title)
                            }
                        }
                    }
                }
            }
            
            if (state.libraryResults.isEmpty() && state.historyResults.isEmpty() && state.sourceResults.isEmpty()) {
                item {
                    Text(
                        text = "No results found.",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            }
        }
    }
}
