package app.forkcast.core.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.changes.DeletionChange
import androidx.health.connect.client.changes.UpsertionChange
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.MealType
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Volume
import androidx.health.connect.client.aggregate.AggregateMetric
import app.forkcast.core.domain.HealthRules
import app.forkcast.core.domain.MealKind
import app.forkcast.core.model.ActivityDay
import app.forkcast.core.model.ExerciseSession
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Nutrients
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.reflect.KClass

/** [HealthConnectSource] on the real Health Connect client (connect-client 1.1.0). */
@Singleton
class ClientHealthConnectSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : HealthConnectSource {

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    override fun availability(): HcAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HcAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcAvailability.UPDATE_REQUIRED
        else -> HcAvailability.UNAVAILABLE
    }

    override suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    override fun historyReadSupported(): Boolean = feature(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY)

    override fun backgroundReadSupported(): Boolean = feature(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND)

    private fun feature(feature: Int): Boolean =
        availability() == HcAvailability.AVAILABLE &&
            client.features.getFeatureStatus(feature) == HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    override suspend fun revokeAll() = client.permissionController.revokeAllPermissions()

    override suspend fun readWeights(from: Instant, to: Instant): List<HcWeight> =
        readAll(WeightRecord::class, TimeRangeFilter.between(from, to)).map { it.toWeight() }

    override suspend fun readBodyFat(from: Instant, to: Instant): List<HcBodyFat> =
        readAll(BodyFatRecord::class, TimeRangeFilter.between(from, to)).map { it.toBodyFat() }

    override suspend fun latestHeightCm(from: Instant): Double? =
        client.readRecords(
            ReadRecordsRequest(HeightRecord::class, TimeRangeFilter.after(from), ascendingOrder = false, pageSize = 1),
        ).records.firstOrNull()?.height?.inMeters?.times(100)

    override suspend fun bodyChangesToken(includeBodyFat: Boolean): String {
        val types: Set<KClass<out Record>> = if (includeBodyFat) setOf(WeightRecord::class, BodyFatRecord::class) else setOf(WeightRecord::class)
        return client.getChangesToken(ChangesTokenRequest(types))
    }

    override suspend fun bodyChanges(token: String): HcBodyChanges {
        val response = client.getChanges(token)
        val weights = ArrayList<HcWeight>()
        val bodyFat = ArrayList<HcBodyFat>()
        val deleted = ArrayList<String>()
        response.changes.forEach { change ->
            when (change) {
                is UpsertionChange -> when (val r = change.record) {
                    is WeightRecord -> weights += r.toWeight()
                    is BodyFatRecord -> bodyFat += r.toBodyFat()
                }
                is DeletionChange -> deleted += change.recordId
            }
        }
        return HcBodyChanges(weights, bodyFat, deleted, response.nextChangesToken, response.hasMore, response.changesTokenExpired)
    }

    override suspend fun activity(from: LocalDate, toInclusive: LocalDate, zone: ZoneId, granted: Set<String>): List<ActivityDay> {
        val metrics = buildSet<AggregateMetric<*>> {
            if (HealthPermissions.READ_STEPS in granted) add(StepsRecord.COUNT_TOTAL)
            if (HealthPermissions.READ_ACTIVE_CALORIES in granted) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (HealthPermissions.READ_TOTAL_CALORIES in granted) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (HealthPermissions.READ_EXERCISE in granted) add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
        }
        if (metrics.isEmpty()) return emptyList()
        // Local-time ranges slice by the phone's days (SPEC §4.9 ActivityDay is per local date).
        val groups = client.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = metrics,
                timeRangeFilter = TimeRangeFilter.between(from.atStartOfDay(), toInclusive.plusDays(1).atStartOfDay()),
                timeRangeSlicer = Period.ofDays(1),
            ),
        )
        val sessions = if (HealthPermissions.READ_EXERCISE in granted) {
            val range = TimeRangeFilter.between(from.atStartOfDay(zone).toInstant(), toInclusive.plusDays(1).atStartOfDay(zone).toInstant())
            readAll(ExerciseSessionRecord::class, range).map { s ->
                val kcal = if (HealthPermissions.READ_ACTIVE_CALORIES in granted) {
                    client.aggregate(
                        AggregateRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), TimeRangeFilter.between(s.startTime, s.endTime)),
                    )[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                } else {
                    null
                }
                ExerciseSession(s.metadata.id, s.title, s.exerciseType, s.startTime.toEpochMilli(), s.endTime.toEpochMilli(), kcal)
            }.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() }
        } else {
            emptyMap()
        }
        return groups.mapNotNull { g ->
            val r = g.result
            val date = g.startTime.toLocalDate()
            val day = ActivityDay(
                date = date,
                steps = r[StepsRecord.COUNT_TOTAL],
                activeKcal = r[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories,
                totalKcal = r[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories,
                exerciseMinutes = r[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes()?.toInt(),
                sessions = sessions[date].orEmpty(),
            )
            day.takeIf { it.steps != null || it.activeKcal != null || it.totalKcal != null || it.exerciseMinutes != null || it.sessions.isNotEmpty() }
        }
    }

    override suspend fun writeNutrition(records: List<NutritionWrite>): List<String> =
        client.insertRecords(records.map { it.toRecord() }).recordIdsList

    override suspend fun deleteNutrition(clientIds: List<String>) =
        client.deleteRecords(NutritionRecord::class, recordIdsList = emptyList(), clientRecordIdsList = clientIds)

    override suspend fun writeHydration(records: List<HydrationWrite>): List<String> =
        client.insertRecords(
            records.map {
                HydrationRecord(
                    startTime = it.start,
                    startZoneOffset = it.offset,
                    endTime = it.start.plus(HealthRules.NUTRITION_RECORD_LENGTH),
                    endZoneOffset = it.offset,
                    volume = Volume.milliliters(it.ml.coerceIn(0.0, MAX_HYDRATION_ML)),
                    metadata = Metadata.manualEntry(clientRecordId = it.clientId, clientRecordVersion = it.version),
                )
            },
        ).recordIdsList

    override suspend fun deleteHydration(clientIds: List<String>) =
        client.deleteRecords(HydrationRecord::class, recordIdsList = emptyList(), clientRecordIdsList = clientIds)

    private suspend fun <T : Record> readAll(type: KClass<T>, range: TimeRangeFilter): List<T> {
        val out = ArrayList<T>()
        var page: String? = null
        do {
            val response = client.readRecords(ReadRecordsRequest(type, range, pageToken = page))
            out += response.records
            // Some versions return "" instead of null for the last page.
            page = response.pageToken?.takeIf { it.isNotEmpty() }
        } while (page != null)
        return out
    }

    private fun WeightRecord.toWeight() = HcWeight(metadata.id, time, weight.inKilograms, metadata.dataOrigin.packageName)

    private fun BodyFatRecord.toBodyFat() = HcBodyFat(metadata.id, time, percentage.value, metadata.dataOrigin.packageName)

    private fun NutritionWrite.toRecord(): NutritionRecord {
        fun g(key: NutrientKey, max: Double = MAX_G) = nutrients[key]?.let { Mass.grams(it.coerceIn(0.0, max)) }
        fun mg(key: NutrientKey) = nutrients[key]?.let { Mass.grams((it / 1_000).coerceIn(0.0, MAX_SMALL_G)) }
        fun ug(key: NutrientKey) = nutrients[key]?.let { Mass.grams((it / 1_000_000).coerceIn(0.0, MAX_SMALL_G)) }
        return NutritionRecord(
            startTime = start,
            startZoneOffset = offset,
            endTime = start.plus(HealthRules.NUTRITION_RECORD_LENGTH),
            endZoneOffset = offset,
            metadata = Metadata.manualEntry(clientRecordId = clientId, clientRecordVersion = version),
            energy = nutrients[NutrientKey.ENERGY_KCAL]?.let { Energy.kilocalories(it.coerceIn(0.0, MAX_KCAL)) },
            protein = g(NutrientKey.PROTEIN_G),
            totalCarbohydrate = g(NutrientKey.CARBS_G),
            totalFat = g(NutrientKey.FAT_G),
            saturatedFat = g(NutrientKey.SAT_FAT_G),
            transFat = g(NutrientKey.TRANS_FAT_G),
            monounsaturatedFat = g(NutrientKey.MONO_FAT_G),
            polyunsaturatedFat = g(NutrientKey.POLY_FAT_G),
            cholesterol = mg(NutrientKey.CHOLESTEROL_MG),
            dietaryFiber = g(NutrientKey.FIBER_G),
            sugar = g(NutrientKey.SUGARS_G),
            sodium = mg(NutrientKey.SODIUM_MG),
            potassium = mg(NutrientKey.POTASSIUM_MG),
            calcium = mg(NutrientKey.CALCIUM_MG),
            iron = mg(NutrientKey.IRON_MG),
            magnesium = mg(NutrientKey.MAGNESIUM_MG),
            vitaminA = ug(NutrientKey.VITAMIN_A_UG),
            vitaminC = mg(NutrientKey.VITAMIN_C_MG),
            vitaminD = ug(NutrientKey.VITAMIN_D_UG),
            vitaminB12 = ug(NutrientKey.VITAMIN_B12_UG),
            caffeine = mg(NutrientKey.CAFFEINE_MG),
            name = name.take(MAX_NAME),
            mealType = when (meal) {
                MealKind.BREAKFAST -> MealType.MEAL_TYPE_BREAKFAST
                MealKind.LUNCH -> MealType.MEAL_TYPE_LUNCH
                MealKind.DINNER -> MealType.MEAL_TYPE_DINNER
                MealKind.SNACK -> MealType.MEAL_TYPE_SNACK
                MealKind.UNKNOWN -> MealType.MEAL_TYPE_UNKNOWN
            },
        )
    }

    private companion object {
        // Health Connect's valid ranges (NutritionRecord, HydrationRecord); values outside fail the whole insert.
        const val MAX_G = 100_000.0
        const val MAX_SMALL_G = 100.0
        const val MAX_KCAL = 100_000.0
        const val MAX_HYDRATION_ML = 100_000.0
        const val MAX_NAME = 200
    }
}
