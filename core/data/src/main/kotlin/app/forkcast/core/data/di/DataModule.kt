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
import app.forkcast.core.data.db.MealSlotDao
import app.forkcast.core.data.repository.DataStoreSettingsRepository
import app.forkcast.core.data.repository.MealSlotRepository
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
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

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

    @Provides
    fun provideMealSlotDao(db: ForkcastDatabase): MealSlotDao = db.mealSlotDao()

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
}
