package local.oss.chronicle.data.sources.plex

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.sources.plex.model.Connection
import local.oss.chronicle.data.sources.plex.model.PlexMediaContainer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.net.SocketTimeoutException
import javax.inject.Provider

/**
 * Tier-aware probing tests for [ConnectionProber].
 *
 * Fix #4: the prober must:
 *  - Race candidates within a tier (LAN, direct WAN, relay) in parallel.
 *  - Only open a lower tier if every candidate in the previous tier failed.
 *  - Pick the highest-tier reachable URI, preferring LAN > direct WAN > relay.
 *  - Return `null` if every tier exhausts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionProberTest {
    private lateinit var plexMediaService: PlexMediaService
    private lateinit var prober: ConnectionProber

    private val lanUri = "http://192.168.1.100:32400"
    private val wanUri = "https://5-6-7-8.abcdef.plex.direct:32400"
    private val relayUri = "https://1-2-3-4.abcdef.plex.direct:8443"
    private val authToken = "tok"

    private val lan = Connection(uri = lanUri, local = true, relay = false)
    private val wan = Connection(uri = wanUri, local = false, relay = false)
    private val relay = Connection(uri = relayUri, local = false, relay = true)

    private val ok: Response<PlexMediaContainer> get() = Response.success(mockk<PlexMediaContainer>(relaxed = true))

    private fun httpError(code: Int) =
        Response.error<PlexMediaContainer>(code, "err".toResponseBody("text/plain".toMediaType()))

    @Before
    fun setup() {
        plexMediaService = mockk(relaxed = true)
        val provider = Provider { plexMediaService }
        prober = ConnectionProber(provider)
    }

    @Test
    fun `returns null for empty connection list`() =
        runTest {
            assertThat(prober.probe(emptyList(), authToken)).isNull()
        }

    @Test
    fun `prefers LAN when LAN, WAN, and relay all reachable`() =
        runTest {
            coEvery { plexMediaService.checkServer(any()) } returns ok

            val winner = prober.probe(listOf(relay, wan, lan), authToken)

            assertThat(winner).isEqualTo(lanUri)
        }

    @Test
    fun `prefers direct WAN over relay when LAN unreachable`() =
        runTest {
            coEvery { plexMediaService.checkServer(lanUri) } throws SocketTimeoutException("down")
            coEvery { plexMediaService.checkServer(wanUri) } returns ok
            coEvery { plexMediaService.checkServer(relayUri) } returns ok

            val winner = prober.probe(listOf(lan, wan, relay), authToken)

            assertThat(winner).isEqualTo(wanUri)
        }

    @Test
    fun `does not probe relay tier when direct WAN tier already succeeded`() =
        runTest {
            coEvery { plexMediaService.checkServer(lanUri) } throws SocketTimeoutException("down")
            coEvery { plexMediaService.checkServer(wanUri) } returns ok
            coEvery { plexMediaService.checkServer(relayUri) } returns ok

            prober.probe(listOf(lan, wan, relay), authToken)

            // Relay must never be hit when WAN was reachable.
            coVerify(exactly = 0) { plexMediaService.checkServer(relayUri) }
        }

    @Test
    fun `falls back to relay when LAN and direct WAN both unreachable`() =
        runTest {
            coEvery { plexMediaService.checkServer(lanUri) } throws SocketTimeoutException("LAN down")
            coEvery { plexMediaService.checkServer(wanUri) } throws SocketTimeoutException("WAN down")
            coEvery { plexMediaService.checkServer(relayUri) } returns ok

            val winner = prober.probe(listOf(lan, wan, relay), authToken)

            assertThat(winner).isEqualTo(relayUri)
            // All three tiers were probed in order.
            coVerify(exactly = 1) { plexMediaService.checkServer(lanUri) }
            coVerify(exactly = 1) { plexMediaService.checkServer(wanUri) }
            coVerify(exactly = 1) { plexMediaService.checkServer(relayUri) }
        }

    @Test
    fun `returns null when every tier fails`() =
        runTest {
            coEvery { plexMediaService.checkServer(any()) } throws SocketTimeoutException("down")

            val winner = prober.probe(listOf(lan, wan, relay), authToken)

            assertThat(winner).isNull()
        }

    @Test
    fun `treats HTTP error response as failure and falls through to next tier`() =
        runTest {
            coEvery { plexMediaService.checkServer(lanUri) } returns httpError(500)
            coEvery { plexMediaService.checkServer(wanUri) } returns ok

            val winner = prober.probe(listOf(lan, wan), authToken)

            assertThat(winner).isEqualTo(wanUri)
        }

    @Test
    fun `handles a connection list reordered by caller (priority is intrinsic)`() =
        runTest {
            // Caller may persist the list in any order; the prober groups by tier internally.
            coEvery { plexMediaService.checkServer(any()) } returns ok

            val winner = prober.probe(listOf(relay, wan, lan), authToken)

            assertThat(winner).isEqualTo(lanUri)
        }

    @Test
    fun `legacy connections without relay flag are treated as direct WAN`() =
        runTest {
            // Simulates fix #1 persisted JSON shape: only `uri` + `local` set; defaults the rest.
            val legacyLan = Connection(uri = lanUri, local = true)
            val legacyWan = Connection(uri = wanUri, local = false) // relay defaults to false
            coEvery { plexMediaService.checkServer(lanUri) } throws SocketTimeoutException("down")
            coEvery { plexMediaService.checkServer(wanUri) } returns ok

            val winner = prober.probe(listOf(legacyLan, legacyWan), authToken)

            assertThat(winner).isEqualTo(wanUri)
            // Sanity: legacy WAN defaulted relay=false, so it ranked as direct WAN.
            assertThat(legacyWan.priority).isEqualTo(Connection.PRIORITY_DIRECT_WAN)
        }
}
