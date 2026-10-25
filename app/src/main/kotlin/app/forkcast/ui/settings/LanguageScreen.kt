package app.forkcast.ui.settings

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import app.forkcast.AppLanguage
import app.forkcast.R

/** Settings › Language (roadmap 2.0, task 1.6): only listed when Forkcast ships more than one language. */
@Composable
fun LanguageRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val languages = remember { AppLanguage.available(context) }
    var current by remember { mutableStateOf(AppLanguage.current(context)) }
    LanguageScreen(
        languages = languages,
        current = current,
        onBack = onBack,
        onPick = { tag ->
            current = tag
            AppLanguage.set(context, tag)
            // Android 13+ recreates the app's activities itself; before that, this screen's activity is redrawn here.
            if (Build.VERSION.SDK_INT < 33) (context as? Activity)?.recreate()
        },
    )
}

@Composable
fun LanguageScreen(languages: List<String>, current: String?, onBack: () -> Unit, onPick: (String?) -> Unit) {
    SettingsScaffold(title = stringResource(R.string.settings_language), onBack = onBack) {
        Column(Modifier.selectableGroup()) {
            Option(stringResource(R.string.language_device), selected = current == null) { onPick(null) }
            languages.forEach { tag -> Option(AppLanguage.displayName(tag), selected = current == tag) { onPick(tag) } }
        }
    }
}

@Composable
private fun Option(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 16.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 16.dp))
    }
}
