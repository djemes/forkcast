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
import app.forkcast.core.data.db.DefaultMealSlots
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.db.DayStatusDao
import app.forkcast.core.data.db.FoodDao
import app.forkcast.core.data.db.GoalDao
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
import app.forkcast.core.data.repository.ProfileRepository
import app.forkcast.core.data.repository.RoomProfileRepository
import app.forkcast.core.data.repository.RoomWeightRepository
import app.forkcast.core.data.repository.WeightRepository
import app.forkcast.core.data.repository.RoomMealSlotRepository
import app.forkcast.core.data.repository.SettingsRepository
import app.forkcast.core.data.settings.AppSettingsSerializer
import app.forkcast.core.model.AppSettings
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    /** Injected so tests can pin "now". Uses the device zone at the time of each call. */
    @Provides
    fun provideClock(): Clock = Clock.systemDefaultZone()

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
}
