package app.forkcast.core.data.repository

import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.network.UsdaClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

/** The optional USDA FoodData Central key (Settings › Food databases): saved encrypted, never logged. */
interface FoodDatabaseKeyRepository {
    /** The saved key, for the masked field with show/hide and copy; null when none is saved. */
    val savedKey: Flow<String?>
    /** Checks [key] with FoodData Central; fails with a FoodApiError. Nothing is saved. */
    suspend fun test(key: String): Result<Unit>
    suspend fun save(key: String)
    /** Removed on purpose: don't ask for it after a restore. */
    suspend fun remove()
}

@Singleton
class DefaultFoodDatabaseKeyRepository @Inject constructor(
    private val secrets: SecretStore,
    private val usda: UsdaClient,
    private val missingKeys: MissingKeysRepository,
) : FoodDatabaseKeyRepository {

    override val savedKey: Flow<String?> = secrets.present
        .map { names -> if (SecretName.USDA_API_KEY in names) secrets.get(SecretName.USDA_API_KEY) else null }
        .onStart { secrets.refresh() }

    override suspend fun test(key: String): Result<Unit> = runCatching { usda.testKey(key) }

    override suspend fun save(key: String) {
        secrets.set(SecretName.USDA_API_KEY, key)
        missingKeys.noteFoodDatabaseKey(true)
    }

    override suspend fun remove() {
        secrets.set(SecretName.USDA_API_KEY, null)
        missingKeys.noteFoodDatabaseKey(false)
    }
}
