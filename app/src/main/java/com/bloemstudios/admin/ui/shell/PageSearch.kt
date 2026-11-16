package com.bloemstudios.admin.ui.shell

import com.bloemstudios.admin.ui.components.LucideIcon
import com.bloemstudios.admin.ui.components.LucideSize

import com.bloemstudios.admin.ui.components.OutlinedTextField
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bloemstudios.admin.R
import com.bloemstudios.admin.ui.theme.VeilColors
import com.bloemstudios.admin.ui.theme.VeilShapes
import com.bloemstudios.admin.ui.theme.VeilSpacing
import java.text.Normalizer

// Test tags. Prefixes are constants so instrumented tests can build tags without calling into the
// (minified) app code.
internal const val SearchButtonTag = "shell-search-button"
internal const val SearchSheetTag = "shell-search-sheet"
internal const val SearchFieldTag = "shell-search-field"
internal const val SearchResultTagPrefix = "shell-search-result:"
internal const val SearchGroupTagPrefix = "shell-search-group:"
internal const val SearchListTag = "shell-search-list"
internal const val SearchEmptyTag = "shell-search-empty"
internal const val SearchCountTag = "shell-search-count"

/** A page as search reads it: its text as shown, in the interface language. */
internal data class SearchablePage(
    val destination: AdminDestination,
    val groupLabel: String,
    val label: String,
    val description: String,
)

/** Lower case, accents dropped, anything but letters and digits a space: the web's `normalizeSearchText`. */
internal fun normalizeSearchText(value: String): String =
    Normalizer.normalize(value.lowercase(), Normalizer.Form.NFKD)
        .replace(Regex("[\\u0300-\\u036f]"), "")
        .replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ")
        .trim()

internal fun searchTokens(query: String): List<String> =
    normalizeSearchText(query).let { if (it.isEmpty()) emptyList() else it.split(Regex("\\s+")) }

/** Every token starts a word, or (from four letters) sits inside one: the web's `textMatchesTokens`. */
internal fun matchesTokens(text: String, tokens: List<String>): Boolean {
    val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
    return tokens.all { token ->
        words.any { it.startsWith(token) || (token.length >= 4 && it.contains(token)) }
    }
}

/**
 * The pages [query] finds, grouped as the drawer groups them. A group whose own name matches keeps
 * all its pages; otherwise a page matches on its group, label, description and keywords. A blank
 * query lists every page.
 */
internal fun filterPages(pages: List<SearchablePage>, query: String): List<Pair<AdminGroup, List<SearchablePage>>> {
    val tokens = searchTokens(query)
    return AdminGroup.entries.mapNotNull { group ->
        val inGroup = pages.filter { it.destination.group == group }
        if (inGroup.isEmpty()) return@mapNotNull null
        if (tokens.isEmpty()) return@mapNotNull group to inGroup
        val groupMatches = matchesTokens(normalizeSearchText(inGroup.first().groupLabel), tokens)
        val found = if (groupMatches) inGroup else inGroup.filter { page ->
            val text = normalizeSearchText(
                (listOf(page.groupLabel, page.label, page.description) + page.destination.keywords).joinToString(" "),
            )
            matchesTokens(text, tokens)
        }
        found.takeIf { it.isNotEmpty() }?.let { group to it }
    }
}

/**
 * The web's page search as a full-screen sheet: a text field over every page the current context
 * offers, matched on label, description and keywords. Picking a page calls [onPick]; the system Back
 * gesture and the arrow call [onDismiss]. Pages are not pickable while [locked].
 */
@Composable
internal fun PageSearchSheet(
    destinations: List<AdminDestination>,
    selectedDestinationId: String?,
    locked: Boolean,
    onPick: (AdminDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val pages = remember(destinations, configuration) {
        destinations.map { destination ->
            SearchablePage(
                destination = destination,
                groupLabel = context.getString(destination.group.labelRes),
                label = context.getString(destination.labelRes),
                description = if (destination.descriptionRes != 0) context.getString(destination.descriptionRes) else "",
            )
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val groups = remember(pages, query) { filterPages(pages, query) }
    val resultCount = groups.sumOf { it.second.size }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Surface(
        // Takes every touch that reaches it, so nothing under the sheet reacts.
        modifier = Modifier.fillMaxSize().testTag(SearchSheetTag).pointerInput(Unit) { detectTapGestures { } },
        color = VeilColors.Canvas,
        contentColor = VeilColors.Text,
    ) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = VeilColors.Card, contentColor = VeilColors.Text) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = VeilSpacing.Tiny),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(VeilSpacing.MinimumTouchTarget)) {
                        LucideIcon(R.drawable.lucide_chevron_left, size = LucideSize.Bar, contentDescription = stringResource(R.string.shell_search_close))
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = VeilSpacing.MinimumTouchTarget)
                            .focusRequester(focus)
                            .testTag(SearchFieldTag),
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        placeholder = { Text(stringResource(R.string.shell_search_placeholder), color = VeilColors.Muted) },
                        leadingIcon = { LucideIcon(R.drawable.lucide_search, contentDescription = null, tint = VeilColors.Muted) },
                        trailingIcon = if (query.isNotEmpty()) ({
                            IconButton(onClick = { query = "" }, modifier = Modifier.size(VeilSpacing.MinimumTouchTarget)) {
                                LucideIcon(R.drawable.lucide_x, contentDescription = stringResource(R.string.shell_search_clear))
                            }
                        }) else null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = {
                            // Enter opens the first match, as the web's palette does.
                            groups.firstOrNull()?.second?.firstOrNull()?.takeIf { !locked }?.let { onPick(it.destination) }
                        }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = VeilColors.Text,
                            unfocusedTextColor = VeilColors.Text,
                            cursorColor = VeilColors.Accent,
                        ),
                    )
                }
            }
            if (groups.isEmpty()) {
                Text(
                    text = stringResource(R.string.shell_search_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VeilColors.Muted,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(VeilSpacing.Large)
                        .testTag(SearchEmptyTag),
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .testTag(SearchListTag)
                        .semantics { contentDescription = context.getString(R.string.shell_search_results) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = VeilSpacing.Small, vertical = VeilSpacing.Small,
                    ),
                    verticalArrangement = Arrangement.spacedBy(VeilSpacing.Tiny),
                ) {
                    groups.forEach { (group, items) ->
                        item(key = "group:${group.name}") {
                            Text(
                                text = stringResource(group.labelRes),
                                style = MaterialTheme.typography.labelMedium,
                                color = VeilColors.Muted,
                                modifier = Modifier
                                    .padding(start = VeilSpacing.CompactHorizontal, top = VeilSpacing.Medium, bottom = VeilSpacing.Tiny)
                                    .testTag(SearchGroupTagPrefix + group.name)
                                    .semantics { heading() },
                            )
                        }
                        items(items, key = { it.destination.id }) { page ->
                            SearchResultRow(
                                page = page,
                                current = page.destination.id == selectedDestinationId,
                                enabled = !locked,
                                onClick = { onPick(page.destination) },
                            )
                        }
                    }
                }
            }
            HorizontalDivider(color = VeilColors.Hairline)
            Text(
                text = if (query.isBlank()) pluralStringResource(R.plurals.shell_search_total, resultCount, resultCount)
                else pluralStringResource(R.plurals.shell_search_matches, resultCount, resultCount),
                style = MaterialTheme.typography.labelMedium,
                color = VeilColors.Muted,
                modifier = Modifier
                    .padding(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Small)
                    .testTag(SearchCountTag),
            )
        }
    }
}

@Composable
private fun SearchResultRow(page: SearchablePage, current: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(VeilShapes.ControlCorner),
        color = if (current) VeilColors.Raised else Color.Transparent,
        contentColor = if (enabled) VeilColors.Text else VeilColors.Muted,
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SearchResultTagPrefix + page.destination.id)
            .semantics { role = Role.Button },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = VeilSpacing.MinimumTouchTarget)
                .padding(horizontal = VeilSpacing.CompactHorizontal, vertical = VeilSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideIcon(page.destination.icon, contentDescription = null, tint = VeilColors.Muted)
            Spacer(Modifier.width(VeilSpacing.Medium))
            Column(Modifier.weight(1f)) {
                Text(page.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (page.description.isNotEmpty()) {
                    Text(
                        page.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = VeilColors.Muted,
                        modifier = Modifier.padding(top = VeilSpacing.Tiny),
                    )
                }
            }
        }
    }
}
