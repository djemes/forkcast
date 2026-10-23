package app.forkcast.core.data.db

import androidx.room.TypeConverter
import app.forkcast.core.model.ExerciseSession
import app.forkcast.core.model.MealEstimateJson
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Nutrients
import app.forkcast.core.model.ServingSize
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Room column conversions. Nutrient maps are stored as JSON objects keyed by enum name
 * ({"ENERGY_KCAL":120.0,...}); names never change, unknown names are skipped on read.
 */
class Converters {

    @TypeConverter fun localDateToString(d: LocalDate?): String? = d?.toString()
    @TypeConverter fun stringToLocalDate(s: String?): LocalDate? = s?.let(LocalDate::parse)

    @TypeConverter fun nutrientsToJson(n: Nutrients): String = NutrientJson.encode(n)
    @TypeConverter fun jsonToNutrients(s: String): Nutrients = NutrientJson.decode(s)

    @TypeConverter fun servingsToJson(list: List<ServingSize>): String =
        json.encodeToString(ListSerializer(ServingSize.serializer()), list)

    @TypeConverter fun jsonToServings(s: String): List<ServingSize> =
        json.decodeFromString(ListSerializer(ServingSize.serializer()), s)

    @TypeConverter fun stringsToJson(list: List<String>): String = json.encodeToString(ListSerializer(String.serializer()), list)
    @TypeConverter fun jsonToStrings(s: String): List<String> = json.decodeFromString(ListSerializer(String.serializer()), s)

    @TypeConverter fun sessionsToJson(list: List<ExerciseSession>): String =
        json.encodeToString(ListSerializer(ExerciseSession.serializer()), list)

    @TypeConverter fun jsonToSessions(s: String): List<ExerciseSession> =
        json.decodeFromString(ListSerializer(ExerciseSession.serializer()), s)

    @TypeConverter fun attemptsToJson(list: List<AttemptRecord>): String = json.encodeToString(ListSerializer(AttemptRecord.serializer()), list)
    @TypeConverter fun jsonToAttempts(s: String): List<AttemptRecord> =
        runCatching { json.decodeFromString(ListSerializer(AttemptRecord.serializer()), s) }.getOrDefault(emptyList())

    @TypeConverter fun estimateToJson(e: MealEstimateJson?): String? = e?.let { json.encodeToString(MealEstimateJson.serializer(), it) }
    @TypeConverter fun jsonToEstimate(s: String?): MealEstimateJson? = s?.let { json.decodeFromString(MealEstimateJson.serializer(), it) }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

object NutrientJson {
    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), Double.serializer())

    fun encode(n: Nutrients): String =
        json.encodeToString(mapSerializer, n.entries.sortedBy { it.key.ordinal }.associate { it.key.name to it.value })

    fun decode(s: String): Nutrients =
        json.decodeFromString(mapSerializer, s).mapNotNull { (k, v) ->
            runCatching { NutrientKey.valueOf(k) }.getOrNull()?.let { it to v }
        }.toMap()
}
