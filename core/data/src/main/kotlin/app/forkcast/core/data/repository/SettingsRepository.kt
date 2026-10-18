package app.forkcast.core.data.repository

import androidx.datastore.core.DataStore
import app.forkcast.core.model.AppSettings
import app.forkcast.core.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setUseWallpaperColors(enabled: Boolean)
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
}
