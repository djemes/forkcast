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
import io.ktor.client.engine.mock.respondError
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
}
