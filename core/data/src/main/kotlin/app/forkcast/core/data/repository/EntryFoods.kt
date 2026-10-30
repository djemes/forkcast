package app.forkcast.core.data.repository

import app.forkcast.core.data.ai.AiEstimateRepository
import app.forkcast.core.domain.FoodLogging
import app.forkcast.core.model.LogEntry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Save as food" and "★ Favorite" on an entry (owner, 2026-10-30): one food per entry, whichever
 * comes first. An entry with a food uses it; an AI entry becomes a food from its estimate (the
 * plate at 1×, with the description); anything else (a quick add) from the entry itself. The
 * entry is linked to the food, so the second action finds it instead of making another.
 */
@Singleton
class EntryFoods @Inject constructor(
    private val foods: FoodRepository,
    private val log: LogRepository,
    private val estimates: AiEstimateRepository,
) {
    /** The food behind [entry], made and linked if it has none. Null when there's nothing to make one from. */
    suspend fun saveAsFood(entry: LogEntry): String? {
        entry.foodId?.takeIf { foods.get(it) != null }?.let { return it }
        entry.aiEstimateId?.let { return estimates.saveAsFood(it, entry.displayName) }
        val id = foods.saveCustom(FoodLogging.foodFromEntry(entry))
        log.update(entry.copy(foodId = id))
        return id
    }

    /**
     * Stars the food behind [entry]: a database food with the entry's amount, a food made from the
     * entry (an AI estimate, a quick add) with 1 serving, which is the whole plate.
     */
    suspend fun favorite(entry: LogEntry): String? {
        val ownFood = entry.aiEstimateId == null && entry.foodId?.let { foods.get(it) } != null
        val id = saveAsFood(entry) ?: return null
        if (ownFood) foods.setFavoriteAmount(id, entry.quantity, entry.servingLabel)
        else foods.setFavoriteAmount(id, 1.0, FoodLogging.SERVING_LABEL)
        return id
    }
}
