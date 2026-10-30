package app.forkcast.core.data.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.ConscryptMode
import java.io.ByteArrayOutputStream
import kotlin.random.Random

// Robolectric with the JDK's own AES-GCM: Robolectric's Conscrypt provider fails the Oracle JDK's
// JCE signature check, and an earlier test in the same JVM leaves it installed. The JDK's AES-GCM
// and PBKDF2 are the same algorithms Android provides.
@ConscryptMode(ConscryptMode.Mode.OFF)
@RunWith(AndroidJUnit4::class)
class BackupCryptoTest {

    private val password = "correct horse".toCharArray()

    private fun encrypt(plain: ByteArray, pw: CharArray = password): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(out, pw, FAST, SEGMENT).use { it.write(plain) }
        return out.toByteArray()
    }

    private fun decrypt(bytes: ByteArray, pw: CharArray = password): ByteArray =
        BackupCrypto.decrypt(bytes.inputStream(), pw).use { it.readBytes() }

    /** Bytes before the first segment. */
    private fun headerSize(bytes: ByteArray, plainSize: Int): Int {
        val segments = plainSize / SEGMENT + 1
        return bytes.size - plainSize - segments * TAG
    }

    @Test fun roundTripsAcrossSegmentBoundaries() {
        for (size in listOf(0, 1, SEGMENT - 1, SEGMENT, SEGMENT + 1, 3 * SEGMENT, 3 * SEGMENT + 5)) {
            val plain = Random(size).nextBytes(size)
            val bytes = encrypt(plain)
            assertThat(BackupCrypto.isEncrypted(bytes)).isTrue()
            assertThat(decrypt(bytes)).isEqualTo(plain)
        }
    }

    @Test fun byteByByteWritesGiveTheSameResult() {
        val plain = Random(1).nextBytes(2 * SEGMENT + 3)
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(out, password, FAST, SEGMENT).use { s -> plain.forEach { s.write(it.toInt()) } }
        assertThat(decrypt(out.toByteArray())).isEqualTo(plain)
    }

    @Test fun wrongPasswordFails() {
        val bytes = encrypt(Random(2).nextBytes(100))
        assertThrows(BackupDecryptException::class.java) { decrypt(bytes, "wrong password".toCharArray()) }
    }

    @Test fun aChangedByteFails() {
        val bytes = encrypt(Random(3).nextBytes(2 * SEGMENT))
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 1).toByte()
        assertThrows(BackupDecryptException::class.java) { decrypt(bytes) }
    }

    @Test fun cuttingOffWholeSegmentsFails() {
        val size = 3 * SEGMENT + 10
        val bytes = encrypt(Random(4).nextBytes(size))
        // Ends right after the second segment, which was written as "not the last".
        val cut = bytes.copyOf(headerSize(bytes, size) + 2 * (SEGMENT + TAG))
        assertThrows(BackupDecryptException::class.java) { decrypt(cut) }
    }

    @Test fun swappedSegmentsFail() {
        val size = 2 * SEGMENT + 10
        val bytes = encrypt(Random(5).nextBytes(size))
        val header = headerSize(bytes, size)
        val seg = SEGMENT + TAG
        val swapped = bytes.copyOf()
        System.arraycopy(bytes, header, swapped, header + seg, seg)
        System.arraycopy(bytes, header + seg, swapped, header, seg)
        assertThrows(BackupDecryptException::class.java) { decrypt(swapped) }
    }

    @Test fun anEditedHeaderFails() {
        val bytes = encrypt(Random(6).nextBytes(10))
        // The salt starts after the magic, the version (1 byte), memory and passes (4 bytes each) and lanes (1 byte).
        val i = BackupCrypto.MAGIC.size + 10
        bytes[i] = (bytes[i].toInt() xor 1).toByte()
        assertThrows(BackupDecryptException::class.java) { decrypt(bytes) }
    }

    @Test fun absurdKeySettingsAreRefusedBeforeAnyWork() {
        val bytes = encrypt(Random(7).nextBytes(10))
        // Memory (KiB) right after the version byte.
        val at = BackupCrypto.MAGIC.size + 1
        bytes[at] = 0x7f
        for (i in 1 until 4) bytes[at + i] = -1
        assertThrows(BackupDecryptException::class.java) { decrypt(bytes) }
    }

    @Test fun filesFromOlderVersionsStillOpen() {
        val plain = Random(8).nextBytes(SEGMENT + 7)
        val out = ByteArrayOutputStream()
        BackupCrypto.encryptPbkdf2(out, password, ITERATIONS, SEGMENT).use { it.write(plain) }
        assertThat(decrypt(out.toByteArray())).isEqualTo(plain)
        assertThrows(BackupDecryptException::class.java) { decrypt(out.toByteArray(), "wrong password".toCharArray()) }
    }

    @Test fun plainFilesAreNotTakenForEncryptedOnes() {
        assertThat(BackupCrypto.isEncrypted("""{"app":"Forkcast"}""".encodeToByteArray())).isFalse()
        assertThat(BackupCrypto.isEncrypted(byteArrayOf(0x50, 0x4b, 3, 4))).isFalse()
    }

    private companion object {
        const val ITERATIONS = 100_000
        /** The smallest Argon2id settings a file may ask for, so the tests stay quick. */
        val FAST = Kdf(memoryKiB = 8 * 1024, iterations = 1, parallelism = 1)
        const val SEGMENT = 4_096
        const val TAG = 16
    }
}
