package yokai.presentation.suggestions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import yokai.i18n.MR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsFilterSheet(
    availableTags: List<String>,
    blacklistedTags: Set<String>,
    onApply: (Set<String>) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // Track pending blacklist changes locally — only commit on Apply
    var pendingBlacklist by remember(blacklistedTags) { mutableStateOf(blacklistedTags) }
    val normalizedPending = remember(pendingBlacklist) {
        pendingBlacklist
            .map { it.normalizedTagKey() }
            .filter { it.isNotBlank() }
            .toSet()
    }
    val filteredTags = remember(availableTags, searchQuery) {
        val query = searchQuery.normalizedTagKey()
        if (query.isBlank()) {
            availableTags
        } else {
            availableTags.filter { tag -> tag.normalizedTagKey().contains(query) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(MR.strings.suggestions_exclude_tags),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )

            if (availableTags.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp)
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(MR.strings.no_results_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    placeholder = { Text(text = stringResource(MR.strings.search)) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = stringResource(MR.strings.close),
                                )
                            }
                        }
                    },
                    singleLine = true,
                )

                if (filteredTags.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp)
                            .padding(horizontal = 24.dp, vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No matching tags.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(
                            items = filteredTags,
                            key = { tag -> tag.normalizedTagKey() },
                        ) { tag ->
                            val isBlacklisted = tag.normalizedTagKey() in normalizedPending
                            TagExclusionRow(
                                tag = tag,
                                isBlacklisted = isBlacklisted,
                                onCheckedChange = { checked ->
                                    pendingBlacklist = if (checked) {
                                        pendingBlacklist + tag
                                    } else {
                                        pendingBlacklist.filterNot {
                                            it.normalizedTagKey() == tag.normalizedTagKey()
                                        }.toSet()
                                    }
                                },
                            )
                        }
                    }
                }

                // Apply / Cancel buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        onClick = onDismissRequest,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(MR.strings.cancel))
                    }
                    Button(
                        onClick = { onApply(pendingBlacklist) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(text = stringResource(MR.strings.apply))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun TagExclusionRow(
    tag: String,
    isBlacklisted: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = isBlacklisted,
                role = Role.Checkbox,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = tag,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Checkbox(
            checked = isBlacklisted,
            onCheckedChange = null,
        )
    }
}

private fun String.normalizedTagKey(): String =
    lowercase().trim().replace(WHITESPACE, " ")

private val WHITESPACE = Regex("\\s+")
