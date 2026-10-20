package app.forkcast.core.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.forkcast.core.network.NetworkWaiter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [NetworkWaiter] backed by ConnectivityManager: returns at once when the default network has
 * validated internet, otherwise waits for one (e.g. while the phone moves between Wi-Fi and
 * mobile data). Never throws; any platform error counts as "go ahead and retry".
 */
class ConnectivityNetworkWaiter(context: Context) : NetworkWaiter {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    override suspend fun awaitOnline(timeoutMs: Long): Boolean {
        val manager = cm ?: return true
        if (isOnline(manager.getNetworkCapabilities(manager.activeNetwork))) return true
        val online = CompletableDeferred<Unit>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (isOnline(caps)) online.complete(Unit)
            }
        }
        return try {
            manager.registerDefaultNetworkCallback(callback)
            withTimeoutOrNull(timeoutMs) { online.await() } != null
        } catch (e: RuntimeException) {
            true
        } finally {
            runCatching { manager.unregisterNetworkCallback(callback) }
        }
    }

    private fun isOnline(caps: NetworkCapabilities?): Boolean =
        caps != null &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
