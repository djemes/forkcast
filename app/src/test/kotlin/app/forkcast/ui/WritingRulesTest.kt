package app.forkcast.ui

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * CLAUDE.md's writing rules, checked on every string in both languages so a new one can't slip
 * past review: no exclamation marks, no medical claims, and the plain words chosen in roadmap
 * 2.0 tasks 3.5 and 3.6 ("limit", not "quota"; "AI service", not "profile" for an AI).
 */
class WritingRulesTest {

    /** Text of every <string> and plural <item>, by "file: name". */
    private fun strings(): Map<String, String> {
        // Unit tests run in the module folder.
        val folders = listOf("src/main/res/values", "src/main/res/values-pt-rBR", "src/debug/res/values", "src/debug/res/values-pt-rBR")
        val parser = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        return folders.flatMap { folder ->
            File(folder).listFiles { f -> f.name.startsWith("strings") && f.name.endsWith(".xml") }.orEmpty().flatMap { file ->
                val doc = parser.parse(file)
                listOf("string", "item").flatMap { tag ->
                    val nodes = doc.getElementsByTagName(tag)
                    (0 until nodes.length).map { i ->
                        val node = nodes.item(i)
                        val name = node.attributes.getNamedItem("name")?.nodeValue ?: node.parentNode.attributes.getNamedItem("name")?.nodeValue
                        "$folder/${file.name}: $name" to node.textContent
                    }
                }
            }
        }.toMap()
    }

    @Test fun stringsWereFound() {
        assertWithMessage("strings read").that(strings().size).isGreaterThan(500)
    }

    @Test fun noExclamationMarks() {
        val found = strings().filterValues { it.contains('!') }
        assertWithMessage("strings with \"!\"").that(found.keys).isEmpty()
    }

    @Test fun noMedicalClaims() {
        val words = Regex(
            "\\b(treat|treats|treating|cure|cures|diagnos\\w*|prescri\\w*|medical-grade|clinically|tratar|trata|curar|cura|diagnostic\\w*|prescrev\\w*|clinicamente)\\b",
            RegexOption.IGNORE_CASE,
        )
        // The disclaimer says what Forkcast doesn't do ("doesn't diagnose, treat, cure…").
        val found = strings().filterValues { words.containsMatchIn(it) }.filterKeys { !it.endsWith(": terms_not_body") }
        assertWithMessage("strings with medical claims").that(found.keys).isEmpty()
    }

    @Test fun plainWordsForAiLimitsAndServices() {
        val jargon = Regex("\\b(quota|cota|AI profiles?|perfis de IA|perfil de IA|AI providers?|provedor(es)? de IA)\\b", RegexOption.IGNORE_CASE)
        // The legal texts (English only, reviewed by a lawyer) keep the precise word "provider".
        val found = strings().filterValues { jargon.containsMatchIn(it) }.filterKeys { "/strings_legal.xml:" !in it }
        assertWithMessage("strings with \"quota\", \"AI profile\" or \"AI provider\"").that(found.keys).isEmpty()
    }
}
