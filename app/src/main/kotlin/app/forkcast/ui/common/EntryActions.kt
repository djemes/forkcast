package app.forkcast.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.ui.theme.ForkcastTheme

/**
 * An entry's name with a small pencil after it (owner, 2026-10-30): tapping it edits the name in
 * place, with Done and Cancel. Renaming changes only this entry.
 */
@Composable
fun RenameableName(
    name: String,
    onRename: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    enabled: Boolean = true,
    heading: Boolean = false,
) {
    var editing by rememberSaveable { mutableStateOf(false) }
    if (!editing) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = style, modifier = Modifier.weight(1f, fill = false).then(if (heading) Modifier.semantics { heading() } else Modifier))
            if (enabled) {
                IconButton(onClick = { editing = true }) {
                    Icon(
                        painterResource(R.drawable.ic_edit),
                        contentDescription = stringResource(R.string.rename),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        return
    }
    var text by rememberSaveable { mutableStateOf(name) }
    val focus = remember { FocusRequester() }
    val done = {
        val clean = text.trim()
        if (clean.isNotEmpty()) {
            if (clean != name) onRename(clean)
            editing = false
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.rename_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { done() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
        )
        Row(Modifier.align(Alignment.End)) {
            TextButton(onClick = { text = name; editing = false }) { Text(stringResource(R.string.cancel)) }
            TextButton(onClick = done, enabled = text.isNotBlank()) { Text(stringResource(R.string.rename_done)) }
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/**
 * The ⋮ menu on an entry (AI review, edit sheet; owner, 2026-10-30): Save as food (until it is
 * one), ★ Favorite or Unfavorite, and Delete. Items left null aren't shown.
 */
@Composable
fun EntryMenu(
    onSaveAsFood: (() -> Unit)?,
    favorite: Boolean,
    onFavorite: (() -> Unit)?,
    onUnfavorite: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(painterResource(R.drawable.ic_more_vert), stringResource(R.string.entry_more)) }
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        onSaveAsFood?.let { save ->
            DropdownMenuItem(text = { Text(stringResource(R.string.entry_save_as_food)) }, onClick = { open = false; save() })
        }
        if (favorite) {
            onUnfavorite?.let { un ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.favorite_unfavorite)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_star_outline), contentDescription = null) },
                    onClick = { open = false; un() },
                )
            }
        } else {
            onFavorite?.let { fav ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.entry_favorite)) },
                    leadingIcon = { Icon(painterResource(R.drawable.ic_star), contentDescription = null, tint = ForkcastTheme.colors.pine) },
                    onClick = { open = false; fav() },
                )
            }
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = { open = false; onDelete() },
        )
    }
}
