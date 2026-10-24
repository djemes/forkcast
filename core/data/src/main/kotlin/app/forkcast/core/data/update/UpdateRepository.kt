package app.forkcast.core.data.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import app.forkcast.core.data.secrets.SecretName
import app.forkcast.core.data.secrets.SecretStore
import app.forkcast.core.domain.AppVersion
import app.forkcast.core.domain.Checksums
import app.forkcast.core.network.update.GitHubReleases
import app.forkcast.core.network.update.Release
import app.forkcast.core.network.update.UpdateError
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** The answer to "Check for updates". */
sealed interface UpdateCheck {
    /** GitHub has no release this phone can see without a token: the repository is still private. */
    data object NeedsToken : UpdateCheck
    data class UpToDate(val latestTag: String) : UpdateCheck
    data class Available(val release: Release) : UpdateCheck
    data class Failed(val error: UpdateError) : UpdateCheck
}

/** Why a downloaded APK can't update this install (checked before Android is asked). */
enum class ApkProblem {
    NOT_AN_APK,
    OTHER_APP,
    OTHER_SIGNER,
    NOT_NEWER,
    NO_APK_IN_RELEASE,
    /** The release has neither SHA256SUMS nor a GitHub digest to check the file against. */
    NO_CHECKSUM,
    /** The file isn't the one the release lists: damaged or replaced on the way. */
    WRONG_CHECKSUM,
}

sealed interface Download {
    data class Ready(val apk: File) : Download
    data class Refused(val problem: ApkProblem, val apkVersionCode: Long? = null) : Download
    data class Failed(val error: UpdateError) : Download
}

/**
 * Updates from GitHub Releases (owner, 2026-10-20; roadmap 2.0 task 1.5): finds the newest
 * release (betas only when asked), downloads its APK into the cache, and checks it against the
 * release's SHA-256 (SHA256SUMS and GitHub's digest), that it's this app, signed with this app's
 * key and newer, before the system installer is asked. The token is used only while the
 * repository is private.
 */
interface UpdateRepository {
    val hasToken: Flow<Boolean>
    suspend fun savedToken(): String?
    suspend fun setToken(token: String?)
    suspend fun check(currentVersionName: String, includeBetas: Boolean): UpdateCheck
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

    override suspend fun check(currentVersionName: String, includeBetas: Boolean): UpdateCheck {
        val token = savedToken()
        return try {
            val newest = if (includeBetas) {
                releases.recent(token).filter { AppVersion.parse(it.tag) != null }.maxByOrNull { AppVersion.parse(it.tag)!! }
                    ?: throw UpdateError.NoRelease()
            } else {
                releases.latest(token)
            }
            if (AppVersion.isNewer(newest.tag, currentVersionName)) UpdateCheck.Available(newest) else UpdateCheck.UpToDate(newest.tag)
        } catch (e: UpdateError.NoRelease) {
            if (token == null) UpdateCheck.NeedsToken else UpdateCheck.Failed(e)
        } catch (e: UpdateError) {
            UpdateCheck.Failed(e)
        }
    }

    override suspend fun download(release: Release, progress: (Long, Long) -> Unit): Download {
        val asset = release.apk ?: return Download.Refused(ApkProblem.NO_APK_IN_RELEASE)
        val token = savedToken()
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        // One update at a time: older downloads go.
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "forkcast-${release.tag.removePrefix("v")}.apk")
        val expected = try {
            withContext(Dispatchers.IO) {
                file.outputStream().use { out -> releases.download(token, asset, out, progress) }
                expectedHashes(release, token)
            }
        } catch (e: UpdateError) {
            file.delete()
            return Download.Failed(e)
        }
        return withContext(Dispatchers.IO) {
            when {
                expected.isEmpty() -> refuse(file, ApkProblem.NO_CHECKSUM)
                sha256(file).let { actual -> expected.any { it != actual } } -> refuse(file, ApkProblem.WRONG_CHECKSUM)
                else -> verify(file)
            }
        }
    }

    /** What the release says the APK's SHA-256 is: from SHA256SUMS and from GitHub's digest, whichever exist. */
    private suspend fun expectedHashes(release: Release, token: String?): List<String> {
        val apk = release.apk ?: return emptyList()
        val fromSums = release.checksums?.let { sums ->
            val text = ByteArrayOutputStream().also { releases.download(token, sums, it) { _, _ -> } }.toString(Charsets.UTF_8.name())
            // A SHA256SUMS that doesn't list the APK can't vouch for it.
            Checksums.fromSums(text, apk.name) ?: return listOf(MISSING)
        }
        return listOfNotNull(fromSums, Checksums.fromDigest(apk.digest))
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
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
        /** Stands for "SHA256SUMS doesn't list this APK": never equal to a real hash. */
        private const val MISSING = "missing"
    }
}
