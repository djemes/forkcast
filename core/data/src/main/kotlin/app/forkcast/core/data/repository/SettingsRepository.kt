package app.forkcast.core.data.repository

import androidx.datastore.core.DataStore
import app.forkcast.core.model.AiSettings
import app.forkcast.core.model.AppSettings
import app.forkcast.core.model.NotificationSettings
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
    suspend fun setWaterTargetMl(ml: Double?)
    suspend fun updateAi(transform: (AiSettings) -> AiSettings)
    suspend fun updateNotifications(transform: (NotificationSettings) -> NotificationSettings)
    suspend fun setAdaptiveTdee(enabled: Boolean)
    suspend fun setActivityBonus(enabled: Boolean)
    suspend fun update(transform: (AppSettings) -> AppSettings)
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

    override suspend fun setWaterTargetMl(ml: Double?) {
        dataStore.updateData { it.copy(waterTargetMl = ml) }
    }

    override suspend fun updateAi(transform: (AiSettings) -> AiSettings) {
        dataStore.updateData { it.copy(ai = transform(it.ai)) }
    }

    override suspend fun updateNotifications(transform: (NotificationSettings) -> NotificationSettings) {
        dataStore.updateData { it.copy(notifications = transform(it.notifications)) }
    }

    override suspend fun setAdaptiveTdee(enabled: Boolean) {
        dataStore.updateData { it.copy(adaptiveTdee = enabled) }
    }

    override suspend fun setActivityBonus(enabled: Boolean) {
        dataStore.updateData { it.copy(activityBonus = enabled) }
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.updateData(transform)
    }
}
