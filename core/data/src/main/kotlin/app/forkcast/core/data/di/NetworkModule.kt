package app.forkcast.core.data.di

import android.content.Context
import app.forkcast.core.data.ai.AiEstimateRepository
import app.forkcast.core.data.ai.DefaultAiEstimateRepository
import app.forkcast.core.data.ai.EstimateScheduler
import app.forkcast.core.data.ai.WorkManagerEstimateScheduler
import app.forkcast.core.data.repository.AiRepository
import app.forkcast.core.data.repository.DefaultAiRepository
import app.forkcast.core.data.repository.DefaultFoodRepository
import app.forkcast.core.data.repository.FoodRepository
import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.data.net.ConnectivityNetworkWaiter
import app.forkcast.core.network.HttpClientFactory
import app.forkcast.core.network.NetworkWaiter
import app.forkcast.core.network.ai.AiDefaults
import app.forkcast.core.network.ai.AiHttpClientFactory
import app.forkcast.core.network.ai.MealEstimatorFactory
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
    fun provideHttpClient(@Named(USER_AGENT) userAgent: String, network: NetworkWaiter): HttpClient =
        HttpClientFactory.create(userAgent, network = network)

    /** Lets retries wait out a Wi-Fi ↔ mobile data switch instead of reporting "No connection". */
    @Provides
    @Singleton
    fun provideNetworkWaiter(@ApplicationContext context: Context): NetworkWaiter = ConnectivityNetworkWaiter(context)

    @Provides
    @Singleton
    fun provideSecretStore(@ApplicationContext context: Context): SecretStore =
        SecretStore(SecretStore.secretsFile(context), { SecretStore.androidAead(context) }, Dispatchers.IO)

    @Provides
    fun provideUsdaKeyProvider(store: SecretStore): UsdaKeyProvider = UsdaKeyProvider { store.get(SecretName.USDA_API_KEY) }

    /** Separate client for AI providers: 60 s reads, no retry on 429 (the chain falls through). */
    @Provides
    @Singleton
    @Named(AI_HTTP)
    fun provideAiHttpClient(@Named(USER_AGENT) userAgent: String, network: NetworkWaiter): HttpClient =
        AiHttpClientFactory.create(userAgent, network = network)

    @Provides
    @Singleton
    fun provideAiDefaults(): AiDefaults = AiDefaults.load()

    @Provides
    @Singleton
    fun provideMealEstimatorFactory(@Named(AI_HTTP) http: HttpClient, defaults: AiDefaults, store: SecretStore): MealEstimatorFactory =
        MealEstimatorFactory(http, defaults) { profileId -> store.aiKey(profileId) }
}

private const val AI_HTTP = "aiHttp"

/** Qualifier for the GitHub repository releases come from ("owner/name"), provided by :app. */
const val GITHUB_REPO = "githubRepo"

@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {
    /** Its own client: no redirects followed (the token stays on api.github.com), long reads. */
    @Provides
    @Singleton
    fun provideGitHubReleases(
        @ApplicationContext context: Context,
        @Named(USER_AGENT) userAgent: String,
        @Named(GITHUB_REPO) repo: String,
    ): app.forkcast.core.network.update.GitHubReleases =
        app.forkcast.core.network.update.GitHubReleases(
            app.forkcast.core.network.update.UpdateHttpClientFactory.create(userAgent),
            repo,
            // A local stand-in only in a debuggable build set up for it (ReleasesMock).
            api = app.forkcast.core.data.update.ReleasesMock.baseUrl(context) ?: app.forkcast.core.network.update.GitHubReleases.API,
        )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class FoodModule {
    @Binds
    abstract fun bindFoodRepository(impl: DefaultFoodRepository): FoodRepository

    @Binds
    abstract fun bindAiRepository(impl: DefaultAiRepository): AiRepository

    @Binds
    abstract fun bindAiEstimateRepository(impl: DefaultAiEstimateRepository): AiEstimateRepository

    @Binds
    abstract fun bindEstimateScheduler(impl: WorkManagerEstimateScheduler): EstimateScheduler

    @Binds
    abstract fun bindUpdateRepository(impl: app.forkcast.core.data.update.GitHubUpdateRepository): app.forkcast.core.data.update.UpdateRepository
}
