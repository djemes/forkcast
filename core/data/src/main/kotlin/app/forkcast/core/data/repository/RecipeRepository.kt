package app.forkcast.core.data.repository

import androidx.room.withTransaction
import app.forkcast.core.data.db.FoodDao
import app.forkcast.core.data.db.FoodEntity
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.db.RecipeDao
import app.forkcast.core.data.db.RecipeEntity
import app.forkcast.core.data.db.RecipeIngredientEntity
import app.forkcast.core.domain.RecipeMath
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.Recipe
import app.forkcast.core.model.RecipeIngredient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Recipes (SPEC §4.5). Each saved recipe keeps a loggable food (source RECIPE) up to date. */
interface RecipeRepository {
    val recipes: Flow<List<Recipe>>
    suspend fun get(id: String): Recipe?
    /** The recipe behind a RECIPE food (for "Edit recipe" on its food detail). */
    suspend fun forFood(foodId: String): Recipe?
    /**
     * Creates or updates [recipe] (blank id = new) and its food; returns the recipe id. Past log
     * entries keep their own snapshots, so editing a recipe only changes future logs.
     */
    suspend fun save(recipe: Recipe): String
    /** Soft-deletes the recipe, its ingredients and its food. */
    suspend fun delete(id: String)
}

@Singleton
class RoomRecipeRepository @Inject constructor(
    private val db: ForkcastDatabase,
    private val dao: RecipeDao,
    private val foodDao: FoodDao,
    private val clock: Clock,
) : RecipeRepository {

    override val recipes: Flow<List<Recipe>> = combine(dao.observeRecipes(), dao.observeIngredients()) { rows, ingredients ->
        val byRecipe = ingredients.groupBy { it.recipeId }
        rows.map { it.toModel(byRecipe[it.id].orEmpty()) }
    }

    override suspend fun get(id: String): Recipe? =
        dao.get(id)?.takeIf { it.deletedAt == null }?.let { it.toModel(dao.ingredientsFor(it.id)) }

    override suspend fun forFood(foodId: String): Recipe? = dao.getByFood(foodId)?.let { it.toModel(dao.ingredientsFor(it.id)) }

    override suspend fun save(recipe: Recipe): String = db.withTransaction {
        val now = clock.millis()
        val existing = recipe.id.takeIf { it.isNotEmpty() }?.let { dao.get(it) }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val name = recipe.name.trim()
        val result = RecipeMath.compute(recipe.ingredients, recipe.servings, recipe.cookedWeightG)
        // The loggable food: kept in step with the recipe (its name, per-100 g values and serving).
        val oldFood = existing?.foodId?.let { foodDao.get(it) }
        val foodId = oldFood?.id ?: UUID.randomUUID().toString()
        val food = (oldFood ?: FoodEntity(
            id = foodId, name = name, brand = null, source = FoodSource.RECIPE, sourceId = id, barcode = null,
            servingSizes = emptyList(), nutrientsPer100g = emptyMap(), isFavorite = false, lastUsedAt = null,
            lastQuantity = null, lastServingLabel = null, createdAt = now, updatedAt = now,
        )).copy(
            name = name,
            servingSizes = result?.let { RecipeMath.servingSizes(it) }.orEmpty(),
            nutrientsPer100g = result?.per100g.orEmpty(),
            updatedAt = now,
            deletedAt = null,
        )
        foodDao.upsert(food)
        dao.upsert(
            RecipeEntity(
                id = id,
                name = name,
                servings = recipe.servings,
                cookedWeightG = recipe.cookedWeightG?.takeIf { it > 0 },
                foodId = foodId,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
        val rows = recipe.ingredients.mapIndexed { i, ing ->
            RecipeIngredientEntity(
                id = ing.id.ifEmpty { UUID.randomUUID().toString() },
                recipeId = id,
                sortOrder = i,
                foodId = ing.foodId,
                displayName = ing.displayName,
                brand = ing.brand,
                quantity = ing.quantity,
                servingLabel = ing.servingLabel,
                grams = ing.grams,
                nutrients = ing.nutrients,
                createdAt = now,
                updatedAt = now,
            )
        }
        val previous = dao.ingredientsFor(id).associateBy { it.id }
        dao.upsertIngredients(rows.map { r -> previous[r.id]?.let { r.copy(createdAt = it.createdAt) } ?: r })
        dao.softDeleteIngredientsExcept(id, rows.map { it.id }, now)
        id
    }

    override suspend fun delete(id: String): Unit = db.withTransaction {
        val now = clock.millis()
        val row = dao.get(id) ?: return@withTransaction
        dao.upsert(row.copy(deletedAt = now, updatedAt = now))
        dao.softDeleteIngredientsExcept(id, emptyList(), now)
        row.foodId?.let { foodDao.softDelete(it, now) }
    }
}

private fun RecipeEntity.toModel(ingredients: List<RecipeIngredientEntity>) = Recipe(
    id = id,
    name = name,
    servings = servings,
    cookedWeightG = cookedWeightG,
    ingredients = ingredients.sortedBy { it.sortOrder }.map {
        RecipeIngredient(it.id, it.foodId, it.displayName, it.brand, it.quantity, it.servingLabel, it.grams, it.nutrients)
    },
    foodId = foodId,
)
