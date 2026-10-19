package app.forkcast.fakes

import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.repository.WeightRepository
import app.forkcast.core.domain.DailyTargets
import app.forkcast.core.domain.DailyTargetsCalculator
import app.forkcast.core.model.AiSettings
import app.forkcast.core.model.AppSettings
import app.forkcast.core.model.Goal
import app.forkcast.core.model.NotificationSettings
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.ThemeMode
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.UserProfile
import app.forkcast.core.model.WeightEntry
import app.forkcast.core.model.WeightSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate

class FakeSettingsRepository : SettingsRepository {
    val state = MutableStateFlow(AppSettings())
    override val settings: Flow<AppSettings> = state
    override suspend fun setThemeMode(mode: ThemeMode) = state.update { it.copy(appearance = it.appearance.copy(themeMode = mode)) }
    override suspend fun setUseWallpaperColors(enabled: Boolean) = state.update { it.copy(appearance = it.appearance.copy(useWallpaperColors = enabled)) }
    override suspend fun setUnits(units: UnitPrefs) = state.update { it.copy(units = units) }
    override suspend fun setWaterTargetMl(ml: Double?) = state.update { it.copy(waterTargetMl = ml) }
    override suspend fun updateAi(transform: (AiSettings) -> AiSettings) = state.update { it.copy(ai = transform(it.ai)) }
    override suspend fun updateNotifications(transform: (NotificationSettings) -> NotificationSettings) =
        state.update { it.copy(notifications = transform(it.notifications)) }
    override suspend fun setAdaptiveTdee(enabled: Boolean) = state.update { it.copy(adaptiveTdee = enabled) }
    override suspend fun setActivityBonus(enabled: Boolean) = state.update { it.copy(activityBonus = enabled) }
}

class FakeWeightRepository : WeightRepository {
    val list = MutableStateFlow<List<WeightEntry>>(emptyList())
    override val entries: Flow<List<WeightEntry>> = list
    override val latestKg: Flow<Double?> = list.map { it.maxByOrNull { e -> e.timestamp }?.kg }
    override suspend fun add(kg: Double, bodyFatPct: Double?, at: Instant): String {
        val id = "w${list.value.size}"
        list.update { it + WeightEntry(id, at, kg, bodyFatPct, WeightSource.MANUAL) }
        return id
    }
    override suspend fun update(entry: WeightEntry) = list.update { l -> l.map { if (it.id == entry.id) entry else it } }
    override suspend fun delete(id: String) = list.update { l -> l.filterNot { it.id == id } }
}

class FakeProfileRepository(
    private val weights: FakeWeightRepository,
    private val today: () -> LocalDate,
) : ProfileRepository {
    val profileState = MutableStateFlow<UserProfile?>(null)
    val goalState = MutableStateFlow<Goal?>(null)
    val overrideState = MutableStateFlow<Map<NutrientKey, Double>>(emptyMap())
    val goalHistory = mutableListOf<Goal>()

    override val profile: Flow<UserProfile?> = profileState
    override val activeGoal: Flow<Goal?> = goalState
    override val overrides: Flow<Map<NutrientKey, Double>> = overrideState
    override suspend fun saveProfile(profile: UserProfile) { profileState.value = profile }
    override suspend fun saveGoal(goal: Goal) {
        goalHistory += goal
        goalState.value = goal
    }
    override suspend fun setOverride(key: NutrientKey, amount: Double?) =
        overrideState.update { if (amount == null) it - key else it + (key to amount) }

    override fun observeDailyTargets(): Flow<DailyTargets?> =
        combine(profileState, goalState, overrideState, weights.latestKg) { p, g, o, w ->
            if (p == null || g == null) null else DailyTargetsCalculator.compute(p, g, w ?: g.startWeightKg, today(), o)
        }
}
