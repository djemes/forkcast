package app.forkcast.core.data.ai

import app.forkcast.core.data.db.AiDao
import app.forkcast.core.data.db.AiMatchCacheEntity
import app.forkcast.core.data.repository.FoodRepository
import app.forkcast.core.domain.AiMatching
import app.forkcast.core.model.EstimatedItem
import app.forkcast.core.model.Food
import app.forkcast.core.model.kcal
import app.forkcast.core.network.FoodApiError
import app.forkcast.core.network.UsdaClient
import java.time.Clock
import javax.inject.Inject

/** A USDA record chosen for an AI component, already cached as a local food. */
data class ComponentMatch(val food: Food, val score: Double)

/**
 * Matches AI components to USDA generic foods (SPEC §4.6.1). Results, including "no good
 * match", are cached by normalized query so repeat meals need no lookups. Network problems
 * never fail an estimate: the component just keeps the model's own numbers.
 */
class ComponentMatcher @Inject constructor(
    private val usda: UsdaClient,
    private val foods: FoodRepository,
    private val dao: AiDao,
    private val clock: Clock,
) {
    suspend fun match(item: EstimatedItem): ComponentMatch? {
        val key = AiMatching.cacheKey(item.usdaQuery, item.preparation)
        val modelKcalPerG = if (item.grams > 0) item.nutrients.kcal / item.grams else 0.0
        dao.match(key)?.let { cached ->
            if (cached.foodId == null) return null
            val food = foods.get(cached.foodId)
            if (food != null) {
                // The same query can still disagree on energy this time (e.g. a sauce-heavy version).
                return if (AiMatching.kcalAgrees(food.nutrientsPer100g.kcal / 100.0, modelKcalPerG)) ComponentMatch(food, cached.score) else null
            }
        }
        val hits = try {
            usda.searchGeneric(item.usdaQuery)
        } catch (e: FoodApiError) {
            return null
        }
        val candidates = hits.map { AiMatching.Candidate(it.food, it.food.name, it.dataType, it.food.nutrientsPer100g.kcal) }
        val best = AiMatching.best(item.usdaQuery, item.preparation, modelKcalPerG, candidates)
        val now = clock.millis()
        if (best == null) {
            dao.upsertMatch(AiMatchCacheEntity(key, null, null, 0.0, now, now))
            return null
        }
        val localId = foods.cache(best.first.item)
        dao.upsertMatch(AiMatchCacheEntity(key, localId, best.first.item.sourceId, best.second, now, now))
        val food = foods.get(localId) ?: return null
        return ComponentMatch(food, best.second)
    }
}
