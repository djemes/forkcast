package app.forkcast.core.domain

import java.util.Locale

/**
 * The name an AI estimate's entry gets (owner, 2026-10-30): the model's meal_name when it says
 * what the food is, else one built from the main components ("Banana", "Chicken, rice & guacamole").
 * Names like "Snack" or "Lunch at 13:15" say nothing a diary can use.
 */
object MealNaming {

    /** A component as the model returned it: its name, calories, and whether it's a hidden ingredient (oil, dressing). */
    data class Part(val name: String, val kcal: Double, val hidden: Boolean = false)

    /** The words for two and three parts, from the app's language ("%1$s & %2$s", "%1$s, %2$s & %3$s"). */
    data class Joiners(val two: String, val three: String)

    /** Whole names that say nothing about the food, in the shipped languages (lowercase). */
    private val GENERIC_NAMES = setOf(
        "meal", "food", "plate", "dish", "snack", "snacks", "breakfast", "lunch", "dinner", "supper", "brunch",
        "photo meal", "described meal", "my meal", "a meal",
        "refeição", "comida", "prato", "lanche", "lanches", "café da manhã", "almoço", "jantar", "ceia",
        "refeição por foto", "refeição descrita", "minha refeição",
    )

    /** Words that add nothing on their own ("at", "my"); a name made only of these and generic words is generic. */
    private val FILLER = setOf(
        "meal", "food", "plate", "dish", "snack", "snacks", "breakfast", "lunch", "dinner", "supper", "brunch",
        "at", "the", "my", "a", "an", "of", "for", "today", "photo", "described",
        "refeição", "comida", "prato", "lanche", "lanches", "almoço", "jantar", "ceia", "às", "as", "de", "do", "da", "no", "na",
        "minha", "meu", "hoje", "foto", "descrita",
    )

    /** "13:15", "1:05", "3pm", "3 p.m.", "13h15", "13h". */
    private val TIME = Regex("""\b\d{1,2}:\d{2}\b|\b\d{1,2}\s?([ap]\.?m\.?)(?=\W|$)|\b\d{1,2}h(\d{2})?\b""", RegexOption.IGNORE_CASE)

    private val WORD = Regex("""[\p{L}']+""")

    /** True when [name] is blank, one of the user's meal slots, a time, or only words like "meal" and "lunch". */
    fun isGeneric(name: String?, slotNames: Collection<String>): Boolean {
        val n = name?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (n.isEmpty()) return true
        val slots = slotNames.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        if (n in GENERIC_NAMES || n in slots) return true
        if (TIME.containsMatchIn(n)) return true
        val slotWords = slots.flatMap { s -> WORD.findAll(s).map { it.value } }.toSet()
        val words = WORD.findAll(n).map { it.value }.toList()
        return words.isEmpty() || words.all { it in FILLER || it in slotWords }
    }

    /**
     * The model's [modelName] if it isn't generic, else the main [parts] by calories: the only one,
     * or up to three joined with [joiners]. Hidden ingredients (oil, dressing) aren't named unless
     * they're all there is. Null when there's nothing to name it from.
     */
    fun name(modelName: String?, parts: List<Part>, slotNames: Collection<String>, joiners: Joiners, locale: Locale): String? {
        modelName?.trim()?.takeUnless { isGeneric(it, slotNames) }?.let { return it }
        return fromParts(parts, joiners, locale)
    }

    fun fromParts(parts: List<Part>, joiners: Joiners, locale: Locale): String? {
        val named = parts.filter { it.name.isNotBlank() }
        val main = named.filterNot { it.hidden }.ifEmpty { named }
        val names = main.sortedByDescending { it.kcal }.map { it.name.trim() }.distinctBy { it.lowercase(locale) }.take(MAX_PARTS)
        val joined = when (names.size) {
            0 -> return null
            1 -> names[0]
            2 -> joiners.two.format(locale, names[0], lowerFirst(names[1], locale))
            else -> joiners.three.format(locale, names[0], lowerFirst(names[1], locale), lowerFirst(names[2], locale))
        }
        return joined.replaceFirstChar { it.titlecase(locale) }
    }

    /**
     * "Rice" → "rice" after the first part; a name that looks like a proper noun or an acronym
     * ("BBQ sauce", "McDonald's fries") keeps its capitals.
     */
    private fun lowerFirst(s: String, locale: Locale): String {
        val first = s.substringBefore(' ')
        val looksPlain = first.length > 1 && first.drop(1).none { it.isUpperCase() }
        return if (looksPlain) s.replaceFirstChar { it.lowercase(locale) } else s
    }

    private const val MAX_PARTS = 3
}
