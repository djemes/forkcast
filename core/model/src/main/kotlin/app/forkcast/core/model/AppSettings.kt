package app.forkcast.core.model

import kotlinx.serialization.Serializable

/** Non-secret app settings, stored in DataStore as JSON. Secrets never live here. */
@Serializable
data class AppSettings(
    val appearance: AppearanceSettings = AppearanceSettings(),
    /** Null until chosen in onboarding (the default comes from the device locale). */
    val units: UnitPrefs? = null,
    /** Daily water goal in ml; null = default by sex (SPEC §4.9). */
    val waterTargetMl: Double? = null,
    val ai: AiSettings = AiSettings(),
    val notifications: NotificationSettings = NotificationSettings(),
    val health: HealthSettings = HealthSettings(),
    /**
     * Use the adaptive maintenance estimate once there is enough data (SPEC §5.6).
     * False locks maintenance to the formula value.
     */
    val adaptiveTdee: Boolean = true,
    /**
     * Activity above a usual day adds part of it to that day's budget (SPEC §5.2). Off shows
     * activity only. Replaces the Phase 3 Included/Added-back profile modes (owner, 2026-10-19).
     */
    val activityBonus: Boolean = true,
)

/**
 * Settings › Health Connect (SPEC §4.9). What Android actually granted is asked from Health
 * Connect each time; these are the user's choices inside Forkcast.
 */
@Serializable
data class HealthSettings(
    /** The user connected Forkcast to Health Connect (and hasn't disconnected). */
    val connected: Boolean = false,
    val readWeight: Boolean = true,
    val readActivity: Boolean = true,
    val writeNutrition: Boolean = true,
    val writeHydration: Boolean = true,
)

/** Settings › Notifications (SPEC §4.8). Times are minutes after local midnight. */
@Serializable
data class NotificationSettings(
    /** POST_NOTIFICATIONS was requested once, in context; never ask on launch. */
    val permissionAsked: Boolean = false,
    val budgetEnabled: Boolean = true,
    /** Percent of the day's budget; each fires at most once per day. */
    val budgetThresholds: List<Int> = listOf(80, 100),
    val eveningEnabled: Boolean = false,
    val eveningMinute: Int = 16 * 60 + 30,
    val proteinEnabled: Boolean = false,
    val proteinMinute: Int = 17 * 60,
    val mealRemindersEnabled: Boolean = true,
    /** Meal slot id → reminder time. Empty = defaults (Lunch 13:30, Dinner 20:00, matched by name). */
    val mealReminderMinutes: Map<String, Int> = emptyMap(),
    /** Times of meals switched off, so switching them back on restores the time (and the category can restore them). */
    val mealReminderOffMinutes: Map<String, Int> = emptyMap(),
    val weighInEnabled: Boolean = true,
    val weighInMinute: Int = 7 * 60 + 30,
    val estimateReadyEnabled: Boolean = true,
    val backupFailedEnabled: Boolean = true,
    val quietStartMinute: Int = 22 * 60,
    val quietEndMinute: Int = 7 * 60,
)

/** Settings › AI (SPEC §4.7). Profiles live in the database; keys in the encrypted store. */
@Serializable
data class AiSettings(
    /** "Off" turns photo estimation off entirely. */
    val enabled: Boolean = true,
    /** Sent with every photo. Never body data (the screen says so). */
    val personalContext: String = "",
    /** Log estimates immediately with an "AI · review" badge (SPEC §4.6 step 7). */
    val autoAccept: Boolean = true,
    /** Delete meal photos older than this many days (only the photo; entries stay); null keeps them. */
    val photoRetentionDays: Int? = 14,
    /** The 14-day default (owner, 2026-10-19) was applied once to settings saved with the old 90-day default. */
    val photoRetention14Applied: Boolean = false,
    /** User-edited prices per 1M tokens, keyed by model id; overrides the defaults file. */
    val priceOverrides: Map<String, ModelPrice> = emptyMap(),
)

@Serializable
data class ModelPrice(val inputPerM: Double, val outputPerM: Double)

@Serializable
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You colors from the wallpaper. Off by default (Forkcast palette). */
    val useWallpaperColors: Boolean = false,
)

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }
