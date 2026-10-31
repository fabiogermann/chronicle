package local.oss.chronicle.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowConnectivityManager
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

/**
 * Behavioural tests for [NetworkMonitor]'s handling of network loss during a Wi-Fi → cellular
 * handover (Reddit "playback dies when leaving home Wi-Fi" reports).
 *
 * Uses Robolectric's [ShadowConnectivityManager]: the monitor registers a callback for EVERY
 * INTERNET-capable network, so `onLost` fires for whichever network went away — including
 * Wi-Fi while cellular stays up.
 *
 * The bug: `onLost` used to set `Disconnected` unconditionally. With mobile data already
 * active, losing Wi-Fi therefore produced `Connected(wifi) → Disconnected`, and since cellular
 * never fired a NEW `onAvailable` (it was already up), the state stayed `Disconnected` — the
 * ConnectionRefreshCoordinator never ran a refresh and playback kept the dead LAN URL.
 *
 * Shadow model (4.17): `activeNetwork` is derived from `activeNetworkInfo.type` via a
 * netId → Network table, and `addNetwork(network, info)` registers a Network under its OWN
 * netId — so the tests use netIds that match the network TYPE constants (TYPE_WIFI = 1,
 * TYPE_MOBILE = 0), which are the ids `setActiveNetworkInfo` looks up.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class NetworkMonitorCallbackTest {
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var shadowConnectivityManager: ShadowConnectivityManager

    private val wifiNetwork: Network = ShadowNetwork.newInstance(ConnectivityManager.TYPE_WIFI)
    private val cellularNetwork: Network = ShadowNetwork.newInstance(ConnectivityManager.TYPE_MOBILE)

    private fun capabilities(vararg transports: Int): NetworkCapabilities {
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadow.extract<ShadowNetworkCapabilities>(caps).apply {
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            transports.forEach { addTransportType(it) }
        }
        return caps
    }

    private fun connectedInfo(type: Int): NetworkInfo =
        ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            type,
            0,
            true,
            true,
        )

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowConnectivityManager = Shadow.extract(connectivityManager)
        shadowConnectivityManager.clearAllNetworks()
        shadowConnectivityManager.setNetworkCallbacksEnabled(true)
    }

    private fun fireOnLost(network: Network) {
        shadowConnectivityManager.networkCallbacks.forEach { it.onLost(network) }
    }

    @Test
    fun `losing Wi-Fi while cellular stays up yields Connected(cellular), not Disconnected`() {
        // Given: Wi-Fi is the active network, cellular also up (typical handover precondition).
        shadowConnectivityManager.addNetwork(wifiNetwork, connectedInfo(ConnectivityManager.TYPE_WIFI))
        shadowConnectivityManager.addNetwork(cellularNetwork, connectedInfo(ConnectivityManager.TYPE_MOBILE))
        shadowConnectivityManager.setNetworkCapabilities(
            wifiNetwork,
            capabilities(NetworkCapabilities.TRANSPORT_WIFI),
        )
        shadowConnectivityManager.setNetworkCapabilities(
            cellularNetwork,
            capabilities(NetworkCapabilities.TRANSPORT_CELLULAR),
        )
        shadowConnectivityManager.setActiveNetworkInfo(connectedInfo(ConnectivityManager.TYPE_WIFI))

        val monitor = NetworkMonitor(ApplicationProvider.getApplicationContext())
        val initial = monitor.currentState
        assertThat(initial).isInstanceOf(NetworkState.Connected::class.java)
        assertThat((initial as NetworkState.Connected).isWifi).isTrue()

        // When: Wi-Fi is lost and the platform has already switched the default network to
        // cellular (post-transition delivery of onLost).
        shadowConnectivityManager.removeNetwork(wifiNetwork)
        shadowConnectivityManager.setActiveNetworkInfo(connectedInfo(ConnectivityManager.TYPE_MOBILE))
        fireOnLost(wifiNetwork)

        // Then: the monitor must report cellular connectivity, NOT Disconnected — otherwise the
        // coordinator's Connected->Connected filter swallows the WiFi->Cellular transition and
        // no connection refresh fires.
        val state = monitor.currentState
        assertThat(state).isInstanceOf(NetworkState.Connected::class.java)
        assertThat((state as NetworkState.Connected).isCellular).isTrue()
        assertThat(state.isWifi).isFalse()
    }

    @Test
    fun `losing the only network yields Disconnected`() {
        shadowConnectivityManager.addNetwork(wifiNetwork, connectedInfo(ConnectivityManager.TYPE_WIFI))
        shadowConnectivityManager.setNetworkCapabilities(
            wifiNetwork,
            capabilities(NetworkCapabilities.TRANSPORT_WIFI),
        )
        shadowConnectivityManager.setActiveNetworkInfo(connectedInfo(ConnectivityManager.TYPE_WIFI))

        val monitor = NetworkMonitor(ApplicationProvider.getApplicationContext())
        assertThat(monitor.currentState).isInstanceOf(NetworkState.Connected::class.java)

        // Wi-Fi goes away and nothing replaces it (activeNetwork -> null).
        shadowConnectivityManager.removeNetwork(wifiNetwork)
        shadowConnectivityManager.setActiveNetworkInfo(null)
        fireOnLost(wifiNetwork)

        assertThat(monitor.currentState).isEqualTo(NetworkState.Disconnected)
    }

    @Test
    fun `onLost while the lost network is still reported active does NOT resurrect it`() {
        // The platform race this pins: ConnectivityManager can deliver onLost(wifi) BEFORE
        // updating the default network, so activeNetwork still returns the dead Wi-Fi network
        // inside the callback. Deriving state from it would emit a bogus Connected(wifi) and
        // the coordinator would see no transition at all.
        shadowConnectivityManager.addNetwork(wifiNetwork, connectedInfo(ConnectivityManager.TYPE_WIFI))
        shadowConnectivityManager.setNetworkCapabilities(
            wifiNetwork,
            capabilities(NetworkCapabilities.TRANSPORT_WIFI),
        )
        shadowConnectivityManager.setActiveNetworkInfo(connectedInfo(ConnectivityManager.TYPE_WIFI))

        val monitor = NetworkMonitor(ApplicationProvider.getApplicationContext())
        assertThat(monitor.currentState).isInstanceOf(NetworkState.Connected::class.java)

        // When: Wi-Fi is lost but the shadow still reports Wi-Fi as the active network (the
        // transient the platform exhibits between onLost and the default-network switch).
        fireOnLost(wifiNetwork)

        // Then: the monitor must NOT trust the stale active network — it reports Disconnected
        // rather than resurrecting the dead Wi-Fi network as Connected.
        assertThat(monitor.currentState).isEqualTo(NetworkState.Disconnected)
    }
}
