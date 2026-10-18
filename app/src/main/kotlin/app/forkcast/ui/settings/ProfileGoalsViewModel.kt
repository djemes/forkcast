package app.forkcast.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.repository.WeightRepository
import app.forkcast.core.domain.DailyTargets
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
)

@HiltViewModel
class ProfileGoalsViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val weights: WeightRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ViewModel() {

    val state: StateFlow<ProfileGoalsState?> = combine(
        settings.settings, profiles.profile, profiles.activeGoal, profiles.observeDailyTargets(),
    ) { s, p, g, t ->
        if (p == null || g == null || t == null) null
        else ProfileGoalsState(
            units = s.units ?: UnitFormatter.defaultsFor(Locale.getDefault()),
            profile = p,
            goal = g,
            targets = t,
            currentKg = t.currentKg,
            today = LocalDate.now(clock),
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

    fun setCustomBudget(kcal: Double?) = launch { profiles.setOverride(NutrientKey.ENERGY_KCAL, kcal) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
