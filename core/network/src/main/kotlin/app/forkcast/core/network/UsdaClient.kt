package app.forkcast.core.network

import app.forkcast.core.domain.Gtin
import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.ServingSize
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject

/** Supplies the user's FoodData Central key; null means "use DEMO_KEY". */
fun interface UsdaKeyProvider {
    suspend fun apiKey(): String?
}

/** USDA FoodData Central (SPEC §7). Key goes in the X-Api-Key header, never the URL. */
class UsdaClient @Inject constructor(
    private val http: HttpClient,
    private val keys: UsdaKeyProvider,
) {
    suspend fun search(query: String, pageSize: Int = 25, dataTypes: List<String> = ALL_TYPES): List<Food> {
        val response = call {
            http.post("$BASE/foods/search") {
                header(KEY_HEADER, key())
                contentType(ContentType.Application.Json)
                setBody(UsdaSearchRequest(query, dataTypes, pageSize))
            }
        }
        return parse<UsdaSearchResponse>(response).foods.mapNotNull { it.toFood() }
    }

    /** Generic foods for AI component matching (SPEC §4.6.1), each with its FDC data type. */
    suspend fun searchGeneric(query: String, pageSize: Int = 10): List<UsdaHit> {
        val response = call {
            http.post("$BASE/foods/search") {
                header(KEY_HEADER, key())
                contentType(ContentType.Application.Json)
                setBody(UsdaSearchRequest(query, GENERIC_TYPES, pageSize))
            }
        }
        return parse<UsdaSearchResponse>(response).foods.mapNotNull { f -> f.toFood()?.let { UsdaHit(it, f.dataType) } }
    }

    /** Full record with portions; better data than the search row (no duplicate nutrients). */
    suspend fun food(fdcId: String): Food? {
        val response = call { http.get("$BASE/food/$fdcId") { header(KEY_HEADER, key()) } }
        if (response.status.value == 404) return null
        return parse<FoodDetail>(response).toFood()
    }

    /** Branded food whose GTIN matches [gtin14], trying the shorter forms FDC may store. */
    suspend fun byGtin(gtin14: String): Food? {
        for (variant in Gtin.lookupVariants(gtin14).take(2)) {
            val hit = search(variant, pageSize = 5, dataTypes = listOf("Branded")).firstOrNull { it.barcode == gtin14 }
            if (hit != null) return hit
        }
        return null
    }

    /** Checks [key] with the smallest request FDC offers (one food); throws [FoodApiError] when it fails. */
    suspend fun testKey(key: String) {
        call { http.get("$BASE/foods/list") { header(KEY_HEADER, key); parameter("pageSize", 1) } }
    }

    private suspend fun key() = keys.apiKey()?.takeIf { it.isNotBlank() } ?: DEMO_KEY

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse {
        val r = try {
            block()
        } catch (e: java.io.IOException) {
            throw FoodApiError.Offline()
        }
        when (r.status.value) {
            401, 403 -> throw FoodApiError.InvalidKey()
            429 -> throw FoodApiError.RateLimited()
            in 500..599 -> throw FoodApiError.Server(r.status.value)
        }
        return r
    }

    private suspend inline fun <reified T> parse(r: HttpResponse): T =
        try { r.body<T>() } catch (e: Exception) { throw FoodApiError.BadResponse(e) }

    companion object {
        const val BASE = "https://api.nal.usda.gov/fdc/v1"
        const val DEMO_KEY = "DEMO_KEY"
        private const val KEY_HEADER = "X-Api-Key"
        val ALL_TYPES = listOf("Foundation", "SR Legacy", "Survey (FNDDS)", "Branded")
        val GENERIC_TYPES = listOf("Foundation", "SR Legacy", "Survey (FNDDS)")
    }
}

/** A search result with its FDC data type ("Foundation", "SR Legacy", "Survey (FNDDS)", "Branded"). */
data class UsdaHit(val food: Food, val dataType: String?)

@Serializable
private data class UsdaSearchRequest(
    val query: String,
    val dataType: List<String>,
    val pageSize: Int,
)

@Serializable
private data class UsdaSearchResponse(val foods: List<UsdaSearchFood> = emptyList())

@Serializable
private data class UsdaSearchFood(
    val fdcId: Long,
    val description: String,
    val dataType: String? = null,
    val brandOwner: String? = null,
    val brandName: String? = null,
    val gtinUpc: String? = null,
    val servingSize: Double? = null,
    val servingSizeUnit: String? = null,
    val householdServingFullText: String? = null,
    val foodNutrients: List<SearchNutrient> = emptyList(),
    val foodMeasures: List<SearchMeasure> = emptyList(),
) {
    fun toFood(): Food? {
        val nutrients = UsdaNutrientMap.map(foodNutrients.map { Triple(it.nutrientId, it.unitName, it.value) })
        if (nutrients.isEmpty()) return null
        val servings = brandedServing(servingSize, servingSizeUnit, householdServingFullText) +
            foodMeasures.filter { (it.gramWeight ?: 0.0) > 0 && !it.disseminationText.isNullOrBlank() && it.disseminationText != "Quantity not specified" }
                .map { ServingSize(it.disseminationText!!, it.gramWeight!!) }
        return usdaFood(fdcId, description, brandName ?: brandOwner, gtinUpc, servings, nutrients)
    }
}

@Serializable
private data class SearchNutrient(val nutrientId: Int, val unitName: String? = null, val value: Double? = null)

@Serializable
private data class SearchMeasure(val disseminationText: String? = null, val gramWeight: Double? = null)

@Serializable
private data class FoodDetail(
    val fdcId: Long,
    val description: String,
    val brandOwner: String? = null,
    val brandName: String? = null,
    val gtinUpc: String? = null,
    val servingSize: Double? = null,
    val servingSizeUnit: String? = null,
    val householdServingFullText: String? = null,
    val foodNutrients: List<DetailNutrient> = emptyList(),
    val foodPortions: List<Portion> = emptyList(),
) {
    fun toFood(): Food? {
        val nutrients = UsdaNutrientMap.map(
            foodNutrients.mapNotNull { n -> n.nutrient?.let { Triple(it.id, it.unitName, n.amount) } },
        )
        if (nutrients.isEmpty()) return null
        val portions = foodPortions
            .filter { (it.gramWeight ?: 0.0) > 0 }
            .sortedBy { it.sequenceNumber ?: Int.MAX_VALUE }
            .mapNotNull { p -> portionLabel(p)?.let { ServingSize(it, p.gramWeight!!) } }
        return usdaFood(fdcId, description, brandName ?: brandOwner, gtinUpc, brandedServing(servingSize, servingSizeUnit, householdServingFullText) + portions, nutrients)
    }
}

@Serializable
private data class DetailNutrient(val nutrient: NutrientRef? = null, val amount: Double? = null)

@Serializable
private data class NutrientRef(val id: Int, val unitName: String? = null)

@Serializable
private data class Portion(
    val amount: Double? = null,
    val modifier: String? = null,
    val portionDescription: String? = null,
    val gramWeight: Double? = null,
    val measureUnit: MeasureUnit? = null,
    val sequenceNumber: Int? = null,
)

/**
 * A readable serving name, or null to skip the portion.
 * FNDDS: `portionDescription` is the label ("1 cup, cooked"); its `modifier` is an internal
 * numeric code ("90000" = "Quantity not specified"), never shown.
 * SR Legacy: no description; label from amount + modifier ("1 cup") or the measure unit.
 */
private fun portionLabel(p: Portion): String? {
    p.portionDescription?.let { d ->
        return d.trim().takeIf { it.isNotEmpty() && !it.equals("Quantity not specified", ignoreCase = true) }
    }
    val amount = p.amount?.let { a -> if (a == a.toLong().toDouble()) a.toLong().toString() else a.toString() }
    val unit = p.modifier?.trim()?.takeIf { it.isNotEmpty() && !it.all(Char::isDigit) }
        ?: p.measureUnit?.name?.takeIf { it.isNotBlank() && it != "undetermined" }
        ?: return null
    return listOfNotNull(amount, unit).joinToString(" ")
}

@Serializable
private data class MeasureUnit(val name: String? = null, @SerialName("abbreviation") val abbr: String? = null)

/** Branded foods state their serving in g (GRM) or ml (MLT); ml is treated as g (water density). */
private fun brandedServing(size: Double?, unit: String?, household: String?): List<ServingSize> {
    if (size == null || size <= 0) return emptyList()
    val u = unit?.uppercase()
    if (u != null && u !in setOf("G", "GRM", "ML", "MLT")) return emptyList()
    val label = household?.takeIf { it.isNotBlank() } ?: "1 serving"
    return listOf(ServingSize(label, size))
}

private fun usdaFood(
    fdcId: Long,
    description: String,
    brand: String?,
    gtin: String?,
    servings: List<ServingSize>,
    nutrients: Map<app.forkcast.core.model.NutrientKey, Double>,
) = Food(
    id = "",
    name = description.trim(),
    brand = brand?.trim()?.takeIf { it.isNotEmpty() },
    source = FoodSource.USDA,
    sourceId = fdcId.toString(),
    barcode = gtin?.let { Gtin.normalize(it) },
    servingSizes = servings.distinctBy { it.label.lowercase() },
    nutrientsPer100g = nutrients,
    isFavorite = false,
    lastUsedAt = null,
    lastQuantity = null,
    lastServingLabel = null,
)
