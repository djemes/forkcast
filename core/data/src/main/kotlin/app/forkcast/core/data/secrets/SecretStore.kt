package app.forkcast.core.data.secrets

import android.content.Context
import com.google.crypto.tink.Aead
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/** Names of stored secrets. Values are never logged, exported, or backed up. */
enum class SecretName { USDA_API_KEY }

/**
 * API keys at rest (CLAUDE.md "Secrets"): one file, `files/secrets/store.bin`, encrypted with
 * Tink AES-256-GCM whose keyset is itself wrapped by an Android Keystore master key.
 * Both the file and the keyset prefs (`secrets.xml`) are excluded from Android backup, so a
 * restored phone starts with no keys and asks for them again.
 */
class SecretStore(
    private val file: File,
    private val aeadProvider: () -> Aead,
    private val io: CoroutineDispatcher,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val aead by lazy { aeadProvider() }

    private val _present = MutableStateFlow<Set<SecretName>>(emptySet())
    /** Which secrets exist (never their values), for settings screens. */
    val present: StateFlow<Set<SecretName>> = _present.asStateFlow()

    private val _aiKeysPresent = MutableStateFlow<Set<String>>(emptySet())
    /** AI profile ids that have a key (never the values). */
    val aiKeysPresent: StateFlow<Set<String>> = _aiKeysPresent.asStateFlow()

    suspend fun get(name: SecretName): String? = read(name.name)

    suspend fun set(name: SecretName, value: String?) = write(name.name, value)

    /** Key for an AI profile (SPEC §4.7), stored under `ai:<profileId>`. */
    suspend fun aiKey(profileId: String): String? = read(AI_PREFIX + profileId)

    suspend fun setAiKey(profileId: String, value: String?) = write(AI_PREFIX + profileId, value)

    /**
     * Every stored secret, for the passphrase-encrypted settings export only (SPEC §4.11).
     * No other caller may use this.
     */
    internal suspend fun exportAll(): Map<String, String> = withContext(io) { mutex.withLock { readAll() } }

    /** Replaces every secret (settings import). */
    internal suspend fun replaceAll(values: Map<String, String>) = withContext(io) {
        mutex.withLock { persist(values.filterValues { it.isNotBlank() }) }
    }

    /** Loads presence at startup. */
    suspend fun refresh() = withContext(io) { mutex.withLock { publish(readAll()) } }

    private suspend fun read(key: String): String? = withContext(io) { mutex.withLock { readAll()[key] } }

    private suspend fun write(key: String, value: String?) = withContext(io) {
        mutex.withLock {
            val all = readAll().toMutableMap()
            if (value.isNullOrBlank()) all.remove(key) else all[key] = value.trim()
            persist(all)
        }
    }

    private fun persist(all: Map<String, String>) {
        file.parentFile?.mkdirs()
        val bytes = json.encodeToString(serializer, all).encodeToByteArray()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(aead.encrypt(bytes, ASSOCIATED_DATA))
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
        publish(all)
    }

    private fun publish(all: Map<String, String>) {
        _present.value = all.keys.mapNotNull { runCatching { SecretName.valueOf(it) }.getOrNull() }.toSet()
        _aiKeysPresent.value = all.keys.filter { it.startsWith(AI_PREFIX) }.map { it.removePrefix(AI_PREFIX) }.toSet()
    }

    private fun readAll(): Map<String, String> {
        if (!file.exists()) return emptyMap()
        return try {
            json.decodeFromString(serializer, aead.decrypt(file.readBytes(), ASSOCIATED_DATA).decodeToString())
        } catch (e: java.security.GeneralSecurityException) {
            // Keyset lost (e.g. restored onto a new phone): the old file can't be read. Start clean.
            file.delete()
            emptyMap()
        }
    }

    companion object {
        private const val AI_PREFIX = "ai:"
        private val ASSOCIATED_DATA = "forkcast-secrets-v1".encodeToByteArray()
        private const val KEYSET_NAME = "forkcast_secrets_keyset"
        /** Must match the exclusion in data_extraction_rules.xml / backup_rules.xml. */
        const val KEYSET_PREFS = "secrets"
        private const val MASTER_KEY_URI = "android-keystore://forkcast_secrets_master"

        fun androidAead(context: Context): Aead {
            AeadConfig.register()
            return AndroidKeysetManager.Builder()
                .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS)
                .withKeyTemplate(com.google.crypto.tink.KeyTemplates.get("AES256_GCM"))
                .withMasterKeyUri(MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        }

        fun secretsFile(context: Context) = File(File(context.filesDir, "secrets"), "store.bin")
    }
}
