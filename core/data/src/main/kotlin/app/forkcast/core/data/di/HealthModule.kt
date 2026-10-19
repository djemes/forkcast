package app.forkcast.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import app.forkcast.core.data.health.ClientHealthConnectSource
import app.forkcast.core.data.health.HealthConnectRepository
import app.forkcast.core.data.health.HealthConnectSource
import app.forkcast.core.data.health.HealthRepository
import app.forkcast.core.data.health.HealthSyncState
import app.forkcast.core.data.health.HealthSyncStateSerializer
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object HealthStateModule {
    @Provides
    @Singleton
    @Named(HealthConnectRepository.HEALTH_SYNC_STATE)
    fun provideHealthSyncState(@ApplicationContext context: Context): DataStore<HealthSyncState> =
        DataStoreFactory.create(
            serializer = HealthSyncStateSerializer,
            corruptionHandler = ReplaceFileCorruptionHandler { HealthSyncState() },
            produceFile = { File(context.filesDir, "${HealthConnectRepository.SYNC_DIR}/state.json") },
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class HealthModule {
    @Binds abstract fun bindHealthConnectSource(impl: ClientHealthConnectSource): HealthConnectSource

    @Binds abstract fun bindHealthRepository(impl: HealthConnectRepository): HealthRepository
}
