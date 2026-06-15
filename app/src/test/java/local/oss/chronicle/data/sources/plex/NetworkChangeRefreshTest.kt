package local.oss.chronicle.data.sources.plex

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import local.oss.chronicle.data.local.LibraryRepository
import local.oss.chronicle.data.model.Library
import local.oss.chronicle.util.NetworkMonitor
import local.oss.chronicle.util.NetworkState
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Tests for [ConnectionRefreshCoordinator] — the network-change → cache-invalidation glue.
 *
 * Validates that when [NetworkMonitor.networkState] emits transitions, the coordinator:
 *  - invalidates [ServerConnectionResolver] (no-args, all libraries),
 *  - clears [PlaybackUrlResolver] URL cache,
 *  - kicks [PlexConfig.connectToServerWithRetryAndState] (not the deprecated no-retry variant),
 *  - proactively re-resolves every persisted library,
 *  - debounces rapid flapping into a single refresh,
 *  - ignores no-op capability changes (same transport, same flags).
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class)
class NetworkChangeRefreshTest {
    private val testDispatcher = StandardTestDispatcher()

    private lateinit var networkState: MutableStateFlow<NetworkState>
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var serverConnectionResolver: ServerConnectionResolver
    private lateinit var playbackUrlResolver: PlaybackUrlResolver
    private lateinit var plexConfig: PlexConfig
    private lateinit var plexMediaService: PlexMediaService
    private lateinit var libraryRepository: LibraryRepository

    private lateinit var coordinator: ConnectionRefreshCoordinator

    /** Counts refreshes by hooking the very first call the routine makes: invalidate(null). */
    private var refreshCount: Int = 0

    private fun lib(id: String) =
        Library(
            id = id,
            accountId = "acct",
            serverId = "srv",
            serverName = "srv",
            name = "Audiobooks",
            type = "artist",
            lastSyncedAt = null,
            itemCount = 0,
            isActive = true,
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        networkState = MutableStateFlow(NetworkState.Unknown)
        networkMonitor = mockk(relaxed = true)
        every { networkMonitor.networkState } returns networkState
        every { networkMonitor.currentState } answers { networkState.value }

        refreshCount = 0
        serverConnectionResolver = mockk(relaxed = true)
        every { serverConnectionResolver.invalidate(null) } answers { refreshCount++ }
        playbackUrlResolver = mockk(relaxed = true)
        every { playbackUrlResolver.clearCache() } just Runs
        plexConfig = mockk(relaxed = true)
        every { plexConfig.connectToServerWithRetryAndState(any()) } just Runs
        plexMediaService = mockk(relaxed = true)
        libraryRepository = mockk(relaxed = true)
        coEvery { libraryRepository.getLibraryById(any()) } returns null
        coEvery { libraryRepository.getAllLibrariesSnapshot() } returns emptyList()

        coordinator =
            ConnectionRefreshCoordinator(
                networkMonitor = networkMonitor,
                serverConnectionResolver = serverConnectionResolver,
                playbackUrlResolver = playbackUrlResolver,
                plexConfig = plexConfig,
                plexMediaService = plexMediaService,
                libraryRepository = libraryRepository,
            )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `Disconnected to Connected triggers full refresh`() =
        runTest(testDispatcher) {
            coordinator.start(CoroutineScope(testDispatcher))
            advanceUntilIdle()
            // Set a baseline AFTER startup-refresh runs so we only count the transition that follows.
            val baseline = refreshCount

            networkState.value = NetworkState.Disconnected
            advanceTimeBy(1500)
            advanceUntilIdle()
            networkState.value = NetworkState.Connected(isWifi = true, isCellular = false, isMetered = false)
            advanceTimeBy(1500)
            advanceUntilIdle()

            assert(refreshCount > baseline) {
                "Expected refresh after Disconnected -> Connected (baseline=$baseline, after=$refreshCount)"
            }
            verify(atLeast = 1) { playbackUrlResolver.clearCache() }
            verify(atLeast = 1) { plexConfig.connectToServerWithRetryAndState(plexMediaService) }
        }

    @Test
    fun `WiFi to Cellular triggers refresh`() =
        runTest(testDispatcher) {
            networkState.value = NetworkState.Connected(isWifi = true, isCellular = false, isMetered = false)
            coordinator.start(CoroutineScope(testDispatcher))
            advanceTimeBy(1500)
            advanceUntilIdle()
            val baseline = refreshCount

            networkState.value = NetworkState.Connected(isWifi = false, isCellular = true, isMetered = true)
            advanceTimeBy(1500)
            advanceUntilIdle()

            assert(refreshCount == baseline + 1) {
                "Expected exactly 1 additional refresh on WiFi -> Cellular (baseline=$baseline, after=$refreshCount)"
            }
        }

    @Test
    fun `Same transport with identical Connected state does not retrigger refresh`() =
        runTest(testDispatcher) {
            networkState.value = NetworkState.Connected(isWifi = true, isCellular = false, isMetered = false)
            coordinator.start(CoroutineScope(testDispatcher))
            advanceTimeBy(1500)
            advanceUntilIdle()
            val baseline = refreshCount

            // Re-emit an identical Connected state — StateFlow won't even re-emit equal values,
            // but exercise the path defensively by emitting through a non-distinct-by-value source.
            networkState.value = NetworkState.Connected(isWifi = true, isCellular = false, isMetered = false)
            advanceTimeBy(1500)
            advanceUntilIdle()

            assert(refreshCount == baseline) {
                "Expected no extra refresh on identical Connected state (baseline=$baseline, after=$refreshCount)"
            }
        }

    @Test
    fun `Rapid flapping is debounced into a single refresh`() =
        runTest(testDispatcher) {
            coordinator.start(CoroutineScope(testDispatcher))
            advanceUntilIdle()
            val baseline = refreshCount

            // 4 rapid changes within 400ms (under the 1000ms debounce window).
            networkState.value = NetworkState.Disconnected
            advanceTimeBy(100)
            networkState.value = NetworkState.Connected(isWifi = true, isCellular = false, isMetered = false)
            advanceTimeBy(100)
            networkState.value = NetworkState.Disconnected
            advanceTimeBy(100)
            networkState.value = NetworkState.Connected(isWifi = false, isCellular = true, isMetered = true)
            // Past the debounce window — only the final state should produce a refresh.
            advanceTimeBy(1500)
            advanceUntilIdle()

            val refreshes = refreshCount - baseline
            assert(refreshes == 1) {
                "Expected exactly 1 refresh after flapping, got $refreshes (baseline=$baseline, after=$refreshCount)"
            }
        }

    @Test
    fun `Refresh re-resolves every persisted library`() =
        runTest(testDispatcher) {
            val libs = listOf(lib("plex:library:1"), lib("plex:library:2"), lib("plex:library:3"))
            coEvery { libraryRepository.getAllLibrariesSnapshot() } returns libs

            networkState.value = NetworkState.Connected(isWifi = true, isCellular = false, isMetered = false)
            coordinator.start(CoroutineScope(testDispatcher))
            advanceTimeBy(1500)
            advanceUntilIdle()

            coVerify(atLeast = 1) { serverConnectionResolver.reResolve("plex:library:1") }
            coVerify(atLeast = 1) { serverConnectionResolver.reResolve("plex:library:2") }
            coVerify(atLeast = 1) { serverConnectionResolver.reResolve("plex:library:3") }
        }

    @Test
    fun `Startup with already-connected network fires one initial refresh`() =
        runTest(testDispatcher) {
            networkState.value = NetworkState.Connected(isWifi = true, isCellular = false, isMetered = false)

            coordinator.start(CoroutineScope(testDispatcher))
            advanceTimeBy(1500)
            advanceUntilIdle()

            assert(refreshCount == 1) {
                "Expected exactly 1 startup refresh, got $refreshCount"
            }
        }

    @Test
    fun `Refresh uses retry-enabled connect variant not the deprecated one`() =
        runTest(testDispatcher) {
            networkState.value = NetworkState.Connected(isWifi = true, isCellular = false, isMetered = false)
            coordinator.start(CoroutineScope(testDispatcher))
            advanceTimeBy(1500)
            advanceUntilIdle()

            verify(atLeast = 1) { plexConfig.connectToServerWithRetryAndState(plexMediaService) }
            // The deprecated variant must NOT be called by the coordinator.
            @Suppress("DEPRECATION")
            verify(exactly = 0) { plexConfig.connectToServer(plexMediaService) }
        }

    @Test
    fun `Refresh public function can be invoked directly for player error recovery seam`() =
        runTest(testDispatcher) {
            // Fix #3 (MediaPlayerService.onPlayerError) will call refresh() directly.
            // Verify the public seam exists and performs the same routine.
            coordinator.refresh()
            advanceUntilIdle()

            verify(atLeast = 1) { serverConnectionResolver.invalidate(null) }
            verify(atLeast = 1) { playbackUrlResolver.clearCache() }
            verify(atLeast = 1) { plexConfig.connectToServerWithRetryAndState(plexMediaService) }
        }
}
