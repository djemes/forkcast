package app.forkcast.core.data.settings

import androidx.datastore.core.CorruptionException
import app.forkcast.core.model.AppSettings
import app.forkcast.core.model.AppearanceSettings
import app.forkcast.core.model.ThemeMode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class AppSettingsSerializerTest {

    private suspend fun roundTrip(s: AppSettings): AppSettings {
        val out = ByteArrayOutputStream()
        AppSettingsSerializer.writeTo(s, out)
        return AppSettingsSerializer.readFrom(ByteArrayInputStream(out.toByteArray()))
    }

    private suspend fun read(text: String) =
        AppSettingsSerializer.readFrom(ByteArrayInputStream(text.encodeToByteArray()))

    @Test fun roundTripKeepsValues() = runTest {
        val s = AppSettings(AppearanceSettings(themeMode = ThemeMode.DARK, useWallpaperColors = true))
        assertThat(roundTrip(s)).isEqualTo(s)
    }

    @Test fun emptyFileGivesDefaults() = runTest {
        assertThat(read("")).isEqualTo(AppSettings())
    }

    @Test fun missingAndUnknownKeysAreTolerated() = runTest {
        assertThat(read("""{"appearance":{"themeMode":"LIGHT"},"futureThing":42}"""))
            .isEqualTo(AppSettings(AppearanceSettings(themeMode = ThemeMode.LIGHT)))
    }

    @Test fun unknownEnumValueFallsBackToDefault() = runTest {
        assertThat(read("""{"appearance":{"themeMode":"SEPIA"}}""").appearance.themeMode)
            .isEqualTo(ThemeMode.SYSTEM)
    }

    /** 1.5 to 1.8.1 had a daily or weekly backup until days were chosen (1.9.1 reads it as days). */
    @Test fun olderBackupScheduleReadsAsDays() = runTest {
        assertThat(read("""{"backup":{"frequency":"DAILY","weekday":3,"days":null}}""").backup.days).containsExactly(1, 2, 3, 4, 5, 6, 7)
        assertThat(read("""{"backup":{"frequency":"WEEKLY","weekday":3}}""").backup.days).containsExactly(3)
        assertThat(read("""{"backup":{"frequency":"WEEKLY","weekday":3,"days":[]}}""").backup.days).containsExactly(3)
        assertThat(read("""{"backup":{"frequency":"DAILY","days":[2,4]}}""").backup.days).containsExactly(2, 4)
        assertThat(read("""{}""").backup.days).containsExactly(1)
    }

    @Test fun garbageIsReportedAsCorruption() {
        assertThrows(CorruptionException::class.java) {
            runBlocking { read("not json") }
        }
    }
}
