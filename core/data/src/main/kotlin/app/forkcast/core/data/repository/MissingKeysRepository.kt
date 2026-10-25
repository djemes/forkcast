package app.forkcast.core.data.repository

import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.data.settings.DevicePrefs
import app.forkcast.core.model.ProviderId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** A key the app needs but doesn't have (after a restore or a move to a new phone). */
data class MissingKey(
    /** "usda" or "ai:<profileId>". */
    val id: String,
    /** The AI profile's name; null for the food database key. */
    val profileName: String?,
) {
    val isAi: Boolean get() = profileName != null
}

/**
 * Backups never carry API keys (SPEC §4.11), so after Android restores the app onto a new phone
 * (or an import brings AI profiles over) the keys are gone. This lists what is missing: enabled
 * AI profiles that need a key and have none, and the food-database key if one had been saved.
 */
interface MissingKeysRepository {
    val missing: Flow<List<MissingKey>>
    suspend fun dismiss(ids: Collection<String>)
    /** Records that a food-database key was saved (true) or removed on purpose (false). */
    suspend fun noteFoodDatabaseKey(saved: Boolean)
    /** Keeps the "had a food-database key" note in step with the key store (installs that saved one before the note existed). */
    fun start(scope: CoroutineScope)
}

@Singleton
class DefaultMissingKeysRepository @Inject constructor(
    private val ai: AiRepository,
    private val secrets: SecretStore,
    private val settings: SettingsRepository,
    private val device: DevicePrefs,
) : MissingKeysRepository {

    override val missing: Flow<List<MissingKey>> =
        combine(ai.profiles, secrets.present, settings.settings, device.state) { profiles, present, s, d ->
            // With AI turned off, a missing AI key is no reason to speak up (roadmap 2.0 task 3.1).
            val aiMissing = profiles.filter { s.ai.enabled }
                // OpenAI-compatible endpoints (a local Ollama, say) can work without a key.
                .filter { it.profile.enabled && !it.hasKey && it.profile.provider != ProviderId.OPENAI_COMPAT }
                .map { MissingKey("ai:${it.profile.id}", it.profile.name) }
            val usda = MissingKey(USDA, null).takeIf { s.keyNotes.foodDatabaseKeySaved && SecretName.USDA_API_KEY !in present }
            (aiMissing + listOfNotNull(usda)).filter { it.id !in d.dismissedMissingKeys }
        }.onStart { secrets.refresh() }.distinctUntilChanged()

    override suspend fun dismiss(ids: Collection<String>) = device.update { it.copy(dismissedMissingKeys = it.dismissedMissingKeys + ids) }

    override suspend fun noteFoodDatabaseKey(saved: Boolean) {
        settings.update { it.copy(keyNotes = it.keyNotes.copy(foodDatabaseKeySaved = saved)) }
        // Saving a key again clears an old "Not now" for it.
        device.update { it.copy(dismissedMissingKeys = it.dismissedMissingKeys - USDA) }
    }

    override fun start(scope: CoroutineScope) {
        scope.launch {
            secrets.refresh()
            secrets.present.filter { SecretName.USDA_API_KEY in it }.collect {
                if (!settings.settings.first().keyNotes.foodDatabaseKeySaved) noteFoodDatabaseKey(true)
            }
        }
    }

    private companion object {
        const val USDA = "usda"
    }
}
