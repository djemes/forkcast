package app.forkcast.core.model

import kotlinx.serialization.Serializable

/** Non-secret app settings, stored in DataStore as JSON. Secrets never live here. */
@Serializable
data class AppSettings(
    val appearance: AppearanceSettings = AppearanceSettings(),
)

@Serializable
data class AppearanceSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You colors from the wallpaper. Off by default (Forkcast palette). */
    val useWallpaperColors: Boolean = false,
)

@Serializable
enum class ThemeMode { SYSTEM, LIGHT, DARK }
