package app.forkcast.core.data.health

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/** Why the last sync stopped short; shown in Settings › Health Connect. */
@Serializable
enum class HealthError { UNAVAILABLE, PERMISSION, RATE_LIMITED, FAILED }

/**
 * This phone's Health Connect sync position. Kept in its own file under `health-sync/`, which
 * Auto Backup and device transfer skip: changes tokens belong to this phone's Health Connect.
 */
@Serializable
data class HealthSyncState(
    /** Changes token for Weight (+ BodyFat when [bodyTokenHasBodyFat]). */
    val bodyToken: String? = null,
    /**
     * Write state in the database (hcSyncedAt) was reset for this install. Auto Backup restores
     * the database but not this file, so a restored phone writes everything to its own Health
     * Connect again instead of trusting the old phone's write state.
     */
    val writesInitialized: Boolean = false,
    val bodyTokenHasBodyFat: Boolean = false,
    /** The last full weight read went back past 30 days (history permission). */
    val weightsFullHistory: Boolean = false,
    /** The first activity read (30 or 90 days) is done; later syncs refresh the last week. */
    val activityBackfilled: Boolean = false,
    val activityBackfillDays: Int = 0,
    val lastSyncAt: Long? = null,
    val lastError: HealthError? = null,
    /** Latest Health Connect height, offered as a profile update (never applied silently). */
    val heightCm: Double? = null,
)

object HealthSyncStateSerializer : Serializer<HealthSyncState> {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    override val defaultValue = HealthSyncState()

    override suspend fun readFrom(input: InputStream): HealthSyncState {
        val text = input.readBytes().decodeToString()
        if (text.isBlank()) return defaultValue
        return try {
            json.decodeFromString(HealthSyncState.serializer(), text)
        } catch (e: SerializationException) {
            throw CorruptionException("Health sync state is not valid JSON", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Health sync state has invalid values", e)
        }
    }

    override suspend fun writeTo(t: HealthSyncState, output: OutputStream) {
        output.write(json.encodeToString(HealthSyncState.serializer(), t).encodeToByteArray())
    }
}
