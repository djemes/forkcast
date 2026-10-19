package app.forkcast.core.data.notify

import app.forkcast.core.data.repository.LogRepository
import app.forkcast.core.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/** Wires notifications up for the app's lifetime (called once from the Application). */
@Singleton
class NotificationStarter @Inject constructor(
    private val engine: NotificationEngine,
    private val scheduler: ReminderScheduler,
    private val log: LogRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        // Budget thresholds: re-check whenever today's log changes (not on app start).
        scope.launch {
            today().flatMapLatest { day -> log.observeRange(day, day).map { list -> list.sumOf { it.nutrients.values.sum() } } }
                .distinctUntilChanged()
                .drop(1)
                .collect { runCatching { engine.checkBudget() } }
        }
        // Reminders: replan whenever notification settings change.
        scope.launch {
            settings.settings.map { it.notifications }.distinctUntilChanged().collect { runCatching { scheduler.scheduleNext() } }
        }
    }

    private fun today() = flow {
        while (true) {
            emit(LocalDate.now(clock))
            delay(60_000)
        }
    }.distinctUntilChanged()
}
