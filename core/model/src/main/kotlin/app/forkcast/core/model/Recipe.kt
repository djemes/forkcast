package app.forkcast.core.model

/**
 * A recipe (SPEC §4.5): ingredients plus a number of servings, optionally the final cooked
 * weight. Saving it keeps a loggable [Food] (source RECIPE) in sync, [foodId].
 */
data class Recipe(
    val id: String,
    val name: String,
    val servings: Double,
    /** Weight of the finished dish; null = the raw ingredients' total. */
    val cookedWeightG: Double?,
    val ingredients: List<RecipeIngredient>,
    val foodId: String? = null,
)

/** One ingredient: an amount of a food, with its nutrients snapshotted for that amount. */
data class RecipeIngredient(
    val id: String,
    val foodId: String?,
    val displayName: String,
    val brand: String?,
    val quantity: Double,
    val servingLabel: String,
    val grams: Double,
    val nutrients: Nutrients,
)
