package app.forkcast.core.data.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The backup's password is wrong, or the encrypted file was changed or cut short. */
class BackupDecryptException(cause: Throwable? = null) : IOException("wrong password or damaged backup", cause)

/**
 * Password-protected backups (roadmap 2.0 decision D5, owner OK 2026-10-24). The same key
 * derivation as the settings export (PBKDF2-HMAC-SHA256, random salt, [SettingsCrypto.ITERATIONS]),
 * then AES-256-GCM in segments of [SEGMENT] bytes, so a backup full of photos never has to fit in
 * memory (Android's GCM buffers a whole message on decrypt).
 *
 * File: [MAGIC], format version, iterations, salt, nonce prefix, segment size; then the segments.
 * Segment i's nonce is the prefix + i; its associated data is the header + i + "last segment?",
 * so reordered, dropped or cut-off segments and an edited header all fail to decrypt.
 */
object BackupCrypto {
    const val SEGMENT = 1 shl 20
    private const val VERSION = 1
    private const val SALT_BYTES = 16
    private const val PREFIX_BYTES = 8
    private const val TAG_BYTES = 16
    private val random = SecureRandom()

    /** "FORKCAST-ENC" in ASCII: how [isEncrypted] tells a protected backup from a zip or JSON. */
    val MAGIC: ByteArray = "FORKCAST-ENC".encodeToByteArray()

    /** Whether [head] (the file's first bytes) starts an encrypted backup. */
    fun isEncrypted(head: ByteArray): Boolean = head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC)

    /** Writes the header to [out] now and returns a stream that encrypts into it; close it to finish the file. */
    fun encrypt(out: OutputStream, password: CharArray, iterations: Int = SettingsCrypto.ITERATIONS, segment: Int = SEGMENT): OutputStream {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val prefix = ByteArray(PREFIX_BYTES).also(random::nextBytes)
        val header = header(iterations, salt, prefix, segment)
        out.write(header)
        return EncryptingStream(out, SettingsCrypto.deriveKey(password, salt, iterations), header, prefix, segment)
    }

    /** Reads the header from [input] and returns the decrypted stream; reads throw [BackupDecryptException]. */
    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also { data.readFully(it) }
        if (!magic.contentEquals(MAGIC)) throw BackupDecryptException()
        val version = data.readUnsignedByte()
        if (version != VERSION) throw BackupDecryptException()
        val iterations = data.readInt()
        // Bounds keep a hostile file from asking for a year of PBKDF2 or a huge buffer.
        if (iterations !in 100_000..10_000_000) throw BackupDecryptException()
        val salt = ByteArray(SALT_BYTES).also { data.readFully(it) }
        val prefix = ByteArray(PREFIX_BYTES).also { data.readFully(it) }
        val segment = data.readInt()
        if (segment !in 4_096..(16 shl 20)) throw BackupDecryptException()
        val header = header(iterations, salt, prefix, segment)
        return DecryptingStream(input, SettingsCrypto.deriveKey(password, salt, iterations), header, prefix, segment)
    }

    private fun header(iterations: Int, salt: ByteArray, prefix: ByteArray, segment: Int): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                write(MAGIC)
                writeByte(VERSION)
                writeInt(iterations)
                write(salt)
                write(prefix)
                writeInt(segment)
            }
        }.toByteArray()

    private fun cipher(mode: Int, key: SecretKeySpec, header: ByteArray, prefix: ByteArray, index: Int, last: Boolean): Cipher {
        val nonce = ByteBuffer.allocate(PREFIX_BYTES + 4).put(prefix).putInt(index).array()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BYTES * 8, nonce))
            updateAAD(ByteBuffer.allocate(header.size + 5).put(header).putInt(index).put(if (last) 1 else 0).array())
        }
    }

    /** Holds a full segment back until more data (or close) says whether it's the last. */
    private class EncryptingStream(
        private val out: OutputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val prefix: ByteArray,
        private val segment: Int,
    ) : OutputStream() {
        private val buf = ByteArray(segment)
        private var size = 0
        private var index = 0
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "closed" }
            var o = off
            var left = len
            while (left > 0) {
                if (size == segment) emit(last = false)
                val n = minOf(left, segment - size)
                System.arraycopy(b, o, buf, size, n)
                size += n
                o += n
                left -= n
            }
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            closed = true
            out.use { emit(last = true) }
        }

        private fun emit(last: Boolean) {
            if (index == Int.MAX_VALUE) throw IOException("backup too large")
            out.write(cipher(Cipher.ENCRYPT_MODE, key, header, prefix, index, last).doFinal(buf, 0, size))
            size = 0
            index++
        }
    }

    /** A segment is the last when the file ends with it; one byte of look-ahead tells. */
    private class DecryptingStream(
        private val input: InputStream,
        private val key: SecretKeySpec,
        private val header: ByteArray,
        private val prefix: ByteArray,
        segment: Int,
    ) : InputStream() {
        private val chunk = ByteArray(segment + TAG_BYTES)
        private var plain = ByteArray(0)
        private var pos = 0
        private var index = 0
        private var lookahead = -1
        private var finished = false

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (pos == plain.size) {
                if (finished) return -1
                next()
            }
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        private fun next() {
            var size = 0
            if (lookahead >= 0) {
                chunk[0] = lookahead.toByte()
                size = 1
                lookahead = -1
            }
            while (size < chunk.size) {
                val n = input.read(chunk, size, chunk.size - size)
                if (n < 0) break
                size += n
            }
            val last = size < chunk.size || input.read().also { lookahead = it } < 0
            if (size < TAG_BYTES) throw BackupDecryptException()
            plain = try {
                cipher(Cipher.DECRYPT_MODE, key, header, prefix, index, last).doFinal(chunk, 0, size)
            } catch (e: GeneralSecurityException) {
                throw BackupDecryptException(e)
            }
            pos = 0
            index++
            finished = last
        }

        override fun close() = input.close()
    }
}
