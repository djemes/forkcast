package app.forkcast.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.ServingSize
import app.forkcast.core.network.HttpClientFactory
import app.forkcast.core.network.OffClient
import app.forkcast.core.network.UsdaClient
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class FoodRepositoryTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ForkcastDatabase::class.java)
        .allowMainThreadQueries().build()
    private val http = HttpClientFactory.create("t", MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })
    private val repo = DefaultFoodRepository(
        db.foodDao(), UsdaClient(http) { null }, OffClient(http),
        Clock.fixed(Instant.parse("2026-10-18T16:00:00Z"), ZoneId.of("UTC")),
    )

    @After fun tearDown() = db.close()

    private fun remote(name: String, brand: String? = null, sourceId: String = name, kcal: Double = 100.0) = Food(
        "", name, brand, FoodSource.USDA, sourceId, null, listOf(ServingSize("1 cup", 158.0)),
        mapOf(NutrientKey.ENERGY_KCAL to kcal), false, null, null, null,
    )

    @Test fun localSearchMatchesEveryWordInNameOrBrand() = runTest {
        repo.cache(remote("Rice, white, long-grain, cooked"))
        repo.cache(remote("Rice, brown, cooked", sourceId = "b"))
        repo.cache(remote("Greek yogurt", brand = "Chobani", sourceId = "y"))
        assertThat(repo.searchLocal("white rice").map { it.name }).containsExactly("Rice, white, long-grain, cooked")
        assertThat(repo.searchLocal("chobani yogurt")).hasSize(1)
        assertThat(repo.searchLocal("RICE")).hasSize(2)
        assertThat(repo.searchLocal("  ")).isEmpty()
    }

    @Test fun cachingTwiceUpdatesInsteadOfDuplicating() = runTest {
        val id1 = repo.cache(remote("Rice", sourceId = "168878", kcal = 130.0))
        repo.setFavorite(id1, true)
        val id2 = repo.cache(remote("Rice, cooked", sourceId = "168878", kcal = 131.0))
        assertThat(id2).isEqualTo(id1)
        val food = repo.get(id1)!!
        assertThat(food.name).isEqualTo("Rice, cooked")
        assertThat(food.isFavorite).isTrue()
        assertThat(repo.observeFavorites().first()).hasSize(1)
    }

    /** A favorite's default amount (roadmap 3.10): its last amount when starred, then whatever the user sets; caching keeps it. */
    @Test fun favoritingKeepsADefaultAmount() = runTest {
        val id = repo.cache(remote("Oats", sourceId = "o"))
        db.foodDao().markUsed(id, 0.5, "cup", 1)
        repo.setFavorite(id, true)
        assertThat(repo.get(id)!!.defaultAmount).isEqualTo(0.5 to "cup")
        db.foodDao().markUsed(id, 2.0, "cup", 2)
        assertThat(repo.get(id)!!.defaultAmount).isEqualTo(0.5 to "cup")
        repo.setFavoriteAmount(id, 40.0, "g")
        repo.cache(remote("Oats, rolled", sourceId = "o"))
        assertThat(repo.get(id)!!.defaultAmount).isEqualTo(40.0 to "g")
        // Not a favorite: the last amount again; starring again restores the default it had.
        repo.setFavorite(id, false)
        assertThat(repo.get(id)!!.defaultAmount).isEqualTo(2.0 to "cup")
        repo.setFavorite(id, true)
        assertThat(repo.get(id)!!.defaultAmount).isEqualTo(40.0 to "g")
    }

    @Test fun customFoodsCreateUpdateDelete() = runTest {
        val id = repo.saveCustom(remote("Gabe's chili").copy(source = FoodSource.CUSTOM, sourceId = null))
        assertThat(repo.observeCustom().first().single().name).isEqualTo("Gabe's chili")
        repo.saveCustom(repo.get(id)!!.copy(name = "Chili"))
        assertThat(repo.get(id)!!.name).isEqualTo("Chili")
        repo.delete(id)
        assertThat(repo.get(id)).isNull()
        assertThat(repo.searchLocal("chili")).isEmpty()
    }

    @Test fun refreshDetailsOfflineKeepsTheCachedCopy() = runTest {
        val id = repo.cache(remote("Rice", sourceId = "168878"))
        repo.refreshDetails(id) // the mock server returns 503
        assertThat(repo.get(id)!!.nutrientsPer100g[NutrientKey.ENERGY_KCAL]).isEqualTo(100.0)
    }

    @Test fun barcodeLookupCachedFoundNotFoundAndOffline() = runTest {
        // Cached: found without the network (the mock server is down).
        val id = repo.saveCustom(remote("Label bar").copy(source = FoodSource.LABEL, sourceId = null, barcode = "00012345678905"))
        assertThat(repo.lookupBarcode("00012345678905")).isEqualTo(BarcodeLookup.Found(id, fromCache = true))

        // Not cached and both databases unreachable: a network failure, not "not found".
        assertThat(repo.lookupBarcode("00016000275287")).isInstanceOf(BarcodeLookup.Failed::class.java)
    }

    @Test fun barcodeLookupCachesTheWinnerWithItsBarcode() = runTest {
        val fixtures = mapOf(
            "api.nal.usda.gov" to javaClass.getResource("/fixtures/usda_search_branded.json")!!.readText(),
            "world.openfoodfacts.org" to """{"status":0}""",
        )
        val online = HttpClientFactory.create("t", MockEngine { req ->
            respond(fixtures.getValue(req.url.host), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repo2 = DefaultFoodRepository(db.foodDao(), UsdaClient(online) { "k" }, OffClient(online), Clock.systemUTC())
        val result = repo2.lookupBarcode("00016000275287") as BarcodeLookup.Found
        assertThat(result.fromCache).isFalse()
        val food = repo2.get(result.foodId)!!
        assertThat(food.name).isEqualTo("Cheerios Cereal")
        assertThat(food.barcode).isEqualTo("00016000275287")
        // Second scan is local and works offline.
        assertThat(repo.lookupBarcode("00016000275287")).isEqualTo(BarcodeLookup.Found(result.foodId, fromCache = true))
    }

    @Test fun barcodeNotFoundWhenADatabaseAnswered() = runTest {
        val online = HttpClientFactory.create("t", MockEngine { req ->
            val body = if (req.url.host == "api.nal.usda.gov") """{"foods":[]}""" else """{"status":0}"""
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val repo2 = DefaultFoodRepository(db.foodDao(), UsdaClient(online) { "k" }, OffClient(online), Clock.systemUTC())
        assertThat(repo2.lookupBarcode("00012345678905")).isEqualTo(BarcodeLookup.NotFound)
    }
}
