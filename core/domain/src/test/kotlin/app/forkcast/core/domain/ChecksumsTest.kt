package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChecksumsTest {
    private val a = "a".repeat(64)
    private val b = "B".repeat(64)

    @Test fun findsTheFileInSha256sums() {
        val sums = "$a  Forkcast-v1.5.0.apk\n$b *other.txt\n"
        assertThat(Checksums.fromSums(sums, "Forkcast-v1.5.0.apk")).isEqualTo(a)
        assertThat(Checksums.fromSums(sums, "other.txt")).isEqualTo("b".repeat(64))
        assertThat(Checksums.fromSums(sums, "missing.apk")).isNull()
        assertThat(Checksums.fromSums("garbage", "x")).isNull()
        assertThat(Checksums.fromSums("$a  Forkcast-v1.5.0.apk\r\n", "Forkcast-v1.5.0.apk")).isEqualTo(a)
    }

    @Test fun readsOnlySha256Digests() {
        assertThat(Checksums.fromDigest("sha256:$a")).isEqualTo(a)
        assertThat(Checksums.fromDigest("sha512:$a")).isNull()
        assertThat(Checksums.fromDigest("sha256:xyz")).isNull()
        assertThat(Checksums.fromDigest(null)).isNull()
    }
}
