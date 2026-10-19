package app.forkcast.ui.photo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.core.model.Food
import app.forkcast.core.model.kcal
import app.forkcast.core.network.FoodApiError
import app.forkcast.ui.format.UnitFormatter
import kotlinx.coroutines.delay

/** The "Change match" search: USDA plus your own foods, for one component. */
data class MatchSearchState(
    val componentId: String,
    val componentName: String,
    val query: String,
    val loading: Boolean = false,
    val results: List<Food> = emptyList(),
    val error: FoodApiError? = null,
)

/**
 * Pick the food an AI component really is (SPEC §4.6 step 8, "tap to change match"). The query
 * starts as the model's USDA search; results show kcal per 100 g so the right one is easy to spot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchSearchSheet(
    state: MatchSearchState,
    f: UnitFormatter,
    onQuery: (String) -> Unit,
    onPick: (Food) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(state.componentId) { mutableStateOf(state.query) }
    // Search as you type, after a short pause.
    LaunchedEffect(text) {
        delay(400)
        if (text.isNotBlank() && text != state.query) onQuery(text)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(stringResource(R.string.review_match_title, state.componentName), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.review_match_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onQuery(text) }),
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                state.loading -> Text(stringResource(R.string.search_loading), style = MaterialTheme.typography.bodyMedium)
                state.error is FoodApiError.Offline && state.results.isEmpty() -> Text(stringResource(R.string.search_offline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                state.error != null && state.results.isEmpty() -> Text(stringResource(R.string.search_failed, stringResource(R.string.search_usda)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                state.results.isEmpty() -> Text(stringResource(R.string.search_no_results), style = MaterialTheme.typography.bodyMedium)
            }
            state.results.forEach { food ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clickable(role = Role.Button) { onPick(food) }
                        .padding(vertical = 8.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(food.name, style = MaterialTheme.typography.bodyLarge)
                        food.brand?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Text(
                        stringResource(R.string.review_match_per_100g, f.energy(food.nutrientsPer100g.kcal)),
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
