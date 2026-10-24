package app.forkcast.ui.common

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal

/**
 * Android's own unlock prompt (fingerprint or face, falling back to the PIN, pattern or password),
 * for showing or copying a saved key (owner, 2026-10-23). This only gates the screen: the keys'
 * encryption key isn't tied to it, so estimates, sync and backups use the keys with no prompt.
 *
 * The platform BiometricPrompt (API 28+) works from any activity context, so no extra library.
 */
object DeviceAuth {

    /** A phone with no screen lock can't confirm it's the owner: the caller explains instead. */
    fun hasScreenLock(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    fun authenticate(context: Context, title: String, subtitle: String, onSuccess: () -> Unit, onFailure: () -> Unit = {}) {
        val builder = BiometricPrompt.Builder(context).setTitle(title).setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Face unlock on many phones is a "weak" biometric; fine here, since nothing is decrypted with it.
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(
            CancellationSignal(),
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = onFailure()
            },
        )
    }
}
