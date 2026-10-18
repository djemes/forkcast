package app.forkcast.core.network

import app.forkcast.core.domain.Gtin
import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.ServingSize
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import javax.inject.Inject

/**
 * Open Food Facts (SPEC §7): read-only, no key, descriptive User-Agent set on the client.
 * Product lookup uses API v2; text search uses search-a-licious (verified 2026-10-18).
 */
class OffClient @Inject constructor(private val http: HttpClient) {

    suspend fun product(gtin14: String): Food? {
        // OFF keys products by their printed code (EAN-13/UPC-A/EAN-8); try 13 then 12 digits.
        for (code in Gtin.lookupVariants(gtin14).drop(1).ifEmpty { listOf(gtin14) }) {
            val r = call { http.get("$PRODUCT_BASE/$code") { parameter("fields", FIELDS) } }
            if (r.status.value == 404) continue
            val body = parse<OffProductResponse>(r)
            if (body.status == 1 && body.product != null) return body.product.toFood(code)
        }
        return null
    }

    suspend fun search(query: String, pageSize: Int = 20): List<Food> {
        val r = call {
            http.get(SEARCH_URL) {
                parameter("q", query)
                parameter("page_size", pageSize)
                parameter("fields", FIELDS)
            }
        }
        return parse<OffSearchResponse>(r).hits.mapNotNull { it.toFood(it.code) }
    }

    private suspend fun call(block: suspend () -> HttpResponse): HttpResponse {
        val r = try {
            block()
        } catch (e: java.io.IOException) {
            throw FoodApiError.Offline()
        }
        when (r.status.value) {
            429 -> throw FoodApiError.RateLimited()
            in 500..599 -> throw FoodApiError.Server(r.status.value)
        }
        return r
    }

    private suspend inline fun <reified T> parse(r: HttpResponse): T =
        try { r.body<T>() } catch (e: Exception) { throw FoodApiError.BadResponse(e) }

    companion object {
        const val PRODUCT_BASE = "https://world.openfoodfacts.org/api/v2/product"
        const val SEARCH_URL = "https://search.openfoodfacts.org/search"
        private const val FIELDS = "code,product_name,product_name_en,brands,serving_size,serving_quantity,nutriments"
    }
}

@Serializable
private data class OffProductResponse(val status: Int = 0, val product: OffProduct? = null)

@Serializable
private data class OffSearchResponse(val hits: List<OffProduct> = emptyList())

@Serializable
private data class OffProduct(
    val code: String? = null,
    val product_name: String? = null,
    val product_name_en: String? = null,
    /** A comma-separated string from the product API, a list from search. */
    val brands: JsonElement? = null,
    val serving_size: String? = null,
    val serving_quantity: JsonElement? = null,
    val nutriments: JsonObject? = null,
) {
    fun toFood(codeFallback: String?): Food? {
        val name = (product_name ?: product_name_en)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val n = nutriments?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.let { p -> (p.doubleOrNull ?: p.content.toDoubleOrNull())?.let { k to it } } }
            ?.toMap().orEmpty()
        val nutrients = OffNutrientMap.map(n)
        if (nutrients.isEmpty()) return null
        val brand = when (brands) {
            is JsonArray -> brands.firstOrNull()?.let { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> brands.content.split(',').firstOrNull()
            else -> null
        }?.trim()?.takeIf { it.isNotEmpty() }
        val servingGrams = (serving_quantity as? JsonPrimitive)?.let { it.doubleOrNull ?: it.content.toDoubleOrNull() }
        val servings = if (servingGrams != null && servingGrams > 0) {
            listOf(ServingSize(serving_size?.takeIf { it.isNotBlank() } ?: "1 serving", servingGrams))
        } else emptyList()
        val code = code ?: codeFallback
        return Food(
            id = "",
            name = name,
            brand = brand,
            source = FoodSource.OFF,
            sourceId = code,
            barcode = code?.let { Gtin.normalize(it) },
            servingSizes = servings,
            nutrientsPer100g = nutrients,
            isFavorite = false,
            lastUsedAt = null,
            lastQuantity = null,
            lastServingLabel = null,
        )
    }
}
