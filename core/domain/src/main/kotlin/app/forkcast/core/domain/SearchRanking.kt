package app.forkcast.core.domain

import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import kotlin.math.ln
import kotlin.math.min

/**
 * One list for a food search (owner, 2026-10-29): your foods, USDA and Open Food Facts merged and
 * ranked by how well the name matches, how complete the nutrition data is, how often you've
 * logged it, and favorites first. Pure, so it's unit-tested.
 */
object SearchRanking {

    /** Words that describe a plain food rather than a different one ("Bananas, raw" is just bananas). */
    private val plainWords = setOf("raw", "fresh", "plain", "whole", "cru", "crua", "fresco", "fresca", "natural")

    /**
     * [own] (already on this phone, first), [usda] then [off]; the same food once: by id, by
     * source and source id (a cached copy of a search result), then by barcode, keeping the copy on
     * this phone, else the one with more nutrients (USDA on ties, like barcode lookups).
     */
    fun merge(query: String, own: List<Food>, usda: List<Food>, off: List<Food>, uses: Map<String, Int> = emptyMap()): List<Food> {
        val kept = LinkedHashMap<String, Food>()
        fun keyOf(f: Food) = f.barcode?.let { "gtin:$it" } ?: f.sourceId?.let { "${f.source}:$it" } ?: "id:${f.id}"
        val bySource = HashMap<String, String>()
        for (food in own + usda + off) {
            val sourceKey = food.sourceId?.let { "${food.source}:$it" }
            val key = sourceKey?.let(bySource::get) ?: keyOf(food)
            val existing = kept[key]
            if (existing == null || better(food, existing)) kept[key] = food
            if (sourceKey != null) bySource[sourceKey] = key
        }
        return rank(query, kept.values.toList(), uses)
    }

    private fun better(candidate: Food, existing: Food): Boolean = when {
        existing.id.isNotEmpty() -> false
        candidate.id.isNotEmpty() -> true
        else -> candidate.nutrientsPer100g.size > existing.nutrientsPer100g.size
    }

    /** Best first; equal scores keep their order. */
    fun rank(query: String, foods: List<Food>, uses: Map<String, Int> = emptyMap()): List<Food> =
        foods.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Food>> { score(query, it.value, uses[it.value.id] ?: 0) }.thenBy { it.index })
            .map { it.value }

    fun score(query: String, food: Food, uses: Int = 0): Double {
        val q = DescribedMatch.words(query).distinct()
        if (q.isEmpty()) return 0.0
        val name = DescribedMatch.words(food.name)
        val brand = food.brand?.let(DescribedMatch::words).orEmpty()
        val inName = q.count { it in name }
        val coverage = q.count { it in name || it in brand }.toDouble() / q.size
        // How much of the name is what was asked for: "Bananas, raw" beats "Banana chips, sweetened".
        val extra = name.count { it !in q && it !in plainWords }
        var s = coverage + 0.5 * inName / q.size - 0.08 * min(extra, 6)
        if (name.firstOrNull() == q.first()) s += 0.15
        if (brand.isEmpty() && food.source != FoodSource.OFF) s += 0.05
        // Complete data is worth more: generic USDA foods carry 30+ nutrients, many products 8.
        s += 0.3 * min(food.nutrientsPer100g.size, COMPLETE) / COMPLETE
        s += 0.5 * ln(1.0 + min(uses, MANY_USES)) / ln(1.0 + MANY_USES)
        if (food.id.isNotEmpty() && food.source in ownSources) s += 0.1
        // Favorites that match lead the list.
        if (food.isFavorite && coverage >= DescribedMatch.THRESHOLD) s += 2.0
        return s
    }

    /** Foods you made: tagged "Your foods" in the list. */
    val ownSources = setOf(FoodSource.CUSTOM, FoodSource.LABEL, FoodSource.RECIPE, FoodSource.AI_TEXT)

    private const val COMPLETE = 20
    private const val MANY_USES = 20
}
