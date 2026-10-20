package app.forkcast.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The paths opened many times a day (SPEC §9): cold start to Today, scrolling the day, the Log
 * sheet, food search, Trends and Settings. Runs against the emulator's existing data (an app that
 * is past onboarding); on a fresh install it records the startup path only.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        if (!device.wait(Until.hasObject(By.desc("Log")), WAIT_MS)) return@collect

        // Scroll Today down and back up.
        device.findObject(By.scrollable(true))?.let { list ->
            list.fling(androidx.test.uiautomator.Direction.DOWN)
            device.waitForIdle()
            list.fling(androidx.test.uiautomator.Direction.UP)
            device.waitForIdle()
        }

        // Log sheet → Search, type a query, back to Today.
        device.findObject(By.desc("Log"))?.click()
        if (device.wait(Until.hasObject(By.text("Search")), WAIT_MS)) {
            device.findObject(By.text("Search"))?.click()
            device.wait(Until.hasObject(By.clazz("android.widget.EditText")), WAIT_MS)
            device.findObject(By.clazz("android.widget.EditText"))?.text = "rice"
            device.waitForIdle()
            device.pressBack() // keyboard
            device.pressBack() // search
            device.waitForIdle()
        }

        // Trends, then Settings.
        openAndReturn("Open trends")
        openAndReturn("Settings")
    }

    private fun androidx.benchmark.macro.MacrobenchmarkScope.openAndReturn(description: String) {
        val button = device.findObject(By.desc(description)) ?: return
        button.click()
        device.waitForIdle()
        Thread.sleep(SETTLE_MS)
        device.pressBack()
        device.wait(Until.hasObject(By.desc("Log")), WAIT_MS)
    }

    private companion object {
        const val PACKAGE = "app.forkcast"
        const val WAIT_MS = 5_000L
        const val SETTLE_MS = 800L
    }
}
