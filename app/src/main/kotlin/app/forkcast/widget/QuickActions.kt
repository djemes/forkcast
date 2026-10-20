package app.forkcast.widget

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.forkcast.MainActivity
import app.forkcast.R
import app.forkcast.core.data.notify.Notifier
import kotlinx.coroutines.launch

/**
 * The "+1 glass of water" app shortcut: logs a glass without opening the app, confirms with a
 * short message and closes (an invisible activity, because shortcuts can only start activities).
 */
class AddWaterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val added = runCatching { WidgetData.addGlass(applicationContext) }
            updateAllWidgets(applicationContext)
            val text = added.fold(
                onSuccess = { WidgetData.today(applicationContext).let { s -> getString(R.string.shortcut_water_done, s.format.waterLiters(s.glassMl)) } },
                onFailure = { getString(R.string.shortcut_water_failed) },
            )
            Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}

/** Quick Settings tile base: opens Forkcast at [open] and collapses the shade (unlocking first if needed). */
abstract class OpenTile(private val open: String) : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { launch() } else launch()
    }

    // Below Android 14 the Intent overload is the only one there is.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launch() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(Notifier.EXTRA_OPEN, open)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, open.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** "Scan food" tile: straight to the barcode scanner. */
class ScanTileService : OpenTile(OPEN_SCAN)

/** "Photo meal" tile: straight to the AI photo camera. */
class PhotoTileService : OpenTile(OPEN_PHOTO)
