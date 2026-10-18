package app.forkcast.di

import app.forkcast.BuildConfig
import app.forkcast.core.data.backup.APP_VERSION
import app.forkcast.core.data.di.USER_AGENT
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** Open Food Facts asks apps to identify themselves; no personal email (DECISIONS.md). */
    @Provides
    @Named(USER_AGENT)
    fun provideUserAgent(): String = "Forkcast/${BuildConfig.VERSION_NAME} (https://github.com/djemes/forkcast)"

    @Provides
    @Named(APP_VERSION)
    fun provideAppVersion(): String = BuildConfig.VERSION_NAME
}
