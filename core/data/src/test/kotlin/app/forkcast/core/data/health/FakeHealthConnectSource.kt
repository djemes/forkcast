package app.forkcast.core.data.health

import app.forkcast.core.model.ActivityDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** In-memory Health Connect (SPEC §9 FakeHealthConnect). */
class FakeHealthConnectSource : HealthConnectSource {
    var availability = HcAvailability.AVAILABLE
    var granted: Set<String> = HealthPermissions.ALL
    var history = true
    var background = true

    val weights = mutableListOf<HcWeight>()
    val bodyFat = mutableListOf<HcBodyFat>()
    var heightCm: Double? = null
    val activityDays = mutableMapOf<LocalDate, ActivityDay>()

    /** Changes since the last token: the test adds them. */
    val pendingWeightChanges = mutableListOf<HcWeight>()
    val pendingDeletions = mutableListOf<String>()
    var tokenExpired = false
    var tokensIssued = 0
    var fullReads = 0
    var activityRequests = mutableListOf<Pair<LocalDate, LocalDate>>()

    /** client id → (version, record) as Health Connect would keep it. */
    val nutrition = mutableMapOf<String, NutritionWrite>()
    val hydration = mutableMapOf<String, HydrationWrite>()
    val deleteCalls = mutableListOf<List<String>>()
    var revoked = false

    override fun availability() = availability
    override suspend fun grantedPermissions() = granted
    override fun historyReadSupported() = history
    override fun backgroundReadSupported() = background
    override suspend fun revokeAll() { revoked = true; granted = emptySet() }

    override suspend fun readWeights(from: Instant, to: Instant): List<HcWeight> {
        fullReads++
        return weights.filter { !it.time.isBefore(from) && it.time.isBefore(to) }
    }

    override suspend fun readBodyFat(from: Instant, to: Instant) = bodyFat.filter { !it.time.isBefore(from) && it.time.isBefore(to) }
    override suspend fun latestHeightCm(from: Instant) = heightCm

    override suspend fun bodyChangesToken(includeBodyFat: Boolean): String {
        tokensIssued++
        pendingWeightChanges.clear()
        pendingDeletions.clear()
        tokenExpired = false
        return "token-$tokensIssued"
    }

    override suspend fun bodyChanges(token: String): HcBodyChanges {
        if (tokenExpired) return HcBodyChanges(emptyList(), emptyList(), emptyList(), token, hasMore = false, expired = true)
        val out = HcBodyChanges(pendingWeightChanges.toList(), emptyList(), pendingDeletions.toList(), "$token+", hasMore = false, expired = false)
        pendingWeightChanges.clear()
        pendingDeletions.clear()
        return out
    }

    override suspend fun activity(from: LocalDate, toInclusive: LocalDate, zone: ZoneId, granted: Set<String>): List<ActivityDay> {
        activityRequests += from to toInclusive
        return activityDays.values.filter { !it.date.isBefore(from) && !it.date.isAfter(toInclusive) }
    }

    override suspend fun writeNutrition(records: List<NutritionWrite>): List<String> = records.map { r ->
        val kept = nutrition[r.clientId]
        if (kept == null || r.version > kept.version) nutrition[r.clientId] = r
        "hc-${r.clientId}"
    }

    override suspend fun deleteNutrition(clientIds: List<String>) {
        deleteCalls += clientIds
        clientIds.forEach { nutrition.remove(it) }
    }

    override suspend fun writeHydration(records: List<HydrationWrite>): List<String> = records.map { r ->
        hydration[r.clientId] = r
        "hc-${r.clientId}"
    }

    override suspend fun deleteHydration(clientIds: List<String>) {
        deleteCalls += clientIds
        clientIds.forEach { hydration.remove(it) }
    }
}
