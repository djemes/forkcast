package app.forkcast.core.data.repository

import app.forkcast.core.data.db.GoalDao
import app.forkcast.core.data.db.GoalEntity
import app.forkcast.core.data.db.ProfileDao
import app.forkcast.core.data.db.ProfileEntity
import app.forkcast.core.data.db.TargetOverrideDao
import app.forkcast.core.data.db.TargetOverrideEntity
import app.forkcast.core.domain.DailyTargets
import app.forkcast.core.domain.DailyTargetsCalculator
import app.forkcast.core.model.Goal
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

interface ProfileRepository {
    val profile: Flow<UserProfile?>
    val activeGoal: Flow<Goal?>
    /** Latest override per nutrient (ENERGY_KCAL = custom budget). */
    val overrides: Flow<Map<NutrientKey, Double>>
    suspend fun saveProfile(profile: UserProfile)
    /** Stores [goal] as a new active goal row; earlier goals stay as history. */
    suspend fun saveGoal(goal: Goal)
    /** Sets or (with null) clears an override. */
    suspend fun setOverride(key: NutrientKey, amount: Double?)
    /** Today's budget and targets, or null until onboarding is done. */
    fun observeDailyTargets(): Flow<DailyTargets?>
}

@Singleton
class RoomProfileRepository @Inject constructor(
    private val profileDao: ProfileDao,
    private val goalDao: GoalDao,
    private val overrideDao: TargetOverrideDao,
    private val weightRepository: WeightRepository,
    private val maintenance: MaintenanceRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : ProfileRepository {

    override val profile: Flow<UserProfile?> = profileDao.observe().map { it?.toModel() }.distinctUntilChanged()

    override val activeGoal: Flow<Goal?> = goalDao.observeActive().map { it?.toModel() }.distinctUntilChanged()

    override val overrides: Flow<Map<NutrientKey, Double>> = overrideDao.observeAll().map { rows ->
        val today = LocalDate.now(clock)
        rows.filter { !it.effectiveFrom.isAfter(today) }
            .groupBy { it.nutrientKey }
            .mapNotNull { (key, list) ->
                val k = runCatching { NutrientKey.valueOf(key) }.getOrNull() ?: return@mapNotNull null
                k to list.maxWith(compareBy({ it.effectiveFrom }, { it.updatedAt })).amount
            }.toMap()
    }.distinctUntilChanged()

    override suspend fun saveProfile(profile: UserProfile) {
        val now = clock.millis()
        val existing = profileDao.get()
        profileDao.upsert(
            ProfileEntity(
                sex = profile.sex,
                birthDate = profile.birthDate,
                heightCm = profile.heightCm,
                activityLevel = profile.activityLevel,
                exerciseMode = profile.exerciseMode,
                exerciseAddBackPct = profile.exerciseAddBackPct,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    override suspend fun saveGoal(goal: Goal) {
        val now = clock.millis()
        goalDao.setActive(
            GoalEntity(
                id = UUID.randomUUID().toString(),
                goalType = goal.goalType,
                startDate = goal.startDate,
                startWeightKg = goal.startWeightKg,
                targetWeightKg = goal.targetWeightKg,
                targetDate = goal.targetDate,
                weeklyRateKg = goal.weeklyRateKg,
                isActive = true,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    override suspend fun setOverride(key: NutrientKey, amount: Double?) {
        val now = clock.millis()
        overrideDao.clear(key.name, now)
        if (amount != null) {
            overrideDao.upsert(
                TargetOverrideEntity(UUID.randomUUID().toString(), key.name, amount, LocalDate.now(clock), now, now),
            )
        }
    }

    override fun observeDailyTargets(): Flow<DailyTargets?> {
        val today = LocalDate.now(clock)
        // Adaptive maintenance (SPEC §5.6) unless the user locked it to the formula.
        val adaptive = combine(settings.settings.map { it.adaptiveTdee }.distinctUntilChanged(), maintenance.latest) { on, latest ->
            if (on) latest?.kcal else null
        }
        return combine(
            combine(profile, activeGoal, overrides, weightRepository.latestKg) { p, g, o, kg -> Inputs(p, g, o, kg) },
            adaptive,
            maintenance.observeAverageBonus(today),
        ) { i, adaptiveKcal, avgBonus ->
            if (i.profile == null || i.goal == null) null
            else DailyTargetsCalculator.compute(i.profile, i.goal, i.latestKg ?: i.goal.startWeightKg, LocalDate.now(clock), i.overrides, adaptiveKcal, avgBonus)
        }
    }

    private data class Inputs(val profile: UserProfile?, val goal: Goal?, val overrides: Map<NutrientKey, Double>, val latestKg: Double?)
}

internal fun ProfileEntity.toModel() = UserProfile(sex, birthDate, heightCm, activityLevel, exerciseMode, exerciseAddBackPct)

internal fun GoalEntity.toModel() =
    Goal(id, goalType, startDate, startWeightKg, targetWeightKg, targetDate, weeklyRateKg)
