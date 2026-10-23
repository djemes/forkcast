package app.forkcast.core.domain

import java.util.Locale

/**
 * How close a search is to a food saved from "Describe" (owner, 2026-10-23: "typing something
 * close to a previously described item should suggest the saved food first"). Compares the
 * meaningful words of the query with the food's name, brand and the text first described;
 * amounts ("2 slices") and small words don't count. Pure, so it's unit-tested.
 */
object DescribedMatch {
    /** Share of the query's words that must be found. */
    const val THRESHOLD = 0.6

    private val ignored = setOf(
        "a", "an", "the", "of", "with", "and", "or", "in", "on", "for", "to", "from", "my", "some",
        "slice", "slices", "piece", "pieces", "cup", "cups", "bowl", "plate", "serving", "servings", "large", "medium", "small",
        "de", "com", "e", "um", "uma", "fatia", "fatias",
    )

    fun words(text: String): List<String> = text.lowercase(Locale.ROOT)
        .split(Regex("""[^\p{L}\p{N}]+"""))
        .filter { it.length >= 2 && it !in ignored && it.any(Char::isLetter) }
        .map(::stem)

    /** 0..1: the share of the query's words found among [texts]' words (plural-insensitive). */
    fun score(query: String, texts: List<String>): Double {
        val q = words(query).distinct()
        if (q.isEmpty()) return 0.0
        val hay = texts.flatMap(::words).toSet()
        return q.count { it in hay }.toDouble() / q.size
    }

    private fun stem(w: String): String = when {
        w.length > 4 && w.endsWith("es") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") -> w.dropLast(1)
        else -> w
    }
}
