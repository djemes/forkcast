package app.forkcast.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.forkcast.R

/**
 * Settings' Basics and Advanced (roadmap 2.0 task 3.5): what most people need shows by default;
 * expert options wait behind one "Advanced" that starts collapsed and remembers itself across
 * rotation. [content] is laid out in a Column under the button.
 */
@Composable
fun AdvancedSection(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val state = stringResource(if (open) R.string.advanced_state_open else R.string.advanced_state_closed)
    Column(modifier) {
        TextButton(
            onClick = { open = !open },
            modifier = Modifier.padding(horizontal = 4.dp).heightIn(min = 48.dp).semantics { stateDescription = state },
        ) {
            Text(stringResource(R.string.advanced))
            Icon(
                painterResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        if (open) Column(verticalArrangement = Arrangement.spacedBy(0.dp), content = content)
    }
}
