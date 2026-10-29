package app.forkcast.core.domain

import app.forkcast.core.model.Food
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.NutrientKey
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SearchRankingTest {

    private fun food(
        name: String,
        source: FoodSource,
        nutrients: Int = 8,
        brand: String? = null,
        id: String = "",
        sourceId: String? = name,
        barcode: String? = null,
        favorite: Boolean = false,
    ) = Food(
        id, name, brand, source, sourceId, barcode, emptyList(),
        NutrientKey.entries.take(nutrients).associateWith { 1.0 }, favorite, null, null, null,
    )

    @Test fun `plain banana ranks above branded banana products`() {
        val chips = food("BANANA CHIPS", FoodSource.USDA, nutrients = 12, brand = "GREAT VALUE")
        val bread = food("BANANA NUT BREAD", FoodSource.USDA, nutrients = 14, brand = "BAKERY")
        val raw = food("Bananas, raw", FoodSource.USDA, nutrients = 35)
        val ranked = SearchRanking.rank("banana", listOf(chips, bread, raw))
        assertThat(ranked.first()).isEqualTo(raw)
    }

    @Test fun `a matching favorite comes first, a non-matching one doesn't`() {
        val raw = food("Bananas, raw", FoodSource.USDA, nutrients = 35)
        val fav = food("Banana smoothie", FoodSource.CUSTOM, id = "f1", favorite = true)
        val otherFav = food("Oatmeal", FoodSource.CUSTOM, id = "f2", favorite = true)
        val ranked = SearchRanking.rank("banana", listOf(raw, otherFav, fav))
        assertThat(ranked.first()).isEqualTo(fav)
        assertThat(ranked.last()).isEqualTo(otherFav)
    }

    @Test fun `foods you log often rank higher`() {
        val a = food("Greek yogurt, plain", FoodSource.USDA, id = "a")
        val b = food("Greek yogurt, vanilla", FoodSource.USDA, id = "b")
        assertThat(SearchRanking.rank("greek yogurt", listOf(a, b), mapOf("b" to 15)).first()).isEqualTo(b)
    }

    @Test fun `same barcode from both databases appears once, the more complete one`() {
        val usda = food("Cheerios", FoodSource.USDA, nutrients = 10, barcode = "00016000275287")
        val off = food("Cheerios", FoodSource.OFF, nutrients = 20, barcode = "00016000275287", sourceId = "16000275287")
        val merged = SearchRanking.merge("cheerios", emptyList(), listOf(usda), listOf(off))
        assertThat(merged).containsExactly(off)
    }

    @Test fun `the copy on this phone replaces the search result`() {
        val local = food("Cheerios", FoodSource.USDA, id = "local1", sourceId = "123")
        val remote = food("Cheerios", FoodSource.USDA, nutrients = 30, sourceId = "123", barcode = "00016000275287")
        val merged = SearchRanking.merge("cheerios", listOf(local), listOf(remote), emptyList())
        assertThat(merged).containsExactly(local)
    }

    @Test fun `different foods without barcodes are all kept`() {
        val a = food("Rice, white", FoodSource.USDA)
        val b = food("Rice, white", FoodSource.OFF, sourceId = "999")
        assertThat(SearchRanking.merge("rice", emptyList(), listOf(a), listOf(b))).hasSize(2)
    }
}
