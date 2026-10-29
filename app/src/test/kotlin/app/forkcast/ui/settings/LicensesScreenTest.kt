package app.forkcast.ui.settings

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Roadmap 2.0 tasks 4.5 and 4.6: licenses, attributions, the non-affiliation note (all offline) and the donation row. */
@RunWith(AndroidJUnit4::class)
class LicensesScreenTest {

    @get:Rule val compose = createComposeRule()

    @Test fun aboutShowsAttributionsAndOpensLicenses() {
        var opened = 0
        compose.setContent { ForkcastTheme { AboutScreen(versionName = "1.8.0", versionCode = 1, onBack = {}, onOpenLicenses = { opened++ }) } }
        compose.onNodeWithText("Licenses").performScrollTo().performClick()
        compose.onNodeWithText("Food data from Open Food Facts", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Food data from USDA FoodData Central", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("not affiliated with or endorsed by", substring = true).performScrollTo().assertIsDisplayed()
        assertThat(opened).isEqualTo(1)
    }

    /** Roadmap 2.0 task 4.6: one voluntary "Support Forkcast" row, opening the donation page in the browser. */
    @Test fun supportRowOpensTheDonationPage() {
        val opened = mutableListOf<String>()
        val uriHandler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides uriHandler) {
                ForkcastTheme { AboutScreen(versionName = "1.8.0", versionCode = 1, onBack = {}, donationUrl = "https://ko-fi.com/example") }
            }
        }
        compose.onNodeWithText("Support Forkcast").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("unlock anything", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Leave a tip").performScrollTo().performClick()
        assertThat(opened).containsExactly("https://ko-fi.com/example")
    }

    /** No URL set yet (or the Play build): no row at all. */
    @Test fun supportRowHiddenWithoutADonationPage() {
        compose.setContent { ForkcastTheme { AboutScreen(versionName = "1.8.0", versionCode = 1, onBack = {}, donationUrl = null) } }
        compose.onNodeWithText("Support Forkcast").assertDoesNotExist()
        compose.onNodeWithText("Leave a tip").assertDoesNotExist()
    }

    @Test fun licensesListTheAppLicenseAndTheLibraries() {
        var opened = 0
        compose.setContent { ForkcastTheme { LicensesScreen(onBack = {}, onOpenAppLicense = { opened++ }) } }
        compose.onNodeWithText("GNU General Public License, version 3", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Read Forkcast's license").performClick()
        assertThat(opened).isEqualTo(1)
        // Generated from the dependencies at build time (loads asynchronously); the font comes from config/aboutlibraries.
        compose.waitUntil(5_000) {
            runCatching { compose.onNode(hasScrollAction()).performScrollToNode(hasText("Manrope")) }.isSuccess
        }
        compose.onNodeWithText("Manrope").assertIsDisplayed()
    }

    @Test fun appLicenseIsBundledFromTheRepoRoot() {
        val assets = ApplicationProvider.getApplicationContext<Context>().assets
        val gpl = assets.open("licenses/LICENSE").bufferedReader().use { it.readText() }
        val permission = assets.open("licenses/LICENSE-ADDITIONAL-PERMISSION.md").bufferedReader().use { it.readText() }
        assertThat(gpl).contains("GNU GENERAL PUBLIC LICENSE")
        assertThat(gpl).contains("Version 3, 29 June 2007")
        assertThat(permission).contains("Additional permission under GNU GPL version 3 section 7")
    }

    @Test fun licenseTextReflowsIntoParagraphs() {
        val text = "# Forkcast license\n\nCopyright (C) 2026\n\nForkcast is free software: see\n[LICENSE](LICENSE), with the\r\n  permission below.\n\n\n## Additional permission\n"
        assertThat(licenseParagraphs(text)).containsExactly(
            LicenseParagraph("Forkcast license", heading = true),
            LicenseParagraph("Copyright (C) 2026"),
            LicenseParagraph("Forkcast is free software: see LICENSE, with the permission below."),
            LicenseParagraph("Additional permission", heading = true),
        ).inOrder()
    }
}
