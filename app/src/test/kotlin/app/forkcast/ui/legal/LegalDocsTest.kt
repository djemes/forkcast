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
 * TERMS.md and PRIVACY.md at the repository root, and site/terms.html and site/privacy.html (the
 * Pages copy, Phase 5), are the app's legal texts, printed (roadmap 2.0, tasks 4.1 and 4.4).
 * After changing strings_legal.xml, run this test with -Dlegal.update=true (or delete the files)
 * to write them again.
 */
@RunWith(AndroidJUnit4::class)
class LegalDocsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Each section's heading and its lines, bullets marked by a leading "• ". */
    private fun sections(document: LegalDocument): List<Pair<String, List<String>>> = document.sections.map { section ->
        context.getString(section.heading) to section.body.flatMap { context.getString(it).split('\n') }
    }

    private fun markdown(document: LegalDocument): String = buildString {
        append("# Forkcast — ").append(context.getString(document.title)).append("\n\n")
        append(context.getString(document.effective)).append("\n")
        sections(document).forEach { (heading, lines) ->
            append("\n## ").append(heading).append("\n\n")
            lines.forEachIndexed { i, line ->
                if (line.startsWith(BULLET)) {
                    append("- ").append(line.removePrefix(BULLET)).append("\n")
                } else {
                    // A paragraph after a list needs a blank line in Markdown.
                    if (i > 0) append("\n")
                    append(line).append("\n")
                }
            }
        }
    }

    private fun html(document: LegalDocument): String = buildString {
        val title = context.getString(document.title)
        append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
        append("  <meta charset=\"utf-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        append("  <title>Forkcast ").append(escape(title)).append("</title>\n")
        append("  <link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n<main>\n")
        append("  <h1>").append(escape(title)).append("</h1>\n")
        append("  <p class=\"lead\">").append(escape(context.getString(document.effective))).append("</p>\n")
        sections(document).forEach { (heading, lines) ->
            append("\n  <h2>").append(escape(heading)).append("</h2>\n")
            var inList = false
            lines.forEach { line ->
                val bullet = line.startsWith(BULLET)
                if (bullet && !inList) append("  <ul>\n")
                if (!bullet && inList) append("  </ul>\n")
                inList = bullet
                if (bullet) append("    <li>").append(linked(line.removePrefix(BULLET))).append("</li>\n")
                else append("  <p>").append(linked(line)).append("</p>\n")
            }
            if (inList) append("  </ul>\n")
        }
        append("\n  <footer class=\"muted\"><a href=\"index.html\">Forkcast home</a></footer>\n</main>\n</body>\n</html>\n")
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun linked(text: String) = URL.replace(escape(text)) { "<a href=\"${it.value}\">${it.value}</a>" }

    private fun check(expected: String, name: String) {
        // Unit tests run in the module folder.
        val file = File("../$name")
        if (System.getProperty("legal.update") == "true" || !file.exists()) file.writeText(expected)
        assertWithMessage("$name is out of date: run LegalDocsTest with -Dlegal.update=true").that(file.readText()).isEqualTo(expected)
    }

    @Test fun termsMatchTheApp() = check(markdown(LegalTexts.terms), "TERMS.md")

    @Test fun privacyPolicyMatchesTheApp() = check(markdown(LegalTexts.privacy), "PRIVACY.md")

    @Test fun sitePagesMatchTheApp() {
        check(html(LegalTexts.terms), "site/terms.html")
        check(html(LegalTexts.privacy), "site/privacy.html")
    }

    /** The effective-date line names the version that start-up asks for. */
    @Test fun termsNameTheirVersion() {
        assertThat(context.getString(LegalTexts.terms.effective)).contains("Agreement version ${Agreement.VERSION}.")
    }

    private companion object {
        const val BULLET = "• "
        val URL = Regex("https://[^\\s<]+[^\\s<.,;)]")
    }
}
