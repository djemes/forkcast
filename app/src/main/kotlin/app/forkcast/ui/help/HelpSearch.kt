package app.forkcast.ui.help

import java.text.Normalizer
import java.util.Locale

/** An entry with its text in the app's language, for searching. */
data class HelpText(val entry: HelpEntry, val question: String, val answer: String)

/**
 * Help's search: every word typed must appear in the question or the answer, ignoring case and
 * accents ("manutencao" finds "manutenção"). A blank query keeps everything.
 */
object HelpSearch {

    fun filter(texts: List<HelpText>, query: String): List<HelpText> {
        val words = fold(query).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return texts
        return texts.filter { t ->
            val haystack = fold(t.question + " " + t.answer)
            words.all { haystack.contains(it) }
        }
    }

    private val marks = Regex("\\p{Mn}+")

    private fun fold(s: String): String =
        marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
}
