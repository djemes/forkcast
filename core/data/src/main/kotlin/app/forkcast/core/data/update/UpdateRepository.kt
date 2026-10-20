package app.forkcast.core.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.domain.AppVersion
import app.forkcast.core.network.update.GitHubReleases
import app.forkcast.core.network.update.Release
import app.forkcast.core.network.update.UpdateError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** The answer to "Check for updates". */
sealed interface UpdateCheck {
    /** No GitHub token saved: the private repository can't be read. */
    data object NoToken : UpdateCheck
    data class UpToDate(val latestTag: String) : UpdateCheck
    data class Available(val release: Release) : UpdateCheck
    data class Failed(val error: UpdateError) : UpdateCheck
}

/** Why a downloaded APK can't update this install (checked before Android is asked). */
enum class ApkProblem { NOT_AN_APK, OTHER_APP, OTHER_SIGNER, NOT_NEWER, NO_APK_IN_RELEASE }

sealed interface Download {
    data class Ready(val apk: File) : Download
    data class Refused(val problem: ApkProblem, val apkVersionCode: Long? = null) : Download
    data class Failed(val error: UpdateError) : Download
}

/**
 * "Check for updates" (owner, 2026-10-20): asks GitHub for the newest release with the user's own
 * read-only token, downloads its APK into the cache, and checks it's this app, signed with this
 * app's key and newer, before the system installer is asked. Only runs when the user taps.
 */
interface UpdateRepository {
    val hasToken: Flow<Boolean>
    suspend fun savedToken(): String?
    suspend fun setToken(token: String?)
    suspend fun check(currentVersionName: String): UpdateCheck
    suspend fun download(release: Release, progress: (Long, Long) -> Unit): Download
}

@Singleton
class GitHubUpdateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val releases: GitHubReleases,
    private val secrets: SecretStore,
) : UpdateRepository {

    override val hasToken: Flow<Boolean> = secrets.present.map { SecretName.GITHUB_TOKEN in it }

    override suspend fun savedToken(): String? = secrets.get(SecretName.GITHUB_TOKEN)

    override suspend fun setToken(token: String?) = secrets.set(SecretName.GITHUB_TOKEN, token)

    override suspend fun check(currentVersionName: String): UpdateCheck {
        val token = savedToken() ?: return UpdateCheck.NoToken
        return try {
            val latest = releases.latest(token)
            if (AppVersion.isNewer(latest.tag, currentVersionName)) UpdateCheck.Available(latest) else UpdateCheck.UpToDate(latest.tag)
        } catch (e: UpdateError) {
            UpdateCheck.Failed(e)
        }
    }

    override suspend fun download(release: Release, progress: (Long, Long) -> Unit): Download {
        val asset = release.apk ?: return Download.Refused(ApkProblem.NO_APK_IN_RELEASE)
        val token = savedToken() ?: return Download.Failed(UpdateError.TokenRejected(401))
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        // One update at a time: older downloads go.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "forkcast-${release.tag.removePrefix("v")}.apk")
        try {
            withContext(Dispatchers.IO) { file.outputStream().use { out -> releases.download(token, asset, out, progress) } }
        } catch (e: UpdateError) {
            file.delete()
            return Download.Failed(e)
        }
        return withContext(Dispatchers.IO) { verify(file) }
    }

    /** Same package, same signing key, higher versionCode: otherwise Android would refuse it anyway. */
    private fun verify(file: File): Download {
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val apk = pm.getPackageArchiveInfo(file.path, flags) ?: return refuse(file, ApkProblem.NOT_AN_APK)
        if (apk.packageName != context.packageName) return refuse(file, ApkProblem.OTHER_APP)
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (signers(apk) != signers(installed) || signers(apk).isEmpty()) return refuse(file, ApkProblem.OTHER_SIGNER)
        if (apk.longVersionCode <= installed.longVersionCode) return refuse(file, ApkProblem.NOT_NEWER, apk.longVersionCode)
        return Download.Ready(file)
    }

    private fun refuse(file: File, problem: ApkProblem, code: Long? = null): Download {
        file.delete()
        return Download.Refused(problem, code)
    }

    private fun signers(info: PackageInfo): Set<String> =
        info.signingInfo?.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()

    companion object {
        /** In the cache: never backed up, and Android may clear it. */
        const val DIR = "updates"
    }
}
