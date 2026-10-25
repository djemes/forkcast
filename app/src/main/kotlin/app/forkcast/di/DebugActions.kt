package app.forkcast.di

import androidx.annotation.StringRes
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/**
 * A developer tool shown under Settings › Advanced. Only debug builds bind any (src/debug, e.g.
 * "Load demo data"); release builds get the empty set, so no tool code ships in them.
 */
interface DebugAction {
    @get:StringRes val label: Int
    @get:StringRes val summary: Int

    /** Does the work; returns the message to show when it's done (a string resource). */
    suspend fun run(): Int
}

@Module
@InstallIn(SingletonComponent::class)
abstract class DebugActionsModule {
    /** Declared so the set exists, empty, in builds that bind none. */
    @Multibinds
    abstract fun debugActions(): Set<DebugAction>
}
