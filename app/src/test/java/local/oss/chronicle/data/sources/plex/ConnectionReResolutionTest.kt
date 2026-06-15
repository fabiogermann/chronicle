package local.oss.chronicle.data.sources.plex

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.local.AccountRepository
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.data.model.ServerConnection
import local.oss.chronicle.data.sources.plex.model.Connection
import local.oss.chronicle.data.sources.plex.model.PlexMediaContainer
import local.oss.chronicle.features.account.CredentialManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.net.SocketTimeoutException

/**
 * Tests for the off-network playback fix.
 *
 * Validates that [ServerConnectionResolver]:
 *  - probes the persisted [Connection] list at resolve time when the cached choice is stale,
 *  - picks a reachable URI (e.g. WAN) when the previously chosen LAN URI is unreachable,
 *  - persists the chosen URI back to the [Library] row,
 *  - caches the result for a TTL to avoid thundering herd,
 *  - exposes invalidate() and reResolve() seams for network/playback recovery.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionReResolutionTest {
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var accountRepository: AccountRepository
    private lateinit var credentialManager: CredentialManager
    private lateinit var plexConfig: PlexConfig
    private lateinit var plexPrefsRepo: PlexPrefsRepo
    private lateinit var connectionProber: ConnectionProber
    private lateinit var resolver: ServerConnectionResolver

    private val libraryId = "plex:library:42"
    private val accountId = "plex:account:abc"
    private val lanUri = "http://192.168.1.100:32400"
    private val wanUri = "https://wan.plex.direct:32400"
    private val relayUri = "https://relay.plex.tv:443"
    private val authToken = "valid-token"

    private val library =
        Library(
            id = libraryId,
            accountId = accountId,
            serverId = "server-1",
            serverName = "Test Server",
            name = "Audiobooks",
            type = "artist",
            lastSyncedAt = null,
            itemCount = 0,
            isActive = true,
            serverUrl = lanUri,
            authToken = authToken,
            connections =
                listOf(
                    Connection(uri = lanUri, local = true),
                    Connection(uri = wanUri, local = false, relay = false),
                    Connection(uri = relayUri, local = false, relay = true),
                ),
            chosenConnectionUri = lanUri,
            lastConnectionCheckAt = null,
        )

    @Before
    fun setup() {
        libraryRepository = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        credentialManager = mockk(relaxed = true)
        plexConfig = mockk(relaxed = true)
        plexPrefsRepo = mockk(relaxed = true)
        connectionProber = mockk(relaxed = true)

        coEvery { plexConfig.url } returns PlexConfig.PLACEHOLDER_URL
        coEvery { plexPrefsRepo.server } returns null
        coEvery { plexPrefsRepo.user } returns null
        coEvery { plexPrefsRepo.accountAuthToken } returns ""

        resolver =
            ServerConnectionResolver(
                libraryRepository,
                accountRepository,
                credentialManager,
                plexConfig,
                plexPrefsRepo,
                connectionProber,
            )
    }

    @Test
    fun `resolve re-probes and returns WAN URI when LAN is unreachable`() =
        runTest {
            // Given: A library with [LAN, WAN, RELAY] connections persisted, chosen=LAN, but stale
            coEvery { libraryRepository.getLibraryById(libraryId) } returns library
            coEvery { libraryRepository.getServerConnection(libraryId) } returns
                ServerConnection(lanUri, authToken)

            // The prober (probing the persisted list) finds WAN reachable, LAN unreachable
            coEvery {
                connectionProber.probe(library.connections!!, authToken)
            } returns wanUri

            // When: Resolving the library connection
            val result = resolver.resolve(libraryId)

            // Then: The WAN URI is returned (prober ran, LAN was discarded)
            assertThat(result.serverUrl).isEqualTo(wanUri)
            assertThat(result.authToken).isEqualTo(authToken)
            // And: The library row was updated so future calls see the new chosen URI
            coVerify { libraryRepository.updateLibrary(match { it.chosenConnectionUri == wanUri }) }
        }

    @Test
    fun `resolve re-probes when cached connection check is stale`() =
        runTest {
            // Given: A library where the last connection check is older than the TTL
            val stale =
                library.copy(
                    lastConnectionCheckAt = System.currentTimeMillis() - (60 * 60 * 1000L),
                )
            coEvery { libraryRepository.getLibraryById(libraryId) } returns stale
            coEvery { libraryRepository.getServerConnection(libraryId) } returns
                ServerConnection(lanUri, authToken)
            coEvery { connectionProber.probe(stale.connections!!, authToken) } returns wanUri

            // When: Resolving
            val result = resolver.resolve(libraryId)

            // Then: Prober was called and WAN selected
            coVerify(exactly = 1) { connectionProber.probe(stale.connections!!, authToken) }
            assertThat(result.serverUrl).isEqualTo(wanUri)
        }

    @Test
    fun `resolve does not re-probe within TTL once a successful check is recorded`() =
        runTest {
            // Given: A library whose last successful check is recent (within TTL)
            val fresh =
                library.copy(
                    chosenConnectionUri = wanUri,
                    serverUrl = wanUri,
                    lastConnectionCheckAt = System.currentTimeMillis() - 1000L, // 1 second ago
                )
            coEvery { libraryRepository.getLibraryById(libraryId) } returns fresh
            coEvery { libraryRepository.getServerConnection(libraryId) } returns
                ServerConnection(wanUri, authToken)

            // When: Resolving twice in quick succession
            val r1 = resolver.resolve(libraryId)
            val r2 = resolver.resolve(libraryId)

            // Then: Prober is NOT called (cache + DB both fresh)
            coVerify(exactly = 0) { connectionProber.probe(any(), any()) }
            assertThat(r1.serverUrl).isEqualTo(wanUri)
            assertThat(r2.serverUrl).isEqualTo(wanUri)
        }

    @Test
    fun `reResolve always re-probes regardless of TTL`() =
        runTest {
            // Given: A freshly-checked library (would not normally probe)
            val fresh =
                library.copy(
                    chosenConnectionUri = lanUri,
                    serverUrl = lanUri,
                    lastConnectionCheckAt = System.currentTimeMillis(),
                )
            coEvery { libraryRepository.getLibraryById(libraryId) } returns fresh
            coEvery { libraryRepository.getServerConnection(libraryId) } returns
                ServerConnection(lanUri, authToken)
            coEvery { connectionProber.probe(fresh.connections!!, authToken) } returns wanUri

            // When: Caller explicitly requests reResolve (e.g. from MediaPlayerService.onPlayerError)
            val result = resolver.reResolve(libraryId)

            // Then: Prober is called, new URI returned
            coVerify(exactly = 1) { connectionProber.probe(fresh.connections!!, authToken) }
            assertThat(result.serverUrl).isEqualTo(wanUri)
        }

    @Test
    fun `invalidate forces next resolve to re-probe`() =
        runTest {
            // Given: A fresh library (cache populated)
            val fresh =
                library.copy(
                    chosenConnectionUri = lanUri,
                    serverUrl = lanUri,
                    lastConnectionCheckAt = System.currentTimeMillis(),
                )
            coEvery { libraryRepository.getLibraryById(libraryId) } returns fresh
            coEvery { libraryRepository.getServerConnection(libraryId) } returns
                ServerConnection(lanUri, authToken)
            coEvery { connectionProber.probe(any(), any()) } returns wanUri

            // Prime the cache
            resolver.resolve(libraryId)
            coVerify(exactly = 0) { connectionProber.probe(any(), any()) }

            // When: invalidate is called (e.g. from NetworkMonitor on network change)
            resolver.invalidate(libraryId)
            val result = resolver.resolve(libraryId)

            // Then: Prober runs on the next resolve
            coVerify(exactly = 1) { connectionProber.probe(any(), any()) }
            assertThat(result.serverUrl).isEqualTo(wanUri)
        }

    @Test
    fun `resolve falls back to legacy serverUrl when no connection list is persisted`() =
        runTest {
            // Given: A legacy library row (no connections list yet) but with serverUrl set
            val legacy =
                library.copy(
                    connections = null,
                    chosenConnectionUri = null,
                    lastConnectionCheckAt = null,
                )
            coEvery { libraryRepository.getLibraryById(libraryId) } returns legacy
            coEvery { libraryRepository.getServerConnection(libraryId) } returns
                ServerConnection(lanUri, authToken)

            // When: Resolving
            val result = resolver.resolve(libraryId)

            // Then: Legacy serverUrl is used and prober is NOT called
            coVerify(exactly = 0) { connectionProber.probe(any(), any()) }
            assertThat(result.serverUrl).isEqualTo(lanUri)
            assertThat(result.authToken).isEqualTo(authToken)
        }

    @Test
    fun `resolve caches the probed result and does not re-probe on subsequent calls within TTL`() =
        runTest {
            // Given: A stale library triggering one probe
            val stale =
                library.copy(
                    lastConnectionCheckAt = System.currentTimeMillis() - (60 * 60 * 1000L),
                )
            coEvery { libraryRepository.getLibraryById(libraryId) } returns stale
            coEvery { libraryRepository.getServerConnection(libraryId) } returns
                ServerConnection(lanUri, authToken)
            coEvery { connectionProber.probe(any(), any()) } returns wanUri

            // When: Resolving multiple times
            val r1 = resolver.resolve(libraryId)
            val r2 = resolver.resolve(libraryId)
            val r3 = resolver.resolve(libraryId)

            // Then: Prober is invoked only ONCE (thundering herd prevention)
            coVerify(exactly = 1) { connectionProber.probe(any(), any()) }
            assertThat(r1.serverUrl).isEqualTo(wanUri)
            assertThat(r2.serverUrl).isEqualTo(wanUri)
            assertThat(r3.serverUrl).isEqualTo(wanUri)
        }

    // -----------------------------------------------------------------------------------------
    // Fix #4: relay deprioritization through the resolver
    //
    // The resolver delegates to ConnectionProber, which is responsible for tier-aware probing.
    // From the resolver's perspective we only need to verify the prober's chosen URI flows
    // through to the persisted Library row exactly as it does for LAN/WAN.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `resolve picks relay URI and persists it when prober returns relay as last resort`() =
        runTest {
            // Given: A library with LAN + WAN + Relay; LAN and WAN unreachable per the prober,
            // so the prober returns the relay URI.
            val stale =
                library.copy(
                    lastConnectionCheckAt = System.currentTimeMillis() - (60 * 60 * 1000L),
                )
            coEvery { libraryRepository.getLibraryById(libraryId) } returns stale
            coEvery { libraryRepository.getServerConnection(libraryId) } returns
                ServerConnection(lanUri, authToken)
            coEvery {
                connectionProber.probe(stale.connections!!, authToken)
            } returns relayUri

            // When: Resolving
            val result = resolver.resolve(libraryId)

            // Then: Relay URI is selected and persisted to the Library row.
            assertThat(result.serverUrl).isEqualTo(relayUri)
            assertThat(result.authToken).isEqualTo(authToken)
            coVerify { libraryRepository.updateLibrary(match { it.chosenConnectionUri == relayUri }) }
        }

    @Test
    fun `resolve handles legacy-shaped persisted connections without relay field`() =
        runTest {
            // Given: A library row whose `connections` JSON was written by fix #1, i.e. only
            // `uri` + `local`. Fix #4 added `relay`/`protocol`/etc. with defaults; legacy rows
            // must still load and the picker should treat them as direct WAN (relay = false).
            val legacyConnections =
                listOf(
                    // These are constructed with only uri/local, matching what the legacy JSON
                    // deserializes into. The defaulted `relay` field is false.
                    Connection(uri = lanUri, local = true),
                    Connection(uri = wanUri, local = false),
                )
            val stale =
                library.copy(
                    connections = legacyConnections,
                    lastConnectionCheckAt = System.currentTimeMillis() - (60 * 60 * 1000L),
                )
            coEvery { libraryRepository.getLibraryById(libraryId) } returns stale
            coEvery { libraryRepository.getServerConnection(libraryId) } returns
                ServerConnection(lanUri, authToken)
            coEvery { connectionProber.probe(legacyConnections, authToken) } returns wanUri

            // When
            val result = resolver.resolve(libraryId)

            // Then: probe was called with the legacy list (relay defaulted to false; no crash),
            // and the picker selected the WAN URI without surprises.
            coVerify(exactly = 1) { connectionProber.probe(legacyConnections, authToken) }
            assertThat(result.serverUrl).isEqualTo(wanUri)
            // Sanity: the legacy connections have relay=false by default.
            assertThat(legacyConnections.all { !it.relay }).isTrue()
        }
}
