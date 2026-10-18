package app.forkcast.core.data.repository

import androidx.datastore.core.DataStore
import app.forkcast.core.model.AppSettings
import app.forkcast.core.model.ThemeMode
import app.forkcast.core.model.UnitPrefs
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setUseWallpaperColors(enabled: Boolean)
    suspend fun setUnits(units: UnitPrefs)
}

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<AppSettings>,
) : SettingsRepository {

    override val settings: Flow<AppSettings> = dataStore.data

    override suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.updateData { it.copy(appearance = it.appearance.copy(themeMode = mode)) }
    }

    override suspend fun setUseWallpaperColors(enabled: Boolean) {
        dataStore.updateData { it.copy(appearance = it.appearance.copy(useWallpaperColors = enabled)) }
    }

    override suspend fun setUnits(units: UnitPrefs) {
        dataStore.updateData { it.copy(units = units) }
    }
}
