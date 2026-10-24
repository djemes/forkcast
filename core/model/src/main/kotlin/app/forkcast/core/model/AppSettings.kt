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
    /** The glass (Today, the water widget, the shortcut) in ml; null = 8 fl oz or 250 ml by units. */
    val waterGlassMl: Double? = null,
    /** Can, bottle and tumbler sizes and the container the water drop adds (owner, 2026-10-23). */
    val waterContainers: WaterContainers = WaterContainers(),
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
    /** What the user had saved as keys, so a restored phone (keys are never backed up) can ask for them (SPEC §4.11). */
    val keyNotes: KeyNotes = KeyNotes(),
    /** Google Drive auto-backup choices (SPEC §4.11). The connection itself is per phone (DevicePrefs). */
    val backup: BackupSettings = BackupSettings(),
)

/** What the water drop on Today (and the widget) adds. */
@Serializable
enum class WaterContainer { GLASS, CAN, BOTTLE, TUMBLER }

/**
 * Container sizes in ml (null = the default for the user's units; the glass is
 * [AppSettings.waterGlassMl]) and the last one used, which the drop adds in one tap.
 */
@Serializable
data class WaterContainers(
    val canMl: Double? = null,
    val bottleMl: Double? = null,
    val tumblerMl: Double? = null,
    val lastUsed: WaterContainer = WaterContainer.GLASS,
)

/**
 * Any network, charging or not, data only (no photos). Since 1.2 a new install starts weekly on
 * Mondays around 3 am (owner, 2026-10-23); settings are stored with every field, so phones that
 * already chose keep their choice.
 */
@Serializable
data class BackupSettings(
    /** Back up automatically once Drive is connected on this phone. */
    val auto: Boolean = true,
    val frequency: BackupFrequency = BackupFrequency.WEEKLY,
    /** Day of a weekly backup, ISO (1 = Monday). */
    val weekday: Int = 1,
    val wifiOnly: Boolean = false,
    val chargingOnly: Boolean = false,
)

@Serializable
enum class BackupFrequency(val days: Int) { DAILY(1), WEEKLY(7) }

/** Never the keys themselves: only that a food-database key was saved (dismissed prompts are per phone, in DevicePrefs). */
@Serializable
data class KeyNotes(
    val foodDatabaseKeySaved: Boolean = false,
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
    /** Weekly check-in (owner, 2026-10-19): a look back at the last 7 days, on [weeklyDay] (ISO 1 = Monday). */
    val weeklyEnabled: Boolean = true,
    val weeklyDay: Int = 1,
    val weeklyMinute: Int = 9 * 60,
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
    /** The last connection test per profile id, so "does it work?" survives a restart. No secrets. */
    val lastChecks: Map<String, ProfileCheck> = emptyMap(),
)

/** Outcome of a profile's last connection test. */
@Serializable
data class ProfileCheck(
    val ok: Boolean,
    val atMillis: Long,
    val latencyMs: Long? = null,
    /** Why it failed, coarse (no provider text: it can echo request details). */
    val failure: CheckFailure? = null,
)

@Serializable
enum class CheckFailure { KEY, QUOTA, MODEL, NETWORK, OTHER }

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
