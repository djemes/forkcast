package app.forkcast.core.domain

import kotlin.math.abs

/**
 * Scoring USDA candidates for an AI component (SPEC §4.6.1): token overlap with the model's
 * `usda_query` and preparation, FNDDS preferred for mixed dishes and Foundation/SR Legacy for
 * single ingredients, then a ±40% kcal/g sanity check against the model's own estimate.
 */
object AiMatching {
    const val THRESHOLD = 0.5
    const val KCAL_TOLERANCE = 0.40

    private val STOPWORDS = setOf("and", "with", "or", "the", "a", "an", "of", "in", "on", "for", "to", "from", "ns", "as", "nfs", "type")
    private val DISH_WORDS = setOf(
        "sandwich", "burger", "pizza", "soup", "stew", "salad", "burrito", "taco", "pasta", "lasagna", "casserole",
        "curry", "stir", "fry", "wrap", "sushi", "omelet", "omelette", "chili", "pie", "dish", "bowl", "noodles",
    )
    private val PREP_SYNONYMS = mapOf(
        "fried" to setOf("fried", "pan"),
        "roasted" to setOf("roasted", "baked", "broiled"),
        "baked" to setOf("baked", "roasted"),
        "grilled" to setOf("grilled", "broiled"),
        "boiled" to setOf("boiled", "cooked", "steamed"),
        "steamed" to setOf("steamed", "cooked", "boiled"),
        "raw" to setOf("raw", "fresh"),
    )

    fun tokens(text: String): List<String> = text.lowercase()
        .split(Regex("[^a-z0-9%]+"))
        .filter { it.isNotBlank() && it !in STOPWORDS }
        .map { if (it.length > 3 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }

    /** Query normalization for the match cache. */
    fun cacheKey(query: String, preparation: String): String =
        (tokens(query) + listOf("|") + tokens(preparation)).joinToString(" ")

    fun isMixedDish(query: String): Boolean {
        val t = tokens(query)
        return t.any { it in DISH_WORDS } || t.size >= 6
    }

    /** 0..1+: how well [candidateName] answers [query]. */
    fun score(query: String, preparation: String, candidateName: String, dataType: String?): Double {
        val q = tokens(query).toSet()
        if (q.isEmpty()) return 0.0
        val c = tokens(candidateName).toSet()
        val overlap = q.count { it in c }.toDouble()
        val recall = overlap / q.size
        val precision = if (c.isEmpty()) 0.0 else overlap / c.size
        var score = 0.7 * recall + 0.3 * precision
        val prep = preparation.lowercase().trim()
        val prepWords = PREP_SYNONYMS[prep] ?: setOf(prep)
        if (prep.isNotBlank() && prep != "unknown" && prepWords.any { it in c }) score += 0.1
        val mixed = isMixedDish(query)
        score += when (dataType) {
            "Survey (FNDDS)" -> if (mixed) 0.08 else 0.0
            "Foundation", "SR Legacy" -> if (mixed) 0.0 else 0.05
            else -> 0.0
        }
        return score
    }

    /** The USDA energy density agrees with the model's within ±40% (both kcal per gram). */
    fun kcalAgrees(usdaKcalPerG: Double, modelKcalPerG: Double): Boolean {
        if (modelKcalPerG <= 0.0) return usdaKcalPerG < 0.2
        return abs(usdaKcalPerG - modelKcalPerG) / modelKcalPerG <= KCAL_TOLERANCE
    }

    data class Candidate<T>(val item: T, val name: String, val dataType: String?, val kcalPer100g: Double)

    /** The best acceptable candidate and its score, or null (use the model's values). */
    fun <T> best(query: String, preparation: String, modelKcalPerG: Double, candidates: List<Candidate<T>>): Pair<Candidate<T>, Double>? =
        candidates
            .map { it to score(query, preparation, it.name, it.dataType) }
            .filter { (c, s) -> s >= THRESHOLD && kcalAgrees(c.kcalPer100g / 100.0, modelKcalPerG) }
            .maxByOrNull { it.second }
}
