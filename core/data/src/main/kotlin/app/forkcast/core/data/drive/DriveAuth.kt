package app.forkcast.core.data.drive

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** The outcome of asking Google for Drive access (SPEC §4.11, scope `drive.file`). */
sealed interface DriveAccess {
    /** A short-lived access token; never stored or logged. */
    class Granted(val token: String) : DriveAccess {
        override fun toString() = "Granted"
    }

    /** The user has to choose an account or agree first: launch [intent] from an activity. */
    class NeedsConsent(val intent: PendingIntent) : DriveAccess

    /** [reason]: [NOT_SET_UP] (no OAuth client for this app yet), [CANCELED], [OFFLINE] or a status code. */
    data class Failed(val reason: String) : DriveAccess

    companion object {
        const val NOT_SET_UP = "not_set_up"
        const val CANCELED = "canceled"
        const val OFFLINE = "offline"
    }
}

/**
 * Drive access through Google Identity's AuthorizationClient (developer.android.com/identity/authorization,
 * play-services-auth 22.0.0). No refresh tokens on the device: each backup asks again, and Google
 * answers silently while access is still granted.
 */
interface DriveAuth {
    /** Silent when access was granted before; otherwise [DriveAccess.NeedsConsent]. */
    suspend fun authorize(): DriveAccess
    /** Reads the result of the consent screen launched from [DriveAccess.NeedsConsent]. */
    fun fromConsent(data: Intent?): DriveAccess
    /** Drops a token Drive refused, so the next [authorize] gets a fresh one. */
    suspend fun forget(token: String)
    /** Removes Forkcast's access to the account's Drive (Disconnect). */
    suspend fun revoke(email: String)
}

@Singleton
class GoogleDriveAuth @Inject constructor(@ApplicationContext private val context: Context) : DriveAuth {

    private val client get() = Identity.getAuthorizationClient(context)
    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_FILE))).build()

    override suspend fun authorize(): DriveAccess = try {
        handle(client.authorize(request).await())
    } catch (e: ApiException) {
        failed(e)
    }

    override fun fromConsent(data: Intent?): DriveAccess = try {
        handle(client.getAuthorizationResultFromIntent(data))
    } catch (e: ApiException) {
        failed(e)
    }

    override suspend fun forget(token: String) {
        runCatching { client.clearToken(ClearTokenRequest.builder().setToken(token).build()).await() }
    }

    override suspend fun revoke(email: String) {
        runCatching {
            client.revokeAccess(
                RevokeAccessRequest.builder().setAccount(Account(email, "com.google")).setScopes(listOf(Scope(DRIVE_FILE))).build(),
            ).await()
        }
    }

    private fun handle(result: AuthorizationResult): DriveAccess {
        val pending = result.pendingIntent
        if (result.hasResolution() && pending != null) return DriveAccess.NeedsConsent(pending)
        return result.accessToken?.let { DriveAccess.Granted(it) } ?: DriveAccess.Failed("no_token")
    }

    private fun failed(e: ApiException): DriveAccess = DriveAccess.Failed(
        when (e.statusCode) {
            // No Android OAuth client matches this app's package + signing SHA-1 (SETUP Stage 5 not done).
            CommonStatusCodes.DEVELOPER_ERROR -> DriveAccess.NOT_SET_UP
            CommonStatusCodes.CANCELED -> DriveAccess.CANCELED
            CommonStatusCodes.NETWORK_ERROR -> DriveAccess.OFFLINE
            else -> "status_${e.statusCode}"
        },
    )

    companion object {
        const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"
    }
}
