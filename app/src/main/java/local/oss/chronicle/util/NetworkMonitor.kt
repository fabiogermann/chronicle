package local.oss.chronicle.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network state representation.
 */
sealed class NetworkState {
    /** Network is available and connected */
    data class Connected(
        val isWifi: Boolean = false,
        val isCellular: Boolean = false,
        val isMetered: Boolean = true,
    ) : NetworkState()

    /** Network is not available */
    data object Disconnected : NetworkState()

    /** Network state is unknown (e.g., during initialization) */
    data object Unknown : NetworkState()
}

/**
 * Monitors network connectivity state and exposes it as a StateFlow.
 *
 * Usage:
 * ```
 * networkMonitor.networkState.collect { state ->
 *     when (state) {
 *         is NetworkState.Connected -> handleConnected()
 *         is NetworkState.Disconnected -> handleDisconnected()
 *         is NetworkState.Unknown -> handleUnknown()
 *     }
 * }
 * ```
 */
@Singleton
class NetworkMonitor
    @Inject
    constructor(
        private val context: Context,
    ) {
        private val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        private val _networkState = MutableStateFlow<NetworkState>(NetworkState.Unknown)

        /** Current network state as a StateFlow for observing changes */
        val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

        /** Current network state (non-reactive, for one-time checks) */
        val currentState: NetworkState get() = _networkState.value

        /** Whether the network is currently connected */
        val isConnected: Boolean
            get() = _networkState.value is NetworkState.Connected

        private val networkCallback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    updateNetworkState()
                }

                override fun onLost(network: Network) {
                    _networkState.value = NetworkState.Disconnected
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities,
                ) {
                    updateNetworkState()
                }
            }

        init {
            // Get initial state
            updateNetworkState()

            // Register for updates
            val request =
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
        }

        private fun updateNetworkState() {
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities =
                activeNetwork?.let {
                    connectivityManager.getNetworkCapabilities(it)
                }

            _networkState.value =
                if (capabilities != null &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    NetworkState.Connected(
                        isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                        isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
                        isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
                    )
                } else {
                    NetworkState.Disconnected
                }
        }

        /**
         * Returns a Flow that emits when network becomes available.
         * Useful for triggering retry operations when network is restored.
         */
        fun awaitNetworkAvailable(): Flow<NetworkState.Connected> =
            callbackFlow {
                val callback =
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            val state = _networkState.value
                            if (state is NetworkState.Connected) {
                                trySend(state)
                            }
                        }
                    }

                val request =
                    NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                connectivityManager.registerNetworkCallback(request, callback)

                awaitClose {
                    connectivityManager.unregisterNetworkCallback(callback)
                }
            }

        /**
         * Clean up resources. Call when the monitor is no longer needed.
         */
        fun cleanup() {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (e: IllegalArgumentException) {
                // Callback was not registered, ignore
            }
        }
    }
