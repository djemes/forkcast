package app.forkcast.core.data.secrets

import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.GeneralSecurityException

/**
 * Tests SecretStore's own logic. A tiny keyed test cipher stands in for Tink: real Tink + the
 * Android Keystore can't run here (no Keystore on the JVM, and Oracle's JCE rejects Robolectric's
 * class loader). The real path is exercised on the device/emulator.
 */
class SecretStoreTest {

    @get:Rule val tmp = TemporaryFolder()

    /** XOR with a key byte plus a key tag; decrypting with a different key fails like a real AEAD. */
    private class TestAead(private val key: Byte) : Aead {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
            byteArrayOf(key) + plaintext.map { (it.toInt() xor key.toInt()).toByte() }

        override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            if (ciphertext.isEmpty() || ciphertext[0] != key) throw GeneralSecurityException("wrong key")
            return ciphertext.drop(1).map { (it.toInt() xor key.toInt()).toByte() }.toByteArray()
        }
    }

    private fun store(file: File, key: Byte = 0x5A) = SecretStore(file, { TestAead(key) }, Dispatchers.Unconfined)

    @Test fun roundTripOverwriteAndClear() = runTest {
        val file = File(tmp.root, "secrets/store.bin")
        val s = store(file)
        assertThat(s.get(SecretName.USDA_API_KEY)).isNull()
        s.set(SecretName.USDA_API_KEY, "  abc123  ")
        assertThat(s.get(SecretName.USDA_API_KEY)).isEqualTo("abc123")
        assertThat(s.present.value).containsExactly(SecretName.USDA_API_KEY)
        s.set(SecretName.USDA_API_KEY, "def456")
        assertThat(store(file).get(SecretName.USDA_API_KEY)).isEqualTo("def456")
        s.set(SecretName.USDA_API_KEY, null)
        assertThat(s.get(SecretName.USDA_API_KEY)).isNull()
        assertThat(s.present.value).isEmpty()
    }

    @Test fun fileIsNotPlaintext() = runTest {
        val file = File(tmp.root, "store.bin")
        store(file).set(SecretName.USDA_API_KEY, "very-secret-value")
        assertThat(String(file.readBytes(), Charsets.ISO_8859_1)).doesNotContain("very-secret-value")
    }

    @Test fun lostKeysetStartsCleanInsteadOfCrashing() = runTest {
        val file = File(tmp.root, "store.bin")
        store(file, key = 1).set(SecretName.USDA_API_KEY, "abc")
        // Simulates a restore onto a new phone: same file, different key.
        val restored = store(file, key = 2)
        assertThat(restored.get(SecretName.USDA_API_KEY)).isNull()
        assertThat(file.exists()).isFalse()
        // Kept aside, not deleted, in case the Keystore only failed for a moment.
        assertThat(File(tmp.root, "store.bin.unreadable").exists()).isTrue()
    }

    @Test fun refreshPublishesPresence() = runTest {
        val file = File(tmp.root, "store.bin")
        store(file).set(SecretName.USDA_API_KEY, "abc")
        val fresh = store(file)
        assertThat(fresh.present.value).isEmpty()
        fresh.refresh()
        assertThat(fresh.present.value).containsExactly(SecretName.USDA_API_KEY)
    }

}
