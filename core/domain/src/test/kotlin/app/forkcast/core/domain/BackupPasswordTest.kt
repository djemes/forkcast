package app.forkcast.core.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Random

class BackupPasswordTest {

    @Test fun `shorter than ten is too short`() {
        assertThat(BackupPassword.strength("Ab1!xyz9q")).isEqualTo(0)
        assertThat(BackupPassword.isAcceptable("Ab1!xyz9q")).isFalse()
        assertThat(BackupPassword.isAcceptable("Ab1!xyz9qr")).isTrue()
    }

    @Test fun `the meter rises with length and variety`() {
        assertThat(BackupPassword.strength("aaaaaaaaaaaa")).isEqualTo(1)
        assertThat(BackupPassword.strength("abcdefghij")).isEqualTo(1)
        assertThat(BackupPassword.strength("abcdefgh12")).isEqualTo(2)
        assertThat(BackupPassword.strength("Abcdefgh12")).isEqualTo(3)
        assertThat(BackupPassword.strength("correct horse battery staple")).isEqualTo(4)
    }

    @Test fun `a generated password is long, grouped and strong`() {
        val pw = BackupPassword.generate(Random(42))
        assertThat(pw).matches("[a-zA-Z2-9]{4}(-[a-zA-Z2-9]{4}){4}")
        assertThat(pw).doesNotContainMatch("[01lIO]")
        assertThat(BackupPassword.strength(pw)).isEqualTo(4)
        assertThat(BackupPassword.generate(Random(43))).isNotEqualTo(pw)
    }
}
