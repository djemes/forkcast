package app.forkcast.core.data.update

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.network.update.GitHubReleases
import app.forkcast.core.network.update.UpdateHttpClientFactory
import com.google.common.truth.Truth.assertThat
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** The updater against a stand-in for GitHub's Releases API (a public repository: no token). */
@RunWith(AndroidJUnit4::class)
class UpdateRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val apkBytes = "not really an apk".toByteArray()
    private val apkHash = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }
    private val wrongHash = "0".repeat(64)

    /** What the stand-in GitHub serves. */
    private inner class FakeGitHub(
        var sums: String? = "$apkHash  Forkcast-v1.6.0.apk\n",
        var digest: String? = "sha256:$apkHash",
        var withBeta: Boolean = true,
    ) {
        val auths = mutableListOf<String?>()

        private fun release(tag: String, pre: Boolean): String {
            val assets = buildList {
                add("""{"name":"Forkcast-$tag.apk","url":"https://api.github.com/a/apk","size":${apkBytes.size}${digest?.let { ",\"digest\":\"$it\"" } ?: ""}}""")
                sums?.let { add("""{"name":"SHA256SUMS","url":"https://api.github.com/a/sums","size":${it.length}}""") }
            }.joinToString(",")
            return """{"tag_name":"$tag","html_url":"h","prerelease":$pre,"assets":[$assets]}"""
        }

        val engine = MockEngine { req ->
            val auth = req.headers[HttpHeaders.Authorization]
            auths += auth
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            val path = req.url.encodedPath
            when {
                path.endsWith("/releases/latest") -> respond(release("v1.6.0", false), HttpStatusCode.OK, json)
                path.endsWith("/releases") -> respond(
                    "[" + listOfNotNull(release("v1.6.0", false), if (withBeta) release("v1.7.0-beta.1", true) else null).joinToString(",") + "]",
                    HttpStatusCode.OK,
                    json,
                )
                path == "/a/apk" -> respond(ByteReadChannel(apkBytes), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, apkBytes.size.toString()))
                path == "/a/sums" -> respond(ByteReadChannel(sums!!.toByteArray()), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, sums!!.length.toString()))
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        fun repo() = GitHubUpdateRepository(context, GitHubReleases(UpdateHttpClientFactory.create("Forkcast/test", engine), "djemes/forkcast"))
    }

    @Test fun publicRepositoryNeedsNoToken() = runTest {
        val gh = FakeGitHub()
        val check = gh.repo().check("1.5.0", includeBetas = false)
        assertThat((check as UpdateCheck.Available).release.tag).isEqualTo("v1.6.0")
        assertThat(gh.auths).containsExactly(null)
    }

    @Test fun betasOnlyWhenAsked() = runTest {
        val gh = FakeGitHub()
        assertThat((gh.repo().check("1.6.0", includeBetas = false) as UpdateCheck.UpToDate).latestTag).isEqualTo("v1.6.0")
        assertThat((gh.repo().check("1.6.0", includeBetas = true) as UpdateCheck.Available).release.tag).isEqualTo("v1.7.0-beta.1")
        gh.withBeta = false
        assertThat(gh.repo().check("1.6.0", includeBetas = true)).isInstanceOf(UpdateCheck.UpToDate::class.java)
    }

    private suspend fun downloadWith(gh: FakeGitHub): Download {
        val release = (gh.repo().check("1.5.0", includeBetas = false) as UpdateCheck.Available).release
        return gh.repo().download(release) { _, _ -> }
    }

    /** Both checksums match: on to the APK checks (this test file isn't an APK, so that's where it stops). */
    @Test fun matchingChecksumsGoOnToThePackageChecks() = runTest {
        assertThat(downloadWith(FakeGitHub())).isEqualTo(Download.Refused(ApkProblem.NOT_AN_APK))
    }

    @Test fun aTamperedFileIsRefused() = runTest {
        assertThat(downloadWith(FakeGitHub(sums = "$wrongHash  Forkcast-v1.6.0.apk\n"))).isEqualTo(Download.Refused(ApkProblem.WRONG_CHECKSUM))
        assertThat(downloadWith(FakeGitHub(digest = "sha256:$wrongHash"))).isEqualTo(Download.Refused(ApkProblem.WRONG_CHECKSUM))
        // SHA256SUMS that doesn't list the APK can't vouch for it.
        assertThat(downloadWith(FakeGitHub(sums = "$apkHash  something-else.apk\n"))).isEqualTo(Download.Refused(ApkProblem.WRONG_CHECKSUM))
        assertThat(File(context.cacheDir, GitHubUpdateRepository.DIR).listFiles().orEmpty()).isEmpty()
    }

    @Test fun eitherChecksumAloneIsEnoughButNoneIsRefused() = runTest {
        assertThat(downloadWith(FakeGitHub(sums = null))).isEqualTo(Download.Refused(ApkProblem.NOT_AN_APK))
        assertThat(downloadWith(FakeGitHub(digest = null))).isEqualTo(Download.Refused(ApkProblem.NOT_AN_APK))
        assertThat(downloadWith(FakeGitHub(sums = null, digest = null))).isEqualTo(Download.Refused(ApkProblem.NO_CHECKSUM))
    }
}
