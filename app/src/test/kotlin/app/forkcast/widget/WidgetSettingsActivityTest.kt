package app.forkcast.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** REVIEW-2 S6: the exported widget settings screen only acts on Forkcast's own widgets. */
@RunWith(AndroidJUnit4::class)
class WidgetSettingsActivityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = AppWidgetManager.getInstance(context)

    private fun open(id: Int) = ActivityScenario.launchActivityForResult<WidgetSettingsActivity>(
        Intent(context, WidgetSettingsActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
    )

    private fun bound(provider: ComponentName, id: Int): Int {
        val shadow = shadowOf(manager)
        shadow.addInstalledProvider(AppWidgetProviderInfo().apply { this.provider = provider })
        shadow.setAllowedToBindAppWidgets(true)
        assertThat(manager.bindAppWidgetIdIfAllowed(id, provider)).isTrue()
        return id
    }

    @Test fun someoneElsesWidgetIdIsRefused() {
        val other = bound(ComponentName("com.example.other", "com.example.other.Widget"), id = 7)
        for (id in listOf(other, 4242)) {
            open(id).use { scenario ->
                assertThat(scenario.result.resultCode).isEqualTo(Activity.RESULT_CANCELED)
            }
        }
    }

    @Test fun forkcastWidgetOpensItsSettings() {
        val ours = bound(ComponentName(context, CaloriesWidgetReceiver::class.java), id = 8)
        open(ours).use { scenario ->
            scenario.onActivity { assertThat(it.isFinishing).isFalse() }
        }
    }
}
