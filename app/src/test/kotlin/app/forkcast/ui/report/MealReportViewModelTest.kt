package app.forkcast.ui.report

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.forkcast.R
import app.forkcast.core.data.repository.LogRepository
import app.forkcast.core.model.FoodSource
import app.forkcast.core.model.LogEntry
import app.forkcast.core.model.NutrientKey
import app.forkcast.core.model.UnitPrefs
import app.forkcast.fakes.FakeDocumentIo
import app.forkcast.fakes.FakeLogRepository
import app.forkcast.fakes.FakeMealSlotRepository
import app.forkcast.fakes.FakeProfileRepository
import app.forkcast.fakes.FakeSettingsRepository
import app.forkcast.fakes.FakeWaterRepository
import app.forkcast.fakes.FakeWeightRepository
import app.forkcast.ui.format.UnitFormatter
import app.forkcast.ui.format.UnitLabels
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class MealReportViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val clock = Clock.fixed(Instant.parse("2026-10-29T16:00:00Z"), ZoneId.of("America/New_York"))
    private val today = LocalDate.of(2026, 10, 29)
    private val log = FakeLogRepository()
    private val documents = FakeDocumentIo()
    private val weights = FakeWeightRepository()
    private val files = MealReportFiles(context.resources, UnitFormatter(UnitPrefs.US, Locale.US, UnitLabels.from(context.resources)), Locale.US)
    private val uri: Uri = Uri.parse("content://test/report")
    private val reportDir = File(context.cacheDir, "report")

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(logRepository: LogRepository = log) = MealReportViewModel(
        context, logRepository, FakeMealSlotRepository(), FakeProfileRepository(weights) { today }, FakeWaterRepository(), weights,
        FakeSettingsRepository(), documents, clock,
    )

    private fun entry(date: LocalDate, kcal: Double) = LogEntry(
        id = "", date = date, mealSlotId = "b", foodId = null, displayName = "Oatmeal", brand = null, quantity = 1.0, servingLabel = "1 cup",
        grams = 234.0, nutrients = mapOf(NutrientKey.ENERGY_KCAL to kcal), source = FoodSource.USDA, note = null, loggedAt = Instant.EPOCH,
        eatenMinute = null, waterMl = null,
    )

    /** Like advanceUntilIdle, also waiting out the file written on Dispatchers.IO. */
    private fun TestScope.settle(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            advanceUntilIdle()
            if (done()) return
            check(System.currentTimeMillis() < deadline) { "still working" }
            Thread.sleep(5)
        }
    }

    @Test fun allTimeMakesTheFileOnceSavesACopyAndDeletesItOnClose() = runTest(dispatcher) {
        log.add(listOf(entry(today.minusDays(400), 500.0), entry(today, 700.0)))
        val vm = vm()
        advanceUntilIdle()
        assertThat(vm.state.value.firstDate).isEqualTo(today.minusDays(400))

        vm.generate(ReportOptions(today.minusDays(400), today, ReportFormat.SPREADSHEET, weight = true, water = true), files)
        settle { vm.state.value.step !is ReportStep.Working }
        val ready = vm.state.value.step as ReportStep.Ready
        assertThat(ready.days).isEqualTo(401)
        assertThat(ready.loggedDays).isEqualTo(2)
        assertThat(ready.entries).isEqualTo(2)
        assertThat(ready.file.name).isEqualTo("forkcast-report-2025-09-24_to_2026-10-29.xlsx")
        assertThat(ready.file.exists()).isTrue()

        vm.saveTo(uri)
        advanceUntilIdle()
        assertThat(documents.writtenBytes[uri]).isEqualTo(ready.file.readBytes())
        assertThat(vm.state.value.notice).isEqualTo(R.string.report_saved)

        vm.close()
        settle { !ready.file.exists() }
        assertThat(vm.state.value.step).isNull()
    }

    @Test fun cancelStopsTheWorkAndLeavesNoFile() = runTest(dispatcher) {
        val stuck = object : LogRepository by log {
            override suspend fun range(from: LocalDate, to: LocalDate): List<LogEntry> = awaitCancellation()
        }
        val vm = vm(stuck)
        vm.generate(ReportOptions(today.minusDays(29), today, ReportFormat.PDF, weight = true, water = true), files)
        advanceUntilIdle()
        assertThat(vm.state.value.step).isEqualTo(ReportStep.Working(null))
        vm.cancel()
        advanceUntilIdle()
        assertThat(vm.state.value.step).isNull()
        assertThat(reportDir.listFiles().orEmpty()).isEmpty()
    }
}
