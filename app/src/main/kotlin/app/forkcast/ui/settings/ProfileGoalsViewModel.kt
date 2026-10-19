package app.forkcast.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.repository.MaintenanceEstimate
import app.forkcast.core.data.repository.MaintenanceProgress
import app.forkcast.core.data.repository.MaintenanceRepository
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.repository.WeightRepository
import app.forkcast.core.domain.ActivityBonus
import app.forkcast.core.domain.ActivityLevelAdvice
import app.forkcast.core.domain.DailyTargets
import app.forkcast.core.model.ActivityLevel
import app.forkcast.core.model.Goal
import app.forkcast.core.model.GoalType
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.UserProfile
import app.forkcast.ui.format.UnitFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.util.Locale
import javax.inject.Inject

data class ProfileGoalsState(
    val units: UnitPrefs,
    val profile: UserProfile,
    val goal: Goal,
    val targets: DailyTargets,
    val currentKg: Double,
    val today: LocalDate,
    val maintenance: MaintenanceEstimate? = null,
    val progress: MaintenanceProgress = MaintenanceProgress(0, 0),
    /** Settings: follow the adaptive estimate (false = formula). */
    val adaptiveOn: Boolean = true,
    /** Settings: activity above usual adds budget (SPEC §5.2). */
    val activityBonusOn: Boolean = true,
    /** Today's activity bonus, for its usual (the 28 days before today). */
    val activity: ActivityBonus = ActivityBonus.NONE,
) {
    /** The activity level synced activity points to, once there are enough days (SPEC §5.2). */
    val suggestedLevel: ActivityLevel?
        get() = activity.usualKcal?.takeIf { activity.baselineDays >= ActivityLevelAdvice.MIN_DAYS }
            ?.let { ActivityLevelAdvice.suggest(targets.bmrKcal, it) }
}

@HiltViewModel
class ProfileGoalsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val weights: WeightRepository,
    private val settings: SettingsRepository,
    maintenance: MaintenanceRepository,
    private val clock: Clock,
) : ViewModel() {

    val state: StateFlow<ProfileGoalsState?> = combine(
        settings.settings, profiles.profile, profiles.activeGoal, profiles.observeDailyTargets(),
        combine(maintenance.latest, maintenance.observeProgress(), maintenance.observeActivityBonus(LocalDate.now(clock))) { m, pr, a -> Triple(m, pr, a) },
    ) { s, p, g, t, (m, pr, a) ->
        if (p == null || g == null || t == null) null
        else ProfileGoalsState(
            units = s.units ?: UnitFormatter.defaultsFor(Locale.getDefault()),
            profile = p,
            goal = g,
            targets = t,
            currentKg = t.currentKg,
            today = LocalDate.now(clock),
            maintenance = m,
            progress = pr,
            adaptiveOn = s.adaptiveTdee,
            activityBonusOn = s.activityBonus,
            activity = a,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setUnits(units: UnitPrefs) = launch { settings.setUnits(units) }

    fun updateProfile(change: (UserProfile) -> UserProfile) = launch {
        profiles.profile.first()?.let { profiles.saveProfile(change(it)) }
    }

    /** A goal edit starts a new goal from today's weight; the old one stays as history. */
    fun updateGoal(type: GoalType, targetKg: Double, weeklyRateKg: Double?, targetDate: LocalDate?) = launch {
        val current = weights.latestKg.first() ?: profiles.activeGoal.first()?.startWeightKg ?: return@launch
        profiles.saveGoal(
            Goal(
                id = "",
                goalType = type,
                startDate = LocalDate.now(clock),
                startWeightKg = current,
                targetWeightKg = if (type == GoalType.MAINTAIN) current else targetKg,
                targetDate = if (type == GoalType.MAINTAIN) null else targetDate,
                weeklyRateKg = if (type == GoalType.MAINTAIN) null else weeklyRateKg,
            ),
        )
    }

    fun setAdaptive(on: Boolean) = launch { settings.setAdaptiveTdee(on) }

    fun setActivityBonus(on: Boolean) = launch { settings.setActivityBonus(on) }

    fun setCustomBudget(kcal: Double?) = launch { profiles.setOverride(NutrientKey.ENERGY_KCAL, kcal) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
