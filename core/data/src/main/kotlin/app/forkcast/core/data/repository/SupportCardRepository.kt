package app.forkcast.core.data.repository

import app.forkcast.core.data.settings.DevicePrefs
import app.forkcast.core.data.settings.DeviceState
import app.forkcast.core.domain.SupportCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the Ko-fi card shows at the top of Settings (owner, 2026-10-30). Kept in [DevicePrefs]:
 * this phone only, never in backups.
 */
interface SupportCardRepository {
    /** True while the card shows for the installed [versionName]. */
    fun shown(versionName: String): Flow<Boolean>

    /** [shown]'s current value, so the screen opens with the card already in place. */
    fun isShown(versionName: String): Boolean

    /** Hides it for [SupportCard.HIDDEN_FOR], or until a later major version than [versionName] is installed. */
    suspend fun hide(versionName: String)

    /** Undo for [hide]. */
    suspend fun show()
}

@Singleton
class DefaultSupportCardRepository @Inject constructor(
    private val device: DevicePrefs,
    private val clock: Clock,
) : SupportCardRepository {

    override fun shown(versionName: String): Flow<Boolean> =
        device.state.map { isShown(it, versionName) }.distinctUntilChanged()

    override fun isShown(versionName: String): Boolean = isShown(device.state.value, versionName)

    private fun isShown(state: DeviceState, versionName: String): Boolean =
        SupportCard.isShown(state.supportCardHiddenAtMajor, state.supportCardHiddenAt, versionName, clock.millis())

    override suspend fun hide(versionName: String) {
        device.update { it.copy(supportCardHiddenAtMajor = SupportCard.major(versionName), supportCardHiddenAt = clock.millis()) }
    }

    override suspend fun show() {
        device.update { it.copy(supportCardHiddenAtMajor = null, supportCardHiddenAt = null) }
    }
}
