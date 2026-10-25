package app.forkcast

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.ui.settings.LanguageScreen
import app.forkcast.ui.theme.ForkcastTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppLanguageTest {

    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun languagesComeFromLocalesConfig() {
        assertThat(AppLanguage.available(context)).containsExactly("en", "pt-BR").inOrder()
    }

    /** A language is files only: every one in locales_config has its values folder, and the other way round. */
    @Test fun everyListedLanguageHasItsStrings() {
        val res = generateSequence(File("").absoluteFile) { it.parentFile }
            .flatMap { sequenceOf(File(it, "src/main/res"), File(it, "app/src/main/res")) }
            .first { File(it, "values/strings.xml").exists() }
        val folders = res.listFiles().orEmpty().map { it.name }.filter { it.startsWith("values-") && File(res, "${it}/strings.xml").exists() }
            // values-pt-rBR → pt-BR, values-b+es+419 → es-419
            .map { it.removePrefix("values-") }
            .map { if (it.startsWith("b+")) it.removePrefix("b+").replace('+', '-') else it.replace("-r", "-") }
        assertThat(AppLanguage.available(context).filter { it != "en" }).containsExactlyElementsIn(folders)
    }

    @Test fun eachLanguageIsNamedInItself() {
        assertThat(AppLanguage.displayName("pt-BR")).isEqualTo("Português (Brasil)")
        assertThat(AppLanguage.displayName("en")).isEqualTo("English")
    }

    @Test fun pickerOffersThePhonesLanguageAndEachShippedOne() {
        var picked: String? = "unset"
        compose.setContent { ForkcastTheme { LanguageScreen(listOf("en", "pt-BR"), current = null, onBack = {}, onPick = { picked = it }) } }
        compose.onNodeWithText("The phone's language").assertIsSelected()
        compose.onNodeWithText("Português (Brasil)").performClick()
        assertThat(picked).isEqualTo("pt-BR")
    }
}
