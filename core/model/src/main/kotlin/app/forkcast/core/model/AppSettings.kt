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
    /** Delete meal photos older than this many days; null keeps them forever. */
    val photoRetentionDays: Int? = 90,
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
