package app.forkcast.core.data.backup

import androidx.datastore.core.DataStore
import androidx.room.withTransaction
import app.forkcast.core.data.db.AiProfileEntity
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.time.Clock
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * The settings export (SPEC §4.11, owner request 2026-10-18): every setting, the AI profiles
 * and their API keys, and the food-database key, in one passphrase-encrypted file. It is the
 * only way keys ever leave the device, and only when the user makes one.
 */
@Serializable
data class SettingsPayload(
    val settingsSchemaVersion: Int = VERSION,
    val app: String = "Forkcast",
    val appVersion: String,
    val exportedAt: String,
    val settings: AppSettings,
    val aiProfiles: List<AiProfileEntity>,
    /** Secret name → value ("USDA_API_KEY", "ai:<profileId>"). */
    val secrets: Map<String, String>,
) {
    companion object {
        const val VERSION = 1
    }
}

/** What the import screen shows before anything changes. Never includes key values. */
data class SettingsPreview(
    val exportedAt: String,
    val profileNames: List<String>,
    val keyCount: Int,
    val hasFoodDatabaseKey: Boolean,
)

enum class SettingsImportError { UNREADABLE, NOT_FORKCAST, NEWER_VERSION, WRONG_PASSPHRASE }

sealed interface OpenedSettings {
    data class Ok(val payload: SettingsPayload, val preview: SettingsPreview) : OpenedSettings
    data class Failed(val error: SettingsImportError) : OpenedSettings
}

/**
 * File format: a small JSON envelope (readable header, so the app can tell it's a Forkcast
 * settings file and which KDF to use) around AES-256-GCM ciphertext of [SettingsPayload].
 * Key = PBKDF2-HMAC-SHA256(passphrase, random 16-byte salt, [iterations]). The header is bound
 * to the ciphertext as associated data, so editing it fails decryption.
 */
@Serializable
data class SettingsEnvelope(
    val format: String = FORMAT,
    val version: Int = 1,
    val kdf: String = KDF,
    val iterations: Int,
    val salt: String,
    val iv: String,
    val ciphertext: String,
) {
    fun associatedData(): ByteArray = "$format|$version|$kdf|$iterations|$salt|$iv".encodeToByteArray()

    companion object {
        const val FORMAT = "forkcast-settings"
        const val KDF = "PBKDF2WithHmacSHA256"
    }
}

object SettingsCrypto {
    /** OWASP 2023 minimum for PBKDF2-HMAC-SHA256; about a second on a current phone. */
    const val ITERATIONS = 600_000
    const val MIN_PASSPHRASE = 8
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private val random = SecureRandom()
    private val b64 = Base64.getEncoder()
    private val unb64 = Base64.getDecoder()

    fun encrypt(plaintext: ByteArray, passphrase: CharArray, iterations: Int = ITERATIONS): SettingsEnvelope {
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val header = SettingsEnvelope(iterations = iterations, salt = b64.encodeToString(salt), iv = b64.encodeToString(iv), ciphertext = "")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(header.associatedData())
        return header.copy(ciphertext = b64.encodeToString(cipher.doFinal(plaintext)))
    }

    /** Throws [AEADBadTagException] for a wrong passphrase or a tampered file. */
    fun decrypt(envelope: SettingsEnvelope, passphrase: CharArray): ByteArray {
        val salt = unb64.decode(envelope.salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, envelope.iterations), GCMParameterSpec(TAG_BITS, unb64.decode(envelope.iv)))
        cipher.updateAAD(envelope.associatedData())
        return cipher.doFinal(unb64.decode(envelope.ciphertext))
    }

    /** Also the key of password-protected backups ([BackupCrypto]). */
    internal fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance(SettingsEnvelope.KDF).generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    /** 0 = too short, 1 = weak, 2 = fair, 3 = strong. A hint only; any 8+ characters are allowed. */
    fun strength(passphrase: String): Int {
        if (passphrase.length < MIN_PASSPHRASE) return 0
        val classes = listOf(Regex("[a-z]"), Regex("[A-Z]"), Regex("[0-9]"), Regex("[^a-zA-Z0-9]")).count { it.containsMatchIn(passphrase) }
        val words = passphrase.trim().split(Regex("\\s+")).size
        return when {
            passphrase.length >= 20 || words >= 4 -> 3
            passphrase.length >= 12 && classes >= 3 -> 3
            passphrase.length >= 12 || classes >= 3 -> 2
            else -> 1
        }
    }
}

@Singleton
class SettingsBackupRepository @Inject constructor(
    private val db: ForkcastDatabase,
    private val settings: DataStore<AppSettings>,
    private val secrets: SecretStore,
    private val clock: Clock,
    @Named(APP_VERSION) private val appVersion: String,
) {
    /** Visible for tests, which use fewer iterations. */
    internal var iterations = SettingsCrypto.ITERATIONS

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    suspend fun export(passphrase: CharArray): String = withContext(Dispatchers.Default) {
        val payload = SettingsPayload(
            appVersion = appVersion,
            exportedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString(),
            settings = settings.data.first(),
            aiProfiles = db.backupDao().aiProfiles().filter { it.deletedAt == null },
            secrets = secrets.exportAll(),
        )
        val plain = json.encodeToString(SettingsPayload.serializer(), payload).encodeToByteArray()
        try {
            json.encodeToString(SettingsEnvelope.serializer(), SettingsCrypto.encrypt(plain, passphrase, iterations))
        } finally {
            plain.fill(0)
        }
    }

    suspend fun open(text: String, passphrase: CharArray): OpenedSettings = withContext(Dispatchers.Default) {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return@withContext OpenedSettings.Failed(SettingsImportError.UNREADABLE)
        if (runCatching { root["format"]?.jsonPrimitive?.content }.getOrNull() != SettingsEnvelope.FORMAT) {
            return@withContext OpenedSettings.Failed(SettingsImportError.NOT_FORKCAST)
        }
        if ((root["version"]?.jsonPrimitive?.intOrNull ?: 0) > 1) return@withContext OpenedSettings.Failed(SettingsImportError.NEWER_VERSION)
        val envelope = runCatching { json.decodeFromJsonElement(SettingsEnvelope.serializer(), root) }.getOrNull()
            ?: return@withContext OpenedSettings.Failed(SettingsImportError.UNREADABLE)
        val plain = try {
            SettingsCrypto.decrypt(envelope, passphrase)
        } catch (e: AEADBadTagException) {
            return@withContext OpenedSettings.Failed(SettingsImportError.WRONG_PASSPHRASE)
        } catch (e: GeneralSecurityException) {
            return@withContext OpenedSettings.Failed(SettingsImportError.UNREADABLE)
        } catch (e: IllegalArgumentException) {
            return@withContext OpenedSettings.Failed(SettingsImportError.UNREADABLE)
        }
        val payload = try {
            json.decodeFromString(SettingsPayload.serializer(), plain.decodeToString())
        } catch (e: IllegalArgumentException) {
            return@withContext OpenedSettings.Failed(SettingsImportError.UNREADABLE)
        } finally {
            plain.fill(0)
        }
        if (payload.settingsSchemaVersion > SettingsPayload.VERSION) return@withContext OpenedSettings.Failed(SettingsImportError.NEWER_VERSION)
        OpenedSettings.Ok(
            payload,
            SettingsPreview(
                exportedAt = payload.exportedAt,
                profileNames = payload.aiProfiles.sortedBy { it.sortOrder }.map { it.name },
                keyCount = payload.secrets.size,
                hasFoodDatabaseKey = SecretName.USDA_API_KEY.name in payload.secrets,
            ),
        )
    }

    /** Replaces settings, AI profiles and keys with the file's. This phone's permission prompt state is kept. */
    suspend fun apply(payload: SettingsPayload) {
        val now = clock.millis()
        val dao = db.backupDao()
        db.withTransaction {
            val incoming = payload.aiProfiles.map { it.id }.toSet()
            dao.upsertAiProfiles(dao.aiProfiles().filter { it.id !in incoming && it.deletedAt == null }.map { it.copy(deletedAt = now, updatedAt = now) })
            dao.upsertAiProfiles(payload.aiProfiles.map { it.copy(updatedAt = now, deletedAt = null, cooldownUntil = null, needsAttention = false) })
        }
        settings.updateData { local ->
            payload.settings.copy(notifications = payload.settings.notifications.copy(permissionAsked = local.notifications.permissionAsked))
        }
        secrets.replaceAll(payload.secrets)
    }
}
