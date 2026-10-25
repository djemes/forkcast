package app.forkcast.core.network.ai

import app.forkcast.core.model.MealEstimateJson
import app.forkcast.core.network.NetworkJson
import kotlinx.serialization.SerializationException

/** System prompt and user text (AI_PROVIDERS.md §4). Same words for every provider. */
object MealPrompt {
    /** Recorded on each estimate, so accuracy can be compared across prompt versions. */
    const val VERSION = "meal-v2"

    /** v2 (2026-10-23): described meals, chain-restaurant and packaged items, drinks as items. */
    val system: String by lazy {
        requireNotNull(MealPrompt::class.java.getResourceAsStream("/ai/meal_system_prompt_v2.txt")) { "prompt resource missing" }
            .bufferedReader().use { it.readText().trim() }
    }

    /** The text that follows the images in the user turn. */
    fun userText(r: MealEstimateRequest): String = buildString {
        append("Meal: ${r.mealSlot} at ${r.localTime}. Units the user thinks in: ${r.unitsHint}.\n")
        append("Write every food and meal name in ${r.language}.\n")
        if (r.isTextOnly) append("No photo: estimate from the description alone.\n")
        append("Description: ${r.description?.takeIf { it.isNotBlank() }?.trim() ?: "none"}\n")
        append("Personal context: ${r.personalContext?.takeIf { it.isNotBlank() }?.trim() ?: "none"}")
        if (r.previous != null && !r.correction.isNullOrBlank()) {
            append("\n\nPrevious estimate:\n")
            append(NetworkJson.encodeToString(MealEstimateJson.serializer(), r.previous))
            append("\n\nUser correction: ${r.correction.trim()}. Return the full updated result.")
        }
    }

    const val TEST_TEXT = "Reply with the single word: ok"
}

/**
 * Parses and sanity-checks model output (AI_PROVIDERS.md §3): grams > 0, low ≤ grams ≤ high,
 * ≤ 9.5 kcal/g. Bad items are fixed or dropped with a warning; this never throws anything
 * other than [AiError.BadResponse].
 */
object MealEstimateParser {
    private const val MAX_KCAL_PER_G = 9.5

    /** [warnings] are "<code>\t<item name>" ([NO_WEIGHT], [KCAL_CAPPED]); the review screen words them. */
    data class Parsed(val json: MealEstimateJson, val warnings: List<String>)

    const val NO_WEIGHT = "no_weight"
    const val KCAL_CAPPED = "kcal_capped"

    fun parse(raw: String): Parsed {
        val text = stripFences(raw)
        val decoded = try {
            NetworkJson.decodeFromString(MealEstimateJson.serializer(), text)
        } catch (e: SerializationException) {
            throw AiError.BadResponse(raw, "not valid JSON")
        } catch (e: IllegalArgumentException) {
            throw AiError.BadResponse(raw, "not valid JSON")
        }
        return validate(decoded)
    }

    fun validate(input: MealEstimateJson): Parsed {
        val warnings = mutableListOf<String>()
        val items = input.items.mapNotNull { item ->
            val label = item.name.ifBlank { "item" }
            if (!(item.grams > 0) || item.grams.isNaN()) {
                warnings += "$NO_WEIGHT\t$label"
                return@mapNotNull null
            }
            var low = item.gramsLow.takeIf { it > 0 && it.isFinite() } ?: item.grams
            var high = item.gramsHigh.takeIf { it > 0 && it.isFinite() } ?: item.grams
            if (low > high) low = high.also { high = low }
            if (low > item.grams) low = item.grams
            if (high < item.grams) high = item.grams
            var nutrients = item.nutrients
            if (nutrients.kcal < 0 || nutrients.kcal > item.grams * MAX_KCAL_PER_G) {
                val capped = (item.grams * MAX_KCAL_PER_G).coerceAtLeast(0.0)
                warnings += "$KCAL_CAPPED\t$label"
                nutrients = nutrients.copy(kcal = nutrients.kcal.coerceIn(0.0, capped))
            }
            nutrients = nutrients.copy(
                proteinG = nutrients.proteinG.coerceAtLeast(0.0),
                carbsG = nutrients.carbsG.coerceAtLeast(0.0),
                fatG = nutrients.fatG.coerceAtLeast(0.0),
            )
            item.copy(
                name = label,
                usdaQuery = item.usdaQuery.ifBlank { label },
                gramsLow = low,
                gramsHigh = high,
                confidence = item.confidence.coerceIn(0.0, 1.0),
                nutrients = nutrients,
            )
        }
        if (items.isEmpty() && input.clarifyingQuestion.isNullOrBlank()) {
            throw AiError.BadResponse(null, "no food items in the estimate")
        }
        return Parsed(
            input.copy(
                items = items,
                overallConfidence = input.overallConfidence.coerceIn(0.0, 1.0),
                clarifyingQuestion = input.clarifyingQuestion?.takeIf { it.isNotBlank() },
            ),
            warnings,
        )
    }

    /** Some OpenAI-compatible servers wrap JSON in ``` fences even in JSON mode. */
    private fun stripFences(raw: String): String {
        val t = raw.trim()
        if (!t.startsWith("```")) return t
        return t.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    }
}
