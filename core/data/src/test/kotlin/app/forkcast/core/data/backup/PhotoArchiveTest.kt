package app.forkcast.core.data.backup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.core.data.ai.PhotoStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class PhotoArchiveTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val photos = PhotoStore(context)
    private val archive = PhotoArchive(context, photos)

    @Test fun zipCarriesJsonAndPhotosAndRestoresThemOnlyAfterCommit() = runTest {
        photos.root.deleteRecursively()
        File(photos.root, "a1/0.jpg").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
        val out = ByteArrayOutputStream()
        archive.write(out, """{"app":"Forkcast"}""")
        val bytes = out.toByteArray()
        assertThat(archive.isZip(bytes.inputStream().buffered())).isTrue()
        assertThat(archive.isZip("{}".byteInputStream().buffered())).isFalse()

        // Restore onto a "new phone".
        photos.root.deleteRecursively()
        val json = archive.read(bytes.inputStream()).json
        assertThat(json).isEqualTo("""{"app":"Forkcast"}""")
        assertThat(File(photos.root, "a1/0.jpg").exists()).isFalse()
        archive.commitStaged()
        assertThat(File(photos.root, "a1/0.jpg").readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test fun pathTraversalIsRejected() = runTest {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("forkcast.json")); zip.write("{}".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("photos/../../evil.jpg")); zip.write(byteArrayOf(9)); zip.closeEntry()
        }
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { archive.read(out.toByteArray().inputStream()) } }
    }

    @Test fun zipWithoutJsonIsRejected() = runTest {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip -> zip.putNextEntry(ZipEntry("photos/a/0.jpg")); zip.write(byteArrayOf(1)); zip.closeEntry() }
        assertThrows(IOException::class.java) { kotlinx.coroutines.runBlocking { archive.read(out.toByteArray().inputStream()) } }
    }
}
