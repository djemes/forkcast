package app.forkcast.ui.common

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.forkcast.R
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** "8:15 AM" (or "08:15") for minutes after midnight, in the phone's style. */
@Composable
fun mealTimeText(minute: Int): String {
    val locale = LocalLocale.current.platformLocale
    return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(LocalTime.of(minute / 60 % 24, minute % 60))
}

/**
 * When it was eaten (owner, 2026-10-23): a button showing the time; tapping opens the clock.
 * [latestMinute] is now on today, so no time can be in the future (like dates).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MealTimeField(minute: Int, onChange: (Int) -> Unit, latestMinute: Int? = null, modifier: Modifier = Modifier, label: String? = null) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label ?: stringResource(R.string.meal_time_label), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { open = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_schedule), contentDescription = null)
            Text(mealTimeText(minute), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (open) {
        val is24 = DateFormat.is24HourFormat(LocalContext.current)
        val state = rememberTimePickerState(initialHour = minute / 60, initialMinute = minute % 60, is24Hour = is24)
        val picked = state.hour * 60 + state.minute
        val future = latestMinute != null && picked > latestMinute
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(label ?: stringResource(R.string.meal_time_label)) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    TimePicker(state = state)
                    if (future) {
                        Row { Text(stringResource(R.string.meal_time_future), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onChange(picked); open = false }, enabled = !future) { Text(stringResource(R.string.ok)) } },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
