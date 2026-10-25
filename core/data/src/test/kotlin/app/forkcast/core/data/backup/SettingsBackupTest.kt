package app.forkcast.core.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.db.AiProfileEntity
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.model.AiSettings
import app.forkcast.core.model.AppSettings
import app.forkcast.core.model.NotificationSettings
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

// Robolectric's Conscrypt provider fails the Oracle JDK's JCE signature check; the JDK's own
// AES-GCM and PBKDF2 are the same algorithms Android provides.
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(AndroidJUnit4::class)
class SettingsBackupTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock = Clock.fixed(Instant.parse("2026-10-18T16:00:00Z"), ZoneId.of("America/New_York"))

    /** Test stand-in for the Keystore-backed AEAD (see SecretStoreTest). */
    private class XorAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = encrypt(ciphertext, associatedData)
    }

    private inner class Phone {
        val db = Room.inMemoryDatabaseBuilder(context, ForkcastDatabase::class.java).allowMainThreadQueries().build()
        val settings = MemoryDataStore(AppSettings())
        val secrets = SecretStore(File(context.filesDir, "secrets-${System.nanoTime()}/store.bin"), { XorAead() }, Dispatchers.Unconfined)
        val repo = SettingsBackupRepository(db, settings, secrets, clock, "0.2.0").apply { iterations = 1_000 }
    }

    private val a = Phone()
    private val b = Phone()

    @After fun tearDown() {
        a.db.close()
        b.db.close()
    }

    private suspend fun seedA() {
        a.settings.updateData {
            it.copy(ai = AiSettings(personalContext = "27 cm plates", autoAccept = false), notifications = NotificationSettings(eveningEnabled = true, permissionAsked = true))
        }
        a.db.backupDao().upsertAiProfiles(listOf(
            AiProfileEntity("p1", "Gemini 3.8 Flash", ProviderId.GEMINI, "gemini-3.8-flash", null, TierLabel.FREE, 0, true, false, null, 1, 1),
            AiProfileEntity("p2", "Claude Haiku 5.5", ProviderId.CLAUDE, "claude-haiku-5-5", null, TierLabel.PAID, 1, true, true, 99, 1, 1),
        ))
        a.secrets.setAiKey("p1", "g-secret-key")
        a.secrets.setAiKey("p2", "sk-ant-secret")
        a.secrets.set(SecretName.USDA_API_KEY, "usda-secret")
    }

    @Test fun roundTripMovesSettingsProfilesAndKeysToAnotherPhone() = runTest {
        seedA()
        val file = a.repo.export("correct horse battery".toCharArray())

        // Keys never appear in clear text in the file.
        assertThat(file).doesNotContain("g-secret-key")
        assertThat(file).doesNotContain("sk-ant-secret")
        assertThat(file).doesNotContain("27 cm plates")

        b.db.backupDao().upsertAiProfiles(listOf(AiProfileEntity("old", "Old", ProviderId.GEMINI, "x", null, TierLabel.FREE, 0, true, false, null, 1, 1)))
        val opened = b.repo.open(file, "correct horse battery".toCharArray()) as OpenedSettings.Ok
        assertThat(opened.preview.profileNames).containsExactly("Gemini 3.8 Flash", "Claude Haiku 5.5").inOrder()
        assertThat(opened.preview.keyCount).isEqualTo(3)
        assertThat(opened.preview.hasFoodDatabaseKey).isTrue()

        b.repo.apply(opened.payload)
        assertThat(b.secrets.aiKey("p1")).isEqualTo("g-secret-key")
        assertThat(b.secrets.aiKey("p2")).isEqualTo("sk-ant-secret")
        assertThat(b.secrets.get(SecretName.USDA_API_KEY)).isEqualTo("usda-secret")
        val profiles = b.db.backupDao().aiProfiles()
        assertThat(profiles.filter { it.deletedAt == null }.map { it.id }).containsExactly("p1", "p2")
        assertThat(profiles.first { it.id == "old" }.deletedAt).isNotNull()
        // A fresh start on the new phone: no inherited cooldown or rejected-key flag.
        assertThat(profiles.first { it.id == "p2" }.needsAttention).isFalse()
        val s = b.settings.data.first()
        assertThat(s.ai.personalContext).isEqualTo("27 cm plates")
        assertThat(s.notifications.eveningEnabled).isTrue()
        // This phone's own permission prompt state is kept.
        assertThat(s.notifications.permissionAsked).isFalse()
    }

    /** REVIEW-2 S4: a settings file's own AI server is named and arrives turned off on another phone. */
    @Test fun customServerFromAnotherPhoneArrivesTurnedOff() = runTest {
        val ollama = AiProfileEntity("o", "Ollama", ProviderId.OPENAI_COMPAT, "llava", "http://192.0.2.7:11434", TierLabel.FREE, 0, true, false, null, 1, 1)
        a.db.backupDao().upsertAiProfiles(listOf(ollama))
        val file = a.repo.export("correct horse battery".toCharArray())

        val opened = b.repo.open(file, "correct horse battery".toCharArray()) as OpenedSettings.Ok
        assertThat(opened.preview.aiServers).containsExactly("192.0.2.7:11434")
        b.repo.apply(opened.payload)
        assertThat(b.db.backupDao().aiProfiles().single().enabled).isFalse()

        // Back on the phone that made it, the same profile on the same server stays on.
        a.repo.apply((a.repo.open(file, "correct horse battery".toCharArray()) as OpenedSettings.Ok).payload)
        assertThat(a.db.backupDao().aiProfiles().single().enabled).isTrue()
    }

    @Test fun wrongPassphraseAndTamperingFailCleanly() = runTest {
        seedA()
        val file = a.repo.export("correct horse battery".toCharArray())
        assertThat(b.repo.open(file, "wrong passphrase".toCharArray())).isEqualTo(OpenedSettings.Failed(SettingsImportError.WRONG_PASSPHRASE))

        // Lowering the iteration count in the header breaks the authenticated data.
        val root = Json.parseToJsonElement(file).jsonObject.toMutableMap()
        root["iterations"] = JsonPrimitive(1)
        val tampered = Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), kotlinx.serialization.json.JsonObject(root))
        assertThat(b.repo.open(tampered, "correct horse battery".toCharArray())).isEqualTo(OpenedSettings.Failed(SettingsImportError.WRONG_PASSPHRASE))

        assertThat(b.repo.open("not json", "x".toCharArray())).isEqualTo(OpenedSettings.Failed(SettingsImportError.UNREADABLE))
        assertThat(b.repo.open("""{"app":"Forkcast","schemaVersion":2}""", "x".toCharArray())).isEqualTo(OpenedSettings.Failed(SettingsImportError.NOT_FORKCAST))
        assertThat(b.repo.open("""{"format":"forkcast-settings","version":9}""", "x".toCharArray())).isEqualTo(OpenedSettings.Failed(SettingsImportError.NEWER_VERSION))
    }

    @Test fun eachExportUsesFreshSaltAndIv() = runTest {
        seedA()
        val one = Json.parseToJsonElement(a.repo.export("passphrase1".toCharArray())).jsonObject
        val two = Json.parseToJsonElement(a.repo.export("passphrase1".toCharArray())).jsonObject
        assertThat(one["salt"]).isNotEqualTo(two["salt"])
        assertThat(one["iv"]).isNotEqualTo(two["iv"])
        assertThat(one["kdf"]).isEqualTo(JsonPrimitive("PBKDF2WithHmacSHA256"))
    }

    @Test fun strengthHint() {
        assertThat(SettingsCrypto.strength("short")).isEqualTo(0)
        assertThat(SettingsCrypto.strength("password")).isEqualTo(1)
        assertThat(SettingsCrypto.strength("Passw0rd!x")).isEqualTo(2)
        assertThat(SettingsCrypto.strength("correct horse battery staple")).isEqualTo(3)
    }
}
