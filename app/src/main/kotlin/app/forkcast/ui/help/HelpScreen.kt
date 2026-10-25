package app.forkcast.ui.help

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.forkcast.R

@Composable
fun HelpRoute(onBack: () -> Unit, entries: List<HelpEntry>, viewModel: HelpViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HelpScreen(state, viewModel::onEvent, onBack, entries)
}

/**
 * Settings › Help (roadmap 2.0 task 3.4): questions grouped by topic, then the glossary, with a
 * search over questions and answers. Answers open in place; a link from an error opens one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(state: HelpUiState, onEvent: (HelpEvent) -> Unit, onBack: () -> Unit, entries: List<HelpEntry> = HelpEntry.entries) {
    val res = LocalResources.current
    val texts = remember(entries, res) { entries.map { HelpText(it, res.getString(it.question), res.getString(it.answer)) } }
    val shown = remember(texts, state.query) { HelpSearch.filter(texts, state.query) }
    val rows = remember(shown) { helpRows(shown) }
    val list = rememberLazyListState()
    LaunchedEffect(state.focus) {
        val focus = state.focus ?: return@LaunchedEffect
        // +1: the search field is the first item.
        val index = rows.indexOfFirst { it is HelpRow.Item && it.text.entry == focus }
        if (index >= 0) list.scrollToItem(index + 1)
        onEvent(HelpEvent.FocusShown)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.help_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.action_back)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(bottom = 24.dp),
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
        ) {
            item { SearchField(state.query) { onEvent(HelpEvent.Search(it)) } }
            if (rows.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.help_no_match, state.query.trim()),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            helpItems(rows, state.expanded, onEvent)
            item {
                Text(
                    stringResource(R.string.not_medical_advice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

/** A topic heading or one entry, flattened for the list. */
private sealed interface HelpRow {
    data class Topic(val topic: HelpTopic, val first: Boolean) : HelpRow
    data class Item(val text: HelpText) : HelpRow
}

private fun helpRows(shown: List<HelpText>): List<HelpRow> =
    shown.groupBy { it.entry.topic }.toSortedMap().entries.flatMapIndexed { i, (topic, texts) ->
        listOf(HelpRow.Topic(topic, first = i == 0)) + texts.map { HelpRow.Item(it) }
    }

private fun LazyListScope.helpItems(rows: List<HelpRow>, expanded: Set<HelpEntry>, onEvent: (HelpEvent) -> Unit) {
    items(rows, key = { row -> if (row is HelpRow.Topic) "topic:" + row.topic.name else (row as HelpRow.Item).text.entry.name }) { row ->
        when (row) {
            is HelpRow.Topic -> TopicHeading(row.topic, row.first)
            is HelpRow.Item -> EntryRow(row.text, open = row.text.entry in expanded) { onEvent(HelpEvent.Toggle(row.text.entry)) }
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(R.string.help_search)) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(painterResource(R.drawable.ic_close), stringResource(R.string.search_clear)) }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun TopicHeading(topic: HelpTopic, first: Boolean) {
    Column {
        if (!first) HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            stringResource(topic.title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
        )
    }
}

/** The question as a row that opens and closes its answer; the arrow and the state say which. */
@Composable
private fun EntryRow(text: HelpText, open: Boolean, onToggle: () -> Unit) {
    val state = stringResource(if (open) R.string.help_state_open else R.string.help_state_closed)
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = state }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(text.question, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(painterResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more), contentDescription = null)
        }
        if (open) {
            Text(
                text.answer,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}
