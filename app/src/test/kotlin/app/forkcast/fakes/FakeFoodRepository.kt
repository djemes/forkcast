package app.forkcast.fakes

import app.forkcast.core.data.repository.BarcodeLookup
import app.forkcast.core.data.repository.FoodRepository
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.ServingSize
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale

class FakeFoodRepository : FoodRepository {
    val foods = MutableStateFlow<Map<String, Food>>(emptyMap())
    var usdaResults: () -> List<Food> = { emptyList() }
    var offResults: () -> List<Food> = { emptyList() }
    private var next = 0

    fun put(food: Food): String {
        val id = food.id.ifEmpty { "food${next++}" }
        foods.update { it + (id to food.copy(id = id)) }
        return id
    }

    override suspend fun searchLocal(query: String, limit: Int): List<Food> {
        val words = query.lowercase(Locale.ROOT).split(" ").filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return foods.value.values.filter { f -> words.all { it in "${f.name} ${f.brand.orEmpty()}".lowercase(Locale.ROOT) } }
    }
    override suspend fun searchUsda(query: String) = usdaResults()
    override suspend fun searchOff(query: String) = offResults()
    override suspend fun get(id: String) = foods.value[id]
    override fun observe(id: String): Flow<Food?> = foods.map { it[id] }
    override suspend fun cache(remote: Food): String =
        foods.value.values.firstOrNull { it.source == remote.source && it.sourceId == remote.sourceId }?.id ?: put(remote.copy(id = ""))
    override suspend fun refreshDetails(id: String) = Unit
    override fun observeRecents(limit: Int) = foods.map { m -> m.values.filter { it.lastUsedAt != null }.sortedByDescending { it.lastUsedAt }.take(limit) }
    override fun observeFavorites() = foods.map { m -> m.values.filter { it.isFavorite } }
    var uses: Map<String, Int> = emptyMap()
    override suspend fun useCounts(ids: List<String>) = uses.filterKeys { it in ids }
    override fun observeCustom() = foods.map { m -> m.values.filter { it.source == FoodSource.CUSTOM } }
    override suspend fun setFavorite(id: String, favorite: Boolean) = foods.update { m -> m[id]?.let { m + (id to it.copy(isFavorite = favorite)) } ?: m }
    override suspend fun setFavoriteAmount(id: String, quantity: Double, servingLabel: String) = foods.update { m ->
        m[id]?.let { m + (id to it.copy(isFavorite = true, favoriteQuantity = quantity, favoriteServingLabel = servingLabel)) } ?: m
    }
    override suspend fun setCountsAsWater(id: String, counts: Boolean?) = foods.update { m -> m[id]?.let { m + (id to it.copy(countsAsWater = counts)) } ?: m }
    override suspend fun saveCustom(food: Food): String = put(food)
    override suspend fun delete(id: String) = foods.update { it - id }
    override suspend fun findLocalByBarcode(gtin14: String) = foods.value.values.firstOrNull { it.barcode == gtin14 }
    var barcodeResult: (String) -> BarcodeLookup = { gtin -> findBarcodeLocally(gtin) }
    private fun findBarcodeLocally(gtin: String) =
        foods.value.values.firstOrNull { it.barcode == gtin }?.let { BarcodeLookup.Found(it.id, fromCache = true) } ?: BarcodeLookup.NotFound
    override suspend fun lookupBarcode(gtin14: String): BarcodeLookup = barcodeResult(gtin14)

    companion object {
        fun food(name: String, kcal: Double = 130.0, source: FoodSource = FoodSource.USDA, sourceId: String = name, barcode: String? = null) = Food(
            "", name, null, source, sourceId, barcode, listOf(ServingSize("1 cup", 158.0)),
            mapOf(NutrientKey.ENERGY_KCAL to kcal, NutrientKey.PROTEIN_G to 2.7), false, null, null, null,
        )
    }
}

/** A SecretStore backed by a temp file and a trivial cipher (no Android Keystore on the JVM). */
fun testSecretStore(dir: File): SecretStore = SecretStore(
    File(dir, "store.bin"),
    {
        object : Aead {
            override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext.reversedArray()
            override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = ciphertext.reversedArray()
        }
    },
    Dispatchers.Unconfined,
)
