package app.forkcast.core.network

/**
 * Lets a retry wait out a network switch (Wi-Fi ↔ mobile data, a new Wi-Fi address) instead of
 * failing with "No connection" while the phone is still online. Found on the owner's phone
 * 2026-10-19: Samsung's "switch to mobile data" moved traffic mid-request, and the next tap worked.
 * The Android implementation watches ConnectivityManager; tests and the JVM use [None].
 */
fun interface NetworkWaiter {
    /** Suspends until a validated internet connection exists or [timeoutMs] passes; true if online. */
    suspend fun awaitOnline(timeoutMs: Long): Boolean

    companion object {
        val None = NetworkWaiter { true }

        /** How long a retry waits for the network to come back before giving up. */
        const val WAIT_MS = 10_000L
    }
}
