package app.forkcast.core.domain

import app.forkcast.core.model.NutrientKey

/** What could be read off a Nutrition Facts panel. Values are per serving, as printed. */
data class ParsedLabel(
    val servingLabel: String?,
    val servingGrams: Double?,
    val perServing: Map<NutrientKey, Double>,
) {
    /** Calories and serving weight are what make the result usable; everything else is a bonus. */
    val isUsable: Boolean get() = NutrientKey.ENERGY_KCAL in perServing && servingGrams != null
}

/**
 * Reads US-style Nutrition Facts text as returned by on-device OCR (SPEC §4.4).
 * OCR is noisy, so the parser tolerates: O/o for 0 and l/I for 1 next to units ("Og", "lg"),
 * labels and numbers split across lines, missing spaces ("Sodium160mg"), and %DV columns.
 * Everything it can't read is left out; the user reviews an editable form afterwards.
 */
object NutritionLabelParser {

    private data class Rule(val key: NutrientKey, val label: Regex, val units: Set<String>)

    private const val MASS = "(?:g|mg|mcg|µg|ug)"

    // Order matters: more specific labels first (e.g. "Saturated Fat" before "Total Fat").
    private val rules = listOf(
        Rule(NutrientKey.SAT_FAT_G, Regex("sat(?:urated)?\\.?\\s*fat", RegexOption.IGNORE_CASE), setOf("g")),
        Rule(NutrientKey.TRANS_FAT_G, Regex("trans\\s*fat", RegexOption.IGNORE_CASE), setOf("g")),
        Rule(NutrientKey.POLY_FAT_G, Regex("poly(?:unsaturated)?\\.?\\s*fat", RegexOption.IGNORE_CASE), setOf("g")),
        Rule(NutrientKey.MONO_FAT_G, Regex("mono(?:unsaturated)?\\.?\\s*fat", RegexOption.IGNORE_CASE), setOf("g")),
        Rule(NutrientKey.FAT_G, Regex("(?:total\\s*)?fat", RegexOption.IGNORE_CASE), setOf("g")),
        Rule(NutrientKey.CHOLESTEROL_MG, Regex("chol(?:esterol|est\\.?)", RegexOption.IGNORE_CASE), setOf("mg")),
        Rule(NutrientKey.SODIUM_MG, Regex("sodium", RegexOption.IGNORE_CASE), setOf("mg")),
        Rule(NutrientKey.FIBER_G, Regex("(?:dietary\\s*)?fib(?:er|re)", RegexOption.IGNORE_CASE), setOf("g")),
        Rule(NutrientKey.ADDED_SUGARS_G, Regex("added\\s*sugars?", RegexOption.IGNORE_CASE), setOf("g")),
        Rule(NutrientKey.SUGARS_G, Regex("(?:total\\s*)?sugars?", RegexOption.IGNORE_CASE), setOf("g")),
        Rule(NutrientKey.CARBS_G, Regex("(?:total\\s*)?carb(?:ohydrate|\\.)?s?", RegexOption.IGNORE_CASE), setOf("g")),
        Rule(NutrientKey.PROTEIN_G, Regex("protein", RegexOption.IGNORE_CASE), setOf("g")),
        Rule(NutrientKey.VITAMIN_D_UG, Regex("vit(?:amin|\\.)?\\s*d", RegexOption.IGNORE_CASE), setOf("mcg", "µg", "ug")),
        Rule(NutrientKey.CALCIUM_MG, Regex("calcium", RegexOption.IGNORE_CASE), setOf("mg")),
        Rule(NutrientKey.IRON_MG, Regex("iron", RegexOption.IGNORE_CASE), setOf("mg")),
        Rule(NutrientKey.POTASSIUM_MG, Regex("potas(?:sium|\\.)", RegexOption.IGNORE_CASE), setOf("mg")),
    )

    private val number = "(\\d+(?:[.,]\\d+)?)"
    private val amountWithUnit = Regex("(?<![\\d%])<?\\s*$number\\s*($MASS)\\b", RegexOption.IGNORE_CASE)
    private val calories = Regex("calories\\s*(?:per\\s*serving)?\\s*:?\\s*$number", RegexOption.IGNORE_CASE)
    private val servingSize = Regex(
        "serving\\s*size\\s*:?\\s*(.*?)\\s*\\(\\s*$number\\s*(g|ml)\\s*\\)",
        RegexOption.IGNORE_CASE,
    )
    private val servingSizeGramsOnly = Regex("serving\\s*size\\s*:?\\s*$number\\s*(g|ml)\\b", RegexOption.IGNORE_CASE)

    fun parse(ocrText: String): ParsedLabel {
        val lines = ocrText.lines().map { clean(it) }.filter { it.isNotBlank() }
        val text = lines.joinToString("\n")
        val found = LinkedHashMap<NutrientKey, Double>()

        // Calories: on the same line, or the number alone on the next line ("Calories\n230").
        lines.forEachIndexed { i, line ->
            if (NutrientKey.ENERGY_KCAL in found) return@forEachIndexed
            if (Regex("calories\\s*from\\s*fat", RegexOption.IGNORE_CASE).containsMatchIn(line)) return@forEachIndexed
            calories.find(line)?.let { found[NutrientKey.ENERGY_KCAL] = num(it.groupValues[1]); return@forEachIndexed }
            if (line.trim().equals("calories", ignoreCase = true) || line.trim().equals("amount per serving calories", ignoreCase = true)) {
                lines.getOrNull(i + 1)?.trim()?.let { next ->
                    Regex("^$number$").find(next)?.let { found[NutrientKey.ENERGY_KCAL] = num(it.groupValues[1]) }
                }
            }
        }

        lines.forEachIndexed { i, line ->
            val claimed = mutableListOf<IntRange>()
            for (rule in rules) {
                if (rule.key in found) continue
                val m = rule.label.findAll(line).firstOrNull { match -> claimed.none { it.overlaps(match.range) } } ?: continue
                // "Total Fat" must not grab "Saturated Fat"/"Trans Fat" text, which rules above already claimed.
                if (rule.key == NutrientKey.FAT_G && Regex("(sat|trans|poly|mono)", RegexOption.IGNORE_CASE).containsMatchIn(line.substring(0, m.range.first).takeLast(14))) continue
                if (rule.key == NutrientKey.SUGARS_G && Regex("added", RegexOption.IGNORE_CASE).containsMatchIn(line.substring(0, m.range.first).takeLast(8))) continue
                val after = line.substring(m.range.last + 1)
                val amount = firstAmount(after, rule.units)
                    // "Includes 10g Added Sugars": amount before the label.
                    ?: if (rule.key == NutrientKey.ADDED_SUGARS_G) firstAmount(line.substring(0, m.range.first), rule.units) else null
                    // Label and value split across lines.
                    ?: lines.getOrNull(i + 1)?.let { next -> if (rules.none { it.label.containsMatchIn(next) }) firstAmount(next, rule.units) else null }
                if (amount != null) {
                    found[rule.key] = amount
                    claimed += m.range
                }
            }
        }

        val serving = servingSize.find(text)
        val servingGrams = serving?.let { num(it.groupValues[2]) } ?: servingSizeGramsOnly.find(text)?.let { num(it.groupValues[1]) }
        val servingLabel = serving?.groupValues?.get(1)?.trim()?.trimEnd(',', ':')?.takeIf { it.isNotBlank() }
        return ParsedLabel(servingLabel, servingGrams, found)
    }

    /** First "number unit" whose unit is one of [units]; converts mcg to µg and g/mg as needed. */
    private fun firstAmount(text: String, units: Set<String>): Double? {
        for (m in amountWithUnit.findAll(text)) {
            val value = num(m.groupValues[1])
            val unit = m.groupValues[2].lowercase().replace("µ", "u")
            val normalized = units.map { it.replace("µ", "u") }.toSet()
            if (unit in normalized) return value
            // A gram value where mg is expected (rare) or the reverse.
            if (unit == "g" && "mg" in normalized) return value * 1000
            if (unit == "mg" && "g" in normalized) return value / 1000
        }
        return null
    }

    /** Fixes the usual OCR confusions next to units: "Og"→"0g", "lg"/"Ig"→"1g", "5 g" spacing kept. */
    private fun clean(line: String): String = line
        .replace(Regex("(?<=[\\s\\d.(])[Oo](?=\\s*(?:g|mg|mcg)\\b)"), "0")
        .replace(Regex("(?<=[\\s(])[lI](?=\\s*(?:g|mg|mcg)\\b)"), "1")
        .replace(Regex("(?<=\\d)[Oo](?=\\d|\\s*(?:g|mg|mcg)\\b)"), "0")
        .replace(' ', ' ')

    private fun num(s: String): Double = s.replace(',', '.').toDouble()

    private fun IntRange.overlaps(other: IntRange) = first <= other.last && other.first <= last
}
