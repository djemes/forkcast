package app.forkcast.core.data.repository

import androidx.datastore.core.DataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.db.ForkcastDatabase
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.data.settings.AppSettingsSerializer
import app.forkcast.core.data.settings.DevicePrefs
import app.forkcast.core.model.AiRecipient
import app.forkcast.core.model.ProviderId
import app.forkcast.core.model.TierLabel
import app.forkcast.core.network.ai.AiChain
import app.forkcast.core.network.ai.AiDefaults
import app.forkcast.core.network.ai.AiError
import app.forkcast.core.network.ai.MealEstimateRequest
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

/** Settings › AI profile list: delete keeps the fallback order intact, Undo puts everything back. */
@RunWith(AndroidJUnit4::class)
class AiRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, ForkcastDatabase::class.java).allowMainThreadQueries().build()
    private val clock = Clock.fixed(Instant.parse("2026-10-19T14:00:00Z"), ZoneId.of("America/New_York"))

    /** Test stand-in for the Keystore-backed AEAD (see SecretStoreTest). */
    private class XorAead : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = encrypt(ciphertext, associatedData)
    }

    private val secretsFile = File(context.filesDir, "secrets-${System.nanoTime()}/store.bin")
    private val secrets = SecretStore(secretsFile, { XorAead() }, Dispatchers.Unconfined)
    private val settings = DataStoreSettingsRepository(
        DataStoreFactory.create(AppSettingsSerializer) { File(context.filesDir, "settings-ai-${System.nanoTime()}.json") },
    )
    private val defaults = AiDefaults.load()
    /** Every request that reached the "provider". */
    private val sent = mutableListOf<String>()
    private val http = AiHttpClientFactory.create("t", MockEngine { request -> sent += request.url.host; respond("{}") })
    private val consent = DefaultConsentRepository(DevicePrefs(context), clock)
    private val repo = DefaultAiRepository(
        db.aiDao(), secrets, MealEstimatorFactory(http, defaults) { secrets.aiKey(it) }, defaults, settings, clock, consent,
    )

    @After fun tearDown() = db.close()

    private suspend fun add(name: String, key: String) =
        repo.saveProfile(AiProfileDraft(null, name, ProviderId.GEMINI, "gemini-3.8-flash", null, TierLabel.FREE), key)

    private suspend fun order() = repo.profilesNow().sortedBy { it.order }.map { it.name to it.order }

    /** After an app restart the store hasn't been read yet; the list must still know which keys exist. */
    /** Roadmap 2.0 task 4.2: nothing goes to a provider before the user allows it, or after taking it back. */
    @Test fun noRequestWithoutPermission() = runTest {
        val gemini = AiRecipient.of(ProviderId.GEMINI, null)
        consent.revokeAi(gemini)
        val id = add("A", "AQ.key-a")
        val profile = repo.profile(id)!!
        val request = MealEstimateRequest(emptyList(), "toast", "Lunch", "12:00", "metric", null, null, null, profile.model)

        assertThat(repo.test(id).exceptionOrNull()).isInstanceOf(AiError.NoConsent::class.java)
        assertThat(repo.testKey(ProviderId.GEMINI, profile.model, "AQ.key").exceptionOrNull()).isInstanceOf(AiError.NoConsent::class.java)
        assertThat(repo.listModels(ProviderId.GEMINI, null, id, null).exceptionOrNull()).isInstanceOf(AiError.NoConsent::class.java)
        assertThat(repo.estimatorFor(profile).estimate(request).exceptionOrNull()).isInstanceOf(AiError.NoConsent::class.java)
        assertThat(sent).isEmpty()

        consent.allowAi(gemini)
        repo.test(id)
        repo.estimatorFor(profile).estimate(request)
        assertThat(sent).hasSize(2)

        consent.revokeAi(gemini)
        repo.estimatorFor(profile, textOnly = true).estimate(request)
        repo.listModels(ProviderId.GEMINI, null, id, null)
        assertThat(sent).hasSize(2)
    }

    /** An OpenAI-compatible server is its own recipient: allowing OpenRouter doesn't allow another host. */
    @Test fun permissionIsPerServer() = runTest {
        consent.allowAi(AiRecipient.of(ProviderId.OPENAI_COMPAT, "https://openrouter.ai/api"))
        assertThat(repo.listModels(ProviderId.OPENAI_COMPAT, "https://example.org/v1", null, "k").exceptionOrNull())
            .isInstanceOf(AiError.NoConsent::class.java)
        assertThat(sent).isEmpty()
        repo.listModels(ProviderId.OPENAI_COMPAT, "https://openrouter.ai/api", null, "k")
        assertThat(sent).containsExactly("openrouter.ai")
    }

    @Test fun savedKeysShowAfterRestart() = runTest {
        val id = add("A", "AQ.key-a")
        val restarted = SecretStore(secretsFile, { XorAead() }, Dispatchers.Unconfined)
        val repo2 = DefaultAiRepository(db.aiDao(), restarted, MealEstimatorFactory(http, defaults) { restarted.aiKey(it) }, defaults, settings, clock, consent)
        assertThat(repo2.profiles.first().single { it.profile.id == id }.hasKey).isTrue()
    }

    @Test fun deleteMovesTheRestUpAndUndoRestoresPlaceAndKey() = runTest {
        val a = add("A", "AQ.key-a")
        val b = add("B", "AIza-key-b")
        add("C", "AQ.key-c")

        val deleted = repo.deleteProfile(b)!!
        assertThat(order()).containsExactly("A" to 0, "C" to 1).inOrder()
        assertThat(secrets.aiKey(b)).isNull()
        assertThat(repo.profiles.first().map { it.profile.name }).containsExactly("A", "C").inOrder()
        // The chain now tries A, then C.
        assertThat(AiChain.usable(repo.profilesNow(), clock.instant()).map { it.name }).containsExactly("A", "C").inOrder()
        // The Undo token never prints the key.
        assertThat(deleted.toString()).doesNotContain("AIza-key-b")

        repo.restoreProfile(deleted)
        assertThat(order()).containsExactly("A" to 0, "B" to 1, "C" to 2).inOrder()
        assertThat(repo.savedKey(b)).isEqualTo("AIza-key-b")
        assertThat(repo.profiles.first().single { it.profile.id == b }.hasKey).isTrue()

        // Deleting the first profile makes the next one first.
        repo.deleteProfile(a)
        assertThat(order().first()).isEqualTo("B" to 0)
    }

    @Test fun newProfileAfterDeleteGoesLast() = runTest {
        val a = add("A", "k1")
        add("B", "k2")
        repo.deleteProfile(a)
        add("C", "k3")
        assertThat(order()).containsExactly("B" to 0, "C" to 1).inOrder()
    }

    @Test fun deletingTwiceIsHarmless() = runTest {
        val a = add("A", "k1")
        assertThat(repo.deleteProfile(a)).isNotNull()
        assertThat(repo.deleteProfile(a)).isNull()
        assertThat(repo.deleteProfile("missing")).isNull()
    }
}
