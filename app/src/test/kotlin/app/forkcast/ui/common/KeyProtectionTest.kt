package app.forkcast.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** REVIEW-2 S1, S2: a shown key can't be screenshotted, and a copied key leaves the clipboard. */
@RunWith(AndroidJUnit4::class)
class KeyProtectionTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun secure() = compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test fun windowIsSecureOnlyWhileAKeyIsShown() {
        var first by mutableStateOf(false)
        var second by mutableStateOf(false)
        compose.setContent {
            SecureWhile(first)
            SecureWhile(second)
        }
        compose.waitForIdle()
        assertThat(secure()).isFalse()
        first = true
        compose.waitForIdle()
        assertThat(secure()).isTrue()
        second = true
        first = false
        compose.waitForIdle()
        assertThat(secure()).isTrue()
        second = false
        compose.waitForIdle()
        assertThat(secure()).isFalse()
    }

    @Test fun clearingTouchesOnlyOurOwnCopy() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("k", "AIza-secret"))
        KeyClipboard.clearNow(context, "AIza-secret")
        assertThat(cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()).isNotEqualTo("AIza-secret")

        cm.setPrimaryClip(ClipData.newPlainText("note", "shopping list"))
        KeyClipboard.clearNow(context, "AIza-secret")
        assertThat(cm.primaryClip?.getItemAt(0)?.text.toString()).isEqualTo("shopping list")
    }
}
