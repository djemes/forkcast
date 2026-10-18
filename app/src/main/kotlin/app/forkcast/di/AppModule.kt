package app.forkcast.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.time.Clock

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** Injected so tests can pin "now". Uses the device zone at the time of each call. */
    @Provides
    fun provideClock(): Clock = Clock.systemDefaultZone()
}
