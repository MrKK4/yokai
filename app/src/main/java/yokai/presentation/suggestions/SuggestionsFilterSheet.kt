package yokai.presentation.suggestions

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import yokai.i18n.MR

private enum class TagFilterMode { NEUTRAL, PINNED, BLACKLISTED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuggestionsFilterSheet(
    availableTags: List<String>,
    blacklistedTags: Set<String>,
    pinnedTags: Set<String>,
    pinEnabled: Boolean,
    onApply: (blacklist: Set<String>, pinned: Set<String>) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by rememberSaveable { mutableStateOf("") }
    // Track pending changes locally — only commit on Apply.
    var pendingBlacklist by remember(blacklistedTags) { mutableStateOf(blacklistedTags) }
    var pendingPinned by remember(pinnedTags, pinEnabled) {
        mutableStateOf(if (pinEnabled) pinnedTags else emptySet())
    }
    // Custom tags = pinned or blacklisted tags not sourced from the library (user-typed).
    var customTags by remember(blacklistedTags, pinnedTags, availableTags) {
        mutableStateOf(
            (blacklistedTags + pinnedTags)
                .filter { ft -> availableTags.none { it.normalizedTagKey() == ft.normalizedTagKey() } }
                .toSet(),
        )
    }
    val normalizedBlacklist = remember(pendingBlacklist) {
        pendingBlacklist.map { it.normalizedTagKey() }.filter { it.isNotBlank() }.toSet()
    }
    val normalizedPinned = remember(pendingPinned) {
        pendingPinned.map { it.normalizedTagKey() }.filter { it.isNotBlank() }.toSet()
    }
    val filteredTags = remember(availableTags, customTags, searchQuery) {
        val query = searchQuery.normalizedTagKey()
        val allTags = customTags.toList() + availableTags.filter { at ->
            customTags.none { ct -> ct.normalizedTagKey() == at.normalizedTagKey() }
        }
        if (query.isBlank()) allTags
        else allTags.filter { tag -> tag.normalizedTagKey().contains(query) }
    }
    val showAddButton = remember(searchQuery, availableTags, customTags) {
        val query = searchQuery.trim()
        query.isNotBlank() &&
            availableTags.none { it.normalizedTagKey() == query.normalizedTagKey() } &&
            customTags.none { it.normalizedTagKey() == query.normalizedTagKey() }
    }

    fun modeOf(tagKey: String): TagFilterMode = when (tagKey) {
        in normalizedPinned -> TagFilterMode.PINNED
        in normalizedBlacklist -> TagFilterMode.BLACKLISTED
        else -> TagFilterMode.NEUTRAL
    }

    fun cycleMode(tag: String) {
        val key = tag.normalizedTagKey()
        val withoutTagFromPinned = { pendingPinned.filterNot { it.normalizedTagKey() == key }.toSet() }
        val withoutTagFromBlacklist = { pendingBlacklist.filterNot { it.normalizedTagKey() == key }.toSet() }
        when (modeOf(key)) {
            TagFilterMode.NEUTRAL -> {
                if (pinEnabled) {
                    pendingPinned = pendingPinned + tag
                } else {
                    pendingBlacklist = pendingBlacklist + tag
                }
            }
            TagFilterMode.PINNED -> {
                // Pin -> Blacklist
                pendingPinned = withoutTagFromPinned()
                pendingBlacklist = pendingBlacklist + tag
            }
            TagFilterMode.BLACKLISTED -> {
                // Blacklist -> Neutral
                pendingBlacklist = withoutTagFromBlacklist()
            }
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
                text = stringResource(
                    if (pinEnabled) MR.strings.suggestions_filter_tags else MR.strings.suggestions_exclude_tags,
                ),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )

            if (pinEnabled) {
                Text(
                    text = stringResource(MR.strings.suggestions_filter_legend),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                placeholder = {
                    Text(
                        text = if (availableTags.isEmpty()) {
                            stringResource(MR.strings.add_tag)
                        } else {
                            stringResource(MR.strings.search)
                        },
                    )
                },
                leadingIcon = {
                    Icon(imageVector = Icons.Outlined.Search, contentDescription = null)
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

            when {
                filteredTags.isEmpty() && !showAddButton -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
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
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (showAddButton) {
                            item(key = "__add__") {
                                TextButton(
                                    onClick = {
                                        val tag = searchQuery.trim()
                                        customTags = customTags + tag
                                        // User-added tags default to blacklisted to preserve
                                        // the prior single-action behavior.
                                        pendingBlacklist = pendingBlacklist + tag
                                        searchQuery = ""
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Add,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 8.dp),
                                    )
                                    Text(
                                        text = "${stringResource(MR.strings.add_tag)} \"${searchQuery.trim()}\"",
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        items(
                            items = filteredTags,
                            key = { tag -> tag.normalizedTagKey() },
                        ) { tag ->
                            TagFilterRow(
                                tag = tag,
                                mode = modeOf(tag.normalizedTagKey()),
                                pinEnabled = pinEnabled,
                                isCustom = customTags.any { it.normalizedTagKey() == tag.normalizedTagKey() },
                                onPinClick = {
                                    val key = tag.normalizedTagKey()
                                    if (modeOf(key) == TagFilterMode.PINNED) {
                                        pendingPinned = pendingPinned.filterNot { it.normalizedTagKey() == key }.toSet()
                                    } else {
                                        pendingBlacklist = pendingBlacklist.filterNot { it.normalizedTagKey() == key }.toSet()
                                        pendingPinned = pendingPinned + tag
                                    }
                                },
                                onBlockClick = {
                                    val key = tag.normalizedTagKey()
                                    if (modeOf(key) == TagFilterMode.BLACKLISTED) {
                                        pendingBlacklist = pendingBlacklist.filterNot { it.normalizedTagKey() == key }.toSet()
                                    } else {
                                        pendingPinned = pendingPinned.filterNot { it.normalizedTagKey() == key }.toSet()
                                        pendingBlacklist = pendingBlacklist + tag
                                    }
                                },
                                onRowClick = { cycleMode(tag) },
                            )
                        }
                    }
                }
            }

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
                    onClick = { onApply(pendingBlacklist, pendingPinned) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(text = stringResource(MR.strings.apply))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TagFilterRow(
    tag: String,
    mode: TagFilterMode,
    pinEnabled: Boolean,
    isCustom: Boolean,
    onPinClick: () -> Unit,
    onBlockClick: () -> Unit,
    onRowClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onRowClick)
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        ) {
            Text(
                text = tag,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = when (mode) {
                    TagFilterMode.BLACKLISTED -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (isCustom) {
                Text(
                    text = stringResource(MR.strings.custom_tag),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (pinEnabled) {
            IconButton(
                onClick = onPinClick,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = if (mode == TagFilterMode.PINNED) {
                        Icons.Filled.PushPin
                    } else {
                        Icons.Outlined.PushPin
                    },
                    contentDescription = stringResource(MR.strings.suggestions_pin_tag),
                    tint = if (mode == TagFilterMode.PINNED) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        IconButton(
            onClick = onBlockClick,
            modifier = Modifier.size(40.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Block,
                contentDescription = stringResource(MR.strings.suggestions_block_tag),
                tint = if (mode == TagFilterMode.BLACKLISTED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

private fun String.normalizedTagKey(): String =
    lowercase().trim().replace(WHITESPACE, " ")

private val WHITESPACE = Regex("\\s+")
