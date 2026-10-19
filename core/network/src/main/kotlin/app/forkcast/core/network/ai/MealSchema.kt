package app.forkcast.core.network.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The response schema (AI_PROVIDERS.md §3) and its per-provider variants. The canonical form is
 * what Gemini gets; Claude and OpenAI strict mode need small, mechanical changes.
 */
object MealSchema {

    // Declared first: the schemas below are built at class init and read it.
    private val NUTRIENT_FIELDS = listOf("kcal", "protein_g", "carbs_g", "fat_g", "fiber_g", "sugar_g", "sat_fat_g", "sodium_mg")

    val canonical: JsonObject = buildJsonObject {
        put("type", "object")
        required("meal_name", "items", "overall_confidence", "assumptions", "clarifying_question")
        putJsonObject("properties") {
            putJsonObject("meal_name") { put("type", "string") }
            putJsonObject("items") {
                put("type", "array")
                putJsonObject("items") {
                    put("type", "object")
                    required(
                        "name", "usda_query", "preparation", "grams", "grams_low", "grams_high",
                        "confidence", "is_hidden_ingredient", "nutrients",
                    )
                    putJsonObject("properties") {
                        putJsonObject("name") { put("type", "string") }
                        putJsonObject("usda_query") {
                            put("type", "string")
                            put("description", "Generic USDA-style name, e.g. 'Rice, white, cooked' or 'Chicken thigh, roasted, meat only'")
                        }
                        putJsonObject("preparation") {
                            put("type", "string")
                            put("description", "raw | boiled | fried | roasted | grilled | baked | sauce | unknown ...")
                        }
                        putJsonObject("grams") { put("type", "number") }
                        putJsonObject("grams_low") { put("type", "number") }
                        putJsonObject("grams_high") { put("type", "number") }
                        putJsonObject("confidence") { unitInterval() }
                        putJsonObject("is_hidden_ingredient") {
                            put("type", "boolean")
                            put("description", "true for cooking oil, butter, dressing, sugar in drinks, etc. inferred rather than seen")
                        }
                        putJsonObject("nutrients") {
                            put("type", "object")
                            required("kcal", "protein_g", "carbs_g", "fat_g")
                            putJsonObject("properties") {
                                for (k in NUTRIENT_FIELDS) putJsonObject(k) { put("type", "number") }
                            }
                        }
                    }
                }
            }
            putJsonObject("overall_confidence") { unitInterval() }
            putJsonObject("assumptions") {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
            }
            putJsonObject("clarifying_question") {
                putJsonArray("type") { add(JsonPrimitive("string")); add(JsonPrimitive("null")) }
            }
        }
    }

    /** Claude structured outputs: no numeric bounds; `additionalProperties: false` on every object. */
    val claude: JsonObject = transform(canonical, stripBounds = true, closeObjects = true, requireAll = false)

    /** OpenAI strict json_schema: also every property required (optional ones become nullable). */
    val openAiStrict: JsonObject = transform(canonical, stripBounds = true, closeObjects = true, requireAll = true)

    private fun kotlinx.serialization.json.JsonObjectBuilder.required(vararg names: String) =
        put("required", buildJsonArray { names.forEach { add(JsonPrimitive(it)) } })

    private fun kotlinx.serialization.json.JsonObjectBuilder.unitInterval() {
        put("type", "number")
        put("minimum", 0)
        put("maximum", 1)
    }

    private fun transform(node: JsonElement, stripBounds: Boolean, closeObjects: Boolean, requireAll: Boolean): JsonObject {
        val obj = node.jsonObject
        val props = obj["properties"]?.jsonObject
        val required = (obj["required"] as? JsonArray)?.map { (it as JsonPrimitive).content }?.toSet() ?: emptySet()
        return buildJsonObject {
            for ((key, value) in obj) {
                when {
                    stripBounds && (key == "minimum" || key == "maximum") -> Unit
                    key == "properties" && props != null -> putJsonObject("properties") {
                        for ((name, schema) in props) {
                            var child = transform(schema, stripBounds, closeObjects, requireAll)
                            if (requireAll && name !in required) child = nullable(child)
                            put(name, child)
                        }
                    }
                    key == "required" && requireAll && props != null ->
                        put("required", buildJsonArray { props.keys.forEach { add(JsonPrimitive(it)) } })
                    key == "items" && value is JsonObject -> put("items", transform(value, stripBounds, closeObjects, requireAll))
                    else -> put(key, value)
                }
            }
            if (closeObjects && obj["type"] == JsonPrimitive("object")) put("additionalProperties", false)
        }
    }

    private fun nullable(schema: JsonObject): JsonObject = buildJsonObject {
        for ((k, v) in schema) {
            if (k == "type" && v is JsonPrimitive) put("type", buildJsonArray { add(v); add(JsonPrimitive("null")) }) else put(k, v)
        }
    }
}
