package app.forkcast.core.data.health

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.BodyFatRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeightRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import app.forkcast.core.model.HealthSettings

/**
 * Health Connect permissions Forkcast uses (SPEC §4.9). Each one is also declared in the
 * app manifest; ManifestPrivacyTest keeps the two lists equal.
 */
object HealthPermissions {
    val READ_WEIGHT = HealthPermission.getReadPermission(WeightRecord::class)
    val READ_BODY_FAT = HealthPermission.getReadPermission(BodyFatRecord::class)
    val READ_HEIGHT = HealthPermission.getReadPermission(HeightRecord::class)
    val READ_STEPS = HealthPermission.getReadPermission(StepsRecord::class)
    val READ_ACTIVE_CALORIES = HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
    val READ_TOTAL_CALORIES = HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class)
    val READ_EXERCISE = HealthPermission.getReadPermission(ExerciseSessionRecord::class)
    val WRITE_NUTRITION = HealthPermission.getWritePermission(NutritionRecord::class)
    val WRITE_HYDRATION = HealthPermission.getWritePermission(HydrationRecord::class)
    /** Data from before the 30 days preceding the first grant (owner: yes, full weight history). */
    const val READ_HISTORY = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    /** Reads while the app is closed: background sync and the weigh-in reminder (owner: yes). */
    const val READ_IN_BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    val BODY = setOf(READ_WEIGHT, READ_BODY_FAT, READ_HEIGHT)
    val ACTIVITY = setOf(READ_STEPS, READ_ACTIVE_CALORIES, READ_TOTAL_CALORIES, READ_EXERCISE)

    val ALL: Set<String> = BODY + ACTIVITY + setOf(WRITE_NUTRITION, WRITE_HYDRATION, READ_HISTORY, READ_IN_BACKGROUND)

    /** What to ask for, given the user's choices and what this Health Connect supports. */
    fun wanted(settings: HealthSettings, historySupported: Boolean, backgroundSupported: Boolean): Set<String> = buildSet {
        if (settings.readWeight) addAll(BODY)
        if (settings.readActivity) addAll(ACTIVITY)
        if (settings.writeNutrition) add(WRITE_NUTRITION)
        if (settings.writeHydration) add(WRITE_HYDRATION)
        val reads = settings.readWeight || settings.readActivity
        if (reads && historySupported) add(READ_HISTORY)
        if (reads && backgroundSupported) add(READ_IN_BACKGROUND)
    }
}
