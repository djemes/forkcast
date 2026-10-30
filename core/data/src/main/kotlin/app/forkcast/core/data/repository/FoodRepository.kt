package app.forkcast.core.data.repository

import app.forkcast.core.data.db.FoodDao
import app.forkcast.core.data.db.FoodEntity
import app.forkcast.core.domain.BarcodeChoice
import app.forkcast.core.domain.DescribedMatch
import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.network.FoodApiError
import app.forkcast.core.network.OffClient
import app.forkcast.core.network.UsdaClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of a barcode lookup (SPEC §4.4). */
sealed interface BarcodeLookup {
    data class Found(val foodId: String, val fromCache: Boolean) : BarcodeLookup
    data object NotFound : BarcodeLookup
    /** Not cached locally and the databases couldn't be reached. */
    data class Failed(val error: FoodApiError) : BarcodeLookup
}

interface FoodRepository {
    /** Your own foods (custom, cached, recents, favorites). Works offline. */
    suspend fun searchLocal(query: String, limit: Int = 30): List<Food>
    /** Throws [app.forkcast.core.network.FoodApiError] on failure. */
    suspend fun searchUsda(query: String): List<Food>
    suspend fun searchOff(query: String): List<Food>
    suspend fun get(id: String): Food?
    fun observe(id: String): Flow<Food?>
    /** Stores a remote result locally (or refreshes the cached copy) and returns its local id. */
    suspend fun cache(remote: Food): String
    /** Re-fetches a cached USDA food's full record (portions, clean nutrients). No-op offline. */
    suspend fun refreshDetails(id: String)
    fun observeRecents(limit: Int = 50): Flow<List<Food>>
    fun observeFavorites(): Flow<List<Food>>
    /** How often each of [ids] was logged (foods never logged are left out). */
    suspend fun useCounts(ids: List<String>): Map<String, Int>
    fun observeCustom(): Flow<List<Food>>
    /** Favoriting keeps the food's default amount, or starts it from the last amount logged (roadmap 3.10). */
    suspend fun setFavorite(id: String, favorite: Boolean)
    /** Favorites [id] with [quantity] × [servingLabel] as the amount it's logged with from now on; past entries never change. */
    suspend fun setFavoriteAmount(id: String, quantity: Double, servingLabel: String)
    /** The user's "counts toward water" choice for a food; null returns it to the drink rule. */
    suspend fun setCountsAsWater(id: String, counts: Boolean?)
    /** Creates (id == "") or updates a custom/label food. Returns its id. */
    suspend fun saveCustom(food: Food): String
    suspend fun delete(id: String)
    suspend fun findLocalByBarcode(gtin14: String): Food?
    /** Local cache first, then USDA Branded and Open Food Facts in parallel; the winner is cached. */
    suspend fun lookupBarcode(gtin14: String): BarcodeLookup
}

@Singleton
class DefaultFoodRepository @Inject constructor(
    private val dao: FoodDao,
    private val usda: UsdaClient,
    private val off: OffClient,
    private val clock: Clock,
) : FoodRepository {

    override suspend fun searchLocal(query: String, limit: Int): List<Food> {
        val words = query.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        // Foods saved from "Describe" first when the search is close to what was described
        // ("2 slices costco pizza" finds "Costco cheese pizza"): never ask the AI twice (owner, 2026-10-23).
        val described = dao.bySource(FoodSource.AI_TEXT)
            .map { it to DescribedMatch.score(query, listOfNotNull(it.name, it.brand, it.describedAs)) }
            .filter { it.second >= DescribedMatch.THRESHOLD }
            .sortedByDescending { it.second }
            .map { it.first }
        // SQL narrows by the longest word; every word must then match name or brand.
        val plain = dao.search(words.maxBy { it.length }, limit = 200)
            .filter { e -> val hay = "${e.name} ${e.brand.orEmpty()}".lowercase(Locale.ROOT); words.all { it in hay } }
        return (described + plain).distinctBy { it.id }
            .take(limit)
            .map { it.toModel() }
    }

    override suspend fun searchUsda(query: String): List<Food> = usda.search(query)

    override suspend fun searchOff(query: String): List<Food> = off.search(query)

    override suspend fun get(id: String): Food? = dao.get(id)?.takeIf { it.deletedAt == null }?.toModel()

    override fun observe(id: String): Flow<Food?> = dao.observe(id).map { it?.takeIf { e -> e.deletedAt == null }?.toModel() }

    override suspend fun cache(remote: Food): String {
        val now = clock.millis()
        val existing = remote.sourceId?.let { dao.findBySource(remote.source, it) }
        if (existing != null) {
            // Keep the user's own state (favorite, last used); refresh the data.
            dao.upsert(
                existing.copy(
                    name = remote.name,
                    brand = remote.brand,
                    barcode = remote.barcode ?: existing.barcode,
                    servingSizes = remote.servingSizes.ifEmpty { existing.servingSizes },
                    nutrientsPer100g = remote.nutrientsPer100g,
                    updatedAt = now,
                ),
            )
            return existing.id
        }
        val id = UUID.randomUUID().toString()
        dao.upsert(remote.toEntity(id, now))
        return id
    }

    override suspend fun refreshDetails(id: String) {
        val local = dao.get(id) ?: return
        val fdcId = local.sourceId
        if (local.source != FoodSource.USDA || fdcId == null) return
        val fresh = runCatching { usda.food(fdcId) }.getOrNull() ?: return
        dao.upsert(
            local.copy(
                servingSizes = fresh.servingSizes.ifEmpty { local.servingSizes },
                nutrientsPer100g = fresh.nutrientsPer100g,
                barcode = fresh.barcode ?: local.barcode,
                updatedAt = clock.millis(),
            ),
        )
    }

    override fun observeRecents(limit: Int): Flow<List<Food>> = dao.observeRecents(limit).map { l -> l.map { it.toModel() } }

    override fun observeFavorites(): Flow<List<Food>> = dao.observeFavorites().map { l -> l.map { it.toModel() } }

    override suspend fun useCounts(ids: List<String>): Map<String, Int> =
        if (ids.isEmpty()) emptyMap() else dao.useCounts(ids).associate { it.foodId to it.uses }

    override fun observeCustom(): Flow<List<Food>> = dao.observeCustom().map { l -> l.map { it.toModel() } }

    override suspend fun setFavorite(id: String, favorite: Boolean) {
        val now = clock.millis()
        dao.setFavorite(id, favorite, now)
        val food = dao.get(id) ?: return
        if (favorite && food.favoriteQuantity == null && food.lastQuantity != null && food.lastServingLabel != null) {
            dao.setFavoriteAmount(id, food.lastQuantity, food.lastServingLabel, now)
        }
    }

    override suspend fun setFavoriteAmount(id: String, quantity: Double, servingLabel: String) =
        dao.setFavoriteAmount(id, quantity, servingLabel, clock.millis())

    override suspend fun setCountsAsWater(id: String, counts: Boolean?) = dao.setCountsAsWater(id, counts, clock.millis())

    override suspend fun saveCustom(food: Food): String {
        val now = clock.millis()
        val existing = food.id.takeIf { it.isNotEmpty() }?.let { dao.get(it) }
        if (existing != null) {
            dao.upsert(
                existing.copy(
                    name = food.name,
                    brand = food.brand,
                    barcode = food.barcode,
                    servingSizes = food.servingSizes,
                    nutrientsPer100g = food.nutrientsPer100g,
                    describedAs = food.describedAs ?: existing.describedAs,
                    updatedAt = now,
                ),
            )
            return existing.id
        }
        val id = UUID.randomUUID().toString()
        dao.upsert(food.toEntity(id, now))
        return id
    }

    override suspend fun delete(id: String) = dao.softDelete(id, clock.millis())

    override suspend fun findLocalByBarcode(gtin14: String): Food? = dao.findByBarcode(gtin14)?.toModel()

    override suspend fun lookupBarcode(gtin14: String): BarcodeLookup {
        dao.findByBarcode(gtin14)?.let { return BarcodeLookup.Found(it.id, fromCache = true) }
        val (usdaResult, offResult) = coroutineScope {
            val u = async { runCatchingApi { usda.byGtin(gtin14) } }
            val o = async { runCatchingApi { off.product(gtin14) } }
            u.await() to o.await()
        }
        val chosen = BarcodeChoice.choose(usdaResult.getOrNull(), offResult.getOrNull(), gtin14)
        if (chosen != null) return BarcodeLookup.Found(cache(chosen.copy(barcode = gtin14)), fromCache = false)
        val error = listOfNotNull(usdaResult.exceptionOrNull(), offResult.exceptionOrNull()).filterIsInstance<FoodApiError>()
        // Only call it "not found" if at least one database actually answered.
        return if (error.size == 2) BarcodeLookup.Failed(error.first()) else BarcodeLookup.NotFound
    }

    private suspend fun <T> runCatchingApi(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: FoodApiError) {
        Result.failure(e)
    } catch (e: Exception) {
        Result.failure(FoodApiError.BadResponse(e))
    }
}

fun FoodEntity.toModel() = Food(
    id = id,
    name = name,
    brand = brand,
    source = source,
    sourceId = sourceId,
    barcode = barcode,
    servingSizes = servingSizes,
    nutrientsPer100g = nutrientsPer100g,
    isFavorite = isFavorite,
    lastUsedAt = lastUsedAt,
    lastQuantity = lastQuantity,
    lastServingLabel = lastServingLabel,
    countsAsWater = countsAsWater,
    describedAs = describedAs,
    favoriteQuantity = favoriteQuantity,
    favoriteServingLabel = favoriteServingLabel,
)

private fun Food.toEntity(id: String, now: Long) = FoodEntity(
    id = id,
    name = name,
    brand = brand,
    source = source,
    sourceId = sourceId,
    barcode = barcode,
    servingSizes = servingSizes,
    nutrientsPer100g = nutrientsPer100g,
    isFavorite = isFavorite,
    lastUsedAt = lastUsedAt,
    lastQuantity = lastQuantity,
    lastServingLabel = lastServingLabel,
    createdAt = now,
    updatedAt = now,
    countsAsWater = countsAsWater,
    describedAs = describedAs,
    favoriteQuantity = favoriteQuantity,
    favoriteServingLabel = favoriteServingLabel,
)
