package app.forkcast.ui.food

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.forkcast.R
import app.forkcast.ui.theme.ForkcastTheme

/** Filled when [favorite]; a 48 dp button when it can be changed here. */
@Composable
fun FavoriteStar(favorite: Boolean, onToggle: (() -> Unit)?) {
    val icon = painterResource(if (favorite) R.drawable.ic_star else R.drawable.ic_star_outline)
    val tint = if (favorite) ForkcastTheme.colors.apricot else MaterialTheme.colorScheme.onSurfaceVariant
    if (onToggle != null) {
        IconButton(onClick = onToggle) {
            Icon(icon, stringResource(if (favorite) R.string.food_favorite_remove else R.string.food_favorite_add), tint = tint)
        }
    } else if (favorite) {
        Icon(icon, stringResource(R.string.badge_favorite), tint = tint, modifier = Modifier.padding(start = 8.dp).size(18.dp))
    }
}
