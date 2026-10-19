package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiMatchingTest {

    private fun c(name: String, type: String, kcal100: Double) = AiMatching.Candidate(name, name, type, kcal100)

    @Test fun picksTheClosestGenericRecord() {
        val candidates = listOf(
            c("Rice, white, long-grain, regular, enriched, cooked", "SR Legacy", 130.0),
            c("Rice, brown, long-grain, cooked", "SR Legacy", 123.0),
            c("Rice pudding", "Survey (FNDDS)", 122.0),
        )
        val best = AiMatching.best("Rice, white, long-grain, cooked", "boiled", modelKcalPerG = 1.3, candidates)
        assertThat(best!!.first.name).startsWith("Rice, white")
    }

    @Test fun rejectsAMatchWhoseCaloriesDisagree() {
        // Model thinks this is a light dish; USDA's top text match is far denser.
        val candidates = listOf(c("Chicken, thigh, meat and skin, fried, batter", "SR Legacy", 277.0))
        assertThat(AiMatching.best("Chicken thigh, fried", "fried", modelKcalPerG = 1.5, candidates)).isNull()
        assertThat(AiMatching.best("Chicken thigh, fried", "fried", modelKcalPerG = 2.6, candidates)).isNotNull()
    }

    @Test fun belowThresholdFallsBackToModelValues() {
        val candidates = listOf(c("Beverages, coffee, brewed", "SR Legacy", 1.0))
        assertThat(AiMatching.best("Chicken tikka masala", "sauce", modelKcalPerG = 1.4, candidates)).isNull()
    }

    @Test fun prefersFnddsForMixedDishes() {
        val sr = AiMatching.score("Pizza, cheese, thin crust", "baked", "Pizza, cheese, thin crust", "SR Legacy")
        val fndds = AiMatching.score("Pizza, cheese, thin crust", "baked", "Pizza, cheese, thin crust", "Survey (FNDDS)")
        assertThat(fndds).isGreaterThan(sr)
        val single = AiMatching.score("Broccoli, raw", "raw", "Broccoli, raw", "Foundation")
        val singleFndds = AiMatching.score("Broccoli, raw", "raw", "Broccoli, raw", "Survey (FNDDS)")
        assertThat(single).isGreaterThan(singleFndds)
    }

    @Test fun oilMatchesWithNearZeroWaterContent() {
        val oil = listOf(c("Oil, olive, salad or cooking", "SR Legacy", 884.0))
        assertThat(AiMatching.best("Oil, olive, salad or cooking", "unknown", modelKcalPerG = 8.8, oil)).isNotNull()
    }

    @Test fun cacheKeyIgnoresCaseAndPunctuation() {
        assertThat(AiMatching.cacheKey("Rice, WHITE, cooked", "Boiled")).isEqualTo(AiMatching.cacheKey("rice white cooked", "boiled"))
    }
}
