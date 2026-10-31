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
    /** The glass of Today's water drop (and the shortcut) in ml; null = 8 fl oz or 250 ml by units. */
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
    /** Automatic backup choices (SPEC §4.11). The folder itself is per phone (DevicePrefs). */
    val backup: BackupSettings = BackupSettings(),
    /** Settings › Updates (roadmap 2.0, task 1.5; GitHub build only). */
    val updates: UpdateSettings = UpdateSettings(),
)

/** How Forkcast looks for new versions on GitHub. */
@Serializable
data class UpdateSettings(
    /** Look at most once a day in the background and notify when there's a new version. */
    val auto: Boolean = true,
    /** Offer pre-releases (betas) too. */
    val beta: Boolean = false,
)

/** What the water drop on Today (and the "+1 glass" shortcut) adds. */
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
 * Backups (owner, 2026-10-29): automatic backup to a folder the user picks, on chosen days around
 * a time, kept by a retention preset. Every backup is encrypted with the backup password (a
 * secret, so not here) and holds everything, photos included. The folder itself is per phone
 * (DevicePrefs).
 */
@Serializable
data class BackupSettings(
    /** Back up automatically to the chosen folder; runs only while a backup password is set. */
    val auto: Boolean = true,
    /** Days of the week, ISO (1 = Monday). Never empty: no day turns [auto] off instead. */
    val days: Set<Int> = setOf(1),
    /** Around this time, minutes after midnight. */
    val minute: Int = DEFAULT_MINUTE,
    val retention: RetentionPreset = RetentionPreset.BALANCED,
    /** [RetentionPreset.CUSTOM]: newest backups kept, and one a month / a year for how many. */
    val customRecent: Int = 7,
    val customMonths: Int = 6,
    val customYears: Int = 0,
    /** Today reminds when there's been no backup for [REMINDER_DAYS] days. */
    val reminder: Boolean = true,
) {
    companion object {
        /** 3 am. */
        const val DEFAULT_MINUTE = 3 * 60
        const val REMINDER_DAYS = 14
    }
}

/** How many old backups the folder keeps (owner, 2026-10-29); the rules are in BackupRetention. */
@Serializable
enum class RetentionPreset { SIMPLE, BALANCED, LONG_TERM, CUSTOM }

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
