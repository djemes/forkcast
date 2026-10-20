package app.forkcast.widget

import android.content.Context
import app.forkcast.core.data.repository.LogRepository
import app.forkcast.core.data.repository.MaintenanceRepository
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.repository.WaterRepository
import app.forkcast.core.domain.DayMath
import app.forkcast.core.domain.DayTotals
import app.forkcast.core.domain.MealSlotPicker
import app.forkcast.core.domain.NutritionConstants
import app.forkcast.core.domain.UnitConversions
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.Sex
import app.forkcast.core.model.UnitPrefs
import app.forkcast.core.model.WaterUnit
import app.forkcast.ui.format.UnitFormatter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale

/** What widgets, shortcuts and tiles need from the app graph (they aren't Hilt entry points themselves). */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun log(): LogRepository
    fun water(): WaterRepository
    fun profiles(): ProfileRepository
    fun maintenance(): MaintenanceRepository
    fun settings(): SettingsRepository
    fun slots(): MealSlotRepository
    fun clock(): Clock
    fun health(): app.forkcast.core.data.health.HealthSyncStarter
}

fun Context.widgetGraph(): WidgetEntryPoint = EntryPointAccessors.fromApplication(applicationContext, WidgetEntryPoint::class.java)

/** Today's numbers for the home-screen widgets: the same math as Today (SPEC §4.2, §4.9). */
data class TodaySnapshot(
    /** Null until onboarding is done (no budget yet). */
    val totals: DayTotals?,
    val proteinG: Double,
    val proteinTargetG: Double,
    val carbsG: Double,
    val carbsTargetG: Double,
    val fatG: Double,
    val fatTargetG: Double,
    val waterMl: Double,
    val waterTargetMl: Double,
    val glassMl: Double,
    val units: UnitPrefs,
) {
    val format: UnitFormatter get() = UnitFormatter(units)
}

object WidgetData {

    suspend fun today(context: Context): TodaySnapshot = observeToday(context).first()

    /**
     * Today's snapshot, re-emitted whenever the log, water, targets, settings, profile or activity
     * bonus change, and when the day rolls over. A running widget session collects this, so a
     * "+ glass" tap or a log in the app shows at once.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observeToday(context: Context): Flow<TodaySnapshot> {
        val g = context.widgetGraph()
        val day = flow {
            while (true) {
                emit(LocalDate.now(g.clock()))
                delay(DAY_CHECK_MS)
            }
        }.distinctUntilChanged()
        return day.flatMapLatest { today ->
            combine(
                combine(g.log().observeDay(today), g.water().observeDay(today), ::Pair),
                g.profiles().observeDailyTargets(),
                g.settings().settings,
                g.profiles().profile,
                g.maintenance().observeActivityBonus(today),
            ) { (entries, water), targets, settings, profile, bonus ->
                build(entries, water, targets, settings, profile, bonus.bonusKcal)
            }
        }.distinctUntilChanged()
    }

    private fun build(
        entries: List<app.forkcast.core.model.LogEntry>,
        water: List<app.forkcast.core.model.WaterEntry>,
        targets: app.forkcast.core.domain.DailyTargets?,
        settings: app.forkcast.core.model.AppSettings,
        profile: app.forkcast.core.model.UserProfile?,
        bonusKcal: Double,
    ): TodaySnapshot {
        val units = settings.units ?: UnitFormatter.defaultsFor(Locale.getDefault())
        val totals = targets?.let { DayMath.totals(entries, it.budgetKcal, bonusKcal) }
        fun target(key: NutrientKey) = targets?.targets?.get(key)?.amount ?: 0.0
        return TodaySnapshot(
            totals = totals,
            proteinG = totals?.get(NutrientKey.PROTEIN_G) ?: 0.0,
            proteinTargetG = target(NutrientKey.PROTEIN_G),
            carbsG = totals?.get(NutrientKey.CARBS_G) ?: 0.0,
            carbsTargetG = target(NutrientKey.CARBS_G),
            fatG = totals?.get(NutrientKey.FAT_G) ?: 0.0,
            fatTargetG = target(NutrientKey.FAT_G),
            // Drinks that count toward water add to it, as on Today (SPEC §4.9).
            waterMl = water.sumOf { it.ml } + entries.sumOf { it.waterMl ?: 0.0 },
            waterTargetMl = settings.waterTargetMl
                ?: if (profile?.sex == Sex.FEMALE) NutritionConstants.WATER_TARGET_FEMALE_ML else NutritionConstants.WATER_TARGET_MALE_ML,
            glassMl = settings.waterGlassMl ?: glassMl(units),
            units = units,
        )
    }

    private const val DAY_CHECK_MS = 60_000L

    /** Default "+ glass" when none is set in Settings › Water: 8 fl oz for US units, 250 ml for metric. */
    fun glassMl(units: UnitPrefs): Double = if (units.water == WaterUnit.FL_OZ) UnitConversions.flOzToMl(8.0) else 250.0

    /**
     * Adds one glass to today through the same repository as Today's "+ glass", so Today, Trends
     * and Health Connect see it exactly the same way; returns the new entry's id (for Undo).
     */
    suspend fun addGlass(context: Context): String {
        val g = context.widgetGraph()
        val settings = g.settings().settings.first()
        val units = settings.units ?: UnitFormatter.defaultsFor(Locale.getDefault())
        val id = g.water().add(LocalDate.now(g.clock()), settings.waterGlassMl ?: glassMl(units))
        g.health().syncSoon()
        return id
    }

    /** Undo for a glass added from a widget. */
    suspend fun removeGlass(context: Context, id: String) {
        val g = context.widgetGraph()
        g.water().delete(id)
        g.health().syncSoon()
    }

    /** The meal that fits the time of day (same rule as the Log button on Today). */
    suspend fun mealNow(context: Context): String? {
        val g = context.widgetGraph()
        val now = LocalTime.now(g.clock())
        return MealSlotPicker.defaultFor(g.slots().observeMealSlots().first(), now.hour * 60 + now.minute)?.id
    }
}
