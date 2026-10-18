package app.forkcast.core.network

import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.NutrientKey
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/** Parsers run against real responses saved from the live APIs on 2026-10-18. */
class FoodClientsTest {

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    private val requests = mutableListOf<HttpRequestData>()

    private fun client(handler: (HttpRequestData) -> Pair<HttpStatusCode, String>) = HttpClientFactory.create(
        "Forkcast/test (https://github.com/djemes/forkcast)",
        MockEngine { req ->
            requests += req
            val (status, body) = handler(req)
            if (status.value >= 400) respondError(status, body)
            else respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    @Test fun usdaSearchParsesSrLegacyWithPortions() = runTest {
        val usda = UsdaClient(client { HttpStatusCode.OK to fixture("usda_search_sr.json") }) { "my-key" }
        val foods = usda.search("rice")
        val rice = foods.first()
        assertThat(rice.source).isEqualTo(FoodSource.USDA)
        assertThat(rice.name).startsWith("Rice, white, long-grain")
        assertThat(rice.nutrientsPer100g[NutrientKey.ENERGY_KCAL]).isWithin(1.0).of(130.0)
        assertThat(rice.nutrientsPer100g).containsKey(NutrientKey.PROTEIN_G)
        // Key travels in a header, never in the URL.
        assertThat(requests.single().headers["X-Api-Key"]).isEqualTo("my-key")
        assertThat(requests.single().url.toString()).doesNotContain("my-key")
        assertThat(requests.single().headers[HttpHeaders.UserAgent]).startsWith("Forkcast/")
    }

    @Test fun usdaFallsBackToDemoKey() = runTest {
        val usda = UsdaClient(client { HttpStatusCode.OK to fixture("usda_search_sr.json") }) { null }
        usda.search("rice")
        assertThat(requests.single().headers["X-Api-Key"]).isEqualTo("DEMO_KEY")
    }

    @Test fun usdaFoundationEnergyComesFromAtwater() = runTest {
        val usda = UsdaClient(client { HttpStatusCode.OK to fixture("usda_search_foundation.json") }) { "k" }
        val chicken = usda.search("chicken").first()
        assertThat(chicken.nutrientsPer100g[NutrientKey.ENERGY_KCAL]).isGreaterThan(80.0)
        assertThat(chicken.nutrientsPer100g[NutrientKey.ENERGY_KCAL]).isLessThan(150.0)
    }

    @Test fun usdaBrandedHasGtinServingAndFirstNutrientWins() = runTest {
        val usda = UsdaClient(client { HttpStatusCode.OK to fixture("usda_search_branded.json") }) { "k" }
        val cheerios = usda.search("cheerios").first()
        assertThat(cheerios.barcode).isEqualTo("00016000275287")
        assertThat(cheerios.brand).isEqualTo("Cheerios")
        assertThat(cheerios.servingSizes.first().grams).isEqualTo(20.0)
        // The search row repeats energy with a per-serving derivation (117); the per-100 g value wins.
        assertThat(cheerios.nutrientsPer100g[NutrientKey.ENERGY_KCAL]).isEqualTo(359.0)
    }

    @Test fun usdaDetailHasPortions() = runTest {
        val usda = UsdaClient(client { HttpStatusCode.OK to fixture("usda_food_sr.json") }) { "k" }
        val rice = usda.food("168878")!!
        assertThat(rice.servingSizes.map { it.grams }).contains(158.0)
        assertThat(rice.servingSizes.first().label).contains("cup")
    }

    @Test fun fnddsPortionsSkipQuantityNotSpecified() = runTest {
        val usda = UsdaClient(client { HttpStatusCode.OK to fixture("usda_food_fndds.json") }) { "k" }
        val rice = usda.food("2708408")!!
        // FNDDS lists "Quantity not specified" (modifier code 90000) first; it must not become a serving.
        assertThat(rice.servingSizes.map { it.label }).containsExactly("1 cup, cooked")
        assertThat(rice.servingSizes.single().grams).isEqualTo(158.0)
    }

    @Test fun usdaByGtinMatchesNormalizedCode() = runTest {
        val usda = UsdaClient(client { HttpStatusCode.OK to fixture("usda_search_branded.json") }) { "k" }
        assertThat(usda.byGtin("00016000275287")!!.name).isEqualTo("Cheerios Cereal")
    }

    @Test fun usdaErrorsAreTyped() = runTest {
        assertThrows(FoodApiError.InvalidKey::class.java) {
            kotlinx.coroutines.runBlocking { UsdaClient(client { HttpStatusCode.Forbidden to "{}" }) { "bad" }.search("x") }
        }
        assertThrows(FoodApiError.BadResponse::class.java) {
            kotlinx.coroutines.runBlocking { UsdaClient(client { HttpStatusCode.OK to "<html>" }) { "k" }.search("x") }
        }
    }

    @Test fun offProductMapsUnitsAndBrand() = runTest {
        val off = OffClient(client { HttpStatusCode.OK to fixture("off_product.json") })
        val nutella = off.product("03017624010701")!!
        assertThat(nutella.source).isEqualTo(FoodSource.OFF)
        assertThat(nutella.brand).isEqualTo("Ferrero")
        assertThat(nutella.barcode).isEqualTo("03017624010701")
        assertThat(nutella.nutrientsPer100g[NutrientKey.ENERGY_KCAL]).isWithin(5.0).of(539.0)
        // OFF stores sodium in grams; we store mg.
        assertThat(nutella.nutrientsPer100g[NutrientKey.SODIUM_MG]!!).isGreaterThan(10.0)
        assertThat(requests.single().url.encodedPath).endsWith("/api/v2/product/3017624010701")
    }

    @Test fun offNotFoundIsNull() = runTest {
        val off = OffClient(client { HttpStatusCode.OK to fixture("off_not_found.json") })
        assertThat(off.product("00000000000000")).isNull()
    }

    @Test fun offSearchParsesHits() = runTest {
        val off = OffClient(client { HttpStatusCode.OK to fixture("off_search.json") })
        val hits = off.search("greek yogurt")
        assertThat(hits).isNotEmpty()
        assertThat(hits.first().brand).isEqualTo("Chobani")
        assertThat(hits.first().nutrientsPer100g).containsKey(NutrientKey.PROTEIN_G)
    }

    @Test fun offlineIsTyped() = runTest {
        val http = HttpClientFactory.create("t", MockEngine { throw java.net.UnknownHostException("no network") })
        assertThrows(FoodApiError.Offline::class.java) {
            kotlinx.coroutines.runBlocking { OffClient(http).search("x") }
        }
    }
}
