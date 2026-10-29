package app.forkcast.core.data.backup

import android.content.Context
import app.forkcast.core.data.secrets.SecretStore
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import java.io.File

/** A secret store in [dir] under the app's files; its own encryption is Keystore-backed in the app, a pass-through is enough here. */
fun testSecretStore(context: Context, dir: String): SecretStore =
    SecretStore(File(context.filesDir, "$dir/store.bin"), { PlainAead }, Dispatchers.Unconfined)

private object PlainAead : Aead {
    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext.reversedArray()
    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray) = ciphertext.reversedArray()
}
