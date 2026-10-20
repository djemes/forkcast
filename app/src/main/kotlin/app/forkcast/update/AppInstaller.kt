package app.forkcast.update

import androidx.core.net.toUri
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Hands a downloaded update to Android's own installer (PackageInstaller), which asks the user to
 * confirm and installs over this app, keeping its data: an update, never an uninstall. Android
 * refuses an APK signed with another key; UpdateRepository checks that before we get here.
 */
object AppInstaller {

    sealed interface Status {
        data object Idle : Status
        data object Waiting : Status
        /** Android refused or the user cancelled; [message] is the installer's own text, if any. */
        data class Failed(val message: String?) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** Android asks once per installing app ("Install unknown apps"): Forkcast needs it to update itself. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun allowInstallsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())

    suspend fun install(context: Context, apk: File) = withContext(Dispatchers.IO) {
        _status.value = Status.Waiting
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Once Forkcast has installed itself, Android may skip the prompt for later updates.
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("forkcast.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val intent = Intent(context, InstallResultReceiver::class.java).setAction(ACTION_RESULT)
                // Mutable: the installer adds its status and, when needed, the confirmation intent.
                val result = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                session.commit(result.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            _status.value = Status.Failed(e.message)
        }
    }

    internal fun onResult(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            // The app restarts as the new version.
            PackageInstaller.STATUS_SUCCESS -> _status.value = Status.Idle
            else -> _status.value = Status.Failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
        }
    }

    fun reset() {
        _status.value = Status.Idle
    }

    private const val ACTION_RESULT = "app.forkcast.update.INSTALL_RESULT"
}

/** Receives PackageInstaller's answer for [AppInstaller.install]. Not exported. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AppInstaller.onResult(context, intent)
}
