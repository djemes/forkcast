package app.forkcast.ui.legal

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * TERMS.md and PRIVACY.md at the repository root are the app's legal texts, printed (roadmap 2.0,
 * tasks 4.1 and 4.4). After changing strings_legal.xml, run this test with -Dlegal.update=true
 * (or delete the files) to write them again.
 */
@RunWith(AndroidJUnit4::class)
class LegalDocsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun markdown(document: LegalDocument): String = buildString {
        append("# Forkcast — ").append(context.getString(document.title)).append("\n\n")
        append(context.getString(document.effective)).append("\n")
        document.sections.forEach { section ->
            append("\n## ").append(context.getString(section.heading)).append("\n\n")
            val lines = section.body.flatMap { context.getString(it).split('\n') }
            lines.forEachIndexed { i, line ->
                if (line.startsWith("• ")) {
                    append("- ").append(line.removePrefix("• ")).append("\n")
                } else {
                    // A paragraph after a list needs a blank line in Markdown.
                    if (i > 0) append("\n")
                    append(line).append("\n")
                }
            }
        }
    }

    private fun check(document: LegalDocument, name: String) {
        // Unit tests run in the module folder.
        val file = File("../$name")
        val expected = markdown(document)
        if (System.getProperty("legal.update") == "true" || !file.exists()) file.writeText(expected)
        assertWithMessage("$name is out of date: run LegalDocsTest with -Dlegal.update=true").that(file.readText()).isEqualTo(expected)
    }

    @Test fun termsMatchTheApp() = check(LegalTexts.terms, "TERMS.md")

    @Test fun privacyPolicyMatchesTheApp() = check(LegalTexts.privacy, "PRIVACY.md")

    /** The effective-date line names the version that start-up asks for. */
    @Test fun termsNameTheirVersion() {
        assertThat(context.getString(LegalTexts.terms.effective)).contains("Agreement version ${Agreement.VERSION}.")
    }
}
