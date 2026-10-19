package app.forkcast.core.data.health

import app.forkcast.core.domain.MealKind
import app.forkcast.core.model.ActivityDay
import app.forkcast.core.model.Nutrients
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

enum class HcAvailability { AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE }

data class HcWeight(val id: String, val time: Instant, val kg: Double, val origin: String)

data class HcBodyFat(val id: String, val time: Instant, val pct: Double, val origin: String)

/** Weight and body-fat changes since a changes token. */
data class HcBodyChanges(
    val weights: List<HcWeight>,
    val bodyFat: List<HcBodyFat>,
    val deletedIds: List<String>,
    val nextToken: String,
    val hasMore: Boolean,
    /** The token is too old (Health Connect keeps 30 days): read everything again. */
    val expired: Boolean,
)

data class NutritionWrite(
    /** Our log entry id: writes are idempotent by client record id. */
    val clientId: String,
    /** Our updatedAt: Health Connect keeps the highest version. */
    val version: Long,
    val start: Instant,
    val offset: ZoneOffset,
    val name: String,
    val meal: MealKind,
    val nutrients: Nutrients,
)

data class HydrationWrite(val clientId: String, val version: Long, val start: Instant, val offset: ZoneOffset, val ml: Double)

/**
 * The only code that talks to Health Connect (fakeable: SPEC §9 FakeHealthConnect).
 * Every call can throw (SecurityException when a permission is gone, IllegalStateException
 * for quota, RemoteException/IOException); the repository catches and reports.
 */
interface HealthConnectSource {
    fun availability(): HcAvailability
    suspend fun grantedPermissions(): Set<String>
    /** History reads (older than 30 days) and background reads depend on the Health Connect version. */
    fun historyReadSupported(): Boolean
    fun backgroundReadSupported(): Boolean
    suspend fun revokeAll()

    suspend fun readWeights(from: Instant, to: Instant): List<HcWeight>
    suspend fun readBodyFat(from: Instant, to: Instant): List<HcBodyFat>
    suspend fun latestHeightCm(from: Instant): Double?
    /** [includeBodyFat] only when that permission is granted (a token needs read access to every type). */
    suspend fun bodyChangesToken(includeBodyFat: Boolean): String
    suspend fun bodyChanges(token: String): HcBodyChanges

    /** One [ActivityDay] per local date in the range that has any data; asks only for granted types. */
    suspend fun activity(from: LocalDate, toInclusive: LocalDate, zone: ZoneId, granted: Set<String>): List<ActivityDay>

    /** Returns Health Connect record ids, in order. */
    suspend fun writeNutrition(records: List<NutritionWrite>): List<String>
    suspend fun deleteNutrition(clientIds: List<String>)
    suspend fun writeHydration(records: List<HydrationWrite>): List<String>
    suspend fun deleteHydration(clientIds: List<String>)
}
