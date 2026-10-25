package app.forkcast.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import app.forkcast.core.data.R
import app.forkcast.core.data.backup.BackupRepository
import app.forkcast.core.data.backup.RoomBackupRepository
import app.forkcast.core.data.db.AiDao
import app.forkcast.core.data.db.DefaultMealSlots
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.db.DayStatusDao
import app.forkcast.core.data.db.FoodDao
import app.forkcast.core.data.db.GoalDao
import app.forkcast.core.data.db.HealthDao
import app.forkcast.core.data.db.LogEntryDao
import app.forkcast.core.data.db.MealSlotDao
import app.forkcast.core.data.db.ProfileDao
import app.forkcast.core.data.db.SavedMealDao
import app.forkcast.core.data.db.TargetOverrideDao
import app.forkcast.core.data.db.WaterDao
import app.forkcast.core.data.db.WeightDao
import app.forkcast.core.data.repository.DataStoreSettingsRepository
import app.forkcast.core.data.repository.LogRepository
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.data.repository.RoomLogRepository
import app.forkcast.core.data.repository.RoomSavedMealRepository
import app.forkcast.core.data.repository.RoomWaterRepository
import app.forkcast.core.data.repository.WaterRepository
import app.forkcast.core.data.repository.SavedMealRepository
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.RoomProfileRepository
import app.forkcast.core.data.repository.RoomWeightRepository
import app.forkcast.core.data.repository.WeightRepository
import app.forkcast.core.data.repository.RoomMealSlotRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.settings.AppSettingsSerializer
import app.forkcast.core.model.AppSettings
import app.forkcast.core.data.repository.DefaultFoodDatabaseKeyRepository
import app.forkcast.core.data.repository.FoodDatabaseKeyRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    /** Injected so tests can pin "now". Uses the device zone at the time of each call. */
    @Provides
    fun provideClock(): Clock = SystemZoneClock
}

/**
 * The system clock in the phone's zone as it is now. `Clock.systemDefaultZone()` keeps the zone
 * it was made with, so singletons kept using the old zone after a trip until the app was killed
 * (Android updates the default zone in a running process).
 */
object SystemZoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = system(zone)
    override fun instant(): Instant = Instant.now()
    override fun millis(): Long = System.currentTimeMillis()
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): ForkcastDatabase {
        val names = DefaultMealSlots.Names(
            breakfast = context.getString(R.string.meal_breakfast),
            lunch = context.getString(R.string.meal_lunch),
            dinner = context.getString(R.string.meal_dinner),
            snacks = context.getString(R.string.meal_snacks),
        )
        return Room.databaseBuilder(context, ForkcastDatabase::class.java, ForkcastDatabase.NAME)
            .addCallback(object : RoomDatabase.Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    DefaultMealSlots.insertInto(db, names)
                }
            })
            .build()
    }

    @Provides fun provideMealSlotDao(db: ForkcastDatabase): MealSlotDao = db.mealSlotDao()
    @Provides fun provideProfileDao(db: ForkcastDatabase): ProfileDao = db.profileDao()
    @Provides fun provideGoalDao(db: ForkcastDatabase): GoalDao = db.goalDao()
    @Provides fun provideTargetOverrideDao(db: ForkcastDatabase): TargetOverrideDao = db.targetOverrideDao()
    @Provides fun provideFoodDao(db: ForkcastDatabase): FoodDao = db.foodDao()
    @Provides fun provideLogEntryDao(db: ForkcastDatabase): LogEntryDao = db.logEntryDao()
    @Provides fun provideSavedMealDao(db: ForkcastDatabase): SavedMealDao = db.savedMealDao()
    @Provides fun provideWeightDao(db: ForkcastDatabase): WeightDao = db.weightDao()
    @Provides fun provideWaterDao(db: ForkcastDatabase): WaterDao = db.waterDao()
    @Provides fun provideDayStatusDao(db: ForkcastDatabase): DayStatusDao = db.dayStatusDao()
    @Provides fun provideAiDao(db: ForkcastDatabase): AiDao = db.aiDao()
    @Provides fun provideHealthDao(db: ForkcastDatabase): HealthDao = db.healthDao()
    @Provides fun provideRecipeDao(db: ForkcastDatabase): app.forkcast.core.data.db.RecipeDao = db.recipeDao()

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<AppSettings> =
        DataStoreFactory.create(
            serializer = AppSettingsSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
            produceFile = { context.dataStoreFile("settings.json") },
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindMealSlotRepository(impl: RoomMealSlotRepository): MealSlotRepository

    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    abstract fun bindProfileRepository(impl: RoomProfileRepository): ProfileRepository

    @Binds
    abstract fun bindWeightRepository(impl: RoomWeightRepository): WeightRepository

    @Binds
    abstract fun bindLogRepository(impl: RoomLogRepository): LogRepository

    @Binds
    abstract fun bindSavedMealRepository(impl: RoomSavedMealRepository): SavedMealRepository

    @Binds
    abstract fun bindWaterRepository(impl: RoomWaterRepository): WaterRepository

    @Binds
    abstract fun bindBackupRepository(impl: RoomBackupRepository): BackupRepository

    @Binds
    abstract fun bindWeeklyCheckInRepository(impl: app.forkcast.core.data.repository.RoomWeeklyCheckInRepository): app.forkcast.core.data.repository.WeeklyCheckInRepository

    @Binds
    abstract fun bindFolderBackupRepository(impl: app.forkcast.core.data.backup.folder.DefaultFolderBackupRepository): app.forkcast.core.data.backup.folder.FolderBackupRepository

    @Binds
    abstract fun bindBackupFolder(impl: app.forkcast.core.data.backup.folder.SafBackupFolder): app.forkcast.core.data.backup.folder.BackupFolder

    @Binds
    abstract fun bindMissingKeysRepository(impl: app.forkcast.core.data.repository.DefaultMissingKeysRepository): app.forkcast.core.data.repository.MissingKeysRepository

    @Binds
    abstract fun bindConsentRepository(impl: app.forkcast.core.data.repository.DefaultConsentRepository): app.forkcast.core.data.repository.ConsentRepository

    @Binds
    abstract fun bindRecipeRepository(impl: app.forkcast.core.data.repository.RoomRecipeRepository): app.forkcast.core.data.repository.RecipeRepository

    @Binds
    abstract fun bindTrendsRepository(impl: app.forkcast.core.data.repository.RoomTrendsRepository): app.forkcast.core.data.repository.TrendsRepository

    @Binds
    abstract fun bindFoodDatabaseKeyRepository(impl: DefaultFoodDatabaseKeyRepository): FoodDatabaseKeyRepository

    @Binds
    abstract fun bindMaintenanceRepository(impl: app.forkcast.core.data.repository.RoomMaintenanceRepository): app.forkcast.core.data.repository.MaintenanceRepository
}
