package app.forkcast.core.data.di

import android.content.Context
import app.forkcast.core.data.repository.DefaultFoodRepository
import app.forkcast.core.data.repository.FoodRepository
import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.network.HttpClientFactory
import app.forkcast.core.network.UsdaKeyProvider
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import javax.inject.Named
import javax.inject.Singleton

/** Qualifier for the app's User-Agent ("Forkcast/<version> (<repo url>)"), provided by :app. */
const val USER_AGENT = "userAgent"

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideHttpClient(@Named(USER_AGENT) userAgent: String): HttpClient = HttpClientFactory.create(userAgent)

    @Provides
    @Singleton
    fun provideSecretStore(@ApplicationContext context: Context): SecretStore =
        SecretStore(SecretStore.secretsFile(context), { SecretStore.androidAead(context) }, Dispatchers.IO)

    @Provides
    fun provideUsdaKeyProvider(store: SecretStore): UsdaKeyProvider = UsdaKeyProvider { store.get(SecretName.USDA_API_KEY) }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FoodModule {
    @Binds
    abstract fun bindFoodRepository(impl: DefaultFoodRepository): FoodRepository
}
