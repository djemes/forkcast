package app.forkcast.debug

import app.forkcast.R
import app.forkcast.core.data.backup.BackupRepository
import app.forkcast.core.data.backup.ImportMode
import app.forkcast.core.data.repository.MealSlotRepository
import app.forkcast.core.data.repository.WeightRepository
import app.forkcast.di.DebugAction
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.first
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

/**
 * Settings › Advanced › Load demo data (debug builds only): [DemoData] merged in through the
 * backup import, so it lands in this phone's own meals like any restored backup would.
 */
class DemoDataAction @Inject constructor(
    private val backups: BackupRepository,
    private val meals: MealSlotRepository,
    private val weights: WeightRepository,
    private val clock: Clock,
) : DebugAction {
    override val label = R.string.debug_demo_data
    override val summary = R.string.debug_demo_data_summary

    override suspend fun run(): Int {
        val slots = meals.observeMealSlots().first()
        val endKg = weights.latestKg.first() ?: DEFAULT_KG
        backups.import(DemoData.build(LocalDate.now(clock), clock.zone, slots, endKg, clock.millis()), ImportMode.MERGE)
        return R.string.debug_demo_data_done
    }

    private companion object {
        /** Before onboarding's first weigh-in. */
        const val DEFAULT_KG = 80.0
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DemoDataModule {
    @Binds
    @IntoSet
    abstract fun demoData(action: DemoDataAction): DebugAction
}
