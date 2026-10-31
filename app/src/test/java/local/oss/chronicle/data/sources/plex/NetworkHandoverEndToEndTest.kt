package local.oss.chronicle.data.sources.plex

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import local.oss.chronicle.data.local.AccountRepository
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.data.model.MediaItemTrack
import local.oss.chronicle.data.model.ServerConnection
import local.oss.chronicle.data.sources.plex.model.Connection
import local.oss.chronicle.features.account.CredentialManager
import local.oss.chronicle.util.NetworkMonitor
import local.oss.chronicle.util.NetworkState
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * End-to-end regression test for the network-handover connection bug (Reddit threads
 * `1t5dgcm`, `1sqm7f2`, `1wudlnz`, `1wndqcy`, `1whgzn6`, `1vw0noo`, `1x57o97`, `1v902i7`;
 * GitHub #9): playback works on home Wi-Fi, the user leaves, audio dies when the buffer
 * drains and pressing Play does nothing because the app keeps trying the LAN URI it picked
 * at login time.
 *
 * Wires the REAL [ConnectionRefreshCoordinator], [ServerConnectionResolver] and
 * [PlaybackUrlResolver] together and fakes only the true edges:
 *  - the [NetworkMonitor] state flow (Android system service),
 *  - the [ConnectionProber] (actual network I/O),
 *  - the repositories / prefs (database),
 *  - the clock/dispatchers (test dispatcher).
 *
 * Given a library resolved to a LAN URI on Wi-Fi, emitting
 * `Connected(isWifi=false, isCellular=true)` and advancing past the debounce must re-probe
 * the library so it now resolves to the WAN URI, and must empty the playback URL caches.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class NetworkHandoverEndToEndTest {
    private val dispatcher = StandardTestDispatcher()

    private lateinit var networkState: MutableStateFlow<NetworkState>
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var accountRepository: AccountRepository
    private lateinit var credentialManager: CredentialManager
    private lateinit var plexConfig: PlexConfig
    private lateinit var plexPrefsRepo: PlexPrefsRepo
    private lateinit var connectionProber: ConnectionProber
    private lateinit var scopedPlexServiceFactory: ScopedPlexServiceFactory
    private lateinit var plexMediaService: PlexMediaService

    private lateinit var serverConnectionResolver: ServerConnectionResolver
    private lateinit var playbackUrlResolver: PlaybackUrlResolver
    private lateinit var coordinator: ConnectionRefreshCoordinator

    private val libraryId = "plex:library:42"
    private val lanUri = "http://192.168.1.100:32400"
    private val wanUri = "https://wan.plex.direct:32400"
    private val authToken = "valid-token"

    private val connections =
        listOf(
            Connection(uri = lanUri, local = true),
            Connection(uri = wanUri, local = false, relay = false),
        )

    @Before
    fun setUp() {
        networkState = MutableStateFlow(NetworkState.Unknown)
        networkMonitor = mockk(relaxed = true)
        every { networkMonitor.networkState } returns networkState
        every { networkMonitor.currentState } answers { networkState.value }

        libraryRepository = mockk(relaxed = true)
        accountRepository = mockk(relaxed = true)
        credentialManager = mockk(relaxed = true)
        plexConfig = mockk(relaxed = true)
        plexPrefsRepo = mockk(relaxed = true)
        connectionProber = mockk(relaxed = true)
        scopedPlexServiceFactory = mockk(relaxed = true)
        plexMediaService = mockk(relaxed = true)

        every { plexConfig.url } returns PlexConfig.PLACEHOLDER_URL
        every { plexPrefsRepo.server } returns null
        every { plexPrefsRepo.user } returns null
        every { plexPrefsRepo.accountAuthToken } returns ""

        serverConnectionResolver =
            ServerConnectionResolver(
                libraryRepository,
                accountRepository,
                credentialManager,
                plexConfig,
                plexPrefsRepo,
                connectionProber,
            )
        serverConnectionResolver.resetForTesting()
        playbackUrlResolver = PlaybackUrlResolver(serverConnectionResolver, scopedPlexServiceFactory)
        coordinator =
            ConnectionRefreshCoordinator(
                networkMonitor = networkMonitor,
                serverConnectionResolver = serverConnectionResolver,
                playbackUrlResolver = playbackUrlResolver,
                plexConfig = plexConfig,
                plexMediaService = plexMediaService,
                libraryRepository = libraryRepository,
            )

        MediaItemTrack.streamingUrlCache.clear()
    }

    @After
    fun tearDown() {
        MediaItemTrack.streamingUrlCache.clear()
    }

    private fun libraryAt(
        chosenUri: String,
        checkedAt: Long,
    ) = Library(
        id = libraryId,
        accountId = "plex:account:abc",
        serverId = "server-1",
        serverName = "Test Server",
        name = "Audiobooks",
        type = "artist",
        lastSyncedAt = null,
        itemCount = 0,
        isActive = true,
        serverUrl = chosenUri,
        authToken = authToken,
        connections = connections,
        chosenConnectionUri = chosenUri,
        lastConnectionCheckAt = checkedAt,
    )

    /**
     * Library row states this test walks through. The resolver persists probe winners back to
     * the "DB" by updating [state], so subsequent resolves see exactly what a real row would.
     */
    private var state: Library = libraryAt(lanUri, checkedAt = 0L)

    private fun wireRepositoryToState() {
        coEvery { libraryRepository.getLibraryById(libraryId) } answers { state }
        coEvery { libraryRepository.getServerConnection(libraryId) } answers {
            ServerConnection(state.serverUrl, authToken)
        }
        coEvery { libraryRepository.getAllLibrariesSnapshot() } answers { listOf(state) }
        coEvery { libraryRepository.updateLibrary(any()) } answers { state = firstArg() }
    }

    @Test
    fun `wifi to cellular handover re-probes the library to the WAN URI and clears playback URL caches`() =
        runTest(dispatcher) {
            // Given: app on Wi-Fi, library resolved to the LAN URI (probe picks LAN on Wi-Fi).
            wireRepositoryToState()
            coEvery { connectionProber.probe(connections, authToken) } returns lanUri
            networkState.value = NetworkState.Connected(isWifi = true, isCellular = false, isMetered = false)

            coordinator.start(CoroutineScope(dispatcher))
            advanceTimeBy(ConnectionRefreshCoordinator.DEBOUNCE_MS + 500)
            advanceUntilIdle()

            // When: the user starts playback on Wi-Fi — resolve populates the caches, LAN wins.
            val onWifi = serverConnectionResolver.resolve(libraryId)
            assertThat(onWifi.serverUrl).isEqualTo(lanUri)
            coVerify(exactly = 1) { connectionProber.probe(any(), any()) }

            // And: streaming URL caches hold entries built against the LAN connection.
            MediaItemTrack.streamingUrlCache["plex:track:1"] = "$lanUri/library/parts/1/file.mp3"
            assertThat(MediaItemTrack.streamingUrlCache).isNotEmpty()

            // When: the user leaves the house — Wi-Fi drops, cellular takes over, and the LAN
            // URI is no longer reachable so the prober now picks the WAN URI.
            coEvery { connectionProber.probe(connections, authToken) } returns wanUri
            networkState.value = NetworkState.Connected(isWifi = false, isCellular = true, isMetered = true)
            advanceTimeBy(ConnectionRefreshCoordinator.DEBOUNCE_MS + 500)
            advanceUntilIdle()

            // Then: the refresh INVALIDATED the resolver (not just eagerly re-resolved): a
            // brand-new library the coordinator never saw — first resolved only now, with a
            // fresh-TTL stored LAN choice — is still forced through exactly one re-probe and
            // comes back on the WAN URI. Reverting the coordinator -> resolver invalidate(null)
            // wiring breaks this: without the global invalidation, the fresh TTL lets the stale
            // DB connection be served un-probed.
            val newLibraryId = "plex:library:777"
            state = libraryAt(lanUri, checkedAt = System.currentTimeMillis()).copy(id = newLibraryId)
            coEvery { libraryRepository.getLibraryById(newLibraryId) } answers { state }
            coEvery { libraryRepository.getServerConnection(newLibraryId) } answers {
                ServerConnection(state.serverUrl, authToken)
            }
            coEvery { libraryRepository.updateLibrary(any()) } answers { state = firstArg() }

            val afterHandover = serverConnectionResolver.resolve(newLibraryId)
            coVerify(exactly = 3) { connectionProber.probe(any(), any()) }
            assertThat(afterHandover.serverUrl).isEqualTo(wanUri)

            // And: the playback URL caches were emptied by the refresh so no stale LAN URL can
            // be used for the next play attempt.
            assertThat(MediaItemTrack.streamingUrlCache).isEmpty()
        }
}
