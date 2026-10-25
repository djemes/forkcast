package app.forkcast.core.data.repository

import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.data.settings.AppSettingsSerializer
import app.forkcast.core.data.settings.DevicePrefs
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import app.forkcast.core.network.ai.AiDefaults
import app.forkcast.core.network.ai.AiHttpClientFactory
import app.forkcast.core.network.ai.MealEstimatorFactory
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.Aead
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** After a restore the keys are gone (never backed up): the app names what to add again (SPEC §4.11). */
@RunWith(AndroidJUnit4::class)
class MissingKeysRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ForkcastDatabase::class.java).allowMainThreadQueries().build()
    private val clock = Clock.fixed(Instant.parse("2026-10-19T14:00:00Z"), ZoneId.of("America/New_York"))

    private class XorAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = encrypt(ciphertext, associatedData)
    }

    private val secrets = SecretStore(File(context.filesDir, "secrets-${System.nanoTime()}/store.bin"), { XorAead() }, Dispatchers.Unconfined)
    private val settings = DataStoreSettingsRepository(
        DataStoreFactory.create(AppSettingsSerializer) { File(context.filesDir, "settings-mk-${System.nanoTime()}.json") },
    )
    private val defaults = AiDefaults.load()
    private val ai = DefaultAiRepository(
        db.aiDao(), secrets, MealEstimatorFactory(AiHttpClientFactory.create("t", MockEngine { respond("{}") }), defaults) { secrets.aiKey(it) },
        defaults, settings, clock, DefaultConsentRepository(DevicePrefs(context), clock),
    )
    private val device = DevicePrefs(context).also { d -> d.update { it.copy(dismissedMissingKeys = emptySet()) } }
    private val repo = DefaultMissingKeysRepository(ai, secrets, settings, device)

    @After fun tearDown() = db.close()

    @Test fun namesProfilesWithoutKeysAndALostFoodDatabaseKey() = runTest {
        ai.saveProfile(AiProfileDraft(null, "Gemini (free)", ProviderId.GEMINI, "m", null, TierLabel.FREE), "AQ.key")
        val restored = ai.saveProfile(AiProfileDraft(null, "Claude", ProviderId.CLAUDE, "m", null, TierLabel.PAID), null)
        ai.saveProfile(AiProfileDraft(null, "Ollama", ProviderId.OPENAI_COMPAT, "m", "http://box:11434/v1", TierLabel.FREE), null)
        repo.noteFoodDatabaseKey(true)

        assertThat(repo.missing.first()).containsExactly(MissingKey("ai:$restored", "Claude"), MissingKey("usda", null)).inOrder()

        secrets.set(SecretName.USDA_API_KEY, "usda-key")
        assertThat(repo.missing.first()).containsExactly(MissingKey("ai:$restored", "Claude"))
    }

    @Test fun notNowStopsAskingOnThisPhone() = runTest {
        val id = ai.saveProfile(AiProfileDraft(null, "Claude", ProviderId.CLAUDE, "m", null, TierLabel.PAID), null)
        repo.dismiss(listOf("ai:$id"))
        assertThat(repo.missing.first()).isEmpty()
        assertThat(device.state.value.dismissedMissingKeys).contains("ai:$id")
    }

    @Test fun aiTurnedOffDoesNotAskForAiKeys() = runTest {
        ai.saveProfile(AiProfileDraft(null, "Claude", ProviderId.CLAUDE, "m", null, TierLabel.PAID), null)
        settings.updateAi { it.copy(enabled = false) }
        assertThat(repo.missing.first()).isEmpty()
    }

    @Test fun keyRemovedOnPurposeIsNotAskedFor() = runTest {
        repo.noteFoodDatabaseKey(true)
        repo.noteFoodDatabaseKey(false)
        assertThat(repo.missing.first()).isEmpty()
    }
}
